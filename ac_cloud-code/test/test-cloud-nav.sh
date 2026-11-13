#!/usr/bin/env bash
# ac_cloud-code tester (#562): the 7-tab bottom nav, its seam, its link-out
# target, the English base and the licence boundary — all without a build.
#
# Every value asserted is READ from what the app renders or derives: the tab
# order is the label sequence the real tabs.js builds from the real nav.json;
# the MyTerminal package is what tools/resolve-targets.py derives from the fleet
# and is then checked against the target app's own source; the language default
# is read out of Acode's settings and compared with the fleet's i18n policy.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
FAIL=0
ok()   { printf '  PASS  %s\n' "$1"; }
fail() { printf '  FAIL  %s\n' "$1"; FAIL=1; }

# ── the bar, rendered through the real module ──────────────────────────────
nav_out="$(node "$ROOT/test/cloud_nav.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "node driver crashed (exit $status): $nav_out"
checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; checks=$((checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; checks=$((checks + 1)) ;;
  esac
done <<< "$nav_out"
[ "$checks" -ge 15 ] || fail "the nav driver made only $checks checks — it is not seeing the module"

# ── #1699 the Backlog root index (live / offline last-good / reason) + rule-16 field parity ──
idx_out="$(node "$ROOT/test/backlog_index.mjs" "$ROOT" 2>&1)"
idx_status=$?
[ "$idx_status" -eq 0 ] || fail "backlog index driver crashed (exit $idx_status): $idx_out"
idx_checks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; idx_checks=$((idx_checks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; idx_checks=$((idx_checks + 1)) ;;
  esac
done <<< "$idx_out"
[ "$idx_checks" -ge 30 ] || fail "the backlog index driver made only $idx_checks checks"

# ── #1158 the shared bars (first row, second row, bottom bar, gradient), through the real bars.js ──
bars_out="$(node "$ROOT/test/cloud_bars.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "bars driver crashed (exit $status): $bars_out"
bchecks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; bchecks=$((bchecks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; bchecks=$((bchecks + 1)) ;;
  esac
done <<< "$bars_out"
[ "$bchecks" -ge 40 ] || fail "the bars driver made only $bchecks checks — it is not seeing the module"

# ── the editor's text must not bleed through the other pages (Playwright, real css + nav.json) ──
bleed_out="$(node "$ROOT/test/cloud_bleed.mjs" "$ROOT" 2>&1)"
status=$?
[ "$status" -eq 0 ] || fail "bleed driver crashed (exit $status): $bleed_out"
gchecks=0
while IFS= read -r line; do
  case "$line" in
    "CHECK ok "*)  ok "${line#CHECK ok }"; gchecks=$((gchecks + 1)) ;;
    "CHECK bad "*) fail "${line#CHECK bad }"; gchecks=$((gchecks + 1)) ;;
  esac
done <<< "$bleed_out"
[ "$gchecks" -ge 4 ] || fail "the bleed driver made only $gchecks checks"

# ── the seam: Acode is touched in exactly one place for the nav ────────────
main="$ROOT/src/main.js"
imports="$(grep -c '^import mountCloudNav from "./cloud";$' "$main")"
calls="$(grep -c 'mountCloudNav();' "$main")"
[ "$imports" = 1 ] && [ "$calls" = 1 ] && ok "main.js imports and mounts the nav exactly once" \
  || fail "main.js seam: $imports import(s), $calls call(s) of mountCloudNav (need 1 and 1)"
after="$(awk '/root.appendOuter\(/{seen=1; next} seen && /mountCloudNav\(\);/{print "yes"; exit}' "$main")"
[ "$after" = yes ] && ok "the nav mounts after Acode renders its root" \
  || fail "mountCloudNav() is not after root.appendOuter(...) — the bar would mount before the editor exists"

# ── the seam markers: every stub export is marked ──────────────────────────
seams="$ROOT/src/cloud/seams.js"
exports="$(grep -c '^export async function ' "$seams")"
marked="$(grep -c '^// SEAM: ' "$seams")"
[ "$exports" -ge 1 ] && [ "$exports" = "$marked" ] && ok "$exports seam functions, each marked SEAM:" \
  || fail "seams.js has $exports exported functions but $marked SEAM: markers"

# ── MyTerminal: the target is derived from the fleet and really exists ─────
targets="$(python3 "$ROOT/tools/resolve-targets.py" "$ROOT" --print 2>&1)"
status=$?
if [ "$status" -ne 0 ]; then
  fail "resolve-targets.py could not resolve the MyTerminal target: $targets"
else
  verdict="$(python3 - "$ROOT" "$targets" <<'PY'
import json, os, sys
root, t = sys.argv[1], json.loads(sys.argv[2])["myterminal"]
repo = os.path.dirname(root)
cls = t["activity"].rsplit(".", 1)[1]
found = [os.path.join(d, f) for d, _, fs in os.walk(os.path.join(repo, t["app_dir"]))
         for f in fs if f in (cls + ".kt", cls + ".java")]
print("ok" if found else "bad", t["id"], t["package"], t["activity"], (found or ["<no source>"])[0].replace(repo + "/", ""))
PY
)"
  case "$verdict" in
    ok*)  ok "MyTerminal → ${verdict#ok } (activity source exists)" ;;
    *)    fail "MyTerminal target resolves to an activity with no source: ${verdict#bad }" ;;
  esac
fi
declared="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
target = json.load(open(os.path.join(root, "src/cloud/nav.json")))["myterminal"]["target"]
def values(node):
    if isinstance(node, dict):
        for k, v in node.items():
            if not k.startswith("_"):
                yield from values(v)
    elif isinstance(node, list):
        for v in node:
            yield from values(v)
    elif isinstance(node, str):
        yield node
hits = []
for d, _, fs in os.walk(os.path.join(root, "src")):
    for f in fs:
        p = os.path.join(d, f)
        if f == "targets.gen.json":
            continue  # the resolver's OUTPUT, not a declaration
        if f.endswith(".json"):
            try:
                n = sum(1 for v in values(json.load(open(p))) if v == target)
            except ValueError:
                continue
        elif f.endswith((".js", ".ts")):
            n = len(re.findall(r"""['"`]%s['"`]""" % re.escape(target), open(p, errors="replace").read()))
        else:
            continue
        hits += [os.path.relpath(p, root)] * n
print(len(hits), " ".join(hits))
PY
)"
read -r count where <<< "$declared"
[ "$count" = 1 ] && ok "the MyTerminal target id is a value in exactly one place ($where)" \
  || fail "the MyTerminal target id is a value in $count places under src/ ($where) — it must be declared once"

# ── English base (#299): first run keeps the declared default ──────────────
lang="$(python3 - "$ROOT" <<'PY'
import json, os, re, sys
root = sys.argv[1]
src = open(os.path.join(root, "src/lib/settings.js")).read()
default = re.search(r'^\s*lang:\s*"([^"]+)"', src, re.M).group(1)
first = re.search(r'if \(!\(await fs\.exists\(\)\)\) \{(.*?)\n\t\t\}', src, re.S).group(1)
policy = json.load(open(os.path.join(os.path.dirname(root), "1_cicd/src/i18n-policy.json")))
base = policy["base_language"]["locale"]
print(default, base, "navigator.language" in first)
PY
)"
read -r default base leaks <<< "$lang"
[ "${default%%-*}" = "$base" ] && ok "Acode's default language $default is the fleet base '$base'" \
  || fail "Acode's default language $default is not the fleet base '$base'"
[ "$leaks" = False ] && ok "first run does not adopt the device locale" \
  || fail "first run sets lang from navigator.language — a Spanish phone boots in Spanish (#299)"

# ── licence boundary: the GPL binaries stay pruned ─────────────────────────
grep -q 'Permission is hereby granted, free of charge' <<<"$(head -3 "$ROOT/license.txt")" \
  && ok "license.txt is the MIT grant" || fail "license.txt is not the MIT text Acode was vendored under"
for d in $(python3 -c 'import json,sys; print(" ".join(json.load(open(sys.argv[1]))["upstream"]["pruned"]))' "$ROOT/build.json"); do
  [ ! -e "$ROOT/$d" ] && ok "pruned: $d" || fail "$d is back in the tree — build.json::upstream.pruned says it must not be"
done
blobs="$(find "$ROOT" -path "$ROOT/node_modules" -prune -o -path "$ROOT/platforms" -prune -o -path "$ROOT/plugins" -prune -o \( -name '*.so' -o -name '*.rootfs' \) -print | head -5)"
[ -z "$blobs" ] && ok "no prebuilt native binaries or rootfs images are vendored" \
  || fail "prebuilt binaries vendored without source: $blobs"

if [ "$FAIL" -eq 0 ]; then
  printf '  cloud-code nav tester: ALL PASS\n'
else
  printf '  cloud-code nav tester: FAIL\n' >&2
  exit 1
fi
