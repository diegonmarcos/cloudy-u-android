//! #1502 the two master controls: Server ON/OFF and Keep alive ON/OFF, as one declared state machine.
//!
//! build.json::power declares the store (a JSON file both this crate and the Kotlin keep-alive glue read), the defaults and the
//! decision table (`matrix`); [`plan`] is the rules and the tests hold it equal to the table. Server OFF: no CONTENT listener (the
//! diagnostics page, DNS, the mesh listener, every Repos site, sshd) and the drive answers 503 for everything but `/__app__`;
//! the ADMIN surface (:8002 `/__app__` + `/api/power`) is never unbound (`admin`), so Server ON is always one click away. Keep alive OFF: no foreground service, no sticky
//! restart, no boot receiver, no scheduled wake; the server lives only while the app process does. Only Server ON with Keep
//! alive ON runs the foreground service and the boot autostart.

use crate::apis;
use serde_json::{json, Value};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::sync::{Mutex, OnceLock};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Switches {
    pub server: bool,
    pub keep_alive: bool,
}

/// What a state allows. `wake` (an alarm or a scheduled job) is never allowed in any state.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Plan {
    /// Content listeners (diag, DNS, mesh, sites, sshd, the drive's files). Follows Server.
    pub listeners: bool,
    /// The admin surface (:8002 /__app__ + /api/power) stays bound in EVERY state: the way back on.
    pub admin: bool,
    pub service: bool,
    pub boot: bool,
    pub sticky: bool,
    pub wake: bool,
}

pub fn plan(s: Switches) -> Plan {
    let svc = s.server && s.keep_alive;
    Plan { listeners: s.server, admin: true, service: svc, boot: svc, sticky: svc, wake: false }
}

/// May another app's start request (SuperApp wake, MCP phone tool, log export) bring the server up from outside the UI?
pub fn may_start_from_outside(s: Switches) -> bool {
    plan(s).service
}

pub struct Cfg {
    pub store: PathBuf,
    pub default: Switches,
    pub matrix: Vec<(Switches, Plan)>,
    pub off_url: String,
}

impl Cfg {
    pub fn from_build(b: &Value) -> Result<Cfg, String> {
        let p = b.get("power").ok_or("build.json has no `power` block")?;
        let flag = |v: &Value, k: &str| v[k].as_bool().ok_or(format!("build.json::power: `{}` must be a boolean", k));
        let sw = |v: &Value| Ok::<_, String>(Switches { server: flag(v, "server")?, keep_alive: flag(v, "keep_alive")? });
        let matrix = p["matrix"]
            .as_array()
            .ok_or("build.json::power.matrix must be an array")?
            .iter()
            .map(|r| Ok::<_, String>((sw(r)?, Plan { listeners: flag(r, "listeners")?, admin: flag(r, "admin")?, service: flag(r, "service")?, boot: flag(r, "boot")?, sticky: flag(r, "sticky")?, wake: flag(r, "wake")? })))
            .collect::<Result<Vec<_>, String>>()?;
        Ok(Cfg {
            store: PathBuf::from(p["store"].as_str().ok_or("build.json::power.store is missing")?),
            default: sw(&p["default"])?,
            matrix,
            off_url: p["off_url"].as_str().ok_or("build.json::power.off_url is missing")?.to_string(),
        })
    }
}

pub fn cfg() -> &'static Cfg {
    static C: OnceLock<Cfg> = OnceLock::new();
    C.get_or_init(|| Cfg::from_build(apis::build()).expect("build.json::power is invalid (the tests catch this)"))
}

/// #1836 the services one server process hosts, each with its own enable flag. Server is the master: a service answers only while
/// Server is ON AND its own flag is ON. ADB Shell is not hosted by this process (it has its own owner-side server).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Service {
    Pages,
    Api,
    Sshd,
}

impl Service {
    pub const ALL: [Service; 3] = [Service::Pages, Service::Api, Service::Sshd];
    pub fn name(self) -> &'static str {
        match self {
            Service::Pages => "pages",
            Service::Api => "api",
            Service::Sshd => "sshd",
        }
    }
}

/// The per-service enable flags (persisted as `svc`; a missing key is ON, so an old store keeps serving everything).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct Svc {
    pub pages: bool,
    pub api: bool,
    pub sshd: bool,
}

impl Default for Svc {
    fn default() -> Svc {
        Svc { pages: true, api: true, sshd: true }
    }
}

impl Svc {
    pub fn get(&self, s: Service) -> bool {
        match s {
            Service::Pages => self.pages,
            Service::Api => self.api,
            Service::Sshd => self.sshd,
        }
    }
    pub fn with(mut self, s: Service, on: bool) -> Svc {
        match s {
            Service::Pages => self.pages = on,
            Service::Api => self.api = on,
            Service::Sshd => self.sshd = on,
        }
        self
    }
}

/// #1836 does `s` answer? Server (master) ON and the service's own flag ON.
pub fn service_on(sw: Switches, svc: Svc, s: Service) -> bool {
    plan(sw).listeners && svc.get(s)
}

/// The persisted (switches, flags) pair the request gates read.
pub fn enabled_pair() -> (Switches, Svc) {
    let st = current();
    (st.sw, st.svc)
}

/// [`service_on`] against the persisted state.
pub fn enabled(s: Service) -> bool {
    let st = current();
    service_on(st.sw, st.svc, s)
}

/// The persisted state: the two switches and the ids of the Repos sites that are enabled (started again when the server starts).
#[derive(Clone, Debug, PartialEq)]
pub struct State {
    pub sw: Switches,
    /// #1836 the per-service enable flags (Server stays the master switch).
    pub svc: Svc,
    pub sites: Vec<String>,
    /// The SSH door's own switch (door.rs): None follows Keep alive, Some is the owner's choice.
    pub ssh_door: Option<bool>,
    /// This app started the door it supervises (so it may stop it again); false for a door someone else brought up.
    pub ssh_owned: bool,
    /// Mesh-only (door.rs): None is the declared default (ON), Some is the owner's choice.
    pub mesh_only: Option<bool>,
    /// The hosted doors' own run switches (door id -> owner's choice); absent = the door's declared `default_on`.
    pub door_on: BTreeMap<String, bool>,
    /// Per-port Mesh-only for every door but the supervised one (door id -> owner's choice); absent = the door's declared default (ON).
    pub door_mesh: BTreeMap<String, bool>,
}

fn bool_map(v: &Value) -> BTreeMap<String, bool> {
    v.as_object().map(|o| o.iter().filter_map(|(k, b)| b.as_bool().map(|b| (k.clone(), b))).collect()).unwrap_or_default()
}

pub fn load(path: &Path, default: Switches) -> State {
    let v: Value = std::fs::read_to_string(path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null);
    State {
        sw: Switches { server: v["server"].as_bool().unwrap_or(default.server), keep_alive: v["keep_alive"].as_bool().unwrap_or(default.keep_alive) },
        svc: Svc { pages: v["svc"]["pages"].as_bool().unwrap_or(true), api: v["svc"]["api"].as_bool().unwrap_or(true), sshd: v["svc"]["sshd"].as_bool().unwrap_or(true) },
        sites: v["sites"].as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect()).unwrap_or_default(),
        ssh_door: v["ssh_door"].as_bool(),
        ssh_owned: v["ssh_owned"].as_bool().unwrap_or(false),
        mesh_only: v["mesh_only"].as_bool(),
        door_on: bool_map(&v["door_on"]),
        door_mesh: bool_map(&v["door_mesh_only"]),
    }
}

/// Atomic write (temp file, rename). The Kotlin glue reads the same file.
pub fn save(path: &Path, st: &State) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {}", dir.display(), e))?;
    }
    let tmp = path.with_extension("json.tmp");
    let mut doc = json!({"version": 1, "server": st.sw.server, "keep_alive": st.sw.keep_alive, "sites": st.sites, "ssh_owned": st.ssh_owned});
    if st.svc != Svc::default() {
        doc["svc"] = json!({"pages": st.svc.pages, "api": st.svc.api, "sshd": st.svc.sshd});
    }
    if let Some(on) = st.ssh_door {
        doc["ssh_door"] = json!(on);
    }
    if let Some(on) = st.mesh_only {
        doc["mesh_only"] = json!(on);
    }
    if !st.door_on.is_empty() {
        doc["door_on"] = json!(st.door_on);
    }
    if !st.door_mesh.is_empty() {
        doc["door_mesh_only"] = json!(st.door_mesh);
    }
    let body = doc.to_string();
    std::fs::write(&tmp, body).map_err(|e| format!("cannot write {}: {}", tmp.display(), e))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("cannot replace {}: {}", path.display(), e))
}

static LOCK: Mutex<()> = Mutex::new(());

pub fn current() -> State {
    load(&cfg().store, cfg().default)
}

/// #1836 one service's own enable flag: persisted only; [`crate::sync_services`] acts on it.
pub fn set_service(s: Service, on: bool) {
    let _g = LOCK.lock();
    let mut st = current();
    st.svc = st.svc.with(s, on);
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

static STARTED: OnceLock<std::time::Instant> = OnceLock::new();

/// #1836 seconds this server process has been up (the clock starts at the first start or first read).
pub fn uptime_s() -> u64 {
    STARTED.get_or_init(std::time::Instant::now).elapsed().as_secs()
}

/// Remember that a Repos site is enabled (or not): ON starts the enabled ones.
pub fn set_site(id: &str, on: bool) {
    let _g = LOCK.lock();
    let mut st = current();
    st.sites.retain(|s| s != id);
    if on {
        st.sites.push(id.to_string());
    }
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// The SSH door's own switch: Some(on) is the owner's choice, None hands the door back to Keep alive. Persisted only; door.rs acts.
pub fn set_ssh_door(on: Option<bool>) {
    let _g = LOCK.lock();
    let mut st = current();
    st.ssh_door = on;
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// The Mesh-only switch (door.rs): Some(on) is the owner's choice, None is the declared default. Persisted only; door.rs acts.
pub fn set_mesh_only(on: Option<bool>) {
    let _g = LOCK.lock();
    let mut st = current();
    st.mesh_only = on;
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// A hosted door's own run switch: Some(on) is the owner's choice, None hands it back to the declared default.
pub fn set_door_on(id: &str, on: Option<bool>) {
    let _g = LOCK.lock();
    let mut st = current();
    match on {
        Some(b) => st.door_on.insert(id.to_string(), b),
        None => st.door_on.remove(id),
    };
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// One port's Mesh-only switch (every door but the supervised one): Some(on) is the owner's choice, None the declared default.
pub fn set_door_mesh_only(id: &str, on: Option<bool>) {
    let _g = LOCK.lock();
    let mut st = current();
    match on {
        Some(b) => st.door_mesh.insert(id.to_string(), b),
        None => st.door_mesh.remove(id),
    };
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// Remember whether this app started the door it supervises.
pub fn set_ssh_owned(owned: bool) {
    let _g = LOCK.lock();
    let mut st = current();
    st.ssh_owned = owned;
    if let Err(e) = save(&cfg().store, &st) {
        eprintln!("c3-webserver: {}", e);
    }
}

/// The Android side (the keep-alive service and the boot receiver follow the state): installed by the Tauri crate.
type Hook = dyn Fn() + Send + Sync;
static SYNC: OnceLock<Box<Hook>> = OnceLock::new();

pub fn set_sync_hook(f: Box<Hook>) -> bool {
    SYNC.set(f).is_ok()
}

/// Persist the switches, then bring the listeners and the Android service in line.
pub fn apply(sw: Switches) -> Result<(), String> {
    {
        let _g = LOCK.lock();
        let mut st = current();
        st.sw = sw;
        save(&cfg().store, &st)?;
    }
    if plan(sw).listeners {
        crate::resume()?;
    } else {
        // Content only: the admin surface (:8002) stays bound so the owner can switch back on.
        crate::stop_content();
    }
    if let Some(f) = SYNC.get() {
        f();
    }
    // The supervised SSH door follows the same switches (start with Keep alive, stop with Server). Err = no Android bridge yet.
    let _ = crate::door::sync(false, false);
    Ok(())
}

/// #1836 restart the server: Server OFF (content listeners end), then ON again with the persisted per-service flags.
pub fn restart() -> Result<(), String> {
    let sw = current().sw;
    apply(Switches { server: false, ..sw })?;
    apply(Switches { server: true, ..sw })
}

/// The live state for the Home and Configs controls and for tools: switches, plan, what listens, how many sites.
pub fn status_json() -> Value {
    let st = current();
    let sv = crate::serving::get();
    let running = crate::running();
    let mut ports = vec![];
    if running {
        ports.push(json!({"name": "admin", "port": sv.drive_port, "proto": "tcp"}));
    }
    if crate::content_running() {
        ports.push(json!({"name": "diagnostics", "port": sv.diag_port, "proto": "tcp"}));
        ports.push(json!({"name": "dns", "port": sv.dns.port, "proto": "udp"}));
        // #1845 one entry per mesh address the listener really holds (none while the tunnel is down), never a declared port that is not bound
        if let Some(p) = apis::build()["mesh"]["port"].as_u64().or_else(|| apis::build()["runtime"]["mesh"]["port"].as_u64()) {
            for a in crate::meshlogs::bound_addrs() {
                ports.push(json!({"name": "mesh", "port": p, "proto": "tcp", "addr": a.to_string()}));
            }
        }
        ports.extend(crate::repos::host().ports().into_iter().map(|p| json!({"name": "site", "port": p, "proto": "tcp"})));
    }
    let ssh = crate::door::summary();
    if ssh["up"] == true {
        ports.push(json!({"name": "ssh", "port": ssh["port"], "proto": "tcp"}));
    }
    let p = plan(st.sw);
    let svc: Vec<(&str, Value)> = Service::ALL.iter().map(|s| (s.name(), json!({"enabled": st.svc.get(*s), "on": service_on(st.sw, st.svc, *s)}))).collect();
    json!({
        "svc": svc.iter().map(|(k, v)| (k.to_string(), v.clone())).collect::<serde_json::Map<String, Value>>(),
        "pid": std::process::id(), "uptime_s": uptime_s(),
        "server": st.sw.server, "keep_alive": st.sw.keep_alive, "running": running, "content": crate::content_running(), "admin": true,
        "sites": crate::repos::host().count(), "ports": ports, "off_url": cfg().off_url, "ssh_door": ssh, "vpn": apis::build()["vpn"].clone(),
        "plan": {"listeners": p.listeners, "admin": p.admin, "service": p.service, "boot": p.boot, "sticky": p.sticky, "wake": p.wake},
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    const ALL: [Switches; 4] = [
        Switches { server: false, keep_alive: false },
        Switches { server: false, keep_alive: true },
        Switches { server: true, keep_alive: false },
        Switches { server: true, keep_alive: true },
    ];

    #[test]
    fn the_rules_equal_the_declared_table() {
        let c = Cfg::from_build(apis::build()).unwrap();
        assert_eq!(c.matrix.len(), 4, "every state has a row");
        for s in ALL {
            let row = c.matrix.iter().find(|(m, _)| *m == s).unwrap_or_else(|| panic!("no matrix row for {:?}", s));
            assert_eq!(row.1, plan(s), "{:?}", s);
        }
        assert!(c.default.keep_alive, "keep alive defaults to ON (#1775)");
    }

    #[test]
    fn server_off_means_no_content_listeners_but_the_admin_surface_stays_in_every_keep_alive_state() {
        for ka in [false, true] {
            let p = plan(Switches { server: false, keep_alive: ka });
            assert_eq!(p, Plan { listeners: false, admin: true, service: false, boot: false, sticky: false, wake: false });
        }
    }

    #[test]
    fn the_admin_surface_is_bound_in_every_state() {
        assert!(ALL.iter().all(|s| plan(*s).admin), "Server OFF must never unbind /__app__ and /api/power");
    }

    #[test]
    fn keep_alive_off_means_no_service_no_boot_no_sticky_no_wake() {
        for server in [false, true] {
            let p = plan(Switches { server, keep_alive: false });
            assert!(!p.service && !p.boot && !p.sticky && !p.wake, "{:?}", p);
        }
        assert!(plan(Switches { server: true, keep_alive: false }).listeners, "the server still serves while the app is in use");
    }

    #[test]
    fn on_on_runs_the_foreground_service_and_only_then() {
        let p = plan(Switches { server: true, keep_alive: true });
        assert!(p.listeners && p.service && p.boot && p.sticky && !p.wake);
        assert_eq!(ALL.iter().filter(|s| plan(**s).service).count(), 1);
        assert_eq!(ALL.iter().filter(|s| may_start_from_outside(**s)).count(), 1, "outside callers may start it in one state only");
    }

    #[test]
    fn no_state_schedules_a_wake() {
        assert!(ALL.iter().all(|s| !plan(*s).wake));
    }

    #[test]
    fn the_store_round_trips_defaults_and_survives_garbage() {
        let dir = std::env::temp_dir().join(format!("c3ws-power-{}", std::process::id()));
        let f = dir.join("power.json");
        let d = Switches { server: true, keep_alive: false };
        assert_eq!(load(&f, d), State { sw: d, svc: Svc::default(), sites: vec![], ssh_door: None, ssh_owned: false, mesh_only: None, door_on: BTreeMap::new(), door_mesh: BTreeMap::new() }, "a missing file is the default");
        let st = State { sw: Switches { server: false, keep_alive: true }, svc: Svc { pages: false, api: true, sshd: false }, sites: vec!["a".into()], ssh_door: Some(false), ssh_owned: true, mesh_only: Some(false), door_on: BTreeMap::from([("webserver".to_string(), false)]), door_mesh: BTreeMap::from([("webserver".to_string(), false)]) };
        save(&f, &st).unwrap();
        assert_eq!(load(&f, d), st);
        let follow = State { ssh_door: None, ..st.clone() };
        save(&f, &follow).unwrap();
        assert_eq!(load(&f, d).ssh_door, None, "no key means follow Keep alive");
        assert_eq!(load(&f, d).mesh_only, Some(false));
        save(&f, &State { mesh_only: None, ..st.clone() }).unwrap();
        assert_eq!(load(&f, d).mesh_only, None, "no key means the declared default");
        std::fs::write(&f, "not json").unwrap();
        assert_eq!(load(&f, d).sw, d);
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn a_service_answers_only_while_server_and_its_own_flag_are_on() {
        let on = Switches { server: true, keep_alive: true };
        let off = Switches { server: false, keep_alive: true };
        for s in Service::ALL {
            assert!(service_on(on, Svc::default(), s), "{:?} defaults ON", s);
            assert!(!service_on(off, Svc::default(), s), "Server OFF stops {:?} (master)", s);
            assert!(!service_on(on, Svc::default().with(s, false), s), "{:?} disabled", s);
            for other in Service::ALL.into_iter().filter(|o| *o != s) {
                assert!(service_on(on, Svc::default().with(s, false), other), "disabling {:?} leaves {:?} serving", s, other);
            }
        }
        assert!(plan(off).admin, "the admin surface stays so a service can be started again");
    }

    #[test]
    fn the_service_flags_persist_and_an_old_store_means_all_on() {
        let dir = std::env::temp_dir().join(format!("c3ws-svc-{}", std::process::id()));
        let f = dir.join("power.json");
        let d = Switches { server: true, keep_alive: true };
        std::fs::create_dir_all(&dir).unwrap();
        std::fs::write(&f, r#"{"version":1,"server":true,"keep_alive":true}"#).unwrap();
        assert_eq!(load(&f, d).svc, Svc::default(), "no `svc` key: every service ON");
        let st = State { svc: Svc::default().with(Service::Api, false), ..load(&f, d) };
        save(&f, &st).unwrap();
        let back = load(&f, d).svc;
        assert!(!back.api && back.pages && back.sshd, "only API was disabled: {:?}", back);
        std::fs::write(&f, r#"{"svc":{"pages":"x"}}"#).unwrap();
        assert_eq!(load(&f, d).svc, Svc::default(), "a malformed flag is ON");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
