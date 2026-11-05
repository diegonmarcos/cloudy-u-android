#!/usr/bin/env bash
# lib-consumers-guard.test — prove the check FAILS when lib-consumers.json
# disagrees with the build.json files, and passes when it agrees. A check only ever watched
# passing is indistinguishable from `exit 0`.
#
# python3 and coreutils only; no network, no build.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-lib-consumers.py"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

python3 "$GUARD" check "$ROOT" >/dev/null 2>&1 && ok "the committed tree passes" || fail "the committed tree does not pass"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
# every file the generator can read, and nothing else: the tree's build inputs, not its sources
( cd "$ROOT" && find . \( -name .git -o -name z_archive -o -name build -o -name node_modules \) -prune -o -type f -print | sed 's|^\./||' \
    | grep -E '(\.(json|gradle|kts|patch)|/AndroidManifest\.xml|cloud-android-lib-consumers\.py|cloud_android_lib_closure\.py|cloud-android-lib-apks-engines\.py)$' \
    | grep -v '^z_archive/' | tar -cf - -T - 2>/dev/null ) | tar -xf - -C "$WORK"
chk() { python3 "$WORK/1_cicd/src/scripts/cloud-android-lib-consumers.py" check "$WORK" 2>&1; }

has() { local o; o="$(chk)"; grep -q "$1" <<<"$o"; }
chk >/dev/null && ok "the copy passes" || { fail "the copy does not pass: $(chk | head -3)"; }

# 1. an app starts compiling a lib it did not: the data file is stale
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ac_cloud-wallet/build.json"
d = json.load(open(p)); d.setdefault("modules", {})["libs:battery"] = {"dir": "../ab_cloud-libs-shared/libs/battery", "type": "library"}
json.dump(d, open(p, "w"))
PY
has "lib-consumers.json does not match" && ok "an app gaining a module makes the data file stale" || fail "a new module in an app's build.json went unnoticed"

# 2. regenerate in the copy: green again
python3 "$WORK/1_cicd/src/scripts/cloud-android-lib-consumers.py" write "$WORK"
chk >/dev/null && ok "regenerating the data file makes it pass" || fail "regeneration does not converge: $(chk | head -3)"

# 3. a non-engine lib loses its description
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ab_cloud-libs-shared/lib-apks/build.json"
d = json.load(open(p)); del d["lib_apks"]["static_libs"]["battery"]; json.dump(d, open(p, "w"))
PY
has "no description for battery" && ok "a lib without a description fails" || fail "a lib without a description passed"

# 4. a described lib is gone
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/ab_cloud-libs-shared/lib-apks/build.json"
d = json.load(open(p)); d["lib_apks"]["static_libs"]["battery"] = "x"; d["lib_apks"]["static_libs"]["ghost"] = "gone"; json.dump(d, open(p, "w"))
PY
has "describes ghost" && ok "a description for a lib that is gone fails" || fail "a stale description passed"

cp "$ROOT/ab_cloud-libs-shared/lib-apks/build.json" "$WORK/ab_cloud-libs-shared/lib-apks/"
# 5. an ML lib is listed with the non-ML ones (flag flipped): the data no longer matches the ml- rule
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/aa_cloud-superapp/data/lib-consumers.json"
d = json.load(open(p)); next(e for e in d["build_time"] if e["id"] == "ml-l-image")["ml"] = False; json.dump(d, open(p, "w"))
PY
python3 "$WORK/1_cicd/src/scripts/cloud-android-lib-consumers.py" write "$WORK" >/dev/null  # regenerate: green again
chk >/dev/null && ok "regeneration heals a flipped ML flag" || fail "ML flag regeneration does not converge"
python3 - "$WORK" <<'PY'
import json, sys
p = sys.argv[1] + "/aa_cloud-superapp/data/lib-consumers.json"
d = json.load(open(p)); next(e for e in d["build_time"] if e["id"] == "ml-l-image")["ml"] = False; json.dump(d, open(p, "w"))
PY
has "lib-consumers.json does not match" && ok "an ML lib flagged as non-ML fails" || fail "an ML lib mixed into the non-ML list passed"

# 6. #1511 the category map: a lib in no category, in two, or a member that is no lib each fail
python3 "$WORK/1_cicd/src/scripts/cloud-android-lib-consumers.py" write "$WORK" >/dev/null  # a clean data file to start from
cat_edit() { python3 - "$WORK" "$1" <<'PY'
import json, sys
p = sys.argv[1] + "/1_cicd/src/data/lib-categories.json"
d = json.load(open(p)); c = d["categories"]
if sys.argv[2] == "drop": c[0]["members"].remove("ui-kit")
if sys.argv[2] == "dup": c[1]["members"].append("ui-kit")
if sys.argv[2] == "ghost": c[0]["members"].append("ghost-lib")
json.dump(d, open(p, "w"))
PY
}
cat_edit drop
has "ui-kit is in no category" && ok "a lib in no category fails" || fail "an uncategorized lib passed"
cp "$ROOT/1_cicd/src/data/lib-categories.json" "$WORK/1_cicd/src/data/"; cat_edit dup
has "ui-kit is in 2 categories" && ok "a lib in two categories fails" || fail "a doubly categorized lib passed"
cp "$ROOT/1_cicd/src/data/lib-categories.json" "$WORK/1_cicd/src/data/"; cat_edit ghost
has "lists ghost-lib" && ok "a category member that is no lib fails" || fail "a ghost category member passed"
cp "$ROOT/1_cicd/src/data/lib-categories.json" "$WORK/1_cicd/src/data/"
chk >/dev/null && ok "the restored category map passes" || fail "the restored category map does not pass: $(chk | head -3)"

[ "$FAILURES" -eq 0 ] && echo "all lib-consumers guard checks passed" || { echo "$FAILURES failed"; exit 1; }
