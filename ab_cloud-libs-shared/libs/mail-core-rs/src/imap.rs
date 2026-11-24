//! IMAP fetch through the core (#1837 phase 3): LOGIN, SELECT, UID SEARCH, UID FETCH (flags, INTERNALDATE, the whole RFC 5322
//! source by BODY.PEEK[]) and IDLE with a timeout, over an abstract byte stream ([ImapIo]). The bytes move through the app's own
//! socket (TLS, STARTTLS, trust store, mesh routing stay in Kotlin, exactly as JMAP goes through MailCoreTransport), so the core
//! adds no TLS stack, no async runtime and no crate: std only, and the .so does not grow by a dependency.
//! Tests drive it against a scripted server ([crate::fake_imap]) and the same scripted transcript the Kotlin ImapSession reads
//! (fixtures/imap/transcript.txt).
use serde::{Deserialize, Serialize};

#[derive(Debug)]
pub enum IoErr {
    Timeout,
    Closed,
    Other(String),
}

/// The byte pipe to the server. `read` answers what arrived (at least one byte), `Timeout` when nothing did within `timeout_ms`.
pub trait ImapIo {
    fn write(&mut self, bytes: &[u8]) -> Result<(), String>;
    fn read(&mut self, timeout_ms: u32) -> Result<Vec<u8>, IoErr>;
}

#[derive(Debug, Clone, Default, Deserialize)]
#[serde(rename_all = "camelCase", default)]
pub struct FetchArgs {
    pub user: String,
    pub password: String,
    pub mailbox: String,
    /// Explicit UIDs; when empty the newest `newest` UIDs of the mailbox (UID SEARCH ALL) are fetched.
    pub uids: Vec<u64>,
    pub newest: usize,
    pub timeout_ms: u32,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Fetched {
    pub uid: u64,
    pub flags: Vec<String>,
    pub internal_date: String,
    /// The raw RFC 5322 source, base64.
    pub raw: String,
    /// What the reader makes of it (kind and decoded text), so a caller can store and show it without a second parse.
    pub kind: String,
    pub text: String,
}

#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(rename_all = "camelCase")]
pub struct Report {
    pub exists: u64,
    pub uid_validity: u64,
    pub uid_next: u64,
    pub messages: Vec<Fetched>,
}

struct Untagged {
    /// The line with each literal replaced by `{LIT}`.
    text: String,
    literals: Vec<Vec<u8>>,
}

pub struct Client<'a> {
    io: &'a mut dyn ImapIo,
    buf: Vec<u8>,
    n: u32,
    timeout_ms: u32,
}

fn quote(s: &str) -> String {
    format!("\"{}\"", s.replace('\\', "\\\\").replace('"', "\\\"").replace(['\r', '\n'], ""))
}

fn io_err(e: IoErr) -> String {
    match e {
        IoErr::Timeout => "imap: timed out".into(),
        IoErr::Closed => "imap: connection closed".into(),
        IoErr::Other(s) => format!("imap: {s}"),
    }
}

impl<'a> Client<'a> {
    pub fn new(io: &'a mut dyn ImapIo, timeout_ms: u32) -> Self {
        Client { io, buf: Vec::new(), n: 0, timeout_ms: if timeout_ms == 0 { 30_000 } else { timeout_ms } }
    }

    fn fill(&mut self, timeout: u32) -> Result<(), IoErr> {
        let more = self.io.read(timeout)?;
        if more.is_empty() {
            return Err(IoErr::Closed);
        }
        self.buf.extend_from_slice(&more);
        Ok(())
    }

    fn line_with(&mut self, timeout: u32) -> Result<String, IoErr> {
        loop {
            if let Some(i) = self.buf.windows(2).position(|w| w == b"\r\n") {
                let l: Vec<u8> = self.buf.drain(..i + 2).collect();
                return Ok(String::from_utf8_lossy(&l[..i]).into_owned());
            }
            self.fill(timeout)?;
        }
    }

    fn take(&mut self, n: usize) -> Result<Vec<u8>, IoErr> {
        while self.buf.len() < n {
            self.fill(self.timeout_ms)?;
        }
        Ok(self.buf.drain(..n).collect())
    }

    /// One response line, the literals it announces (`{n}` at its end) read and the line continued after each.
    fn response_line(&mut self, timeout: u32) -> Result<Untagged, IoErr> {
        let mut text = self.line_with(timeout)?;
        let mut literals = Vec::new();
        loop {
            let Some(open) = text.rfind('{').filter(|_| text.ends_with('}')) else { break };
            let Ok(len) = text[open + 1..text.len() - 1].parse::<usize>() else { break };
            literals.push(self.take(len)?);
            text.truncate(open);
            text.push_str("{LIT}");
            let rest = self.line_with(self.timeout_ms)?;
            text.push_str(&rest);
        }
        Ok(Untagged { text, literals })
    }

    fn run(&mut self, cmd: &str) -> Result<Vec<Untagged>, String> {
        self.n += 1;
        let tag = format!("A{}", self.n);
        self.io.write(format!("{tag} {cmd}\r\n").as_bytes())?;
        let mut out = Vec::new();
        loop {
            let t = self.response_line(self.timeout_ms).map_err(io_err)?;
            if let Some(rest) = t.text.strip_prefix(&format!("{tag} ")) {
                return if rest.starts_with("OK") {
                    Ok(out)
                } else {
                    let verb = cmd.split(' ').next().unwrap_or("");
                    Err(format!("imap: {verb} refused: {}", if verb == "LOGIN" { "authentication failed" } else { rest }))
                };
            }
            out.push(t);
        }
    }

    /// The server's greeting, then LOGIN.
    pub fn login(&mut self, user: &str, password: &str) -> Result<(), String> {
        let g = self.response_line(self.timeout_ms).map_err(io_err)?;
        if !g.text.starts_with("* OK") && !g.text.starts_with("* PREAUTH") {
            return Err(format!("imap: unexpected greeting: {}", g.text));
        }
        self.run(&format!("LOGIN {} {}", quote(user), quote(password))).map(|_| ())
    }

    /// SELECT: (exists, uidvalidity, uidnext).
    pub fn select(&mut self, mailbox: &str) -> Result<(u64, u64, u64), String> {
        let (mut ex, mut uv, mut un) = (0, 0, 0);
        for u in self.run(&format!("SELECT {}", quote(mailbox)))? {
            let t = &u.text;
            if t.ends_with(" EXISTS") {
                ex = t.split(' ').nth(1).and_then(|n| n.parse().ok()).unwrap_or(0);
            }
            if let Some(n) = after_num(t, "[UIDVALIDITY ") {
                uv = n;
            }
            if let Some(n) = after_num(t, "[UIDNEXT ") {
                un = n;
            }
        }
        Ok((ex, uv, un))
    }

    pub fn uid_search_all(&mut self) -> Result<Vec<u64>, String> {
        let mut v = Vec::new();
        for u in self.run("UID SEARCH ALL")? {
            if let Some(rest) = u.text.strip_prefix("* SEARCH") {
                v.extend(rest.split_whitespace().filter_map(|n| n.parse::<u64>().ok()));
            }
        }
        Ok(v)
    }

    pub fn fetch(&mut self, uids: &[u64]) -> Result<Vec<Fetched>, String> {
        if uids.is_empty() {
            return Ok(Vec::new());
        }
        let set = uids.iter().map(|u| u.to_string()).collect::<Vec<_>>().join(",");
        let mut out = Vec::new();
        for u in self.run(&format!("UID FETCH {set} (UID FLAGS INTERNALDATE BODY.PEEK[])"))? {
            if !u.text.contains(" FETCH (") {
                continue;
            }
            let (Some(uid), Some(raw)) = (after_num(&u.text, "UID "), u.literals.first()) else { continue };
            let flags = u.text.split("FLAGS (").nth(1).and_then(|r| r.split(')').next()).map(|f| f.split_whitespace().map(String::from).collect()).unwrap_or_default();
            let internal_date = u.text.split("INTERNALDATE \"").nth(1).and_then(|r| r.split('"').next()).unwrap_or("").to_string();
            let (kind, text) = match crate::reader::parse(raw) {
                Ok(p) => (p.kind, p.text),
                Err(_) => ("text".to_string(), String::new()),
            };
            out.push(Fetched { uid, flags, internal_date, raw: crate::reader::base64(raw), kind, text });
        }
        Ok(out)
    }

    /// IDLE for up to `wait_ms`: true when the server announced a change (EXISTS / EXPUNGE / FETCH) before the timeout.
    pub fn idle(&mut self, wait_ms: u32) -> Result<bool, String> {
        self.n += 1;
        let tag = format!("A{}", self.n);
        self.io.write(format!("{tag} IDLE\r\n").as_bytes())?;
        let cont = self.response_line(self.timeout_ms).map_err(io_err)?;
        if !cont.text.starts_with('+') {
            return Err(format!("imap: IDLE refused: {}", cont.text));
        }
        let mut changed = false;
        match self.response_line(wait_ms) {
            Ok(u) => changed = u.text.starts_with("* ") && (u.text.ends_with("EXISTS") || u.text.ends_with("EXPUNGE") || u.text.contains(" FETCH")),
            Err(IoErr::Timeout) => {}
            Err(e) => return Err(io_err(e)),
        }
        self.io.write(b"DONE\r\n")?;
        loop {
            let t = self.response_line(self.timeout_ms).map_err(io_err)?;
            if t.text.starts_with(&format!("{tag} ")) {
                return Ok(changed);
            }
            if t.text.ends_with("EXISTS") || t.text.ends_with("EXPUNGE") {
                changed = true;
            }
        }
    }

    pub fn logout(&mut self) {
        let _ = self.run("LOGOUT");
    }
}

fn after_num(text: &str, key: &str) -> Option<u64> {
    let i = text.find(key)? + key.len();
    let d: String = text[i..].chars().take_while(|c| c.is_ascii_digit()).collect();
    d.parse().ok()
}

/// LOGIN, SELECT, then fetch `uids` (or the newest `newest` of the mailbox, 50 by default) and LOGOUT.
pub fn fetch_mailbox(io: &mut dyn ImapIo, a: &FetchArgs) -> Result<Report, String> {
    let mut c = Client::new(io, a.timeout_ms);
    c.login(&a.user, &a.password)?;
    let (exists, uid_validity, uid_next) = c.select(&a.mailbox)?;
    let mut uids = a.uids.clone();
    if uids.is_empty() {
        let mut all = c.uid_search_all()?;
        all.sort_unstable();
        let n = if a.newest == 0 { 50 } else { a.newest };
        uids = all.into_iter().rev().take(n).collect();
        uids.sort_unstable();
    }
    let messages = c.fetch(&uids)?;
    c.logout();
    Ok(Report { exists, uid_validity, uid_next, messages })
}
