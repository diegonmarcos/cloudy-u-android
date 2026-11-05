#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #886 Tabs in the island, drop-to-group, and the tab strip        ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# What this certifies, statically (the rules themselves are JVM-tested in BrowserGroupsTest):
#   T1 Tabs is a ui.sections id AND in ui.bottom_nav; MainActivity routes it to the host's openTabs();
#      the host's Browser door goes back to the open tab; the pill shows Tabs while the switcher is up.
#   T2 the grid reports a drop ON a tab / ON a group header (hit-tested while dragging), and the host
#      hands both to the store's group rules; a same-group drop stays a reorder.
#   T3 groups are named, coloured and editable: the header shows the colour and a ✎ that opens the editor.
#   T4 the strip: under the bar, on by default (only an explicit false hides it), the tab's group or the
#      tab alone, favicons kept from the page, a + that opens the New tab screen joined to the group.
# Each is then run against planted mutations; one that leaves every check green fails this tester.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, json, os
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    grid = src.get(LIB + "/BrowserTabGrid.kt", "")
    prefs = src.get(LIB + "/BrowserTabPrefs.kt", "")
    groups = src.get(LIB + "/BrowserTabGroups.kt", "")
    strip = src.get(LIB + "/BrowserTabStrip.kt", "")
    main_ = src.get(APP + "/MainActivity.kt", "")
    ui = build_json(root)["ui"]
    # T1
    ok("tabs" in ui.get("bottom_nav", []) and any(s.get("id") == "tabs" for s in ui.get("sections", [])), "T1 Tabs is a section and an island item")
    ok('"tabs" -> host.openTabs()' in main_ and '"browser" -> host.openBrowser()' in main_, "T1 MainActivity routes the island to the host's doors")
    ok('if (f.isTabsOpen) "tabs" else "browser"' in main_, "T1 the pill shows Tabs while the switcher is on screen")
    ok('text = " ← Tabs "' not in frag, "T1 the redundant Tabs button at the top left of the bar is gone (#893)")
    ok("fun openTabs()" in frag and "showGrid()" in frag[frag.find("fun openTabs()"):frag.find("fun openTabs()") + 160], "T1 openTabs shows the grid")
    ok("fun openBrowser()" in frag and "showDetail(t)" in frag[frag.find("fun openBrowser()"):frag.find("fun openBrowser()") + 400], "T1 openBrowser returns to the active tab")
    ok(os.path.exists(os.path.join(root, "ac_cloud-browser/app/src/main/res/drawable/ic_nav_tabs.xml")), "T1 the Tabs icon exists")
    # T2
    ok("BrowserTabGroups.hit(slots, cx, cy, k)" in grid and "fun move(delta: Offset)" in grid, "T2 the grid hit-tests the drop target while dragging")
    ok("onDropOnTab(k, target.tabKey)" in grid and "onDropOnGroup(k, target.group)" in grid, "T2 the grid reports a drop on a tab and on a group header")
    ok("targetGroup == dragGroup" in grid, "T2 a drop within one named group is still a reorder, not a regroup")
    ok("a.group.isNotBlank() && model.canMove(" in grid, "T2 a loose tab dragged over another is never swapped under the finger")
    ok("prefs.dropOnTab(drag, target)" in frag and "prefs.dropOnGroup(drag, g)" in frag, "T2 the host sends both drops to the group rules")
    ok("fun dropOnTab(" in groups and "created = true" in groups, "T2 dropping on a loose tab creates a group")
    ok("fun dropOnGroup(" in groups and "if (group.isBlank() || drag.group == group) return null" in groups, "T2 dropping on a group joins it")
    # T3
    ok("BrowserTabGroups.colorOf(groupColors(), row.group)" in grid and "onEdit(row.group)" in grid, "T3 a group header shows its colour and an edit button")
    ok("showGroupEditor(g)" in frag and "prefs.renameGroup(group, name, color)" in frag, "T3 the editor renames and recolours")
    ok("fun nameFor(" in groups and "fun nextColor(" in groups and "fun unique(" in groups, "T3 a new group is named and coloured without clashing")
    ok("fun renameGroup(" in prefs and "fun ungroup(" in prefs and "group_colors" in prefs, "T3 name and colour persist; ungroup dissolves")
    # T4
    ok('browserSettings.bool("tab_strip") != false' in frag and "BrowserTabStrip(" in frag, "T4 the strip is drawn unless the setting is explicitly false")
    st = [s for s in build_json(root)["ui"]["browser"]["settings"] if s["key"] == "tab_strip"]
    ok(st and st[0].get("default") is True, "T4 tab_strip is declared on by default")
    ok("fun visible(" in strip and "current.group.isBlank()" in strip, "T4 the strip shows the current tab's group, or the tab alone")
    ok("onNew = { promptForUrl(private_ = currentTab()?.isPrivate == true, joinTabKey = currentTab()?.key) }" in frag, "T4 + opens a new tab joined to the current tab's group")
    ok("prefs.startOrJoinGroup(joinTabKey, tab.key)" in frag and "prefs.addNew(" in frag, "T4 the new tab is a new tab, placed in the group (or starting one)")
    ok("onSelect = { t -> if (t.key != currentTab()?.key)" in frag and "showDetail(prefs.byId(t.key) ?: t)" in frag, "T4 tapping an icon switches tab")
    ok("override fun onReceivedIcon" in frag and "saveFavicon(tabKey, view?.url, icon)" in frag and "if (tab.isPrivate) return" in frag[frag.find("private fun saveFavicon"):], "T4 favicons are kept from the page, never for a private tab")
    ok("fun startOrJoin(" in groups, "T4 + on a loose tab starts a group, on a grouped one joins it")

FRAG = LIB + "/BrowserHostFragment.kt"
GRID = LIB + "/BrowserTabGrid.kt"
main("tabs and groups", check, [
    ("Tabs leaves the island", "ac_cloud-browser/build.json", '      "browser",\n      "tabs",\n      "domains"', '      "browser",\n      "domains"', "T1 Tabs is a section"),
    ("the Tabs button returns to the bar", LIB + "/BrowserHostFragment.kt", "        // Address bar: a plain field.", "        bar.addView(TextView(ctx).apply { text = \" ← Tabs \" })\n        // Address bar: a plain field.", "Tabs button at the top left"),
    ("the island stops opening the switcher", APP + "/MainActivity.kt", '"tabs" -> host.openTabs()', '"tabs" -> Unit', "routes the island"),
    ("the pill stays on Browser", APP + "/MainActivity.kt", 'if (f.isTabsOpen) "tabs" else "browser"', '"browser"', "shows Tabs"),
    ("the grid stops hit-testing", GRID, "BrowserTabGroups.hit(slots, cx, cy, k)", "null", "hit-tests"),
    ("a drop on a header is lost", GRID, "target?.group != null -> onDropOnGroup(k, target.group)", "target?.group != null -> Unit", "drop on a tab and on a group header"),
    ("a same-group drop regroups", GRID, "targetGroup == dragGroup", "targetGroup == \"\\u0000\"", "still a reorder"),
    ("loose tabs swap under the finger", GRID, "a.group.isNotBlank() && model.canMove(", "model.canMove(", "never swapped"),
    ("the host drops the drop", FRAG, "prefs.dropOnTab(drag, target)", "null", "sends both drops"),
    ("the group loses its colour", GRID, "Color(BrowserTabGroups.colorOf(groupColors(), row.group))", "Color(0xFFE9D8FD)", "shows its colour"),
    ("the editor stops saving", FRAG, "prefs.renameGroup(group, name, color)", "Unit", "renames and recolours"),
    ("the strip is off unless switched on", FRAG, 'browserSettings.bool("tab_strip") != false', 'browserSettings.bool("tab_strip") == true', "drawn unless"),
    ("tab_strip defaults off", "ac_cloud-browser/build.json", '"key": "tab_strip",\n          "type": "bool",\n          "default": true,\n          "label": "Tab strip', '"key": "tab_strip",\n          "type": "bool",\n          "default": false,\n          "label": "Tab strip', "on by default"),
    ("+ forgets the group", FRAG, "joinTabKey = currentTab()?.key) }", "joinTabKey = null) }", "joined to the current tab's group"),
    ("a tap on an icon does nothing", FRAG, "showDetail(prefs.byId(t.key) ?: t)", "Unit", "switches tab"),
    ("a private tab's favicon is kept", FRAG, "if (tab.isPrivate) return\n        val host", "val host", "never for a private tab"),
])
PYEOF
