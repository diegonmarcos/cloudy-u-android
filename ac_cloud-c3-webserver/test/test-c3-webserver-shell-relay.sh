#!/usr/bin/env bash
# #1682 SECURITY guard: the webserver's network routes never relay shell commands, except the routes declared in data/mesh-shell-relay.json.
#   S1  every /ipc registry route that reaches the shell broker with a command-running method is declared (route, app, scope, method)
#   S2  every declared route still exists with its scope, its POST + cmd check, and the declared method
#   S3  the auth chain is intact: meshlogs::route runs authorize() before the /ipc gateway, and the gateway checks the peer scope
#       (allowed(&peer.scopes, &entry.acl)) before the provider call
#   S4  the declared scope exists and only the declared peers hold it (or '*') (the broker allowlist side, caller == alias of the webserver,
#       is aa_cloud-superapp/test/test-shell-broker-guards.sh B10: that file is the broker's, outside this app)
#   S5  no other Rust source (server/src, core/src) names the shell broker: no second, undeclared relay
# Static: no build, no device. Then each violation is planted and must turn red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }
python3 - "$APP" <<'PY'
import copy, json, os, re, sys
APP = sys.argv[1]
def rd(p):
    with open(p, encoding="utf-8") as f: return f.read()
def strip(s): return re.sub(r"//[^\n]*|/\*.*?\*/", "", s, flags=re.S)
decl = json.load(open(os.path.join(APP, "data/mesh-shell-relay.json")))
ipc = json.load(open(os.path.join(APP, "data/api-ipc-map.json")))
allow = {}
rust = {}
for base in ("server/src", "core/src"):
    for d, _, fs in os.walk(os.path.join(APP, base)):
        for f in fs:
            if f.endswith(".rs"): p = os.path.join(d, f); rust[os.path.relpath(p, APP)] = rd(p)
def ipc_main(s):   # ipc.rs without its #[cfg(test)] module
    i = s.find("#[cfg(test)]"); return strip(s if i < 0 else s[:i])
def relays(e):
    i = e.get("ipc") or {}
    if "shellbroker" not in (i.get("endpoint", "") + i.get("authority", "")): return False
    ms = ([i["method"]] if i.get("method") else []) + list(i.get("methods", []))
    return any(m not in decl["read_only_methods"] for m in ms) or not ms
def checks(D, M, A, R):
    out = []
    ents = M["ipc_registry"]["entries"]
    key = lambda e: (e["route"], e.get("app_class"))
    dec = {(a["route"], a["app_class"]): a for a in D["allowed"]}
    out.append(("S1 every shell-relaying route is declared", [e["route"] for e in ents if relays(e) and key(e) not in dec]))
    bad = []
    for k, a in dec.items():
        e = next((e for e in ents if key(e) == k), None)
        i = (e or {}).get("ipc") or {}
        if not e or e.get("acl") != a["acl"] or i.get("method") != a["method"] or not i.get("post") or not i.get("require_cmd"): bad.append(a["route"])
    out.append(("S2 every declared route keeps its scope, POST + cmd check and method", bad))
    g = ipc_main(R.get("server/src/ipc.rs", "")); gw = g[g.find("pub fn gateway("):]
    m = strip(R.get("server/src/meshlogs.rs", "")); rt = m[m.find("pub fn route("):]
    sc, call = gw.find("if !allowed(&peer.scopes, &entry.acl)"), gw.find("src(&authority")
    au, gi = rt.find("authorize(req.header(\"authorization\")"), rt.find("crate::ipc::gateway(")
    out.append(("S3 bearer gate before the gateway, scope check before the provider call",
                [] if (0 <= sc < call and 0 <= au < gi) else ["ipc.rs/meshlogs.rs"]))
    scopes = {a["acl"] for a in D["allowed"]}
    holders = sorted(p for p, v in M["peers"].items() if not p.startswith("_") and (set(v.get("scopes", [])) & scopes or "*" in v.get("scopes", [])))
    ok4 = all(s in M["acl_scopes"] for s in scopes) and holders == sorted(D["scope_peers"])
    out.append(("S4 the scope, its peers and the broker caller are the declared ones", [] if ok4 else ["peers/allowlist"]))
    out.append(("S5 no other Rust source names the shell broker", sorted(k for k, v in R.items() if k != "server/src/ipc.rs" and "shellbroker" in strip(v)) + (["server/src/ipc.rs"] if "shellbroker" in g else [])))
    return out
fails = 0
for n, b in checks(decl, ipc, allow, rust):
    print(("  PASS: " if not b else "  FAIL: ") + n + ("" if not b else " -> " + ", ".join(b))); fails += 1 if b else 0
def plant(label, rid, f):
    global fails
    D, M, A, R = copy.deepcopy(decl), copy.deepcopy(ipc), copy.deepcopy(allow), dict(rust)
    f(D, M, A, R)
    red = any(n.startswith(rid) and b for n, b in checks(D, M, A, R))
    print(("  PASS: " if red else "  FAIL: ") + f"planted: {label} turns {rid} red"); fails += 0 if red else 1
first = lambda M, r: next(e for e in M["ipc_registry"]["entries"] if e["route"] == r and "superapp" in e.get("app_class", ""))
plant("a new undeclared relay route", "S1", lambda D, M, A, R: M["ipc_registry"]["entries"].append(dict(copy.deepcopy(first(M, "exec")), route="exec2")))
plant("an undeclared route switching status to exec", "S1", lambda D, M, A, R: first(M, "status")["ipc"].__setitem__("method", "exec_json"))
plant("a declared route losing its scope", "S2", lambda D, M, A, R: first(M, "exec").__setitem__("acl", "logs.read"))
plant("a declared route losing its cmd check", "S2", lambda D, M, A, R: first(M, "exec")["ipc"].__setitem__("require_cmd", False))
plant("the declared method changing", "S2", lambda D, M, A, R: D["allowed"][0].__setitem__("method", "exec"))
plant("the gateway's scope check removed", "S3", lambda D, M, A, R: R.__setitem__("server/src/ipc.rs", R["server/src/ipc.rs"].replace("if !allowed(&peer.scopes, &entry.acl)", "if false")))
plant("the bearer gate removed", "S3", lambda D, M, A, R: R.__setitem__("server/src/meshlogs.rs", R["server/src/meshlogs.rs"].replace('authorize(req.header("authorization")', 'Option::<Resp>::None.or(None).xor(None); let _ = (')))
plant("the read-only peer granted shell.exec", "S4", lambda D, M, A, R: M["peers"]["ops-console"]["scopes"].append("shell.exec"))
plant("a second peer granted the scope", "S4", lambda D, M, A, R: M["peers"]["browser-peer"]["scopes"].append("shell.exec"))
plant("a second relay in another Rust file", "S5", lambda D, M, A, R: R.__setitem__("server/src/app.rs", R.get("server/src/app.rs", "") + '\nconst X: &str = "com.diegonmarcos.superapp.shellbroker";'))
print("FAIL" if fails else "PASS: no undeclared shell relay on the network routes"); sys.exit(1 if fails else 0)
PY
