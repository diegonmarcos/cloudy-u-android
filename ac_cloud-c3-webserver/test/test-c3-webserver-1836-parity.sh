#!/usr/bin/env bash
# #1836 Home: per-service enable flags + common status rows + a Server table. Rule 16: nothing the Home drew before #1836 may be lost.
#  B  FROZEN BASELINE (every c3_ tag and every row / action / light / band label literal drawn by HomePage.kt + StatusPage.kt at git
#     HEAD before #1836): each must still be in the Home sources; the only move is the Server switch, to the Server table
#  S  every service table (Pages, API, SSHD, ADB Shell) draws the same status block (ServiceStatusRows over HomeModel.statusRows)
#  F  each hosted service has its own switch (Pages, SSHD) / Start-Stop (API) that posts only its own svc_<key> flag; Start also turns Server ON
#  V  the Server table (start / stop / restart / probe, pid, uptime, ports bound, keep-alive) and its Summary light row exist, drawn last
#  R  the Rust server persists the flags (power.rs), gates drive / diag / mesh / sshd / sites per service (lib.rs, meshlogs.rs, door.rs),
#     the power route accepts svc_*, restart (app.rs), and the Rust + Kotlin unit tests exist
# Each check is proven red by a mutation. Static: no build, no device.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import os, re, sys
APP = sys.argv[1]
def rd(p):
    try: return open(os.path.join(APP, p), encoding="utf-8").read()
    except OSError: return ""
UI = "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui/"
TEST = "src-tauri/gen/android/app/src/test/java/com/diegonmarcos/cloudwebserver/HomeModelTest.kt"
TAGS = ["c3_adb_keep_alive", "c3_adb_probe", "c3_adb_toggle", "c3_all_probe", "c3_all_startstop", "c3_api_keep_alive", "c3_api_probe", "c3_api_toggle", "c3_home", "c3_home_goto_api", "c3_home_goto_device", "c3_home_goto_pages", "c3_home_goto_sshd", "c3_home_pages", "c3_home_refresh", "c3_home_sshd", "c3_master_keep_alive", "c3_mode_edit", "c3_mode_view", "c3_pages_depends", "c3_pages_probe", "c3_power", "c3_power_keep_alive", "c3_power_server", "c3_power_ssh_door", "c3_power_ssh_follow", "c3_sshd_keep_alive", "c3_status_api", "c3_status_goto_api", "c3_status_goto_pages", "c3_status_summary", "c3_sum_adb", "c3_sum_api", "c3_sum_pages", "c3_sum_sshd"]
LABELS = ["ADB SHELL", "ADB Shell", "API", "API ›", "Active sites", "App status", "DEVICE", "DNS", "Depends on", "Device ›", "Endpoint", "Follow Keep alive", "Health", "IPs", "Keep alive", "Loopback server", "MCP", "MCP tools", "Mesh", "Mode", "Opens", "PAGES", "Page status", "Pages", "Pages ›", "Ports", "Probe", "Probe All", "Probe health", "Re-check", "Refresh", "Restart", "SSH door", "SSHD", "SSHd ›", "Served list", "Server", "Serving status", "Start", "State", "Stop", "Summary", "Topology", "Undeclared ports", "Uptime", "Version", "ssh command"]
def fn(src, name):
    i = src.find("fun %s(" % name)
    if i < 0: return ""
    j = src.find("\n}\n", i)
    return src[i:j if j > 0 else len(src)]
def check(home, status, model, power, lib, app, mesh, door, ktest):
    bad = []
    both = home + "\n" + status
    for t in TAGS:
        if '"%s"' % t not in both: bad.append("B1 tag %s is no longer drawn" % t)
    for l in LABELS:
        if '"%s"' % l not in both: bad.append("B2 label %r is no longer drawn" % l)
    if 'c3_power_server' not in fn(status, "ServerTable"): bad.append("B3 the Server switch is not in the Server table")
    for t in ["PagesTable", "ApiTable", "SshdTable", "AdbTable"]:
        if "ServiceStatusRows(HomeModel.statusRows(" not in fn(status, t): bad.append("S1 %s lacks the common status rows" % t)
    for k in ["Service status", "Enabled", "Server (master)", "Listening on", "Probe", "Keep-alive"]:
        if 'SRow("%s"' % k not in model: bad.append("S2 status row %s missing" % k)
    for k, t in [("pages", "PagesTable"), ("sshd", "SshdTable")]:
        if 'HomeModel.svcSet("%s", to)' % k not in fn(status, t): bad.append("F1 %s has no own service switch" % t)
    if 'HomeModel.svcSet("api", !apiOn)' not in fn(status, "ApiTable"): bad.append("F2 API Start/Stop does not post only its own flag")
    if "h.askOff(false)" in fn(status, "ApiTable") or 'h.setPower("server"' in fn(status, "ApiTable"): bad.append("F3 API Stop still stops the whole server")
    if 'if (on) it.put("server", true)' not in model: bad.append("F4 Start does not turn the Server on")
    st = fn(status, "ServerTable")
    for lit in ["c3_server_start", "c3_server_stop", "c3_server_restart", "c3_server_probe", '"PID"', '"Uptime"', '"Ports bound"', '"Keep-alive"', "restart"]:
        if lit not in st: bad.append("V1 Server table lacks %s" % lit)
    if 'DLights("Server"' not in fn(status, "SummaryTable"): bad.append("V2 no Server row in the Summary")
    if "ServerTable(data)" not in home or home.find("AdbTable(data") > home.find("ServerTable(data"): bad.append("V3 the Server table is not drawn last")
    for lit in ["pub enum Service", "pub fn service_on(", "pub fn set_service(", 'doc["svc"]', "pub fn restart("]:
        if lit not in power: bad.append("R1 power.rs lacks %s" % lit)
    if "power::Service::Pages" not in lib or "power::Service::Api" not in lib or "pub fn sync_services(" not in lib: bad.append("R2 lib.rs does not gate Pages and API / sync")
    if "Service::Api" not in mesh: bad.append("R3 the mesh log export is not gated by API")
    if "Service::Sshd" not in door: bad.append("R4 the hosted sshd is not gated by SSHD")
    if "svc_" not in app or "restart" not in app: bad.append("R5 the power route does not take svc_* / restart")
    if "a_disabled_service_answers_503_while_the_others_keep_serving" not in lib: bad.append("R6 Rust handler gating test missing")
    for lit in ["a_service_answers_only_while_server_and_its_own_flag_are_on", "the_service_flags_persist_and_an_old_store_means_all_on"]:
        if lit not in power: bad.append("R6 Rust test %s missing" % lit)
    if "stopping API leaves the Pages and SSHD lights on" not in ktest: bad.append("R7 Kotlin gating test missing")
    return bad
files = (rd(UI + "HomePage.kt"), rd(UI + "StatusPage.kt"), rd(UI + "HomeModel.kt"), rd("server/src/power.rs"), rd("server/src/lib.rs"), rd("server/src/app.rs"), rd("server/src/meshlogs.rs"), rd("server/src/door.rs"), rd(TEST))
bad = check(*files)
for b in bad: print("  FAIL:", b)
if bad or not files[1]: sys.exit(1)
print("  PASS: #1836 baseline kept, common status rows, per-service flags, Server table, Rust gating")
H = files[0]
def rep(i, a, b):
    f = list(files); assert a in f[i], a; f[i] = f[i].replace(a, b); return tuple(f)
srvlast = H.replace('SectionBand("server", "Server")\n        ServerTable(data)\n', "").replace('SectionBand("adb", "ADB Shell")', 'SectionBand("server", "Server")\n        ServerTable(data)\n        SectionBand("adb", "ADB Shell")')
muts = [
  ("an old tag dropped", rep(1, '"c3_api_keep_alive"', '"x"')),
  ("an old label dropped", rep(1, '"Undeclared ports"', '"x"')),
  ("Server switch left out of the Server table", rep(1, '"c3_power_server") { to -> if (!to) h.askOff(false)', '"c3_x") { to -> if (!to) h.askOff(false)')),
  ("a table without the status rows", rep(1, 'ServiceStatusRows(HomeModel.statusRows(if (pw == null) null else HomeModel.svcFlag(pw, "api")', 'Nothing(HomeModel.statusRows(if (pw == null) null else HomeModel.svcFlag(pw, "api")')),
  ("a status row dropped", rep(2, 'SRow("Probe", probe', 'SRow("Probex", probe')),
  ("API Stop stops the whole server", rep(1, 'h.setMany(HomeModel.svcSet("api", !apiOn))', 'h.askOff(false)')),
  ("Pages without its own switch", rep(1, 'HomeModel.svcSet("pages", to)', 'HomeModel.svcSet("api", to)')),
  ("Start does not turn the Server on", rep(2, 'if (on) it.put("server", true)', 'if (on) it.put("x", true)')),
  ("Server table without Restart", rep(1, '"c3_server_restart"', '"c3_server_rx"')),
  ("no Server summary row", rep(1, 'DLights("Server"', 'DLights("Srv"')),
  ("Server table not last", (srvlast,) + files[1:]),
  ("Rust flag not persisted", rep(3, 'doc["svc"]', 'doc["svcx"]')),
  ("Rust drive not gated per service", rep(4, 'power::Service::Pages', 'power::Service::Xx')),
  ("Rust mesh not gated", rep(6, 'Service::Api', 'Service::Pages')),
  ("Rust sshd not gated", rep(7, 'Service::Sshd', 'Service::Pages')),
  ("power route ignores svc_", rep(5, 'svc_', 'sv_')),
  ("Rust gating test removed", rep(4, 'a_disabled_service_answers_503_while_the_others_keep_serving', 'x')),
  ("Kotlin gating test removed", rep(8, 'stopping API leaves the Pages and SSHD lights on', 'x')),
]
ok = True
for name, m in muts:
    if not check(*m): print("  FAIL: mutation not caught:", name); ok = False
    else: print("  PASS: mutation turns red:", name)
sys.exit(0 if ok else 1)
PY
