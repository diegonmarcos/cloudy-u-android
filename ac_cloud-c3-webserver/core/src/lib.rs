//! cloud-c3-webserver's native core (#1682 P1). The Rust server crate (HTTP drive, diagnostics page, private DNS, the
//! admin API under /__app__, the embedded sshd, the roots of #1677) is started in-process, exactly as the Tauri host
//! started it; the UI is now the native Kotlin / Compose app, which reads the same loopback admin API the web shell
//! read (`http://127.0.0.1:<runtime.port>/__app__/api/...`). This file is the whole binding: five JNI entry points.
//!
//!   MainActivity.nativeAttach -> port     bind the servers (idempotent), install the Android bridge on the activity
//!   MainActivity.nativeDetach             the activity is going away: forget it (the service Context stays)
//!   KeepAliveService.nativeStart -> ok    the keep-alive foreground service brings the servers up (boot, update)
//!   KeepAliveService.nativeStop           Stop on the notification: Server OFF, every listener released
//!   Core.nativeUiSections -> json         build.json::ui.sections, the Compose shell's nav declaration
//!
//! Nothing here binds a new socket or changes an address: the servers bind exactly what build.json::runtime / serving
//! declare, as before.

#[cfg(target_os = "android")]
mod bridge;

/// Bind the servers (the drive, the diagnostics page and the private DNS). Idempotent: the activity and the keep-alive
/// service both call it, whichever comes first binds. A failed bind is logged, not fatal (the UI then names the problem).
pub fn start_servers() -> Result<u16, Box<dyn std::error::Error>> {
    let port: u16 = env!("C3_WEBSERVER_PORT").parse()?;
    let cfg = c3_webserver::Config {
        drive_port: port,
        diag_port: env!("C3_WEBSERVER_DIAG_PORT").parse()?,
        root: env!("C3_WEBSERVER_SERVE_ROOT").to_string(),
    };
    if let Err(e) = c3_webserver::start(cfg) {
        eprintln!("c3-webserver did not start: {}", e);
    }
    // The jump-host helpers (fleet-nix, fleet-termux, fleet-adb, fleet-ipc) for a shell of this app: rendered from build.json::ssh_doors.
    let bin = format!("/data/data/{}/files/bin", env!("C3_WEBSERVER_APP_ID"));
    if let Err(e) = c3_webserver::jump::install(std::path::Path::new(&bin)) {
        eprintln!("c3-webserver: jump helpers not installed: {}", e);
    }
    Ok(port)
}

/// build.json::ui.sections as JSON text: the native app's nav declaration (libs:bottomnav NavDecl.parse), served by the core.
pub fn ui_sections() -> &'static str {
    env!("C3_WEBSERVER_UI_SECTIONS")
}

/// Core.nativeUiSections: the nav declaration for the Compose shell (no server needed, it is the core's own copy of build.json::ui).
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_Core_nativeUiSections(
    env: jni::JNIEnv,
    _this: jni::objects::JObject,
) -> jni::sys::jstring {
    match env.new_string(ui_sections()) {
        Ok(s) => s.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// The port the admin API answers on (build.json::runtime.port), for the Compose UI.
pub fn admin_port() -> u16 {
    env!("C3_WEBSERVER_PORT").parse().unwrap_or(0)
}

/// MainActivity.onCreate (off the main thread): bind the servers, then wire the server crate's Android hooks (the
/// SuperApp bridge reads, cloud-browser, Start app, the ADB Shell link, the native Configs pages, keep-alive sync, the SSH
/// door) to this activity. Returns the admin port, or 0 when the port could not be read (the UI then shows the reason).
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_MainActivity_nativeAttach(
    mut env: jni::JNIEnv,
    _this: jni::objects::JObject,
    activity: jni::objects::JObject,
) -> jni::sys::jint {
    let port = start_servers().unwrap_or(0);
    bridge::install(&mut env, &activity);
    port as jni::sys::jint
}

/// MainActivity.onDestroy: drop the activity reference so a destroyed activity is never called (the service Context, when
/// the keep-alive service runs, still serves the SSH door).
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_MainActivity_nativeDetach(_env: jni::JNIEnv, _this: jni::objects::JObject) {
    bridge::forget_activity();
}

/// The keep-alive foreground service (KeepAliveService.kt, started at boot, after an update, by the browser and by
/// the activity) calls this once it is in the foreground: the servers then live as long as the service, not the UI.
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_KeepAliveService_nativeStart(
    mut env: jni::JNIEnv,
    service: jni::objects::JObject,
) -> jni::sys::jboolean {
    let ok = start_servers().is_ok();
    // The SSH door (door.rs): the one supervised app brings its external access up together. With Keep alive ON this asks the
    // nix-on-droid app to start its sshd (also at boot, which is when this service starts); the supervisor runs once here,
    // it never polls. A refusal or a missing nix app is logged, not fatal.
    bridge::note_service(&mut env, Some(&service));
    if let Err(e) = c3_webserver::door::sync(false, false) {
        eprintln!("c3-webserver: ssh door not started: {}", e);
    }
    ok as jni::sys::jboolean
}

/// #1502 Stop on the foreground notification: Server OFF, every listener released (the service then ends itself).
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "system" fn Java_com_diegonmarcos_cloudwebserver_KeepAliveService_nativeStop(
    mut env: jni::JNIEnv,
    service: jni::objects::JObject,
) {
    use c3_webserver::power;
    // power::apply also runs the SSH door's supervisor pass (Server OFF stops the door this app started), so the service Context
    // must be known first and is dropped only afterwards.
    bridge::note_service(&mut env, Some(&service));
    let _ = power::apply(power::Switches { server: false, keep_alive: power::current().sw.keep_alive });
    bridge::note_service(&mut env, None);
}

#[cfg(test)]
mod tests {
    /// The admin port is build.json::runtime.port, compiled in: the Compose UI and the server agree on it.
    #[test]
    fn admin_port_is_the_declared_runtime_port() {
        let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../build.json")).unwrap();
        let json: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(super::admin_port() as u64, json["runtime"]["port"].as_u64().unwrap());
        assert!(super::admin_port() > 0);
    }

    /// The nav declaration the core hands the Compose UI is build.json::ui.sections, byte for byte in meaning.
    #[test]
    fn ui_sections_are_the_declared_ones() {
        let text = std::fs::read_to_string(concat!(env!("CARGO_MANIFEST_DIR"), "/../build.json")).unwrap();
        let json: serde_json::Value = serde_json::from_str(&text).unwrap();
        let got: serde_json::Value = serde_json::from_str(super::ui_sections()).unwrap();
        assert_eq!(got, json["ui"]["sections"]);
        assert!(got.as_array().map(|a| !a.is_empty()).unwrap_or(false));
    }
}
