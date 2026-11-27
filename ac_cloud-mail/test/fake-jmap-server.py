#!/usr/bin/env python3
"""A tiny fake JMAP server for the Cloud Mail offline emulator proof (#1837, workflow test-mail-core-offline-emulator.yml).

Python standard library only. It runs on the CI runner and the emulator reaches it at http://10.0.2.2:<port> (the emulator's host
loopback). It holds a few seeded messages in several mailboxes and answers exactly what the app's two JMAP clients ask:

  GET  /.well-known/jmap                         the session (apiUrl and downloadUrl point back at the Host the request used)
  POST /jmap/api                                 Mailbox/get, Mailbox/changes, Email/query, Email/get, Email/changes, Email/queryChanges
                                                 (cannotCalculateChanges: the client then queries again), with result references (#ids)
  GET  /jmap/download/<account>/<blob>/<name>    the raw RFC 5322 message of a blob (message/rfc822)
  GET  /__stats                                  request counters (what the proof reads to know the app really synced)

The seed: an Inbox with a Latin-1 (ISO-8859-1) "España" message, an HTML newsletter with a <style> block and a table, plain
messages, one message in the Inbox AND the "Receipts" label (the one-row-per-message filing case), a label-only message, and
messages in Archive and Sent. No real address, host or credential appears here: the account is the placeholder one the test
variant seeds, and any Authorization header is accepted.

  python3 fake-jmap-server.py [--port 8099] [--selftest]
"""
import json
import re
import os
import sys
import urllib.parse
from email.utils import format_datetime
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

ACCOUNT = "u1"
STATE = "s1"
BASE = datetime(2026, 11, 20, 10, 0, 0, tzinfo=timezone.utc)

MAILBOXES = [
    ("mb-inbox", "Inbox", "inbox", 0),
    ("mb-drafts", "Drafts", "drafts", 1),
    ("mb-sent", "Sent", "sent", 2),
    ("mb-archive", "Archive", "archive", 3),
    ("mb-junk", "Spam", "junk", 4),
    ("mb-trash", "Trash", "trash", 5),
    ("mb-receipts", "Receipts", None, 10),
]

NEWSLETTER_HTML = """<html><head><style>
.wrap { width: 600px; margin: 0 auto; font-family: Arial, sans-serif; }
.hero { background: #1a73e8; color: #ffffff; padding: 18px; }
.story td { padding: 8px; border-bottom: 1px solid #dddddd; }
@media (max-width: 480px) { .wrap { width: 100%; } }
</style></head><body>
<div class="wrap"><div class="hero"><h1>Weekly Digest 42</h1></div>
<table class="story" width="100%"><tr><td><b>Story one</b><br>Rust is now inside the mail app.</td></tr>
<tr><td><b>Story two</b><br>The reader keeps the newsletter's own layout.</td></tr></table>
<p>You receive this digest because you subscribed. <a href="https://example.org/unsubscribe">Unsubscribe</a></p></div>
</body></html>"""


def qp_header(text, charset="iso-8859-1"):
    """An RFC 2047 Q-encoded word of [text]."""
    out = []
    for b in text.encode(charset):
        c = chr(b)
        out.append(c if (c.isalnum() and b < 128) else ("_" if c == " " else "=%02X" % b))
    return "=?%s?Q?%s?=" % (charset.upper(), "".join(out))


def raw_message(msg):
    """The RFC 5322 bytes of a seeded message dict."""
    head = [
        "From: %s <%s>" % (qp_header(msg["from_name"]) if not msg["from_name"].isascii() else msg["from_name"], msg["from_email"]),
        "To: Me <me@example.org>",
        "Subject: %s" % (qp_header(msg["subject"]) if not msg["subject"].isascii() else msg["subject"]),
        "Date: %s" % format_datetime(msg["received"]),
        "Message-ID: <%s@fake-jmap.test>" % msg["id"],
        "MIME-Version: 1.0",
    ]
    if msg.get("html"):
        head += ['Content-Type: text/html; charset=utf-8', "Content-Transfer-Encoding: 8bit"]
        body = msg["html"].encode("utf-8")
    elif msg.get("latin1"):
        head += ["Content-Type: text/plain; charset=iso-8859-1", "Content-Transfer-Encoding: 8bit"]
        body = msg["text"].encode("iso-8859-1")
    else:
        head += ["Content-Type: text/plain; charset=utf-8", "Content-Transfer-Encoding: 8bit"]
        body = msg["text"].encode("utf-8")
    return ("\r\n".join(head) + "\r\n\r\n").encode("ascii") + body


def seed():
    out = []

    def add(id, boxes, hours, subject, frm_name, frm_email, text="", html=None, latin1=False, seen=False):
        out.append(dict(
            id=id, boxes=boxes, received=BASE - timedelta(hours=hours), subject=subject, from_name=frm_name,
            from_email=frm_email, text=text, html=html, latin1=latin1, seen=seen,
        ))

    add("m-espana", ["mb-inbox"], 1, "España: reunión de equipo", "María García", "maria@example.es",
        text="Hola equipo,\n\nBienvenidos a España. Mañana nos vemos en Málaga a las diez.\n\nUn abrazo,\nMaría\n", latin1=True)
    add("m-news", ["mb-inbox"], 2, "Weekly Digest 42 - Newsletter", "The Digest", "news@digest.example",
        text="Weekly Digest 42. Story one. Story two.", html=NEWSLETTER_HTML)
    add("m-receipt", ["mb-inbox", "mb-receipts"], 3, "Receipt 1001 from Shop", "Shop", "billing@shop.example",
        text="Thank you for your order 1001. Total 12.50.\n")
    for i in range(1, 4):
        add("m-plain%d" % i, ["mb-inbox"], 3 + i, "Plain hello %d" % i, "Pat %d" % i, "pat%d@example.net" % i,
            text="Hello number %d, nothing special.\n" % i, seen=True)
    add("m-warranty", ["mb-receipts"], 8, "Label only: Warranty card", "Care Desk", "care@warranty.example",
        text="Your warranty card is attached to this label.\n")
    add("m-tax", ["mb-archive"], 9, "Archived: Tax documents 2025", "Tax Office", "tax@gov.example",
        text="The documents for 2025 are archived here.\n", seen=True)
    add("m-sent", ["mb-sent"], 10, "Sent: re lunch on Friday", "Me", "me@example.org",
        text="See you on Friday at noon.\n", seen=True)
    # #1952: FAKE_JMAP_BULK=N adds N older Inbox messages, so a walk can prove the list of a folder larger than one page.
    for i in range(1, int(os.environ.get("FAKE_JMAP_BULK", "0")) + 1):
        add("m-bulk%d" % i, ["mb-inbox"], 20 + i, "Bulk message %d" % i, "Bulk %d" % i, "bulk@bulk%03d.example" % (i % 60),
            text="Bulk body %d.\n" % i, seen=True)
    return out


MESSAGES = seed()
BY_ID = {m["id"]: m for m in MESSAGES}
STATS = {"requests": 0, "session": 0, "api": 0, "download": 0, "methods": {}}
# #1952: GET /__fail?code=502 makes every later answer that status (the hub out of memory); /__fail?code=0 heals it.
FAIL = {"code": 0}


def mailbox_json(box):
    id, name, role, order = box
    members = [m for m in MESSAGES if id in m["boxes"]]
    return {
        "id": id, "name": name, "role": role, "parentId": None, "sortOrder": order,
        "totalEmails": len(members), "unreadEmails": sum(1 for m in members if not m["seen"]),
        "totalThreads": len(members), "unreadThreads": sum(1 for m in members if not m["seen"]),
        "myRights": {"mayReadItems": True, "mayAddItems": True, "mayRemoveItems": True, "maySetSeen": True,
                     "maySetKeywords": True, "mayCreateChild": True, "mayRename": False, "mayDelete": False, "maySubmit": True},
        "isSubscribed": True,
    }


def iso(dt):
    return dt.strftime("%Y-%m-%dT%H:%M:%SZ")


def email_json(m):
    html = m.get("html")
    body = {"textBody": [{"partId": "1", "type": "text/plain", "blobId": "b-%s-1" % m["id"], "size": len(m["text"])}]}
    values = {"1": {"value": m["text"], "isTruncated": False, "isEncodingProblem": False}}
    if html:
        body["htmlBody"] = [{"partId": "2", "type": "text/html", "blobId": "b-%s-2" % m["id"], "size": len(html)}]
        values["2"] = {"value": html, "isTruncated": False, "isEncodingProblem": False}
    else:
        body["htmlBody"] = body["textBody"]
    keywords = {"$seen": True} if m["seen"] else {}
    return {
        "id": m["id"], "blobId": "b-" + m["id"], "threadId": "t-" + m["id"], "mailboxIds": {b: True for b in m["boxes"]},
        "keywords": keywords, "size": len(raw_message(m)), "receivedAt": iso(m["received"]),
        "from": [{"name": m["from_name"], "email": m["from_email"]}], "to": [{"name": "Me", "email": "me@example.org"}],
        "cc": [], "bcc": [], "replyTo": [], "subject": m["subject"],
        "preview": re.sub(r"\s+", " ", m["text"]).strip()[:120], "hasAttachment": False, "attachments": [],
        "messageId": ["%s@fake-jmap.test" % m["id"]], "inReplyTo": [], "references": [], "headers": [],
        "sentAt": iso(m["received"]), **body, "bodyValues": values,
    }


def query_ids(mailbox):
    rows = [m for m in MESSAGES if mailbox is None or mailbox in m["boxes"]]
    rows.sort(key=lambda m: (m["received"], m["id"]), reverse=True)
    return [m["id"] for m in rows]


def resolve(args, results):
    """JMAP result references: {"#ids": {"resultOf": tag, "name": ..., "path": "/ids"}}."""
    out = {}
    for k, v in args.items():
        if not k.startswith("#"):
            out[k] = v
            continue
        prior = next((r for r in results if r[2] == v.get("resultOf")), None)
        val = prior[1] if prior else {}
        for part in [p for p in v.get("path", "").split("/") if p]:
            if part == "*":
                continue
            val = val.get(part) if isinstance(val, dict) else val
        if v.get("path", "").endswith("/*/id") and isinstance(val, list):
            val = [x.get("id") if isinstance(x, dict) else x for x in prior[1].get("list", [])]
        out[k[1:]] = val
    return out


def handle(name, a):
    STATS["methods"][name] = STATS["methods"].get(name, 0) + 1
    ids = a.get("ids")
    if name == "Mailbox/get":
        boxes = [b for b in MAILBOXES if ids is None or b[0] in ids]
        return {"accountId": ACCOUNT, "state": STATE, "list": [mailbox_json(b) for b in boxes],
                "notFound": [i for i in (ids or []) if i not in {b[0] for b in MAILBOXES}]}
    if name in ("Mailbox/changes", "Email/changes"):
        since = a.get("sinceState", "")
        if since != STATE:
            raise KeyError("cannotCalculateChanges")
        return {"accountId": ACCOUNT, "oldState": since, "newState": STATE, "hasMoreChanges": False,
                "created": [], "updated": [], "destroyed": []}
    if name == "Email/get":
        if ids is None:
            raise KeyError("invalidArguments")
        props = a.get("properties")
        out = []
        for i in ids:
            m = BY_ID.get(i)
            if m:
                j = email_json(m)
                if props:
                    keep = set(props) | {"id"}
                    if a.get("fetchTextBodyValues") or a.get("fetchHTMLBodyValues") or a.get("fetchAllBodyValues"):
                        keep |= {"bodyValues", "textBody", "htmlBody"}
                    j = {k: v for k, v in j.items() if k in keep}
                out.append(j)
        return {"accountId": ACCOUNT, "state": STATE, "list": out, "notFound": [i for i in ids if i not in BY_ID]}
    if name == "Email/query":
        flt = a.get("filter") or {}
        all_ids = query_ids(flt.get("inMailbox"))
        position = int(a.get("position") or 0)
        limit = int(a.get("limit") or 256)
        page = all_ids[position:position + limit]
        return {"accountId": ACCOUNT, "queryState": "q1", "canCalculateChanges": False, "position": position,
                "ids": page, "total": len(all_ids)}
    if name == "Email/queryChanges":
        raise KeyError("cannotCalculateChanges")
    raise KeyError("unknownMethod")


def api(body, host):
    req = json.loads(body)
    results = []
    for call in req.get("methodCalls", []):
        name, args, tag = call[0], call[1], call[2]
        try:
            results.append([name, handle(name, resolve(args, results)), tag])
        except KeyError as e:
            results.append(["error", {"type": e.args[0]}, tag])
    return {"methodResponses": results, "sessionState": STATE}


def session(host):
    base = "http://%s" % host
    return {
        "capabilities": {
            "urn:ietf:params:jmap:core": {"maxObjectsInGet": 500, "maxObjectsInSet": 500, "maxCallsInRequest": 16,
                                          "maxConcurrentRequests": 4, "maxSizeRequest": 10000000},
            "urn:ietf:params:jmap:mail": {},
        },
        "accounts": {ACCOUNT: {"name": "walk@fake-jmap.test", "isPersonal": True, "isReadOnly": False,
                               "accountCapabilities": {"urn:ietf:params:jmap:mail": {}}}},
        "primaryAccounts": {"urn:ietf:params:jmap:mail": ACCOUNT},
        "username": "walk@fake-jmap.test",
        "apiUrl": base + "/jmap/api",
        "downloadUrl": base + "/jmap/download/{accountId}/{blobId}/{name}?accept={type}",
        "uploadUrl": base + "/jmap/upload/{accountId}/",
        "state": STATE,
    }


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    def send(self, code, payload, ctype="application/json"):
        data = payload if isinstance(payload, bytes) else json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def authorised(self):
        if self.headers.get("Authorization"):
            return True
        self.send(401, {"error": "no Authorization header"})
        return False

    def do_GET(self):
        STATS["requests"] += 1
        host = self.headers.get("Host", "10.0.2.2:8099")
        path = urllib.parse.urlparse(self.path).path
        if path == "/__stats":
            return self.send(200, STATS)
        if path == "/__fail":
            FAIL["code"] = int(urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query).get("code", ["0"])[0])
            return self.send(200, FAIL)
        if FAIL["code"]:
            STATS["failed"] = STATS.get("failed", 0) + 1
            return self.send(FAIL["code"], b"<html>502 Bad Gateway</html>", "text/html")
        if not self.authorised():
            return
        if path in ("/.well-known/jmap", "/jmap/session"):
            STATS["session"] += 1
            return self.send(200, session(host))
        m = re.match(r"^/jmap/download/[^/]+/([^/]+)/", path)
        if m:
            STATS["download"] += 1
            blob = urllib.parse.unquote(m.group(1))
            msg = BY_ID.get(blob[2:] if blob.startswith("b-") else blob)
            if msg:
                return self.send(200, raw_message(msg), "message/rfc822")
        self.send(404, {"error": "not found"})

    def do_POST(self):
        STATS["requests"] += 1
        n = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(n) if n else b"{}"
        if FAIL["code"]:
            STATS["failed"] = STATS.get("failed", 0) + 1
            return self.send(FAIL["code"], b"<html>502 Bad Gateway</html>", "text/html")
        if not self.authorised():
            return
        if urllib.parse.urlparse(self.path).path == "/jmap/api":
            STATS["api"] += 1
            return self.send(200, api(body, self.headers.get("Host", "")))
        self.send(404, {"error": "not found"})


def selftest():
    s = session("10.0.2.2:8099")
    assert s["apiUrl"] == "http://10.0.2.2:8099/jmap/api"
    r = api(json.dumps({"methodCalls": [
        ["Email/query", {"accountId": ACCOUNT, "filter": {"inMailbox": "mb-inbox"}, "limit": 3, "calculateTotal": True}, "q"],
        ["Email/get", {"accountId": ACCOUNT, "#ids": {"resultOf": "q", "name": "Email/query", "path": "/ids"}}, "g"],
        ["Mailbox/get", {"accountId": ACCOUNT}, "m"],
        ["Email/changes", {"accountId": ACCOUNT, "sinceState": STATE}, "c"],
        ["Foo/bar", {}, "x"],
    ]}), "h")["methodResponses"]
    assert r[0][1]["total"] == 6 and len(r[0][1]["ids"]) == 3, r[0]
    assert [e["id"] for e in r[1][1]["list"]] == r[0][1]["ids"], r[1]
    assert len(r[2][1]["list"]) == len(MAILBOXES)
    assert r[3][1]["created"] == [] and r[4][0] == "error"
    assert query_ids("mb-receipts") == ["m-receipt", "m-warranty"], query_ids("mb-receipts")
    raw = raw_message(BY_ID["m-espana"])
    assert "Bienvenidos a Espa\xf1a".encode("iso-8859-1") in raw and b"charset=iso-8859-1" in raw
    assert b"=?ISO-8859-1?Q?Espa=F1a" in raw
    assert b"<style>" in raw_message(BY_ID["m-news"])
    print("fake-jmap-server selftest ok: %d messages, %d mailboxes" % (len(MESSAGES), len(MAILBOXES)))


if __name__ == "__main__":
    if "--selftest" in sys.argv:
        selftest()
        sys.exit(0)
    port = int(sys.argv[sys.argv.index("--port") + 1]) if "--port" in sys.argv else 8099
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()
