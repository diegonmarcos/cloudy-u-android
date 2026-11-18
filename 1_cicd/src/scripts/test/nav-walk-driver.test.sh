#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ nav-walk-driver.test — the runtime nav walk's driver, offline     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# cloud-android-nav-walk.py drives a real emulator; this tester drives it against a FAKE screen (dump/tap/swipe/shell replaced),
# so the three #1760 walk failures are pinned without an emulator, each with the mutation that brings the failure back:
#
#   D1 a dropdown entry that sits more than 10 swipes down is still reached (Cloud C3: Configs sits under the whole drawer tree,
#      in the #1758 Sections group of a page below depth 0; "P6 ... no entry for section configs"). Mutation: DROPDOWN_SWIPES = 10.
#   D2 a title dropdown left open by the step before is closed before the bottom bar is read (Cloud Store: "P4 no
#      bottomnav_item_cloud on screen"). Mutation: the walker reads the bar with the popup still open.
#   D3 an app whose gear opens its OWN full-screen Configs (Cloud Dialer's DialerConfigsActivity, tag dialer_configs_back) is left
#      by its back arrow before the off-bar section is entered from the dropdown ("the title dropdown did not open (to reach
#      section favorites)"). Mutation: only fleetcfg_back is recognised.
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../../../.." && pwd)
WALK="$ROOT/1_cicd/src/scripts/cloud-android-nav-walk.py"

run() {  # $1 = the walker source to test; prints one line per case, exits 1 on any failed case
python3 -I - "$1" <<'PYEOF'
import importlib.util, sys, types
import xml.etree.ElementTree as ET

spec = importlib.util.spec_from_file_location("navwalk", sys.argv[1])
w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w)

def node(rid, t, b=0, text=""):
    return w.Node(ET.Element("node", {"resource-id": rid, "text": text, "bounds": "[0,%d][800,%d]" % (t, b or t + 100)}))

class Screen:
    """A fake device: state -> nodes; taps, swipes and Back move the state."""
    def __init__(self): self.log = []
    def install(self):
        w.dump = lambda *a, **k: self.nodes()
        w.tap = lambda n: self.on_tap(n.rid)
        w.swipe = lambda x, y1, y2, ms=350: self.on_swipe(y1 - y2)
        w.shell = lambda cmd, timeout=90: (self.on_back() if "KEYCODE_BACK" in cmd else "")
        w.settle = lambda s=0.9: None
    def on_tap(self, rid): self.log.append(("tap", rid))
    def on_swipe(self, dy): self.log.append(("swipe", dy))
    def on_back(self): self.log.append(("back",)); return ""

def walker(sections, bottom):
    k = w.Walk.__new__(w.Walk)
    k.sections = {s: {"id": s, "label": s.title()} for s in sections}
    k.bottom = list(bottom); k.w, k.h = 1080, 2340; k.results = []; k.pkg = "x"; k.unopened = set()
    k.evidence = lambda name: None
    k.foreground = lambda: True
    k.hide_ime = lambda: None
    k.run_page = lambda *a, **kw: k.results.append({"section": a[0], "page": a[1], "status": "PASS"})
    return k

fails = 0
def ok(cond, name):
    global fails
    print(("PASS  " if cond else "FAIL  ") + name)
    fails += 0 if cond else 1

# D1: 60 rows (crumbs, pages, a long drawer tree, then the Sections group); 8 rows on screen, a swipe of 500 px moves 4 rows
class LongDropdown(Screen):
    rows = ["p%d" % i for i in range(5)] + ["drawer%d" % i for i in range(48)] + ["home", "ops", "observ", "infra", "containers", "configs", "end"]
    off = 0
    def nodes(self):
        vis = self.rows[self.off:self.off + 8]
        out = [node(w.DROPDOWN, 100, 1900), node(w.CRUMB0, 100, 180)]
        for i, r in enumerate(vis):
            out.append(node((w.ENTRY + r) if not r.startswith("drawer") else "c3_drawer_" + r, 200 + i * 200))
        return out
    def on_swipe(self, dy):
        self.off = min(self.off + 4, len(self.rows) - 8)
    def on_tap(self, rid): self.log.append(("tap", rid))
s = LongDropdown(); s.install()
k = walker(["home"], ["home"])
ok(k.pick("configs") and ("tap", w.ENTRY + "configs") in s.log, "D1 an entry 12 swipes down the dropdown is reached and tapped")

# D2: the section-list popup is still open; Back closes it; then the bar shows
class LingeringDropdown(Screen):
    open = True
    def nodes(self):
        if self.open:
            return [node(w.DROPDOWN, 100, 1900), node(w.CRUMB0, 100, 180), node(w.ENTRY + "cloud", 200), node(w.ENTRY + "phone", 300)]
        return [node(w.TITLE, 100), node(w.CONFIGS, 100), node(w.ITEM + "cloud", 2000), node(w.ITEM + "phone", 2000)]
    def on_back(self): self.open = False; return ""
s = LingeringDropdown(); s.install()
k = walker(["cloud", "phone"], ["cloud", "phone"])
k.visit_section("cloud")
ok(not any(r["status"] == "FAIL" for r in k.results) and ("tap", w.ITEM + "cloud") in s.log,
   "D2 a dropdown left open is closed before the bottom bar is read")

# D3: the gear opens the app's own full-screen Configs; its back arrow returns to the shell; only then is the section entered
class OwnConfigs(Screen):
    state = "main"
    def nodes(self):
        if self.state == "cfg":
            return [node("dialer_configs", 0, 2340), node("dialer_configs_back", 100), node("fleetcfg_general", 300)]
        return [node(w.TITLE, 100), node(w.CONFIGS, 100), node(w.ITEM + "home", 2000)]
    def on_tap(self, rid):
        self.log.append(("tap", rid))
        if rid == w.CONFIGS: self.state = "cfg"
        elif rid == "dialer_configs_back": self.state = "main"
    def on_back(self):
        self.log.append(("back",)); self.state = "main"; return ""
s = OwnConfigs(); s.install()
k = walker(["home", "favorites"], ["home"])
seen = []
k.enter_section = lambda sid: (seen.append(s.state), None)[1]
k.visit_section("favorites")
ok(seen == ["main"] and ("tap", "dialer_configs_back") in s.log, "D3 the app's own Configs screen is left by its back arrow before the dropdown is used")
print("%d failed" % fails)
sys.exit(1 if fails else 0)
PYEOF
}

echo "── the walker as committed"
run "$WALK"

# every mutation must turn its case red
mutate() {  # $1 name, $2 python expression rewriting `s`
  local m; m=$(mktemp -d)
  python3 -I -c "import sys; s=open(sys.argv[1]).read(); t=$2; assert t!=s, 'mutation did not apply'; open(sys.argv[2],'w').write(t)" "$WALK" "$m/walk.py"
  if run "$m/walk.py" >"$m/out" 2>&1; then echo "FAIL  mutation survived: $1"; cat "$m/out"; rm -rf "$m"; exit 1; fi
  echo "PASS  mutation caught: $1 ($(grep -m1 '^FAIL' "$m/out"))"
  rm -rf "$m"
}
echo "── mutations"
mutate "the dropdown is read 10 swipes deep" 's.replace("DROPDOWN_SWIPES = 40", "DROPDOWN_SWIPES = 10")'
mutate "the bar is read with a dropdown open" 's.replace("if not any(n.rid.startswith(ITEM) for n in nodes) and (find(nodes, CRUMB0)", "if False and (find(nodes, CRUMB0)")'
mutate "only fleetcfg_back leaves a Configs screen" 's.replace("next((n for n in cfg_nodes if n.rid.endswith(\"_configs_back\")), None)", "None").replace("elif cfg_nodes and find(cfg_nodes, TITLE) is None", "elif False")'
echo "nav-walk-driver: all cases passed, 3 mutations caught"
