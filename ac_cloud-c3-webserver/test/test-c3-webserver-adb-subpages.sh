#!/usr/bin/env bash
# #1713 the ADB Shell sub pages: (1) Status, Shell, Perms, Server, All are declared in build.json::ui.sections[adb_shell].pages in that order
# and drawn by the fleet's shared second row (NavSectionLayout, libs:ui-nav-bars) - the owner side keeps no tab row of its own; (2) the Server
# page is ONLY the local ADB shell server management (state, pid/port/uptime, launcher, last result, Start / Stop / Restart, the auto-start
# chain, the profile selector, the server log); (3) FIELD PARITY (rule 16) against the previous Server tab (AdbOwnerSide at 2735821a8 / 4d6f8a9b1):
# every field it held is still drawn, on the page named in AdbOwnerSide.FIELD_HOME, and none of the moved fields is on Server. Then every check
# is mutated in a copy and must turn red. Static: no build, no device.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import json, os, re, sys
APP = sys.argv[1]
K = os.path.join(APP, "src-tauri/gen/android/app/src")
def rd(p):
    try: return open(os.path.join(APP, p), encoding="utf-8").read()
    except OSError: return ""
OWN = "src-tauri/gen/android/app/src/owner/java/com/diegonmarcos/cloudwebserver/owner/"
MAIN = "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/"
ORDER = [("adb_status", "Status"), ("adb_term", "Shell"), ("adb_perms", "Perms"), ("adb_server", "Server"), ("adb_all", "All")]
# fields of the previous Server tab (frozen; the list only grows): needle -> the page that draws it now
OLD = {
  '"c3_adb_owner"': "adb_status", '"c3_adb_owner_status"': "adb_status", '"c3_adb_owner_connect"': "adb_status", '"c3_adb_owner_recheck"': "adb_status",
  '"c3_adb_owner_tester"': "adb_status", '"c3_adb_owner_test"': "adb_status", '"c3_adb_owner_logs"': "adb_status",
  '"Run id -u"': "adb_status", 'ready.linkText()': "adb_status", 'AdbReadiness.connect(ctx': "adb_status", 'AdbReadiness.check(ctx, fresh = true)': "adb_status",
  'ShellBroker.exec(ctx, ShellBroker.SELF, "id -u")': "adb_status", 'ChannelLog.shared.asText()': "adb_status", '"no channel log yet"': "adb_status",
  '"c3_adb_owner_page"': "adb_all", 'AdbShellPage()': "adb_all",
}
# the previous tab row's four lib tabs: each still drawn on its sub page
LIBTABS = {"AdbStatusTab()": "adb_status", "AdbTermTab()": "adb_term", "AdbPermsTab()": "adb_perms", "AdbShellBrokerPage()": "adb_all"}
SERVER_FIELDS = ["ChannelReader.read(", "f.serverRunning", "f.serverPort", "f?.serverPid", "f?.serverUid", "f?.serverUptime", "AdbShellBootstrap.lastLauncher(ctx)",
  "AdbShellBootstrap.bootstrapState(ctx)", "LocalServerAutoStart.lastOutcome", "LocalServerAutoStart.lastSteps", "ConnectPlan.steps(", "ChannelOps.startServer(ctx, restart = false)",
  "ChannelOps.stopServer(ctx)", "ChannelOps.startServer(ctx, restart = true)", "ChannelModeBar", "ChannelLog.shared.entries()", '"c3_adb_server_start"', '"c3_adb_server_stop"',
  '"c3_adb_server_restart"', '"c3_adb_server_chain"', '"c3_adb_server_profile"', '"c3_adb_server_log"', '"c3_adb_server_state"']
NOT_ON_SERVER = ["ShellBroker.exec", "AdbShellPage()", "AdbReadiness.connect", "Run id -u"]

def check(bj, owner, server, shell, wpage, decl):
    bad = []
    secs = {s["id"]: s for s in bj["ui"]["sections"]}
    got = [(p["id"], p["label"]) for p in (secs.get("adb_shell") or {}).get("pages") or []]
    if got != ORDER: bad.append("N1 build.json::ui adb_shell pages %r, want %r" % (got, ORDER))
    for pid, _ in ORDER:
        c = "PAGE_" + pid.upper()
        if '"%s"' % pid not in decl or c not in decl: bad.append("N2 Decl.%s missing" % c)
        if "Decl." + c not in owner + wpage: bad.append("N2 nothing dispatches on Decl.%s" % c)
    if "NavSectionLayout(" not in shell: bad.append("N3 Shell.kt does not draw the sub pages with the fleet NavSectionLayout")
    if re.search(r"horizontalScroll|\.clickable\s*\{\s*tab\s*=|c3_adb_owner_tab_|private val TABS", owner): bad.append("N3 the owner side draws a tab row of its own")
    if "Decl.SECTION_ADB_SHELL -> WebAdbShellPage(open" not in shell: bad.append("N4 Shell.kt does not hand the page to the ADB Shell")
    for n, home in OLD.items():
        if n not in owner: bad.append("P1 old Server-tab field %s is gone" % n)
    for n, home in OLD.items():
        if n in server: bad.append("P2 moved field %s is still on the Server page" % n)
    for n, pid in LIBTABS.items():
        if n not in owner: bad.append("P3 old tab content %s is gone" % n)
    for n in SERVER_FIELDS:
        if n not in server: bad.append("S1 the Server page lacks %s" % n)
    for n in NOT_ON_SERVER:
        if n in server: bad.append("S2 %s is not server management but is on the Server page" % n)
    for t in ("Local server", "Shizuku", "Embedded"):
        pass
    if "FIELD_HOME" not in owner: bad.append("P4 AdbOwnerSide.FIELD_HOME (where each field went) is missing")
    for n, pid in (("Status", "PAGE_ADB_STATUS"), ("Tester", "PAGE_ADB_STATUS"), ("Logs", "PAGE_ADB_STATUS"), ("ADB Shell", "PAGE_ADB_ALL")):
        if not re.search(r'"%s" to Decl\.%s' % (n, pid), owner): bad.append("P4 FIELD_HOME does not map %s to %s" % (n, pid))
    return bad

bj = json.loads(rd("build.json")); owner = rd(OWN + "AdbOwnerSide.kt"); server = rd(OWN + "AdbServerTab.kt")
shell = rd(MAIN + "ui/Shell.kt"); wpage = rd(MAIN + "ui/WebAdbShellPage.kt"); decl = rd(MAIN + "Decl.kt")
bad = check(bj, owner, server, shell, wpage, decl); fails = 0
print(("  PASS: " if not bad else "  FAIL: ") + "ADB sub pages declared, shared-bar drawn, Server is server-only, every old field kept" + ("" if not bad else "\n    " + "\n    ".join(bad)))
fails += 1 if bad else 0
def mut(name, **kw):
    global fails
    a = dict(bj=json.loads(json.dumps(bj)), owner=owner, server=server, shell=shell, wpage=wpage, decl=decl); a.update(kw)
    red = bool(check(**a)); print(("  PASS: " if red else "  FAIL: ") + "mutation turns red: " + name); fails += 0 if red else 1
b2 = json.loads(json.dumps(bj)); [s for s in b2["ui"]["sections"] if s["id"] == "adb_shell"][0]["pages"].reverse(); mut("pages reordered", bj=b2)
b3 = json.loads(json.dumps(bj)); [s for s in b3["ui"]["sections"] if s["id"] == "adb_shell"][0]["pages"].pop(); mut("a page undeclared", bj=b3)
mut("custom tab row back", owner=owner + "\nRow(Modifier.horizontalScroll(s)) {}")
mut("shared second row dropped", shell=shell.replace("NavSectionLayout(", "XLayout("))
for n in list(OLD) + list(LIBTABS): mut("drop " + n, owner=owner.replace(n, "X"))
for n in OLD: mut("moved field %s back onto Server" % n, server=server + "\n" + n)
for n in SERVER_FIELDS: mut("Server loses " + n, server=server.replace(n, "X"))
mut("FIELD_HOME dropped", owner=owner.replace("FIELD_HOME", "X"))
sys.exit(1 if fails else 0)
PY
