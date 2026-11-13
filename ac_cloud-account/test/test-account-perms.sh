#!/usr/bin/env bash
# test-account-perms: Setup ▸ perms and PermsPlan (account redesign spec 4.9, section 10 task 6).
# Static, no build, no network. The verb table is DATA in build.json and read, never written in Kotlin;
# every shell write is followed by a re-read that alone may say GRANTED; a row the shell cannot grant
# says "needs the user" (never a skip, never a success); the profile's perms topic captures all four
# categories. Checks the real tree, then plants each mutation in a scratch copy and requires RED.
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
PROF="ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"

check() {
python3 - "$1" "$PROF" <<'PY'
import json, re, sys
R, PROF = sys.argv[1], sys.argv[2]
fails = 0
def rd(p): return open(f"{R}/{p}", encoding="utf-8").read()
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1

bj = json.loads(rd("ac_cloud-account/build.json"))
perms = bj.get("ui", {}).get("account", {}).get("perms", {})
verbs = perms.get("shell_verbs", {})
SPEC = {"runtime": "pm grant {pkg} {perm}", "appop": "appops set {pkg} {op} allow",
        "listener": "cmd notification allow_listener {component}", "role": "cmd role add-role-holder {role} {pkg}",
        "battery": "dumpsys deviceidle whitelist +{pkg}", "verifier": "PackageVerifier"}
ok(verbs == SPEC, "build.json ui.account.perms.shell_verbs = the spec 4.9 table", f"shell_verbs differ from spec 4.9: {verbs}")
ok(set(perms.get("needs_user", {})) >= {"device_admin", "accessibility"}, "needs_user declares device admin + accessibility", "needs_user lacks device_admin/accessibility")
ok(set(perms.get("appops", {})) >= {"SYSTEM_ALERT_WINDOW", "GET_USAGE_STATS", "REQUEST_INSTALL_PACKAGES", "ACTIVATE_VPN"}, "the four app ops are declared", "an app op is missing from the declaration")
ok(set(perms.get("roles", {})) == {"dialer", "sms", "browser", "home"}, "the four roles are declared", "roles declaration wrong")

pp = rd(f"{PROF}/PermsPlan.kt")
code = "\n".join(l for l in pp.splitlines() if not l.strip().startswith(("*", "/**", "//")))
hard = [v.split(" {")[0] for k, v in SPEC.items() if k != "verifier"] + ["allow_listener", "add-role-holder", "deviceidle whitelist"]
leak = [h for h in hard if h in code]
ok(not leak, "no shell verb is written in PermsPlan.kt", f"verb hardcoded in Kotlin: {leak}")
ok("BuildConfig.UI_ACCOUNT_B64" in pp and 'optJSONObject("shell_verbs")' in pp and "decl.verbs[item.kind.id]" in pp,
   "verbs read from BuildConfig.UI_ACCOUNT_B64 shell_verbs", "verbs not read from the declaration")
ok("fun plan(ctx: Context, profile: JSONObject?): Plan" in pp and "fun summary(): String" in pp
   and "fun apply(ctx: Context, plan: Plan, channel: ShellChannel?): Outcome" in pp,
   "public surface plan / summary / apply as documented", "public surface signature changed")
ok("PUBLIC SURFACE" in pp, "signatures documented at the top", "signatures not documented")

m = re.search(r"fun applyOne\(.*?\n    }\n", pp, re.S)
body = m.group(0) if m else ""
ex = body.find("channel.exec(")
rr = body.find("val after = read(ctx, item, channel)", ex)
ok(ex >= 0 and rr > ex and body.count("Status.GRANTED") == body.count("if (after == true) Line(item, Status.GRANTED"),
   "every shell write is re-read and only the re-read says GRANTED", "a write is not re-read before GRANTED")
ok("if (item.kind == Kind.USER) return Line(item, Status.NEEDS_USER," in body, "device admin / accessibility = NEEDS_USER", "a needs-the-user row is skipped or faked")
ok("if (channel == null) return Line(item, Status.NO_CHANNEL" in body, "no channel = NO_CHANNEL, named", "no channel falls through")
ok("it.status == Status.GRANTED || it.status == Status.ALREADY }" in pp, "Outcome.ok counts only GRANTED / ALREADY", "Outcome.ok counts a skip as success")
ok("fun apply(ctx: Context, plan: Plan, channel: ShellChannel?): Outcome = Outcome(plan.todo.map { applyOne(ctx, it, channel) })" in pp,
   "apply = applyOne over every todo item (none dropped)", "apply drops or bypasses items")

cap = re.search(r"fun capture\(.*?\n    }\n", pp, re.S)
cb = cap.group(0) if cap else ""
for k in ("granted", "appops", "roles", "battery"):
    ok(f'.put("{k}", {k})' in cb, f"capture records {k}", f"capture misses the {k} category")
dp = rd(f"{PROF}/DeviceProfile.kt")
ok("PermsPlan.capture(ctx" in dp and "build(device, apps, settings, cls, perms = perms)" in dp, "DeviceProfile.capture fills perms from PermsPlan", "DeviceProfile.capture leaves perms empty")

pg = rd(f"{PROF}/PermsPage.kt")
ok("PermsPlan.apply(ctx, p, ch)" in pg and "GRANT_ALL" in pg, "page: Grant all runs PermsPlan.apply", "page has no Grant all")
ok('ShellClient.openArmPage(ctx)' in pg and 'NO_CHANNEL' in pg, "page: no channel offers the owner's ADB Shell page", "page: no channel is not offered the owner's ADB Shell page")
ok("PermsPlan.settingsIntent(item)" in pg and "needs the user" in pg, "page: needs-the-user rows open Settings", "page: needs-the-user rows do not open Settings")
ok("PermsPlan.applyOne(ctx, i, channel)" in pg, "page: per-row Grant", "page: no per-row Grant")

ma = rd("ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt")
ok('"perms" -> AccountPermsPage(go)' in ma, "MainActivity mounts Setup ▸ perms", "Setup ▸ perms not mounted")
api = rd(f"{PROF}/AccountDebugApi.kt")
ok('Op("perms", "dry=1|run=1"' in api and '"perms" -> perms(ctx, q)' in api, "debug op /api/account/perms", "no perms debug op")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
FILES="ac_cloud-account/build.json ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt
$PROF/PermsPlan.kt $PROF/PermsPage.kt $PROF/DeviceProfile.kt $PROF/AccountDebugApi.kt"
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  for x in $FILES; do mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; done
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation red: $label"; return 0
}

echo "-- mutations --"
MUT=0
mutate "verb table hardcoded" "$PROF/PermsPlan.kt" \
  'val verb = decl.verbs[item.kind.id] ?: return' \
  'val verb = mapOf("runtime" to "pm grant {pkg} {perm}")[item.kind.id] ?: return' || MUT=1
mutate "verb table dropped from build.json" "ac_cloud-account/build.json" \
  '"battery": "dumpsys deviceidle whitelist +{pkg}",' '' || MUT=1
mutate "write not re-read" "$PROF/PermsPlan.kt" \
  'val out = runCatching { channel.exec(ctx, "$cmd 2>&1") }.getOrNull()
        val after = read(ctx, item, channel)' \
  'val out = runCatching { channel.exec(ctx, "$cmd 2>&1") }.getOrNull()
        val after = true' || MUT=1
mutate "needs the user turned into a silent skip" "$PROF/PermsPlan.kt" \
  'if (item.kind == Kind.USER) return Line(item, Status.NEEDS_USER,' \
  'if (item.kind == Kind.USER) return Line(item, Status.ALREADY,' || MUT=1
mutate "needs the user counted as ok" "$PROF/PermsPlan.kt" \
  'it.status == Status.GRANTED || it.status == Status.ALREADY }' \
  'it.status != Status.FAILED }' || MUT=1
mutate "capture missing roles" "$PROF/PermsPlan.kt" '.put("roles", roles)' '' || MUT=1
mutate "capture missing battery" "$PROF/PermsPlan.kt" '.put("battery", battery)' '' || MUT=1
mutate "DeviceProfile perms left empty" "$PROF/DeviceProfile.kt" 'build(device, apps, settings, cls, perms = perms)' 'build(device, apps, settings, cls)' || MUT=1
mutate "page not mounted" "ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt" '"perms" -> AccountPermsPage(go)' '"perms" -> AccountPlaceholderPage(section, page, "task 6")' || MUT=1
mutate "no-channel row offers nothing" "$PROF/PermsPage.kt" 'ShellClient.openArmPage(ctx)' '' || MUT=1

[ "$REAL" -eq 0 ] && [ "$MUT" -eq 0 ] && { echo "PASS"; exit 0; }
echo "FAIL (real=$REAL mutations=$MUT)"; exit 1
