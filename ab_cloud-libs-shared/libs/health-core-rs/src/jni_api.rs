//! The JNI boundary (class com.diegonmarcos.cloudlib.healthcore.HealthNative). Every entry point runs inside [crate::api::guard]:
//! nothing can unwind into the JVM. An answer is `OK\n<json>` or `ERR\t<reason>`; a null answer means the string could not be built.

use crate::api;
use jni::objects::{JClass, JString};
use jni::sys::{jlong, jstring};
use jni::JNIEnv;

fn text(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s).map(|j| j.into()).map_err(|e| format!("bad string argument: {}", e))
}

fn answer(env: &mut JNIEnv, body: String) -> jstring {
    env.new_string(body).map(|s| s.into_raw()).unwrap_or(std::ptr::null_mut())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativeVersion(mut env: JNIEnv, _c: JClass) -> jstring {
    answer(&mut env, api::version())
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativeProbe(mut env: JNIEnv, _c: JClass, url: JString, timeout_ms: jlong) -> jstring {
    let r = api::guard(|| api::probe(&text(&mut env, &url)?, timeout_ms.max(0) as u64));
    answer(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativeAnalyze(mut env: JNIEnv, _c: JClass, report: JString, layers: JString) -> jstring {
    let r = api::guard(|| api::analyze(&text(&mut env, &report)?, &text(&mut env, &layers)?));
    answer(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativeMerge(mut env: JNIEnv, _c: JClass, phone: JString, fleet: JString) -> jstring {
    let r = api::guard(|| api::merge(&text(&mut env, &phone)?, &text(&mut env, &fleet)?));
    answer(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativePlanMesh(mut env: JNIEnv, _c: JClass, decl: JString) -> jstring {
    let r = api::guard(|| api::plan_mesh(&text(&mut env, &decl)?));
    answer(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativeRedact(mut env: JNIEnv, _c: JClass, input: JString) -> jstring {
    let r = api::guard(|| api::redact(&text(&mut env, &input)?));
    answer(&mut env, r)
}

#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudlib_healthcore_HealthNative_nativePanicProbe(mut env: JNIEnv, _c: JClass) -> jstring {
    let r = api::guard(|| -> Result<String, String> { panic!("health-core panic probe") });
    answer(&mut env, r)
}
