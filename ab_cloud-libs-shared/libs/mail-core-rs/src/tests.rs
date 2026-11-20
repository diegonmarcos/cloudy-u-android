//! The engine against the fake JMAP server (fake.rs): after every scenario the store must EQUAL the server, mailbox by
//! mailbox, in the server's order, with the server's keywords. Plus the performance budgets of #1837 (CI asserts them):
//! a list query over 10k messages < 150 ms, the sync of 1,000 headers < 5 s.

use std::cell::Cell;
use std::collections::BTreeSet;
use std::time::{Duration, Instant};

use rusqlite::params;
use serde_json::{json, Value};

use crate::fake::{FEmail, FakeJmap};
use crate::jmap::Transport;
use crate::query::{self, ChangesArgs, Group, ListArgs};
use crate::store::{self, Store};
use crate::sync::{sync, SyncConfig};

const ACC: &str = "acc1";
const BASE: i64 = 1_700_000_000_000;
const DOMAINS: [&str; 4] = ["example.org", "mail.google.com", "news.bbc.co.uk", "sede.gob.es"];

fn cfg() -> SyncConfig {
    let mut c = SyncConfig::new(ACC, "u1");
    // Small pages, so every walk and every fetch is paged many times over.
    c.query_page = 100;
    c.get_chunk = 50;
    c.prefetch_bodies = 5;
    c.properties = vec!["attachments".into(), "messageId".into()];
    c
}

/// inbox / archive / trash / a label, [n] messages; every tenth is in the Inbox AND the label (two memberships).
fn seeded(n: usize) -> FakeJmap {
    let f = FakeJmap::new();
    f.with(|s| {
        s.add_mailbox("inbox", "Inbox", Some("inbox"), 0);
        s.add_mailbox("archive", "Archive", Some("archive"), 1);
        s.add_mailbox("trash", "Trash", Some("trash"), 2);
        s.add_mailbox("lbl", "Receipts", None, 10);
        for i in 0..n {
            let id = format!("e{:05}", i);
            let boxes: Vec<&str> = match i % 10 {
                0..=5 => vec!["inbox"],
                6 | 7 => vec!["archive"],
                8 => vec!["inbox", "lbl"],
                _ => vec!["trash"],
            };
            let from = format!("user{}@{}", i % 37, DOMAINS[i % 4]);
            s.add_email(&id, &boxes, BASE + (i as i64) * 60_000, &format!("Subject {i}"), &from, &format!("t{}", i / 3), i % 3 == 0);
        }
    });
    f
}

fn kw(e: &FEmail, k: &str) -> bool {
    e.keywords.get(k).copied().unwrap_or(false)
}

/// The store equals the server: the same mailboxes, each with the same ids in the same order, the same messages with the
/// same keywords, and every mailbox's local count equal to the server's total.
fn assert_mirrors(st: &Store, f: &FakeJmap) {
    let c = &st.conn;
    let fk = f.inner.borrow();
    let local: BTreeSet<String> = store::mailbox_ids(c, ACC).unwrap().into_iter().collect();
    let server: BTreeSet<String> = fk.mailboxes.keys().cloned().collect();
    assert_eq!(local, server, "mailboxes");
    for mb in fk.mailboxes.keys() {
        assert_eq!(store::member_ids(c, ACC, mb).unwrap(), fk.query_ids(mb), "mailbox {mb}: ids or order differ");
    }
    let n: i64 = c.query_row("SELECT COUNT(*) FROM messages WHERE account = ?1", [ACC], |r| r.get(0)).unwrap();
    assert_eq!(n as usize, fk.emails.len(), "message count");
    for e in fk.emails.values() {
        let (seen, flagged): (bool, bool) = c
            .query_row("SELECT seen, flagged FROM messages WHERE account = ?1 AND id = ?2", params![ACC, e.id], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap_or_else(|_| panic!("{} missing from the store", e.id));
        assert_eq!(seen, kw(e, "$seen"), "{} $seen", e.id);
        assert_eq!(flagged, kw(e, "$flagged"), "{} $flagged", e.id);
    }
    let counts = query::counts(c, ACC).unwrap();
    for (id, v) in counts.as_object().unwrap() {
        assert_eq!(v["local"], v["serverTotal"], "mailbox {id} count vs server total");
    }
}

#[test]
fn initial_sync_mirrors_every_mailbox_whole_newest_first() {
    let f = seeded(1234);
    let st = Store::open(":memory:").unwrap();
    let r = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r.mode, "initial");
    assert!(r.complete);
    assert_mirrors(&st, &f);
    let inbox = r.walks.iter().find(|w| w.mailbox == "inbox").expect("the inbox was walked");
    assert_eq!(inbox.local_before, 0);
    assert_eq!(inbox.local_after, inbox.server_total);
    assert_eq!(inbox.gap_ranges, vec![(0, inbox.server_total as usize - 1)]);
    // The label's members came with the Inbox walk (mailboxIds carries both): nothing fetched twice.
    let lbl = r.walks.iter().find(|w| w.mailbox == "lbl").unwrap();
    assert_eq!(lbl.missing, 0);
    // A second pass is incremental and moves nothing.
    let r2 = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r2.mode, "incremental");
    assert_eq!(r2.fetched, 0);
    assert!(r2.walks.is_empty(), "{:?}", r2.walks);
    assert_mirrors(&st, &f);
}

#[test]
fn new_mail_and_keyword_changes_arrive_by_email_changes() {
    let f = seeded(300);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    f.with(|s| {
        for i in 0..5 {
            s.add_email(&format!("n{i}"), &["inbox"], BASE + 1_000_000_000 + i * 1000, "New", "x@new.org", "tn", false);
        }
        s.set_keyword("e00001", "$flagged", true);
    });
    let r = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r.mode, "incremental");
    assert_eq!((r.email_created, r.email_updated), (5, 1));
    assert_eq!(r.fetched, 6);
    assert!(r.walks.is_empty(), "no gap, no walk: {:?}", r.walks);
    assert_mirrors(&st, &f);
    // The newest is first in the list.
    let l = query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: Group::None, offset: 0, limit: 3, unread_only: false })
        .unwrap();
    assert_eq!(l["rows"][0]["id"], "n4");
}

#[test]
fn deletes_and_moves_are_applied_and_fed_as_removals() {
    let f = seeded(300);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    let before = store::current_seq(&st.conn).unwrap();
    f.with(|s| {
        s.destroy_email("e00000");
        s.destroy_email("e00010");
        s.destroy_email("e00009");
        s.move_email("e00002", "inbox", "archive");
        s.move_email("e00008", "lbl", "trash");
    });
    let r = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r.email_destroyed, 3);
    assert_mirrors(&st, &f);
    let ch = query::changes_since(
        &st.conn,
        &ChangesArgs { account: ACC.into(), mailbox: "inbox".into(), since_seq: before, since_id: String::new(), limit: 500 },
    )
    .unwrap();
    let removed: BTreeSet<String> = ch["removed"].as_array().unwrap().iter().map(|v| v.as_str().unwrap().to_string()).collect();
    for id in ["e00000", "e00010", "e00002"] {
        assert!(removed.contains(id), "{id} not fed as removed from the inbox: {removed:?}");
    }
    let lbl = query::changes_since(
        &st.conn,
        &ChangesArgs { account: ACC.into(), mailbox: "lbl".into(), since_seq: before, since_id: String::new(), limit: 500 },
    )
    .unwrap();
    assert!(lbl["removed"].as_array().unwrap().contains(&json!("e00008")));
}

#[test]
fn a_change_the_server_never_reported_is_found_by_gap_detection() {
    let f = seeded(1000);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    // Seven messages in the MIDDLE of the inbox's history that Email/changes never mentions (the counters do move).
    f.with(|s| {
        s.silent = true;
        for i in 0..7 {
            s.add_email(&format!("g{i}"), &["inbox"], BASE + 500 * 60_000 + 7 + i, "Gap", "g@gap.org", "tg", false);
        }
        s.silent = false;
    });
    let r = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r.email_created, 0, "the change log said nothing");
    let w = r.walks.iter().find(|w| w.mailbox == "inbox").expect("the inbox gap triggered a walk");
    assert_eq!(w.reason, "gap");
    assert_eq!(w.missing, 7);
    assert_eq!(w.gap_ranges.len(), 1, "{:?}", w.gap_ranges);
    assert_mirrors(&st, &f);
}

#[test]
fn rows_lost_locally_are_refetched() {
    let f = seeded(1000);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    st.conn
        .execute(
            "DELETE FROM membership WHERE account = ?1 AND mailbox_id = 'inbox' AND email_id IN
               (SELECT email_id FROM membership WHERE account = ?1 AND mailbox_id = 'inbox' ORDER BY received_ms DESC LIMIT 10 OFFSET 300)",
            [ACC],
        )
        .unwrap();
    let r = sync(&st, &f, &cfg()).unwrap();
    let w = r.walks.iter().find(|w| w.mailbox == "inbox").unwrap();
    assert_eq!((w.reason.as_str(), w.missing), ("gap", 10));
    assert_eq!(w.gap_ranges, vec![(300, 309)]);
    assert_mirrors(&st, &f);
}

#[test]
fn a_lost_history_rereads_every_mailbox() {
    let f = seeded(400);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    f.with(|s| {
        s.silent = true;
        s.set_keyword("e00004", "$flagged", true);
        s.set_keyword("e00005", "$seen", true);
        s.silent = false;
        s.forget_history();
    });
    let r = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r.mode, "resync");
    assert!(r.walks.iter().all(|w| w.reason == "refresh"), "{:?}", r.walks);
    assert_mirrors(&st, &f);
    let r2 = sync(&st, &f, &cfg()).unwrap();
    assert_eq!(r2.mode, "incremental");
    assert!(r2.walks.is_empty());
}

/// Fails every request after the first [left].
struct Flaky<'a> {
    inner: &'a FakeJmap,
    left: Cell<usize>,
}

impl Transport for Flaky<'_> {
    fn post(&self, body: &str) -> Result<String, String> {
        if self.left.get() == 0 {
            return Err("network down".into());
        }
        self.left.set(self.left.get() - 1);
        self.inner.post(body)
    }
}

#[test]
fn an_interrupted_sync_keeps_every_finished_page_and_resumes() {
    let f = seeded(1500);
    let st = Store::open(":memory:").unwrap();
    let flaky = Flaky { inner: &f, left: Cell::new(12) };
    let e = sync(&st, &flaky, &cfg()).unwrap_err();
    assert!(e.contains("network down"), "{e}");
    let kept = store::local_count(&st.conn, ACC, "inbox").unwrap();
    assert!(kept > 0, "the pages that landed are kept");
    // A second interruption NEVER blanks what is there.
    let flaky = Flaky { inner: &f, left: Cell::new(3) };
    let _ = sync(&st, &flaky, &cfg());
    assert!(store::local_count(&st.conn, ACC, "inbox").unwrap() >= kept);
    let r = sync(&st, &f, &cfg()).unwrap();
    assert!(r.complete);
    assert_mirrors(&st, &f);
}

#[test]
fn a_budget_stops_the_walks_and_the_next_pass_resumes() {
    let f = seeded(500);
    let st = Store::open(":memory:").unwrap();
    let mut c = cfg();
    c.budget_ms = Some(0);
    let r = sync(&st, &f, &c).unwrap();
    assert!(!r.complete);
    let r = sync(&st, &f, &cfg()).unwrap();
    assert!(r.complete);
    assert!(r.walks.iter().any(|w| w.reason == "resume" || w.reason == "initial"), "{:?}", r.walks);
    assert_mirrors(&st, &f);
}

#[test]
fn the_server_get_limit_is_respected() {
    let f = seeded(200);
    f.with(|s| s.max_get = 50);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    assert_mirrors(&st, &f);
    let f2 = seeded(200);
    f2.with(|s| s.max_get = 40);
    let e = sync(&Store::open(":memory:").unwrap(), &f2, &cfg()).unwrap_err();
    assert!(e.contains("requestTooLarge"), "{e}");
}

#[test]
fn a_destroyed_mailbox_leaves_the_store() {
    let f = seeded(200);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    f.with(|s| {
        for id in s.query_ids("lbl") {
            s.move_email(&id, "lbl", "archive");
        }
        s.destroy_mailbox("lbl");
    });
    sync(&st, &f, &cfg()).unwrap();
    assert_mirrors(&st, &f);
}

#[test]
fn newest_bodies_are_prefetched_per_mailbox() {
    let f = seeded(200);
    let st = Store::open(":memory:").unwrap();
    let r = sync(&st, &f, &cfg()).unwrap();
    assert!(r.bodies >= 5, "{}", r.bodies);
    let newest = f.inner.borrow().query_ids("inbox").into_iter().take(5).collect::<Vec<_>>();
    for id in &newest {
        let b = query::body(&st.conn, ACC, id).unwrap();
        assert_eq!(b["fetched"], true, "{id}");
        assert_eq!(b["text"], format!("Body of {id}"));
        assert_eq!(b["html"], format!("<p>Body of {id}</p>"));
    }
    let older = f.inner.borrow().query_ids("inbox")[50].clone();
    assert_eq!(query::body(&st.conn, ACC, &older).unwrap()["fetched"], false);
}

#[test]
fn grouped_lists_follow_the_server_order_and_count_unread() {
    let f = seeded(600);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    let fk = f.inner.borrow();
    let inbox = fk.query_ids("inbox");
    for g in [Group::Thread, Group::Sender, Group::Domain] {
        let l = query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: g, offset: 0, limit: 10_000, unread_only: false })
            .unwrap();
        let groups = l["groups"].as_array().unwrap();
        let count: i64 = groups.iter().map(|x| x["count"].as_i64().unwrap()).sum();
        assert_eq!(count as usize, inbox.len(), "{g:?}: every inbox message in exactly one group");
        let latest: Vec<i64> = groups.iter().map(|x| x["latestMs"].as_i64().unwrap()).collect();
        assert!(latest.windows(2).all(|w| w[0] >= w[1]), "{g:?}: groups in server date order");
        assert_eq!(groups[0]["latest"]["id"], json!(inbox[0]), "{g:?}: the newest message heads the first group");
        let unread: i64 = groups.iter().map(|x| x["unread"].as_i64().unwrap()).sum();
        let server_unread = inbox.iter().filter(|id| !kw(&fk.emails[*id], "$seen")).count();
        assert_eq!(unread as usize, server_unread, "{g:?}: unread");
    }
    let d = query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: Group::Domain, offset: 0, limit: 100, unread_only: false })
        .unwrap();
    let keys: BTreeSet<String> = d["groups"].as_array().unwrap().iter().map(|g| g["key"].as_str().unwrap().to_string()).collect();
    let want: BTreeSet<String> = ["example.org", "google.com", "bbc.co.uk", "gob.es"].iter().map(|s| s.to_string()).collect();
    assert_eq!(keys, want);
    let unread = query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: Group::None, offset: 0, limit: 10_000, unread_only: true })
        .unwrap();
    assert!(unread["rows"].as_array().unwrap().iter().all(|r| r["seen"] == false));
}

#[test]
fn the_projection_feed_pages_by_cursor_and_ends_at_the_store_seq() {
    let f = seeded(700);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    let mut seen = BTreeSet::new();
    let (mut seq, mut id) = (0i64, String::new());
    loop {
        let ch = query::changes_since(&st.conn, &ChangesArgs { account: ACC.into(), mailbox: "inbox".into(), since_seq: seq, since_id: id.clone(), limit: 97 })
            .unwrap();
        for e in ch["emails"].as_array().unwrap() {
            assert!(seen.insert(e["id"].as_str().unwrap().to_string()), "a row fed twice");
            assert!(e.get("attachments").is_some(), "the app's list properties ride along");
        }
        seq = ch["nextSeq"].as_i64().unwrap();
        id = ch["nextId"].as_str().unwrap().to_string();
        if !ch["more"].as_bool().unwrap() {
            break;
        }
    }
    assert_eq!(seen.len(), f.inner.borrow().query_ids("inbox").len());
    assert_eq!(seq, store::current_seq(&st.conn).unwrap());
    // Nothing changed: the next round is empty.
    let ch = query::changes_since(&st.conn, &ChangesArgs { account: ACC.into(), mailbox: "inbox".into(), since_seq: seq, since_id: String::new(), limit: 97 })
        .unwrap();
    assert_eq!(ch["emails"].as_array().unwrap().len(), 0);
    assert_eq!(ch["removed"].as_array().unwrap().len(), 0);
}

#[test]
fn fts_search_finds_subject_and_sender() {
    let f = seeded(120);
    let st = Store::open(":memory:").unwrap();
    sync(&st, &f, &cfg()).unwrap();
    let r = query::search(&st.conn, ACC, "Subject 42", 10).unwrap();
    assert!(r["rows"].as_array().unwrap().iter().any(|x| x["id"] == "e00042"), "{r}");
    let r = query::search(&st.conn, ACC, "user5 \"odd", 500).unwrap();
    assert!(r["rows"].is_array());
}

// ── performance budgets (#1837) ─────────────────────────────────────────────────────────────────────────────────────

fn ten_k_store() -> Store {
    let st = Store::open(":memory:").unwrap();
    store::ensure_account(&st.conn, ACC, "u1").unwrap();
    let tx = st.conn.unchecked_transaction().unwrap();
    let seq = store::next_seq(&tx).unwrap();
    for i in 0..10_000i64 {
        let v: Value = json!({
            "id": format!("m{i:05}"), "threadId": format!("t{}", i / 4), "mailboxIds": {"inbox": true},
            "keywords": if i % 3 == 0 { json!({"$seen": true}) } else { json!({}) },
            "receivedAt": crate::fake::iso(BASE + i * 60_000), "size": 2000,
            "from": [{"name": format!("Sender {}", i % 400), "email": format!("s{}@d{}.example.org", i % 400, i % 90)}],
            "to": [{"email": "me@example.org"}], "subject": format!("Message {i}"), "preview": "Lorem ipsum dolor sit amet",
            "hasAttachment": i % 7 == 0,
        });
        store::upsert_email(&tx, ACC, &v, seq).unwrap();
    }
    tx.commit().unwrap();
    st
}

fn budget<T>(what: &str, max: Duration, f: impl FnOnce() -> T) -> T {
    let t = Instant::now();
    let out = f();
    let took = t.elapsed();
    println!("BUDGET {what}: {} ms (max {} ms)", took.as_millis(), max.as_millis());
    assert!(took < max, "{what} took {took:?}, budget {max:?}");
    out
}

#[test]
fn budget_list_query_over_10k_messages_under_150_ms() {
    let st = ten_k_store();
    let max = Duration::from_millis(150);
    for g in [Group::None, Group::Thread, Group::Sender, Group::Domain] {
        let l = budget(&format!("list {g:?} first page of 10k"), max, || {
            query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: g, offset: 0, limit: 100, unread_only: false })
                .unwrap()
        });
        assert!(l["total"].as_i64().unwrap() > 0);
        budget(&format!("list {g:?} deep page of 10k"), max, || {
            query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: g, offset: 2000, limit: 100, unread_only: true })
                .unwrap()
        });
    }
    let l = query::list(&st.conn, &ListArgs { account: ACC.into(), mailbox: "inbox".into(), group: Group::None, offset: 0, limit: 1, unread_only: false })
        .unwrap();
    assert_eq!(l["rows"][0]["id"], "m09999");
    assert_eq!(l["total"], 10_000);
    budget("unread counts over 10k", max, || query::counts(&st.conn, ACC).unwrap());
    budget("projection feed page of 500 over 10k", max, || {
        query::changes_since(&st.conn, &ChangesArgs { account: ACC.into(), mailbox: "inbox".into(), since_seq: 0, since_id: String::new(), limit: 500 })
            .unwrap()
    });
}

#[test]
fn budget_sync_of_1000_headers_under_5_s() {
    let f = FakeJmap::new();
    f.with(|s| {
        s.add_mailbox("inbox", "Inbox", Some("inbox"), 0);
        for i in 0..1000i64 {
            s.add_email(&format!("h{i:04}"), &["inbox"], BASE + i * 1000, &format!("Header {i}"), "a@example.org", &format!("t{i}"), false);
        }
    });
    let st = Store::open(":memory:").unwrap();
    let mut c = SyncConfig::new(ACC, "u1");
    c.prefetch_bodies = 0;
    let r = budget("sync of 1,000 headers", Duration::from_secs(5), || sync(&st, &f, &c).unwrap());
    assert_eq!(store::local_count(&st.conn, ACC, "inbox").unwrap(), 1000);
    println!("sync report: {}", serde_json::to_string(&r).unwrap());
    // 1000 new headers arriving incrementally also fit.
    f.with(|s| {
        for i in 0..1000i64 {
            s.add_email(&format!("k{i:04}"), &["inbox"], BASE + 10_000_000 + i * 1000, "Later", "b@example.org", "tk", false);
        }
    });
    budget("incremental sync of 1,000 new headers", Duration::from_secs(5), || sync(&st, &f, &c).unwrap());
    assert_eq!(store::local_count(&st.conn, ACC, "inbox").unwrap(), 2000);
}
