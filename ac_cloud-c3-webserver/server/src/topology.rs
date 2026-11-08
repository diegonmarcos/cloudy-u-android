//! The MCP -> API -> IPC topology as one joined graph (`GET /__app__/api/topology`).
//!
//! Three declarations are joined, none retyped here:
//!   * MCP -> API: `data/mcp-catalogue.json` (generated; every tool carries the API route node it maps to);
//!   * API: this app's [`app::ROUTES`], plus the mesh and diag listeners' routes declared in the map;
//!   * API -> IPC: `data/api-ipc-map.json` (hand-declared from the real dispatch), whose endpoints are resolved against
//!     `build.json::api.ipc` (per-app templates) and `build.json::mesh` (the headless and shell-broker authorities).
//!
//! Shape: `{nodes:[{layer: mcp|api|ipc, id, label, meta}], edges:[{from, to, kind}], routes:[{mcp_tool, api_route, ipc_route, method,
//! auth, summary, status, ipc_state}]}`. `status` is a stub (`unknown`) until live probing is wired; `ipc_state` is
//! `mapped | none | unmapped | n/a` (n/a: the row has no API leg).

use crate::apis::{self, App};
use crate::app::{self, Kind, Route};
use fleet_webserver::http::{Req, Resp};
use serde_json::{json, Value};
use std::collections::BTreeSet;

pub const MAP: &str = include_str!("../../data/api-ipc-map.json");

pub fn map() -> &'static Value {
    static M: std::sync::OnceLock<Value> = std::sync::OnceLock::new();
    M.get_or_init(|| serde_json::from_str(MAP).expect("data/api-ipc-map.json parses"))
}

fn s(v: &Value) -> String {
    v.as_str().unwrap_or("").to_string()
}

fn arr(v: &Value) -> &[Value] {
    v.as_array().map(|a| a.as_slice()).unwrap_or(&[])
}

/// The authority text of one IPC endpoint of the map.
fn authority(e: &Value, b: &Value) -> Value {
    if let Some(t) = e["template"].as_str() {
        let id = t.rsplit('#').next().unwrap_or("");
        return arr(&b["api"]["ipc"]).iter().find(|x| x["id"] == id).map(|x| x["authority"].clone()).unwrap_or(Value::Null);
    }
    if let Some(keys) = e["authority_mesh"].as_array() {
        let parts: Vec<String> = keys.iter().map(|k| s(&b["mesh"][k.as_str().unwrap_or("")])).collect();
        return json!(parts.join("."));
    }
    e["authority"].clone()
}

fn api_id(surface: &str, path: &str) -> String {
    format!("api:{}:{}", surface, path)
}

/// A catalogue route is MCP-only when the generator found no API route for its node.
fn mcp_only(route: &str) -> bool {
    route.contains("no API route")
}

/// Join the three declarations. Pure: the tests feed it fixtures.
pub fn build(cat: &Value, map: &Value, b: &Value, apps: &[App], table: &[Route]) -> Value {
    let mut nodes: Vec<Value> = vec![];
    let mut edges: Vec<Value> = vec![];
    let mut seen_edge = BTreeSet::new();
    let mut edge = |edges: &mut Vec<Value>, from: &str, to: &str, kind: &str| {
        if seen_edge.insert(format!("{}|{}|{}", from, to, kind)) {
            edges.push(json!({"from": from, "to": to, "kind": kind}));
        }
    };

    // IPC layer.
    let n_apps = apps.len();
    for e in arr(&map["ipc_endpoints"]) {
        let per_app = e["template"].is_string();
        nodes.push(json!({"layer": "ipc", "id": format!("ipc:{}", s(&e["id"])), "label": s(&e["id"]),
            "meta": {"kind": e["kind"], "authority": authority(e, b), "what": e["what"], "per_app": per_app, "apps": if per_app { n_apps } else { 0 }}}));
    }

    // API layer, app/mesh/diag surfaces: ROUTES are the app surface's source of truth, the map carries the rest.
    struct Row {
        id: String,
        path: String,
        method: String,
        auth: String,
        summary: String,
        ipc: Value,
        tools: Vec<String>,
    }
    let mut rows: Vec<Row> = vec![];
    for r in table.iter() {
        let m = arr(&map["routes"]).iter().find(|x| x["surface"] == "app" && x["path"] == r.path);
        rows.push(Row {
            id: api_id("app", r.path),
            path: r.path.to_string(),
            method: if r.post { "GET|POST" } else { "GET" }.to_string(),
            auth: s(&map["surfaces"]["app"]["auth"]),
            summary: r.summary.to_string(),
            ipc: m.map(|x| x["ipc"].clone()).unwrap_or_else(|| json!("unmapped")),
            tools: vec![],
        });
        let _ = Kind::Page;
    }
    for m in arr(&map["routes"]).iter().filter(|x| x["surface"] != "app") {
        let surf = s(&m["surface"]);
        rows.push(Row {
            id: api_id(&surf, &s(&m["path"])),
            path: s(&m["path"]),
            method: s(&m["method"]),
            auth: s(&map["surfaces"][surf.as_str()]["auth"]),
            summary: s(&m["via"]),
            ipc: m["ipc"].clone(),
            tools: arr(&m["mcp_tools"]).iter().map(s).collect(),
        });
    }
    let kind_of = |r: &Row| -> &'static str {
        if r.id.starts_with("api:app:") {
            table.iter().find(|t| t.path == r.path).map(|t| if t.kind == Kind::Page { "page" } else { "api" }).unwrap_or("api")
        } else {
            "api"
        }
    };
    for r in &rows {
        let surf = r.id.split(':').nth(1).unwrap_or("");
        nodes.push(json!({"layer": "api", "id": r.id, "label": r.path,
            "meta": {"surface": surf, "kind": kind_of(r), "method": r.method, "auth": r.auth, "summary": r.summary}}));
        for i in arr(&r.ipc) {
            edge(&mut edges, &r.id, &format!("ipc:{}", s(i)), "dispatches");
        }
    }

    // MCP layer + the catalogue's API nodes.
    let mut out_rows: Vec<Value> = vec![];
    let mut cat_api = BTreeSet::new();
    let status = "unknown";
    let ipc_rows = |api: &str, ipc: &Value| -> Vec<(Value, &'static str)> {
        let _ = api;
        match ipc {
            Value::Null => vec![(Value::Null, "none")],
            Value::String(_) => vec![(Value::Null, "unmapped")],
            _ => arr(ipc).iter().map(|i| (json!(format!("ipc:{}", s(i))), "mapped")).collect(),
        }
    };
    for sv in arr(cat) {
        let sid = format!("mcp:{}", s(&sv["name"]));
        nodes.push(json!({"layer": "mcp", "id": sid, "label": s(&sv["name"]),
            "meta": {"kind": "server", "auth": sv["auth"], "endpoint": sv["endpoint"], "aliases": sv["aliases"], "api": sv["api"], "tools_count": sv["tools_count"], "tools_listed": arr(&sv["tools"]).len()}}));
        for t in arr(&sv["tools"]) {
            let tid = format!("mcp:{}/{}", s(&sv["name"]), s(&t["tool"]));
            nodes.push(json!({"layer": "mcp", "id": tid, "label": s(&t["tool"]),
                "meta": {"kind": "tool", "server": sv["name"], "auth": sv["auth"], "tree": t["tree"], "legacy": t["legacy"]}}));
            edge(&mut edges, &sid, &tid, "exposes");
            let route = s(&t["route"]);
            let mut legs = 0;
            if !mcp_only(&route) {
                let aid = format!("api:c3:{}", s(&t["node"]));
                if cat_api.insert(aid.clone()) {
                    nodes.push(json!({"layer": "api", "id": aid, "label": s(&t["node"]),
                        "meta": {"surface": "c3", "kind": "api", "api": sv["api"], "method": null, "auth": sv["auth"], "summary": route}}));
                }
                edge(&mut edges, &tid, &aid, "maps_to");
                // The catalogue API is remote (cloudy-c3-api): the map declares no IPC for it.
                out_rows.push(json!({"mcp_tool": s(&t["tool"]), "mcp_server": s(&sv["name"]), "api_route": s(&t["node"]), "ipc_route": null, "method": null,
                    "auth": s(&sv["auth"]), "summary": route, "status": status, "ipc_state": "n/a"}));
                legs += 1;
            }
            for r in rows.iter().filter(|r| r.tools.iter().any(|x| *x == s(&t["tool"]))) {
                edge(&mut edges, &tid, &r.id, "served_by");
                for (ipc, state) in ipc_rows(&r.id, &r.ipc) {
                    out_rows.push(json!({"mcp_tool": s(&t["tool"]), "mcp_server": s(&sv["name"]), "api_route": r.path, "ipc_route": ipc, "method": r.method,
                        "auth": r.auth, "summary": r.summary, "status": status, "ipc_state": state}));
                }
                legs += 1;
            }
            if legs == 0 {
                out_rows.push(json!({"mcp_tool": s(&t["tool"]), "mcp_server": s(&sv["name"]), "api_route": null, "ipc_route": null, "method": null,
                    "auth": s(&sv["auth"]), "summary": route, "status": "mcp_only", "ipc_state": "n/a"}));
            }
        }
    }
    // Webserver routes no catalogue tool points at: still part of the API -> IPC half.
    for r in rows.iter().filter(|r| r.tools.is_empty()) {
        for (ipc, state) in ipc_rows(&r.id, &r.ipc) {
            out_rows.push(json!({"mcp_tool": null, "mcp_server": null, "api_route": r.path, "ipc_route": ipc, "method": r.method,
                "auth": r.auth, "summary": r.summary, "status": status, "ipc_state": state}));
        }
    }
    json!({"nodes": nodes, "edges": edges, "routes": out_rows})
}

pub fn topology() -> Value {
    build(apis::data()["mcp_catalogue"].as_array().map(|_| &apis::data()["mcp_catalogue"]).unwrap_or(&Value::Null), map(), apis::build(), apis::apps(), app::ROUTES)
}

pub fn api_topology(_ctx: &crate::Ctx, _req: &Req) -> Resp {
    Resp::json(200, topology().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn api_ipc_map_covers_every_route() {
        let m = map();
        let ids: BTreeSet<String> = arr(&m["ipc_endpoints"]).iter().map(|e| s(&e["id"])).collect();
        let mapped = |p: &str| arr(&m["routes"]).iter().find(|x| x["surface"] == "app" && x["path"] == p);
        for r in app::ROUTES {
            let e = mapped(r.path).unwrap_or_else(|| panic!("{} is not in data/api-ipc-map.json", r.path));
            assert!(e["ipc"].is_null() || e["ipc"] == "unmapped" || e["ipc"].is_array(), "{}: ipc is null, \"unmapped\" or a list", r.path);
            assert!(!s(&e["via"]).is_empty(), "{} names its dispatch", r.path);
        }
        for e in arr(&m["routes"]) {
            if e["surface"] == "app" {
                assert!(app::ROUTES.iter().any(|r| r.path == e["path"]), "{} is mapped but is no ROUTES row", e["path"]);
            }
            for i in arr(&e["ipc"]) {
                assert!(ids.contains(&s(i)), "{} names unknown ipc endpoint {}", e["path"], i);
            }
        }
        // every template endpoint exists in build.json::api.ipc
        for e in arr(&m["ipc_endpoints"]).iter().filter(|e| e["template"].is_string()) {
            assert!(authority(e, apis::build()).is_string(), "{} has no build.json::api.ipc template", e["id"]);
        }
        for e in arr(&m["ipc_endpoints"]).iter().filter(|e| e["authority_mesh"].is_array()) {
            assert!(!s(&authority(e, apis::build())).starts_with('.') && s(&authority(e, apis::build())).contains('.'), "{} authority resolves from build.json::mesh", e["id"]);
        }
    }

    #[test]
    fn topology_is_a_well_formed_three_layer_graph() {
        let t = topology();
        let nodes = arr(&t["nodes"]);
        let ids: BTreeSet<String> = nodes.iter().map(|n| s(&n["id"])).collect();
        assert_eq!(ids.len(), nodes.len(), "node ids are unique");
        for layer in ["mcp", "api", "ipc"] {
            assert!(nodes.iter().any(|n| n["layer"] == layer), "layer {} has nodes", layer);
        }
        assert!(nodes.iter().filter(|n| n["layer"] == "mcp").count() > 150, "all catalogue tools are nodes");
        let layer = |id: &str| nodes.iter().find(|n| n["id"] == id).map(|n| s(&n["layer"])).unwrap();
        for e in arr(&t["edges"]) {
            assert!(ids.contains(&s(&e["from"])) && ids.contains(&s(&e["to"])), "edge {} references real nodes", e);
            match s(&e["kind"]).as_str() {
                "exposes" => assert_eq!((layer(&s(&e["from"])), layer(&s(&e["to"]))), ("mcp".into(), "mcp".into())),
                "maps_to" | "served_by" => assert_eq!((layer(&s(&e["from"])), layer(&s(&e["to"]))), ("mcp".into(), "api".into())),
                "dispatches" => assert_eq!((layer(&s(&e["from"])), layer(&s(&e["to"]))), ("api".into(), "ipc".into())),
                k => panic!("unknown edge kind {}", k),
            }
        }
        // every ROUTES row is an api node and appears in routes[]; every mapped one has its dispatch edge
        let rows = arr(&t["routes"]);
        for r in app::ROUTES {
            assert!(ids.contains(&api_id("app", r.path)), "{} is a node", r.path);
            assert!(rows.iter().any(|x| x["api_route"] == r.path), "{} is in routes[]", r.path);
        }
        for m in arr(&map()["routes"]) {
            for i in arr(&m["ipc"]) {
                let from = api_id(&s(&m["surface"]), &s(&m["path"]));
                assert!(arr(&t["edges"]).iter().any(|e| e["from"] == from && e["to"] == format!("ipc:{}", s(i)) && e["kind"] == "dispatches"), "{} -> {}", from, i);
                assert!(rows.iter().any(|x| x["api_route"] == m["path"] && x["ipc_route"] == format!("ipc:{}", s(i))), "row {} -> {}", from, i);
            }
        }
        for x in rows {
            for k in ["mcp_tool", "api_route", "ipc_route", "method", "auth", "summary", "status"] {
                assert!(x.get(k).is_some(), "row has {}: {}", k, x);
            }
        }
        // a full chain exists: tool -> webserver api -> ipc
        assert!(rows.iter().any(|x| x["mcp_tool"] == "obs.debug.phone_logs" && x["api_route"] == "/logs/<app>" && x["ipc_route"] == "ipc:logexport"), "phone_logs -> /logs/<app> -> logexport");
    }

    #[test]
    fn unmapped_and_null_are_distinguished() {
        let cat = json!([{"name": "m", "auth": "a", "tools": [{"tool": "t", "route": "/x (no API route: MCP only)", "node": "x"}]}]);
        let map = json!({"surfaces": {"app": {"auth": "l"}}, "ipc_endpoints": [], "routes": []});
        let t = build(&cat, &map, &json!({}), &[], app::ROUTES);
        let rows = arr(&t["routes"]);
        assert!(rows.iter().any(|x| x["mcp_tool"] == "t" && x["status"] == "mcp_only"));
        assert!(rows.iter().filter(|x| x["mcp_tool"].is_null()).all(|x| x["ipc_state"] == "unmapped"), "a ROUTES row missing from the map is unmapped, never silently null");
    }
}
