//! The rest of the browser's stores in Rust (#1841 phase 2), one section per store, each a pure `op(json) -> json` like lists.rs and held
//! to the same fixture corpus as its Kotlin twin (ListsMore.kt, KtMore):
//!   dl_*      downloads: the DownloadManager index, the download manager's job list (every field), its policy, backoff, names and sizes in
//!             words, the seeding rule, the Downloads page's filter and sort
//!   set_*     settings: validate and type a value against the catalogue, the FleetConfig snapshot, the repair of values stored under the wrong type
//!   perm_*    site permissions: the per-site rule resolution (most specific host, global default, declared default), zoom, the Sites page's
//!             hosts, set / forget, the WebView state mappings, the lowercase-host repair
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

// ── settings ───────────────────────────────────────────────────────────────────────────────────────────────────────────

fn kt_list(v: &[String]) -> String {
    format!("[{}]", v.join(", "))
}

fn setting<'a>(cat: &'a [Value], key: &str) -> Option<&'a Value> {
    cat.iter().find(|s| text(s, "key", "") == key)
}

fn str_list(v: &Value, k: &str) -> Vec<String> {
    arr(v, k).iter().map(|x| text(&json!({ "x": x }), "x", "")).collect()
}

fn parse_bool(v: &str) -> Option<bool> {
    match lower(v).as_str() {
        "true" | "1" | "on" | "yes" => Some(true),
        "false" | "0" | "off" | "no" => Some(false),
        _ => None,
    }
}

fn split_set(v: &str) -> Vec<String> {
    let mut out: Vec<String> = Vec::new();
    for p in v.split(',').map(|x| x.trim()).filter(|x| !x.is_empty()) {
        if !out.iter().any(|o| o == p) {
            out.push(p.to_string());
        }
    }
    out
}

fn limit(s: &Value, k: &str) -> Option<i64> {
    s.get(k).and_then(Value::as_i64)
}

/// `raw` (a query-string value) -> the typed value to store, or the error in words (the Kotlin catalogue's own messages).
fn set_parse(a: &Value) -> Value {
    let cat = arr(a, "catalogue");
    let key = text(a, "key", "");
    let s = match setting(cat, &key) {
        Some(s) => s,
        None => return json!({ "error": format!("{key}: not a setting (see settings/catalogue)") }),
    };
    let raw = text(a, "raw", "");
    let v = raw.trim();
    let ty = text(s, "type", "string");
    let values = str_list(s, "values");
    let err = |m: String| json!({ "error": m });
    match ty.as_str() {
        "bool" => match parse_bool(v) {
            Some(b) => json!({ "value": b, "type": "bool" }),
            None => err(format!("{key}: '{v}' is not a bool (true/false)")),
        },
        "int" => match v.parse::<i32>() {
            Err(_) => err(format!("{key}: '{v}' is not an int")),
            Ok(n) => {
                let (mn, mx) = (limit(s, "min"), limit(s, "max"));
                if mn.map_or(false, |m| (n as i64) < m) || mx.map_or(false, |m| (n as i64) > m) {
                    let show = |o: Option<i64>| o.map_or("null".to_string(), |x| x.to_string());
                    err(format!("{key}: {n} is out of range {}..{}", show(mn), show(mx)))
                } else {
                    json!({ "value": n, "type": "int" })
                }
            }
        },
        "enum" => {
            if values.iter().any(|x| x == v) { json!({ "value": v, "type": "enum" }) } else { err(format!("{key}: '{v}' is not one of {}", kt_list(&values))) }
        }
        "set" => {
            let set = split_set(v);
            let bad: Vec<String> = if values.is_empty() { vec![] } else { set.iter().filter(|x| !values.contains(x)).cloned().collect() };
            if bad.is_empty() { json!({ "value": set, "type": "set" }) } else { err(format!("{key}: {} not in {}", kt_list(&bad), kt_list(&values))) }
        }
        _ => json!({ "value": v, "type": ty }),
    }
}

/// Every setting's current value (else its default) plus `_types`, the FleetConfig export shape.
fn set_snapshot(a: &Value) -> Value {
    let values = a.get("values").cloned().unwrap_or_else(|| json!({}));
    let mut out = Map::new();
    let mut types = Map::new();
    for s in arr(a, "catalogue") {
        let key = text(s, "key", "");
        let v = match values.get(&key) {
            Some(Value::Null) | None => s.get("default").cloned().unwrap_or(Value::Null),
            Some(v) => v.clone(),
        };
        let v = match v {
            Value::Array(items) => {
                let mut t: Vec<String> = items.iter().map(|x| text(&json!({ "x": x }), "x", "")).collect();
                t.sort();
                json!(t)
            }
            other => other,
        };
        out.insert(key.clone(), v);
        types.insert(key, json!(text(s, "type", "string")));
    }
    out.insert("_types".into(), Value::Object(types));
    Value::Object(out)
}

/// A stored value the prefs hold under the wrong type (reading it would throw) coerced to the catalogue's type, or dropped.
fn set_coerce(ty: &str, v: &Value) -> Option<Value> {
    match ty {
        "bool" => match v {
            Value::Bool(_) => Some(v.clone()),
            Value::String(s) => parse_bool(s.trim()).map(|b| json!(b)),
            _ => None,
        },
        "int" => match v {
            Value::Number(n) => n.as_i64().filter(|x| i32::try_from(*x).is_ok()).map(|x| json!(x)),
            Value::String(s) => s.trim().parse::<i32>().ok().map(|x| json!(x)),
            _ => None,
        },
        "set" => match v {
            Value::Array(items) => Some(json!(items.iter().filter_map(|x| x.as_str().map(String::from)).collect::<Vec<_>>())),
            Value::String(s) => Some(json!(split_set(s.trim()))),
            _ => None,
        },
        _ => match v {
            Value::String(_) => Some(v.clone()),
            Value::Number(n) => Some(json!(n.to_string())),
            Value::Bool(b) => Some(json!(b.to_string())),
            _ => None,
        },
    }
}

/// `stored` = `{key: {"t": prefs type (Boolean|Integer|Long|Float|String|Set), "v": value}}`: what to rewrite and what to drop.
fn set_migrate(a: &Value) -> Value {
    let cat = arr(a, "catalogue");
    let mut fixes = Map::new();
    let mut drops: Vec<String> = Vec::new();
    let stored = a.get("stored").and_then(Value::as_object).cloned().unwrap_or_default();
    let mut keys: Vec<&String> = stored.keys().collect();
    keys.sort();
    for k in keys {
        let s = match setting(cat, k) {
            Some(s) => s,
            None => continue, // a key the catalogue does not declare (search_engine_id ...) is left alone
        };
        let ty = text(s, "type", "string");
        let want = match ty.as_str() { "bool" => "Boolean", "int" => "Integer", "set" => "Set", _ => "String" };
        let e = &stored[k];
        if text(e, "t", "") == want {
            continue;
        }
        match set_coerce(&ty, e.get("v").unwrap_or(&Value::Null)) {
            Some(v) => {
                let tag = if ty == "enum" { "string".to_string() } else { ty.clone() };
                fixes.insert(k.clone(), json!({ "t": tag, "v": v }));
            }
            None => drops.push(k.clone()),
        }
    }
    json!({ "fixes": fixes, "drops": drops })
}

// ── site permissions ───────────────────────────────────────────────────────────────────────────────────────────────────

fn perm_key(host: &str, perm: &str) -> String {
    format!("{}|{}", lower(host), perm)
}

fn suffixes(host: &str) -> Vec<String> {
    let l = lower(host);
    let parts: Vec<&str> = l.split('.').filter(|p| !p.is_empty()).collect();
    (0..parts.len()).map(|i| parts[i..].join(".")).collect()
}

fn rule<'a>(rules: &'a Value, k: &str) -> Option<&'a str> {
    rules.get(k).and_then(Value::as_str)
}

fn perm_resolve(rules: &Value, host: &str, perm: &str, default: &str) -> String {
    suffixes(host)
        .iter()
        .find_map(|h| rule(rules, &perm_key(h, perm)))
        .or_else(|| rule(rules, &perm_key("*", perm)))
        .unwrap_or(default)
        .to_string()
}

fn perm_zoom(rules: &Value, host: &str) -> Option<i64> {
    suffixes(host).iter().find_map(|h| rule(rules, &perm_key(h, "zoom"))).and_then(|s| s.parse::<i32>().ok()).map(|n| (n as i64).clamp(50, 300))
}

fn perm_hosts(rules: &Value) -> Value {
    let mut by: std::collections::BTreeMap<String, Map<String, Value>> = std::collections::BTreeMap::new();
    if let Some(o) = rules.as_object() {
        for (k, v) in o {
            if let Some((h, p)) = k.split_once('|') {
                if !h.is_empty() && h != "*" {
                    by.entry(h.to_string()).or_default().insert(p.to_string(), v.clone());
                }
            }
        }
    }
    Value::Array(by.into_iter().map(|(h, r)| json!({ "host": h, "rules": r })).collect())
}

fn perms(op: &str, a: &Value) -> Option<Value> {
    let rules = a.get("rules").cloned().unwrap_or_else(|| json!({}));
    Some(match op {
        "perm_key" => json!({ "key": perm_key(&text(a, "host", ""), &text(a, "perm", "")) }),
        "perm_resolve" => json!({ "value": perm_resolve(&rules, &text(a, "host", ""), &text(a, "perm", ""), &text(a, "default", "ask")) }),
        "perm_default" => json!({ "value": rule(&rules, &perm_key("*", &text(a, "perm", ""))).map(String::from).unwrap_or_else(|| text(a, "default", "ask")) }),
        "perm_zoom" => json!({ "zoom": perm_zoom(&rules, &text(a, "host", "")) }),
        "perm_hosts" => json!({ "hosts": perm_hosts(&rules) }),
        "perm_ordered" => {
            let p = arr(a, "perms");
            let top = |x: &&Value| x.get("top").and_then(Value::as_bool).unwrap_or(false);
            let ids: Vec<String> = p.iter().filter(top).chain(p.iter().filter(|x| !top(x))).map(|x| text(x, "id", "")).collect();
            json!({ "ids": ids })
        }
        "perm_set" => {
            let (host, perm, value) = (text(a, "host", ""), text(a, "perm", ""), text(a, "value", ""));
            if blank(&host) {
                json!({ "error": "host= is required" })
            } else if !["allow", "deny", "ask"].contains(&value.as_str()) {
                json!({ "error": "value must be one of [allow, deny, ask]" })
            } else {
                json!({ "key": perm_key(&host, &perm), "value": value })
            }
        }
        "perm_set_zoom" => {
            let host = text(a, "host", "");
            if blank(&host) {
                json!({ "action": "none" })
            } else {
                let k = perm_key(&host, "zoom");
                match a.get("percent").and_then(Value::as_i64) {
                    None => json!({ "action": "remove", "key": k }),
                    Some(p) => json!({ "action": "put", "key": k, "value": p.clamp(50, 300).to_string() }),
                }
            }
        }
        "perm_forget" => {
            let host = lower(&text(a, "host", ""));
            let keys: Vec<String> = arr(a, "keys").iter().filter_map(|x| x.as_str()).filter(|k| k.split('|').next().unwrap_or("") == host).map(String::from).collect();
            json!({ "remove": keys })
        }
        "perm_map" => {
            let state = text(a, "state", "");
            let gesture = a.get("gesture").and_then(Value::as_bool).unwrap_or(false);
            let kind = text(a, "kind", "");
            match kind.as_str() {
                "autoplay_needs_gesture" => json!({ "value": state != "allow" }),
                "mixed_content" => {
                    let mode = match state.as_str() {
                        "allow" => 0,
                        "deny" => 1,
                        _ => 2,
                    };
                    json!({ "value": mode })
                }
                "popup_allowed" => {
                    let ok = match state.as_str() {
                        "allow" => true,
                        "deny" => false,
                        _ => gesture,
                    };
                    json!({ "value": ok })
                }
                "on_unless_blocked" => json!({ "value": state != "deny" }),
                other => json!({ "error": format!("unknown mapping {other}") }),
            }
        }
        "perm_migrate" => {
            // keys written with an upper-case host never matched a lookup (the lookups lower-case): they move to the canonical key
            let mut moves: Vec<Value> = Vec::new();
            let mut drops: Vec<String> = Vec::new();
            if let Some(o) = rules.as_object() {
                let mut keys: Vec<&String> = o.keys().collect();
                keys.sort();
                for k in keys {
                    if let Some((h, p)) = k.split_once('|') {
                        if !h.is_empty() && lower(h) != h {
                            let to = format!("{}|{p}", lower(h));
                            if o.contains_key(&to) || moves.iter().any(|m| m["to"] == json!(to)) {
                                drops.push(k.clone());
                            } else {
                                moves.push(json!({ "from": k, "to": to, "value": o[k] }));
                            }
                        }
                    }
                }
            }
            json!({ "moves": moves, "drops": drops })
        }
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
    match op {
        "set_parse" => return Ok(set_parse(a)),
        "set_snapshot" => return Ok(set_snapshot(a)),
        "set_migrate" => return Ok(set_migrate(a)),
        _ => {}
    }
    if let Some(v) = perms(op, a) {
        return Ok(v);
    }
    Err(format!("unknown list op {op}"))
}
