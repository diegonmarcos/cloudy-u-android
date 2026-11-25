# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/cloud-android-apps-mcp-api.py ───
#!/usr/bin/env python3
"""cloud-android-apps-mcp-api -- the endpoints every Store > Cloud > Apps app declares, for Store > Access > Apps MCP-API.

No list of endpoints is typed in the Store. Every app declares its own in its build.json, top level,

  api_endpoints: {mcp: [E...], debug: [E...], app: [E...]}      E = {method, path, what}

(kinds: mcp = tools an agent calls over MCP; debug = routes reporting the app's own state and logs; app = its
functional API). A kind an app leaves out is MISSING and the page says so; an app with no debug list inherits the
shared default. The defaults and the page's words are declared once, in ac_cloud-store/build.json::apps_mcp_api
(apps_group, kinds, shared_debug). This script consolidates them into
ab_cloud-libs-shared/libs/appstore/src/main/assets/apps-mcp-api.json (libs:appstore AppsMcpApi reads it), keyed by
the app's fleet row id (found through android.application_id == the fleet row's package).

  gen ROOT     print the consolidated JSON
  write ROOT   write the asset (aa_cloud-superapp/data/regen.sh calls this)
  check ROOT   exit 1 when a declaration is invalid or the committed asset differs from gen
"""
import glob
import json
import os
import sys

# #1566 the output is a fleet-private tier-2 dataset (served as fleet/apps-mcp-api by Cloud Account's private route): it lives in
# cloud-me-pvt-configs fleet-data/2/fleet/, CI materialises it into the gitignored fleet-private-data/ (FLEET_PRIVATE_DIR overrides).
# `write` writes the generated copy there; publish it to the private repo. `check` compares the materialised private copy.
OUT = "fleet-private-data/apps-mcp-api.json"
FLEET = "aa_cloud-superapp/data/constellation-fleet.json"
STORE = "ac_cloud-store/build.json"
METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"]


def build_jsons(root):
    out = []
    for pat in ("*/build.json", "*/*/build.json"):
        for p in sorted(glob.glob(os.path.join(root, pat))):
            rel = os.path.relpath(p, root)
            if not rel.startswith(("z_archive/", ".")):
                out.append(rel)
    return out


def endpoints(rows, where, problems):
    out = []
    if not isinstance(rows, list):
        problems.append("%s is not a list" % where)
        return out
    for i, e in enumerate(rows):
        if not isinstance(e, dict) or e.get("method") not in METHODS or not str(e.get("path", "")).startswith("/") or not e.get("what"):
            problems.append("%s[%d] needs method (%s), a path starting with / and what" % (where, i, "/".join(METHODS)))
            continue
        out.append({"method": e["method"], "path": e["path"], "what": e["what"]})
    return out


def consolidate(root):
    problems = []
    fleet = json.load(open(os.path.join(root, FLEET)))
    id_by_pkg = {a["package"]: a["id"] for a in fleet.get("apps", [])}
    members = {m for g in fleet.get("groups", []) for m in g.get("members", [])}
    decl = json.load(open(os.path.join(root, STORE))).get("apps_mcp_api") or {}
    kinds = decl.get("kinds") or []
    kind_ids = [k.get("id") for k in kinds]
    if kind_ids != ["mcp", "debug", "app"]:
        problems.append("%s::apps_mcp_api.kinds must be mcp, debug, app" % STORE)
    group = decl.get("apps_group", "")
    gmembers = next((g.get("members", []) for g in fleet.get("groups", []) if g.get("id") == group), None)
    if gmembers is None:
        problems.append("%s::apps_mcp_api.apps_group %r is not a fleet group" % (STORE, group))
        gmembers = []
    sd = dict(decl.get("shared_debug") or {})
    sd["endpoints"] = endpoints(sd.get("endpoints", []), STORE + "::apps_mcp_api.shared_debug.endpoints", problems)
    if not ({"GET", "POST"} <= {e["method"] for e in sd["endpoints"]}):
        problems.append("shared_debug must offer a GET and a POST")
    apps = {}
    for rel in build_jsons(root):
        try:
            bj = json.load(open(os.path.join(root, rel)))
        except (OSError, ValueError) as e:
            problems.append("%s: unreadable (%s)" % (rel, e))
            continue
        if "api_endpoints" not in bj:
            continue
        pkg = (bj.get("android") or {}).get("application_id", "")
        fid = id_by_pkg.get(pkg)
        if not fid:
            problems.append("%s: api_endpoints on an app whose package %r is not a fleet row" % (rel, pkg))
            continue
        if fid not in gmembers:
            problems.append("%s: %s is not in the %s group, so the page would not list it" % (rel, fid, group))
            continue
        ae = bj["api_endpoints"]
        extra = set(ae) - {"mcp", "debug", "app"} if isinstance(ae, dict) else {"(not an object)"}
        if extra:
            problems.append("%s: api_endpoints has unknown kinds %s" % (rel, sorted(extra)))
            continue
        apps[fid] = {k: endpoints(ae[k], "%s::api_endpoints.%s" % (rel, k), problems) for k in ("mcp", "debug", "app") if k in ae}
    doc = {
        "_doc": "GENERATED by 1_cicd/src/scripts/cloud-android-apps-mcp-api.py from each app's build.json::api_endpoints and "
                "ac_cloud-store/build.json::apps_mcp_api (do not edit; run data/regen.sh or the script's write). Read by "
                "libs:appstore AppsMcpApi (Store > Access > Apps MCP-API). A kind an app does not list is MISSING; an app with "
                "no debug list inherits shared_debug.",
        "apps_group": group,
        "kinds": kinds,
        "shared_debug": sd,
        "apps": dict(sorted(apps.items())),
    }
    return doc, problems


def out_path(root):
    d = os.environ.get("FLEET_PRIVATE_DIR")
    return os.path.join(d, os.path.basename(OUT)) if d else os.path.join(root, OUT)


def dump(doc):
    """The served form: schemaVersion first (the fleet data client refuses a dataset without it)."""
    return json.dumps(dict([("schemaVersion", 1)] + list(doc.items())), indent=2, ensure_ascii=False) + "\n"


def main(argv):
    if len(argv) != 3 or argv[1] not in ("gen", "write", "check"):
        print(__doc__)
        return 2
    root = argv[2]
    doc, problems = consolidate(root)
    if argv[1] == "gen":
        sys.stdout.write(dump(doc))
        return 1 if problems else 0
    if argv[1] == "write":
        for p in problems:
            print("ERROR: " + p, file=sys.stderr)
        if problems:
            return 1
        path = out_path(root)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w") as h:
            h.write(dump(doc))
        print("wrote %s (%d apps declare endpoints); publish it to cloud-me-pvt-configs fleet-data/2/fleet/apps-mcp-api.json" % (path, len(doc["apps"])))
        return 0
    path = out_path(root)
    committed = open(path).read() if os.path.exists(path) else ""
    if committed != dump(doc):
        problems.append("%s is stale: run python3 1_cicd/src/scripts/cloud-android-apps-mcp-api.py write . "
                        "(or aa_cloud-superapp/data/regen.sh)" % OUT)
    for p in problems:
        print("FAIL  " + p)
    print("── %d app(s) declare endpoints; %d problem(s) ──" % (len(doc["apps"]), len(problems)))
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
