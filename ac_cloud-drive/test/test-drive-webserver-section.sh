#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ Header Settings button (Profile / Configs) and Configs ▸ Webserver       ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The owner asked for a round Settings button at the top right of the header, a dropdown of
# Profile and Configs, and Configs reordered General, Webserver, Backups, Others with a new
# Webserver section that opens cloud-c3-webserver. This pins the structure, not the look (the
# look is DriveScreenshotTest's PNG at the owner's A37 density):
#
#   W1  ui.top_bar (#1149) declares the hamburger (drawer), the inline actions and [account, configs]
#       (libs:bottomnav's bar: AI sparkles, Profile avatar, Configs three dots, no Settings dropdown),
#       and the shell draws it with NavBarTopFirstRowScaffold, the fleet's rememberAccountLauncher, an
#       onConfigs that selects the Configs tab, an onAi that selects the AI tab and a TopBarDrawer
#       side menu - no copy of the bar and no cloudaccount literal of its own. Profile is the
#       account label.
#   W2  ui.sections[configs].pages is general, webserver, backups, others - in that order -
#       and ConfigsScreen dispatches "webserver" to WebserverPage.
#   W3  ui.webserver resolves: its fleet id is in the constellation manifest, the activity is the
#       app's launcher activity, and the fallback URL's port is ac_cloud-c3-webserver's
#       build.json::runtime.port (one port, declared where the server is).
#   W4  the launch is an EXPLICIT component intent (setComponent) with no implicit action, and
#       the page offers the localhost fallback and the "Install c3-webserver" note when absent.
#   W5  the A37 screenshot test exists: 384x832dp at 450dpi, writes the PNG CI uploads.
#   M   mutation-proved: each of W1-W4 goes RED on a copy with the property removed.
#
# OWN-SOURCE ONLY. python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
BJ="$APP/build.json"
SHELL_KT="$SRC/ui/DriveShell.kt"
CONFIGS_KT="$SRC/configs/ConfigsScreen.kt"
PAGE_KT="$SRC/configs/WebserverPage.kt"
SHOT="$APP/app/src/test/java/com/diegonmarcos/clouddrive/ui/DriveScreenshotTest.kt"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"
WS_BJ="$ROOT/ac_cloud-c3-webserver/build.json"
WS_MANIFEST="$ROOT/ac_cloud-c3-webserver/src-tauri/gen/android/app/src/main/AndroidManifest.xml"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$SHELL_KT" "$CONFIGS_KT" "$PAGE_KT" "$SHOT" "$FLEET" "$WS_BJ" "$WS_MANIFEST"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required - this tester is unrun, not passing"; exit 1; }
done

# w1 <build.json> <DriveShell.kt>
w1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
ui = json.load(open(sys.argv[1]))["ui"]
shell = open(sys.argv[2], encoding="utf-8").read()
bad = 0
tb = ui.get("top_bar") or {}
if tb != {"drawer": True, "inline_actions": True, "settings": ["account", "configs"]}:
    print("    ui.top_bar is %s, not {drawer, inline_actions, settings: [account, configs]}" % tb); bad = 1
for needle in ("NavBarTopFirstRowScaffold(", "rememberAccountLauncher()", "onConfigs = {", "onAi = {", "TopBarDrawer {", "R.string.top_bar_profile"):
    if needle not in shell:
        print("    DriveShell lacks %s" % needle); bad = 1
if not re.search(r'onConfigs = \{.*selected = ', shell):
    print("    onConfigs does not select the Configs tab"); bad = 1
if re.search(r'cloudaccount|Icons\.\w+\.Menu', shell):
    print("    DriveShell draws its own account launch or hamburger"); bad = 1
sys.exit(bad)
PYTHON
}

# w2 <build.json> <ConfigsScreen.kt>
w2() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
ui = json.load(open(sys.argv[1]))["ui"]
cfg = next(s for s in ui["sections"] if s["id"] == "configs")["pages"]
ids = [p["id"] for p in cfg]
bad = 0
if ids != ["general", "perms", "about", "drive", "git", "fsync", "mounts", "webserver"]:
    print("    Configs pages are %s, not general/git/fsync/mounts/webserver (#1216)" % ids); bad = 1
if '"webserver" -> WebserverPage(' not in open(sys.argv[2], encoding="utf-8").read():
    print("    ConfigsScreen does not dispatch webserver to WebserverPage"); bad = 1
sys.exit(bad)
PYTHON
}

# w3 <build.json> <fleet.json> <c3-webserver build.json> <c3-webserver manifest>
w3() {
    python3 - "$1" "$2" "$3" "$4" <<'PYTHON'
import json, re, sys
ws = json.load(open(sys.argv[1]))["ui"].get("webserver") or {}
fleet = {a["id"]: a for a in json.load(open(sys.argv[2]))["apps"]}
srv = json.load(open(sys.argv[3]))
manifest = open(sys.argv[4], encoding="utf-8").read()
bad = 0
if ws.get("fleet") not in fleet:
    print("    ui.webserver.fleet %r is not in the constellation manifest" % ws.get("fleet")); bad = 1
elif fleet[ws["fleet"]]["package"] != srv["android"]["application_id"]:
    print("    the fleet package differs from c3-webserver's application_id"); bad = 1
act = ws.get("activity", "")
if not act or 'android:name="%s"' % act not in manifest:
    print("    activity %r is not declared by c3-webserver's manifest" % act); bad = 1
m = re.fullmatch(r"http://localhost:(\d+)/?", ws.get("fallback_url", ""))
if not m or int(m.group(1)) != srv["runtime"]["port"]:
    print("    fallback_url %r does not name runtime.port %s" % (ws.get("fallback_url"), srv["runtime"]["port"])); bad = 1
sys.exit(bad)
PYTHON
}

# w4 <WebserverPage.kt>
w4() {
    python3 - "$1" <<'PYTHON'
import sys
t = open(sys.argv[1], encoding="utf-8").read()
bad = 0
for needle in (".setComponent(", "actions.openUrl(decl.fallbackUrl)", "R.string.configs_webserver_install", "R.string.configs_webserver_localhost"):
    if needle not in t:
        print("    WebserverPage lacks %s" % needle); bad = 1
if "Intent(Intent.ACTION_VIEW" in t or "getLaunchIntentForPackage" in t:
    print("    the launch is not an explicit component intent"); bad = 1
sys.exit(bad)
PYTHON
}

w5() {
    grep -q 'qualifiers = "w384dp-h832dp-450dpi"' "$1" && grep -q 'configs-webserver@a37.png' "$1" && grep -q 'build/drive-screens' "$1"
}

w1 "$BJ" "$SHELL_KT" && pass "W1 the shell draws the fleet's bar: hamburger menu, AI, Profile, Configs" || fail "W1 the header is not the fleet bar with hamburger, AI, Profile and Configs"
w2 "$BJ" "$CONFIGS_KT" && pass "W2 Configs reads General, Webserver, Backups, Others" || fail "W2 Configs sections are not in the owner's order"
w3 "$BJ" "$FLEET" "$WS_BJ" "$WS_MANIFEST" && pass "W3 ui.webserver resolves to c3-webserver's package, activity and port" || fail "W3 ui.webserver does not resolve"
w4 "$PAGE_KT" && pass "W4 explicit launch with the localhost and install-note fallbacks" || fail "W4 the Webserver launch is not explicit or lacks its fallback"
w5 "$SHOT" && pass "W5 the A37 screenshot test writes the PNG" || fail "W5 the A37 screenshot test is missing"

echo
echo "── mutation proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); p=b["ui"]["sections"]; c=next(s for s in p if s["id"]=="configs"); c["pages"].reverse(); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/rev.json"
w2 "$TMP/rev.json" "$CONFIGS_KT" >/dev/null && fail "M: reversed Configs order passed W2" || pass "M: reordered Configs → W2 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["webserver"]["fallback_url"]="http://localhost:9"; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/port.json"
w3 "$TMP/port.json" "$FLEET" "$WS_BJ" "$WS_MANIFEST" >/dev/null && fail "M: a wrong port passed W3" || pass "M: fallback port off runtime.port → W3 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["top_bar"]["settings"]=["configs"]; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/nobar.json"
w1 "$TMP/nobar.json" "$SHELL_KT" >/dev/null && fail "M: a bar without Profile passed W1" || pass "M: bar without Profile → W1 RED"
sed 's/\.setComponent(/.setPackage(/' "$PAGE_KT" > "$TMP/implicit.kt"
w4 "$TMP/implicit.kt" >/dev/null && fail "M: a non-explicit launch passed W4" || pass "M: launch without a component → W4 RED"
w1 "$BJ" "$SHELL_KT" && w2 "$BJ" "$CONFIGS_KT" && w3 "$BJ" "$FLEET" "$WS_BJ" "$WS_MANIFEST" && w4 "$PAGE_KT" && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-webserver-section: all checks passed"; else echo "test-drive-webserver-section: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
