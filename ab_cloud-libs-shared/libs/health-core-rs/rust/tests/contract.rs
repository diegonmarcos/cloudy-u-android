//! The c3-report/v1 contract: the fixture validates against the schema, survives a parse/write round trip, and an engine-built report
//! from the gcp_proxy_oom graph validates too. The Kotlin envelope parser test reads the same files.

use health_core::graph::{attribute, infer, Layer};
use health_core::json::{parse, Json};
use health_core::model::{Check, Report, Status};
use health_core::schema::validate;

fn root() -> std::path::PathBuf {
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}
fn load(p: &str) -> Json {
    parse(&std::fs::read_to_string(root().join(p)).unwrap()).unwrap()
}

#[test]
fn the_fixture_matches_the_schema_and_round_trips() {
    let schema = load("schemas/c3-report-v1.schema.json");
    let fx = load("fixtures/report_gcp_proxy_oom.json");
    assert_eq!(validate(&schema, &fx), Vec::<String>::new());
    let r = Report::from_json(&fx).unwrap();
    assert_eq!(r.roots, vec!["vms.alive:gcp-E2-f_0"]);
    assert_eq!(r.status(), "RED");
    let again = parse(&r.write()).unwrap();
    assert_eq!(validate(&schema, &again), Vec::<String>::new());
    assert_eq!(Report::from_json(&again).unwrap(), r);
    // the summary the engine computes equals the fixture's
    assert_eq!(again.get("summary"), fx.get("summary"));
}

#[test]
fn a_built_report_validates_and_unknown_kinds_never_carry_numbers() {
    let layers = vec![Layer { id: "vms".into(), label: "VMs".into(), rank: 1, depends_on: vec![] }];
    let mut cs = vec![
        Check::new("p1", "mesh", Status::Down), Check::new("p2", "vms", Status::Down),
        Check::new("vm", "vms", Status::Blind).evidence(&["p1", "p2"]),
        Check::new("svc", "lights", Status::Down).needs(&["vm"]),
        Check::new("ns", "data", Status::NotServed).details("not served: report family not published yet"),
    ];
    let all = cs.clone();
    infer(&mut cs[2], &all, 2);
    let roots = attribute(&mut cs, &layers);
    let r = Report { engine: "health-core".into(), engine_version: health_core::VERSION.into(), run_id: "t".into(), runner: "phone".into(),
        vantage: "phone:test".into(), started: "a".into(), finished: "b".into(), checks: cs, roots, missing: vec![], redactions: 0 };
    let v = parse(&r.write()).unwrap();
    assert_eq!(validate(&load("schemas/c3-report-v1.schema.json"), &v), Vec::<String>::new());
    for c in v.arr("checks") {
        let st = Status::parse(&c.str("status"));
        if !st.carries_number() {
            assert_eq!(c.get("code"), Some(&Json::Null), "{} carries a code", c.str("id"));
            assert!(!c.str("details").is_empty() || st == Status::Down || st == Status::Pending || st == Status::Blind && c.str("confidence") == "measured",
                "{} has no reason", c.str("id"));
        }
    }
    // a broken envelope is reported, not accepted
    let mut bad = v.clone();
    if let Json::Obj(kv) = &mut bad { kv.retain(|(k, _)| k != "checks"); }
    assert!(!validate(&load("schemas/c3-report-v1.schema.json"), &bad).is_empty());
    assert!(Report::from_json(&parse(r#"{"schema":"other"}"#).unwrap()).is_err());
}
