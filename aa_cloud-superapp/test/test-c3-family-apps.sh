#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #687 — EVERY cloud-c3 FAMILY APP is in the Store with ONE name; cloud-c3 ║
# ║ itself is linked from Cloud ▸ Apps ▸ Configs and its sub-apps are NOT —  ║
# ║ they launch from inside cloud-c3. Derived, never a list typed here.      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS BESIDE test-c3-is-an-app.sh. That tester pins cloud-c3 by
# name, including the orphan logic for the section it was extracted from. The
# family has grown (cloud-c3-webserver, #687), and a second copy of the same
# five checks with a different name typed in is the drift this repository's
# name guard exists to stop. So the FAMILY is derived: every ui.external_apps
# entry whose id is cloud-c3 or cloud-c3-<x> is a family member, and each one
# must satisfy the same contract. A member missing from external_apps is not
# found here — it is found by T3 of test-app-names-pattern.sh and by the fleet
# guard — so a vacuity check demands at least two members.
#
#   T1  IN THE STORE: a COMPLETE fleet row for each member (package, registry,
#       namespace, image, tag, asset, release_url, repo_url, ghcr_page,
#       version_name, version_code, per-ABI assets), kind app, group apps, and
#       the row's label IS the member's id. #276: a half-filled row has no
#       symptom, it draws an empty page that looks exactly like a working one.
#   T2  LINKED IN ONE PLACE: cloud-c3 itself has a Cloud ▸ Apps ▸ Configs tile
#       targeting extapp:cloud-c3 (never page:), captioned by function (T7).
#       Every SUB-app cloud-c3-<x> has NO tile in that grid: it belongs inside
#       cloud-c3's own Apps tab, beside Watchdog / Morpheus / WatchTower, and
#       ac_cloud-c3/test/test-c3-shell.sh T5 asserts it is there. A sub-app
#       also tiled here is the same app reachable from two launchers.
#   T3  ONE NAME: the external_apps entry carries a package for the tap to open
#       and no `label` (#351/#224); its install_apk_url is the fleet's release_url.
#   M   mutation-proof: a gutted row, a page: target, a removed C3 tile, a
#       sub-app tile put back in Configs, a label put back and a wrong install
#       URL each go RED; a family of one is RED.
#
# OWN-SOURCE ONLY: this application's build.json and its generated fleet file.
# python3 and jq.
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

# check <build.json> <fleet> : all three contracts for every family member
check() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1], encoding="utf-8"))
fleet = json.load(open(sys.argv[2], encoding="utf-8")).get("apps") or []
FAMILY = re.compile(r"^cloud-c3(-[a-z0-9]+)*$")
members = [a for a in bj["ui"]["external_apps"] if FAMILY.match(a.get("id", ""))]
bad = []
if len(members) < 2:
    bad.append("only %d cloud-c3 family member(s) declared in ui.external_apps — the family has at least cloud-c3 and cloud-c3-webserver, so this tester would be checking nothing" % len(members))
configs = None
for section in bj["ui"]["sections"]:
    for group in section.get("tile_groups") or section.get("groups") or []:
        if group.get("title") == "Setup":
            configs = group
# tile groups live wherever the Cloud section keeps them; find the Configs group anywhere under ui
def walk(node):
    if isinstance(node, dict):
        yield node
        for v in node.values(): yield from walk(v)
    elif isinstance(node, list):
        for v in node: yield from walk(v)
if configs is None:
    for node in walk(bj["ui"]):
        if node.get("title") == "Setup" and isinstance(node.get("tiles"), list):
            configs = node; break
if configs is None:
    bad.append("no tile group titled 'Setup' with tiles under ui — Cloud > Apps > Setup is where the family is linked")
by_label = {}
for row in fleet:
    by_label.setdefault(row.get("label"), []).append(row)
REQUIRED = ("package", "registry", "namespace", "image", "tag", "asset", "release_url", "repo_url",
            "ghcr_page", "version_name", "version_code", "assets")
for m in members:
    mid = m["id"]
    # T1 the Store row
    rows = by_label.get(mid, [])
    if len(rows) != 1:
        bad.append("T1 %s: the fleet manifest carries %d rows labelled %r, not 1 — regen.sh scans build.json files, so the app's directory or declaration is off" % (mid, len(rows), mid))
    else:
        r = rows[0]
        missing = [k for k in REQUIRED if r.get(k) in (None, "", {}, [])]
        if missing:
            bad.append("T1 %s: the fleet row is INCOMPLETE, missing %s (#276: a half-filled row has no symptom)" % (mid, missing))
        if r.get("kind") != "app":
            bad.append("T1 %s: fleet row kind is %r, not 'app'" % (mid, r.get("kind")))
        if r.get("group") != "apps":
            bad.append("T1 %s: fleet row group is %r, not 'apps' — it would not appear under Store > Cloud apps" % (mid, r.get("group")))
        if r.get("package") != m.get("install_package"):
            bad.append("T1 %s: fleet package %r != external_apps install_package %r" % (mid, r.get("package"), m.get("install_package")))
        if m.get("install_apk_url") and m["install_apk_url"] != r.get("release_url"):
            bad.append("T3 %s: install_apk_url %r is not the fleet's release_url %r — a tap on an uninstalled app would fetch an asset that is not published" % (mid, m["install_apk_url"], r.get("release_url")))
    # T2 the Configs tile: the root app only; a sub-app lives inside cloud-c3
    if configs is not None:
        tiles = [t for t in configs["tiles"] if t.get("target") == "extapp:" + mid]
        page_tiles = [t for t in configs["tiles"] if isinstance(t.get("target"), str) and t["target"].startswith("page:") and mid.replace("cloud-", "") in t["target"]]
        if mid != "cloud-c3":
            if tiles or page_tiles:
                bad.append("T2 %s: Cloud > Apps > Configs has a tile for this cloud-c3 sub-app (%s) — it launches from inside cloud-c3 > Apps, not from the superapp grid" % (mid, [t.get("target") for t in tiles + page_tiles]))
            tiles = []
        elif not tiles:
            bad.append("T2 %s: the Cloud > Apps > Configs group has no tile targeting extapp:%s%s" % (mid, mid, " (a page: tile points at it instead)" if page_tiles else ""))
        for t in tiles:
            if t.get("label") == mid or t.get("label") in (mid.replace("cloud-", ""),):
                bad.append("T2 %s: the tile is captioned with the application's identity %r (T7: captions name the function)" % (mid, t.get("label")))
    # T3 one name
    if "label" in m:
        bad.append("T3 %s: ui.external_apps entry carries a label %r — an application has ONE name (#351), the caption is derived" % (mid, m["label"]))
    if not m.get("install_package") and not m.get("hub_package"):
        bad.append("T3 %s: the external_apps entry names no package, so there is nothing for a tap to open" % mid)
for b in bad:
    print("    " + b)
print("    members: " + ", ".join(sorted(m["id"] for m in members)))
sys.exit(1 if bad else 0)
PYTHON
}

echo "── cloud-c3 family apps (derived from ui.external_apps) ──"
check "$BJ" "$FLEET" && pass "T1-T3 every cloud-c3 family app has a complete Store row and one name; cloud-c3 is tiled in Cloud > Apps > Configs, its sub-apps are not" || fail "a family member is missing from the Store, mis-linked, or named twice"

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mutate() {
    local title="$1" mut="$2" bj="$WORK/bj$RANDOM.json" fl="$WORK/fl$RANDOM.json"
    cp "$BJ" "$bj"; cp "$FLEET" "$fl"
    "$mut" "$bj" "$fl" || { fail "M: mutation '$title' did not apply — a mutation that does not mutate proves nothing"; return; }
    if check "$bj" "$fl" >/dev/null 2>&1; then fail "M: '$title' stayed GREEN — the check is vacuous"; else pass "M: '$title' goes red"; fi
}
m_gut_row()      { python3 - "$2" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p))
hit=[a for a in d["apps"] if a.get("label")=="cloud-c3-webserver"]; assert hit
hit[0].pop("release_url"); hit[0].pop("ghcr_page"); json.dump(d,open(p,"w"))
PY
}
m_kind_lib()     { python3 - "$2" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p))
hit=[a for a in d["apps"] if a.get("label")=="cloud-c3-webserver"]; assert hit
hit[0]["kind"]="lib"; json.dump(d,open(p,"w"))
PY
}
m_page_target()  { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); n=0
def walk(x):
    global n
    if isinstance(x,dict):
        if x.get("target")=="extapp:cloud-c3": x["target"]="page:c3/health"; n+=1
        for v in x.values(): walk(v)
    elif isinstance(x,list):
        for v in x: walk(v)
walk(d["ui"]); assert n>=1, n; json.dump(d,open(p,"w"))  # n>=1: the removed Cloud C3 tab left two more extapp:cloud-c3 tiles
PY
}
m_drop_tile()    { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); n=0
def walk(x):
    global n
    if isinstance(x,dict):
        if isinstance(x.get("tiles"),list):
            before=len(x["tiles"]); x["tiles"]=[t for t in x["tiles"] if t.get("target")!="extapp:cloud-c3"]; n+=before-len(x["tiles"])
        for v in x.values(): walk(v)
    elif isinstance(x,list):
        for v in x: walk(v)
walk(d["ui"]); assert n==1, n; json.dump(d,open(p,"w"))
PY
}
m_subapp_back() { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p)); n=0
def walk(x):
    global n
    if isinstance(x,dict):
        if x.get("title")=="Setup" and isinstance(x.get("tiles"),list) and any(t.get("target")=="extapp:cloud-c3" for t in x["tiles"]):
            x["tiles"].append({"id":"c3-webserver","label":"Web Server","icon":"ic_p_watchdog","target":"extapp:cloud-c3-webserver"}); n+=1
        for v in x.values(): walk(v)
    elif isinstance(x,list):
        for v in x: walk(v)
walk(d["ui"]); assert n==1, n; json.dump(d,open(p,"w"))
PY
}
m_add_label()    { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p))
hit=[a for a in d["ui"]["external_apps"] if a.get("id")=="cloud-c3-webserver"]; assert hit
hit[0]["label"]="Web Server"; json.dump(d,open(p,"w"))
PY
}
m_wrong_url()    { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p))
hit=[a for a in d["ui"]["external_apps"] if a.get("id")=="cloud-c3-webserver"]; assert hit
hit[0]["install_apk_url"]=hit[0]["install_apk_url"].replace("Cloud-C3-WebServer.apk","Old-Name.apk"); json.dump(d,open(p,"w"))
PY
}
m_family_of_one() { python3 - "$1" <<'PY'
import json,sys; p=sys.argv[1]; d=json.load(open(p))
before=len(d["ui"]["external_apps"]); d["ui"]["external_apps"]=[a for a in d["ui"]["external_apps"] if a.get("id")!="cloud-c3-webserver"]
assert len(d["ui"]["external_apps"])==before-1; json.dump(d,open(p,"w"))
PY
}
mutate "the Store row loses release_url and ghcr_page"      m_gut_row
mutate "the Store row is kind lib"                          m_kind_lib
mutate "the C3 Configs tile points at a page: route"        m_page_target
mutate "the C3 Configs tile is removed"                     m_drop_tile
mutate "the webserver is tiled in Configs again"            m_subapp_back
mutate "the external_apps entry regains a label"            m_add_label
mutate "install_apk_url names an unpublished asset"         m_wrong_url
mutate "the family shrinks to one member"                   m_family_of_one

echo
if [ "$FAILURES" -eq 0 ]; then echo "── test-c3-family-apps: all green ──"; else echo "── test-c3-family-apps: $FAILURES FAILED ──"; exit 1; fi
