//! History and bookmark search over a JSON list `[{"t":title,"u":url,"ts":millis}]`. Answers indices into that list.

use serde_json::Value;

fn field<'a>(v: &'a Value, k: &str) -> &'a str {
    v.get(k).and_then(Value::as_str).unwrap_or("")
}

/// The host and path of `url` (lowercase), no scheme, no `www.`, no query: what a typed prefix is matched against.
fn host_path(url: &str) -> String {
    let l = url.to_lowercase();
    let rest = l.split_once("://").map(|x| x.1).unwrap_or(&l);
    rest.strip_prefix("www.").unwrap_or(rest).to_string()
}

/// `ranked == false`: the History page's filter, every entry whose title or url contains the whole trimmed query (case-blind),
/// in the INPUT order. `ranked == true`: every entry holding every word of the query, best first (host prefix, title prefix, a word
/// of the title starting with it, plain substring; more recent first among equals).
pub fn search(entries: &Value, query: &str, limit: usize, ranked: bool) -> Vec<usize> {
    let arr = match entries.as_array() {
        Some(a) => a,
        None => return vec![],
    };
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        return (0..arr.len().min(limit)).collect();
    }
    if !ranked {
        return arr
            .iter()
            .enumerate()
            .filter(|(_, v)| field(v, "t").to_lowercase().contains(&q) || field(v, "u").to_lowercase().contains(&q))
            .map(|(i, _)| i)
            .take(limit)
            .collect();
    }
    let words: Vec<&str> = q.split_whitespace().collect();
    let mut scored: Vec<(i64, i64, usize)> = Vec::new();
    for (i, v) in arr.iter().enumerate() {
        let title = field(v, "t").to_lowercase();
        let url = field(v, "u").to_lowercase();
        let hp = host_path(&url);
        let mut total = 0i64;
        let mut all = true;
        for w in &words {
            let s = if hp.starts_with(w) {
                100
            } else if title.starts_with(w) {
                80
            } else if title.split(|c: char| !c.is_alphanumeric()).any(|t| t.starts_with(w)) {
                60
            } else if title.contains(w) || url.contains(w) {
                30
            } else {
                0
            };
            if s == 0 {
                all = false;
                break;
            }
            total += s;
        }
        if all {
            scored.push((total, v.get("ts").and_then(Value::as_i64).unwrap_or(0), i));
        }
    }
    scored.sort_by(|a, b| b.0.cmp(&a.0).then(b.1.cmp(&a.1)).then(a.2.cmp(&b.2)));
    scored.into_iter().map(|x| x.2).take(limit).collect()
}
