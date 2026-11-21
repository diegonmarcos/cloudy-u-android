//! The session / tab snapshot store (SQLite, bundled). One row per tab key.

use rusqlite::{params, Connection, OptionalExtension};
use serde_json::{json, Value};

pub struct SessionStore {
    conn: Connection,
}

fn e<T: std::fmt::Display>(x: T) -> String {
    x.to_string()
}

impl SessionStore {
    /// Open (creating) the store at `path`; `:memory:` is allowed (tests).
    pub fn open(path: &str) -> Result<Self, String> {
        if path != ":memory:" {
            if let Some(dir) = std::path::Path::new(path).parent() {
                let _ = std::fs::create_dir_all(dir);
            }
        }
        let conn = Connection::open(path).map_err(e)?;
        let _ = conn.pragma_update(None, "journal_mode", "WAL");
        let _ = conn.pragma_update(None, "synchronous", "NORMAL");
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS tab_snapshot(
                tab_key    TEXT PRIMARY KEY,
                url        TEXT NOT NULL,
                title      TEXT NOT NULL DEFAULT '',
                scroll_x   INTEGER NOT NULL DEFAULT 0,
                scroll_y   INTEGER NOT NULL DEFAULT 0,
                favicon    TEXT NOT NULL DEFAULT '',
                history    TEXT NOT NULL DEFAULT '[]',
                hidx       INTEGER NOT NULL DEFAULT 0,
                state_file TEXT NOT NULL DEFAULT '',
                updated_at INTEGER NOT NULL DEFAULT 0
            );
            CREATE INDEX IF NOT EXISTS tab_snapshot_updated ON tab_snapshot(updated_at DESC);",
        )
        .map_err(e)?;
        Ok(SessionStore { conn })
    }

    /// Store a snapshot: `{key,url,title,scrollX,scrollY,favicon,history:[url],index,stateFile,updatedAt,private}`.
    /// A private tab is refused: it keeps nothing.
    pub fn put(&self, snap: &Value) -> Result<(), String> {
        if snap.get("private").and_then(Value::as_bool).unwrap_or(false) {
            return Err("private tabs are never stored".into());
        }
        let key = snap.get("key").and_then(Value::as_str).filter(|k| !k.is_empty()).ok_or("snapshot has no key")?;
        let url = snap.get("url").and_then(Value::as_str).unwrap_or("");
        if url.is_empty() {
            return Err("snapshot has no url".into());
        }
        let history = snap.get("history").cloned().unwrap_or_else(|| json!([]));
        self.conn
            .execute(
                "INSERT INTO tab_snapshot(tab_key,url,title,scroll_x,scroll_y,favicon,history,hidx,state_file,updated_at)
                 VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10)
                 ON CONFLICT(tab_key) DO UPDATE SET url=?2,title=?3,scroll_x=?4,scroll_y=?5,favicon=?6,history=?7,hidx=?8,state_file=?9,updated_at=?10",
                params![
                    key,
                    url,
                    snap.get("title").and_then(Value::as_str).unwrap_or(""),
                    snap.get("scrollX").and_then(Value::as_i64).unwrap_or(0),
                    snap.get("scrollY").and_then(Value::as_i64).unwrap_or(0),
                    snap.get("favicon").and_then(Value::as_str).unwrap_or(""),
                    history.to_string(),
                    snap.get("index").and_then(Value::as_i64).unwrap_or(0),
                    snap.get("stateFile").and_then(Value::as_str).unwrap_or(""),
                    snap.get("updatedAt").and_then(Value::as_i64).unwrap_or(0),
                ],
            )
            .map_err(e)?;
        Ok(())
    }

    fn row(r: &rusqlite::Row) -> rusqlite::Result<Value> {
        let history: String = r.get(6)?;
        Ok(json!({
            "key": r.get::<_, String>(0)?,
            "url": r.get::<_, String>(1)?,
            "title": r.get::<_, String>(2)?,
            "scrollX": r.get::<_, i64>(3)?,
            "scrollY": r.get::<_, i64>(4)?,
            "favicon": r.get::<_, String>(5)?,
            "history": serde_json::from_str::<Value>(&history).unwrap_or_else(|_| json!([])),
            "index": r.get::<_, i64>(7)?,
            "stateFile": r.get::<_, String>(8)?,
            "updatedAt": r.get::<_, i64>(9)?,
        }))
    }

    const COLS: &'static str = "tab_key,url,title,scroll_x,scroll_y,favicon,history,hidx,state_file,updated_at";

    pub fn get(&self, key: &str) -> Result<Option<Value>, String> {
        self.conn
            .query_row(&format!("SELECT {} FROM tab_snapshot WHERE tab_key=?1", Self::COLS), params![key], Self::row)
            .optional()
            .map_err(e)
    }

    /// Every snapshot, newest first.
    pub fn list(&self) -> Result<Vec<Value>, String> {
        let mut st = self.conn.prepare(&format!("SELECT {} FROM tab_snapshot ORDER BY updated_at DESC, tab_key", Self::COLS)).map_err(e)?;
        let rows = st.query_map([], Self::row).map_err(e)?;
        rows.collect::<Result<Vec<_>, _>>().map_err(e)
    }

    pub fn delete(&self, key: &str) -> Result<bool, String> {
        Ok(self.conn.execute("DELETE FROM tab_snapshot WHERE tab_key=?1", params![key]).map_err(e)? > 0)
    }

    /// Drop every snapshot whose key is not in `keep` (tabs that no longer exist). Returns how many went.
    pub fn prune(&self, keep: &[String]) -> Result<usize, String> {
        let live: std::collections::HashSet<&str> = keep.iter().map(String::as_str).collect();
        let all: Vec<String> = {
            let mut st = self.conn.prepare("SELECT tab_key FROM tab_snapshot").map_err(e)?;
            let rows = st.query_map([], |r| r.get::<_, String>(0)).map_err(e)?;
            rows.collect::<Result<Vec<_>, _>>().map_err(e)?
        };
        let mut n = 0;
        for k in all.iter().filter(|k| !live.contains(k.as_str())) {
            n += self.conn.execute("DELETE FROM tab_snapshot WHERE tab_key=?1", params![k]).map_err(e)?;
        }
        Ok(n)
    }

    pub fn count(&self) -> Result<i64, String> {
        self.conn.query_row("SELECT COUNT(*) FROM tab_snapshot", [], |r| r.get(0)).map_err(e)
    }
}
