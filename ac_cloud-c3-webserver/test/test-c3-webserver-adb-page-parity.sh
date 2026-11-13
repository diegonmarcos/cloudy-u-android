#!/usr/bin/env bash
# #1682 P2b field parity: ui/WebAdbShellPage.kt (renamed from ui/AdbShellPage.kt so the lib keeps the fleet's one AdbShellPage) keeps
# every field of the P1 page: its page / card / action tags, its action labels, the core route it posts to, the auto-open on select,
# the not-installed reason and the Configs link; and it is reached from the ADB Shell section. Then each field is removed in a copy
# and must turn the check red. Static: no build, no device.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; APP="$(cd "$HERE/.." && pwd)"
python3 - "$APP" <<'PY'
import os, re, sys
APP = sys.argv[1]; UI = os.path.join(APP, "src-tauri/gen/android/app/src/main/java/com/diegonmarcos/cloudwebserver/ui")
def rd(p):
    try: return open(p, encoding="utf-8").read()
    except OSError: return ""
# The P1 page's fields (ui/AdbShellPage.kt at 50b8920e1), frozen: the list only grows.
FIELDS = ['"c3_adb_shell"', '"c3_adb"', '"c3_adb_open"', '"c3_adb_refresh"', '"c3_adb_goto_configs"',
          '"▶ Open ADB Shell in Cloud SuperApp"', '"Refresh"', '"Open Configs ›"', "Api.ADB_SHELL", "Api.post(Api.ADB_SHELL",
          "ErrorNote(l.str(\"error\"))", "Opened Cloud SuperApp > ADB Shell.", "Opening Cloud SuperApp > ADB Shell...",
          "LaunchedEffect(link)", "Decl.SECTION_CONFIGS", "rememberObj(Api.ADB_SHELL, tick)"]
def check(page, shell, old_exists):
    bad = [f for f in FIELDS if f not in page]
    if "fun WebAdbShellPage(" not in page: bad.append("fun WebAdbShellPage(")
    if "Decl.SECTION_ADB_SHELL -> WebAdbShellPage(open)" not in shell: bad.append("Shell.kt dispatch")
    if old_exists: bad.append("ui/AdbShellPage.kt is back (the lib owns the one AdbShellPage)")
    return bad
page = rd(os.path.join(UI, "WebAdbShellPage.kt")); shell = rd(os.path.join(UI, "Shell.kt")); old = os.path.exists(os.path.join(UI, "AdbShellPage.kt"))
bad = check(page, shell, old); fails = 0
print(("  PASS: " if not bad else "  FAIL: ") + "WebAdbShellPage keeps every field of the P1 ADB Shell page" + ("" if not bad else " -> " + ", ".join(bad))); fails += 1 if bad else 0
for f in FIELDS + ["fun WebAdbShellPage("]:
    red = bool(check(page.replace(f, "X"), shell, False))
    print(("  PASS: " if red else "  FAIL: ") + "dropping %s turns red" % f); fails += 0 if red else 1
for label, sh, o in (("the section dispatch dropped", shell.replace("WebAdbShellPage(open)", "X"), False), ("the old file back", shell, True)):
    red = bool(check(page, sh, o)); print(("  PASS: " if red else "  FAIL: ") + label + " turns red"); fails += 0 if red else 1
# #1682 P4 per-feature parity: every feature of the SuperApp's ADB Shell page (its AdbShellFragment: the four tabs, each a lib composable)
# is on the OWNER page (src/owner AdbOwnerSide) too, plus the owner's own: readiness + Connect + re-check, the Tester, the channel log and
# the lib's pairing / modes page. The SuperApp's fragment is read, so a tab added there without the owner page fails here.
ROOT = os.path.abspath(os.path.join(APP, ".."))
OWNER = rd(os.path.join(APP, "src-tauri/gen/android/app/src/owner/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt"))
FRAG = re.sub(r"//[^\n]*|/\*.*?\*/", "", rd(os.path.join(ROOT, "aa_cloud-superapp/app/src/owner/java/com/diegonmarcos/superapp/configs/AdbShellFragment.kt")), flags=re.S)
SA_TABS = re.findall(r"AdbCockpitModel\.Tab\.\w+ -> (\w+)\(\)", FRAG)
OWN_FEATURES = ["AdbReadiness.connect(", "AdbReadiness.check(ctx, fresh = true)", "ShellBroker.exec(ctx, ShellBroker.SELF, \"id -u\")", "ChannelLog.shared", "AdbShellPage()", "AdbReadiness.state.collectAsState()"]
def owner_bad(owner, tabs):
    miss = [t for t in tabs if t + "()" not in owner]
    miss += [f for f in OWN_FEATURES if f not in owner]
    if len(tabs) < 4: miss.append("the SuperApp fragment hosts fewer than 4 tabs (parsed %s)" % tabs)
    return miss
b = owner_bad(OWNER, SA_TABS)
print(("  PASS: " if not b else "  FAIL: ") + "P4 the owner page hosts every SuperApp ADB feature (%s + owner's own)" % ", ".join(SA_TABS) + ("" if not b else " -> " + ", ".join(b))); fails += 1 if b else 0
for f in SA_TABS + OWN_FEATURES:
    red = bool(owner_bad(OWNER.replace(f, "X"), SA_TABS)); print(("  PASS: " if red else "  FAIL: ") + "P4 dropping %s from the owner page turns red" % f); fails += 0 if red else 1
red = bool(owner_bad(OWNER, SA_TABS + ["AdbNewTab"])); print(("  PASS: " if red else "  FAIL: ") + "P4 a new SuperApp tab missing on the owner page turns red"); fails += 0 if red else 1
print("FAIL" if fails else "PASS: ADB Shell page parity"); sys.exit(1 if fails else 0)
PY
