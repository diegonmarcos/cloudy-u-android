//! The local mail store: ONE SQLite file (bundled SQLite, WAL), the mirror of every synced account.
//!
//! Tables:
//!   accounts    local account id, JMAP account id, the account-wide Mailbox and Email state strings, last sync
//!   mailboxes   every mailbox of the account with the server's counters (totalEmails is what gap detection compares)
//!   messages    one row per message: ids, thread id, from/to/cc, subject, preview, receivedAt, keywords, size, the
//!               server's Email object verbatim (`json`, what the app maps into its own row, so no field is lost) and `seq`
//!   membership  (mailbox, message): a message in two mailboxes has two rows; ordered by receivedAt for the lists
//!   tombstones  a membership that ended (delete, move), with the `seq` it ended at: the projection feed's removals
//!   sync_state  per mailbox: the Email/query state, the server total, the local count, complete or not, gaps found
//!   bodies      the prefetched bodies (text and HTML part values, the raw part list), with a fetched flag
//!   raw_messages  the message's RFC 5322 bytes as the server holds them (JMAP blob download), what the reader parses
//!   reader_docs   the parsed reader document (kind, sanitised fragment, decoded text), versioned: an open never parses
//!   messages_fts  FTS5 over subject, sender and preview (rowid = messages.rowid)
//!
//! `seq` is a store-wide counter bumped once per write batch: every row a batch writes and every membership it ends carries
//! it, so "what changed in this mailbox since cursor N" is one indexed read (query::changes_since).

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::Value;

pub const SCHEMA_VERSION: i64 = 1;

const SCHEMA: &str = r#"
CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL);
INSERT OR IGNORE INTO meta(key, value) VALUES('seq', 0);
CREATE TABLE IF NOT EXISTS accounts(
  id TEXT PRIMARY KEY,
  jmap_account_id TEXT,
  mailbox_state TEXT,
  email_state TEXT,
  last_sync_ms INTEGER,
  last_error TEXT
);
CREATE TABLE IF NOT EXISTS mailboxes(
  account TEXT NOT NULL,
  id TEXT NOT NULL,
  name TEXT,
  role TEXT,
  parent_id TEXT,
  sort_order INTEGER,
  total_emails INTEGER,
  unread_emails INTEGER,
  total_threads INTEGER,
  unread_threads INTEGER,
  json TEXT,
  PRIMARY KEY(account, id)
);
CREATE TABLE IF NOT EXISTS messages(
  account TEXT NOT NULL,
  id TEXT NOT NULL,
  thread_id TEXT,
  from_name TEXT,
  from_email TEXT,
  from_domain TEXT,
  to_json TEXT,
  cc_json TEXT,
  subject TEXT,
  preview TEXT,
  received_at TEXT,
  received_ms INTEGER NOT NULL DEFAULT 0,
  seen INTEGER NOT NULL DEFAULT 0,
  flagged INTEGER NOT NULL DEFAULT 0,
  draft INTEGER NOT NULL DEFAULT 0,
  keywords TEXT,
  size INTEGER,
  has_attachment INTEGER NOT NULL DEFAULT 0,
  json TEXT NOT NULL,
  seq INTEGER NOT NULL,
  PRIMARY KEY(account, id)
);
CREATE INDEX IF NOT EXISTS messages_seq ON messages(account, seq, id);
CREATE INDEX IF NOT EXISTS messages_thread ON messages(account, thread_id);
CREATE TABLE IF NOT EXISTS membership(
  account TEXT NOT NULL,
  mailbox_id TEXT NOT NULL,
  email_id TEXT NOT NULL,
  received_ms INTEGER NOT NULL,
  PRIMARY KEY(account, mailbox_id, email_id)
);
CREATE INDEX IF NOT EXISTS membership_order ON membership(account, mailbox_id, received_ms DESC, email_id DESC);
CREATE INDEX IF NOT EXISTS membership_email ON membership(account, email_id);
CREATE TABLE IF NOT EXISTS tombstones(
  account TEXT NOT NULL,
  mailbox_id TEXT NOT NULL,
  email_id TEXT NOT NULL,
  seq INTEGER NOT NULL,
  PRIMARY KEY(account, mailbox_id, email_id)
);
CREATE INDEX IF NOT EXISTS tombstones_seq ON tombstones(account, mailbox_id, seq);
CREATE TABLE IF NOT EXISTS sync_state(
  account TEXT NOT NULL,
  mailbox_id TEXT NOT NULL,
  query_state TEXT,
  server_total INTEGER,
  local_count INTEGER,
  complete INTEGER NOT NULL DEFAULT 0,
  gaps_found INTEGER NOT NULL DEFAULT 0,
  refresh_pending INTEGER NOT NULL DEFAULT 0,
  last_walk_ms INTEGER,
  last_sync_ms INTEGER,
  PRIMARY KEY(account, mailbox_id)
);
CREATE TABLE IF NOT EXISTS bodies(
  account TEXT NOT NULL,
  email_id TEXT NOT NULL,
  text_body TEXT,
  html_body TEXT,
  raw_json TEXT,
  fetched INTEGER NOT NULL DEFAULT 0,
  fetched_at INTEGER,
  PRIMARY KEY(account, email_id)
);
CREATE TABLE IF NOT EXISTS raw_messages(
  account TEXT NOT NULL,
  email_id TEXT NOT NULL,
  raw BLOB NOT NULL,
  size INTEGER NOT NULL,
  fetched_at INTEGER NOT NULL,
  PRIMARY KEY(account, email_id)
);
CREATE TABLE IF NOT EXISTS reader_docs(
  account TEXT NOT NULL,
  email_id TEXT NOT NULL,
  version INTEGER NOT NULL,
  kind TEXT NOT NULL,
  fragment TEXT NOT NULL,
  text_body TEXT,
  remote_images INTEGER NOT NULL DEFAULT 0,
  encoding_problem INTEGER NOT NULL DEFAULT 0,
  parsed_at INTEGER NOT NULL,
  PRIMARY KEY(account, email_id)
);
CREATE VIRTUAL TABLE IF NOT EXISTS messages_fts USING fts5(subject, sender, preview, tokenize = 'unicode61 remove_diacritics 2');
"#;

pub struct Store {
    pub conn: Connection,
}

pub fn err<E: std::fmt::Display>(e: E) -> String {
    e.to_string()
}

impl Store {
    /// Open (and create or migrate) the store at [path]; ":memory:" for tests.
    pub fn open(path: &str) -> Result<Store, String> {
        let conn = if path == ":memory:" {
            Connection::open_in_memory().map_err(err)?
        } else {
            if let Some(dir) = std::path::Path::new(path).parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            Connection::open(path).map_err(err)?
        };
        conn.busy_timeout(std::time::Duration::from_secs(10)).map_err(err)?;
        // WAL: the UI's reads never wait for a sync's writes (a reader connection, jni_api), and a crash loses no commit.
        let _: String = conn.query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0)).unwrap_or_default();
        conn.execute_batch("PRAGMA synchronous=NORMAL; PRAGMA temp_store=MEMORY;").map_err(err)?;
        conn.execute_batch(SCHEMA).map_err(err)?;
        conn.execute_batch(&format!("PRAGMA user_version={SCHEMA_VERSION};")).map_err(err)?;
        Ok(Store { conn })
    }
}

/// The next write batch's sequence number (store-wide, monotonic).
pub fn next_seq(conn: &Connection) -> Result<i64, String> {
    conn.execute("UPDATE meta SET value = value + 1 WHERE key = 'seq'", []).map_err(err)?;
    conn.query_row("SELECT value FROM meta WHERE key = 'seq'", [], |r| r.get(0)).map_err(err)
}

pub fn current_seq(conn: &Connection) -> Result<i64, String> {
    conn.query_row("SELECT value FROM meta WHERE key = 'seq'", [], |r| r.get(0)).map_err(err)
}

// ── accounts ────────────────────────────────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Default, Clone)]
pub struct AccountRow {
    pub id: String,
    pub jmap_account_id: String,
    pub mailbox_state: Option<String>,
    pub email_state: Option<String>,
}

pub fn ensure_account(conn: &Connection, id: &str, jmap_account_id: &str) -> Result<AccountRow, String> {
    conn.execute("INSERT OR IGNORE INTO accounts(id, jmap_account_id) VALUES(?1, ?2)", params![id, jmap_account_id]).map_err(err)?;
    let row: AccountRow = conn
        .query_row("SELECT id, jmap_account_id, mailbox_state, email_state FROM accounts WHERE id = ?1", params![id], |r| {
            Ok(AccountRow {
                id: r.get(0)?,
                jmap_account_id: r.get::<_, Option<String>>(1)?.unwrap_or_default(),
                mailbox_state: r.get(2)?,
                email_state: r.get(3)?,
            })
        })
        .map_err(err)?;
    if row.jmap_account_id != jmap_account_id {
        // The server account behind this local account changed: every state string and row is another account's.
        drop_account(conn, id)?;
        conn.execute("INSERT INTO accounts(id, jmap_account_id) VALUES(?1, ?2)", params![id, jmap_account_id]).map_err(err)?;
        return Ok(AccountRow { id: id.to_string(), jmap_account_id: jmap_account_id.to_string(), ..Default::default() });
    }
    Ok(row)
}

pub fn set_mailbox_state(conn: &Connection, account: &str, state: Option<&str>) -> Result<(), String> {
    conn.execute("UPDATE accounts SET mailbox_state = ?2 WHERE id = ?1", params![account, state]).map_err(err).map(|_| ())
}

pub fn set_email_state(conn: &Connection, account: &str, state: Option<&str>) -> Result<(), String> {
    conn.execute("UPDATE accounts SET email_state = ?2 WHERE id = ?1", params![account, state]).map_err(err).map(|_| ())
}

pub fn set_last_sync(conn: &Connection, account: &str, ms: i64, error: Option<&str>) -> Result<(), String> {
    conn.execute("UPDATE accounts SET last_sync_ms = ?2, last_error = ?3 WHERE id = ?1", params![account, ms, error])
        .map_err(err)
        .map(|_| ())
}

/// Every row of [account], in every table: the account was removed from the app, or its server account changed.
pub fn drop_account(conn: &Connection, account: &str) -> Result<(), String> {
    conn.execute(
        "DELETE FROM messages_fts WHERE rowid IN (SELECT rowid FROM messages WHERE account = ?1)",
        params![account],
    )
    .map_err(err)?;
    for t in ["messages", "membership", "tombstones", "sync_state", "bodies", "mailboxes", "raw_messages", "reader_docs"] {
        conn.execute(&format!("DELETE FROM {t} WHERE account = ?1"), params![account]).map_err(err)?;
    }
    conn.execute("DELETE FROM accounts WHERE id = ?1", params![account]).map_err(err)?;
    Ok(())
}

// ── mailboxes ───────────────────────────────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Clone)]
pub struct MailboxRow {
    pub id: String,
    pub name: String,
    pub role: Option<String>,
    pub total_emails: i64,
    pub unread_emails: i64,
    pub sort_order: i64,
}

pub fn upsert_mailbox(conn: &Connection, account: &str, v: &Value) -> Result<(), String> {
    let id = v.get("id").and_then(Value::as_str).ok_or("mailbox without id")?;
    let i = |k: &str| v.get(k).and_then(Value::as_i64);
    let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
    conn.execute(
        "INSERT INTO mailboxes(account, id, name, role, parent_id, sort_order, total_emails, unread_emails, total_threads, unread_threads, json)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11)
         ON CONFLICT(account, id) DO UPDATE SET name = excluded.name, role = excluded.role, parent_id = excluded.parent_id,
           sort_order = excluded.sort_order, total_emails = excluded.total_emails, unread_emails = excluded.unread_emails,
           total_threads = excluded.total_threads, unread_threads = excluded.unread_threads, json = excluded.json",
        params![
            account,
            id,
            s("name"),
            s("role"),
            s("parentId"),
            i("sortOrder").unwrap_or(0),
            i("totalEmails").unwrap_or(0),
            i("unreadEmails").unwrap_or(0),
            i("totalThreads").unwrap_or(0),
            i("unreadThreads").unwrap_or(0),
            v.to_string()
        ],
    )
    .map_err(err)?;
    Ok(())
}

/// A mailbox that left the server: its rows, and the memberships in it (each becomes a tombstone at [seq]).
pub fn delete_mailbox(conn: &Connection, account: &str, id: &str, seq: i64) -> Result<(), String> {
    conn.execute(
        "INSERT OR REPLACE INTO tombstones(account, mailbox_id, email_id, seq)
         SELECT account, mailbox_id, email_id, ?3 FROM membership WHERE account = ?1 AND mailbox_id = ?2",
        params![account, id, seq],
    )
    .map_err(err)?;
    conn.execute("DELETE FROM membership WHERE account = ?1 AND mailbox_id = ?2", params![account, id]).map_err(err)?;
    conn.execute("DELETE FROM mailboxes WHERE account = ?1 AND id = ?2", params![account, id]).map_err(err)?;
    conn.execute("DELETE FROM sync_state WHERE account = ?1 AND mailbox_id = ?2", params![account, id]).map_err(err)?;
    Ok(())
}

/// The account's mailboxes in sync order: Inbox first, then the other role folders, then by the server's sortOrder and name.
pub fn mailboxes(conn: &Connection, account: &str) -> Result<Vec<MailboxRow>, String> {
    let mut st = conn
        .prepare(
            "SELECT id, COALESCE(name, ''), role, COALESCE(total_emails, 0), COALESCE(unread_emails, 0), COALESCE(sort_order, 0)
             FROM mailboxes WHERE account = ?1
             ORDER BY CASE WHEN role = 'inbox' THEN 0 WHEN role IS NOT NULL THEN 1 ELSE 2 END, sort_order, name, id",
        )
        .map_err(err)?;
    let rows = st
        .query_map(params![account], |r| {
            Ok(MailboxRow {
                id: r.get(0)?,
                name: r.get(1)?,
                role: r.get(2)?,
                total_emails: r.get(3)?,
                unread_emails: r.get(4)?,
                sort_order: r.get(5)?,
            })
        })
        .map_err(err)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(err)
}

pub fn mailbox_ids(conn: &Connection, account: &str) -> Result<Vec<String>, String> {
    Ok(mailboxes(conn, account)?.into_iter().map(|m| m.id).collect())
}

// ── messages ────────────────────────────────────────────────────────────────────────────────────────────────────────

/// RFC 3339 (`2026-11-20T10:00:00Z`, fractions and offsets allowed) to epoch milliseconds; 0 when unparsable.
pub fn epoch_ms(s: &str) -> i64 {
    fn num(s: &str) -> Option<i64> {
        s.parse::<i64>().ok()
    }
    let b = s.as_bytes();
    if b.len() < 19 || b[4] != b'-' || b[7] != b'-' || (b[10] != b'T' && b[10] != b't' && b[10] != b' ') {
        return 0;
    }
    let (Some(y), Some(mo), Some(d), Some(h), Some(mi), Some(se)) =
        (num(&s[0..4]), num(&s[5..7]), num(&s[8..10]), num(&s[11..13]), num(&s[14..16]), num(&s[17..19]))
    else {
        return 0;
    };
    let mut rest = &s[19..];
    let mut frac_ms = 0i64;
    if let Some(r) = rest.strip_prefix('.') {
        let digits: String = r.chars().take_while(|c| c.is_ascii_digit()).collect();
        let mut f = digits.clone();
        f.truncate(3);
        while f.len() < 3 {
            f.push('0');
        }
        frac_ms = num(&f).unwrap_or(0);
        rest = &r[digits.len()..];
    }
    let offset_min = if rest.is_empty() || rest.eq_ignore_ascii_case("z") {
        0
    } else if rest.len() >= 6 && (rest.starts_with('+') || rest.starts_with('-')) {
        let sign = if rest.starts_with('-') { -1 } else { 1 };
        sign * (num(&rest[1..3]).unwrap_or(0) * 60 + num(&rest[4..6]).unwrap_or(0))
    } else {
        0
    };
    // Days from civil (Howard Hinnant).
    let (y, m) = if mo <= 2 { (y - 1, mo + 9) } else { (y, mo - 3) };
    let era = (if y >= 0 { y } else { y - 399 }) / 400;
    let yoe = y - era * 400;
    let doy = (153 * m + 2) / 5 + d - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    let days = era * 146097 + doe - 719468;
    ((days * 86400 + h * 3600 + mi * 60 + se) - offset_min * 60) * 1000 + frac_ms
}

fn kw(v: &Value, k: &str) -> bool {
    v.get("keywords").and_then(|m| m.get(k)).and_then(Value::as_bool).unwrap_or(false)
}

/// The mailboxes the object says it is in; None when the object does not carry `mailboxIds` (membership left as is).
pub fn mailbox_ids_of(v: &Value) -> Option<Vec<String>> {
    let m = v.get("mailboxIds")?.as_object()?;
    Some(m.iter().filter(|(_, on)| on.as_bool().unwrap_or(false)).map(|(k, _)| k.clone()).collect())
}

/// Insert or replace one message from its JMAP Email object, its FTS row and its memberships, at [seq].
pub fn upsert_email(conn: &Connection, account: &str, v: &Value, seq: i64) -> Result<(), String> {
    let id = v.get("id").and_then(Value::as_str).ok_or("email without id")?;
    let s = |k: &str| v.get(k).and_then(Value::as_str).map(str::to_string);
    let from = v.get("from").and_then(Value::as_array).and_then(|a| a.first());
    let from_name = from.and_then(|f| f.get("name")).and_then(Value::as_str).map(str::to_string);
    let from_email = from.and_then(|f| f.get("email")).and_then(Value::as_str).map(str::to_string);
    let from_domain = from_email.as_deref().map(crate::domain::registrable).unwrap_or_default();
    let received_at = s("receivedAt");
    let received_ms = received_at.as_deref().map(epoch_ms).unwrap_or(0);
    let json_of = |k: &str| v.get(k).filter(|x| !x.is_null()).map(|x| x.to_string());
    conn.execute(
        "INSERT INTO messages(account, id, thread_id, from_name, from_email, from_domain, to_json, cc_json, subject, preview,
           received_at, received_ms, seen, flagged, draft, keywords, size, has_attachment, json, seq)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)
         ON CONFLICT(account, id) DO UPDATE SET thread_id = excluded.thread_id, from_name = excluded.from_name,
           from_email = excluded.from_email, from_domain = excluded.from_domain, to_json = excluded.to_json,
           cc_json = excluded.cc_json, subject = excluded.subject, preview = excluded.preview,
           received_at = excluded.received_at, received_ms = excluded.received_ms, seen = excluded.seen,
           flagged = excluded.flagged, draft = excluded.draft, keywords = excluded.keywords, size = excluded.size,
           has_attachment = excluded.has_attachment, json = excluded.json, seq = excluded.seq",
        params![
            account,
            id,
            s("threadId"),
            from_name,
            from_email,
            from_domain,
            json_of("to"),
            json_of("cc"),
            s("subject"),
            s("preview"),
            received_at,
            received_ms,
            kw(v, "$seen"),
            kw(v, "$flagged"),
            kw(v, "$draft"),
            json_of("keywords"),
            v.get("size").and_then(Value::as_i64),
            v.get("hasAttachment").and_then(Value::as_bool).unwrap_or(false),
            v.to_string(),
            seq
        ],
    )
    .map_err(err)?;
    let rowid: i64 =
        conn.query_row("SELECT rowid FROM messages WHERE account = ?1 AND id = ?2", params![account, id], |r| r.get(0)).map_err(err)?;
    conn.execute("DELETE FROM messages_fts WHERE rowid = ?1", params![rowid]).map_err(err)?;
    let sender = [from_name.as_deref().unwrap_or(""), from_email.as_deref().unwrap_or("")].join(" ");
    conn.execute(
        "INSERT INTO messages_fts(rowid, subject, sender, preview) VALUES(?1, ?2, ?3, ?4)",
        params![rowid, s("subject").unwrap_or_default(), sender.trim(), s("preview").unwrap_or_default()],
    )
    .map_err(err)?;
    if let Some(now) = mailbox_ids_of(v) {
        set_membership(conn, account, id, &now, received_ms, seq)?;
    } else {
        conn.execute(
            "UPDATE membership SET received_ms = ?3 WHERE account = ?1 AND email_id = ?2",
            params![account, id, received_ms],
        )
        .map_err(err)?;
    }
    Ok(())
}

/// Make [email]'s memberships exactly [now]: the ones it left become tombstones at [seq], the ones it joined lose theirs.
pub fn set_membership(conn: &Connection, account: &str, email: &str, now: &[String], received_ms: i64, seq: i64) -> Result<(), String> {
    let before = memberships_of(conn, account, email)?;
    for m in before.iter().filter(|m| !now.contains(m)) {
        conn.execute(
            "DELETE FROM membership WHERE account = ?1 AND mailbox_id = ?2 AND email_id = ?3",
            params![account, m, email],
        )
        .map_err(err)?;
        conn.execute(
            "INSERT OR REPLACE INTO tombstones(account, mailbox_id, email_id, seq) VALUES(?1, ?2, ?3, ?4)",
            params![account, m, email, seq],
        )
        .map_err(err)?;
    }
    for m in now {
        conn.execute(
            "INSERT INTO membership(account, mailbox_id, email_id, received_ms) VALUES(?1, ?2, ?3, ?4)
             ON CONFLICT(account, mailbox_id, email_id) DO UPDATE SET received_ms = excluded.received_ms",
            params![account, m, email, received_ms],
        )
        .map_err(err)?;
        conn.execute(
            "DELETE FROM tombstones WHERE account = ?1 AND mailbox_id = ?2 AND email_id = ?3",
            params![account, m, email],
        )
        .map_err(err)?;
    }
    Ok(())
}

pub fn memberships_of(conn: &Connection, account: &str, email: &str) -> Result<Vec<String>, String> {
    let mut st = conn.prepare_cached("SELECT mailbox_id FROM membership WHERE account = ?1 AND email_id = ?2").map_err(err)?;
    let rows = st.query_map(params![account, email], |r| r.get::<_, String>(0)).map_err(err)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(err)
}

/// A message that left the server: every membership becomes a tombstone at [seq], then the message, its body and FTS row go.
pub fn delete_email(conn: &Connection, account: &str, id: &str, seq: i64) -> Result<(), String> {
    set_membership(conn, account, id, &[], 0, seq)?;
    let rowid: Option<i64> = conn
        .query_row("SELECT rowid FROM messages WHERE account = ?1 AND id = ?2", params![account, id], |r| r.get(0))
        .optional()
        .map_err(err)?;
    if let Some(rowid) = rowid {
        conn.execute("DELETE FROM messages_fts WHERE rowid = ?1", params![rowid]).map_err(err)?;
    }
    conn.execute("DELETE FROM messages WHERE account = ?1 AND id = ?2", params![account, id]).map_err(err)?;
    conn.execute("DELETE FROM bodies WHERE account = ?1 AND email_id = ?2", params![account, id]).map_err(err)?;
    conn.execute("DELETE FROM raw_messages WHERE account = ?1 AND email_id = ?2", params![account, id]).map_err(err)?;
    conn.execute("DELETE FROM reader_docs WHERE account = ?1 AND email_id = ?2", params![account, id]).map_err(err)?;
    Ok(())
}

/// Messages left in no mailbox (their last mailbox was destroyed): JMAP has no such message, so neither does the store.
pub fn delete_orphans(conn: &Connection, account: &str, seq: i64) -> Result<usize, String> {
    let ids: Vec<String> = {
        let mut st = conn
            .prepare(
                "SELECT id FROM messages m WHERE account = ?1
                 AND NOT EXISTS (SELECT 1 FROM membership b WHERE b.account = m.account AND b.email_id = m.id)",
            )
            .map_err(err)?;
        let rows = st.query_map(params![account], |r| r.get::<_, String>(0)).map_err(err)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(err)?
    };
    for id in &ids {
        delete_email(conn, account, id, seq)?;
    }
    Ok(ids.len())
}

pub fn has_membership(conn: &Connection, account: &str, mailbox: &str, email: &str) -> Result<bool, String> {
    let mut st = conn
        .prepare_cached("SELECT 1 FROM membership WHERE account = ?1 AND mailbox_id = ?2 AND email_id = ?3")
        .map_err(err)?;
    st.exists(params![account, mailbox, email]).map_err(err)
}

pub fn local_count(conn: &Connection, account: &str, mailbox: &str) -> Result<i64, String> {
    conn.query_row(
        "SELECT COUNT(*) FROM membership WHERE account = ?1 AND mailbox_id = ?2",
        params![account, mailbox],
        |r| r.get(0),
    )
    .map_err(err)
}

pub fn member_ids(conn: &Connection, account: &str, mailbox: &str) -> Result<Vec<String>, String> {
    let mut st = conn
        .prepare("SELECT email_id FROM membership WHERE account = ?1 AND mailbox_id = ?2 ORDER BY received_ms DESC, email_id DESC")
        .map_err(err)?;
    let rows = st.query_map(params![account, mailbox], |r| r.get::<_, String>(0)).map_err(err)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(err)
}

// ── sync state ──────────────────────────────────────────────────────────────────────────────────────────────────────

#[derive(Debug, Default, Clone)]
pub struct SyncStateRow {
    pub query_state: Option<String>,
    pub server_total: i64,
    pub local_count: i64,
    pub complete: bool,
    pub gaps_found: i64,
    /// Every message of the mailbox must be re-read (the Email state could not be followed: cannotCalculateChanges).
    pub refresh_pending: bool,
    pub last_walk_ms: Option<i64>,
}

pub fn sync_state(conn: &Connection, account: &str, mailbox: &str) -> Result<Option<SyncStateRow>, String> {
    conn.query_row(
        "SELECT query_state, COALESCE(server_total, 0), COALESCE(local_count, 0), complete, gaps_found, refresh_pending, last_walk_ms
         FROM sync_state WHERE account = ?1 AND mailbox_id = ?2",
        params![account, mailbox],
        |r| {
            Ok(SyncStateRow {
                query_state: r.get(0)?,
                server_total: r.get(1)?,
                local_count: r.get(2)?,
                complete: r.get::<_, i64>(3)? != 0,
                gaps_found: r.get(4)?,
                refresh_pending: r.get::<_, i64>(5)? != 0,
                last_walk_ms: r.get(6)?,
            })
        },
    )
    .optional()
    .map_err(err)
}

pub fn put_sync_state(conn: &Connection, account: &str, mailbox: &str, s: &SyncStateRow, walked: bool, now_ms: i64) -> Result<(), String> {
    conn.execute(
        "INSERT INTO sync_state(account, mailbox_id, query_state, server_total, local_count, complete, gaps_found, refresh_pending,
           last_walk_ms, last_sync_ms)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?10, CASE WHEN ?8 THEN ?9 ELSE NULL END, ?9)
         ON CONFLICT(account, mailbox_id) DO UPDATE SET query_state = excluded.query_state, server_total = excluded.server_total,
           local_count = excluded.local_count, complete = excluded.complete, gaps_found = excluded.gaps_found,
           refresh_pending = excluded.refresh_pending, last_walk_ms = COALESCE(excluded.last_walk_ms, sync_state.last_walk_ms), last_sync_ms = excluded.last_sync_ms",
        params![
            account,
            mailbox,
            s.query_state,
            s.server_total,
            s.local_count,
            s.complete as i64,
            s.gaps_found,
            walked,
            now_ms,
            s.refresh_pending as i64
        ],
    )
    .map_err(err)
    .map(|_| ())
}

// ── bodies ──────────────────────────────────────────────────────────────────────────────────────────────────────────

/// The newest [n] messages of [mailbox] whose body is not fetched yet.
pub fn unfetched_newest(conn: &Connection, account: &str, mailbox: &str, n: usize) -> Result<Vec<String>, String> {
    let mut st = conn
        .prepare(
            "SELECT b.email_id FROM membership b
             WHERE b.account = ?1 AND b.mailbox_id = ?2
               AND b.email_id IN (SELECT email_id FROM membership WHERE account = ?1 AND mailbox_id = ?2
                                  ORDER BY received_ms DESC, email_id DESC LIMIT ?3)
               AND NOT EXISTS (SELECT 1 FROM bodies y WHERE y.account = b.account AND y.email_id = b.email_id AND y.fetched = 1)
             ORDER BY b.received_ms DESC, b.email_id DESC",
        )
        .map_err(err)?;
    let rows = st.query_map(params![account, mailbox, n as i64], |r| r.get::<_, String>(0)).map_err(err)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(err)
}

/// Store one body from an Email/get with body values: the text parts' values joined, the HTML parts' values joined.
pub fn put_body(conn: &Connection, account: &str, v: &Value, now_ms: i64) -> Result<(), String> {
    let id = v.get("id").and_then(Value::as_str).ok_or("body without id")?;
    let values = v.get("bodyValues").and_then(Value::as_object);
    let join = |k: &str| -> Option<String> {
        let parts = v.get(k)?.as_array()?;
        let texts: Vec<String> = parts
            .iter()
            .filter_map(|p| p.get("partId").and_then(Value::as_str))
            .filter_map(|pid| values.and_then(|m| m.get(pid)).and_then(|bv| bv.get("value")).and_then(Value::as_str))
            .map(str::to_string)
            .collect();
        if texts.is_empty() {
            None
        } else {
            Some(texts.join("\n"))
        }
    };
    conn.execute(
        "INSERT INTO bodies(account, email_id, text_body, html_body, raw_json, fetched, fetched_at) VALUES(?1, ?2, ?3, ?4, ?5, 1, ?6)
         ON CONFLICT(account, email_id) DO UPDATE SET text_body = excluded.text_body, html_body = excluded.html_body,
           raw_json = excluded.raw_json, fetched = 1, fetched_at = excluded.fetched_at",
        params![account, id, join("textBody"), join("htmlBody"), v.to_string(), now_ms],
    )
    .map_err(err)?;
    Ok(())
}

// ── raw messages and the parsed reader documents ────────────────────────────────────────────────────────────────────────

/// Store a message's raw bytes and its parsed reader document in one transaction.
pub fn put_raw(conn: &Connection, account: &str, id: &str, raw: &[u8], parsed: Option<&crate::reader::Parsed>, now_ms: i64) -> Result<(), String> {
    let tx = conn.unchecked_transaction().map_err(err)?;
    tx.execute(
        "INSERT INTO raw_messages(account, email_id, raw, size, fetched_at) VALUES(?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(account, email_id) DO UPDATE SET raw = excluded.raw, size = excluded.size, fetched_at = excluded.fetched_at",
        params![account, id, raw, raw.len() as i64, now_ms],
    )
    .map_err(err)?;
    if let Some(p) = parsed {
        put_reader(&tx, account, id, p, now_ms)?;
    }
    tx.commit().map_err(err)
}

pub fn put_reader(conn: &Connection, account: &str, id: &str, p: &crate::reader::Parsed, now_ms: i64) -> Result<(), String> {
    conn.execute(
        "INSERT INTO reader_docs(account, email_id, version, kind, fragment, text_body, remote_images, encoding_problem, parsed_at)
         VALUES(?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)
         ON CONFLICT(account, email_id) DO UPDATE SET version = excluded.version, kind = excluded.kind, fragment = excluded.fragment,
           text_body = excluded.text_body, remote_images = excluded.remote_images, encoding_problem = excluded.encoding_problem,
           parsed_at = excluded.parsed_at",
        params![account, id, crate::reader::READER_VERSION, p.kind, p.fragment, p.text, p.remote_images, p.encoding_problem, now_ms],
    )
    .map_err(err)?;
    Ok(())
}

pub fn has_raw(conn: &Connection, account: &str, id: &str) -> Result<bool, String> {
    let n: i64 = conn
        .query_row("SELECT COUNT(*) FROM raw_messages WHERE account = ?1 AND email_id = ?2", params![account, id], |r| r.get(0))
        .map_err(err)?;
    Ok(n > 0)
}

/// The stored parsed document of the current version.
pub fn get_reader(conn: &Connection, account: &str, id: &str) -> Result<Option<crate::reader::Parsed>, String> {
    conn.query_row(
        "SELECT kind, fragment, text_body, remote_images, encoding_problem FROM reader_docs
         WHERE account = ?1 AND email_id = ?2 AND version = ?3",
        params![account, id, crate::reader::READER_VERSION],
        |r| {
            Ok(crate::reader::Parsed {
                kind: r.get(0)?,
                fragment: r.get(1)?,
                text: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
                remote_images: r.get(3)?,
                encoding_problem: r.get(4)?,
            })
        },
    )
    .optional()
    .map_err(err)
}

pub fn get_raw(conn: &Connection, account: &str, id: &str) -> Result<Option<Vec<u8>>, String> {
    conn.query_row("SELECT raw FROM raw_messages WHERE account = ?1 AND email_id = ?2", params![account, id], |r| r.get::<_, Vec<u8>>(0))
        .optional()
        .map_err(err)
}

/// The JMAP blobId a stored message was fetched with (the server's Email object verbatim is in `json`).
pub fn blob_id(conn: &Connection, account: &str, id: &str) -> Result<Option<String>, String> {
    let json: Option<String> = conn
        .query_row("SELECT json FROM messages WHERE account = ?1 AND id = ?2", params![account, id], |r| r.get(0))
        .optional()
        .map_err(err)?;
    Ok(json.and_then(|j| serde_json::from_str::<Value>(&j).ok()).and_then(|v| v.get("blobId").and_then(Value::as_str).map(str::to_string)))
}

/// The newest [n] messages of [mailbox] whose raw message is not stored yet and whose size is at most [max_size], with their size.
pub fn unraw_newest(conn: &Connection, account: &str, mailbox: &str, n: usize, max_size: i64) -> Result<Vec<String>, String> {
    let mut st = conn
        .prepare(
            "SELECT b.email_id FROM membership b
             JOIN messages m ON m.account = b.account AND m.id = b.email_id
             WHERE b.account = ?1 AND b.mailbox_id = ?2
               AND b.email_id IN (SELECT email_id FROM membership WHERE account = ?1 AND mailbox_id = ?2
                                  ORDER BY received_ms DESC, email_id DESC LIMIT ?3)
               AND COALESCE(m.size, 0) <= ?4
               AND NOT EXISTS (SELECT 1 FROM raw_messages y WHERE y.account = b.account AND y.email_id = b.email_id)
             ORDER BY b.received_ms DESC, b.email_id DESC",
        )
        .map_err(err)?;
    let rows = st.query_map(params![account, mailbox, n as i64, max_size], |r| r.get::<_, String>(0)).map_err(err)?;
    rows.collect::<Result<Vec<_>, _>>().map_err(err)
}

/// Every mailbox of [account] must be re-read whole (the Email state was lost): set before the walks, cleared by each walk.
pub fn mark_refresh_all(conn: &Connection, account: &str, now_ms: i64) -> Result<(), String> {
    for id in mailbox_ids(conn, account)? {
        let mut s = sync_state(conn, account, &id)?.unwrap_or_default();
        s.refresh_pending = true;
        s.complete = false;
        put_sync_state(conn, account, &id, &s, false, now_ms)?;
    }
    Ok(())
}

pub fn now_ms() -> i64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.as_millis() as i64).unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn epoch_ms_reads_rfc3339() {
        assert_eq!(epoch_ms("1970-01-01T00:00:00Z"), 0);
        assert_eq!(epoch_ms("2026-11-20T10:00:00Z"), 1_795_168_800_000);
        assert_eq!(epoch_ms("2026-11-20T11:00:00+01:00"), 1_795_168_800_000);
        assert_eq!(epoch_ms("2026-11-20T10:00:00.250Z"), 1_795_168_800_250);
        assert_eq!(epoch_ms("garbage"), 0);
    }

    #[test]
    fn membership_moves_leave_tombstones() {
        let s = Store::open(":memory:").unwrap();
        let c = &s.conn;
        ensure_account(c, "a", "u1").unwrap();
        let seq = next_seq(c).unwrap();
        let e = json!({"id": "m1", "threadId": "t1", "subject": "Hi", "receivedAt": "2026-11-20T10:00:00Z",
                       "from": [{"name": "Ann", "email": "ann@example.org"}], "keywords": {"$seen": true}, "mailboxIds": {"inbox": true}});
        upsert_email(c, "a", &e, seq).unwrap();
        assert_eq!(local_count(c, "a", "inbox").unwrap(), 1);
        let seq2 = next_seq(c).unwrap();
        let moved = json!({"id": "m1", "receivedAt": "2026-11-20T10:00:00Z", "mailboxIds": {"archive": true}});
        upsert_email(c, "a", &moved, seq2).unwrap();
        assert_eq!(local_count(c, "a", "inbox").unwrap(), 0);
        assert_eq!(local_count(c, "a", "archive").unwrap(), 1);
        let t: i64 = c
            .query_row("SELECT seq FROM tombstones WHERE account='a' AND mailbox_id='inbox' AND email_id='m1'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(t, seq2);
        delete_email(c, "a", "m1", next_seq(c).unwrap()).unwrap();
        let n: i64 = c.query_row("SELECT COUNT(*) FROM messages", [], |r| r.get(0)).unwrap();
        assert_eq!(n, 0);
        let f: i64 = c.query_row("SELECT COUNT(*) FROM messages_fts", [], |r| r.get(0)).unwrap();
        assert_eq!(f, 0);
    }
}
