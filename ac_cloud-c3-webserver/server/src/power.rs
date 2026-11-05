//! #1502 the two master controls: Server ON/OFF and Keep alive ON/OFF, as one declared state machine.
//!
//! build.json::power declares the store (a JSON file both this crate and the Kotlin keep-alive glue read), the defaults and the
//! decision table (`matrix`); [`plan`] is the rules and the tests hold it equal to the table. Server OFF: no listener (the
//! loopback pages, the mesh listener, every Repos site) and no port held. Keep alive OFF: no foreground service, no sticky
//! restart, no boot receiver, no scheduled wake; the server lives only while the app process does. Only Server ON with Keep
//! alive ON runs the foreground service and the boot autostart.

use crate::apis;
use serde_json::{json, Value};
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
    pub listeners: bool,
    pub service: bool,
    pub boot: bool,
    pub sticky: bool,
    pub wake: bool,
}

pub fn plan(s: Switches) -> Plan {
    let svc = s.server && s.keep_alive;
    Plan { listeners: s.server, service: svc, boot: svc, sticky: svc, wake: false }
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
            .map(|r| Ok::<_, String>((sw(r)?, Plan { listeners: flag(r, "listeners")?, service: flag(r, "service")?, boot: flag(r, "boot")?, sticky: flag(r, "sticky")?, wake: flag(r, "wake")? })))
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

/// The persisted state: the two switches and the ids of the Repos sites that are enabled (started again when the server starts).
#[derive(Clone, Debug, PartialEq)]
pub struct State {
    pub sw: Switches,
    pub sites: Vec<String>,
}

pub fn load(path: &Path, default: Switches) -> State {
    let v: Value = std::fs::read_to_string(path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(Value::Null);
    State {
        sw: Switches { server: v["server"].as_bool().unwrap_or(default.server), keep_alive: v["keep_alive"].as_bool().unwrap_or(default.keep_alive) },
        sites: v["sites"].as_array().map(|a| a.iter().filter_map(|s| s.as_str().map(str::to_string)).collect()).unwrap_or_default(),
    }
}

/// Atomic write (temp file, rename). The Kotlin glue reads the same file.
pub fn save(path: &Path, st: &State) -> Result<(), String> {
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("cannot create {}: {}", dir.display(), e))?;
    }
    let tmp = path.with_extension("json.tmp");
    let body = json!({"version": 1, "server": st.sw.server, "keep_alive": st.sw.keep_alive, "sites": st.sites}).to_string();
    std::fs::write(&tmp, body).map_err(|e| format!("cannot write {}: {}", tmp.display(), e))?;
    std::fs::rename(&tmp, path).map_err(|e| format!("cannot replace {}: {}", path.display(), e))
}

static LOCK: Mutex<()> = Mutex::new(());

pub fn current() -> State {
    load(&cfg().store, cfg().default)
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
        crate::stop_all();
    }
    if let Some(f) = SYNC.get() {
        f();
    }
    Ok(())
}

/// The live state for the Home and Configs controls and for tools: switches, plan, what listens, how many sites.
pub fn status_json() -> Value {
    let st = current();
    let sv = crate::serving::get();
    let running = crate::running();
    let mut ports = vec![];
    if running {
        ports.push(json!({"name": "drive", "port": sv.drive_port, "proto": "tcp"}));
        ports.push(json!({"name": "diagnostics", "port": sv.diag_port, "proto": "tcp"}));
        ports.push(json!({"name": "dns", "port": sv.dns.port, "proto": "udp"}));
        if let Some(p) = apis::build()["mesh"]["port"].as_u64().or_else(|| apis::build()["runtime"]["mesh"]["port"].as_u64()) {
            ports.push(json!({"name": "mesh", "port": p, "proto": "tcp"}));
        }
        ports.extend(crate::repos::host().ports().into_iter().map(|p| json!({"name": "site", "port": p, "proto": "tcp"})));
    }
    let p = plan(st.sw);
    json!({
        "server": st.sw.server, "keep_alive": st.sw.keep_alive, "running": running,
        "sites": crate::repos::host().count(), "ports": ports, "off_url": cfg().off_url,
        "plan": {"listeners": p.listeners, "service": p.service, "boot": p.boot, "sticky": p.sticky, "wake": p.wake},
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
        assert!(!c.default.keep_alive, "keep alive defaults to OFF");
    }

    #[test]
    fn server_off_means_no_listeners_in_every_keep_alive_state() {
        for ka in [false, true] {
            let p = plan(Switches { server: false, keep_alive: ka });
            assert_eq!(p, Plan { listeners: false, service: false, boot: false, sticky: false, wake: false });
        }
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
        assert_eq!(load(&f, d), State { sw: d, sites: vec![] }, "a missing file is the default");
        let st = State { sw: Switches { server: false, keep_alive: true }, sites: vec!["a".into()] };
        save(&f, &st).unwrap();
        assert_eq!(load(&f, d), st);
        std::fs::write(&f, "not json").unwrap();
        assert_eq!(load(&f, d).sw, d);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
