//! The JNI boundary (class com.diegonmarcos.cloudlib.browsercore.NativeBridge). EVERY entry point runs inside [guard]: a panic or an
//! error becomes an `ERR\t<reason>` answer and Cloud Browser runs its Kotlin fallback for that call. Nothing here can unwind into the JVM.
//! An answer is `OK\n<json>` or `ERR\t<reason>`; the filter's hot call answers a plain boolean (false on any failure).

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde_json::{json, Value};

use crate::filter::FilterSet;
use crate::search;
use crate::store::SessionStore;

fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

fn one_line(s: &str) -> String {
    s.replace(['\n', '\r', '\t'], " ")
}

/// Run `f`; its Ok text is the answer, its Err text or a panic becomes `ERR`.
pub fn guard<F: FnOnce() -> Result<String, String>>(f: F) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(body)) => format!("OK\n{}", body),
        Ok(Err(e)) => format!("ERR\t{}", one_line(&e)),
        Err(p) => format!("ERR\tpanic: {}", one_line(&panic_text(p))),
    }
}

type Shared = Arc<Mutex<SessionStore>>;

fn stores() -> &'static Mutex<HashMap<String, Shared>> {
    static S: OnceLock<Mutex<HashMap<String, Shared>>> = OnceLock::new();
    S.get_or_init(Default::default)
}

fn store_for(path: &str) -> Result<Shared, String> {
    let mut m = stores().lock().unwrap_or_else(|p| p.into_inner());
    if let Some(s) = m.get(path) {
        return Ok(s.clone());
    }
    let s = Arc::new(Mutex::new(SessionStore::open(path)?));
    m.insert(path.to_string(), s.clone());
    Ok(s)
}

/// One store call. ops: put (arg: snapshot json), get (arg: key), list, delete (arg: key), prune (arg: json array of keys to keep), count.
pub fn do_store(db: &str, op: &str, arg: &str) -> Result<String, String> {
    let shared = store_for(db)?;
    let s = shared.lock().unwrap_or_else(|p| p.into_inner());
    match op {
        "put" => {
            let v: Value = serde_json::from_str(arg).map_err(|e| format!("snapshot json: {e}"))?;
            s.put(&v)?;
            Ok("{\"ok\":true}".into())
        }
        "get" => Ok(json!({ "snapshot": s.get(arg)? }).to_string()),
        "list" => Ok(json!({ "snapshots": s.list()? }).to_string()),
        "delete" => Ok(json!({ "deleted": s.delete(arg)? }).to_string()),
        "prune" => {
            let keep: Vec<String> = serde_json::from_str(arg).map_err(|e| format!("keys json: {e}"))?;
            Ok(json!({ "pruned": s.prune(&keep)? }).to_string())
        }
        "count" => Ok(json!({ "count": s.count()? }).to_string()),
        // the browser's stores (history, bookmark, setting, perm, download): arg is json {ns,key,val,ts,limit,cap,url,title}
        "row_put" | "row_get" | "row_list" | "row_delete" | "row_clear" | "row_count" | "history_visit" => {
            let a: Value = serde_json::from_str(arg).map_err(|e| format!("row json: {e}"))?;
            let ns = a.get("ns").and_then(Value::as_str).unwrap_or("");
            let key = a.get("key").and_then(Value::as_str).unwrap_or("");
            let ts = a.get("ts").and_then(Value::as_i64).unwrap_or(0);
            match op {
                "row_put" => { s.row_put(ns, key, a.get("val").and_then(Value::as_str).unwrap_or(""), ts)?; Ok("{\"ok\":true}".into()) }
                "row_get" => Ok(match s.row_get(ns, key)? {
                    Some((v, t)) => json!({ "val": v, "ts": t }),
                    None => json!({ "val": null }),
                }.to_string()),
                "row_list" => Ok(json!({ "rows": s.row_list(ns, a.get("limit").and_then(Value::as_i64).unwrap_or(1000))?
                    .into_iter().map(|(k, v, t)| json!({ "key": k, "val": v, "ts": t })).collect::<Vec<_>>() }).to_string()),
                "row_delete" => Ok(json!({ "deleted": s.row_delete(ns, key)? }).to_string()),
                "row_clear" => Ok(json!({ "cleared": s.row_clear(ns)? }).to_string()),
                "row_count" => Ok(json!({ "count": s.row_count(ns)? }).to_string()),
                _ => {
                    let url = a.get("url").and_then(Value::as_str).unwrap_or("");
                    s.history_visit(url, a.get("title").and_then(Value::as_str).unwrap_or(""), ts, a.get("cap").and_then(Value::as_i64).unwrap_or(2000))?;
                    Ok("{\"ok\":true}".into())
                }
            }
        }
        other => Err(format!("unknown op {other}")),
    }
}

pub fn do_search(entries: &str, query: &str, limit: i32, ranked: bool) -> Result<String, String> {
    let v: Value = serde_json::from_str(entries).map_err(|e| format!("entries json: {e}"))?;
    let idx = search::search(&v, query, limit.max(0) as usize, ranked);
    Ok(serde_json::to_string(&idx).map_err(|e| e.to_string())?)
}

fn filters() -> &'static Mutex<HashMap<i64, Arc<FilterSet>>> {
    static F: OnceLock<Mutex<HashMap<i64, Arc<FilterSet>>>> = OnceLock::new();
    F.get_or_init(Default::default)
}

static NEXT: std::sync::atomic::AtomicI64 = std::sync::atomic::AtomicI64::new(1);

/// Compile a rule set; the handle stays valid until [filter_free].
pub fn filter_new(rules: &str) -> i64 {
    let h = NEXT.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
    filters().lock().unwrap_or_else(|p| p.into_inner()).insert(h, Arc::new(FilterSet::parse(rules)));
    h
}

pub fn filter_free(h: i64) {
    filters().lock().unwrap_or_else(|p| p.into_inner()).remove(&h);
}

pub fn filter_blocks_url(h: i64, url: &str, request_host: &str, page_host: &str, main_frame: bool) -> bool {
    let set = filters().lock().unwrap_or_else(|p| p.into_inner()).get(&h).cloned();
    set.map(|s| s.should_block_url(url, request_host, page_host, main_frame)).unwrap_or(false)
}

pub fn filter_blocks(h: i64, request_host: &str, page_host: &str, main_frame: bool) -> bool {
    let set = filters().lock().unwrap_or_else(|p| p.into_inner()).get(&h).cloned();
    set.map(|s| s.should_block(request_host, page_host, main_frame)).unwrap_or(false)
}

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|j| j.into()).map_err(|e| format!("jni string: {e}"))
}

fn answer(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeVersion(mut env: JNIEnv, _c: JClass) -> jstring {
    answer(&mut env, crate::VERSION.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeStore(
    mut env: JNIEnv,
    _c: JClass,
    db: JString,
    op: JString,
    arg: JString,
) -> jstring {
    let text = match (read(&mut env, &db), read(&mut env, &op), read(&mut env, &arg)) {
        (Ok(d), Ok(o), Ok(a)) => guard(|| do_store(&d, &o, &a)),
        (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeSearch(
    mut env: JNIEnv,
    _c: JClass,
    entries: JString,
    query: JString,
    limit: jint,
    ranked: jboolean,
) -> jstring {
    let text = match (read(&mut env, &entries), read(&mut env, &query)) {
        (Ok(e), Ok(q)) => guard(|| do_search(&e, &q, limit, ranked != JNI_FALSE)),
        (Err(e), _) | (_, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeFilterNew(mut env: JNIEnv, _c: JClass, rules: JString) -> jlong {
    let r = match read(&mut env, &rules) {
        Ok(r) => r,
        Err(_) => return 0,
    };
    catch_unwind(AssertUnwindSafe(|| filter_new(&r))).unwrap_or(0)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeFilterFree(_env: JNIEnv, _c: JClass, handle: jlong) {
    let _ = catch_unwind(AssertUnwindSafe(|| filter_free(handle)));
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeFilterBlocks(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
    request_host: JString,
    page_host: JString,
    main_frame: jboolean,
) -> jboolean {
    let (r, p) = match (read(&mut env, &request_host), read(&mut env, &page_host)) {
        (Ok(r), Ok(p)) => (r, p),
        _ => return JNI_FALSE,
    };
    let hit = catch_unwind(AssertUnwindSafe(|| filter_blocks(handle, &r, &p, main_frame != JNI_FALSE))).unwrap_or(false);
    if hit { JNI_TRUE } else { JNI_FALSE }
}

/// The address bar and start-up decisions, and the reader extraction, as `OK\n<json>`.
pub fn do_omnibox(op: &str, a: &str, b: &str) -> Result<String, String> {
    match op {
        "resolve" => Ok(json!({ "url": crate::omnibox::resolve(a, b) }).to_string()),
        "is_url" => Ok(json!({ "url_like": crate::omnibox::is_url_like(a) }).to_string()),
        "start" => {
            let v: Value = serde_json::from_str(a).map_err(|e| format!("start json: {e}"))?;
            let f = |k: &str| v.get(k).and_then(Value::as_bool).unwrap_or(false);
            let (action, offer) = crate::omnibox::start_decision(f("unclean"), f("has_last"), f("restore_setting"), f("open_url"), f("kept"));
            Ok(json!({ "action": action, "offer": offer }).to_string())
        }
        "reader" => {
            let (title, paragraphs) = crate::reader::extract(a);
            Ok(json!({ "title": title, "paragraphs": paragraphs }).to_string())
        }
        "download_next" => Ok(json!({ "state": crate::store::download_next(a, b)? }).to_string()),
        "merge_lists" => {
            let lists: Vec<String> = serde_json::from_str(a).map_err(|e| format!("lists json: {e}"))?;
            Ok(json!({ "rules": crate::filter::merge(&lists) }).to_string())
        }
        other => Err(format!("unknown op {other}")),
    }
}

/// One list-store op (history, bookmarks, Quickmarks, visit log; see lists.rs): arg is the op's json, the answer `OK\n<json>`.
pub fn do_lists(op: &str, arg: &str) -> Result<String, String> {
    let a: Value = serde_json::from_str(arg).map_err(|e| format!("lists json: {e}"))?;
    Ok(crate::lists::run(op, &a)?.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeLists(mut env: JNIEnv, _c: JClass, op: JString, arg: JString) -> jstring {
    let text = match (read(&mut env, &op), read(&mut env, &arg)) {
        (Ok(o), Ok(a)) => guard(|| do_lists(&o, &a)),
        (Err(e), _) | (_, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeOmnibox(
    mut env: JNIEnv,
    _c: JClass,
    op: JString,
    a: JString,
    b: JString,
) -> jstring {
    let text = match (read(&mut env, &op), read(&mut env, &a), read(&mut env, &b)) {
        (Ok(o), Ok(a), Ok(b)) => guard(|| do_omnibox(&o, &a, &b)),
        (Err(e), _, _) | (_, Err(e), _) | (_, _, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativeFilterBlocksUrl(
    mut env: JNIEnv,
    _c: JClass,
    handle: jlong,
    url: JString,
    request_host: JString,
    page_host: JString,
    main_frame: jboolean,
) -> jboolean {
    let (u, r, p) = match (read(&mut env, &url), read(&mut env, &request_host), read(&mut env, &page_host)) {
        (Ok(u), Ok(r), Ok(p)) => (u, r, p),
        _ => return JNI_FALSE,
    };
    let hit = catch_unwind(AssertUnwindSafe(|| filter_blocks_url(handle, &u, &r, &p, main_frame != JNI_FALSE))).unwrap_or(false);
    if hit { JNI_TRUE } else { JNI_FALSE }
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_browsercore_NativeBridge_nativePanicProbe(mut env: JNIEnv, _c: JClass) -> jstring {
    let text = guard(|| -> Result<String, String> { panic!("browser-core-rs panic probe") });
    answer(&mut env, text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_panic_becomes_an_err_answer() {
        let a = guard(|| -> Result<String, String> { panic!("browser-core-rs panic probe") });
        assert!(a.starts_with("ERR\tpanic: browser-core-rs panic probe"), "{a}");
        assert_eq!(guard(|| Err("no\nsuch".into())), "ERR\tno such");
        assert_eq!(guard(|| Ok("{}".into())), "OK\n{}");
    }

    #[test]
    fn store_calls_answer_through_the_boundary() {
        let a = guard(|| do_store(":memory:", "count", ""));
        assert_eq!(a, "OK\n{\"count\":0}");
        let a = guard(|| do_store(":memory:", "nope", ""));
        assert_eq!(a, "ERR\tunknown op nope");
        let a = guard(|| do_store(":memory:", "put", "not json"));
        assert!(a.starts_with("ERR\tsnapshot json"), "{a}");
    }
}
