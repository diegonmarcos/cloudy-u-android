//! cloud-media-center as a Tauri app: one window on the bundled web/ pages.
//!
//! The pages are static (web/, bundled through tauri.conf.json's
//! frontendDist), so the Rust side starts the runtime and registers
//! tauri-plugin-opener: every link to TIDAL, YouTube, Pinterest, IMDb or a
//! book page is handed to the phone's own apps instead of opening inside the
//! webview.
//!
//! One command, `installed_packages` (#1765, Games tab): of the package names
//! the page passes, the ones that are installed and launchable on the phone.
//! It reads PackageManager through JNI, so the app stays Rust and web with no
//! Kotlin beyond Tauri's glue. The manifest's <queries> (MAIN/LAUNCHER) gives
//! the visibility Android 11+ requires.

/// The launchable subset of `pkgs`. Never fails: on error, or off Android, the
/// answer is empty (the page then shows no installed marks rather than a wrong one).
#[tauri::command]
fn installed_packages(pkgs: Vec<String>) -> Vec<String> {
    probe(&pkgs).unwrap_or_default()
}

#[cfg(target_os = "android")]
fn probe(pkgs: &[String]) -> Result<Vec<String>, jni::errors::Error> {
    use jni::objects::{JObject, JValue};
    use jni::JavaVM;
    let ctx = ndk_context::android_context();
    let vm = unsafe { JavaVM::from_raw(ctx.vm().cast()) }?;
    let mut env = vm.attach_current_thread()?;
    let context = unsafe { JObject::from_raw(ctx.context().cast()) };
    let pm = env
        .call_method(&context, "getPackageManager", "()Landroid/content/pm/PackageManager;", &[])?
        .l()?;
    let mut found = Vec::new();
    for p in pkgs {
        let name = env.new_string(p)?;
        let intent = env
            .call_method(
                &pm,
                "getLaunchIntentForPackage",
                "(Ljava/lang/String;)Landroid/content/Intent;",
                &[JValue::Object(&name)],
            )?
            .l()?;
        if !intent.is_null() {
            found.push(p.clone());
        }
        env.delete_local_ref(name)?;
        env.delete_local_ref(intent)?;
    }
    Ok(found)
}

#[cfg(not(target_os = "android"))]
fn probe(_pkgs: &[String]) -> Result<Vec<String>, String> {
    Ok(Vec::new())
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .invoke_handler(tauri::generate_handler![installed_packages])
        .run(tauri::generate_context!())
        .expect("cloud-media-center: the Tauri runtime failed to start");
}
