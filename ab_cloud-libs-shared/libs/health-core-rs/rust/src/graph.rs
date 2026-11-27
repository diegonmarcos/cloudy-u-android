//! The dependency graph: template expansion, attribution (root cause), inference, gating and the layer light.
//! Pure: checks in, checks out. The rules are the design's (#1955 C rev 1 section 2.3, rev 2: check logic is Rust).

use crate::model::{Check, Status};
use std::collections::{HashMap, HashSet};

/// One declared layer: its id, its attribution rank (lower = more fundamental) and the layers it depends on.
#[derive(Debug, Clone, PartialEq)]
pub struct Layer {
    pub id: String,
    pub label: String,
    pub rank: u32,
    pub depends_on: Vec<String>,
}

/// `container:{container}` with {container: [a, b]} -> [container:a, container:b]; several placeholders expand as a product.
pub fn expand(template: &str, vars: &HashMap<String, Vec<String>>) -> Vec<String> {
    let mut out = vec![template.to_string()];
    let mut keys: Vec<&String> = vars.keys().collect();
    keys.sort();
    for k in keys {
        let ph = format!("{{{}}}", k);
        if !template.contains(&ph) { continue; }
        let vals = &vars[k];
        let phr: &str = &ph;
        out = out.iter().flat_map(|t| vals.iter().map(move |v| t.replace(phr, v))).collect();
    }
    out
}

fn rank_of(layers: &[Layer], layer: &str) -> u32 {
    layers.iter().find(|l| l.id == layer).map(|l| l.rank).unwrap_or(u32::MAX)
}

/// Topological order, needs first; a need that is not a check is ignored; a cycle keeps declaration order for the rest.
pub fn topo(checks: &[Check]) -> Vec<usize> {
    let idx: HashMap<&str, usize> = checks.iter().enumerate().map(|(i, c)| (c.id.as_str(), i)).collect();
    let mut state = vec![0u8; checks.len()];
    let mut out = Vec::with_capacity(checks.len());
    fn visit(i: usize, checks: &[Check], idx: &HashMap<&str, usize>, state: &mut Vec<u8>, out: &mut Vec<usize>) {
        if state[i] != 0 { return; }
        state[i] = 1;
        for n in &checks[i].needs {
            if let Some(&j) = idx.get(n.as_str()) { visit(j, checks, idx, state, out); }
        }
        state[i] = 2;
        out.push(i);
    }
    for i in 0..checks.len() { visit(i, checks, &idx, &mut state, &mut out); }
    out
}

/// Attribution. A failing check (down, blind, gated) whose need is down or explained gets `explained_by` = that need's root; with several,
/// the primary is the root of the lowest layer rank, then the one explaining the most. A DOWN check with no failing need is a root.
/// Returns the roots ranked by how many checks they explain (then id).
pub fn attribute(checks: &mut Vec<Check>, layers: &[Layer]) -> Vec<String> {
    let order = topo(checks);
    let idx: HashMap<String, usize> = checks.iter().enumerate().map(|(i, c)| (c.id.clone(), i)).collect();
    // the root each check resolves to (itself for a root)
    let mut root: HashMap<usize, String> = HashMap::new();
    for &i in &order {
        let st = checks[i].status;
        if !st.failing() { checks[i].explained_by = None; continue; }
        let mut cands: Vec<String> = checks[i].needs.iter()
            .filter_map(|n| idx.get(n))
            .filter_map(|&j| root.get(&j).cloned())
            .collect();
        cands.sort();
        cands.dedup();
        if cands.is_empty() {
            checks[i].explained_by = None;
            if st == Status::Down { root.insert(i, checks[i].id.clone()); }
        } else {
            cands.sort_by(|a, b| {
                let ra = rank_of(layers, &checks[idx[a]].layer);
                let rb = rank_of(layers, &checks[idx[b]].layer);
                ra.cmp(&rb).then(a.cmp(b))
            });
            let pick = cands[0].clone();
            checks[i].explained_by = Some(pick.clone());
            root.insert(i, pick);
        }
    }
    let mut count: HashMap<String, usize> = HashMap::new();
    for c in checks.iter() {
        if let Some(r) = &c.explained_by { *count.entry(r.clone()).or_insert(0) += 1; }
    }
    let mut roots: Vec<String> = checks.iter()
        .filter(|c| c.status == Status::Down && c.explained_by.is_none())
        .map(|c| c.id.clone()).collect();
    roots.sort_by(|a, b| count.get(b).unwrap_or(&0).cmp(count.get(a).unwrap_or(&0)).then(a.cmp(b)));
    roots
}

/// Inference for an entity the vantage cannot read: DOWN (inferred) when every evidence check is DOWN and there are at least
/// `min_evidence` of them. It never infers OK: otherwise the reading stays as it is.
pub fn infer(c: &mut Check, all: &[Check], min_evidence: usize) {
    let ev: Vec<&Check> = c.evidence.iter().filter_map(|e| all.iter().find(|x| &x.id == e)).collect();
    if ev.len() >= min_evidence && min_evidence > 0 && ev.iter().all(|x| x.status == Status::Down) {
        c.status = Status::Down;
        c.confidence = "inferred".into();
        c.details = format!("inferred: {} of {} probes fail", ev.len(), c.evidence.len());
    }
}

/// Gating: checks that need a DOWN root are not probed (GATED), except the first `samples` of them, probed to confirm the gate.
/// Returns the ids to probe. If any sample answers, the caller lifts the gate and probes all.
pub fn gate(checks: &mut [Check], roots: &[String], samples: usize) -> Vec<String> {
    let rs: HashSet<&str> = roots.iter().map(|x| x.as_str()).collect();
    let mut kept = 0usize;
    let mut probe = Vec::new();
    for c in checks.iter_mut() {
        let blocked = c.needs.iter().find(|n| rs.contains(n.as_str())).cloned();
        match blocked {
            Some(r) if kept >= samples => {
                c.status = Status::Gated;
                c.details = format!("not probed: blocked by {}", r);
            }
            Some(_) => { kept += 1; probe.push(c.id.clone()); }
            None => probe.push(c.id.clone()),
        }
    }
    probe
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Light {
    Red,
    /// DOWN checks exist and all are explained by a root in ANOTHER layer
    RedHollow,
    Amber,
    Green,
    Grey,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Counts {
    pub ok: usize,
    pub warn: usize,
    pub down: usize,
    pub caused: usize,
    pub blocked: usize,
    pub not_served: usize,
}

/// The light of one layer, and the six counts its chip prints (ok / warn / down / caused / blocked / not served).
pub fn layer_light(layer: &str, checks: &[Check]) -> (Light, Counts) {
    let own: Vec<&Check> = checks.iter().filter(|c| c.layer == layer).collect();
    let layer_of: HashMap<&str, &str> = checks.iter().map(|c| (c.id.as_str(), c.layer.as_str())).collect();
    let mut k = Counts::default();
    for c in &own {
        match c.status {
            Status::Ok => k.ok += 1,
            Status::Warn => k.warn += 1,
            Status::Down if c.explained_by.is_some() => k.caused += 1,
            Status::Down => k.down += 1,
            Status::Gated => k.blocked += 1,
            Status::NotServed => k.not_served += 1,
            _ => {}
        }
    }
    let downs: Vec<&&Check> = own.iter().filter(|c| c.status == Status::Down).collect();
    let root_here = downs.iter().any(|c| match &c.explained_by {
        None => true,
        Some(r) => layer_of.get(r.as_str()) == Some(&layer),
    });
    let light = if root_here {
        Light::Red
    } else if !downs.is_empty() {
        Light::RedHollow
    } else if own.iter().any(|c| matches!(c.status, Status::Warn | Status::Blind | Status::Gated)) {
        Light::Amber
    } else if !own.is_empty() && own.iter().all(|c| c.status == Status::Ok) {
        Light::Green
    } else {
        Light::Grey
    };
    (light, k)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::Check;

    fn layers() -> Vec<Layer> {
        let l = |id: &str, rank: u32| Layer { id: id.into(), label: id.into(), rank, depends_on: vec![] };
        vec![l("vantage", 0), l("vms", 1), l("mesh", 2), l("containers", 3), l("certs", 4), l("edge", 5), l("control", 8), l("lights", 9)]
    }

    /// gcp_proxy_oom: the hub is unreadable, three phone probes fail, the apex answers.
    fn oom() -> Vec<Check> {
        vec![
            Check::new("mesh.hub_tcp:wg0", "mesh", Status::Down),
            Check::new("vms.mesh_tcp:gcp-E2-f_0", "vms", Status::Down),
            Check::new("edge.ingress:vpn", "edge", Status::Down),
            Check::new("vms.alive:gcp-E2-f_0", "vms", Status::Blind).evidence(&["mesh.hub_tcp:wg0", "vms.mesh_tcp:gcp-E2-f_0", "edge.ingress:vpn"]),
            Check::new("mesh.hub:wg0", "mesh", Status::Down).needs(&["vms.alive:gcp-E2-f_0"]),
            Check::new("control.api_public", "control", Status::Down).needs(&["vms.alive:gcp-E2-f_0"]),
            Check::new("lights.private:gitea", "lights", Status::Blind).needs(&["mesh.hub:wg0"]),
            Check::new("lights.public:apex", "lights", Status::Ok),
        ]
    }

    #[test]
    fn expand_is_a_product_of_the_placeholders() {
        let mut v = HashMap::new();
        v.insert("vm".to_string(), vec!["a".to_string(), "b".to_string()]);
        v.insert("mesh".to_string(), vec!["wg0".to_string()]);
        assert_eq!(expand("x:{vm}:{mesh}", &v), vec!["x:a:wg0", "x:b:wg0"]);
        assert_eq!(expand("plain", &v), vec!["plain"]);
    }

    #[test]
    fn gcp_proxy_oom_has_one_inferred_root_and_the_rest_is_explained() {
        let mut cs = oom();
        let all = cs.clone();
        infer(&mut cs[3], &all, 3);
        assert_eq!(cs[3].status, Status::Down);
        assert_eq!(cs[3].confidence, "inferred");
        let roots = attribute(&mut cs, &layers());
        // the three probes are roots of their own (no needs), the hub VM explains the hub, the API and (through the hub) the private leg
        assert!(roots.contains(&"vms.alive:gcp-E2-f_0".to_string()));
        assert_eq!(roots[0], "vms.alive:gcp-E2-f_0", "ranked first: it explains the most");
        assert_eq!(cs[4].explained_by.as_deref(), Some("vms.alive:gcp-E2-f_0"));
        assert_eq!(cs[5].explained_by.as_deref(), Some("vms.alive:gcp-E2-f_0"));
        assert_eq!(cs[6].explained_by.as_deref(), Some("vms.alive:gcp-E2-f_0"), "a blind leg is explained through its need's root");
        assert_eq!(cs[7].explained_by, None);
        assert_eq!(layer_light("vms", &cs).0, Light::Red);
        assert_eq!(layer_light("control", &cs).0, Light::RedHollow);
        assert_eq!(layer_light("lights", &cs).0, Light::Amber);
    }

    #[test]
    fn inference_needs_every_evidence_down_and_the_minimum_and_never_infers_ok() {
        let mut cs = oom();
        cs[2].status = Status::Ok;
        let all = cs.clone();
        let mut c = cs[3].clone();
        infer(&mut c, &all, 3);
        assert_eq!(c.status, Status::Blind, "one evidence answered: no inference");
        let mut c = oom()[3].clone();
        infer(&mut c, &oom(), 4);
        assert_eq!(c.status, Status::Blind, "fewer evidence than the minimum");
        let mut c = oom()[3].clone();
        infer(&mut c, &oom(), 0);
        assert_eq!(c.status, Status::Blind, "a zero minimum infers nothing");
        let mut ok = Check::new("x", "vms", Status::Blind).evidence(&["y"]);
        infer(&mut ok, &[Check::new("y", "mesh", Status::Ok)], 1);
        assert_eq!(ok.status, Status::Blind);
        assert_eq!(ok.confidence, "measured");
    }

    #[test]
    fn two_roots_rank_by_how_many_they_explain_then_by_id() {
        let mut cs = vec![
            Check::new("a.root", "vms", Status::Down),
            Check::new("b.root", "vms", Status::Down),
            Check::new("x1", "lights", Status::Down).needs(&["b.root"]),
            Check::new("x2", "lights", Status::Down).needs(&["b.root"]),
            Check::new("x3", "lights", Status::Down).needs(&["a.root"]),
        ];
        assert_eq!(attribute(&mut cs, &layers()), vec!["b.root", "a.root"]);
        let mut tie = vec![Check::new("z", "vms", Status::Down), Check::new("y", "vms", Status::Down)];
        assert_eq!(attribute(&mut tie, &layers()), vec!["y", "z"]);
    }

    #[test]
    fn the_primary_root_is_the_lowest_layer_rank() {
        let mut cs = vec![
            Check::new("edge.caddy", "edge", Status::Down),
            Check::new("vms.alive", "vms", Status::Down),
            Check::new("leg", "lights", Status::Down).needs(&["edge.caddy", "vms.alive"]),
        ];
        attribute(&mut cs, &layers());
        assert_eq!(cs[2].explained_by.as_deref(), Some("vms.alive"));
        // needs listed in the other order, same answer; a need of an unknown layer ranks last
        let mut cs = vec![
            Check::new("q", "nowhere", Status::Down),
            Check::new("vms.alive", "vms", Status::Down),
            Check::new("leg", "lights", Status::Down).needs(&["vms.alive", "q"]),
        ];
        attribute(&mut cs, &layers());
        assert_eq!(cs[2].explained_by.as_deref(), Some("vms.alive"));
    }

    #[test]
    fn a_blind_check_with_no_failing_need_is_not_a_root_and_an_ok_need_explains_nothing() {
        let mut cs = vec![
            Check::new("vantage.vpn", "vantage", Status::Blind),
            Check::new("leg", "lights", Status::Blind).needs(&["vantage.vpn"]),
            Check::new("up", "vms", Status::Ok),
            Check::new("down", "lights", Status::Down).needs(&["up"]),
        ];
        let roots = attribute(&mut cs, &layers());
        assert_eq!(roots, vec!["down"]);
        assert_eq!(cs[1].explained_by, None);
        assert_eq!(cs[3].explained_by, None);
        assert_eq!(layer_light("lights", &cs).0, Light::Red);
        assert_eq!(layer_light("vantage", &cs).0, Light::Amber);
    }

    #[test]
    fn attribution_follows_order_even_when_a_need_is_declared_later() {
        let mut cs = vec![
            Check::new("leg", "lights", Status::Down).needs(&["mid"]),
            Check::new("mid", "edge", Status::Down).needs(&["base"]),
            Check::new("base", "vms", Status::Down),
        ];
        let roots = attribute(&mut cs, &layers());
        assert_eq!(roots, vec!["base"]);
        assert_eq!(cs[0].explained_by.as_deref(), Some("base"));
        assert_eq!(topo(&cs), vec![2, 1, 0]);
    }

    #[test]
    fn gating_keeps_samples_and_names_the_root() {
        let mut cs: Vec<Check> = (0..5).map(|i| Check::new(&format!("leg{}", i), "lights", Status::Pending).needs(&["root"])).collect();
        cs.push(Check::new("free", "lights", Status::Pending));
        let probe = gate(&mut cs, &["root".to_string()], 3);
        assert_eq!(probe, vec!["leg0", "leg1", "leg2", "free"]);
        assert_eq!(cs[3].status, Status::Gated);
        assert_eq!(cs[3].details, "not probed: blocked by root");
        assert_eq!(cs[2].status, Status::Pending);
        let mut none: Vec<Check> = vec![Check::new("a", "lights", Status::Pending).needs(&["root"])];
        assert!(gate(&mut none, &["root".to_string()], 0).is_empty());
        assert_eq!(none[0].status, Status::Gated);
    }

    #[test]
    fn layer_light_green_grey_amber_and_the_six_counts() {
        let cs = vec![
            Check::new("a", "certs", Status::Ok),
            Check::new("b", "certs", Status::Ok),
            Check::new("w", "edge", Status::Warn),
            Check::new("g", "edge", Status::Gated),
            Check::new("n", "edge", Status::NotServed),
            Check::new("n2", "data", Status::NotServed),
        ];
        assert_eq!(layer_light("certs", &cs), (Light::Green, Counts { ok: 2, ..Counts::default() }));
        assert_eq!(layer_light("edge", &cs), (Light::Amber, Counts { warn: 1, blocked: 1, not_served: 1, ..Counts::default() }));
        assert_eq!(layer_light("data", &cs).0, Light::Grey, "not served only: nothing measured");
        assert_eq!(layer_light("none", &cs).0, Light::Grey);
        let gated_only = vec![Check::new("g", "x", Status::Gated)];
        assert_eq!(layer_light("x", &gated_only).0, Light::Amber);
        let blind_only = vec![Check::new("b", "x", Status::Blind)];
        assert_eq!(layer_light("x", &blind_only).0, Light::Amber);
        let mut caused = vec![Check::new("r", "vms", Status::Down), Check::new("c", "x", Status::Down)];
        caused[1].explained_by = Some("r".into());
        assert_eq!(layer_light("x", &caused), (Light::RedHollow, Counts { caused: 1, ..Counts::default() }));
        let mut same = vec![Check::new("r", "x", Status::Down), Check::new("c", "x", Status::Down)];
        same[1].explained_by = Some("r".into());
        assert_eq!(layer_light("x", &same), (Light::Red, Counts { down: 1, caused: 1, ..Counts::default() }));
    }
}
