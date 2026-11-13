#!/usr/bin/env python3
"""cloud-android-device-access -- THE device access model: one dataset both Cloud Store > Access and Cloud C3 Webserver > API draw.

Nothing is typed here. Every row is read from a declaration that already exists:

  aa_cloud-superapp/data/constellation-fleet.json   the fleet rows (id, package, label, kind, engines)
  aa_cloud-superapp/data/ipc-mesh.json              each member's fleet IPC ops (its own API routes) and the fleet permission
  1_cicd/src/data/debug-ports.json                  the on-phone API gateway and each member's port
  ac_cloud-c3-webserver/data/api-ipc-map.json       the webserver's own routes, the unified /ipc holder (scopes, peers, registry)
  ac_cloud-c3-webserver/build.json                  api.ipc (the per-app IPC templates), api.engine_action, mesh (the listener's routes)
  ac_cloud-c3-webserver/data/mcp-catalogue.json     the MCP tools that act on this device (node infra/personal/control)
  */build.json::api_endpoints                       each app's declared endpoints (cloud-android-apps-mcp-api.py consolidates them)
  aa_cloud-superapp/data/fleet-identity.json        the shared-uid groups (apps that reach each other's files with no IPC)
  1_cicd/src/data/adb-owner.json                    the one app that owns the ADB shell

The output is the runtime dataset data/fleet/device-access.json (tier 3, served by Cloud Store's fleet data provider, read through
libs:fleet-data-client; the webserver's Rust core compiles the same file in). The model that parses it is ONE class,
libs:fleet-data-client DeviceAccess. An app's API count is the number of routes it really exposes (ops + declared endpoints + its
own listener routes), so an app that declares an API never reads zero.

  gen ROOT     print the dataset
  write ROOT   write data/fleet/device-access.json
  check ROOT   exit 1 when a declaration is invalid or the committed dataset differs from gen
"""
import importlib.util
import json
import os
import sys

OUT = "data/fleet/device-access.json"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
IPC_MESH = "aa_cloud-superapp/data/ipc-mesh.json"
PORTS = "1_cicd/src/data/debug-ports.json"
MAP = "ac_cloud-c3-webserver/data/api-ipc-map.json"
HOLDER = "ac_cloud-c3-webserver/build.json"
CATALOGUE = "ac_cloud-c3-webserver/data/mcp-catalogue.json"
IDENTITY = "aa_cloud-superapp/data/fleet-identity.json"
ADB_OWNER = "1_cicd/src/data/adb-owner.json"
DEVICE_NODE = "infra/personal/control"


def load(root, rel):
    with open(os.path.join(root, rel), encoding="utf-8") as h:
        return json.load(h)


def declared_endpoints(root):
    """The consolidation Store > Access already used (one derivation, not a second reader of api_endpoints)."""
    here = os.path.dirname(os.path.abspath(__file__))
    spec = importlib.util.spec_from_file_location("apps_mcp_api", os.path.join(here, "cloud-android-apps-mcp-api.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod.consolidate(root)


def dotted(node, path):
    for key in path.split("."):
        node = node.get(key) if isinstance(node, dict) else None
    return node


def clean(d):
    return {k: v for k, v in d.items() if not k.startswith("_")}


def build(root):
    problems = []
    fleet = load(root, FLEET)
    mesh = load(root, IPC_MESH)
    ports = load(root, PORTS)
    amap = load(root, MAP)
    holder = load(root, HOLDER)
    cat = load(root, CATALOGUE)
    ident = load(root, IDENTITY)
    owner = load(root, ADB_OWNER)
    decl, decl_problems = declared_endpoints(root)
    problems += decl_problems

    rows = {a["package"]: a for a in fleet.get("apps", [])}
    id_by_pkg = {p: a["id"] for p, a in rows.items()}
    holder_pkg = (holder.get("android") or {}).get("application_id", "")
    holder_id = id_by_pkg.get(holder_pkg)
    if not holder_id:
        problems.append("%s: android.application_id %r is not a fleet row" % (HOLDER, holder_pkg))
    shared = mesh.get("shared", {})
    reg = amap.get("ipc_registry", {})
    prefix = reg.get("route_prefix", "/ipc")
    aliases = clean(reg.get("app_aliases", {}))
    endpoint_ids = {e["id"] for e in amap.get("ipc_endpoints", [])}

    def registry_for(pkg, fid):
        out = []
        for e in reg.get("entries", []):
            cls = e.get("app_class", "")
            own = cls == "fleet" or cls == "package:" + pkg
            also = fid in e.get("also_apps", [])
            if not (own or also):
                continue
            ipc = e.get("ipc", {})
            if ipc.get("endpoint") not in endpoint_ids:
                problems.append("%s: registry route %s names unknown ipc endpoint %r" % (MAP, e.get("route"), ipc.get("endpoint")))
            # the authority is always the package the class names, never the alias the caller used
            target = pkg if own else cls[len("package:"):]
            methods = ipc.get("methods") or ([ipc["method"]] if ipc.get("method") else [])
            if ipc.get("method_from_body"):
                methods = [v for k, v in ipc["method_from_body"].items() if k != "field"]
            out.append({
                "route": e["route"],
                "path": "%s/%s/%s" % (prefix, fid, e["route"]),
                "method": "POST" if ipc.get("post") else "GET",
                "kind": ipc.get("kind", ""),
                "authority": str(ipc.get("authority", "")).replace("{package}", target),
                "methods": methods,
                "acl": e.get("acl", ""),
                "status": e.get("status", ""),
                "mcp_tool": e.get("mcp_tool"),
                "legacy_route": e.get("legacy_route"),
                "class": cls,
                "what": e.get("what", ""),
            })
        return out

    apps = []
    for m in mesh.get("members", []):
        pkg = m["pkg"]
        row = rows.get(pkg)
        if row is None:
            problems.append("%s: member %s (%s) is not a fleet row" % (IPC_MESH, m.get("id"), pkg))
            continue
        fid = row["id"]
        eps = list(m.get("endpoints", [])) + [e for lib in m.get("libs", []) for e in shared.get(lib, [])]
        ops = sorted({e["name"] for e in eps if e.get("kind") == "op" and e.get("name")})
        own_routes = []
        if pkg == holder_pkg:
            for r in amap.get("routes", []):
                own_routes.append({k: r[k] for k in ("path", "surface", "method", "ipc", "via", "mcp_tools") if k in r})
        declared = decl["apps"].get(fid, {})
        apps.append({
            "id": fid,
            "pkg": pkg,
            "label": row.get("display_name") or row.get("label") or fid,
            "kind": row.get("kind", "app"),
            "door": bool(m.get("door")),
            "port": ports.get("ports", {}).get(pkg),
            "engines": [e["binding"] for e in row.get("engines", []) if e.get("binding")],
            "ops": ops,
            "routes": own_routes,
            "declared": {k: declared[k] for k in ("mcp", "debug", "app") if k in declared},
            "ipc": registry_for(pkg, fid),
        })

    server = next((s for s in cat.get("servers", []) if any(t.get("node") == DEVICE_NODE for t in s.get("tools", []))), None)
    by_tool = {}
    for r in amap.get("routes", []):
        for t in r.get("mcp_tools") or []:
            by_tool.setdefault(t, []).append({"path": r["path"], "surface": r["surface"], "method": r.get("method", "GET")})
    for e in reg.get("entries", []):
        if e.get("mcp_tool"):
            cls = e.get("app_class", "")
            who = "<app>" if cls == "fleet" else id_by_pkg.get(cls[len("package:"):], cls)
            by_tool.setdefault(e["mcp_tool"], []).append({"path": "%s/%s/%s" % (prefix, who, e["route"]), "surface": "mesh", "method": "POST" if e.get("ipc", {}).get("post") else "GET", "acl": e.get("acl", "")})
    tools = []
    for t in (server or {}).get("tools", []):
        if t.get("node") != DEVICE_NODE:
            continue
        routes = by_tool.get(t["tool"], [])
        tools.append({
            "tool": t["tool"],
            "legacy": t.get("legacy"),
            "tree": t.get("tree", ""),
            "door": "mesh" if routes else "debug",
            "routes": routes,
        })
    if not tools:
        problems.append("%s: no tool of node %s (the device MCP tools)" % (CATALOGUE, DEVICE_NODE))

    def group_uid(g):
        ref = g.get("shared_user_id")
        if isinstance(ref, str):
            return ref
        if isinstance(ref, dict) and ref.get("from"):
            try:
                v = dotted(load(root, ref["from"]), ref.get("key", ""))
            except (OSError, ValueError):
                v = None
            return v if isinstance(v, str) else None
        return None

    hm = holder.get("mesh", {})
    gw = ports.get("gateway", {})
    doc = {
        "schemaVersion": 1,
        "_doc": "GENERATED by 1_cicd/src/scripts/cloud-android-device-access.py from the fleet declarations (do not edit; run the "
                "script's write). THE device access model: this phone's MCP doors and tools, each app's own API routes, each app's "
                "IPC routes behind the one mesh gateway, the identities that reach each other without IPC and the app that owns the "
                "ADB shell. Read by libs:fleet-data-client DeviceAccess (Cloud Store > Access, Cloud C3 Webserver > API).",
        "permission": mesh.get("permission", {}),
        "door": {k: mesh.get("door", {}).get(k, "") for k in ("authority", "method", "probe")},
        "gateways": {
            "debug": {
                "pkg": gw.get("pkg", ""), "app": id_by_pkg.get(gw.get("pkg", "")), "bind": gw.get("bind", ""), "port": gw.get("port"),
                "route": gw.get("route", ""), "roster": gw.get("apps", ""),
                "what": "the one on-phone API gateway: every app's own routes (ops), loopback only, reached over ssh",
            },
            "mesh": {
                "pkg": holder_pkg, "app": holder_id,
                "listener": amap.get("surfaces", {}).get("mesh", {}).get("listener", ""),
                "auth": amap.get("surfaces", {}).get("mesh", {}).get("auth", ""),
                "port": dotted(holder, "runtime.mesh.port"),
                "cidrs": hm.get("cidrs", []),
                "ipc_route": hm.get("ipc_route", prefix), "logs_route": hm.get("logs_route", ""), "endpoints_route": hm.get("endpoints_route", ""),
                "aliases": aliases,
                "what": "the one mesh gateway: every app's on-device IPC behind <ipc_route>/<app>/<route>, per-peer ACL, mesh peers only",
            },
        },
        "surfaces": clean(amap.get("surfaces", {})),
        "acl_scopes": clean(amap.get("acl_scopes", {})),
        "peers": [{"id": k, "scopes": v.get("scopes", []), "what": v.get("what", "")} for k, v in clean(amap.get("peers", {})).items()],
        "mcp": {
            "server": (server or {}).get("name", ""), "endpoint": (server or {}).get("endpoint", ""), "auth": (server or {}).get("auth", ""),
            "aliases": (server or {}).get("aliases", []), "node": DEVICE_NODE, "tools": tools,
        },
        "kinds": decl.get("kinds", []),
        "shared_debug": decl.get("shared_debug", {}),
        "ipc_templates": [clean(t) for t in holder.get("api", {}).get("ipc", [])],
        "engine_action": holder.get("api", {}).get("engine_action", ""),
        "apps": apps,
        "identity": {"groups": [{"id": g["id"], "label": g.get("label", g["id"]), "uid": group_uid(g), "members": g.get("members", [])} for g in ident.get("groups", [])]},
        "privileged": {"adb_owner": {"app": id_by_pkg.get(owner.get("package", "")), "pkg": owner.get("package", ""), "dir": owner.get("adb_owner", "")}},
    }
    if doc["gateways"]["mesh"]["port"] is None:
        problems.append("%s: runtime.mesh.port is not declared" % HOLDER)
    for a in apps:
        if a["door"] and not a["ops"]:
            problems.append("%s exposes the fleet door but no op: its API would read zero" % a["id"])
    return doc, problems


def dump(doc):
    return json.dumps(doc, indent=1, ensure_ascii=False) + "\n"


def main(argv):
    if len(argv) != 3 or argv[1] not in ("gen", "write", "check"):
        print(__doc__)
        return 2
    root = argv[2]
    doc, problems = build(root)
    if argv[1] == "gen":
        sys.stdout.write(dump(doc))
        return 1 if problems else 0
    if argv[1] == "write":
        for p in problems:
            print("ERROR: " + p, file=sys.stderr)
        if problems:
            return 1
        with open(os.path.join(root, OUT), "w", encoding="utf-8") as h:
            h.write(dump(doc))
        print("wrote %s (%d apps, %d device MCP tools)" % (OUT, len(doc["apps"]), len(doc["mcp"]["tools"])))
        return 0
    path = os.path.join(root, OUT)
    committed = open(path, encoding="utf-8").read() if os.path.exists(path) else ""
    if committed != dump(doc):
        problems.append("%s is stale: run python3 1_cicd/src/scripts/cloud-android-device-access.py write ." % OUT)
    for p in problems:
        print("FAIL  " + p)
    print("── device access: %d apps, %d problem(s) ──" % (len(doc["apps"]), len(problems)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
