#!/usr/bin/env python3
"""Derive the `mcp_catalogue` block of data/api_providers.json (Infra > APIs > C3 MCP, #1725) from the generated MCP catalogue.

The MCP servers of the C3 environment and the c3-api routes their tools map to used to be listed by Cloud C3 Webserver > API (its
MCP page and the remote nodes of its Topology). That app shows only the phone now (the tools that act on the device stay there);
the rest lives here. NOTHING is typed: every server and tool is READ from ac_cloud-c3-webserver/data/mcp-catalogue.json (itself
generated from the containers' declarations), minus the device tools named by data/fleet/device-access.json::mcp.tools. Each
server keeps every field the webserver showed (name, endpoint, auth, aliases, the API it fronts, its tool count) and each tool its
own (tool, API route, tree, node, old name). No secret: names and addresses only.

  python3 data/gen_c3_mcp.py write [ROOT]   rewrite the one `"mcp_catalogue": ...` line of data/api_providers.json
  python3 data/gen_c3_mcp.py check [ROOT]   exit 1 when that line differs from what the catalogue produces
  python3 data/gen_c3_mcp.py gen   [ROOT]   print the block

ROOT is the repo root (default: two levels above this file).
"""
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
SOURCE = "ac_cloud-c3-webserver/data/mcp-catalogue.json"
DEVICE = "data/fleet/device-access.json"
OUT = "ac_cloud-c3/data/api_providers.json"
GROUP = "c3_mcp"
LINE = '  "mcp_catalogue": '


def block(root):
    cat = json.load(open(os.path.join(root, SOURCE), encoding="utf-8"))
    device = {t["tool"] for t in json.load(open(os.path.join(root, DEVICE), encoding="utf-8"))["mcp"]["tools"]}
    servers = []
    for s in cat.get("servers", []):
        tools = [{"tool": t["tool"], "route": t.get("route", ""), "tree": t.get("tree", ""), "node": t.get("node", ""), "legacy": t.get("legacy")}
                 for t in s.get("tools", []) if t["tool"] not in device]
        servers.append({"name": s["name"], "endpoint": s.get("endpoint", ""), "auth": s.get("auth", ""), "aliases": s.get("aliases", []),
                        "api": s.get("api", ""), "tools_count": s.get("tools_count", 0),
                        "device_tools": sum(1 for t in s.get("tools", []) if t["tool"] in device), "tools": tools})
    return {"group": GROUP, "source": SOURCE + "#servers", "generated_by": "ac_cloud-c3/data/gen_c3_mcp.py", "servers": servers}


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
    n = sum(len(s["tools"]) for s in block(root)["servers"])
    if argv[1] == "check":
        if have != want:
            print("FAIL  %s::mcp_catalogue is stale: run python3 ac_cloud-c3/data/gen_c3_mcp.py write" % OUT)
            return 1
        print("C3 MCP catalogue: %d servers, %d tools, current" % (len(block(root)["servers"]), n))
        return 0
    if have is None:
        print("ERROR: %s has no single line starting with %r" % (OUT, LINE), file=sys.stderr)
        return 1
    lines = text.split("\n")
    i = next(k for k, l in enumerate(lines) if l.startswith(LINE))
    lines[i] = want + ("," if lines[i].rstrip().endswith(",") else "")
    open(path, "w", encoding="utf-8").write("\n".join(lines))
    json.load(open(path, encoding="utf-8"))
    print("wrote %s::mcp_catalogue (%d servers, %d tools)" % (OUT, len(block(root)["servers"]), n))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
