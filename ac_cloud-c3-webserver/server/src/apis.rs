//! The API tab's four sub-pages (Public Endpoints, Mesh Endpoints, Apps Endpoints, Apps IPC) as data.
//!
//! Nothing here names an app, a service or a port: every row is read from declarations that already exist (build.json::api.sources,
//! resolved by build.rs into `api-data.json`: the fleet services, the per-app debug ports, the fleet manifest) and from this
//! app's own build.json (`api.ipc` templates, `mesh`). A page is `{title, note, groups:[{title, rows:[{head, badges, lines,
//! links:[{label, href}], actions}]}]}`, the one shape the shell draws for every sub-page (dense rows).

use crate::meshlogs::Mesh;
use crate::serving;
use serde_json::{json, Value};
use std::sync::OnceLock;

pub const API_DATA: &str = include_str!(concat!(env!("OUT_DIR"), "/api-data.json"));

/// This app's resolved build.json.
pub fn build() -> &'static Value {
    static B: OnceLock<Value> = OnceLock::new();
    B.get_or_init(|| serde_json::from_str(serving::BUILD_JSON).expect("build.json parses (serving::parse checks it)"))
}

pub fn data() -> &'static Value {
    static D: OnceLock<Value> = OnceLock::new();
    D.get_or_init(|| serde_json::from_str(API_DATA).expect("api-data.json parses"))
}

#[derive(Clone, Debug, PartialEq)]
pub struct App {
    pub id: String,
    pub name: String,
    pub package: String,
    pub engines: Vec<String>,
}

/// The fleet manifest's applications (kind `app`): the ONLY list of apps anywhere in this crate.
pub fn apps_from(d: &Value) -> Vec<App> {
    d["fleet"]
        .as_array()
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .filter(|a| a["kind"] == "app")
        .filter_map(|a| {
            Some(App {
                id: a["id"].as_str()?.to_string(),
                name: a["display_name"].as_str().or(a["label"].as_str()).unwrap_or_else(|| a["id"].as_str().unwrap_or("")).to_string(),
                package: a["package"].as_str()?.to_string(),
                engines: a["engines"].as_array().map(|e| e.iter().filter_map(|x| x["binding"].as_str().map(str::to_string)).collect()).unwrap_or_default(),
            })
        })
        .collect()
}

pub fn apps() -> &'static Vec<App> {
    static A: OnceLock<Vec<App>> = OnceLock::new();
    A.get_or_init(|| apps_from(data()))
}

fn fill(tpl: &str, pkg: &str) -> String {
    tpl.replace("{package}", pkg)
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or("").to_string()
}

fn row(head: &str, badges: Vec<String>, lines: Vec<String>, links: Vec<(String, String)>) -> Value {
    json!({"head": head, "badges": badges, "lines": lines, "links": links.into_iter().map(|(l, h)| json!({"label": l, "href": h})).collect::<Vec<_>>()})
}

pub fn page(title: &str, note: &str, groups: Vec<Value>) -> Value {
    json!({"title": title, "note": note, "groups": groups})
}

pub fn group(title: &str, rows: Vec<Value>) -> Value {
    json!({"title": title, "rows": rows})
}

/// The public URL of a fleet service (scheme, domain, path), when it has one.
pub fn public_url(svc: &Value) -> Option<String> {
    let d = svc["public_domain"].as_str().filter(|d| !d.is_empty())?;
    Some(format!("{}://{}{}", svc["public_scheme"].as_str().unwrap_or("https"), d, svc["public_path"].as_str().unwrap_or("")))
}

/// The mesh URL of a fleet service, when it has one.
pub fn mesh_url(svc: &Value) -> Option<String> {
    let d = svc["mesh_dns"].as_str().filter(|d| !d.is_empty())?;
    let port = svc["mesh_port"].as_u64().map(|p| format!(":{}", p)).unwrap_or_default();
    Some(format!("{}://{}{}{}", svc["mesh_scheme"].as_str().unwrap_or("http"), d, port, svc["mesh_path"].as_str().unwrap_or("")))
}

pub fn public_page(d: &Value) -> Value {
    let rows: Vec<Value> = d["services"]
        .as_array()
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .filter_map(|v| {
            let url = public_url(v)?;
            let mut lines = vec![];
            if let Some(h) = v["health"].as_str() {
                lines.push(format!("health {}", h));
            }
            Some(row(&s(&v["id"]), vec![s(&v["kind"]), s(&v["policy"])].into_iter().filter(|b| !b.is_empty()).collect(), lines, vec![(url.clone(), url)]))
        })
        .collect();
    page("Public Endpoints", "The public URLs of the fleet services, from the fleet endpoint declaration (Cloud Account). Reach them with the fleet bearer where the policy says so.", vec![group(&format!("{} public URLs", rows.len()), rows)])
}

pub fn mesh_page(d: &Value, m: &Mesh, apps: &[App]) -> Value {
    let base = format!("http://{}:{}", m.bind, m.port);
    let mut head = vec![row(
        &format!("{}{}", base, m.route),
        vec!["GET".into(), "Bearer".into(), "mesh only".into()],
        vec![format!("the apps that export logs; peers from {} only; every path needs the fleet bearer", m.cidr_text())],
        vec![],
    )];
    head.push(row(&format!("{}{}/public | mesh", base, m.endpoints_route), vec!["GET".into(), "Bearer".into()], vec!["these Public and Mesh Endpoints pages as JSON (for cloudy-c3-mcp)".to_string()], vec![]));
    head.push(row(&format!("{}{}/<app>?n=&errors=&crashes=&method=", base, m.route), vec!["GET".into(), "Bearer".into()], vec![format!("calls <package>.{} of that app: {}", m.suffix, m.methods.join(" | "))], vec![]));
    let logs: Vec<Value> = apps.iter().map(|a| row(&a.name, vec![a.id.clone()], vec![format!("{}.{}", a.package, m.suffix)], vec![(format!("{}{}/{}", base, m.route, a.id), format!("{}{}/{}", base, m.route, a.id))])).collect();
    let svc: Vec<Value> = d["services"]
        .as_array()
        .map(|a| a.as_slice())
        .unwrap_or(&[])
        .iter()
        .filter_map(|v| {
            let url = mesh_url(v)?;
            Some(row(&s(&v["id"]), vec![s(&v["policy"])].into_iter().filter(|b| !b.is_empty()).collect(), vec![], vec![(url.clone(), url)]))
        })
        .collect();
    page(
        "Mesh Endpoints",
        "This phone's mesh listener routes each app's log export (libs:log-export) onto the mesh. It is bound to the mesh address only, never public.",
        vec![group("Cloud-Webserver on the mesh", head), group(&format!("Apps logs · {}", logs.len()), logs), group(&format!("Fleet services on the mesh · {}", svc.len()), svc)],
    )
}

pub fn apps_page(d: &Value, apps: &[App], b: &Value) -> Value {
    let ports = &d["debug_ports"];
    let gw = &d["debug_gateway"];
    let mut rows = vec![];
    if let Some(p) = gw["port"].as_u64() {
        rows.push(row(
            &s(&gw["pkg"]),
            vec![format!(":{}", p), s(&gw["bind"])],
            vec![format!("gateway {}", s(&gw["route"])), format!("roster {}", s(&gw["apps"]))],
            vec![(format!("http://{}:{}{}", s(&gw["bind"]), p, s(&gw["apps"])), format!("http://{}:{}{}", s(&gw["bind"]), p, s(&gw["apps"])))],
        ));
    }
    let routes: Vec<String> = b["api"]["debug_routes"].as_array().map(|a| a.iter().map(|r| format!("{}  {}", s(&r["path"]), s(&r["what"]))).collect()).unwrap_or_default();
    for a in apps {
        if let Some(p) = ports[&a.package].as_u64() {
            rows.push(row(&a.name, vec![format!(":{}", p), "127.0.0.1".into()], routes.clone(), vec![("ping".into(), format!("http://127.0.0.1:{}/api/system/ping", p))]));
        }
    }
    page("Apps Endpoints", "Each app's in-app debug API: loopback only, one fixed port per app (the debug-port table behind every debug-api.json), fleet bearer on everything but ping.", vec![group(&format!("{} debug APIs", rows.len()), rows)])
}

pub fn ipc_page(apps: &[App], b: &Value) -> Value {
    let tpl = b["api"]["ipc"].as_array().cloned().unwrap_or_default();
    let engine = s(&b["api"]["engine_action"]);
    let groups: Vec<Value> = apps
        .iter()
        .map(|a| {
            let mut rows: Vec<Value> = tpl
                .iter()
                .map(|t| row(&fill(&s(&t["authority"]), &a.package), vec![s(&t["kind"]), s(&t["id"])], vec![format!("{}  ·  {}", s(&t["methods"]), s(&t["guard"])), s(&t["what"])], vec![]))
                .collect();
            if !a.engines.is_empty() {
                rows.push(row(&fill(&engine, &a.package), vec!["service".into(), "engine".into()], vec![format!("binds {}", a.engines.join(", "))], vec![]));
            }
            group(&format!("{} · {}", a.name, a.package), rows)
        })
        .collect();
    page("Apps IPC", "Each app's IPC endpoints, derived from the fleet manifest and the templates in build.json::api.ipc.", groups)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture() -> Value {
        json!({
            "services": [
                {"id": "pub-svc", "kind": "ai", "public_domain": "x.example.org", "public_path": "/p", "mesh_dns": "x.mesh.example.org", "mesh_port": 99, "policy": "mesh_only"},
                {"id": "mesh-only", "mesh_dns": "m.mesh.example.org"}
            ],
            "debug_ports": {"com.a": 38100},
            "debug_gateway": {"pkg": "com.gw", "port": 38080, "bind": "127.0.0.1", "route": "/app/<id>", "apps": "/api/fleet/apps"},
            "fleet": [
                {"id": "a", "kind": "app", "display_name": "A", "package": "com.a", "engines": [{"binding": "gh"}]},
                {"id": "b", "kind": "app", "label": "b", "package": "com.b"},
                {"id": "lib-x", "kind": "lib", "package": "com.lib"}
            ]
        })
    }

    #[test]
    fn apps_are_the_manifest_apps_only() {
        let apps = apps_from(&fixture());
        assert_eq!(apps.iter().map(|a| a.id.as_str()).collect::<Vec<_>>(), vec!["a", "b"]);
        assert_eq!(apps[0].engines, vec!["gh"]);
    }

    #[test]
    fn public_page_lists_only_public_urls() {
        let p = public_page(&fixture());
        let rows = &p["groups"][0]["rows"];
        assert_eq!(rows.as_array().unwrap().len(), 1);
        assert_eq!(rows[0]["links"][0]["href"], "https://x.example.org/p");
        assert_eq!(public_url(&json!({"public_domain": ""})), None);
    }

    #[test]
    fn apps_page_lists_ports_from_the_table() {
        let b = json!({"api": {"debug_routes": [{"path": "/api/system/ping", "what": "live"}]}});
        let p = apps_page(&fixture(), &apps_from(&fixture()), &b);
        let rows = p["groups"][0]["rows"].as_array().unwrap();
        assert_eq!(rows.len(), 2, "the gateway and the one app that has a port");
        assert_eq!(rows[1]["links"][0]["href"], "http://127.0.0.1:38100/api/system/ping");
    }

    #[test]
    fn ipc_page_derives_every_authority_from_the_package() {
        let b = json!({"api": {"engine_action": "{package}.ENGINE", "ipc": [{"id": "logexport", "kind": "provider", "authority": "{package}.logexport", "methods": "m", "guard": "g", "what": "w"}]}});
        let p = ipc_page(&apps_from(&fixture()), &b);
        let g = p["groups"].as_array().unwrap();
        assert_eq!(g.len(), 2);
        assert_eq!(g[0]["rows"][0]["head"], "com.a.logexport");
        assert_eq!(g[0]["rows"][1]["head"], "com.a.ENGINE", "an app with engines lists its engine service");
        assert_eq!(g[1]["rows"].as_array().unwrap().len(), 1);
    }

    #[test]
    fn the_real_declarations_feed_every_page() {
        let apps = apps();
        assert!(apps.len() >= 30, "the fleet manifest lists the apps ({})", apps.len());
        let b = build();
        assert!(public_page(data())["groups"][0]["rows"].as_array().unwrap().len() >= 3);
        assert!(apps_page(data(), apps, b)["groups"][0]["rows"].as_array().unwrap().len() >= 10);
        let ipc = ipc_page(apps, b);
        let tpl = b["api"]["ipc"].as_array().unwrap();
        assert!(tpl.iter().any(|t| t["id"] == "logexport"), "the log-export door is in the IPC templates");
        for g in ipc["groups"].as_array().unwrap() {
            assert!(g["rows"].as_array().unwrap().iter().any(|r| r["head"].as_str().unwrap().ends_with(".logexport")), "{} lists its log export", g["title"]);
        }
    }
}
