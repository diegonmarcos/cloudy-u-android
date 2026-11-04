#!/usr/bin/env bash
# #1313 stale temp pack cleanup (ui.sync.git.stale_pack_cleanup). Gradle proves it compiles and runs StalePackCleanerTest;
# this proves the declaration and the safety rails stay, each proven red by a mutation.
#   S1 DECLARED: enabled, min_age_hours >= 1, the five patterns, none able to name a real pack-*, a _doc.
#   S2 SAFE: the cleaner never matches pack-*, only regular non-symlink files, only older than the age gate, never while a
#      job runs; the cld.termux script has the same rails (find -type f, ! -name 'pack-*', -mmin).
#   S3 WIRED: runs on IO, at app start (MainActivity), before a bulk pull and before the scheduled worker cycle; the
#      coordinator's busy test covers per-repo ops, clones, depth/bare runs and bulk pulls; says nothing when nothing went.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"; BJ="$APP/build.json"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
CLEAN="$APP/../ab_cloud-libs-shared/libs/git-repos/src/main/java/com/diegonmarcos/cloudlib/gitrepos/StalePackCleaner.kt"; COORD="$SRC/sync/GitSyncCoordinator.kt"; MAIN="$SRC/MainActivity.kt"; WORKER="$SRC/GitSyncWorker.kt"
TESTS="$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/StalePackCleanerTest.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

s1() { python3 - "$1" <<'PY'
import fnmatch, json, sys
g = json.load(open(sys.argv[1]))["ui"]["sync"]["git"]
c = g.get("stale_pack_cleanup"); bad = 0
if not isinstance(c, dict): print("    no stale_pack_cleanup block"); sys.exit(1)
if c.get("enabled") is not True: print("    not enabled"); bad = 1
if not isinstance(c.get("min_age_hours"), int) or c["min_age_hours"] < 1: print("    min_age_hours must be an int >= 1"); bad = 1
pats = c.get("patterns") or []
for want in ("incoming_*.pack", "incoming_*.idx", "tmp_pack_*", "tmp_idx_*", "tmp_rev_*"):
    if want not in pats: print("    patterns lack", want); bad = 1
for p in pats:
    if p.startswith("pack-") or p.startswith("*") or "/" in p or any(fnmatch.fnmatch(n, p) for n in ("pack-0a1b.pack", "pack-0a1b.idx", "pack-0a1b.rev", "pack-0a1b.keep")):
        print("    a pattern could name a real pack:", p); bad = 1
if len(g.get("_doc_stale_pack_cleanup", "")) < 100: print("    no _doc_stale_pack_cleanup"); bad = 1
sys.exit(bad)
PY
}
s2() { for t in '!name.startsWith(PACK_PREFIX)' 'Files.isSymbolicLink(p)' 'LinkOption.NOFOLLOW_LINKS' 'f.lastModified() >= cutoff' 'if (jobRunning) return' "-type f ! -name 'pack-*'" '-mmin +' 'safePattern(it)'; do grep -qF -- "$t" "$1" || { echo "    cleaner lacks $t"; return 1; }; done; }
s3() { for t in 'Dispatchers.IO' 'StalePackCleaner.busy(' 'depthRun.value?.finished == false' 'bulkRunning()' 'sweepStalePacks(ownBulk = true)' 'cloning.value.size'; do grep -qF -- "$t" "$1" || { echo "    coordinator lacks $t"; return 1; }; done
    grep -qF 'made.sweepStalePacks()' "$2" || { echo "    app start does not sweep"; return 1; }
    grep -qF 'StalePackCleaner.sweep(' "$3" || { echo "    the worker cycle does not sweep"; return 1; }
    grep -qF 'if (line.isNotEmpty())' "$1" || { echo "    the coordinator reports even when nothing was cleaned"; return 1; }
    local body; body="$(sed -n '/fun sweepStalePacks(/,/^    }$/p' "$1")"
    case "$body" in *Dispatchers.Main*) echo "    a sweep touches Main"; return 1;; esac; }

echo "── S1 declared ──"; s1 "$BJ" && pass "block declared, patterns safe, documented" || fail "declaration"
echo "── S2 safe ──";     s2 "$CLEAN" && pass "pack-* excluded, regular non-symlink, age gate, job gate, find rails" || fail "safety rails"
echo "── S3 wired ──";    s3 "$COORD" "$MAIN" "$WORKER" && pass "IO, app start, before bulk pull and worker cycle, silent when empty" || fail "wiring"
grep -q '@Test' "$TESTS" && pass "JVM StalePackCleanerTest present" || fail "missing JVM test"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mut() { # name file-var sed-expr checker...
    local n="$1" f="$2" e="$3"; shift 3; sed "$e" "$f" > "$TMP/m.kt"; "$@" "$TMP/m.kt" >/dev/null 2>&1 && fail "$n passed (vacuous)" || pass "$n → RED"; }
mut "pack-* guard removed"     "$CLEAN" 's/!name.startsWith(PACK_PREFIX)/true/' s2
mut "symlink check removed"    "$CLEAN" 's/Files.isSymbolicLink(p)/false/' s2
mut "age gate removed"         "$CLEAN" 's/f.lastModified() >= cutoff/false/' s2
mut "job gate removed"         "$CLEAN" 's/if (jobRunning) return/if (false) return/' s2
mut "find pack guard removed"  "$CLEAN" "s/! -name 'pack-\*'//" s2
sed 's/StalePackCleaner.busy(/other.busy(/' "$COORD" > "$TMP/c.kt"; s3 "$TMP/c.kt" "$MAIN" "$WORKER" >/dev/null 2>&1 && fail "busy check removed passed (vacuous)" || pass "busy check removed → RED"
sed 's/made.sweepStalePacks()/made/' "$MAIN" > "$TMP/mn.kt"; s3 "$COORD" "$TMP/mn.kt" "$WORKER" >/dev/null 2>&1 && fail "app-start sweep removed passed (vacuous)" || pass "app-start sweep removed → RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["stale_pack_cleanup"]["patterns"].append("pack-*"); json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b.json"
s1 "$TMP/b.json" >/dev/null 2>&1 && fail "pack-* pattern passed (vacuous)" || pass "pack-* pattern declared → RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sync"]["git"]["stale_pack_cleanup"]["min_age_hours"]=0; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/b0.json"
s1 "$TMP/b0.json" >/dev/null 2>&1 && fail "age 0 passed (vacuous)" || pass "min_age_hours 0 → RED"
s1 "$BJ" >/dev/null && s2 "$CLEAN" >/dev/null && s3 "$COORD" "$MAIN" "$WORKER" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree went red"
echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-stale-packs: all checks passed"; else echo "test-drive-stale-packs: $FAILURES check(s) FAILED"; exit 1; fi
