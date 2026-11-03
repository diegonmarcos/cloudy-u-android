#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-c3 Infra — five fleet pages, ONE source of truth, no DB containers  ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The Infra section (owner request, 2026-10-20) is Providers | VMs | Storage | Electronics | Domains, each a page of
# data/fleet_slice.json, which data/gen_fleet_slice.py DERIVES from the fleet's own declarations in cloud-infra. The JVM
# suite (FleetModelTest, InfraScreensComposeTest) proves declaration -> model -> screen. This file holds the guards that
# a JVM test cannot see because they are about what is NOT in the sources:
#
#   T1  the declaration, the dispatch and the screens agree: the declared Infra pages are exactly InfraSections.ALL, each
#       has an arm in InfraSectionScreen, there is no Stack / Dbs / API page and no stack_infra left anywhere.
#   T2  NO DB CONTAINER UNDER INFRA. Every container filed under Ops-dBs-* in the one Containers document
#       (data/containers_declared.json, #1148) is in the Infra Apps group and appears nowhere in the Infra sources, the
#       slice or the Infra declaration; the document has only the groups infra / cloud / project.
#   T3  NO SECOND LITERAL COPY OF THE FLEET. Every fact in data/fleet_slice.json (VM ids and aliases, public / WireGuard
#       addresses, regions, sizes, bucket names, device models, A/AAAA targets) is absent from every hand-authored file of
#       the section: its Kotlin, its tests, its generator, this tester, the Infra part of build.json. data/ holds only
#       generated or carried files; a new file there is red until it is a declared generator output.
#   T4  the slice is baked and complete: BuildConfig.FLEET_SLICE_B64 is a buildConfigField, Declarations.fleet decodes
#       it, every section has content, the domain carries its registration and its mail signing (MX, SPF, DKIM, DMARC,
#       MTA-STS, TLS-RPT), and the generator names its sources and carries no fact of its own.
#   T5  the live half uses the existing c3 API client only (OpsClient), on routes the declaration or the page names.
#   M   mutation-proof: each check above is shown able to go RED on a mutated copy.
#
# OWN-SOURCE ONLY. python3.
set -uo pipefail

APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REL="app/src/main/java/com/diegonmarcos/cloudc3"
FAILURES=0
pass() { echo "  PASS  $1"; }
fail() { echo "  FAIL  $1"; FAILURES=$((FAILURES + 1)); }
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent - a verdict from a missing tool is not a verdict"; exit 1; }
for f in build.json data/fleet_slice.json data/gen_fleet_slice.py data/containers_declared.json "$REL/pages/InfraPages.kt" "$REL/pages/FleetModel.kt" app/build.gradle; do
    [ -f "$APP/$f" ] || { echo "ERROR missing $APP/$f - unrun, not passing"; exit 1; }
done

# t1 <app dir> : declaration == dispatch == screens
t1() { python3 - "$1" <<'PY'
import json, re, sys
app = sys.argv[1]
rel = app + "/app/src/main/java/com/diegonmarcos/cloudc3"
b = json.load(open(app + "/build.json", encoding="utf-8"))
ui = b["ui"]
bad = []
infra = next((s for s in ui["sections"] if s["id"] == "infra"), None)
declared = [p["id"] for p in (infra or {}).get("pages") or []]
pages_kt = open(rel + "/pages/InfraPages.kt", encoding="utf-8").read()
code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", pages_kt, flags=re.S))
consts = dict(re.findall(r'const val (\w+) = "([a-z_]+)"', code[code.index("object InfraSections"):code.index("object InfraTags")]))
all_m = re.search(r"val ALL = listOf\(([^)]*)\)", code)
all_ids = [consts[n.strip()] for n in all_m.group(1).split(",")] if all_m else []
if declared != all_ids:
    bad.append("ui.sections[infra].pages %s != InfraSections.ALL %s" % (declared, all_ids))
screen = code[code.index("fun InfraSectionScreen"):]
screen = screen[:screen.index("\n}\n")]
for name, pid in consts.items():
    if "InfraSections.%s ->" % name not in screen:
        bad.append("InfraSectionScreen has no arm for page %r (InfraSections.%s)" % (pid, name))
for dead in ("stack", "dbs", "apis", "api", "mcp", "databases"):
    if dead in declared:
        bad.append("Infra declares a %r page: DBs, APIs and MCPs are containers and live under Containers" % dead)
if "stack_infra" in (ui.get("carried_c3") or {}): bad.append("ui.carried_c3.stack_infra is back")
gradle = open(app + "/app/build.gradle", encoding="utf-8").read()
if "stack_infra" in gradle or "UI_STACK_INFRA_B64" in gradle: bad.append("app/build.gradle still bakes stack_infra")
for dirpath, _d, files in __import__("os").walk(rel):
    for f in files:
        if f.endswith(".kt"):
            t = open(dirpath + "/" + f, encoding="utf-8").read()
            if "STACK_INFRA" in t or "stackInfra" in t: bad.append("%s still names STACK_INFRA / stackInfra" % f)
tabs = open(rel + "/pages/TabFragments.kt", encoding="utf-8").read()
for name, pid in consts.items():
    if '"%s" -> InfraSectionFragment.newInstance(InfraSections.%s)' % (pid, name) not in tabs:
        bad.append("InfraFragment does not resolve page %r to InfraSectionFragment" % pid)
for p in (infra or {}).get("pages") or []:
    if not p.get("icon"): bad.append("Infra page %r declares no icon" % p["id"])
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}

# t2 <app dir> : no DB container under Infra
t2() { python3 - "$1" <<'PY'
import json, os, sys
app = sys.argv[1]
rel = app + "/app/src/main/java/com/diegonmarcos/cloudc3"
bad = []
cs = json.load(open(app + "/data/containers_declared.json", encoding="utf-8"))
# #1148: Containers read ONE document, containers-declared.json (groups infra / cloud / project, every container once).
# Every database container (category Ops-dBs-*) lives in the Infra Apps group, never on the Infra pages of this app.
dbs = {c["name"] for c in cs["containers"] if c["category"].startswith("Ops-dBs")}
if not dbs: bad.append("no container is filed under Ops-dBs-* any more - the guard has nothing to guard (Containers lost its Dbs)")
for c in cs["containers"]:
    if c["category"].startswith("Ops-dBs") and c["group"] != "Infra Apps":
        bad.append("DB container %r is in group %r, not Infra Apps" % (c["name"], c["group"]))
for g in cs["groups"]:
    if g["id"] not in ("infra", "cloud", "project"):
        bad.append("containers_declared.json carries the group %r: only Infra Apps, Cloud Apps and Project Apps exist" % g["id"])
slice_text = open(app + "/data/fleet_slice.json", encoding="utf-8").read()
sl = json.loads(slice_text)
if set(sl["storage"]) != {"buckets", "backends"}: bad.append("fleet_slice.storage holds %s, not just buckets and backends" % sorted(sl["storage"]))
bj = json.load(open(app + "/build.json", encoding="utf-8"))
infra_decl = json.dumps(next(s for s in bj["ui"]["sections"] if s["id"] == "infra"))
srcs = {"fleet_slice.json": slice_text, "build.json[infra]": infra_decl}
for f in os.listdir(rel + "/pages"):
    if f.startswith(("Infra", "FleetModel", "FleetLive", "FleetFixtures")): srcs["pages/" + f] = open(rel + "/pages/" + f, encoding="utf-8").read()
for where, text in srcs.items():
    for n in sorted(dbs):
        if n in text: bad.append("DB container %r appears in %s" % (n, where))
tiles = json.dumps((bj["ui"].get("carried_c3") or {}).get("stack_topology"))
if "anchor:stack/dbs" in tiles or "anchor:stack/apis" in tiles: bad.append("a Containers tile still targets the removed stack/dbs or stack/apis anchor")
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}

# t3 <app dir> : no second literal copy of the fleet
t3() { python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
rel = app + "/app/src/main/java/com/diegonmarcos/cloudc3"
sl = json.load(open(app + "/data/fleet_slice.json", encoding="utf-8"))
facts = set()
for v in sl["vms"]:
    for k in ("id", "alias", "public_ip", "wg_ip", "wg_public_ip", "region", "size"):
        if v.get(k): facts.add(str(v[k]))
for d in sl["electronics"]:
    for k in ("wg_ip", "wg_public_ip", "model"):
        if d.get(k): facts.add(d[k])
for bk in sl["storage"]["buckets"]:
    for k in ("name", "dns"):
        if bk.get(k): facts.add(bk[k])
for dm in sl["domains"]:
    for r in dm["records"]:
        if r["type"] in ("A", "AAAA"): facts.add(r["content"])
facts = {f for f in facts if len(f) >= 4 and f != "TBD"}
if len(facts) < 20: print("    only %d facts read from the slice - the guard would be hollow" % len(facts)); sys.exit(1)
bad = []
hand = [app + "/data/gen_fleet_slice.py", app + "/test/test-c3-infra.sh",
        app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/TabFragments.kt"]
for sub in (rel + "/pages", app + "/app/src/test/java/com/diegonmarcos/cloudc3"):
    if os.path.isdir(sub):
        hand += [sub + "/" + f for f in os.listdir(sub) if f.startswith(("Infra", "FleetModel", "FleetLive", "FleetFixtures"))]
texts = {p: open(p, encoding="utf-8").read() for p in hand if os.path.isfile(p)}
bj = json.load(open(app + "/build.json", encoding="utf-8"))
texts["build.json[ui.sections.infra]"] = json.dumps(next(s for s in bj["ui"]["sections"] if s["id"] == "infra"))
texts["build.json[ui.aliases]"] = json.dumps(bj["ui"]["aliases"])
for where, t in texts.items():
    for f in sorted(facts):
        if f in t: bad.append("%s spells the fleet fact %r - read it from data/fleet_slice.json" % (os.path.relpath(where, app) if where.startswith(app) else where, f))
# data/ holds generated or carried files only
generated = set(re.findall(r'os\.path\.join\(HERE, "([a-z_]+\.json)"\)', open(app + "/data/gen_infra_slice.py", encoding="utf-8").read())) | \
            set(re.findall(r'os\.path\.join\(HERE, "([a-z_]+\.json)"\)', open(app + "/data/gen_fleet_slice.py", encoding="utf-8").read()))
carried = {"mesh.json", "services_public.json", "services_private.json", "containers_declared.json"}   # carried copies: the SuperApp's, and the configs-sync data repo's containers-declared.json
generated |= {"routes.json", "declarations.json", "infra_slice.json"}                                # gen_infra_slice.py's outputs
for f in os.listdir(app + "/data"):
    if f.endswith(".json") and f not in generated and f not in carried:
        bad.append("data/%s is a hand-authored data file: fleet data has ONE source (cloud-infra); add a generator output or delete it" % f)
if "fleet_slice.json" not in generated: bad.append("fleet_slice.json is not an output of data/gen_fleet_slice.py")
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}

# t4 <app dir> : baked and complete; the generator carries no fact
t4() { python3 - "$1" <<'PY'
import json, re, sys
app = sys.argv[1]
rel = app + "/app/src/main/java/com/diegonmarcos/cloudc3"
bad = []
gradle = open(app + "/app/build.gradle", encoding="utf-8").read()
if not re.search(r'buildConfigField\s+"String\[\]",\s*"FLEET_SLICE_B64"', gradle): bad.append("app/build.gradle never bakes FLEET_SLICE_B64 - the five pages would decode an empty slice (#276)")
if "fleet_slice.json" not in gradle: bad.append("app/build.gradle does not read data/fleet_slice.json")
if "BuildConfig.FLEET_SLICE_B64" not in open(rel + "/Declarations.kt", encoding="utf-8").read(): bad.append("Declarations.fleet does not decode BuildConfig.FLEET_SLICE_B64")
sl = json.load(open(app + "/data/fleet_slice.json", encoding="utf-8"))
if sl.get("_meta", {}).get("generated_by") != "data/gen_fleet_slice.py": bad.append("fleet_slice.json does not name its generator")
for k in ("providers", "vms", "serverless", "electronics", "domains"):
    if not sl.get(k): bad.append("fleet_slice.%s is empty" % k)
if not sl["storage"]["buckets"]: bad.append("fleet_slice.storage.buckets is empty")
d = (sl["domains"] or [{}])[0]
if d.get("registrar") != "Cloudflare" or d.get("admin") != "Cloudflare": bad.append("the domain's registrar / admin are not Cloudflare")
if not str(d.get("renewal_paid", "")).endswith("-10"): bad.append("the domain's renewal is not recorded as paid in October")
for k in ("expires", "next_renewal"):
    if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", str(d.get(k, ""))): bad.append("domain.%s is not an ISO date" % k)
groups = {r["group"] for r in d.get("records") or []}
for g in ("MX", "SPF", "DKIM", "DMARC", "MTA-STS", "TLS-RPT"):
    if g not in groups: bad.append("the domain declares no %s record" % g)
gen = open(app + "/data/gen_fleet_slice.py", encoding="utf-8").read()
for must in ("1_cloud-configs/dist/_cloud-data-consolidated.json", "c_vps/ba-clo_cloudflare/src/terraform.json",
             "1_cloud-configs/src/inputs/fleet-assets.json", "1_cloud-configs/src/inputs/superapp-users.json",
             "c_vps/vps_gcloud/src/terraform.json"):
    if must not in gen: bad.append("the generator does not name its source %s" % must)
if re.search(r"\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b", re.sub(r'""".*?"""', "", gen, flags=re.S)):
    bad.append("the generator spells an IPv4 address: it must READ every address")
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}

# t5 <app dir> : the live half rides the existing client
t5() { python3 - "$1" <<'PY'
import json, re, sys
app = sys.argv[1]
rel = app + "/app/src/main/java/com/diegonmarcos/cloudc3"
bad = []
live = open(rel + "/pages/FleetLive.kt", encoding="utf-8").read()
code = re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", live, flags=re.S))
if "OpsClient.get(" not in code: bad.append("FleetLive does not go through OpsClient.get (same base URL, bearer and named failures as every C3 call)")
for banned in ("HttpURLConnection", "URL(", "okhttp", "Retrofit"):
    if banned in code: bad.append("FleetLive opens its own connection (%s): the c3 API is reached through OpsClient only" % banned)
for route in ("/health/tier3/", "/cloud-data/cloudflare-dns"):
    if route not in code: bad.append("FleetLive no longer asks %s" % route)
sl = json.load(open(app + "/data/fleet_slice.json", encoding="utf-8"))
for p in sl["providers"]:
    path = (p.get("cost") or {}).get("live")
    if path and not path.startswith("/cloud/"): bad.append("provider %s reads its cost from %r, not a /cloud/* finops route" % (p["id"], path))
for b_ in bad: print("    " + b_)
sys.exit(1 if bad else 0)
PY
}

t1 "$APP" && pass "T1 the declared Infra pages == InfraSections.ALL == the screens' arms; no Stack / Dbs / API page, no stack_infra" || fail "T1 the Infra declaration, dispatch and screens disagree"
t2 "$APP" && pass "T2 no DB container under Infra: they stay under Containers and appear nowhere in the Infra sources, slice or declaration" || fail "T2 a DB container leaked into Infra (or Containers lost its Dbs)"
t3 "$APP" && pass "T3 no fleet fact is spelled outside data/fleet_slice.json; data/ holds generated or carried files only" || fail "T3 a second literal copy of the fleet exists"
t4 "$APP" && pass "T4 the slice is baked, complete (registration + mail signing) and its generator carries no fact" || fail "T4 the slice is unbaked, incomplete or its generator types a fact"
t5 "$APP" && pass "T5 the live half uses OpsClient on the finops / health / cloud-data routes only" || fail "T5 the live half bypasses the c3 API client"

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
KT="app/src/main/java/com/diegonmarcos/cloudc3"
m_api_page()      { pyjson "$1/build.json" 'next(s for s in d["ui"]["sections"] if s["id"]=="infra")["pages"].append({"id":"apis","label":"API","icon":"ic_code"})'; }
m_stack_back()    { pyjson "$1/build.json" 'd["ui"]["carried_c3"]["stack_infra"]=[{"kind":"tile_row"}]'; }
m_arm_dropped()   { python3 - "$1/$KT/pages/InfraPages.kt" <<'PY'
import sys
p = sys.argv[1]; s = open(p).read()
assert "InfraSections.DOMAINS -> DomainsScreen(slice, live, today)\n" in s
open(p, "w").write(s.replace("InfraSections.DOMAINS -> DomainsScreen(slice, live, today)\n", ""))
PY
}
m_db_in_slice()   { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
cs = json.load(open(app + "/data/containers_declared.json"))
db = [c["name"] for c in cs["containers"] if c["category"].startswith("Ops-dBs")][0]
sl = json.load(open(app + "/data/fleet_slice.json"))
sl["storage"]["backends"].append({"id": db, "label": db})
json.dump(sl, open(app + "/data/fleet_slice.json", "w"), indent=1)
PY
}
m_dbs_group()     { pyjson "$1/data/containers_declared.json" 'd["groups"].append({"id":"dbs","label":"DBs","categories":[]})'; }
m_db_in_kotlin()  { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
cs = json.load(open(app + "/data/containers_declared.json"))
db = [c["name"] for c in cs["containers"] if c["category"].startswith("Ops-dBs")][0]
open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt", "a").write('\n// ' + db + '\n')
PY
}
m_ip_in_kotlin()  { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
ip = next(v["wg_ip"] for v in json.load(open(app + "/data/fleet_slice.json"))["vms"] if v.get("wg_ip"))
open(app + "/app/src/main/java/com/diegonmarcos/cloudc3/pages/InfraPages.kt", "a").write('\nval typedIp = "' + ip + '"\n')
PY
}
m_vm_in_json()    { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
vm = json.load(open(app + "/data/fleet_slice.json"))["vms"][0]["id"]
b = json.load(open(app + "/build.json"))
next(s for s in b["ui"]["sections"] if s["id"] == "infra")["_doc"] += " " + vm
json.dump(b, open(app + "/build.json", "w"), indent=2)
PY
}
m_data_file()     { printf '{"vms":[]}' > "$1/data/vms.json"; }
m_no_bake()       { python3 - "$1/app/build.gradle" <<'PY'
import re, sys
p = sys.argv[1]; s = open(p).read()
open(p, "w").write(re.sub(r'[^\n]*buildConfigField "String\[\]", "FLEET_SLICE_B64"[^\n]*\n', "", s))
PY
}
m_no_dmarc()      { pyjson "$1/data/fleet_slice.json" 'd["domains"][0]["records"]=[r for r in d["domains"][0]["records"] if r["group"]!="DMARC"]'; }
m_own_http()      { printf '\nval c = java.net.HttpURLConnection::class\n' >> "$1/$KT/pages/FleetLive.kt"; }
m_gen_ip()        { python3 - "$1" <<'PY'
import json, sys
app = sys.argv[1]
ip = next(v["wg_ip"] for v in json.load(open(app + "/data/fleet_slice.json"))["vms"] if v.get("wg_ip"))
open(app + "/data/gen_fleet_slice.py", "a").write('\nX = "' + ip + '"\n')
PY
}

mutate "an API page is declared under Infra"               t1 m_api_page
mutate "stack_infra comes back"                            t1 m_stack_back
mutate "a declared Infra page loses its screen arm"        t1 m_arm_dropped
mutate "a DB container is listed as storage"               t2 m_db_in_slice
mutate "the dbs cross-cut group comes back"                t2 m_dbs_group
mutate "a DB container is named in the Infra sources"      t2 m_db_in_kotlin
mutate "a WireGuard address is typed into Infra Kotlin"    t3 m_ip_in_kotlin
mutate "a VM id is typed into the Infra declaration"       t3 m_vm_in_json
mutate "a hand-authored fleet data file appears in data/"  t3 m_data_file
mutate "the slice is no longer baked"                      t4 m_no_bake
mutate "the domain loses its DMARC record"                 t4 m_no_dmarc
mutate "the generator types an address"                    t4 m_gen_ip
mutate "the live half opens its own connection"            t5 m_own_http

if [ "$FAILURES" -ne 0 ] || [ "$MUT_FAIL" -ne 0 ]; then
    echo "FAIL  $FAILURES assertion(s) red, $MUT_FAIL mutation(s) void or hollow"
    exit 1
fi
echo "PASS  5 properties asserted, 13 mutations each proved able to go red"
