//! File-tree diff for sync: two listings of `path, size, mtime ms [, hash]`, the delta out.
//!
//! A path only on the right is ADDED, only on the left is REMOVED. On both sides it is CHANGED when the sizes differ or, when both
//! sides carry a hash, the hashes differ; without a hash on both sides the mtimes decide too (a different mtime with the same size
//! is a change: it cannot be told from a rewrite without a hash). The answer is sorted by path.

use std::collections::HashMap;

use crate::wire;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Item {
    pub path: String,
    pub size: u64,
    pub mtime: i64,
    pub hash: String,
}

pub fn parse(text: &str) -> Vec<Item> {
    text.split('\n')
        .filter(|l| !l.is_empty())
        .filter_map(|l| {
            let f = wire::fields(l);
            if f.len() < 3 {
                return None;
            }
            Some(Item { path: f[0].clone(), size: f[1].parse().ok()?, mtime: f[2].parse().ok()?, hash: f.get(3).cloned().unwrap_or_default() })
        })
        .collect()
}

pub fn changed(l: &Item, r: &Item) -> bool {
    if l.size != r.size {
        return true;
    }
    if !l.hash.is_empty() && !r.hash.is_empty() {
        return l.hash != r.hash;
    }
    l.mtime != r.mtime
}

/// (kind, path) with kind one of `A` (added: right only), `D` (deleted: left only), `M` (modified).
pub fn diff(left: &[Item], right: &[Item]) -> Vec<(char, String)> {
    let lm: HashMap<&str, &Item> = left.iter().map(|i| (i.path.as_str(), i)).collect();
    let rm: HashMap<&str, &Item> = right.iter().map(|i| (i.path.as_str(), i)).collect();
    let mut out: Vec<(char, String)> = Vec::new();
    for (p, l) in &lm {
        match rm.get(p) {
            None => out.push(('D', p.to_string())),
            Some(r) if changed(l, r) => out.push(('M', p.to_string())),
            _ => {}
        }
    }
    for p in rm.keys() {
        if !lm.contains_key(p) {
            out.push(('A', p.to_string()));
        }
    }
    out.sort_by(|a, b| a.1.cmp(&b.1).then(a.0.cmp(&b.0)));
    out
}

pub fn encode(d: &[(char, String)]) -> String {
    d.iter().map(|(k, p)| format!("{}\t{}\n", k, wire::esc(p))).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn it(p: &str, s: u64, m: i64, h: &str) -> Item {
        Item { path: p.into(), size: s, mtime: m, hash: h.into() }
    }

    #[test]
    fn added_removed_modified() {
        let l = vec![it("keep", 1, 1, ""), it("gone", 1, 1, ""), it("size", 1, 1, ""), it("time", 1, 1, ""), it("same-hash", 1, 1, "h"), it("diff-hash", 1, 1, "h"), it("one-hash", 1, 1, "h")];
        let r = vec![it("keep", 1, 1, ""), it("new", 9, 9, ""), it("size", 2, 1, ""), it("time", 1, 2, ""), it("same-hash", 1, 5, "h"), it("diff-hash", 1, 1, "g"), it("one-hash", 1, 2, "")];
        let d = diff(&l, &r);
        assert_eq!(
            d,
            vec![('M', "diff-hash".into()), ('D', "gone".into()), ('A', "new".into()), ('M', "one-hash".into()), ('M', "size".into()), ('M', "time".into())]
        );
        assert_eq!(encode(&d).lines().count(), 6);
    }

    #[test]
    fn parses_the_wire_and_skips_bad_lines() {
        let v = parse("a\\tb\t5\t7\thh\nbad\nc\tx\t1\n\nd\t2\t3\n");
        assert_eq!(v, vec![it("a\tb", 5, 7, "hh"), it("d", 2, 3, "")]);
    }
}
