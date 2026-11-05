#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #648 — the WHOLE SuperApp c3 surface is here: same declarations, same    ║
# ║ cards, same pages, and "lost nothing" is a TESTED property               ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The first pass at #648 copied ONE fragment (C3HealthFragment) and
# reported the c3 page moved. Nothing objected, because nothing enumerated what the c3
# surface IS and diffed the copy against the enumeration. This file is that enumeration:
#
#   T1  the carried DECLARATION is complete: ui.carried_c3 holds every page id the
#       SuperApp's c3 section declares (visible, action AND hidden), and both stacks;
#       stack_observability carries the FIVE feed cards (by source), the NTFY centre
#       (stream=channels) and its Index/More rows; stack_topology carries the two address
#       cards, the TWO container dashboards (infra / user; the providers+dbs+mcpapi Stack card was dropped 2026-10-20),
#       and its Index/More rows.
#   T2  every declared panel KIND has a renderer arm in C3StackFragment, and every
#       declared feed SOURCE has a fetcher arm — a card the declaration lists must not
#       fall through to the unknown-kind row.
#   T3  the COPY MANIFEST: every enumerated source artifact of the SuperApp c3 surface
#       has its (name-adapted) counterpart file in this app — a lost file is a red, not
#       a claim.
#   T4  the DATA travelled and is BAKED: containers_declared.json / mesh.json / ui.ntfy parse
#       non-empty, and app/build.gradle bakes each BuildConfig blob the carried pages
#       decode.
#   T5  every tile TARGET in the two stacks resolves: anchor: to a declared anchor,
#       extapp: to a declared sibling, page: to a dispatcher branch or to the six
#       SuperApp sample-stub ids whose taps are STATED as not shipped here.
#   M   mutation-proof: each check above is shown able to go RED.
#
# OWN-SOURCE ONLY. python3 and grep.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-c3"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
[ -f "$APP/build.json" ] || { echo "ERROR missing $APP/build.json — unrun, not passing"; exit 1; }

# t1 <app dir> : the carried declaration is complete
t1() {
    python3 - "$1" <<'PYTHON'
import json, sys
app = sys.argv[1]
ui = json.load(open(app + "/build.json", encoding="utf-8"))["ui"]
bad = []
c3 = ui.get("carried_c3")
if c3 is None:
    print("    ui.carried_c3 is missing — the ENTIRE SuperApp declaration block is gone"); sys.exit(1)
page_ids = [p.get("id") for p in (c3.get("pages") or [])]
want_pages = ["topology", "observability", "watchdog", "morpheus", "watchtower",
              "reports", "stack", "health", "workflows", "vms", "logs", "dagu", "gha"]
for pid in want_pages:
    if pid not in page_ids:
        bad.append("ui.carried_c3.pages lost %r — the SuperApp declares it (visible, action or hidden) "
                   "and a copy that drops a declaration is the half-copy this ticket reopened over" % pid)
obs = c3.get("stack_observability") or []
topo = c3.get("stack_topology") or []
if not obs:  bad.append("stack_observability is missing or empty — the whole Observability page is gone")
if not topo: bad.append("stack_topology is missing or empty — the whole Topology page is gone")
sources = [p.get("source") for p in obs if p.get("kind") == "feed"]
for s in ("github_run_stats", "github_runs", "dagu_runs", "github_commits", "gitea_commits"):
    if s not in sources:
        bad.append("stack_observability lost the %r feed card — five cards is the declaration, not a suggestion" % s)
if not any(p.get("kind") == "notification_center" and p.get("stream") == "channels" for p in obs):
    bad.append("stack_observability lost the NTFY centre (kind=notification_center, stream=channels)")
if sum(1 for p in obs if p.get("kind") == "tile_row") < 2:
    bad.append("stack_observability has fewer than two tile_rows — the Index and More rows are part of the page")
infra = []   # 2026-10-20: stack_infra is gone on purpose - the Infra section is five pages over data/fleet_slice.json (test-c3-infra.sh)
if "stack_infra" in c3: bad.append("stack_infra is back: Infra is the five fleet pages, not the SuperApp's Stack card")
# 2026-10-16: the two address cards left Topology; their data lives on in the Containers > Table page, so
# 'lost nothing' is now: no row the cards showed is missing from data/infra_slice.json::routes.
kinds_topo = [p.get("kind") for p in topo]
for k in ("c3_public", "c3_private"):
    if k in kinds_topo:
        bad.append("stack_topology still embeds the %r address card - it moved to the Table page" % k)
import os
_here = os.path.abspath(app)
_slice = {"routes": json.load(open(os.path.join(_here, "data", "routes.json"), encoding="utf-8"))}
_have = {(r.get("container"), bool(r.get("public_url"))) for r in _slice.get("routes") or []}
for fn, key, has_pub in (("services_public.json", "name", True), ("services_private.json", "name", False)):
    for row in json.load(open(os.path.join(_here, "data", fn), encoding="utf-8")):
        if (row[key], has_pub) not in _have:
            bad.append("the Table page lost %s row %r that the address cards showed" % (fn, row[key]))
dash = [p for p in topo + infra if p.get("kind") == "cloud_dashboard"]
if len(dash) != 2:
    bad.append("stack_topology carries %d cloud_dashboard cards, not the two Containers groups "
               "(Infra Apps, Cloud Apps; Project Apps is gone, #1454)" % len(dash))
dash_groups = sorted(tuple(sorted(([p.get("group")] if p.get("group") else []) + (p.get("groups") or []))) for p in dash)
if dash_groups != sorted([("infra",), ("cloud",)]):
    bad.append("the dashboards' group bindings are %s, not infra / cloud" % dash_groups)
for _name, _stack in (("stack_topology", topo),):
    if not any(p.get("kind") == "tile_row" and p.get("title") == "Index" for p in _stack):
        bad.append("%s lost its Index row" % _name)
    if any(p.get("kind") == "tile_row" and p.get("title") == "More" for p in _stack):
        bad.append("%s has a More row - owner item 10 removed the More table" % _name)
if not (ui.get("ntfy") or {}).get("channels"):
    bad.append("ui.ntfy.channels is missing or empty — the NTFY centre would list no channel")
for key in ("labels", "scopes", "taxon"):
    if key not in (ui.get("ntfy") or {}):
        bad.append("ui.ntfy lost %r — part of the SuperApp catalog the cards resolve through" % key)
# the six stub ids must NOT be declared as pages of this app's tabs (#648: no placeholder)
for tab in ("infra", "containers", "observ", "ops", "configs"):
    sec = next((s for s in (ui.get("sections") or []) if s.get("id") == tab), {})
    for p in (sec.get("pages") or []):
        if p.get("id") in ("reports", "stack", "workflows", "vms", "logs", "gha"):
            bad.append("ui.sections[%s] declares %r, a SuperApp sample-stub page with no fragment anywhere — "
                       "declaring it here ships a placeholder" % (tab, p.get("id")))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t2 <app dir> : every declared kind and feed source has a renderer arm
t2() {
    python3 - "$1" <<'PYTHON'
import json, re, sys
app = sys.argv[1]
ui = json.load(open(app + "/build.json", encoding="utf-8"))["ui"]
c3 = ui.get("carried_c3") or {}
panels = (c3.get("stack_observability") or []) + (c3.get("stack_topology") or []) + (c3.get("stack_infra") or [])
src = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/cloud/C3StackFragment.kt",
           encoding="utf-8").read()
code = re.sub(r"/\*.*?\*/", "", src, flags=re.S)
code = re.sub(r"//[^\n]*", "", code)
bad = []
kinds = {p.get("kind") for p in panels if p.get("kind")}
for k in sorted(kinds):
    if k == "section_title":
        if "SectionTitle(" not in code:
            bad.append("kind 'section_title' has no renderer (SectionTitle gone)")
        continue
    if not re.search(r'"%s"\s*->' % re.escape(k), code):
        bad.append("declared panel kind %r has NO arm in C3StackFragment — the card would render "
                   "the unknown-kind row, a placeholder in a shipped tab" % k)
for s in sorted({p.get("source") for p in panels if p.get("kind") == "feed" and p.get("source")}):
    if not re.search(r'"%s"\s*->' % re.escape(s), code):
        bad.append("declared feed source %r has NO fetcher arm in renderFeed" % s)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t3 <app dir> : the copy manifest — every enumerated artifact has its counterpart
t3() {
    python3 - "$1" <<'PYTHON'
import os, re, sys
app = sys.argv[1]
K = "app/src/main/java/com/diegonmarcos/cloudc3"
R = "app/src/main/res"
# source (aa_cloud-superapp / ab_cloud-libs-shared) → destination in THIS app, with the
# symbol the destination must still declare. libs:ops entries are shared BY REFERENCE
# (settings.gradle `dir`), so their "counterpart" is the module wiring, checked below.
manifest = [
    ("app/.../cloud/C3HealthFragment.kt",   K + "/cloud/C3HealthFragment.kt",   "class C3HealthFragment"),
    ("app/.../cloud/C3MeshFragment.kt",     K + "/cloud/C3MeshFragment.kt",     "class C3MeshFragment"),
    ("app/.../cloud/MeshView.kt",           K + "/cloud/MeshView.kt",           "object MeshView"),
    ("app/.../cloud/CloudData.kt",          K + "/cloud/CloudData.kt",          "object CloudData"),
    ("app/.../cloud/ContainerConfigs.kt",   K + "/cloud/ContainerConfigs.kt",   "object ContainerConfigs"),
    ("app/.../cloud/ContainerSheet.kt",     K + "/cloud/ContainerSheet.kt",     "object ContainerSheet"),
    ("app/.../cloud/DaguRunsFeed.kt",       K + "/cloud/DaguRunsFeed.kt",       "object DaguRunsFeed"),
    ("app/.../cloud/GiteaFeed.kt",          K + "/cloud/GiteaFeed.kt",          "object GiteaFeed"),
    ("app/.../cloud/GitHubFeed.kt",         K + "/cloud/GitHubFeed.kt",         "object GitHubFeed"),
    ("app/.../cloud/OpsClient.kt",          K + "/cloud/OpsClient.kt",          "object OpsClient"),
    ("app/.../launcher/AggregatorStackFragment.kt (c3 kinds)", K + "/cloud/C3StackFragment.kt", "class C3StackFragment"),
    ("app/.../launcher/StackAnchors.kt",    K + "/cloud/StackAnchors.kt",       "class StackAnchors"),
    ("app/.../launcher/StackFilters.kt",    K + "/cloud/StackFilters.kt",       "object StackFilters"),
    ("app/.../launcher/IndexTiles.kt",      K + "/cloud/IndexTiles.kt",         "object IndexTiles"),
    ("app/.../launcher/Sections.kt (stack/dashboard/mesh models)", K + "/cloud/Stacks.kt", "object Stacks"),
    ("app/.../launcher/Sections.kt (service tables)", K + "/cloud/Services.kt", "object Services"),
    ("app/.../rss/NtfyCatalog.kt",          K + "/cloud/NtfyCatalog.kt",        "object NtfyCatalog"),
    ("app/.../rss/NtfyScopes.kt",           K + "/cloud/NtfyScopes.kt",         "object NtfyScopes"),
    ("app/.../ui/StatusLight.kt",           K + "/cloud/StatusLight.kt",        "object StatusLight"),
    ("app/.../ui/Haptics.kt (tap)",         K + "/cloud/Haptics.kt",            "object Haptics"),
    ("res/xml/network_security_config.xml", R + "/xml/network_security_config.xml", "10.0.0.6"),
    ("data/services_public.json",           "data/services_public.json",        "public_url"),
    ("data/services_private.json",          "data/services_private.json",       "private_dns"),
    ("data/containers_declared.json",       "data/containers_declared.json",    "groups"),
    ("data/mesh.json",                      "data/mesh.json",                   "transports"),
]
drawables = ["ic_p_c3_stack", "ic_p_c3_health", "ic_p_c3_workflows", "ic_p_c3_reports",
             "ic_p_c3_vms", "ic_p_logs", "ic_p_sol_cloud", "ic_code", "ic_robot", "ic_rss",
             "ic_wg", "ic_link_tile", "ic_chevron_right", "ic_check_all"]
for d in drawables:
    manifest.append(("res/drawable/%s.xml" % d, R + "/drawable/%s.xml" % d, "<"))
bad = []
for src, dst, needle in manifest:
    p = os.path.join(app, dst)
    if not os.path.isfile(p):
        bad.append("LOST: %s has no counterpart %s" % (src, dst))
        continue
    if needle not in open(p, encoding="utf-8").read():
        bad.append("HOLLOW: %s exists but no longer carries %r — the file is there and the "
                   "content is not" % (dst, needle))
# libs:ops travels by reference: the module must be declared and the app must depend on it,
# or DaguFragment/DaguPrefs (page:c3/dagu, the bearer store) silently fall out of the build.
import json
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
mods = bj.get("modules") or {}
if "libs:ops" not in mods:
    bad.append("LOST: modules carries no libs:ops — DaguFragment (page:c3/dagu) and DaguPrefs are gone")
elif "libs:ops" not in ((mods.get("app") or {}).get("depends_on") or []):
    bad.append("libs:ops is declared but app does not depend_on it — present and unlinked")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t4 <app dir> : the data parses non-empty and every carried blob is BAKED
t4() {
    python3 - "$1" <<'PYTHON'
import json, sys
app = sys.argv[1]
bad = []
try:
    cs = json.load(open(app + "/data/containers_declared.json", encoding="utf-8"))
    ids = [g.get("id") for g in (cs.get("groups") or [])]
    for g in ("infra", "cloud", "project"):
        if g not in ids:
            bad.append("data/containers_declared.json has no group %r - a dashboard card would render empty" % g)
    for g in ids:
        if g not in ("infra", "cloud", "project"):
            bad.append("data/containers_declared.json carries the group %r: only Infra Apps, Cloud Apps and Project Apps exist (#1148)" % g)
except Exception as e:
    bad.append("data/containers_declared.json does not parse: %s" % e)
try:
    mesh = json.load(open(app + "/data/mesh.json", encoding="utf-8"))
    if not mesh.get("nodes") or not mesh.get("transports"):
        bad.append("data/mesh.json carries no nodes/transports — the WG mesh page would render empty")
except Exception as e:
    bad.append("data/mesh.json does not parse: %s" % e)
import re
gradle = open(app + "/app/build.gradle", encoding="utf-8").read()
for field in ("UI_STACK_TOPOLOGY_B64", "UI_STACK_OBSERV_B64", "UI_NTFY_B64",
              "UI_TILE_COLUMNS", "CONTAINERS_DECLARED_B64", "MESH_JSON_B64",
              "SERVICES_PUBLIC_B64", "SERVICES_PRIVATE_B64"):
    # The buildConfigField CALL, not the name appearing in a comment: a comment that
    # remembers a bake is exactly what would keep this green after the bake was dropped.
    if not re.search(r'buildConfigField\s+"(?:String(?:\[\])?|int)",\s*"%s"' % field, gradle):
        bad.append("app/build.gradle never bakes %s — the declaration exists and the phone "
                   "would decode an empty blob that looks exactly like a working one (#276)" % field)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

# t5 <app dir> : every stack tile target resolves — no unclassified dead tap
t5() {
    python3 - "$1" <<'PYTHON'
import json, re, sys
app = sys.argv[1]
ui = json.load(open(app + "/build.json", encoding="utf-8"))["ui"]
c3 = ui.get("carried_c3") or {}
bad = []
main = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/MainActivity.kt", encoding="utf-8").read()
main_code = re.sub(r"/\*.*?\*/", "", main, flags=re.S)
main_code = re.sub(r"//[^\n]*", "", main_code)
dispatched_pages = {lit for grp in re.findall(r'((?:"[a-z0-9_]+/[a-z0-9_-]+"\s*,\s*)*"[a-z0-9_]+/[a-z0-9_-]+")\s*->', main_code)
                    for lit in re.findall(r'"([^"]+)"', grp)}
extapp_ids = {a.get("id") for a in (ui.get("external_apps") or [])}
stub_pages = {"c3/reports", "c3/stack", "c3/workflows", "c3/vms", "c3/logs", "c3/gha"}
for stack_key in ("stack_observability", "stack_topology"):
    panels = c3.get(stack_key) or []
    anchors = set()
    for p in panels:
        if p.get("anchor"): anchors.add(p["anchor"])
        for a in (p.get("anchors") or []):
            if a.get("id"): anchors.add(a["id"])
    for p in panels:
        for t in (p.get("tiles") or []):
            tgt = t.get("target") or ""
            if tgt.startswith("anchor:"):
                if tgt[len("anchor:"):] not in anchors:
                    bad.append("%s tile %r targets %r but no panel declares that anchor — a dead "
                               "scroll" % (stack_key, t.get("id"), tgt))
            elif tgt.startswith("extapp:"):
                if tgt.split(":", 1)[1].split("/")[0].split("#")[0] not in extapp_ids:
                    bad.append("%s tile %r targets %r but ui.external_apps declares no such id" %
                               (stack_key, t.get("id"), tgt))
            elif tgt.startswith("page:"):
                page = tgt[len("page:"):].split("#")[0]
                if page not in dispatched_pages and page not in stub_pages:
                    bad.append("%s tile %r targets %r — neither a dispatcher branch in MainActivity "
                               "nor a known SuperApp stub id: an UNCLASSIFIED dead tap" %
                               (stack_key, t.get("id"), tgt))
            elif tgt.startswith("http"):
                pass
            else:
                bad.append("%s tile %r has target %r, a grammar nothing here dispatches" %
                           (stack_key, t.get("id"), tgt))
# the Analytics card's handoff row
for p in (c3.get("stack_observability") or []):
    u = p.get("url") or ""
    if u.startswith("extapp:") and u.split(":", 1)[1].split("/")[0] not in extapp_ids:
        bad.append("panel %r hands off to %r but ui.external_apps declares no such id" % (p.get("title"), u))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PYTHON
}

echo "── #648 cloud-c3: the WHOLE c3 surface, and 'lost nothing' as a property ──"
t1 "$APP" && pass "T1 the carried declaration is complete: every page id, five feed cards, the NTFY centre, two dashboards, both address cards, Index+More rows" || fail "T1 the carried declaration lost something"
t2 "$APP" && pass "T2 every declared panel kind and feed source has a renderer arm" || fail "T2 a declared card has no renderer"
t3 "$APP" && pass "T3 the copy manifest holds: every enumerated artifact has its counterpart, with its content" || fail "T3 a copied artifact is lost or hollow"
t4 "$APP" && pass "T4 the data travelled and every carried blob is baked into BuildConfig" || fail "T4 data missing or a blob unbaked"
t5 "$APP" && pass "T5 every stack tile target resolves (anchor/extapp/page), none unclassified" || fail "T5 a tile is a dead tap"

# ── MUTATION PROOF ─────────────────────────────────────────────────────────
echo
echo "── mutation proof: each check must be able to go RED ──"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <setup-fn>
    local name="$1" check="$2" setup="$3"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    cp -r "$APP" "$WORK/t/ac_cloud-c3"
    local COPY="$WORK/t/ac_cloud-c3"
    if ! "$check" "$COPY" >/dev/null 2>&1; then
        echo "  VOID  $name — the unmutated COPY is already red"; MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if ! "$setup" "$COPY" >/dev/null 2>&1; then
        echo "  VOID  $name — the mutation itself failed to apply"; MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$COPY" >/dev/null 2>&1; then
        echo "  HOLLOW  $name — the check stayed GREEN under the mutation"; MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}

pyjson() { python3 - "$1" "$2" <<'PY'
import json, sys, collections
p, expr = sys.argv[1], sys.argv[2]
d = json.load(open(p), object_pairs_hook=collections.OrderedDict)
exec(expr, {"d": d})
json.dump(d, open(p, "w"), indent=2)
PY
}

m_drop_feed()   { pyjson "$1/build.json" 'c3=d["ui"]["carried_c3"]; c3["stack_observability"]=[p for p in c3["stack_observability"] if p.get("source")!="gitea_commits"]'; }
m_drop_arm()    { python3 - "$1/app/src/main/java/com/diegonmarcos/cloudc3/cloud/C3StackFragment.kt" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        "dagu_runs"        -> daguRunsFeed(ctx, panel)\n','')
assert s!=b
open(p,"w").write(s)
PY
}
m_lose_file()   { rm "$1/app/src/main/java/com/diegonmarcos/cloudc3/cloud/NtfyCatalog.kt"; }
m_hollow_file() { printf 'package com.diegonmarcos.cloudc3.cloud\n' > "$1/app/src/main/java/com/diegonmarcos/cloudc3/cloud/GiteaFeed.kt"; }
m_unbake()      { python3 - "$1/app/build.gradle" <<'PY'
import sys
p=sys.argv[1]; s=open(p).read(); b=s
s=s.replace('        buildConfigField "String[]", "CONTAINERS_DECLARED_B64", containersDeclaredB64Array\n','')
assert s!=b
open(p,"w").write(s)
PY
}
m_table_row_lost() { pyjson "$1/data/routes.json" 'd[:]=d[1:]'; }
m_dead_anchor() { pyjson "$1/build.json" 'c3=d["ui"]["carried_c3"]; c3["stack_topology"][0]["tiles"][0]["target"]="anchor:no-such-card"'; }
m_orphan_page() { pyjson "$1/build.json" 'c3=d["ui"]["carried_c3"]; c3["stack_observability"][-1]["tiles"][0]["target"]="page:c3/nowhere"'; }
m_drop_module() { pyjson "$1/build.json" 'del d["modules"]["libs:ops"]'; }

mutate "a feed card is dropped from the declaration"        t1 m_drop_feed
mutate "a feed source loses its fetcher arm"                t2 m_drop_arm
mutate "a copied source file is deleted"                    t3 m_lose_file
mutate "a copied file is emptied but left in place"         t3 m_hollow_file
mutate "libs:ops is dropped from the module graph"          t3 m_drop_module
mutate "a carried blob is no longer baked"                  t4 m_unbake
mutate "the Table page loses a row the address cards showed" t1 m_table_row_lost
mutate "an Index tile targets an undeclared anchor"         t5 m_dead_anchor
mutate "a More tile targets a page nothing dispatches"      t5 m_orphan_page

echo
if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  5 properties asserted, 9 mutations each proved able to go red"
