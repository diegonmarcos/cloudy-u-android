#!/usr/bin/env bash
# test-superintel-parity - rule 16 for the #1854 regroup of Cloud SuperIntel: a regroup, not a removal. Every page, field, row and chip
# the app had before is still reachable, now in its new place; statically, from the sources and the data alone.
#   P1  every old page id maps to a new place that build.json::ui declares (Tools -> Models, AIs -> Context, Cost -> the top-right window,
#       tools_mcp and mcp -> MCP-Tools, apis -> Models > API, dotfiles -> split over Plugins / Hooks / Skills)
#   P2  every group of the derived assets (mcp, dotfiles, plugins) is shown on exactly one Context page, and the MCP-Tools page is the whole MCP list;
#       replaying the page selectors on the data shows every row
#   P3  the Cost window carries the whole former Cost page: the `cost` page of pages.json is the cost kind over cost.json, every column,
#       row and token column of cost.json is still declared, AiScreens still draws the weekly block, the Daily and Monthly tables and the
#       Claude / OpenRouter columns, and the shell wires the window to the top-right icon and to the SuperApp badge's deep link
#   P4  every composer chip of before (plus ... more) is still a control of the composer, with its id; the renames are only labels
#   P5  Models > Models keeps the Core list (every tab of the catalogue) and adds the OpenRouter table with every field of the ticket
#   P6  MCP-KG is the composer's KG homes, read from libs:ai's design (the kg page kind; no baked copy that could drift)
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir> -> prints FAIL lines, exit 1 on any
python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
ai = os.path.join(app, "app/src/main/assets/ai")
fx = os.path.join(app, "test/fixtures")   # #1566 fleet-private served datasets (apis, cost): synthetic sample fixtures
fl = os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudsuperintel")   # #1562 pages, mcp and plugins are served datasets
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")
lib = os.path.join(app, "..", "ab_cloud-libs-shared", "libs", "ai", "src", "main")
sections = {s["id"]: s for s in bj["ui"]["sections"]}
def pages(sec): return [p["id"] for p in sections.get(sec, {"pages": []})["pages"]]
def labels(sec): return [p["label"] for p in sections.get(sec, {"pages": []})["pages"]]
pj = json.loads(read(os.path.join(fl, "pages.json")))["pages"]

# P1 old page -> new place (the section and page id that now hold it, and the label it carries)
OLD_TO_NEW = {
    "tools_models":  ("models", "tools_models", "Models"),
    "tools_pickers": ("models", "tools_pickers", "Agents"),
    "tools_tasks":   ("models", "tools_tasks", "Tasks"),
    "tools_mcp":     ("context", "mcp", "MCP-Tools"),
    "apis":          ("models", "apis", "API"),
    "mcp":           ("context", "mcp", "MCP-Tools"),
    "plugins":       ("context", "plugins", "Plugins"),
}
for old, (sec, page, label) in OLD_TO_NEW.items():
    if page not in pages(sec): bad.append("P1 old page %s has no home: %s/%s is not declared" % (old, sec, page))
    elif labels(sec)[pages(sec).index(page)] != label: bad.append("P1 old page %s: %s/%s must be labelled %s" % (old, sec, page, label))
if sections.get("models", {}).get("label") != "Models" or sections.get("context", {}).get("label") != "Context": bad.append("P1 the sections must be labelled Models and Context")
if "tools_mcp" in pages("models"): bad.append("P1 the MCP page must have left Models (it is Context > MCP-Tools)")
if "cost" in sections or "cost" in bj["ui"]["bottom_nav"]: bad.append("P1 Cost must leave the bottom nav (it is the top-right window)")
for gone in ("tools", "ais"):
    if gone in sections or gone in bj["ui"]["bottom_nav"]: bad.append("P1 section %s was renamed, it must not stay" % gone)
# the old dotfiles page is split: each of its five parts of the SOT has a Context page
dot_pages = [k for k, v in pj.items() if v["kind"] == "rows" and any(s["asset"] == "ai/dotfiles.json" for s in v.get("sources", []))]
if sorted(dot_pages) != ["hooks", "plugins", "skills"]: bad.append("P1 the former Dotfiles groups must be split over Plugins, Hooks and Skills, got %s" % sorted(dot_pages))

# P2 every group of the derived assets is on exactly one page
def groups(asset): return [g for g in json.loads(read(os.path.join(fl if asset in ("mcp.json", "plugins.json") else ai, asset)))["groups"]]
shown = {}
for key, v in pj.items():
    if v["kind"] != "rows": continue
    for s in v["sources"]:
        a = s["asset"].split("/")[-1]
        for g in groups(a):
            if not s["groups"] or any(re.fullmatch(p, g["title"]) for p in s["groups"]):
                shown.setdefault((a, g["title"]), []).append(key)
for a in ("mcp.json", "dotfiles.json", "plugins.json"):
    for g in groups(a):
        homes = shown.get((a, g["title"]), [])
        if len(homes) != 1: bad.append("P2 group %r of %s is on %d Context pages (must be exactly one): %s" % (g["title"], a, len(homes), homes))
mcp_tools = [(s["asset"], s["groups"]) for s in pj["mcp"]["sources"]]
if mcp_tools != [("ai/mcp.json", [".*"])]: bad.append("P2 MCP-Tools must be the whole former MCP list: every group of mcp.json")
rows_total = sum(len(g["rows"]) for a in ("mcp.json", "dotfiles.json", "plugins.json") for g in groups(a))
rows_shown = sum(len(g["rows"]) for (a, t), homes in shown.items() if a in ("mcp.json", "dotfiles.json", "plugins.json") for g in groups(a) if g["title"] == t)
if rows_total != rows_shown: bad.append("P2 %d rows of the derived assets, %d shown" % (rows_total, rows_shown))
aidata = code(os.path.join(kt, "AiData.kt")); screens = code(os.path.join(kt, "ui/AiScreens.kt"))
if "keep.isEmpty() || keep.any { it.matches(" not in aidata: bad.append("P2 AiData no longer selects groups by full match")
if "spec.sources.flatMap" not in screens: bad.append("P2 the rows page does not draw its declared sources")

# P3 the Cost window carries the whole former Cost page
if pj.get("cost", {}).get("kind") != "cost" or pj["cost"].get("asset") != "ai/cost.json": bad.append("P3 pages.json lost the cost page (the Cost window draws it)")
cost = json.loads(read(os.path.join(fx, "cost.json")))
if cost["columns"] != ["Claude", "OpenRouter"] and [str(c).lower() for c in cost["columns"]][:2] != ["claude", "openrouter"]: bad.append("P3 cost.json lost the Claude / OpenRouter columns: %s" % cost["columns"])
if not cost.get("token_columns") or len(cost["token_columns"]) < 6: bad.append("P3 cost.json lost the Daily / Monthly token columns")
metrics = {r["claude"] for r in cost["rows"]} | {r["openrouter"] for r in cost["rows"]}
for m in ("seven_day", "week_left", "week_est", "day_tokens", "month_tokens", "last_24h", "last_7d", "month", "balance", "est_30d"):
    if m not in metrics: bad.append("P3 cost.json lost the %s row" % m)
for need in ("TokenTable(", "AiTags.COST_WEEK", "AiTags.COST_DAILY", "AiTags.COST_MONTHLY", "row.claude", "row.openrouter", "R.string.cost_refresh", "cost_week", "cfg.columns"):
    if need not in screens: bad.append("P3 AiScreens no longer draws %s of the Cost page" % need)
shell = code(os.path.join(kt, "ui/SiShell.kt"))
wins = code(os.path.join(kt, "ui/SiWindows.kt"))
if "siWindows(aiPages[PAGE_COST])" not in shell or 'PAGE_COST = "cost"' not in shell: bad.append("P3 SiShell does not build the Cost window from the cost page")
if "windows = windows" not in shell: bad.append("P3 the top-right icons are not wired to the windows")
if "usage = { _, close -> SiWindow(" not in wins or "AiPage(cost)" not in wins: bad.append("P3 the Cost window does not draw the whole Cost page (AiPage)")
for tag in ("COST", "CONTEXT", "FILES"):
    if "WindowTags.%s" % tag not in wins: bad.append("P3 the %s window is missing" % tag)
if "asked.section == PAGE_COST" not in shell or "costFromLink" not in shell: bad.append("P3 the SuperApp badge's deep link no longer opens Cost")
forum = read(os.path.join(lib, "kotlin/com/diegonmarcos/superapp/ai/AiForumChat.kt"))
for need in ('window = "usage"', 'window = "context"', 'window = "files"', "windows.usage(chat)", "windows.context(chat)", "windows.files(chat)"):
    if need not in forum: bad.append("P3 the shared top bar does not open the host's window: %s" % need)

# P4 every composer chip of before is still a control, ids unchanged
OLD_CHIPS = ["plus", "task", "specialist", "agent", "model", "effort", "mode", "skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more"]
comp = json.loads(read(os.path.join(app, "..", "data/fleet/app/com.diegonmarcos.cloudsuperintel/composer.json")))["controls"]
design = json.loads(read(os.path.join(lib, "assets/ai/ai-design.json")))
lay = {c["id"]: c for c in design["composer"]["layout"]["controls"]}
for c in OLD_CHIPS:
    if c not in comp: bad.append("P4 chip %s left composer.json" % c)
    if c not in lay: bad.append("P4 chip %s left the composer layout" % c)
for c in ("plugins", "weby_apps", "hooks"):
    if c not in comp or c not in lay: bad.append("P4 the new chip %s is missing" % c)
if lay.get("kg_apps", {}).get("label") != "Cloudy Apps": bad.append("P4 KG-Cloud-apps must be renamed Cloudy Apps (id kg_apps kept)")
if design["mcp"]["buckets"][1]["label"] != "KG-Cloud-apps": bad.append("P4 the apps bucket keeps its id and bucket label")
bar = read(os.path.join(lib, "kotlin/com/diegonmarcos/superapp/ai/AiComposerBar.kt"))
for ctl in ("plugins", "weby_apps", "hooks"):
    if not re.search(r'"%s" -> (ItemMenu|WebyAppsControl|HooksControl)' % ctl, bar): bad.append("P4 AiComposerBar draws no %s control" % ctl)

# P5 the Core list stays; the table carries every ticket field
tabs = json.loads(read(os.path.join(lib, "assets/ai/model-tabs.json")))
core = [b for b in tabs["layout"] if b.get("header") == "Core"]
if not core or [t for r in core[0]["rows"] for t in r] != ["frontier", "text", "search", "audio", "image", "video", "specialist", "tasks", "medium", "small"]: bad.append("P5 the Core list (the catalogue's tabs) changed")
orm = code(os.path.join(kt, "OpenRouterModels.kt"))
cols = re.search(r"val COLUMNS = listOf\((.*?)\)", orm, re.S)
want = ["id", "name", "context_length", "prompt", "completion", "request", "image", "modality", "tokenizer", "max_completion_tokens", "is_moderated", "supported_parameters", "created", "description"]
if not cols or re.findall(r'"([^"]+)"', cols.group(1)) != want: bad.append("P5 the OpenRouter table lost a field of the ticket")
for need in ("OpenRouterStore(", "MAX_AGE_MS", "stale()", "refresh()"):
    if need not in orm: bad.append("P5 OpenRouterModels lacks %s (cache and daily refresh)" % need)
if "24L * 60 * 60 * 1000" not in orm: bad.append("P5 the refresh is not once a day")
mp = code(os.path.join(kt, "ui/ModelsPage.kt"))
for need in ("AiModelCatalogue(", "OrCoreTable(", "store.stale()", "ModelsTags.REFRESH", "R.string.or_stamp"):
    if need not in mp: bad.append("P5 ModelsPage lacks %s" % need)
lp = read(os.path.join(lib, "kotlin/com/diegonmarcos/superapp/ai/AiCataloguePages.kt"))
if "tabExtra?.invoke(tab" not in lp: bad.append("P5 the catalogue does not draw the host's per-Core block")

# P6 MCP-KG = the composer's KG homes, read from the shared design (no baked copy), so the page and the chips cannot drift
if pj.get("mcp_kg") != {"kind": "kg"}: bad.append("P6 MCP-KG must be the kg page (the composer's KG homes)")
if os.path.exists(os.path.join(ai, "mcp-kg.json")) or os.path.exists(os.path.join(fl, "mcp-kg.json")): bad.append("P6 mcp-kg.json is a baked copy of the design: the page reads libs:ai's design")
for need in ("fun kgGroups(buckets: List<AiMcpBucket>, tabs: List<AiKgTab>)", "b.known.map", "it.kind != \"mcp\""):
    if need not in aidata: bad.append("P6 AiData.kgGroups lost %s" % need)
if "AiData.kgGroups(it.mcpBuckets, it.kgStoreTabs)" not in screens: bad.append("P6 the kg page does not read the composer's buckets and KG-Store tabs")
if not design["mcp"]["buckets"] or not all(b["known"] for b in design["mcp"]["buckets"]): bad.append("P6 the design's KG buckets must each list their known servers")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[superintel-parity] the #1854 regroup loses nothing"
out="$(check "$APP")" && ok "P1-P6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy next to the shared libs: each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/ac_cloud-superintel" "$W/ab_cloud-libs-shared" "$W/data"; cp -r "$APP" "$W/ac_cloud-superintel"
  mkdir -p "$W/ab_cloud-libs-shared/libs"; cp -r "$APP/../ab_cloud-libs-shared/libs/ai" "$W/ab_cloud-libs-shared/libs/ai"
  mkdir -p "$W/data/fleet/app"; cp -r "$APP/../data/fleet/app/com.diegonmarcos.cloudsuperintel" "$W/data/fleet/app/"
  ( cd "$W/ac_cloud-superintel" && eval "$2" )
  if out="$(check "$W/ac_cloud-superintel" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudsuperintel
AI=app/src/main/assets/ai
FX=test/fixtures
FL=../data/fleet/app/com.diegonmarcos.cloudsuperintel
LIB=../ab_cloud-libs-shared/libs/ai/src/main
PYJ="import json;p='build.json';d=json.load(open(p));"
PYP="import json;p='$FL/pages.json';d=json.load(open(p));"
mutant "Cost returns to the bar"     "python3 -c \"${PYJ}d['ui']['bottom_nav'].append('cost');d['ui']['sections'].append({'id':'cost','label':'Cost','icon':'payments','pages':[]});json.dump(d,open(p,'w'))\"" "P1 Cost must leave the bottom nav"
mutant "MCP stays in Models"         "python3 -c \"${PYJ}[s['pages'].append({'id':'tools_mcp','label':'MCP','icon':'hub','pages':[]}) for s in d['ui']['sections'] if s['id']=='models'];json.dump(d,open(p,'w'))\"" "P1 the MCP page must have left Models"
mutant "API page is lost"            "python3 -c \"${PYJ}[s['pages'].pop() for s in d['ui']['sections'] if s['id']=='models'];json.dump(d,open(p,'w'))\"" "P1 old page apis has no home"
mutant "a Context page is renamed"   "sed -i 's/\"MCP-Tools\"/\"MCP\"/' build.json" "P1 old page mcp: context/mcp must be labelled MCP-Tools"
mutant "a dotfiles group is orphaned" "python3 -c \"${PYP}d['pages']['hooks']['sources'][1]['groups'].remove('.* · Permissions');json.dump(d,open(p,'w'))\"" "P2 group 'settings.project.json · Permissions' of dotfiles.json is on 0 Context pages"
mutant "a group is shown twice"      "python3 -c \"${PYP}d['pages']['skills']['sources'][0]['groups'].append('Hooks');json.dump(d,open(p,'w'))\"" "P2 group 'Hooks' of plugins.json is on 2 Context pages"
mutant "MCP-Tools drops a group"     "python3 -c \"${PYP}d['pages']['mcp']['sources'][0]['groups']=['Default.*'];json.dump(d,open(p,'w'))\"" "P2 MCP-Tools must be the whole"
mutant "the page selection is lost"  "sed -i 's/keep.isEmpty() || keep.any { it.matches(/keep.isEmpty() || keep.all { it.matches(/' $KT/AiData.kt" "P2 AiData no longer selects"
mutant "Cost page is lost"           "python3 -c \"${PYP}del d['pages']['cost'];json.dump(d,open(p,'w'))\"" "P3 pages.json lost the cost page"
mutant "a Cost row is lost"          "python3 -c \"${PYP}p='$FX/cost.json';d=json.load(open(p));d['rows']=[r for r in d['rows'] if r['openrouter']!='est_30d'];json.dump(d,open(p,'w'))\"" "P3 cost.json lost the est_30d row"
mutant "the Daily table is lost"     "sed -i 's/AiTags.COST_DAILY/AiTags.COST_DAILX/g' $KT/ui/AiScreens.kt" "P3 AiScreens no longer draws AiTags.COST_DAILY"
mutant "the Cost window is hollow"   "sed -i 's/AiPage(cost)/Unit/' $KT/ui/SiWindows.kt" "P3 the Cost window does not draw the whole Cost page"
mutant "the badge link is dropped"   "sed -i 's/asked.section == PAGE_COST/asked.section == \"x\"/' $KT/ui/SiShell.kt" "P3 the SuperApp badge's deep link"
mutant "the top bar ignores windows" "sed -i 's/windows.context(chat)/Unit/' $LIB/kotlin/com/diegonmarcos/superapp/ai/AiForumChat.kt" "P3 the shared top bar does not open the host's window"
mutant "a chip is dropped"           "sed -i 's/\"perf\",//' ../data/fleet/app/com.diegonmarcos.cloudsuperintel/composer.json" "P4 chip perf left composer.json"
mutant "a chip is dropped from layout" "python3 -c \"import json;p='$LIB/assets/ai/ai-design.json';d=json.load(open(p));d['composer']['layout']['controls']=[c for c in d['composer']['layout']['controls'] if c['id']!='kg_infra'];json.dump(d,open(p,'w'))\"" "P4 chip kg_infra left the composer layout"
mutant "Cloudy Apps is not renamed"  "sed -i 's/\"label\": \"Cloudy Apps\"/\"label\": \"KG-Cloud-apps\"/' $LIB/assets/ai/ai-design.json" "P4 KG-Cloud-apps must be renamed"
mutant "the Hooks chip is undrawn"   "sed -i 's/\"hooks\" -> HooksControl/\"hooksx\" -> HooksControl/' $LIB/kotlin/com/diegonmarcos/superapp/ai/AiComposerBar.kt" "P4 AiComposerBar draws no hooks control"
mutant "a Core is dropped"           "python3 -c \"import json;p='$LIB/assets/ai/model-tabs.json';d=json.load(open(p));d['layout'][0]['rows'][2].remove('small');json.dump(d,open(p,'w'))\"" "P5 the Core list"
mutant "a table field is dropped"    "sed -i 's/\"tokenizer\",//' $KT/OpenRouterModels.kt" "P5 the OpenRouter table lost a field"
mutant "the daily refresh is lost"   "sed -i 's/24L \\* 60 \\* 60 \\* 1000/7L * 24 * 60 * 60 * 1000/' $KT/OpenRouterModels.kt" "P5 the refresh is not once a day"
mutant "the Refresh button is lost"  "sed -i 's/ModelsTags.REFRESH/ModelsTags.STAMP/' $KT/ui/ModelsPage.kt" "P5 ModelsPage lacks ModelsTags.REFRESH"
mutant "the per-Core block is lost"  "sed -i 's/tabExtra?.invoke(tab/tabExtra?.let(tab/' $LIB/kotlin/com/diegonmarcos/superapp/ai/AiCataloguePages.kt" "P5 the catalogue does not draw the host's per-Core block"
mutant "MCP-KG is no longer the kg page" "python3 -c \"${PYP}d['pages']['mcp_kg']={'kind':'rows','sources':[{'asset':'ai/mcp.json','groups':['Mesh-only']}]};json.dump(d,open(p,'w'))\"" "P6 MCP-KG must be the kg page"
mutant "MCP-KG forgets the buckets"  "sed -i 's/b.known.map/b.exact.map/' $KT/AiData.kt" "P6 AiData.kgGroups lost b.known.map"
mutant "a baked KG copy appears"     "echo '{}' > $AI/mcp-kg.json" "P6 mcp-kg.json is a baked copy"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
