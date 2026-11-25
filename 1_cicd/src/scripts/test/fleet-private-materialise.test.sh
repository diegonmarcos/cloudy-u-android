#!/usr/bin/env bash
# fleet-private-materialise.test (#1566) — the fleet-private tier-2 datasets are
# not in the public repo, and CI materialises them before anything reads them.
#
# 1. every checkout of THIS repository in a workflow source or template is
#    followed by the "Checkout private fleet data" step (sparse checkouts and
#    other-repository checkouts are exempt: they never run the build);
# 2. every dataset in fleet-private-paths.json is untracked and gitignored;
# 3. the materialiser writes each declared dataset from the private checkout
#    and fails loud when one is missing.
# Mutations proven below: drop the step -> 1 red; track a dataset -> 2 red;
# remove a dataset from the private checkout -> 3 red.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# uncovered FILE...: "file:line" of each own-repo checkout with no private-data step after it.
uncovered() {
  python3 - "$@" <<'PY'
import re, sys
for f in sys.argv[1:]:
    L = open(f).read().split("\n")
    steps = [i for i, l in enumerate(L) if re.match(r"^\s*- ", l)]
    steps.append(len(L))
    for a, b in zip(steps, steps[1:]):
        blk = "\n".join(L[a:b])
        if not re.search(r"uses:\s*actions/checkout@", blk): continue
        if re.search(r"^\s*(repository|path):", blk, re.M) or "sparse-checkout" in blk: continue
        if "fleet-private" in blk: continue
        nxt = "\n".join(L[b:steps[steps.index(a) + 1 + 1]] if steps.index(a) + 2 < len(steps) else L[b:])
        if "uses: ./.github/actions/fleet-private" not in nxt:
            print("%s:%d" % (f, a + 1))
PY
}

mkdir -p "$WORK/m"
printf '    steps:\n      - uses: actions/checkout@v4\n      - name: Checkout private fleet data\n        uses: ./.github/actions/fleet-private\n      - run: true\n' > "$WORK/m/good.yml"
printf '    steps:\n      - uses: actions/checkout@v4\n      - run: true\n' > "$WORK/m/bad.yml"
[ -z "$(uncovered "$WORK/m/good.yml")" ] && ok "scan: covered checkout passes" || fail "scan: covered checkout flagged"
[ -n "$(uncovered "$WORK/m/bad.yml")" ] && ok "mutation: checkout without the step is caught" || fail "mutation: missing step not caught"

bad="$(uncovered "$ROOT"/1_cicd/src/cicd/*.yml "$ROOT"/1_cicd/src/templates/*.in)"
if [ -z "$bad" ]; then ok "every own-repo checkout materialises the private fleet data"; else fail "checkout without the private fleet data step:"; echo "$bad"; fi

MAP="$ROOT/1_cicd/src/data/fleet-private-paths.json"
paths="$(python3 -c "import json;print('\n'.join(json.load(open('$MAP'))['datasets'].values()))")"
for p in $paths; do
  [ -z "$(git -C "$ROOT" ls-files -- "$p")" ] && ok "$p is not tracked" || fail "$p is tracked in the public repo"
  git -C "$ROOT" check-ignore -q -- "$p" && ok "$p is gitignored" || fail "$p is not gitignored"
done

# materialiser against a fake private checkout
FAKE="$WORK/ws"; mkdir -p "$FAKE/1_cicd/src/data" "$FAKE/.fleet-private/fleet-data/2/fleet"
cp "$MAP" "$FAKE/1_cicd/src/data/"
for ds in $(python3 -c "import json;print(' '.join(json.load(open('$MAP'))['datasets']))"); do echo '{"fake":true}' > "$FAKE/.fleet-private/fleet-data/2/fleet/$ds.json"; done
if sh "$ROOT/1_cicd/src/scripts/cloud-android-fleet-private-materialise.sh" "$FAKE" >/dev/null 2>&1; then ok "materialiser writes every declared dataset"; else fail "materialiser failed on a complete private checkout"; fi
for p in $paths; do [ -f "$FAKE/$p" ] || fail "materialiser did not write $p"; done
first="$(ls "$FAKE/.fleet-private/fleet-data/2/fleet" | head -1)"
if [ -n "$first" ]; then
  rm "$FAKE/.fleet-private/fleet-data/2/fleet/$first"
  sh "$ROOT/1_cicd/src/scripts/cloud-android-fleet-private-materialise.sh" "$FAKE" >/dev/null 2>&1 && fail "mutation: missing dataset not caught" || ok "mutation: a dataset missing from the private checkout fails loud"
fi
[ "$FAILURES" = 0 ] && echo "PASS fleet-private-materialise" || { echo "FAILED: $FAILURES"; exit 1; }
