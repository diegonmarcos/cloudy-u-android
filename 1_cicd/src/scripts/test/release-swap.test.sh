#!/usr/bin/env bash
# #1783 P4: a release asset is never missing while bytes are in transit; the swap restores the old asset on failure.
# Drives the REAL cloud-android-release-swap.sh against a mock `gh` that models the release's asset list. No network.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
SW="$ROOT/1_cicd/src/scripts/cloud-android-release-swap.sh"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mkdir "$W/bin"
cat > "$W/bin/gh" <<'PY'
#!/usr/bin/env python3
import json, os, re, sys
S = os.environ["MOCK_STATE"]; L = os.environ["MOCK_LOG"]
st = json.load(open(S)); a = sys.argv[1:]
def save(): json.dump(st, open(S, "w"))
def log(x): open(L, "a").write(x + " live=" + ",".join(sorted(v["name"] for v in st["assets"].values())) + "\n")
if a[:2] == ["release", "upload"]:
    if any("--clobber" in x for x in a): sys.exit(9)
    p = a[3]; n = os.path.basename(p)
    if n in [v["name"] for v in st["assets"].values()]: sys.exit(1)
    st["next"] += 1; st["assets"][str(st["next"])] = {"name": n, "body": open(p).read()}; save(); log("UPLOAD " + n)
elif a[0] == "api" and a[1] == "-X" and a[2] == "PATCH":
    if os.environ.get("MOCK_FAIL_RENAME_TO") and ("name=" + os.environ["MOCK_FAIL_RENAME_TO"]) in a and not st.get("failed"):
        st["failed"] = 1; save(); log("PATCH-FAILED"); sys.exit(1)
    i = a[3].rsplit("/", 1)[1]; n = a[5].split("=", 1)[1]
    if n in [v["name"] for v in st["assets"].values()]: sys.exit(1)
    st["assets"][i]["name"] = n; save(); log("RENAME %s" % n)
elif a[0] == "api" and a[1] == "-X" and a[2] == "DELETE":
    del st["assets"][a[3].rsplit("/", 1)[1]]; save(); log("DELETE")
elif a[0] == "api":
    n = re.search(r'name=="([^"]+)"', " ".join(a)).group(1)
    for i, v in st["assets"].items():
        if v["name"] == n: print(i)
else:
    sys.exit(2)
PY
chmod +x "$W/bin/gh"
export PATH="$W/bin:$PATH" SWAP_REPO=o/r MOCK_STATE="$W/state.json" MOCK_LOG="$W/log"
printf 'new-bytes' > "$W/app.apk"; printf 'new-sha' > "$W/app.apk.sha256"
fresh() { echo '{"next":10,"assets":{"1":{"name":"app.apk","body":"old-bytes"},"2":{"name":"app.apk.sha256","body":"old-sha"}}}' > "$W/state.json"; : > "$W/log"; }
body() { python3 -c 'import json,sys;print(next((v["body"] for v in json.load(open(sys.argv[1]))["assets"].values() if v["name"]==sys.argv[2]),"MISSING"))' "$W/state.json" "$1"; }

fresh; sh "$SW" tag "$W/app.apk" "$W/app.apk.sha256" >/dev/null 2>&1
[ "$(body app.apk)" = new-bytes ] && [ "$(body app.apk.sha256)" = new-sha ] && ok "the new bytes and sidecar replace the old" || fail "swap did not replace the assets"
python3 -c 'import json,sys;n=sorted(v["name"] for v in json.load(open(sys.argv[1]))["assets"].values());sys.exit(0 if n==["app.apk","app.apk.sha256"] else 1)' "$W/state.json" && ok "no temp or .old asset is left behind" || fail "leftover assets: $(cat "$W/state.json")"

# the live name is missing only between the two renames: never while an upload happens
python3 - "$W/log" <<'PY' && ok "app.apk exists during the whole upload; the only gap is the two metadata renames" || fail "app.apk was missing during an upload"
import sys
for l in open(sys.argv[1]):
    if l.startswith("UPLOAD") and "live=" in l and "app.apk," not in l + "," and ",app.apk," not in "," + l.split("live=")[1].strip() + ",":
        sys.exit(1)
PY
ups="$(grep -c '^UPLOAD' "$W/log")"; [ "$ups" = 2 ] && ok "each file is uploaded once, under a temporary name" || fail "unexpected uploads: $ups"
grep -q 'swap-' <<<"$(grep '^UPLOAD' "$W/log")" && ok "uploads use the .swap- temporary name" || fail "upload used the live name"

# gap length: between the rename that frees app.apk and the rename that restores it there is no upload/delete
python3 - "$W/log" <<'PY' && ok "no upload or delete happens inside the gap" || fail "an operation ran inside the gap"
import sys
gap = False
for l in open(sys.argv[1]):
    live = l.split("live=")[1].strip().split(",")
    if "app.apk" not in live and gap is False and not l.startswith("UPLOAD"): gap = True; continue
    if gap:
        if "app.apk" in live: gap = False
        elif not l.startswith("RENAME"): sys.exit(1)
PY

# failure while swapping in: the old asset comes back
fresh; MOCK_FAIL_RENAME_TO=app.apk sh "$SW" tag "$W/app.apk" >/dev/null 2>&1 && fail "a failed swap reported success" || ok "a failed swap exits non-zero"
[ "$(body app.apk)" = old-bytes ] && ok "a failed swap restores the old asset" || fail "old asset lost after a failed swap: $(body app.apk)"

# first publish (no old asset)
echo '{"next":10,"assets":{}}' > "$W/state.json"; : > "$W/log"
sh "$SW" tag "$W/app.apk" >/dev/null 2>&1; [ "$(body app.apk)" = new-bytes ] && ok "a first publish works" || fail "first publish failed"

# mutation: the old delete-then-upload shape is red
cat > "$W/clobber.sh" <<'SH'
gh release upload tag "$1" --clobber
SH
sh "$W/clobber.sh" "$W/app.apk" >/dev/null 2>&1 && fail "mock accepted --clobber" || ok "mutant: a --clobber upload is rejected by the mock (the harness would catch a regression)"

# repo guard: no migrated publish path uses --clobber
BAD="$(cd "$ROOT" && git grep -n -E 'gh release upload.*--clobber|release upload.*--clobber' -- 1_cicd/src/scripts/cloud-android-publish-gate.sh 1_cicd/src/actions/publish-ghcr/publish.sh 1_cicd/src/cicd/ship-cloud-office.yml 1_cicd/src/cicd/ship-termux-boot.yml 1_cicd/src/cicd/build-nixdroid-bootstrap.yml 2>/dev/null)"
[ -z "$BAD" ] && ok "the publish gate, publish-ghcr and the office/termux-boot/bootstrap workflows no longer clobber" || fail "clobber remains: $BAD"

echo
[ "$FAILURES" -eq 0 ] && { echo "== RESULT(#1783 atomic release swap): held =="; exit 0; }
echo "== RESULT(#1783 atomic release swap): $FAILURES check(s) failed =="; exit 1
