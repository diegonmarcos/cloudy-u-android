#!/usr/bin/env bash
# release-orphan-guard mutation test (#637): the guard must go RED for each
# defect it claims to catch. The baseline listing is DERIVED from the real
# manifest and the real retained list, so it cannot go stale beside them.
set -uo pipefail

ROOT="$(_d="$(cd "$(dirname "$0")" && pwd)"; while [ "$_d" != "/" ] && [ ! -e "$_d/.git" ]; do _d="$(dirname "$_d")"; done; printf '%s' "$_d")"
cd "$ROOT"
GUARD=1_cicd/src/scripts/cloud-android-release-orphan-guard.py
DECL=1_cicd/src/data/release-orphan-guard.json
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT

fail=0
ok()  { echo "  PASS  $*"; }
bad() { echo "  FAIL  $*"; fail=1; }

python3 - "$DECL" > "$T/base" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))
m = json.load(open(d["manifest"]))
names = set(d["retained"])
for r in m["apps"]:
    if r.get("asset"): names.add(r["asset"])
    names.update(v for v in (r.get("assets") or {}).values() if v)
for n in sorted(names):
    print(n); print(n + ".sha256")
PY

expect() { # expect <0|1> <label> <listing> [needle]
  local out rc; out="$(python3 "$GUARD" --assets "$3" 2>&1)"; rc=$?
  if [ "$1" = 0 ]; then
    [ "$rc" = 0 ] && ok "$2" || { bad "$2 (rc=$rc)"; echo "$out"; }
  elif [ "$rc" = 0 ]; then bad "$2 — guard stayed green"; echo "$out"
  elif [ -n "${4:-}" ] && [[ "$out" != *"$4"* ]]; then bad "$2 — red but did not name $4"; echo "$out"
  else ok "$2"; fi
}

[ "$(wc -l < "$T/base")" -gt 20 ] && ok "baseline listing derived ($(wc -l < "$T/base") names)" || bad "baseline listing is implausibly small"
expect 0 "T1 every asset named by a row or retained → green" "$T/base"

{ cat "$T/base"; echo cloud-rootfs-6f37b3960639-arm64.tar.zst; } > "$T/m2"
expect 1 "T2 the #637 bare rootfs re-uploaded → red" "$T/m2" "cloud-rootfs-6f37b3960639-arm64.tar.zst"

{ cat "$T/base"; echo Cloud-Nobody.apk.sha256; } > "$T/m3"
expect 1 "T3 a sidecar whose asset no row names → red" "$T/m3" "Cloud-Nobody.apk"

grep -vx 'Cloud-Sheets.apk' "$T/base" | grep -vx 'Cloud-Sheets.apk.sha256' > "$T/m4"
[ "$(wc -l < "$T/m4")" -lt "$(wc -l < "$T/base")" ] || bad "T4 mutation did not remove Cloud-Sheets.apk"
expect 1 "T4 a retained entry no longer on the release (stale excuse) → red" "$T/m4" "Cloud-Sheets.apk"

: > "$T/m5"
expect 1 "T5 an empty listing is a failed read, not a clean release → red" "$T/m5" "empty"

python3 -c 'import json,sys; m=json.load(open(sys.argv[1])); m["apps"].append({"id":"t6","asset":"Cloud-Sheets.apk"}); json.dump(m,open(sys.argv[2],"w"))' \
  "$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["manifest"])' "$DECL")" "$T/m6.json"
out="$(python3 "$GUARD" --assets "$T/base" --manifest "$T/m6.json" 2>&1)" && { bad "T6 a retained entry a row now names → guard stayed green"; echo "$out"; } \
  || { [[ "$out" == *"now referenced"* ]] && ok "T6 a retained entry a row now names (stale excuse) → red" || { bad "T6 red for the wrong reason"; echo "$out"; }; }

{ cat "$T/base"; echo Cloud-Unknown-x86_64.apk.source.old-20003; } > "$T/m7"
expect 1 "T7 a different unknown .source.old-* orphan (only the named Cloud Search backup is retained) → red" "$T/m7" "Cloud-Unknown-x86_64.apk.source.old-20003"
grep -q 'Cloud-Search-x86_64.apk.source.old-20003' "$DECL" && ok "T7b the stale Cloud Search backup is retained by name" || bad "T7b retained entry missing"

exit "$fail"
