//! The JNI boundary (class com.diegonmarcos.cloudlib.drivecore.NativeBridge). EVERY entry point runs inside [guard]: a panic or an
//! error becomes an `ERR\t<reason>` answer and Kotlin falls back to its own engine and logs the reason. Nothing here can unwind
//! into the JVM. An answer is `OK\n<records>` or `ERR\t<reason>`.

use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::Path;

use jni::objects::{JClass, JString};
use jni::sys::{jint, jlong, jstring};
use jni::JNIEnv;

use crate::{diff, hash, walk, wire};

fn panic_text(p: Box<dyn std::any::Any + Send>) -> String {
    if let Some(s) = p.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = p.downcast_ref::<String>() {
        s.clone()
    } else {
        "unknown panic".to_string()
    }
}

/// Run `f`; its Ok text is the answer, its Err text or a panic becomes `ERR`.
pub fn guard<F: FnOnce() -> Result<String, String>>(f: F) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(body)) => format!("OK\n{}", body),
        Ok(Err(e)) => format!("ERR\t{}", wire::esc(&e)),
        Err(p) => format!("ERR\tpanic: {}", wire::esc(&panic_text(p))),
    }
}

pub fn do_hash(paths: &str, algos: i32, threads: i32, limit: i64) -> Result<String, String> {
    let list = wire::lines(paths);
    let limit = if limit < 0 { None } else { Some(limit as u64) };
    Ok(hash::encode(&hash::hash_many(&list, algos as u32, threads.max(1) as usize, limit)))
}

pub fn do_walk(root: &str, opts: &str) -> Result<String, String> {
    let o = walk::parse_opts(opts);
    walk::walk(Path::new(&wire::unesc(root)), &o).map(|a| walk::encode(&a))
}

pub fn do_diff(left: &str, right: &str) -> Result<String, String> {
    Ok(diff::encode(&diff::diff(&diff::parse(left), &diff::parse(right))))
}

/// A deliberate panic, so the boundary's catch is provable (JVM test and emulator smoke): `ERR\tpanic: ...`.
pub fn do_panic_probe() -> Result<String, String> {
    panic!("drive-core-rs panic probe");
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
pub extern "system" fn Java_com_diegonmarcos_cloudlib_drivecore_NativeBridge_nativeVersion(mut env: JNIEnv, _c: JClass) -> jstring {
    answer(&mut env, crate::VERSION.to_string())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_drivecore_NativeBridge_nativeHash(
    mut env: JNIEnv,
    _c: JClass,
    paths: JString,
    algos: jint,
    threads: jint,
    limit: jlong,
) -> jstring {
    let text = guard(|| do_hash(&read(&mut env, &paths)?, algos, threads, limit));
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_drivecore_NativeBridge_nativeWalk(mut env: JNIEnv, _c: JClass, root: JString, opts: JString) -> jstring {
    let text = guard(|| {
        let r = read(&mut env, &root)?;
        let o = read(&mut env, &opts)?;
        do_walk(&wire::esc(&r), &o)
    });
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_drivecore_NativeBridge_nativeDiff(mut env: JNIEnv, _c: JClass, left: JString, right: JString) -> jstring {
    let text = guard(|| do_diff(&read(&mut env, &left)?, &read(&mut env, &right)?));
    answer(&mut env, text)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_drivecore_NativeBridge_nativePanicProbe(mut env: JNIEnv, _c: JClass) -> jstring {
    let text = guard(do_panic_probe);
    answer(&mut env, text)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::testutil::{tmpdir, write};

    #[test]
    fn a_panic_becomes_an_err_answer() {
        let a = guard(do_panic_probe);
        assert!(a.starts_with("ERR\tpanic: drive-core-rs panic probe"), "{a}");
        assert_eq!(guard(|| Err("no such folder".into())), "ERR\tno such folder");
        assert_eq!(guard(|| Ok("x\n".into())), "OK\nx\n");
    }

    #[test]
    fn the_three_engines_answer_through_the_wire() {
        let d = tmpdir("jni");
        write(&d.join("a.txt"), b"abc");
        let p = d.join("a.txt").to_string_lossy().to_string();
        let h = guard(|| do_hash(&wire::esc(&p), 3, 4, -1));
        assert!(h.starts_with("OK\nH\t"), "{h}");
        assert!(h.contains("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"));
        let w = guard(|| do_walk(&d.to_string_lossy(), "top_files=5\nlist=1\nloose=LOOSE\n"));
        assert!(w.starts_with("OK\nS\t1\t0\t3\t1\nP\t\n"), "{w}");
        assert!(w.contains("X\ttxt\t1\t3\n"));
        let w = guard(|| do_walk("/nonexistent-drive-core-rs", ""));
        assert!(w.starts_with("ERR\t"), "{w}");
        let df = guard(|| do_diff("a\t1\t1\n", "a\t2\t1\nb\t1\t1\n"));
        assert_eq!(df, "OK\nM\ta\nA\tb\n");
        let _ = std::fs::remove_dir_all(&d);
    }
}
