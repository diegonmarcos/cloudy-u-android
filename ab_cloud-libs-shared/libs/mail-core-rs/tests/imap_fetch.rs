//! IMAP through the core (#1837 phase 3) against the scripted transcript shared with the Kotlin ImapSession test
//! (fixtures/imap/transcript.txt): LOGIN, SELECT, UID SEARCH, UID FETCH with a literal, IDLE timeout, a refused login.
use mail_core::imap::{fetch_mailbox, Client, FetchArgs, ImapIo, IoErr};
use std::collections::VecDeque;
use std::fs;
use std::path::PathBuf;

fn fixtures() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures")
}

fn eml() -> Vec<u8> {
    fs::read(fixtures().join("parity/latin1-qp.eml")).unwrap()
}

/// command prefix -> answer template bytes.
fn transcript() -> Vec<(String, String)> {
    let text = fs::read_to_string(fixtures().join("imap/transcript.txt")).unwrap();
    let mut out: Vec<(String, String)> = Vec::new();
    for l in text.lines() {
        if let Some(p) = l.strip_prefix("## ") {
            out.push((p.to_string(), String::new()));
        } else if let Some(last) = out.last_mut() {
            last.1.push_str(l);
            last.1.push_str("\r\n");
        }
    }
    out
}

struct Fake {
    script: Vec<(String, String)>,
    out: VecDeque<u8>,
    input: Vec<u8>,
    idle_tag: Option<String>,
    login_ok: bool,
    commands: Vec<String>,
    idle_event: bool,
}

impl Fake {
    fn new(login_ok: bool) -> Self {
        let mut f = Fake { script: transcript(), out: VecDeque::new(), input: Vec::new(), idle_tag: None, login_ok, commands: vec![], idle_event: false };
        f.out.extend(b"* OK IMAP4rev1 ready\r\n");
        f
    }
}

impl ImapIo for Fake {
    fn write(&mut self, bytes: &[u8]) -> Result<(), String> {
        self.input.extend_from_slice(bytes);
        while let Some(i) = self.input.windows(2).position(|w| w == b"\r\n") {
            let line = String::from_utf8_lossy(&self.input.drain(..i + 2).collect::<Vec<u8>>()).trim_end().to_string();
            if line == "DONE" {
                let t = self.idle_tag.take().unwrap();
                self.out.extend(format!("{t} OK idle terminated\r\n").bytes());
                continue;
            }
            let (tag, cmd) = line.split_once(' ').unwrap();
            self.commands.push(cmd.to_string());
            if cmd == "IDLE" {
                self.idle_tag = Some(tag.to_string());
                self.out.extend(b"+ idling\r\n");
                if self.idle_event {
                    self.out.extend(b"* 2 EXISTS\r\n");
                }
                continue;
            }
            if cmd.starts_with("LOGIN") && !self.login_ok {
                self.out.extend(format!("{tag} NO [AUTHENTICATIONFAILED] bad\r\n").bytes());
                continue;
            }
            let (_, tpl) = self.script.iter().find(|(p, _)| cmd.starts_with(p.as_str())).expect(&format!("unscripted command {cmd}"));
            let body = eml();
            let mut ans = tpl.replace("{tag}", tag).replace("{LEN}", &body.len().to_string()).into_bytes();
            if let Some(i) = ans.windows(6).position(|w| w == b"{BODY}") {
                ans.splice(i..i + 6, body);
            }
            self.out.extend(ans);
        }
        Ok(())
    }

    fn read(&mut self, _t: u32) -> Result<Vec<u8>, IoErr> {
        if self.out.is_empty() {
            return Err(IoErr::Timeout);
        }
        // Hand the bytes over in awkward 7-byte slices: literals and lines must survive any chunking.
        let n = self.out.len().min(7);
        Ok(self.out.drain(..n).collect())
    }
}

fn args() -> FetchArgs {
    FetchArgs { user: "tester".into(), password: "secret".into(), mailbox: "INBOX".into(), newest: 10, ..Default::default() }
}

#[test]
fn login_select_search_fetch_reads_the_transcript() {
    let mut f = Fake::new(true);
    let r = fetch_mailbox(&mut f, &args()).unwrap();
    assert_eq!((r.exists, r.uid_validity, r.uid_next), (1, 42, 8));
    assert_eq!(r.messages.len(), 1);
    let m = &r.messages[0];
    assert_eq!(m.uid, 7);
    assert_eq!(m.flags, vec!["\\Seen".to_string()]);
    assert_eq!(m.internal_date, "01-Jan-2024 00:00:00 +0000");
    assert_eq!(m.raw, mail_core_b64(&eml()));
    assert!(m.text.contains("Notificación oficial: España"), "{}", m.text);
    assert_eq!(f.commands[0], "LOGIN \"tester\" \"secret\"");
    assert!(f.commands.iter().any(|c| c == "UID FETCH 7 (UID FLAGS INTERNALDATE BODY.PEEK[])"), "{:?}", f.commands);
}

fn mail_core_b64(b: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::new();
    for c in b.chunks(3) {
        let (b1, b2) = (*c.get(1).unwrap_or(&0) as u32, *c.get(2).unwrap_or(&0) as u32);
        let n = ((c[0] as u32) << 16) | (b1 << 8) | b2;
        s.push(T[(n >> 18 & 63) as usize] as char);
        s.push(T[(n >> 12 & 63) as usize] as char);
        s.push(if c.len() > 1 { T[(n >> 6 & 63) as usize] as char } else { '=' });
        s.push(if c.len() > 2 { T[(n & 63) as usize] as char } else { '=' });
    }
    s
}

#[test]
fn a_refused_login_is_an_error_that_never_echoes_the_password() {
    let mut f = Fake::new(false);
    let e = fetch_mailbox(&mut f, &args()).unwrap_err();
    assert!(e.contains("LOGIN refused") && !e.contains("secret"), "{e}");
}

#[test]
fn idle_times_out_quietly_and_reports_a_push() {
    let mut f = Fake::new(true);
    {
        let mut c = Client::new(&mut f, 1000);
        c.login("tester", "secret").unwrap();
        c.select("INBOX").unwrap();
        assert!(!c.idle(10).unwrap(), "no change: the IDLE timeout is not a push");
    }
    let mut g = Fake::new(true);
    g.idle_event = true;
    let mut c = Client::new(&mut g, 1000);
    c.login("tester", "secret").unwrap();
    assert!(c.idle(10).unwrap());
}

#[test]
fn a_dead_connection_is_an_error() {
    struct Dead;
    impl ImapIo for Dead {
        fn write(&mut self, _: &[u8]) -> Result<(), String> {
            Ok(())
        }
        fn read(&mut self, _: u32) -> Result<Vec<u8>, IoErr> {
            Ok(Vec::new())
        }
    }
    assert!(fetch_mailbox(&mut Dead, &args()).unwrap_err().contains("closed"));
}
