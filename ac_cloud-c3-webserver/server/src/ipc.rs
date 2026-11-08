//! #1495/#1580 the UNIFIED IPC HOLDER: ONE gateway, `/ipc/<app>/<route>`, fronting every fleet app's on-device IPC,
//! with CENTRALIZED per-peer, per-route access control read from ONE declared registry.
//!
//! The holder retires the per-app mesh endpoints (`/logs/<app>`, `/browser/*`, `/shell/*`): instead of each app family
//! owning its own mesh route and its own auth, Cloud-Webserver reads `data/api-ipc-map.json` (`acl_scopes`, `peers`,
//! `ipc_registry`) and dispatches every declared route through one surface. The registry is the single source of truth —
//! MCP tool <-> gateway route <-> IPC target (`{package}.<authority>`) <-> required ACL scope.
//!
//! Access, fail closed (the mesh listener's bearer + mesh-only guard runs first, in [`crate::meshlogs::route`]):
//!   * the caller's PEER identity is the `X-C3-Peer` header; no header is the default peer `fleet` (full access, back-compat).
//!     cloud-mesh owns peer discovery and the per-peer token binding (phase 2); this holder only reads the declared scopes.
//!   * every route requires one ACL scope ([`Entry::acl`]); a peer is served only when its scopes contain it (or `*`).
//!   * the app is always a fleet-manifest app and the authority is ALWAYS `{package}` of that app — never taken from the request.
//!   * a provider call wakes its app, so an app in `mesh.no_wake` is refused (403) exactly as the legacy `/logs/<app>` did.

use crate::apis::App;
use crate::meshlogs::LogSource;
use fleet_webserver::http::{Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::{json, Value};
use std::collections::BTreeMap;

/// The IPC target of one registry route (a `ContentResolver.call(content://{package}.<authority>, method, arg)`).
#[derive(Clone, Debug)]
pub struct Ipc {
    pub endpoint: String,
    pub kind: String,
    /// Authority template, e.g. `{package}.logexport`; `{package}` is filled from the resolved manifest app.
    pub authority: String,
    /// A fixed provider method, when the route does not choose one.
    pub method: Option<String>,
    /// The allowed `?method=` set (with [`Ipc::default_method`]), when the route lets the caller pick.
    pub methods: Vec<String>,
    pub default_method: Option<String>,
    pub post: bool,
    pub max_body: usize,
    pub require_cmd: bool,
    /// The broker answer carries a `state` mapped to an HTTP status (shell broker).
    pub broker_status: bool,
    /// `(body field, method when true, method when false)`: the method depends on a boolean body field (browser CDP).
    pub method_from_body: Option<(String, String, String)>,
}

/// One declared route of one app class.
#[derive(Clone, Debug)]
pub struct Entry {
    pub route: String,
    /// `fleet` (every manifest app) or `package:<pkg>` or a manifest app id.
    pub app_class: String,
    pub mcp_tool: Option<String>,
    pub legacy_route: Option<String>,
    pub acl: String,
    pub status: String,
    pub what: String,
    pub ipc: Ipc,
}

/// The whole declared registry: scopes, per-peer access, and the routes.
#[derive(Clone, Debug)]
pub struct Registry {
    pub prefix: String,
    pub scopes: BTreeMap<String, String>,
    pub peers: BTreeMap<String, Vec<String>>,
    pub entries: Vec<Entry>,
}

/// A caller after the fleet-bearer gate: its id and the scopes the registry grants it.
#[derive(Clone, Debug, PartialEq)]
pub struct Peer {
    pub id: String,
    pub scopes: Vec<String>,
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or("").to_string()
}

fn strs(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

impl Registry {
    /// Parse and validate the registry out of `data/api-ipc-map.json`. Every entry's ACL and IPC endpoint, and every peer
    /// scope, must be declared, so a drifted registry fails fast rather than dispatching into nothing.
    pub fn from_map(m: &Value) -> Result<Registry, String> {
        let scopes: BTreeMap<String, String> =
            m["acl_scopes"].as_object().map(|o| o.iter().filter(|(k, _)| *k != "_doc").map(|(k, v)| (k.clone(), s(v))).collect()).unwrap_or_default();
        if scopes.is_empty() {
            return Err("acl_scopes is empty".into());
        }
        let peers: BTreeMap<String, Vec<String>> = m["peers"]
            .as_object()
            .map(|o| o.iter().filter(|(k, _)| *k != "_doc").map(|(k, v)| (k.clone(), strs(&v["scopes"]))).collect())
            .unwrap_or_default();
        for (p, sc) in &peers {
            for one in sc {
                if one != "*" && !scopes.contains_key(one) {
                    return Err(format!("peer {} is granted undeclared scope {}", p, one));
                }
            }
        }
        if peers.get("fleet").map(|s| s.is_empty()).unwrap_or(true) {
            return Err("peer `fleet` must be declared with its scopes (the back-compat default peer)".into());
        }
        let reg = &m["ipc_registry"];
        let prefix = reg["route_prefix"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).ok_or("ipc_registry.route_prefix must be a path")?.trim_end_matches('/').to_string();
        let endpoints: Vec<String> = m["ipc_endpoints"].as_array().map(|a| a.iter().map(|e| s(&e["id"])).collect()).unwrap_or_default();
        let mut entries = vec![];
        for e in reg["entries"].as_array().map(|a| a.as_slice()).unwrap_or(&[]) {
            let ipc = &e["ipc"];
            let endpoint = s(&ipc["endpoint"]);
            if !endpoints.contains(&endpoint) {
                return Err(format!("route {} names unknown ipc endpoint {}", s(&e["route"]), endpoint));
            }
            let acl = s(&e["acl"]);
            if !scopes.contains_key(&acl) {
                return Err(format!("route {} requires undeclared scope {}", s(&e["route"]), acl));
            }
            let method_from_body = match ipc.get("method_from_body") {
                Some(Value::Object(o)) => Some((
                    o.get("field").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    o.get("true").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                    o.get("false").and_then(|v| v.as_str()).unwrap_or("").to_string(),
                )),
                _ => None,
            };
            entries.push(Entry {
                route: s(&e["route"]),
                app_class: s(&e["app_class"]),
                mcp_tool: e["mcp_tool"].as_str().map(str::to_string),
                legacy_route: e["legacy_route"].as_str().map(str::to_string),
                acl,
                status: e["status"].as_str().unwrap_or("active").to_string(),
                what: s(&e["what"]),
                ipc: Ipc {
                    endpoint,
                    kind: s(&ipc["kind"]),
                    authority: s(&ipc["authority"]),
                    method: ipc["method"].as_str().map(str::to_string),
                    methods: strs(&ipc["methods"]),
                    default_method: ipc["default_method"].as_str().map(str::to_string),
                    post: ipc["post"].as_bool().unwrap_or(false),
                    max_body: ipc["max_body"].as_u64().unwrap_or(1_000_000) as usize,
                    require_cmd: ipc["require_cmd"].as_bool().unwrap_or(false),
                    broker_status: ipc["broker_status"].as_bool().unwrap_or(false),
                    method_from_body,
                },
            });
        }
        if entries.is_empty() {
            return Err("ipc_registry.entries is empty".into());
        }
        Ok(Registry { prefix, scopes, peers, entries })
    }

    /// Does this entry apply to this app? `fleet` is every manifest app; otherwise the class is `package:<pkg>` or the app id.
    pub fn applies(e: &Entry, app: &App) -> bool {
        e.app_class == "fleet" || e.app_class == format!("package:{}", app.package) || e.app_class == app.id
    }

    /// The registry routes that apply to one app (for the index listing and the UI).
    pub fn routes_for<'a>(&'a self, app: &App) -> Vec<&'a Entry> {
        self.entries.iter().filter(|e| Registry::applies(e, app)).collect()
    }
}

/// Is a peer with these scopes allowed a route that requires `required`? `*` grants every scope.
pub fn allowed(scopes: &[String], required: &str) -> bool {
    scopes.iter().any(|s| s == "*" || s == required)
}

/// Resolve the caller's peer from the `X-C3-Peer` header against the registry. No header is the default peer `fleet`.
/// A named peer the registry does not declare is refused (403) — never silently granted.
pub fn resolve_peer(reg: &Registry, header: Option<&str>) -> Result<Peer, Resp> {
    let id = header.map(|h| h.trim()).filter(|h| !h.is_empty()).unwrap_or("fleet");
    match reg.peers.get(id) {
        Some(scopes) => Ok(Peer { id: id.to_string(), scopes: scopes.clone() }),
        None => Err(Resp::json(403, format!("{{\"error\":{},\"peer\":{}}}", jstr("unknown mesh peer; declare it in the registry (data/api-ipc-map.json::peers)"), jstr(id)))),
    }
}

fn digits(v: Option<&str>, max: u32) -> Option<u32> {
    v.and_then(|x| x.parse::<u32>().ok()).map(|n| n.min(max))
}

/// The GET query arg of a logs-style route (`n`, `errors`, `crashes`), the same shape the legacy `/logs/<app>` built.
fn query_arg(req: &Req) -> String {
    let mut arg = vec![];
    if let Some(n) = digits(req.q("n"), 2000) {
        arg.push(format!("n={}", n));
    }
    if req.q("errors") == Some("1") {
        arg.push("errors=1".to_string());
    }
    if let Some(c) = digits(req.q("crashes"), 20) {
        arg.push(format!("crashes={}", c));
    }
    arg.join("&")
}

fn err(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

/// A JSON row describing one registry route (the index listing and the UI both read it).
pub fn entry_json(reg: &Registry, e: &Entry) -> Value {
    json!({
        "route": format!("{}/<app>/{}", reg.prefix, e.route),
        "app_class": e.app_class,
        "mcp_tool": e.mcp_tool,
        "legacy_route": e.legacy_route,
        "acl": e.acl,
        "status": e.status,
        "what": e.what,
        "ipc": {"endpoint": e.ipc.endpoint, "kind": e.ipc.kind, "authority": e.ipc.authority,
                "method": e.ipc.method, "methods": e.ipc.methods, "post": e.ipc.post},
    })
}

/// The unified gateway, after the mesh listener's bearer + mesh-only gate. `rest` is the path after the `/ipc` prefix,
/// `peer` the resolved caller, `no_wake` the packages a provider call must never wake. Pure: the tests feed it stubs.
pub fn gateway(reg: &Registry, apps: &[App], rest: &str, req: &Req, peer: &Peer, no_wake: &[String], src: &LogSource) -> Resp {
    let rest = rest.trim_matches('/');
    // `/ipc` with no app: the whole registry index.
    if rest.is_empty() {
        let rows: Vec<Value> = reg.entries.iter().map(|e| entry_json(reg, e)).collect();
        return Resp::json(200, json!({"prefix": reg.prefix, "peer": peer.id, "routes": rows}).to_string());
    }
    let (app_seg, route) = match rest.split_once('/') {
        Some((a, r)) => (a, r.trim_matches('/')),
        None => (rest, ""),
    };
    let app = match apps.iter().find(|a| a.id == app_seg || a.package == app_seg) {
        Some(a) => a,
        None => return err(404, &format!("{} is not an app of the fleet manifest", app_seg)),
    };
    // `/ipc/<app>` with no route: that app's declared routes.
    if route.is_empty() {
        let rows: Vec<Value> = reg.routes_for(app).iter().map(|e| entry_json(reg, e)).collect();
        return Resp::json(200, json!({"app": app.id, "package": app.package, "routes": rows}).to_string());
    }
    let entry = match reg.entries.iter().find(|e| Registry::applies(e, app) && e.route == route) {
        Some(e) => e,
        None => return err(404, &format!("{} has no IPC route {}", app.id, route)),
    };
    // Centralized ACL: the one place that holds every peer's per-route access.
    if !allowed(&peer.scopes, &entry.acl) {
        return Resp::json(403, format!("{{\"error\":{},\"peer\":{},\"needs\":{}}}", jstr(&format!("peer {} is not allowed {} (needs scope {})", peer.id, route, entry.acl)), jstr(&peer.id), jstr(&entry.acl)));
    }
    // A provider call wakes the app: an app the fleet declares must never be woken is refused here, as the legacy route did.
    if entry.ipc.kind == "provider" && no_wake.iter().any(|p| *p == app.package) {
        return Resp::json(403, format!("{{\"error\":{},\"app\":{}}}", jstr("this app is never woken from the mesh; read its log in the app (Settings, Debug API) or from its own screen"), jstr(&app.id)));
    }
    if entry.ipc.post != (req.method == "POST") {
        return Resp::json(405, format!("{{\"error\":{}}}", jstr(if entry.ipc.post { "POST required" } else { "GET required" }))).with("allow", if entry.ipc.post { "POST" } else { "GET" });
    }
    if req.body.len() > entry.ipc.max_body {
        return err(413, "body too large");
    }
    // Resolve the provider method from the registry (fixed, chosen via ?method, or a boolean body field), never from the path.
    let body_val = || serde_json::from_slice::<Value>(&req.body).ok();
    let method: String = if let Some((field, t, f)) = &entry.ipc.method_from_body {
        if body_val().and_then(|v| v[field].as_bool()).unwrap_or(false) { t.clone() } else { f.clone() }
    } else if let Some(m) = &entry.ipc.method {
        m.clone()
    } else if !entry.ipc.methods.is_empty() {
        let def = entry.ipc.default_method.clone().unwrap_or_default();
        let chosen = req.q("method").unwrap_or(&def).to_string();
        if !entry.ipc.methods.iter().any(|x| *x == chosen) {
            return err(400, &format!("method must be one of {}", entry.ipc.methods.join(", ")));
        }
        chosen
    } else {
        return err(500, "registry route declares no method");
    };
    if entry.ipc.require_cmd {
        let ok = body_val().map(|v| v["cmd"].as_str().map_or(false, |c| !c.trim().is_empty() && c.len() <= 2048)).unwrap_or(false);
        if !ok {
            return err(400, "body must be {\"cmd\": \"...\", \"timeoutMs\": n}");
        }
    }
    let arg = if entry.ipc.post { String::from_utf8_lossy(&req.body).to_string() } else { query_arg(req) };
    let authority = entry.ipc.authority.replace("{package}", &app.package);
    match src(&authority, &method, &arg) {
        Ok((_, payload)) => {
            if entry.ipc.broker_status {
                let status = match serde_json::from_str::<Value>(&payload).ok().as_ref().and_then(|v| v["state"].as_str()) {
                    Some("needs_arm") => 409,
                    Some("denied") => 403,
                    Some("timeout") => 504,
                    Some("failed") => 502,
                    _ => 200,
                };
                Resp::bytes(status, "application/json", payload.into_bytes())
            } else {
                Resp::bytes(200, "application/json", payload.into_bytes())
            }
        }
        Err(e) => Resp::json(502, format!("{{\"error\":{},\"app\":{}}}", jstr(&e), jstr(&app.id))),
    }
}

/// The registry parsed once from `data/api-ipc-map.json` (shared with topology's `MAP`). `Err` is a drifted registry.
pub fn registry() -> &'static Result<Registry, String> {
    static R: std::sync::OnceLock<Result<Registry, String>> = std::sync::OnceLock::new();
    R.get_or_init(|| Registry::from_map(crate::topology::map()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use fleet_webserver::http;

    fn reg() -> Registry {
        Registry::from_map(crate::topology::map()).expect("the committed registry parses and validates")
    }
    fn apps() -> Vec<App> {
        // the three proof apps, exactly as the fleet manifest declares them (drive is woken; store & account are no_wake).
        vec![
            App { id: "drive".into(), name: "Cloud Drive".into(), package: "com.diegonmarcos.clouddrive".into(), engines: vec![] },
            App { id: "store".into(), name: "Cloud Store".into(), package: "com.diegonmarcos.cloudstore".into(), engines: vec![] },
            App { id: "account".into(), name: "Cloud Account".into(), package: "com.diegonmarcos.cloudaccount".into(), engines: vec![] },
            App { id: "browser".into(), name: "Cloud Browser".into(), package: "com.diegonmarcos.cloudbrowser".into(), engines: vec![] },
            App { id: "aa_cloud-superapp".into(), name: "SuperApp".into(), package: "com.diegonmarcos.superapp".into(), engines: vec![] },
        ]
    }
    fn no_wake() -> Vec<String> {
        vec!["com.diegonmarcos.cloudaccount".into(), "com.diegonmarcos.cloudstore".into()]
    }
    fn fleet_peer() -> Peer {
        Peer { id: "fleet".into(), scopes: vec!["*".into()] }
    }
    fn get(path: &str) -> Req {
        http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: 10.0.0.9\r\n\r\n", path)).unwrap()
    }
    fn post(path: &str, body: &str) -> Req {
        let mut r = http::parse_request(&format!("POST {} HTTP/1.1\r\nhost: 10.0.0.9\r\ncontent-length: {}\r\n\r\n", path, body.len())).unwrap();
        r.body = body.as_bytes().to_vec();
        r
    }
    /// A provider stub: it answers for the apps declared below and records what it was asked.
    fn spy() -> (std::sync::Arc<std::sync::Mutex<Vec<(String, String, String)>>>, Box<LogSource>) {
        let seen = std::sync::Arc::new(std::sync::Mutex::new(vec![]));
        let sink = seen.clone();
        let f: Box<LogSource> = Box::new(move |authority: &str, method: &str, arg: &str| {
            sink.lock().unwrap().push((authority.to_string(), method.to_string(), arg.to_string()));
            if authority.starts_with("com.diegonmarcos.superapp.shellbroker") {
                Ok(("application/json".into(), "{\"state\":\"ok\",\"exitCode\":0}".into()))
            } else {
                Ok(("application/json".into(), format!("{{\"m\":\"{}\",\"a\":\"{}\"}}", method, arg)))
            }
        });
        (seen, f)
    }

    #[test]
    fn the_committed_registry_is_valid_and_wires_the_declared_endpoints() {
        let r = reg();
        assert!(r.scopes.contains_key("logs.read") && r.scopes.contains_key("shell.exec"));
        assert_eq!(r.peers["fleet"], vec!["*".to_string()]);
        // the fleet logs route, the browser control routes and the shell routes are all declared.
        assert!(r.entries.iter().any(|e| e.app_class == "fleet" && e.route == "logs" && e.ipc.endpoint == "logexport"));
        assert!(r.entries.iter().any(|e| e.app_class.contains("cloudbrowser") && e.route == "command" && e.ipc.post));
        assert!(r.entries.iter().any(|e| e.app_class.contains("superapp") && e.route == "exec" && e.ipc.broker_status));
    }

    #[test]
    fn a_woken_app_dispatches_to_its_providers_authority() {
        // #proof Cloud Drive is a manifest app and is NOT no_wake: /ipc/drive/logs reaches com.diegonmarcos.clouddrive.logexport.
        let (seen, src) = spy();
        // `rest` is the path after /ipc with the query already parsed off (req.q), exactly as the mesh listener passes it.
        let r = gateway(&reg(), &apps(), "drive/logs", &get("/ipc/drive/logs?n=50&errors=1&crashes=2"), &fleet_peer(), &no_wake(), &src);
        assert_eq!(r.status, 200);
        assert_eq!(String::from_utf8_lossy(&r.body), "{\"m\":\"export\",\"a\":\"n=50&errors=1&crashes=2\"}");
        let s = seen.lock().unwrap().clone();
        assert_eq!(s, vec![("com.diegonmarcos.clouddrive.logexport".into(), "export".into(), "n=50&errors=1&crashes=2".into())]);
        // ?method picks a declared method; the authority is still the app's own, never from the path.
        let (_, src2) = spy();
        let m = gateway(&reg(), &apps(), "drive/logs", &get("/ipc/drive/logs?method=meta"), &fleet_peer(), &no_wake(), &src2);
        assert_eq!(String::from_utf8_lossy(&m.body), "{\"m\":\"meta\",\"a\":\"\"}");
    }

    #[test]
    fn a_no_wake_app_is_refused_centrally_and_never_reaches_the_provider() {
        // #proof Cloud Store and Cloud Account must never be woken from the mesh; the holder enforces it for every route.
        for app in ["store", "account", "com.diegonmarcos.cloudstore"] {
            let spy = |_: &str, _: &str, _: &str| -> Result<(String, String), String> { panic!("a no-wake app must not be called") };
            let r = gateway(&reg(), &apps(), &format!("{}/logs", app), &get(&format!("/ipc/{}/logs", app)), &fleet_peer(), &no_wake(), &spy);
            assert_eq!(r.status, 403, "{}", app);
        }
    }

    #[test]
    fn the_acl_gates_each_route_by_the_peers_scopes() {
        let r = reg();
        // a read-only ops peer may read drive logs but may not drive the browser or the shell.
        let ops = Peer { id: "ops-console".into(), scopes: r.peers["ops-console"].clone() };
        let (_, src) = spy();
        assert_eq!(gateway(&r, &apps(), "drive/logs", &get("/ipc/drive/logs"), &ops, &no_wake(), &src).status, 200);
        let shell = gateway(&r, &apps(), "aa_cloud-superapp/exec", &post("/ipc/aa_cloud-superapp/exec", "{\"cmd\":\"id\"}"), &ops, &no_wake(), &src);
        assert_eq!(shell.status, 403, "ops-console lacks shell.exec");
        let browser = gateway(&r, &apps(), "browser/command", &post("/ipc/browser/command", "{}"), &ops, &no_wake(), &src);
        assert_eq!(browser.status, 403, "ops-console lacks browser.control");
        // the browser peer may drive the browser but not the shell.
        let bp = Peer { id: "browser-peer".into(), scopes: r.peers["browser-peer"].clone() };
        assert_eq!(gateway(&r, &apps(), "browser/command", &post("/ipc/browser/command", "{\"cmd\":\"html\"}"), &bp, &no_wake(), &src).status, 200);
        assert_eq!(gateway(&r, &apps(), "aa_cloud-superapp/exec", &post("/ipc/aa_cloud-superapp/exec", "{\"cmd\":\"id\"}"), &bp, &no_wake(), &src).status, 403);
    }

    #[test]
    fn an_unknown_peer_is_refused() {
        assert!(resolve_peer(&reg(), Some("nobody")).is_err());
        assert_eq!(resolve_peer(&reg(), None).ok().map(|p| p.id), Some("fleet".to_string()));
        assert_eq!(resolve_peer(&reg(), Some("ops-console")).ok().map(|p| p.scopes), Some(vec!["logs.read".to_string(), "diag.read".to_string()]));
    }

    #[test]
    fn browser_and_shell_dispatch_through_the_one_gateway() {
        let r = reg();
        let (seen, src) = spy();
        // browser: POST command/run/stop reach the headless provider with the declared method; CDP picks the method from the body.
        assert_eq!(gateway(&r, &apps(), "browser/command", &post("/ipc/browser/command", "{\"cmd\":\"html\"}"), &fleet_peer(), &no_wake(), &src).status, 200);
        assert_eq!(gateway(&r, &apps(), "browser/cdp", &post("/ipc/browser/cdp", "{\"on\":true}"), &fleet_peer(), &no_wake(), &src).status, 200);
        assert_eq!(gateway(&r, &apps(), "browser/cdp", &post("/ipc/browser/cdp", "{\"on\":false}"), &fleet_peer(), &no_wake(), &src).status, 200);
        // shell: the broker's state is mapped to a status, and a bad body is refused before the phone.
        assert_eq!(gateway(&r, &apps(), "aa_cloud-superapp/exec", &post("/ipc/aa_cloud-superapp/exec", "{\"cmd\":\"getprop ro.x\",\"timeoutMs\":5000}"), &fleet_peer(), &no_wake(), &src).status, 200);
        let s = seen.lock().unwrap().clone();
        assert!(s.iter().any(|x| x.0 == "com.diegonmarcos.cloudbrowser.headless" && x.1 == "command"));
        assert!(s.iter().any(|x| x.0 == "com.diegonmarcos.cloudbrowser.headless" && x.1 == "cdp_on"));
        assert!(s.iter().any(|x| x.0 == "com.diegonmarcos.cloudbrowser.headless" && x.1 == "cdp_off"));
        assert!(s.iter().any(|x| x.0 == "com.diegonmarcos.superapp.shellbroker" && x.1 == "exec_json"));
        let bad = |body: &str| gateway(&r, &apps(), "aa_cloud-superapp/exec", &post("/ipc/aa_cloud-superapp/exec", body), &fleet_peer(), &no_wake(), &{ let f: Box<LogSource> = Box::new(|_: &str, _: &str, _: &str| panic!("refused body must not reach the phone")); f }).status;
        assert_eq!(bad("not json"), 400);
        assert_eq!(bad("{\"cmd\":\"  \"}"), 400);
    }

    #[test]
    fn unknown_app_route_and_wrong_verb_are_rejected_before_any_call() {
        let r = reg();
        let spy = |_: &str, _: &str, _: &str| -> Result<(String, String), String> { panic!("must not be called") };
        assert_eq!(gateway(&r, &apps(), "nope/logs", &get("/ipc/nope/logs"), &fleet_peer(), &no_wake(), &spy).status, 404);
        assert_eq!(gateway(&r, &apps(), "drive/shell", &get("/ipc/drive/shell"), &fleet_peer(), &no_wake(), &spy).status, 404);
        assert_eq!(gateway(&r, &apps(), "drive/logs", &post("/ipc/drive/logs", "{}"), &fleet_peer(), &no_wake(), &spy).status, 405);
        assert_eq!(gateway(&r, &apps(), "browser/command", &get("/ipc/browser/command"), &fleet_peer(), &no_wake(), &spy).status, 405);
    }

    #[test]
    fn the_index_lists_the_registry() {
        let (_, src) = spy();
        let idx = gateway(&reg(), &apps(), "", &get("/ipc"), &fleet_peer(), &no_wake(), &src);
        assert_eq!(idx.status, 200);
        let v: Value = serde_json::from_slice(&idx.body).unwrap();
        assert_eq!(v["prefix"], "/ipc");
        assert!(v["routes"].as_array().unwrap().iter().any(|r| r["route"] == "/ipc/<app>/logs"));
        // /ipc/<app> lists that app's routes.
        let a = gateway(&reg(), &apps(), "drive", &get("/ipc/drive"), &fleet_peer(), &no_wake(), &src);
        let av: Value = serde_json::from_slice(&a.body).unwrap();
        assert!(av["routes"].as_array().unwrap().iter().any(|r| r["route"] == "/ipc/<app>/logs"));
    }
}
