//! The two Android calls the server crate cannot make itself, installed into it
//! at startup: (1) read the SuperApp's About / Logcat / Diagnose through its
//! exported, CONSTELLATION_DATA signature-permission ContentProvider, and
//! (2) hand a served page to cloud-browser. Both are JNI on the activity Tauri
//! gives us; the server threads that call them attach themselves per call.
//!
//! Nothing here names a package, an authority or a port: they come from
//! build.json::serving through c3_webserver::serving.

use jni::objects::{GlobalRef, JObject, JString, JValue};
use jni::{JNIEnv, JavaVM};
use std::sync::OnceLock;

struct Handle {
    vm: JavaVM,
    activity: GlobalRef,
}

static HANDLE: OnceLock<Handle> = OnceLock::new();
/// The keep-alive service as a Context, while it runs: the SSH door's start/stop signal must work at boot, when no activity exists.
static SERVICE: std::sync::Mutex<Option<Handle>> = std::sync::Mutex::new(None);

/// KeepAliveService.nativeStart: remember the service Context (None on nativeStop) and install the SSH door's sender.
pub fn note_service(env: &mut JNIEnv, service: Option<&JObject>) {
    let h = service.and_then(|svc| {
        let vm = env.get_java_vm().ok()?;
        let activity = env.new_global_ref(svc).ok()?;
        Some(Handle { vm, activity })
    });
    if let Ok(mut g) = SERVICE.lock() {
        *g = h;
    }
    c3_webserver::door::set_sender(Box::new(send_door));
}

/// Keep the VM and the activity, and wire the server crate's two hooks to them.
pub fn install(window: &tauri::WebviewWindow) {
    let _ = window.with_webview(|webview| {
        webview.jni_handle().exec(|env, activity, _webview| {
            let made = (|| -> jni::errors::Result<()> {
                let vm = env.get_java_vm()?;
                let activity = env.new_global_ref(activity)?;
                let _ = HANDLE.set(Handle { vm, activity });
                Ok(())
            })();
            match made {
                Ok(()) => {
                    c3_webserver::diag::set_source(Box::new(read_bridge));
                    // #1490 the mesh listener's two reads: an app's log export (any fleet authority the server crate names)
                    // and the fleet bearer token the SuperApp mints.
                    c3_webserver::meshlogs::set_log_source(Box::new(call_provider));
                    c3_webserver::meshlogs::set_token_source(Box::new(read_token));
                    c3_webserver::diag::set_opener(Box::new(open_in_browser));
                    // Pages > Start app: launch a terminal app whose own my-webserver serves its page.
                    c3_webserver::served::set_launcher(Box::new(launch_app));
                    // #1677 the ADB Shell tab: the SuperApp's launcher intent carrying its page extra (build.json::adb_shell).
                    c3_webserver::served::set_extra_launcher(Box::new(launch_app_with));
                    // #1630 the Configs strip's native tail (Agents, Perms, Logcat, X-Ray App, About): one tab of the shared FleetConfigsActivity.
                    c3_webserver::served::set_configs_opener(Box::new(open_configs));
                    // #1502 the keep-alive service and the boot receiver follow the Server / Keep alive switches.
                    c3_webserver::power::set_sync_hook(Box::new(sync_keep_alive));
                    // The SSH door the keep-alive supervisor keeps up (door.rs): the signal to the nix-on-droid app.
                    c3_webserver::door::set_sender(Box::new(send_door));
                }
                Err(e) => {
                    let _ = env.exception_clear();
                    eprintln!("cloud-c3-webserver: bridge not installed: {}", e);
                }
            }
        });
    });
}

/// Run `f` on this thread attached to the VM; a pending Java exception becomes the Err text.
fn with_env<T>(f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    let h = HANDLE.get().ok_or_else(|| "the Android bridge is not installed yet (the window is still opening)".to_string())?;
    run_on(h, f)
}

fn run_on<T>(h: &Handle, f: impl FnOnce(&mut JNIEnv, &JObject) -> jni::errors::Result<T>) -> Result<T, String> {
    let mut env = h.vm.attach_current_thread().map_err(|e| e.to_string())?;
    let out = f(&mut env, h.activity.as_obj());
    if let Some(msg) = take_exception(&mut env) {
        return Err(msg);
    }
    out.map_err(|e| e.to_string())
}

fn take_exception(env: &mut JNIEnv) -> Option<String> {
    if !env.exception_check().unwrap_or(false) {
        return None;
    }
    let ex = env.exception_occurred().ok()?;
    let _ = env.exception_clear();
    let text = env
        .call_method(&ex, "toString", "()Ljava/lang/String;", &[])
        .ok()
        .and_then(|v| v.l().ok())
        .and_then(|o| env.get_string(&JString::from(o)).ok().map(String::from));
    Some(text.unwrap_or_else(|| "a Java exception".to_string()))
}

fn uri<'a>(env: &mut JNIEnv<'a>, s: &str) -> jni::errors::Result<JObject<'a>> {
    let js = env.new_string(s)?;
    env.call_static_method("android/net/Uri", "parse", "(Ljava/lang/String;)Landroid/net/Uri;", &[JValue::Object(&js)])?.l()
}

fn bundle_string(env: &mut JNIEnv, bundle: &JObject, key: &str) -> jni::errors::Result<String> {
    let k = env.new_string(key)?;
    let v = env.call_method(bundle, "getString", "(Ljava/lang/String;)Ljava/lang/String;", &[JValue::Object(&k)])?.l()?;
    if v.is_null() {
        return Ok(String::new());
    }
    Ok(String::from(env.get_string(&JString::from(v))?))
}

/// ContentResolver.call(content://<authority>, method, arg) -> Bundle {ok, mime, payload, error}.
fn call_provider(authority: &str, method: &str, arg: &str) -> Result<(String, String), String> {
    let reply = with_env(|env, activity| {
        let cr = env.call_method(activity, "getContentResolver", "()Landroid/content/ContentResolver;", &[])?.l()?;
        let u = uri(env, &format!("content://{}", authority))?;
        let m = env.new_string(method)?;
        let a = env.new_string(arg)?;
        let null = JObject::null();
        let bundle = env
            .call_method(
                &cr,
                "call",
                "(Landroid/net/Uri;Ljava/lang/String;Ljava/lang/String;Landroid/os/Bundle;)Landroid/os/Bundle;",
                &[JValue::Object(&u), JValue::Object(&m), JValue::Object(&a), JValue::Object(&null)],
            )?
            .l()?;
        if bundle.is_null() {
            return Ok(None);
        }
        let ok_key = env.new_string("ok")?;
        let ok = env.call_method(&bundle, "getBoolean", "(Ljava/lang/String;)Z", &[JValue::Object(&ok_key)])?.z()?;
        Ok(Some((ok, bundle_string(env, &bundle, "mime")?, bundle_string(env, &bundle, "payload")?, bundle_string(env, &bundle, "error")?)))
    })
    .map_err(|e| friendly(&e, authority))?;
    match reply {
        None => Err(format!("{} answered nothing: is the app installed and up to date?", authority)),
        Some((true, mime, payload, _)) => Ok((mime, payload)),
        Some((false, _, _, error)) => Err(if error.is_empty() { format!("{} refused the call", authority) } else { error }),
    }
}

fn read_bridge(method: &str, arg: &str) -> Result<(String, String), String> {
    let authority = c3_webserver::serving::get().bridge_authority.clone();
    call_provider(&authority, method, arg)
}

/// The fleet bearer token: `content://<superapp>.fleet/token`, first row, first column (FleetToken's own handshake).
/// None when the SuperApp is missing or refuses; the mesh listener then answers 503, never open.
fn read_token() -> Option<String> {
    let sv = c3_webserver::serving::get();
    let suffix = c3_webserver::apis::build()["mesh"]["token_authority_suffix"].as_str().unwrap_or("fleet").to_string();
    let target = format!("content://{}.{}/token", sv.bridge_package, suffix);
    with_env(|env, activity| {
        let cr = env.call_method(activity, "getContentResolver", "()Landroid/content/ContentResolver;", &[])?.l()?;
        let u = uri(env, &target)?;
        let null = JObject::null();
        let cursor = env
            .call_method(
                &cr,
                "query",
                "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;",
                &[JValue::Object(&u), JValue::Object(&null), JValue::Object(&null), JValue::Object(&null), JValue::Object(&null)],
            )?
            .l()?;
        if cursor.is_null() {
            return Ok(None);
        }
        let first = env.call_method(&cursor, "moveToFirst", "()Z", &[])?.z()?;
        let token = if first {
            let v = env.call_method(&cursor, "getString", "(I)Ljava/lang/String;", &[JValue::Int(0)])?.l()?;
            if v.is_null() {
                None
            } else {
                Some(String::from(env.get_string(&JString::from(v))?))
            }
        } else {
            None
        };
        env.call_method(&cursor, "close", "()V", &[])?;
        Ok(token)
    })
    .ok()
    .flatten()
}

fn friendly(e: &str, authority: &str) -> String {
    if e.contains("SecurityException") {
        format!("not allowed to read {}: this app and the SuperApp must be signed with the same key", authority)
    } else if e.contains("Unknown URI") || e.contains("IllegalArgumentException") {
        format!("{} is not installed (the SuperApp with the diagnostics bridge)", authority)
    } else {
        e.to_string()
    }
}

/// VIEW <url> aimed at cloud-browser's package and nobody else's: a private name handed to the system would be
/// resolved on the public internet by whichever browser took it.
fn open_in_browser(url: &str) -> Result<(), String> {
    let package = c3_webserver::serving::get().browser_package.clone();
    with_env(|env, activity| {
        let action = env.new_string("android.intent.action.VIEW")?;
        let u = uri(env, url)?;
        let intent = env.new_object("android/content/Intent", "(Ljava/lang/String;Landroid/net/Uri;)V", &[JValue::Object(&action), JValue::Object(&u)])?;
        let pkg = env.new_string(&package)?;
        env.call_method(&intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&pkg)])?;
        env.call_method(&intent, "addFlags", "(I)Landroid/content/Intent;", &[JValue::Int(0x1000_0000)])?; // FLAG_ACTIVITY_NEW_TASK
        env.call_method(activity, "startActivity", "(Landroid/content/Intent;)V", &[JValue::Object(&intent)])?;
        Ok(())
    })
    .map_err(|e| if e.contains("ActivityNotFoundException") { format!("{} is not installed", package) } else { e })
}

/// MAIN / LAUNCHER aimed at one package and nobody else's: wakes a closed terminal app so its own my-webserver answers again.
fn launch_app(package: &str) -> Result<(), String> {
    let package = package.to_string();
    with_env(|env, activity| {
        let action = env.new_string("android.intent.action.MAIN")?;
        let intent = env.new_object("android/content/Intent", "(Ljava/lang/String;)V", &[JValue::Object(&action)])?;
        let cat = env.new_string("android.intent.category.LAUNCHER")?;
        env.call_method(&intent, "addCategory", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&cat)])?;
        let pkg = env.new_string(&package)?;
        env.call_method(&intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&pkg)])?;
        env.call_method(&intent, "addFlags", "(I)Landroid/content/Intent;", &[JValue::Int(0x1000_0000)])?; // FLAG_ACTIVITY_NEW_TASK
        env.call_method(activity, "startActivity", "(Landroid/content/Intent;)V", &[JValue::Object(&intent)])?;
        Ok(())
    })
    .map_err(|e| if e.contains("ActivityNotFoundException") { format!("{} is not installed", package) } else { e })
}

/// #1677 MAIN / LAUNCHER aimed at one package with one String extra: the SuperApp opens the page its `shortcut_action` extra names
/// (onCreate on a cold start, onNewIntent when it already runs), here its ADB Shell page. Not installed = a clear message.
fn launch_app_with(package: &str, key: &str, value: &str) -> Result<(), String> {
    let (package, key, value) = (package.to_string(), key.to_string(), value.to_string());
    with_env(|env, activity| {
        let action = env.new_string("android.intent.action.MAIN")?;
        let intent = env.new_object("android/content/Intent", "(Ljava/lang/String;)V", &[JValue::Object(&action)])?;
        let cat = env.new_string("android.intent.category.LAUNCHER")?;
        env.call_method(&intent, "addCategory", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&cat)])?;
        let pkg = env.new_string(&package)?;
        env.call_method(&intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&pkg)])?;
        let k = env.new_string(&key)?;
        let v = env.new_string(&value)?;
        env.call_method(&intent, "putExtra", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&k), JValue::Object(&v)])?;
        env.call_method(&intent, "addFlags", "(I)Landroid/content/Intent;", &[JValue::Int(0x1000_0000)])?; // FLAG_ACTIVITY_NEW_TASK
        env.call_method(activity, "startActivity", "(Landroid/content/Intent;)V", &[JValue::Object(&intent)])?;
        Ok(())
    })
    .map_err(|e| if e.contains("ActivityNotFoundException") { format!("{} is not installed", package) } else { e })
}

/// #1630 the shared native Configs screen (libs:ui-nav-bars FleetConfigsActivity, not exported) at one tab: the implicit
/// com.diegonmarcos.fleetconfigs.OPEN action addressed to THIS app's own package, the tab id in the fleet_configs_page extra.
fn open_configs(page: &str) -> Result<(), String> {
    let page = page.to_string();
    with_env(|env, activity| {
        let action = env.new_string("com.diegonmarcos.fleetconfigs.OPEN")?;
        let intent = env.new_object("android/content/Intent", "(Ljava/lang/String;)V", &[JValue::Object(&action)])?;
        let own = env.call_method(activity, "getPackageName", "()Ljava/lang/String;", &[])?.l()?;
        env.call_method(&intent, "setPackage", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&own)])?;
        let key = env.new_string("fleet_configs_page")?;
        let val = env.new_string(&page)?;
        env.call_method(&intent, "putExtra", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&key), JValue::Object(&val)])?;
        let dkey = env.new_string("fleet_configs_dark")?;
        env.call_method(&intent, "putExtra", "(Ljava/lang/String;Z)Landroid/content/Intent;", &[JValue::Object(&dkey), JValue::Bool(1)])?;
        env.call_method(activity, "startActivity", "(Landroid/content/Intent;)V", &[JValue::Object(&intent)])?;
        Ok(())
    })
}

/// #1502 MainActivity.syncKeepAlive(): start the foreground service and enable the boot receiver only when Server and Keep alive
/// are both ON, stop the service and disable the receiver otherwise.
fn sync_keep_alive() {
    if let Err(e) = with_env(|env, activity| env.call_method(activity, "syncKeepAlive", "()V", &[]).map(|_| ())) {
        eprintln!("cloud-c3-webserver: keep-alive sync failed: {}", e);
    }
}

/// The SSH door's start / stop signal (door.rs): an explicit foreground-service start of the nix-on-droid app's MeshSshdService,
/// guarded there by the fleet's signature permission. Package, class and action names come from build.json::ssh_doors (resolved
/// from that app's own declaration). The activity is the Context when the window is open; the keep-alive service is, at boot.
fn send_door(cmd: c3_webserver::door::Cmd) -> Result<(), String> {
    let c = c3_webserver::door::supervised().control;
    let (action, mesh_only) = match cmd {
        c3_webserver::door::Cmd::Start(m) => (c.action_start.clone(), Some(m)),
        c3_webserver::door::Cmd::Stop => (c.action_stop.clone(), None),
    };
    let call = |env: &mut JNIEnv, ctx: &JObject| -> jni::errors::Result<()> {
        let intent = env.new_object("android/content/Intent", "()V", &[])?;
        let pkg = env.new_string(&c.package)?;
        let cls = env.new_string(&c.service)?;
        env.call_method(&intent, "setClassName", "(Ljava/lang/String;Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&pkg), JValue::Object(&cls)])?;
        let act = env.new_string(&action)?;
        env.call_method(&intent, "setAction", "(Ljava/lang/String;)Landroid/content/Intent;", &[JValue::Object(&act)])?;
        if let Some(m) = mesh_only {
            // The Mesh-only flag travels with every start; the hosting app resolves the mesh addresses itself and fails closed.
            let key = env.new_string(&c.extra_mesh_only)?;
            env.call_method(&intent, "putExtra", "(Ljava/lang/String;Z)Landroid/content/Intent;", &[JValue::Object(&key), JValue::Bool(m as jni::sys::jboolean)])?;
        }
        env.call_method(ctx, "startForegroundService", "(Landroid/content/Intent;)Landroid/content/ComponentName;", &[JValue::Object(&intent)])?;
        Ok(())
    };
    let r = match HANDLE.get() {
        Some(h) => run_on(h, call),
        None => {
            let g = SERVICE.lock().map_err(|_| "poisoned".to_string())?;
            let h = g.as_ref().ok_or_else(|| "no Android context yet (neither the window nor the keep-alive service is up)".to_string())?;
            run_on(h, call)
        }
    };
    r.map_err(|e| if e.contains("SecurityException") { format!("{} refused the call: both apps must carry the fleet key and its CONSTELLATION_DATA permission", c.package) } else { e })
}
