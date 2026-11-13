//! drive-core-rs (#1729): the CPU-heavy engines of Cloud Drive, in Rust, behind a thin JNI layer.
//!
//!   hash  streaming BLAKE3 + SHA-256 of many files, bounded worker threads
//!   walk  parallel directory walk: sizes, top-N folders and files, per-extension totals, optional file listing, in ONE batch
//!   diff  file-tree delta (path + size + mtime + optional hash)
//!
//! Kotlin (libs:drive-core-rs) stays the API surface and the fallback: it calls these through [jni_api], which turns every panic
//! and every error into an `ERR` answer, never a crash. Wire format: UTF-8 text, one record per line, tab-separated fields, with
//! `\\`, tab and newline escaped in every field ([wire]).

pub mod diff;
pub mod hash;
pub mod jni_api;
pub mod walk;
pub mod wire;

#[cfg(test)]
mod testutil;

/// The crate version, answered to Kotlin so the emulator log can say which core ran.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
