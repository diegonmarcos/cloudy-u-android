#!/usr/bin/env python3
"""#1499 Derives the AIs section's data (assets/ai/mcp.json, dotfiles.json, plugins.json) from the cloudy-u-linux SOT
(da_my-ai/data/claude): mcp-policy.json, settings.project.json, settings.base.json, agents/, cloud-marketplace/c3-infra.
Names and counts only: no env values, no URLs, no tokens. Usage: derive-ai-data.py <path to da_my-ai/data/claude> [--check]
--check exits 1 when the committed assets differ from the derivation."""
import glob, json, os, re, sys

sot = sys.argv[1]
check = "--check" in sys.argv
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../app/src/main/assets/ai")
digest_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../app/src/test/resources/mcp-sot.json")
J = lambda p: json.load(open(os.path.join(sot, p)))
row = lambda title, caption="", end="": {"title": title, "caption": caption, "end": end}
grp = lambda title, rows: {"title": title, "rows": rows}
DOC = "Derived by tools/derive-ai-data.py from the cloudy-u-linux SOT da_my-ai/data/claude (names and counts only, no secrets). Never edit by hand."

# ── MCP ──
pol = J("mcp-policy.json")
sizes = J("mcp-sizes.json")
ren = pol["renames"]
tiers = pol["tiers"]
alias = ren["aliases"]
tools = lambda n: "%d tool%s" % (sizes["servers"][n]["tools"], "" if sizes["servers"][n]["tools"] == 1 else "s") if n in sizes["servers"] else ""
# The c3 tool groups: the tree dispatchers the SOT's merge note names (c3_infra ... c3_help) and the merged peers group; browser.* is the peers-control
# browser group #1450 announces (declared here because the SOT's note predates it: drop BROWSER_GROUP when the note names it).
BROWSER_GROUP = [("browser.*", "peers-control browser tools (#1450): in cloudy-c3-mcp, held until the c3 hierarchy has their nodes")]
c3_groups = []
for g in re.findall(r"\b(?:c3_[a-z]+|infra_[a-z]+)\b", sizes.get("_doc_c3_merge", "")):
    if g not in c3_groups: c3_groups.append(g)
mcp = [
    grp("Default (loaded in every session)", [row(n, "default tier, grouped dispatcher", tools(n)) for n in tiers["default"]]),
    grp("Opt-in (current names)", [row(n, "opt-in tier", tools(n)) for n in tiers["opt-in"] if n not in alias]),
    grp("New names (#1450)", [row(n, "was " + v["from"], "new") for n, v in ren["map"].items() if n not in alias]),
    grp("cloudy-c3-mcp tool groups (#1495 peers-control merge)", [row(g, "tool group of cloudy-c3-mcp") for g in c3_groups] + [row(n, c) for n, c in BROWSER_GROUP]),
    grp("Aliases of old names (kept one release)", [row(o, "old name, alias", "-> " + n) for o, n in alias.items()]),
    grp("Mesh-only", [row(n, "direct on the mesh, no public route") for n in pol["direct_http"] if n not in alias]),
]

# ── Dotfiles ──
agents = {}
for f in sorted(glob.glob(os.path.join(sot, "agents/*.md"))):
    parts = open(f).read().split("---")
    fm = dict(re.findall(r"^(\w+):\s*(.*)$", parts[1], re.M)) if len(parts) > 2 else {}
    if "name" in fm: agents[fm["name"]] = fm
def settings(name):
    d = J(name)
    p = d.get("permissions", {})
    rows_hooks = [row(ev + (" [" + h["matcher"] + "]" if h.get("matcher") else ""), os.path.basename(x["command"].split()[-1].strip('"')) if "hooks/c3" in x["command"] else x["command"].split()[-1][:40])
                  for ev, hs in d.get("hooks", {}).items() for h in hs for x in h["hooks"]]
    models = [row("effort", "", str(d["effortLevel"]))] if "effortLevel" in d else []
    models += [row("agent " + n, "model", a.get("model", "") + (" / " + a["effort"] if a.get("effort") else "")) for n, a in agents.items()]
    return [
        grp(name + " · Permissions", [row("default mode", "", p.get("defaultMode", "-"))]
            + [row(r, "allow") for r in p.get("allow", [])] + [row(r, "deny") for r in p.get("deny", [])]),
        grp(name + " · Env names", [row(k, "name only, value never shown") for k in d.get("env", {}) if not k.startswith("_")]),
        grp(name + " · Hooks", rows_hooks or [row("none declared", "")]),
        grp(name + " · Models", models),
        grp(name + " · Plugins", [row(k, "enabled" if v else "off") for k, v in d.get("enabledPlugins", {}).items()]),
    ]
dot = settings("settings.project.json") + settings("settings.base.json")

# ── Plugins ──
c3 = os.path.join(sot, "cloud-marketplace/c3-infra")
pj = json.load(open(os.path.join(c3, ".claude-plugin/plugin.json")))
skill = open(os.path.join(c3, "skills/c3-infra/SKILL.md")).read()
desc = re.search(r"^description:\s*(.*)$", skill, re.M).group(1)
rules = []
for f in sorted(glob.glob(os.path.join(c3, "skills/c3-infra/rules/*.md"))):
    first = open(f).readline().lstrip("# ").strip()
    rules.append(row(os.path.basename(f), first))
hooks = [row(os.path.basename(x["command"].strip('"')), ev) for ev, hs in json.load(open(os.path.join(c3, "hooks/hooks.json")))["hooks"].items() for h in hs for x in h["hooks"]]
hooks += [row(os.path.basename(f), "posts or beacons AI usage") for f in sorted(glob.glob(os.path.join(c3, "hooks/ai-usage-*.sh")))]
enabled = {}
for n in ("settings.base.json", "settings.project.json"):
    for k, v in J(n).get("enabledPlugins", {}).items(): enabled[k] = enabled.get(k, False) or v
plug = [
    grp("Enabled plugins (SOT)", [row(k.split("@")[0], "@" + k.split("@")[1], "on" if v else "off") for k, v in enabled.items()]),
    grp("c3-infra plugin", [row(pj.get("name", "c3-infra"), pj.get("description", ""), pj.get("version", ""))]),
    grp("Skills", [row("c3-infra", desc)]),
    grp("Rules", rules),
    grp("Agents", [row(n, a.get("description", ""), a.get("model", "")) for n, a in agents.items()]),
    grp("Hooks", hooks),
]

# The names of the SOT's MCP policy as the JVM test reads them (the CI has no cloudy-u-linux): tiers, the #1450 map, the aliases. Names only.
digest = {"_doc": "Names of the cloudy-u-linux SOT mcp-policy.json the MCP page was derived from, for AiMcpSotTest. " + DOC,
          "default": tiers["default"], "opt-in": tiers["opt-in"], "new": {n: v["from"] for n, v in ren["map"].items()}, "aliases": alias}

bad = 0
dtext = json.dumps(digest, indent=1, ensure_ascii=False) + "\n"
if check:
    if not os.path.exists(digest_path) or open(digest_path).read() != dtext: print("DIFF mcp-sot"); bad = 1
else:
    os.makedirs(os.path.dirname(digest_path), exist_ok=True)
    open(digest_path, "w").write(dtext)
for name, groups in (("mcp", mcp), ("dotfiles", dot), ("plugins", plug)):
    text = json.dumps({"_doc": DOC, "groups": groups}, indent=1, ensure_ascii=False) + "\n"
    path = os.path.join(out, name + ".json")
    if check:
        if not os.path.exists(path) or open(path).read() != text: print("DIFF " + name); bad = 1
    else:
        open(path, "w").write(text)
sys.exit(bad)
