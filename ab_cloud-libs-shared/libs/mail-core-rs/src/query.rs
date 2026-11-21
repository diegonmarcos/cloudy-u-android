//! The list queries, as JSON. Order is the server's: receivedAt newest first (id descending breaks a tie, the same tie-break
//! every read uses, so two reads of one mailbox cannot disagree).
//!
//!   list           one mailbox, flat or grouped by thread / sender / domain, paged by offset, optionally unread only
//!   counts         per mailbox: local count, local unread, the server's totals, sync state (complete, gaps found)
//!   changes_since  the projection feed: the Email objects of a mailbox written after a (seq, id) cursor, paged, and the
//!                  memberships that ended after it (tombstones); how the app keeps its own list rows equal to the store
//!   search         FTS5 over subject, sender and preview
//!   body           a prefetched body

use rusqlite::{params, Connection, OptionalExtension, Row};
use serde::Deserialize;
use serde_json::{json, Value};

use crate::store::err;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize, Default)]
#[serde(rename_all = "lowercase")]
pub enum Group {
    #[default]
    None,
    Thread,
    Sender,
    Domain,
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ListArgs {
    pub account: String,
    pub mailbox: String,
    #[serde(default)]
    pub group: Group,
    #[serde(default)]
    pub offset: usize,
    #[serde(default = "d_limit")]
    pub limit: usize,
    #[serde(default)]
    pub unread_only: bool,
}

fn d_limit() -> usize {
    100
}

const ROW_COLS: &str = "m.id, m.thread_id, m.from_name, m.from_email, m.from_domain, m.subject, m.preview, m.received_at, \
                        b.received_ms, m.seen, m.flagged, m.draft, m.has_attachment, m.size, m.to_json, m.cc_json";

// Typed column reads (a turbofish inside `json!` would split the macro's arguments at its comma).
fn g_s(r: &Row, i: usize) -> rusqlite::Result<String> {
    r.get(i)
}
fn g_os(r: &Row, i: usize) -> rusqlite::Result<Option<String>> {
    r.get(i)
}
fn g_i(r: &Row, i: usize) -> rusqlite::Result<i64> {
    r.get(i)
}
fn g_oi(r: &Row, i: usize) -> rusqlite::Result<Option<i64>> {
    r.get(i)
}
fn g_b(r: &Row, i: usize) -> rusqlite::Result<bool> {
    r.get(i)
}

fn row_json(r: &Row, base: usize) -> rusqlite::Result<Value> {
    let parse = |s: Option<String>| s.and_then(|t| serde_json::from_str::<Value>(&t).ok()).unwrap_or(Value::Null);
    Ok(json!({
        "id": g_s(r, base)?,
        "threadId": g_os(r, base + 1)?,
        "fromName": g_os(r, base + 2)?,
        "fromEmail": g_os(r, base + 3)?,
        "fromDomain": g_os(r, base + 4)?,
        "subject": g_os(r, base + 5)?,
        "preview": g_os(r, base + 6)?,
        "receivedAt": g_os(r, base + 7)?,
        "receivedMs": g_i(r, base + 8)?,
        "seen": g_b(r, base + 9)?,
        "flagged": g_b(r, base + 10)?,
        "draft": g_b(r, base + 11)?,
        "hasAttachment": g_b(r, base + 12)?,
        "size": g_oi(r, base + 13)?,
        "to": parse(g_os(r, base + 14)?),
        "cc": parse(g_os(r, base + 15)?),
    }))
}

/// One page of [a.mailbox]: `{"total": n, "rows": [...]}` (flat) or `{"total": groups, "groups": [{key, count, unread,
/// latestMs, latest: row}]}` (grouped; a group's place is its newest message's).
pub fn list(c: &Connection, a: &ListArgs) -> Result<Value, String> {
    let unread = if a.unread_only { " AND m.seen = 0" } else { "" };
    let from = "FROM membership b JOIN messages m ON m.account = b.account AND m.id = b.email_id \
                WHERE b.account = ?1 AND b.mailbox_id = ?2";
    if a.group == Group::None {
        let total: i64 = c.query_row(&format!("SELECT COUNT(*) {from}{unread}"), params![a.account, a.mailbox], |r| r.get(0)).map_err(err)?;
        let sql = format!("SELECT {ROW_COLS} {from}{unread} ORDER BY b.received_ms DESC, b.email_id DESC LIMIT ?3 OFFSET ?4");
        let mut st = c.prepare_cached(&sql).map_err(err)?;
        let rows = st
            .query_map(params![a.account, a.mailbox, a.limit as i64, a.offset as i64], |r| row_json(r, 0))
            .map_err(err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(err)?;
        return Ok(json!({ "total": total, "rows": rows }));
    }
    let key = match a.group {
        Group::Thread => "COALESCE(m.thread_id, m.id)",
        Group::Sender => "LOWER(COALESCE(m.from_email, ''))",
        Group::Domain => "COALESCE(m.from_domain, '')",
        Group::None => unreachable!(),
    };
    let having = if a.unread_only { " HAVING SUM(CASE WHEN m.seen = 0 THEN 1 ELSE 0 END) > 0" } else { "" };
    // SQLite's bare columns beside MAX(): the other columns come from the row holding the maximum, i.e. the group's newest.
    // ORDER BY the newest message's (received_ms, id), the flat list's own order, so a group sits where its newest row would.
    let grouped = format!(
        "SELECT {key} AS k, COUNT(*) AS n, SUM(CASE WHEN m.seen = 0 THEN 1 ELSE 0 END) AS u, \
         MAX(b.received_ms) AS latest, {ROW_COLS} {from} GROUP BY k{having}"
    );
    let total: i64 = c
        .query_row(&format!("SELECT COUNT(*) FROM ({grouped})"), params![a.account, a.mailbox], |r| r.get(0))
        .map_err(err)?;
    let sql = format!("SELECT * FROM ({grouped}) ORDER BY latest DESC, id DESC LIMIT ?3 OFFSET ?4");
    let mut st = c.prepare_cached(&sql).map_err(err)?;
    let groups = st
        .query_map(params![a.account, a.mailbox, a.limit as i64, a.offset as i64], |r| {
            Ok(json!({
                "key": g_s(r, 0)?,
                "count": g_i(r, 1)?,
                "unread": g_i(r, 2)?,
                "latestMs": g_i(r, 3)?,
                "latest": row_json(r, 4)?,
            }))
        })
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    Ok(json!({ "total": total, "groups": groups }))
}

/// Per mailbox of [account]: `{mailboxId: {name, role, local, unread, serverTotal, serverUnread, complete, gapsFound}}`.
pub fn counts(c: &Connection, account: &str) -> Result<Value, String> {
    let mut st = c
        .prepare(
            "SELECT x.id, x.name, x.role, COALESCE(x.total_emails, 0), COALESCE(x.unread_emails, 0),
               (SELECT COUNT(*) FROM membership b WHERE b.account = x.account AND b.mailbox_id = x.id),
               (SELECT COUNT(*) FROM membership b JOIN messages m ON m.account = b.account AND m.id = b.email_id
                  WHERE b.account = x.account AND b.mailbox_id = x.id AND m.seen = 0),
               COALESCE(s.complete, 0), COALESCE(s.gaps_found, 0)
             FROM mailboxes x LEFT JOIN sync_state s ON s.account = x.account AND s.mailbox_id = x.id
             WHERE x.account = ?1",
        )
        .map_err(err)?;
    let mut filed: std::collections::HashMap<String, i64> = std::collections::HashMap::new();
    for p in primary_map(c, account)?.into_values() {
        *filed.entry(p).or_insert(0) += 1;
    }
    let mut out = serde_json::Map::new();
    let rows = st
        .query_map(params![account], |r| {
            let id = g_s(r, 0)?;
            Ok((
                id.clone(),
                json!({
                    "name": g_os(r, 1)?,
                    "role": g_os(r, 2)?,
                    "serverTotal": g_i(r, 3)?,
                    "serverUnread": g_i(r, 4)?,
                    "local": g_i(r, 5)?,
                    "primaryLocal": filed.get(&id).copied().unwrap_or(0),
                    "unread": g_i(r, 6)?,
                    "complete": g_i(r, 7)? != 0,
                    "gapsFound": g_i(r, 8)?,
                }),
            ))
        })
        .map_err(err)?;
    for row in rows {
        let (k, v) = row.map_err(err)?;
        out.insert(k, v);
    }
    Ok(Value::Object(out))
}

#[derive(Debug, Clone, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ChangesArgs {
    pub account: String,
    pub mailbox: String,
    #[serde(default)]
    pub since_seq: i64,
    /// The last id of the previous page at [since_seq]; empty at the start of a round.
    #[serde(default)]
    pub since_id: String,
    #[serde(default = "d_changes_limit")]
    pub limit: usize,
}

fn d_changes_limit() -> usize {
    500
}

/// The projection feed: `{"emails": [<Email object>...], "removed": [ids], "nextSeq": s, "nextId": id, "more": bool,
/// "total": members, "seq": store seq, "primary": {id: mailbox}, "moved": [<Email object>...]}`. `primary` is the one mailbox the
/// app files each fed message under ([primary_mailbox]); `moved` are the messages of `removed` that are still in another mailbox.
/// `removed` is answered on the first page of a round (empty [since_id]) only.
pub fn changes_since(c: &Connection, a: &ChangesArgs) -> Result<Value, String> {
    let mut st = c
        .prepare_cached(
            "SELECT m.seq, m.id, m.json FROM membership b JOIN messages m ON m.account = b.account AND m.id = b.email_id
             WHERE b.account = ?1 AND b.mailbox_id = ?2 AND (m.seq > ?3 OR (m.seq = ?3 AND m.id > ?4))
             ORDER BY m.seq, m.id LIMIT ?5",
        )
        .map_err(err)?;
    let rows: Vec<(i64, String, String)> = st
        .query_map(params![a.account, a.mailbox, a.since_seq, a.since_id, a.limit as i64], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    let more = rows.len() >= a.limit;
    let (next_seq, next_id) = match rows.last() {
        Some((s, id, _)) if more => (*s, id.clone()),
        _ => (crate::store::current_seq(c)?, String::new()),
    };
    let emails: Vec<Value> = rows.iter().filter_map(|(_, _, j)| serde_json::from_str(j).ok()).collect();
    let mut primary = serde_json::Map::new();
    for (_, id, _) in &rows {
        if let Some(p) = primary_mailbox(c, &a.account, id)? {
            primary.insert(id.clone(), Value::String(p));
        }
    }
    let removed: Vec<String> = if a.since_id.is_empty() && a.since_seq > 0 {
        let mut t = c
            .prepare_cached("SELECT email_id FROM tombstones WHERE account = ?1 AND mailbox_id = ?2 AND seq > ?3")
            .map_err(err)?;
        let ids = t
            .query_map(params![a.account, a.mailbox, a.since_seq], |r| g_s(r, 0))
            .map_err(err)?
            .collect::<Result<Vec<_>, _>>()
            .map_err(err)?;
        ids
    } else {
        vec![]
    };
    // A membership that ended is not always a message that left: one still in another mailbox is re-filed there (`moved`).
    let mut moved: Vec<Value> = vec![];
    for id in &removed {
        if let Some(p) = primary_mailbox(c, &a.account, id)? {
            let j: Option<String> = c
                .query_row("SELECT json FROM messages WHERE account = ?1 AND id = ?2", params![a.account, id], |r| r.get(0))
                .optional()
                .map_err(err)?;
            if let Some(v) = j.and_then(|t| serde_json::from_str::<Value>(&t).ok()) {
                primary.insert(id.clone(), Value::String(p));
                moved.push(v);
            }
        }
    }
    let total = crate::store::local_count(c, &a.account, &a.mailbox)?;
    Ok(json!({
        "emails": emails,
        "moved": moved,
        "primary": primary,
        "removed": removed,
        "nextSeq": next_seq,
        "nextId": next_id,
        "more": more,
        "total": total,
    }))
}

/// Every member id of a mailbox in the server's order, for an exact reconcile on the app side.
pub fn member_ids(c: &Connection, account: &str, mailbox: &str) -> Result<Value, String> {
    let ids = crate::store::member_ids(c, account, mailbox)?;
    let primary = primary_map(c, account)?;
    let filed: Vec<&String> = ids.iter().filter(|i| primary.get(*i).map(String::as_str) == Some(mailbox)).collect();
    Ok(json!({ "ids": ids, "primaryIds": filed }))
}

/// The app keeps ONE list row per message (Room: key account + id), so a message in the Inbox and a label is filed under one
/// mailbox only. The rule is a pure function of the message's memberships (never of which mailbox was synced last), so two
/// passes cannot file it back and forth: the most specific state wins (Trash, Junk, Drafts), then Inbox, Sent, a label (no
/// role), then the roles that are only a view (Archive, All, Important, Flagged); a tie takes the smaller mailbox id.
pub fn folder_rank(role: Option<&str>) -> u8 {
    match role {
        Some("trash") => 0,
        Some("junk") => 1,
        Some("drafts") => 2,
        Some("inbox") => 3,
        Some("sent") => 4,
        None | Some("") => 5,
        Some(_) => 6,
    }
}

/// The mailbox [folder_rank] files the message under, from the (mailbox, role) pairs it is a member of. None = no membership.
pub fn pick_primary(members: &[(String, Option<String>)]) -> Option<String> {
    members.iter().min_by(|a, b| (folder_rank(a.1.as_deref()), &a.0).cmp(&(folder_rank(b.1.as_deref()), &b.0))).map(|m| m.0.clone())
}

/// The primary mailbox of one message of the store (None when it is in no mailbox).
pub fn primary_mailbox(c: &Connection, account: &str, email_id: &str) -> Result<Option<String>, String> {
    let mut st = c
        .prepare_cached(
            "SELECT b.mailbox_id, x.role FROM membership b LEFT JOIN mailboxes x ON x.account = b.account AND x.id = b.mailbox_id
             WHERE b.account = ?1 AND b.email_id = ?2",
        )
        .map_err(err)?;
    let m = st
        .query_map(params![account, email_id], |r| Ok((g_s(r, 0)?, g_os(r, 1)?)))
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    Ok(pick_primary(&m))
}

/// message id -> primary mailbox, for the whole account.
pub fn primary_map(c: &Connection, account: &str) -> Result<std::collections::HashMap<String, String>, String> {
    let mut st = c
        .prepare_cached(
            "SELECT b.email_id, b.mailbox_id, x.role FROM membership b LEFT JOIN mailboxes x ON x.account = b.account AND x.id = b.mailbox_id
             WHERE b.account = ?1",
        )
        .map_err(err)?;
    let mut by: std::collections::HashMap<String, Vec<(String, Option<String>)>> = std::collections::HashMap::new();
    let rows = st.query_map(params![account], |r| Ok((g_s(r, 0)?, g_s(r, 1)?, g_os(r, 2)?))).map_err(err)?;
    for row in rows {
        let (e, m, role) = row.map_err(err)?;
        by.entry(e).or_default().push((m, role));
    }
    Ok(by.into_iter().filter_map(|(e, m)| pick_primary(&m).map(|p| (e, p))).collect())
}

/// FTS5 search of [account] (subject, sender, preview), newest first.
pub fn search(c: &Connection, account: &str, text: &str, limit: usize) -> Result<Value, String> {
    // Every term quoted: user text is never FTS5 syntax.
    let q: String = text
        .split_whitespace()
        .map(|t| format!("\"{}\"", t.replace('"', "\"\"")))
        .collect::<Vec<_>>()
        .join(" ");
    if q.is_empty() {
        return Ok(json!({ "rows": [] }));
    }
    let sql = format!(
        "SELECT {} FROM messages_fts f JOIN messages m ON m.rowid = f.rowid
         JOIN (SELECT account, email_id, MAX(received_ms) AS received_ms FROM membership GROUP BY account, email_id) b
           ON b.account = m.account AND b.email_id = m.id
         WHERE messages_fts MATCH ?1 AND m.account = ?2 ORDER BY m.received_ms DESC, m.id DESC LIMIT ?3",
        ROW_COLS
    );
    let mut st = c.prepare(&sql).map_err(err)?;
    let rows = st
        .query_map(params![q, account, limit as i64], |r| row_json(r, 0))
        .map_err(err)?
        .collect::<Result<Vec<_>, _>>()
        .map_err(err)?;
    Ok(json!({ "rows": rows }))
}

/// The prefetched body of a message, or `{"fetched": false}`.
pub fn body(c: &Connection, account: &str, id: &str) -> Result<Value, String> {
    let row = c
        .query_row(
            "SELECT text_body, html_body, raw_json, fetched_at FROM bodies WHERE account = ?1 AND email_id = ?2 AND fetched = 1",
            params![account, id],
            |r| {
                Ok(json!({
                    "fetched": true,
                    "text": g_os(r, 0)?,
                    "html": g_os(r, 1)?,
                    "raw": g_os(r, 2)?.and_then(|s| serde_json::from_str::<Value>(&s).ok()),
                    "fetchedAt": g_oi(r, 3)?,
                }))
            },
        )
        .optional()
        .map_err(err)?;
    Ok(row.unwrap_or_else(|| json!({ "fetched": false })))
}

/// The reader document of a message, from the store: `{"available":true,"kind","fragment","text","remoteImages",...}` or
/// `{"available":false}` when the raw message is not stored. The parsed document is what an open reads (one indexed row); a
/// document of an older READER_VERSION is re-parsed from the stored raw message once and written back.
pub fn reader(c: &Connection, account: &str, id: &str) -> Result<Value, String> {
    let started = std::time::Instant::now();
    let mut parsed = crate::store::get_reader(c, account, id)?;
    let mut reparsed = false;
    if parsed.is_none() {
        if let Some(raw) = crate::store::get_raw(c, account, id)? {
            let p = crate::reader::parse(&raw)?;
            crate::store::put_reader(c, account, id, &p, crate::store::now_ms())?;
            parsed = Some(p);
            reparsed = true;
        }
    }
    Ok(match parsed {
        Some(p) => json!({
            "available": true,
            "kind": p.kind,
            "fragment": p.fragment,
            "text": p.text,
            "remoteImages": p.remote_images,
            "encodingProblem": p.encoding_problem,
            "reparsed": reparsed,
            "ms": started.elapsed().as_millis() as u64,
        }),
        None => json!({ "available": false }),
    })
}

/// The local account ids the store holds: `{"ids": [...]}`.
pub fn accounts(c: &Connection) -> Result<Value, String> {
    let mut st = c.prepare("SELECT id FROM accounts ORDER BY id").map_err(err)?;
    let ids = st.query_map([], |r| r.get::<_, String>(0)).map_err(err)?.collect::<Result<Vec<_>, _>>().map_err(err)?;
    Ok(json!({ "ids": ids }))
}

/// Row counts of every table, for the logs and X-Ray.
pub fn stats(c: &Connection) -> Result<Value, String> {
    let mut out = serde_json::Map::new();
    for t in ["accounts", "mailboxes", "messages", "membership", "tombstones", "sync_state", "bodies", "raw_messages", "reader_docs"] {
        let n: i64 = c.query_row(&format!("SELECT COUNT(*) FROM {t}"), [], |r| r.get(0)).map_err(err)?;
        out.insert(t.to_string(), json!(n));
    }
    out.insert("seq".into(), json!(crate::store::current_seq(c)?));
    out.insert("schema".into(), json!(crate::store::SCHEMA_VERSION));
    Ok(Value::Object(out))
}
