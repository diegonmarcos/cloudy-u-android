#!/usr/bin/env bash
# #1693 Cloud Tasks types no CalDAV password and no mail-agent secret: Settings > Agent and the Configs CalDAV card take them from
# Cloud Account (App credentials) over the per-credential grant. Each mutation is planted in a scratch copy and must go RED.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
check() {
python3 - "$1" <<'PY'
import os, re, sys
R = sys.argv[1]; M = os.path.join(R, "ac_cloud-tasks/app/src/main")
rd = lambda p: open(os.path.join(M, p), encoding="utf-8").read()
sp, br, html = rd("java/com/diegonmarcos/cloudagenda/ui/SettingsPages.kt"), rd("java/com/diegonmarcos/cloudagenda/CalBridge.kt"), rd("assets/tasks.html")
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS  " if c else "  FAIL  ") + (good if c else bad)); fails += (not c)
ok("PasswordVisualTransformation(" not in sp and 'type="password"' not in html, "T1 no password field in Settings > Agent or the CalDAV card", "T1 a secret is typed in Tasks")
ok("AccountClient.credential(" in sp and "tasks.jmap_secret" in sp and "AccountClient.openCredentials(" in sp, "T2 the agent page takes the secret from Cloud Account", "T2 the agent page does not read Cloud Account")
ok("fun takeCaldavPassword" in br and "AccountClient.credential(" in br and 'CALDAV_CREDENTIAL = "tasks.caldav_password"' in br and "fun openCloudAccountCredentials" in br, "T3 the bridge takes the CalDAV password from Cloud Account", "T3 the bridge lost the Cloud Account read")
ok("caldav-take-btn" in html and "Api.takeCaldavPassword()" in html and "caldav-set-btn" in html and "password: document" not in html, "T4 the CalDAV card has Take / Set and never sends a typed password", "T4 the CalDAV card still handles a typed password")
ok(len(re.findall(r"takeCaldavPassword", html)) >= 4, "T5 the web bridge, its mock and the exempt list name the new methods", "T5 a wrapper is missing")
sys.exit(fails)
PY
}
echo "-- real tree --"; check "$ROOT"; REAL=$?
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT; M=0
mutate() {  # label file-under-main python-replace-old python-replace-new
  rm -rf "$TMP/r"; mkdir -p "$TMP/r/ac_cloud-tasks/app/src"; cp -r "$ROOT/ac_cloud-tasks/app/src/main" "$TMP/r/ac_cloud-tasks/app/src/"
  python3 - "$TMP/r/ac_cloud-tasks/app/src/main/$2" "$3" "$4" <<'PY'
import sys
p, a, b = sys.argv[1:4]; s = open(p, encoding="utf-8").read(); assert a in s, "mutation anchor missing: " + a; open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
  if check "$TMP/r" >/dev/null 2>&1; then echo "  NOT RED: $1"; M=1; else echo "  red  $1"; fi
}
echo "-- mutations --"
mutate "the CalDAV card types a password again" assets/tasks.html 'id="caldav-take-btn"' 'id="caldav-take-btn"></button><input type="password" id="x"'
mutate "the agent page types a secret again" java/com/diegonmarcos/cloudagenda/ui/SettingsPages.kt 'Take it from Cloud Account' 'PasswordVisualTransformation()'
mutate "the agent page stops reading" java/com/diegonmarcos/cloudagenda/ui/SettingsPages.kt 'AccountClient.credential(' 'Other.credential('
mutate "the bridge read is dropped" java/com/diegonmarcos/cloudagenda/CalBridge.kt 'fun takeCaldavPassword' 'fun takeNothing'
mutate "the card sends a typed password" assets/tasks.html "username: document.getElementById('caldav-username').value.trim()" "username: document.getElementById('caldav-username').value.trim(), password: document.getElementById('x').value"
[ "$REAL" = 0 ] && [ "$M" = 0 ] && { echo "OK"; exit 0; }; echo "RED"; exit 1
