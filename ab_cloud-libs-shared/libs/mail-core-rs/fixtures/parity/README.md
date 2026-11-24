# Cross-engine parity fixtures (#1837)

Each `NAME.eml` is a raw message, `NAME.expect` the facts BOTH engines must report for it: the Rust core (`tests/parity_fixtures.rs`)
and the Kotlin engine (`ac_cloud-mail/core/imap/src/test/.../MimeParserParityFixturesTest.kt`). Lines: `kind: html|text`,
`contains: <phrase>` (in the visible text, whitespace collapsed, tags stripped), `absent: <phrase>`, `attachment: <name> | <mime type>`.
The Kotlin engine stays until both suites are green on every fixture; add a fixture here, never edit one to fit an engine.
