#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #663 — C3 IS AN APP: it is in the Store, and Cloud ▸ Apps ▸ Configs      ║
# ║ opens the APK. And the surface it came from is not orphaned yet.         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS LIVES HERE AND NOT IN ac_cloud-c3/test. Every assertion below reads
# THIS application's declarations — build.json and the fleet manifest. A tester
# under ac_cloud-c3/ that reached into aa_cloud-superapp/ would be reading foreign
# source, which cloud-android-test-engine.sh downgrades to a WARNING: it could
# never fail a release, so it would be a green that verified nothing (#639). The
# owner of a declaration owns the tester that holds it.
#
#   T1  cloud-c3 is IN THE STORE: the fleet manifest carries a COMPLETE row —
#       package, registry/namespace/image, tag, asset, release_url, repo_url,
#       ghcr_page, version_name, version_code, per-ABI assets — with kind `app`
#       and group `apps`. #276 is why every field is checked and not just
#       presence of the row: a half-filled fleet entry has NO symptom, it draws
#       an empty page that looks exactly like a working one.
#   T2  Cloud ▸ Apps ▸ Configs opens the APPLICATION: that tile's target is
#       extapp:cloud-c3 and NOT a page: route. "The C3 page now is a app" —
#       a tile still pointing at page:c3/... is the whole defect of this ticket.
#   T3  the tile can RESOLVE: ui.external_apps declares cloud-c3 with a package,
#       because Sections.externalApp() reads ONLY that array — without an entry
#       the tap snacks "Unknown app" instead of offering the APK.
#   T4  the declaration restates no name: that entry carries NO `label`, so the
#       application has one name (#351, the copy #224 reverted). The displayed
#       caption is DERIVED by dropping the family prefix.
#   T5  NO ORPHAN, IN EITHER DIRECTION. The in-app C3 section is still present
#       because its five live feeds, NTFY centre and three container dashboards
#       are NOT ported into the APK yet. While it is present it must stay
#       REACHABLE — a section nothing links to is invisible, working, and dead.
#       When it is finally deleted this check inverts: see the note at T5.
#   M   mutation-proof: a page: target back on the Configs tile, a removed
#       external_apps entry, a label added back, a gutted fleet row and a C3
#       section with every inbound link cut each go RED.
#
# OWN-SOURCE ONLY. python3 and jq.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/aa_cloud-superapp"
BJ="$APP/build.json"
FLEET="$APP/data/constellation-fleet.json"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$FLEET"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
command -v jq >/dev/null 2>&1 || { echo "ERROR jq absent — a verdict from a missing tool is not a verdict"; exit 1; }

# t1 <fleet manifest> : the Store row is complete
t1() {
    python3 - "$1" <<'PYTHON'
import json, sys
apps = json.load(open(sys.argv[1], encoding="utf-8")).get("apps") or []
rows = [a for a in apps if a.get("id") == "c3"]
if len(rows) != 1:
    print("    the fleet manifest carries %d rows with id 'c3', not 1 — regen.sh scans "
          "*/build.json, so a missing row means the app is not in the Store at all" % len(rows))
    sys.exit(1)
r = rows[0]
required = ("package", "registry", "namespace", "image", "tag", "asset", "release_url",
            "repo_url", "ghcr_page", "version_name", "version_code")
bad = [k for k in required if not r.get(k)]
if bad:
    print("    the c3 fleet row is INCOMPLETE, missing %s — #276: a half-filled entry has no "
          "symptom, it draws an empty page that looks exactly like a working one" % ", ".join(bad))
if r.get("kind") != "app":
    print("    the c3 fleet row is kind %r, not 'app' — the Store's affordances are chosen by "
          "KIND, so it would not get the app set" % r.get("kind"))
    bad.append("kind")
if r.get("group") != "apps":
    print("    the c3 fleet row is group %r, not 'apps' — it would not appear under Store Cloud apps"
          % r.get("group"))
    bad.append("group")
if not (r.get("assets") or {}):
    print("    the c3 fleet row declares no per-ABI assets map")
    bad.append("assets")
if r.get("package") and not r["package"].startswith("com.diegonmarcos."):
    print("    the c3 fleet row's package %r is not ours" % r["package"])
    bad.append("package")
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <build.json> : Cloud > Apps > Configs opens the APK, not a page
t2() {
    python3 - "$1" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
cloud = next(s for s in d["ui"]["sections"] if s.get("id") == "cloud")
# The Configs group is the one holding Store and Configs (#324, between Projects W
# and Actions). Found by its CONTENT, not by an index, so inserting a group above
# it does not silently move this assertion onto a different row.
grp = None
for g in cloud.get("tile_groups") or []:
    labels = [t.get("label") for t in (g.get("tiles") or [])]
    if "Store" in labels and "Launcher" in labels:
        grp = g; break
if grp is None:
    print("    no Cloud tile_group holds both Store and Controls — the #324 group this ticket "
          "links C3 from is gone or renamed"); sys.exit(1)
tiles = [t for t in grp["tiles"] if t.get("id") == "c3"]
if not tiles:
    print("    the Cloud > Apps > Configs group has no c3 tile — #663 asks for C3 to be LINKED there")
    sys.exit(1)
target = tiles[0].get("target") or ""
bad = []
if target.startswith("page:"):
    bad.append("the Configs group's C3 tile still targets %r — 'the C3 page now is a app', so a "
               "page: route here is exactly the defect #663 names" % target)
elif target != "extapp:cloud-c3":
    bad.append("the Configs group's C3 tile targets %r, not extapp:cloud-c3" % target)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3/t4 <build.json> : the tile resolves, and restates no name
t34() {
    python3 - "$1" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
ext = [a for a in d["ui"]["external_apps"] if a.get("id") == "cloud-c3"]
bad = []
if len(ext) != 1:
    print("    ui.external_apps declares cloud-c3 %d times, not once — Sections.externalApp() reads "
          "ONLY this array, so with no entry the tile snacks 'Unknown app: cloud-c3' AFTER the tap "
          "instead of offering the APK" % len(ext))
    sys.exit(1)
e = ext[0]
if not (e.get("hub_package") or e.get("install_package")):
    bad.append("the cloud-c3 entry names no package, so there is nothing for a tap to open")
for k in ("hub_package", "install_package"):
    if e.get(k) and e[k] != "com.diegonmarcos.cloudc3":
        bad.append("the cloud-c3 entry's %s is %r, not com.diegonmarcos.cloudc3" % (k, e[k]))
if "label" in e:
    bad.append("the cloud-c3 entry carries a label %r — an application has ONE name (#351) and a "
               "caption beside a fleet package is the second copy #224 reverted; the caption is "
               "DERIVED by dropping the family prefix" % e.get("label"))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t5 <build.json> : while the in-app C3 section still exists, it stays reachable
t5() {
    python3 - "$1" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
ui = d["ui"]
section = next((s for s in ui["sections"] if s.get("id") == "c3"), None)
if section is None:
    # THE SECTION HAS BEEN DELETED. This check then inverts: nothing anywhere may
    # still point INTO it, or a tap lands on a page that no longer exists.
    dangling = []
    def walk(node, path):
        if isinstance(node, dict):
            for k, v in node.items():
                if k.startswith("_doc"): continue
                walk(v, "%s.%s" % (path, k))
        elif isinstance(node, list):
            for i, v in enumerate(node): walk(v, "%s[%d]" % (path, i))
        elif isinstance(node, str):
            if node.startswith("page:c3/") or node == "page:cloud/c3":
                if "retired_targets" not in path:
                    dangling.append("%s = %s" % (path, node))
    walk(d, "")
    for x in dangling:
        print("    the C3 section is gone but %s still points into it — a tap that does nothing" % x)
    sys.exit(1 if dangling else 0)

# THE SECTION IS STILL PRESENT, deliberately: its five live feeds, NTFY centre and
# three container dashboards are not ported into the APK, whose Topology and Observ
# tabs render a stated not-built placeholder. While it is here it must be REACHABLE.
inbound = []
def find(node, path):
    if isinstance(node, dict):
        for k, v in node.items():
            if k.startswith("_doc"): continue
            find(v, "%s.%s" % (path, k))
    elif isinstance(node, list):
        for i, v in enumerate(node): find(v, "%s[%d]" % (path, i))
    elif isinstance(node, str):
        if node in ("page:cloud/c3",) or node.startswith("page:c3/"):
            if "retired_targets" not in path: inbound.append("%s = %s" % (path, node))
    return
find(d, "")
# A `mirror_section` page counts too: it IS a way in.
mirrors = [p.get("id") for s in ui["sections"] for p in (s.get("pages") or [])
           if p.get("mirror_section") == "c3"]
if not inbound and not mirrors:
    print("    the C3 section still exists and NOTHING links to it — %d pages and both stacks "
          "would be invisible, working and dead, which is the orphan this ticket exists to "
          "prevent. Either delete the section or keep a way in."
          % len(section.get("pages") or []))
    sys.exit(1)
sys.exit(0)
PYTHON
}

echo "── #663 C3 is an app: Store row, the Configs link, and no orphan either way ──"
t1  "$FLEET" && pass "T1 cloud-c3 has a COMPLETE fleet row, kind app, group apps (in the Store)" || fail "T1 the Store row is missing or incomplete"
t2  "$BJ"    && pass "T2 Cloud > Apps > Configs opens extapp:cloud-c3, not a page" || fail "T2 the Configs link does not open the APK"
t34 "$BJ"    && pass "T3/T4 the tile resolves through ui.external_apps, and restates no name" || fail "T3/T4 the tile cannot resolve, or restates the app's name"
t5  "$BJ"    && pass "T5 no orphan: the surviving C3 section is still reachable" || fail "T5 the C3 surface is orphaned or dangling"

# ── MUTATION PROOF ─────────────────────────────────────────────────────────
echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <which-file: bj|fleet> <setup-fn>
    local name="$1" check="$2" which="$3" setup="$4"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t/data"
    cp "$BJ" "$WORK/t/build.json"; cp "$FLEET" "$WORK/t/data/constellation-fleet.json"
    local subject="$WORK/t/build.json"
    [ "$which" = "fleet" ] && subject="$WORK/t/data/constellation-fleet.json"
    if ! "$check" "$subject" >/dev/null 2>&1; then
        echo "  VOID  $name — the unmutated COPY is already red, so this mutation proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if ! "$setup" "$subject" >/dev/null 2>&1; then
        echo "  VOID  $name — the mutation itself failed to apply, so it proves nothing"
        MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$subject" >/dev/null 2>&1; then
        echo "  HOLLOW  $name — the check stayed GREEN under the mutation"
        MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}

m_page_target() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
cloud=next(s for s in d["ui"]["sections"] if s.get("id")=="cloud")
for g in cloud["tile_groups"]:
    labels=[t.get("label") for t in g.get("tiles") or []]
    if "Store" in labels and "Launcher" in labels:
        for t in g["tiles"]:
            if t.get("id")=="c3": t["target"]="page:c3/topology"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_drop_external() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
d["ui"]["external_apps"]=[a for a in d["ui"]["external_apps"] if a.get("id")!="cloud-c3"]
json.dump(d,open(p,"w"),indent=2)
PY
}
m_add_label() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
for a in d["ui"]["external_apps"]:
    if a.get("id")=="cloud-c3": a["label"]="C3"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_gut_fleet_row() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
for a in d["apps"]:
    if a.get("id")=="c3": a.pop("release_url",None); a.pop("ghcr_page",None)
json.dump(d,open(p,"w"),indent=2)
PY
}
m_fleet_wrong_kind() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
for a in d["apps"]:
    if a.get("id")=="c3": a["kind"]="lib"
json.dump(d,open(p,"w"),indent=2)
PY
}
m_orphan_section() { python3 - "$1" <<'PY'
import json,sys,collections
p=sys.argv[1]; d=json.load(open(p),object_pairs_hook=collections.OrderedDict)
# cut EVERY way into the surviving C3 section: the mirror page and every page: link
for s in d["ui"]["sections"]:
    s["pages"]=[q for q in (s.get("pages") or []) if q.get("mirror_section")!="c3"]
def scrub(node):
    if isinstance(node,dict):
        for k,v in list(node.items()):
            if isinstance(v,str) and (v=="page:cloud/c3" or v.startswith("page:c3/")) and "retired" not in k:
                node[k]="action:none"
            else: scrub(v)
    elif isinstance(node,list):
        for v in node: scrub(v)
scrub(d["ui"]); scrub(d["onehand"])
json.dump(d,open(p,"w"),indent=2)
PY
}

mutate "the Configs tile points at a page again"      t2  bj    m_page_target
mutate "ui.external_apps loses the cloud-c3 entry"    t34 bj    m_drop_external
mutate "the cloud-c3 entry regains a label (#224)"    t34 bj    m_add_label
mutate "the fleet row loses release_url + ghcr_page"  t1  fleet m_gut_fleet_row
mutate "the fleet row is filed as a lib, not an app"  t1  fleet m_fleet_wrong_kind
mutate "every way into the C3 section is cut"         t5  bj    m_orphan_section

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  5 properties asserted, 6 mutations each proved able to go red"
