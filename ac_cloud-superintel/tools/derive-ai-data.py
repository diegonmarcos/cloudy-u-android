#!/usr/bin/env python3
"""#1499 Derives the AIs section's data (assets/ai/mcp.json, dotfiles.json, plugins.json) from the cloudy-u-linux SOT
(da_my-ai/data/claude): mcp-policy.json, settings.project.json, settings.base.json, agents/, cloud-marketplace/c3-infra.
Names and counts only: no env values, no URLs, no tokens. Usage: derive-ai-data.py <path to da_my-ai/data/claude> [--check]
--check exits 1 when the committed assets differ from the derivation."""
import glob, json, os, re, sys

sot = sys.argv[1]
check = "--check" in sys.argv
out = os.path.join(os.path.dirname(os.path.abspath(__file__)), "../app/src/main/assets/ai")
J = lambda p: json.load(open(os.path.join(sot, p)))
row = lambda title, caption="", end="": {"title": title, "caption": caption, "end": end}
grp = lambda title, rows: {"title": title, "rows": rows}
DOC = "Derived by tools/derive-ai-data.py from the cloudy-u-linux SOT da_my-ai/data/claude (names and counts only, no secrets). Never edit by hand."

# ── MCP ──
pol = J("mcp-policy.json")
ren = pol["renames"]
tiers = pol["tiers"]
mcp = [
    grp("Default (loaded in every session)", [row(n, "default tier, grouped dispatcher") for n in tiers["default"]]),
    grp("Opt-in", [row(n, "opt-in tier", ren["aliases"].get(n, "") and "alias of " + ren["aliases"][n]) for n in tiers["opt-in"]]),
    grp("New names (#1450)", [row(n, "was " + v["from"], "new") for n, v in ren["map"].items()]),
    grp("Aliases (kept one release)", [row(o, "old name", "-> " + n) for o, n in ren["aliases"].items()]),
    grp("c3-mcp peers-control merge (#1495)", [row(o, "merged into cloudy-c3-mcp (peers-control tool group)", "alias") for o, n in ren["aliases"].items() if n == "cloudy-c3-mcp" and o != "cloud-infra-mcp"]),
    grp("Mesh-only", [row(n, "direct on the mesh, no public route") for n in pol["direct_http"] if n in ren["map"] or n in tiers["opt-in"]]),
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

bad = 0
for name, groups in (("mcp", mcp), ("dotfiles", dot), ("plugins", plug)):
    text = json.dumps({"_doc": DOC, "groups": groups}, indent=1, ensure_ascii=False) + "\n"
    path = os.path.join(out, name + ".json")
    if check:
        if not os.path.exists(path) or open(path).read() != text: print("DIFF " + name); bad = 1
    else:
        open(path, "w").write(text)
sys.exit(bad)
