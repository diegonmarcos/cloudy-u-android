//! #1490 the MESH LISTENER: `/logs/<app>` routes each fleet app's log export onto the mesh.
//!
//! Cloud-Webserver calls the app's `<package>.logexport` provider (libs:log-export, contract in
//! ab_cloud-libs-shared/libs/devtools .../logexport/LogExportContract.kt) and relays its JSON. The provider call is JNI and belongs to
//! the Tauri crate, which installs it with [`set_log_source`] / [`set_token_source`]; this crate stays platform-free and its tests
//! run against stubs.
//!
//! Exposure, fail closed (never public):
//!   * it binds EVERY mesh address of the phone (#1845: one socket per local IPv4 inside `mesh.cidrs`, the phone holds one per mesh,
//!     10.0.0.x and 10.1.0.x), re-reads the addresses every few seconds and binds / drops sockets as they come and go; build.json's
//!     build-time mesh.bind is only the declared fallback for display and the bind guard) and refuses to
//!     start unless that address lies inside `mesh.cidrs`; 0.0.0.0, loopback and any public address are refused;
//!   * it answers only peers inside `mesh.cidrs`;
//!   * EVERY path needs `Authorization: Bearer <fleet token>` (the token THIS app owns and stores encrypted, #1835), compared in constant time; with no
//!     token known the answer is 503, never open.

use crate::apis::{self, App};
use fleet_webserver::http::{self, Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::Value;
use std::net::{IpAddr, Ipv4Addr, TcpListener};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

/// #1845 the mesh addresses the listener holds a socket on RIGHT NOW (Home, the Mesh Endpoints page and the status ports read it).
static BOUND: Mutex<Vec<Ipv4Addr>> = Mutex::new(Vec::new());

pub fn bound_addrs() -> Vec<Ipv4Addr> {
    BOUND.lock().map(|b| b.clone()).unwrap_or_default()
}

fn set_bound(v: Vec<Ipv4Addr>) {
    if let Ok(mut b) = BOUND.lock() {
        *b = v;
    }
}

/// (authority, method, arg) -> Ok((mime, payload)) | Err(reason): ContentResolver.call(content://authority, method, arg).
pub type LogSource = dyn Fn(&str, &str, &str) -> Result<(String, String), String> + Send + Sync;
/// The fleet bearer token THIS app owns (#1835: Configs > Fleet token, Keystore-encrypted), or None when none is set.
pub type TokenSource = dyn Fn() -> Option<String> + Send + Sync;

static LOGS: OnceLock<Box<LogSource>> = OnceLock::new();
static TOKEN: OnceLock<Box<TokenSource>> = OnceLock::new();

pub fn set_log_source(f: Box<LogSource>) -> bool {
    LOGS.set(f).is_ok()
}
pub fn set_token_source(f: Box<TokenSource>) -> bool {
    TOKEN.set(f).is_ok()
}

/// What the listener authorises against: the installed token source (this app's own owner provider), nothing else.
pub fn current_token() -> Option<String> {
    TOKEN.get().and_then(|t| t())
}

/// #1835 the content URI of the token's OWNER, this app's own provider (`<own package>.<suffix>`), never the SuperApp's.
pub fn owner_token_target(own_package: &str, suffix: &str) -> String {
    format!("content://{}.{}/token", own_package, suffix)
}

#[derive(Clone, Debug)]
pub struct Mesh {
    pub bind: Ipv4Addr,
    pub port: u16,
    pub cidrs: Vec<(Ipv4Addr, u8)>,
    pub retry_s: u64,
    pub route: String,
    /// #1495/#1580 the UNIFIED holder prefix: `<ipc_route>/<app>/<route>` fronts every fleet app's IPC through one gateway
    /// with centralized per-peer, per-route ACL (ipc.rs, data/api-ipc-map.json). The per-app routes below are retired behind it.
    pub ipc_route: String,
    /// `<endpoints_route>/public` and `/mesh`: the API tab's Public and Mesh Endpoints pages, for cloudy-c3-mcp.
    pub endpoints_route: String,
    pub suffix: String,
    pub default_method: String,
    pub methods: Vec<String>,
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
            ipc_route: m["ipc_route"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).unwrap_or("/ipc").trim_end_matches('/').to_string(),
            endpoints_route: m["endpoints_route"].as_str().filter(|r| r.starts_with('/') && r.len() > 1).unwrap_or("/endpoints").trim_end_matches('/').to_string(),
            suffix: m["ipc"]["authority_suffix"].as_str().filter(|x| !x.is_empty()).ok_or("mesh.ipc.authority_suffix missing")?.to_string(),
            default_method: m["ipc"]["default_method"].as_str().unwrap_or("export").to_string(),
            methods: strs(&m["ipc"]["methods"]),
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

    /// #1834 the address to bind NOW: the phone's OWN mesh address. `bind` is resolved at build time from ONE declared WireGuard
    /// profile, so every phone's build carried that one phone's address (10.0.0.9) and a phone holding another mesh address
    /// (10.0.0.10) retried a bind that could never succeed: its mesh API never came up. The first local IPv4 inside the mesh
    /// subnets wins; the declared address is only the fallback. Never anything outside `cidrs`.
    pub fn live_bind(&self) -> Ipv4Addr {
        pick_bind(self.bind, &local_ipv4(), |ip| self.is_mesh(IpAddr::V4(ip)))
    }

    /// #1845 EVERY mesh address of this phone now (one per mesh), in interface order; empty while the tunnel is down.
    pub fn live_binds(&self) -> Vec<Ipv4Addr> {
        mesh_addrs(&local_ipv4(), |ip| self.is_mesh(IpAddr::V4(ip)))
    }

    /// #1845 what to show: the addresses actually bound, else the live mesh addresses, else the declared fallback.
    pub fn shown_binds(&self) -> Vec<Ipv4Addr> {
        let b = bound_addrs();
        if !b.is_empty() {
            return b;
        }
        let l = self.live_binds();
        if l.is_empty() { vec![self.bind] } else { l }
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

/// Pure: the bind address from the phone's local IPv4 addresses: the first one inside the mesh, else the declared one.
pub fn pick_bind(declared: Ipv4Addr, local: &[Ipv4Addr], is_mesh: impl Fn(Ipv4Addr) -> bool) -> Ipv4Addr {
    local.iter().copied().find(|ip| is_mesh(*ip)).unwrap_or(declared)
}

/// Pure (#1845): every local address inside the mesh, de-duplicated, in order.
pub fn mesh_addrs(local: &[Ipv4Addr], is_mesh: impl Fn(Ipv4Addr) -> bool) -> Vec<Ipv4Addr> {
    let mut out: Vec<Ipv4Addr> = Vec::new();
    for ip in local.iter().copied().filter(|ip| is_mesh(*ip)) {
        if !out.contains(&ip) {
            out.push(ip);
        }
    }
    out
}

/// Pure (#1845): (to bind, to drop) from the addresses wanted now and the ones already held.
pub fn plan_binds(want: &[Ipv4Addr], held: &[Ipv4Addr]) -> (Vec<Ipv4Addr>, Vec<Ipv4Addr>) {
    (want.iter().copied().filter(|a| !held.contains(a)).collect(), held.iter().copied().filter(|a| !want.contains(a)).collect())
}

/// Every local IPv4 address of this device (getifaddrs); empty when the call fails.
pub fn local_ipv4() -> Vec<Ipv4Addr> {
    let mut out = Vec::new();
    unsafe {
        let mut ifap: *mut libc::ifaddrs = std::ptr::null_mut();
        if libc::getifaddrs(&mut ifap) != 0 {
            return out;
        }
        let mut p = ifap;
        while !p.is_null() {
            let a = (*p).ifa_addr;
            if !a.is_null() && (*a).sa_family as i32 == libc::AF_INET {
                let sin = &*(a as *const libc::sockaddr_in);
                out.push(Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr)));
            }
            p = (*p).ifa_next;
        }
        libc::freeifaddrs(ifap);
    }
    out
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
        None => return Some(Resp::json(503, format!("{{\"error\":{}}}", jstr("no fleet token is set (Cloud C3 Webserver > Configs > Fleet token)")))),
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
    // #1835 the MCP endpoint of this listener, after the bearer gate: it lists this phone's tools and runs them as internal GETs.
    if p == crate::meshmcp::MCP_PATH {
        return crate::meshmcp::handle(req, &|r| route(m, apps, r, token, src));
    }
    // #1495/#1580 the UNIFIED holder, after the bearer gate: /ipc/<app>/<route>, dispatched through the declared registry with
    // centralized per-peer, per-route ACL (ipc.rs). #1580 the per-app /browser and /shell endpoints are now served ONLY here;
    // /logs is still served below too (kept until its mesh_page row moves to the gateway).
    if let Some(rest) = p.strip_prefix(m.ipc_route.as_str()).filter(|r| r.is_empty() || r.starts_with('/')) {
        return match crate::ipc::registry() {
            Ok(reg) => match crate::ipc::resolve_peer(reg, req.header("x-c3-peer")) {
                Ok(peer) => crate::ipc::gateway(reg, apps, rest, req, &peer, &m.no_wake, src),
                Err(resp) => resp,
            },
            Err(e) => Resp::json(500, format!("{{\"error\":{}}}", jstr(e))),
        };
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

/// Bind the mesh address (retrying while the tunnel is down) and serve [`route`] on it, mesh peers only.
pub fn start(m: Mesh, stop: Arc<std::sync::atomic::AtomicBool>) -> Result<(), String> {
    m.check_bind()?;
    let m = Arc::new(m);
    let handler: Arc<http::Handler> = {
        let m = m.clone();
        Arc::new(move |req: &Req| {
            let src = LOGS.get();
            // #1836 the mesh log export is part of the API service
            if let Some(r) = crate::gate(crate::power::Service::Api, &crate::power::enabled_pair()) {
                return r;
            }
            match src {
                Some(s) => route(&m, apis::apps(), req, current_token().as_deref(), &**s),
                None => Resp::json(503, "{\"error\":\"no log bridge in this process\"}".to_string()),
            }
        })
    };
    // #1845 one supervisor: a socket per mesh address (each its own stop flag), re-read every 3 s so a tunnel that comes up, goes down
    // or changes address is followed; the peer filter and the bearer gate are the same on every socket.
    std::thread::spawn(move || {
        let mut held: Vec<(Ipv4Addr, Arc<AtomicBool>)> = Vec::new();
        loop {
            if stop.load(Ordering::SeqCst) {
                for (_, f) in &held {
                    f.store(true, Ordering::SeqCst);
                }
                set_bound(vec![]);
                return;
            }
            let want = m.live_binds();
            let have: Vec<Ipv4Addr> = held.iter().map(|(a, _)| *a).collect();
            let (add, drop) = plan_binds(&want, &have);
            held.retain(|(a, f)| {
                if drop.contains(a) {
                    f.store(true, Ordering::SeqCst);
                    println!("c3-webserver mesh logs released {}:{}", a, m.port);
                    false
                } else {
                    true
                }
            });
            for addr in add {
                if let Ok(l) = TcpListener::bind((addr, m.port)) {
                    println!("c3-webserver mesh logs on http://{}:{}{}", addr, m.port, m.route);
                    let flag = Arc::new(AtomicBool::new(false));
                    let (mm, f2) = (m.clone(), flag.clone());
                    http::listen_with(l, handler.clone(), Arc::new(move |ip| mm.is_mesh(ip)), Some(f2));
                    held.push((addr, flag));
                }
            }
            set_bound(held.iter().map(|(a, _)| *a).collect());
            std::thread::sleep(Duration::from_secs(3));
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
    fn the_mcp_endpoint_needs_the_bearer_and_lists_tools() {
        // #1835 /mcp is behind the same gate as every other path (and the listener itself binds the mesh address only).
        let (m, a) = (mesh(), apps());
        assert_eq!(route(&m, &a, &post("/mcp", None, "{}"), Some("tok"), &stub).status, 401);
        assert_eq!(route(&m, &a, &post("/mcp", Some("Bearer wrong"), "{}"), Some("tok"), &stub).status, 401);
        assert_eq!(route(&m, &a, &post("/mcp", Some("Bearer x"), "{}"), None, &stub).status, 503);
        let ok = route(&m, &a, &post("/mcp", Some("Bearer tok"), "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), Some("tok"), &stub);
        assert_eq!(ok.status, 200);
        assert!(String::from_utf8_lossy(&ok.body).contains("endpoints_mesh"));
        let call = route(&m, &a, &post("/mcp", Some("Bearer tok"), "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"endpoints_mesh\"}}"), Some("tok"), &stub);
        assert!(String::from_utf8_lossy(&call.body).contains("Endpoints"));
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
    fn the_listener_uses_the_webserver_own_token() {
        // #1835 the installed source is the webserver's own owner provider, addressed by its own package, not the SuperApp's.
        let target = owner_token_target("com.diegonmarcos.cloudwebserver", "fleettoken");
        assert_eq!(target, "content://com.diegonmarcos.cloudwebserver.fleettoken/token");
        assert!(!target.contains("superapp"));
        assert!(set_token_source(Box::new(|| Some("webserver-token".to_string()))));
        assert_eq!(current_token().as_deref(), Some("webserver-token"));
        assert!(authorize(Some("Bearer webserver-token"), current_token().as_deref()).is_none());
        assert_eq!(authorize(Some("Bearer superapp-token"), current_token().as_deref()).unwrap().status, 401);
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

    #[test]
    fn every_mesh_address_of_the_phone_is_wanted_not_just_the_first() {
        // #1845 the phone holds 10.0.0.10/24 and 10.1.0.12/24: the old first-match bound one, and a peer reaching the other got ECONNREFUSED.
        let m = mesh();
        let local: Vec<Ipv4Addr> = ["127.0.0.1", "192.168.1.20", "10.0.0.10", "10.1.0.12", "10.0.0.10", "10.2.0.5"].iter().map(|s| s.parse().unwrap()).collect();
        let got = mesh_addrs(&local, |ip| m.is_mesh(IpAddr::V4(ip)));
        assert_eq!(got, vec!["10.0.0.10".parse::<Ipv4Addr>().unwrap(), "10.1.0.12".parse().unwrap()], "both meshes, once each, nothing public or loopback");
        assert!(mesh_addrs(&["192.168.1.20".parse().unwrap()], |ip| m.is_mesh(IpAddr::V4(ip))).is_empty(), "tunnel down: nothing to bind");
    }

    #[test]
    fn a_changed_address_set_binds_the_new_and_drops_the_gone() {
        let ip = |s: &str| -> Ipv4Addr { s.parse().unwrap() };
        assert_eq!(plan_binds(&[ip("10.0.0.10"), ip("10.1.0.12")], &[]), (vec![ip("10.0.0.10"), ip("10.1.0.12")], vec![]));
        assert_eq!(plan_binds(&[ip("10.0.0.10"), ip("10.1.0.12")], &[ip("10.0.0.10"), ip("10.1.0.12")]), (vec![], vec![]), "stable: no churn");
        assert_eq!(plan_binds(&[ip("10.0.0.11"), ip("10.1.0.12")], &[ip("10.0.0.10"), ip("10.1.0.12")]), (vec![ip("10.0.0.11")], vec![ip("10.0.0.10")]), "one mesh readdressed");
        assert_eq!(plan_binds(&[], &[ip("10.0.0.10")]), (vec![], vec![ip("10.0.0.10")]), "tunnel down: everything released");
    }

    #[test]
    fn one_socket_per_address_answers_each_with_the_bearer_gate_first() {
        // two loopback-range stand-ins for the two mesh addresses: each socket answers, a non-mesh peer is cut, the bearer is still first.
        let m = Arc::new(Mesh { cidrs: vec![("127.0.0.0".parse().unwrap(), 8)], ..mesh() });
        let handler: Arc<http::Handler> = Arc::new(|req: &Req| route(&mesh(), &apps(), req, Some("tok"), &stub));
        let stop = Arc::new(AtomicBool::new(false));
        let mut ports = vec![];
        for addr in ["127.0.0.1", "127.0.0.2"] {
            let l = TcpListener::bind((addr, 0)).unwrap();
            ports.push((addr, l.local_addr().unwrap().port()));
            let mm = m.clone();
            http::listen_with(l, handler.clone(), Arc::new(move |ip| ip.is_loopback() && mm.cidrs.iter().any(|c| cidr_contains(*c, ip))), Some(stop.clone()));
        }
        for (addr, port) in ports {
            use std::io::{Read, Write};
            let ask = |auth: &str| {
                let mut c = std::net::TcpStream::connect((addr, port)).unwrap();
                c.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
                c.write_all(format!("GET /logs HTTP/1.1\r\nhost: {}\r\n{}connection: close\r\n\r\n", addr, auth).as_bytes()).unwrap();
                let mut s = String::new();
                let _ = c.read_to_string(&mut s);
                s
            };
            assert!(ask("").starts_with("HTTP/1.1 401"), "{}: the bearer gate answers first", addr);
            assert!(ask("authorization: Bearer tok\r\n").starts_with("HTTP/1.1 200"), "{}: a bearer is served", addr);
        }
        stop.store(true, Ordering::SeqCst);
    }

    #[test]
    fn the_bind_is_the_phones_own_mesh_address_not_the_declared_one() {
        let mesh = |ip: Ipv4Addr| ip.octets()[0] == 10 && (ip.octets()[1] == 0 || ip.octets()[1] == 1);
        let declared: Ipv4Addr = "10.0.0.9".parse().unwrap();
        let a37 = ["127.0.0.1".parse().unwrap(), "192.168.1.20".parse().unwrap(), "10.0.0.10".parse().unwrap()];
        assert_eq!(pick_bind(declared, &a37, mesh), "10.0.0.10".parse::<Ipv4Addr>().unwrap());
        let down = ["127.0.0.1".parse().unwrap(), "192.168.1.20".parse().unwrap()];
        assert_eq!(pick_bind(declared, &down, mesh), declared, "tunnel down: the declared address, whose bind then fails and retries");
        assert!(!local_ipv4().is_empty(), "getifaddrs answers on the test host");
    }
}
