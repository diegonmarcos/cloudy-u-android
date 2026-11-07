//! The sub-pages of the API and Pages tabs, and the Repos API. Every sub-page is declared in build.json::subpages (tab, id, label,
//! route) and answers `/__app__/api/sub/<tab>-<id>` with the one page shape of apis.rs; the shell draws them all with one renderer.
//! The Pages tab's three: Drive (Emulated storage and what Cloud-Drive serves), Terminals (each terminal's ~/home as a servable root,
//! through its own my-webserver) and Repos (the entries of repos.rs, with start/stop and the add form).

use crate::apis::{self, group, page};
use crate::repos::{self, Cfg, Entry, Host, Kind};
use crate::{serving, Ctx};
use fleet_webserver::http::{Req, Resp};
use fleet_webserver::render::jstr;
use serde_json::{json, Value};

pub fn subpages(_ctx: &Ctx, _req: &Req) -> Resp {
    Resp::json(200, apis::build()["subpages"].to_string())
}

fn ok(v: Value) -> Resp {
    Resp::json(200, v.to_string())
}

fn err(status: u16, msg: &str) -> Resp {
    Resp::json(status, format!("{{\"error\":{}}}", jstr(msg)))
}

pub fn api_mcp(_c: &Ctx, _r: &Req) -> Resp {
    ok(apis::mcp_page(apis::data()))
}

pub fn api_public(_c: &Ctx, _r: &Req) -> Resp {
    ok(apis::public_page(apis::data()))
}

pub fn api_mesh(_c: &Ctx, _r: &Req) -> Resp {
    match crate::meshlogs::Mesh::from_build(apis::build()) {
        Ok(m) => ok(apis::mesh_page(apis::data(), &m, apis::apps())),
        Err(e) => err(500, &e),
    }
}

pub fn api_apps(_c: &Ctx, _r: &Req) -> Resp {
    ok(apis::apps_page(apis::data(), apis::apps(), apis::build()))
}

pub fn api_ipc(_c: &Ctx, _r: &Req) -> Resp {
    ok(apis::ipc_page(apis::apps(), apis::build()))
}

fn link(label: &str, href: &str) -> Value {
    json!({"label": label, "href": href})
}

// ── Pages > Drive ──────────────────────────────────────────────────────────────────────────────
pub fn page_drive(_c: &Ctx, _r: &Req) -> Resp {
    let sv = serving::get();
    let b = apis::build();
    let mut groups = vec![];
    if let Some(d) = sv.endpoints.iter().find(|e| e.role == serving::Role::Drive) {
        groups.push(group(
            "Emulated storage",
            vec![json!({"head": d.title, "badges": [format!(":{}", d.port), "drive"], "lines": [d.summary.clone(), format!("root {}", b["runtime"]["serve_root"].as_str().unwrap_or(""))], "links": [link(&d.name, &d.url_name()), link(&format!("127.0.0.1:{}", d.port), &d.url_ip())]})],
        ));
        if let Ok(cfg) = repos::cfg() {
            if let Some(src) = cfg.sources.iter().find(|s| matches!(s.kind, Kind::Local { .. })) {
                let rows: Vec<Value> = repos::discover(cfg, &src.id)
                    .unwrap_or_default()
                    .iter()
                    .map(|rel| json!({"head": rel, "badges": ["git"], "lines": [], "links": [link("open", &format!("http://127.0.0.1:{}/{}/", d.port, rel))]}))
                    .collect();
                groups.push(group(&format!("Git repositories on {} · {}", src.label, rows.len()), rows));
            }
        }
    }
    let note = b["pages"]["drive"]["served_mode"].as_str().unwrap_or("");
    groups.push(group("Cloud-Drive served mode", vec![json!({"head": "Editor > Served", "badges": ["loopback", "random port"], "lines": [note], "links": []})]));
    ok(page("Drive", "The pages Emulated storage and Cloud-Drive serve on this phone.", groups))
}

// ── Pages > Terminals ──────────────────────────────────────────────────────────────────────────
pub fn page_terminals(_c: &Ctx, _r: &Req) -> Resp {
    let rows: Vec<Value> = serving::get()
        .endpoints
        .iter()
        .filter(|e| e.role == serving::Role::External)
        .map(|e| {
            let up = serving::is_up(e.port);
            json!({"head": e.title, "badges": [format!(":{}", e.port), if up { "answering" } else { "not answering" }, e.owner.clone()], "lines": [e.summary.clone(), "its ~/home is a servable root: pick it as a source in Repos".to_string()], "links": [link(&e.name, &e.url_name()), link(&format!("127.0.0.1:{}", e.port), &e.url_ip())]})
        })
        .collect();
    ok(page("Terminals", "Each terminal app's ~/home, served by the terminal's own my-webserver (the existing shell bridge) on loopback.", vec![group(&format!("{} terminals", rows.len()), rows)]))
}

// ── Pages > Repos ──────────────────────────────────────────────────────────────────────────────
fn entry_row(cfg: &Cfg, host: &Host, e: &Entry) -> Value {
    let running = host.running(&e.id);
    let mut actions = vec![if running { json!({"label": "Stop", "post": "/__app__/api/repos", "body": {"op": "stop", "id": e.id}}) } else { json!({"label": "Start", "post": "/__app__/api/repos", "body": {"op": "start", "id": e.id}}) }];
    actions.push(json!({"label": "Remove", "post": "/__app__/api/repos", "body": {"op": "remove", "id": e.id}}));
    let label = cfg.source(&e.source).map(|s| s.label.clone()).unwrap_or_else(|| e.source.clone());
    json!({
        "head": e.label,
        "badges": [label, e.exposure.clone(), format!(":{}", e.port), if running { "serving" } else { "stopped" }],
        "lines": [format!("{}{}", e.repo, if e.start.is_empty() { String::new() } else { format!("  ›  {}", e.start) })],
        "links": if running { vec![link(&repos::url(cfg, e), &repos::url(cfg, e))] } else { vec![] },
        "actions": actions
    })
}

pub fn repos_page(cfg: &Cfg, host: &Host, entries: &[Entry]) -> Value {
    let rows: Vec<Value> = entries.iter().map(|e| entry_row(cfg, host, e)).collect();
    let form = json!({
        "title": "Add a repo site",
        "post": "/__app__/api/repos",
        "discover": "/__app__/api/repos/discover?source=",
        "fields": [
            {"id": "label", "label": "Name", "kind": "text"},
            {"id": "source", "label": "Source", "kind": "select", "options": cfg.sources.iter().map(|s| json!({"value": s.id, "label": s.label})).collect::<Vec<_>>()},
            {"id": "repo", "label": "Repo", "kind": "repo"},
            {"id": "start", "label": "Start folder or entry file", "kind": "text"},
            {"id": "exposure", "label": "Reachable from", "kind": "select", "options": cfg.exposures.iter().map(|x| json!({"value": x, "label": x})).collect::<Vec<_>>()}
        ],
        "submit": {"label": "Add", "body": {"op": "add"}}
    });
    let mut p = page("Repos", "Each entry serves one repo (or one folder or file of it) on its own port, started and stopped on its own. Loopback or mesh only, never public.", vec![group(&format!("{} entries (at most {})", rows.len(), cfg.max_entries), rows)]);
    p["form"] = form;
    p
}

pub fn page_repos(_c: &Ctx, _r: &Req) -> Resp {
    let cfg = match repos::cfg() {
        Ok(c) => c,
        Err(e) => return err(500, &e),
    };
    match repos::load(&cfg.store) {
        Ok(entries) => ok(repos_page(cfg, repos::host(), &entries)),
        Err(e) => err(500, &e),
    }
}

/// One Repos operation against a store and a host: add | remove | start | stop. Err = (status, message).
pub fn repos_op(cfg: &Cfg, host: &Host, body: &Value) -> Result<Value, (u16, String)> {
    let t = |k: &str| body[k].as_str().unwrap_or("");
    let mut entries = repos::load(&cfg.store).map_err(|e| (500u16, e))?;
    let find = |entries: &[Entry]| entries.iter().position(|e| e.id == t("id")).ok_or((404u16, format!("no entry {}", t("id"))));
    match t("op") {
        "add" => {
            let e = repos::add(cfg, &mut entries, t("label"), t("source"), t("repo"), t("start"), t("exposure")).map_err(|e| (400u16, e))?;
            repos::save(&cfg.store, &entries).map_err(|e| (500u16, e))?;
            // "Add & serve" (Pages tab): the new place starts at once, on its own port, exactly like a `start` op.
            if body["serve"].as_bool() == Some(true) {
                let port = host.start(cfg, &e).map_err(|m| (409u16, format!("added, but not started: {}", m)))?;
                crate::power::set_site(&e.id, true);
                return Ok(json!({"ok": true, "entry": e.to_json(), "port": port, "url": repos::url(cfg, &e)}));
            }
            Ok(json!({"ok": true, "entry": e.to_json()}))
        }
        "remove" => {
            let i = find(&entries)?;
            host.stop(&entries[i].id);
            crate::power::set_site(&entries[i].id, false);
            entries.remove(i);
            repos::save(&cfg.store, &entries).map_err(|e| (500u16, e))?;
            Ok(json!({"ok": true}))
        }
        "start" => {
            let i = find(&entries)?;
            let port = host.start(cfg, &entries[i]).map_err(|e| (409u16, e))?;
            crate::power::set_site(&entries[i].id, true);
            Ok(json!({"ok": true, "port": port, "url": repos::url(cfg, &entries[i])}))
        }
        "stop" => {
            let i = find(&entries)?;
            crate::power::set_site(&entries[i].id, false);
            Ok(json!({"ok": true, "was_running": host.stop(&entries[i].id)}))
        }
        other => Err((400u16, format!("op {:?} is not add|remove|start|stop", other))),
    }
}

pub fn api_repos(_c: &Ctx, req: &Req) -> Resp {
    let cfg = match repos::cfg() {
        Ok(c) => c,
        Err(e) => return err(500, &e),
    };
    if req.method == "POST" {
        let body: Value = match serde_json::from_slice(&req.body) {
            Ok(v) => v,
            Err(_) => return err(400, "the body must be JSON"),
        };
        return match repos_op(cfg, repos::host(), &body) {
            Ok(v) => ok(v),
            Err((s, m)) => err(s, &m),
        };
    }
    match repos::load(&cfg.store) {
        Ok(entries) => ok(json!({"entries": entries.iter().map(|e| { let mut j = e.to_json(); j["running"] = json!(repos::host().running(&e.id)); j["url"] = json!(repos::url(cfg, e)); j }).collect::<Vec<_>>()})),
        Err(e) => err(500, &e),
    }
}

/// GET ?source=<id> (default: the first source): the git repositories of that source, for the add form's repo field.
pub fn api_discover(_c: &Ctx, req: &Req) -> Resp {
    let cfg = match repos::cfg() {
        Ok(c) => c,
        Err(e) => return err(500, &e),
    };
    let first = cfg.sources.first().map(|s| s.id.as_str()).unwrap_or("");
    match repos::discover(cfg, req.q("source").unwrap_or(first)) {
        Ok(list) => ok(json!({"repos": list})),
        Err(e) => err(404, &e),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::repos::{Cfg, Kind, Source};
    use std::fs;
    use std::net::{Ipv4Addr, TcpListener};
    use std::path::PathBuf;

    fn setup(tag: &str) -> (PathBuf, Cfg) {
        let d = std::env::temp_dir().join(format!("c3ws-sub-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&d);
        fs::create_dir_all(d.join("proj/.git")).unwrap();
        fs::write(d.join("proj/index.html"), "PROJ-HOME").unwrap();
        let d = fs::canonicalize(&d).unwrap();
        let first = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap().local_addr().unwrap().port();
        let cfg = Cfg { store: d.join("state/repos.json"), port_first: first, port_last: 65535, max_entries: 5, scan_depth: 3, exposures: vec!["loopback".into()], sources: vec![Source { id: "emu".into(), label: "Emulated".into(), kind: Kind::Local { root: d.clone() } }], mesh: None };
        (d, cfg)
    }

    #[test]
    fn add_start_stop_remove_persist_in_the_json_store() {
        let (d, cfg) = setup("ops");
        let host = Host::default();
        let r = repos_op(&cfg, &host, &json!({"op": "add", "label": "My Site", "source": "emu", "repo": "proj", "start": "", "exposure": "loopback"})).unwrap();
        let id = r["entry"]["id"].as_str().unwrap().to_string();
        assert_eq!(repos::load(&cfg.store).unwrap().len(), 1, "the entry is in the JSON store");
        let text = fs::read_to_string(&cfg.store).unwrap();
        assert!(text.contains("My Site") && text.contains("proj"));
        let s = repos_op(&cfg, &host, &json!({"op": "start", "id": id})).unwrap();
        let port = s["port"].as_u64().unwrap() as u16;
        let (st, _, body) = repos::http_get(port, "/index.html", std::time::Duration::from_secs(3)).unwrap();
        assert_eq!(st, 200);
        assert!(String::from_utf8_lossy(&body).contains("PROJ-HOME"));
        assert!(repos_page(&cfg, &host, &repos::load(&cfg.store).unwrap())["groups"][0]["rows"][0]["badges"].to_string().contains("serving"));
        assert_eq!(repos_op(&cfg, &host, &json!({"op": "stop", "id": id})).unwrap()["was_running"], true);
        assert_eq!(repos_op(&cfg, &host, &json!({"op": "start", "id": "nope"})).unwrap_err().0, 404);
        assert_eq!(repos_op(&cfg, &host, &json!({"op": "add", "label": "x", "source": "emu", "repo": "../proj"})).unwrap_err().0, 400, "a traversal is a 400");
        assert_eq!(repos_op(&cfg, &host, &json!({"op": "wat"})).unwrap_err().0, 400);
        // a second process (a restart) reads the same entries back.
        assert_eq!(repos::load(&cfg.store).unwrap()[0].id, id);
        repos_op(&cfg, &host, &json!({"op": "remove", "id": id})).unwrap();
        assert!(repos::load(&cfg.store).unwrap().is_empty());
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn the_page_form_offers_only_declared_sources_and_non_public_exposures() {
        let (d, cfg) = setup("form");
        let p = repos_page(&cfg, &Host::default(), &[]);
        let f = &p["form"]["fields"];
        assert_eq!(f[1]["options"][0]["value"], "emu");
        let expo: Vec<&str> = f[4]["options"].as_array().unwrap().iter().filter_map(|o| o["value"].as_str()).collect();
        assert_eq!(expo, vec!["loopback"]);
        assert!(!p.to_string().contains("public\""), "no public exposure is ever offered");
        let _ = fs::remove_dir_all(&d);
    }

    #[test]
    fn add_with_serve_starts_the_place_and_any_file_or_folder_is_a_place() {
        let (d, cfg) = setup("serve");
        fs::create_dir_all(d.join("plain")).unwrap();
        fs::write(d.join("plain/index.html"), "PLAIN-HOME").unwrap();
        fs::write(d.join("one.html"), "ONE-FILE").unwrap();
        let host = Host::default();
        let r = repos_op(&cfg, &host, &json!({"op": "add", "label": "Plain", "source": "emu", "repo": "plain", "exposure": "loopback", "serve": true})).unwrap();
        let port = r["port"].as_u64().unwrap() as u16;
        let (st, _, body) = repos::http_get(port, "/index.html", std::time::Duration::from_secs(3)).unwrap();
        assert_eq!((st, String::from_utf8_lossy(&body).contains("PLAIN-HOME")), (200, true), "a folder that is not a git repository is served");
        let f = repos_op(&cfg, &host, &json!({"op": "add", "label": "One", "source": "emu", "repo": "one.html", "exposure": "loopback", "serve": true})).unwrap();
        let (_, _, body) = repos::http_get(f["port"].as_u64().unwrap() as u16, "/", std::time::Duration::from_secs(3)).unwrap();
        assert!(String::from_utf8_lossy(&body).contains("ONE-FILE"), "a single file is served as the index");
        host.stop_all();
        let _ = fs::remove_dir_all(&d);
    }
}
