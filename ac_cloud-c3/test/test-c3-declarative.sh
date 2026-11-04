#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3 #1454 - the platform rows, the project pages and the nav are DECLARED, never typed in Kotlin ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Owner requirement (2026-11-04): everything is declarative. The Infra platform rows (Containers-as-Service,
# Containers-Node-Python), the Containers project sub-pages and the container -> project mapping come from JSON
# declarations (data/platforms.json + the registry slice, data/container_projects.json, build.json); the nav (the project
# strip, the Topology | Table switch under each, Agents under Ops) lives in build.json; the owner's PRIVATE email
# identities are not in this public repo at all. This tester fails when a page hard-codes any of them.
#
#   D1  no platform label or id, no project label or id, is spelled as a string literal in the app's Kotlin
#   D2  the nav: every Containers project page nests exactly Topology | Table; Agents is an Ops page and not a Containers one
#   D3  the project strip, data/container_projects.json and the Kotlin agree with no list in between (ids match, codes look like registry codes)
#   D4  the platform rows are declared: platforms.json entries reach fleet_slice.json (container_platforms / hosting), and the Infra pages read them from the slice
#   D5  NO PERSONAL EMAIL ADDRESS in this app's tree (build.json, data, Kotlin, tests, scripts); the ONLY exception is data/public_identities.json, exactly the 3 owner-declared public ones
#   M   mutation-proof: each check above is shown able to go RED on a mutated copy.
#
# OWN-SOURCE ONLY. python3.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
KT="app/src/main/java/com/diegonmarcos/cloudc3"
FAILURES=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent - a verdict from a missing tool is not a verdict"; exit 1; }
for f in build.json data/platforms.json data/container_projects.json data/fleet_slice.json "$KT/pages/InfraPages.kt" "$KT/pages/TabFragments.kt"; do
    [ -f "$APP/$f" ] || { echo "ERROR missing $APP/$f - unrun, not passing"; exit 1; }
done

# d1 <app dir> : no declared name is spelled as a string literal in main Kotlin
d1() { python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
names = set()
pl = json.load(open(app + "/data/platforms.json", encoding="utf-8"))
for k in ("container_platforms", "hosting_platforms"):
    for p in pl.get(k) or []:
        names |= {p["id"], p["label"]}
cp = json.load(open(app + "/data/container_projects.json", encoding="utf-8"))
for p in cp["projects"]:
    names |= {p["id"], p["label"]}
    names |= set(p.get("folder_prefix") or []) | set(p.get("name_prefix") or [])
names -= {"all"}   # the All page is the absence of a project: no declaration names it
if len(names) < 12: print("    only %d declared names - the guard would be hollow" % len(names)); sys.exit(1)
bad = []
root = app + "/app/src/main/java"
for dp, _d, files in os.walk(root):
    for f in files:
        if not f.endswith(".kt"): continue
        p = os.path.join(dp, f)
        t = open(p, encoding="utf-8").read()
        code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", t, flags=re.S))
        for lit in re.findall(r'"((?:[^"\\\n]|\\.)*)"', code):
            if lit in names:
                bad.append("%s spells the declared name %r - read it from the declaration" % (os.path.relpath(p, app), lit))
for b in sorted(set(bad)): print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d2 <app dir> : nav
d2() { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
ui = json.load(open(app + "/build.json", encoding="utf-8"))["ui"]
secs = {s["id"]: s for s in ui["sections"]}
bad = []
cont = secs["containers"].get("pages") or []
if len(cont) < 2: bad.append("Containers declares %d project pages" % len(cont))
for p in cont:
    want = [p["id"] + "_topology", p["id"] + "_table"]
    got = [q["id"] for q in p.get("pages") or []]
    if got != want: bad.append("Containers page %r nests %s, not the Topology | Table switch %s" % (p["id"], got, want))
    labels = [q["label"] for q in p.get("pages") or []]
    if labels != ["Topology", "Table"]: bad.append("Containers page %r labels its switch %s" % (p["id"], labels))
if [p["label"] for p in cont] != ["All", "Cloudy", "Cloud-me", "CG", "Capital", "Hardtech", "Others"]:
    bad.append("the Containers strip reads %s" % [p["label"] for p in cont])
ops = [p["id"] for p in secs["ops"]["pages"]]
if "agents" not in ops: bad.append("Agents is not an Ops page")
def ids(pages): return [p["id"] for p in pages] + [i for p in pages for i in ids(p.get("pages") or [])]
if "agents" in ids(cont): bad.append("Agents is still under Containers")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d3 <app dir> : strip == mapping
d3() { python3 - "$1" <<'PY'
import json, re, sys
app = sys.argv[1]
ui = json.load(open(app + "/build.json", encoding="utf-8"))["ui"]
strip = [p["id"] for p in next(s for s in ui["sections"] if s["id"] == "containers")["pages"]]
cp = json.load(open(app + "/data/container_projects.json", encoding="utf-8"))
declared = [p["id"] for p in cp["projects"]]
bad = []
if sorted(declared) != sorted(i for i in strip if i != "all"): bad.append("the strip %s and container_projects.json %s name different projects" % (strip, declared))
if cp.get("default") not in declared: bad.append("the default project %r is not declared" % cp.get("default"))
for p in cp["projects"]:
    if p["id"] != cp.get("default") and not re.fullmatch(r"\d+(\.\d+)*", p.get("code", "")): bad.append("project %r has no registry code" % p["id"])
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d4 <app dir> : platform rows are declared and reach the slice
d4() { python3 - "$1" <<'PY'
import json, re, sys
app = sys.argv[1]
pl = json.load(open(app + "/data/platforms.json", encoding="utf-8"))
sl = json.load(open(app + "/data/fleet_slice.json", encoding="utf-8"))
bad = []
have_c = {p["id"] for p in sl.get("container_platforms") or []}
have_h = {p["id"] for p in sl.get("hosting") or []}
for p in pl.get("container_platforms") or []:
    if p["id"] not in have_c: bad.append("container platform %r is declared but absent from fleet_slice.json - run data/gen_fleet_slice.py" % p["id"])
for p in pl.get("hosting_platforms") or []:
    if p["id"] not in have_h: bad.append("hosting platform %r is declared but absent from fleet_slice.json - run data/gen_fleet_slice.py" % p["id"])
if not any(p.get("services") for p in sl.get("container_platforms") or []): bad.append("no container platform carries a service count READ from the registry")
if not any(p.get("project") for p in sl.get("container_platforms") or []): bad.append("no container platform carries a project READ from the registry")
gen = open(app + "/data/gen_fleet_slice.py", encoding="utf-8").read()
if 'os.path.join(HERE, "platforms.json")' not in gen: bad.append("the generator does not read data/platforms.json")
infra = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt", encoding="utf-8").read()
for must in ("slice.containerPlatforms", "slice.hosting"):
    if must not in infra: bad.append("InfraPages.kt does not read %s" % must)
if re.search(r"slice\.serverless", re.sub(r"//[^\n]*", "", infra)): bad.append("Containers-as-Service lists containers (slice.serverless): it lists platforms")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d5 <app dir> : no personal address anywhere in the app tree
d5() { python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
rx = re.compile(r"[A-Za-z0-9._%+-]+@(?:gmail|googlemail|live|hotmail|outlook|yahoo|proton|protonmail|icloud|me|msn)\.[A-Za-z.]+", re.I)
# the ONE file allowed to hold personal addresses, and exactly this set (owner-declared public, 2026-11-04)
ALLOWED_FILE = os.path.join("data", "public_identities.json")
ALLOWED = {u + "@" + h for u, h in (("diegonmarcos", "live.com"), ("diegonmarcos1", "gmail.com"), ("42.d13.go", "gmail.com"))}  # split so this file holds none
bad = []
for dp, dn, files in os.walk(app):
    dn[:] = [d for d in dn if d not in (".git", "build", ".gradle", "node_modules")]
    for f in files:
        if f.endswith((".png", ".webp", ".jpg", ".ttf", ".so", ".jar", ".apk", ".zst")): continue
        p = os.path.join(dp, f)
        try: t = open(p, encoding="utf-8").read()
        except Exception: continue
        rel = os.path.relpath(p, app)
        if rel == ALLOWED_FILE:
            found = {x.group(0).lower() for x in rx.finditer(t)}
            if found != ALLOWED: bad.append("%s must hold exactly the 3 declared public addresses" % rel)
            continue
        for m in [x.group(0) for x in rx.finditer(t)]:
            bad.append("%s holds a personal-looking address (%s...) - the owner's identities are private data, loaded at run time" % (os.path.relpath(p, app), m[:2]))
src = json.load(open(app + "/build.json", encoding="utf-8"))["ui"].get("private_sources", {}).get("identities") or {}
for k in ("owner", "repo", "path"):
    if not src.get(k): bad.append("ui.private_sources.identities has no %s" % k)
for b in sorted(set(bad)): print("    " + b)
sys.exit(1 if bad else 0)
PY
}

d1 "$APP" && pass "D1 no platform or project name is a string literal in Kotlin" || fail "D1 a page hard-codes a declared platform or project"
d2 "$APP" && pass "D2 every project nests Topology | Table; Agents is under Ops" || fail "D2 the nav declaration is wrong"
d3 "$APP" && pass "D3 the project strip and the container -> project mapping agree" || fail "D3 the strip and the mapping disagree"
d4 "$APP" && pass "D4 the platform rows are declared, reach the slice, and the Infra pages read the slice" || fail "D4 the platform rows are not declarative"
d5 "$APP" && pass "D5 no personal email address in the app tree; the private source is a declaration" || fail "D5 a personal address or an undeclared private source"

# ── M: every check able to go RED ───────────────────────────────────────────
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
MUT_FAIL=0
mutate() { # <name> <check-fn> <setup-fn>
    local name="$1" check="$2" setup="$3"
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    cp -r "$APP/build.json" "$APP/data" "$APP/app" "$APP/test" "$WORK/t/" 2>/dev/null
    local COPY="$WORK/t"
    if ! "$check" "$COPY" >/dev/null 2>&1; then
        echo "  VOID  $name - the unmutated COPY is already red"; MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if ! "$setup" "$COPY" >/dev/null 2>&1; then
        echo "  VOID  $name - the mutation itself failed to apply"; MUT_FAIL=$((MUT_FAIL + 1)); return
    fi
    if "$check" "$COPY" >/dev/null 2>&1; then
        echo "  HOLLOW  $name - the check stayed GREEN under the mutation"; MUT_FAIL=$((MUT_FAIL + 1))
    else
        echo "  RED     $name"
    fi
}
pyjson() { python3 - "$1" "$2" <<'PY'
import json, sys, collections
p, expr = sys.argv[1], sys.argv[2]
d = json.load(open(p), object_pairs_hook=collections.OrderedDict)
exec(expr, {"d": d, "json": json})
json.dump(d, open(p, "w"), indent=2)
PY
}
append() { printf '%s\n' "$2" >> "$1"; }
m_kt_project()   { append "$1/$KT/pages/TabFragments.kt" 'val projectsTyped = listOf("Hardtech", "Capital")'; }
m_kt_platform()  { append "$1/$KT/pages/InfraPages.kt" 'val platformsTyped = listOf("Google Firebase")'; }
m_kt_prefix()    { append "$1/$KT/pages/ContainerProjects.kt" 'val folderTyped = "user-fin_"'; }
m_no_switch()    { pyjson "$1/build.json" 'next(s for s in d["ui"]["sections"] if s["id"]=="containers")["pages"][2]["pages"].pop()'; }
m_agents_back()  { pyjson "$1/build.json" 'next(s for s in d["ui"]["sections"] if s["id"]=="containers")["pages"].append({"id":"agents","label":"Agents","icon":"ic_robot"})'; }
m_no_agents()    { pyjson "$1/build.json" 'o=next(s for s in d["ui"]["sections"] if s["id"]=="ops"); o["pages"]=[p for p in o["pages"] if p["id"]!="agents"]'; }
m_extra_page()   { pyjson "$1/build.json" 'next(s for s in d["ui"]["sections"] if s["id"]=="containers")["pages"].append({"id":"ghost","label":"Ghost","icon":"ic_p_c3_stack","pages":[{"id":"ghost_topology","label":"Topology","icon":"ic_p_c3_stack"},{"id":"ghost_table","label":"Table","icon":"ic_link_tile"}]})'; }
m_platform_gone(){ pyjson "$1/data/fleet_slice.json" 'd["hosting"]=d["hosting"][1:]'; }
m_lists_ctrs()   { append "$1/$KT/pages/InfraPages.kt" 'val x = slice.serverless'; }
m_email_json()   { pyjson "$1/build.json" 'd["description"]=d.get("description","")+" mail someone"+"@"+"gmail.com"'; }
m_email_kt()     { append "$1/$KT/pages/PrivateData.kt" 'val e = "someone'"@"'live.com"'; }
m_email_data()   { pyjson "$1/data/public_identities.json" 'd["emails"].append({"address":"extra"+"@"+"gmail.com"})'; }
m_email_other()  { printf '{"x":"diegonmarcos%s"}\n' '@live.com' > "$1/data/zz_other.json"; }
m_no_source()    { pyjson "$1/build.json" 'del d["ui"]["private_sources"]'; }

mutate "a project label is typed into Kotlin"            d1 m_kt_project
mutate "a platform label is typed into Kotlin"           d1 m_kt_platform
mutate "a folder prefix is typed into Kotlin"            d1 m_kt_prefix
mutate "a project loses its Topology | Table switch"     d2 m_no_switch
mutate "Agents is back under Containers"                 d2 m_agents_back
mutate "Agents leaves Ops"                               d2 m_no_agents
mutate "the strip gains a project the mapping lacks"     d3 m_extra_page
mutate "a declared hosting platform never reaches the slice" d4 m_platform_gone
mutate "Containers-as-Service lists containers again"    d4 m_lists_ctrs
mutate "a personal address lands in build.json"          d5 m_email_json
mutate "a personal address lands in Kotlin"              d5 m_email_kt
mutate "a 4th address lands in public_identities.json"    d5 m_email_data
mutate "an allowed address is copied into another file"   d5 m_email_other
mutate "the private source declaration is removed"       d5 m_no_source

if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  5 properties asserted, 12 mutations each proved able to go red"
