//! #1677 THE PAGES TAB ROOTS: the folders the Pages tab shows, declared in build.json::roots (never a path in code), each in one
//! of two modes:
//!
//!   explorer  a two-column file explorer (tree left, the selected file rendered right): Cloud Termux, Cloud Nix, Emulated storage.
//!   pages     served like a web browser would: the root's index opens and its links navigate inside the root: git-repos-master,
//!             front-html, cloud-me-pvt-notes (as cloned on the phone; a missing clone is a "clone it" state, never an error).
//!
//! A root names a `source` of build.json::repos.sources (local = a folder this process reads; terminal = the terminal's own
//! my-webserver on loopback) and a `repo` folder relative to it. Everything a root serves goes through the SAME two serving paths
//! as a repo site (repos::dir_handler: the shared fleet-webserver over one folder; repos::proxy_handler: the path-guarded GET proxy
//! of a terminal), under `/__app__/root/<id>/<path>` on the drive listener, so the page frame and the explorer preview stay
//! same-origin and a root's relative links resolve inside it. Every path is guarded by repos::safe_rel (no `..`, no absolute path)
//! and, for local roots, by the canonical prefix of the root after symlinks. Nothing here panics on a missing root or permission:
//! every failure is a status with a reason (ready | missing | offline | no_access | error).

use crate::repos::{self, Kind};
use crate::{apis, Ctx};
use fleet_webserver::http::{Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::{json, Value};
use std::path::PathBuf;
use std::sync::OnceLock;
use std::time::Duration;

/// The prefix every root's files are served under: `/__app__/root/<id>/<path>`.
pub const RAW: &str = "/__app__/root";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Mode {
    Explorer,
    Pages,
}

impl Mode {
    pub fn id(&self) -> &'static str {
        match self {
            Mode::Explorer => "explorer",
            Mode::Pages => "pages",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct Root {
    pub id: String,
    pub label: String,
    pub mode: Mode,
    pub source: String,
    pub repo: String,
    pub clone: String,
    pub note: String,
}

#[derive(Clone, Debug)]
pub struct Decl {
    pub roots: Vec<Root>,
    pub index: Vec<String>,
}

fn id_ok(s: &str) -> bool {
    !s.is_empty() && s.len() <= 64 && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_')
}

/// Parse build.json::roots. Err names the first bad entry (the server test fails on it, the running app shows the reason).
pub fn from_build(b: &Value) -> Result<Decl, String> {
    let r = &b["roots"];
    let sources: Vec<&str> = b["repos"]["sources"].as_array().map(|a| a.iter().filter_map(|s| s["id"].as_str()).collect()).unwrap_or_default();
    let mut roots: Vec<Root> = vec![];
    for (key, mode) in [("explorer", Mode::Explorer), ("pages", Mode::Pages)] {
        for x in r[key].as_array().ok_or(format!("roots.{} must be an array", key))? {
            let id = x["id"].as_str().filter(|s| id_ok(s)).ok_or(format!("roots.{}[].id must be 1-64 of [A-Za-z0-9_-]", key))?.to_string();
            if roots.iter().any(|o| o.id == id) {
                return Err(format!("roots: {} is declared twice", id));
            }
            let source = x["source"].as_str().unwrap_or("").to_string();
            if !sources.contains(&source.as_str()) {
                return Err(format!("roots.{}[{}].source {:?} is not a repos.sources id", key, id, source));
            }
            let repo = repos::safe_rel(x["repo"].as_str().unwrap_or(""), true).map_err(|e| format!("roots.{}[{}].repo: {}", key, id, e))?;
            roots.push(Root {
                label: x["label"].as_str().unwrap_or(&id).to_string(),
                mode,
                source,
                repo,
                clone: x["clone"].as_str().unwrap_or("").to_string(),
                note: x["note"].as_str().unwrap_or("").to_string(),
                id,
            });
        }
    }
    let index: Vec<String> = r["index"].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default();
    let index = if index.is_empty() { vec!["index.html".to_string(), "index.htm".to_string()] } else { index };
    if index.iter().any(|n| n.is_empty() || n.contains('/') || n.contains("..")) {
        return Err("roots.index names must be plain file names".to_string());
    }
    Ok(Decl { roots, index })
}

pub fn decl() -> Result<&'static Decl, String> {
    static D: OnceLock<Result<Decl, String>> = OnceLock::new();
    D.get_or_init(|| from_build(apis::build())).as_ref().map_err(|e| e.clone())
}

fn join(a: &str, b: &str) -> String {
    [a, b].iter().filter(|s| !s.is_empty()).cloned().collect::<Vec<_>>().join("/")
}

/// The serving endpoint id behind a terminal source (for Start app), from build.json::repos.sources.
fn endpoint_of(source: &str) -> Option<String> {
    apis::build()["repos"]["sources"].as_array()?.iter().find(|s| s["id"].as_str() == Some(source))?["endpoint"].as_str().map(str::to_string)
}

/// The live state of a path inside a root: (status, reason). Never panics.
#[derive(Clone, Debug, PartialEq)]
pub enum Where {
    Dir,
    File,
}

fn denied(e: &std::io::Error) -> bool {
    e.kind() == std::io::ErrorKind::PermissionDenied
}

const NO_ACCESS: &str = "storage access is not granted to this app: grant it in Configs > Perms";

/// Locate `rel` under the root: Ok(Dir|File) or Err((status, reason)).
pub fn locate(cfg: &repos::Cfg, r: &Root, rel: &str) -> Result<Where, (&'static str, String)> {
    let src = cfg.source(&r.source).ok_or(("error", format!("no source {}", r.source)))?;
    let rel = repos::safe_rel(rel, true).map_err(|e| ("error", e))?;
    match &src.kind {
        Kind::Local { root } => {
            let base = match std::fs::canonicalize(root) {
                Ok(b) => b,
                Err(e) if denied(&e) => return Err(("no_access", NO_ACCESS.to_string())),
                Err(_) => return Err(("no_access", format!("{} is not readable ({})", root.display(), NO_ACCESS))),
            };
            if std::fs::read_dir(&base).is_err() {
                return Err(("no_access", NO_ACCESS.to_string()));
            }
            let top = base.join(&r.repo);
            let top = match std::fs::canonicalize(&top) {
                Ok(t) if t.starts_with(&base) => t,
                Ok(_) => return Err(("error", format!("{} resolves outside {}", r.repo, src.label))),
                Err(_) => return Err(("missing", format!("{} is not on this phone ({}/{})", r.label, root.display(), r.repo))),
            };
            let p = match std::fs::canonicalize(top.join(&rel)) {
                Ok(p) if p.starts_with(&top) => p,
                Ok(_) => return Err(("error", format!("{} resolves outside {}", rel, r.label))),
                Err(e) if denied(&e) => return Err(("no_access", NO_ACCESS.to_string())),
                Err(_) => return Err(("missing", format!("{} not found in {}", if rel.is_empty() { "." } else { rel.as_str() }, r.label))),
            };
            Ok(if p.is_dir() { Where::Dir } else { Where::File })
        }
        Kind::Terminal { port } => {
            let full = format!("/{}", join(&r.repo, &rel));
            match repos::terminal_ls(*port, &full, Duration::from_millis(1500)) {
                Ok((200, _)) => Ok(Where::Dir),
                Ok((_, _)) => {
                    // not a folder: a file answers a HEAD-like GET of its parent listing
                    let (parent, name) = match full.rsplit_once('/') {
                        Some((p, n)) => (if p.is_empty() { "/".to_string() } else { p.to_string() }, n.to_string()),
                        None => ("/".to_string(), full.clone()),
                    };
                    match list_terminal(*port, &parent) {
                        Ok(items) if items.iter().any(|(n, d)| n == &name && !d) => Ok(Where::File),
                        _ if rel.is_empty() => Err(("missing", format!("{} is not cloned in {} (~/{})", r.label, src.label, r.repo))),
                        _ => Err(("missing", format!("{} not found in {}", rel, r.label))),
                    }
                }
                Err(e) => Err(("offline", format!("{}'s own my-webserver does not answer, so its files cannot be read: open the app (Start app). {}", src.label, e))),
            }
        }
    }
}

fn list_terminal(port: u16, full: &str) -> Result<Vec<(String, bool)>, String> {
    match repos::terminal_ls(port, full, Duration::from_millis(2500))? {
        (200, items) => Ok(items),
        (st, _) => Err(format!("listing answered {}", st)),
    }
}

/// The entries of a folder inside a root, folders first then by name. Err((status, reason)).
pub fn list(cfg: &repos::Cfg, r: &Root, rel: &str) -> Result<Vec<(String, bool)>, (&'static str, String)> {
    if locate(cfg, r, rel)? != Where::Dir {
        return Err(("error", format!("{} is a file, not a folder", rel)));
    }
    let rel = repos::safe_rel(rel, true).map_err(|e| ("error", e))?;
    let src = cfg.source(&r.source).ok_or(("error", format!("no source {}", r.source)))?;
    let mut items = match &src.kind {
        Kind::Local { root } => {
            let dir = root.join(&r.repo).join(&rel);
            let rd = std::fs::read_dir(&dir).map_err(|e| if denied(&e) { ("no_access", NO_ACCESS.to_string()) } else { ("error", e.to_string()) })?;
            rd.flatten().map(|e| (e.file_name().to_string_lossy().to_string(), e.path().is_dir())).collect::<Vec<_>>()
        }
        Kind::Terminal { port } => list_terminal(*port, &format!("/{}", join(&r.repo, &rel))).map_err(|e| ("offline", e))?,
    };
    items.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| a.0.to_lowercase().cmp(&b.0.to_lowercase())));
    Ok(items)
}

fn root_json(cfg: Option<&repos::Cfg>, r: &Root) -> Value {
    let src = cfg.and_then(|c| c.source(&r.source));
    let (status, reason) = match cfg {
        Some(c) => match locate(c, r, "") {
            Ok(_) => ("ready", String::new()),
            Err((s, why)) => (s, why),
        },
        None => ("error", "the repos sources do not parse".to_string()),
    };
    let terminal = matches!(src.map(|s| &s.kind), Some(Kind::Terminal { .. }));
    let start_app = if terminal && status == "offline" {
        endpoint_of(&r.source).map(|id| json!({"post": "/__app__/api/served", "body": {"op": "start_app", "id": id}, "label": "Start app"})).unwrap_or(Value::Null)
    } else {
        Value::Null
    };
    json!({
        "id": r.id, "label": r.label, "mode": r.mode.id(), "source": r.source,
        "source_label": src.map(|s| s.label.clone()).unwrap_or_else(|| r.source.clone()),
        "kind": if terminal { "terminal" } else { "local" },
        "repo": r.repo, "status": status, "reason": reason, "clone": r.clone, "note": r.note,
        "url": format!("{}/{}/", RAW, r.id),
        "start_app": start_app,
        "perms": if status == "no_access" { json!({"post": "/__app__/api/configs-open", "body": {"page": "perms"}, "label": "Open Configs > Perms"}) } else { Value::Null }
    })
}

fn ok(v: Value) -> Resp {
    Resp::json(200, v.to_string())
}

/// GET /__app__/api/roots: every declared root with its mode and live status. Always 200: a bad declaration is a reason, not a crash.
pub fn api_roots(_c: &Ctx, _r: &Req) -> Resp {
    let d = match decl() {
        Ok(d) => d,
        Err(e) => return ok(json!({"roots": [], "index": [], "error": e})),
    };
    let cfg = repos::cfg().ok();
    ok(json!({"roots": d.roots.iter().map(|r| root_json(cfg, r)).collect::<Vec<_>>(), "index": d.index, "raw": RAW}))
}

/// GET /__app__/api/roots/ls?root=<id>&path=<rel>: one folder of a root. Always 200 with `status` (ready or the reason).
pub fn api_ls(_c: &Ctx, req: &Req) -> Resp {
    let id = req.q("root").unwrap_or("");
    let path = req.q("path").unwrap_or("");
    let fail = |s: &str, why: &str| ok(json!({"root": id, "path": path, "status": s, "reason": why, "entries": []}));
    let d = match decl() {
        Ok(d) => d,
        Err(e) => return fail("error", &e),
    };
    let r = match d.roots.iter().find(|r| r.id == id) {
        Some(r) => r,
        None if id.is_empty() => return fail("error", "name a root: ?root=<id>"),
        None => return fail("error", &format!("no root {}", id)),
    };
    let cfg = match repos::cfg() {
        Ok(c) => c,
        Err(e) => return fail("error", &e),
    };
    match list(cfg, r, path) {
        Ok(items) => ok(json!({"root": id, "path": path, "status": "ready", "reason": "",
            "entries": items.iter().map(|(n, d)| json!({"name": n, "dir": d})).collect::<Vec<_>>()})),
        Err((s, why)) => fail(s, &why),
    }
}

fn note_page(title: &str, body: &str) -> Resp {
    let esc = |s: &str| s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;");
    Resp::html(format!(
        "<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>{}</title><body style=\"font:14px system-ui;background:#0b0e14;color:#e6e9ef;padding:16px\"><h3>{}</h3><p style=\"color:#8b95a7\">{}</p></body>",
        esc(title), esc(title), esc(body)
    ))
}

/// GET /__app__/root/<id>/<path>: a root's file through the repos serving paths. A folder of a Pages root opens its index (the
/// first of build.json::roots.index it holds); a folder URL without its trailing slash is redirected so relative links resolve.
pub fn serve(_c: &Ctx, req: &Req) -> Resp {
    let d = match decl() {
        Ok(d) => d,
        Err(e) => return note_page("Roots are not declared", &e),
    };
    let cfg = match repos::cfg() {
        Ok(c) => c,
        Err(e) => return note_page("Sources do not parse", &e),
    };
    serve_in(d, cfg, req)
}

/// [serve] over any declaration and sources (the tests feed it a temporary local root).
pub fn serve_in(d: &Decl, cfg: &repos::Cfg, req: &Req) -> Resp {
    let rest = req.path.strip_prefix(RAW).unwrap_or("").trim_start_matches('/');
    if rest.is_empty() {
        return note_page("Roots", "Open a root from Pages: /__app__/root/<id>/<path>.");
    }
    let (id, sub) = rest.split_once('/').map(|(a, b)| (a, b.to_string())).unwrap_or((rest, String::new()));
    let r = match d.roots.iter().find(|r| r.id == id) {
        Some(r) => r,
        None => return note_page("No such root", id),
    };
    let rel = match repos::safe_rel(&sub, true) {
        Ok(x) => x,
        Err(e) => return Resp::text(400, &e),
    };
    let mut target = rel.clone();
    match locate(cfg, r, &rel) {
        Err((s, why)) => {
            let title = match s {
                "missing" if rel.is_empty() => format!("{} is not cloned on this phone", r.label),
                "missing" => "Not found".to_string(),
                "offline" => "The terminal is not open".to_string(),
                "no_access" => "Storage access needed".to_string(),
                _ => "Cannot open".to_string(),
            };
            let hint = if s == "missing" && rel.is_empty() && !r.clone.is_empty() { format!(" Clone it: {}", r.clone) } else { String::new() };
            return note_page(&title, &format!("{}{}", why, hint));
        }
        Ok(Where::Dir) => {
            if !req.path.ends_with('/') {
                let loc = format!("{}/", repos::pct_encode(&req.path));
                return Resp::text(301, "moved").with("location", &loc);
            }
            if r.mode == Mode::Pages {
                if let Ok(items) = list(cfg, r, &rel) {
                    if let Some(ix) = d.index.iter().find(|n| items.iter().any(|(m, dir)| m == *n && !dir)) {
                        target = join(&rel, ix);
                    }
                }
            }
        }
        Ok(Where::File) => {}
    }
    let src = match cfg.source(&r.source) {
        Some(s) => s,
        None => return note_page("No source", &r.source),
    };
    let inner = Req { method: "GET".to_string(), path: format!("/{}", target), query: req.query.clone(), headers: req.headers.clone(), body: vec![] };
    match &src.kind {
        Kind::Local { root } => {
            let top: PathBuf = root.join(&r.repo);
            (repos::dir_handler(top, None, vec![]))(&inner)
        }
        Kind::Terminal { port } => (repos::proxy_handler(*port, format!("/{}", r.repo), None))(&inner),
    }
}

/// The ADB Shell tab (#1677): the declared deep link into the SuperApp's ADB Shell page (build.json::adb_shell): the package is the
/// declared SuperApp bridge package, the extra and its value are declared, nothing is taken from the request.
pub fn adb_shell_link() -> Result<(String, String, String), String> {
    let a = &apis::build()["adb_shell"];
    let extra = a["extra"].as_str().filter(|s| !s.is_empty()).ok_or("build.json::adb_shell.extra is missing")?.to_string();
    let value = a["value"].as_str().filter(|s| !s.is_empty()).ok_or("build.json::adb_shell.value is missing")?.to_string();
    let pkg = crate::serving::get().bridge_package.clone();
    if pkg.is_empty() {
        return Err("no SuperApp package is declared (build.json::serving bridge package)".to_string());
    }
    Ok((pkg, extra, value))
}

/// GET: the deep link as declared; POST {}: open it through the Android bridge. A missing SuperApp is a clear message (502).
pub fn api_adb_shell(_c: &Ctx, req: &Req) -> Resp {
    let (pkg, extra, value) = match adb_shell_link() {
        Ok(x) => x,
        Err(e) => return Resp::json(200, json!({"ok": false, "error": e}).to_string()),
    };
    if req.method != "POST" {
        return ok(json!({"package": pkg, "extra": extra, "value": value, "label": apis::build()["adb_shell"]["label"].as_str().unwrap_or("ADB Shell")}));
    }
    match crate::served::launch_with(&pkg, &extra, &value) {
        Ok(()) => Resp::json(200, format!("{{\"ok\":true,\"package\":{}}}", jstr(&pkg))),
        Err(e) if e.contains("not installed") => Resp::json(502, json!({"error": format!("Cloud SuperApp ({}) is not installed on this phone: install it to use its ADB Shell page", pkg), "missing": true}).to_string()),
        Err(e) => Resp::json(502, json!({"error": e}).to_string()),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_real_declaration_has_both_modes_and_every_owner_root() {
        let d = from_build(apis::build()).unwrap();
        let ex: Vec<&str> = d.roots.iter().filter(|r| r.mode == Mode::Explorer).map(|r| r.id.as_str()).collect();
        let pg: Vec<&str> = d.roots.iter().filter(|r| r.mode == Mode::Pages).map(|r| r.id.as_str()).collect();
        assert_eq!(ex, ["termux", "nix", "emulated"]);
        assert_eq!(pg, ["git-repos-master", "front-html", "cloud-me-pvt-notes"]);
        assert!(d.index.iter().any(|n| n == "index.html"));
    }

    #[test]
    fn a_bad_declaration_is_refused() {
        let mut b = apis::build().clone();
        b["roots"]["pages"][0]["repo"] = json!("../etc");
        assert!(from_build(&b).is_err(), "a root may not climb out");
        let mut b = apis::build().clone();
        b["roots"]["pages"][0]["source"] = json!("nowhere");
        assert!(from_build(&b).is_err(), "an undeclared source");
        let mut b = apis::build().clone();
        let first = b["roots"]["explorer"][0].clone();
        b["roots"]["pages"].as_array_mut().unwrap().push(first);
        assert!(from_build(&b).is_err(), "a duplicate id");
    }

    fn tmp(tag: &str) -> PathBuf {
        let d = std::env::temp_dir().join(format!("c3ws-roots-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&d);
        std::fs::create_dir_all(&d).unwrap();
        d
    }

    fn local_cfg(root: &std::path::Path) -> repos::Cfg {
        repos::Cfg {
            store: root.join("store.json"),
            port_first: 18100,
            port_last: 18110,
            max_entries: 4,
            scan_depth: 2,
            exposures: vec!["loopback".to_string()],
            sources: vec![repos::Source { id: "emulated".to_string(), label: "Emulated".to_string(), kind: Kind::Local { root: root.to_path_buf() } }],
            mesh: None,
        }
    }

    fn root(id: &str, mode: Mode, repo: &str) -> Root {
        Root { id: id.to_string(), label: id.to_string(), mode, source: "emulated".to_string(), repo: repo.to_string(), clone: String::new(), note: String::new() }
    }

    #[test]
    fn a_local_root_lists_guards_and_reports_a_missing_clone() {
        let t = tmp("ls");
        std::fs::create_dir_all(t.join("site/sub")).unwrap();
        std::fs::write(t.join("site/index.html"), "<a href=sub/>x</a>").unwrap();
        std::fs::write(t.join("site/sub/b.md"), "# b").unwrap();
        let cfg = local_cfg(&t);
        let r = root("site", Mode::Pages, "site");
        let items = list(&cfg, &r, "").unwrap();
        assert_eq!(items, vec![("sub".to_string(), true), ("index.html".to_string(), false)], "folders first");
        assert_eq!(locate(&cfg, &r, "sub/b.md").unwrap(), Where::File);
        assert_eq!(locate(&cfg, &r, "../x").unwrap_err().0, "error", "no climbing out");
        let gone = root("gone", Mode::Pages, "not-cloned");
        assert_eq!(locate(&cfg, &gone, "").unwrap_err().0, "missing", "a missing clone is a state");
        assert!(list(&cfg, &gone, "").is_err());
        let _ = std::fs::remove_dir_all(&t);
    }

    fn req(p: &str) -> Req {
        fleet_webserver::http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: 127.0.0.1\r\n\r\n", p)).unwrap()
    }

    #[test]
    fn a_pages_root_serves_its_index_and_navigates_inside_it() {
        let t = tmp("serve");
        std::fs::create_dir_all(t.join("site/sub")).unwrap();
        std::fs::write(t.join("site/index.html"), "<p>HOME-PAGE</p><a href=\"sub/b.html\">b</a>").unwrap();
        std::fs::write(t.join("site/sub/b.html"), "<p>PAGE-B</p><a href=\"../index.html\">home</a>").unwrap();
        std::fs::write(t.join("site/sub/c.md"), "# Heading C").unwrap();
        let cfg = local_cfg(&t);
        let d = Decl { roots: vec![root("site", Mode::Pages, "site"), root("gone", Mode::Pages, "not-cloned"), root("x", Mode::Explorer, "site")], index: vec!["index.html".to_string()] };
        let body = |p: &str| { let r = serve_in(&d, &cfg, &req(p)); (r.status, String::from_utf8_lossy(&r.body).to_string()) };
        let (st, b) = body("/__app__/root/site/");
        assert_eq!(st, 200);
        assert!(b.contains("HOME-PAGE"), "the root's index opens: {}", &b[..b.len().min(300)]);
        assert_eq!(serve_in(&d, &cfg, &req("/__app__/root/site")).status, 301, "a folder without its slash is redirected so relative links resolve");
        assert_eq!(serve_in(&d, &cfg, &req("/__app__/root/site/sub")).status, 301);
        let (st, b) = body("/__app__/root/site/sub/b.html");
        assert!(st == 200 && b.contains("PAGE-B"), "a link inside the root navigates inside it");
        let (st, b) = body("/__app__/root/site/sub/c.md");
        assert!(st == 200 && b.contains("Heading C"), "Markdown is served (rendered by the shared server)");
        let (st, b) = body("/__app__/root/gone/");
        assert!(st == 200 && b.contains("not cloned"), "a missing clone is a clone-it page, not an error: {}", b);
        let (st, b) = body("/__app__/root/x/index.html");
        assert!(st == 200 && b.contains("HOME-PAGE"), "an explorer root previews a file");
        let _ = std::fs::remove_dir_all(&t);
    }

    #[test]
    fn the_apis_always_answer_200() {
        let c = Ctx { root: std::env::temp_dir(), port: 8002, started: std::time::Instant::now(), binary: "t".to_string() };
        let get = |p: &str| fleet_webserver::http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: 127.0.0.1\r\n\r\n", p)).unwrap();
        let v: Value = serde_json::from_slice(&api_roots(&c, &get("/__app__/api/roots")).body).unwrap();
        assert_eq!(v["roots"].as_array().unwrap().len(), 6);
        for r in v["roots"].as_array().unwrap() {
            assert!(["ready", "missing", "offline", "no_access", "error"].contains(&r["status"].as_str().unwrap()), "{}", r);
        }
        for q in ["", "?root=nope", "?root=termux&path=../../etc", "?root=emulated&path=%2Fetc"] {
            let resp = api_ls(&c, &get(&format!("/__app__/api/roots/ls{}", q)));
            assert_eq!(resp.status, 200, "{}", q);
        }
        assert_eq!(serve(&c, &get("/__app__/root/nope/x")).status, 200, "an unknown root is a note page");
        let climb = format!("GET {} HTTP/1.1\r\nhost: 127.0.0.1\r\n\r\n", "/__app__/root/emulated/../../etc/passwd");
        if let Some(req) = fleet_webserver::http::parse_request(&climb) {
            assert!(!String::from_utf8_lossy(&serve(&c, &req).body).contains("root:x:"), "a climbing path never serves");
        }
    }

    #[test]
    fn the_adb_shell_link_is_the_declared_superapp_page() {
        let (pkg, extra, value) = adb_shell_link().unwrap();
        assert_eq!(pkg, crate::serving::get().bridge_package);
        assert_eq!(extra, "shortcut_action");
        assert_eq!(value, "page:config/adb-shell");
    }
}
