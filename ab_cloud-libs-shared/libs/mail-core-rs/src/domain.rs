//! The sender's registrable domain, the key of "Group by Domain". The same rule as Cloud Mail's Kotlin `SenderDomain`
//! (core/data .../mail/SenderDomain.kt): the host after the last `@`, lower-cased, the last two labels, or three under a known
//! multi-label public suffix. Kept identical so a domain group in the store and in the app's list name the same senders.

const MULTI_LABEL_SUFFIXES: &[&str] = &[
    "co.uk", "org.uk", "ac.uk", "gov.uk", "me.uk", "ltd.uk", "plc.uk", "net.uk", "sch.uk", "nhs.uk", "com.br", "net.br", "org.br",
    "gov.br", "edu.br", "com.au", "net.au", "org.au", "edu.au", "gov.au", "id.au", "co.jp", "ne.jp", "or.jp", "ac.jp", "go.jp",
    "gr.jp", "co.nz", "net.nz", "org.nz", "govt.nz", "co.za", "org.za", "co.in", "net.in", "org.in", "gov.in", "co.kr", "or.kr",
    "co.id", "co.il", "co.th", "com.mx", "com.ar", "com.tr", "com.cn", "net.cn", "org.cn", "com.hk", "com.tw", "com.sg", "com.my",
    "com.ua", "com.pl", "com.es", "com.pt",
];

fn label_ok(l: &str) -> bool {
    let chars: Vec<char> = l.chars().collect();
    if chars.is_empty() {
        return false;
    }
    let edge = |c: char| c.is_alphanumeric();
    edge(chars[0]) && edge(*chars.last().unwrap()) && chars.iter().all(|c| c.is_alphanumeric() || *c == '-')
}

/// The host of an address, or None when it has no `@` or a label is not a hostname label.
pub fn host(address: &str) -> Option<String> {
    let raw = address.trim();
    let at = raw.rfind('@')?;
    let host = raw[at + 1..].trim().to_lowercase();
    let host = host.trim_end_matches('.').to_string();
    if host.is_empty() || !host.split('.').all(label_ok) {
        return None;
    }
    Some(host)
}

/// The registrable domain of an address; "" when there is none (the app's `SenderDomain.indexed`).
pub fn registrable(address: &str) -> String {
    let Some(h) = host(address) else { return String::new() };
    let labels: Vec<&str> = h.split('.').collect();
    if labels.len() <= 2 {
        return h;
    }
    let last_two = labels[labels.len() - 2..].join(".");
    let keep = if MULTI_LABEL_SUFFIXES.contains(&last_two.as_str()) { 3 } else { 2 };
    labels[labels.len() - keep..].join(".")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn registrable_matches_the_apps_rule() {
        assert_eq!(registrable("a@mail.google.com"), "google.com");
        assert_eq!(registrable("x@news.bbc.co.uk"), "bbc.co.uk");
        assert_eq!(registrable("x@Example.ORG."), "example.org");
        assert_eq!(registrable("no-at-sign"), "");
        assert_eq!(registrable("x@bad_label.com"), "");
        assert_eq!(registrable("x@sede.agenciatributaria.gob.es"), "gob.es");
    }
}
