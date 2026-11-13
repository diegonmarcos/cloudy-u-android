// build.json::serving holds no port number. Each endpoint names its `owner` (an app dir at the repo
// root) and `port_from`, the dotted path inside that owner's build.json where the port is declared
// (the terminals' runtime.webserver.port, this app's runtime.port / runtime.diag.port). This script
// reads those declarations and writes the RESOLVED build.json to OUT_DIR, which serving.rs compiles
// in: the rest of the crate (parse, its tests, routes, dns) sees an ordinary `port` and never a
// second copy of one. A literal `port` in the source declaration, an unreadable owner or a path that
// does not lead to an integer stops the build here, not on the phone.
use serde_json::Value;
use std::path::{Path, PathBuf};
use std::{env, fs};

fn read(p: &Path) -> Value {
    println!("cargo:rerun-if-changed={}", p.display());
    let text = fs::read_to_string(p).unwrap_or_else(|e| panic!("read {}: {}", p.display(), e));
    serde_json::from_str(&text).unwrap_or_else(|e| panic!("parse {}: {}", p.display(), e))
}

fn main() {
    let manifest = PathBuf::from(env::var("CARGO_MANIFEST_DIR").expect("CARGO_MANIFEST_DIR"));
    let repo = manifest.join("..").join("..");
    let mut root = read(&manifest.join("..").join("build.json"));
    let endpoints = root["serving"]["endpoints"].as_array_mut().expect("build.json::serving.endpoints must be an array");
    for e in endpoints.iter_mut() {
        let id = e["id"].as_str().unwrap_or("?").to_string();
        if e.get("port").is_some() {
            panic!("build.json::serving.endpoints[{}] spells a literal `port`: name port_from in its owner's build.json instead (one declaration)", id);
        }
        let owner = e["owner"].as_str().unwrap_or_else(|| panic!("build.json::serving.endpoints[{}].owner missing", id)).to_string();
        let from = e["port_from"].as_str().unwrap_or_else(|| panic!("build.json::serving.endpoints[{}].port_from missing", id)).to_string();
        let decl = read(&repo.join(&owner).join("build.json"));
        let mut node = &decl;
        for key in from.split('.') {
            node = node.get(key).unwrap_or_else(|| panic!("{}/build.json has no {} (serving.endpoints[{}].port_from)", owner, from, id));
        }
        let port = node.as_u64().unwrap_or_else(|| panic!("{}/build.json {} is not an integer (serving.endpoints[{}])", owner, from, id));
        e["port"] = port.into();
    }
    // build.json::ssh_doors: every door's `<key>_from` (`file#dotted.path` from the repo root, the owner's own declaration) becomes
    // `<key>`; a literal next to a `_from` is a second copy and stops the build.
    // The same rule for ssh_doors' own keys (mesh_only_from: the hosting app's mesh_only block).
    {
        let top = root["ssh_doors"].as_object_mut().expect("build.json::ssh_doors must be an object");
        let froms: Vec<String> = top.keys().filter(|k| k.ends_with("_from")).cloned().collect();
        for k in froms {
            let spec = top[&k].as_str().unwrap_or_else(|| panic!("ssh_doors.{} is not a string", k)).to_string();
            let (file, path) = spec.split_once('#').unwrap_or_else(|| panic!("ssh_doors.{} must be file#path", k));
            let blob = read(&repo.join(file));
            let mut n = &blob;
            for key in path.split('.') {
                n = n.get(key).unwrap_or_else(|| panic!("{} has no {} (ssh_doors.{})", file, path, k));
            }
            top.insert(k.trim_end_matches("_from").to_string(), n.clone());
        }
    }
    for d in root["ssh_doors"]["doors"].as_array_mut().expect("build.json::ssh_doors.doors must be an array") {
        let id = d["id"].as_str().unwrap_or("?").to_string();
        let froms: Vec<String> = d.as_object().expect("a door is an object").keys().filter(|k| k.ends_with("_from")).cloned().collect();
        if d.get("port").is_some() {
            panic!("build.json::ssh_doors[{}] spells a literal `port`: name port_from in the owner's declaration instead", id);
        }
        for k in froms {
            let spec = d[k.as_str()].as_str().unwrap_or_else(|| panic!("ssh_doors[{}].{} is not a string", id, k)).to_string();
            let (file, path) = spec.split_once('#').unwrap_or_else(|| panic!("ssh_doors[{}].{} must be file#path", id, k));
            let blob = read(&repo.join(file));
            let mut n = &blob;
            for key in path.split('.') {
                n = n.get(key).unwrap_or_else(|| panic!("{} has no {} (ssh_doors[{}].{})", file, path, id, k));
            }
            let name = k.trim_end_matches("_from").to_string();
            if d.get(name.as_str()).is_some() {
                panic!("build.json::ssh_doors[{}] spells a literal `{}` next to {}", id, name, k);
            }
            d[name.as_str()] = n.clone();
        }
    }

    // #1490 the mesh bind address: the first IPv4 of the declared WireGuard profile's address (declared once, in its owner).
    let pf = root["mesh"]["profile_from"].clone();
    let owner = pf["owner"].as_str().expect("build.json::mesh.profile_from.owner");
    let decl = read(&repo.join(owner).join("build.json"));
    let mut node = &decl;
    for key in pf["path"].as_str().expect("build.json::mesh.profile_from.path").split('.') {
        node = node.get(key).unwrap_or_else(|| panic!("{}/build.json has no {} (mesh.profile_from)", owner, key));
    }
    let id = pf["id"].as_str().expect("build.json::mesh.profile_from.id");
    let field = pf["field"].as_str().expect("build.json::mesh.profile_from.field");
    let addr = node.as_array().expect("mesh.profile_from.path is not an array").iter().find(|p| p["id"] == id).unwrap_or_else(|| panic!("no profile {} in {}", id, owner))[field]
        .as_str().expect("the profile field is not a string").to_string();
    let bind = addr.split(',').map(|a| a.trim().split('/').next().unwrap_or("").to_string()).find(|a| a.parse::<std::net::Ipv4Addr>().is_ok())
        .unwrap_or_else(|| panic!("profile {} has no IPv4 address ({})", id, addr));
    root["mesh"]["bind"] = bind.into();

    // #1490 the API tab's data: every source is `file#dotted.path` from the repo root, declared in build.json::api.sources.
    let mut api = serde_json::Map::new();
    for (name, spec) in root["api"]["sources"].as_object().expect("build.json::api.sources").iter() {
        let spec = spec.as_str().unwrap_or_else(|| panic!("api.sources.{} is not a string", name));
        let (file, path) = spec.split_once('#').unwrap_or_else(|| panic!("api.sources.{} must be file#path", name));
        let blob = read(&repo.join(file));
        let mut n = &blob;
        // `file#` with no path is the whole document (#1718 the device access dataset).
        for key in path.split('.').filter(|k| !k.is_empty()) {
            n = n.get(key).unwrap_or_else(|| panic!("{} has no {} (api.sources.{})", file, path, name));
        }
        api.insert(name.clone(), n.clone());
    }
    let out_dir = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR"));
    fs::write(out_dir.join("api-data.json"), Value::Object(api).to_string()).expect("write api-data.json");

    let out = PathBuf::from(env::var("OUT_DIR").expect("OUT_DIR")).join("build.json");
    fs::write(&out, root.to_string()).unwrap_or_else(|e| panic!("write {}: {}", out.display(), e));
}
