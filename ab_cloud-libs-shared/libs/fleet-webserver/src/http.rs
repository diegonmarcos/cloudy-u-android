//! The HTTP/1.1 plumbing every listener of the fleet shares: parse a request, hand it to a
//! handler, write the response. GET and HEAD, plus POST (a body up to write::MAX_BODY, read for
//! the handler; only /__api__/write accepts one), loopback peers only, one thread per
//! connection, `connection: close`.

use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub struct Req {
    pub method: String,
    /// Percent-decoded path.
    pub path: String,
    pub query: Vec<(String, String)>,
    /// Lower-cased names.
    pub headers: Vec<(String, String)>,
    /// The request body (POST only; empty otherwise).
    pub body: Vec<u8>,
}

impl Req {
    pub fn q(&self, key: &str) -> Option<&str> {
        self.query.iter().find(|(k, _)| k == key).map(|(_, v)| v.as_str())
    }
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers.iter().find(|(k, _)| k == name).map(|(_, v)| v.as_str())
    }
}

pub struct Resp {
    pub status: u16,
    pub ctype: &'static str,
    pub body: Vec<u8>,
    pub headers: Vec<(String, String)>,
    /// A file sent from disk instead of from `body` (length declared by the caller).
    pub stream: Option<(std::fs::File, u64)>,
}

impl Resp {
    pub fn bytes(status: u16, ctype: &'static str, body: Vec<u8>) -> Resp {
        Resp { status, ctype, body, headers: Vec::new(), stream: None }
    }
    pub fn stream(status: u16, ctype: &'static str, file: std::fs::File, len: u64) -> Resp {
        Resp { status, ctype, body: Vec::new(), headers: Vec::new(), stream: Some((file, len)) }
    }
    pub fn json(status: u16, body: String) -> Resp {
        Resp::bytes(status, "application/json", body.into_bytes())
    }
    pub fn html(body: String) -> Resp {
        Resp::bytes(200, "text/html; charset=utf-8", body.into_bytes())
    }
    pub fn text(status: u16, body: &str) -> Resp {
        Resp::bytes(status, "text/plain; charset=utf-8", body.as_bytes().to_vec())
    }
    pub fn with(mut self, k: &str, v: &str) -> Resp {
        self.headers.push((k.to_string(), v.to_string()));
        self
    }
    fn len(&self) -> u64 {
        self.stream.as_ref().map(|s| s.1).unwrap_or(self.body.len() as u64)
    }
}

fn reason(status: u16) -> &'static str {
    match status {
        200 => "OK",
        400 => "Bad Request",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        413 => "Payload Too Large",
        431 => "Request Header Fields Too Large",
        500 => "Internal Server Error",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        _ => "",
    }
}

/// Percent-decode; '+' becomes a space only when `form` is set (query values).
pub fn pct_decode(s: &str, form: bool) -> String {
    let b = s.as_bytes();
    let mut out: Vec<u8> = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hex = std::str::from_utf8(&b[i + 1..i + 3]).ok().and_then(|h| u8::from_str_radix(h, 16).ok());
                match hex {
                    Some(v) => {
                        out.push(v);
                        i += 3;
                        continue;
                    }
                    None => out.push(b'%'),
                }
            }
            b'+' if form => out.push(b' '),
            c => out.push(c),
        }
        i += 1;
    }
    String::from_utf8_lossy(&out).to_string()
}

pub fn parse_request(head: &str) -> Option<Req> {
    let line = head.lines().next()?;
    let mut parts = line.split(' ');
    let method = parts.next()?.to_string();
    let target = parts.next()?;
    let version = parts.next()?;
    if !version.starts_with("HTTP/1.") {
        return None;
    }
    let (raw_path, raw_query) = match target.find('?') {
        Some(i) => (&target[..i], &target[i + 1..]),
        None => (target, ""),
    };
    let path = pct_decode(raw_path, false);
    if !path.starts_with('/') {
        return None;
    }
    let mut query = Vec::new();
    for pair in raw_query.split('&') {
        if pair.is_empty() {
            continue;
        }
        let (k, v) = match pair.find('=') {
            Some(i) => (&pair[..i], &pair[i + 1..]),
            None => (pair, ""),
        };
        query.push((pct_decode(k, true), pct_decode(v, true)));
    }
    let mut headers = Vec::new();
    for l in head.lines().skip(1) {
        if let Some(i) = l.find(':') {
            headers.push((l[..i].trim().to_ascii_lowercase(), l[i + 1..].trim().to_string()));
        }
    }
    Some(Req { method, path, query, headers, body: Vec::new() })
}

/// The host part of a Host value: `127.0.0.1:8002` -> `127.0.0.1`.
pub fn host_of(v: &str) -> String {
    let v = v.trim();
    let v = v.split("://").last().unwrap_or(v);
    let v = v.split('/').next().unwrap_or(v);
    if let Some(rest) = v.strip_prefix('[') {
        return format!("[{}]", rest.split(']').next().unwrap_or(""));
    }
    v.split(':').next().unwrap_or(v).to_ascii_lowercase()
}

const HEAD_LIMIT: usize = 16 * 1024;

pub type Handler = dyn Fn(&Req) -> Resp + Send + Sync;

/// Which peers a listener answers. The default is loopback only; a mesh listener passes its own (the mesh subnets).
pub type PeerCheck = dyn Fn(std::net::IpAddr) -> bool + Send + Sync;

fn serve(handler: &Handler, mut stream: TcpStream, port: u16, peer: &PeerCheck) {
    let started = Instant::now();
    let _ = stream.set_read_timeout(Some(Duration::from_secs(10)));
    let _ = stream.set_write_timeout(Some(Duration::from_secs(30)));
    let peer_ok = stream.peer_addr().map(|a| peer(a.ip())).unwrap_or(false);

    let mut buf: Vec<u8> = Vec::with_capacity(2048);
    let mut chunk = [0u8; 2048];
    let mut head_end = None;
    while buf.len() < HEAD_LIMIT {
        let n = match stream.read(&mut chunk) {
            Ok(0) | Err(_) => break,
            Ok(n) => n,
        };
        buf.extend_from_slice(&chunk[..n]);
        if let Some(i) = buf.windows(4).position(|w| w == b"\r\n\r\n") {
            head_end = Some(i + 4);
            break;
        }
    }
    let (req, resp) = if !peer_ok {
        (None, Resp::text(403, "Forbidden: loopback only"))
    } else if head_end.is_none() {
        (None, Resp::text(if buf.len() >= HEAD_LIMIT { 431 } else { 400 }, "Bad request"))
    } else {
        let head = String::from_utf8_lossy(&buf[..head_end.unwrap()]).to_string();
        match parse_request(&head) {
            Some(req) if req.method == "GET" || req.method == "HEAD" => {
                let resp = handler(&req);
                (Some(req), resp)
            }
            Some(mut req) if req.method == "POST" => {
                let declared = req.header("content-length").and_then(|v| v.trim().parse::<usize>().ok()).unwrap_or(0);
                if declared > crate::write::MAX_BODY {
                    let r = Resp::json(413, "{\"error\":\"request body too large (>32MB)\"}".to_string());
                    (Some(req), r)
                } else {
                    let mut body = buf[head_end.unwrap()..].to_vec();
                    while body.len() < declared {
                        let n = match stream.read(&mut chunk) {
                            Ok(0) | Err(_) => break,
                            Ok(n) => n,
                        };
                        body.extend_from_slice(&chunk[..n]);
                    }
                    body.truncate(declared);
                    req.body = body;
                    let resp = handler(&req);
                    (Some(req), resp)
                }
            }
            Some(req) => (Some(req), Resp::text(405, "method not allowed: this server answers GET and HEAD").with("allow", "GET, HEAD")),
            None => (None, Resp::text(400, "Bad request")),
        }
    };
    let head_only = req.as_ref().map(|r| r.method == "HEAD").unwrap_or(false);
    let len = resp.len();
    let mut out = format!("HTTP/1.1 {} {}\r\ncontent-type: {}\r\ncontent-length: {}\r\nconnection: close\r\n", resp.status, reason(resp.status), resp.ctype, len);
    if !resp.headers.iter().any(|(k, _)| k.eq_ignore_ascii_case("cache-control")) {
        out.push_str("cache-control: no-store\r\n");
    }
    for (k, v) in &resp.headers {
        out.push_str(&format!("{}: {}\r\n", k, v));
    }
    out.push_str("\r\n");
    let _ = stream.write_all(out.as_bytes());
    if !head_only {
        match resp.stream {
            Some((file, n)) => {
                let _ = std::io::copy(&mut file.take(n), &mut stream);
            }
            None => {
                let _ = stream.write_all(&resp.body);
            }
        }
    }
    let _ = stream.flush();
    let (m, p) = match &req {
        Some(r) => (r.method.as_str(), r.path.as_str()),
        None => ("-", "-"),
    };
    println!("{} {} {} {}ms {}B :{}", m, p, resp.status, started.elapsed().as_millis(), len, port);
}

/// Accept on `listener` forever, on a thread of its own, one thread per connection. Loopback peers only.
pub fn listen(listener: TcpListener, handler: Arc<Handler>) {
    listen_with(listener, handler, Arc::new(|ip: std::net::IpAddr| ip.is_loopback()), None);
}

/// [`listen`] with the peers it answers chosen by the caller, and an optional stop flag: when it is set the accept loop
/// ends and the listener closes (a stopped site frees its port). Without a flag the accept is blocking, as [`listen`] always was.
pub fn listen_with(listener: TcpListener, handler: Arc<Handler>, peer: Arc<PeerCheck>, stop: Option<Arc<std::sync::atomic::AtomicBool>>) {
    let port = listener.local_addr().map(|a| a.port()).unwrap_or(0);
    if stop.is_some() {
        let _ = listener.set_nonblocking(true);
    }
    std::thread::spawn(move || loop {
        if let Some(s) = &stop {
            if s.load(std::sync::atomic::Ordering::SeqCst) {
                return;
            }
        }
        match listener.accept() {
            Ok((stream, _)) => {
                let _ = stream.set_nonblocking(false);
                let (h, p) = (handler.clone(), peer.clone());
                std::thread::spawn(move || serve(&*h, stream, port, &*p));
            }
            Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => std::thread::sleep(Duration::from_millis(40)),
            Err(e) => eprintln!("accept: {}", e),
        }
    });
}
