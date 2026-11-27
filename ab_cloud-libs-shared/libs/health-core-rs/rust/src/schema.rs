//! A small JSON Schema checker for the subset the c3-report/v1 contract uses: type, required, properties, items, enum, const.
//! The Rust tests and the Kotlin parser test validate the same fixture files against the same schema file.

use crate::json::Json;

fn type_ok(t: &str, v: &Json) -> bool {
    match (t, v) {
        ("object", Json::Obj(_)) | ("array", Json::Arr(_)) | ("string", Json::Str(_)) | ("boolean", Json::Bool(_)) | ("null", Json::Null) => true,
        ("number", Json::Num(_)) => true,
        ("integer", Json::Num(n)) => n.fract() == 0.0,
        _ => false,
    }
}

/// Every violation as "path: what"; empty = valid.
pub fn validate(schema: &Json, v: &Json) -> Vec<String> {
    let mut out = Vec::new();
    walk(schema, v, "$", &mut out);
    out
}

fn walk(sc: &Json, v: &Json, path: &str, out: &mut Vec<String>) {
    match sc.get("type") {
        Some(Json::Str(t)) => if !type_ok(t, v) { out.push(format!("{}: want {}", path, t)); return; },
        Some(Json::Arr(ts)) => if !ts.iter().any(|t| t.as_str().map(|t| type_ok(t, v)).unwrap_or(false)) {
            out.push(format!("{}: want one of {:?}", path, ts.iter().filter_map(|t| t.as_str()).collect::<Vec<_>>())); return;
        },
        _ => {}
    }
    if let Some(Json::Arr(e)) = sc.get("enum") {
        if !e.contains(v) { out.push(format!("{}: not in enum", path)); }
    }
    if let Some(c) = sc.get("const") {
        if c != v { out.push(format!("{}: not the const", path)); }
    }
    if let Json::Obj(kv) = v {
        for r in sc.strs("required") {
            if !kv.iter().any(|(k, _)| *k == r) { out.push(format!("{}: missing {}", path, r)); }
        }
        if let Some(Json::Obj(props)) = sc.get("properties") {
            for (k, psc) in props {
                if let Some((_, x)) = kv.iter().find(|(a, _)| a == k) { walk(psc, x, &format!("{}.{}", path, k), out); }
            }
        }
    }
    if let (Json::Arr(items), Some(isc)) = (v, sc.get("items")) {
        for (i, x) in items.iter().enumerate() { walk(isc, x, &format!("{}[{}]", path, i), out); }
    }
}
