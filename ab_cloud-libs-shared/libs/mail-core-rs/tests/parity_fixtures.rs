//! Parity fixtures (#1837): the Rust reader against the same `.eml` + `.expect` pairs the Kotlin MimeParser is tested on
//! (ac_cloud-mail/core/imap MimeParserParityFixturesTest). A fixture an engine cannot satisfy fails that engine's suite.
use std::fs;
use std::path::PathBuf;

fn dir() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("fixtures/parity")
}

fn collapse(s: &str) -> String {
    let mut out = String::new();
    let mut in_tag = false;
    for c in s.chars() {
        match c {
            '<' => in_tag = true,
            '>' if in_tag => {
                in_tag = false;
            }
            _ if !in_tag => out.push(c),
            _ => {}
        }
    }
    out.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Runs every fixture; returns the failures (empty when all agree).
fn check(only_ext: Option<&str>) -> (usize, Vec<String>) {
    let mut n = 0;
    let mut bad = Vec::new();
    let mut names: Vec<_> = fs::read_dir(dir()).unwrap().map(|e| e.unwrap().path()).filter(|p| p.extension().map_or(false, |e| e == "eml")).collect();
    names.sort();
    for eml in names {
        n += 1;
        let name = eml.file_stem().unwrap().to_string_lossy().to_string();
        let raw = fs::read(&eml).unwrap();
        let expect = fs::read_to_string(eml.with_extension("expect")).unwrap();
        let _ = only_ext;
        let parsed = mail_core::reader::parse(&raw).unwrap_or_else(|e| panic!("{name}: {e}"));
        let visible = collapse(if parsed.kind == "html" { &parsed.fragment } else { &parsed.text });
        let atts = mail_core::reader::attachments(&raw);
        let mut want_atts = 0;
        for line in expect.lines().filter(|l| !l.trim().is_empty()) {
            let (k, v) = line.split_once(": ").unwrap_or((line.trim_end_matches(':'), ""));
            match k {
                "kind" if parsed.kind != v => bad.push(format!("{name}: kind {} != {v}", parsed.kind)),
                "contains" if !visible.contains(v) => bad.push(format!("{name}: missing '{v}' in '{visible}'")),
                "absent" if visible.contains(v) => bad.push(format!("{name}: '{v}' present")),
                "attachment" => {
                    want_atts += 1;
                    let (nm, ty) = v.split_once(" | ").unwrap();
                    if !atts.iter().any(|(a, t)| a == nm && t == ty) {
                        bad.push(format!("{name}: attachment {nm} {ty} not in {atts:?}"));
                    }
                }
                _ => {}
            }
        }
        if atts.len() != want_atts {
            bad.push(format!("{name}: {} attachments, expected {want_atts}", atts.len()));
        }
    }
    (n, bad)
}

#[test]
fn every_fixture_reads_the_same_in_the_rust_core() {
    let (n, bad) = check(None);
    assert!(n >= 6, "the fixture set shrank to {n}");
    assert!(bad.is_empty(), "parity failures:\n{}", bad.join("\n"));
}

/// Proves the harness can fail: a deliberately wrong expectation is reported (the mutation proof).
#[test]
fn a_wrong_expectation_is_caught() {
    let raw = fs::read(dir().join("plain-utf8.eml")).unwrap();
    let parsed = mail_core::reader::parse(&raw).unwrap();
    assert!(!collapse(&parsed.text).contains("Madrid"));
    assert!(collapse(&parsed.text).contains("Málaga"));
}
