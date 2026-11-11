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
#   D6  APIs row (#1471): data/api_providers.json is the one declaration; build.json declares the row and a page per group; Kotlin spells no provider or group; a secret is a NAME (env var / vault key / store key) and nothing else
#   D5 also scans the whole tree for secret-SHAPED values (provider key prefixes, bearer tokens, private-key blocks), so no secret value can land anywhere
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
for f in build.json data/api_providers.json "$KT/pages/ApiProviders.kt" data/platforms.json data/container_projects.json data/fleet_slice.json "$KT/pages/InfraPages.kt" "$KT/pages/TabFragments.kt"; do
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
ap = json.load(open(app + "/data/api_providers.json", encoding="utf-8"))
for g in ap["groups"]: names |= {g["id"], g["label"], g["page"]}
for p in ap["providers"]: names |= {p["id"], p["label"]}
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
def ids(pages): return [p["id"] for p in pages] + [i for p in pages for i in ids(p.get("pages") or [])]
ops = ids(secs["ops"]["pages"])
if "orch_agents" not in ops: bad.append("Agents is not an Ops page")
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
SECRET = re.compile(r"sk-or-v1-[A-Za-z0-9]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|apify_api_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{20,}\.[A-Za-z0-9_-]{10,}|(?i:bearer) +[A-Za-z0-9._~+/=-]{24,}|-----BEGIN [A-Z ]*PRIVATE KEY-----")
bad = []
for dp, dn, files in os.walk(app):
    dn[:] = [d for d in dn if d not in (".git", "build", ".gradle", "node_modules")]
    for f in files:
        if f.endswith((".png", ".webp", ".jpg", ".ttf", ".so", ".jar", ".apk", ".zst")): continue
        p = os.path.join(dp, f)
        try: t = open(p, encoding="utf-8").read()
        except Exception: continue
        rel = os.path.relpath(p, app)
        for m in SECRET.finditer(t):
            bad.append("%s holds a secret-shaped value (%s...) - only secret NAMES may be written, never a value" % (rel, m.group(0)[:4]))
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

# d6 <app dir> : the APIs row is declared; secrets are names only
d6() { python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bj = json.load(open(app + "/build.json", encoding="utf-8"))
ap = json.load(open(app + "/data/api_providers.json", encoding="utf-8"))
infra = next(s for s in bj["ui"]["sections"] if s["id"] == "infra")
rows = [g["label"] for g in infra["groups"]]
bad = []
if rows != ["Bare", "APIs", "Personal", "Domains"]: bad.append("the Infra rows read %s, not Bare | APIs | Personal | Domains" % rows)
row = next((g for g in infra["groups"] if g["id"] == "apis"), {})
pages = {p["id"] for p in infra["pages"]}
gids = [g["id"] for g in ap["groups"]]
if gids[:2] != ["ai", "scraper"]: bad.append("the API groups read %s, not AI then Scraper" % gids)
if (row.get("pages") or []) != [g["page"] for g in ap["groups"]]: bad.append("the APIs row lists %s, not the page of each declared group %s" % (row.get("pages"), [g["page"] for g in ap["groups"]]))
for g in ap["groups"]:
    if g["page"] not in pages: bad.append("group %r answers page %r that build.json does not declare" % (g["id"], g["page"]))
ids = [p["id"] for p in ap["providers"]]
if len(set(ids)) != len(ids): bad.append("duplicate provider ids")
if not {"openrouter", "claude", "apify", "crawlee", "cloudflare-browser-run"} <= set(ids): bad.append("OpenRouter, Claude, Apify, Cloudy Crawlee and Cloudflare Browser Run must all be declared (got %s)" % ids)
KEYS = {"id", "label", "group", "category", "links", "used_by", "auth", "secrets"}
NAME = re.compile(r"[A-Za-z][A-Za-z0-9_./-]{2,63}")
KIND = re.compile(r"env|vault|store [a-z_]+")
SECRET = re.compile(r"sk-or-v1-[A-Za-z0-9]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|apify_api_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{20,}|(?i:bearer) +[A-Za-z0-9._~+/=-]{24,}")
for p in ap["providers"]:
    if p.get("group") not in gids: bad.append("provider %r is in no declared group" % p["id"])
    if set(p) - KEYS: bad.append("provider %r has undeclared keys %s - there is no field for a value" % (p["id"], sorted(set(p) - KEYS)))
    if not p.get("secrets"): bad.append("provider %r declares no secret name" % p["id"])
    for s in p.get("secrets") or []:
        if set(s) != {"name", "kind"}: bad.append("provider %r: a secret is exactly {name, kind}, got %s" % (p["id"], sorted(s)))
        elif not NAME.fullmatch(s["name"]) or SECRET.search(s["name"]): bad.append("provider %r: %r is not a secret NAME" % (p["id"], s["name"][:6]))
        elif not KIND.fullmatch(s["kind"]): bad.append("provider %r: secret kind %r" % (p["id"], s["kind"]))
    for l in p.get("links") or []:
        if not re.fullmatch(r"https://[a-z0-9.-]+(/[A-Za-z0-9._~/-]*)?", l["url"]): bad.append("provider %r: link %r carries more than a plain https address (no query, no credentials)" % (p["id"], l["url"][:30]))
    if not p.get("links"): bad.append("provider %r has no console or docs link" % p["id"])
    if not p.get("auth"): bad.append("provider %r declares no auth method" % p["id"])
    if SECRET.search(json.dumps(p)): bad.append("provider %r holds a secret-shaped value" % p["id"])
kt = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/ApiProviders.kt", encoding="utf-8").read()
m = re.search(r"data class Secret\(([^)]*)\)", kt)
if not m or m.group(1).replace(" ", "") != "valname:String,valkind:String": bad.append("ApiProviders.Secret must carry a name and a kind only")
infra_kt = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt", encoding="utf-8").read()
if "Declarations.apiProviders" not in infra_kt or "apis.groupOfPage" not in infra_kt: bad.append("InfraPages.kt does not read the declared API providers")
tabs = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt", encoding="utf-8").read()
if "apiProviders.groupOfPage" not in tabs: bad.append("InfraFragment does not resolve the declared API pages")
build = open(app + "/app/build" + ".gradle", encoding="utf-8").read()
if "API_PROVIDERS_B64" not in build or "api_providers.json" not in build: bad.append("the app build never bakes data/api_providers.json")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d7 <app dir> : #1 Project Apps is gone from every project tab (Topology cards, remap, no group)
d7() { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
bj = json.load(open(app + "/build.json", encoding="utf-8"))
cp = json.load(open(app + "/data/container_projects.json", encoding="utf-8"))
bad = []
def walk(x):
    if isinstance(x, dict):
        yield x
        for v in x.values(): yield from walk(v)
    elif isinstance(x, list):
        for v in x: yield from walk(v)
for n in walk(bj):
    if n.get("kind") == "cloud_dashboard" and (n.get("group") == "project" or n.get("title") == "Project Apps"): bad.append("build.json still declares a Project Apps dashboard card")
if cp.get("group_remap", {}).get("Project Apps") != "Cloud Apps": bad.append("group_remap no longer moves Project Apps")
for g in cp.get("container_family", {}).values():
    if g == "Project Apps": bad.append("a container family is Project Apps")
kt = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/ContainerProjects.kt", encoding="utf-8").read()
if "targets" not in kt: bad.append("regroup does not create a missing remap target (a live document without Cloud Apps would keep Project Apps)")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d8 <app dir> : #2 Filters is ONE page-wide row above the project strip
d8() { python3 - "$1" <<'PY'
import re, sys
app = sys.argv[1]; K = app + "/app/src/main/java/com/diegonmarcos/cloudc3/"
rd = lambda f: open(K + f, encoding="utf-8").read()
rt, st, pg, tf = rd("pages/RoutesTable.kt"), rd("cloud/C3StackFragment.kt"), rd("pages/PagedFragment.kt"), rd("pages/TabFragments.kt")
bad = []
m = re.search(r"fun KindBar\(.*?\n}\n", rt, flags=re.S)
if not m or 'Text("Filters"' not in m.group(0): bad.append("the kind row is not labelled Filters")
if re.search(r'ChipRow\("Kind"', rt): bad.append("a Kind chip row is left")
if len(re.findall(r"\bKindBar\(", rt + st + tf)) != 2: bad.append("KindBar must be defined once and called once (ContainersFragment.AboveStrip), not per page")
if "AboveStrip()" not in pg or pg.index("AboveStrip()") > pg.index("NavBarTopSecondRow("): bad.append("PagedFragment does not draw AboveStrip before the project strip")
if not re.search(r"class ContainersFragment.*?override fun AboveStrip\(\) = KindBar", tf, flags=re.S): bad.append("ContainersFragment does not draw Filters above the strip")
for name, src in (("Topology (C3StackFragment)", st), ("Table (RoutesTableFragment)", rt)):
    if "ContainerFilter.kind" not in src: bad.append("%s does not read the page-wide ContainerFilter" % name)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d9 <app dir> : #4 every project tab shows data; the Topology draws one card per scoped group (no static group-id filter)
d9() { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
cp = json.load(open(app + "/data/container_projects.json", encoding="utf-8"))
rd = json.load(open(app + "/data/repos_declared.json", encoding="utf-8"))
cd = json.load(open(app + "/data/containers_declared.json", encoding="utf-8"))
bad = []
count = {p["id"]: 0 for p in cp["projects"]}
for r in rd["repos"]:
    if r["project"] not in count: bad.append("repo %r is in the undeclared project %r" % (r["name"], r["project"]))
    else: count[r["project"]] += 1
for c in cd["containers"]:
    pid = cp["name_overrides"].get(c["name"]) or next((p["id"] for p in cp["projects"] if any(c.get("dir", "").startswith(x) for x in p["folder_prefix"]) or any(c["name"].startswith(x) for x in p["name_prefix"])), cp["default"])
    if pid in count: count[pid] += 1
for pid, n in count.items():
    if n == 0: bad.append("project %r shows nothing: no container and no repo" % pid)
kt = open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/cloud/C3StackFragment.kt", encoding="utf-8").read()
if "shownPanels(panels)" not in kt or "dashGroupIds = listOf(g.id)" not in kt: bad.append("the Topology does not draw one card per scoped group: a group id outside the panels' static list would be dropped (the root cause of the empty tabs)")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d10 <app dir> : #5 Infra Apps / Cloud Apps are Cloudy's only; every other project has its own registry families
d10() { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
cp = json.load(open(app + "/data/container_projects.json", encoding="utf-8"))
rd = json.load(open(app + "/data/repos_declared.json", encoding="utf-8"))
bad = []
macro = {"Infra Apps", "Cloud Apps", "Project Apps"}
seen = {}
for p in cp["projects"]:
    fams = rd["projects"].get(p["id"], {}).get("families") or []
    if p.get("repo_group"): continue
    if not fams: bad.append("project %r declares no registry families" % p["id"])
    if macro & set(fams): bad.append("project %r repeats a Cloudy macro group" % p["id"])
    if tuple(fams) in seen: bad.append("projects %r and %r share one family list" % (p["id"], seen[tuple(fams)]))
    seen[tuple(fams)] = p["id"]
    for r in rd["repos"]:
        if r["project"] == p["id"] and r["group"] not in fams: bad.append("repo %r sits in %r, not a family of %r" % (r["name"], r["group"], p["id"]))
for n, f in cp.get("container_family", {}).items():
    pid = cp["name_overrides"].get(n)
    if pid and f not in (rd["projects"].get(pid, {}).get("families") or []): bad.append("container %r family %r is not a registry family of %r" % (n, f, pid))
    if f in macro: bad.append("container %r is in the Cloudy macro group %r" % (n, f))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

# d11 <app dir> : #1605 the Containers-Node-Python page lists both Google Apps Script environments (declared AND in the slice the page reads)
d11() { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
pl = json.load(open(app + "/data/platforms.json", encoding="utf-8"))
sl = json.load(open(app + "/data/fleet_slice.json", encoding="utf-8"))
want = {"google-apps-script-workspace": "Google Apps Script \u2013 Workspace", "google-apps-script-personal": "Google Apps Script \u2013 Personal"}
bad = []
for k, rows in (("platforms.json hosting_platforms", pl.get("hosting_platforms") or []), ("fleet_slice.json hosting", sl.get("hosting") or [])):
    have = {p["id"]: p["label"] for p in rows}
    for i, l in want.items():
        if have.get(i) != l: bad.append("%s lacks %r labelled %r" % (k, i, l))
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}

d1 "$APP" && pass "D1 no platform or project name is a string literal in Kotlin" || fail "D1 a page hard-codes a declared platform or project"
d2 "$APP" && pass "D2 every project nests Topology | Table; Agents is under Ops" || fail "D2 the nav declaration is wrong"
d3 "$APP" && pass "D3 the project strip and the container -> project mapping agree" || fail "D3 the strip and the mapping disagree"
d4 "$APP" && pass "D4 the platform rows are declared, reach the slice, and the Infra pages read the slice" || fail "D4 the platform rows are not declarative"
d5 "$APP" && pass "D5 no personal email address and no secret-shaped value in the app tree; the private source is a declaration" || fail "D5 a personal address, a secret value or an undeclared private source"
d6 "$APP" && pass "D6 the APIs row and its groups are declared; a secret is a name only" || fail "D6 the APIs row is not declarative or a secret is not a name"
d7 "$APP" && pass "D7 no tab has a Project Apps table or macro group" || fail "D7 Project Apps is back on a tab"
d8 "$APP" && pass "D8 Filters is one page-wide row above the project tabs, read by Topology and Table" || fail "D8 the Filters row is not page-wide"
d9 "$APP" && pass "D9 every project tab shows data and the Topology draws one card per scoped group" || fail "D9 a project tab is empty"
d10 "$APP" && pass "D10 Infra Apps and Cloud Apps are Cloudy's only; every other project has its own families" || fail "D10 a project repeats Cloudy's groups or lacks families"
d11 "$APP" && pass "D11 both Google Apps Script environments are declared Containers-Node-Python platforms and reach the slice" || fail "D11 a Google Apps Script platform is missing"

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
m_no_agents()    { pyjson "$1/build.json" 'o=next(s for s in d["ui"]["sections"] if s["id"]=="ops"); [p.__setitem__("pages",[q for q in p["pages"] if q["id"]!="orch_agents"]) for p in o["pages"]]'; }
m_extra_page()   { pyjson "$1/build.json" 'next(s for s in d["ui"]["sections"] if s["id"]=="containers")["pages"].append({"id":"ghost","label":"Ghost","icon":"ic_p_c3_stack","pages":[{"id":"ghost_topology","label":"Topology","icon":"ic_p_c3_stack"},{"id":"ghost_table","label":"Table","icon":"ic_link_tile"}]})'; }
m_platform_gone(){ pyjson "$1/data/fleet_slice.json" 'd["hosting"]=d["hosting"][1:]'; }
m_lists_ctrs()   { append "$1/$KT/pages/InfraPages.kt" 'val x = slice.serverless'; }
m_email_json()   { pyjson "$1/build.json" 'd["description"]=d.get("description","")+" mail someone"+"@"+"gmail.com"'; }
m_email_kt()     { append "$1/$KT/pages/PrivateData.kt" 'val e = "someone'"@"'live.com"'; }
m_email_data()   { pyjson "$1/data/public_identities.json" 'd["emails"].append({"address":"extra"+"@"+"gmail.com"})'; }
m_email_other()  { printf '{"x":"diegonmarcos%s"}\n' '@live.com' > "$1/data/zz_other.json"; }
m_kt_provider()  { append "$1/$KT/pages/InfraPages.kt" 'val providerTyped = listOf("OpenRouter")'; }
m_kt_group()     { append "$1/$KT/pages/TabFragments.kt" 'val groupPageTyped = "api_scraper"'; }
m_api_row_gone() { pyjson "$1/build.json" 'i=next(s for s in d["ui"]["sections"] if s["id"]=="infra"); i["groups"]=[g for g in i["groups"] if g["id"]!="apis"]'; }
m_api_order()    { pyjson "$1/build.json" 'i=next(s for s in d["ui"]["sections"] if s["id"]=="infra"); g=i["groups"]; g[1],g[2]=g[2],g[1]'; }
m_api_value()    { pyjson "$1/data/api_providers.json" 'd["providers"][0]["secrets"][0]["value"]="x1"'; }
m_api_key_name() { pyjson "$1/data/api_providers.json" 'd["providers"][0]["secrets"][0]["name"]="sk-or-v1-"+"a"*24'; }
m_api_field()    { pyjson "$1/data/api_providers.json" 'd["providers"][1]["token"]="abc"'; }
m_api_nosecret() { pyjson "$1/data/api_providers.json" 'd["providers"][2]["secrets"]=[]'; }
m_api_page()     { pyjson "$1/build.json" 'i=next(s for s in d["ui"]["sections"] if s["id"]=="infra"); i["pages"]=[p for p in i["pages"] if p["id"]!="api_ai"]'; }
m_secret_json()  { pyjson "$1/build.json" 'd["description"]=d.get("description","")+" key sk-or-v1-"+"0123456789abcdef0123"'; }
m_secret_kt()    { append "$1/$KT/pages/PrivateData.kt" 'val k = "ghp''_0123456789abcdefghijklmnop"'; }
m_secret_bearer() { printf 'Authorization: Bearer %s\n' "abcdefghijklmnopqrstuvwxyz0123" > "$1/data/zz_note.txt"; }
m_ps_card()      { printf '%s\n' 'x' >/dev/null; python3 - "$1/data/container_projects.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["group_remap"] = {}; json.dump(d, open(p, "w"))
PY
}
m_filters_row()  { sed -i 's/Text("Filters"/Text("Kind"/' "$1/$KT/pages/RoutesTable.kt"; }
m_empty_tab()    { python3 - "$1/data/repos_declared.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["repos"] = [r for r in d["repos"] if r["project"] != "hardtech"]; json.dump(d, open(p, "w"))
PY
}
m_fam_repeat()   { python3 - "$1/data/repos_declared.json" <<'PY'
import json, sys
p = sys.argv[1]; d = json.load(open(p)); d["projects"]["cg"]["families"].append("Infra Apps"); json.dump(d, open(p, "w"))
PY
}
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
mutate "Project Apps is no longer remapped"              d7 m_ps_card
mutate "the Filters label is renamed back to Kind"       d8 m_filters_row
mutate "a project tab has no container and no repo"      d9 m_empty_tab
mutate "a project repeats Infra Apps"                    d10 m_fam_repeat
mutate "a provider name is typed into Kotlin"            d1 m_kt_provider
mutate "an API group page id is typed into Kotlin"       d1 m_kt_group
mutate "the APIs row is removed from build.json"         d6 m_api_row_gone
mutate "the APIs row leaves its place after Bare"        d6 m_api_order
mutate "a secret gains a value field"                    d6 m_api_value
mutate "a secret name is a key-shaped value"             d6 m_api_key_name
mutate "a provider gains a token field"                  d6 m_api_field
mutate "a provider declares no secret name"              d6 m_api_nosecret
mutate "a declared group's page is missing from build.json" d6 m_api_page
mutate "a provider key lands in build.json"              d5 m_secret_json
mutate "a GitHub token lands in Kotlin"                  d5 m_secret_kt
mutate "a bearer token lands in a data file"             d5 m_secret_bearer

if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  10 properties asserted, 29 mutations each proved able to go red"
