//! Content-blocking host matching. Rules, one per line: `||host^` or `host` blocks that host and its subdomains,
//! `@@||host^` is an exception; blank lines, `!` and `#` comments are skipped; a rule with no dot (a bare TLD) never matches.

use std::collections::HashSet;

#[derive(Default)]
pub struct FilterSet {
    block: HashSet<String>,
    allow: HashSet<String>,
    /// `/segment` rules: a sub-request whose url contains the segment is blocked (an adblock-style path rule), unless an exception matches.
    paths: Vec<String>,
    path_allow: Vec<String>,
}

/// Several downloaded lists as one rule text: lines trimmed, comments and blanks dropped, duplicates removed, first-seen order kept.
/// This is the list-update step: the app downloads the lists, the core merges them, [FilterSet::parse] compiles the result.
pub fn merge(lists: &[String]) -> String {
    let mut seen = HashSet::new();
    let mut out = Vec::new();
    for l in lists {
        for line in l.lines() {
            let t = line.trim();
            if t.is_empty() || t.starts_with('!') || t.starts_with('#') {
                continue;
            }
            if seen.insert(t.to_string()) {
                out.push(t.to_string());
            }
        }
    }
    out.join("\n")
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
            // options (`$third-party`, `$script,image`) are not matched on: the rule applies to every sub-request
            let body = body.split('$').next().unwrap_or("");
            if let Some(seg) = body.strip_prefix('/') {
                if !seg.is_empty() {
                    let rule = format!("/{}", seg.to_lowercase());
                    if exception { f.path_allow.push(rule) } else { f.paths.push(rule) }
                }
                continue;
            }
            let host = clean(body);
            if !host.contains('.') {
                continue;
            }
            if exception { f.allow.insert(host); } else { f.block.insert(host); }
        }
        f
    }

    pub fn len(&self) -> usize {
        self.block.len() + self.paths.len()
    }

    /// [Self::should_block] plus the path rules: a sub-request to `url` is blocked when its host is listed, or its url holds a blocked
    /// `/segment`, and no exception holds. Never the main frame.
    pub fn should_block_url(&self, url: &str, request_host: &str, page_host: &str, main_frame: bool) -> bool {
        if main_frame {
            return false;
        }
        if self.should_block(request_host, page_host, false) {
            return true;
        }
        if self.paths.is_empty() {
            return false;
        }
        let u = url.to_lowercase();
        self.paths.iter().any(|p| u.contains(p.as_str())) && !self.path_allow.iter().any(|p| u.contains(p.as_str()))
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
