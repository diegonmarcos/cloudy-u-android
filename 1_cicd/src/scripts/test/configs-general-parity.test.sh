#!/usr/bin/env bash
# configs-general-parity.test -- prove the General field-parity guard (#1852, rule 16) passes on the real tree and goes RED, naming the
# field, when ANY entry or action of the former Configs > General is dropped (one mutation per recorded field), when a section loses
# its live state, and when the big saturated tiles come back. python3 and coreutils only; no build.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-configs-general-parity-guard.py"
DATA=1_cicd/src/data/configs-general-parity.json
[ -f "$GUARD" ] && [ -f "$ROOT/$DATA" ] || { echo "ERROR missing guard or data: this test is unrun, not passing"; exit 1; }
export PYTHONDONTWRITEBYTECODE=1
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

out="$(python3 "$GUARD" "$ROOT")"; rc=$?
[ "$rc" -eq 0 ] && echo "ok     the real tree passes ($(tail -1 <<<"$out"))" || { echo "FAIL   the real tree is red"; echo "$out"; exit 1; }

python3 - "$ROOT" "$GUARD" "$WORK" <<'PY'
import json, os, shutil, subprocess, sys
root, guard, work = sys.argv[1:4]
rule = json.load(open(os.path.join(root, "1_cicd/src/data/configs-general-parity.json")))
files = rule["files"] + [rule["state_file"], "1_cicd/src/data/configs-general-parity.json"]
fails = proven = 0

def run(name, rel, edit, want):
    """copy the fixture, apply EDIT(text)->text to REL, the guard must exit 1 and print WANT."""
    global fails, proven
    fx = os.path.join(work, "fx")
    shutil.rmtree(fx, ignore_errors=True)
    for f in files:
        os.makedirs(os.path.dirname(os.path.join(fx, f)), exist_ok=True)
        shutil.copy(os.path.join(root, f), os.path.join(fx, f))
    p = os.path.join(fx, rel)
    t = open(p, encoding="utf-8").read()
    n = edit(t)
    if n == t:
        print("FAIL   %s: the mutation changed nothing" % name); fails += 1; return
    open(p, "w", encoding="utf-8").write(n)
    r = subprocess.run([sys.executable, guard, fx], capture_output=True, text=True)
    if r.returncode != 0 and want in r.stdout:
        proven += 1
    else:
        print("FAIL   %s: the guard stayed green or did not name %r" % (name, want)); fails += 1

# every recorded field, dropped from the source it lives in, must be named
for fld in rule["fields"]:
    for rel in rule["files"]:
        if fld["needle"] in open(os.path.join(root, rel), encoding="utf-8").read():
            run("drop %s: %s" % (fld["section"], fld["what"]), rel, lambda t, n=fld["needle"]: t.replace(n, "REMOVED_FIELD"), "F1 General lost %s: %s" % (fld["section"], fld["what"]))
            break
    else:
        print("FAIL   field %r is in none of the sources" % fld["needle"]); fails += 1
# a section loses its live state
for sec, needle in rule["state"].items():
    run("no live state for %s" % sec, rule["state_file"], lambda t, n=needle: t.replace(n, "// gone"), "F2 General shows no live state for %s" % sec)
for needle in rule["state_shown"]:
    for rel in rule["files"]:
        if needle in open(os.path.join(root, rel), encoding="utf-8").read():
            run("the page stops showing the state (%s)" % needle, rel, lambda t, n=needle: t.replace(n, '""'), "F2 the General page does not show the live state")
            break
# the big saturated tiles come back
for fb in rule["forbidden"]:
    rel = rule["files"][0]
    run("the old tile is back: %s" % fb["why"], rel, lambda t, n=fb["needle"]: t + "\n// " + n + "\n", "F3 the former layout is back: " + fb["why"])
print("configs-general-parity.test: %d mutation(s) proven red, %d failure(s)" % (proven, fails))
sys.exit(1 if fails else 0)
PY
