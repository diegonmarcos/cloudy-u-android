#!/usr/bin/env bash
# test-superintel-plugins - Context > Plugins of Cloud SuperIntel is two groups, Skills plugins and Performance plugins (#1939), statically.
#   L1  the plugins page selects both class groups (and still the Enabled plugins group), the class groups first
#   L2  the partition: every plugin of the SOT's enabled list is in EXACTLY ONE of the two class groups, and no class group holds a plugin that is not a SOT plugin
#   L3  a mixed plugin sits under its primary class with a badge for the OTHER class only; a single-payload plugin carries no badge
#   L4  the cold-start seed of the plugins data is byte-equal to the served dataset
#   L5  the class is declared at the source: when the SOT is at hand (CLAUDE_SOT_DIR) plugin-classes.json declares every marketplace plugin once, with a valid class
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir>
python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
fl = os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudsuperintel")
seed = os.path.join(app, "app/src/main/assets/si/seed")
SK, PF, EN, RG = "Skills plugins", "Performance plugins", "Enabled plugins (SOT)", "Registry plugins (SOT)"
pj = json.loads(read(os.path.join(fl, "pages.json")))["pages"]
plug = json.loads(read(os.path.join(fl, "plugins.json")))["groups"]
by = {g["title"]: [r for r in g["rows"]] for g in plug}

# L1
src = [s for s in pj["plugins"]["sources"] if s["asset"] == "ai/plugins.json"]
sel = src[0]["groups"] if src else []
if SK not in sel or PF not in sel: bad.append("L1 the plugins page does not select both class groups")
elif sel.index(SK) != 0 or sel.index(PF) != 1: bad.append("L1 the class groups must come first on the plugins page")
if not any(re.fullmatch(p, EN) for p in sel): bad.append("L1 the plugins page lost the Enabled plugins group")
if not any(re.fullmatch(p, RG) for p in sel): bad.append("L1 the plugins page lost the Registry plugins group")
for n in ("headroom", "rtk", "caveman"):
    if n not in [r["title"] for r in by.get(RG, [])]: bad.append("L2 registry plugin %s is missing from the Registry group" % n)
for t in (SK, PF, EN, RG):
    if t not in by: bad.append("L1 plugins.json has no group %r" % t)

# L2
universe = [r["title"] for r in by.get(EN, []) + by.get(RG, [])]   # the marketplace plugins and the status-bar registry plugins (headroom, rtk, caveman ...)
if not universe: bad.append("L2 the enabled-plugins list is empty")
for n in sorted(set(universe)):
    homes = [t for t in (SK, PF) if any(r["title"] == n for r in by.get(t, []))]
    if len(homes) != 1: bad.append("L2 plugin %s is in %d class groups (must be exactly one): %s" % (n, len(homes), homes))
for t in (SK, PF):
    titles = [r["title"] for r in by.get(t, [])]
    if len(titles) != len(set(titles)): bad.append("L2 group %s lists a plugin twice" % t)
    for n in titles:
        if n not in universe: bad.append("L2 %s lists %s, which is not an enabled SOT plugin" % (t, n))

# L3
for t, other in ((SK, "Performance"), (PF, "Skills")):
    for r in by.get(t, []):
        m = re.findall(r" · also (\w+) \(", r["caption"])
        if m not in ([], [other]): bad.append("L3 %s row %s has a badge %s (only the other class, %s, may be badged)" % (t, r["title"], m, other))
        pay = re.search(r"payload: ([^·]*)", r["caption"])
        if not pay: bad.append("L3 %s row %s states no payload" % (t, r["title"])); continue
        kinds = [k.split()[0] for k in pay.group(1).split(",") if k.strip() and k.strip() != "none yet"]
        sk = {"skills", "commands", "agents"}
        has_other = any((k not in sk) if t == SK else (k in sk) for k in kinds)
        if has_other != bool(m): bad.append("L3 %s row %s: the badge must show exactly when the payload carries the other class" % (t, r["title"]))

# L4
if read(os.path.join(seed, "plugins.json")) != read(os.path.join(fl, "plugins.json")): bad.append("L4 the cold-start seed plugins.json differs from the served dataset")
if read(os.path.join(seed, "pages.json")) != read(os.path.join(fl, "pages.json")): bad.append("L4 the cold-start seed pages.json differs from the served dataset")

# L5
sot = os.environ.get("CLAUDE_SOT_DIR", "")
if sot and os.path.isfile(os.path.join(sot, "plugin-classes.json")):
    pc = json.loads(read(os.path.join(sot, "plugin-classes.json")))
    rp = os.path.join(sot, "claude-plugins.json")
    if os.path.exists(rp):
        for e in json.loads(read(rp))["plugins"]:
            if e["id"] not in pc["plugins"]: bad.append("L5 registry plugin %s has no declared class" % e["id"])
    for n, v in pc["plugins"].items():
        if v.get("primary") not in pc["classes"]: bad.append("L5 SOT plugin %s has no valid class" % n)
    mp = os.path.join(sot, "cloud-marketplace")
    for n in sorted(os.listdir(mp)):
        if os.path.exists(os.path.join(mp, n, ".claude-plugin/plugin.json")) and n not in pc["plugins"]: bad.append("L5 SOT plugin %s has no declared class" % n)
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[superintel-plugins] Skills plugins and Performance plugins partition the plugins"
out="$(check "$APP")" && ok "L1-L5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FL=../data/fleet/app/com.diegonmarcos.cloudsuperintel
mutant() {   # mutant <name> <edit run in the copy> <needle expected in the output>
  rm -rf "$W/ac_cloud-superintel" "$W/data"; cp -r "$APP" "$W/ac_cloud-superintel"
  mkdir -p "$W/data/fleet/app"; cp -r "$APP/../data/fleet/app/com.diegonmarcos.cloudsuperintel" "$W/data/fleet/app/"
  ( cd "$W/ac_cloud-superintel" && eval "$2" )
  if out="$(check "$W/ac_cloud-superintel" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
PYP="import json;p='$FL/plugins.json';d=json.load(open(p));g={x['title']:x for x in d['groups']};"
mutant "a plugin is in no group"      "python3 -c \"${PYP}g['Skills plugins']['rows']=[r for r in g['Skills plugins']['rows'] if r['title']!='ponytail'];json.dump(d,open(p,'w'))\"" "L2 plugin ponytail is in 0 class groups"
mutant "a plugin is in both groups"   "python3 -c \"${PYP}g['Performance plugins']['rows'].append(dict(g['Skills plugins']['rows'][0]));json.dump(d,open(p,'w'))\"" "L2 plugin c3-infra is in 2 class groups"
mutant "an unknown plugin is listed"  "python3 -c \"${PYP}g['Performance plugins']['rows'].append({'title':'ghost','caption':'x · payload: hooks 1','end':''});json.dump(d,open(p,'w'))\"" "L2 Performance plugins lists ghost"
mutant "the group is missing"         "python3 -c \"${PYP}d['groups']=[x for x in d['groups'] if x['title']!='Performance plugins'];json.dump(d,open(p,'w'))\"" "L1 plugins.json has no group 'Performance plugins'"
mutant "the page drops a class group" "sed -i 's/\"Performance plugins\",//' $FL/pages.json" "L1 the plugins page does not select both class groups"
mutant "the Enabled group is dropped" "python3 -c \"import json;p='$FL/pages.json';d=json.load(open(p));d['pages']['plugins']['sources'][0]['groups']=['Skills plugins','Performance plugins'];json.dump(d,open(p,'w'))\"" "L1 the plugins page lost the Enabled plugins group"
mutant "a badge points at its own class" "python3 -c \"${PYP}r=g['Skills plugins']['rows'][0];r['caption']=r['caption'].replace('also Performance','also Skills');json.dump(d,open(p,'w'))\"" "L3 Skills plugins row c3-infra has a badge"
mutant "a mixed plugin loses its badge" "python3 -c \"${PYP}r=g['Skills plugins']['rows'][0];r['caption']=r['caption'].replace(' · also Performance (hooks)','');json.dump(d,open(p,'w'))\"" "L3 Skills plugins row c3-infra: the badge must show"
mutant "a registry plugin is in no group" "python3 -c \"${PYP}g['Performance plugins']['rows']=[r for r in g['Performance plugins']['rows'] if r['title']!='headroom'];json.dump(d,open(p,'w'))\"" "L2 plugin headroom is in 0 class groups"
mutant "a registry plugin leaves the registry group" "python3 -c \"${PYP}g['Registry plugins (SOT)']['rows']=[r for r in g['Registry plugins (SOT)']['rows'] if r['title']!='rtk'];json.dump(d,open(p,'w'))\"" "L2 registry plugin rtk is missing"
mutant "a registry plugin is in both groups" "python3 -c \"${PYP}g['Skills plugins']['rows'].append({'title':'caveman','caption':'x · payload: tooling','end':''});json.dump(d,open(p,'w'))\"" "L2 plugin caveman is in 2 class groups"
mutant "the page drops the registry group" "sed -i 's/\"Registry plugins [\\\\]*(SOT[\\\\]*)\",//' $FL/pages.json" "L1 the plugins page lost the Registry plugins group"
mutant "the seed drifts"              "echo ' ' >> app/src/main/assets/si/seed/plugins.json" "L4 the cold-start seed plugins.json differs"
SOTR="$W/sotr"; mkdir -p "$SOTR/cloud-marketplace"; echo '{"plugins":[{"id":"zap"}]}' > "$SOTR/claude-plugins.json"
echo '{"classes":{"skills":"Skills plugins","performance":"Performance plugins"},"plugins":{}}' > "$SOTR/plugin-classes.json"
if out="$(CLAUDE_SOT_DIR="$SOTR" check "$APP" 2>&1)"; then bad "mutant 'a registry plugin is unclassified' stayed green"; elif grep -q -- "L5 registry plugin zap has no declared class" <<<"$out"; then ok "mutant 'a registry plugin is unclassified' is red (L5)"; else bad "wrong reason: $out"; fi
SOTW="$W/sot"; mkdir -p "$SOTW/cloud-marketplace/p1/.claude-plugin"; echo '{}' > "$SOTW/cloud-marketplace/p1/.claude-plugin/plugin.json"
echo '{"classes":{"skills":"Skills plugins","performance":"Performance plugins"},"plugins":{}}' > "$SOTW/plugin-classes.json"
if out="$(CLAUDE_SOT_DIR="$SOTW" check "$APP" 2>&1)"; then bad "mutant 'the SOT leaves a plugin unclassified' stayed green"; elif grep -q -- "L5 SOT plugin p1 has no declared class" <<<"$out"; then ok "mutant 'the SOT leaves a plugin unclassified' is red (L5)"; else bad "wrong reason: $out"; fi
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
