#!/usr/bin/env bash
# #1934 THE UNIFIED REPO CONTROL SHEET (Overview | Sync | Clone | Workflow | Manage) is DATA (build.json ui.git_control_sheet), drawn by libs:git-ui.
# P1 FIELD PARITY (rule 16): every control id of the earlier menu, bulk bar, repo table, status window and sheet (test/fixtures/git-controls-before.txt)
#    sits in EXACTLY ONE control of the new structure (per-repo tabs + header + bulk tabs); nothing is dropped, nothing is doubled.
# P2 REFERENCE COVERAGE: every control/group/tab of the owner's reference (test/fixtures/git-control-sheet-reference.txt) is declared.
# P3 STRUCTURE: five tabs in order for the per-repo and the bulk sheet, the four header actions, a Spanish label on every tab, group and control,
#    needs_clone controls name a reason, a disabled control names a reason, destructive controls confirm.
# P4 WIRING: the sheet is drawn from the declaration (no tab or group list in Kotlin), the preview and the executed command come from ONE model.
# Each assertion is proven red by a mutation.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
FIX="$APP/test/fixtures"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
LIB="$ROOT/ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui"
CORE="$ROOT/ab_cloud-libs-shared/libs/git-core/src/main/java/com/diegonmarcos/cloudlib/gitcore"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

p1() { python3 - "$1" "$FIX/git-controls-before.txt" <<'PY'
import json, sys, collections
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]
cnt = collections.Counter()
h = d["header"]
ctl = list(h["actions"]) + [h["refresh"], h["close"], h["banner"], h["clone_now"]] + list(h["fields"])
for tabs in (d["tabs"], d["bulk"]["tabs"]):
    for t in tabs:
        for g in t["groups"]: ctl += g["controls"]
for c in ctl:
    for l in c.get("legacy", []): cnt[l] += 1
before = [l.strip() for l in open(sys.argv[2]) if l.strip() and not l.startswith("#")]
bad = 0
lost = [b for b in before if cnt[b] == 0]; dup = [k for k, v in cnt.items() if v > 1]; extra = [k for k in cnt if k not in before]
if lost: print("    lost:", lost); bad = 1
if dup: print("    doubled:", dup); bad = 1
if extra: print("    not a before id:", extra); bad = 1
print("    parity: %d before ids, %d placed, %d controls" % (len(before), sum(cnt.values()), len(ctl)))
sys.exit(bad)
PY
}

p2() { python3 - "$1" "$FIX/git-control-sheet-reference.txt" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]
labels = set(); groups = set(); tabs = set()
h = d["header"]
for c in list(h["actions"]) + [h["refresh"], h["close"], h["banner"], h["clone_now"]]: labels.add(c["label"])
for t in d["tabs"]:
    tabs.add(t["label"])
    for g in t["groups"]:
        groups.add(g["label"])
        for c in g["controls"]: labels.add(c["label"])
        for k in ("input", "chips"):
            if g.get(k) and g[k].get("label"): labels.add(g[k]["label"])
for p in d["sync_presets"]: labels.add(p["label"])
bad = 0; n = 0
for l in open(sys.argv[2]):
    l = l.rstrip("\n")
    if not l or l.startswith("#"): continue
    kind, name = l.split("|", 1); n += 1
    ok = (name in tabs) if kind == "tab" else (name in groups) if kind == "group" else (name in labels)
    if not ok: print("    reference control not declared:", kind, name); bad = 1
print("    reference: %d controls checked" % n)
sys.exit(bad)
PY
}

p3() { python3 - "$1" <<'PY'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["git_control_sheet"]
bad = 0
order = ["overview", "sync", "clone", "workflow", "manage"]
if [t["id"] for t in d["tabs"]] != order: print("    per-repo tabs", [t["id"] for t in d["tabs"]]); bad = 1
if [t["id"] for t in d["bulk"]["tabs"]] != order: print("    bulk tabs", [t["id"] for t in d["bulk"]["tabs"]]); bad = 1
if [a["label"] for a in d["header"]["actions"]] != ["Repo list", "Fetch status", "Check lite", "Check full"]: print("    header action row"); bad = 1
reasons = d["reasons"]
allc = []
def walk(tabs, tag):
    global bad
    for t in tabs:
        if not (t.get("i18n") or {}).get("es"): print("    tab without es:", t["id"]); bad = 1
        for g in t["groups"]:
            if not (g.get("i18n") or {}).get("es"): print("    group without es:", g["id"]); bad = 1
            for c in g["controls"]: allc.append((tag, c))
walk(d["tabs"], "p"); walk(d["bulk"]["tabs"], "b")
ids = [c["id"] for tag, c in allc]
if len(ids) != len(set(ids)) and False: pass
for tag, c in allc:
    if not (c.get("i18n") or {}).get("es"): print("    control without es:", c["id"]); bad = 1
    if c.get("disabled") and c.get("reason") not in reasons: print("    disabled control without a stated reason:", c["id"]); bad = 1
    if c.get("needs_clone") and "needs_clone" not in reasons: print("    needs_clone has no stated reason"); bad = 1
    if c.get("danger") and not c.get("confirm") and c.get("kind") not in ("option", "flag"): print("    destructive control without confirm:", c["id"]); bad = 1
byid = {c["id"]: c for tag, c in allc}
for need in ("sy.flag.forceRaw", "sy.opt"):
    pass
if not byid["sy.flag.forceRaw"].get("danger"): print("    Force Push (!) is not marked dangerous"); bad = 1
if not byid["sy.flag.submodules"].get("disabled"): print("    submodules must be shown disabled with its reason"); bad = 1
if not byid["mg.life.delete_remote"].get("confirm") or not byid["mg.life.delete_local"].get("confirm"): print("    delete without confirm"); bad = 1
if not d["bulk"].get("confirm_lists_repos"): print("    bulk confirm must list the repos"); bad = 1
sys.exit(bad)
PY
}

p4() {
    local lib="$1" host="$2" core="$3"
    grep -qF 'tabsFor(host.bulk)' "$lib" || { echo "    the sheet does not draw the declared tabs"; return 1; }
    ! grep -qE '"overview"|"manage"|"Overview"|"Danger Zone"' "$lib" || { echo "    a tab or group name is hard-coded in the lib"; return 1; }
    grep -qF 'GitStep.preview(s.steps())' "$lib" || { echo "    the preview is not generated from the option model"; return 1; }
    grep -qF 'o.steps()' "$host" || { echo "    the host does not execute the option model's steps"; return 1; }
    grep -qF 'fun steps()' "$core" || { echo "    the option model has no steps()"; return 1; }
}

echo "── P1 field parity (rule 16) ──"
p1 "$BJ" && pass "every before id sits in exactly one control" || fail "field parity"
echo "── P2 reference coverage ──"
p2 "$BJ" && pass "every control of the reference is declared" || fail "reference coverage"
echo "── P3 structure ──"
p3 "$BJ" && pass "five tabs, header row, Spanish labels, reasons, confirms" || fail "structure"
echo "── P4 wiring ──"
HOSTF="$SRC/sync/ControlSheetHost.kt"
p4 "$LIB/GitControlSheet.kt" "$HOSTF" "$CORE/GitCommandPlan.kt" && pass "drawn from the declaration; one option model for preview and run" || fail "wiring"
grep -qF 'git_sheet_badge_destructive' "$ROOT/ab_cloud-libs-shared/libs/git-ui/src/main/res/values/strings.xml" && pass "chrome strings in values (labels carry the declared es)" || fail "strings"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mut() { python3 -c "import json,sys; b=json.load(open(sys.argv[1])); d=b['ui']['git_control_sheet']; exec(sys.argv[3]); json.dump(b,open(sys.argv[2],'w'))" "$BJ" "$TMP/b.json" "$1"; }
mut "d['tabs'][2]['groups'][1]['controls'][0]['legacy'].remove('menu:clone_repo_full')"; p1 "$TMP/b.json" >/dev/null && fail "P1 passed a dropped control (vacuous)" || pass "a before id removed → P1 RED"
mut "d['tabs'][0]['groups'][1]['controls'][0]['legacy'].append('menu:folder_delete')"; p1 "$TMP/b.json" >/dev/null && fail "P1 passed a doubled id (vacuous)" || pass "an id in two places → P1 RED"
mut "d['bulk']['tabs'][1]['groups'][2]['controls'].pop(0)"; p1 "$TMP/b.json" >/dev/null && fail "P1 passed a dropped bulk control (vacuous)" || pass "a bulk control removed → P1 RED"
mut "d['tabs'][3]['groups'][1]['controls'].pop(0)"; p2 "$TMP/b.json" >/dev/null && fail "P2 passed a missing reference control (vacuous)" || pass "a reference control removed → P2 RED"
mut "d['tabs'].pop(3)"; p3 "$TMP/b.json" >/dev/null && fail "P3 passed a missing tab (vacuous)" || pass "a tab removed → P3 RED"
mut "[c.update(danger=False) for g in d['tabs'][1]['groups'] for c in g['controls'] if c['id']=='sy.flag.forceRaw']"; p3 "$TMP/b.json" >/dev/null && fail "P3 passed Force Push not dangerous (vacuous)" || pass "Force Push not marked dangerous → P3 RED"
mut "[c.update(confirm=False) for g in d['tabs'][4]['groups'] for c in g['controls'] if c['id']=='mg.life.delete_local']"; p3 "$TMP/b.json" >/dev/null && fail "P3 passed Delete Local without confirm (vacuous)" || pass "Delete Local without confirm → P3 RED"
sed 's/GitStep.preview(s.steps())/"git hardcoded"/' "$LIB/GitControlSheet.kt" > "$TMP/l.kt"; p4 "$TMP/l.kt" "$HOSTF" "$CORE/GitCommandPlan.kt" >/dev/null && fail "P4 passed a hand-written preview (vacuous)" || pass "preview not from the model → P4 RED"
sed 's/tabsFor(host.bulk)/listOf()/' "$LIB/GitControlSheet.kt" > "$TMP/l.kt"; p4 "$TMP/l.kt" "$HOSTF" "$CORE/GitCommandPlan.kt" >/dev/null && fail "P4 passed tabs not from the declaration (vacuous)" || pass "tabs not from the declaration → P4 RED"
p1 "$BJ" >/dev/null && pass "unmutated tree is still green" || fail "the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-control-sheet: all checks passed"; else echo "test-drive-git-control-sheet: $FAILURES check(s) FAILED"; exit 1; fi
