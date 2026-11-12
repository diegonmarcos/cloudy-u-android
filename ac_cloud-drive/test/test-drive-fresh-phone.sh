#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #730 — a FRESH phone: no mesh, no prior state, cloud-drive + a terminal  ║
# ║ just installed. The store must seed and the terminal must mount IT.      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. On a freshly set-up phone ~/cloud-shared-store was an
# empty directory in the terminal while an older phone held every repository.
# Read from the code, three independent defects produce exactly that:
#
#   * cloud-termux bound the store at the HOST path $HOME/cloud-shared-store
#     while $HOME itself is bound as /root, so the guest's ~ never saw the bind —
#     only the empty mkdir made just before it;
#   * cloud-termux targets SDK 28 but asked only for All-Files-Access, which the
#     platform ignores below target 30: the legacy READ/WRITE grant was never
#     requested, so a fresh install could not read shared storage at all;
#   * cloud-drive's seed is scheduled by the same onCreate that sends the user to
#     the all-files toggle, so its first pass ran WITHOUT the grant, every clone
#     failed as a bare I/O error and the next attempt waited out a back-off.
#
# Each check is a function of its input files, so the MUT block below runs the
# SAME check on a copy with the defect planted back and requires it to go RED —
# after first proving the planted copy really differs from the original.
#
#   F1  the seed is scheduled on EVERY launch (first one included), and re-kicked
#       the moment the all-files grant arrives.
#   F2  without all-files access the seed attempts nothing, reports every declared
#       repository as needs-storage, and retries — before the migration touches
#       the store.
#   F3  #1679 NO repository is seeded: nothing is cloned without a user tap.
#   F4  the seed's network constraint is declared in build.json, not hardcoded.
#   F5  cloud-shared-store: cloud-termux makes ~/cloud-shared-store a REAL directory in
#       its home (seen as /root/cloud-shared-store through the $HOME bind) whatever the
#       storage grant, and cloud-nix links its ~/cloud-shared-store to that directory in
#       the owner's home (shared uid).
#   F6  without storage access neither terminal leaves ~/emulated as a link or an
#       empty mount point that reads as empty storage.
#   F7  cloud-termux asks for the grant its TARGET sdk needs.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
MAIN="$SRC/MainActivity.kt"
SEED="$SRC/StoreSeed.kt"
MANIFEST="$APP/data/drive-git-repos.json"
BUILD_JSON="$APP/build.json"
GRADLE="$APP/app/build.gradle"
ENTER="$ROOT/ac_cloud-termux/rootfs/enter.sh"
TACT="$ROOT/ac_cloud-termux/app/src/main/java/com/termux/app/TermuxActivity.java"
TMAN="$ROOT/ac_cloud-termux/app/src/main/AndroidManifest.xml"
TPROPS="$ROOT/ac_cloud-termux/gradle.properties"
BAKE="$ROOT/ac_cloud-nix-on-droid/app/src/main/cpp/bake_default_packages.py"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$MAIN" "$SEED" "$MANIFEST" "$BUILD_JSON" "$GRADLE" "$ENTER" "$TACT" "$TMAN" "$TPROPS" "$BAKE"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
SHARED_ROOT="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["storage"]["shared_root"])' "$ROOT/ac_cloud-termux/build.json")"
[ -n "$SHARED_ROOT" ] || { echo "ERROR ac_cloud-termux/build.json declares no storage.shared_root"; exit 1; }

# f1 <MainActivity.kt>
f1() {
    python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'override fun onCreate\(.*?\n    \}\n', s, re.S)
body = m.group(0) if m else ''
bad = []
# Top-level of onCreate (8 spaces), never under a condition: the first launch must schedule.
if not re.search(r'^        StoreSeedWorker\.schedule\(this\)$', body, re.M):
    bad.append('onCreate does not schedule the seed unconditionally')
r = re.search(r'override fun onResume\(\).*?\n    \}\n', s, re.S)
if not r or not re.search(r'if \(has && !hadStorageAccess\) StoreSeedWorker\.kick\(this\)', r.group(0)):
    bad.append('onResume does not re-kick the seed when the all-files grant arrives')
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f2 <StoreSeed.kt>
f2() {
    python3 - "$1" "${2:-$SRC/SeedReport.kt}" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'override fun doWork\(\).*?\n    \}\n', s, re.S)
body = m.group(0) if m else ''
gate = body.find('SeedReport.withoutStorage(Declarations.seedRepos.map { it.name }, Places.hasAllFilesAccess(applicationContext))')
mig = body.find('StoreMigration.migrate(')
bad = []
if gate < 0: bad.append('doWork has no all-files gate')
elif mig >= 0 and gate > mig: bad.append('the gate runs AFTER the migration has already touched the store')
else:
    tail = body[gate:gate + 600]
    # The retry is the worker's bounded budget (SeedPolicy), reached through conclude(); the three lines
    # below pin each link of it: the gate hands its report to conclude(), conclude maps RETRY to
    # Result.retry(), and an incomplete report (every repository NEEDS_STORAGE, which is resumable) is
    # RETRY until MAX_PASSES.
    if 'return conclude(blocked)' not in tail: bad.append('a pass without storage does not retry (it no longer ends in conclude(blocked))')
    if not re.search(r'SeedPolicy\.Next\.RETRY -> \{[^\n]*Result\.retry\(\) \}', s): bad.append('conclude() does not map RETRY to Result.retry()')
    rep = open(sys.argv[2]).read()
    if not re.search(r'report\.complete -> Next\.DONE\s+pass >= MAX_PASSES -> Next\.GIVE_UP\s+else -> Next\.RETRY', rep): bad.append('SeedPolicy.decide no longer retries an incomplete pass until the budget is spent')
    if not re.search(r'val complete: Boolean get\(\) = outcomes\.size == declared && resumable\.isEmpty\(\)', rep): bad.append('a report with unresolved repositories can read as complete')
    if not re.search(r'val RESUMABLE = setOf\([^)]*NEEDS_STORAGE', rep): bad.append('NEEDS_STORAGE is not a resumable outcome, so the retry would end the work')
    if 'reportFile(applicationContext).writeText(blocked.text())' not in tail: bad.append('a pass without storage is not persisted for the Git page')
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f3 <drive-git-repos.json> <StoreSeed.kt dir's Declarations.kt>
f3() {
    python3 - "$1" "$SRC/Declarations.kt" "$BUILD_JSON" <<'PY'
import json, re, sys
repos = json.load(open(sys.argv[1]))['repos']
decl = open(sys.argv[2]).read()
bad = []
if not re.search(r'val seedRepos: List<GitRepoDecl> get\(\) = gitFamily\.repos\.filter \{ it\.seed && !it\.private \}', decl):
    bad.append('Declarations.seedRepos is no longer "declared seed and not private"')
# The name families are the DECLARED ones (build.json::ui.sync.git.name_families), not a prefix typed here:
# the cloud-* / front-* names were renamed (cloudy-*, Projects-*), and a family list typed in this file
# is exactly what went stale.
fams = tuple(json.load(open(sys.argv[3]))['ui']['sync']['git']['name_families'])
pub = [r['name'] for r in repos if r.get('public') and not r.get('private') and r['name'].startswith(fams)]
if not pub: bad.append('the manifest declares no public repository in a declared name family at all')
# #1679 owner rule: nothing is cloned without a user tap, so NO repository is seeded (the public ones stay listed, clone on tap).
seeded = [r['name'] for r in repos if r.get('seed')]
if seeded: bad.append('#1679 repositories marked for an automatic clone: ' + ', '.join(seeded))
print('\n'.join('    ' + b for b in bad)); sys.exit(1 if bad else 0)
PY
}

# f4 <StoreSeed.kt> <build.json> <build.gradle>
f4() {
    local bad=0
    python3 -c 'import json,sys; v=json.load(open(sys.argv[1]))["storage"]["seed"].get("require_unmetered_network"); sys.exit(0 if isinstance(v,bool) else 1)' "$2" \
        || { echo "    build.json::storage.seed.require_unmetered_network is not declared"; bad=1; }
    grep -q 'buildConfigField "boolean", "SEED_REQUIRE_UNMETERED"' "$3" || { echo "    gradle does not bake SEED_REQUIRE_UNMETERED"; bad=1; }
    grep -q 'if (BuildConfig.SEED_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED' "$1" \
        || { echo "    the seed's network type is not read from the declaration"; bad=1; }
    grep -q 'setRequiredNetworkType(NetworkType.UNMETERED)' "$1" && { echo "    the seed still hardcodes UNMETERED"; bad=1; }
    return $bad
}

# f5 <enter.sh> <bake_default_packages.py>
# cloud-shared-store: the store is a real directory in cld.termux's home, made whatever the
# grant (the guest sees it through the $HOME bind), and nix links to that directory.
f5() {
    local bad=0
    grep -q -- '-b "$HOME:/root"' "$1" || { echo "    enter.sh no longer binds \$HOME as /root — the guest would not see the store"; bad=1; }
    grep -qxF 'CLOUD_STORE="$HOME/cloud-shared-store"' "$1" && grep -qF 'mkdir -p "$CLOUD_STORE"' "$1" \
        || { echo "    cloud-termux does not make ~/cloud-shared-store a real directory"; bad=1; }
    python3 - "$1" <<'PY' || bad=1
import re, sys
s = open(sys.argv[1]).read()
m = re.search(r'^if ls /storage/emulated/0 >/dev/null 2>&1; then\n(.*?)^fi\n', s, re.S | re.M)
if m and 'CLOUD_STORE' in m.group(0):
    print("    the store is made inside the storage-grant branch: it must not depend on the grant"); sys.exit(1)
PY
    grep -qF 'cloud_storage_link "$HOME/{store_dir}" "$CLOUD_STORE_OWNER"' "$2" \
        && grep -qF "CLOUD_STORE_OWNER=\"\${{HOME%/*/files/home}}/{owner}/files/home/{store_dir}\"" "$2" \
        || { echo "    cloud-nix does not link ~/cloud-shared-store to the owner's store"; bad=1; }
    return $bad
}

# f6 <enter.sh> <bake_default_packages.py>
f6() {
    python3 - "$1" "$2" <<'PY2'
import re, sys
enter, bake = open(sys.argv[1]).read(), open(sys.argv[2]).read()
bad = []
m = re.search(r'^if ls /storage/emulated/0 >/dev/null 2>&1; then\n(.*?)^else\n(.*?)^fi\n', enter, re.S | re.M)
if not m: bad.append('enter.sh has no readable/unreadable branch')
else:
    if re.search(r'^\s*(storage_link|mkdir -p) "\$HOME/emulated', enter[:m.start()], re.M):
        bad.append('enter.sh makes ~/emulated before knowing storage is readable')
    if 'storage_link "$HOME/emulated"' not in m.group(1): bad.append('enter.sh does not link ~/emulated when storage is readable')
    if 'storage_unlink "$HOME/emulated"' not in m.group(2): bad.append('cloud-termux leaves ~/emulated behind when storage is not granted')
b = re.search(r"^def storage_setup\(.*?^def ", bake, re.S | re.M)
if not b: bad.append('the nix bin/login storage block (storage_setup) is gone')
else:
    blk = b.group(0)
    iff, els = blk.find("'if [ -n \"$CLOUD_SDCARD\" ]; then\\n'"), blk.find("'else\\n'\n        f'  cloud_trace \"storage: ls")
    mk = blk.find('cloud_storage_link "$HOME/emulated"')
    if mk < 0 or iff < 0 or mk < iff: bad.append('cloud-nix links ~/emulated before knowing storage is readable')
    if els < 0 or 'cloud_storage_unlink "$HOME/emulated"' not in blk[els:]: bad.append('cloud-nix leaves ~/emulated behind when storage is not granted')
    if 'storage_setup(shared_root_name)' not in bake: bad.append('bin/login is not given storage_setup')
print('\n'.join('    ' + x for x in bad)); sys.exit(1 if bad else 0)
PY2
}

# f7 <TermuxActivity.java> <AndroidManifest.xml> <gradle.properties>
f7() {
    local bad=0 target
    target="$(sed -n 's/^targetSdkVersion=//p' "$3")"
    if [ "${target:-0}" -lt 30 ]; then
        grep -q 'getApplicationInfo().targetSdkVersion < Build.VERSION_CODES.R' "$1" || { echo "    TermuxActivity does not branch on its TARGET sdk ($target)"; bad=1; }
        grep -q 'String\[\] legacy = {Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE}' "$1" \
            || { echo "    TermuxActivity does not ask for the legacy READ+WRITE grant target $target needs"; bad=1; }
        grep -q 'requestPermissions(legacy, PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION)' "$1" || { echo "    the legacy grant is never requested"; bad=1; }
        grep -q 'android.permission.READ_EXTERNAL_STORAGE' "$2" || { echo "    the manifest does not declare READ_EXTERNAL_STORAGE, so it cannot be granted"; bad=1; }
    fi
    return $bad
}

echo "── #730 a fresh phone: the store seeds and the terminal mounts it ──"
run() { local id="$1" what="$2"; shift 2; local out; if out="$("$@" 2>&1)"; then pass "$id $what"; else fail "$id $what"; [ -n "$out" ] && echo "$out"; fi; }
run F1 "the seed is scheduled on first launch and re-kicked on the all-files grant" f1 "$MAIN"
run F2 "without all-files access the seed reports needs-storage for every repository and retries" f2 "$SEED"
run F3 "#1679 no repository of the manifest is seeded (clone only on a user tap)" f3 "$MANIFEST"
run F4 "the seed's network constraint is declared" f4 "$SEED" "$BUILD_JSON" "$GRADLE"
run F5 "cloud-termux makes ~/cloud-shared-store in its home (grant-independent) and cloud-nix links to it" f5 "$ENTER" "$BAKE"
run F6 "no ~/emulated link or empty mount point is left when storage is not granted" f6 "$ENTER" "$BAKE"
run F7 "cloud-termux asks for the grant its target sdk needs" f7 "$TACT" "$TMAN" "$TPROPS"

echo "── MUT: each check goes RED on its defect planted back ──"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
# mut <id> <src> <python-expr transforming s> <check> [extra args, MUTATED stands for the copy]
mut() {
    local id="$1" src="$2" expr="$3" check="$4"; shift 4
    local m="$T/$id.$(basename "$src")"
    python3 -c "import sys; s=open(sys.argv[1]).read(); s2=$expr; open(sys.argv[2],'w').write(s2)" "$src" "$m"
    if cmp -s "$src" "$m"; then fail "MUT $id: the mutation did not apply — the check is unproven"; return; fi
    local args=() a; for a in "$@"; do [ "$a" = MUTATED ] && args+=("$m") || args+=("$a"); done
    if "$check" "${args[@]}" >/dev/null 2>&1; then fail "MUT $id: still GREEN with the defect planted"; else pass "MUT $id goes RED"; fi
}
mut F1a "$MAIN" "s.replace('        StoreSeedWorker.schedule(this)\n', '        if (hadStorageAccess) StoreSeedWorker.schedule(this)\n')" f1 MUTATED
mut F1b "$MAIN" "s.replace('StoreSeedWorker.kick(this)', 'Unit')" f1 MUTATED
mut F2a "$SEED" "s.replace('return conclude(blocked)', 'return Result.success()', 1)" f2 MUTATED
mut F2b "$SEED" "s.replace('SeedReport.withoutStorage(', 'SeedReport.withoutStorageX(')" f2 MUTATED
# F3 un-seeds the first seeded public repository (whatever its current name) through the JSON itself (a duplicate key would lose to the original).
mut F3 "$MANIFEST" "(lambda j, d: ([r.update(seed=True) for r in [x for x in d['repos'] if x.get('public') and not x.get('private')][:1]], j.dumps(d, indent=1))[1])(__import__('json'), __import__('json').loads(s))" f3 MUTATED
mut F4 "$SEED" "s.replace('if (BuildConfig.SEED_REQUIRE_UNMETERED) NetworkType.UNMETERED else NetworkType.CONNECTED', 'NetworkType.UNMETERED')" f4 MUTATED "$BUILD_JSON" "$GRADLE"
mut F5a "$ENTER" "s.replace('mkdir -p \"\$CLOUD_STORE\" \"\$CLOUD_STORE_CFG\"', 'true')" f5 MUTATED "$BAKE"
mut F5b "$BAKE" "s.replace('cloud_storage_link \"\$HOME/{store_dir}\" \"\$CLOUD_STORE_OWNER\"', 'cloud_storage_link \"\$HOME/{store_dir}\" \"\$CLOUD_STORE_OWNER.old\"')" f5 "$ENTER" MUTATED
mut F6a "$ENTER" "s.replace('    storage_unlink \"\$HOME/emulated\"\n', '')" f6 MUTATED "$BAKE"
mut F6b "$BAKE" "s.replace(\"'  cloud_storage_unlink \\\"\$HOME/emulated\\\"\\\\n'\", \"''\")" f6 "$ENTER" MUTATED
mut F7a "$TACT" "s.replace('Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE', 'Manifest.permission.WRITE_EXTERNAL_STORAGE')" f7 MUTATED "$TMAN" "$TPROPS"
mut F7b "$TMAN" "s.replace('<uses-permission android:name=\"android.permission.READ_EXTERNAL_STORAGE\" />', '')" f7 "$TACT" MUTATED "$TPROPS"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS — test-drive-fresh-phone"; exit 0; fi
echo "FAIL — test-drive-fresh-phone: $FAILURES check(s)"; exit 1
