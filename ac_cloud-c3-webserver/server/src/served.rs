//! The Pages tab: ONE list of the pages that are actually served, then "Add a place to serve" and the undeclared-pages checker.
//!
//! A row is a declared endpoint (build.json::serving.endpoints: Termux, Nix, Local = the emulated drive, Cloud = diagnostics), a
//! user-added place (an entry of the Repos store, repos.rs: the add form IS the Repos add API, generalised to any folder or file) or
//! an ADOPTED port (an undeclared page the owner chose to declare). Each row carries its live status, metadata, source and links, and
//! the one Start/Stop that applies to it:
//!   * Local / Cloud: this app owns the listener. Stop is a persisted pause: the listener stays bound (the app shell lives on the
//!     Local port) but the page answers 503 until Start. [is_paused] is read by the two handlers in lib.rs.
//!   * Termux / Nix: served by each terminal's own my-webserver in that app's process. This app cannot stop another app's server, so
//!     Stop is not offered; when the app is closed nothing answers, and "Start app" launches it (the Android bridge, [set_launcher]).
//!   * Places: repos.rs Host start / stop, through the same POST /__app__/api/repos as before.
//!
//! UNDECLARED means: a TCP port that accepts a connection on loopback and is not in the declared set (serving endpoints, every `port`
//! under build.json::runtime, the SSH doors, the Repos store, the adopted ports). The phone's sandbox may deny /proc/net/tcp
//! (Android 10+ hides other apps' sockets), so the scan is /proc/net/tcp when readable PLUS a connect probe of the fleet's own port
//! range and a few well-known dev ports. It cannot see a listener bound to a non-loopback address only, or on a port outside those.

use crate::{apis, door, repos, serving};
use fleet_webserver::http::{Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::path::PathBuf;
use std::sync::{Mutex, OnceLock};
use std::time::Duration;

fn ok(v: Value) -> Resp {
    Resp::json(200, v.to_string())
}

fn err(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

// ── the two small stores, beside the Repos store ──────────────────────────────────────────────
fn store_dir() -> Option<PathBuf> {
    repos::cfg().ok().and_then(|c| c.store.parent().map(|p| p.to_path_buf()))
}

fn read_json(name: &str) -> Value {
    store_dir().and_then(|d| std::fs::read_to_string(d.join(name)).ok()).and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null)
}

fn write_json(name: &str, v: &Value) -> Result<(), String> {
    let dir = store_dir().ok_or("no store directory is declared")?;
    std::fs::create_dir_all(&dir).map_err(|e| format!("cannot create {}: {}", dir.display(), e))?;
    let tmp = dir.join(format!("{}.tmp", name));
    std::fs::write(&tmp, v.to_string()).map_err(|e| format!("cannot write {}: {}", tmp.display(), e))?;
    std::fs::rename(&tmp, dir.join(name)).map_err(|e| format!("cannot replace {}: {}", name, e))
}

static LOCK: Mutex<()> = Mutex::new(());
const PAUSED: &str = "served-paused.json";
const ADOPTED: &str = "served-adopted.json";

/// Endpoint ids the owner stopped serving (Local, Cloud).
pub fn paused() -> BTreeSet<String> {
    read_json(PAUSED)["paused"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

pub fn is_paused(id: &str) -> bool {
    paused().contains(id)
}

fn set_paused(id: &str, on: bool) -> Result<(), String> {
    let _g = LOCK.lock();
    let mut p = paused();
    if on {
        p.insert(id.to_string());
    } else {
        p.remove(id);
    }
    write_json(PAUSED, &json!({"paused": p}))
}

/// (port, name) pairs the owner adopted from the undeclared list.
pub fn adopted() -> Vec<(u16, String)> {
    read_json(ADOPTED)["adopted"].as_array().map(|a| a.iter().filter_map(|x| Some((x["port"].as_u64()? as u16, x["name"].as_str()?.to_string()))).collect()).unwrap_or_default()
}

fn save_adopted(v: &[(u16, String)]) -> Result<(), String> {
    write_json(ADOPTED, &json!({"adopted": v.iter().map(|(p, n)| json!({"port": p, "name": n})).collect::<Vec<_>>()}))
}

// ── the Android hook: launch a terminal app ───────────────────────────────────────────────────
type Launcher = dyn Fn(&str) -> Result<(), String> + Send + Sync;
static LAUNCHER: OnceLock<Box<Launcher>> = OnceLock::new();

/// Installed by the Tauri crate: open the launch activity of a package.
pub fn set_launcher(f: Box<Launcher>) -> bool {
    LAUNCHER.set(f).is_ok()
}

type ConfigsOpener = dyn Fn(&str) -> Result<(), String> + Send + Sync;
static CONFIGS_OPENER: OnceLock<Box<ConfigsOpener>> = OnceLock::new();

/// #1630 Installed by the Tauri crate: open one tab of the shared native Configs screen (libs:ui-nav-bars FleetConfigsActivity).
pub fn set_configs_opener(f: Box<ConfigsOpener>) -> bool {
    CONFIGS_OPENER.set(f).is_ok()
}

/// #1630 open the native Configs tab `page` (agents, perms, logcat, xray, about) through the installed Android bridge.
pub fn open_configs(page: &str) -> Result<(), String> {
    let f = CONFIGS_OPENER.get().ok_or_else(|| "no Android bridge installed yet".to_string())?;
    f(page)
}

type ExtraLauncher = dyn Fn(&str, &str, &str) -> Result<(), String> + Send + Sync;
static EXTRA_LAUNCHER: OnceLock<Box<ExtraLauncher>> = OnceLock::new();

/// #1677 Installed by the Tauri crate: open a package's MAIN/LAUNCHER activity carrying one String extra (the SuperApp reads its
/// `shortcut_action` extra on that intent and opens the named page: the ADB Shell tab's deep link).
pub fn set_extra_launcher(f: Box<ExtraLauncher>) -> bool {
    EXTRA_LAUNCHER.set(f).is_ok()
}

/// #1677 launch `package` with the String extra `key` = `value` through the installed Android bridge.
pub fn launch_with(package: &str, key: &str, value: &str) -> Result<(), String> {
    let f = EXTRA_LAUNCHER.get().ok_or_else(|| "no Android bridge installed yet".to_string())?;
    f(package, key, value)
}

/// #1614 launch a package's MAIN/LAUNCHER activity through the installed Android bridge (the same hook Start App uses).
/// Used by the X-Ray Device page's "Start Superapp" button; the caller decides which package is allowed to launch.
pub fn launch(package: &str) -> Result<(), String> {
    let f = LAUNCHER.get().ok_or_else(|| "no Android bridge installed yet".to_string())?;
    f(package)
}

// ── live facts ────────────────────────────────────────────────────────────────────────────────
fn answers(port: u16) -> bool {
    serving::is_up(port) && repos::http_get(port, "/", Duration::from_millis(400)).is_ok()
}

fn link(label: &str, href: &str) -> Value {
    json!({"label": label, "href": href})
}

fn door_package(id: &str) -> Option<String> {
    door::cfg().doors.iter().find(|d| d.id == id).map(|d| d.package.clone()).filter(|p| !p.is_empty())
}

fn toggle(label: &str, body: Value, danger: bool, why: &str) -> Value {
    json!({"label": label, "post": "/__app__/api/served", "body": body, "danger": danger, "disabled": !why.is_empty(), "why": why})
}

fn endpoint_row(e: &serving::Endpoint, paused: &BTreeSet<String>) -> Value {
    let tcp = serving::is_up(e.port);
    let b = apis::build();
    let external = e.role == serving::Role::External;
    let on = if external { tcp && answers(e.port) } else { tcp && !paused.contains(&e.id) };
    let home = door_package(&e.id).map(|p| format!("/data/data/{}/files/home", p)).unwrap_or_else(|| e.owner.clone());
    let source = match e.role {
        serving::Role::External => home,
        serving::Role::Drive => b["runtime"]["serve_root"].as_str().unwrap_or("").to_string(),
        serving::Role::Diag => format!("in-app · {}", e.summary),
    };
    let tgl = if external {
        if on {
            toggle("Stop serving", json!({"op": "stop", "id": e.id}), true, "this page is served by the terminal's own my-webserver: stop it from that terminal")
        } else {
            toggle("Start serving", json!({"op": "start_app", "id": e.id}), false, "")
        }
    } else if on {
        toggle("Stop serving", json!({"op": "stop", "id": e.id}), true, "")
    } else {
        toggle("Start serving", json!({"op": "start", "id": e.id}), false, "")
    };
    json!({
        "id": e.id, "kind": "declared", "name": e.title, "port": e.port, "role": e.role.name(), "up": tcp, "serving": on,
        "paused": paused.contains(&e.id),
        "app": if external { json!(if tcp { "open" } else { "not open" }) } else { Value::Null },
        // #1613 the terminal pages (Termux, Nix) always carry a Start App button, whether or not the app's own
        // my-webserver is answering now: launching it brings a closed app up and brings an open one to the front.
        "start_app": if external { json!({"post": "/__app__/api/served", "body": {"op": "start_app", "id": e.id}}) } else { Value::Null },
        "auth": "loopback peers only", "exposure": "loopback", "host": e.owner,
        "source": source, "source_open": if e.role == serving::Role::Diag { String::new() } else { e.url_name() },
        "links": [link(&e.name, &e.url_name()), link(&format!("127.0.0.1:{}", e.port), &e.url_ip())],
        "toggle": tgl, "config": "configs"
    })
}

fn place_row(cfg: &repos::Cfg, host: &repos::Host, e: &repos::Entry) -> Value {
    let running = host.running(&e.id);
    let label = cfg.source(&e.source).map(|s| s.label.clone()).unwrap_or_else(|| e.source.clone());
    let url = repos::url(cfg, e);
    let path = format!("{}{}", e.repo, if e.start.is_empty() { String::new() } else { format!("/{}", e.start) });
    json!({
        "id": format!("place:{}", e.id), "kind": "place", "name": e.label, "port": e.port, "role": "place", "up": running, "serving": running, "paused": false,
        "app": Value::Null, "start_app": Value::Null,
        "auth": if e.exposure == "mesh" { "mesh peers only" } else { "loopback peers only" }, "exposure": e.exposure, "host": "this app",
        "source": format!("{} · {}", label, path), "source_open": if running { url.clone() } else { String::new() },
        "links": if running { vec![link(&url, &url)] } else { vec![] },
        "toggle": json!({"label": if running { "Stop serving" } else { "Start serving" }, "post": "/__app__/api/repos", "body": {"op": if running { "stop" } else { "start" }, "id": e.id}, "danger": running, "disabled": false, "why": ""}),
        "remove": {"label": "Remove", "post": "/__app__/api/repos", "body": {"op": "remove", "id": e.id}}, "config": "form"
    })
}

fn adopted_row(port: u16, name: &str) -> Value {
    let up = serving::is_up(port);
    json!({
        "id": format!("adopted:{}", port), "kind": "adopted", "name": name, "port": port, "role": "adopted", "up": up, "serving": up, "paused": false,
        "app": Value::Null, "start_app": Value::Null,
        "auth": "unknown (not ours)", "exposure": "loopback", "host": "another process",
        "source": format!("127.0.0.1:{}", port), "source_open": format!("http://127.0.0.1:{}/", port),
        "links": [link(&format!("127.0.0.1:{}", port), &format!("http://127.0.0.1:{}/", port))],
        "toggle": toggle(if up { "Stop serving" } else { "Start serving" }, json!({"op": "stop", "port": port}), up, "served by another process: this app can neither start nor stop it"),
        "remove": {"label": "Forget", "post": "/__app__/api/served", "body": {"op": "forget", "port": port}}, "config": "form"
    })
}

// ── the undeclared checker ────────────────────────────────────────────────────────────────────
fn collect_ports(v: &Value, out: &mut BTreeSet<u16>) {
    match v {
        Value::Object(m) => {
            for (k, x) in m {
                if k == "port" {
                    if let Some(p) = x.as_u64() {
                        out.insert(p as u16);
                    }
                } else {
                    collect_ports(x, out);
                }
            }
        }
        Value::Array(a) => a.iter().for_each(|x| collect_ports(x, out)),
        _ => {}
    }
}

/// Every port this phone's configuration declares: serving endpoints, build.json::runtime (drive, diag, mesh, sshd), the SSH doors
/// and their discover ports, the Repos entries and the adopted ports.
pub fn declared_ports() -> BTreeSet<u16> {
    let mut d = BTreeSet::new();
    let sv = serving::get();
    d.extend(sv.endpoints.iter().map(|e| e.port));
    d.insert(sv.dns.port);
    collect_ports(&apis::build()["runtime"], &mut d);
    let doors = door::cfg();
    d.extend(doors.doors.iter().filter_map(|x| x.port));
    d.extend(doors.discover.iter().copied());
    if let Ok(c) = repos::cfg() {
        if let Ok(es) = repos::load(&c.store) {
            d.extend(es.iter().map(|e| e.port));
        }
    }
    d.extend(adopted().iter().map(|(p, _)| *p));
    d
}

/// The LISTEN ports of /proc/net/tcp{,6} text (state 0A), user range only.
pub fn listen_ports(proc_net: &str) -> BTreeSet<u16> {
    let mut out = BTreeSet::new();
    for line in proc_net.lines().skip(1) {
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 4 || f[3] != "0A" {
            continue;
        }
        if let Some(p) = f[1].rsplit_once(':').and_then(|(_, p)| u16::from_str_radix(p, 16).ok()) {
            if (1024..32768).contains(&p) {
                out.insert(p);
            }
        }
    }
    out
}

/// The ports the connect probe tries when /proc says nothing: the fleet's range and a few well-known dev ports.
fn probe_ports() -> Vec<u16> {
    let p = &apis::build()["served"]["probe"];
    let (first, last) = (p["first"].as_u64().unwrap_or(0) as u16, p["last"].as_u64().unwrap_or(0) as u16);
    let mut v: Vec<u16> = (first..=last.max(first)).filter(|p| *p >= 1024).collect();
    v.extend(p["extra"].as_array().map(|a| a.iter().filter_map(|x| x.as_u64().map(|x| x as u16)).collect::<Vec<_>>()).unwrap_or_default());
    v
}

/// The declared set is subtracted from what answers; pure so the tests drive it.
pub fn undeclared_of(found: &BTreeSet<u16>, declared: &BTreeSet<u16>) -> Vec<u16> {
    found.difference(declared).copied().collect()
}

pub fn scan() -> Value {
    let declared = declared_ports();
    let mut found = BTreeSet::new();
    let mut method = vec![];
    let mut proc_ok = false;
    for f in ["/proc/net/tcp", "/proc/net/tcp6"] {
        if let Ok(t) = std::fs::read_to_string(f) {
            let l = listen_ports(&t);
            proc_ok |= t.lines().count() > 1;
            found.extend(l);
        }
    }
    if proc_ok {
        method.push("/proc/net/tcp");
    }
    method.push("loopback connect probe");
    for p in probe_ports() {
        if !declared.contains(&p) && serving::is_up(p) {
            found.insert(p);
        }
    }
    let host = repos::host();
    let ours = host.ports();
    let items: Vec<Value> = undeclared_of(&found, &declared)
        .into_iter()
        .filter(|p| serving::is_up(*p))
        .map(|p| {
            let label = match repos::http_get(p, "/", Duration::from_millis(300)) {
                Ok((st, ct, _)) => format!("HTTP {} {}", st, ct).trim().to_string(),
                Err(_) => "listening, not HTTP".to_string(),
            };
            let mine = ours.contains(&p);
            json!({"port": p, "addr": format!("127.0.0.1:{}", p), "label": label, "ours": mine, "href": format!("http://127.0.0.1:{}/", p)})
        })
        .collect();
    json!({
        "items": items, "method": method.join(" + "), "declared": declared.len(),
        "scope": "TCP ports that accept a connection on 127.0.0.1 and are not declared (serving endpoints, runtime ports, SSH doors, places, adopted). Android may hide other apps' sockets from /proc, so the probe covers the range declared in build.json::served.probe; a listener bound only to a non-loopback address, or elsewhere, is not seen. Stop works only on a listener this app itself holds; another app's port is declared or left alone."
    })
}

// ── the page ──────────────────────────────────────────────────────────────────────────────────
pub fn form(cfg: &repos::Cfg) -> Value {
    json!({
        "title": "\u{ff0b} Add a place to serve",
        "post": "/__app__/api/repos",
        "discover": "/__app__/api/repos/discover?source=",
        "fields": [
            {"id": "label", "label": "Name", "kind": "text"},
            {"id": "source", "label": "Source", "kind": "select", "options": cfg.sources.iter().map(|s| json!({"value": s.id, "label": s.label})).collect::<Vec<_>>()},
            {"id": "repo", "label": "Path / folder / file", "kind": "repo"},
            {"id": "exposure", "label": "Exposure", "kind": "select", "options": cfg.exposures.iter().map(|x| json!({"value": x, "label": x})).collect::<Vec<_>>()},
            {"id": "auth", "label": "Auth", "kind": "select", "options": [{"value": "peer", "label": "peer check (loopback or mesh only)"}]}
        ],
        "submit": {"label": "Add & serve", "body": {"op": "add", "start": "", "serve": true}}
    })
}

pub fn page() -> Result<Value, String> {
    let sv = serving::get();
    let pz = paused();
    let mut rows: Vec<Value> = sv.endpoints.iter().map(|e| endpoint_row(e, &pz)).collect();
    let cfg = repos::cfg()?;
    let entries = repos::load(&cfg.store).unwrap_or_default();
    rows.extend(entries.iter().map(|e| place_row(cfg, repos::host(), e)));
    rows.extend(adopted().iter().map(|(p, n)| adopted_row(*p, n)));
    Ok(json!({"rows": rows, "form": form(cfg), "undeclared": scan(), "dns": {"host": sv.dns.host, "port": sv.dns.port}}))
}

pub fn api_served(_c: &crate::Ctx, req: &Req) -> Resp {
    if req.method != "POST" {
        return match page() {
            Ok(v) => ok(v),
            Err(e) => err(500, &e),
        };
    }
    let body: Value = match serde_json::from_slice(&req.body) {
        Ok(v) => v,
        Err(_) => return err(400, "the body must be JSON"),
    };
    match op(&body) {
        Ok(v) => ok(v),
        Err((s, m)) => err(s, &m),
    }
}

/// start | stop (Local, Cloud) | start_app (Termux, Nix) | rescan | declare {port, name?} | forget {port} | stop_undeclared {port}.
pub fn op(body: &Value) -> Result<Value, (u16, String)> {
    let id = body["id"].as_str().unwrap_or("");
    let port = body["port"].as_u64().filter(|p| (1024..=65535).contains(p)).map(|p| p as u16);
    let mine = |id: &str| serving::by_id(id).ok_or((404u16, format!("no served page {}", id)));
    match body["op"].as_str().unwrap_or("") {
        "start" | "stop" if port.is_some() && id.is_empty() => Err((409, "that page is served by another process: this app can neither start nor stop it".to_string())),
        o @ ("start" | "stop") => {
            let e = mine(id)?;
            if e.role == serving::Role::External {
                return Err((409, format!("{} is served by its own terminal app: use Start app, and stop it from that terminal", e.title)));
            }
            set_paused(&e.id, o == "stop").map_err(|m| (500u16, m))?;
            Ok(json!({"ok": true, "id": e.id, "serving": o == "start"}))
        }
        "start_app" => {
            let e = mine(id)?;
            if e.role != serving::Role::External {
                return Err((400, format!("{} is part of this app: it has no app to start", e.title)));
            }
            let pkg = door_package(&e.id).ok_or((404u16, format!("no package is declared for {}", e.title)))?;
            let launch = LAUNCHER.get().ok_or((503u16, "no Android bridge installed yet".to_string()))?;
            launch(&pkg).map_err(|m| (502u16, m))?;
            Ok(json!({"ok": true, "package": pkg}))
        }
        "rescan" => Ok(scan()),
        "declare" => {
            let p = port.ok_or((400u16, "a port in 1024..65535 is required".to_string()))?;
            if declared_ports().contains(&p) {
                return Err((409, format!("port {} is already declared", p)));
            }
            if !serving::is_up(p) {
                return Err((409, format!("nothing answers on 127.0.0.1:{} now", p)));
            }
            let name = body["name"].as_str().map(str::trim).filter(|n| !n.is_empty() && n.chars().count() <= 60 && !n.chars().any(|c| c.is_control())).map(str::to_string).unwrap_or_else(|| format!("port {}", p));
            let _g = LOCK.lock();
            let mut a = adopted();
            a.push((p, name));
            save_adopted(&a).map_err(|m| (500u16, m))?;
            Ok(json!({"ok": true, "port": p}))
        }
        "forget" => {
            let p = port.ok_or((400u16, "a port is required".to_string()))?;
            let _g = LOCK.lock();
            let a: Vec<(u16, String)> = adopted().into_iter().filter(|(x, _)| *x != p).collect();
            save_adopted(&a).map_err(|m| (500u16, m))?;
            Ok(json!({"ok": true}))
        }
        "stop_undeclared" => {
            let p = port.ok_or((400u16, "a port is required".to_string()))?;
            if declared_ports().contains(&p) {
                return Err((409, format!("port {} is declared: stop it from its own row", p)));
            }
            if repos::host().stop_port(p) {
                Ok(json!({"ok": true}))
            } else {
                Err((409, format!("127.0.0.1:{} is held by another app: Android does not let this app stop it. Declare it, or close that app", p)))
            }
        }
        other => Err((400, format!("op {:?} is not start|stop|start_app|rescan|declare|forget|stop_undeclared", other))),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn proc_net_listen_ports_are_the_user_range_listeners() {
        let t = "  sl  local_address rem_address   st\n   0: 0100007F:1F42 00000000:0000 0A 0 0\n   1: 0100007F:C350 00000000:0000 0A 0 0\n   2: 0100007F:1F43 0100007F:9999 01 0 0\n   3: 00000000:0016 00000000:0000 0A 0 0\n";
        assert_eq!(listen_ports(t).into_iter().collect::<Vec<_>>(), vec![8002], "0A only, 1024..32768 only");
    }

    #[test]
    fn undeclared_is_found_minus_declared() {
        let found: BTreeSet<u16> = [8000, 8002, 9090].into();
        let declared: BTreeSet<u16> = [8000, 8002].into();
        assert_eq!(undeclared_of(&found, &declared), vec![9090]);
    }

    #[test]
    fn every_declared_endpoint_has_a_row_and_the_runtime_ports_are_declared() {
        let pz = BTreeSet::new();
        for e in &serving::get().endpoints {
            let r = endpoint_row(e, &pz);
            for k in ["name", "port", "role", "serving", "auth", "exposure", "host", "source", "links", "toggle"] {
                assert!(r.get(k).is_some(), "{} lacks {}", e.id, k);
            }
            assert_eq!(r["app"].is_string(), e.role == serving::Role::External, "app status only for the terminals");
            // #1613 the terminal pages always offer Start App; the in-app pages (Local, Cloud/X-Ray Device) never do.
            assert_eq!(r["start_app"].is_object(), e.role == serving::Role::External, "Start App only for the terminals, and always");
        }
        let d = declared_ports();
        assert!(d.contains(&serving::get().drive_port) && d.contains(&serving::get().diag_port) && d.contains(&serving::get().dns.port));
    }

    #[test]
    fn external_pages_cannot_be_stopped_from_here() {
        assert_eq!(op(&json!({"op": "stop", "id": "termux"})).unwrap_err().0, 409);
        assert_eq!(op(&json!({"op": "wat"})).unwrap_err().0, 400);
        let drive = serving::get().endpoints.iter().find(|e| e.role == serving::Role::Drive).unwrap();
        assert_eq!(op(&json!({"op": "start_app", "id": drive.id})).unwrap_err().0, 400, "Local is part of this app: no app to start");
    }
}
