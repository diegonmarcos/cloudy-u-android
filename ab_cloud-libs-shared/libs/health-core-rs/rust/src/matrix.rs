//! The mesh prober matrix A to F (the SuperApp's model), planned from the declaration: A private names, B public controls,
//! C peers and D fallbacks (held by Cloud Mesh: not served here, except the wstunnel endpoint probe of D), E IPv4 (each hub :53, each
//! VM WG IP), F IPv6. Planning only; [crate::prober::probe] runs each spec.

#[derive(Debug, Clone, PartialEq)]
pub struct Spec {
    pub group: char,
    pub id: String,
    /// a URL or host:port for the prober; empty when the spec is not served from this vantage
    pub target: String,
    pub not_served: String,
}

#[derive(Debug, Clone, Default)]
pub struct MeshDecl {
    pub private_names: Vec<String>,
    pub public_controls: Vec<String>,
    pub hubs_v4: Vec<String>,
    pub hubs_v6: Vec<String>,
    pub vm_wg_ips: Vec<(String, String)>,
    pub wstunnel_endpoint: String,
}

pub const HELD_BY_MESH: &str = "held by Cloud Mesh (SuperApp); ticket S1";

pub fn plan(d: &MeshDecl) -> Vec<Spec> {
    let sp = |g: char, id: String, t: String| Spec { group: g, id, target: t, not_served: String::new() };
    let mut out = Vec::new();
    for n in &d.private_names { out.push(sp('A', format!("A.name:{}", n), format!("https://{}/", n))); }
    for u in &d.public_controls { out.push(sp('B', format!("B.public:{}", u), u.clone())); }
    out.push(Spec { group: 'C', id: "C.peers".into(), target: String::new(), not_served: HELD_BY_MESH.into() });
    out.push(Spec { group: 'D', id: "D.fallbacks".into(), target: String::new(), not_served: HELD_BY_MESH.into() });
    if !d.wstunnel_endpoint.is_empty() { out.push(sp('D', format!("D.wstunnel:{}", d.wstunnel_endpoint), d.wstunnel_endpoint.clone())); }
    for h in &d.hubs_v4 { out.push(sp('E', format!("E.hub53:{}", h), format!("{}:53", h))); }
    for (vm, ip) in &d.vm_wg_ips { out.push(sp('E', format!("E.vm:{}", vm), format!("{}:22", ip))); }
    for h in &d.hubs_v6 { out.push(sp('F', format!("F.hub53:{}", h), format!("[{}]:53", h))); }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn every_group_is_planned_and_c_d_are_not_served_here() {
        let d = MeshDecl {
            private_names: vec!["gitea.mesh.example".into()], public_controls: vec!["https://example.com/".into()],
            hubs_v4: vec!["10.0.0.1".into()], hubs_v6: vec!["fd00::1".into()], vm_wg_ips: vec![("oci-apps".into(), "10.0.0.6".into())],
            wstunnel_endpoint: "vpn.example.com:443".into(),
        };
        let p = plan(&d);
        let groups: Vec<char> = p.iter().map(|s| s.group).collect();
        for g in ['A', 'B', 'C', 'D', 'E', 'F'] { assert!(groups.contains(&g), "group {} planned", g); }
        assert_eq!(p.iter().filter(|s| !s.not_served.is_empty()).count(), 2);
        assert!(p.iter().any(|s| s.target == "10.0.0.1:53"));
        assert!(p.iter().any(|s| s.target == "10.0.0.6:22"));
        assert!(p.iter().any(|s| s.target == "[fd00::1]:53"));
        assert!(p.iter().any(|s| s.target == "vpn.example.com:443"));
        assert_eq!(plan(&MeshDecl::default()).len(), 2, "an empty declaration still states C and D");
    }
}
