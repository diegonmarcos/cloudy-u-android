# IMAP transcript parity (#1837 phase 3)
`transcript.txt` is the scripted server both engines read: the Rust client (tests/imap_fetch.rs, std only, over an in-memory pipe)
and the Kotlin ImapSession (MimeParserParityFixturesTest.imapTranscript, over FakeImapServer). Blocks start with `## <command prefix>`;
the body is the answer, `{tag}` the command's tag, `{BODY}` the raw message `latin1-qp.eml` (parity fixtures) and `{LEN}` its length.
Both must end with UID 7, UIDVALIDITY 42, EXISTS 1 and the identical source bytes.
