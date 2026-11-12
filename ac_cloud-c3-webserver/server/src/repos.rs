//! #1490 the Repos sub-page: many entries, each (source, repo, start folder or entry file) with its OWN port, started and stopped one
//! by one, so several sites serve at once.
//!
//! Persisted declaratively: one JSON file (build.json::repos.store) holding the entries, nothing in code. Each running site is a
//! fleet-webserver on its own listener: one folder, GET and HEAD only, the shared traversal guard on every request, plus the guards
//! here on what an entry may name (no `..`, no absolute path, no NUL or backslash, symlinks may not leave the source or the repo).
//! The same rules as libs:local-serve (Kotlin, for Cloud-Drive's Editor); this app is Rust-only, so it cannot host that lib.
//! A site binds loopback, or the mesh address when its entry says `mesh`; never a public address.
//! `local` sources are read from disk; `terminal` sources are a terminal's own my-webserver (its $HOME), reached through its
//! loopback port by a GET proxy that refuses `..` before it forwards anything.

use crate::meshlogs::Mesh;
use crate::serving;
use fleet_webserver::http::{self, Req, Resp};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

#[derive(Clone, Debug, PartialEq)]
pub enum Kind {
    Local { root: PathBuf },
    Terminal { port: u16 },
}

#[derive(Clone, Debug, PartialEq)]
pub struct Source {
    pub id: String,
    pub label: String,
    pub kind: Kind,
}

#[derive(Clone, Debug)]
pub struct Cfg {
    pub store: PathBuf,
    pub port_first: u16,
    pub port_last: u16,
    pub max_entries: usize,
    pub scan_depth: usize,
    pub exposures: Vec<String>,
    pub sources: Vec<Source>,
    pub mesh: Option<Mesh>,
}

impl Cfg {
    /// `endpoint_port` maps a serving endpoint id to its loopback port (the terminals' my-webserver).
    pub fn from_build(b: &Value, mesh: Option<Mesh>, endpoint_port: &dyn Fn(&str) -> Option<u16>) -> Result<Cfg, String> {
        let r = &b["repos"];
        let port = |k: &str| r[k].as_u64().filter(|p| (1024..=65535).contains(p)).map(|p| p as u16).ok_or(format!("repos.{} must be an integer in 1024..65535", k));
        let mut sources = vec![];
        for s in r["sources"].as_array().ok_or("repos.sources must be an array")? {
            let id = s["id"].as_str().filter(|x| !x.is_empty()).ok_or("repos.sources[].id missing")?.to_string();
            let kind = match s["kind"].as_str() {
                Some("local") => Kind::Local { root: PathBuf::from(s["root"].as_str().filter(|x| x.starts_with('/')).ok_or(format!("repos.sources[{}].root must be absolute", id))?) },
                Some("terminal") => {
                    let ep = s["endpoint"].as_str().ok_or(format!("repos.sources[{}].endpoint missing", id))?;
                    Kind::Terminal { port: endpoint_port(ep).ok_or(format!("repos.sources[{}]: no serving endpoint {}", id, ep))? }
                }
                other => return Err(format!("repos.sources[{}].kind {:?} is not local|terminal", id, other)),
            };
            if sources.iter().any(|x: &Source| x.id == id) {
                return Err(format!("repos.sources: {} is declared twice", id));
            }
            sources.push(Source { label: s["label"].as_str().unwrap_or(&id).to_string(), id, kind });
        }
        let cfg = Cfg {
            store: PathBuf::from(r["store"].as_str().filter(|x| x.starts_with('/')).ok_or("repos.store must be an absolute path")?),
            port_first: port("port_first")?,
            port_last: port("port_last")?,
            max_entries: r["max_entries"].as_u64().unwrap_or(24).clamp(1, 200) as usize,
            scan_depth: r["scan_depth"].as_u64().unwrap_or(3).clamp(1, 6) as usize,
            exposures: r["exposures"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default(),
            sources,
            mesh,
        };
        if cfg.port_first > cfg.port_last {
            return Err("repos.port_first is above port_last".to_string());
        }
        if cfg.exposures.iter().any(|e| e != "loopback" && e != "mesh") {
            return Err("repos.exposures may only name loopback and mesh: a repo site is never public".to_string());
        }
        Ok(cfg)
    }
    pub fn source(&self, id: &str) -> Option<&Source> {
        self.sources.iter().find(|s| s.id == id)
    }
}

pub fn cfg() -> Result<&'static Cfg, String> {
    static C: std::sync::OnceLock<Result<Cfg, String>> = std::sync::OnceLock::new();
    C.get_or_init(|| {
        let b = crate::apis::build();
        Cfg::from_build(b, Mesh::from_build(b).ok(), &|id| serving::by_id(id).map(|e| e.port))
    })
    .as_ref()
    .map_err(|e| e.clone())
}

// ── entries and the JSON store ─────────────────────────────────────────────────────────────────
#[derive(Clone, Debug, PartialEq)]
pub struct Entry {
    pub id: String,
    pub label: String,
    pub source: String,
    pub repo: String,
    pub start: String,
    pub exposure: String,
    pub port: u16,
}

impl Entry {
    pub fn to_json(&self) -> Value {
        json!({"id": self.id, "label": self.label, "source": self.source, "repo": self.repo, "start": self.start, "exposure": self.exposure, "port": self.port})
    }
    pub fn from_json(v: &Value) -> Option<Entry> {
        let t = |k: &str| v[k].as_str().map(str::to_string);
        Some(Entry { id: t("id")?, label: t("label")?, source: t("source")?, repo: t("repo")?, start: t("start").unwrap_or_default(), exposure: t("exposure").unwrap_or_else(|| "loopback".to_string()), port: v["port"].as_u64().filter(|p| *p <= 65535)? as u16 })
    }
}

pub fn load(path: &Path) -> Result<Vec<Entry>, String> {
    let text = match std::fs::read_to_string(path) {
        Ok(t) => t,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(vec![]),
        Err(e) => return Err(format!("cannot read {}: {}", path.display(), e)),
    };
    let v: Value = serde_json::from_str(&text).map_err(|e| format!("{} is not valid JSON: {}", path.display(), e))?;
    Ok(v["entries"].as_array().map(|a| a.iter().filter_map(Entry::from_json).collect()).unwrap_or_default())
}

/// Write the store atomically (a temp file beside it, then rename), so a crash never leaves half a file.
pub fn save(path: &Path, entries: &[Entry]) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {}", dir.display(), e))?;
    }
    let tmp = path.with_extension("json.tmp");
    let body = serde_json::to_string_pretty(&json!({"version": 1, "entries": entries.iter().map(Entry::to_json).collect::<Vec<_>>()})).map_err(|e| e.to_string())?;
    std::fs::write(&tmp, body).map_err(|e| format!("cannot write {}: {}", tmp.display(), e))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("cannot replace {}: {}", path.display(), e))
}

// ── the path-traversal guard ───────────────────────────────────────────────────────────────────
/// A relative path an entry may name: `a/b/c`. Refuses absolute paths, `..`, NUL, backslash, a drive or scheme colon and control
/// characters; `.` and empty segments are dropped. `allow_empty` is for the start folder (the repo root itself).
pub fn safe_rel(s: &str, allow_empty: bool) -> Result<String, String> {
    if s.contains('\0') || s.contains('\\') || s.contains(':') || s.chars().any(|c| c.is_control()) {
        return Err(format!("{:?} has a character a path here may not have (NUL, backslash, colon, control)", s));
    }
    if s.starts_with('/') {
        return Err(format!("{:?} is absolute: name it relative to the source", s));
    }
    let mut out = vec![];
    for seg in s.split('/') {
        match seg {
            "" | "." => {}
            ".." => return Err(format!("{:?} climbs out with `..`", s)),
            other => out.push(other),
        }
    }
    if out.is_empty() && !allow_empty {
        return Err("a repo is required".to_string());
    }
    Ok(out.join("/"))
}

fn looks_like_file(seg: &str) -> bool {
    seg.rfind('.').map(|i| i > 0 && i + 1 < seg.len()).unwrap_or(false)
}

#[derive(Debug, PartialEq)]
pub enum Target {
    Dir { root: PathBuf, index: Option<String> },
    Proxy { port: u16, base: String, index: Option<String> },
}

/// Resolve an entry to what a site serves. Local: the path (a folder, a git repository or one file) must exist under the source root, the start must stay inside
/// the repo after symlinks are resolved. Terminal: the (guarded) path prefix on the terminal's own server.
pub fn resolve(cfg: &Cfg, e: &Entry) -> Result<Target, String> {
    let src = cfg.source(&e.source).ok_or_else(|| format!("no source {}", e.source))?;
    let repo = safe_rel(&e.repo, false)?;
    let start = safe_rel(&e.start, true)?;
    match &src.kind {
        Kind::Local { root } => {
            let base = std::fs::canonicalize(root).map_err(|_| format!("{} is not readable (storage access not granted yet?)", root.display()))?;
            let repo_dir = std::fs::canonicalize(base.join(&repo)).map_err(|_| format!("{} not found in {}", repo, src.label))?;
            if !repo_dir.starts_with(&base) {
                return Err(format!("{} resolves outside {}", repo, src.label));
            }
            // A place to serve is any folder or single file under the source (a git repository is just the common case).
            if repo_dir.is_file() {
                if !start.is_empty() {
                    return Err(format!("{} is a file: it has no start folder", repo));
                }
                let name = repo_dir.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                return Ok(Target::Dir { root: repo_dir.parent().map(Path::to_path_buf).unwrap_or(base), index: Some(name) });
            }
            if !repo_dir.is_dir() {
                return Err(format!("{} is neither a file nor a folder", repo));
            }
            let target = std::fs::canonicalize(repo_dir.join(&start)).map_err(|_| format!("start {} not found in {}", if start.is_empty() { "." } else { start.as_str() }, repo))?;
            if !target.starts_with(&repo_dir) {
                return Err(format!("start {} resolves outside the repo", start));
            }
            if target.is_file() {
                let name = target.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
                Ok(Target::Dir { root: target.parent().map(Path::to_path_buf).unwrap_or(repo_dir), index: Some(name) })
            } else if target.is_dir() {
                Ok(Target::Dir { root: target, index: None })
            } else {
                Err(format!("start {} is neither a file nor a folder", start))
            }
        }
        Kind::Terminal { port } => {
            let mut segs: Vec<&str> = repo.split('/').chain(start.split('/')).filter(|x| !x.is_empty()).collect();
            let is_file = !start.is_empty() && segs.last().map(|l| looks_like_file(l)).unwrap_or(false);
            let index = if is_file { segs.pop().map(str::to_string) } else { None };
            Ok(Target::Proxy { port: *port, base: format!("/{}", segs.join("/")), index })
        }
    }
}

// ── serving ────────────────────────────────────────────────────────────────────────────────────
pub fn pct_encode(s: &str) -> String {
    let mut o = String::new();
    for b in s.bytes() {
        if b.is_ascii_alphanumeric() || b"-._~/".contains(&b) {
            o.push(b as char);
        } else {
            o.push_str(&format!("%{:02X}", b));
        }
    }
    o
}

/// One GET against a loopback server: (status, content-type, body). Bounded in time and size.
pub fn http_get(port: u16, path_and_query: &str, timeout: Duration) -> Result<(u16, String, Vec<u8>), String> {
    let mut s = TcpStream::connect_timeout(&SocketAddr::from(([127, 0, 0, 1], port)), timeout).map_err(|e| format!("127.0.0.1:{} does not answer ({})", port, e))?;
    let _ = s.set_read_timeout(Some(timeout));
    let _ = s.set_write_timeout(Some(timeout));
    write!(s, "GET {} HTTP/1.1\r\nhost: 127.0.0.1:{}\r\nconnection: close\r\n\r\n", path_and_query, port).map_err(|e| e.to_string())?;
    let mut raw = vec![];
    let _ = s.take(64 * 1024 * 1024).read_to_end(&mut raw);
    let cut = raw.windows(4).position(|w| w == b"\r\n\r\n").ok_or("the upstream sent no HTTP head")?;
    let head = String::from_utf8_lossy(&raw[..cut]).to_string();
    let status = head.lines().next().and_then(|l| l.split_whitespace().nth(1)).and_then(|c| c.parse::<u16>().ok()).ok_or("the upstream sent no status")?;
    let ctype = head.lines().skip(1).find_map(|l| l.split_once(':').filter(|(k, _)| k.eq_ignore_ascii_case("content-type")).map(|(_, v)| v.trim().to_string())).unwrap_or_default();
    Ok((status, ctype, raw[cut + 4..].to_vec()))
}

fn static_ctype(upstream: &str, path: &str) -> &'static str {
    let u = upstream.to_ascii_lowercase();
    if u.starts_with("text/html") {
        "text/html; charset=utf-8"
    } else if u.starts_with("application/json") {
        "application/json"
    } else if u.starts_with("text/plain") {
        "text/plain; charset=utf-8"
    } else {
        fleet_webserver::files::mime_for(Path::new(path))
    }
}

/// The GET proxy of a terminal source. `..` is refused before anything is forwarded; the upstream path is the entry's base plus the
/// request path, so a site can never read above its own folder on the terminal.
pub fn proxy_handler(port: u16, base: String, index: Option<String>) -> Arc<http::Handler> {
    Arc::new(move |req: &Req| {
        let mut path = req.path.clone();
        if path.is_empty() || path == "/" {
            path = format!("/{}", index.clone().unwrap_or_default());
        }
        if path.contains('\0') || path.contains('\\') || path.split('/').any(|s| s == "..") {
            return Resp::text(400, "Bad path");
        }
        let query: Vec<String> = req.query.iter().map(|(k, v)| format!("{}={}", pct_encode(k), pct_encode(v))).collect();
        let upstream = format!("{}{}{}{}", base.trim_end_matches('/'), pct_encode(&path), if query.is_empty() { "" } else { "?" }, query.join("&"));
        match http_get(port, &upstream, Duration::from_secs(8)) {
            Ok((status, ctype, body)) => Resp::bytes(status, static_ctype(&ctype, &path), body),
            Err(e) => Resp::text(502, &format!("the terminal's server is not answering: {}", e)),
        }
    })
}

/// The directory handler: the shared fleet web server over one folder; an entry file answers `/`.
pub fn dir_handler(root: PathBuf, index: Option<String>, extra_hosts: Vec<String>) -> Arc<http::Handler> {
    let inner = fleet_webserver::handler(&fleet_webserver::Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts });
    Arc::new(move |req: &Req| match &index {
        Some(ix) if req.path == "/" || req.path.is_empty() => {
            inner(&Req { method: req.method.clone(), path: format!("/{}", ix), query: req.query.clone(), headers: req.headers.clone(), body: vec![] })
        }
        _ => inner(req),
    })
}

struct Site {
    stop: Arc<AtomicBool>,
    port: u16,
}

/// The running sites, by entry id.
#[derive(Default)]
pub struct Host {
    sites: Mutex<HashMap<String, Site>>,
}

impl Host {
    pub fn running(&self, id: &str) -> bool {
        self.sites.lock().map(|s| s.contains_key(id)).unwrap_or(false)
    }

    pub fn start(&self, cfg: &Cfg, e: &Entry) -> Result<u16, String> {
        if self.running(&e.id) {
            return Ok(e.port);
        }
        let target = resolve(cfg, e)?;
        let (addr, peer) = match e.exposure.as_str() {
            "loopback" => (Ipv4Addr::LOCALHOST, Arc::new(|ip: IpAddr| ip.is_loopback()) as Arc<http::PeerCheck>),
            "mesh" => {
                let m = cfg.mesh.clone().ok_or("this entry is exposed on the mesh but no mesh address is declared")?;
                m.check_bind()?;
                let bind = m.bind;
                (bind, Arc::new(move |ip: IpAddr| m.is_mesh(ip)) as Arc<http::PeerCheck>)
            }
            other => return Err(format!("exposure {:?} is not loopback|mesh: a repo site is never public", other)),
        };
        if !cfg.exposures.iter().any(|x| *x == e.exposure) {
            return Err(format!("exposure {} is not enabled in build.json::repos.exposures", e.exposure));
        }
        let listener = TcpListener::bind((addr, e.port)).map_err(|er| format!("cannot bind {}:{} ({})", addr, e.port, er))?;
        let handler = match target {
            Target::Dir { root, index } => dir_handler(root, index, vec![addr.to_string()]),
            Target::Proxy { port, base, index } => proxy_handler(port, base, index),
        };
        let stop = Arc::new(AtomicBool::new(false));
        http::listen_with(listener, handler, peer, Some(stop.clone()));
        self.sites.lock().map_err(|_| "poisoned")?.insert(e.id.clone(), Site { stop, port: e.port });
        Ok(e.port)
    }

    pub fn count(&self) -> usize {
        self.sites.lock().map(|s| s.len()).unwrap_or(0)
    }

    pub fn ports(&self) -> Vec<u16> {
        let mut p: Vec<u16> = self.sites.lock().map(|s| s.values().map(|x| x.port).collect()).unwrap_or_default();
        p.sort();
        p
    }

    /// #1502 Server OFF: stop every site (they stay enabled in the power store, so ON starts them again).
    pub fn stop_all(&self) {
        let ids: Vec<String> = self.sites.lock().map(|s| s.keys().cloned().collect()).unwrap_or_default();
        for id in ids {
            self.stop(&id);
        }
    }

    /// Stop whichever site holds `port` (an orphan: running here but no longer in the store).
    pub fn stop_port(&self, port: u16) -> bool {
        let id = self.sites.lock().ok().and_then(|s| s.iter().find(|(_, v)| v.port == port).map(|(k, _)| k.clone()));
        id.map(|i| self.stop(&i)).unwrap_or(false)
    }

    pub fn stop(&self, id: &str) -> bool {
        match self.sites.lock().ok().and_then(|mut s| s.remove(id)) {
            Some(site) => {
                site.stop.store(true, Ordering::SeqCst);
                true
            }
            None => false,
        }
    }
}

pub fn host() -> &'static Host {
    static H: std::sync::OnceLock<Host> = std::sync::OnceLock::new();
    H.get_or_init(Host::default)
}

// ── editing the store ──────────────────────────────────────────────────────────────────────────
fn slug(label: &str) -> String {
    let s: String = label.to_ascii_lowercase().chars().map(|c| if c.is_ascii_alphanumeric() { c } else { '-' }).collect();
    s.trim_matches('-').chars().take(24).collect()
}

/// Validate and add an entry: a free port of the range, every field guarded, the target resolved once so a bad entry fails now.
pub fn add(cfg: &Cfg, entries: &mut Vec<Entry>, label: &str, source: &str, repo: &str, start: &str, exposure: &str) -> Result<Entry, String> {
    let label = label.trim();
    if label.is_empty() || label.chars().count() > 60 || label.chars().any(|c| c.is_control()) {
        return Err("a label of 1 to 60 characters is required".to_string());
    }
    if entries.len() >= cfg.max_entries {
        return Err(format!("at most {} entries", cfg.max_entries));
    }
    let port = (cfg.port_first..=cfg.port_last).find(|p| !entries.iter().any(|e| e.port == *p)).ok_or("no free port left in the repo range")?;
    let e = Entry { id: format!("{}-{}", slug(label), port), label: label.to_string(), source: source.to_string(), repo: safe_rel(repo, false)?, start: safe_rel(start, true)?, exposure: if exposure.is_empty() { "loopback".to_string() } else { exposure.to_string() }, port };
    if !cfg.exposures.iter().any(|x| *x == e.exposure) {
        return Err(format!("exposure {} is not allowed (loopback or mesh)", e.exposure));
    }
    if entries.iter().any(|x| x.source == e.source && x.repo == e.repo && x.start == e.start && x.exposure == e.exposure) {
        return Err("that source, repo and start is already an entry".to_string());
    }
    resolve(cfg, &e)?;
    entries.push(e.clone());
    Ok(e)
}

/// Which directories of a tree hold a `.git`: breadth-first over `lister(rel) -> [(name, is_dir)]` (dot entries included), not descending
/// into a repo or a hidden folder, bounded in depth and result count.
pub fn scan(lister: &dyn Fn(&str) -> Option<Vec<(String, bool)>>, depth: usize, cap: usize) -> Vec<String> {
    let mut found = vec![];
    let mut level: Vec<String> = vec![String::new()];
    for _ in 0..=depth {
        let mut next = vec![];
        for rel in &level {
            let Some(items) = lister(rel) else { continue };
            if !rel.is_empty() && items.iter().any(|(n, _)| n == ".git") {
                found.push(rel.clone());
                if found.len() >= cap {
                    return found;
                }
                continue;
            }
            for (n, is_dir) in items {
                if is_dir && !n.starts_with('.') && n != "node_modules" && n != "Android" {
                    next.push(if rel.is_empty() { n } else { format!("{}/{}", rel, n) });
                }
            }
        }
        level = next;
    }
    found.sort();
    found
}

pub fn discover(cfg: &Cfg, source: &str) -> Result<Vec<String>, String> {
    let src = cfg.source(source).ok_or_else(|| format!("no source {}", source))?;
    match &src.kind {
        Kind::Local { root } => Ok(scan(
            &|rel| {
                let rd = std::fs::read_dir(root.join(rel)).ok()?;
                Some(rd.flatten().map(|e| (e.file_name().to_string_lossy().to_string(), e.path().is_dir())).collect())
            },
            cfg.scan_depth,
            200,
        )),
        Kind::Terminal { port } => {
            let port = *port;
            Ok(scan(
                &|rel| {
                    let (st, _, body) = http_get(port, &format!("/__api__/ls?dot=1&path={}", pct_encode(&format!("/{}", rel))), Duration::from_secs(4)).ok()?;
                    if st != 200 {
                        return None;
                    }
                    let v: Value = serde_json::from_slice(&body).ok()?;
                    Some(v.as_array()?.iter().filter_map(|x| Some((x["name"].as_str()?.to_string(), x["isDir"].as_bool().unwrap_or(false)))).collect())
                },
                cfg.scan_depth.min(2),
                200,
            ))
        }
    }
}

/// #1677 one folder of a terminal's own shared server (its /__api__/ls, dot entries included): (status, [(name, is_dir)]). The Pages
/// tab's roots (roots.rs) list Termux / Nix folders through it, as discover() does: a CLIENT call, not a route this crate answers.
pub fn terminal_ls(port: u16, full: &str, timeout: Duration) -> Result<(u16, Vec<(String, bool)>), String> {
    let (st, _, body) = http_get(port, &format!("/__api__/ls?dot=1&path={}", pct_encode(full)), timeout)?;
    if st != 200 {
        return Ok((st, vec![]));
    }
    let v: Value = serde_json::from_slice(&body).map_err(|e| e.to_string())?;
    Ok((st, v.as_array().map(|a| a.iter().filter_map(|x| Some((x["name"].as_str()?.to_string(), x["isDir"].as_bool().unwrap_or(false)))).collect()).unwrap_or_default()))
}

/// The URL a running entry is reachable at.
pub fn url(cfg: &Cfg, e: &Entry) -> String {
    let host = if e.exposure == "mesh" { cfg.mesh.as_ref().map(|m| m.bind.to_string()).unwrap_or_else(|| "127.0.0.1".to_string()) } else { "127.0.0.1".to_string() };
    format!("http://{}:{}/", host, e.port)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("c3ws-repos-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(&d).unwrap();
        fs::canonicalize(&d).unwrap()
    }

    fn cfg_for(root: &Path, store: &Path) -> Cfg {
        Cfg {
            store: store.to_path_buf(),
            port_first: 0,
            port_last: 0,
            max_entries: 3,
            scan_depth: 3,
            exposures: vec!["loopback".into()],
            sources: vec![Source { id: "emu".into(), label: "Emu".into(), kind: Kind::Local { root: root.to_path_buf() } }, Source { id: "term".into(), label: "Term".into(), kind: Kind::Terminal { port: 1 } }],
            mesh: None,
        }
    }

    fn site(root: &Path, repo: &str, files: &[(&str, &str)]) {
        let r = root.join(repo);
        fs::create_dir_all(r.join(".git")).unwrap();
        for (n, c) in files {
            let p = r.join(n);
            fs::create_dir_all(p.parent().unwrap()).unwrap();
            fs::write(p, c).unwrap();
        }
    }

    fn get(port: u16, path: &str) -> (u16, String) {
        let (st, _, body) = http_get(port, path, Duration::from_secs(3)).unwrap();
        (st, String::from_utf8_lossy(&body).to_string())
    }

    #[test]
    fn safe_rel_refuses_every_escape() {
        for bad in ["../x", "a/../../x", "/etc", "a\\b", "a\0b", "c:evil", "a/..", "..", "a\nb"] {
            assert!(safe_rel(bad, true).is_err(), "{:?} must be refused", bad);
        }
        assert_eq!(safe_rel("a//./b/", false).unwrap(), "a/b");
        assert_eq!(safe_rel("", true).unwrap(), "");
        assert!(safe_rel("", false).is_err(), "a repo is required");
        assert!(safe_rel("..a/..b", false).is_ok(), "a name that merely starts with dots is fine");
    }

    #[test]
    fn the_store_round_trips_and_survives_a_missing_file() {
        let d = tmp("store");
        let p = d.join("sub/repos.json");
        assert_eq!(load(&p).unwrap(), vec![]);
        let e = Entry { id: "a-1".into(), label: "A".into(), source: "emu".into(), repo: "r".into(), start: "docs".into(), exposure: "loopback".into(), port: 8100 };
        save(&p, &[e.clone()]).unwrap();
        assert_eq!(load(&p).unwrap(), vec![e]);
        fs::write(&p, "{broken").unwrap();
        assert!(load(&p).is_err(), "a corrupt store is an error, never silently emptied");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn add_allocates_distinct_ports_and_validates() {
        let d = tmp("add");
        site(&d, "one", &[("index.html", "x")]);
        site(&d, "two", &[("index.html", "y")]);
        let mut c = cfg_for(&d, &d.join("s.json"));
        c.port_first = 9100;
        c.port_last = 9101;
        let mut entries = vec![];
        let a = add(&c, &mut entries, "Site One", "emu", "one", "", "").unwrap();
        let b = add(&c, &mut entries, "Site Two", "emu", "two", "", "loopback").unwrap();
        assert_ne!(a.port, b.port, "each entry has its own port");
        assert!(add(&c, &mut entries, "Three", "emu", "one", "", "").is_err(), "the range is exhausted");
        c.port_last = 9102;
        assert!(add(&c, &mut entries, "Dup", "emu", "one", "", "").is_err(), "the same source, repo and start twice");
        assert!(add(&c, &mut entries, "Esc", "emu", "../one", "", "").is_err(), "a repo that climbs out");
        assert!(add(&c, &mut entries, "Esc2", "emu", "one", "../two", "").is_err(), "a start that climbs out");
        assert!(add(&c, &mut entries, "NoGit", "emu", "nope", "", "").is_err(), "a missing repo");
        assert!(add(&c, &mut entries, "Pub", "emu", "two", "docs", "public").is_err(), "a public exposure");
        assert!(add(&c, &mut entries, "Mesh", "emu", "two", "", "mesh").is_err(), "mesh is not enabled in this config");
        assert!(add(&c, &mut entries, "", "emu", "two", "", "").is_err(), "no label");
        assert!(add(&c, &mut entries, "Src", "nosuch", "two", "", "").is_err(), "unknown source");
        assert_eq!(entries.len(), 2);
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn a_symlink_out_of_the_repo_or_the_source_is_refused() {
        let d = tmp("link");
        let outside = tmp("outside");
        fs::write(outside.join("secret.txt"), "SECRET").unwrap();
        site(&d, "repo", &[("ok.html", "ok")]);
        #[cfg(unix)]
        {
            std::os::unix::fs::symlink(&outside, d.join("repo/escape")).unwrap();
            std::os::unix::fs::symlink(&outside, d.join("linkrepo")).unwrap();
            let c = cfg_for(&d, &d.join("s.json"));
            let e = |repo: &str, start: &str| Entry { id: "x".into(), label: "x".into(), source: "emu".into(), repo: repo.into(), start: start.into(), exposure: "loopback".into(), port: 1 };
            assert!(resolve(&c, &e("repo", "escape")).is_err(), "a start symlink out of the repo");
            assert!(resolve(&c, &e("linkrepo", "")).is_err(), "a repo symlink out of the source");
            assert!(resolve(&c, &e("repo", "")).is_ok());
        }
        let _ = fs::remove_dir_all(&d);
        let _ = fs::remove_dir_all(&outside);
    }

    #[test]
    fn several_sites_serve_at_once_each_on_its_own_port() {
        let d = tmp("multi");
        site(&d, "alpha", &[("index.html", "ALPHA-HOME"), ("docs/guide.html", "ALPHA-GUIDE")]);
        site(&d, "beta", &[("app/main.html", "BETA-MAIN"), ("app/style.css", "body{}"), ("secret.txt", "BETA-SECRET")]);
        let mut c = cfg_for(&d, &d.join("s.json"));
        // Free ports for the three sites: bind to 0, note the port, drop the listener.
        let free = || TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();
        let (p1, p2, p3) = (free(), free(), free());
        c.port_first = p1.min(p2).min(p3);
        c.port_last = 65535;
        let mk = |id: &str, repo: &str, start: &str, port: u16| Entry { id: id.into(), label: id.into(), source: "emu".into(), repo: repo.into(), start: start.into(), exposure: "loopback".into(), port };
        let (a, b, f) = (mk("a", "alpha", "", p1), mk("b", "beta", "app", p2), mk("f", "beta", "app/main.html", p3));
        let host = Host::default();
        for e in [&a, &b, &f] {
            host.start(&c, e).unwrap_or_else(|er| panic!("start {}: {}", e.id, er));
        }
        assert!(host.running("a") && host.running("b") && host.running("f"));
        assert!(get(p1, "/index.html").1.contains("ALPHA-HOME"));
        assert!(get(p1, "/docs/guide.html").1.contains("ALPHA-GUIDE"));
        assert!(get(p2, "/main.html").1.contains("BETA-MAIN"), "beta serves from its start folder");
        assert_eq!(get(p2, "/secret.txt").0, 404, "a file above the start folder is not served");
        assert_eq!(get(p2, "/../secret.txt").0, 404, "traversal is refused");
        assert_eq!(get(p2, "/%2e%2e/secret.txt").0, 404, "an encoded traversal is refused");
        assert!(get(p3, "/").1.contains("BETA-MAIN"), "an entry file answers /");
        assert_eq!(get(p1, "/app/main.html").0, 404, "one site does not serve another's files");
        // stop one: only that port closes.
        assert!(host.stop("a"));
        std::thread::sleep(Duration::from_millis(200));
        assert!(!host.running("a"));
        assert!(http_get(p1, "/index.html", Duration::from_millis(500)).is_err(), "a stopped site no longer answers");
        assert_eq!(get(p2, "/main.html").0, 200, "the others keep serving");
        assert!(!host.stop("a"), "stopping twice is a no-op");
        // and it restarts on the same port.
        host.start(&c, &a).unwrap();
        assert!(get(p1, "/index.html").1.contains("ALPHA-HOME"));
        for id in ["a", "b", "f"] {
            host.stop(id);
        }
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn a_mesh_entry_without_a_mesh_address_and_a_public_one_never_bind() {
        let d = tmp("expo");
        site(&d, "r", &[("index.html", "x")]);
        let mut c = cfg_for(&d, &d.join("s.json"));
        c.exposures = vec!["loopback".into(), "mesh".into()];
        let e = Entry { id: "e".into(), label: "e".into(), source: "emu".into(), repo: "r".into(), start: "".into(), exposure: "mesh".into(), port: 1 };
        assert!(Host::default().start(&c, &e).is_err(), "no mesh address declared");
        let p = Entry { exposure: "public".into(), ..e.clone() };
        assert!(Host::default().start(&c, &p).unwrap_err().contains("never public"));
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn the_terminal_proxy_forwards_inside_its_base_and_refuses_traversal() {
        // A fake terminal server: echoes the path it was asked for.
        let l = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let tport = l.local_addr().unwrap().port();
        let seen = Arc::new(Mutex::new(vec![]));
        let s2 = seen.clone();
        http::listen(l, Arc::new(move |req: &Req| {
            s2.lock().unwrap().push(req.path.clone());
            Resp::text(200, &format!("UP:{}", req.path))
        }));
        let l2 = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let pport = l2.local_addr().unwrap().port();
        http::listen(l2, proxy_handler(tport, "/proj/site".into(), Some("home.html".into())));
        assert_eq!(get(pport, "/a/b.txt").1, "UP:/proj/site/a/b.txt");
        assert_eq!(get(pport, "/").1, "UP:/proj/site/home.html", "the entry file answers /");
        assert_eq!(get(pport, "/../../etc/passwd").0, 400);
        assert_eq!(get(pport, "/%2e%2e/x").0, 400);
        assert!(seen.lock().unwrap().iter().all(|p| p.starts_with("/proj/site/")), "nothing outside the base was ever requested: {:?}", seen.lock().unwrap());
    }

    #[test]
    fn terminal_targets_split_an_entry_file_from_the_folder() {
        let d = tmp("term");
        let c = cfg_for(&d, &d.join("s.json"));
        let e = |start: &str| Entry { id: "t".into(), label: "t".into(), source: "term".into(), repo: "work/site".into(), start: start.into(), exposure: "loopback".into(), port: 1 };
        assert_eq!(resolve(&c, &e("")).unwrap(), Target::Proxy { port: 1, base: "/work/site".into(), index: None });
        assert_eq!(resolve(&c, &e("public")).unwrap(), Target::Proxy { port: 1, base: "/work/site/public".into(), index: None });
        assert_eq!(resolve(&c, &e("public/index.html")).unwrap(), Target::Proxy { port: 1, base: "/work/site/public".into(), index: Some("index.html".into()) });
        assert!(resolve(&c, &e("../up")).is_err());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn scan_finds_repos_without_descending_into_them() {
        let tree: HashMap<&str, Vec<(&str, bool)>> = HashMap::from([
            ("", vec![("a", true), (".hidden", true), ("Android", true), ("f.txt", false)]),
            ("a", vec![("r1", true), ("deep", true)]),
            ("a/r1", vec![(".git", true), ("sub", true)]),
            ("a/deep", vec![("x", true)]),
            ("a/deep/x", vec![(".git", true), ("y", true)]),
            ("a/deep/x/y", vec![(".git", true)]),
        ]);
        let lister = |rel: &str| tree.get(rel).map(|v| v.iter().map(|(n, d)| (n.to_string(), *d)).collect());
        assert_eq!(scan(&lister, 3, 50), vec!["a/deep/x".to_string(), "a/r1".to_string()], "found at depth 3, a repo is not entered, y (depth 4) is out of range");
        assert_eq!(scan(&lister, 2, 50), vec!["a/r1".to_string()], "bounded in depth");
        assert_eq!(scan(&lister, 3, 1).len(), 1, "bounded in count");
    }

    #[test]
    fn the_real_declaration_parses_and_never_allows_public() {
        let c = cfg().expect("build.json::repos");
        assert!(c.exposures.iter().all(|e| e == "loopback" || e == "mesh"));
        assert!(c.sources.iter().any(|s| matches!(s.kind, Kind::Local { .. })) && c.sources.iter().any(|s| matches!(s.kind, Kind::Terminal { .. })));
        assert!(c.port_first > 8004 && c.port_first < c.port_last);
    }
}
