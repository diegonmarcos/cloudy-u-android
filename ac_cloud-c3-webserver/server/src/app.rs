//! THE APP SHELL: the pages around the file view. Five bottom tabs (Pages, API, Home, Files, Configs), the
//! link chooser (Open in browser / Open embedded) and their JSON, all under the one prefix `/__app__/`.
//!
//! The file view itself is NOT here. The Files tab embeds `/`, which is the shared fleet-webserver (the Linux
//! my-webserver UI, byte for byte); this module never answers a path outside [`PREFIX`], so the drive's own
//! routes stay exactly the shared server's. Every path this module answers is a row of [`ROUTES`] and dispatch
//! is a walk over that table, so "served" and "declared" are the same list; the Pages and API tabs render
//! `/__app__/api/routes`, which is this table serialised, and the bottom nav is its `tab` rows.

use crate::{about, crashes, engines, serving, sub, Ctx};
use fleet_webserver::http::{self, Req, Resp};
use fleet_webserver::render::jstr;

pub const PREFIX: &str = "/__app__";

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Kind {
    Page,
    Api,
}

pub struct Route {
    pub kind: Kind,
    pub path: &'static str,
    /// Bottom-nav caption when this page is one of the tabs.
    pub tab: Option<&'static str>,
    /// The row also answers POST (guarded: JSON body, x-c3-write header, allowed origin).
    pub post: bool,
    pub summary: &'static str,
    pub handler: fn(&Ctx, &Req) -> Resp,
}

pub const ROUTES: &[Route] = &[
    Route { kind: Kind::Page, path: "/__app__", tab: None, post: false, summary: "The app shell, opened on the Home tab", handler: page_shell },
    Route { kind: Kind::Page, path: "/__app__/tab/pages", tab: Some("Pages"), post: false, summary: "Every served page of the phone by port and by private DNS name, and every page this app serves, read from its own route table", handler: page_shell },
    Route { kind: Kind::Page, path: "/__app__/tab/api", tab: Some("API"), post: false, summary: "Every API route of this app shell, read from its own route table", handler: page_shell },
    Route { kind: Kind::Page, path: "/__app__/tab/home", tab: Some("Home"), post: false, summary: "About this phone: device, kernel, CPU, memory, storage, battery, network", handler: page_shell },
    Route { kind: Kind::Page, path: "/__app__/tab/files", tab: Some("Files"), post: false, summary: "The file view: the shared web server's Linux UI on the served root, unchanged", handler: page_shell },
    Route { kind: Kind::Page, path: "/__app__/tab/configs", tab: Some("Configs"), post: false, summary: "About (ports, DNS names, versions), Engines (every renderer and feature) and Links (what a tapped link does)", handler: page_shell },
    Route { kind: Kind::Api, path: "/__app__/api/routes", tab: None, post: false, summary: "This route table as JSON: kind, path, tab, summary", handler: api_routes },
    Route { kind: Kind::Api, path: "/__app__/api/config", tab: None, post: false, summary: "Effective configuration: bind, port, root, methods, route counts", handler: api_config },
    Route { kind: Kind::Api, path: "/__app__/api/serving", tab: None, post: false, summary: "The served ports and their private DNS names, whether each answers now, the DNS server and the versions", handler: api_serving },
    Route { kind: Kind::Api, path: "/__app__/api/engines", tab: None, post: false, summary: "Every render and feature engine of the web server, read-only", handler: api_engines },
    Route { kind: Kind::Api, path: "/__app__/api/about", tab: None, post: false, summary: "About this phone: macro groups of sections of rows, the Configs > About shape (what the Home tab renders)", handler: api_about },
    Route { kind: Kind::Api, path: "/__app__/api/links", tab: None, post: false, summary: "The link-open declaration: link types, the choices each offers, whether a choice can be remembered", handler: api_links },
    Route { kind: Kind::Page, path: "/__app__/crashes", tab: None, post: false, summary: "Every fleet app's crashes reported to this phone (libs:core's local sink), grouped by app, newest first", handler: crashes::page },
    Route { kind: Kind::Api, path: "/__app__/api/crashes", tab: None, post: true, summary: "GET the crash reports as JSON (?app=<package>); POST one {app, time_ms, trace, phase, version, device}: libs:core's local sink, works offline", handler: crashes_api },
    Route { kind: Kind::Api, path: "/__app__/api/subpages", tab: None, post: false, summary: "The sub-pages of the API and Pages tabs (build.json::subpages): tab, id, label and the route that answers each", handler: sub::subpages },
    Route { kind: Kind::Api, path: "/__app__/api/sub/api-mcp", tab: None, post: false, summary: "API > MCP: the MCP tool catalogue of all our APIs, generated from the declarations: tool, API route, server, auth", handler: sub::api_mcp },
    Route { kind: Kind::Api, path: "/__app__/api/sub/api-public", tab: None, post: false, summary: "API > Public Endpoints: the public URLs of the fleet services", handler: sub::api_public },
    Route { kind: Kind::Api, path: "/__app__/api/sub/api-mesh", tab: None, post: false, summary: "API > Mesh Endpoints: this phone's mesh listener (/logs/<app>, bearer, mesh peers only) and the fleet services on the mesh", handler: sub::api_mesh },
    Route { kind: Kind::Api, path: "/__app__/api/sub/api-apps", tab: None, post: false, summary: "API > Apps Endpoints: each app's loopback debug API and its port", handler: sub::api_apps },
    Route { kind: Kind::Api, path: "/__app__/api/sub/api-ipc", tab: None, post: false, summary: "API > Apps IPC: each app's IPC endpoints, derived from the fleet manifest", handler: sub::api_ipc },
    Route { kind: Kind::Api, path: "/__app__/api/sub/pages-drive", tab: None, post: false, summary: "Pages > Drive: the pages of Emulated storage and of Cloud-Drive's served mode", handler: sub::page_drive },
    Route { kind: Kind::Api, path: "/__app__/api/sub/pages-terminals", tab: None, post: false, summary: "Pages > Terminals: each terminal app's ~/home as a servable root", handler: sub::page_terminals },
    Route { kind: Kind::Api, path: "/__app__/api/sub/pages-repos", tab: None, post: false, summary: "Pages > Repos: the repo sites, their ports and state, and the add form", handler: sub::page_repos },
    Route { kind: Kind::Api, path: "/__app__/api/repos", tab: None, post: true, summary: "GET the repo entries; POST {op: add|remove|start|stop, ...}: the Repos store and its sites (loopback or mesh, never public)", handler: sub::api_repos },
    Route { kind: Kind::Api, path: "/__app__/api/repos/discover", tab: None, post: false, summary: "GET ?source=<id>: the git repositories a Repos source holds", handler: sub::api_discover },
    Route { kind: Kind::Api, path: "/__app__/api/power", tab: None, post: true, summary: "GET the master controls and the live state (running, ports, sites); POST {server?, keep_alive?} sets Server ON/OFF and Keep alive ON/OFF (build.json::power)", handler: api_power },
    Route { kind: Kind::Api, path: "/__app__/api/open", tab: None, post: true, summary: "POST {url} or {id,via:name|ip}: hand an http(s) link to cloud-browser", handler: api_open },
];

/// Exact match over the table (a trailing slash is noise).
pub fn find(path: &str) -> Option<&'static Route> {
    let p = path.trim_end_matches('/');
    ROUTES.iter().find(|r| r.path == p)
}

/// Refuse a table that cannot be dispatched unambiguously.
pub fn check_table() -> Result<(), String> {
    let mut seen = std::collections::HashSet::new();
    for r in ROUTES {
        if !seen.insert(r.path) {
            return Err(format!("route {} is declared twice", r.path));
        }
        if r.path != PREFIX && !r.path.starts_with("/__app__/") {
            return Err(format!("route {} is outside {}", r.path, PREFIX));
        }
        if r.tab.is_some() && (r.kind != Kind::Page || !r.path.starts_with("/__app__/tab/")) {
            return Err(format!("route {} carries a tab caption but is not a /__app__/tab/ page", r.path));
        }
        if r.path.starts_with("/__app__/tab/") && r.tab.is_none() {
            return Err(format!("tab page {} has no tab caption", r.path));
        }
        if r.post && r.kind != Kind::Api {
            return Err(format!("route {} answers POST but is not an API route", r.path));
        }
    }
    // #1490 every declared sub-page has a route and every sub-page route is declared: one list.
    let declared: Vec<String> = crate::apis::build()["subpages"].as_array().map(|a| a.iter().filter_map(|p| p["route"].as_str().map(str::to_string)).collect()).unwrap_or_default();
    for d in &declared {
        if !ROUTES.iter().any(|r| r.path == d) {
            return Err(format!("build.json::subpages names {} but no route answers it", d));
        }
    }
    for r in ROUTES.iter().filter(|r| r.path.starts_with("/__app__/api/sub/")) {
        if !declared.iter().any(|d| d == r.path) {
            return Err(format!("route {} is a sub-page but build.json::subpages does not declare it", r.path));
        }
    }
    if !ROUTES.iter().any(|r| r.tab.is_some()) {
        return Err("no route is a tab; the shell would have no bottom nav".to_string());
    }
    Ok(())
}

pub fn routes_json() -> String {
    routes_json_of(ROUTES.iter())
}

/// Serialise any sequence of rows; the tests feed it the table with a row planted and a row removed.
pub fn routes_json_of<'a, I: Iterator<Item = &'a Route>>(rows: I) -> String {
    let rows: Vec<String> = rows
        .map(|r| {
            format!(
                "{{\"kind\":{},\"path\":{},\"tab\":{},\"summary\":{}}}",
                jstr(match r.kind { Kind::Page => "page", Kind::Api => "api" }),
                jstr(r.path),
                match r.tab { Some(t) => jstr(t), None => "null".to_string() },
                jstr(r.summary)
            )
        })
        .collect();
    format!("[{}]", rows.join(","))
}

fn jerr(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

/// Answer a request under [`PREFIX`]; None for any other path (the caller hands those to the shared server).
pub fn handle(ctx: &Ctx, req: &Req, allowed_host: &dyn Fn(&str) -> bool) -> Option<Resp> {
    let p = req.path.trim_end_matches('/');
    if p != PREFIX && !p.starts_with("/__app__/") {
        return None;
    }
    if let Some(h) = req.header("host") {
        if !allowed_host(&http::host_of(h)) {
            return Some(Resp::text(403, "Forbidden host: this server answers 127.0.0.1, localhost and its four private names"));
        }
    }
    let route = match find(&req.path) {
        Some(r) => r,
        None => return Some(Resp::text(404, "Not found")),
    };
    let is_read = req.method == "GET" || req.method == "HEAD";
    if !is_read && !(req.method == "POST" && route.post) {
        return Some(Resp::text(405, "method not allowed").with("allow", if route.post { "GET, HEAD, POST" } else { "GET, HEAD" }));
    }
    if req.method == "POST" {
        // A page on another origin can fire a form POST at 127.0.0.1. A custom header forces a CORS preflight this
        // server never grants, and a JSON content type cannot come from a plain <form>.
        if req.header("x-c3-write") != Some("1") {
            return Some(Resp::text(403, "missing x-c3-write header"));
        }
        if !req.header("content-type").unwrap_or("").to_ascii_lowercase().starts_with("application/json") {
            return Some(Resp::text(403, "content-type must be application/json"));
        }
        if let Some(o) = req.header("origin") {
            if !allowed_host(&http::host_of(o)) {
                return Some(Resp::text(403, "origin not allowed"));
            }
        }
    }
    Some((route.handler)(ctx, req))
}

const SHELL: &str = include_str!("../ui/shell.html");

fn page_shell(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::html(SHELL.to_string())
}

fn api_routes(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(200, routes_json())
}

fn api_serving(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(200, serving::json(true))
}

/// #1502 the master controls: GET the state, POST {server?, keep_alive?} (either may be omitted) and get the new state.
fn api_power(_ctx: &Ctx, req: &Req) -> Resp {
    if req.method == "POST" {
        let body: serde_json::Value = match serde_json::from_slice(&req.body) {
            Ok(v) => v,
            Err(_) => return jerr(400, "the body must be JSON"),
        };
        let cur = crate::power::current().sw;
        let want = |k: &str, d: bool| match &body[k] {
            serde_json::Value::Null => Ok(d),
            serde_json::Value::Bool(b) => Ok(*b),
            _ => Err(format!("{} must be true or false", k)),
        };
        let sw = match (want("server", cur.server), want("keep_alive", cur.keep_alive)) {
            (Ok(server), Ok(keep_alive)) => crate::power::Switches { server, keep_alive },
            (Err(e), _) | (_, Err(e)) => return jerr(400, &e),
        };
        if let Err(e) = crate::power::apply(sw) {
            return jerr(500, &e);
        }
    }
    Resp::json(200, crate::power::status_json().to_string())
}

fn api_engines(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(200, engines::json())
}

fn api_config(ctx: &Ctx, _req: &Req) -> Resp {
    let pages = ROUTES.iter().filter(|r| r.kind == Kind::Page).count();
    let apis = ROUTES.iter().filter(|r| r.kind == Kind::Api).count();
    Resp::json(
        200,
        format!(
            concat!(
                "{{\"name\":{},\"version\":{},\"bind\":\"127.0.0.1\",\"port\":{},\"root\":{},\"binary\":{},",
                "\"pid\":{},\"methods\":[\"GET\",\"HEAD\"],\"routes\":{{\"pages\":{},\"api\":{}}},\"arch\":{},\"os\":{}}}"
            ),
            jstr(env!("CARGO_PKG_NAME")),
            jstr(env!("CARGO_PKG_VERSION")),
            ctx.port,
            jstr(&ctx.root.to_string_lossy()),
            jstr(&ctx.binary),
            std::process::id(),
            pages,
            apis,
            jstr(std::env::consts::ARCH),
            jstr(std::env::consts::OS)
        ),
    )
}

fn api_about(ctx: &Ctx, _req: &Req) -> Resp {
    let out: Vec<String> = about::groups(ctx)
        .iter()
        .map(|(macro_label, sections)| {
            let secs: Vec<String> = sections
                .iter()
                .map(|(title, rows)| {
                    let r: Vec<String> = rows.iter().map(|(k, v)| format!("[{},{}]", jstr(k), jstr(v))).collect();
                    format!("{{\"title\":{},\"rows\":[{}]}}", jstr(title), r.join(","))
                })
                .collect();
            format!("{{\"macro\":{},\"sections\":[{}]}}", jstr(macro_label), secs.join(","))
        })
        .collect();
    Resp::json(200, format!("[{}]", out.join(",")))
}

/// The link-open declaration, build.json::links, verbatim: the shell reads its types and choices from here.
pub fn links() -> Result<serde_json::Value, String> {
    let root: serde_json::Value = serde_json::from_str(serving::BUILD_JSON).map_err(|e| format!("build.json does not parse: {}", e))?;
    let l = root["links"].clone();
    check_links(&l)?;
    Ok(l)
}

/// Well-formedness of build.json::links: every type names known choices, ids are unique, the last type is the
/// catch-all (`schemes ["*"]`, `hosts any`) so no link is left without a rule.
pub fn check_links(l: &serde_json::Value) -> Result<(), String> {
    let choices: Vec<&str> = l["choices"].as_array().ok_or("links.choices must be an array")?.iter().filter_map(|c| c["id"].as_str()).collect();
    if !(choices.contains(&"browser") && choices.contains(&"embedded")) {
        return Err("links.choices must declare browser and embedded".to_string());
    }
    let types = l["types"].as_array().ok_or("links.types must be an array")?;
    let mut seen = std::collections::HashSet::new();
    for t in types {
        let id = t["id"].as_str().ok_or("a link type has no id")?;
        if !seen.insert(id) {
            return Err(format!("link type {} is declared twice", id));
        }
        if !matches!(t["hosts"].as_str(), Some("local" | "external" | "any")) {
            return Err(format!("link type {} has hosts {:?}", id, t["hosts"]));
        }
        let cs = t["choices"].as_array().filter(|c| !c.is_empty()).ok_or_else(|| format!("link type {} offers no choice", id))?;
        for c in cs {
            if !choices.contains(&c.as_str().unwrap_or("")) {
                return Err(format!("link type {} offers an undeclared choice {}", id, c));
            }
        }
    }
    let last = types.last().ok_or("links.types is empty")?;
    if last["schemes"] != serde_json::json!(["*"]) || last["hosts"] != "any" {
        return Err("the last link type must be the catch-all (schemes [\"*\"], hosts any)".to_string());
    }
    Ok(())
}

fn api_links(_ctx: &Ctx, _req: &Req) -> Resp {
    match links() {
        Ok(v) => Resp::json(200, v.to_string()),
        Err(e) => jerr(500, &e),
    }
}

/// An openable link: http or https, a host, no whitespace or control characters.
pub fn openable(url: &str) -> bool {
    let rest = match url.strip_prefix("https://").or_else(|| url.strip_prefix("http://")) {
        Some(r) => r,
        None => return false,
    };
    !rest.is_empty() && !rest.starts_with('/') && !url.chars().any(|c| c.is_whitespace() || c.is_control())
}

/// POST {url} | {id, via}: hand a link to cloud-browser. {id, via} is built from the declaration; {url} must be an
/// http(s) link (the user's explicit choice in the chooser), anything else is refused.
fn crashes_api(ctx: &Ctx, req: &Req) -> Resp {
    if req.method == "POST" { crashes::api_post(ctx, req) } else { crashes::api_get(ctx, req) }
}

fn api_open(_ctx: &Ctx, req: &Req) -> Resp {
    let body: serde_json::Value = match serde_json::from_slice(&req.body) {
        Ok(v) => v,
        Err(_) => return jerr(400, "body must be JSON {url} or {id, via}"),
    };
    let url = if let Some(u) = body["url"].as_str() {
        if !openable(u) {
            return jerr(400, "only http and https links can be opened");
        }
        u.to_string()
    } else {
        let ep = match body["id"].as_str().and_then(serving::by_id) {
            Some(e) => e,
            None => return jerr(404, "no such served page"),
        };
        if body["via"].as_str() == Some("ip") { ep.url_ip() } else { ep.url_name() }
    };
    match crate::diag::open(&url) {
        Ok(()) => Resp::json(200, format!("{{\"ok\":true,\"url\":{}}}", jstr(&url))),
        Err(e) => jerr(502, &e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    fn ctx() -> Ctx {
        Ctx { root: std::env::temp_dir(), port: 8002, started: Instant::now(), binary: "test".to_string() }
    }

    fn get(path: &str) -> Option<Resp> {
        let req = http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: 127.0.0.1\r\n\r\n", path)).unwrap();
        handle(&ctx(), &req, &|h: &str| h == "127.0.0.1")
    }

    #[test]
    fn the_table_is_well_formed() {
        check_table().unwrap();
        let tabs: Vec<&str> = ROUTES.iter().filter_map(|r| r.tab).collect();
        assert_eq!(tabs, ["Pages", "API", "Home", "Files", "Configs"], "the five bottom tabs, in order");
    }

    #[test]
    fn a_planted_route_appears_and_a_removed_one_disappears() {
        let planted = Route { kind: Kind::Page, path: "/__app__/tab/extra", tab: Some("Extra"), post: false, summary: "planted", handler: page_shell };
        let with: String = routes_json_of(ROUTES.iter().chain(std::iter::once(&planted)));
        assert!(with.contains("\"tab\":\"Extra\""));
        let without = routes_json_of(ROUTES.iter().filter(|r| r.tab != Some("API")));
        assert!(!without.contains("\"tab\":\"API\""));
    }

    #[test]
    fn nothing_outside_the_prefix_is_answered() {
        for p in ["/", "/__api__/ls", "/__browse__", "/__lib__/marked.min.js", "/some/file.txt", "/__app__x"] {
            assert!(get(p).is_none(), "{} belongs to the shared file server, not to the shell", p);
        }
        assert_eq!(get("/__app__/nope").unwrap().status, 404);
    }

    #[test]
    fn every_tab_and_api_row_answers() {
        for r in ROUTES.iter().filter(|r| !r.post) {
            let resp = get(r.path).unwrap();
            assert_eq!(resp.status, 200, "{}", r.path);
        }
        assert!(String::from_utf8_lossy(&get("/__app__/tab/files").unwrap().body).contains("id=\"nav\""));
    }

    #[test]
    fn open_is_guarded() {
        let post = |extra: &str, body: &str| {
            let head = format!("POST /__app__/api/open HTTP/1.1\r\nhost: 127.0.0.1\r\n{}content-length: {}\r\n\r\n", extra, body.len());
            let mut req = http::parse_request(&head).unwrap();
            req.body = body.as_bytes().to_vec();
            handle(&ctx(), &req, &|h: &str| h == "127.0.0.1").unwrap().status
        };
        assert_eq!(post("content-type: application/json\r\n", "{\"url\":\"https://example.org/\"}"), 403, "no x-c3-write header");
        assert_eq!(post("x-c3-write: 1\r\n", "{\"url\":\"https://example.org/\"}"), 403, "not JSON");
        assert_eq!(post("x-c3-write: 1\r\ncontent-type: application/json\r\norigin: http://evil.example\r\n", "{}"), 403, "foreign origin");
        assert_eq!(post("x-c3-write: 1\r\ncontent-type: application/json\r\n", "{\"url\":\"file:///etc/passwd\"}"), 400);
        assert_eq!(post("x-c3-write: 1\r\ncontent-type: application/json\r\n", "{\"url\":\"intent://x#Intent;end\"}"), 400);
        // a well-formed https link reaches the opener, which this test process has none of
        assert_eq!(post("x-c3-write: 1\r\ncontent-type: application/json\r\n", "{\"url\":\"https://example.org/\"}"), 502);
        assert_eq!(post("x-c3-write: 1\r\ncontent-type: application/json\r\n", "not json"), 400);
    }

    #[test]
    fn openable_accepts_only_web_links() {
        assert!(openable("http://127.0.0.1:8002/a.md") && openable("https://emulated.app/x"));
        for bad in ["", "javascript:alert(1)", "file:///x", "http://", "http:///x", "https://a b", "mailto:a@b.c", "//host/x"] {
            assert!(!openable(bad), "{}", bad);
        }
    }

    #[test]
    fn links_declaration_is_well_formed_and_refuses_bad_ones() {
        let l = links().unwrap();
        assert_eq!(l["types"][0]["id"], "served");
        let mut bad = l.clone();
        bad["types"][0]["choices"] = serde_json::json!(["teleport"]);
        assert!(check_links(&bad).is_err(), "an undeclared choice");
        let mut bad = l.clone();
        bad["types"].as_array_mut().unwrap().pop();
        assert!(check_links(&bad).is_err(), "no catch-all");
        let mut bad = l.clone();
        let first = bad["types"][0].clone();
        bad["types"].as_array_mut().unwrap().insert(1, first);
        assert!(check_links(&bad).is_err(), "duplicate type");
    }
}
