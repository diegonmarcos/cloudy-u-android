#!/usr/bin/env bash
# test-account-nav-parity: field parity of Cloud Account against its pre-#1625 navigation (#1671).
#
# #1625 / #1626 built the contacts redesign (Groups | Business | All | People | Configs, a device ContactsContract
# source behind READ_CONTACTS) in this app by mistake; #1671 moved it to Cloud Contacts and restored this app. This
# tester pins the restored state to the exact tree of build.json::ui as it was before #1625 (commit cc0fc6d7d^):
#  1. the bottom nav, the default section and EVERY section / page / strip id, in order (nothing lost, nothing added);
#  2. no contacts section, no contacts screen source and no READ_CONTACTS in the manifest or perms.needs;
#  3. MainActivity routes no contacts screen.
# Then each mutation is planted in a scratch copy and the check must go RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
A="ac_cloud-account"

check() {
python3 - "$1" "$A" <<'PY'
import json, os, re, sys
root, A = sys.argv[1], sys.argv[2]
# build.json::ui of cc0fc6d7d^ (the last commit before #1625), as an id tree: [id, [children]] or a leaf id.
# #1693 one page ADDED (connect > auth > import-configs, Import configs moved here from the SuperApp); nothing removed.
WANT = json.loads('''{"bottom_nav": ["vault", "config", "connect", "devices", "activity"], "default": "vault", "sections": [["vault", ["ids", "cards", ["secrets", ["providers", "keys", "tokens", "grants", "import", "entries"]], ["autocomplete", ["personal", "sites", "snippets"]]]], ["config", [["android", ["cloud-apps", "phone-apps", "runbook"]], "desktop", ["c3", ["infra", "containers"]], "repos"]], ["connect", [["status", ["connections", "apps", "endpoints"]], ["auth", ["hub", "signin", "import-configs", "settings"]], ["fleetsetup", ["services", "drive", "push", "perms", "adb-shell"]]]], ["devices", ["mesh", "peer-control", ["backup", ["databases", "bundle", "me"]], "files", "working", "diff"]], ["activity", ["activity", "fleetdata"]], ["configs", ["general", "perms", "about"]]]}''')
fails = 0
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
rd = lambda p: open(os.path.join(root, p), encoding="utf-8").read()
d = json.loads(rd(f"{A}/build.json"))
ui = d["ui"]
def t(ps): return [[p["id"], t(p.get("pages") or [])] if p.get("pages") else p["id"] for p in ps]
got = {"bottom_nav": ui.get("bottom_nav"), "default": ui.get("default_section"), "sections": [[s["id"], t(s.get("pages") or [])] for s in ui.get("sections") or []]}
ok(got["bottom_nav"] == WANT["bottom_nav"] and got["default"] == WANT["default"],
   "bottom nav Vault | Config | Connect | Devices | Activity, default Vault (pre-#1625)", f"bottom nav {got['bottom_nav']} default {got['default']}")
ok(got["sections"] == WANT["sections"], "every section, page and strip id equals the pre-#1625 tree", f"the ui tree drifted from pre-#1625: {got['sections']}")
contacts_ids = {"groups", "business", "all", "people"}
ok(not contacts_ids & {s["id"] for s in ui["sections"]}, "no contacts section (they live in Cloud Contacts, #1671)", "a contacts section is back in Cloud Account")
mf = rd(f"{A}/app/src/main/AndroidManifest.xml")
ok("android.permission.READ_CONTACTS" not in mf and "contacts_read" not in (d.get("perms") or {}).get("needs", []),
   "no READ_CONTACTS in the manifest or perms.needs", "READ_CONTACTS / contacts_read is back in Cloud Account")
K = f"{A}/app/src/main/java/com/diegonmarcos/cloudaccount"
ok(not os.path.isdir(os.path.join(root, K, "contacts")), "no contacts/ screen sources", "contacts/ sources are back in Cloud Account")
ma = rd(f"{K}/MainActivity.kt")
ok("contacts." not in ma and "ContactsScreen" not in ma and '"groups" ->' not in ma, "MainActivity routes no contacts screen", "MainActivity still routes a contacts screen")
sys.exit(1 if fails else 0)
PY
}

echo "test-account-nav-parity"
check "$ROOT" || exit 1

mutate() {  # mutate <label> <file> <old> <new>
  tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' RETURN
  mkdir -p "$tmp/$A"; cp -r "$ROOT/$A/build.json" "$ROOT/$A/app" "$tmp/$A/" 2>/dev/null
  python3 - "$tmp/$2" "$3" "$4" <<'PY' || { echo "  mutation not planted: $1"; return 1; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if a not in s: sys.exit(1)
open(p, "w", encoding="utf-8").write(s.replace(a, b, 1))
PY
  if check "$tmp" >/dev/null 2>&1; then echo "  mutation NOT caught: $1"; return 1; fi
  echo "  mutation caught: $1"
}
M=0
mutate "contacts nav back" "$A/build.json" '"bottom_nav": ["vault", "config", "connect", "devices", "activity"]' '"bottom_nav": ["groups", "business", "all", "people", "configs"]' || M=1
mutate "a strip page lost" "$A/build.json" '"id": "phone-apps",' '"id": "phone-appsx",' || M=1
mutate "READ_CONTACTS back" "$A/app/src/main/AndroidManifest.xml" '<application' '<uses-permission android:name="android.permission.READ_CONTACTS" />
<application' || M=1
[ "$M" = 0 ] || exit 1
echo "test-account-nav-parity: OK"
