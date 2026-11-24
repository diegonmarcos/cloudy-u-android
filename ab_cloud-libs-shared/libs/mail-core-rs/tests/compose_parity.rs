//! Compose parity (#1837 phase 3): what the Rust builder writes reads back, through mail-parser, as the same facts the Kotlin
//! OutgoingMime output reads back as through the Kotlin MimeParser (MimeParserParityFixturesTest.composeParity).
use mail_core::compose::{build, Attachment, Compose};

fn sample(html: bool, att: bool) -> Compose {
    Compose {
        from: "Alice <alice@example.com>".into(),
        to: vec!["bob@example.com".into()],
        subject: "Reunión: España".into(),
        text: "Mañana en Málaga, señor Muñoz.".into(),
        html: if html { Some("<p>Mañana en <b>Málaga</b>, señor Muñoz.</p>".into()) } else { None },
        message_id: "id-1@example.com".into(),
        date: "Mon, 1 Jan 2024 00:00:00 +0000".into(),
        attachments: if att { vec![Attachment { name: "nota.pdf".into(), ty: "application/pdf".into(), data: "UERGLURBVEE=".into() }] } else { vec![] },
        ..Default::default()
    }
}

fn read(c: &Compose) -> (String, mail_core::reader::Parsed, Vec<(String, String)>) {
    let raw = build(c);
    let msg = mail_parser::MessageParser::default().parse(raw.as_bytes()).expect("parses");
    let subj = msg.subject().unwrap_or("").to_string();
    (subj, mail_core::reader::parse(raw.as_bytes()).unwrap(), mail_core::reader::attachments(raw.as_bytes()))
}

#[test]
fn plain_html_and_attachment_variants_read_back_the_same_facts() {
    for (html, att) in [(false, false), (true, false), (false, true), (true, true)] {
        let (subj, p, atts) = read(&sample(html, att));
        assert_eq!(subj, "Reunión: España", "html={html} att={att}");
        assert!(p.text.contains("Mañana en Málaga, señor Muñoz."), "html={html} att={att}: {:?}", p.text);
        assert_eq!(p.kind, if html { "html" } else { "text" });
        assert!(!p.encoding_problem);
        assert_eq!(atts, if att { vec![("nota.pdf".to_string(), "application/pdf".to_string())] } else { vec![] });
    }
}

#[test]
fn header_injection_and_bcc_are_refused() {
    let mut c = sample(false, false);
    c.to = vec!["bob@example.com\r\nX-Injected: evil".into()];
    c.bcc = vec!["hidden@example.com".into()];
    let raw = build(&c);
    assert!(!raw.contains("\r\nX-Injected:"));
    assert!(!raw.contains("Bcc:"));
    c.write_bcc = true;
    assert!(build(&c).contains("Bcc: hidden@example.com"));
}

#[test]
fn a_long_subject_is_split_in_valid_encoded_words() {
    let mut c = sample(false, false);
    c.subject = "Ñandú ".repeat(30);
    let (subj, _, _) = read(&c);
    assert_eq!(subj, "Ñandú ".repeat(30));
}
