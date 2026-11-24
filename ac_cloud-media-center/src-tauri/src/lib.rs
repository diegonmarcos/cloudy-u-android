//! cloud-media-center as a Tauri app: one window on the bundled web/ pages.
//!
//! The pages are static (web/, bundled through tauri.conf.json's
//! frontendDist), so the Rust side starts the runtime and registers
//! tauri-plugin-opener: every link to TIDAL, YouTube, Pinterest, IMDb or a
//! book page is handed to the phone's own apps instead of opening inside the
//! webview.

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .plugin(tauri_plugin_opener::init())
        .run(tauri::generate_context!())
        .expect("cloud-media-center: the Tauri runtime failed to start");
}
