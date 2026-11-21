//! A minimal JMAP client (RFC 8620 core, RFC 8621 mail): the six methods the sync engine needs, over a [Transport].
//!
//! The transport carries ONE request body to the session's apiUrl and answers the response body. On Android it is the app's
//! own HTTP client (JmapClient's OkHttp: mesh first then public with the fleet bearer, the gateway cookie, the phone's trust
//! store, the 429 / limit retry), so the core adds no second network stack and no second TLS trust decision. In tests it is
//! the fake server of `fake.rs`.

use serde_json::{json, Value};

pub const USING: [&str; 2] = ["urn:ietf:params:jmap:core", "urn:ietf:params:jmap:mail"];

/// One POST to the JMAP apiUrl: request JSON in, response JSON out. An Err is a transport failure (no answer, HTTP error).
pub trait Transport {
    fn post(&self, body: &str) -> Result<String, String>;

    /// GET the raw bytes of the blob [blob_id] of the account (a message's RFC 5322 source: the JMAP download URL). On Android
    /// the app's own HTTP client again (same routing, bearer, cookie and trust store as [Transport::post]).
    fn download(&self, _blob_id: &str) -> Result<Vec<u8>, String> {
        Err("this transport cannot download blobs".to_string())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum JmapError {
    /// The request did not get an answer (network, HTTP status, the Kotlin transport's own error).
    Transport(String),
    /// A method-level error (`["error", {"type": ...}]`), e.g. `cannotCalculateChanges`, `requestTooLarge`.
    Method { kind: String, description: String },
    /// An answer that is not JMAP.
    Protocol(String),
}

impl JmapError {
    pub fn is_kind(&self, k: &str) -> bool {
        matches!(self, JmapError::Method { kind, .. } if kind == k)
    }
}

impl std::fmt::Display for JmapError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            JmapError::Transport(e) => write!(f, "transport: {e}"),
            JmapError::Method { kind, description } => write!(f, "JMAP {kind}: {description}"),
            JmapError::Protocol(e) => write!(f, "protocol: {e}"),
        }
    }
}

pub type JResult<T> = Result<T, JmapError>;

/// What a `Foo/get` answered.
#[derive(Debug, Default, Clone)]
pub struct GetResult {
    pub state: String,
    pub list: Vec<Value>,
    pub not_found: Vec<String>,
}

/// What a `Foo/changes` answered.
#[derive(Debug, Default, Clone)]
pub struct Changes {
    pub old_state: String,
    pub new_state: String,
    pub has_more: bool,
    pub created: Vec<String>,
    pub updated: Vec<String>,
    pub destroyed: Vec<String>,
}

/// What an `Email/query` answered (ids only).
#[derive(Debug, Default, Clone)]
pub struct QueryResult {
    pub query_state: String,
    pub ids: Vec<String>,
    pub position: usize,
    pub total: Option<usize>,
}

pub struct Jmap<'a> {
    pub transport: &'a dyn Transport,
    pub account_id: String,
    /// Requests sent, for the sync report and the performance tests.
    pub requests: std::cell::Cell<usize>,
}

fn strs(v: &Value) -> Vec<String> {
    v.as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(str::to_string)).collect()).unwrap_or_default()
}

impl<'a> Jmap<'a> {
    pub fn new(transport: &'a dyn Transport, account_id: &str) -> Self {
        Jmap { transport, account_id: account_id.to_string(), requests: std::cell::Cell::new(0) }
    }

    /// One request carrying ONE method call; answers that call's arguments.
    pub fn call(&self, method: &str, args: Value) -> JResult<Value> {
        let req = json!({ "using": USING, "methodCalls": [[method, args, "c0"]] });
        self.requests.set(self.requests.get() + 1);
        let body = self.transport.post(&req.to_string()).map_err(JmapError::Transport)?;
        let root: Value = serde_json::from_str(&body).map_err(|e| JmapError::Protocol(format!("not JSON: {e}")))?;
        let responses = root
            .get("methodResponses")
            .and_then(Value::as_array)
            .ok_or_else(|| JmapError::Protocol("no methodResponses".into()))?;
        let first = responses.first().and_then(Value::as_array).ok_or_else(|| JmapError::Protocol("empty methodResponses".into()))?;
        let name = first.first().and_then(Value::as_str).unwrap_or("");
        let args = first.get(1).cloned().unwrap_or(Value::Null);
        if name == "error" {
            return Err(JmapError::Method {
                kind: args.get("type").and_then(Value::as_str).unwrap_or("unknown").to_string(),
                description: args.get("description").and_then(Value::as_str).unwrap_or("").to_string(),
            });
        }
        if name != method {
            return Err(JmapError::Protocol(format!("asked {method}, answered {name}")));
        }
        Ok(args)
    }

    fn get(&self, method: &str, ids: Option<&[String]>, properties: Option<&[String]>, extra: Option<Value>) -> JResult<GetResult> {
        let mut args = json!({ "accountId": self.account_id, "ids": ids });
        if let Some(p) = properties {
            args["properties"] = json!(p);
        }
        if let Some(Value::Object(m)) = extra {
            for (k, v) in m {
                args[k] = v;
            }
        }
        let a = self.call(method, args)?;
        Ok(GetResult {
            state: a.get("state").and_then(Value::as_str).unwrap_or("").to_string(),
            list: a.get("list").and_then(Value::as_array).cloned().unwrap_or_default(),
            not_found: strs(a.get("notFound").unwrap_or(&Value::Null)),
        })
    }

    fn changes(&self, method: &str, since: &str, max: usize) -> JResult<Changes> {
        let a = self.call(method, json!({ "accountId": self.account_id, "sinceState": since, "maxChanges": max }))?;
        Ok(Changes {
            old_state: a.get("oldState").and_then(Value::as_str).unwrap_or("").to_string(),
            new_state: a.get("newState").and_then(Value::as_str).unwrap_or("").to_string(),
            has_more: a.get("hasMoreChanges").and_then(Value::as_bool).unwrap_or(false),
            created: strs(a.get("created").unwrap_or(&Value::Null)),
            updated: strs(a.get("updated").unwrap_or(&Value::Null)),
            destroyed: strs(a.get("destroyed").unwrap_or(&Value::Null)),
        })
    }

    /// Every mailbox (ids None) or the named ones.
    pub fn mailbox_get(&self, ids: Option<&[String]>) -> JResult<GetResult> {
        self.get("Mailbox/get", ids, None, None)
    }

    pub fn mailbox_changes(&self, since: &str, max: usize) -> JResult<Changes> {
        self.changes("Mailbox/changes", since, max)
    }

    pub fn email_get(&self, ids: &[String], properties: &[String]) -> JResult<GetResult> {
        self.get("Email/get", Some(ids), Some(properties), None)
    }

    /// The account's current Email state, with no object (`ids: []`).
    pub fn email_state(&self) -> JResult<String> {
        let none: Vec<String> = Vec::new();
        let props = vec!["id".to_string()];
        Ok(self.get("Email/get", Some(none.as_slice()), Some(props.as_slice()), None)?.state)
    }

    /// Bodies: the text and HTML part values, fetched with the part list (RFC 8621 §4.2).
    pub fn email_get_bodies(&self, ids: &[String], max_bytes: usize) -> JResult<GetResult> {
        let props: Vec<String> =
            ["id", "blobId", "size", "textBody", "htmlBody", "attachments", "bodyValues"].iter().map(|s| s.to_string()).collect();
        self.get(
            "Email/get",
            Some(ids),
            Some(props.as_slice()),
            Some(json!({ "fetchTextBodyValues": true, "fetchHTMLBodyValues": true, "maxBodyValueBytes": max_bytes })),
        )
    }

    pub fn email_changes(&self, since: &str, max: usize) -> JResult<Changes> {
        self.changes("Email/changes", since, max)
    }

    /// One page of a mailbox's ids, the server's own order: receivedAt, newest first.
    pub fn email_query(&self, mailbox_id: &str, position: usize, limit: usize) -> JResult<QueryResult> {
        let a = self.call(
            "Email/query",
            json!({
                "accountId": self.account_id,
                "filter": { "inMailbox": mailbox_id },
                "sort": [{ "property": "receivedAt", "isAscending": false }],
                "collapseThreads": false,
                "position": position,
                "limit": limit,
                "calculateTotal": true,
            }),
        )?;
        Ok(QueryResult {
            query_state: a.get("queryState").and_then(Value::as_str).unwrap_or("").to_string(),
            ids: strs(a.get("ids").unwrap_or(&Value::Null)),
            position: a.get("position").and_then(Value::as_u64).unwrap_or(position as u64) as usize,
            total: a.get("total").and_then(Value::as_u64).map(|t| t as usize),
        })
    }
}
