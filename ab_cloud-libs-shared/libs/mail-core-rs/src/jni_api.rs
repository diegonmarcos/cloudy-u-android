//! The JNI boundary (class com.diegonmarcos.cloudlib.mailcore.NativeBridge). EVERY entry point runs inside [guard]: a panic or an
//! error becomes an `ERR\t<reason>` answer, and Cloud Mail keeps its previous engine for that pass and logs why. Nothing here
//! can unwind into the JVM. An answer is `OK\n<json>` or `ERR\t<reason>`.
//!
//! Stores are cached per database path: ONE writer connection (the sync, serialised by its mutex) and ONE reader connection
//! (the list queries), so in WAL mode a list read never waits for a sync's writes.

use std::cell::RefCell;
use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JObject, JString, JValue};
use jni::sys::jstring;
use jni::JNIEnv;
use serde_json::{json, Value};

use crate::jmap::Transport;
use crate::store::Store;
use crate::{query, store, sync};

/// The prefix a Kotlin transport answers a failed request with (instead of throwing across JNI).
pub const TRANSPORT_ERR: &str = "\u{1}ERR\t";

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

type Shared = Arc<Mutex<Store>>;

fn cache(writer: bool) -> &'static Mutex<HashMap<String, Shared>> {
    static W: OnceLock<Mutex<HashMap<String, Shared>>> = OnceLock::new();
    static R: OnceLock<Mutex<HashMap<String, Shared>>> = OnceLock::new();
    if writer { W.get_or_init(Default::default) } else { R.get_or_init(Default::default) }
}

fn store_for(path: &str, writer: bool) -> Result<Shared, String> {
    let mut m = cache(writer).lock().unwrap_or_else(|p| p.into_inner());
    if let Some(s) = m.get(path) {
        return Ok(s.clone());
    }
    let s = Arc::new(Mutex::new(Store::open(path)?));
    m.insert(path.to_string(), s.clone());
    Ok(s)
}

fn with_store<T>(path: &str, writer: bool, f: impl FnOnce(&Store) -> Result<T, String>) -> Result<T, String> {
    let shared = store_for(path, writer)?;
    let guard = shared.lock().unwrap_or_else(|p| p.into_inner());
    f(&guard)
}

pub fn do_sync(db: &str, config: &str, t: &dyn Transport) -> Result<String, String> {
    let cfg: sync::SyncConfig = serde_json::from_str(config).map_err(|e| format!("sync config: {e}"))?;
    with_store(db, true, |st| sync::sync(st, t, &cfg)).and_then(|r| serde_json::to_string(&r).map_err(|e| e.to_string()))
}

fn s<'a>(q: &'a Value, k: &str) -> Result<&'a str, String> {
    q.get(k).and_then(Value::as_str).ok_or_else(|| format!("query without {k}"))
}

/// One read (or the account drop) described by a JSON object with an `op`.
pub fn do_query(db: &str, q: &str) -> Result<String, String> {
    let q: Value = serde_json::from_str(q).map_err(|e| format!("query: {e}"))?;
    let op = s(&q, "op")?.to_string();
    let parse = |e: serde_json::Error| format!("{op}: {e}");
    let out = match op.as_str() {
        "list" => {
            let a: query::ListArgs = serde_json::from_value(q.clone()).map_err(parse)?;
            with_store(db, false, |st| query::list(&st.conn, &a))?
        }
        "changes" => {
            let a: query::ChangesArgs = serde_json::from_value(q.clone()).map_err(parse)?;
            with_store(db, false, |st| query::changes_since(&st.conn, &a))?
        }
        "counts" => with_store(db, false, |st| query::counts(&st.conn, s(&q, "account")?))?,
        "primaries" => {
            let ids: Vec<String> = q.get("ids").and_then(Value::as_array).map(|a| a.iter().filter_map(|v| v.as_str().map(String::from)).collect()).unwrap_or_default();
            with_store(db, false, |st| {
                let refs: Vec<&str> = ids.iter().map(String::as_str).collect();
                query::primary_of(&st.conn, s(&q, "account")?, &refs).map(|m| json!({ "primary": m }))
            })?
        }
        "members" => with_store(db, false, |st| query::member_ids(&st.conn, s(&q, "account")?, s(&q, "mailbox")?))?,
        "search" => {
            let limit = q.get("limit").and_then(Value::as_u64).unwrap_or(100) as usize;
            with_store(db, false, |st| query::search(&st.conn, s(&q, "account")?, s(&q, "text")?, limit))?
        }
        "compose" => {
            let c: crate::compose::Compose = serde_json::from_value(q.clone()).map_err(parse)?;
            json!({ "mime": crate::compose::build(&c) })
        }
        "reader" => with_store(db, false, |st| query::reader(&st.conn, s(&q, "account")?, s(&q, "id")?))?,
        "wrap" => {
            let o: crate::reader::Opts = serde_json::from_value(q.clone()).map_err(parse)?;
            json!({ "doc": crate::reader::wrap(s(&q, "kind")?, s(&q, "fragment")?, &o) })
        }
        "body" => with_store(db, false, |st| query::body(&st.conn, s(&q, "account")?, s(&q, "id")?))?,
        "stats" => with_store(db, false, |st| query::stats(&st.conn))?,
        "accounts" => with_store(db, false, |st| query::accounts(&st.conn))?,
        "dropAccount" => with_store(db, true, |st| {
            store::drop_account(&st.conn, s(&q, "account")?)?;
            Ok(serde_json::json!({ "dropped": true }))
        })?,
        other => return Err(format!("unknown op {other}")),
    };
    Ok(out.to_string())
}

/// The on-demand fetch of raw messages (a message opened before the prefetch reached it). Its own short-lived connection, not
/// the cached writer: a long sync holds that one, and an open must not wait for it (SQLite's busy timeout serialises the writes).
pub fn do_fetch_raw(db: &str, args: &str, t: &dyn Transport) -> Result<String, String> {
    let a: sync::FetchArgs = serde_json::from_str(args).map_err(|e| format!("fetch args: {e}"))?;
    let st = Store::open(db)?;
    let r = sync::fetch_raw(&st, t, &a)?;
    serde_json::to_string(&r).map_err(|e| e.to_string())
}

/// A deliberate panic, so the boundary's catch is provable: `ERR\tpanic: ...`.
pub fn do_panic_probe() -> Result<String, String> {
    panic!("mail-core-rs panic probe");
}

/// The Kotlin transport (`MailCoreTransport.post(String): String`), called on the thread that called nativeSync. Each call
/// runs in its own local frame, so a sync of thousands of requests holds no growing set of JNI local references.
struct JniTransport<'a, 'local, 'o> {
    env: RefCell<&'a mut JNIEnv<'local>>,
    obj: &'a JObject<'o>,
}

impl Transport for JniTransport<'_, '_, '_> {
    fn download(&self, blob_id: &str) -> Result<Vec<u8>, String> {
        self.download_bytes(blob_id)
    }

    fn post(&self, body: &str) -> Result<String, String> {
        let mut env = self.env.borrow_mut();
        let obj = self.obj;
        let r: Result<Result<String, String>, jni::errors::Error> = env.with_local_frame(8, |env| {
            let jbody = env.new_string(body)?;
            match env.call_method(obj, "post", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&jbody)]) {
                Ok(v) => {
                    let o = v.l()?;
                    if o.is_null() {
                        return Ok(Err("the transport answered null".to_string()));
                    }
                    let js = JString::from(o);
                    let text: String = env.get_string(&js)?.into();
                    Ok(match text.strip_prefix(TRANSPORT_ERR) {
                        Some(e) => Err(e.to_string()),
                        None => Ok(text),
                    })
                }
                Err(e) => {
                    if env.exception_check().unwrap_or(false) {
                        let _ = env.exception_clear();
                    }
                    Ok(Err(format!("the transport threw: {e}")))
                }
            }
        });
        match r {
            Ok(inner) => inner,
            Err(e) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                Err(format!("JNI: {e}"))
            }
        }
    }
}

impl JniTransport<'_, '_, '_> {
    /// `MailCoreTransport.download(String): ByteArray`: a thrown exception is an Err, never an unwind into the JVM.
    fn download_bytes(&self, blob_id: &str) -> Result<Vec<u8>, String> {
        let mut env = self.env.borrow_mut();
        let obj = self.obj;
        let r: Result<Result<Vec<u8>, String>, jni::errors::Error> = env.with_local_frame(8, |env| {
            let jid = env.new_string(blob_id)?;
            match env.call_method(obj, "download", "(Ljava/lang/String;)[B", &[JValue::Object(&jid)]) {
                Ok(v) => {
                    let o = v.l()?;
                    if o.is_null() {
                        return Ok(Err("the transport answered null".to_string()));
                    }
                    let arr = JByteArray::from(o);
                    let bytes = env.convert_byte_array(arr)?;
                    Ok(Ok(bytes))
                }
                Err(e) => {
                    if env.exception_check().unwrap_or(false) {
                        let _ = env.exception_clear();
                    }
                    Ok(Err(format!("the download threw: {e}")))
                }
            }
        });
        match r {
            Ok(inner) => inner,
            Err(e) => {
                if env.exception_check().unwrap_or(false) {
                    let _ = env.exception_clear();
                }
                Err(format!("JNI: {e}"))
            }
        }
    }
}

/// The Kotlin byte pipe (`MailCoreImapIo`): write(ByteArray), read(timeoutMs): ByteArray (empty = timed out, null = closed).
struct JniImapIo<'a, 'local, 'o> {
    env: &'a mut JNIEnv<'local>,
    obj: &'a JObject<'o>,
}

impl JniImapIo<'_, '_, '_> {
    fn clear(&mut self) {
        if self.env.exception_check().unwrap_or(false) {
            let _ = self.env.exception_clear();
        }
    }
}

impl crate::imap::ImapIo for JniImapIo<'_, '_, '_> {
    fn write(&mut self, bytes: &[u8]) -> Result<(), String> {
        let r = self.env.with_local_frame(8, |env| {
            let arr = env.byte_array_from_slice(bytes)?;
            env.call_method(self.obj, "write", "([B)V", &[JValue::Object(&arr)]).map(|_| ())
        });
        match r {
            Ok(()) => Ok(()),
            Err(e) => {
                self.clear();
                Err(format!("the socket write failed: {e}"))
            }
        }
    }

    fn read(&mut self, timeout_ms: u32) -> Result<Vec<u8>, crate::imap::IoErr> {
        use crate::imap::IoErr;
        let r: Result<Option<Vec<u8>>, jni::errors::Error> = self.env.with_local_frame(8, |env| {
            let v = env.call_method(self.obj, "read", "(I)[B", &[JValue::Int(timeout_ms as i32)])?;
            let o = v.l()?;
            if o.is_null() {
                return Ok(None);
            }
            Ok(Some(env.convert_byte_array(JByteArray::from(o))?))
        });
        match r {
            Ok(Some(b)) if b.is_empty() => Err(IoErr::Timeout),
            Ok(Some(b)) => Ok(b),
            Ok(None) => Err(IoErr::Closed),
            Err(e) => {
                self.clear();
                Err(IoErr::Other(format!("the socket read failed: {e}")))
            }
        }
    }
}

/// `{user,password,mailbox,uids|newest}` -> the fetch report as JSON, over the Kotlin byte pipe.
pub fn do_imap_fetch(args: &str, io: &mut dyn crate::imap::ImapIo) -> Result<String, String> {
    let a: crate::imap::FetchArgs = serde_json::from_str(args).map_err(|e| format!("imap args: {e}"))?;
    let r = crate::imap::fetch_mailbox(io, &a)?;
    serde_json::to_string(&r).map_err(|e| e.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativeImapFetch(
    mut env: JNIEnv,
    _c: JClass,
    args: JString,
    io: JObject,
) -> jstring {
    let text = match read(&mut env, &args) {
        Ok(a) => {
            let mut p = JniImapIo { env: &mut env, obj: &io };
            guard(|| do_imap_fetch(&a, &mut p))
        }
        Err(e) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

fn read(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|j| j.into()).map_err(|e| e.to_string())
}

fn answer(env: &mut JNIEnv, text: String) -> jstring {
    match env.new_string(text) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativeVersion(mut env: JNIEnv, _c: JClass) -> jstring {
    answer(&mut env, crate::VERSION.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativeSync(
    mut env: JNIEnv,
    _c: JClass,
    db: JString,
    config: JString,
    transport: JObject,
) -> jstring {
    let text = match (read(&mut env, &db), read(&mut env, &config)) {
        (Ok(d), Ok(c)) => {
            let t = JniTransport { env: RefCell::new(&mut env), obj: &transport };
            guard(|| do_sync(&d, &c, &t))
        }
        (Err(e), _) | (_, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativeFetchRaw(
    mut env: JNIEnv,
    _c: JClass,
    db: JString,
    args: JString,
    transport: JObject,
) -> jstring {
    let text = match (read(&mut env, &db), read(&mut env, &args)) {
        (Ok(d), Ok(a)) => {
            let t = JniTransport { env: RefCell::new(&mut env), obj: &transport };
            guard(|| do_fetch_raw(&d, &a, &t))
        }
        (Err(e), _) | (_, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativeQuery(
    mut env: JNIEnv,
    _c: JClass,
    db: JString,
    q: JString,
) -> jstring {
    let text = match (read(&mut env, &db), read(&mut env, &q)) {
        (Ok(d), Ok(q)) => guard(|| do_query(&d, &q)),
        (Err(e), _) | (_, Err(e)) => format!("ERR\t{}", one_line(&e)),
    };
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_mailcore_NativeBridge_nativePanicProbe(mut env: JNIEnv, _c: JClass) -> jstring {
    let text = guard(do_panic_probe);
    answer(&mut env, text)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_panic_becomes_an_err_answer() {
        let a = guard(do_panic_probe);
        assert!(a.starts_with("ERR\tpanic: mail-core-rs panic probe"), "{a}");
        assert_eq!(guard(|| Err("no\nsuch".into())), "ERR\tno such");
        assert_eq!(guard(|| Ok("{}".into())), "OK\n{}");
    }

    #[test]
    fn queries_answer_through_the_boundary() {
        let dir = std::env::temp_dir().join(format!("mail-core-jni-{}", std::process::id()));
        let db = dir.join("mail.db").to_string_lossy().to_string();
        let a = guard(|| do_query(&db, r#"{"op":"stats"}"#));
        assert!(a.starts_with("OK\n{"), "{a}");
        let a = guard(|| do_query(&db, r#"{"op":"list","account":"a","mailbox":"inbox"}"#));
        assert_eq!(a, "OK\n{\"rows\":[],\"total\":0}");
        let a = guard(|| do_query(&db, r#"{"op":"nope"}"#));
        assert_eq!(a, "ERR\tunknown op nope");
        let a = guard(|| do_sync(&db, "not json", &crate::fake::FakeJmap::new()));
        assert!(a.starts_with("ERR\tsync config"), "{a}");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
