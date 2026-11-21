//! The address bar's decisions (#1841): is what was typed a destination or a search, the scheme of a bare host, the search URL, and the
//! start-up decision (what the browser shows when it starts: the open url, the kept tab, the last tab, the grid with an offer to restore).
//! The same rules as the Kotlin BrowserSearch / host start code they replace; the Kotlin twins stay as the fallback.

/// Hosts with no TLS at the other end: `localhost`, `127.0.0.1`, `10.*`, `192.168.*`, `172.16-31.*`.
pub fn is_private_host(host: &str) -> bool {
    if host == "localhost" || host == "127.0.0.1" || host.starts_with("10.") || host.starts_with("192.168.") {
        return true;
    }
    if let Some(rest) = host.strip_prefix("172.") {
        let b: Vec<char> = rest.chars().take(3).collect();
        if b.len() == 3 && b[2] == '.' {
            let ok = match (b[0], b[1]) {
                ('1', d) => ('6'..='9').contains(&d),
                ('2', d) => d.is_ascii_digit(),
                ('3', d) => d == '0' || d == '1',
                _ => false,
            };
            return ok;
        }
    }
    false
}

fn host_of(s: &str) -> &str {
    let h = s.split('/').next().unwrap_or("");
    h.split(':').next().unwrap_or("")
}

pub fn is_url_like(raw: &str) -> bool {
    let s = raw.trim();
    if s.is_empty() {
        return false;
    }
    if s.contains("://") {
        return true;
    }
    if s.chars().any(char::is_whitespace) {
        return false;
    }
    let host = host_of(s);
    if is_private_host(host) || host.ends_with(".local") || host.ends_with(".lan") {
        return true;
    }
    match host.rfind('.') {
        Some(dot) if dot > 0 && dot + 1 < host.len() => {
            let tld = &host[dot + 1..];
            tld.chars().count() >= 2 && tld.chars().all(char::is_alphabetic)
        }
        _ => false,
    }
}

pub fn normalize_url(raw: &str) -> String {
    let s = raw.trim();
    if s.is_empty() {
        return String::new();
    }
    if s.contains("://") {
        return s.to_string();
    }
    let host = host_of(s);
    if is_private_host(host) || host.ends_with(".local") || host.ends_with(".lan") {
        format!("http://{s}")
    } else {
        format!("https://{s}")
    }
}

/// java.net.URLEncoder.encode(s, "UTF-8"): unreserved `A-Za-z0-9.-*_` kept, space is `+`, everything else `%XX` per UTF-8 byte.
pub fn url_encode(s: &str) -> String {
    let mut out = String::new();
    for b in s.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'.' | b'-' | b'*' | b'_' => out.push(b as char),
            b' ' => out.push('+'),
            _ => out.push_str(&format!("%{:02X}", b)),
        }
    }
    out
}

pub fn search_url(query: &str, template: &str) -> String {
    template.replace("{q}", &url_encode(query.trim()))
}

/// THE address-bar decision: a url is navigated, anything else is searched with `template`. "" for a blank entry.
pub fn resolve(input: &str, template: &str) -> String {
    let s = input.trim();
    if s.is_empty() {
        return String::new();
    }
    if is_url_like(s) { normalize_url(s) } else { search_url(s, template) }
}

/// What the browser shows at start. `unclean`: the previous run did not end cleanly; `has_last`: a last active tab exists;
/// `restore_setting`: the user's "reopen last tab" switch; `open_url`: an incoming link not yet handled; `kept`: a tab the fragment
/// was already showing (a recreated view). Answers `(action, offer)`: action is open_url | kept | restore | grid; offer = show the
/// "Restore previous tabs" banner (an unclean end with a last tab and nothing else asked for).
pub fn start_decision(unclean: bool, has_last: bool, restore_setting: bool, open_url: bool, kept: bool) -> (&'static str, bool) {
    if open_url {
        ("open_url", false)
    } else if kept {
        ("kept", false)
    } else if has_last && restore_setting && !unclean {
        ("restore", false)
    } else {
        ("grid", unclean && has_last)
    }
}
