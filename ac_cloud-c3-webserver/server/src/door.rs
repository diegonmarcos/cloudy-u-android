//! The fleet's SSH doors, as data, and the supervisor for the one this app keeps up.
//!
//! build.json::ssh_doors declares every door (the nix-on-droid mesh sshd on 8024, the Termux sshd on 8023, the SuperApp, which
//! hosts none, and the extra ports to look at). Nothing about a door is written here: build.rs resolves each `*_from`
//! (`file#dotted.path`, from the owner's own declaration) into the same key without the suffix, and every live fact (listening,
//! the banner, who is connected) is probed when asked, never stored.
//!
//! The supervised door is the nix-on-droid sshd, the real OpenSSH the fleet's MCP already dials. This app does not host an sshd:
//! it asks that app to start or stop it through its exported, signature-guarded MeshSshdService
//! (build.json::mesh_sshd.control), the same way the keep-alive service holds this app's own ports. [`wanted`] is the rule:
//! the door is wanted while Server is ON and, unless the owner set the door's own switch, while Keep alive is ON. [`decide`]
//! is the whole supervisor: start a wanted door that is down; stop an unwanted one only when this app started it (or the
//! owner just pressed Stop); never touch a door someone else brought up. [`sync`] runs it once per event (service start,
//! switch change); nothing polls and nothing is scheduled.

use crate::{apis, power};
use serde_json::{json, Value};
use std::collections::HashMap;
use std::io::Read;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant};

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Cmd {
    /// Start (or restart on a changed config) the door; the flag is Mesh-only, sent with every start.
    Start(bool),
    Stop,
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Action {
    Start,
    Stop,
    Nothing,
}

/// Is the door wanted? Server OFF never; otherwise the door's own switch when the owner set one, else Keep alive.
pub fn wanted(sw: power::Switches, explicit: Option<bool>) -> bool {
    sw.server && explicit.unwrap_or(sw.keep_alive)
}

/// The supervisor's rule. `recent_start`: a start was sent a moment ago and the sshd may still be coming up (no second start).
/// `reapply`: the Mesh-only flag just changed, so a wanted door that is up gets one more start, which restarts it on the new bind.
pub fn decide(wanted: bool, up: bool, owned: bool, force_stop: bool, recent_start: bool, reapply: bool) -> Action {
    if wanted && up && reapply {
        return Action::Start;
    }
    if wanted && !up {
        return if recent_start { Action::Nothing } else { Action::Start };
    }
    if !wanted && up && (owned || force_stop) {
        return Action::Stop;
    }
    Action::Nothing
}

/// Does this app own the door after `act`? It does once it started a door that was down; it does not once it stopped it,
/// and a door that is down is nobody's.
pub fn owned_after(act: Action, up: bool, owned: bool) -> bool {
    match act {
        Action::Start => true,
        Action::Stop => false,
        Action::Nothing => owned && up,
    }
}

/// The Android side (an Intent to the nix app's service), installed by the Tauri crate.
type Sender = dyn Fn(Cmd) -> Result<(), String> + Send + Sync;
static SENDER: OnceLock<Box<Sender>> = OnceLock::new();

pub fn set_sender(f: Box<Sender>) -> bool {
    SENDER.set(f).is_ok()
}

static LOCK: Mutex<()> = Mutex::new(());
static LAST_START: Mutex<Option<Instant>> = Mutex::new(None);
const START_GRACE: Duration = Duration::from_secs(90);

/// One supervisor pass with the live facts handed in (the tests call this with a recording sender).
pub fn sync_with(send: &dyn Fn(Cmd) -> Result<(), String>, up: bool, recent_start: bool, force_stop: bool, reapply: bool) -> Result<Action, String> {
    let _g = LOCK.lock().map_err(|_| "poisoned".to_string())?;
    let st = power::current();
    let act = decide(wanted(st.sw, st.ssh_door), up, st.ssh_owned, force_stop, recent_start, reapply);
    match act {
        Action::Start => send(Cmd::Start(mesh_only(&st)))?,
        Action::Stop => send(Cmd::Stop)?,
        Action::Nothing => {}
    }
    // A door that was up and is restarted on a new bind stays whoever's it was.
    let own = if act == Action::Start && up { st.ssh_owned } else { owned_after(act, up, st.ssh_owned) };
    if own != st.ssh_owned {
        power::set_ssh_owned(own);
    }
    Ok(act)
}

/// One supervisor pass against the real door and the installed sender. Err when there is no Android bridge yet.
pub fn sync(force_stop: bool, reapply: bool) -> Result<Action, String> {
    let send = SENDER.get().ok_or_else(|| "no Android bridge installed".to_string())?;
    let up = probe(supervised().port).up;
    let recent = LAST_START.lock().map(|l| l.map(|t| t.elapsed() < START_GRACE).unwrap_or(false)).unwrap_or(false);
    let act = sync_with(&**send, up, recent, force_stop, reapply)?;
    if act == Action::Start {
        if let Ok(mut l) = LAST_START.lock() {
            *l = Some(Instant::now());
        }
    }
    Ok(act)
}

// ── the declaration ─────────────────────────────────────────────────────────

#[derive(Clone, Debug)]
pub struct Door {
    pub id: String,
    pub title: String,
    pub app: String,
    pub package: String,
    pub user: String,
    pub engine: String,
    pub port: Option<u16>,
    pub bind: String,
    pub auth: String,
    pub supervised: bool,
    pub note: String,
    pub control: Option<Control>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Control {
    pub package: String,
    pub service: String,
    /// The boolean extra that carries the Mesh-only flag to the hosting app (declared by that app).
    pub extra_mesh_only: String,
    pub action_start: String,
    pub action_stop: String,
}

#[derive(Clone, Debug)]
pub struct Mesh {
    /// Mesh-only when the owner never set the switch: ON (secure by default), declared by the hosting app.
    pub default_on: bool,
    pub cidrs: Vec<String>,
}

#[derive(Clone, Debug)]
pub struct Cfg {
    pub mesh: Mesh,
    pub timeout: Duration,
    pub discover: Vec<u16>,
    pub doors: Vec<Door>,
}

fn txt(v: &Value, k: &str) -> String {
    v[k].as_str().unwrap_or("").to_string()
}

pub fn cfg_from(b: &Value) -> Result<Cfg, String> {
    let d = b.get("ssh_doors").ok_or("build.json has no `ssh_doors` block")?;
    let timeout = Duration::from_millis(d["probe_timeout_ms"].as_u64().ok_or("ssh_doors.probe_timeout_ms must be an integer")?);
    let discover = d["discover_ports"].as_array().ok_or("ssh_doors.discover_ports must be an array")?.iter().filter_map(|p| p.as_u64().map(|p| p as u16)).collect();
    let mo = &d["mesh_only"];
    let mesh = Mesh {
        default_on: mo["default_on"].as_bool().ok_or("ssh_doors.mesh_only.default_on must be a boolean")?,
        cidrs: mo["cidrs"].as_array().ok_or("ssh_doors.mesh_only.cidrs must be an array")?.iter().filter_map(|c| c.as_str().map(str::to_string)).collect(),
    };
    if mesh.cidrs.is_empty() {
        return Err("ssh_doors.mesh_only.cidrs is empty".to_string());
    }
    let mut doors = vec![];
    for r in d["doors"].as_array().ok_or("ssh_doors.doors must be an array")? {
        let id = txt(r, "id");
        if id.is_empty() {
            return Err("an ssh_doors door has no id".to_string());
        }
        let port = match r.get("port") {
            None | Some(Value::Null) => None,
            Some(p) => Some(p.as_u64().filter(|p| (1..=65535).contains(p)).ok_or(format!("ssh_doors door {}: port must be 1..65535", id))? as u16),
        };
        let bind = match r.get("bind") {
            Some(Value::Array(a)) => a.iter().filter_map(Value::as_str).collect::<Vec<_>>().join(", "),
            Some(Value::String(s)) => s.clone(),
            _ => String::new(),
        };
        let control = match r.get("control") {
            Some(c) if c.is_object() => Some(Control {
                package: txt(r, "package"),
                service: txt(c, "service"),
                extra_mesh_only: txt(c, "extra_mesh_only"),
                action_start: txt(c, "action_start"),
                action_stop: txt(c, "action_stop"),
            }),
            _ => None,
        };
        doors.push(Door {
            id,
            title: txt(r, "title"),
            app: txt(r, "app"),
            package: txt(r, "package"),
            user: txt(r, "user"),
            engine: txt(r, "engine"),
            port,
            bind,
            auth: txt(r, "auth"),
            supervised: r["supervised"].as_bool().unwrap_or(false),
            note: txt(r, "note"),
            control,
        });
    }
    if doors.iter().filter(|x| x.supervised).count() != 1 {
        return Err("ssh_doors: exactly one door must be `supervised`".to_string());
    }
    let sup = doors.iter().find(|x| x.supervised).unwrap();
    if sup.port.is_none() || sup.control.is_none() {
        return Err("ssh_doors: the supervised door needs a port and a control hook".to_string());
    }
    Ok(Cfg { mesh, timeout, discover, doors })
}

pub fn cfg() -> &'static Cfg {
    static C: OnceLock<Cfg> = OnceLock::new();
    C.get_or_init(|| cfg_from(apis::build()).expect("build.json::ssh_doors is invalid (the tests catch this)"))
}

/// The one door this app keeps up (the nix-on-droid mesh sshd), with its port and hook resolved.
pub struct Supervised {
    pub port: u16,
    pub control: Control,
}

pub fn supervised() -> Supervised {
    let d = cfg().doors.iter().find(|x| x.supervised).expect("checked by cfg_from");
    Supervised { port: d.port.expect("checked"), control: d.control.clone().expect("checked") }
}

/// Mesh-only now: the owner's switch, else the declared default (ON).
pub fn mesh_only(st: &power::State) -> bool {
    st.mesh_only.unwrap_or(cfg().mesh.default_on)
}

// ── live facts ──────────────────────────────────────────────────────────────

pub struct Probe {
    pub up: bool,
    /// The SSH identification line the server sent ("SSH-2.0-OpenSSH_9.8"), when it sent one.
    pub banner: String,
}

/// One connect to loopback (Android shares 127.0.0.1 across apps) and a short read of the identification line.
pub fn probe(port: u16) -> Probe {
    let addr: SocketAddr = ([127, 0, 0, 1], port).into();
    match TcpStream::connect_timeout(&addr, cfg().timeout) {
        Ok(mut s) => {
            let _ = s.set_read_timeout(Some(cfg().timeout));
            let mut buf = [0u8; 255];
            let n = s.read(&mut buf).unwrap_or(0);
            Probe { up: true, banner: banner_of(&buf[..n]) }
        }
        Err(_) => Probe { up: false, banner: String::new() },
    }
}

pub fn banner_of(bytes: &[u8]) -> String {
    let text = String::from_utf8_lossy(bytes);
    let line = text.lines().next().unwrap_or("").trim();
    if line.starts_with("SSH-") { line.chars().take(120).collect() } else { String::new() }
}

/// "OpenSSH 9.8p1" out of "SSH-2.0-OpenSSH_9.8p1 Ubuntu"; the raw software field for any other implementation.
pub fn engine_of(banner: &str) -> String {
    let soft = banner.splitn(3, '-').nth(2).unwrap_or("").split_whitespace().next().unwrap_or("");
    match soft.strip_prefix("OpenSSH_") {
        Some(v) => format!("OpenSSH {}", v),
        None => soft.replace('_', " "),
    }
}

/// Remote endpoints of the established connections to a local `port`, from the text of /proc/net/tcp or tcp6.
pub fn established(proc_net: &str, port: u16) -> Vec<String> {
    let mut out = vec![];
    for line in proc_net.lines().skip(1) {
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 4 || f[3] != "01" {
            continue;
        }
        let local_port = f[1].rsplit(':').next().and_then(|p| u16::from_str_radix(p, 16).ok());
        if local_port != Some(port) {
            continue;
        }
        if let Some((addr, p)) = f[2].rsplit_once(':') {
            if let Ok(p) = u16::from_str_radix(p, 16) {
                out.push(format!("{}:{}", hex_addr(addr), p));
            }
        }
    }
    out
}

fn hex_addr(h: &str) -> String {
    if h.len() == 8 {
        // /proc prints the IPv4 address as one little-endian 32-bit word.
        if let Ok(w) = u32::from_str_radix(h, 16) {
            let b = w.to_le_bytes();
            return format!("{}.{}.{}.{}", b[0], b[1], b[2], b[3]);
        }
    }
    h.to_string()
}

/// Connected sessions on `port`, or None when /proc/net is not readable by this app (newer Android hides it).
pub fn sessions(port: u16) -> Option<Vec<String>> {
    let mut any = false;
    let mut out = vec![];
    for f in ["/proc/net/tcp", "/proc/net/tcp6"] {
        if let Ok(t) = std::fs::read_to_string(f) {
            any = true;
            out.extend(established(&t, port));
        }
    }
    if any { Some(out) } else { None }
}

/// Local addresses of the LISTEN sockets on `port`, from the text of /proc/net/tcp or tcp6.
pub fn listening(proc_net: &str, port: u16) -> Vec<IpAddr> {
    let mut out = vec![];
    for line in proc_net.lines().skip(1) {
        let f: Vec<&str> = line.split_whitespace().collect();
        if f.len() < 4 || f[3] != "0A" {
            continue;
        }
        if let Some((addr, p)) = f[1].rsplit_once(':') {
            if u16::from_str_radix(p, 16) == Ok(port) {
                if let Some(ip) = hex_ip(addr) {
                    out.push(ip);
                }
            }
        }
    }
    out
}

/// /proc prints an address as little-endian 32-bit words: one for IPv4, four for IPv6.
fn hex_ip(h: &str) -> Option<IpAddr> {
    let word = |i: usize| u32::from_str_radix(h.get(i * 8..i * 8 + 8)?, 16).ok().map(|w| w.to_le_bytes());
    match h.len() {
        8 => word(0).map(|b| IpAddr::V4(Ipv4Addr::new(b[0], b[1], b[2], b[3]))),
        32 => {
            let mut o = [0u8; 16];
            for i in 0..4 {
                o[i * 4..i * 4 + 4].copy_from_slice(&word(i)?);
            }
            Some(IpAddr::V6(Ipv6Addr::from(o)))
        }
        _ => None,
    }
}

fn bits_of(ip: &IpAddr) -> Vec<u8> {
    match ip {
        IpAddr::V4(a) => a.octets().to_vec(),
        IpAddr::V6(a) => a.octets().to_vec(),
    }
}

/// Is `ip` inside `cidr` ("10.0.0.0/24", "fd0c:1d00::/32")? A v4-mapped v6 address is judged as its v4 address.
pub fn in_cidr(ip: &IpAddr, cidr: &str) -> bool {
    let (net, bits) = match cidr.split_once('/') {
        Some((n, b)) => (n, b),
        None => return false,
    };
    let (net, bits): (IpAddr, usize) = match (net.parse(), bits.parse()) {
        (Ok(n), Ok(b)) => (n, b),
        _ => return false,
    };
    let ip = match ip {
        IpAddr::V6(a) => a.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(*ip),
        _ => *ip,
    };
    let (a, b) = (bits_of(&ip), bits_of(&net));
    if a.len() != b.len() || bits > a.len() * 8 {
        return false;
    }
    (0..bits).all(|i| (a[i / 8] ^ b[i / 8]) & (0x80 >> (i % 8)) == 0)
}

/// What a door's listening addresses amount to: closed (nothing listens), mesh-only (loopback and mesh addresses only), or exposed
/// (the wildcard or any other address: reachable from Wi-Fi, LAN or cellular).
pub fn bind_verdict(ips: &[IpAddr], cidrs: &[String]) -> &'static str {
    if ips.is_empty() {
        return "closed";
    }
    let local_or_mesh = |ip: &IpAddr| !ip.is_unspecified() && (ip.is_loopback() || cidrs.iter().any(|c| in_cidr(ip, c)));
    if ips.iter().all(local_or_mesh) { "mesh-only" } else { "exposed" }
}

/// Listening addresses of `port` as this app can see them; None when /proc/net is hidden (newer Android).
pub fn observed_binds(port: u16) -> Option<Vec<IpAddr>> {
    let mut any = false;
    let mut out = vec![];
    for f in ["/proc/net/tcp", "/proc/net/tcp6"] {
        if let Ok(t) = std::fs::read_to_string(f) {
            any = true;
            out.extend(listening(&t, port));
        }
    }
    if any { Some(out) } else { None }
}

/// First moment each door was seen listening by this process, for the "seen up" column (nothing is stored on disk).
static SEEN: Mutex<Option<HashMap<String, Instant>>> = Mutex::new(None);

fn seen_up_s(id: &str, up: bool) -> Option<u64> {
    let mut g = SEEN.lock().ok()?;
    let m = g.get_or_insert_with(HashMap::new);
    if !up {
        m.remove(id);
        return None;
    }
    let t = *m.entry(id.to_string()).or_insert_with(Instant::now);
    Some(t.elapsed().as_secs())
}

/// Door state: connected / listening / starting / down, or "not hosted" for an app that runs no sshd.
pub fn state_of(has_port: bool, up: bool, sessions: usize, wanted_and_asked: bool) -> &'static str {
    if !has_port {
        return "not hosted";
    }
    match (up, sessions > 0, wanted_and_asked) {
        (true, true, _) => "connected",
        (true, false, _) => "listening",
        (false, _, true) => "starting",
        (false, _, false) => "down",
    }
}

/// The compact state of the supervised door for the Server card: port, wanted, explicit switch, owned, live state.
pub fn summary() -> Value {
    let st = power::current();
    let sup = supervised();
    let p = probe(sup.port);
    let want = wanted(st.sw, st.ssh_door);
    let asked = LAST_START.lock().map(|l| l.map(|t| t.elapsed() < START_GRACE).unwrap_or(false)).unwrap_or(false);
    json!({
        "port": sup.port, "wanted": want, "explicit": st.ssh_door, "owned": st.ssh_owned, "up": p.up, "mesh_only": mesh_only(&st),
        "state": state_of(true, p.up, 0, want && asked), "via": "Cloud Terminal Nix-on-Droid (mesh_sshd)",
    })
}

/// The SSHd page's data: every declared door and every other port that answers with an SSH banner, live.
pub fn doors_json() -> Value {
    let st = power::current();
    let want = wanted(st.sw, st.ssh_door);
    let asked = LAST_START.lock().map(|l| l.map(|t| t.elapsed() < START_GRACE).unwrap_or(false)).unwrap_or(false);
    let mut declared_ports = vec![];
    let mut rows = vec![];
    for d in &cfg().doors {
        let (p, ses) = match d.port {
            Some(port) => {
                declared_ports.push(port);
                (Some(probe(port)), sessions(port))
            }
            None => (None, None),
        };
        let up = p.as_ref().map(|p| p.up).unwrap_or(false);
        let banner = p.as_ref().map(|p| p.banner.clone()).unwrap_or_default();
        let engine = if !banner.is_empty() { engine_of(&banner) } else { d.engine.clone() };
        let n = ses.as_ref().map(|s| s.len()).unwrap_or(0);
        let state = state_of(d.port.is_some(), up, n, d.supervised && want && asked);
        let control = if d.supervised {
            json!({"available": true, "wanted": want, "explicit": st.ssh_door, "owned": st.ssh_owned,
                   "note": "Start and Stop ask the hosting app's own sshd through its signature-guarded service; the key list stays in that app."})
        } else {
            let why = if d.port.is_some() { "This app has no hook into that terminal: start or stop its sshd inside it." } else { "No sshd to control." };
            json!({"available": false, "explicit": Value::Null, "note": why})
        };
        let (binds, bind_state) = match d.port {
            None => (Value::Null, "n/a"),
            Some(port) => match observed_binds(port) {
                None => (Value::Null, "unknown"),
                Some(ips) => (json!(ips.iter().map(|i| i.to_string()).collect::<Vec<_>>()), bind_verdict(&ips, &cfg().mesh.cidrs)),
            },
        };
        let sessions_note = if ses.is_none() { "This Android hides /proc/net from apps: connected sessions cannot be listed." } else { "" };
        rows.push(json!({
            "id": d.id, "title": d.title, "app": d.app, "package": d.package, "engine": engine, "banner": banner,
            "port": d.port, "bind": d.bind, "binds": binds, "bind_state": bind_state, "state": state, "auth": d.auth, "supervised": d.supervised,
            "authorized_keys": Value::Null,
            "authorized_keys_note": "Held in the hosting app's private home; not readable from this app's uid.",
            "seen_up_s": seen_up_s(&d.id, up),
            "sessions": ses, "sessions_note": sessions_note,
            "note": d.note, "control": control,
        }));
    }
    let mut other = vec![];
    for port in &cfg().discover {
        if declared_ports.contains(port) {
            continue;
        }
        let p = probe(*port);
        if p.up && !p.banner.is_empty() {
            other.push(json!({"port": port, "engine": engine_of(&p.banner), "banner": p.banner, "state": "listening",
                "sessions": sessions(*port)}));
        }
    }
    json!({"doors": rows, "other": other, "wanted": want, "explicit": st.ssh_door, "keep_alive": st.sw.keep_alive, "server": st.sw.server,
           "mesh_only": mesh_only(&st), "mesh_only_explicit": st.mesh_only, "mesh_cidrs": cfg().mesh.cidrs})
}

/// POST {door, op}: `start` / `stop` set the supervised door's own switch (and act now), `follow` hands it back to Keep alive.
pub fn command(door: &str, op: &str) -> Result<(), String> {
    let d = cfg().doors.iter().find(|x| x.id == door).ok_or_else(|| format!("no door named {}", door))?;
    if !d.supervised {
        return Err(format!("{} has no start/stop hook: use its own terminal", door));
    }
    set(op)
}

/// The same op on the supervised door (the Server card's switch).
pub fn set(op: &str) -> Result<(), String> {
    let (explicit, force) = match op {
        "start" => (Some(true), false),
        "stop" => (Some(false), true),
        "follow" => (None, false),
        _ => return Err("op must be start, stop or follow".to_string()),
    };
    power::set_ssh_door(explicit);
    sync(force, false).map(|_| ())
}

/// The Mesh-only switch: true (the default) binds the supervised door to the mesh addresses only, false to every interface. The
/// choice is persisted and a wanted door that is up is restarted on the new bind now. None hands the switch back to the default.
pub fn set_mesh_only(on: Option<bool>) -> Result<(), String> {
    power::set_mesh_only(on);
    sync(false, true).map(|_| ())
}

#[cfg(test)]
mod tests {
    use super::*;
    use power::Switches;

    const ALL: [Switches; 4] = [
        Switches { server: false, keep_alive: false },
        Switches { server: false, keep_alive: true },
        Switches { server: true, keep_alive: false },
        Switches { server: true, keep_alive: true },
    ];

    #[test]
    fn the_door_follows_keep_alive_until_the_owner_sets_it() {
        for s in ALL {
            assert_eq!(wanted(s, None), s.server && s.keep_alive, "{:?}", s);
        }
        assert!(wanted(Switches { server: true, keep_alive: false }, Some(true)), "an explicit ON works without Keep alive");
        assert!(!wanted(Switches { server: true, keep_alive: true }, Some(false)), "an explicit OFF beats Keep alive");
        for ka in [false, true] {
            assert!(!wanted(Switches { server: false, keep_alive: ka }, Some(true)), "Server OFF closes the door whatever its own switch says");
        }
    }

    #[test]
    fn keep_alive_on_starts_a_down_door_and_only_that() {
        assert_eq!(decide(true, false, false, false, false, false), Action::Start);
        assert_eq!(decide(true, true, false, false, false, false), Action::Nothing, "already up: leave it");
        assert_eq!(decide(true, false, false, false, true, false), Action::Nothing, "a start was just sent: no second one");
    }

    #[test]
    fn stop_only_what_this_app_started_or_what_the_owner_just_stopped() {
        assert_eq!(decide(false, true, true, false, false, false), Action::Stop);
        assert_eq!(decide(false, true, false, false, false, false), Action::Nothing, "a door someone else started stays up");
        assert_eq!(decide(false, true, false, true, false, false), Action::Stop, "the owner pressed Stop");
        assert_eq!(decide(false, false, true, true, false, false), Action::Nothing);
    }

    #[test]
    fn ownership_follows_who_started_it() {
        assert!(owned_after(Action::Start, false, false));
        assert!(!owned_after(Action::Stop, true, true));
        assert!(owned_after(Action::Nothing, true, true));
        assert!(!owned_after(Action::Nothing, false, true), "a door that vanished is nobody's");
        assert!(!owned_after(Action::Nothing, true, false));
    }

    #[test]
    fn the_declaration_is_valid_and_resolved() {
        let c = cfg_from(apis::build()).unwrap();
        let ids: Vec<&str> = c.doors.iter().map(|d| d.id.as_str()).collect();
        assert_eq!(ids, ["nix", "superapp", "termux"], "the three fleet doors");
        let nix = c.doors.iter().find(|d| d.id == "nix").unwrap();
        assert!(nix.supervised && nix.port == Some(8024) && nix.auth.starts_with("pubkey"));
        assert_eq!(c.doors.iter().find(|d| d.id == "termux").unwrap().port, Some(8023));
        assert_eq!(c.doors.iter().find(|d| d.id == "superapp").unwrap().port, None, "the SuperApp hosts no sshd");
        let ctl = nix.control.as_ref().unwrap();
        assert!(ctl.service.ends_with("MeshSshdService") && ctl.action_start.ends_with("MESH_SSHD_START") && ctl.action_stop.ends_with("MESH_SSHD_STOP"));
        assert_eq!(nix.package, "cld.termux.nix");
    }

    #[test]
    fn a_declaration_with_two_supervised_doors_or_none_is_refused() {
        let mut b = apis::build().clone();
        b["ssh_doors"]["doors"][1]["supervised"] = json!(true);
        assert!(cfg_from(&b).is_err());
        let mut b = apis::build().clone();
        b["ssh_doors"]["doors"][0]["supervised"] = json!(false);
        assert!(cfg_from(&b).is_err());
    }

    #[test]
    fn the_banner_names_the_engine() {
        assert_eq!(banner_of(b"SSH-2.0-OpenSSH_9.8p1 Debian\r\n\0\0"), "SSH-2.0-OpenSSH_9.8p1 Debian");
        assert_eq!(banner_of(b"HTTP/1.1 400 Bad Request\r\n"), "", "not an SSH server");
        assert_eq!(engine_of("SSH-2.0-OpenSSH_9.8p1 Debian"), "OpenSSH 9.8p1");
        assert_eq!(engine_of("SSH-2.0-dropbear_2022.83"), "dropbear 2022.83");
        assert_eq!(engine_of(""), "");
    }

    #[test]
    fn established_sessions_are_read_from_proc_net() {
        // 8024 = 0x1F58; remote 10.0.0.2:51000 = 0200000A:C738 (state 01), one LISTEN (0A) and one on another port.
        let t = "  sl  local_address rem_address   st tx_queue rx_queue\n\
                 0: 00000000:1F58 00000000:0000 0A 00000000:00000000 00:00000000 00000000 0 0 1\n\
                 1: 0100007F:1F58 0200000A:C738 01 00000000:00000000 00:00000000 00000000 0 0 2\n\
                 2: 0100007F:1F59 0200000A:C739 01 00000000:00000000 00:00000000 00000000 0 0 3\n";
        assert_eq!(established(t, 8024), vec!["10.0.0.2:51000".to_string()]);
        assert!(established(t, 22).is_empty());
    }

    #[test]
    fn a_changed_mesh_only_flag_restarts_a_wanted_door_that_is_up() {
        assert_eq!(decide(true, true, true, false, true, true), Action::Start, "reapply restarts it on the new bind, even inside the start grace");
        assert_eq!(decide(false, true, false, false, false, true), Action::Nothing, "a door nobody wants is not touched");
        assert_eq!(decide(true, false, false, false, false, true), Action::Start);
    }

    #[test]
    fn mesh_only_is_on_by_default_and_declared_by_the_hosting_app() {
        let c = cfg_from(apis::build()).unwrap();
        assert!(c.mesh.default_on, "secure by default");
        assert!(c.mesh.cidrs.iter().any(|x| x == "10.0.0.0/24") && c.mesh.cidrs.iter().any(|x| x.starts_with("fd0c:1d00")));
        let nix = c.doors.iter().find(|d| d.id == "nix").unwrap();
        assert_eq!(nix.control.as_ref().unwrap().extra_mesh_only, "com.diegonmarcos.cloud.extra.MESH_ONLY");
        let st = power::State { sw: power::Switches { server: true, keep_alive: true }, sites: vec![], ssh_door: None, ssh_owned: false, mesh_only: None };
        assert!(mesh_only(&st));
        assert!(!mesh_only(&power::State { mesh_only: Some(false), ..st.clone() }));
    }

    #[test]
    fn a_wildcard_listener_is_exposed_and_only_mesh_and_loopback_are_mesh_only() {
        let cidrs: Vec<String> = ["10.0.0.0/24", "10.1.0.0/24", "fd0c:1d00::/32", "fd0c:1d01::/32"].iter().map(|s| s.to_string()).collect();
        let ip = |s: &str| s.parse::<IpAddr>().unwrap();
        assert_eq!(bind_verdict(&[], &cidrs), "closed");
        assert_eq!(bind_verdict(&[ip("10.0.0.9"), ip("127.0.0.1"), ip("fd0c:1d00::9"), ip("::1")], &cidrs), "mesh-only");
        assert_eq!(bind_verdict(&[ip("0.0.0.0")], &cidrs), "exposed", "all interfaces");
        assert_eq!(bind_verdict(&[ip("::")], &cidrs), "exposed", "all interfaces, v6");
        assert_eq!(bind_verdict(&[ip("10.0.0.9"), ip("192.168.1.4")], &cidrs), "exposed", "one LAN address among mesh ones");
        assert_eq!(bind_verdict(&[ip("10.0.5.4")], &cidrs), "exposed", "a LAN 10.x outside the mesh range is not the mesh");
        assert!(in_cidr(&ip("fd0c:1d01::7"), "fd0c:1d01::/32") && !in_cidr(&ip("fd0c:1d02::7"), "fd0c:1d01::/32"));
        assert!(in_cidr(&ip("::ffff:10.1.0.4"), "10.1.0.0/24"), "a v4-mapped address is its v4 address");
    }

    #[test]
    fn listening_addresses_are_read_from_proc_net() {
        // 8024 = 0x1F58. 0100007F = 127.0.0.1; 090000 0A... = 10.0.0.9 is 0A000009 read little-endian as 0900000A.
        let t = "  sl  local_address rem_address   st\n\
                 0: 0900000A:1F58 00000000:0000 0A 0 0\n\
                 1: 00000000:1F58 00000000:0000 0A 0 0\n\
                 2: 0100007F:1F59 00000000:0000 0A 0 0\n\
                 3: 0100007F:1F58 0200000A:C738 01 0 0\n";
        assert_eq!(listening(t, 8024), vec!["10.0.0.9".parse::<IpAddr>().unwrap(), "0.0.0.0".parse::<IpAddr>().unwrap()]);
        // ::1 and :: in tcp6's four little-endian words
        let t6 = "  sl local rem st\n\
                  0: 00000000000000000000000001000000:1F58 00000000000000000000000000000000:0000 0A 0 0\n\
                  1: 00000000000000000000000000000000:1F58 00000000000000000000000000000000:0000 0A 0 0\n";
        assert_eq!(listening(t6, 8024), vec!["::1".parse::<IpAddr>().unwrap(), "::".parse::<IpAddr>().unwrap()]);
    }

    #[test]
    fn state_words() {
        assert_eq!(state_of(false, false, 0, false), "not hosted");
        assert_eq!(state_of(true, true, 2, false), "connected");
        assert_eq!(state_of(true, true, 0, false), "listening");
        assert_eq!(state_of(true, false, 0, true), "starting");
        assert_eq!(state_of(true, false, 0, false), "down");
    }
}
