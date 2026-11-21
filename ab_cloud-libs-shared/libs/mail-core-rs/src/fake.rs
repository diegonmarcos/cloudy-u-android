//! A fake JMAP server for the tests: mailboxes, emails, a change log per type, and the five methods the engine calls
//! (Mailbox/get, Mailbox/changes, Email/get, Email/changes, Email/query), with the server limits a real one enforces
//! (maxObjectsInGet -> `requestTooLarge`, a query page cap) and two ways to inject a gap: a change the log never records
//! (`silent`, what a server that omits a delegated account's changes does) and a forgotten history (`forget_history`,
//! `cannotCalculateChanges`).

use std::cell::RefCell;
use std::collections::{BTreeMap, BTreeSet};

use serde_json::{json, Value};

use crate::jmap::Transport;

#[derive(Debug, Clone)]
pub struct FMailbox {
    pub id: String,
    pub name: String,
    pub role: Option<String>,
    pub sort_order: i64,
}

#[derive(Debug, Clone)]
pub struct FEmail {
    pub id: String,
    pub thread_id: String,
    pub subject: String,
    pub from_name: String,
    pub from_email: String,
    pub received_ms: i64,
    pub mailbox_ids: BTreeSet<String>,
    pub keywords: BTreeMap<String, bool>,
    pub size: i64,
}

#[derive(Debug, Clone, Copy, PartialEq)]
enum Kind {
    Created,
    Updated,
    Destroyed,
}

#[derive(Debug, Default)]
pub struct Fake {
    pub mailboxes: BTreeMap<String, FMailbox>,
    pub emails: BTreeMap<String, FEmail>,
    pub state: u64,
    email_log: Vec<(u64, String, Kind)>,
    mailbox_log: Vec<(u64, String, Kind)>,
    /// A sinceState older than this answers `cannotCalculateChanges`.
    pub min_state: u64,
    pub max_get: usize,
    pub max_query: usize,
    /// When true, email mutations are not written to the Email change log (the mailbox counters still move).
    pub silent: bool,
    pub requests: usize,
    /// Raw RFC 5322 messages by email id (the blob `b-<id>`), served by `Transport::download`.
    pub raw: BTreeMap<String, Vec<u8>>,
    /// Blob downloads served.
    pub downloads: usize,
}

pub struct FakeJmap {
    pub inner: RefCell<Fake>,
}

/// Epoch milliseconds to RFC 3339 UTC (`2026-11-20T10:00:00Z`).
pub fn iso(ms: i64) -> String {
    let secs = ms.div_euclid(1000);
    let days = secs.div_euclid(86400);
    let rem = secs.rem_euclid(86400);
    // Civil from days (Howard Hinnant).
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{:04}-{:02}-{:02}T{:02}:{:02}:{:02}Z", y, m, d, rem / 3600, (rem % 3600) / 60, rem % 60)
}

fn st(n: u64) -> String {
    format!("s{n}")
}

fn parse_state(s: &str) -> Option<u64> {
    s.strip_prefix('s')?.parse().ok()
}

impl FakeJmap {
    pub fn new() -> Self {
        FakeJmap { inner: RefCell::new(Fake { max_get: 500, max_query: 1000, state: 1, ..Default::default() }) }
    }

    pub fn with<R>(&self, f: impl FnOnce(&mut Fake) -> R) -> R {
        f(&mut self.inner.borrow_mut())
    }
}

impl Fake {
    fn bump(&mut self) -> u64 {
        self.state += 1;
        self.state
    }

    fn log_email(&mut self, id: &str, k: Kind) {
        let s = self.bump();
        if !self.silent {
            self.email_log.push((s, id.to_string(), k));
        }
    }

    fn touch_mailboxes(&mut self, ids: &[String]) {
        let s = self.state;
        for id in ids {
            self.mailbox_log.push((s, id.clone(), Kind::Updated));
        }
    }

    pub fn add_mailbox(&mut self, id: &str, name: &str, role: Option<&str>, sort_order: i64) {
        let s = self.bump();
        self.mailboxes.insert(id.into(), FMailbox { id: id.into(), name: name.into(), role: role.map(str::to_string), sort_order });
        self.mailbox_log.push((s, id.into(), Kind::Created));
    }

    pub fn destroy_mailbox(&mut self, id: &str) {
        let s = self.bump();
        self.mailboxes.remove(id);
        self.mailbox_log.push((s, id.into(), Kind::Destroyed));
    }

    #[allow(clippy::too_many_arguments)]
    pub fn add_email(&mut self, id: &str, mailboxes: &[&str], received_ms: i64, subject: &str, from: &str, thread: &str, seen: bool) {
        let mut keywords = BTreeMap::new();
        if seen {
            keywords.insert("$seen".to_string(), true);
        }
        let name = from.split('@').next().unwrap_or("").to_string();
        self.emails.insert(
            id.into(),
            FEmail {
                id: id.into(),
                thread_id: thread.into(),
                subject: subject.into(),
                from_name: name,
                from_email: from.into(),
                received_ms,
                mailbox_ids: mailboxes.iter().map(|s| s.to_string()).collect(),
                keywords,
                size: 1000 + subject.len() as i64,
            },
        );
        self.log_email(id, Kind::Created);
        self.touch_mailboxes(&mailboxes.iter().map(|s| s.to_string()).collect::<Vec<_>>());
    }

    pub fn set_keyword(&mut self, id: &str, k: &str, on: bool) {
        let boxes: Vec<String> = {
            let e = self.emails.get_mut(id).expect("no such email");
            if on {
                e.keywords.insert(k.into(), true);
            } else {
                e.keywords.remove(k);
            }
            e.mailbox_ids.iter().cloned().collect()
        };
        self.log_email(id, Kind::Updated);
        self.touch_mailboxes(&boxes);
    }

    pub fn move_email(&mut self, id: &str, from: &str, to: &str) {
        {
            let e = self.emails.get_mut(id).expect("no such email");
            e.mailbox_ids.remove(from);
            e.mailbox_ids.insert(to.into());
        }
        self.log_email(id, Kind::Updated);
        self.touch_mailboxes(&[from.to_string(), to.to_string()]);
    }

    pub fn destroy_email(&mut self, id: &str) {
        let e = self.emails.remove(id).expect("no such email");
        self.log_email(id, Kind::Destroyed);
        self.touch_mailboxes(&e.mailbox_ids.iter().cloned().collect::<Vec<_>>());
    }

    /// Every older state stops being answerable: the next */changes is `cannotCalculateChanges`.
    pub fn forget_history(&mut self) {
        self.min_state = self.state + 1;
        self.bump();
    }

    /// A mailbox's ids in the server's order: receivedAt newest first, id descending on a tie.
    pub fn query_ids(&self, mailbox: &str) -> Vec<String> {
        let mut v: Vec<&FEmail> = self.emails.values().filter(|e| e.mailbox_ids.contains(mailbox)).collect();
        v.sort_by(|a, b| b.received_ms.cmp(&a.received_ms).then(b.id.cmp(&a.id)));
        v.into_iter().map(|e| e.id.clone()).collect()
    }

    fn email_json(&self, e: &FEmail, props: Option<&[String]>, bodies: bool) -> Value {
        let boxes: serde_json::Map<String, Value> = e.mailbox_ids.iter().map(|m| (m.clone(), Value::Bool(true))).collect();
        let mut o = json!({
            "id": e.id,
            "blobId": format!("b-{}", e.id),
            "threadId": e.thread_id,
            "mailboxIds": boxes,
            "keywords": e.keywords,
            "size": e.size,
            "receivedAt": iso(e.received_ms),
            "from": [{ "name": e.from_name, "email": e.from_email }],
            "to": [{ "name": "Me", "email": "me@example.org" }],
            "cc": [],
            "bcc": [],
            "replyTo": [],
            "subject": e.subject,
            "preview": format!("Preview of {}", e.subject),
            "hasAttachment": false,
            "attachments": [],
            "messageId": [format!("<{}@example.org>", e.id)],
        });
        if bodies {
            o["textBody"] = json!([{ "partId": "1", "type": "text/plain" }]);
            o["htmlBody"] = json!([{ "partId": "2", "type": "text/html" }]);
            o["bodyValues"] = json!({
                "1": { "value": format!("Body of {}", e.id), "isTruncated": false },
                "2": { "value": format!("<p>Body of {}</p>", e.id), "isTruncated": false },
            });
        }
        if let Some(p) = props {
            let keep: BTreeSet<&str> = p.iter().map(String::as_str).chain(std::iter::once("id")).collect();
            if let Value::Object(m) = &mut o {
                m.retain(|k, _| keep.contains(k.as_str()));
            }
        }
        o
    }

    fn mailbox_json(&self, m: &FMailbox) -> Value {
        let members: Vec<&FEmail> = self.emails.values().filter(|e| e.mailbox_ids.contains(&m.id)).collect();
        let unread = members.iter().filter(|e| !e.keywords.get("$seen").copied().unwrap_or(false)).count();
        let threads: BTreeSet<&String> = members.iter().map(|e| &e.thread_id).collect();
        json!({
            "id": m.id, "name": m.name, "role": m.role, "parentId": null, "sortOrder": m.sort_order,
            "totalEmails": members.len(), "unreadEmails": unread, "totalThreads": threads.len(), "unreadThreads": 0,
            "myRights": { "mayReadItems": true }, "isSubscribed": true,
        })
    }

    fn changes(&self, log: &[(u64, String, Kind)], since: &str, max: usize) -> Result<Value, Value> {
        let since_n = parse_state(since).ok_or_else(|| json!({"type": "invalidArguments", "description": "bad state"}))?;
        if since_n < self.min_state {
            return Err(json!({"type": "cannotCalculateChanges", "description": "history forgotten"}));
        }
        let after: Vec<&(u64, String, Kind)> = log.iter().filter(|(s, _, _)| *s > since_n).collect();
        let mut take = after.len().min(max.max(1));
        // Never split the entries of one state across two pages: the next page starts AFTER the state answered here.
        while take > 0 && take < after.len() && after[take].0 == after[take - 1].0 {
            take += 1;
        }
        let mut created: Vec<String> = vec![];
        let mut updated: Vec<String> = vec![];
        let mut destroyed: Vec<String> = vec![];
        for (_, id, k) in &after[..take] {
            match k {
                Kind::Created => {
                    if !created.contains(id) {
                        created.push(id.clone());
                    }
                }
                Kind::Updated => {
                    if !created.contains(id) && !updated.contains(id) {
                        updated.push(id.clone());
                    }
                }
                Kind::Destroyed => {
                    if let Some(i) = created.iter().position(|x| x == id) {
                        created.remove(i);
                    } else {
                        updated.retain(|x| x != id);
                        destroyed.push(id.clone());
                    }
                }
            }
        }
        let has_more = take < after.len();
        let new_state = if has_more { after[take - 1].0 } else { self.state };
        Ok(json!({
            "accountId": "u1", "oldState": since, "newState": st(new_state), "hasMoreChanges": has_more,
            "created": created, "updated": updated, "destroyed": destroyed,
        }))
    }

    fn handle(&mut self, method: &str, a: &Value) -> Result<Value, Value> {
        self.requests += 1;
        let ids: Option<Vec<String>> =
            a.get("ids").and_then(Value::as_array).map(|v| v.iter().filter_map(|x| x.as_str().map(str::to_string)).collect());
        match method {
            "Mailbox/get" => {
                let (list, not_found): (Vec<Value>, Vec<String>) = match ids {
                    None => (self.mailboxes.values().map(|m| self.mailbox_json(m)).collect(), vec![]),
                    Some(ids) => {
                        let list = ids.iter().filter_map(|i| self.mailboxes.get(i)).map(|m| self.mailbox_json(m)).collect();
                        let nf = ids.iter().filter(|i| !self.mailboxes.contains_key(*i)).cloned().collect();
                        (list, nf)
                    }
                };
                Ok(json!({ "accountId": "u1", "state": st(self.state), "list": list, "notFound": not_found }))
            }
            "Mailbox/changes" => self.changes(
                &self.mailbox_log,
                a.get("sinceState").and_then(Value::as_str).unwrap_or(""),
                a.get("maxChanges").and_then(Value::as_u64).unwrap_or(500) as usize,
            ),
            "Email/changes" => self.changes(
                &self.email_log,
                a.get("sinceState").and_then(Value::as_str).unwrap_or(""),
                a.get("maxChanges").and_then(Value::as_u64).unwrap_or(500) as usize,
            ),
            "Email/get" => {
                let ids = ids.ok_or_else(|| json!({"type": "requestTooLarge", "description": "ids null on Email/get"}))?;
                if ids.len() > self.max_get {
                    return Err(json!({"type": "requestTooLarge", "description": format!("{} > {}", ids.len(), self.max_get)}));
                }
                let props: Option<Vec<String>> = a
                    .get("properties")
                    .and_then(Value::as_array)
                    .map(|v| v.iter().filter_map(|x| x.as_str().map(str::to_string)).collect());
                let bodies = a.get("fetchTextBodyValues").and_then(Value::as_bool).unwrap_or(false);
                let list: Vec<Value> =
                    ids.iter().filter_map(|i| self.emails.get(i)).map(|e| self.email_json(e, props.as_deref(), bodies)).collect();
                let nf: Vec<String> = ids.iter().filter(|i| !self.emails.contains_key(*i)).cloned().collect();
                Ok(json!({ "accountId": "u1", "state": st(self.state), "list": list, "notFound": nf }))
            }
            "Email/query" => {
                let mb = a.pointer("/filter/inMailbox").and_then(Value::as_str).unwrap_or("");
                let position = a.get("position").and_then(Value::as_u64).unwrap_or(0) as usize;
                let limit = (a.get("limit").and_then(Value::as_u64).unwrap_or(256) as usize).min(self.max_query);
                let all = self.query_ids(mb);
                let page: Vec<String> = all.iter().skip(position).take(limit).cloned().collect();
                Ok(json!({
                    "accountId": "u1", "queryState": format!("q{}", self.state), "canCalculateChanges": false,
                    "position": position, "ids": page, "total": all.len(),
                }))
            }
            _ => Err(json!({"type": "unknownMethod"})),
        }
    }
}

impl Transport for FakeJmap {
    fn download(&self, blob_id: &str) -> Result<Vec<u8>, String> {
        let mut f = self.inner.borrow_mut();
        let id = blob_id.strip_prefix("b-").unwrap_or(blob_id).to_string();
        match f.raw.get(&id).cloned() {
            Some(b) => {
                f.downloads += 1;
                Ok(b)
            }
            None => Err(format!("fake: no blob {blob_id}")),
        }
    }

    fn post(&self, body: &str) -> Result<String, String> {
        let req: Value = serde_json::from_str(body).map_err(|e| format!("fake: bad request {e}"))?;
        let mut f = self.inner.borrow_mut();
        let mut out = vec![];
        for call in req.get("methodCalls").and_then(Value::as_array).ok_or("fake: no methodCalls")? {
            let name = call.get(0).and_then(Value::as_str).unwrap_or("");
            let args = call.get(1).cloned().unwrap_or(Value::Null);
            let tag = call.get(2).cloned().unwrap_or(json!("c0"));
            match f.handle(name, &args) {
                Ok(v) => out.push(json!([name, v, tag])),
                Err(e) => out.push(json!(["error", e, tag])),
            }
        }
        Ok(json!({ "methodResponses": out, "sessionState": "x" }).to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iso_round_trips_through_the_store_parser() {
        for ms in [0i64, 1_795_168_800_000, 1_700_000_000_000, 951_782_400_000] {
            assert_eq!(crate::store::epoch_ms(&iso(ms)), ms, "{}", iso(ms));
        }
        assert_eq!(iso(1_795_168_800_000), "2026-11-20T10:00:00Z");
    }
}
