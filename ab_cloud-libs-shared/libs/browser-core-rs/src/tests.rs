//! The core's suite (cargo test, run by the ship workflow before the APK build): the snapshot store, the search, the filter, and the
//! performance budgets #1841 asserts in CI. Budgets are generous multiples of the measured time: they catch a regression to a quadratic
//! walk or a missing index, not scheduler noise.

use crate::filter::FilterSet;
use crate::jni_api::{do_search, do_store, filter_blocks, filter_free, filter_new};
use crate::search::search;
use crate::store::SessionStore;
use serde_json::{json, Value};
use std::time::{Duration, Instant};

fn snap(key: &str, url: &str) -> Value {
    json!({"key":key,"url":url,"title":format!("t {key}"),"scrollX":3,"scrollY":1200,"favicon":"/f/a.png",
           "history":["https://a.example/","https://a.example/b"],"index":1,"stateFile":"/s/a.bin","updatedAt":100})
}

fn budget<T>(what: &str, max: Duration, f: impl FnOnce() -> T) -> T {
    let t = Instant::now();
    let r = f();
    let took = t.elapsed();
    assert!(took < max, "{what} took {took:?}, budget {max:?}");
    r
}

#[test]
fn a_snapshot_round_trips_with_its_back_stack_and_scroll() {
    let s = SessionStore::open(":memory:").unwrap();
    s.put(&snap("k1", "https://a.example/b")).unwrap();
    let got = s.get("k1").unwrap().unwrap();
    assert_eq!(got["url"], "https://a.example/b");
    assert_eq!(got["scrollY"], 1200);
    assert_eq!(got["index"], 1);
    assert_eq!(got["history"], json!(["https://a.example/", "https://a.example/b"]));
    assert_eq!(got["stateFile"], "/s/a.bin");
    assert!(s.get("nope").unwrap().is_none());
}

#[test]
fn a_second_put_replaces_and_list_is_newest_first() {
    let s = SessionStore::open(":memory:").unwrap();
    let mut a = snap("a", "https://a.example/");
    a["updatedAt"] = json!(10);
    let mut b = snap("b", "https://b.example/");
    b["updatedAt"] = json!(20);
    s.put(&a).unwrap();
    s.put(&b).unwrap();
    a["url"] = json!("https://a.example/new");
    a["updatedAt"] = json!(30);
    s.put(&a).unwrap();
    assert_eq!(s.count().unwrap(), 2);
    let keys: Vec<String> = s.list().unwrap().iter().map(|v| v["key"].as_str().unwrap().to_string()).collect();
    assert_eq!(keys, vec!["a", "b"]);
    assert_eq!(s.get("a").unwrap().unwrap()["url"], "https://a.example/new");
}

#[test]
fn a_private_tab_and_a_bad_snapshot_are_refused() {
    let s = SessionStore::open(":memory:").unwrap();
    let mut p = snap("p", "https://p.example/");
    p["private"] = json!(true);
    assert!(s.put(&p).unwrap_err().contains("private"));
    assert!(s.put(&json!({"url":"https://x/"})).is_err());
    assert!(s.put(&json!({"key":"k"})).is_err());
    assert_eq!(s.count().unwrap(), 0);
}

#[test]
fn prune_and_delete_drop_closed_tabs() {
    let s = SessionStore::open(":memory:").unwrap();
    for k in ["a", "b", "c"] {
        s.put(&snap(k, "https://x.example/")).unwrap();
    }
    assert!(s.delete("a").unwrap());
    assert!(!s.delete("a").unwrap());
    assert_eq!(s.prune(&["b".to_string()]).unwrap(), 1);
    assert_eq!(s.count().unwrap(), 1);
}

#[test]
fn the_store_survives_a_reopen_on_disk() {
    let dir = std::env::temp_dir().join(format!("browser-core-{}", std::process::id()));
    let db = dir.join("sub").join("session.db").to_string_lossy().to_string();
    {
        let s = SessionStore::open(&db).unwrap();
        s.put(&snap("k", "https://a.example/")).unwrap();
    }
    let s = SessionStore::open(&db).unwrap();
    assert_eq!(s.get("k").unwrap().unwrap()["url"], "https://a.example/");
    let _ = std::fs::remove_dir_all(&dir);
}

fn entries() -> Value {
    json!([
        {"t":"Rust Programming Language","u":"https://www.rust-lang.org/","ts":5},
        {"t":"Kotlin docs","u":"https://kotlinlang.org/docs","ts":9},
        {"t":"Cloud rust notes","u":"https://notes.example/rust","ts":7},
        {"t":"Other","u":"https://example.com/","ts":1}
    ])
}

#[test]
fn the_plain_filter_keeps_the_input_order() {
    assert_eq!(search(&entries(), "rust", 50, false), vec![0, 2]);
    assert_eq!(search(&entries(), "  RUST ", 50, false), vec![0, 2]);
    assert_eq!(search(&entries(), "", 50, false), vec![0, 1, 2, 3]);
    assert_eq!(search(&entries(), "rust", 1, false), vec![0]);
    assert!(search(&entries(), "zzz", 50, false).is_empty());
}

#[test]
fn the_ranked_search_puts_host_and_title_prefixes_first() {
    // "rust": host prefix (rust-lang.org) beats a word of a title and a url path
    assert_eq!(search(&entries(), "rust", 50, true), vec![0, 2]);
    assert_eq!(search(&entries(), "kot docs", 50, true), vec![1]);
    // every word must match
    assert!(search(&entries(), "rust kotlin", 50, true).is_empty());
    let out = do_search(&entries().to_string(), "rust", 50, true).unwrap();
    assert_eq!(out, "[0,2]");
}

#[test]
fn the_filter_matches_hosts_and_subdomains_with_exceptions() {
    let f = FilterSet::parse("! comment\n||tracker.example^\nads.example.org\n@@||ok.tracker.example^\n# note\ncom\n\n");
    assert_eq!(f.len(), 2);
    assert!(f.is_tracker("tracker.example"));
    assert!(f.is_tracker("a.b.Tracker.Example"));
    assert!(f.is_tracker("ads.example.org"));
    assert!(!f.is_tracker("example.org"));
    assert!(!f.is_tracker("ok.tracker.example"), "an exception wins");
    assert!(!f.is_tracker("com"), "a bare TLD never matches");
    assert!(f.should_block("x.tracker.example", "news.example", false));
    assert!(!f.should_block("x.tracker.example", "news.example", true), "never the main frame");
    assert!(!f.should_block("x.tracker.example", "tracker.example", false), "the tracker's own site is not blocked");
    assert!(!f.should_block("", "news.example", false));
}

#[test]
fn a_filter_handle_answers_and_frees() {
    let h = filter_new("||t.example^");
    assert!(filter_blocks(h, "a.t.example", "p.example", false));
    filter_free(h);
    assert!(!filter_blocks(h, "a.t.example", "p.example", false));
}

// ── performance budgets (#1841) ─────────────────────────────────────────────────────────────────────────────────────

#[test]
fn budget_1000_snapshots_written_and_read_under_1500_ms() {
    let s = SessionStore::open(":memory:").unwrap();
    budget("1000 puts + 1000 gets + list", Duration::from_millis(1500), || {
        for i in 0..1000 {
            s.put(&snap(&format!("k{i}"), &format!("https://site{i}.example/path"))).unwrap();
        }
        for i in 0..1000 {
            assert!(s.get(&format!("k{i}")).unwrap().is_some());
        }
        assert_eq!(s.list().unwrap().len(), 1000);
    });
}

#[test]
fn budget_search_over_50k_history_rows_under_500_ms() {
    let rows: Vec<Value> = (0..50_000).map(|i| json!({"t":format!("Page number {i} about topic {}", i % 97),"u":format!("https://host{}.example/p/{i}", i % 311),"ts":i})).collect();
    let v = Value::Array(rows);
    let n = budget("plain search over 50k", Duration::from_millis(500), || search(&v, "topic 42", 50, false).len());
    assert!(n > 0);
    let n = budget("ranked search over 50k", Duration::from_millis(800), || search(&v, "host12 page", 20, true).len());
    assert!(n > 0);
}

#[test]
fn budget_100k_filter_checks_under_600_ms() {
    let rules: String = (0..5000).map(|i| format!("||trk{i}.example.net^\n")).collect();
    let f = FilterSet::parse(&rules);
    let mut hits = 0;
    budget("100k should_block over 5000 rules", Duration::from_millis(600), || {
        for i in 0..100_000 {
            if f.should_block(&format!("cdn.trk{}.example.net", i % 10_000), "news.example.com", false) {
                hits += 1;
            }
        }
    });
    assert_eq!(hits, 50_000);
}

#[test]
fn budget_the_store_call_boundary_answers_under_500_ms_per_1000() {
    let db = ":memory:";
    budget("1000 boundary calls", Duration::from_millis(500), || {
        for _ in 0..1000 {
            assert!(do_store(db, "count", "").is_ok());
        }
    });
}

// ── the widened core (#1841): omnibox, start decision, reader, rows, download states, filter lists ───────────────────────

use crate::omnibox;
use crate::reader;
use crate::store::download_next;

#[test]
fn the_omnibox_decides_url_or_search_like_the_kotlin_rules() {
    let tpl = "https://q.example/?q={q}";
    for (typed, want) in [
        ("qwant.com", "https://qwant.com"),
        ("localhost:8000", "http://localhost:8000"),
        ("192.168.1.5/x", "http://192.168.1.5/x"),
        ("172.20.0.1", "http://172.20.0.1"),
        ("172.40.0.1", "https://q.example/?q=172.40.0.1"),
        ("nas.local", "http://nas.local"),
        ("http://a.b/c d", "http://a.b/c d"),
        ("2.5", "https://q.example/?q=2.5"),
        ("rust lang", "https://q.example/?q=rust+lang"),
        ("a.b.co.uk/p", "https://a.b.co.uk/p"),
        ("caf\u{e9} & co", "https://q.example/?q=caf%C3%A9+%26+co"),
        ("  ", ""),
        ("hello", "https://q.example/?q=hello"),
        ("x.1a", "https://q.example/?q=x.1a"),
    ] {
        assert_eq!(omnibox::resolve(typed, tpl), want, "{typed}");
    }
    assert!(omnibox::is_url_like("a.io") && !omnibox::is_url_like("a b.io"));
}

#[test]
fn the_start_decision_never_reopens_the_last_page_after_an_unclean_end() {
    // (unclean, has_last, restore_setting, open_url, kept)
    assert_eq!(omnibox::start_decision(false, true, true, false, false), ("restore", false));
    assert_eq!(omnibox::start_decision(true, true, true, false, false), ("grid", true));
    assert_eq!(omnibox::start_decision(true, false, true, false, false), ("grid", false));
    assert_eq!(omnibox::start_decision(false, true, false, false, false), ("grid", false));
    assert_eq!(omnibox::start_decision(true, true, true, true, false), ("open_url", false));
    assert_eq!(omnibox::start_decision(true, true, true, false, true), ("kept", false));
}

#[test]
fn the_reader_keeps_the_article_and_drops_the_chrome() {
    let html = "<html><head><title>My &amp; Story</title><style>p{color:red}</style></head><body><nav><p>Menu menu menu menu menu menu</p></nav>\
        <h1>Headline</h1><p>The first paragraph is long enough to keep, with an &lt;entity&gt;.</p><script>var x = 'a long script string that must go';</script>\
        <p>short</p><ul><li>A list item that is also long enough to be kept here</li></ul><footer><p>Footer footer footer footer footer footer</p></footer></body></html>";
    let (title, paras) = reader::extract(html);
    assert_eq!(title, "My & Story");
    assert_eq!(paras, vec!["Headline", "The first paragraph is long enough to keep, with an <entity>.", "A list item that is also long enough to be kept here"]);
}

#[test]
fn rows_keep_history_bookmarks_settings_and_downloads_in_one_table() {
    let s = SessionStore::open(":memory:").unwrap();
    s.row_put("setting", "desktop_mode", "true", 1).unwrap();
    s.row_put("perm", "a.example|camera", "deny", 2).unwrap();
    s.row_put("bookmark", "https://a.example/", "{\"t\":\"A\"}", 3).unwrap();
    assert_eq!(s.row_get("setting", "desktop_mode").unwrap(), Some(("true".to_string(), 1)));
    assert_eq!(s.row_get("setting", "nope").unwrap(), None);
    assert_eq!(s.row_count("perm").unwrap(), 1);
    assert!(s.row_put("", "k", "v", 0).is_err());
    assert!(s.row_delete("bookmark", "https://a.example/").unwrap());
    assert_eq!(s.row_clear("setting").unwrap(), 1);
    for i in 0..30 {
        s.history_visit(&format!("https://h.example/{i}"), "t", 100 + i, 10).unwrap();
    }
    s.history_visit("https://h.example/5", "again", 500, 10).unwrap();
    let rows = s.row_list("history", 100).unwrap();
    assert_eq!(rows.len(), 10, "capped to the newest 10");
    assert_eq!(rows[0].0, "https://h.example/5", "a revisit moves to the top and is not duplicated");
}

#[test]
fn the_download_states_follow_the_machine() {
    assert_eq!(download_next("queued", "start").unwrap(), "running");
    assert_eq!(download_next("running", "pause").unwrap(), "paused");
    assert_eq!(download_next("paused", "resume").unwrap(), "running");
    assert_eq!(download_next("running", "finish").unwrap(), "done");
    assert_eq!(download_next("failed", "retry").unwrap(), "queued");
    assert!(download_next("done", "start").is_err());
    assert!(download_next("queued", "finish").is_err());
}

#[test]
fn filter_lists_merge_and_path_rules_block_with_exceptions() {
    let merged = crate::filter::merge(&["! a\n||t.example^\n\n/ads/banner\n".to_string(), "||t.example^\n@@/ads/banner/ok\n# c\n||x.net^$third-party".to_string()]);
    assert_eq!(merged, "||t.example^\n/ads/banner\n@@/ads/banner/ok\n||x.net^$third-party");
    let f = FilterSet::parse(&merged);
    assert!(f.is_tracker("x.net"), "options are ignored");
    assert!(f.should_block_url("https://cdn.site.example/ads/banner/1.png", "cdn.site.example", "news.example", false));
    assert!(!f.should_block_url("https://cdn.site.example/ads/banner/ok/1.png", "cdn.site.example", "news.example", false));
    assert!(!f.should_block_url("https://cdn.site.example/ads/banner/1.png", "cdn.site.example", "news.example", true));
    assert!(f.should_block_url("https://a.t.example/x", "a.t.example", "news.example", false));
}

#[test]
fn budget_10k_omnibox_decisions_and_a_100kb_reader_page_under_500_ms() {
    budget("10k omnibox resolves", Duration::from_millis(500), || {
        for i in 0..10_000 {
            let _ = omnibox::resolve(&format!("site{i}.example/path"), "https://q.example/?q={q}");
            let _ = omnibox::resolve(&format!("some words {i}"), "https://q.example/?q={q}");
        }
    });
    let page: String = (0..800).map(|i| format!("<div><p>Paragraph number {i} with enough words to be kept by the reader.</p></div>")).collect();
    let (_, p) = budget("reader over ~100 KB", Duration::from_millis(500), || reader::extract(&page));
    assert_eq!(p.len(), 800);
}
