//! Reader-mode extraction (#1841): the readable text of an HTML page as `{title, paragraphs}`. A small tolerant tokenizer, no dependency:
//! the text of p / h1-h6 / li / blockquote / pre blocks outside script, style, noscript, nav, header, footer, aside, form, svg, template;
//! entities decoded; a paragraph under 25 characters is dropped unless it is a heading. The Kotlin twin (ReaderTwin) is the fallback.

const SKIP: [&str; 10] = ["script", "style", "noscript", "nav", "header", "footer", "aside", "form", "svg", "template"];
const BLOCK: [&str; 11] = ["p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "div"];
const HEAD: [&str; 6] = ["h1", "h2", "h3", "h4", "h5", "h6"];

pub fn decode(s: &str) -> String {
    s.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}

fn squash(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// `(title, paragraphs)`.
pub fn extract(html: &str) -> (String, Vec<String>) {
    let b: Vec<char> = html.chars().collect();
    let mut i = 0;
    let mut skip_depth: Vec<String> = Vec::new();
    let mut in_title = false;
    let mut title = String::new();
    let mut cur = String::new();
    let mut cur_head = false;
    let mut out: Vec<String> = Vec::new();
    let flush = |cur: &mut String, head: bool, out: &mut Vec<String>| {
        let t = squash(&decode(cur));
        cur.clear();
        if !t.is_empty() && (head || t.chars().count() >= 25) {
            out.push(t);
        }
    };
    while i < b.len() {
        if b[i] == '<' {
            let end = match b[i..].iter().position(|&c| c == '>') {
                Some(p) => i + p,
                None => break,
            };
            let tag: String = b[i + 1..end].iter().collect();
            i = end + 1;
            if tag.starts_with('!') || tag.starts_with('?') {
                continue;
            }
            let closing = tag.starts_with('/');
            let name: String = tag.trim_start_matches('/').chars().take_while(|c| c.is_ascii_alphanumeric()).collect::<String>().to_lowercase();
            if name.is_empty() {
                continue;
            }
            if name == "title" {
                in_title = !closing;
                continue;
            }
            if SKIP.contains(&name.as_str()) {
                if closing {
                    if let Some(p) = skip_depth.iter().rposition(|n| *n == name) {
                        skip_depth.truncate(p);
                    }
                } else if !tag.ends_with('/') {
                    flush(&mut cur, cur_head, &mut out);
                    skip_depth.push(name);
                }
                continue;
            }
            if BLOCK.contains(&name.as_str()) {
                flush(&mut cur, cur_head, &mut out);
                cur_head = !closing && HEAD.contains(&name.as_str());
            }
        } else {
            let start = i;
            while i < b.len() && b[i] != '<' {
                i += 1;
            }
            let text: String = b[start..i].iter().collect();
            if in_title {
                title.push_str(&text);
            } else if skip_depth.is_empty() {
                cur.push_str(&text);
                cur.push(' ');
            }
        }
    }
    flush(&mut cur, cur_head, &mut out);
    (squash(&decode(&title)), out)
}
