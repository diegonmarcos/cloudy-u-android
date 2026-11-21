# libs:browser-core-rs

Cloud Browser's native core (#1841): one Rust crate (`browser-core-rs`, `libbrowser_core.so`), JNI, built by CI per ABI, with a Kotlin twin
of every job as the fallback when the library is not loaded. Kotlin stays the Compose UI and the WebView glue.

| job | Rust | what it owns |
|---|---|---|
| tabs / session | `store.rs` `SessionStore` | per-tab snapshots: url, back/forward stack, scroll, title, favicon, the WebView state file; bundled SQLite |
| stores | `store.rs` rows | one `(ns, key) -> json` table for history (dedupe + cap), bookmarks, settings, site permissions, downloads |
| search | `search.rs` | the History filter (input order) and the ranked address-bar search |
| omnibox | `omnibox.rs` | URL or search, scheme of a bare host, the search URL, and the start-up decision (open url, kept tab, restore, grid + "Restore previous tabs" after an unclean end) |
| content blocking | `filter.rs` | host rules, `/segment` path rules, exceptions, `$option` tails ignored, list merge (the list-update step) |
| reader mode | `reader.rs` | title and readable paragraphs of an HTML page |
| downloads | `store.rs` `download_next` | the state machine queued, running, paused, done, failed, cancelled |

## Rendering stays on Android WebView

The page is drawn by the system WebView (Chromium). Servo is not production-ready for the general web on Android, so this core owns the
logic around the page and never the page. Nothing here renders, loads or parses a live site's scripts.

## Contract

- every answer is `OK\n<json>` or `ERR\t<reason>`; a panic is caught at the JNI boundary (`panic = "unwind"`) and Kotlin falls back for that call;
- `cargo test` asserts the performance budgets (snapshots, a 50k-row search, 100k filter checks, 10k omnibox decisions, a 100 KB reader page);
- `BrowserCoreParityTest` holds the Kotlin twins to the same cases as `src/tests.rs`;
- Cargo.lock is generated and committed by the `browser-core-cargo-lock` workflow with its licence inventory entries.

## Not yet moved

History, bookmarks, settings, site permissions and the download queue still live in the app's SharedPreferences stores, which the fleet
config migration contract declares; the core's rows table and `download_next` are in place for them, the migration of each store is a
separate, tested step.
