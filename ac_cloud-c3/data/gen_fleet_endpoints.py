#!/usr/bin/env python3
"""Derive the `fleet_endpoints` block of data/api_providers.json (Infra > APIs > C3 Endpoints, #1718) from the fleet endpoint declaration.

The fleet services' public and mesh URLs used to be listed by Cloud C3 Webserver > API (Public Endpoints, and the fleet group of
Mesh Endpoints). That page shows only the phone now; the list lives here, in the app that is about the C3 environment. NOTHING is
typed: every service is READ from ac_cloud-account/build.json::fleet_endpoints.services (the declaration Cloud Account serves),
and each row keeps every field the webserver showed (id, kind, policy, health, the public URL, the mesh URL) plus the ones it did
not (source, auth, aliases, route paths). No secret: the declaration holds names and addresses only.

  python3 data/gen_fleet_endpoints.py write [ROOT]   rewrite the one `"fleet_endpoints": ...` line of data/api_providers.json
  python3 data/gen_fleet_endpoints.py check [ROOT]   exit 1 when that line differs from what the declaration produces
  python3 data/gen_fleet_endpoints.py gen   [ROOT]   print the block

ROOT is the repo root (default: two levels above this file).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = "ac_cloud-account/build.json"
KEY = "fleet_endpoints.services"
OUT = "ac_cloud-c3/data/api_providers.json"
GROUP = "c3_endpoints"
LINE = '  "fleet_endpoints": '


def public_url(s):
    """The webserver's apis.rs::public_url, to the letter."""
    d = s.get("public_domain")
    return "%s://%s%s" % (s.get("public_scheme") or "https", d, s.get("public_path") or "") if d else None


def mesh_url(s):
    """The webserver's apis.rs::mesh_url, to the letter."""
    d = s.get("mesh_dns")
    if not d:
        return None
    port = ":%d" % s["mesh_port"] if isinstance(s.get("mesh_port"), int) else ""
    return "%s://%s%s%s" % (s.get("mesh_scheme") or "http", d, port, s.get("mesh_path") or "")


def block(root):
    decl = json.load(open(os.path.join(root, SOURCE), encoding="utf-8"))
    node = decl
    for k in KEY.split("."):
        node = node[k]
    services = []
    for s in node:
        row = {"id": s["id"], "kind": s.get("kind", ""), "policy": s.get("policy", ""), "health": s.get("health", ""),
               "public_url": public_url(s), "mesh_url": mesh_url(s), "source": s.get("source", ""), "auth": s.get("auth", "")}
        if isinstance(row["auth"], (dict, list)):
            row["auth"] = json.dumps(row["auth"], sort_keys=True)
        row["aliases"] = [str(a) for a in s.get("aliases") or []] if isinstance(s.get("aliases"), list) else []
        rp = s.get("route_paths")
        row["route_paths"] = ["%s %s" % (k, v) for k, v in rp.items()] if isinstance(rp, dict) else [str(x) for x in rp or []]
        services.append(row)
    return {"group": GROUP, "source": "%s#%s" % (SOURCE, KEY), "generated_by": "ac_cloud-c3/data/gen_fleet_endpoints.py", "services": services}


def line(root):
    return LINE + json.dumps(block(root), ensure_ascii=False, separators=(", ", ": "))


def current(text):
    hits = [l for l in text.split("\n") if l.startswith(LINE)]
    return hits[0].rstrip(",") if len(hits) == 1 else None


def main(argv):
    if len(argv) < 2 or argv[1] not in ("gen", "write", "check"):
        print(__doc__)
        return 2
    root = argv[2] if len(argv) > 2 else os.path.abspath(os.path.join(HERE, "..", ".."))
    want = line(root)
    if argv[1] == "gen":
        print(want)
        return 0
    path = os.path.join(root, OUT)
    text = open(path, encoding="utf-8").read()
    have = current(text)
    if argv[1] == "check":
        if have != want:
            print("FAIL  %s::fleet_endpoints is stale: run python3 ac_cloud-c3/data/gen_fleet_endpoints.py write" % OUT)
            return 1
        print("fleet endpoints: %d services, current" % len(block(root)["services"]))
        return 0
    if have is None:
        print("ERROR: %s has no single line starting with %r" % (OUT, LINE), file=sys.stderr)
        return 1
    lines = text.split("\n")
    i = next(n for n, l in enumerate(lines) if l.startswith(LINE))
    lines[i] = want + ("," if lines[i].rstrip().endswith(",") else "")
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    json.load(open(path, encoding="utf-8"))
    print("wrote %s::fleet_endpoints (%d services)" % (OUT, len(block(root)["services"])))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
