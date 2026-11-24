//! Compose (#1837 phase 3): the outgoing RFC 5322 message built in the core, the counterpart of the Kotlin OutgoingMime.
//! Plain text leaves as text/plain; an HTML body as multipart/alternative (text first, HTML last); file attachments wrap that in
//! multipart/mixed. Header values lose CR/LF (no header injection), non-ASCII subjects and bodies are RFC 2047 / base64 UTF-8.
//! Attachment data arrives already base64 (the JNI surface is JSON text) and is only folded to 76 columns.
use crate::reader::base64;
use serde::Deserialize;

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Attachment {
    pub name: String,
    #[serde(rename = "type")]
    pub ty: String,
    /// Base64 of the file bytes.
    pub data: String,
}

#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Compose {
    pub from: String,
    pub to: Vec<String>,
    pub cc: Vec<String>,
    /// Written as a `Bcc:` header only when `write_bcc` (the stored Drafts copy), never on the wire.
    pub bcc: Vec<String>,
    pub write_bcc: bool,
    pub subject: String,
    pub text: String,
    pub html: Option<String>,
    pub message_id: String,
    pub in_reply_to: Option<String>,
    pub references: Option<String>,
    /// The Date header value, already formatted (RFC 5322) by the caller.
    pub date: String,
    pub attachments: Vec<Attachment>,
}

fn safe(s: &str) -> String {
    s.chars().filter(|c| *c != '\r' && *c != '\n').collect()
}

fn word(s: &str) -> String {
    let s = safe(s);
    if s.is_ascii() {
        return s;
    }
    let mut out: Vec<String> = Vec::new();
    let mut cur = String::new();
    for ch in s.chars() {
        if cur.len() + ch.len_utf8() > 42 {
            out.push(std::mem::take(&mut cur));
        }
        cur.push(ch);
    }
    if !cur.is_empty() {
        out.push(cur);
    }
    out.iter().map(|w| format!("=?UTF-8?B?{}?=", base64(w.as_bytes()))).collect::<Vec<_>>().join("\r\n ")
}

fn list(v: &[String]) -> String {
    v.iter().map(|a| safe(a)).collect::<Vec<_>>().join(", ")
}

fn fold(b64: &str) -> String {
    let clean: String = b64.chars().filter(|c| !c.is_whitespace()).collect();
    clean.as_bytes().chunks(76).map(|c| String::from_utf8_lossy(c).into_owned()).collect::<Vec<_>>().join("\r\n")
}

fn crlf(s: &str) -> String {
    s.replace("\r\n", "\n").replace('\r', "\n").replace('\n', "\r\n")
}

fn text_part(ct: &str, body: &str) -> String {
    if body.is_ascii() {
        format!("Content-Type: {ct}; charset=utf-8\r\nContent-Transfer-Encoding: 7bit\r\n\r\n{}\r\n", crlf(body).trim_end_matches("\r\n"))
    } else {
        format!("Content-Type: {ct}; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n{}\r\n", fold(&base64(crlf(body).as_bytes())))
    }
}

fn boundary(seed: &str, tag: &str, parts: &str) -> String {
    let mut h: u64 = 1469598103934665603;
    for b in seed.bytes().chain(parts.bytes()) {
        h = (h ^ b as u64).wrapping_mul(1099511628211);
    }
    format!("=_{tag}_{h:016x}")
}

pub fn build(c: &Compose) -> String {
    let mut h = format!("From: {}\r\n", safe(&c.from));
    if !c.to.is_empty() {
        h.push_str(&format!("To: {}\r\n", list(&c.to)));
    }
    if !c.cc.is_empty() {
        h.push_str(&format!("Cc: {}\r\n", list(&c.cc)));
    }
    if c.write_bcc && !c.bcc.is_empty() {
        h.push_str(&format!("Bcc: {}\r\n", list(&c.bcc)));
    }
    h.push_str(&format!("Subject: {}\r\nDate: {}\r\nMessage-ID: <{}>\r\n", word(&c.subject), safe(&c.date), safe(c.message_id.trim_matches(|x| x == '<' || x == '>'))));
    if let Some(r) = &c.in_reply_to {
        h.push_str(&format!("In-Reply-To: <{}>\r\n", safe(r.trim_matches(|x| x == '<' || x == '>'))));
    }
    if let Some(r) = &c.references {
        h.push_str(&format!("References: {}\r\n", safe(r)));
    }
    h.push_str("MIME-Version: 1.0\r\n");

    // The body entity: (headers incl. Content-Type, blank line, body).
    let body = match &c.html {
        Some(html) => {
            let t = text_part("text/plain", &c.text);
            let m = text_part("text/html", html);
            let b = boundary(&c.message_id, "alt", &c.text);
            format!("Content-Type: multipart/alternative; boundary=\"{b}\"\r\n\r\n--{b}\r\n{t}--{b}\r\n{m}--{b}--\r\n")
        }
        None => text_part("text/plain", &c.text),
    };
    if c.attachments.is_empty() {
        return h + &body;
    }
    let b = boundary(&c.message_id, "mix", &c.subject);
    let mut out = format!("{h}Content-Type: multipart/mixed; boundary=\"{b}\"\r\n\r\n--{b}\r\n{body}");
    for a in &c.attachments {
        let name = safe(&a.name).replace('"', "'");
        let ty = if a.ty.is_empty() { "application/octet-stream" } else { &a.ty };
        let n = if name.is_ascii() { format!("\"{name}\"") } else { format!("\"{}\"", word(&name)) };
        out.push_str(&format!(
            "--{b}\r\nContent-Type: {}; name={n}\r\nContent-Transfer-Encoding: base64\r\nContent-Disposition: attachment; filename={n}\r\n\r\n{}\r\n",
            safe(ty),
            fold(&a.data)
        ));
    }
    out.push_str(&format!("--{b}--\r\n"));
    out
}
