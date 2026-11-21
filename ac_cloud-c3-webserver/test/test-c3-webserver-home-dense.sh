#!/usr/bin/env bash
# #1723 / #1834 Home: (1) FIELD PARITY (rule 16) against the previous Home (HomePage.kt + StatusPage.kt + KeepAliveHealthCard.kt at git HEAD before
# #1834, frozen below): #1834 folded every table into ONE TABLE PER SERVICE, so each frozen field must still be drawn inside the table of the
# service it belongs to (the function named beside it), or stay on Configs > Device (not about one service); the only fields not drawn are the two
# the owner removed by #1834 (the keep-alive health block and the Server summary row), listed explicitly; (2) the ADB shell status table has every asked row, each tappable to the ADB Shell sub page it belongs to, fed by the
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
STATUS = "main/java/com/diegonmarcos/cloudwebserver/ui/StatusPage.kt"
CONFIGS = "main/java/com/diegonmarcos/cloudwebserver/ui/ConfigsPages.kt"
DENSE = "main/java/com/diegonmarcos/cloudwebserver/ui/Dense.kt"
MODEL = "owner/java/com/diegonmarcos/cloudwebserver/owner/AdbHomeStatus.kt"
SIDE = "owner/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt"
DORM = "dormant/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt"
# the previous Home, frozen from git HEAD before #1834: (literal, where). where = the function that must draw it; the list only grows
P, A, S, H, D, U = "PagesTable", "ApiTable", "SshdTable", "AdbTable", "SummaryTable", "HomePage"
FROZEN = [
 ("c3_home", U), ("c3_home_refresh", U), ("Refresh", U),
 # Status card + Server card (PowerCard) -> Pages / API / SSHD
 ("State", P), ("Server ON, serving", P), ("Server ON, starting", P), ("Server OFF: pages, sshd, DNS and sites stopped", P),
 ("Version", A), ("Uptime", A), ("Health", A), ("not answering (Server OFF stops :8003)", A), ("Keep alive", P), ("Ports", P), ("Active sites", P),
 ("c3_power_server", "ServerTable"), ("c3_power_keep_alive", P), ("OFF stops the served pages, DNS, the mesh listener, sshd and every site", "ServerTable"),  # #1836: the Server switch moved to the Server table, Pages has its own service switch
 ("ON keeps the server up with a notification and starts it at boot", P), ("Server", P),
 ("c3_power_ssh_door", S), ("c3_power_ssh_follow", S), ("Follow Keep alive", S), ("SSH door", S), ("ON asks Cloud Terminal Nix-on-Droid to start its sshd", S),
 ("Server", "ServerTable"), ("Turn the Server OFF?", U), ("Turn OFF", U), ("Turn ON", "SwitchRow"),
 # Served pages / Pages status
 ("c3_home_pages", P), ("Pages \u203a", P), ("c3_home_goto_pages", P), ("Undeclared ports", P), ("serving", P), ("not serving", P), ("stopped", P), ("role", P),
 ("Page status", P), ("App status", P), ("Serving status", P), ("Served list", P), ("c3_status_goto_pages", P), ("answering now", P), ("PAGES", P),
 # API status, topology, MCP, mesh, DNS
 ("c3_status_api", A), ("API \u203a", A), ("c3_status_goto_api", A), ("MCP tools", A), ("MCP", A), ("Endpoint", A), ("Mesh", A), ("DNS", A),
 ("Topology", A), ("c3_home_goto_api", A), ("Open the API topology (MCP \u2192 API \u2192 IPC)", A),
 # SSHd engines, endpoints
 ("c3_home_sshd", S), ("SSHd \u203a", S), ("c3_home_goto_sshd", S), ("follows keep alive", S), ("own switch", S), ("SSHD", S), ("DEVICE", S), ("IPs", S),
 # ADB shell
 ("Loopback server", H), ("Opens", H), ("ADB SHELL", H),
 # Status summary
 ("c3_status_summary", D), ("c3_mode_edit", D), ("c3_mode_view", D), ("Mode", D), ("Edit (switches work)", D), ("View only (switches locked)", D),
 ("c3_master_keep_alive", D), ("ALL UP", D), ("ATTENTION", D), ("STARTING", D), ("c3_home_goto_device", D),
]
# not about one service: stays on Configs > Device (ConfigsPages.kt DevicePage), drawn from the same About groups
DEVICE_PAGE = ["c3_copy_about", "Copy All Infos", "AboutGroups(", "aboutText("]
DEVICE_FIELDS = ["Uptime", "Memory", "Storage", "Battery", "Network"]  # the old Device strip rows: sections of the About groups there
# removed by the owner in #1834, the only fields allowed to be gone (the test asserts the list and that none is drawn any more)
REMOVED_BY_1834 = ["c3_home_keepalive", "Keep-alive health", "Fix in Perms", "c3_ka_fix_", "Fix opens Configs > Perms", "KeepAliveHealthCard("]
REMOVED_ROW_SERVER = 'DRow("Server", if (serverOn)'
ROWS = [("connection", "adb_status"), ("profile", "adb_server"), ("state", "adb_server"), ("port", "adb_server"), ("pid", "adb_server"), ("uptime", "adb_server"), ("launcher", "adb_server"),
  ("last_start", "adb_server"), ("auto_chain", "adb_server"), ("wireless", "adb_status"), ("paired", "adb_status"), ("clients", "adb_perms")]
LIB = ["ChannelReader.read(", "LocalServerAutoStart.lastSteps", "LocalServerAutoStart.lastOutcome", "AdbShellBootstrap.lastLauncher(", "AdbShellBootstrap.bootstrapState(",
  "ChannelModePrefs.current(", "f?.wirelessDebug", "f.adbPaired", "ShellBroker.callerSummaries("]
def fn(src, name):
    i = src.find("fun %s(" % name)
    if i < 0: return ""
    j = src.find("\n}\n", i)
    return src[i:j if j > 0 else len(src)]
def check(home, dense, model, side, dorm, cfg=None):
    bad = []
    cfg = CFG if cfg is None else cfg
    for lit, where in FROZEN:
        if lit not in fn(home, where): bad.append("P1 old Home field %r is not drawn in %s" % (lit, where))
    for lit in DEVICE_PAGE:
        if lit not in cfg: bad.append("P2 Configs > Device lost %s" % lit)
    for lit in REMOVED_BY_1834:
        if lit in home: bad.append("P3 %r was removed by #1834 but is drawn again" % lit)
    if REMOVED_ROW_SERVER in home: bad.append("P3 the Server summary row was removed by #1834")
    for r, page in ROWS:
        if 'AdbHomeRow("%s"' % r not in model: bad.append("A1 ADB table row %s missing" % r)
        elif not re.search(r'AdbHomeRow\("%s",[^\n]*Decl\.PAGE_%s\)' % (r, page.upper()), model): bad.append("A2 row %s does not open %s" % (r, page))
    if "open(Decl.SECTION_ADB_SHELL, r.page)" not in model: bad.append("A3 the rows are not tappable to their sub page")
    for n in LIB:
        if n not in model: bad.append("A4 the table does not read the lib's %s" % n)
    if re.search(r"class\s+\w*Step\w*\b|lastSteps\s*=", model): bad.append("A5 the table keeps its own auto-start steps")
    if "AdbOwnerSide.HomeStatus(tick, h.open)" not in home: bad.append("A6 Home does not draw the ADB table")
    if "fun HomeStatus(" not in side or "fun HomeStatus(" not in dorm: bad.append("A7 an ADB owner flavour lacks HomeStatus")
    for n in ["KitDensity.body", "KitDensity.caption", "KitDensity.rowPadH", "fun DRow(", "fun DenseCard(", "KEY_COL"]:
        if n not in dense: bad.append("D1 Dense.kt lacks %s" % n)
    for n in ["DensePage(", "DenseCard(", "DRow(", "DCols("]:
        if n not in home: bad.append("D2 Home does not use %s" % n)
    code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", home, flags=re.S))
    if re.search(r"(?<![A-Za-z])(Card|Kv|PageColumn|LinkText|Act|Acts)\(", code): bad.append("D3 Home still draws a big Card / Kv / PageColumn")
    if re.search(r"Color\s*\(|Color\.[A-Z]", dense + model + home): bad.append("D4 a colour literal on Home")
    return bad
CFG = rd(CONFIGS) + rd(HOME)
files = (rd(HOME) + "\n" + rd(STATUS), rd(DENSE), rd(MODEL), rd(SIDE), rd(DORM))
bad = check(*files)
for b in bad: print("  FAIL:", b)
if not rd(MODEL): print("  FAIL: missing files"); bad.append("x")
if bad: sys.exit(1)
print("  PASS: Home parity, ADB status table rows + taps + lib reads, dense primitives")
home, dense, model, side, dorm = files
muts = [
  ("old field dropped", lambda: (home.replace('"c3_power_ssh_follow"', '"x"'),) + files[1:]),
  ("old field moved to the wrong service", lambda: (home.replace('"c3_power_ssh_door"', '"c3_x"'),) + files[1:]),
  ("a row dropped", lambda: (home, dense, model.replace('AdbHomeRow("paired"', 'AdbHomeRow("pairedx"'), side, dorm)),
  ("a row to the wrong page", lambda: (home, dense, model.replace('Decl.PAGE_ADB_PERMS', 'Decl.PAGE_ADB_ALL'), side, dorm)),
  ("rows not tappable", lambda: (home, dense, model.replace('open(Decl.SECTION_ADB_SHELL, r.page)', 'Unit'), side, dorm)),
  ("lib read dropped", lambda: (home, dense, model.replace('LocalServerAutoStart.lastSteps', 'emptyList<StepRun>()'), side, dorm)),
  ("table not drawn", lambda: (home.replace('AdbOwnerSide.HomeStatus(tick, h.open)', ''), dense, model, side, dorm)),
  ("big card back", lambda: (home.replace('DenseCard("Summary"', 'Card("Summary"'),) + files[1:]),
  ("a field moved out of its service table", lambda: (home.replace('DRow("Undeclared ports"', 'DRow("Undeclared"'),) + files[1:]),
  ("a removed block drawn again", lambda: (home + '\nKeepAliveHealthCard(tick, open)',) + files[1:]),
  ("device report lost its page", lambda: (home, dense, model, side, dorm, "")),
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
