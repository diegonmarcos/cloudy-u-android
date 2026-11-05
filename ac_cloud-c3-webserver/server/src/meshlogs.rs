//! #1490 the MESH LISTENER: `/logs/<app>` routes each fleet app's log export onto the mesh.
//!
//! Cloud-Webserver calls the app's `<package>.logexport` provider (libs:log-export, contract in
//! ab_cloud-libs-shared/libs/devtools .../logexport/LogExportContract.kt) and relays its JSON. The provider call is JNI and belongs to
//! the Tauri crate, which installs it with [`set_log_source`] / [`set_token_source`]; this crate stays platform-free and its tests
//! run against stubs.
//!
//! Exposure, fail closed (never public):
//!   * it binds the phone's MESH address only (build.json::mesh.bind, resolved at build time from the WireGuard profile) and refuses to
//!     start unless that address lies inside `mesh.cidrs`; 0.0.0.0, loopback and any public address are refused;
//!   * it answers only peers inside `mesh.cidrs`;
//!   * EVERY path needs `Authorization: Bearer <fleet token>` (the token the SuperApp mints), compared in constant time; with no
//!     token known the answer is 503, never open.

use crate::apis::{self, App};
use fleet_webserver::http::{self, Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::Value;
use std::net::{IpAddr, Ipv4Addr, TcpListener};
use std::sync::{Arc, OnceLock};
use std::time::Duration;

/// (authority, method, arg) -> Ok((mime, payload)) | Err(reason): ContentResolver.call(content://authority, method, arg).
pub type LogSource = dyn Fn(&str, &str, &str) -> Result<(String, String), String> + Send + Sync;
/// The fleet bearer token, or None when it cannot be read (SuperApp missing).
pub type TokenSource = dyn Fn() -> Option<String> + Send + Sync;

static LOGS: OnceLock<Box<LogSource>> = OnceLock::new();
static TOKEN: OnceLock<Box<TokenSource>> = OnceLock::new();

pub fn set_log_source(f: Box<LogSource>) -> bool {
    LOGS.set(f).is_ok()
}
pub fn set_token_source(f: Box<TokenSource>) -> bool {
    TOKEN.set(f).is_ok()
}

#[derive(Clone, Debug)]
pub struct Mesh {
    pub bind: Ipv4Addr,
    pub port: u16,
    pub cidrs: Vec<(Ipv4Addr, u8)>,
    pub retry_s: u64,
    pub route: String,
    /// `<endpoints_route>/public` and `/mesh`: the API tab's Public and Mesh Endpoints pages, for cloudy-c3-mcp.
    pub endpoints_route: String,
    pub suffix: String,
    pub default_method: String,
    pub methods: Vec<String>,
    /// #1498 `/browser/*`: the headless browser API (headless-api v1) of Cloud-Browser's `<package>.headless` provider.
    pub browser_route: String,
    pub browser_package: String,
    pub browser_suffix: String,
    /// #1502 packages whose provider is never called from the mesh (a call starts the app): Cloud Account and Cloud Store.
    pub no_wake: Vec<String>,
}

pub fn parse_cidr(c: &str) -> Result<(Ipv4Addr, u8), String> {
    let (a, n) = c.split_once('/').ok_or_else(|| format!("{} is not a.b.c.d/n", c))?;
    let ip: Ipv4Addr = a.parse().map_err(|_| format!("{} is not an IPv4 address", a))?;
    let n: u8 = n.parse().map_err(|_| format!("{} is not a prefix length", n))?;
    if n == 0 || n > 32 {
        return Err(format!("prefix /{} is not a mesh subnet (a /0 would be the whole internet)", n));
    }
    Ok((ip, n))
}

pub fn cidr_contains(c: (Ipv4Addr, u8), ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => {
            let mask = if c.1 == 32 { u32::MAX } else { !(u32::MAX >> c.1) };
            (u32::from(v4) & mask) == (u32::from(c.0) & mask)
        }
        IpAddr::V6(_) => false,
    }
}

impl Mesh {
    pub fn from_build(b: &Value) -> Result<Mesh, String> {
        let m = &b["mesh"];
        let bind: Ipv4Addr = m["bind"].as_str().ok_or("mesh.bind is not resolved (server/build.rs)")?.parse().map_err(|_| "mesh.bind is not an IPv4 address".to_string())?;
        let port = b["runtime"]["mesh"]["port"].as_u64().filter(|p| (1024..=65535).contains(p)).ok_or("runtime.mesh.port must be an integer in 1024..65535")? as u16;
        let cidrs = m["cidrs"].as_array().ok_or("mesh.cidrs must be an array")?.iter().map(|c| parse_cidr(c.as_str().unwrap_or(""))).collect::<Result<Vec<_>, _>>()?;
        let strs = |v: &Value| v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect::<Vec<_>>()).unwrap_or_default();
        let mesh = Mesh {
            bind,
            port,
            cidrs,
            retry_s: m["retry_s"].as_u64().unwrap_or(20).clamp(1, 600),
            route: m["logs_route"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).ok_or("mesh.logs_route must be a path")?.trim_end_matches('/').to_string(),
            endpoints_route: m["endpoints_route"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).unwrap_or("/endpoints").trim_end_matches('/').to_string(),
            suffix: m["ipc"]["authority_suffix"].as_str().filter(|x| !x.is_empty()).ok_or("mesh.ipc.authority_suffix missing")?.to_string(),
            default_method: m["ipc"]["default_method"].as_str().unwrap_or("export").to_string(),
            methods: strs(&m["ipc"]["methods"]),
            browser_route: m["browser_route"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).unwrap_or("/browser").trim_end_matches('/').to_string(),
            browser_package: m["browser_package"].as_str().filter(|x| !x.is_empty()).unwrap_or("com.diegonmarcos.cloudbrowser").to_string(),
            browser_suffix: m["browser_suffix"].as_str().filter(|x| !x.is_empty()).unwrap_or("headless").to_string(),
            no_wake: strs(&m["no_wake"]),
        };
        if !mesh.methods.contains(&mesh.default_method) {
            return Err(format!("mesh.ipc.default_method {} is not one of its methods", mesh.default_method));
        }
        mesh.check_bind()?;
        Ok(mesh)
    }

    pub fn cidr_text(&self) -> String {
        self.cidrs.iter().map(|(a, n)| format!("{}/{}", a, n)).collect::<Vec<_>>().join(", ")
    }

    /// Is this address one the listener may serve? Inside the mesh subnets, and never unspecified, loopback or multicast.
    pub fn is_mesh(&self, ip: IpAddr) -> bool {
        !ip.is_unspecified() && !ip.is_loopback() && !ip.is_multicast() && self.cidrs.iter().any(|c| cidr_contains(*c, ip))
    }

    /// The bind guard: refuse to start on anything that is not a mesh address.
    pub fn check_bind(&self) -> Result<(), String> {
        if self.is_mesh(IpAddr::V4(self.bind)) {
            Ok(())
        } else {
            Err(format!("mesh bind {} is not inside the mesh subnets {}: the mesh listener never binds anything else", self.bind, self.cidr_text()))
        }
    }
}

fn ct_eq(a: &[u8], b: &[u8]) -> bool {
    let mut d = (a.len() ^ b.len()) as u8;
    for i in 0..a.len().max(b.len()) {
        d |= a.get(i).copied().unwrap_or(0) ^ b.get(i).copied().unwrap_or(0);
    }
    d == 0
}

/// None = authorised. 503 when no token is known (fail closed), 401 for a missing or wrong bearer.
pub fn authorize(header: Option<&str>, token: Option<&str>) -> Option<Resp> {
    let token = match token.filter(|t| !t.is_empty()) {
        Some(t) => t,
        None => return Some(Resp::json(503, format!("{{\"error\":{}}}", jstr("no fleet token is available (is the SuperApp installed?)")))),
    };
    let presented = header.and_then(|h| h.strip_prefix("Bearer ").or_else(|| h.strip_prefix("bearer "))).unwrap_or("");
    if !presented.is_empty() && ct_eq(presented.as_bytes(), token.as_bytes()) {
        None
    } else {
        Some(Resp::json(401, format!("{{\"error\":{}}}", jstr("bearer required"))).with("www-authenticate", "Bearer"))
    }
}

fn digits(v: Option<&str>, max: u32) -> Option<u32> {
    v.and_then(|x| x.parse::<u32>().ok()).map(|n| n.min(max))
}

/// The pure router: auth first, then `<route>` (index) and `<route>/<app>`.
pub fn route(m: &Mesh, apps: &[App], req: &Req, token: Option<&str>, src: &LogSource) -> Resp {
    if let Some(deny) = authorize(req.header("authorization"), token) {
        return deny;
    }
    let p = req.path.trim_end_matches('/');
    if let Some(rest) = p.strip_prefix(m.browser_route.as_str()).filter(|r| r.is_empty() || r.starts_with('/')) {
        return browser(m, rest.trim_start_matches('/'), req, src);
    }
    if let Some(kind) = p.strip_prefix(m.endpoints_route.as_str()).and_then(|r| r.strip_prefix('/')) {
        return match kind {
            "public" => Resp::json(200, apis::public_page(apis::data()).to_string()),
            "mesh" => Resp::json(200, apis::mesh_page(apis::data(), m, apps).to_string()),
            _ => Resp::json(404, "{\"error\":\"not found\"}".to_string()),
        };
    }
    let rest = match p.strip_prefix(m.route.as_str()) {
        Some(r) if r.is_empty() || r.starts_with('/') => r.trim_start_matches('/'),
        _ => return Resp::json(404, "{\"error\":\"not found\"}".to_string()),
    };
    if rest.is_empty() {
        let rows: Vec<String> = apps.iter().map(|a| format!("{{\"app\":{},\"package\":{},\"authority\":{},\"url\":{}}}", jstr(&a.id), jstr(&a.package), jstr(&format!("{}.{}", a.package, m.suffix)), jstr(&format!("{}/{}", m.route, a.id)))).collect();
        return Resp::json(200, format!("[{}]", rows.join(",")));
    }
    // Only an app of the fleet manifest (by id or package) is ever called: the authority is never taken from the request.
    let app = match apps.iter().find(|a| a.id == rest || a.package == rest) {
        Some(a) => a,
        None => return Resp::json(404, format!("{{\"error\":{}}}", jstr(&format!("{} is not an app of the fleet manifest", rest)))),
    };
    if m.no_wake.iter().any(|p| *p == app.package) {
        return Resp::json(403, format!("{{\"error\":{},\"app\":{}}}", jstr("this app is never woken from the mesh; read its log in the app (Settings, Debug API) or from its own screen"), jstr(&app.id)));
    }
    let method = req.q("method").unwrap_or(&m.default_method);
    if !m.methods.iter().any(|x| x == method) {
        return Resp::json(400, format!("{{\"error\":{}}}", jstr(&format!("method must be one of {}", m.methods.join(", ")))));
    }
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
    match src(&format!("{}.{}", app.package, m.suffix), method, &arg.join("&")) {
        Ok((_, payload)) => Resp::bytes(200, "application/json", payload.into_bytes()),
        Err(e) => Resp::json(502, format!("{{\"error\":{},\"app\":{}}}", jstr(&e), jstr(&app.id))),
    }
}

/// #1498 `/browser/*`, after the bearer check: the same headless-api v1 as the container, served from this phone. Only the Cloud-Browser provider is
/// ever called, only with the five declared provider methods, and the body is capped; nothing from the request names an authority or a method.
pub fn browser(m: &Mesh, rest: &str, req: &Req, src: &LogSource) -> Resp {
    let (method, post) = match rest {
        "" | "status" | "sessions" => ("status", false),
        "command" => ("command", true),
        "run" => ("run", true),
        "stop" => ("stop", true),
        "cdp" => (if serde_json::from_slice::<Value>(&req.body).ok().and_then(|v| v["on"].as_bool()).unwrap_or(false) { "cdp_on" } else { "cdp_off" }, true),
        _ => return Resp::json(404, "{\"error\":\"not found\"}".to_string()),
    };
    if post != (req.method == "POST") {
        return Resp::json(405, format!("{{\"error\":{}}}", jstr(if post { "POST required" } else { "GET required" }))).with("allow", if post { "POST" } else { "GET" });
    }
    if req.body.len() > 1_000_000 {
        return Resp::json(413, "{\"error\":\"body too large\"}".to_string());
    }
    let arg = String::from_utf8_lossy(&req.body).to_string();
    match src(&format!("{}.{}", m.browser_package, m.browser_suffix), method, &arg) {
        Ok((_, payload)) => Resp::bytes(200, "application/json", payload.into_bytes()),
        Err(e) => Resp::json(502, format!("{{\"error\":{},\"app\":\"browser\"}}", jstr(&e))),
    }
}

/// Bind the mesh address (retrying while the tunnel is down) and serve [`route`] on it, mesh peers only.
pub fn start(m: Mesh, stop: Arc<std::sync::atomic::AtomicBool>) -> Result<(), String> {
    m.check_bind()?;
    let m = Arc::new(m);
    let handler: Arc<http::Handler> = {
        let m = m.clone();
        Arc::new(move |req: &Req| {
            let (src, token) = (LOGS.get(), TOKEN.get());
            match src {
                Some(s) => route(&m, apis::apps(), req, token.and_then(|t| t()).as_deref(), &**s),
                None => Resp::json(503, "{\"error\":\"no log bridge in this process\"}".to_string()),
            }
        })
    };
    let mut wait: u32 = 0;
    std::thread::spawn(move || loop {
        if stop.load(std::sync::atomic::Ordering::SeqCst) {
            return;
        }
        match TcpListener::bind((m.bind, m.port)) {
            Ok(l) => {
                println!("c3-webserver mesh logs on http://{}:{}{}", m.bind, m.port, m.route);
                let mm = m.clone();
                http::listen_with(l, handler, Arc::new(move |ip| mm.is_mesh(ip)), Some(stop.clone()));
                return;
            }
            // #1487 no tight loop while the tunnel is down: the wait doubles up to 30 minutes.
            Err(_) => {
                std::thread::sleep(Duration::from_secs(m.retry_s.saturating_mul(1 << wait.min(6)).min(1800)));
                wait += 1;
            }
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn mesh() -> Mesh {
        Mesh::from_build(&json!({
            "runtime": {"mesh": {"port": 8004}},
            "mesh": {"bind": "10.0.0.9", "cidrs": ["10.0.0.0/24", "10.1.0.0/24"], "retry_s": 5, "logs_route": "/logs", "endpoints_route": "/endpoints",
                     "ipc": {"authority_suffix": "logexport", "default_method": "export", "methods": ["meta", "logs", "crashes", "export"]}}
        }))
        .unwrap()
    }
    fn apps() -> Vec<App> {
        vec![App { id: "calc".into(), name: "Calc".into(), package: "com.x.calc".into(), engines: vec![] }]
    }
    fn get(path: &str, auth: Option<&str>) -> Req {
        let a = auth.map(|t| format!("authorization: {}\r\n", t)).unwrap_or_default();
        http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: 10.0.0.9\r\n{}\r\n", path, a)).unwrap()
    }
    fn stub(authority: &str, method: &str, arg: &str) -> Result<(String, String), String> {
        if authority == "com.x.calc.logexport" {
            Ok(("application/json".into(), format!("{{\"m\":\"{}\",\"a\":\"{}\"}}", method, arg)))
        } else {
            Err("down".into())
        }
    }

    #[test]
    fn an_app_that_may_not_be_woken_is_never_called() {
        // #1502 a provider call starts the app: Account and Store answer 403 and the log source is never reached.
        let mut m = mesh();
        m.no_wake = vec!["com.x.calc".into()];
        let a = apps();
        let spy = |_: &str, _: &str, _: &str| -> Result<(String, String), String> { panic!("a no-wake app must not be called") };
        assert_eq!(route(&m, &a, &get("/logs/calc", Some("Bearer tok")), Some("tok"), &spy).status, 403);
        assert_eq!(route(&m, &a, &get("/logs/com.x.calc?method=meta", Some("Bearer tok")), Some("tok"), &spy).status, 403);
    }

    #[test]
    fn every_path_needs_the_bearer() {
        let (m, a) = (mesh(), apps());
        for path in ["/logs", "/logs/calc", "/nope", "/"] {
            assert_eq!(route(&m, &a, &get(path, None), Some("tok"), &stub).status, 401, "{} without a bearer", path);
            assert_eq!(route(&m, &a, &get(path, Some("Bearer wrong")), Some("tok"), &stub).status, 401, "{} with a wrong bearer", path);
            assert_eq!(route(&m, &a, &get(path, Some("tok")), Some("tok"), &stub).status, 401, "{} without the Bearer scheme", path);
        }
        let r = route(&m, &a, &get("/logs/calc", None), Some("tok"), &stub);
        assert!(r.headers.iter().any(|(k, v)| k == "www-authenticate" && v == "Bearer"));
    }

    #[test]
    fn the_endpoint_pages_are_bearer_guarded_too() {
        let (m, a) = (mesh(), apps());
        for k in ["public", "mesh"] {
            assert_eq!(route(&m, &a, &get(&format!("/endpoints/{}", k), None), Some("tok"), &stub).status, 401, "{} without a bearer", k);
            let ok = route(&m, &a, &get(&format!("/endpoints/{}", k), Some("Bearer tok")), Some("tok"), &stub);
            assert_eq!(ok.status, 200);
            assert!(String::from_utf8_lossy(&ok.body).contains("Endpoints"), "{}", k);
        }
        assert_eq!(route(&m, &a, &get("/endpoints/other", Some("Bearer tok")), Some("tok"), &stub).status, 404);
    }

    #[test]
    fn with_no_token_known_it_fails_closed() {
        assert_eq!(route(&mesh(), &apps(), &get("/logs", Some("Bearer anything")), None, &stub).status, 503);
        assert_eq!(route(&mesh(), &apps(), &get("/logs", Some("Bearer ")), Some(""), &stub).status, 503);
    }

    #[test]
    fn a_good_bearer_routes_to_the_apps_ipc() {
        let (m, a) = (mesh(), apps());
        let idx = route(&m, &a, &get("/logs", Some("Bearer tok")), Some("tok"), &stub);
        assert_eq!(idx.status, 200);
        assert!(String::from_utf8_lossy(&idx.body).contains("com.x.calc.logexport"));
        let r = route(&m, &a, &get("/logs/calc?n=50&errors=1&crashes=2", Some("Bearer tok")), Some("tok"), &stub);
        assert_eq!(r.status, 200);
        assert_eq!(String::from_utf8_lossy(&r.body), "{\"m\":\"export\",\"a\":\"n=50&errors=1&crashes=2\"}");
        let by_pkg = route(&m, &a, &get("/logs/com.x.calc?method=meta", Some("Bearer tok")), Some("tok"), &stub);
        assert_eq!(String::from_utf8_lossy(&by_pkg.body), "{\"m\":\"meta\",\"a\":\"\"}");
    }

    #[test]
    fn only_manifest_apps_and_declared_methods_are_called() {
        let (m, a) = (mesh(), apps());
        let called = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let seen = called.clone();
        let spy = move |_: &str, _: &str, _: &str| -> Result<(String, String), String> {
            seen.store(true, std::sync::atomic::Ordering::SeqCst);
            Ok(("".into(), "{}".into()))
        };
        assert_eq!(route(&m, &a, &get("/logs/unknown.pkg", Some("Bearer tok")), Some("tok"), &spy).status, 404);
        assert_eq!(route(&m, &a, &get("/logs/calc?method=shell", Some("Bearer tok")), Some("tok"), &spy).status, 400);
        assert_eq!(route(&m, &a, &get("/logs/..%2F..", Some("Bearer tok")), Some("tok"), &spy).status, 404);
        assert!(!called.load(std::sync::atomic::Ordering::SeqCst), "no provider was called for a refused request");
        assert_eq!(route(&m, &a, &get("/other", Some("Bearer tok")), Some("tok"), &spy).status, 404);
    }

    fn post(path: &str, auth: Option<&str>, body: &str) -> Req {
        let a = auth.map(|t| format!("authorization: {}\r\n", t)).unwrap_or_default();
        let mut r = http::parse_request(&format!("POST {} HTTP/1.1\r\nhost: 10.0.0.9\r\n{}content-length: {}\r\n\r\n", path, a, body.len())).unwrap();
        r.body = body.as_bytes().to_vec();
        r
    }

    #[test]
    fn browser_routes_are_bearer_gated_and_call_only_the_browser_provider() {
        let (m, a) = (mesh(), apps());
        for (path, body) in [("/browser/command", "{\"cmd\":\"open\"}"), ("/browser/run", "{}"), ("/browser/stop", ""), ("/browser/cdp", "{\"on\":true}")] {
            assert_eq!(route(&m, &a, &post(path, None, body), Some("tok"), &stub).status, 401, "{} without a bearer", path);
            assert_eq!(route(&m, &a, &post(path, Some("Bearer wrong"), body), Some("tok"), &stub).status, 401, "{} with a wrong bearer", path);
        }
        assert_eq!(route(&m, &a, &get("/browser/status", None), Some("tok"), &stub).status, 401);
        assert_eq!(route(&m, &a, &post("/browser/command", Some("Bearer tok"), "{}"), None, &stub).status, 503, "no token known fails closed");
        let seen = std::sync::Mutex::new(vec![]);
        let spy = |auth: &str, method: &str, arg: &str| -> Result<(String, String), String> {
            seen.lock().unwrap().push((auth.to_string(), method.to_string(), arg.to_string()));
            Ok(("application/json".into(), "{\"ok\":true}".into()))
        };
        assert_eq!(route(&m, &a, &post("/browser/command", Some("Bearer tok"), "{\"cmd\":\"html\"}"), Some("tok"), &spy).status, 200);
        assert_eq!(route(&m, &a, &post("/browser/run", Some("Bearer tok"), "{\"steps\":[]}"), Some("tok"), &spy).status, 200);
        assert_eq!(route(&m, &a, &get("/browser/status", Some("Bearer tok")), Some("tok"), &spy).status, 200);
        assert_eq!(route(&m, &a, &post("/browser/cdp", Some("Bearer tok"), "{\"on\":true}"), Some("tok"), &spy).status, 200);
        assert_eq!(route(&m, &a, &post("/browser/cdp", Some("Bearer tok"), "{\"on\":false}"), Some("tok"), &spy).status, 200);
        let s = seen.lock().unwrap().clone();
        assert!(s.iter().all(|x| x.0 == "com.diegonmarcos.cloudbrowser.headless"), "only the Cloud-Browser provider: {:?}", s);
        assert_eq!(s.iter().map(|x| x.1.as_str()).collect::<Vec<_>>(), ["command", "run", "status", "cdp_on", "cdp_off"]);
        assert_eq!(s[0].2, "{\"cmd\":\"html\"}");
    }

    #[test]
    fn browser_wrong_verb_unknown_path_and_dead_phone() {
        let (m, a) = (mesh(), apps());
        assert_eq!(route(&m, &a, &get("/browser/command", Some("Bearer tok")), Some("tok"), &stub).status, 405);
        assert_eq!(route(&m, &a, &post("/browser/status", Some("Bearer tok"), ""), Some("tok"), &stub).status, 405);
        assert_eq!(route(&m, &a, &post("/browser/shell", Some("Bearer tok"), ""), Some("tok"), &stub).status, 404);
        assert_eq!(route(&m, &a, &post("/browsers", Some("Bearer tok"), ""), Some("tok"), &stub).status, 404);
        assert_eq!(route(&m, &a, &post("/browser/command", Some("Bearer tok"), "{}"), Some("tok"), &stub).status, 502, "the stub knows no headless authority");
    }

    #[test]
    fn a_dead_app_is_a_502_not_a_hang() {
        let m = mesh();
        let a = vec![App { id: "gone".into(), name: "G".into(), package: "com.gone".into(), engines: vec![] }];
        assert_eq!(route(&m, &a, &get("/logs/gone", Some("Bearer tok")), Some("tok"), &stub).status, 502);
    }

    #[test]
    fn the_bind_is_never_public_loopback_or_wildcard() {
        let base = |bind: &str, cidr: &str| {
            Mesh::from_build(&json!({"runtime": {"mesh": {"port": 8004}}, "mesh": {"bind": bind, "cidrs": [cidr], "logs_route": "/logs",
                "ipc": {"authority_suffix": "logexport", "default_method": "export", "methods": ["export"]}}}))
        };
        assert!(base("10.0.0.9", "10.0.0.0/24").is_ok());
        assert!(base("0.0.0.0", "0.0.0.0/1").is_err(), "wildcard");
        assert!(base("127.0.0.1", "127.0.0.0/8").is_err(), "loopback");
        assert!(base("203.0.113.7", "10.0.0.0/24").is_err(), "a public address outside the mesh subnets");
        assert!(base("10.0.0.9", "0.0.0.0/0").is_err(), "a /0 subnet");
        assert!(base("not-an-ip", "10.0.0.0/24").is_err());
    }

    #[test]
    fn only_mesh_peers_are_answered() {
        let m = mesh();
        assert!(m.is_mesh("10.0.0.1".parse().unwrap()));
        assert!(m.is_mesh("10.1.0.200".parse().unwrap()));
        assert!(!m.is_mesh("10.2.0.1".parse().unwrap()));
        assert!(!m.is_mesh("127.0.0.1".parse().unwrap()));
        assert!(!m.is_mesh("192.168.1.5".parse().unwrap()));
        assert!(!m.is_mesh("fd0c:1d00::9".parse().unwrap()));
    }

    #[test]
    fn the_real_declaration_is_a_valid_mesh_listener() {
        let m = Mesh::from_build(apis::build()).expect("build.json::mesh");
        assert!(m.is_mesh(IpAddr::V4(m.bind)));
        assert_ne!(m.port, 8002);
        assert!(m.methods.iter().any(|x| x == "export"));
    }

    #[test]
    fn constant_time_compare_agrees_with_equality() {
        assert!(ct_eq(b"abc", b"abc"));
        assert!(!ct_eq(b"abc", b"abd"));
        assert!(!ct_eq(b"abc", b"ab"));
        assert!(!ct_eq(b"", b"a"));
    }
}
