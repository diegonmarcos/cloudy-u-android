//! #1835 the MCP endpoint of the phone's mesh listener: `POST /mcp` (JSON-RPC 2.0, stateless streamable-HTTP, JSON replies) lists this
//! phone's own tools and runs them. It is a thin front over the routes the listener already serves, so it adds no reach of its own:
//!   * it lives behind the SAME listener as `/ipc` and `/endpoints`: the mesh address only (meshlogs::start, `is_mesh`), and
//!     [`crate::meshlogs::route`] checks the fleet bearer BEFORE this module is reached (no token known = 503, wrong = 401);
//!   * every tool call becomes one internal GET carried with the caller's own authorization header, so the per-peer ACL and the
//!     no-wake rules of the gateway apply unchanged.
//! cloud-infra-mcp (lib-mcp mesh route, `mcp/peers/mesh-route.ts`) is the client; its API/sshd fallbacks read the same paths.

use fleet_webserver::http::{Req, Resp};
use serde_json::{json, Value};

pub const MCP_PATH: &str = "/mcp";
const PROTOCOL: &str = "2025-03-26";

/// (tool, description) in list order; `path_of` maps a call onto the listener's GET route.
const TOOLS: [(&str, &str); 4] = [
    ("endpoints_mesh", "This phone's mesh endpoints page: the mesh listener, its routes and the fleet services on the mesh."),
    ("endpoints_public", "This phone's public endpoints page: the public URLs of the fleet services."),
    ("ipc_index", "The unified IPC gateway index: every declared app route and its ACL scope."),
    ("app_logs", "One fleet app's recent logcat and crash ring through the IPC gateway (/ipc/<app>/logs)."),
];

pub fn tool_names() -> Vec<&'static str> {
    TOOLS.iter().map(|t| t.0).collect()
}

fn schema(name: &str) -> Value {
    if name == "app_logs" {
        json!({"type": "object", "required": ["app"], "properties": {
            "app": {"type": "string", "description": "fleet app id or package"},
            "n": {"type": "number"}, "errors": {"type": "boolean"}, "crashes": {"type": "number"},
            "method": {"type": "string", "enum": ["export", "logs", "crashes", "meta"]}}})
    } else {
        json!({"type": "object", "properties": {}})
    }
}

fn app_ok(s: &str) -> bool {
    !s.is_empty() && s.len() <= 80 && !s.contains("..") && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '.' || c == '_' || c == '-')
}

/// The listener path of one tool call, or why the arguments are refused. Pure.
pub fn path_of(tool: &str, a: &Value) -> Result<(String, Vec<(String, String)>), String> {
    match tool {
        "endpoints_mesh" => Ok(("/endpoints/mesh".into(), vec![])),
        "endpoints_public" => Ok(("/endpoints/public".into(), vec![])),
        "ipc_index" => Ok(("/ipc".into(), vec![])),
        "app_logs" => {
            let app = a["app"].as_str().filter(|s| app_ok(s)).ok_or("app must be a fleet app id or package (letters, digits, . _ -)")?;
            let mut q = vec![];
            if let Some(m) = a["method"].as_str() {
                if !["export", "logs", "crashes", "meta"].contains(&m) {
                    return Err("method must be one of export, logs, crashes, meta".into());
                }
                q.push(("method".to_string(), m.to_string()));
            }
            if let Some(n) = a["n"].as_f64() {
                q.push(("n".to_string(), (n.max(1.0).min(2000.0) as u32).to_string()));
            }
            if a["errors"].as_bool() == Some(true) {
                q.push(("errors".to_string(), "1".to_string()));
            }
            if let Some(c) = a["crashes"].as_f64() {
                q.push(("crashes".to_string(), (c.max(0.0).min(20.0) as u32).to_string()));
            }
            Ok((format!("/ipc/{}/logs", app), q))
        }
        _ => Err(format!("unknown tool {}", tool)),
    }
}

fn rpc(id: &Value, result: Value) -> Resp {
    Resp::json(200, json!({"jsonrpc": "2.0", "id": id, "result": result}).to_string())
}
fn rpc_err(id: &Value, code: i32, msg: &str) -> Resp {
    Resp::json(200, json!({"jsonrpc": "2.0", "id": id, "error": {"code": code, "message": msg}}).to_string())
}

/// Serve one `/mcp` request. `dispatch` runs an internal GET through the listener's own router (bearer already checked by the caller).
pub fn handle(req: &Req, dispatch: &dyn Fn(&Req) -> Resp) -> Resp {
    if req.method != "POST" {
        return Resp::json(405, "{\"error\":\"POST a JSON-RPC 2.0 message\"}".to_string()).with("allow", "POST");
    }
    let msg: Value = match serde_json::from_slice(&req.body) {
        Ok(v) => v,
        Err(_) => return rpc_err(&Value::Null, -32700, "parse error"),
    };
    let id = msg.get("id").cloned().unwrap_or(Value::Null);
    let method = msg["method"].as_str().unwrap_or("");
    match method {
        "initialize" => rpc(&id, json!({"protocolVersion": PROTOCOL, "capabilities": {"tools": {}}, "serverInfo": {"name": "cloud-c3-webserver", "version": "1"}})),
        "ping" => rpc(&id, json!({})),
        m if m.starts_with("notifications/") => Resp::bytes(202, "application/json", Vec::new()),
        "tools/list" => {
            let tools: Vec<Value> = TOOLS.iter().map(|(n, d)| json!({"name": n, "description": d, "inputSchema": schema(n)})).collect();
            rpc(&id, json!({"tools": tools}))
        }
        "tools/call" => {
            let name = msg["params"]["name"].as_str().unwrap_or("");
            let (path, query) = match path_of(name, &msg["params"]["arguments"]) {
                Ok(p) => p,
                Err(e) => return rpc_err(&id, -32602, &e),
            };
            let headers: Vec<(String, String)> = req.headers.iter().filter(|(k, _)| k == "authorization" || k == "x-c3-peer").cloned().collect();
            let inner = Req { method: "GET".into(), path, query, headers, body: Vec::new() };
            let r = dispatch(&inner);
            let text = String::from_utf8_lossy(&r.body).to_string();
            rpc(&id, json!({"content": [{"type": "text", "text": text}], "isError": r.status >= 400}))
        }
        _ => rpc_err(&id, -32601, "method not found"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn post(body: &str) -> Req {
        Req { method: "POST".into(), path: MCP_PATH.into(), query: vec![], headers: vec![("authorization".into(), "Bearer tok".into())], body: body.as_bytes().to_vec() }
    }
    fn echo(r: &Req) -> Resp {
        Resp::json(200, format!("{{\"path\":\"{}\",\"q\":{}}}", r.path, r.query.len()))
    }

    #[test]
    fn it_lists_its_own_tools() {
        let r = handle(&post("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"), &echo);
        let v: Value = serde_json::from_slice(&r.body).unwrap();
        let names: Vec<&str> = v["result"]["tools"].as_array().unwrap().iter().map(|t| t["name"].as_str().unwrap()).collect();
        assert_eq!(names, tool_names());
    }

    #[test]
    fn a_call_becomes_one_internal_get_and_refuses_unsafe_arguments() {
        let r = handle(&post("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"app_logs\",\"arguments\":{\"app\":\"calc\",\"n\":9999}}}"), &echo);
        let v: Value = serde_json::from_slice(&r.body).unwrap();
        assert!(v["result"]["content"][0]["text"].as_str().unwrap().contains("/ipc/calc/logs"));
        assert!(path_of("app_logs", &json!({"app": "../x"})).is_err());
        assert!(path_of("app_logs", &json!({"app": "a\r\nb"})).is_err());
        assert!(path_of("shell", &json!({})).is_err());
    }

    #[test]
    fn it_only_answers_post() {
        let mut g = post("");
        g.method = "GET".into();
        assert_eq!(handle(&g, &echo).status, 405);
    }
}
