//! The text wire shared with Kotlin: records are lines, fields are tab-separated, and `\`, tab, CR and LF are escaped in a field.

pub fn esc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '\\' => o.push_str("\\\\"),
            '\t' => o.push_str("\\t"),
            '\n' => o.push_str("\\n"),
            '\r' => o.push_str("\\r"),
            _ => o.push(c),
        }
    }
    o
}

pub fn unesc(s: &str) -> String {
    let mut o = String::with_capacity(s.len());
    let mut it = s.chars();
    while let Some(c) = it.next() {
        if c != '\\' {
            o.push(c);
            continue;
        }
        match it.next() {
            Some('t') => o.push('\t'),
            Some('n') => o.push('\n'),
            Some('r') => o.push('\r'),
            Some('\\') => o.push('\\'),
            Some(x) => {
                o.push('\\');
                o.push(x);
            }
            None => o.push('\\'),
        }
    }
    o
}

/// Split a text into its non-empty lines and unescape each (a list of paths, names, ...).
pub fn lines(text: &str) -> Vec<String> {
    text.split('\n').filter(|l| !l.is_empty()).map(unesc).collect()
}

/// Split a record line into its unescaped fields.
pub fn fields(line: &str) -> Vec<String> {
    line.split('\t').map(unesc).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn escapes_round_trip() {
        for s in ["plain", "a\tb", "a\nb", "a\\b", "a\\tb", "\\", "tab\t\nnl\r\\x", "ünï/çødé"] {
            assert_eq!(unesc(&esc(s)), s, "{s:?}");
            assert!(!esc(s).contains('\t') && !esc(s).contains('\n'));
        }
    }

    #[test]
    fn lines_skip_empty_and_unescape() {
        assert_eq!(lines("a\n\nb\\nc\n"), vec!["a".to_string(), "b\nc".to_string()]);
    }
}
