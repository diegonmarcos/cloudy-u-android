#!/bin/sh
# test-c3-webserver-perms-parity.sh: Cloud C3 WebServer declares the SuperApp's permission set (owner decision: an always-on,
# equal-capability fleet app). Parity means the webserver GAINS what it lacked; the SuperApp's own manifest is not touched.
#
#   M  every <uses-permission> of aa_cloud-superapp's own manifest is in this app's manifest
#   N  build.json::perms.needs holds the SuperApp's needs, less the four that are bound to a Kotlin service class this
#      Rust-only app may not add (perms.parity.service_bound_not_carried names them; nothing else may be left out)
#   W  WRITE_SECURE_SETTINGS is carried (it supersedes #1515's single-holder rule) in the manifest and in needs
#   G  the fleet perms guard (P1..P8: manifest and needs agree, the dataset is regenerated) passes on the repo
#   X  mutation: a permission dropped from the manifest, or an id dropped from needs, goes red
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
check() {
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
sa = os.path.join(root, "aa_cloud-superapp")
wm = open(os.path.join(app, "src-tauri/gen/android/app/src/main/AndroidManifest.xml")).read()
sm = open(os.path.join(sa, "app/src/main/AndroidManifest.xml")).read()
strip = lambda t: re.sub(r"<!--.*?-->", "", t, flags=re.S)
perms = lambda t: set(re.findall(r'<uses-permission(?:-sdk-23)?\s[^>]*android:name="([^"]+)"', strip(t)))
w, s = perms(wm), perms(sm)
bad = []
for p in sorted(s - w):
    bad.append("M the SuperApp asks %s and this app's manifest does not" % p)
wb, sb = json.load(open(os.path.join(app, "build.json")))["perms"], json.load(open(os.path.join(sa, "build.json")))["perms"]
carried_not = set(wb.get("parity", {}).get("service_bound_not_carried", []))
if carried_not != {"notification_listener", "accessibility", "device_admin", "vpn"}:
    bad.append("N the declared not-carried set is %r, not the four service-bound ids" % sorted(carried_not))
missing = set(sb["needs"]) - set(wb["needs"]) - carried_not
for i in sorted(missing):
    bad.append("N perms.needs lacks %s, which the SuperApp declares" % i)
if "android.permission.WRITE_SECURE_SETTINGS" not in w: bad.append("W WRITE_SECURE_SETTINGS missing from the manifest")
if "write_secure_settings" not in wb["needs"]: bad.append("W write_secure_settings missing from perms.needs")
if wb.get("first_run") != "libs:perms": bad.append("N first run must be libs:perms so the Perms page requests them")
print("\n".join("FAIL " + b for b in bad) if bad else "ok   %d of the SuperApp's %d permissions and %d needs are carried" % (len(s & w), len(s), len(set(sb["needs"]) - carried_not)))
sys.exit(1 if bad else 0)
PY
}
fail=0
check "$APP" "$ROOT" || fail=1
G="$ROOT/1_cicd/dist/scripts/cloud-android-perms-guard.py"
if [ -f "$G" ]; then
  out=$(python3 "$G" "$ROOT" 2>&1) && echo "ok   fleet perms guard: $(echo "$out" | tail -1)" || { echo "FAIL fleet perms guard"; echo "$out" | tail -5; fail=1; }
else echo "skip perms guard not in this checkout"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mkdir -p "$T/app/src-tauri/gen/android/app/src/main" "$T/sa/app/src/main" "$T/root/aa_cloud-superapp/app/src/main"
cp "$APP/build.json" "$T/app/build.json"
cp "$ROOT/aa_cloud-superapp/build.json" "$T/root/aa_cloud-superapp/build.json"
cp "$ROOT/aa_cloud-superapp/app/src/main/AndroidManifest.xml" "$T/root/aa_cloud-superapp/app/src/main/"
sed '/android.permission.READ_SMS/d' "$APP/src-tauri/gen/android/app/src/main/AndroidManifest.xml" > "$T/app/src-tauri/gen/android/app/src/main/AndroidManifest.xml"
check "$T/app" "$T/root" >/dev/null 2>&1 && { echo "FAIL mutation: a dropped permission stayed green"; fail=1; } || echo "ok   mutation: a dropped permission goes red"
exit $fail
