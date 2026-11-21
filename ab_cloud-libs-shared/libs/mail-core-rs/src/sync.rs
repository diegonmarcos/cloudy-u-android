//! The JMAP sync engine: the store mirrors the server, every mailbox and every message.
//!
//! One pass ([sync]):
//!   1. mailboxes   Mailbox/changes from the stored state (Mailbox/get of what changed, destroyed ones removed); no state or
//!                  `cannotCalculateChanges` -> Mailbox/get of all, and a local mailbox the server no longer has is removed
//!   2. messages    Email/changes from the stored Email state, looped while `hasMoreChanges`: created + updated re-read with
//!                  Email/get (so a keyword change and a MOVE land: membership is replaced from `mailboxIds`), destroyed
//!                  deleted. No state yet (first sync): the baseline state is read FIRST and stored, so anything that changes
//!                  while the walks run is caught by the next Email/changes. `cannotCalculateChanges`: a new baseline, and
//!                  every mailbox is marked to be re-read whole
//!   3. walks       a mailbox is walked when it was never completed, when it must be re-read, when the server's total and
//!                  the local count differ (GAP DETECTION), or when its last walk is older than `verifyAfterMs`. A walk is
//!                  Email/query (ids only, the server's order: receivedAt newest first) paged by position; per page the ids
//!                  the store lacks are fetched with Email/get and committed before the next page, so an interrupted walk
//!                  keeps every page it finished and the newest mail lands first. At the end, a local member the server
//!                  did not list is re-read (moved: membership replaced; notFound: deleted)
//!   4. bodies      the newest `prefetchBodies` messages of each mailbox without a fetched body
//!
//! Every write batch is one transaction carrying one `seq` (store.rs), which is what the projection feed reads.

use std::collections::HashSet;
use std::time::Instant;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::jmap::{JResult, Jmap, JmapError, Transport};
use crate::store::{self, err, Store};

fn d_query_page() -> usize {
    500
}
fn d_get_chunk() -> usize {
    200
}
fn d_max_changes() -> usize {
    500
}
fn d_prefetch() -> usize {
    20
}
fn d_raw_max() -> i64 {
    8 * 1024 * 1024
}
fn d_prefetch_raw() -> usize {
    30
}
fn d_body_bytes() -> usize {
    256 * 1024
}
fn d_verify_after() -> i64 {
    6 * 3600 * 1000
}

/// The properties every message is fetched with: what the store's columns need. The app adds its own list properties.
pub const BASE_PROPERTIES: [&str; 15] = [
    "id",
    "blobId",
    "threadId",
    "mailboxIds",
    "keywords",
    "size",
    "receivedAt",
    "from",
    "to",
    "cc",
    "subject",
    "preview",
    "hasAttachment",
    "replyTo",
    "bcc",
];

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncConfig {
    /// The app's local account id (rows are keyed by it).
    pub account: String,
    /// The JMAP accountId on the server.
    pub jmap_account_id: String,
    /// Extra Email properties (the app's list property set), added to [BASE_PROPERTIES].
    #[serde(default)]
    pub properties: Vec<String>,
    #[serde(default = "d_query_page")]
    pub query_page: usize,
    #[serde(default = "d_get_chunk")]
    pub get_chunk: usize,
    #[serde(default = "d_max_changes")]
    pub max_changes: usize,
    #[serde(default = "d_prefetch")]
    pub prefetch_bodies: usize,
    #[serde(default = "d_body_bytes")]
    pub max_body_bytes: usize,
    /// The newest N messages of each mailbox (except Trash and Junk) whose RAW message is downloaded and parsed, so the reader
    /// opens them offline; 0 = none. Messages larger than [raw_max_bytes] are left to the on-demand fetch.
    #[serde(default = "d_prefetch_raw")]
    pub prefetch_raw: usize,
    #[serde(default = "d_raw_max")]
    pub raw_max_bytes: i64,
    /// A mailbox whose counts agree is still re-walked (ids only) when its last walk is older than this.
    #[serde(default = "d_verify_after")]
    pub verify_after_ms: i64,
    /// Stop walking after this long (the rest continues on the next pass); None = no limit.
    #[serde(default)]
    pub budget_ms: Option<u64>,
}

impl SyncConfig {
    pub fn new(account: &str, jmap_account_id: &str) -> Self {
        SyncConfig {
            account: account.into(),
            jmap_account_id: jmap_account_id.into(),
            properties: vec![],
            query_page: d_query_page(),
            get_chunk: d_get_chunk(),
            max_changes: d_max_changes(),
            prefetch_bodies: d_prefetch(),
            max_body_bytes: d_body_bytes(),
            prefetch_raw: d_prefetch_raw(),
            raw_max_bytes: d_raw_max(),
            verify_after_ms: d_verify_after(),
            budget_ms: None,
        }
    }

    pub fn all_properties(&self) -> Vec<String> {
        let mut out: Vec<String> = BASE_PROPERTIES.iter().map(|s| s.to_string()).collect();
        for p in &self.properties {
            if !out.contains(p) {
                out.push(p.clone());
            }
        }
        out
    }
}

#[derive(Debug, Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Walk {
    pub mailbox: String,
    pub reason: String,
    pub server_total: i64,
    pub local_before: i64,
    pub local_after: i64,
    pub missing: usize,
    pub extra: usize,
    /// Position ranges [first, last] of the server's list where the store lacked messages (the gaps that were filled).
    pub gap_ranges: Vec<(usize, usize)>,
    pub complete: bool,
}

#[derive(Debug, Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SyncReport {
    pub account: String,
    /// initial (no Email state yet), incremental, or resync (cannotCalculateChanges).
    pub mode: String,
    pub mailboxes: usize,
    pub mailboxes_changed: usize,
    pub mailboxes_removed: usize,
    pub email_created: usize,
    pub email_updated: usize,
    pub email_destroyed: usize,
    pub fetched: usize,
    pub removed: usize,
    pub walks: Vec<Walk>,
    pub bodies: usize,
    /// Raw messages downloaded and parsed for the reader in this pass.
    pub raw: usize,
    /// Raw downloads that failed in this pass (the first reason is in [raw_error]); a failed download never fails the sync.
    pub raw_failed: usize,
    pub raw_error: String,
    pub requests: usize,
    pub ms: u64,
    pub email_state: String,
    /// False when the budget ran out before every walk finished (the next pass continues).
    pub complete: bool,
    pub seq: i64,
}

struct Ctx<'a, 'b> {
    c: &'a rusqlite::Connection,
    j: &'a Jmap<'b>,
    cfg: &'a SyncConfig,
    props: Vec<String>,
    started: Instant,
}

impl Ctx<'_, '_> {
    fn over_budget(&self) -> bool {
        self.cfg.budget_ms.map(|b| self.started.elapsed().as_millis() as u64 >= b).unwrap_or(false)
    }
    fn acct(&self) -> &str {
        &self.cfg.account
    }
}

fn jerr(e: JmapError) -> String {
    e.to_string()
}

/// Email/get [ids] in chunks and store them; notFound ids are deleted. Answers (stored, deleted).
fn fetch_and_store(x: &Ctx, ids: &[String]) -> Result<(usize, usize), String> {
    let mut stored = 0;
    let mut deleted = 0;
    for chunk in ids.chunks(x.cfg.get_chunk.max(1)) {
        let got = x.j.email_get(chunk, &x.props).map_err(jerr)?;
        let tx = x.c.unchecked_transaction().map_err(err)?;
        let seq = store::next_seq(&tx)?;
        for v in &got.list {
            store::upsert_email(&tx, x.acct(), v, seq)?;
            stored += 1;
        }
        for id in &got.not_found {
            store::delete_email(&tx, x.acct(), id, seq)?;
            deleted += 1;
        }
        tx.commit().map_err(err)?;
    }
    Ok((stored, deleted))
}

fn sync_mailboxes(x: &Ctx, state: Option<&str>, rep: &mut SyncReport) -> Result<(), String> {
    if let Some(mut since) = state.map(str::to_string) {
        let mut ok = true;
        loop {
            match x.j.mailbox_changes(&since, 500) {
                Ok(ch) => {
                    let ids: Vec<String> = ch.created.iter().chain(ch.updated.iter()).cloned().collect();
                    let got = if ids.is_empty() { None } else { Some(x.j.mailbox_get(Some(ids.as_slice())).map_err(jerr)?) };
                    let tx = x.c.unchecked_transaction().map_err(err)?;
                    let seq = store::next_seq(&tx)?;
                    if let Some(g) = &got {
                        for v in &g.list {
                            store::upsert_mailbox(&tx, x.acct(), v)?;
                            rep.mailboxes_changed += 1;
                        }
                        for id in &g.not_found {
                            store::delete_mailbox(&tx, x.acct(), id, seq)?;
                            rep.mailboxes_removed += 1;
                        }
                    }
                    for id in &ch.destroyed {
                        store::delete_mailbox(&tx, x.acct(), id, seq)?;
                        rep.mailboxes_removed += 1;
                    }
                    store::set_mailbox_state(&tx, x.acct(), Some(ch.new_state.as_str()))?;
                    tx.commit().map_err(err)?;
                    since = ch.new_state.clone();
                    if !ch.has_more {
                        break;
                    }
                }
                Err(e) if e.is_kind("cannotCalculateChanges") => {
                    ok = false;
                    break;
                }
                Err(e) => return Err(jerr(e)),
            }
        }
        if ok {
            return Ok(());
        }
    }
    let all = x.j.mailbox_get(None).map_err(jerr)?;
    let tx = x.c.unchecked_transaction().map_err(err)?;
    let seq = store::next_seq(&tx)?;
    let server: HashSet<String> =
        all.list.iter().filter_map(|v| v.get("id").and_then(Value::as_str).map(str::to_string)).collect();
    for v in &all.list {
        store::upsert_mailbox(&tx, x.acct(), v)?;
        rep.mailboxes_changed += 1;
    }
    for id in store::mailbox_ids(&tx, x.acct())? {
        if !server.contains(&id) {
            store::delete_mailbox(&tx, x.acct(), &id, seq)?;
            rep.mailboxes_removed += 1;
        }
    }
    store::set_mailbox_state(&tx, x.acct(), Some(all.state.as_str()))?;
    tx.commit().map_err(err)?;
    Ok(())
}

/// Follow Email/changes from [since]; answers the state it reached.
fn apply_email_changes(x: &Ctx, since: &str, rep: &mut SyncReport) -> JResult<String> {
    let mut state = since.to_string();
    loop {
        let ch = x.j.email_changes(&state, x.cfg.max_changes)?;
        let mut seen = HashSet::new();
        let ids: Vec<String> =
            ch.created.iter().chain(ch.updated.iter()).filter(|id| seen.insert((*id).clone())).cloned().collect();
        rep.email_created += ch.created.len();
        rep.email_updated += ch.updated.len();
        rep.email_destroyed += ch.destroyed.len();
        let (s, d) = fetch_and_store(x, &ids).map_err(JmapError::Protocol)?;
        rep.fetched += s;
        rep.removed += d;
        let wrote: Result<(), String> = (|| {
            let tx = x.c.unchecked_transaction().map_err(err)?;
            let seq = store::next_seq(&tx)?;
            for id in &ch.destroyed {
                store::delete_email(&tx, x.acct(), id, seq)?;
            }
            store::set_email_state(&tx, x.acct(), Some(ch.new_state.as_str()))?;
            tx.commit().map_err(err)
        })();
        wrote.map_err(JmapError::Protocol)?;
        rep.removed += ch.destroyed.len();
        state = ch.new_state;
        if !ch.has_more {
            return Ok(state);
        }
    }
}

/// Walk one mailbox (see the module doc). Answers None when the budget ran out mid-walk.
fn walk_mailbox(x: &Ctx, mb: &store::MailboxRow, reason: &str, refresh: bool) -> Result<Option<Walk>, String> {
    let acct = x.acct();
    let mut w = Walk {
        mailbox: mb.id.clone(),
        reason: reason.to_string(),
        local_before: store::local_count(x.c, acct, &mb.id)?,
        ..Default::default()
    };
    let mut server_ids: Vec<String> = Vec::new();
    let mut position = 0usize;
    let mut total: Option<usize> = None;
    let mut query_state = String::new();
    let mut gap: Option<(usize, usize)> = None;
    loop {
        if x.over_budget() {
            if let Some(g) = gap.take() {
                w.gap_ranges.push(g);
            }
            return Ok(None);
        }
        let q = x.j.email_query(&mb.id, position, x.cfg.query_page.max(1)).map_err(jerr)?;
        if position == 0 {
            query_state = q.query_state.clone();
            total = q.total;
        }
        if q.ids.is_empty() {
            break;
        }
        let mut missing = Vec::new();
        for (i, id) in q.ids.iter().enumerate() {
            let have = !refresh && store::has_membership(x.c, acct, &mb.id, id)?;
            if have {
                if let Some(g) = gap.take() {
                    w.gap_ranges.push(g);
                }
            } else {
                missing.push(id.clone());
                let at = position + i;
                gap = Some(match gap {
                    Some((a, _)) => (a, at),
                    None => (at, at),
                });
            }
        }
        if !missing.is_empty() {
            fetch_and_store(x, &missing)?;
            w.missing += missing.len();
        }
        position += q.ids.len();
        server_ids.extend(q.ids);
        if total.map(|t| position >= t).unwrap_or(false) {
            break;
        }
    }
    if let Some(g) = gap.take() {
        w.gap_ranges.push(g);
    }
    // Local members the server did not list: moved elsewhere, or gone. Re-read them; Email/get decides.
    let listed: HashSet<&String> = server_ids.iter().collect();
    let extras: Vec<String> = store::member_ids(x.c, acct, &mb.id)?.into_iter().filter(|id| !listed.contains(id)).collect();
    if !extras.is_empty() {
        fetch_and_store(x, &extras)?;
        // Still claimed by the store as a member after the re-read (the server's Email/get and Email/query disagree):
        // the query is the list the user sees, so the membership in THIS mailbox ends.
        let tx = x.c.unchecked_transaction().map_err(err)?;
        let seq = store::next_seq(&tx)?;
        for id in &extras {
            if store::has_membership(&tx, acct, &mb.id, id)? {
                let now: Vec<String> = store::memberships_of(&tx, acct, id)?.into_iter().filter(|m| m != &mb.id).collect();
                store::set_membership(&tx, acct, id, &now, 0, seq)?;
            }
        }
        store::delete_orphans(&tx, acct, seq)?;
        tx.commit().map_err(err)?;
        w.extra = extras.len();
    }
    w.server_total = total.map(|t| t as i64).unwrap_or(server_ids.len() as i64);
    w.local_after = store::local_count(x.c, acct, &mb.id)?;
    w.complete = true;
    let st = store::SyncStateRow {
        query_state: Some(query_state),
        server_total: w.server_total,
        local_count: w.local_after,
        complete: true,
        gaps_found: w.missing as i64,
        refresh_pending: false,
        last_walk_ms: None,
    };
    store::put_sync_state(x.c, acct, &mb.id, &st, true, store::now_ms())?;
    Ok(Some(w))
}

fn prefetch_bodies(x: &Ctx) -> Result<usize, String> {
    if x.cfg.prefetch_bodies == 0 {
        return Ok(0);
    }
    let mut n = 0;
    for mb in store::mailboxes(x.c, x.acct())? {
        if x.over_budget() {
            break;
        }
        let ids = store::unfetched_newest(x.c, x.acct(), &mb.id, x.cfg.prefetch_bodies)?;
        for chunk in ids.chunks(x.cfg.get_chunk.clamp(1, 50)) {
            let got = x.j.email_get_bodies(chunk, x.cfg.max_body_bytes).map_err(jerr)?;
            let tx = x.c.unchecked_transaction().map_err(err)?;
            let now = store::now_ms();
            for v in &got.list {
                store::put_body(&tx, x.acct(), v, now)?;
                n += 1;
            }
            tx.commit().map_err(err)?;
        }
    }
    Ok(n)
}

/// The blobId of each message: from the stored Email object, else asked of the server (Email/get, blobId only).
fn blob_ids(x: &Ctx, ids: &[String]) -> Result<Vec<(String, String)>, String> {
    let mut out: Vec<(String, String)> = Vec::new();
    let mut ask: Vec<String> = Vec::new();
    for id in ids {
        match store::blob_id(x.c, x.acct(), id)? {
            Some(b) => out.push((id.clone(), b)),
            None => ask.push(id.clone()),
        }
    }
    for chunk in ask.chunks(x.cfg.get_chunk.clamp(1, 50)) {
        let props = vec!["id".to_string(), "blobId".to_string()];
        let got = x.j.email_get(chunk, &props).map_err(jerr)?;
        for v in &got.list {
            if let (Some(id), Some(b)) = (v.get("id").and_then(Value::as_str), v.get("blobId").and_then(Value::as_str)) {
                out.push((id.to_string(), b.to_string()));
            }
        }
    }
    Ok(out)
}

/// Download one message's raw bytes through the transport, parse them for the reader and store both. A message the parser
/// rejects keeps its raw bytes (the reader answers unavailable and the app falls back to its own path).
fn download_and_store(c: &rusqlite::Connection, account: &str, t: &dyn Transport, id: &str, blob: &str) -> Result<(), String> {
    let raw = t.download(blob)?;
    let parsed = crate::reader::parse(&raw).ok();
    store::put_raw(c, account, id, &raw, parsed.as_ref(), store::now_ms())
}

/// The reader prefetch: the newest `prefetch_raw` messages of each mailbox (not Trash, not Junk) that have no stored raw message
/// are downloaded and parsed, so opening them needs no network. Failures are counted, never raised; three in a row stop the pass.
fn prefetch_raw(x: &Ctx, rep: &mut SyncReport) {
    if x.cfg.prefetch_raw == 0 {
        return;
    }
    let boxes = match store::mailboxes(x.c, x.acct()) {
        Ok(b) => b,
        Err(_) => return,
    };
    let mut failures = 0;
    for mb in boxes {
        if matches!(mb.role.as_deref(), Some("trash") | Some("junk")) {
            continue;
        }
        if x.over_budget() || failures >= 3 {
            break;
        }
        let ids = match store::unraw_newest(x.c, x.acct(), &mb.id, x.cfg.prefetch_raw, x.cfg.raw_max_bytes) {
            Ok(i) => i,
            Err(_) => continue,
        };
        let pairs = match blob_ids(x, &ids) {
            Ok(p) => p,
            Err(e) => {
                failures += 1;
                rep.raw_failed += 1;
                if rep.raw_error.is_empty() {
                    rep.raw_error = e;
                }
                continue;
            }
        };
        for (id, blob) in pairs {
            if x.over_budget() || failures >= 3 {
                break;
            }
            match download_and_store(x.c, x.acct(), x.j.transport, &id, &blob) {
                Ok(()) => {
                    rep.raw += 1;
                    failures = 0;
                }
                Err(e) => {
                    failures += 1;
                    rep.raw_failed += 1;
                    if rep.raw_error.is_empty() {
                        rep.raw_error = e;
                    }
                }
            }
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchArgs {
    pub account: String,
    pub jmap_account_id: String,
    pub ids: Vec<String>,
}

#[derive(Debug, Default, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FetchReport {
    pub fetched: usize,
    pub failed: usize,
    pub error: String,
    pub ms: u64,
}

/// The on-demand fetch: the raw message of each of [FetchArgs::ids] downloaded, parsed and stored (a message opened before the
/// prefetch reached it).
pub fn fetch_raw(st: &Store, t: &dyn Transport, a: &FetchArgs) -> Result<FetchReport, String> {
    let started = Instant::now();
    let cfg = SyncConfig::new(&a.account, &a.jmap_account_id);
    let j = Jmap::new(t, &a.jmap_account_id);
    let x = Ctx { c: &st.conn, j: &j, cfg: &cfg, props: Vec::new(), started };
    let mut rep = FetchReport::default();
    for (id, blob) in blob_ids(&x, &a.ids)? {
        match download_and_store(x.c, x.acct(), t, &id, &blob) {
            Ok(()) => rep.fetched += 1,
            Err(e) => {
                rep.failed += 1;
                if rep.error.is_empty() {
                    rep.error = e;
                }
            }
        }
    }
    rep.ms = started.elapsed().as_millis() as u64;
    Ok(rep)
}

/// One sync pass of [cfg]'s account into [st] over [t]. See the module doc.
pub fn sync(st: &Store, t: &dyn Transport, cfg: &SyncConfig) -> Result<SyncReport, String> {
    let started = Instant::now();
    let j = Jmap::new(t, &cfg.jmap_account_id);
    let x = Ctx { c: &st.conn, j: &j, cfg, props: cfg.all_properties(), started };
    let mut rep = SyncReport { account: cfg.account.clone(), complete: true, ..Default::default() };
    let result = run(&x, &mut rep);
    rep.requests = j.requests.get();
    rep.ms = started.elapsed().as_millis() as u64;
    rep.seq = store::current_seq(x.c).unwrap_or(0);
    match result {
        Ok(()) => {
            store::set_last_sync(x.c, &cfg.account, store::now_ms(), None)?;
            Ok(rep)
        }
        Err(e) => {
            let _ = store::set_last_sync(x.c, &cfg.account, store::now_ms(), Some(e.as_str()));
            Err(e)
        }
    }
}

fn run(x: &Ctx, rep: &mut SyncReport) -> Result<(), String> {
    let acct = store::ensure_account(x.c, x.acct(), &x.cfg.jmap_account_id)?;
    sync_mailboxes(x, acct.mailbox_state.as_deref(), rep)?;
    let state = match acct.email_state.as_deref() {
        None => {
            rep.mode = "initial".into();
            let s = x.j.email_state().map_err(jerr)?;
            store::set_email_state(x.c, x.acct(), Some(s.as_str()))?;
            s
        }
        Some(since) => match apply_email_changes(x, since, rep) {
            Ok(s) => {
                rep.mode = "incremental".into();
                s
            }
            Err(e) if e.is_kind("cannotCalculateChanges") => {
                rep.mode = "resync".into();
                let s = x.j.email_state().map_err(jerr)?;
                store::mark_refresh_all(x.c, x.acct(), store::now_ms())?;
                store::set_email_state(x.c, x.acct(), Some(s.as_str()))?;
                s
            }
            Err(e) => return Err(jerr(e)),
        },
    };
    let now = store::now_ms();
    let boxes = store::mailboxes(x.c, x.acct())?;
    rep.mailboxes = boxes.len();
    for mb in &boxes {
        let local = store::local_count(x.c, x.acct(), &mb.id)?;
        let prev = store::sync_state(x.c, x.acct(), &mb.id)?;
        let reason = match &prev {
            None => Some("initial"),
            Some(p) if p.refresh_pending => Some("refresh"),
            Some(p) if !p.complete => Some("resume"),
            _ if local != mb.total_emails => Some("gap"),
            Some(p) if p.last_walk_ms.map(|w| now - w >= x.cfg.verify_after_ms).unwrap_or(true) => Some("verify"),
            _ => None,
        };
        match reason {
            Some(r) => match walk_mailbox(x, mb, r, prev.as_ref().map(|p| p.refresh_pending).unwrap_or(false))? {
                Some(w) => rep.walks.push(w),
                None => {
                    rep.complete = false;
                    // Progress is kept (every finished page is committed); the walk resumes next pass.
                    let mut p = prev.unwrap_or_default();
                    p.complete = false;
                    p.local_count = store::local_count(x.c, x.acct(), &mb.id)?;
                    p.server_total = mb.total_emails;
                    store::put_sync_state(x.c, x.acct(), &mb.id, &p, false, now)?;
                    break;
                }
            },
            None => {
                let mut p = prev.unwrap_or_default();
                p.local_count = local;
                p.server_total = mb.total_emails;
                store::put_sync_state(x.c, x.acct(), &mb.id, &p, false, now)?;
            }
        }
    }
    {
        let tx = x.c.unchecked_transaction().map_err(err)?;
        let seq = store::next_seq(&tx)?;
        rep.removed += store::delete_orphans(&tx, x.acct(), seq)?;
        tx.commit().map_err(err)?;
    }
    rep.bodies = prefetch_bodies(x)?;
    prefetch_raw(x, rep);
    // The state the store now follows (a walk does not move it).
    rep.email_state = state;
    Ok(())
}
