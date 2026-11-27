//! The string API. Every function takes strings and answers `Ok(json)` or `Err(reason)`; [guard] turns that (and any panic) into the
//! `OK\n<json>` / `ERR\t<reason>` answer the JVM reads. Layers are given as JSON `[{id,label,rank,depends_on[]}]`.

use health_core::graph::{attribute, layer_light, Layer, Light};
use health_core::json::{self, obj, parse, s, Json};
use health_core::matrix::{plan, MeshDecl};
use health_core::model::Report;
use health_core::prober::probe as run_probe;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::time::Duration;

pub fn version() -> String {
    health_core::VERSION.to_string()
}

fn one_line(t: &str) -> String {
    t.replace(['\n', '\r', '\t'], " ")
}

/// Run `f`; Ok is the answer body, an Err or a panic is `ERR`.
pub fn guard<F: FnOnce() -> Result<String, String>>(f: F) -> String {
    match catch_unwind(AssertUnwindSafe(f)) {
        Ok(Ok(b)) => format!("OK\n{}", b),
        Ok(Err(e)) => format!("ERR\t{}", one_line(&e)),
        Err(p) => {
            let m = p.downcast_ref::<&str>().map(|x| x.to_string()).or_else(|| p.downcast_ref::<String>().cloned()).unwrap_or_else(|| "unknown panic".into());
            format!("ERR\tpanic: {}", one_line(&m))
        }
    }
}

fn num(n: u64) -> Json {
    Json::Num(n as f64)
}

fn layers_of(text: &str) -> Result<Vec<Layer>, String> {
    let v = parse(text)?;
    let Json::Arr(items) = v else { return Err("layers: not an array".into()) };
    Ok(items.iter().map(|l| Layer { id: l.str("id"), label: l.str("label"), rank: l.num("rank").unwrap_or(0.0) as u32, depends_on: l.strs("depends_on") }).collect())
}

fn light_id(l: Light) -> &'static str {
    match l {
        Light::Red => "red",
        Light::RedHollow => "red_hollow",
        Light::Amber => "amber",
        Light::Green => "green",
        Light::Grey => "grey",
    }
}

/// One phased probe of a URL (dns, tcp, tls, http on one socket): the status, the phase times, the code and the failed phase.
pub fn probe(url: &str, timeout_ms: u64) -> Result<String, String> {
    let p = run_probe(url, Duration::from_millis(timeout_ms.clamp(200, 30_000)));
    let o = |v: Option<u64>| v.map(num).unwrap_or(Json::Null);
    Ok(json::write(&obj(vec![
        ("status", s(p.status.id())),
        ("phases", obj(vec![("dns", o(p.phases.dns)), ("tcp", o(p.phases.tcp)), ("tls", o(p.phases.tls)), ("http", o(p.phases.http))])),
        ("code", p.code.map(|c| Json::Num(c as f64)).unwrap_or(Json::Null)),
        ("error", s(&p.error)),
        ("addr", s(&p.addr)),
    ])))
}

/// Attribution over a c3-report/v1 envelope: the roots (ranked), every check with its `explained_by`, and one light with its six counts per
/// declared layer, in the layers' rank order (lowest first).
pub fn analyze(report_json: &str, layers_json: &str) -> Result<String, String> {
    let mut r = Report::from_json(&parse(report_json)?)?;
    let mut layers = layers_of(layers_json)?;
    layers.sort_by(|a, b| a.rank.cmp(&b.rank).then(a.id.cmp(&b.id)));
    r.roots = attribute(&mut r.checks, &layers);
    let lights: Vec<Json> = layers.iter().map(|l| {
        let (light, k) = layer_light(&l.id, &r.checks);
        obj(vec![
            ("layer", s(&l.id)), ("label", s(&l.label)), ("rank", num(l.rank as u64)), ("light", s(light_id(light))),
            ("counts", obj(vec![("ok", num(k.ok as u64)), ("warn", num(k.warn as u64)), ("down", num(k.down as u64)), ("caused", num(k.caused as u64)), ("blocked", num(k.blocked as u64)), ("not_served", num(k.not_served as u64))])),
        ])
    }).collect();
    Ok(json::write(&obj(vec![
        ("status", s(r.status())),
        ("roots", Json::Arr(r.roots.iter().map(|x| s(x)).collect())),
        ("checks", Json::Arr(r.checks.iter().map(|c| c.to_json()).collect())),
        ("lights", Json::Arr(lights)),
    ])))
}

/// Merge of two vantages' reports (the phone's and the fleet's): one row per check id with every reading.
pub fn merge(phone_json: &str, fleet_json: &str) -> Result<String, String> {
    let a = Report::from_json(&parse(phone_json)?)?;
    let b = Report::from_json(&parse(fleet_json)?)?;
    let m = health_core::merge::merge(&[("phone", a.checks), ("fleet", b.checks)]);
    Ok(json::write(&Json::Arr(m.iter().map(|(c, rs)| obj(vec![
        ("check", c.to_json()),
        ("readings", Json::Arr(rs.iter().map(|(v, st)| obj(vec![("vantage", s(v)), ("status", s(st.id()))])).collect())),
    ])).collect())))
}

/// The mesh prober plan A to F from a declaration `{private_names[], public_controls[], hubs_v4[], hubs_v6[], vm_wg_ips[[vm,ip]], wstunnel_endpoint}`.
pub fn plan_mesh(decl_json: &str) -> Result<String, String> {
    let v = parse(decl_json)?;
    let d = MeshDecl {
        private_names: v.strs("private_names"), public_controls: v.strs("public_controls"), hubs_v4: v.strs("hubs_v4"), hubs_v6: v.strs("hubs_v6"),
        vm_wg_ips: v.arr("vm_wg_ips").iter().filter_map(|p| match p { Json::Arr(x) if x.len() == 2 => Some((x[0].as_str()?.to_string(), x[1].as_str()?.to_string())), _ => None }).collect(),
        wstunnel_endpoint: v.str("wstunnel_endpoint"),
    };
    Ok(json::write(&Json::Arr(plan(&d).iter().map(|sp| obj(vec![
        ("group", s(&sp.group.to_string())), ("id", s(&sp.id)), ("target", s(&sp.target)), ("not_served", s(&sp.not_served)),
    ])).collect())))
}

/// Redaction of one text: `{text, redactions}`.
pub fn redact(text: &str) -> Result<String, String> {
    let (t, n) = health_core::redact::redact(text);
    Ok(json::write(&obj(vec![("text", s(&t)), ("redactions", num(n as u64))])))
}

#[cfg(test)]
mod tests {
    use super::*;
    use health_core::model::{Check, Status};

    const LAYERS: &str = r#"[{"id":"lights","label":"Lights","rank":9,"depends_on":["vms"]},{"id":"vms","label":"VMs","rank":1,"depends_on":[]}]"#;

    fn report(checks: Vec<Check>) -> String {
        Report { engine: "health-core".into(), engine_version: "0".into(), run_id: "r".into(), runner: "phone".into(), vantage: "phone:t".into(),
            started: String::new(), finished: String::new(), checks, roots: vec![], missing: vec![], redactions: 0 }.write()
    }

    #[test]
    fn analyze_attributes_the_root_and_lists_the_layers_in_rank_order() {
        let r = report(vec![Check::new("vms.alive:a", "vms", Status::Down), Check::new("lights.x", "lights", Status::Down).needs(&["vms.alive:a"])]);
        let out = parse(&analyze(&r, LAYERS).unwrap()).unwrap();
        assert_eq!(out.strs("roots"), vec!["vms.alive:a"]);
        let lights = out.arr("lights");
        assert_eq!(lights.iter().map(|l| l.str("layer")).collect::<Vec<_>>(), vec!["vms", "lights"], "lowest rank first");
        assert_eq!(lights[0].str("light"), "red");
        assert_eq!(lights[1].str("light"), "red_hollow", "caused by a root in another layer");
    }

    #[test]
    fn a_missing_token_style_blind_check_blames_no_one() {
        let r = report(vec![Check::new("vms.alive:a", "vms", Status::Blind).details("device: no C3 token")]);
        let out = parse(&analyze(&r, LAYERS).unwrap()).unwrap();
        assert!(out.strs("roots").is_empty(), "a blind check is never a root");
    }

    #[test]
    fn a_panic_or_an_error_is_an_err_answer_never_an_unwind() {
        assert!(guard(|| Err("x\ty".into())).starts_with("ERR\tx y"));
        assert!(guard(|| -> Result<String, String> { panic!("boom") }).starts_with("ERR\tpanic: boom"));
        assert!(guard(|| analyze("not json", LAYERS)).starts_with("ERR\t"));
        assert!(guard(|| Ok("{}".into())).starts_with("OK\n"));
    }

    #[test]
    fn probe_reports_phases_for_a_local_listener_and_a_dns_failure_for_a_bad_url() {
        let l = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let port = l.local_addr().unwrap().port();
        let out = parse(&probe(&format!("tcp://127.0.0.1:{}", port), 2000).unwrap()).unwrap();
        assert_eq!(out.str("status"), "ok");
        assert!(out.get("phases").unwrap().num("tcp").is_some());
        let bad = parse(&probe("nonsense", 500).unwrap()).unwrap();
        assert_eq!(bad.str("status"), "down");
        assert!(bad.str("error").starts_with("dns:"));
    }

    #[test]
    fn merge_plan_and_redact_pass_through() {
        let a = report(vec![Check::new("c", "lights", Status::Ok)]);
        let b = report(vec![Check::new("c", "lights", Status::Warn)]);
        let m = parse(&merge(&a, &b).unwrap()).unwrap();
        let Json::Arr(rows) = m else { panic!() };
        assert_eq!(rows[0].get("check").unwrap().str("status"), "warn");
        assert_eq!(rows[0].arr("readings").len(), 2);
        let p = parse(&plan_mesh(r#"{"hubs_v4":["10.0.0.1"]}"#).unwrap()).unwrap();
        let Json::Arr(specs) = p else { panic!() };
        assert!(specs.iter().any(|x| x.str("target") == "10.0.0.1:53"));
        let r = parse(&redact("Authorization: Bearer abcdefghijklmnop").unwrap()).unwrap();
        assert!(r.num("redactions").unwrap() >= 1.0);
    }
}
