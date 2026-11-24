# Compose parity (#1837 phase 3)
Both engines build a message from the same inputs and the PARSER of each engine must read the same facts back
(tests/compose_parity.rs for Rust; MimeParserParityFixturesTest.composeParity for Kotlin OutgoingMime).
Facts: subject "Reunión: España", body phrase "Mañana en Málaga, señor Muñoz.", one attachment "nota.pdf | application/pdf".
