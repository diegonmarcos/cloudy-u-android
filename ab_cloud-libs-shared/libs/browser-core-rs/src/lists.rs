//! The browser's list stores in Rust (#1841): history, bookmarks (Quickmarks and Bookmarks), the visit log. The model, search and ranking,
//! dedupe, frecency, sort and filter, import / export (JSON) and the migration of the Kotlin on-disk format. Every op is a pure function
//! `op(json) -> json`, so the Kotlin twin (Lists.kt, KtLists) and this file are held to ONE fixture corpus (fixtures/lists-corpus.json).
//!
//! On-disk format, UNCHANGED from the Kotlin stores (so an older app reads it, the declared prefs stay valid for the fleet config sync):
//!   history    `[{"url","title","ts"}]`, newest first, one row per url, capped (500)
//!   bookmarks  `[{"url","title","folder","ts"}]`, one per url, folder = slash path
//!   quickmarks `[{"title","tiles":[{"id","label","url"|null,"pkg"?}],"folders":[...]}]`
//!   visit log  `[{"u","t","s"}]`
//! Every op takes its list either as `raw` (the stored text) or as an array, answers the list AND its canonical `raw` text, and answers a
//! domain error in band as `{"error": code}`; only a panic or an unknown op is an `Err` (the JNI boundary turns it into `ERR`).

use serde::Serialize;
use serde_json::{json, Map, Value};
use std::collections::{BTreeSet, HashMap, HashSet};

pub const HISTORY_CAP: usize = 500;
pub const VISITLOG_CAP: usize = 20_000;
const DAY_MS: i64 = 86_400_000;
pub const HISTORY_FORMAT: &str = "cloud-browser-history";
pub const BOOKMARKS_FORMAT: &str = "cloud-browser-bookmarks";

/// One recorded page visit (Kotlin BrowserVisit).
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Visit {
    pub url: String,
    pub title: String,
    pub ts: i64,
}

/// One bookmark (Kotlin BrowserBookmark). `folder` is a slash path, "" for the top level.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct Bookmark {
    pub url: String,
    pub title: String,
    pub folder: String,
    pub ts: i64,
}

/// One row of the visit log, as stored (`u`, `t`, `s`).
#[derive(Serialize)]
struct LogRow<'a> {
    u: &'a str,
    t: &'a str,
    s: i64,
}

/// The field names of every model, for the field-parity test (the Kotlin test reads the same fixture).
pub fn model_fields() -> Value {
    let v = serde_json::to_value(Visit { url: String::new(), title: String::new(), ts: 0 }).unwrap_or(Value::Null);
    let b = serde_json::to_value(Bookmark { url: String::new(), title: String::new(), folder: String::new(), ts: 0 }).unwrap_or(Value::Null);
    let keys = |x: &Value| x.as_object().map(|o| o.keys().cloned().collect::<Vec<_>>()).unwrap_or_default();
    json!({ "visit": keys(&v), "bookmark": keys(&b) })
}

// ── tolerant reads (the semantics of org.json's optString / optLong) ────────────────────────────────────────────────────

pub(crate) fn text(v: &Value, k: &str, d: &str) -> String {
    match v.get(k) {
        Some(Value::String(s)) => s.clone(),
        Some(Value::Number(n)) => n.to_string(),
        Some(Value::Bool(b)) => b.to_string(),
        _ => d.to_string(),
    }
}

pub(crate) fn long(v: &Value, k: &str) -> i64 {
    match v.get(k) {
        Some(Value::Number(n)) => n.as_i64().or_else(|| n.as_f64().map(|f| f as i64)).unwrap_or(0),
        Some(Value::String(s)) => s.trim().parse::<i64>().unwrap_or(0),
        _ => 0,
    }
}

pub(crate) fn blank(s: &str) -> bool {
    s.trim().is_empty()
}

pub(crate) fn arr<'a>(a: &'a Value, k: &str) -> &'a [Value] {
    a.get(k).and_then(Value::as_array).map(|v| v.as_slice()).unwrap_or(&[])
}

pub(crate) fn raw_array(raw: &str) -> Vec<Value> {
    match serde_json::from_str::<Value>(raw) {
        Ok(Value::Array(a)) => a,
        _ => vec![],
    }
}

pub(crate) fn cap_of(a: &Value, default: usize) -> usize {
    a.get("cap").and_then(Value::as_i64).filter(|c| *c > 0).map(|c| c as usize).unwrap_or(default)
}

pub(crate) fn lower(s: &str) -> String {
    s.to_lowercase()
}

// ── history ────────────────────────────────────────────────────────────────────────────────────────────────────────────

fn parse_visits(a: &[Value]) -> Vec<Visit> {
    a.iter()
        .filter(|o| o.is_object())
        .filter_map(|o| {
            let url = text(o, "url", "");
            if blank(&url) {
                return None;
            }
            let title = text(o, "title", &url);
            Some(Visit { url, title, ts: long(o, "ts") })
        })
        .collect()
}

fn visits_in(a: &Value) -> Vec<Visit> {
    if let Some(Value::String(raw)) = a.get("raw") {
        return parse_visits(&raw_array(raw));
    }
    parse_visits(arr(a, "visits"))
}

fn by_ts_desc(v: &mut [Visit]) {
    v.sort_by(|x, y| y.ts.cmp(&x.ts)); // stable, like sortedByDescending
}

fn visits_out(v: &[Visit]) -> Value {
    json!({ "visits": v, "raw": serde_json::to_string(v).unwrap_or_else(|_| "[]".into()), "count": v.len() })
}

/// host of a url, lowercase, no scheme, no `www.`, no port, path or query: what the host sort compares.
pub fn host_of(url: &str) -> String {
    let l = lower(url);
    let rest = l.split_once("://").map(|x| x.1).unwrap_or(&l);
    let rest = rest.strip_prefix("www.").unwrap_or(rest);
    rest.split(|c| c == '/' || c == '?' || c == '#' || c == ':').next().unwrap_or("").to_string()
}

/// Frecency weight of a visit by its age (the bands of a browser's location bar): the model carries one time per url, so recency is the signal.
pub fn frecency(ts: i64, now: i64) -> i64 {
    let days = (now - ts).max(0) / DAY_MS;
    match days {
        0..=4 => 100,
        5..=14 => 70,
        15..=31 => 50,
        32..=90 => 30,
        _ => 10,
    }
}

fn sort_idx(v: &[Visit], idx: &mut [usize], sort: &str, now: i64) {
    match sort {
        "recent" => idx.sort_by(|&x, &y| v[y].ts.cmp(&v[x].ts)),
        "oldest" => idx.sort_by(|&x, &y| v[x].ts.cmp(&v[y].ts)),
        "title" => idx.sort_by_key(|&i| lower(&v[i].title)),
        "host" => idx.sort_by(|&x, &y| host_of(&v[x].url).cmp(&host_of(&v[y].url)).then(v[y].ts.cmp(&v[x].ts))),
        "frecency" => idx.sort_by(|&x, &y| frecency(v[y].ts, now).cmp(&frecency(v[x].ts, now)).then(v[y].ts.cmp(&v[x].ts))),
        _ => {} // "input": the order given
    }
}

fn history_query(a: &Value) -> Value {
    let v = visits_in(a);
    let query = text(a, "query", "");
    let q = lower(query.trim());
    let sort = text(a, "sort", "input");
    let ranked = text(a, "mode", "filter") == "ranked" && !q.is_empty();
    let limit = a.get("limit").and_then(Value::as_i64).filter(|l| *l >= 0).map(|l| l as usize).unwrap_or(usize::MAX);
    let now = a.get("now").and_then(Value::as_i64).unwrap_or_else(|| v.iter().map(|x| x.ts).max().unwrap_or(0));
    let mut idx: Vec<usize> = if ranked {
        let entries: Vec<Value> = v.iter().map(|x| json!({ "t": x.title, "u": x.url, "ts": x.ts })).collect();
        crate::search::search(&Value::Array(entries), &query, usize::MAX, true)
    } else {
        (0..v.len()).filter(|&i| q.is_empty() || lower(&v[i].title).contains(&q) || lower(&v[i].url).contains(&q)).collect()
    };
    if !ranked {
        sort_idx(&v, &mut idx, &sort, now);
    }
    idx.truncate(limit);
    let out: Vec<&Visit> = idx.iter().map(|&i| &v[i]).collect();
    json!({ "visits": out, "indices": idx, "count": out.len() })
}

/// The list of an import: `doc` (a value) or `text` (a string), a bare array or an object holding `key`.
fn doc_entries(a: &Value, key: &str) -> Result<Vec<Value>, &'static str> {
    let doc: Value = match a.get("doc") {
        Some(d) => d.clone(),
        None => serde_json::from_str::<Value>(&text(a, "text", "")).map_err(|_| "not_json")?,
    };
    match doc {
        Value::Array(x) => Ok(x),
        Value::Object(o) => match o.get(key) {
            Some(Value::Array(x)) => Ok(x.clone()),
            _ => Err("no_list"),
        },
        _ => Err("not_a_list"),
    }
}

fn history_import(a: &Value) -> Value {
    let entries = match doc_entries(a, "visits") {
        Ok(e) => e,
        Err(code) => return json!({ "error": code }),
    };
    let cap = cap_of(a, HISTORY_CAP);
    let mut v = visits_in(a);
    by_ts_desc(&mut v);
    let mut pos: HashMap<String, usize> = HashMap::new();
    for (i, x) in v.iter().enumerate() {
        pos.entry(x.url.clone()).or_insert(i);
    }
    let (mut added, mut updated, mut skipped) = (0, 0, 0);
    for e in &entries {
        let url = if e.is_object() { text(e, "url", "") } else { String::new() };
        if blank(&url) || url == "about:blank" {
            skipped += 1;
            continue;
        }
        let title = text(e, "title", &url);
        let nv = Visit { url: url.clone(), title, ts: long(e, "ts") };
        match pos.get(&url).copied() {
            Some(i) => {
                if nv.ts > v[i].ts {
                    v[i] = nv;
                    updated += 1;
                }
            }
            None => {
                pos.insert(url, v.len());
                v.push(nv);
                added += 1;
            }
        }
    }
    by_ts_desc(&mut v);
    v.truncate(cap);
    let mut o = visits_out(&v);
    o["added"] = json!(added);
    o["updated"] = json!(updated);
    o["skipped"] = json!(skipped);
    o
}

fn log_in(a: &Value, raw_key: &str, list_key: &str) -> Vec<Visit> {
    if let Some(Value::String(raw)) = a.get(raw_key) {
        return raw_array(raw)
            .iter()
            .filter(|o| o.is_object())
            .filter_map(|o| {
                let url = text(o, "u", "");
                if blank(&url) {
                    return None;
                }
                let title = text(o, "t", &url);
                Some(Visit { url, title, ts: long(o, "s") })
            })
            .collect();
    }
    parse_visits(arr(a, list_key))
}

fn visitlog_merge(a: &Value) -> Value {
    let log = log_in(a, "log_raw", "log");
    let mut history = if let Some(Value::String(_)) = a.get("history_raw") { visits_in(&json!({ "raw": a["history_raw"] })) } else { parse_visits(arr(a, "history")) };
    by_ts_desc(&mut history); // the history the browser lists is newest first: its rows meet the log in that order
    let cap = cap_of(a, VISITLOG_CAP);
    let mut seen: HashSet<(String, i64)> = log.iter().map(|x| (x.url.clone(), x.ts)).collect();
    let mut all: Vec<Visit> = history.into_iter().filter(|x| seen.insert((x.url.clone(), x.ts))).collect();
    all.extend(log);
    by_ts_desc(&mut all);
    all.truncate(cap);
    let rows: Vec<LogRow> = all.iter().map(|x| LogRow { u: &x.url, t: &x.title, s: x.ts }).collect();
    let mut o = visits_out(&all);
    o["raw"] = json!(serde_json::to_string(&rows).unwrap_or_else(|_| "[]".into()));
    o
}

// ── bookmarks ──────────────────────────────────────────────────────────────────────────────────────────────────────────

pub fn norm_folder(f: &str) -> String {
    f.split('/').map(|p| p.trim()).filter(|p| !p.is_empty()).collect::<Vec<_>>().join("/")
}

fn in_folder(f: &str, folder: &str) -> bool {
    f == folder || f.starts_with(&format!("{folder}/"))
}

fn parse_bms(a: &[Value]) -> Vec<Bookmark> {
    a.iter()
        .filter(|o| o.is_object())
        .filter_map(|o| {
            let url = text(o, "url", "");
            if blank(&url) {
                return None;
            }
            let title = text(o, "title", &url);
            Some(Bookmark { url, title, folder: text(o, "folder", ""), ts: long(o, "ts") })
        })
        .collect()
}

fn bms_in(a: &Value) -> Vec<Bookmark> {
    if let Some(Value::String(raw)) = a.get("raw") {
        return parse_bms(&raw_array(raw));
    }
    parse_bms(arr(a, "bookmarks"))
}

fn bms_out(v: &[Bookmark]) -> Value {
    json!({ "bookmarks": v, "raw": serde_json::to_string(v).unwrap_or_else(|_| "[]".into()), "count": v.len() })
}

fn title_or_url(title: String, url: &str) -> String {
    if blank(&title) { url.to_string() } else { title }
}

/// Folder -> bookmarks, the top level ("") first, the rest sorted (first-seen order inside a folder).
fn sections_of(v: &[Bookmark]) -> Vec<(String, Vec<Bookmark>)> {
    let mut out: Vec<(String, Vec<Bookmark>)> = Vec::new();
    for b in v {
        match out.iter().position(|(f, _)| *f == b.folder) {
            Some(i) => out[i].1.push(b.clone()),
            None => out.push((b.folder.clone(), vec![b.clone()])),
        }
    }
    out.sort_by(|x, y| (!x.0.is_empty(), &x.0).cmp(&(!y.0.is_empty(), &y.0)));
    out
}

fn sections_value(s: &[(String, Vec<Bookmark>)]) -> Value {
    Value::Array(s.iter().map(|(f, items)| json!({ "folder": f, "items": items })).collect())
}

fn tokens(q: &str) -> Vec<String> {
    lower(q).split(|c| c == ' ' || c == '\t').filter(|t| !t.is_empty()).map(String::from).collect()
}

fn bm_matches(b: &Bookmark, toks: &[String]) -> bool {
    let hay = lower(&format!("{} {}", b.title, b.url));
    toks.iter().all(|t| hay.contains(t.as_str()))
}

fn bm_view(a: &Value) -> Value {
    let mut secs: Vec<(String, Vec<Bookmark>)> = if a.get("sections").is_some() {
        arr(a, "sections").iter().map(|s| (text(s, "folder", ""), parse_bms(arr(s, "items")))).collect()
    } else {
        sections_of(&bms_in(a))
    };
    if let Some(Value::String(chip)) = a.get("chip") {
        secs.retain(|(f, _)| f.split('/').next().unwrap_or("") == chip.as_str());
    }
    let toks = tokens(&text(a, "query", ""));
    if !toks.is_empty() {
        secs = secs
            .into_iter()
            .filter_map(|(f, items)| {
                let kept: Vec<Bookmark> = items.into_iter().filter(|b| bm_matches(b, &toks)).collect();
                if kept.is_empty() { None } else { Some((f, kept)) }
            })
            .collect();
    }
    json!({ "sections": sections_value(&secs), "count": secs.iter().map(|s| s.1.len()).sum::<usize>() })
}

fn bm_merge_seed(a: &Value) -> Value {
    let mut v = bms_in(a);
    let seed = a.get("seed").cloned().unwrap_or(Value::Null);
    let items = arr(&seed, "items").to_vec();
    let legacy: HashSet<String> = arr(&seed, "legacyGroups").iter().filter_map(|x| x.as_str().map(String::from)).collect();
    let seen: HashSet<String> = arr(a, "seen").iter().filter_map(|x| x.as_str().map(String::from)).collect();
    let version = long(&seed, "version");
    let now = long(a, "now");
    let sorted = |s: &HashSet<String>| s.iter().cloned().collect::<BTreeSet<_>>().into_iter().collect::<Vec<_>>();
    if items.is_empty() {
        let mut o = bms_out(&v);
        o["seen"] = json!(sorted(&seen));
        o["changed"] = json!(false);
        return o;
    }
    for it in &items {
        let url = text(it, "url", "");
        let title = text(it, "title", "");
        let group = text(it, "group", "");
        if !legacy.is_empty() {
            for b in v.iter_mut() {
                if b.url == url && legacy.contains(&b.folder) {
                    b.folder = norm_folder(&group);
                }
            }
        }
        if seen.contains(&url) {
            continue;
        }
        if !v.iter().any(|b| b.url == url) {
            v.push(Bookmark { url, title, folder: norm_folder(&group), ts: now });
        }
    }
    let mut now_seen = seen.clone();
    for it in &items {
        now_seen.insert(text(it, "url", ""));
    }
    let changed = !(now_seen == seen && long(a, "stored_version") >= version);
    let mut o = bms_out(&v);
    o["seen"] = json!(sorted(&now_seen));
    o["changed"] = json!(changed);
    o["version"] = json!(version);
    o
}

fn bm_import(a: &Value) -> Value {
    let entries = match doc_entries(a, "bookmarks") {
        Ok(e) => e,
        Err(code) => return json!({ "error": code }),
    };
    let mut v = bms_in(a);
    let mut pos: HashMap<String, usize> = HashMap::new();
    for (i, x) in v.iter().enumerate() {
        pos.entry(x.url.clone()).or_insert(i);
    }
    let (mut added, mut updated, mut unchanged, mut skipped) = (0, 0, 0, 0);
    for e in &entries {
        let url = if e.is_object() { text(e, "url", "") } else { String::new() };
        if blank(&url) {
            skipped += 1;
            continue;
        }
        let title = text(e, "title", &url);
        let nb = Bookmark { url: url.clone(), title, folder: norm_folder(&text(e, "folder", "")), ts: long(e, "ts") };
        match pos.get(&url).copied() {
            Some(i) => {
                if nb.ts > v[i].ts {
                    v[i] = nb;
                    updated += 1;
                } else {
                    unchanged += 1;
                }
            }
            None => {
                pos.insert(url, v.len());
                v.push(nb);
                added += 1;
            }
        }
    }
    let mut o = bms_out(&v);
    o["added"] = json!(added);
    o["updated"] = json!(updated);
    o["unchanged"] = json!(unchanged);
    o["skipped"] = json!(skipped);
    o
}

// ── Quickmarks (Weby's folders) ────────────────────────────────────────────────────────────────────────────────────────

fn weby_tile(x: &Value) -> Value {
    let url = match x.get("url") {
        Some(Value::String(s)) if !blank(s) => Value::String(s.clone()),
        _ => Value::Null,
    };
    let mut o = Map::new();
    o.insert("id".into(), json!(text(x, "id", "")));
    o.insert("label".into(), json!(text(x, "label", "")));
    o.insert("url".into(), url);
    let pkg = text(x, "pkg", "");
    if !blank(&pkg) {
        o.insert("pkg".into(), json!(pkg));
    }
    Value::Object(o)
}

fn weby_parse(a: &[Value]) -> Vec<Value> {
    a.iter()
        .filter(|o| o.is_object())
        .map(|o| {
            let tiles: Vec<Value> = arr(o, "tiles").iter().filter(|x| x.is_object()).map(weby_tile).collect();
            json!({ "title": text(o, "title", ""), "tiles": tiles, "folders": weby_parse(arr(o, "folders")) })
        })
        .collect()
}

fn weby_all(a: &Value) -> Value {
    const UNSORTED: &str = "Unsorted";
    let mut order: Vec<String> = Vec::new();
    let mut by: HashMap<String, Vec<Value>> = HashMap::new();
    for b in arr(a, "browser") {
        let url = text(b, "url", "");
        let title = text(b, "title", "");
        let folder = text(b, "folder", "");
        let host = url.split_once("://").map(|x| x.1).unwrap_or(&url).split('/').next().unwrap_or("").to_string();
        let label = if blank(&title) { host } else { title };
        let key = if blank(&folder) { UNSORTED.to_string() } else { folder };
        if !by.contains_key(&key) {
            order.push(key.clone());
        }
        by.entry(key).or_default().push(json!({ "id": format!("u:{url}"), "label": label, "url": url }));
    }
    let unsorted = by.remove(UNSORTED).unwrap_or_default();
    order.retain(|k| k != UNSORTED);
    let subs: Vec<Value> = order
        .iter()
        .map(|f| json!({ "title": f.replace('/', "-"), "tiles": by.remove(f).unwrap_or_default(), "folders": [] }))
        .collect();
    let mut out: Vec<Value> = Vec::new();
    if !subs.is_empty() || !unsorted.is_empty() {
        out.push(json!({ "title": "Browser bookmarks", "tiles": unsorted, "folders": subs }));
    }
    let quick = arr(a, "quick");
    if !quick.is_empty() {
        out.push(json!({ "title": "Quickmarks", "tiles": [], "folders": quick }));
    }
    out.extend(arr(a, "catalogue").iter().cloned());
    json!({ "folders": out })
}

// ── migration of the Kotlin on-disk format ─────────────────────────────────────────────────────────────────────────────

/// Reads the stored text of `kind` (history | bookmarks | weby | visitlog) the way the Kotlin store did and answers the canonical list:
/// `valid` false = the text is not a JSON array (the caller must keep it untouched), `dropped` = rows the old reader ignored,
/// `changed` = the canonical list differs from the stored one (a rewrite is due).
fn migrate(a: &Value) -> Result<Value, String> {
    let kind = text(a, "kind", "");
    let raw = text(a, "raw", "");
    let parsed = serde_json::from_str::<Value>(&raw);
    let src: Option<Vec<Value>> = match &parsed {
        Ok(Value::Array(x)) => Some(x.clone()),
        _ => None,
    };
    let valid = src.is_some();
    let src = src.unwrap_or_default();
    let (list, canon): (Value, String) = match kind.as_str() {
        "history" => {
            let mut v = parse_visits(&src);
            by_ts_desc(&mut v);
            (serde_json::to_value(&v).unwrap_or(Value::Null), serde_json::to_string(&v).unwrap_or_default())
        }
        "bookmarks" => {
            let v = parse_bms(&src);
            (serde_json::to_value(&v).unwrap_or(Value::Null), serde_json::to_string(&v).unwrap_or_default())
        }
        "weby" => {
            let v = Value::Array(weby_parse(&src));
            let s = v.to_string();
            (v, s)
        }
        "visitlog" => {
            let v = log_in(&json!({ "raw": raw.clone() }), "raw", "none");
            let rows: Vec<LogRow> = v.iter().map(|x| LogRow { u: &x.url, t: &x.title, s: x.ts }).collect();
            (serde_json::to_value(&rows).unwrap_or(Value::Null), serde_json::to_string(&rows).unwrap_or_default())
        }
        other => match crate::more::migrate_kind(other, &src) {
            Some(x) => x,
            None => return Err(format!("unknown list kind {other}")),
        },
    };
    let count = list.as_array().map(|x| x.len()).unwrap_or(0);
    let changed = match &parsed {
        Ok(p) => *p != list,
        Err(_) => true,
    };
    Ok(json!({ "kind": kind, "valid": valid, "list": list, "raw": canon, "count": count, "dropped": src.len().saturating_sub(count), "changed": changed }))
}

// ── dispatch ───────────────────────────────────────────────────────────────────────────────────────────────────────────

pub fn run(op: &str, a: &Value) -> Result<Value, String> {
    Ok(match op {
        "history_load" => {
            let mut v = visits_in(a);
            by_ts_desc(&mut v);
            visits_out(&v)
        }
        "history_record" => {
            let mut v = visits_in(a);
            by_ts_desc(&mut v);
            let url = text(a, "url", "");
            if blank(&url) || url == "about:blank" {
                let mut o = visits_out(&v);
                o["recorded"] = json!(false);
                return Ok(o);
            }
            v.retain(|x| x.url != url);
            let title = title_or_url(text(a, "title", ""), &url);
            v.insert(0, Visit { url, title, ts: long(a, "now") });
            v.truncate(cap_of(a, HISTORY_CAP));
            let mut o = visits_out(&v);
            o["recorded"] = json!(true);
            o
        }
        "history_dedupe" => {
            let mut v = visits_in(a);
            by_ts_desc(&mut v);
            let mut seen = HashSet::new();
            v.retain(|x| seen.insert(x.url.clone()));
            visits_out(&v)
        }
        "history_query" => history_query(a),
        "history_export" => {
            let v = visits_in(a);
            json!({ "doc": { "format": HISTORY_FORMAT, "version": 1, "count": v.len(), "visits": v } })
        }
        "history_import" => history_import(a),
        "visitlog_merge" => visitlog_merge(a),
        "bm_load" => bms_out(&bms_in(a)),
        "bm_add" => {
            let mut v = bms_in(a);
            let url = text(a, "url", "");
            let b = Bookmark { title: title_or_url(text(a, "title", ""), &url), folder: norm_folder(&text(a, "folder", "")), ts: long(a, "now"), url: url.clone() };
            v.retain(|x| x.url != url);
            v.insert(0, b);
            bms_out(&v)
        }
        "bm_remove" => {
            let url = text(a, "url", "");
            let mut v = bms_in(a);
            v.retain(|x| x.url != url);
            bms_out(&v)
        }
        "bm_has" => {
            let url = text(a, "url", "");
            json!({ "has": bms_in(a).iter().any(|x| x.url == url) })
        }
        "bm_folders" => {
            let mut set = BTreeSet::new();
            for b in bms_in(a) {
                let parts: Vec<&str> = b.folder.split('/').filter(|p| !p.is_empty()).collect();
                for i in 0..parts.len() {
                    set.insert(parts[..=i].join("/"));
                }
            }
            json!({ "folders": set.into_iter().collect::<Vec<_>>() })
        }
        "bm_move_folder" => {
            let from = norm_folder(&text(a, "from", ""));
            let to = norm_folder(&text(a, "to", ""));
            let mut v = bms_in(a);
            if !from.is_empty() {
                for b in v.iter_mut() {
                    if in_folder(&b.folder, &from) {
                        b.folder = norm_folder(&format!("{to}{}", &b.folder[from.len()..]));
                    }
                }
            }
            bms_out(&v)
        }
        "bm_delete_folder" => {
            let f = norm_folder(&text(a, "folder", ""));
            let mut v = bms_in(a);
            if !f.is_empty() {
                v.retain(|b| !in_folder(&b.folder, &f));
            }
            bms_out(&v)
        }
        "bm_edit" => {
            let url = text(a, "url", "");
            let mut v = bms_in(a);
            if let Some(cur) = v.iter().find(|x| x.url == url).cloned() {
                let title = title_or_url(text(a, "title", ""), &url);
                let folder = norm_folder(&text(a, "folder", ""));
                for x in v.iter_mut().filter(|x| x.url == url) {
                    *x = Bookmark { url: cur.url.clone(), title: title.clone(), folder: folder.clone(), ts: cur.ts };
                }
            }
            bms_out(&v)
        }
        "bm_sections" => json!({ "sections": sections_value(&sections_of(&bms_in(a))) }),
        "bm_view" => bm_view(a),
        "bm_merge_seed" => bm_merge_seed(a),
        "bm_export" => {
            let v = bms_in(a);
            json!({ "doc": { "format": BOOKMARKS_FORMAT, "version": 1, "count": v.len(), "bookmarks": v } })
        }
        "bm_import" => bm_import(a),
        "weby_load" => {
            let parsed = serde_json::from_str::<Value>(&text(a, "raw", ""));
            match parsed {
                Ok(Value::Array(x)) => json!({ "folders": weby_parse(&x) }),
                _ => json!({ "folders": null }),
            }
        }
        "weby_encode" => json!({ "raw": Value::Array(weby_parse(arr(a, "folders"))).to_string() }),
        "weby_all" => weby_all(a),
        "migrate" => migrate(a)?,
        other => return crate::more::run(other, a),
    })
}
