//! The app's private DNS server: UDP 127.0.0.1:<serving.dns.port> (5354).
//!
//! Android cannot bind :53 without root and cannot point the system resolver at
//! loopback, so this is NOT a system resolver and nothing is told to use it.
//! Our own apps (cloud-browser) ask it directly, only for the exact names in
//! build.json::serving.endpoints, and map the port from the same declaration
//! (DNS carries no ports). It answers A queries for those names with the
//! loopback address, answers NODATA for other types of those names, and REFUSES
//! every other name: it is not a general resolver and never forwards anything.

use crate::serving;
use std::net::{Ipv4Addr, UdpSocket};

const TYPE_A: u16 = 1;
const TYPE_ANY: u16 = 255;
const CLASS_IN: u16 = 1;

pub const RCODE_OK: u8 = 0;
pub const RCODE_FORMERR: u8 = 1;
pub const RCODE_NOTIMP: u8 = 4;
pub const RCODE_REFUSED: u8 = 5;

/// Parse the single question of `q`: (lowercased name without the trailing dot, qtype, qclass, end offset).
fn question(q: &[u8]) -> Option<(String, u16, u16, usize)> {
    let mut i = 12;
    let mut name = String::new();
    loop {
        let len = *q.get(i)? as usize;
        i += 1;
        if len == 0 {
            break;
        }
        // compression pointers (0xC0) never appear in a question we answer; labels are <= 63
        if len > 63 {
            return None;
        }
        let label = q.get(i..i + len)?;
        if !name.is_empty() {
            name.push('.');
        }
        name.push_str(&String::from_utf8_lossy(label).to_ascii_lowercase());
        i += len;
        if name.len() > 253 {
            return None;
        }
    }
    let t = u16::from_be_bytes([*q.get(i)?, *q.get(i + 1)?]);
    let c = u16::from_be_bytes([*q.get(i + 2)?, *q.get(i + 3)?]);
    Some((name, t, c, i + 4))
}

/// Build the reply to `q` for `names` (the declared ones), answering with `answer`.
/// None = drop the datagram (not a query, or too short to even echo an id).
pub fn respond(q: &[u8], names: &[&str], answer: Ipv4Addr, ttl: u32) -> Option<Vec<u8>> {
    if q.len() < 12 {
        return None;
    }
    let flags = u16::from_be_bytes([q[2], q[3]]);
    if flags & 0x8000 != 0 {
        return None; // a response, not a query
    }
    let opcode = ((flags >> 11) & 0xF) as u8;
    let rd = flags & 0x0100;
    let qd = u16::from_be_bytes([q[4], q[5]]);
    let mut out: Vec<u8> = Vec::with_capacity(64);
    out.extend_from_slice(&q[0..2]);

    let fail = |rcode: u8, out: &mut Vec<u8>| {
        let f: u16 = 0x8000 | rd | rcode as u16;
        out.extend_from_slice(&f.to_be_bytes());
        out.extend_from_slice(&[0, 0, 0, 0, 0, 0, 0, 0]);
    };
    if opcode != 0 {
        fail(RCODE_NOTIMP, &mut out);
        return Some(out);
    }
    let parsed = if qd == 1 { question(q) } else { None };
    let (name, qtype, qclass, end) = match parsed {
        Some(p) => p,
        None => {
            fail(RCODE_FORMERR, &mut out);
            return Some(out);
        }
    };
    if !names.iter().any(|n| *n == name) {
        // Not ours: refuse, with the question echoed. Never forwarded, never guessed.
        let f: u16 = 0x8000 | rd | RCODE_REFUSED as u16;
        out.extend_from_slice(&f.to_be_bytes());
        out.extend_from_slice(&[0, 1, 0, 0, 0, 0, 0, 0]);
        out.extend_from_slice(&q[12..end]);
        return Some(out);
    }
    let answers = qclass == CLASS_IN && (qtype == TYPE_A || qtype == TYPE_ANY);
    // QR=1, AA=1, RD echoed, RCODE 0. NODATA (no answer, NOERROR) for AAAA/HTTPS/TXT of our own names.
    let f: u16 = 0x8400 | rd | RCODE_OK as u16;
    out.extend_from_slice(&f.to_be_bytes());
    out.extend_from_slice(&[0, 1]);
    out.extend_from_slice(&[0, if answers { 1 } else { 0 }]);
    out.extend_from_slice(&[0, 0, 0, 0]);
    out.extend_from_slice(&q[12..end]);
    if answers {
        out.extend_from_slice(&[0xC0, 0x0C]); // name: pointer to the question
        out.extend_from_slice(&TYPE_A.to_be_bytes());
        out.extend_from_slice(&CLASS_IN.to_be_bytes());
        out.extend_from_slice(&ttl.to_be_bytes());
        out.extend_from_slice(&[0, 4]);
        out.extend_from_slice(&answer.octets());
    }
    Some(out)
}

/// The query a client sends for `name` (A, IN, recursion desired). Used by the tests and mirrored
/// by the browser's loopback client (libs:browser PrivateNames).
pub fn query_a(id: u16, name: &str) -> Vec<u8> {
    let mut q = Vec::new();
    q.extend_from_slice(&id.to_be_bytes());
    q.extend_from_slice(&[0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0]);
    for label in name.trim_end_matches('.').split('.') {
        q.push(label.len() as u8);
        q.extend_from_slice(label.as_bytes());
    }
    q.push(0);
    q.extend_from_slice(&TYPE_A.to_be_bytes());
    q.extend_from_slice(&CLASS_IN.to_be_bytes());
    q
}

/// Bind `host:port` with SO_REUSEADDR and SO_REUSEPORT set first. The declared port is 5354 and NOT 5353 on purpose: 5353 is
/// the mDNS port, which Android's own mDNS daemon (and the SuperApp's firestack) bind on every address; a plain
/// `UdpSocket::bind` of 127.0.0.1:5353 failed with "Address already in use" on the emulator, and sharing it would
/// deliver our queries to the wrong socket. The reuse options stay as hardening against any other holder of the port.
/// Falls back to the plain bind.
fn bind_shared(host: &str, port: u16) -> std::io::Result<UdpSocket> {
    if let Ok(ip) = host.parse::<Ipv4Addr>() {
        // SAFETY: plain libc socket calls; the fd is closed on every error path and otherwise owned by the UdpSocket.
        unsafe {
            let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM, 0);
            if fd >= 0 {
                let on: libc::c_int = 1;
                let len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
                let p = &on as *const libc::c_int as *const libc::c_void;
                libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_REUSEADDR, p, len);
                libc::setsockopt(fd, libc::SOL_SOCKET, libc::SO_REUSEPORT, p, len);
                let mut addr: libc::sockaddr_in = std::mem::zeroed();
                addr.sin_family = libc::AF_INET as libc::sa_family_t;
                addr.sin_port = port.to_be();
                addr.sin_addr = libc::in_addr { s_addr: u32::from_ne_bytes(ip.octets()) };
                let rc = libc::bind(fd, &addr as *const libc::sockaddr_in as *const libc::sockaddr, std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t);
                if rc == 0 {
                    use std::os::fd::FromRawFd;
                    return Ok(UdpSocket::from_raw_fd(fd));
                }
                libc::close(fd);
            }
        }
    }
    UdpSocket::bind((host, port))
}

/// Bind the declared address and answer on a thread of its own. Loopback bind, loopback peers only.
pub fn start(stop: std::sync::Arc<std::sync::atomic::AtomicBool>) -> Result<(), String> {
    let sv = serving::get();
    let sock = bind_shared(sv.dns.host.as_str(), sv.dns.port).map_err(|e| format!("cannot bind udp {}:{} ({})", sv.dns.host, sv.dns.port, e))?;
    let answer: Ipv4Addr = sv.dns.answer.parse().map_err(|_| format!("serving.dns.answer {} is not an IPv4 address", sv.dns.answer))?;
    let ttl = sv.dns.ttl_s;
    println!("c3-webserver dns on udp {}:{} answers {} for {}", sv.dns.host, sv.dns.port, answer, serving::names().join(", "));
    std::thread::spawn(move || {
        let names = serving::names();
        let mut buf = [0u8; 512];
        // #1502 a stopped server frees the port: the wait ends every 2 s to look at the flag (a blocking read cannot be cancelled).
        let _ = sock.set_read_timeout(Some(std::time::Duration::from_secs(2)));
        loop {
            if stop.load(std::sync::atomic::Ordering::SeqCst) {
                return;
            }
            let (n, peer) = match sock.recv_from(&mut buf) {
                Ok(x) => x,
                Err(_) => continue,
            };
            if !peer.ip().is_loopback() {
                continue;
            }
            if let Some(reply) = respond(&buf[..n], &names, answer, ttl) {
                let _ = sock.send_to(&reply, peer);
            }
        }
    });
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const NAMES: [&str; 4] = ["termux.app", "nix.app", "emulated.app", "cloud.app"];
    const LO: Ipv4Addr = Ipv4Addr::new(127, 0, 0, 1);

    fn rcode(r: &[u8]) -> u8 {
        r[3] & 0xF
    }
    fn ancount(r: &[u8]) -> u16 {
        u16::from_be_bytes([r[6], r[7]])
    }

    #[test]
    fn binds_beside_a_wildcard_socket_on_the_same_port() {
        // What Android's mDNS daemon does on 5353: a wildcard bind that sets SO_REUSEADDR (std's bind sets none).
        let wild = bind_shared("0.0.0.0", 0).unwrap();
        let port = wild.local_addr().unwrap().port();
        let lo = bind_shared("127.0.0.1", port).expect("the loopback bind must coexist with a wildcard one");
        let c = UdpSocket::bind("127.0.0.1:0").unwrap();
        c.send_to(b"ping", ("127.0.0.1", port)).unwrap();
        lo.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
        let mut b = [0u8; 8];
        assert_eq!(lo.recv_from(&mut b).unwrap().0, 4, "the more specific loopback socket gets the datagram");
    }

    #[test]
    fn each_declared_name_resolves_to_loopback() {
        for n in serving::names() {
            let r = respond(&query_a(0xBEEF, n), &serving::names(), LO, 30).unwrap();
            assert_eq!(&r[0..2], &[0xBE, 0xEF], "the id is echoed");
            assert_eq!(r[2] & 0x80, 0x80, "QR");
            assert_eq!(r[2] & 0x04, 0x04, "AA");
            assert_eq!(rcode(&r), RCODE_OK);
            assert_eq!(ancount(&r), 1);
            assert_eq!(&r[r.len() - 4..], &[127, 0, 0, 1], "{} answers 127.0.0.1", n);
        }
    }

    #[test]
    fn the_declared_names_are_the_four_and_only_the_four() {
        let mut got = serving::names();
        got.sort();
        let mut want = NAMES.to_vec();
        want.sort();
        assert_eq!(got, want);
    }

    #[test]
    fn case_and_trailing_dot_do_not_matter() {
        let r = respond(&query_a(1, "EMULATED.App."), &NAMES, LO, 30).unwrap();
        assert_eq!(ancount(&r), 1);
    }

    #[test]
    fn every_other_name_is_refused_never_answered() {
        for n in ["termux.app.evil.com", "www.termux.app", "app", "example.com", "a.termux.app", "termux.dev", "emulated.app.x"] {
            let r = respond(&query_a(2, n), &NAMES, LO, 30).unwrap();
            assert_eq!(rcode(&r), RCODE_REFUSED, "{} must be refused", n);
            assert_eq!(ancount(&r), 0);
        }
    }

    #[test]
    fn other_record_types_of_our_names_are_nodata() {
        let mut q = query_a(3, "nix.app");
        let n = q.len();
        q[n - 4..n - 2].copy_from_slice(&28u16.to_be_bytes()); // AAAA
        let r = respond(&q, &NAMES, LO, 30).unwrap();
        assert_eq!(rcode(&r), RCODE_OK);
        assert_eq!(ancount(&r), 0);
    }

    #[test]
    fn junk_is_dropped_or_formerr() {
        assert!(respond(&[1, 2, 3], &NAMES, LO, 30).is_none());
        let mut resp = query_a(4, "nix.app");
        resp[2] |= 0x80;
        assert!(respond(&resp, &NAMES, LO, 30).is_none(), "a response is not answered");
        let mut bad = query_a(5, "nix.app");
        bad[5] = 2; // QDCOUNT 2
        assert_eq!(rcode(&respond(&bad, &NAMES, LO, 30).unwrap()), RCODE_FORMERR);
        let mut notimp = query_a(6, "nix.app");
        notimp[2] |= 0x28; // opcode 5
        assert_eq!(rcode(&respond(&notimp, &NAMES, LO, 30).unwrap()), RCODE_NOTIMP);
        let truncated = &query_a(7, "nix.app")[..16];
        assert_eq!(rcode(&respond(truncated, &NAMES, LO, 30).unwrap()), RCODE_FORMERR);
    }

    #[test]
    fn it_answers_over_a_real_udp_socket() {
        let sock = UdpSocket::bind("127.0.0.1:0").unwrap();
        let addr = sock.local_addr().unwrap();
        std::thread::spawn(move || {
            let mut buf = [0u8; 512];
            let (n, peer) = sock.recv_from(&mut buf).unwrap();
            let r = respond(&buf[..n], &serving::names(), LO, 30).unwrap();
            sock.send_to(&r, peer).unwrap();
        });
        let c = UdpSocket::bind("127.0.0.1:0").unwrap();
        c.set_read_timeout(Some(std::time::Duration::from_secs(2))).unwrap();
        c.send_to(&query_a(9, "cloud.app"), addr).unwrap();
        let mut buf = [0u8; 512];
        let n = c.recv(&mut buf).unwrap();
        assert_eq!(&buf[n - 4..n], &[127, 0, 0, 1]);
    }
}
