#!/usr/bin/env python3
"""#1495 Generate data/mcp-catalogue.json, the data of Cloud-Webserver > API > MCP: the MCP tool catalogue of ALL our APIs and MCP servers.

Derived, never typed. Inputs (paths given on the command line, none is a copy):
  --containers <cloudy-u-containers checkout>   every <service>/build.json (mcp and api blocks, proxy, aliases) and the cloudy-c3-mcp tool sources
  --linux <cloudy-u-linux da_my-ai/data/claude> mcp-policy.json (renames, aliases) and api-renames.json
  ../ac_cloud-c3/data/c3_hierarchy.json         the one hierarchy: which page (and so which cloudy-c3-api routes) each cloudy-c3-mcp tool belongs to
Output rows: server (canonical name, aliases, endpoint, auth, the API it fronts, tools) with, for cloudy-c3-mcp, one row per tool and the
API route (tree path and legacy patterns) of its page. `--check` exits 1 when the committed file differs (needs the inputs).
Auth is what the edge enforces (build.json proxy.primary: two_factor, bearer_auth, wg_only) plus the server's own mcp.auth."""
import argparse, glob, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, "mcp-catalogue.json")
HIER = os.path.join(HERE, "..", "..", "ac_cloud-c3", "data", "c3_hierarchy.json")
C3 = "cloudy-c3-mcp"


def J(p):
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def leaves(nodes, pre=""):
    for n in nodes:
        p = pre + n["id"]
        if n.get("children"):
            yield from leaves(n["children"], p + "/")
        else:
            yield p, n


def owner(h, name):
    best = None
    for p, n in leaves(h["sections"]):
        for pat in n.get("mcp", []):
            ok = name.startswith(pat[:-1]) if pat.endswith("*") else pat == name
            if ok:
                sc = len(pat) - 1 if pat.endswith("*") else 10 ** 9
                if best is None or sc > best[0]:
                    best = (sc, p, n)
    return best[1:] if best else (None, None)


def tool_names(src_dir, skip=("meta.ts",)):
    out = []
    for f in sorted(glob.glob(os.path.join(src_dir, "*.ts"))):
        if os.path.basename(f) in skip or f.endswith(".test.ts"):
            continue
        with open(f, encoding="utf-8") as fh:
            out += re.findall(r"""\btool\(\s*["'`]([A-Za-z0-9_.\-]+)["'`]""", fh.read())
    return out


def auth_of(b):
    pr = (b.get("proxy") or {}).get("primary") or {}
    a = []
    if pr.get("auth"):
        a.append(pr["auth"])
    if pr.get("bearer_auth"):
        a.append("bearer")
    if pr.get("wg_only"):
        a.append("mesh only")
    own = (b.get("mcp") or {}).get("auth")
    if own and own != "none":
        a.append(own)
    return " + ".join(dict.fromkeys(a)) or "none"


def route_of(node_path, node):
    api = node.get("api", [])
    pre = "/" + node_path
    if not api:
        return f"{pre} (no API route: MCP only)"
    shown = " ".join(api[:4]) + (f" +{len(api) - 4}" if len(api) > 4 else "")
    return f"{pre} <- {shown}"


def build(containers, linux):
    h = J(HIER)
    pol = J(os.path.join(linux, "mcp-policy.json"))
    ren = J(os.path.join(linux, "api-renames.json"))
    aliases = pol["renames"]["aliases"]                       # old server name -> canonical
    aliases = {**aliases, "cloudy-android-mcp": C3}            # #1495: merged into cloudy-c3-mcp
    aliases["cloud-superapp-mcp"] = C3
    builds = {}
    for f in sorted(glob.glob(os.path.join(containers, "*", "build.json"))):
        try:
            b = J(f)
        except ValueError:
            continue
        builds[os.path.basename(os.path.dirname(f))] = b
    api_names = {b["name"] for b in builds.values() if b.get("api")}
    servers = {}
    for folder, b in builds.items():
        if not (b.get("mcp") or b["name"].endswith("-mcp")):
            continue
        canon = aliases.get(b["name"], b["name"])
        s = servers.setdefault(canon, {"name": canon, "aliases": set(), "folders": [], "tools_count": 0, "auth": None, "endpoint": None})
        s["folders"].append(folder)
        if b["name"] != canon:
            s["aliases"].add(b["name"])
        if b["name"] == canon or s["auth"] is None:   # the canonical build wins; an old-name build serves while there is none
            pr = (b.get("proxy") or {}).get("primary") or {}
            s["auth"] = auth_of(b)
            s["endpoint"] = "https://%s%s%s" % (b.get("domain", "mcp.diegonmarcos.com").split("/")[0], pr.get("base_path", ""), (b.get("mcp") or {}).get("endpoint_path", "/mcp"))
            s["tools_count"] = (b.get("mcp") or {}).get("tools_count", 0)
        for a in (b.get("legacy_aliases") or {}).get("names", []):
            if a != canon:
                s["aliases"].add(a)
    for old, new in aliases.items():
        if new in servers and old != new:
            servers[new]["aliases"].add(old)
    out = []
    for name in sorted(servers):
        s = servers[name]
        api = name[:-4] + "-api"
        row = {"name": name, "aliases": sorted(s["aliases"]), "endpoint": s["endpoint"] or "", "auth": s["auth"] or "none",
               "api": api if api in api_names else "", "tools_count": s["tools_count"], "tools": []}
        if name == C3:
            src = os.path.join(containers, "infra-api_cloud-infra-mcp", "src", "code", "mcp", "tools")
            for t in tool_names(src):
                path, node = owner(h, t)
                if path is None:
                    sys.exit(f"{t}: no node of c3_hierarchy.json owns this tool")
                sec = path.split("/")[0]
                method = ".".join(path.split("/")[1:] + [t.replace(".", "_")])
                row["tools"].append({"tool": t, "tree": f"c3_{sec} {method}", "route": route_of(path, node), "node": path,
                                     **({"legacy": "superapp_" + t[6:]} if t.startswith("peers.") else {})})
            row["tools_count"] = len(row["tools"])
        out.append(row)
    return {"_doc": "GENERATED by ac_cloud-c3-webserver/data/gen_mcp_catalogue.py (#1495) from the build.json files of cloudy-u-containers, mcp-policy.json and api-renames.json of cloudy-u-linux da_my-ai/data/claude, and ac_cloud-c3/data/c3_hierarchy.json. DO NOT EDIT. The data of Cloud-Webserver > API > MCP.",
            "servers": out}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--containers", required=True)
    ap.add_argument("--linux", required=True)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    text = json.dumps(build(a.containers, a.linux), indent=1, ensure_ascii=False) + "\n"
    if a.check:
        if open(OUT, encoding="utf-8").read() != text:
            sys.exit("data/mcp-catalogue.json is stale: re-run data/gen_mcp_catalogue.py")
        return
    open(OUT, "w", encoding="utf-8").write(text)


if __name__ == "__main__":
    main()
