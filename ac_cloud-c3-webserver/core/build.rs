// build.json::runtime is THE declaration of the ports the server binds and the
// root it serves; it reaches the app as compile-time env vars, so lib.rs
// carries no number and no path of its own. (#1682: no tauri-build any more; the core is a plain cdylib.) (build.json::serving, the name ->
// port map, is compiled into the server crate itself and validated there.)
fn main() {
    println!("cargo:rerun-if-changed=../build.json");
    let text = std::fs::read_to_string("../build.json").expect("read ../build.json");
    let json: serde_json::Value = serde_json::from_str(&text).expect("parse ../build.json");
    let port = json["runtime"]["port"].as_u64().expect("build.json::runtime.port");
    let diag_port = json["runtime"]["diag"]["port"].as_u64().expect("build.json::runtime.diag.port");
    let root = json["runtime"]["serve_root"].as_str().expect("build.json::runtime.serve_root");
    let app_id = json["android"]["application_id"].as_str().expect("build.json::android.application_id");
    println!("cargo:rustc-env=C3_WEBSERVER_PORT={}", port);
    println!("cargo:rustc-env=C3_WEBSERVER_DIAG_PORT={}", diag_port);
    println!("cargo:rustc-env=C3_WEBSERVER_SERVE_ROOT={}", root);
    println!("cargo:rustc-env=C3_WEBSERVER_APP_ID={}", app_id);
    // #1682 the native app's nav declaration (build.json::ui.sections), handed to the Compose UI by the core at runtime
    // (MainActivity / Core.nativeUiSections) instead of a BuildConfig base64 field (runtime-data D3 forbids a new one).
    let ui = serde_json::to_string(&json["ui"]["sections"]).expect("build.json::ui.sections");
    assert!(json["ui"]["sections"].is_array(), "build.json::ui.sections must be an array");
    println!("cargo:rustc-env=C3_WEBSERVER_UI_SECTIONS={}", ui);
}
