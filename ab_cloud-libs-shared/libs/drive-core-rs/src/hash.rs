//! Streaming file hashing: BLAKE3 and SHA-256 in ONE pass over the file (1 MiB buffer), many files on bounded worker threads.

use std::fs::File;
use std::io::Read;
use std::path::Path;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

use sha2::{Digest, Sha256};

use crate::wire;

pub const ALGO_BLAKE3: u32 = 1;
pub const ALGO_SHA256: u32 = 2;
/// Never more worker threads than this, whatever Kotlin asks for: a phone has few big cores and flash storage gains nothing past them.
pub const MAX_THREADS: usize = 8;
const BUF: usize = 1 << 20;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hashed {
    pub path: String,
    /// Bytes hashed (the file's size, or `limit` when the file is longer).
    pub bytes: u64,
    pub blake3: String,
    pub sha256: String,
    /// Empty when the file was read; the reason when it was not (the other fields are then empty).
    pub error: String,
}

fn hex(b: &[u8]) -> String {
    const D: &[u8; 16] = b"0123456789abcdef";
    let mut s = String::with_capacity(b.len() * 2);
    for x in b {
        s.push(D[(x >> 4) as usize] as char);
        s.push(D[(x & 15) as usize] as char);
    }
    s
}

/// Hash the first `limit` bytes of `path` (the whole file when `limit` is None) with the algorithms in `algos`.
pub fn hash_file(path: &Path, algos: u32, limit: Option<u64>) -> Result<(u64, String, String), String> {
    let mut f = File::open(path).map_err(|e| e.to_string())?;
    let mut b3 = if algos & ALGO_BLAKE3 != 0 { Some(blake3::Hasher::new()) } else { None };
    let mut s2 = if algos & ALGO_SHA256 != 0 { Some(Sha256::new()) } else { None };
    let mut buf = vec![0u8; BUF];
    let mut left = limit.unwrap_or(u64::MAX);
    let mut total = 0u64;
    while left > 0 {
        let want = std::cmp::min(buf.len() as u64, left) as usize;
        let n = match f.read(&mut buf[..want]) {
            Ok(0) => break,
            Ok(n) => n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(e.to_string()),
        };
        if let Some(h) = b3.as_mut() {
            h.update(&buf[..n]);
        }
        if let Some(h) = s2.as_mut() {
            h.update(&buf[..n]);
        }
        total += n as u64;
        left -= n as u64;
    }
    Ok((
        total,
        b3.map(|h| h.finalize().to_hex().to_string()).unwrap_or_default(),
        s2.map(|h| hex(&h.finalize())).unwrap_or_default(),
    ))
}

/// Hash every path, `threads` workers (clamped to 1..=MAX_THREADS and to the file count). Results come back in INPUT order.
pub fn hash_many(paths: &[String], algos: u32, threads: usize, limit: Option<u64>) -> Vec<Hashed> {
    let n = paths.len();
    let workers = threads.clamp(1, MAX_THREADS).min(n.max(1));
    let next = AtomicUsize::new(0);
    let out: Mutex<Vec<Option<Hashed>>> = Mutex::new(vec![None; n]);
    let work = || loop {
        let i = next.fetch_add(1, Ordering::Relaxed);
        if i >= n {
            break;
        }
        let r = match hash_file(Path::new(&paths[i]), algos, limit) {
            Ok((bytes, blake3, sha256)) => Hashed { path: paths[i].clone(), bytes, blake3, sha256, error: String::new() },
            Err(error) => Hashed { path: paths[i].clone(), bytes: 0, blake3: String::new(), sha256: String::new(), error },
        };
        if let Ok(mut g) = out.lock() {
            g[i] = Some(r);
        }
    };
    if workers <= 1 {
        work();
    } else {
        std::thread::scope(|s| {
            for _ in 0..workers {
                s.spawn(&work);
            }
        });
    }
    let v = out.into_inner().unwrap_or_default();
    v.into_iter()
        .enumerate()
        .map(|(i, h)| h.unwrap_or_else(|| Hashed { path: paths[i].clone(), bytes: 0, blake3: String::new(), sha256: String::new(), error: "not hashed".into() }))
        .collect()
}

/// The wire answer: one `H\tpath\tbytes\tblake3\tsha256\terror` line per file, in input order.
pub fn encode(rs: &[Hashed]) -> String {
    let mut s = String::new();
    for r in rs {
        s.push_str(&format!("H\t{}\t{}\t{}\t{}\t{}\n", wire::esc(&r.path), r.bytes, r.blake3, r.sha256, wire::esc(&r.error)));
    }
    s
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{tmpdir, write};


    #[test]
    fn known_vectors() {
        let d = tmpdir("hash-vec");
        write(&d.join("abc"), b"abc");
        write(&d.join("empty"), b"");
        let (n, b3, s2) = hash_file(&d.join("abc"), ALGO_BLAKE3 | ALGO_SHA256, None).unwrap();
        assert_eq!(n, 3);
        assert_eq!(s2, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assert_eq!(b3, "6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85");
        let (n, b3, s2) = hash_file(&d.join("empty"), ALGO_BLAKE3 | ALGO_SHA256, None).unwrap();
        assert_eq!(n, 0);
        assert_eq!(b3, "af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262");
        assert_eq!(s2, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn only_the_asked_algorithm_and_the_limit() {
        let d = tmpdir("hash-algo");
        write(&d.join("f"), b"abcdef");
        let (_, b3, s2) = hash_file(&d.join("f"), ALGO_SHA256, None).unwrap();
        assert!(b3.is_empty() && s2.len() == 64);
        // the limit hashes a prefix: sha256("abc")
        let (n, _, s2) = hash_file(&d.join("f"), ALGO_SHA256, Some(3)).unwrap();
        assert_eq!(n, 3);
        assert_eq!(s2, "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn a_file_longer_than_the_buffer_streams() {
        let d = tmpdir("hash-big");
        let big: Vec<u8> = (0..(BUF * 2 + 12345)).map(|i| (i % 251) as u8).collect();
        write(&d.join("big"), &big);
        let (n, b3, s2) = hash_file(&d.join("big"), ALGO_BLAKE3 | ALGO_SHA256, None).unwrap();
        assert_eq!(n as usize, big.len());
        assert_eq!(b3, blake3::hash(&big).to_hex().to_string());
        assert_eq!(s2, hex(&Sha256::digest(&big)));
        let _ = std::fs::remove_dir_all(&d);
    }

    #[test]
    fn many_files_keep_input_order_and_report_errors() {
        let d = tmpdir("hash-many");
        let mut paths = Vec::new();
        for i in 0..40 {
            let p = d.join(format!("f{i}"));
            write(&p, format!("content-{i}").as_bytes());
            paths.push(p.to_string_lossy().to_string());
        }
        paths.push(d.join("missing").to_string_lossy().to_string());
        let one = hash_many(&paths, ALGO_BLAKE3 | ALGO_SHA256, 1, None);
        let many = hash_many(&paths, ALGO_BLAKE3 | ALGO_SHA256, 99, None);
        assert_eq!(one, many);
        assert_eq!(many.len(), 41);
        assert_eq!(many[3].path, paths[3]);
        assert_eq!(many[3].blake3, blake3::hash(b"content-3").to_hex().to_string());
        assert!(many[40].error.len() > 0 && many[40].sha256.is_empty());
        assert!(hash_many(&[], ALGO_SHA256, 4, None).is_empty());
        let _ = std::fs::remove_dir_all(&d);
    }
}
