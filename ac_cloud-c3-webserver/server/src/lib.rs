//! c3-webserver — the phone's local web servers, natively on Android.
//!
//! [`start`] binds two loopback listeners and a DNS socket:
//!   * the emulated drive (build.json runtime.port, 8002): the SHARED web server of the fleet
//!     (ab_cloud-libs-shared/libs/fleet-webserver) rooted at /storage/emulated/0 and nothing else. Its
//!     UI is the Linux my-webserver frontend, bundled byte for byte by that crate; there is no
//!     page, route, script or style of the drive in this crate. The app's own pages (five tabs, link chooser)
//!     live under `/__app__/` only (app.rs) and never shadow a path of the shared server;
//!   * the fleet-diagnostics page (runtime.diag.port, 8003): the SuperApp's About, Logcat and
//!     Diagnose, read through its bridge (diag.rs);
//!   * the private DNS (serving.dns, udp 127.0.0.1:5354) for the four names;
//!   * the mesh listener (build.json::mesh, runtime.mesh.port): `/logs/<app>` routes each app's log export onto the mesh, bound to
//!     the mesh address only and bearer-guarded (meshlogs.rs).
//!
//! Every listener binds 127.0.0.1 only and refuses every other peer, and answers GET and HEAD. The
//! Tauri app (../src-tauri) calls [`start`] from its own process before it opens the window on the
//! drive's URL, and so does the app's keep-alive foreground service (started at boot, after an update and by the
//! browser when the DNS does not answer); [`start`] is idempotent, so whichever comes first binds and the other is a no-op.

pub mod about;
pub mod apis;
pub mod app;
pub mod crashes;
pub mod diag;
pub mod dns;
pub mod engines;
pub mod meshlogs;
pub mod power;
pub mod repos;
pub mod serving;
pub mod sub;

use fleet_webserver::http::{self, Req, Resp};
use fleet_webserver::render::jstr;
use std::net::{Ipv4Addr, TcpListener};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Instant;

/// What the :8003 handlers know about their listener.
pub struct Ctx {
    /// The served root (the diagnostics listener serves none; it carries the drive's for About).
    pub root: std::path::PathBuf,
    pub port: u16,
    pub started: Instant,
    pub binary: String,
}

/// The names this server answers to besides loopback: the four private DNS names.
fn private_names() -> Vec<String> {
    serving::get().endpoints.iter().map(|e| e.name.clone()).collect()
}

/// The :8003 listener: the diagnostics page and its three bridge reads, plus liveness and the
/// served-ports map the page's header draws. It serves no files.
pub fn handle_diag(ctx: &Ctx, req: &Req) -> Resp {
    if let Some(h) = req.header("host") {
        let host = http::host_of(h);
        if !(matches!(host.as_str(), "127.0.0.1" | "localhost" | "[::1]") || private_names().iter().any(|n| *n == host)) {
            return Resp::text(403, "Forbidden host: this server answers 127.0.0.1, localhost and its four private names");
        }
    }
    let p = req.path.trim_end_matches('/');
    match if p.is_empty() { "/__diag__" } else { p } {
        "/__diag__" => diag::page(ctx, req),
        "/__api__/health" => Resp::json(
            200,
            format!("{{\"ok\":true,\"pid\":{},\"uptime_s\":{},\"port\":{},\"root\":null}}", std::process::id(), ctx.started.elapsed().as_secs(), ctx.port),
        ),
        "/__api__/serving" => Resp::json(200, serving::json(true)),
        "/__api__/diag/about" => diag::api_about(ctx, req),
        "/__api__/diag/logcat" => diag::api_logcat(ctx, req),
        "/__api__/diag/diagnose" => diag::api_diagnose(ctx, req),
        _ => Resp::text(404, "Not found"),
    }
}

fn loopback() -> Arc<http::PeerCheck> {
    Arc::new(|ip: std::net::IpAddr| ip.is_loopback())
}

fn host_allowed(host: &str) -> bool {
    matches!(host, "127.0.0.1" | "localhost" | "[::1]") || private_names().iter().any(|n| n == host)
}

/// The drive listener: `/__app__/...` is the app shell, everything else is the shared fleet-webserver.
pub fn drive_handler(ctx: Ctx, files: Arc<http::Handler>) -> Arc<http::Handler> {
    Arc::new(move |req: &Req| app::handle(&ctx, req, &host_allowed).unwrap_or_else(|| files(req)))
}

/// What the app hands the server. The ports come from build.json (build.rs), never from code.
#[derive(Clone)]
pub struct Config {
    pub drive_port: u16,
    pub diag_port: u16,
    pub root: String,
}

/// Bind the drive listener (a failure here is the Err), then the diagnostics listener and the DNS socket (a
/// failure there is logged and the rest keeps serving). Returns once the drive socket is listening, so a caller
/// that opens a webview on its URL next never races the bind.
///
/// The root is NOT required to be readable yet: on Android the storage grant arrives after the app has started,
/// and every request resolves its path afresh, so a root that becomes readable later is served from then on.
pub fn start(cfg: Config) -> Result<(), String> {
    // #1502 every caller (the activity, the keep-alive service, an outside start request) lands here: Server OFF binds nothing.
    *CFG.lock().map_err(|_| "poisoned")? = Some(cfg.clone());
    if !power::plan(power::current().sw).listeners {
        println!("c3-webserver: Server is OFF, nothing bound");
        return Ok(());
    }
    // One server per process: the keep-alive service (src-tauri) and the activity both call this, in either order.
    let stop = {
        let mut run = RUN.lock().map_err(|_| "poisoned")?;
        if run.is_some() {
            return Ok(());
        }
        let s = Arc::new(AtomicBool::new(false));
        *run = Some(s.clone());
        s
    };
    let r = start_once(cfg, stop);
    if r.is_err() {
        stop_all();
    }
    r
}

static CFG: Mutex<Option<Config>> = Mutex::new(None);
/// The stop flag of the running generation of listeners; None while nothing is bound.
static RUN: Mutex<Option<Arc<AtomicBool>>> = Mutex::new(None);

pub fn running() -> bool {
    RUN.lock().map(|r| r.is_some()).unwrap_or(false)
}

/// #1502 Server OFF: end every listener (drive, diagnostics, mesh, DNS, every Repos site) so every port is released.
pub fn stop_all() {
    if let Some(s) = RUN.lock().ok().and_then(|mut r| r.take()) {
        s.store(true, Ordering::SeqCst);
    }
    repos::host().stop_all();
}

/// Server ON: bind again with the config the app started with (a no-op when nothing ever called [`start`] in this process).
pub fn resume() -> Result<(), String> {
    let cfg = CFG.lock().map_err(|_| "poisoned")?.clone();
    match cfg {
        Some(c) => start(c),
        None => Ok(()),
    }
}

/// Bind with a few short retries: a listener that was just stopped releases its port within one accept poll.
fn bind_retry(port: u16) -> std::io::Result<TcpListener> {
    let mut last = None;
    for _ in 0..10 {
        match TcpListener::bind((Ipv4Addr::LOCALHOST, port)) {
            Ok(l) => return Ok(l),
            Err(e) => last = Some(e),
        }
        std::thread::sleep(std::time::Duration::from_millis(60));
    }
    Err(last.unwrap())
}

fn start_once(cfg: Config, stop: Arc<AtomicBool>) -> Result<(), String> {
    let sv = serving::get();
    if cfg.drive_port != sv.drive_port || cfg.diag_port != sv.diag_port {
        return Err(format!(
            "the app was built with ports {}/{} but build.json::serving declares {}/{}",
            cfg.drive_port, cfg.diag_port, sv.drive_port, sv.diag_port
        ));
    }
    let drive = bind_retry(cfg.drive_port).map_err(|e| format!("cannot bind 127.0.0.1:{} ({})", cfg.drive_port, e))?;
    app::check_table()?;
    app::links()?;
    let web = fleet_webserver::Config { port: cfg.drive_port, root: cfg.root.clone(), extra_hosts: private_names() };
    println!("c3-webserver {} on http://127.0.0.1:{}/ root={} pid={}", env!("CARGO_PKG_VERSION"), cfg.drive_port, cfg.root, std::process::id());
    let binary = std::env::current_exe().map(|p| p.to_string_lossy().to_string()).unwrap_or_default();
    let dctx = Ctx { root: std::path::PathBuf::from(&cfg.root), port: cfg.drive_port, started: Instant::now(), binary: binary.clone() };
    http::listen_with(drive, drive_handler(dctx, fleet_webserver::handler(&web)), loopback(), Some(stop.clone()));

    match bind_retry(cfg.diag_port) {
        Ok(l) => {
            println!("c3-webserver diagnostics on http://127.0.0.1:{}/", cfg.diag_port);
            let ctx = Ctx { root: std::path::PathBuf::from(&cfg.root), port: cfg.diag_port, started: Instant::now(), binary };
            http::listen_with(l, Arc::new(move |req: &Req| handle_diag(&ctx, req)), loopback(), Some(stop.clone()));
        }
        Err(e) => eprintln!("c3-webserver: diagnostics listener not started: cannot bind 127.0.0.1:{} ({})", cfg.diag_port, e),
    }
    // #1490 the mesh listener: /logs/<app>, mesh peers + fleet bearer only; a failure here never stops the rest.
    match meshlogs::Mesh::from_build(apis::build()).and_then(|m| meshlogs::start(m, stop.clone())) {
        Ok(()) => {}
        Err(e) => eprintln!("c3-webserver: mesh listener not started: {}", e),
    }
    if let Err(e) = dns::start(stop.clone()) {
        eprintln!("c3-webserver: private DNS not started: {}", e);
    }
    // #1502 ON starts the enabled Repos sites too.
    if let Ok(rc) = repos::cfg() {
        if let Ok(entries) = repos::load(&rc.store) {
            for id in power::current().sites {
                if let Some(e) = entries.iter().find(|e| e.id == id) {
                    if let Err(er) = repos::host().start(rc, e) {
                        eprintln!("c3-webserver: site {} not started: {}", id, er);
                    }
                }
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};

    fn diag_get(path: &str, host: &str) -> Resp {
        let ctx = Ctx { root: std::env::temp_dir(), port: 8003, started: Instant::now(), binary: "test".to_string() };
        let req = http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: {}\r\n\r\n", path, host)).unwrap();
        handle_diag(&ctx, &req)
    }

    #[test]
    fn diagnostics_listener_serves_diagnostics_only() {
        assert!(String::from_utf8_lossy(&diag_get("/", "127.0.0.1:8003").body).contains("Diagnose"));
        assert_eq!(diag_get("/__diag__", "cloud.app:8003").status, 200);
        assert_eq!(diag_get("/__api__/health", "127.0.0.1").status, 200);
        assert_eq!(diag_get("/__api__/serving", "127.0.0.1").status, 200);
        for p in ["/some/file.txt", "/__api__/ls", "/__api__/read", "/__api__/search", "/__browse__", "/__lib__/marked.min.js", "/__api__/engines", "/__tab__/files"] {
            assert_eq!(diag_get(p, "127.0.0.1").status, 404, "{} must not be served on the diagnostics port", p);
        }
        assert_eq!(diag_get("/", "evil.example").status, 403, "DNS rebinding is refused");
    }

    /// The drive is the shared server and nothing else: its answer for `/` is the shared lib's browse page.
    #[test]
    fn the_drive_is_the_shared_server() {
        let root = std::env::temp_dir().join(format!("c3ws-drive-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let port = fleet_webserver::start(fleet_webserver::Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts: private_names() }).unwrap();
        let mut s = std::net::TcpStream::connect((Ipv4Addr::LOCALHOST, port)).unwrap();
        write!(s, "GET / HTTP/1.1\r\nhost: emulated.app\r\nconnection: close\r\n\r\n").unwrap();
        let mut out = String::new();
        s.read_to_string(&mut out).unwrap();
        assert!(out.starts_with("HTTP/1.1 200"), "{}", &out[..out.len().min(200)]);
        let body = &out[out.find("\r\n\r\n").unwrap() + 4..];
        assert_eq!(body, fleet_webserver::render::browse_page("/"), "/ on the drive is the Linux frontend, byte for byte, and nothing else");
        for custom in ["<nav", "__tab__", "__app__", "/__api__/routes", "Engines", "Configs"] {
            assert!(!body.contains(custom), "the drive's root page carries custom UI: {}", custom);
        }
        let _ = std::fs::remove_dir_all(&root);
    }

    /// The composed drive listener: `/` stays the shared Linux UI byte for byte, `/__app__/` is the shell.
    #[test]
    fn the_composed_drive_keeps_the_file_view_and_adds_the_shell() {
        let root = std::env::temp_dir().join(format!("c3ws-compose-{}", std::process::id()));
        std::fs::create_dir_all(&root).unwrap();
        let web = fleet_webserver::Config { port: 0, root: root.to_string_lossy().to_string(), extra_hosts: private_names() };
        let h = drive_handler(Ctx { root: root.clone(), port: 8002, started: Instant::now(), binary: "t".to_string() }, fleet_webserver::handler(&web));
        let get = |p: &str| h(&http::parse_request(&format!("GET {} HTTP/1.1\r\nhost: emulated.app\r\n\r\n", p)).unwrap());
        assert_eq!(String::from_utf8_lossy(&get("/").body), fleet_webserver::render::browse_page("/"), "the file view is unchanged");
        let shell = get("/__app__/");
        assert_eq!(shell.status, 200);
        assert!(String::from_utf8_lossy(&shell.body).contains("id=\"nav\""));
        assert_eq!(get("/__api__/ls?path=/").status, 200, "the shared API is still the shared server's");
        assert_eq!(get("/__app__/api/routes").status, 200);
        let _ = std::fs::remove_dir_all(&root);
    }

    #[test]
    fn jstr_is_the_shared_one() {
        assert_eq!(jstr("a\"b"), "\"a\\\"b\"");
    }
}
