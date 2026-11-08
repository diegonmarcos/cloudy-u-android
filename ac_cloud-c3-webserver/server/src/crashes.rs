//! #1122 THE PHONE'S LOCAL CRASH SINK. Every fleet app's libs:core crash handler POSTs its report here on
//! loopback (`POST /__app__/api/crashes`, resolved from this app's declared port, no DNS), so a crash is
//! readable on the phone with the network down. `GET /__app__/crashes` lists every app's crashes.
//!
//! Reports are kept in memory (newest 200) and appended best effort to `c3-crashes.jsonl` in
//! `$C3_CRASH_DIR` or the temp dir, then reloaded at the first request after a restart.

use crate::Ctx;
use fleet_webserver::http::{Req, Resp};
use fleet_webserver::render::jstr;
use std::sync::Mutex;

const KEEP: usize = 200;
/// ...and no single app may hold more than this many of the newest 200. The cap
/// used to be the global KEEP alone, whose scope is the WHOLE store: one app
/// that crash-loops would fill all 200 slots and evict every other app's reports
/// — a quiet app's single crash, the one most worth keeping, pushed out by a
/// chatty neighbour. Scoping eviction per app stops that; the global KEEP stays
/// as the memory ceiling.
const KEEP_PER_APP: usize = 50;
const MAX_BODY: usize = 256 * 1024;

static STORE: Mutex<Option<Vec<serde_json::Value>>> = Mutex::new(None);

fn file() -> std::path::PathBuf {
    let dir = std::env::var_os("C3_CRASH_DIR").map(std::path::PathBuf::from).unwrap_or_else(std::env::temp_dir);
    dir.join("c3-crashes.jsonl")
}

/// Trim `rows` (oldest first) so no app keeps more than [`KEEP_PER_APP`] of its
/// newest reports, then enforce the global [`KEEP`] ceiling. The per-app pass is
/// the scope fix: eviction reaches only the app that is over its own share, so a
/// flood in one app can no longer delete another app's reports.
fn evict(rows: &mut Vec<serde_json::Value>) {
    let mut per_app: std::collections::HashMap<String, usize> = std::collections::HashMap::new();
    let mut keep = vec![false; rows.len()];
    for i in (0..rows.len()).rev() {
        let app = rows[i]["app"].as_str().unwrap_or("").to_string();
        let count = per_app.entry(app).or_insert(0);
        if *count < KEEP_PER_APP {
            *count += 1;
            keep[i] = true;
        }
    }
    let mut idx = 0;
    rows.retain(|_| {
        let k = keep[idx];
        idx += 1;
        k
    });
    let n = rows.len();
    if n > KEEP {
        rows.drain(..n - KEEP);
    }
}

fn with_store<T>(f: impl FnOnce(&mut Vec<serde_json::Value>) -> T) -> T {
    let mut g = STORE.lock().unwrap_or_else(|e| e.into_inner());
    let v = g.get_or_insert_with(|| {
        let text = std::fs::read_to_string(file()).unwrap_or_default();
        let mut rows: Vec<serde_json::Value> = text.lines().filter_map(|l| serde_json::from_str(l).ok()).collect();
        evict(&mut rows);
        rows
    });
    f(v)
}

/// Accept one report: a JSON object with an `app` string. Returns the stored row or the refusal.
pub fn accept(body: &[u8]) -> Result<serde_json::Value, &'static str> {
    if body.len() > MAX_BODY {
        return Err("report too large");
    }
    let mut v: serde_json::Value = serde_json::from_slice(body).map_err(|_| "body must be a JSON object")?;
    let obj = v.as_object_mut().ok_or("body must be a JSON object")?;
    if obj.get("app").and_then(|a| a.as_str()).map_or(true, |a| a.is_empty() || a.len() > 200) {
        return Err("report needs an `app` (package name)");
    }
    obj.entry("received_ms").or_insert_with(|| {
        let ms = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        serde_json::Value::from(ms)
    });
    let row = v.clone();
    with_store(|rows| {
        // A device-side retry of the same report (same app + time + trace) is stored once.
        if !rows.iter().any(|r| r["app"] == row["app"] && r["time_ms"] == row["time_ms"] && r["trace"] == row["trace"]) {
            rows.push(row.clone());
            evict(rows);
            use std::io::Write;
            if let Ok(mut f) = std::fs::OpenOptions::new().create(true).append(true).open(file()) {
                let _ = writeln!(f, "{}", row);
            }
        }
    });
    Ok(v)
}

pub fn api_post(_ctx: &Ctx, req: &Req) -> Resp {
    match accept(&req.body) {
        Ok(_) => Resp::json(200, "{\"ok\":true}".to_string()),
        Err(e) => Resp::json(400, format!("{{\"error\":{}}}", jstr(e))),
    }
}

/// `GET /__app__/api/crashes[?app=<package>]`: newest first.
pub fn api_get(_ctx: &Ctx, req: &Req) -> Resp {
    let app = req.q("app").map(|s| s.to_string());
    let rows = list(app.as_deref());
    Resp::json(200, serde_json::Value::Array(rows).to_string())
}

pub fn list(app: Option<&str>) -> Vec<serde_json::Value> {
    with_store(|rows| rows.iter().rev().filter(|r| app.map_or(true, |a| r["app"] == a)).cloned().collect())
}

fn esc(s: &str) -> String {
    s.replace('&', "&amp;").replace('<', "&lt;").replace('>', "&gt;").replace('"', "&quot;")
}

/// `GET /__app__/crashes`: every app's crashes, grouped by app, newest first, trace expandable.
pub fn page(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::html(render(&list(None)))
}

pub fn render(rows: &[serde_json::Value]) -> String {
    let mut apps: Vec<String> = rows.iter().filter_map(|r| r["app"].as_str().map(String::from)).collect();
    apps.sort();
    apps.dedup();
    let mut out = String::from("<!doctype html><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\"><title>Crashes</title>\
<style>body{background:#101418;color:#e6edf3;font:14px system-ui;margin:0;padding:12px}h1{font-size:18px}h2{font-size:15px;margin:16px 0 4px}\
details{background:#1a2027;border-radius:8px;margin:6px 0;padding:6px 10px}summary{cursor:pointer}pre{white-space:pre-wrap;word-break:break-all;font-size:11px}.m{color:#9aa7b4}</style><h1>Crashes on this phone</h1>");
    if rows.is_empty() {
        out.push_str("<p class=m>No crash has been reported to this phone.</p>");
    }
    for app in &apps {
        let mine: Vec<&serde_json::Value> = rows.iter().filter(|r| r["app"] == app.as_str()).collect();
        out.push_str(&format!("<h2>{} <span class=m>({})</span></h2>", esc(app), mine.len()));
        for r in mine {
            let s = |k: &str| r[k].as_str().unwrap_or("").to_string();
            out.push_str(&format!(
                "<details><summary>{} &middot; {} &middot; {}</summary><pre>{}</pre></details>",
                esc(&s("phase")), esc(&s("version")), esc(&s("time")), esc(&s("trace"))
            ));
        }
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_report_is_stored_once_and_listed_newest_first() {
        std::env::set_var("C3_CRASH_DIR", std::env::temp_dir().join(format!("c3crash-{}", std::process::id())));
        let _ = std::fs::create_dir_all(std::env::var_os("C3_CRASH_DIR").unwrap());
        let a = br#"{"app":"x.one","time_ms":1,"trace":"boom<1>","phase":"p"}"#;
        accept(a).unwrap();
        accept(a).unwrap();
        accept(br#"{"app":"x.two","time_ms":2,"trace":"bang"}"#).unwrap();
        let rows = list(None);
        assert_eq!(rows.iter().filter(|r| r["app"] == "x.one").count(), 1);
        assert_eq!(rows[0]["app"], "x.two");
        assert_eq!(list(Some("x.one")).len(), 1);
        let html = render(&rows);
        assert!(html.contains("x.one") && html.contains("boom&lt;1&gt;") && !html.contains("boom<1>"));
    }

    #[test]
    fn a_flood_in_one_app_cannot_evict_another_apps_report() {
        // The quiet app's single crash, then a flood from a chatty one. Under the
        // old global-only cap the 250 chatty rows would fill all 200 slots and the
        // quiet app's lone report would be gone. Per-app scoping keeps it.
        let mut rows: Vec<serde_json::Value> =
            vec![serde_json::json!({"app":"y.quiet","time_ms":0,"trace":"only"})];
        for i in 0..250 {
            rows.push(serde_json::json!({"app":"x.chatty","time_ms":i,"trace":format!("t{i}")}));
        }
        evict(&mut rows);
        assert_eq!(rows.iter().filter(|r| r["app"] == "y.quiet").count(), 1, "the quiet app's sole report survives the flood");
        assert_eq!(rows.iter().filter(|r| r["app"] == "x.chatty").count(), KEEP_PER_APP, "the chatty app is capped at its per-app share");
    }

    #[test]
    fn the_global_ceiling_still_bounds_total_memory() {
        // Ten apps each within their per-app share (50) is 500 rows; the global
        // KEEP must still cap the whole store.
        let mut rows: Vec<serde_json::Value> = Vec::new();
        for a in 0..10 {
            for i in 0..KEEP_PER_APP {
                rows.push(serde_json::json!({"app":format!("app{a}"),"time_ms":i,"trace":format!("{a}-{i}")}));
            }
        }
        evict(&mut rows);
        assert!(rows.len() <= KEEP, "the global ceiling bounds total memory");
    }

    #[test]
    fn refuses_what_is_not_a_report() {
        assert!(accept(b"nope").is_err());
        assert!(accept(b"[1]").is_err());
        assert!(accept(br#"{"trace":"x"}"#).is_err());
        assert!(accept(&vec![b'a'; MAX_BODY + 1]).is_err());
    }
}
