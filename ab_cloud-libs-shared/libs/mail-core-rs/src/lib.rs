//! mail-core-rs (#1837, phase 1): the engine of Cloud Mail, in Rust, behind a thin JNI layer.
//!
//!   store   the local mail store: accounts, mailboxes, messages (ids, thread ids, from/to/cc, subject, preview, receivedAt,
//!           keywords, size, mailbox membership), per-mailbox sync state, bodies, an FTS5 index; one SQLite file (bundled)
//!   jmap    a minimal JMAP client (RFC 8620 / 8621) over a [jmap::Transport]: on Android the transport is the app's own
//!           HTTP stack (mesh first, public + fleet bearer, the gateway cookie, the phone's trust store), in tests a fake server
//!   sync    the sync engine: every mailbox and every message, newest first and paged; then Mailbox/changes + Email/changes;
//!           gap detection (server total vs local count, the missing ids re-queried); deletes and moves; body prefetch
//!   reader  (phase 2) the raw message -> mail-parser (every charset, QP, base64, nested multipart, cid images) -> ammonia (whitelist)
//!           -> a Gmail-like document; the raw bytes and the parsed document are stored, so an open is one indexed read, offline
//!   query   the list queries the UI draws: server date order, thread / sender / domain grouping, unread counts, the
//!           projection feed (changes since a cursor) and full-text search, as JSON
//!
//! Kotlin (libs:mail-core-rs, MailCore) calls these through [jni_api], which turns every panic and every error into an `ERR`
//! answer, never a crash. Answers are `OK\n<json>` or `ERR\t<reason>`.

pub mod compose;
pub mod domain;
pub mod imap;
pub mod jmap;
pub mod jni_api;
pub mod query;
pub mod reader;
pub mod store;
pub mod sync;

#[cfg(test)]
mod fake;
#[cfg(test)]
mod tests;

/// The crate version, answered to Kotlin so the logs say which core ran.
pub const VERSION: &str = env!("CARGO_PKG_VERSION");
