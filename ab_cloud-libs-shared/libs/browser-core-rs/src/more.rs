//! The rest of the browser's stores in Rust (#1841 phase 2), one section per store, each a pure `op(json) -> json` like lists.rs and held
//! to the same fixture corpus as its Kotlin twin (ListsMore.kt, KtMore):
//!   dl_*      downloads: the DownloadManager index, the download manager's job list (every field), its policy, backoff, names and sizes in
//!             words, the seeding rule, the Downloads page's filter and sort
//! The ops of lists.rs are tried first; an op neither knows is an `Err` (the JNI boundary answers `ERR`, Kotlin runs its twin).

use serde_json::{json, Map, Value};
use std::collections::HashSet;

use crate::lists::{arr, blank, cap_of, lower, long, raw_array, text};

fn int(v: &Value, k: &str, d: i64) -> i64 {
    match v.get(k) {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(d),
        Some(Value::String(s)) => s.trim().parse::<i64>().unwrap_or(d),
        _ => d,
    }
}

fn boolean(v: &Value, k: &str, d: bool) -> bool {
    match v.get(k) {
        Some(Value::Bool(b)) => *b,
        Some(Value::String(s)) if s.eq_ignore_ascii_case("true") => true,
        Some(Value::String(s)) if s.eq_ignore_ascii_case("false") => false,
        _ => d,
    }
}

fn opt_text(v: &Value, k: &str) -> Option<String> {
    match v.get(k) {
        None | Some(Value::Null) => None,
        Some(_) => Some(text(v, k, "")),
    }
}

fn long_at(a: &[Value], i: usize) -> i64 {
    match a.get(i) {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

// ── downloads ──────────────────────────────────────────────────────────────────────────────────────────────────────────

pub const DL_INDEX_CAP: usize = 200;
const DL_STATES: [&str; 8] = ["QUEUED", "RUNNING", "PAUSED", "WAITING_NETWORK", "RETRYING", "FAILED", "COMPLETED", "CANCELLED"];

fn dl_index_row(o: &Value) -> Value {
    json!({ "id": long(o, "id"), "url": text(o, "url", ""), "file": text(o, "file", ""), "mime": text(o, "mime", ""), "ts": long(o, "ts") })
}

fn dl_index_in(a: &Value, key: &str) -> Vec<Value> {
    let src: Vec<Value> = match a.get("raw") {
        Some(Value::String(raw)) => raw_array(raw),
        _ => arr(a, key).to_vec(),
    };
    src.iter().filter(|o| o.is_object() && o.get("id").is_some()).map(dl_index_row).collect()
}

fn dl_index_out(v: Vec<Value>) -> Value {
    let raw = Value::Array(v.clone()).to_string();
    json!({ "downloads": v, "raw": raw, "count": v.len() })
}

/// One job of the download manager, every field, defaults as the Kotlin DlCodec read them. `None` = no id (the row is dropped).
fn dl_job(o: &Value, requeue: bool) -> Option<Value> {
    if !o.is_object() || matches!(o.get("id"), None | Some(Value::Null)) {
        return None;
    }
    let mut j = Map::new();
    j.insert("id".into(), json!(text(o, "id", "")));
    j.insert("url".into(), json!(text(o, "url", "")));
    j.insert("fileName".into(), json!(text(o, "fileName", "")));
    j.insert("mime".into(), json!(text(o, "mime", "")));
    j.insert("dir".into(), json!(text(o, "dir", "")));
    j.insert("kind".into(), json!(text(o, "kind", "http")));
    for k in ["userAgent", "referer", "cookie", "checksum", "etag", "lastModified", "error", "publishedUri", "infoHash"] {
        if let Some(s) = opt_text(o, k) {
            j.insert(k.into(), json!(s));
        }
    }
    let mut st = text(o, "state", "");
    if !DL_STATES.contains(&st.as_str()) {
        st = "PAUSED".into();
    }
    if requeue && (st == "RUNNING" || st == "RETRYING") {
        st = "QUEUED".into();
    }
    j.insert("state".into(), json!(st));
    j.insert("total".into(), json!(int(o, "total", -1)));
    j.insert("downloaded".into(), json!(int(o, "downloaded", 0)));
    if o.get("acceptRanges").is_some() {
        j.insert("acceptRanges".into(), json!(boolean(o, "acceptRanges", false)));
    }
    let segs: Vec<Value> = arr(o, "segments")
        .iter()
        .filter_map(|s| s.as_array().map(|s| json!([long_at(s, 0), long_at(s, 1), long_at(s, 2)])))
        .collect();
    j.insert("segments".into(), Value::Array(segs));
    j.insert("attempts".into(), json!(int(o, "attempts", 0) as i32));
    j.insert("nextAttemptAt".into(), json!(int(o, "nextAttemptAt", 0)));
    j.insert("file".into(), json!(text(o, "file", "")));
    j.insert("saveToSharedStore".into(), json!(boolean(o, "saveToSharedStore", false)));
    j.insert("createdAt".into(), json!(int(o, "createdAt", 0)));
    j.insert("finishedAt".into(), json!(int(o, "finishedAt", 0)));
    if let Some(Value::Array(sel)) = o.get("selectedFiles") {
        j.insert("selectedFiles".into(), Value::Array(sel.iter().enumerate().map(|(i, _)| json!(long_at(sel, i) as i32)).collect()));
    }
    j.insert("sequential".into(), json!(boolean(o, "sequential", false)));
    j.insert("seed".into(), json!(boolean(o, "seed", false)));
    Some(Value::Object(j))
}

fn dl_policy(a: &Value) -> Value {
    let o = match a.get("raw") {
        Some(Value::String(raw)) => serde_json::from_str::<Value>(raw).ok().filter(|v| v.is_object()).unwrap_or_else(|| json!({})),
        _ => json!({}),
    };
    let c = |k: &str, d: i64, lo: i64, hi: i64| int(&o, k, d).clamp(lo, hi);
    json!({
        "parallel": c("parallel", 3, 1, 8), "wifiOnly": boolean(&o, "wifiOnly", false), "allowMetered": boolean(&o, "allowMetered", true),
        "speedLimitKbps": c("speedLimitKbps", 0, 0, i32::MAX as i64), "segments": c("segments", 4, 1, 8), "sharedStore": boolean(&o, "sharedStore", false),
        "seed": boolean(&o, "seed", false), "seedRatio": c("seedRatio", 0, 0, i32::MAX as i64), "seedMinutes": c("seedMinutes", 0, 0, i32::MAX as i64),
        "uploadKbps": c("uploadKbps", 0, 0, i32::MAX as i64),
    })
}

/// Sizes in words: B, then KB / MB / GB / TB with one decimal under 100 and none from 100 (halves round up, as `%.1f` / `%.0f` do).
pub fn dl_bytes(n: i64) -> String {
    if n < 0 {
        return "?".into();
    }
    if n < 1024 {
        return format!("{n} B");
    }
    let units = ["KB", "MB", "GB", "TB"];
    let mut v = n as f64 / 1024.0;
    let mut i = 0;
    while v >= 1024.0 && i < units.len() - 1 {
        v /= 1024.0;
        i += 1;
    }
    if v >= 100.0 {
        format!("{} {}", (v + 0.5).floor() as i64, units[i])
    } else {
        let t = (v * 10.0 + 0.5).floor() as i64;
        format!("{}.{} {}", t / 10, t % 10, units[i])
    }
}

pub fn dl_eta(sec: i64) -> String {
    if sec < 0 {
        "--".into()
    } else if sec < 60 {
        format!("{sec}s")
    } else if sec < 3600 {
        format!("{}m {}s", sec / 60, sec % 60)
    } else {
        format!("{}h {}m", sec / 3600, (sec % 3600) / 60)
    }
}

/// A name safe for one path segment.
pub fn dl_safe_name(raw: &str) -> String {
    let base = raw.rsplit('/').next().unwrap_or("");
    let base = base.rsplit('\\').next().unwrap_or("");
    let clean: String = base.chars().map(|c| if (c as u32) < 0x20 || "<>:\"|?*".contains(c) { '_' } else { c }).collect();
    let clean = clean.trim().trim_start_matches('.').to_string();
    if blank(&clean) { "download".into() } else { clean.chars().take(200).collect() }
}

fn dl_unique(name: &str, taken: &HashSet<String>) -> String {
    if !taken.contains(name) {
        return name.to_string();
    }
    let dot = name.rfind('.').filter(|d| *d > 0).unwrap_or(name.len());
    let (stem, ext) = name.split_at(dot);
    let mut i = 2;
    while taken.contains(&format!("{stem} ({i}){ext}")) {
        i += 1;
    }
    format!("{stem} ({i}){ext}")
}

fn dl_jobs_query(a: &Value) -> Value {
    let jobs = arr(a, "jobs");
    let q = lower(&text(a, "query", ""));
    let mut idx: Vec<usize> = (0..jobs.len())
        .filter(|&i| blank(&q) || lower(&text(&jobs[i], "fileName", "")).contains(&q) || lower(&text(&jobs[i], "url", "")).contains(&q) || lower(&text(&jobs[i], "state", "")).contains(&q))
        .collect();
    match text(a, "sort", "input").as_str() {
        "created" => idx.sort_by(|&x, &y| long(&jobs[y], "createdAt").cmp(&long(&jobs[x], "createdAt"))),
        "name" => idx.sort_by_key(|&i| lower(&text(&jobs[i], "fileName", ""))),
        _ => {}
    }
    json!({ "indices": idx, "count": idx.len() })
}

fn downloads(op: &str, a: &Value) -> Option<Value> {
    Some(match op {
        "dl_index_load" => dl_index_out(dl_index_in(a, "downloads")),
        "dl_index_add" => {
            let d = dl_index_row(a.get("download").unwrap_or(&Value::Null));
            let id = d["id"].as_i64().unwrap_or(0);
            let mut v: Vec<Value> = vec![d];
            v.extend(dl_index_in(a, "downloads").into_iter().filter(|x| x["id"].as_i64().unwrap_or(0) != id));
            v.truncate(cap_of(a, DL_INDEX_CAP));
            dl_index_out(v)
        }
        "dl_status" => {
            let status = match int(a, "code", 0) {
                1 => "pending",
                2 => "running",
                4 => "paused",
                8 => "successful",
                16 => "failed",
                _ => "unknown",
            };
            json!({ "status": status })
        }
        "dl_jobs_load" => {
            let src: Vec<Value> = match a.get("raw") {
                Some(Value::String(raw)) => raw_array(raw),
                _ => arr(a, "jobs").to_vec(),
            };
            let requeue = boolean(a, "requeue", false);
            let jobs: Vec<Value> = src.iter().filter_map(|o| dl_job(o, requeue)).collect();
            let raw = Value::Array(jobs.clone()).to_string();
            json!({ "jobs": jobs, "raw": raw, "count": jobs.len() })
        }
        "dl_policy" => json!({ "policy": dl_policy(a) }),
        "dl_backoff" => {
            let attempt = int(a, "attempt", 0).max(0).min(8) as u32;
            let jitter = a.get("jitter").and_then(Value::as_f64).unwrap_or(0.0);
            json!({ "delay_ms": (2_000i64 << attempt).min(300_000) + (jitter * 1_000.0) as i64 })
        }
        "dl_retryable" => {
            let s = int(a, "status", 0);
            json!({ "retryable": s == 408 || s == 425 || s == 429 || s >= 500 })
        }
        "dl_format" => {
            let n = int(a, "n", 0);
            let t = match text(a, "kind", "bytes").as_str() {
                "speed" => format!("{}/s", dl_bytes(n)),
                "eta" => dl_eta(n),
                _ => dl_bytes(n),
            };
            json!({ "text": t })
        }
        "dl_safe_name" => json!({ "name": dl_safe_name(&text(a, "raw", "")) }),
        "dl_unique" => {
            let taken: HashSet<String> = arr(a, "taken").iter().filter_map(|x| x.as_str().map(String::from)).collect();
            json!({ "name": dl_unique(&text(a, "name", ""), &taken) })
        }
        "dl_seed_reached" => {
            let p = a.get("policy").cloned().unwrap_or(Value::Null);
            let (ratio, minutes) = (int(&p, "seedRatio", 0), int(&p, "seedMinutes", 0));
            let (up, size, sec) = (int(a, "uploaded", 0), int(a, "size", 0), int(a, "seeding_sec", 0));
            json!({ "reached": (ratio > 0 && size > 0 && up * 100 >= ratio * size) || (minutes > 0 && sec >= minutes * 60) })
        }
        "dl_jobs_query" => dl_jobs_query(a),
        _ => return None,
    })
}

/// The canonical list (and its text) of a stored store of `kind` handled here, for lists.rs `migrate`; `None` = not a kind of this file.
pub fn migrate_kind(kind: &str, src: &[Value]) -> Option<(Value, String)> {
    let v: Vec<Value> = match kind {
        "downloads" => src.iter().filter(|o| o.is_object() && o.get("id").is_some()).map(dl_index_row).collect(),
        "dljobs" => src.iter().filter_map(|o| dl_job(o, false)).collect(),
        _ => return None,
    };
    let list = Value::Array(v);
    let raw = list.to_string();
    Some((list, raw))
}

pub fn run(op: &str, a: &Value) -> Result<Value, String> {
    if let Some(v) = downloads(op, a) {
        return Ok(v);
    }
    Err(format!("unknown list op {op}"))
}
