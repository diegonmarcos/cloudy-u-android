//! #1955 C health-core: the check logic of Cloud C3's Health and X-ray (owner rule: health check logic is always Rust). Kotlin only
//! declares the UI, parses the c3-report/v1 envelope, triggers runs and renders them.
//!
//! - [model]: the envelope and the statuses (schemas/c3-report-v1.schema.json is the contract, fixtures/ the shared test files)
//! - [graph]: expand, attribute (root cause), infer, gate, layer light
//! - [redact], [diff], [budget], [merge]
//! - [prober]: phased dns / tcp / tls / http on one socket; [matrix]: the mesh prober plan A to F
//! - [schema]: a small validator of the envelope against the schema file (required keys, types, enums)

pub mod budget;
pub mod diff;
pub mod graph;
pub mod json;
pub mod matrix;
pub mod merge;
pub mod model;
pub mod prober;
pub mod redact;
pub mod schema;

pub const VERSION: &str = env!("CARGO_PKG_VERSION");
