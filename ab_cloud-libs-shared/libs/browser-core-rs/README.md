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

## The list stores (phase 1 of the move: history, bookmarks, Quickmarks, the visit log)

`lists.rs` holds the model (`Visit`, `Bookmark`, Weby's folders and tiles), search and ranking, dedupe, frecency, sort and filter, JSON import
and export, the seed merge, Weby's Bookmarks (all) merge and the migration, as pure `op(json) -> json` functions (`nativeLists` over JNI,
`BrowserCore.lists` in Kotlin, `KtLists` in `Lists.kt` as the twin and the fallback). The stored text keeps its SharedPreferences file, key and
schema (the declared prefs, the fleet-config sync and the scraper still read it); `BrowserListMigration` normalises it once, in place, after
writing a backup file under `files/browser-core/backup/` and keeping the old text under `<key>_pre_core`. `fixtures/lists-corpus.json` is the
ONE corpus `cargo test` (`src/tests.rs`) and `BrowserCoreListsTest` (the twin; the core too where JNI loads) are held to, and its `models`
entry is the field-parity list for `BrowserVisit` / `BrowserBookmark`.

## Not yet moved

Settings, site permissions and the download queue still live in the app's SharedPreferences stores, which the fleet config migration
contract declares; the core's rows table and `download_next` are in place for them, the migration of each store is a separate, tested step
(phase 2).
