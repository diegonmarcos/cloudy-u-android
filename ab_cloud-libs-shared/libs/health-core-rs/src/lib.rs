//! #1955 C A1: the JNI layer of health-core. [api] is the pure string API (cargo-tested on the host); [jni_api] binds it to
//! com.diegonmarcos.cloudlib.healthcore.HealthNative. No check logic lives here or in Kotlin: it is all in the `health-core` crate.
pub mod api;
pub mod jni_api;
