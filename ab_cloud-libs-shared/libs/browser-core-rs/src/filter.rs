//! Content-blocking host matching. Rules, one per line: `||host^` or `host` blocks that host and its subdomains,
//! `@@||host^` is an exception; blank lines, `!` and `#` comments are skipped; a rule with no dot (a bare TLD) never matches.

use std::collections::HashSet;

#[derive(Default)]
pub struct FilterSet {
    block: HashSet<String>,
    allow: HashSet<String>,
}

fn clean(rule: &str) -> String {
    rule.trim().trim_start_matches("||").trim_end_matches('^').trim().trim_end_matches('.').to_lowercase()
}

/// `a.b.c` -> `a.b.c`, `b.c`, `c`.
fn suffixes(host: &str) -> Vec<String> {
    let l = host.to_lowercase();
    let parts: Vec<&str> = l.split('.').filter(|p| !p.is_empty()).collect();
    (0..parts.len()).map(|i| parts[i..].join(".")).collect()
}

impl FilterSet {
    pub fn parse(rules: &str) -> FilterSet {
        let mut f = FilterSet::default();
        for line in rules.lines() {
            let line = line.trim();
            if line.is_empty() || line.starts_with('!') || line.starts_with('#') {
                continue;
            }
            let (exception, body) = match line.strip_prefix("@@") {
                Some(b) => (true, b),
                None => (false, line),
            };
            let host = clean(body);
            if !host.contains('.') {
                continue;
            }
            if exception { f.allow.insert(host); } else { f.block.insert(host); }
        }
        f
    }

    pub fn len(&self) -> usize {
        self.block.len()
    }

    pub fn is_empty(&self) -> bool {
        self.block.is_empty()
    }

    /// `host` is a listed host or a subdomain of one (and not excepted).
    pub fn is_tracker(&self, host: &str) -> bool {
        let sfx = suffixes(host);
        sfx.iter().any(|s| s.contains('.') && self.block.contains(s)) && !sfx.iter().any(|s| s.contains('.') && self.allow.contains(s))
    }

    /// Answer this request empty? Never the main frame; never when the page is itself on a listed host.
    pub fn should_block(&self, request_host: &str, page_host: &str, main_frame: bool) -> bool {
        if main_frame || request_host.is_empty() || !self.is_tracker(request_host) {
            return false;
        }
        !self.is_tracker(page_host)
    }
}
