#!/usr/bin/env bash
# #1682 THE ONE ADB PAGE IS THE OWNER'S (Cloud C3 Webserver). Static: no build, no device, no network.
#   O1  no first-party source outside the owner side opens the SuperApp's own ADB page (in-app route config/adb-shell|adb-status,
#       Target.InApp config/adb-shell, Account's go(setup, adb-shell), the lib's AdbShellActivity), except the listed
#       offenders: a SHRINKING RATCHET (1_cicd/src/data/adb-shell-one-place.json::owner_page.offenders), never an allow-forever
#   O2  every listed offender still matches (a fixed file must be deleted from the list: the list only shrinks)
#   O4  the owner app hosts the one page (AdbShellBrokerPage in AdbOwnerSide)
#   O3  Store code that opens the ADB page does it through the client door to the owner (ShellClient.openArmPage / AdbShellLink.open)
#       and not through any forbidden route; the wire's BROKER_PKG is the owner app's application id
# Then each violation is planted in a scratch copy and must turn a check red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }

check() {  # check <root> [--files]
python3 - "$1" "${2:-}" <<'PY'
import json, os, re, sys
R, mode = sys.argv[1], sys.argv[2]
D = json.load(open(f"{R}/1_cicd/src/data/adb-shell-one-place.json"))["owner_page"]
SKIP = {".git", "build", "node_modules", "z_archive", "test", "tests", "androidTest", "intermediates", ".gradle"}
COMMENT = re.compile(r"//[^\n]*|/\*.*?\*/", re.S)
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad)); fails += 0 if c else 1
def code(p):
    return COMMENT.sub("", open(os.path.join(R, p), encoding="utf-8", errors="replace").read())
rx = {k: re.compile(v) for k, v in D["forbidden"].items()}
dirs, named = D["owner_side"]["dirs"], set(D["owner_side"]["files_named"])
def owner(p): return any(p.startswith(d) for d in dirs) or os.path.basename(p) in named
sources = []
for root, ds, fs in os.walk(R):
    ds[:] = [d for d in ds if d not in SKIP]
    for f in fs:
        if f.endswith((".kt", ".java")): sources.append(os.path.relpath(os.path.join(root, f), R))
hits = {}
for p in sources:
    if owner(p): continue
    t = code(p); h = [k for k, r in rx.items() if r.search(t)]
    if h: hits[p] = h
off = {k: v for k, v in D["offenders"].items()}
new = {p: h for p, h in hits.items() if p not in off}
ok(not new, f"no source outside the owner side opens the SuperApp's own ADB page ({len(hits)} ratcheted offenders remain)",
   "opens the SuperApp's own ADB page instead of the owner's (AdbShellLink.open): " + "; ".join(f"{p} {h}" for p, h in sorted(new.items())))
stale = sorted(p for p in off if p not in hits)
ok(not stale, f"every ratcheted offender still matches ({len(off)} entries)", "stale ratchet entries (fixed: delete them from owner_page.offenders): " + ", ".join(stale))
ok(all(isinstance(v, str) and len(v) > 20 for v in off.values()), "every offender says why and what removes it", "an offender has no reason")
S = D["store_must_use"]
for f in S["files"]:
    t = code(f)
    ok(any(c in t for c in S["calls"]) and not any(r.search(t) for r in rx.values()), f"{os.path.basename(f)} opens the ADB page through the owner door", f"{f} does not open the owner's ADB page through {S['calls']} (or names a forbidden route)")
w = S["owner_package"]
pk = re.search(r'BROKER_PKG\s*=\s*"([^"]+)"', code(w["wire"]))
aid = json.load(open(f"{R}/{w['build_json']}"))["android"]["application_id"]
ok(pk and pk.group(1) == aid, f"the client door targets the owner app ({aid})", f"ShellWire.BROKER_PKG {pk and pk.group(1)} is not the owner's application id {aid}")
OH = json.load(open(f"{R}/1_cicd/src/data/adb-shell-one-place.json"))["owner_host"]
ok(os.path.isfile(os.path.join(R, OH["file"])) and OH["has"] in code(OH["file"]), f"{OH['app']} (the ADB owner) hosts the one page ({OH['has']})", f"{OH['app']} no longer hosts the ADB Shell page ({OH['file']} lacks {OH['has']})")
if mode == "--files":
    keep = set(off) | set(S["files"]) | {w["wire"], w["build_json"], OH["file"], "1_cicd/src/data/adb-shell-one-place.json"}
    keep |= {p for p in sources if owner(p) and any(r.search(code(p)) for r in rx.values())}
    print("\n".join(sorted(keep)), file=sys.stderr)
sys.exit(fails)
PY
}
echo "-- real tree --"
check "$ROOT"; REAL=$?
FILES="$(check "$ROOT" --files 2>&1 >/dev/null)"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>  (empty <old> appends)
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  while IFS= read -r x; do [ -f "$ROOT/$x" ] && { mkdir -p "$d/$(dirname "$x")"; cp "$ROOT/$x" "$d/$x"; }; done <<<"$FILES"
  python3 - "$d/$f" "$old" "$new" <<'PY2' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import os, sys
p, old, new = sys.argv[1:4]
os.makedirs(os.path.dirname(p), exist_ok=True)
s = open(p, encoding='utf-8').read() if os.path.exists(p) else ''
if old and old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1) if old else s + new)
PY2
  if check "$d" >/dev/null 2>&1; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
J=1_cicd/src/data/adb-shell-one-place.json
ST=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreShell.kt
M=0
mutate "the Store opens the SuperApp's in-app page" $ST '' $'\nfun z(s: android.app.Activity) { s.openSectionPage("config", "adb-shell") }\n' || M=$((M+1))
mutate "the Store opens the lib's AdbShellActivity" $ST '' $'\nval q = com.diegonmarcos.superapp.adbdebug.AdbShellActivity::class\nfun y(c: android.content.Context) { com.diegonmarcos.superapp.adbdebug.AdbShellActivity.open(c) }\n' || M=$((M+1))
mutate "the Store stops using the owner door" $ST 'ShellClient.openArmPage(' 'Unit.hashCode(' || M=$((M+1))
mutate "a new offender is not on the ratchet" ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/ConfigsPage.kt '' $'\nfun w(a: Any) { go("setup", "adb-shell") }\n' || M=$((M+1))
mutate "a ratchet entry is stale (listed file does not open the SuperApp page)" $J '"offenders": {}' '"offenders": {"ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreShell.kt": "planted: this file never opens the SuperApp page, so the entry is stale"}' || M=$((M+1))
mutate "the owner stops hosting the page" ac_cloud-c3-webserver/src-tauri/gen/android/app/src/owner/java/com/diegonmarcos/cloudwebserver/owner/AdbOwnerSide.kt 'AdbShellBrokerPage(' 'AdbShellBrokerPag(' || M=$((M+1))
mutate "the wire targets another app" ab_cloud-libs-shared/libs/shell-client/src/main/java/com/diegonmarcos/superapp/shellclient/ShellWire.kt '"com.diegonmarcos.cloudwebserver"' '"com.diegonmarcos.superapp"' || M=$((M+1))
echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
