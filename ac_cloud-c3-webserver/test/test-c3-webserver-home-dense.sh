#!/usr/bin/env bash
# #1723 Home: (1) FIELD PARITY (rule 16) against the previous Home (HomePage.kt at d5d0bdb6d): every card, row label, test tag, action and
# text it drew is still drawn; (2) the ADB shell status table has every asked row, each tappable to the ADB Shell sub page it belongs to, fed by the
# lib's own reads (ChannelReader, LocalServerAutoStart.lastSteps), not a copy; (3) the console density: Home uses the dense primitives on the
# KitDensity tokens, no Material Card / Kv / LinkText left on it. Each check is proven red by a mutation. Static: no build, no device.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import os, re, sys
APP = sys.argv[1]
K = os.path.join(APP, "src-tauri/gen/android/app/src")
def rd(p):
    try: return open(os.path.join(K, p), encoding="utf-8").read()
    except OSError: return ""
HOME = "main/java/com/diegonmarcos/cloudwebserver/ui/HomePage.kt"
DENSE = "main/java/com/diegonmarcos/cloudwebserver/ui/Dense.kt"
MODEL = "owner/java/com/diegonmarcos/cloudwebserver/owner/AdbHomeStatus.kt"
SIDE = "owner/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt"
DORM = "dormant/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt"
# the previous Home (frozen; the list only grows)
OLD = ['"c3_home"', '"c3_home_refresh"', '"Refresh"', '"c3_home_status"', '"Status"', '"State"', '"Version"', '"Uptime"', '"Health"', '"Keep alive"',
  '"c3_power"', '"c3_power_server"', '"c3_power_keep_alive"', '"c3_power_ssh_door"', '"c3_power_ssh_follow"', '"Follow Keep alive"', '"Ports"', '"SSH door"',
  '"Active sites"', '"Turn the Server OFF?"', '"Turn OFF"', '"Turn ON"', '"c3_home_pages"', '"Pages ›"', '"c3_home_goto_pages"', '"Undeclared ports"',
  '"c3_home_sshd"', '"SSHd ›"', '"c3_home_goto_sshd"', '"c3_home_ports"', '"Ports & DNS"', '"DNS"', '"c3_home_device"', '"Full report ›"', '"c3_home_goto_device"',
  '"Copy All Infos"', '"c3_copy_about"', '"c3_home_topology"', '"API topology"', '"c3_home_goto_api"', 'Open the API topology (MCP → API → IPC)',
  'Api.POWER', 'Api.HEALTH', 'Api.SERVING', 'Api.SERVED', 'Api.SSHD', 'Api.ABOUT', 'fun deviceFacts(', 'fun aboutText(', 'AboutGroups(', 'DeviceStrip(',
  '"Server ON, serving"', 'not answering (Server OFF stops :8003)', 'answering now', 'follows keep alive', 'own switch', 'Decl.SECTION_PAGES', 'Decl.SECTION_SSHD',
  'Decl.SECTION_CONFIGS', 'Decl.PAGE_DEVICE', 'Decl.SECTION_API', 'Decl.PAGE_TOPOLOGY']
ROWS = [("profile", "adb_server"), ("state", "adb_server"), ("port", "adb_server"), ("pid", "adb_server"), ("uptime", "adb_server"), ("launcher", "adb_server"),
  ("last_start", "adb_server"), ("auto_chain", "adb_server"), ("wireless", "adb_status"), ("paired", "adb_status"), ("clients", "adb_perms")]
LIB = ["ChannelReader.read(", "LocalServerAutoStart.lastSteps", "LocalServerAutoStart.lastOutcome", "AdbShellBootstrap.lastLauncher(", "AdbShellBootstrap.bootstrapState(",
  "ChannelModePrefs.current(", "f?.wirelessDebug", "f.adbPaired", "ShellBroker.callerSummaries("]
def check(home, dense, model, side, dorm):
    bad = []
    for n in OLD:
        if n not in home: bad.append("P1 old Home field %s is gone" % n)
    for r, page in ROWS:
        if 'AdbHomeRow("%s"' % r not in model: bad.append("A1 ADB table row %s missing" % r)
        elif not re.search(r'AdbHomeRow\("%s",[^\n]*Decl\.PAGE_%s\)' % (r, page.upper()), model): bad.append("A2 row %s does not open %s" % (r, page))
    if "open(Decl.SECTION_ADB_SHELL, r.page)" not in model: bad.append("A3 the rows are not tappable to their sub page")
    for n in LIB:
        if n not in model: bad.append("A4 the table does not read the lib's %s" % n)
    if re.search(r"class\s+\w*Step\w*\b|lastSteps\s*=", model): bad.append("A5 the table keeps its own auto-start steps")
    if "AdbOwnerSide.HomeStatus(tick, open)" not in home: bad.append("A6 Home does not draw the ADB table")
    if "fun HomeStatus(" not in side or "fun HomeStatus(" not in dorm: bad.append("A7 an ADB owner flavour lacks HomeStatus")
    for n in ["KitDensity.body", "KitDensity.caption", "KitDensity.rowPadH", "fun DRow(", "fun DenseCard(", "KEY_COL"]:
        if n not in dense: bad.append("D1 Dense.kt lacks %s" % n)
    for n in ["DensePage(", "DenseCard(", "DRow(", "DCols("]:
        if n not in home: bad.append("D2 Home does not use %s" % n)
    code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", home, flags=re.S))
    if re.search(r"(?<![A-Za-z])(Card|Kv|PageColumn|LinkText|Act|Acts)\(", code): bad.append("D3 Home still draws a big Card / Kv / PageColumn")
    if re.search(r"Color\s*\(|Color\.[A-Z]", dense + model + home): bad.append("D4 a colour literal on Home")
    return bad
files = (rd(HOME), rd(DENSE), rd(MODEL), rd(SIDE), rd(DORM))
bad = check(*files)
for b in bad: print("  FAIL:", b)
if not rd(MODEL): print("  FAIL: missing files"); bad.append("x")
if bad: sys.exit(1)
print("  PASS: Home parity, ADB status table rows + taps + lib reads, dense primitives")
home, dense, model, side, dorm = files
muts = [
  ("old field dropped", lambda: (home.replace('"c3_power_ssh_follow"', '"x"'),) + files[1:]),
  ("a row dropped", lambda: (home, dense, model.replace('AdbHomeRow("paired"', 'AdbHomeRow("pairedx"'), side, dorm)),
  ("a row to the wrong page", lambda: (home, dense, model.replace('Decl.PAGE_ADB_PERMS', 'Decl.PAGE_ADB_ALL'), side, dorm)),
  ("rows not tappable", lambda: (home, dense, model.replace('open(Decl.SECTION_ADB_SHELL, r.page)', 'Unit'), side, dorm)),
  ("lib read dropped", lambda: (home, dense, model.replace('LocalServerAutoStart.lastSteps', 'emptyList<StepRun>()'), side, dorm)),
  ("table not drawn", lambda: (home.replace('AdbOwnerSide.HomeStatus(tick, open)', ''), dense, model, side, dorm)),
  ("big card back", lambda: (home.replace('DenseCard("Status"', 'Card("Status"'),) + files[1:]),
  ("dense token dropped", lambda: (home, dense.replace('KitDensity.caption', '11.sp'), model, side, dorm)),
  ("colour literal", lambda: (home + "\nval c = Color(0xFF00FF00)", dense, model, side, dorm)),
  ("stand-in without HomeStatus", lambda: (home, dense, model, side, dorm.replace('fun HomeStatus(', 'fun Hx('))),
]
ok = True
for name, m in muts:
    if not check(*m()): print("  FAIL: mutation not caught:", name); ok = False
    else: print("  PASS: mutation turns red:", name)
sys.exit(0 if ok else 1)
PY
