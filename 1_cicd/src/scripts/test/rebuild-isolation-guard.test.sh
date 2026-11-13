#!/usr/bin/env bash
# #870 rebuild-isolation guard: proves it goes red on each way isolation can break,
# then holds the real tree + every rebuild-scenarios.json scenario to it.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
G="$ROOT/1_cicd/src/scripts/cloud-android-rebuild-isolation-guard.py"
SC="$ROOT/1_cicd/src/data/rebuild-scenarios.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
cd "$ROOT"

mut() {  # mut <dir> <workflow> <extra path line>: add a watched path to a copy of the source workflows
    cp -r 1_cicd/src/cicd/. "$1/"
    python3 - "$1/$2" "$3" <<'PY'
import sys
p, extra = sys.argv[1:3]
t = open(p).read()
t = t.replace("      # ── end MANAGED-PATHS-HEADER ──\n", "      # ── end MANAGED-PATHS-HEADER ──\n      - \"%s\"\n" % extra, 1)
open(p, "w").write(t)
PY
}
red() {  # red <label> <workflow> <path> <guard args...>
    local label="$1" wf="$2" extra="$3"; shift 3
    local d; d="$(mktemp -d)"; mut "$d" "$wf" "$extra"
    if python3 "$G" --wf "$d" "$@" >/dev/null 2>&1; then fail "$label stayed green"; else ok "$label goes red"; fi
    rm -rf "$d"
}
red "an app watching an engine lib (drive -> libs/gh)"            ship-cloud-drive.yml  "ab_cloud-libs-shared/libs/gh/**"            static
red "an app watching a shared manifest"                           ship-cloud-news.yml   "aa_cloud-superapp/data/constellation-fleet.json" static
red "an app watching a static lib it does not compile"            ship-cloud-news.yml   "ab_cloud-libs-shared/libs/ui-kit/**"        static
red "an app watching another app's dir"                           ship-cloud-news.yml   "ac_cloud-mail/**"                           static
red "predicted rebuild of an app that owns no changed path (another app's dir edit fans to ship-cloud-myterminal.yml)" ship-cloud-myterminal.yml "ab_cloud-terminal-store/**" --files ab_cloud-terminal-store/x.kt

# a static lib must never be predicted as a lib APK, nor a lib change rebuild another lib
d="$(mktemp -d)"; cp -r 1_cicd/src/cicd/. "$d/"
cp ab_cloud-libs-shared/lib-apks/build.json "$d/.bj" 2>/dev/null
if python3 "$G" --wf "$d" --files ab_cloud-libs-shared/libs/news/src/main/java/x.kt >/dev/null; then ok "an engine lib change is isolated on the real tree"; else fail "an engine lib change violates isolation"; fi
rm -rf "$d"

out="$(python3 "$G" --files ab_cloud-libs-shared/libs/news/src/main/java/x.kt)"
grep -q "0 app(s)  | 1 lib APK(s) news" <<<"$out" || grep -Eq "predicted: 0 app\(s\) *\| 1 lib APK\(s\) news" <<<"$out" \
    && ok "libs/news -> exactly one lib APK, no app" || fail "libs/news did not predict exactly 'news' and no app: $out"
out="$(python3 "$G" --files ab_cloud-libs-shared/libs/account/src/main/java/x.kt)"
grep -Eq "predicted: 1 app\(s\) ac_cloud-account *\| 0 lib APK" <<<"$out" \
    && ok "libs/account (static) -> every app that compiles it (only Cloud Account since #1693), no lib APK" || fail "libs/account prediction wrong: $out"

python3 "$G" static && ok "the real workflows hold the static ownership rule" || fail "the real workflows watch something their app does not own"
python3 "$G" --scenarios "$SC" >/dev/null && ok "every rebuild scenario is isolated" || fail "a rebuild scenario violates isolation"
python3 "$ROOT/1_cicd/src/scripts/cloud-android-lib-apks-engines.py" check "$ROOT" && ok "lib_apks.exclude matches the engine discovery" || fail "lib_apks.exclude out of date: run build.sh workflow"

echo
[ "$FAILURES" -eq 0 ] && { echo "== RESULT(#870 rebuild isolation): held =="; exit 0; }
echo "== RESULT(#870 rebuild isolation): $FAILURES check(s) failed =="; exit 1
