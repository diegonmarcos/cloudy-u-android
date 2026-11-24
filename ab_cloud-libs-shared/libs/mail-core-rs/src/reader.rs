//! The reader (#1837, phase 2): a raw RFC 5322 message in, a Gmail-like reading document out.
//!
//!   parse   mail-parser decodes the MIME tree: every charset (ISO-8859-1, Windows-1252 and the other single-byte sets are
//!           built in, UTF-8/16, UTF-7), quoted-printable, base64, nested multipart, inline `cid:` parts. The decoded text is
//!           what the reader shows, so "Espana" with an n-tilde is never a replacement character.
//!   clean   an HTML body goes through ammonia (a whitelist): scripts, forms, iframes, event handlers and `javascript:` links
//!           never survive; inline styles are kept for the properties Gmail keeps (colours, fonts, spacing, borders, widths),
//!           tables and their attributes (width, bgcolor, align, cellpadding...) are kept, `cid:` images are embedded as data
//!           URIs. A text/plain body becomes HTML like Gmail draws it: linkified, a trailing quoted block collapsed, an ASCII
//!           table kept aligned (monospace, no wrapping, scrolling inside its own box and nowhere else).
//!   wrap    the fragment is put in a Gmail-like stylesheet (Roboto / system sans 14 px, Gmail's text and link colours, images
//!           scale to the width, tables keep their layout but never exceed the viewport, `pre` keeps its alignment).
//!
//! parse() is opts-independent (what the store keeps, so an open never parses); wrap() takes the theme and the spacers (what
//! the screen knows). Both are pure functions: the tests feed them fixtures.

use std::collections::HashMap;
use std::sync::OnceLock;

use ammonia::Builder;
use mail_parser::{MessageParser, MimeHeaders, PartType};
use serde::Deserialize;

/// Bumped when parse() changes what it produces: stored parsed documents of an older version are re-parsed from the raw message.
pub const READER_VERSION: i64 = 2;

/// Replaced by the screen's own wording of "quoted text" in [wrap].
pub const QUOTE_PLACEHOLDER: &str = "{{QUOTE}}";

/// A parsed message: what the store keeps for the reader.
#[derive(Debug, Clone, PartialEq)]
pub struct Parsed {
    /// "html" (the sender's own HTML, sanitised) or "text" (a text/plain body drawn as HTML).
    pub kind: String,
    /// The sanitised body fragment (inline images embedded), without a stylesheet.
    pub fragment: String,
    /// The decoded plain text (an HTML body converted), for search and previews.
    pub text: String,
    /// The fragment names a remote (http/https) resource.
    pub remote_images: bool,
    /// The parser met bytes it could not decode in the declared charset (a replacement character may remain).
    pub encoding_problem: bool,
}

/// What the screen knows: theme colours, the document spacers and the image policy.
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(rename_all = "camelCase", default)]
pub struct Opts {
    pub dark: bool,
    /// Page background / text / link colours (CSS hex) for a text body; an HTML body keeps its own colours.
    pub bg: String,
    pub fg: String,
    pub link: String,
    /// Whether the Content-Security-Policy lets remote images load (the WebView client still gates each request).
    pub images: bool,
    /// CSS lengths of the transparent top and bottom spacers (the collapsing header, the overlaying bar).
    pub top: String,
    pub bottom: String,
    /// The wording of the quoted-text button.
    pub quote_label: String,
}

// ── parse ───────────────────────────────────────────────────────────────────────────────────────────────────────────

pub fn parse(raw: &[u8]) -> Result<Parsed, String> {
    let msg = MessageParser::default().parse(raw).ok_or_else(|| "not an RFC 5322 message".to_string())?;
    let encoding_problem = msg.parts.iter().any(|p| p.is_encoding_problem);

    let mut html = String::new();
    for p in msg.html_bodies() {
        if let PartType::Html(h) = &p.body {
            if !html.is_empty() {
                html.push_str("<br>");
            }
            html.push_str(h);
        }
    }
    let mut plain = String::new();
    for p in msg.text_bodies() {
        if let PartType::Text(t) = &p.body {
            if !plain.is_empty() {
                plain.push_str("\n\n");
            }
            plain.push_str(t);
        }
    }

    if !html.trim().is_empty() {
        let cids = collect_cids(&msg);
        // <style> blocks (head and body) are kept like Gmail keeps them: lifted out, sanitised and scoped by us, put back as ONE block.
        let (body_html, raw_css) = extract_styles(&html);
        let css = sanitize_css(&raw_css, &cids);
        let clean = sanitize(&body_html);
        let mut fragment = inline_cids(&clean, &cids);
        if !css.is_empty() {
            fragment = format!("<style>{css}</style>{fragment}");
        }
        let remote_images = fragment.contains("src=\"http") || fragment.contains("url(http") || fragment.contains("url(\"http");
        let text = match msg.body_text(0) {
            Some(t) => t.into_owned(),
            None => plain,
        };
        return Ok(Parsed { kind: "html".into(), fragment, text, remote_images, encoding_problem });
    }
    Ok(Parsed { kind: "text".into(), fragment: text_fragment(&plain), text: plain, remote_images: false, encoding_problem })
}

/// The file attachments of a raw message as (name, lowercase mime type): the cross-engine parity surface (#1837), the same
/// facts the Kotlin MimeParser reports as its attachments (inline cid images are not listed).
pub fn attachments(raw: &[u8]) -> Vec<(String, String)> {
    let Some(msg) = MessageParser::default().parse(raw) else { return Vec::new() };
    msg.attachments()
        .filter(|p| p.content_id().is_none())
        .map(|p| {
            let ty = match p.content_type() {
                Some(ct) => format!("{}/{}", ct.ctype().to_ascii_lowercase(), ct.subtype().unwrap_or("octet-stream").to_ascii_lowercase()),
                None => "application/octet-stream".to_string(),
            };
            (p.attachment_name().unwrap_or("").to_string(), ty)
        })
        .collect()
}

/// Content-ID -> (mime type, bytes) of every inline image part.
fn collect_cids<'a>(msg: &'a mail_parser::Message<'_>) -> HashMap<String, (String, &'a [u8])> {
    let mut map: HashMap<String, (String, &'a [u8])> = HashMap::new();
    for p in msg.parts.iter() {
        let Some(cid) = p.content_id() else { continue };
        let bytes: &[u8] = match &p.body {
            PartType::Binary(b) | PartType::InlineBinary(b) => b.as_ref(),
            _ => continue,
        };
        let mime = match p.content_type() {
            Some(ct) => format!("{}/{}", ct.ctype().to_ascii_lowercase(), ct.subtype().unwrap_or("octet-stream").to_ascii_lowercase()),
            None => "application/octet-stream".to_string(),
        };
        if !mime.starts_with("image/") {
            continue;
        }
        let key = cid.trim().trim_start_matches('<').trim_end_matches('>').to_ascii_lowercase();
        map.insert(key, (mime, bytes));
    }
    map
}

/// `src="cid:x"` -> `src="data:<mime>;base64,..."` for every cid the message carries.
fn inline_cids(html: &str, cids: &HashMap<String, (String, &[u8])>) -> String {
    if cids.is_empty() {
        return html.to_string();
    }
    const KEY: &str = "src=\"cid:";
    let mut out = String::with_capacity(html.len());
    let mut rest = html;
    while let Some(i) = rest.find(KEY) {
        let after = &rest[i + KEY.len()..];
        let Some(end) = after.find('"') else { break };
        let id = after[..end].trim().to_ascii_lowercase();
        out.push_str(&rest[..i]);
        match cids.get(&id) {
            Some((mime, bytes)) => {
                out.push_str("src=\"data:");
                out.push_str(mime);
                out.push_str(";base64,");
                out.push_str(&base64(bytes));
                out.push('"');
            }
            None => out.push_str(&rest[i..i + KEY.len() + end + 1]),
        }
        rest = &after[end + 1..];
    }
    out.push_str(rest);
    out
}

pub(crate) fn base64(b: &[u8]) -> String {
    const T: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    let mut s = String::with_capacity((b.len() + 2) / 3 * 4);
    for c in b.chunks(3) {
        let b1 = if c.len() > 1 { c[1] as u32 } else { 0 };
        let b2 = if c.len() > 2 { c[2] as u32 } else { 0 };
        let n: u32 = ((c[0] as u32) << 16) | (b1 << 8) | b2;
        s.push(T[((n >> 18) & 63) as usize] as char);
        s.push(T[((n >> 12) & 63) as usize] as char);
        s.push(if c.len() > 1 { T[((n >> 6) & 63) as usize] as char } else { '=' });
        s.push(if c.len() > 2 { T[(n & 63) as usize] as char } else { '=' });
    }
    s
}

// ── the HTML sanitiser ──────────────────────────────────────────────────────────────────────────────────────────────

/// The `style` properties kept: what Gmail keeps (colours, type, spacing, borders, sizes, table layout, simple display).
/// Not kept: position, z-index, overflow, transforms, filters, animations, `behavior`, `-moz-binding`, `content`.
const STYLE_PROPERTIES: &[&str] = &[
    "color", "background", "background-color", "background-image", "background-repeat", "background-position", "background-size",
    "font", "font-family", "font-size", "font-style", "font-weight", "font-variant", "line-height", "letter-spacing", "word-spacing",
    "text-align", "text-decoration", "text-indent", "text-transform", "text-overflow", "white-space", "word-break", "word-wrap",
    "overflow-wrap", "vertical-align", "direction", "margin", "margin-top", "margin-right", "margin-bottom", "margin-left",
    "padding", "padding-top", "padding-right", "padding-bottom", "padding-left", "border", "border-top", "border-right",
    "border-bottom", "border-left", "border-color", "border-style", "border-width", "border-top-color", "border-right-color",
    "border-bottom-color", "border-left-color", "border-top-style", "border-right-style", "border-bottom-style",
    "border-left-style", "border-top-width", "border-right-width", "border-bottom-width", "border-left-width", "border-radius",
    "border-collapse", "border-spacing", "width", "min-width", "max-width", "height", "min-height", "max-height", "display",
    "float", "clear", "table-layout", "list-style", "list-style-type", "list-style-position", "opacity", "box-sizing",
];

fn sanitizer() -> &'static Builder<'static> {
    static B: OnceLock<Builder<'static>> = OnceLock::new();
    B.get_or_init(|| {
        let mut b = Builder::default();
        b.add_tags(&["font", "big", "section", "main", "address", "center"]);
        b.add_generic_attributes(&["style", "align", "valign", "bgcolor", "width", "height", "dir", "lang", "title", "class", "id"]);
        b.add_tag_attributes("table", &["border", "cellpadding", "cellspacing"]);
        b.add_tag_attributes("td", &["colspan", "rowspan", "nowrap"]);
        b.add_tag_attributes("th", &["colspan", "rowspan", "nowrap", "scope"]);
        b.add_tag_attributes("font", &["color", "face", "size"]);
        b.add_tag_attributes("img", &["src", "alt", "border"]);
        b.add_tag_attributes("ol", &["start", "type"]);
        b.add_tag_attributes("blockquote", &["cite", "type"]);
        b.filter_style_properties(STYLE_PROPERTIES.iter().copied().collect());
        b.url_schemes(["http", "https", "mailto", "tel", "sms", "cid", "data"].into_iter().collect());
        b.attribute_filter(|element, attribute, value| {
            let v = value.trim_start().to_ascii_lowercase();
            match (element, attribute) {
                ("a", "href") | ("area", "href") if v.starts_with("data:") || v.starts_with("javascript:") => None,
                ("img", "src") if v.starts_with("data:") && !v.starts_with("data:image/") => None,
                _ => Some(value.into()),
            }
        });
        b
    })
}

// ── <style> blocks ──────────────────────────────────────────────────────────────────────────────────────────────────

/// The HTML without its `<style>` elements, and their contents joined.
fn extract_styles(html: &str) -> (String, String) {
    let lower = html.to_ascii_lowercase();
    let mut out = String::with_capacity(html.len());
    let mut css = String::new();
    let mut pos = 0;
    while let Some(i) = lower[pos..].find("<style") {
        let start = pos + i;
        let after = start + 6;
        let nb = lower.as_bytes().get(after).copied().unwrap_or(b'>');
        if !(nb == b'>' || nb.is_ascii_whitespace() || nb == b'/') {
            out.push_str(&html[pos..after]);
            pos = after;
            continue;
        }
        out.push_str(&html[pos..start]);
        let Some(gt) = lower[after..].find('>') else {
            pos = html.len();
            break;
        };
        let body_start = after + gt + 1;
        match lower[body_start..].find("</style") {
            Some(e) => {
                css.push_str(&html[body_start..body_start + e]);
                css.push('\n');
                pos = lower[body_start + e..].find('>').map(|g| body_start + e + g + 1).unwrap_or(html.len());
            }
            None => {
                css.push_str(&html[body_start..]);
                pos = html.len();
                break;
            }
        }
    }
    out.push_str(&html[pos.min(html.len())..]);
    (out, css)
}

type Cids<'a> = HashMap<String, (String, &'a [u8])>;

fn strip_css_comments(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    let mut rest = s;
    while let Some(i) = rest.find("/*") {
        out.push_str(&rest[..i]);
        match rest[i + 2..].find("*/") {
            Some(e) => rest = &rest[i + 2 + e + 2..],
            None => {
                rest = "";
                break;
            }
        }
    }
    out.push_str(rest);
    out
}

fn find_block_end(s: &str, from: usize) -> Option<usize> {
    let b = s.as_bytes();
    let mut depth = 1;
    let mut i = from;
    while i < b.len() {
        match b[i] {
            b'{' => depth += 1,
            b'}' => {
                depth -= 1;
                if depth == 0 {
                    return Some(i);
                }
            }
            _ => {}
        }
        i += 1;
    }
    None
}

/// A mail's stylesheet reduced to rules and `@media` blocks of whitelisted properties, every selector scoped under `.m` (the
/// reader's message container) so mail CSS cannot restyle the reader chrome. `@import`, `@font-face`, `@keyframes` and every other
/// at-rule are dropped; so is any declaration with a remote `url()`, `expression()`, `behavior`, `-moz-binding`, position, etc.
pub fn sanitize_css(css: &str, cids: &Cids) -> String {
    sanitize_rules(&strip_css_comments(css), cids, 0)
}

fn sanitize_rules(s: &str, cids: &Cids, depth: u8) -> String {
    let b = s.as_bytes();
    let mut out = String::new();
    let mut i = 0;
    while i < b.len() {
        if b[i].is_ascii_whitespace() || b[i] == b'}' {
            i += 1;
            continue;
        }
        if b[i] == b'@' {
            let name_end = s[i..].find(|c: char| c.is_whitespace() || c == '{' || c == ';' || c == '(').map(|k| i + k).unwrap_or(s.len());
            let name = s[i + 1..name_end].to_ascii_lowercase();
            let Some(rel) = s[name_end..].find(|c: char| c == ';' || c == '{') else { break };
            let p = name_end + rel;
            if b[p] == b';' {
                i = p + 1;
                continue;
            }
            let Some(end) = find_block_end(s, p + 1) else { break };
            if name == "media" && depth < 2 {
                let prelude = s[name_end..p].trim();
                if prelude.chars().all(|c| c.is_ascii_alphanumeric() || " ():,.-_/<>=".contains(c)) {
                    let inner = sanitize_rules(&s[p + 1..end], cids, depth + 1);
                    if !inner.is_empty() {
                        out.push_str(&format!("@media {prelude}{{{inner}}}"));
                    }
                }
            }
            i = end + 1;
            continue;
        }
        let Some(rel) = s[i..].find('{') else { break };
        let p = i + rel;
        let Some(end) = s[p + 1..].find('}').map(|k| p + 1 + k) else { break };
        let sel = scope_selectors(&s[i..p]);
        let decls = sanitize_decls(&s[p + 1..end], cids);
        if let (Some(sel), false) = (sel, decls.is_empty()) {
            out.push_str(&format!("{sel}{{{decls}}}"));
        }
        i = end + 1;
    }
    out
}

fn scope_selectors(raw: &str) -> Option<String> {
    let t = raw.trim();
    let low = t.to_ascii_lowercase();
    if t.is_empty() || t.contains(|c: char| "<{}\\;@".contains(c)) || low.contains("expression") {
        return None;
    }
    let mut parts: Vec<String> = Vec::new();
    for p in t.split(',') {
        let p = p.trim();
        if p.is_empty() {
            continue;
        }
        let l = p.to_ascii_lowercase();
        if l == "html" || l == "body" || l == ":root" {
            parts.push(".m".to_string());
        } else if l.starts_with("html ") || l.starts_with("body ") {
            parts.push(format!(".m {}", p[5..].trim()));
        } else {
            parts.push(format!(".m {p}"));
        }
    }
    if parts.is_empty() { None } else { Some(parts.join(", ")) }
}

fn split_decls(s: &str) -> Vec<&str> {
    let mut v = Vec::new();
    let mut depth = 0i32;
    let mut quote: Option<char> = None;
    let mut start = 0;
    for (i, c) in s.char_indices() {
        match quote {
            Some(q) => {
                if c == q {
                    quote = None;
                }
            }
            None => match c {
                '"' | '\'' => quote = Some(c),
                '(' => depth += 1,
                ')' => depth -= 1,
                ';' if depth <= 0 => {
                    v.push(&s[start..i]);
                    start = i + 1;
                }
                _ => {}
            },
        }
    }
    v.push(&s[start..]);
    v
}

fn sanitize_decls(body: &str, cids: &Cids) -> String {
    let mut out = String::new();
    for d in split_decls(body) {
        let Some(colon) = d.find(':') else { continue };
        let name = d[..colon].trim().to_ascii_lowercase();
        if !STYLE_PROPERTIES.contains(&name.as_str()) {
            continue;
        }
        if let Some(v) = check_value(d[colon + 1..].trim(), cids) {
            if !v.is_empty() {
                out.push_str(&name);
                out.push(':');
                out.push_str(&v);
                out.push(';');
            }
        }
    }
    out
}

/// A declaration value, or None when it holds anything dangerous. `url()` survives only as a `data:image/` URI or a cid the message carries.
fn check_value(v: &str, cids: &Cids) -> Option<String> {
    let low = v.to_ascii_lowercase();
    for bad in ["expression", "behavior", "binding", "javascript:", "vbscript:", "@import", "\\", "<", "image-set", "src(", "var(", "env("] {
        if low.contains(bad) {
            return None;
        }
    }
    let mut out = String::new();
    let mut rest = v;
    loop {
        let l = rest.to_ascii_lowercase();
        let Some(i) = l.find("url(") else {
            out.push_str(rest);
            break;
        };
        out.push_str(&rest[..i]);
        let after = &rest[i + 4..];
        let close = after.find(')')?;
        let arg = after[..close].trim().trim_matches(|c: char| c == '"' || c == '\'');
        if arg.is_empty() || arg.contains(|c: char| c == '"' || c == '\'' || c.is_whitespace()) {
            return None;
        }
        let al = arg.to_ascii_lowercase();
        if al.starts_with("data:image/") {
            out.push_str(&format!("url(\"{arg}\")"));
        } else if let Some(c) = al.strip_prefix("cid:") {
            let (mime, bytes) = cids.get(c.trim().trim_start_matches('<').trim_end_matches('>'))?;
            out.push_str(&format!("url(\"data:{mime};base64,{}\")", base64(bytes)));
        } else {
            return None;
        }
        rest = &after[close + 1..];
    }
    Some(out.trim().to_string())
}

/// The HTML reduced to the whitelist (see the module doc).
pub fn sanitize(html: &str) -> String {
    sanitizer().clean(html).to_string()
}

// ── text/plain, drawn as Gmail draws it ─────────────────────────────────────────────────────────────────────────────

fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len() + 8);
    for c in s.chars() {
        match c {
            '&' => o.push_str("&amp;"),
            '<' => o.push_str("&lt;"),
            '>' => o.push_str("&gt;"),
            '"' => o.push_str("&quot;"),
            '\'' => o.push_str("&#39;"),
            _ => o.push(c),
        }
    }
    o
}

/// Escaped, with every http(s) address made a link (trailing sentence punctuation stays outside the link).
fn linkify(line: &str) -> String {
    let mut out = String::with_capacity(line.len() + 16);
    let mut rest = line;
    loop {
        let pos = match (rest.find("http://"), rest.find("https://")) {
            (Some(a), Some(b)) => Some(a.min(b)),
            (a, b) => a.or(b),
        };
        let Some(p) = pos else {
            out.push_str(&esc(rest));
            break;
        };
        out.push_str(&esc(&rest[..p]));
        let tail = &rest[p..];
        let end = tail.find(|c: char| c.is_whitespace() || c == '<' || c == '>' || c == '"').unwrap_or(tail.len());
        let candidate = &tail[..end];
        let url = candidate.trim_end_matches(|c: char| ".,;:!?)]}'".contains(c));
        let trailing = &candidate[url.len()..];
        if url.len() > "https://".len() {
            out.push_str("<a href=\"");
            out.push_str(&esc(url));
            out.push_str("\">");
            out.push_str(&esc(url));
            out.push_str("</a>");
        } else {
            out.push_str(&esc(url));
        }
        out.push_str(&esc(trailing));
        rest = &tail[end..];
    }
    out
}

fn is_quoted(l: &str) -> bool {
    l.trim_start().starts_with('>')
}

/// 0 = prose, 1 = a line that looks like a table row (3+ columns separated by 2+ spaces), 2 = a border or a pipe row.
fn table_kind(l: &str) -> u8 {
    let t = l.trim();
    if t.is_empty() {
        return 0;
    }
    if t.chars().any(|c| ('\u{2500}'..='\u{257F}').contains(&c)) {
        return 2;
    }
    let first = t.chars().next().unwrap_or(' ');
    let last = t.chars().last().unwrap_or(' ');
    if t.len() >= 3 && (first == '|' || first == '+') && (last == '|' || last == '+') {
        return 2;
    }
    if t.len() >= 5 && t.chars().all(|c| "-=+_| ".contains(c)) && (t.contains('+') || t.contains('|')) {
        return 2;
    }
    let tokens = t.split_whitespace().count();
    if tokens >= 3 && (t.contains("  ") || t.contains('\t')) {
        return 1;
    }
    0
}

fn is_attribution(l: &str) -> bool {
    let t = l.trim();
    if !t.ends_with(':') || t.len() > 300 {
        return false;
    }
    let low = t.to_lowercase();
    t.starts_with("On ")
        || ["wrote:", "escribió:", "écrit :", "écrit:", "schrieb:", "scritto:", "escreveu:", "schreef:"].iter().any(|k| low.contains(*k))
}

#[derive(PartialEq, Clone, Copy)]
enum Seg {
    Text,
    Quote,
    Table,
}

fn segments(lines: &[&str]) -> Vec<(Seg, usize, usize)> {
    let n = lines.len();
    let kinds: Vec<u8> = lines.iter().map(|l| table_kind(l)).collect();
    let mut segs: Vec<(Seg, usize, usize)> = Vec::new();
    let mut i = 0;
    while i < n {
        let (seg, j) = if is_quoted(lines[i]) {
            let mut j = i;
            while j < n && is_quoted(lines[j]) {
                j += 1;
            }
            (Seg::Quote, j)
        } else if kinds[i] > 0 {
            let mut j = i;
            while j < n && kinds[j] > 0 && !is_quoted(lines[j]) {
                j += 1;
            }
            let strong = kinds[i..j].iter().any(|&k| k == 2);
            if (strong && j - i >= 2) || j - i >= 3 {
                (Seg::Table, j)
            } else {
                (Seg::Text, j)
            }
        } else {
            let mut j = i;
            while j < n && !is_quoted(lines[j]) && kinds[j] == 0 {
                j += 1;
            }
            (Seg::Text, j)
        };
        let merge = seg == Seg::Text && matches!(segs.last(), Some(l) if l.0 == Seg::Text);
        if merge {
            if let Some(l) = segs.last_mut() {
                l.2 = j;
            }
        } else {
            segs.push((seg, i, j));
        }
        i = j;
    }
    segs
}

fn render_segments(lines: &[&str]) -> String {
    let mut out = String::new();
    for (seg, a, b) in segments(lines) {
        let part = &lines[a..b];
        match seg {
            Seg::Text => {
                out.push_str("<div class=\"t\">");
                out.push_str(&part.iter().map(|l| linkify(l)).collect::<Vec<_>>().join("\n"));
                out.push_str("</div>");
            }
            Seg::Table => {
                out.push_str("<pre class=\"ascii\">");
                out.push_str(&part.iter().map(|l| linkify(l.trim_end())).collect::<Vec<_>>().join("\n"));
                out.push_str("</pre>");
            }
            Seg::Quote => {
                let stripped: Vec<&str> = part
                    .iter()
                    .map(|&l| {
                        let t = l.trim_start().strip_prefix('>').unwrap_or(l);
                        t.strip_prefix(' ').unwrap_or(t)
                    })
                    .collect();
                out.push_str("<blockquote class=\"q\">");
                out.push_str(&render_segments(&stripped));
                out.push_str("</blockquote>");
            }
        }
    }
    out
}

/// A text/plain body as the reader's HTML fragment: linkified, quoted runs as quotes, the trailing quoted block (with its
/// "On ... wrote:" line) behind a fold, ASCII tables in a monospace block.
pub fn text_fragment(text: &str) -> String {
    let normal = text.replace("\r\n", "\n").replace('\r', "\n");
    let lines: Vec<&str> = normal.trim_end().split('\n').collect();
    let n = lines.len();
    if normal.trim().is_empty() {
        return String::new();
    }
    // The trailing run of quoted and blank lines.
    let mut i = n;
    while i > 0 && (is_quoted(lines[i - 1]) || lines[i - 1].trim().is_empty()) {
        i -= 1;
    }
    let mut fold_from = n;
    if let Some(first_q) = (i..n).find(|&k| is_quoted(lines[k])) {
        let mut start = first_q;
        let mut j = start;
        while j > 0 && lines[j - 1].trim().is_empty() {
            j -= 1;
        }
        if j > 0 && is_attribution(lines[j - 1]) {
            start = j - 1;
            if start > 0 && lines[start - 1].trim_start().starts_with("On ") && !lines[start - 1].contains("wrote") {
                start -= 1;
            }
        }
        fold_from = start;
    }
    // Nothing above the quote: show it rather than a lone fold button.
    if lines[..fold_from].iter().all(|l| l.trim().is_empty()) {
        fold_from = n;
    }
    let mut out = String::from("<div class=\"s-text\">");
    out.push_str(&render_segments(&lines[..fold_from]));
    if fold_from < n {
        out.push_str("<details class=\"s-quote\"><summary>");
        out.push_str(QUOTE_PLACEHOLDER);
        out.push_str("</summary>");
        out.push_str(&render_segments(&lines[fold_from..]));
        out.push_str("</details>");
    }
    out.push_str("</div>");
    out
}

// ── the document ────────────────────────────────────────────────────────────────────────────────────────────────────

fn color(s: &str, default: &str) -> String {
    let t = s.trim();
    let ok = t.len() >= 4 && t.len() <= 9 && t.starts_with('#') && t[1..].chars().all(|c| c.is_ascii_hexdigit());
    if ok { t.to_string() } else { default.to_string() }
}

fn length(s: &str) -> String {
    let t = s.trim();
    let ok = !t.is_empty() && t.len() <= 12 && t.chars().all(|c| c.is_ascii_digit() || c == '.' || c == '%' || c == 'v' || c == 'h' || c == 'p' || c == 'x');
    if ok { t.to_string() } else { "0".to_string() }
}

/// The `<head>` content: charset, the Content-Security-Policy (no script, no frames, no forms; remote images only when asked),
/// the viewport (width=device-width: the page lays out at the screen's width), the colour scheme.
pub fn head(kind: &str, o: &Opts) -> String {
    let img = if o.images { "data: cid: http: https:" } else { "data: cid:" };
    let scheme = if kind == "html" { "only light" } else if o.dark { "dark" } else { "light" };
    format!(
        "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; img-src {img}; \
         style-src 'unsafe-inline'; font-src data:; media-src {img}; form-action 'none'; base-uri 'none'; frame-src 'none'; \
         object-src 'none'\"><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\
         <meta name=\"color-scheme\" content=\"{scheme}\">"
    )
}

/// The Gmail-like stylesheet. An HTML body is drawn light with Gmail's colours (a dark theme inverts the whole page, the way the
/// app always drew rich mail, with media counter-inverted); a text body takes the theme's colours.
pub fn css(kind: &str, o: &Opts) -> String {
    let html = kind == "html";
    let (bg, fg, link) = if html || !o.dark {
        let (b, f, l) = ("#ffffff", "#202124", "#0b57d0");
        if html { (b.to_string(), f.to_string(), l.to_string()) } else { (color(&o.bg, b), color(&o.fg, f), color(&o.link, l)) }
    } else {
        (color(&o.bg, "#131314"), color(&o.fg, "#e3e3e3"), color(&o.link, "#a8c7fa"))
    };
    let scheme = if html { "only light" } else if o.dark { "dark" } else { "light" };
    let page = if html && o.dark {
        "html{filter:invert(1) hue-rotate(180deg);background:transparent!important}\
         body{background:transparent!important}\
         img,picture,video,svg,iframe,.s-emo{filter:invert(1) hue-rotate(180deg)}\
         .s-end{background:transparent}"
            .to_string()
    } else {
        format!("html,body{{background:{bg}}}.s-end{{background:{bg}}}")
    };
    let muted = if html || !o.dark { "#5f6368" } else { "#9aa0a6" };
    let rule = if html || !o.dark { "#dadce0" } else { "#5f6368" };
    let chip = if html || !o.dark { "#e8eaed" } else { "#3c4043" };
    format!(
        "html{{color-scheme:{scheme};-webkit-text-size-adjust:100%}}\
         html,body{{margin:0;padding:0;max-width:100%!important;min-width:0!important}}\
         body{{padding:12px 16px;font:14px/1.5 Roboto,'Google Sans','Noto Sans','Helvetica Neue',Helvetica,Arial,sans-serif;\
         color:{fg};word-wrap:break-word;overflow-wrap:break-word}}\
         {page}\
         a{{color:{link}}}\
         img,picture,video,canvas,svg{{max-width:100%!important;height:auto!important}}\
         body *{{max-width:100%!important;min-width:0!important}}\
         table{{max-width:100%!important;border-collapse:collapse}}\
         table[cellspacing]{{border-collapse:separate}}\
         td,th{{white-space:normal!important;overflow-wrap:anywhere}}\
         td,th,li,p,div,a,span,blockquote,h1,h2,h3,h4,h5,h6{{overflow-wrap:anywhere}}\
         p{{margin:0 0 1em}}\
         pre,code,kbd,samp{{font-family:'Roboto Mono',ui-monospace,Menlo,Consolas,'DejaVu Sans Mono',monospace}}\
         pre{{white-space:pre!important;overflow-x:auto;max-width:100%;margin:8px 0;font-size:12px;line-height:1.3}}\
         pre.ascii{{font-size:11px;line-height:1.25}}\
         .s-text .t{{white-space:pre-wrap;overflow-wrap:anywhere}}\
         blockquote{{margin:8px 0 8px 4px;padding-left:12px;border-left:2px solid {rule}}}\
         blockquote.q{{color:{muted}}}\
         details.s-quote{{margin:12px 0}}\
         details.s-quote>summary{{cursor:pointer;display:inline-block;list-style:none;padding:2px 10px;border-radius:10px;\
         background:{chip};color:{muted};font-size:13px;margin:4px 0 8px}}\
         details.s-quote>summary::-webkit-details-marker{{display:none}}\
         .s-deceptive{{margin-left:.4em;font-size:.85em;opacity:.85;white-space:nowrap}}\
         hr{{border:0;border-top:1px solid {rule}}}"
    )
}

/// The full reading document of a parsed [kind] / [fragment] under [o].
pub fn wrap(kind: &str, fragment: &str, o: &Opts) -> String {
    let label = if o.quote_label.trim().is_empty() { "Quoted text" } else { o.quote_label.as_str() };
    let body = fragment.replace(QUOTE_PLACEHOLDER, &esc(label));
    format!(
        "<!DOCTYPE html><html><head>{}<style>{}</style></head><body>\
         <div aria-hidden=\"true\" style=\"height:{}\"></div><div class=\"m\">{}</div>\
         <div aria-hidden=\"true\" class=\"s-end\" style=\"height:{}\"></div></body></html>",
        head(kind, o),
        css(kind, o),
        length(&o.top),
        body,
        length(&o.bottom)
    )
}

/// A cheap check the tests and logs share: the decoded text carries no replacement character.
pub fn has_replacement_char(s: &str) -> bool {
    s.contains('\u{FFFD}')
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(headers: &str, body: &[u8]) -> Vec<u8> {
        let mut v = format!("From: Ana <ana@example.es>\r\nTo: me@example.org\r\nDate: Fri, 14 Nov 2025 10:00:00 +0000\r\nMessage-ID: <1@x>\r\n{headers}\r\n\r\n").into_bytes();
        v.extend_from_slice(body);
        v
    }

    #[test]
    fn latin1_body_decodes_without_replacement_characters() {
        let raw = msg(
            "Subject: =?ISO-8859-1?Q?Espa=F1a?=\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=ISO-8859-1\r\nContent-Transfer-Encoding: 8bit",
            b"Hola, Espa\xf1a. Se\xf1or Garc\xeda: ma\xf1ana \xbfqu\xe9 tal?\r\n",
        );
        let p = parse(&raw).unwrap();
        assert!(p.text.contains("España"), "{}", p.text);
        assert!(p.text.contains("Señor García: mañana ¿qué tal?"), "{}", p.text);
        assert!(!has_replacement_char(&p.text) && !has_replacement_char(&p.fragment), "{}", p.fragment);
        assert!(p.fragment.contains("España"));
        assert_eq!(p.kind, "text");
    }

    #[test]
    fn latin1_html_in_quoted_printable() {
        let raw = msg(
            "MIME-Version: 1.0\r\nContent-Type: text/html; charset=iso-8859-1\r\nContent-Transfer-Encoding: quoted-printable",
            b"<html><body><p>Espa=F1a y Espa=\r\n=F1a, caf=E9 =80 5</p></body></html>\r\n",
        );
        let p = parse(&raw).unwrap();
        assert_eq!(p.kind, "html");
        assert!(p.fragment.contains("España y España, café"), "{}", p.fragment);
        assert!(!has_replacement_char(&p.fragment));
    }

    #[test]
    fn windows_1252_smart_quotes_and_dashes() {
        let raw = msg(
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=windows-1252\r\nContent-Transfer-Encoding: quoted-printable",
            b"=93Hello=94 =96 it=92s =80 5 =85\r\n",
        );
        let p = parse(&raw).unwrap();
        assert!(p.text.contains("“Hello” – it’s € 5 …"), "{}", p.text);
        assert!(!has_replacement_char(&p.text));
    }

    #[test]
    fn utf8_quoted_printable_with_soft_breaks() {
        let raw = msg(
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable",
            b"Espa=C3=B1a, Ni=C3=B1o, soft=\r\nbreak and =E2=82=AC\r\n",
        );
        let p = parse(&raw).unwrap();
        assert!(p.text.contains("España, Niño, softbreak and €"), "{}", p.text);
    }

    const PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    #[test]
    fn nested_multipart_with_an_inline_cid_image() {
        let body = format!(
            "--outer\r\nContent-Type: multipart/alternative; boundary=\"alt\"\r\n\r\n\
             --alt\r\nContent-Type: text/plain; charset=utf-8\r\n\r\nplain version\r\n\
             --alt\r\nContent-Type: multipart/related; boundary=\"rel\"\r\n\r\n\
             --rel\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: base64\r\n\r\n{}\r\n\
             --rel\r\nContent-Type: image/png; name=\"logo.png\"\r\nContent-ID: <Logo@x>\r\nContent-Disposition: inline\r\nContent-Transfer-Encoding: base64\r\n\r\n{PNG}\r\n\
             --rel--\r\n--alt--\r\n\
             --outer\r\nContent-Type: application/pdf; name=\"a.pdf\"\r\nContent-Disposition: attachment; filename=\"a.pdf\"\r\nContent-Transfer-Encoding: base64\r\n\r\nJVBERi0xLjQK\r\n\
             --outer--\r\n",
            base64(b"<html><body><p>Hi <b>there</b></p><img src=\"cid:logo@x\" width=\"600\"><img src=\"cid:missing@x\"></body></html>")
        );
        let raw = msg("MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=\"outer\"", body.as_bytes());
        let p = parse(&raw).unwrap();
        assert_eq!(p.kind, "html");
        assert!(p.fragment.contains(&format!("src=\"data:image/png;base64,{PNG}\"")), "{}", p.fragment);
        assert!(p.fragment.contains("<b>there</b>"));
        assert!(p.fragment.contains("src=\"cid:missing@x\""), "an unknown cid is left as it is: {}", p.fragment);
        assert!(!p.remote_images);
        assert!(!p.fragment.contains("plain version"));
    }

    #[test]
    fn an_ascii_table_in_plain_text_keeps_its_alignment() {
        let table = "+--------+-------+\r\n| Name   | Qty   |\r\n+--------+-------+\r\n| Apples |    12 |\r\n+--------+-------+";
        let raw = msg(
            "MIME-Version: 1.0\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: 8bit",
            format!("Hello,\r\n\r\nHere is the table:\r\n{table}\r\nSee https://example.org/a?b=1&c=2.\r\n").as_bytes(),
        );
        let p = parse(&raw).unwrap();
        assert!(p.fragment.contains("<pre class=\"ascii\">+--------+-------+\n| Name   | Qty   |\n+--------+-------+\n| Apples |    12 |\n+--------+-------+</pre>"), "{}", p.fragment);
        assert!(p.fragment.contains("<a href=\"https://example.org/a?b=1&amp;c=2\">https://example.org/a?b=1&amp;c=2</a>."), "{}", p.fragment);
        // And the stylesheet that draws it: monospace, no wrapping.
        let c = css("text", &Opts::default());
        assert!(c.contains("pre.ascii{font-size:11px") && c.contains("white-space:pre!important"), "{c}");
        // Box-drawing and column tables too.
        let f = text_fragment("Totals\n┌──┬──┐\n│ a│ b│\n└──┴──┘\nend");
        assert!(f.contains("<pre class=\"ascii\">┌──┬──┐\n│ a│ b│\n└──┴──┘</pre>"), "{f}");
        let f = text_fragment("Item      Price    Qty\nTea       2.50     3\nCoffee    3.10     1\nbye");
        assert!(f.contains("<pre class=\"ascii\">Item      Price    Qty"), "{f}");
        // Ordinary prose is not a table.
        let f = text_fragment("one  two three\nfour five six");
        assert!(!f.contains("ascii"), "{f}");
    }

    #[test]
    fn plain_text_quotes_collapse_like_gmail() {
        let f = text_fragment("Thanks, will do.\n\nOn Mon, 3 Nov 2025 at 10:00, Ana <ana@example.es> wrote:\n> Can you send it?\n> > earlier\n> Thanks");
        assert!(f.contains("<details class=\"s-quote\"><summary>{{QUOTE}}</summary>"), "{f}");
        assert!(f.starts_with("<div class=\"s-text\"><div class=\"t\">Thanks, will do.") && !f.contains(">Thanks, will do.\n\nOn"), "{f}");
        assert!(f.contains("<blockquote class=\"q\">"), "{f}");
        let d = wrap("text", &f, &Opts { quote_label: "Texto citado".into(), ..Default::default() });
        assert!(d.contains("<summary>Texto citado</summary>") && !d.contains("{{QUOTE}}"), "{d}");
        // A quote in the middle is a quote, not a fold.
        let f = text_fragment("> hi\nreply\nmore");
        assert!(!f.contains("<details") && f.contains("<blockquote class=\"q\"><div class=\"t\">hi</div>"), "{f}");
    }

    const MARKETING: &str = r##"<!DOCTYPE html><html><head><style>.x{color:red}</style><script>alert(1)</script></head>
<body bgcolor="#eeeeee" onload="evil()">
<table width="600" align="center" cellpadding="0" cellspacing="0" border="0" bgcolor="#f4f4f4" style="background-color:#f4f4f4;color:#333333;position:fixed;z-index:99;min-width:600px">
<tr><td style="font-family:Arial,sans-serif;font-size:18px;padding:20px 30px;text-align:center" onclick="steal()">
<h1 style="color:#d93025;font-size:28px">Big sale <span onmouseover="x()">now</span></h1>
<a href="https://shop.example.com/?utm=1" style="background-color:#1a73e8;color:#ffffff;padding:12px 24px;border-radius:4px;display:inline-block">Shop now</a>
<a href="javascript:alert(1)">bad</a>
<img src="https://t.example.com/pixel.gif" width="1" height="1" alt="">
<img src="data:image/gif;base64,R0lGODlhAQABAAAAACH5BAEKAAEALAAAAAABAAEAAAICTAEAOw==" width="800" height="200" alt="hero">
<form action="https://evil.example"><input name="q"><button>go</button></form>
<iframe src="https://evil.example"></iframe>
</td></tr></table>
<p style="font-size:12px;color:#888888">Espa&ntilde;a &amp; co &mdash; unsubscribe</p>
</body></html>"##;

    #[test]
    fn a_marketing_email_is_sanitised_but_keeps_its_look() {
        let raw = msg("MIME-Version: 1.0\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit", MARKETING.as_bytes());
        let p = parse(&raw).unwrap();
        let f = &p.fragment;
        for gone in ["<script", "alert(1)", "onclick", "onload", "onmouseover", "<form", "<input", "<button", "<iframe", "javascript:", "position", "z-index", "evil.example"] {
            assert!(!f.contains(gone), "`{gone}` survived: {f}");
        }
        for kept in ["<table", "width=\"600\"", "align=\"center\"", "cellpadding=\"0\"", "bgcolor=\"#f4f4f4\"", "background-color:#f4f4f4", "color:#d93025", "font-size:28px",
            "padding:20px 30px", "border-radius:4px", "href=\"https://shop.example.com/?utm=1\"", "Big sale", "España &amp; co", "src=\"data:image/gif;base64,", "width=\"800\""] {
            assert!(f.contains(kept), "`{kept}` was lost: {f}");
        }
        assert!(f.contains("<style>.m .x{color:red;}</style>"), "the head <style> is kept, scoped: {f}");
        assert!(p.remote_images, "the tracking pixel is a remote image");
        assert_eq!(p.kind, "html");
        assert!(p.text.contains("Big sale"), "{}", p.text);
    }

    #[test]
    fn a_newsletter_whose_layout_lives_in_a_style_block_keeps_it_scoped() {
        let html = r##"<html><head><style type="text/css">
          /* layout */
          body { background:#eeeeee; font-family:Arial,sans-serif }
          .wrapper { width:600px; margin:0 auto; background-color:#ffffff }
          .btn, a.cta { background:#1a73e8; color:#ffffff; padding:10px 20px; border-radius:4px }
          html .top { color:#333333 }
          @media (max-width:480px) { .wrapper { width:100% } .hide { display:none } }
        </style></head><body><div class="wrapper"><p class="top">Hi</p><a class="btn" href="https://x.example/">Go</a></div>
        <style>.late{color:#d93025}</style></body></html>"##;
        let raw = msg("MIME-Version: 1.0\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit", html.as_bytes());
        let f = parse(&raw).unwrap().fragment;
        for kept in [
            ".m{background:#eeeeee;font-family:Arial,sans-serif;}",
            ".m .wrapper{width:600px;margin:0 auto;background-color:#ffffff;}",
            ".m .btn, .m a.cta{background:#1a73e8;color:#ffffff;padding:10px 20px;border-radius:4px;}",
            ".m .top{color:#333333;}",
            "@media (max-width:480px){.m .wrapper{width:100%;}.m .hide{display:none;}}",
            ".m .late{color:#d93025;}",
            "class=\"wrapper\"",
            "class=\"btn\"",
        ] {
            assert!(f.contains(kept), "`{kept}` missing: {f}");
        }
        assert_eq!(f.matches("<style>").count(), 1, "one <style> block: {f}");
        assert!(f.starts_with("<style>"));
        // Every selector of every rule starts with the container class.
        let css = &f[7..f.find("</style>").unwrap()];
        for rule in css.split('}').filter(|r| r.contains('{')) {
            let sel = rule.rsplit('{').last().unwrap_or("");
            let _ = sel;
            let head = rule.split('{').next().unwrap_or("");
            let head = head.trim_start_matches("@media (max-width:480px)");
            for s in head.split(',') {
                let s = s.trim();
                assert!(s.is_empty() || s.starts_with(".m"), "unscoped selector `{s}` in {css}");
            }
        }
    }

    #[test]
    fn hostile_css_is_stripped() {
        let html = r##"<html><head><style>
          @import url(https://evil.example/x.css);
          @font-face { font-family:x; src:url(https://evil.example/f.woff) }
          @keyframes k { from{color:red} to{color:blue} }
          .a { background:url(https://evil.example/p.gif); color:red }
          .b { background-image:url(data:image/png;base64,AAAA); width:expression(alert(1)) }
          .c { behavior:url(x.htc); -moz-binding:url(x); position:fixed; top:0; color:blue }
          body { position:fixed; color:#111111 }
          .d { background:url(javascript:alert(1)); color:green }
          .e { color:red\9; background:url(cid:logo@x) }
          </style><script>alert(1)</script><img src=x onerror=alert(2)></head><body><p class="a">x</p></body></html>"##;
        let raw = msg("MIME-Version: 1.0\r\nContent-Type: text/html; charset=utf-8\r\nContent-Transfer-Encoding: 8bit", html.as_bytes());
        let f = parse(&raw).unwrap().fragment;
        for gone in ["evil.example", "@import", "@font-face", "@keyframes", "expression", "behavior", "binding", "position", "javascript:", "<script", "alert(", "onerror", "top:0"] {
            assert!(!f.contains(gone), "`{gone}` survived: {f}");
        }
        for kept in [".m .a{color:red;}", ".m .b{background-image:url(\"data:image/png;base64,AAAA\");}", ".m .c{color:blue;}", ".m{color:#111111;}", ".m .d{color:green;}"] {
            assert!(f.contains(kept), "`{kept}` missing: {f}");
        }
        assert!(!f.contains("cid:logo"), "{f}");
    }

    #[test]
    fn the_document_is_gmail_like_and_fits_the_width() {
        let o = Opts { top: "12vh".into(), bottom: "8vh".into(), images: true, ..Default::default() };
        let d = wrap("html", "<p>x</p>", &o);
        for needle in ["font:14px/1.5 Roboto", "color:#202124", "color:#0b57d0", "body *{max-width:100%!important;min-width:0!important}",
            "img,picture,video,canvas,svg{max-width:100%!important;height:auto!important}", "table{max-width:100%!important", "width=device-width",
            "img-src data: cid: http: https:", "style=\"height:12vh\"", "class=\"s-end\" style=\"height:8vh\"", "<div class=\"m\"><p>x</p></div>"] {
            assert!(d.contains(needle), "missing `{needle}` in {d}");
        }
        assert!(!d.contains("html{filter:invert"));
        let off = wrap("html", "<p>x</p>", &Opts::default());
        assert!(off.contains("img-src data: cid:;") && !off.contains("http:"), "{off}");
        let dark = wrap("html", "<p>x</p>", &Opts { dark: true, ..Default::default() });
        assert!(dark.contains("html{filter:invert(1) hue-rotate(180deg)"), "{dark}");
        let dark_text = wrap("text", "<p>x</p>", &Opts { dark: true, bg: "#101010".into(), fg: "#eeeeee".into(), ..Default::default() });
        assert!(!dark_text.contains("invert") && dark_text.contains("#101010") && dark_text.contains("color:#eeeeee"), "{dark_text}");
        // Hostile option values cannot break out of the style or the attribute.
        let evil = wrap("text", "", &Opts { top: "0;}</style><script>".into(), bg: "red;}</style>".into(), ..Default::default() });
        assert!(!evil.contains("<script>") && !evil.contains("red;}"), "{evil}");
    }

    #[test]
    fn garbage_is_an_error_or_an_empty_reader_never_a_panic() {
        assert!(parse(b"").is_err() || parse(b"").unwrap().fragment.is_empty());
        let p = parse(b"\x00\xff\xfe not a mail at all").ok();
        let _ = p;
        let empty = parse(&msg("Content-Type: text/plain", b"")).map(|p| p.fragment).unwrap_or_default();
        assert_eq!(empty, "");
    }
}
