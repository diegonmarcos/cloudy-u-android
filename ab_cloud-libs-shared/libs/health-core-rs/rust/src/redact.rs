//! Redaction of anything secret-shaped before a line leaves the engine: bearer and basic tokens, `key|secret|token|password = value`,
//! private and preshared key blocks, JWT-shaped strings and e-mail addresses. The count of redactions travels in the report.

pub const MASK: &str = "[redacted]";

/// The armour line of a key block, split so this source never holds one whole.
const KEY_HEAD: &str = concat!("-----", "BEGIN");
const KEY_KIND: &str = concat!("PRIVATE", " ", "KEY");

const SECRET_WORDS: [&str; 6] = ["key", "secret", "token", "password", "passwd", "pwd"];

fn is_tok(c: char) -> bool {
    c.is_ascii_alphanumeric() || "-_.~+/=".contains(c)
}

/// A JWT shape: three base64url segments, the first starting "eyJ", each at least 4 long.
fn is_jwt(w: &str) -> bool {
    let parts: Vec<&str> = w.split('.').collect();
    parts.len() == 3 && w.starts_with("eyJ") && parts.iter().all(|p| p.len() >= 4 && p.chars().all(|c| c.is_ascii_alphanumeric() || c == '-' || c == '_'))
}

fn is_email(w: &str) -> bool {
    let t = w.trim_matches(|c: char| !c.is_ascii_alphanumeric());
    match t.split_once('@') {
        Some((a, b)) => !a.is_empty() && b.contains('.') && !b.starts_with('.') && !b.ends_with('.'),
        None => false,
    }
}

/// Redact one text (lines kept). Returns the text and how many values were masked.
pub fn redact(text: &str) -> (String, usize) {
    let mut n = 0usize;
    let mut out = Vec::new();
    let mut in_key = false;
    for line in text.lines() {
        if line.contains(KEY_HEAD) && line.contains(KEY_KIND) { in_key = true; n += 1; out.push(MASK.to_string()); continue; }
        if in_key { if line.contains("-----END") { in_key = false; } continue; }
        let (l, k) = redact_line(line);
        n += k;
        out.push(l);
    }
    let mut joined = out.join("\n");
    if text.ends_with('\n') { joined.push('\n'); }
    (joined, n)
}

fn redact_line(line: &str) -> (String, usize) {
    let mut n = 0usize;
    let lower = line.to_ascii_lowercase();
    // Authorization: Bearer x / Basic x
    for scheme in ["bearer ", "basic "] {
        if let Some(p) = lower.find(scheme) {
            let start = p + scheme.len();
            let end = line[start..].find(|c: char| !is_tok(c)).map(|e| start + e).unwrap_or(line.len());
            if end > start {
                let l = format!("{}{}{}", &line[..start], MASK, &line[end..]);
                let (rest, k) = redact_line(&l);
                return (rest, 1 + k);
            }
        }
    }
    // key=value / key: value where the key names a secret
    let mut s = String::new();
    let mut i = 0usize;
    let b: Vec<char> = line.chars().collect();
    while i < b.len() {
        let c = b[i];
        if c == '=' || c == ':' {
            let mut j = i;
            while j > 0 && (b[j - 1].is_ascii_alphanumeric() || b[j - 1] == '_' || b[j - 1] == '-') { j -= 1; }
            let name: String = b[j..i].iter().collect::<String>().to_ascii_lowercase();
            let secret = !name.is_empty() && SECRET_WORDS.iter().any(|w| name.contains(w));
            let mut k = i + 1;
            while k < b.len() && b[k] == ' ' { k += 1; }
            let quote = k < b.len() && (b[k] == '"' || b[k] == '\'');
            let vs = if quote { k + 1 } else { k };
            let mut ve = vs;
            let stop = |x: char| if quote { x == b[k] } else { x.is_whitespace() || x == ',' || x == ';' };
            while ve < b.len() && !stop(b[ve]) { ve += 1; }
            if secret && ve > vs {
                s.push(c);
                s.extend(b[i + 1..vs].iter());
                s.push_str(MASK);
                n += 1;
                i = ve;
                continue;
            }
        }
        s.push(c);
        i += 1;
    }
    // words: JWTs and e-mail addresses
    let words: Vec<String> = s.split(' ').map(|w| {
        if is_jwt(w.trim_matches(|c: char| c == '"' || c == '\'' || c == ',')) { n += 1; MASK.to_string() }
        else if is_email(w) { n += 1; MASK.to_string() }
        else { w.to_string() }
    }).collect();
    (words.join(" "), n)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bearer_and_basic_are_masked() {
        assert_eq!(redact("Authorization: Bearer abc.DEF-123"), ("Authorization: Bearer [redacted]".to_string(), 1));
        assert_eq!(redact("auth basic dXNlcjpwYXNz ok").0, "auth basic [redacted] ok");
        assert_eq!(redact("bearer ").1, 0, "nothing after the scheme: nothing to mask");
    }

    #[test]
    fn secret_named_values_are_masked_and_others_kept() {
        assert_eq!(redact("API_KEY=sk-123 TZ=UTC"), ("API_KEY=[redacted] TZ=UTC".to_string(), 1));
        assert_eq!(redact("password: \"hunter 2\" user=x").0, "password: \"[redacted]\" user=x");
        assert_eq!(redact("db_passwd='p'").0, "db_passwd='[redacted]'");
        assert_eq!(redact("SECRET_TOKEN=a,next=b").0, "SECRET_TOKEN=[redacted],next=b");
        assert_eq!(redact("level=info msg=started").1, 0);
        assert_eq!(redact("token_url: https://x.example/a").1, 1, "a secret-named value is masked even when it looks like a URL");
        assert_eq!(redact("keyless: https://x.example/a").0, "keyless: https://x.example/a".replace("https://x.example/a", "[redacted]"));
        assert_eq!(redact("key=").1, 0, "an empty value has nothing to mask");
    }

    #[test]
    fn private_key_blocks_jwts_and_emails_are_masked() {
        let t = format!("a\n{} OPENSSH {}-----\nAAAA\nBBBB\n-----END OPENSSH {}-----\nb\n", KEY_HEAD, KEY_KIND, KEY_KIND);
        let t = t.as_str();
        assert_eq!(redact(t), ("a\n[redacted]\nb\n".to_string(), 1));
        let jwt = "eyJhbGciOi.eyJzdWIiOi.c2lnbmF0dXJl";
        assert_eq!(redact(&format!("id {} end", jwt)), ("id [redacted] end".to_string(), 1));
        assert_eq!(redact("eyJ.ab.cd").1, 0, "segments too short for a JWT");
        assert_eq!(redact("mail from owner@example.com ok"), ("mail from [redacted] ok".to_string(), 1));
        assert_eq!(redact("not@mail and a@b. and @x.y").1, 0);
        assert_eq!(redact("plain line").0, "plain line");
    }

    #[test]
    fn edge_shapes_kill_the_mutants() {
        // a bearer line whose rest also holds a secret: both are counted
        assert_eq!(redact("Bearer abc password=x"), ("Bearer [redacted] password=[redacted]".to_string(), 2));
        // a dashed secret name is read whole, a path value is masked
        assert_eq!(redact("secret-id=v").0, "secret-id=[redacted]");
        assert_eq!(redact("password=/run/x").0, "password=[redacted]");
        // JWT segments may hold - and _; a quoted JWT with a trailing comma is still one
        assert_eq!(redact("eyJa-b_c.eyJd_e-f.g-h_i").1, 1);
        assert_eq!(redact("\"eyJabcd.eyJefgh.ijkl\",").1, 1);
        assert_eq!(redact("'eyJabcd.eyJefgh.ijkl'").1, 1);
        assert_eq!(redact("eyJabcd.eyJef+h.ijkl").1, 0, "a + is not base64url");
        assert_eq!(redact("eyJabcd.eyJefgh").1, 0, "two segments are not a JWT");
    }

    #[test]
    fn counts_add_up_over_lines() {
        let (out, n) = redact("TOKEN=a\nx@y.z\nfine");
        assert_eq!(out, "TOKEN=[redacted]\n[redacted]\nfine");
        assert_eq!(n, 2);
    }
}
