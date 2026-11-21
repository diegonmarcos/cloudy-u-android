//! browser-core-rs (#1841): the hot paths of Cloud Browser, in Rust, behind a thin JNI layer.
//!
//!   store   the session / tab snapshot store: one row per tab (url, title, scroll, favicon, the back/forward stack, the file holding
//!           WebView's own saved state), in one SQLite file (bundled). A snapshot restores a tab whose renderer died, whose process
//!           died, or that was dropped from memory; private tabs are never stored
//!   search  history and bookmark search over a JSON list: the plain substring filter the History page uses (input order kept) and a
//!           ranked mode (host prefix, title prefix, word prefix, substring, recency) for the address bar
//!   filter  content-blocking matching: a host rule set (`||host^`, `host`, `@@||host^` exceptions) answers "block this request?"
//!           with the same semantics as the Kotlin BrowserTrackers (a request to a tracker host or its subdomain, unless the page
//!           is itself on a tracker host, and never the main frame)
//!
//! Kotlin (libs:browser-core-rs, BrowserCore) calls these through [jni_api], which turns every panic and error into an `ERR` answer,
//! never a crash. Answers are `OK\n<json>` or `ERR\t<reason>`.

pub mod filter;
pub mod jni_api;
pub mod search;
pub mod store;

#[cfg(test)]
mod tests;

/// The crate version, answered to Kotlin so the logs say which core ran.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
