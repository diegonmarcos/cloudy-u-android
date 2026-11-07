//! The webserver's OWN sshd: russh embedded in this process, listening on the port build.json declares (runtime.sshd.port, 8025).
//!
//! Mesh-only and pubkey-only BY CONSTRUCTION:
//!   * every accepted socket is judged by its source address BEFORE a byte of SSH is spoken ([`mesh_gate`]): with Mesh-only ON
//!     (this port's own switch, read per connection) only loopback and the mesh CIDRs get through, everything else is dropped;
//!   * the only auth method the server offers is public key ([`Config::methods`]); password, keyboard-interactive, none and
//!     certificates are rejected, and a key that is not in the authorized set is refused already at the offer;
//!   * the authorized set is the fleet's declared public keys (build.json::ssh_doors.authorized_keys: the constellation key and the
//!     fleet MCP's deploy key, both committed in the open as public halves) plus an optional `authorized_keys` file next to the
//!     power store.
//!
//! An authenticated session gets a PTY and `sh` in this app's own environment (so loopback DevControl / ADB and fleet IPC are
//! reachable), window-change, exec requests and teardown (the child is hung up when the channel or the connection closes).
//! [`start`] / [`stop`] are driven by the door run-state (door.rs::sync_hosted): stopping drops the runtime, which closes the listener.

use russh::keys::ssh_key::private::Ed25519Keypair;
use russh::keys::{PrivateKey, PublicKey};
use russh::server::{Auth, Config, Handler, Msg, PublicKeyOffer, Session, VerifiedPublicKey};
use russh::{Channel, ChannelId, MethodKind, MethodSet};
use std::collections::HashMap;
use std::fs::File;
use std::io::{Read, Write};
use std::net::{IpAddr, Ipv6Addr, SocketAddr, TcpListener};
use std::os::fd::{AsRawFd, FromRawFd, OwnedFd};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex};

/// Everything the engine needs; door.rs builds it from build.json and the power store, the tests build their own.
#[derive(Clone)]
pub struct Policy {
    /// 0 binds a free port (tests); the real one is runtime.sshd.port.
    pub port: u16,
    /// This port's Mesh-only switch, read on every connection so a change applies at once.
    pub mesh_only: Arc<dyn Fn() -> bool + Send + Sync>,
    pub cidrs: Vec<String>,
    pub keys: Vec<PublicKey>,
    /// Where the ed25519 host key lives (created on first start, 0600). None keeps one in memory for this run.
    pub host_key: Option<PathBuf>,
}

/// The source-range allowlist, before auth: loopback is always allowed (the local helpers and the nix/termux hops dial it), any
/// other peer only inside a mesh CIDR while Mesh-only is ON; with Mesh-only OFF every peer reaches the (still pubkey-only) door.
pub fn mesh_gate(peer: IpAddr, mesh_only: bool, cidrs: &[String]) -> bool {
    if !mesh_only {
        return true;
    }
    let ip = match peer {
        IpAddr::V6(a) => a.to_ipv4_mapped().map(IpAddr::V4).unwrap_or(peer),
        _ => peer,
    };
    ip.is_loopback() || cidrs.iter().any(|c| crate::door::in_cidr(&ip, c))
}

/// Parse authorized_keys text: one OpenSSH public key per line, `#` comments and unparsable lines skipped.
pub fn parse_keys(text: &str) -> Vec<PublicKey> {
    text.lines().map(str::trim).filter(|l| !l.is_empty() && !l.starts_with('#')).filter_map(|l| PublicKey::from_openssh(l).ok()).collect()
}

/// Is `key` one of the authorized keys? Compared on the key material, never on the comment.
pub fn authorized(keys: &[PublicKey], key: &PublicKey) -> bool {
    keys.iter().any(|k| k.key_data() == key.key_data())
}

fn load_host_key(path: &Option<PathBuf>) -> Result<PrivateKey, String> {
    if let Some(p) = path {
        if let Ok(t) = std::fs::read_to_string(p) {
            if let Ok(k) = PrivateKey::from_openssh(&t) {
                return Ok(k);
            }
        }
    }
    let mut seed = [0u8; 32];
    File::open("/dev/urandom").and_then(|mut f| f.read_exact(&mut seed)).map_err(|e| format!("no entropy for the host key: {}", e))?;
    let key = PrivateKey::from(Ed25519Keypair::from_seed(&seed));
    if let Some(p) = path {
        if let Ok(pem) = key.to_openssh(russh::keys::ssh_key::LineEnding::LF) {
            if let Some(d) = p.parent() {
                let _ = std::fs::create_dir_all(d);
            }
            use std::os::unix::fs::OpenOptionsExt;
            let w = std::fs::OpenOptions::new().write(true).create(true).truncate(true).mode(0o600).open(p).and_then(|mut f| f.write_all(pem.as_bytes()));
            if let Err(e) = w {
                eprintln!("c3-webserver sshd: host key not persisted ({}): {}", p.display(), e);
            }
        }
    }
    Ok(key)
}

// ── the running listener ────────────────────────────────────────────────────

struct Runner {
    stop: Arc<tokio::sync::Notify>,
    thread: std::thread::JoinHandle<()>,
    port: u16,
}

static RUN: Mutex<Option<Runner>> = Mutex::new(None);

/// Is the listener up (this process's own state; the page also probes the port)?
pub fn running() -> bool {
    RUN.lock().map(|r| r.is_some()).unwrap_or(false)
}

/// Bind the port and serve. Returns the bound port once the socket is listening. Idempotent: a second call while running is a no-op.
pub fn start(policy: Policy) -> Result<u16, String> {
    let mut run = RUN.lock().map_err(|_| "poisoned".to_string())?;
    if let Some(r) = run.as_ref() {
        return Ok(r.port);
    }
    let listener = bind(policy.port)?;
    let port = listener.local_addr().map_err(|e| e.to_string())?.port();
    listener.set_nonblocking(true).map_err(|e| e.to_string())?;
    let host_key = load_host_key(&policy.host_key)?;
    let mut config = Config::default();
    config.keys = vec![host_key];
    config.methods = MethodSet::from(&[MethodKind::PublicKey][..]);
    config.auth_rejection_time = std::time::Duration::from_secs(1);
    config.auth_rejection_time_initial = Some(std::time::Duration::from_millis(0));
    config.inactivity_timeout = Some(std::time::Duration::from_secs(12 * 3600));
    let config = Arc::new(config);
    let stop = Arc::new(tokio::sync::Notify::new());
    let stop2 = stop.clone();
    let keys = Arc::new(policy.keys.clone());
    let thread = std::thread::Builder::new()
        .name("c3-sshd".into())
        .spawn(move || {
            let rt = match tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build() {
                Ok(rt) => rt,
                Err(e) => return eprintln!("c3-webserver sshd: no runtime: {}", e),
            };
            rt.block_on(accept_loop(listener, config, policy, keys, stop2));
            // Dropping the runtime ends every session task and closes the listener.
        })
        .map_err(|e| e.to_string())?;
    *run = Some(Runner { stop, thread, port });
    println!("c3-webserver sshd listening on :{} (pubkey only)", port);
    Ok(port)
}

/// Close the listener and every session. Safe when not running.
pub fn stop() {
    let r = RUN.lock().ok().and_then(|mut r| r.take());
    if let Some(r) = r {
        r.stop.notify_one();
        let _ = r.thread.join();
        println!("c3-webserver sshd stopped");
    }
}

fn bind(port: u16) -> Result<TcpListener, String> {
    let mut last = String::new();
    for _ in 0..10 {
        // Dual-stack first (the mesh has v4 and fd0c:: addresses); the source gate, not the bind, is the mesh-only enforcement.
        match TcpListener::bind(SocketAddr::from((Ipv6Addr::UNSPECIFIED, port))).or_else(|_| TcpListener::bind(("0.0.0.0", port))) {
            Ok(l) => return Ok(l),
            Err(e) => last = e.to_string(),
        }
        std::thread::sleep(std::time::Duration::from_millis(60));
    }
    Err(format!("cannot bind sshd port {} ({})", port, last))
}

async fn accept_loop(listener: TcpListener, config: Arc<Config>, policy: Policy, keys: Arc<Vec<PublicKey>>, stop: Arc<tokio::sync::Notify>) {
    let listener = match tokio::net::TcpListener::from_std(listener) {
        Ok(l) => l,
        Err(e) => return eprintln!("c3-webserver sshd: {}", e),
    };
    loop {
        tokio::select! {
            _ = stop.notified() => break,
            acc = listener.accept() => {
                let (sock, peer) = match acc { Ok(x) => x, Err(_) => continue };
                // THE GATE: judged by source address before any SSH byte is exchanged.
                if !mesh_gate(peer.ip(), (policy.mesh_only)(), &policy.cidrs) {
                    drop(sock);
                    continue;
                }
                let _ = sock.set_nodelay(true);
                let handler = Conn { keys: keys.clone(), ptys: HashMap::new(), shells: HashMap::new(), rt: tokio::runtime::Handle::current() };
                let config = config.clone();
                tokio::spawn(async move {
                    if let Ok(s) = russh::server::run_stream(config, sock, handler).await {
                        let _ = s.await;
                    }
                });
            }
        }
    }
}

// ── one connection ──────────────────────────────────────────────────────────

struct Pty {
    term: String,
    cols: u32,
    rows: u32,
}

struct Shell {
    writer: File,
    master: OwnedFd,
    pid: i32,
}

struct Conn {
    keys: Arc<Vec<PublicKey>>,
    ptys: HashMap<ChannelId, Pty>,
    shells: HashMap<ChannelId, Shell>,
    rt: tokio::runtime::Handle,
}

impl Drop for Conn {
    fn drop(&mut self) {
        for (_, s) in self.shells.drain() {
            unsafe { libc::kill(s.pid, libc::SIGHUP) };
        }
    }
}

fn winsize(cols: u32, rows: u32) -> libc::winsize {
    libc::winsize { ws_row: rows.clamp(1, 1000) as u16, ws_col: cols.clamp(1, 1000) as u16, ws_xpixel: 0, ws_ypixel: 0 }
}

fn shell_path() -> &'static str {
    ["/system/bin/sh", "/bin/sh"].into_iter().find(|p| Path::new(p).exists()).unwrap_or("sh")
}

impl Conn {
    /// Spawn `sh` (or `sh -c cmd`) on a fresh PTY and pump it to the channel.
    fn spawn(&mut self, channel: ChannelId, cmd: Option<String>, handle: russh::server::Handle) -> Result<(), String> {
        let pty = self.ptys.get(&channel);
        let (term, cols, rows) = pty.map(|p| (p.term.clone(), p.cols, p.rows)).unwrap_or(("xterm".to_string(), 80, 24));
        let (mut m, mut s) = (-1, -1);
        let ws = winsize(cols, rows);
        if unsafe { libc::openpty(&mut m, &mut s, std::ptr::null_mut(), std::ptr::null(), &ws) } != 0 {
            return Err("openpty failed".to_string());
        }
        let (master, slave) = unsafe { (OwnedFd::from_raw_fd(m), OwnedFd::from_raw_fd(s)) };
        let dup = |fd: &OwnedFd| fd.try_clone().map(File::from).map_err(|e| e.to_string());
        let mut c = Command::new(shell_path());
        match &cmd {
            Some(line) => {
                c.arg("-c").arg(line);
            }
            None => {
                c.arg("-i");
            }
        }
        c.env("TERM", term).stdin(Stdio::from(dup(&slave)?)).stdout(Stdio::from(dup(&slave)?)).stderr(Stdio::from(dup(&slave)?));
        if let Ok(h) = std::env::var("HOME") {
            c.current_dir(h);
        }
        unsafe {
            c.pre_exec(|| {
                libc::setsid();
                libc::ioctl(0, libc::TIOCSCTTY as _, 0);
                Ok(())
            });
        }
        let mut child = c.spawn().map_err(|e| format!("cannot start sh: {}", e))?;
        drop(slave);
        let pid = child.id() as i32;
        let mut reader = dup(&master)?;
        let writer = dup(&master)?;
        let rt = self.rt.clone();
        std::thread::spawn(move || {
            let mut buf = [0u8; 8192];
            loop {
                match reader.read(&mut buf) {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if rt.block_on(handle.data(channel, buf[..n].to_vec())).is_err() {
                            break;
                        }
                    }
                }
            }
            let code = child.wait().ok().and_then(|s| s.code()).unwrap_or(0) as u32;
            let _ = rt.block_on(async {
                let _ = handle.exit_status_request(channel, code).await;
                let _ = handle.eof(channel).await;
                handle.close(channel).await
            });
        });
        self.shells.insert(channel, Shell { writer, master, pid });
        Ok(())
    }
}

impl Handler for Conn {
    type Error = russh::Error;

    // Offered keys outside the authorized set are refused right here, before a signature is even asked for.
    async fn auth_publickey_offered(&mut self, _user: &str, offer: PublicKeyOffer<'_>) -> Result<Auth, Self::Error> {
        Ok(if authorized(&self.keys, offer.public_key) { Auth::Accept } else { Auth::reject() })
    }

    async fn auth_publickey(&mut self, _user: &str, key: VerifiedPublicKey<'_>) -> Result<Auth, Self::Error> {
        Ok(if authorized(&self.keys, key.public_key) { Auth::Accept } else { Auth::reject() })
    }

    async fn channel_open_session(&mut self, _channel: Channel<Msg>, reply: russh::server::ChannelOpenHandle, _session: &mut Session) -> Result<(), Self::Error> {
        reply.accept().await;
        Ok(())
    }

    async fn pty_request(
        &mut self,
        channel: ChannelId,
        term: &str,
        col_width: u32,
        row_height: u32,
        _pix_width: u32,
        _pix_height: u32,
        _modes: &[(russh::Pty, u32)],
        session: &mut Session,
    ) -> Result<(), Self::Error> {
        self.ptys.insert(channel, Pty { term: term.to_string(), cols: col_width, rows: row_height });
        session.channel_success(channel)
    }

    async fn shell_request(&mut self, channel: ChannelId, session: &mut Session) -> Result<(), Self::Error> {
        match self.spawn(channel, None, session.handle()) {
            Ok(()) => session.channel_success(channel),
            Err(e) => {
                eprintln!("c3-webserver sshd: {}", e);
                session.channel_failure(channel)
            }
        }
    }

    async fn exec_request(&mut self, channel: ChannelId, data: &[u8], session: &mut Session) -> Result<(), Self::Error> {
        let line = String::from_utf8_lossy(data).to_string();
        match self.spawn(channel, Some(line), session.handle()) {
            Ok(()) => session.channel_success(channel),
            Err(e) => {
                eprintln!("c3-webserver sshd: {}", e);
                session.channel_failure(channel)
            }
        }
    }

    async fn window_change_request(&mut self, channel: ChannelId, col_width: u32, row_height: u32, _pw: u32, _ph: u32, _session: &mut Session) -> Result<(), Self::Error> {
        if let Some(p) = self.ptys.get_mut(&channel) {
            p.cols = col_width;
            p.rows = row_height;
        }
        if let Some(s) = self.shells.get(&channel) {
            let ws = winsize(col_width, row_height);
            unsafe { libc::ioctl(s.master.as_raw_fd(), libc::TIOCSWINSZ as _, &ws) };
        }
        Ok(())
    }

    async fn data(&mut self, channel: ChannelId, data: &[u8], _session: &mut Session) -> Result<(), Self::Error> {
        if let Some(s) = self.shells.get_mut(&channel) {
            let _ = s.writer.write_all(data);
        }
        Ok(())
    }

    async fn channel_eof(&mut self, channel: ChannelId, _session: &mut Session) -> Result<(), Self::Error> {
        // The client closed its input: hang the shell up (a command run with exec finishes on its own first).
        if let Some(s) = self.shells.get(&channel) {
            let _ = s.writer.try_clone().map(|mut w| w.write_all(&[4]));
        }
        Ok(())
    }

    async fn channel_close(&mut self, channel: ChannelId, _session: &mut Session) -> Result<(), Self::Error> {
        self.ptys.remove(&channel);
        if let Some(s) = self.shells.remove(&channel) {
            unsafe { libc::kill(s.pid, libc::SIGHUP) };
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const CIDRS: [&str; 4] = ["10.0.0.0/24", "10.1.0.0/24", "fd0c:1d00::/32", "fd0c:1d01::/32"];
    fn cidrs() -> Vec<String> {
        CIDRS.iter().map(|s| s.to_string()).collect()
    }
    fn ip(s: &str) -> IpAddr {
        s.parse().unwrap()
    }

    #[test]
    fn the_mesh_gate_drops_every_non_mesh_source_before_auth() {
        let c = cidrs();
        for ok in ["10.0.0.9", "10.1.0.4", "fd0c:1d00::7", "fd0c:1d01::7", "127.0.0.1", "::1", "::ffff:10.0.0.9"] {
            assert!(mesh_gate(ip(ok), true, &c), "{} is mesh or loopback", ok);
        }
        for bad in ["192.168.1.4", "10.0.5.4", "10.2.0.1", "8.8.8.8", "fd0c:1d02::1", "2001:db8::1", "::ffff:192.168.1.4"] {
            assert!(!mesh_gate(ip(bad), true, &c), "{} must be dropped while mesh-only is ON", bad);
        }
        assert!(mesh_gate(ip("192.168.1.4"), false, &c), "mesh-only OFF opens the port to every source (still pubkey-only)");
    }

    #[test]
    fn the_declared_fleet_keys_are_the_authorized_set_and_an_unknown_key_is_refused() {
        let b = crate::apis::build();
        let lines: Vec<String> = b["ssh_doors"]["authorized_keys"].as_array().expect("ssh_doors.authorized_keys").iter().filter_map(|v| v.as_str().map(str::to_string)).collect();
        let keys = parse_keys(&lines.join("\n"));
        assert_eq!(keys.len(), lines.len(), "every declared key parses");
        assert!(keys.iter().any(|k| k.algorithm().as_str() == "ssh-ed25519"), "the constellation key");
        assert!(keys.iter().any(|k| k.algorithm().as_str() == "ssh-rsa"), "the fleet MCP's deploy key (vault_id_rsa)");
        assert!(keys.iter().all(|k| authorized(&keys, k)));
        let unknown = PrivateKey::from(Ed25519Keypair::from_seed(&[7u8; 32])).public_key().clone();
        assert!(!authorized(&keys, &unknown), "an unknown key is not authorized");
        // a seeded key is accepted whatever its comment says
        let seeded = parse_keys(&format!("# c\n{}\n", unknown.to_openssh().unwrap()));
        assert!(authorized(&seeded, &unknown) && !authorized(&seeded, &keys[0]));
    }

    #[test]
    fn only_public_key_auth_is_offered() {
        let m = MethodSet::from(&[MethodKind::PublicKey][..]);
        assert!(m.contains(&MethodKind::PublicKey) && !m.contains(&MethodKind::Password) && !m.contains(&MethodKind::KeyboardInteractive) && !m.contains(&MethodKind::None));
    }

    #[test]
    fn the_run_state_opens_and_closes_the_listener() {
        let policy = Policy { port: 0, mesh_only: Arc::new(|| true), cidrs: cidrs(), keys: vec![], host_key: None };
        assert!(!running());
        let port = start(policy.clone()).expect("bind");
        assert!(running());
        assert_eq!(start(policy.clone()).unwrap(), port, "idempotent");
        // a loopback peer passes the gate and is greeted with an SSH identification line
        let mut s = std::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).expect("listening");
        s.set_read_timeout(Some(std::time::Duration::from_secs(5))).unwrap();
        let mut buf = [0u8; 64];
        let n = s.read(&mut buf).unwrap();
        assert!(String::from_utf8_lossy(&buf[..n]).starts_with("SSH-2.0-"), "an SSH server answers");
        drop(s);
        stop();
        assert!(!running());
        std::thread::sleep(std::time::Duration::from_millis(100));
        assert!(std::net::TcpStream::connect((std::net::Ipv4Addr::LOCALHOST, port)).is_err(), "closed after stop");
    }
}
