//! cloud-c3-webserver as a Tauri app: bind the server, then open the one
//! window on it. The window is built here rather than declared in
//! tauri.conf.json because Tauri creates declared windows BEFORE `setup`
//! runs, which would point the webview at a socket that is not listening yet.

#[cfg(target_os = "android")]
mod bridge;

use tauri::{WebviewUrl, WebviewWindowBuilder};

/// Bind the servers (the drive, the diagnostics page and the private DNS). Idempotent: the activity and the
/// keep-alive service both call it, whichever comes first binds. A failed bind is logged, not fatal.
fn start_servers() -> Result<u16, Box<dyn std::error::Error>> {
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

/// #1502 the Server stopped page's way back: the served pages are down while the server is OFF, so this Tauri command (called by the
/// bundled ui-off page) is the control that works then. Either switch may be omitted. Returns the live state as JSON.
#[tauri::command]
fn power_set(server: Option<bool>, keep_alive: Option<bool>) -> Result<String, String> {
    use c3_webserver::power;
    let cur = power::current().sw;
    power::apply(power::Switches { server: server.unwrap_or(cur.server), keep_alive: keep_alive.unwrap_or(cur.keep_alive) })?;
    Ok(power::status_json().to_string())
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

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![power_set])
        .setup(|app| {
            // A failed bind is logged, not fatal: the window still opens and
            // says "not available", which names the problem on the screen
            // instead of closing the app with no word.
            let port = start_servers()?;
            // #1618 The app ALWAYS opens on its shell (five tabs), whether the Server is ON or OFF. The admin surface
            // (/__app__ and /api/power) is bound in every power state (lib.rs start_admin), so with the Server OFF the
            // shell still renders the normal pages and shows Server = stopped with a Start control. The ON/OFF switch
            // only binds or unbinds the HTTP content listeners; it never gates navigation, rendering or any in-app
            // feature, and the window is never sent to the bundled off page (which blocked the whole UI while OFF).
            let url: tauri::Url = format!("http://127.0.0.1:{}/__app__/", port).parse()?;
            let _window = WebviewWindowBuilder::new(app, "main", WebviewUrl::External(url)).build()?;
            #[cfg(target_os = "android")]
            {
                request_storage(&_window);
                bridge::install(&_window);
            }
            Ok(())
        })
        .run(tauri::generate_context!())
        .expect("cloud-c3-webserver: the Tauri runtime failed to start");
}

/// build.json::runtime.serve_root is shared storage: at targetSdk 28 one
/// READ_EXTERNAL_STORAGE grant covers it. Asked on the activity Tauri hands
/// over, from Rust; a phone that already granted it answers without a dialog.
#[cfg(target_os = "android")]
fn request_storage(window: &tauri::WebviewWindow) {
    let _ = window.with_webview(|webview| {
        webview.jni_handle().exec(|env, activity, _webview| {
            let asked = (|| -> jni::errors::Result<()> {
                let perm = env.new_string("android.permission.READ_EXTERNAL_STORAGE")?;
                let perms = env.new_object_array(1, "java/lang/String", &perm)?;
                env.call_method(
                    activity,
                    "requestPermissions",
                    "([Ljava/lang/String;I)V",
                    &[jni::objects::JValue::Object(&perms), jni::objects::JValue::Int(1)],
                )?;
                Ok(())
            })();
            if let Err(e) = asked {
                // A pending Java exception would abort the next JNI call Tauri makes.
                let _ = env.exception_clear();
                eprintln!("cloud-c3-webserver: storage permission request failed: {}", e);
            }
        });
    });
}
