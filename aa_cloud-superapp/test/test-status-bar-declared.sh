#!/usr/bin/env bash
# Tester: the HOME STATUS BAR is declared (build.json::home_status_bar) and the strip RENDERS FROM that declaration.
#
#   S1  every element has id, kind, position (left|centre|right), order, visibility; ids unique; orders unique per cluster
#   S2  the network elements are exactly NetworkSections.ORDER; clock, R/S/C and the battery are declared
#   S3  the declared row height is the 26 dp the shell leaves above the toolbar island (res/values/dimens.xml)
#   S4  the strip draws each cluster from StatusBarPrefs.layout (the declaration + the owner's overrides); no cluster is a
#       hard-coded list in LauncherStatusStrip.kt any more
#   S5  the clock opens the Tasks popup; the strip dispatches every declared `opens` value
#   S6  the One-Hand page's Status Bar section lists the declared elements with a visibility switch and a reorder
#   S8  (#1522) the ADB icon opens the ADB Shell page (adb_shell_page), one dot, refreshed on resume with no timer
#   S7  the declaration is baked (STATUS_BAR_B64) and a JVM test pins the layout to the declared order
#
# Usage: ./test-status-bar-declared.sh   (static only, no network, no device)
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
exec python3 - "$APP" <<'PY'
import json, re, sys
app = sys.argv[1]
fails = []
def ok(c, good, bad):
    print(("ok   " if c else "FAIL ") + (good if c else bad))
    if not c: fails.append(bad)
def read(p): return open(f"{app}/{p}", encoding="utf-8").read()
def code(t): return re.sub(r'/\*.*?\*/|//[^\n]*', '', t, flags=re.S)
sb = json.load(open(f"{app}/build.json", encoding="utf-8")).get("home_status_bar", {})
els = sb.get("elements", [])
ok(len(els) >= 14, f"S1: {len(els)} elements declared", f"S1: only {len(els)} elements declared")
need = {"id", "label", "short", "kind", "position", "order", "visible", "pet", "opens"}
ok(all(need <= set(e) for e in els), "S1: every element carries id, label, short, kind, position, order, visible, pet, opens", "S1: an element lacks one of " + str(sorted(need)))
ok(len({e["id"] for e in els}) == len(els), "S1: ids are unique", "S1: duplicate element id")
ok(all(e["position"] in ("left", "centre", "right") for e in els), "S1: positions are left|centre|right", "S1: an element has an unknown position")
dup = [p for p in ("left", "centre", "right") if len({e["order"] for e in els if e["position"] == p}) != len([e for e in els if e["position"] == p])]
ok(not dup, "S1: orders are unique inside each cluster", f"S1: clusters with a repeated order: {dup}")
ns = read("app/src/main/java/com/diegonmarcos/superapp/network/NetworkSections.kt")
order = re.search(r'val ORDER: List<String> = listOf\(([^)]*)\)', ns).group(1).replace(" ", "").split(",")
consts = {k: v for k, v in re.findall(r'const val ([A-Z]+) = "([a-z]+)"', ns)}
orderIds = sorted(consts[o] for o in order)
net = sorted(e["id"] for e in els if e["kind"] == "network")
ok(net == orderIds, f"S2: the network elements are NetworkSections.ORDER ({len(net)})", f"S2: network elements {net} != NetworkSections.ORDER {orderIds}")
left = [e["id"] for e in sorted(els, key=lambda e: e["order"]) if e["position"] == "left"]
ok(left == [consts[o] for o in order], "S2: the declared left order starts as the popup's section order", f"S2: left order {left}")
ids = {e["id"]: e for e in els}
ok(all(i in ids for i in ("clock", "ram", "storage", "cpu", "battery")), "S2: clock, R/S/C and battery are declared", "S2: clock/ram/storage/cpu/battery missing")
ok(ids.get("clock", {}).get("position") == "centre" and ids.get("battery", {}).get("position") == "right", "S2: clock centre, battery right", "S2: clock/battery position wrong")
dim = re.search(r'name="shell_top_row_margin">(\d+)dp', read("app/src/main/res/values/dimens.xml"))
ok(dim and int(dim.group(1)) == sb.get("row_height_dp"), f"S3: row height {sb.get('row_height_dp')} dp is shell_top_row_margin", "S3: row_height_dp differs from dimens.xml::shell_top_row_margin")
strip = read("app/src/main/java/com/diegonmarcos/superapp/launcher/LauncherStatusStrip.kt")
sc = code(strip)
ok("StatusBarPrefs.layout(ctx)" in sc, "S4: the strip asks the declaration (with overrides) for its layout", "S4: the strip does not read StatusBarPrefs.layout")
ok(all(f"StripCluster(layout.getValue(StatusBarDecl.{p}" in sc for p in ("LEFT", "CENTRE", "RIGHT")), "S4: each of the three clusters is drawn from layout[position]", "S4: a cluster is not drawn from the layout")
ok("NetworkSections.ORDER" not in sc and 'listOf("ram"' not in sc and "toolOf" not in sc, "S4: no hard-coded element list is left in the strip", "S4: a hard-coded element list (NetworkSections.ORDER / listOf(\"ram\" / toolOf) is still in the strip")
opens = {e["opens"] for e in els}
ok(all((f'"{o}" ->' in sc or f'== "{o}"' in sc) for o in opens), f"S5: the strip dispatches every declared `opens` ({sorted(opens)})", "S5: an `opens` value is not dispatched by the strip")
ok(ids.get("clock", {}).get("opens") == "tasks_popup" and "TasksPopup.show(host, r)" in sc, "S5: the clock opens the Tasks popup", "S5: the clock does not open the Tasks popup")
frag = read("app/src/main/java/com/diegonmarcos/superapp/configs/OneHandFragment.kt")
sec = frag[frag.find("private fun addStatusBar"):]
ok("StatusBarDecl.ordered(" in sec and "ConfigSwitchRow(el.label" in sec and "StatusBarDecl.move(" in sec and "StatusBarPrefs.setVisible" in sec,
   "S6: the Status Bar section lists the declared elements with a switch and a reorder", "S6: the Status Bar section lacks the list, the switch or the reorder")
g = read("app/build.gradle")
ok("STATUS_BAR_B64" in g and "declaredB64('home_status_bar')" in g, "S7: the declaration is baked", "S7: STATUS_BAR_B64 is not baked")
ok(ids.get("adb", {}).get("opens") == "adb_shell_page" and '"adb_shell_page" ->' in sc and "AdbShellLink.open(ctx)" in sc,
   "S8: the ADB icon opens the ADB Shell page", "S8: the ADB icon does not open the ADB Shell page")
ok("ON_RESUME" in sc and "model.refreshBroker()" in sc and "adbPeersTicker" not in sc and "postDelayed(brokerTicker" not in sc,
   "S8: the ADB dot is refreshed on resume, no timer", "S8: the ADB dot has no resume refresh, or polls")
import os
t = f"{app}/app/src/test/java/com/diegonmarcos/superapp/launcher/StatusBarDeclTest.kt"
tt = open(t, encoding="utf-8").read() if os.path.exists(t) else ""
ok("swapping two declared orders changes the render order" in tt, "S7: a JVM test pins the render order to the declared one", "S7: StatusBarDeclTest is missing its order-mutation test")
sys.exit(1 if fails else 0)
PY
