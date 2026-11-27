//! The phased prober: DNS, TCP connect, TLS, HTTP first status line, each timed separately on ONE socket, so a failure names its phase.
//! std only: TLS is reported "not served (no TLS engine in this build)" for https, with dns and tcp still measured (a TLS failure is
//! never a "down" by itself). The JNI layer binds the socket to the VPN or the direct network before connect; no decision is made there.

use crate::model::{Phases, Status};
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpStream, ToSocketAddrs};
use std::time::{Duration, Instant};

#[derive(Debug, Clone, PartialEq)]
pub struct Target {
    pub scheme: String,
    pub host: String,
    pub port: u16,
    pub path: String,
}

/// `https://a.b:8443/x` -> Target; a bare `host:port` is tcp.
pub fn parse_url(url: &str) -> Option<Target> {
    let (scheme, rest) = match url.split_once("://") { Some((s, r)) => (s.to_ascii_lowercase(), r), None => ("tcp".to_string(), url) };
    let (hostport, path) = match rest.find('/') { Some(i) => (&rest[..i], &rest[i..]), None => (rest, "/") };
    let (host, port) = match hostport.rsplit_once(':') {
        Some((h, p)) if !h.is_empty() => (h.to_string(), p.parse::<u16>().ok()?),
        _ => {
            let d = match scheme.as_str() { "https" => 443, "http" => 80, _ => return None };
            (hostport.to_string(), d)
        }
    };
    if host.is_empty() || host.starts_with(':') { return None; }
    let host = host.trim_start_matches('[').trim_end_matches(']').to_string();
    Some(Target { scheme, host, port, path: path.to_string() })
}

#[derive(Debug, Clone, PartialEq)]
pub struct Probe {
    pub status: Status,
    pub phases: Phases,
    pub code: Option<u16>,
    /// which phase failed (dns, tcp, tls, http) and why; empty when it answered
    pub error: String,
    pub addr: String,
}

fn ms(t: Instant) -> u64 {
    t.elapsed().as_millis() as u64
}

/// The status line's code: "HTTP/1.1 302 Found" -> 302.
pub fn status_code(line: &str) -> Option<u16> {
    let mut it = line.split_whitespace();
    let v = it.next()?;
    if !v.starts_with("HTTP/") { return None; }
    it.next()?.parse().ok()
}

pub fn probe(url: &str, timeout: Duration) -> Probe {
    let mut ph = Phases::default();
    let fail = |ph: Phases, e: String, addr: String| Probe { status: Status::Down, phases: ph, code: None, error: e, addr };
    let t = match parse_url(url) { Some(t) => t, None => return fail(ph, format!("dns: not a URL: {}", url), String::new()) };
    let t0 = Instant::now();
    let addrs: Vec<SocketAddr> = match (t.host.as_str(), t.port).to_socket_addrs() {
        Ok(a) => a.collect(),
        Err(e) => { ph.dns = Some(ms(t0)); return fail(ph, format!("dns: {}", e), String::new()); }
    };
    ph.dns = Some(ms(t0));
    let addr = match addrs.first() { Some(a) => *a, None => return fail(ph, "dns: no address".into(), String::new()) };
    let t1 = Instant::now();
    let mut sock = match TcpStream::connect_timeout(&addr, timeout) {
        Ok(s) => s,
        Err(e) => { ph.tcp = Some(ms(t1)); return fail(ph, format!("tcp: {}", e), addr.to_string()); }
    };
    ph.tcp = Some(ms(t1));
    let _ = sock.set_read_timeout(Some(timeout));
    let _ = sock.set_write_timeout(Some(timeout));
    match t.scheme.as_str() {
        "tcp" => Probe { status: Status::Ok, phases: ph, code: None, error: String::new(), addr: addr.to_string() },
        "https" => Probe { status: Status::Warn, phases: ph, code: None, error: "tls: not served (no TLS engine in this build)".into(), addr: addr.to_string() },
        _ => {
            let t3 = Instant::now();
            let req = format!("GET {} HTTP/1.1\r\nHost: {}\r\nUser-Agent: health-core\r\nConnection: close\r\n\r\n", t.path, t.host);
            if let Err(e) = sock.write_all(req.as_bytes()) {
                ph.http = Some(ms(t3));
                return fail(ph, format!("http: {}", e), addr.to_string());
            }
            let mut buf = [0u8; 512];
            let mut got = Vec::new();
            loop {
                match sock.read(&mut buf) {
                    Ok(0) => break,
                    Ok(n) => { got.extend_from_slice(&buf[..n]); if got.contains(&b'\n') { break; } }
                    Err(e) => { ph.http = Some(ms(t3)); return fail(ph, format!("http: {}", e), addr.to_string()); }
                }
            }
            ph.http = Some(ms(t3));
            let line = String::from_utf8_lossy(&got).lines().next().unwrap_or("").to_string();
            match status_code(&line) {
                // any HTTP reply is an answer (a 302 to the login, a 401 from the gate); a 5xx from the edge is down
                Some(c) if c >= 500 => Probe { status: Status::Down, phases: ph, code: Some(c), error: format!("http: {}", line), addr: addr.to_string() },
                Some(c) => Probe { status: Status::Ok, phases: ph, code: Some(c), error: String::new(), addr: addr.to_string() },
                None => fail(ph, format!("http: no status line ({} bytes)", got.len()), addr.to_string()),
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;
    use std::thread;

    fn serve(reply: &'static str) -> u16 {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        thread::spawn(move || {
            if let Ok((mut s, _)) = l.accept() {
                let mut b = [0u8; 1024];
                let _ = s.read(&mut b);
                let _ = s.write_all(reply.as_bytes());
            }
        });
        port
    }

    #[test]
    fn urls_parse_with_default_ports() {
        assert_eq!(parse_url("https://a.b/x").unwrap(), Target { scheme: "https".into(), host: "a.b".into(), port: 443, path: "/x".into() });
        assert_eq!(parse_url("http://a.b:8080").unwrap().port, 8080);
        assert_eq!(parse_url("10.0.0.1:53").unwrap().scheme, "tcp");
        assert!(parse_url("10.0.0.1").is_none(), "tcp needs a port");
        assert!(parse_url("http://:80/").is_none());
        assert_eq!(status_code("HTTP/1.1 302 Found"), Some(302));
        assert_eq!(status_code("SSH-2.0"), None);
    }

    #[test]
    fn each_phase_is_reported_separately() {
        let port = serve("HTTP/1.1 302 Found\r\nLocation: /login\r\n\r\n");
        let p = probe(&format!("http://127.0.0.1:{}/", port), Duration::from_secs(2));
        assert_eq!(p.status, Status::Ok);
        assert_eq!(p.code, Some(302));
        assert!(p.phases.dns.is_some() && p.phases.tcp.is_some() && p.phases.http.is_some());
        assert_eq!(p.phases.tls, None, "plain http has no tls phase");
    }

    #[test]
    fn a_5xx_is_down_and_names_http() {
        let port = serve("HTTP/1.1 502 Bad Gateway\r\n\r\n");
        let p = probe(&format!("http://127.0.0.1:{}/", port), Duration::from_secs(2));
        assert_eq!(p.status, Status::Down);
        assert!(p.error.starts_with("http:"));
        assert_eq!(p.code, Some(502));
    }

    #[test]
    fn refused_is_a_tcp_failure_with_dns_measured() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        drop(l);
        let p = probe(&format!("http://127.0.0.1:{}/", port), Duration::from_secs(2));
        assert_eq!(p.status, Status::Down);
        assert!(p.error.starts_with("tcp:"), "{}", p.error);
        assert!(p.phases.dns.is_some() && p.phases.tcp.is_some());
        assert_eq!(p.phases.http, None);
    }

    #[test]
    fn dns_failure_stops_at_dns() {
        let p = probe("http://no-such-host.invalid/", Duration::from_secs(2));
        assert_eq!(p.status, Status::Down);
        assert!(p.error.starts_with("dns:"), "{}", p.error);
        assert_eq!(p.phases.tcp, None);
    }

    #[test]
    fn https_keeps_tcp_green_and_says_tls_is_not_served() {
        let l = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let p = probe(&format!("https://127.0.0.1:{}/", port), Duration::from_secs(2));
        drop(l);
        assert_eq!(p.status, Status::Warn);
        assert!(p.error.starts_with("tls: not served"));
        assert!(p.phases.tcp.is_some());
        let tcp = probe(&format!("127.0.0.1:{}", serve("x")), Duration::from_secs(2));
        assert_eq!(tcp.status, Status::Ok);
    }
}
