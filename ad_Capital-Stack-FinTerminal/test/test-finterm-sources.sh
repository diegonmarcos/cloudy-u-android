#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ test-finterm-sources: Config > Sources, statically                ║
# ╚══════════════════════════════════════════════════════════════════╝
# Capital-Stack-FinTerminal's source catalog (data/sources.json), dashboards (data/dashboards.json) and container control (data/control.json), from
# the sources alone: no build, no network, no device. It certifies
#   S1  the catalog: ids unique, auth none | free_key | paid, a keyed source says where its key goes, every connector is one Providers.kt names
#   S2  MAPPING: every FinTerm entry (build.json ui.stacks dashboard entries) has a dashboard and is fed by at least one declared source;
#       every feed names a tab and entry that exist; every panel's source feeds its entry
#   S3  PRIORITY: Cloud-Fincept (fin_api, the own-fleet primary) is the one primary, has the lowest priority number, feeds every entry,
#       and every fred_series panel declares it as its primary route (the public source is the fallback)
#   S4  KEYS: stored only through SafeEncryptedPrefs in the declared secret store; no plain SharedPreferences besides the holdings list;
#       nothing logs (no Log / println / printStackTrace in the app); no key-shaped literal in the data
#   S5  CONTROL: Stop and Restart ask first (data and UI), the control is MESH ONLY (enabled() needs onMesh, the endpoint is a declared fleet
#       mesh endpoint), the UI gates every button on it
#   S6  NO HARD-CODED URL: no address and no mesh host in the Kotlin, none in dashboards/control data; the mesh address is baked from the fleet declaration
#   S7  MODEL PANELS (Feeds.kt): every model source declares cors, schedule.ttl_s >= 60 and a numeric rate_limit and has a parser; every model panel
#       routes the container's /v1 surface first, its provider is its source's connector, its view draws its derive, its fallbacks are key-free
#       connectors that feed the entry, its slots are known and a per-item path has items
#   S8  CENTRAL BANKS (data/central-banks.json): every bank names its source page, every ts is date + time in the bank's zone as UTC, and the
#       last declared decision is at least 60 days ahead (FINTERM_TODAY overrides today)
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="${LNKTREE_ROOT:-$(cd "$APP/.." && pwd)}"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }
[ -f "$APP/data/sources.json" ] || { echo "ERROR: $APP/data/sources.json missing - zero checks ran"; exit 1; }

check() {   # check <app dir> <repo root>
python3 - "$1" "$2" <<'PY'
import json, os, re, sys
app, root = sys.argv[1], sys.argv[2]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(t):
    t = re.sub(r"/\*.*?\*/", "", t, flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
cat = json.loads(read(os.path.join(app, "data/sources.json")))
dash = json.loads(read(os.path.join(app, "data/dashboards.json")))
ctl = json.loads(read(os.path.join(app, "data/control.json")))
kt = {}
base = os.path.join(app, "app/src/main/java")
for d, _, fs in os.walk(base):
    for f in fs:
        if f.endswith(".kt"): kt[os.path.relpath(os.path.join(d, f), base)] = code(read(os.path.join(d, f)))
def ktf(name): return next((t for k, t in kt.items() if k.endswith("/" + name)), "")
S = cat["sources"]; ids = [s["id"] for s in S]; byid = {s["id"]: s for s in S}

# S1
if len(ids) != len(set(ids)): bad.append("S1 source ids are not unique")
conn = set(re.findall(r'"(\w+)"', (re.search(r"CONNECTORS[^=]*=\s*setOf\(([^)]*)\)", ktf("Providers.kt")) or [None, ""])[1]))
for s in S:
    for k in ("id", "name", "category", "coverage", "feeds", "auth"):
        if not s.get(k): bad.append("S1 source %s lacks %s" % (s.get("id"), k))
    a = s.get("auth", {})
    if a.get("type") not in ("none", "free_key", "paid"): bad.append("S1 source %s auth type %r" % (s["id"], a.get("type")))
    if a.get("type") in ("free_key", "paid") and not a.get("key_in"): bad.append("S1 keyed source %s does not say where its key goes" % s["id"])
    if s.get("category") not in cat.get("categories", []): bad.append("S1 source %s category %r is not declared" % (s["id"], s.get("category")))
    if not (s.get("endpoint") or s.get("user_endpoint") or s.get("fleet_endpoint")): bad.append("S1 source %s has no endpoint" % s["id"])
    if s.get("connector") and s["connector"] not in conn: bad.append("S1 source %s connector %s is not in Providers.kt CONNECTORS" % (s["id"], s["connector"]))
    if s.get("connector") and a.get("type") != "none": bad.append("S1 source %s is live but keyed" % s["id"])

# S2
tabs = bj["ui"]["bottom_nav"]; stacks = bj["ui"]["stacks"]
entries = [(t, e["title"], e.get("dashboard")) for t in tabs for e in stacks.get(t, []) if e.get("kind") == "dashboard"]
dbyid = {d["id"]: d for d in dash["dashboards"]}
def feeds(s, tab, entry): return any(f["tab"] == tab and ("*" in f["entries"] or entry in f["entries"]) for f in s["feeds"])
for tab, title, did in entries:
    if not any(feeds(s, tab, title) for s in S): bad.append("S2 FinTerm entry %s/%s maps to no declared source" % (tab, title))
    d = dbyid.get(did)
    if d is None: bad.append("S2 entry %s names no dashboard (%r)" % (title, did)); continue
    if (d["tab"], d["entry"]) != (tab, title): bad.append("S2 dashboard %s is for %s/%s, not %s/%s" % (did, d["tab"], d["entry"], tab, title))
    for p in d["panels"]:
        s = byid.get(p["source"])
        if s is None: bad.append("S2 panel %s/%s source %s is not declared" % (did, p["id"], p["source"]))
        elif not feeds(s, tab, title): bad.append("S2 panel %s/%s: source %s does not feed %s/%s" % (did, p["id"], p["source"], tab, title))
        if not (p.get("fetch") or p.get("placeholder") or p.get("view") == "alloc"): bad.append("S2 panel %s/%s is neither live nor a placeholder" % (did, p["id"]))
for s in S:
    for f in s["feeds"]:
        if f["tab"] not in tabs: bad.append("S2 source %s feeds the unknown tab %s" % (s["id"], f["tab"]))
        for e in f["entries"]:
            if e != "*" and not any(t == f["tab"] and n == e for t, n, _ in entries): bad.append("S2 source %s feeds %s/%s which is not a FinTerm entry" % (s["id"], f["tab"], e))
if len(entries) != len(dbyid): bad.append("S2 %d entries but %d dashboards" % (len(entries), len(dbyid)))

# S3
prim = [s for s in S if s.get("primary")]
if len(prim) != 1: bad.append("S3 want exactly one primary source, found %d" % len(prim))
else:
    p0 = prim[0]
    if any(s["priority"] <= p0["priority"] for s in S if s is not p0): bad.append("S3 the primary %s does not have the lowest priority number" % p0["id"])
    for tab, title, _ in entries:
        if not feeds(p0, tab, title): bad.append("S3 the primary %s does not feed %s/%s" % (p0["id"], tab, title))
    if not p0.get("fleet_endpoint") or p0.get("endpoint"): bad.append("S3 the primary's address must come from its fleet endpoint, not be typed")
    if any(s["priority"] > 10 for s in S if s.get("auth", {}).get("type") == "none" and not s.get("primary")): bad.append("S3 a key-free public source ranks below the keyed ones")
    if any(s["priority"] <= 10 for s in S if s.get("auth", {}).get("type") != "none"): bad.append("S3 a keyed source outranks the key-free public ones")
    for d in dash["dashboards"]:
        for p in d["panels"]:
            f = p.get("fetch")
            if f and f.get("provider") == "fred_series" and (f.get("primary") or {}).get("source") != p0["id"]:
                bad.append("S3 panel %s/%s is a FRED panel without the primary route %s" % (d["id"], p["id"], p0["id"]))

# S4
box = ktf("EncryptedSecretBox.kt")
m = re.search(r'SafeEncryptedPrefs\.prefs\(\s*\w+\s*,\s*PREFS\s*\)', box)
if not m: bad.append("S4 EncryptedSecretBox does not open its file through SafeEncryptedPrefs.prefs")
store = (re.search(r'const val PREFS\s*=\s*"([^"]+)"', box) or [None, ""])[1]
fc = json.loads(read(os.path.join(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")))
st = fc.get("stores", {}).get(store)
if not st or st.get("kind") != "encrypted" or st.get("class") != "secret" or "ad_Capital-Stack-FinTerminal" not in st.get("used_by", []) and "ad_Capital-Stack-FinTerminal" not in st.get("used_by", []):
    bad.append("S4 the key store %r is not declared encrypted + secret for this app in fleet-config.json" % store)
for k, t in kt.items():
    if re.search(r"\bgetSharedPreferences\(|EncryptedSharedPreferences\.create|\bMasterKey", t) and not k.endswith("HoldingsPanel.kt"):
        bad.append("S4 %s keeps state in plain/own preferences: keys go only through SafeEncryptedPrefs" % k)
    if re.search(r"\bLog\.[a-z]\(|\bprintln\(|\bprintStackTrace\(|\bTimber\.", t): bad.append("S4 %s logs or prints (a key must never reach a log)" % k)
if "SourceKeys(EncryptedSecretBox(" not in ktf("SourcesPanel.kt"): bad.append("S4 Config > Sources does not store keys through SourceKeys(EncryptedSecretBox")
if "visualTransformation = PasswordVisualTransformation()" not in ktf("SourcesPanel.kt"): bad.append("S4 the key field is not masked")
def strings(o):
    if isinstance(o, str): yield o
    elif isinstance(o, dict):
        for v in o.values(): yield from strings(v)
    elif isinstance(o, list):
        for v in o: yield from strings(v)
for name, doc in (("sources", cat), ("dashboards", dash), ("control", ctl)):
    for v in strings(doc):
        # a declared name (lowercase words joined by '-', e.g. the container capital-stack-finterminal-api) is not a key
        if re.fullmatch(r"[A-Za-z0-9_\-]{28,}", v) and not re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)+", v): bad.append("S4 data/%s.json holds a key-shaped literal %s..." % (name, v[:6]))

# S5
acts = {a["id"]: a for a in ctl["actions"]}
for need in ("start", "stop", "restart"):
    if need not in acts: bad.append("S5 control.json declares no %s action" % need)
for a in ("stop", "restart"):
    if a in acts and acts[a].get("confirm") is not True: bad.append("S5 %s does not require confirmation in control.json" % a)
    if a in acts and not acts[a].get("enabled_when"): bad.append("S5 %s is available in no state" % a)
if ctl["api"].get("mesh_only") is not True: bad.append("S5 the control api is not declared mesh_only")
acct = json.loads(read(os.path.join(root, "ac_cloud-account/build.json")))["fleet_endpoints"]["services"]
fe = next((s for s in acct if s["id"] == ctl["api"].get("fleet_endpoint")), None)
if not fe or not fe.get("mesh_dns"): bad.append("S5 control fleet endpoint %r has no mesh address in ac_cloud-account fleet_endpoints" % ctl["api"].get("fleet_endpoint"))
if prim:
    pe = next((s for s in acct if s["id"] == prim[0].get("fleet_endpoint")), None)
    if not pe or not pe.get("mesh_dns") or pe.get("policy") != "mesh_only": bad.append("S5 the primary's fleet endpoint is not a mesh_only declared endpoint")
if not re.search(r"if\s*\(\s*!onMesh\s*\)\s*return false", ktf("Control.kt")): bad.append("S5 Control.enabled does not refuse off the mesh")
cc = ktf("ContainerControl.kt")
if "Control.enabled(decl, a.id, state, onMesh == true)" not in cc: bad.append("S5 ContainerControl does not gate each button on Control.enabled with onMesh")
if not re.search(r"Control\.needsConfirm\(decl, a\.id\)\)\s*pending = a\.id else run\(a\.id\)", cc): bad.append("S5 ContainerControl does not route a confirming action through the pending state")
if "KitConfirmDialog(" not in cc or "onConfirm = {" not in cc: bad.append("S5 ContainerControl draws no confirmation dialog")
if len(re.findall(r"(?<![.\w])run\(", cc)) != 3: bad.append("S5 ContainerControl calls run() from somewhere besides the button and the dialog")
if "R.string.ctl_mesh_only" not in cc: bad.append("S5 ContainerControl has no mesh-only note")
if "Authorization" in cc or "Authorization" in ktf("Http.kt") or "Authorization" in ktf("Control.kt"): bad.append("S5 the mesh control sends a bearer (WireGuard is its auth)")

# S6
for k, t in kt.items():
    for mm in re.finditer(r"https?://[^\s\"']+", t): bad.append("S6 %s spells the address %s" % (k, mm.group(0)))
    for mm in re.finditer(r"[\w.-]*(?:mesh\.diegonmarcos|diegonmarcos\.com|\.mesh\.)[\w.-]*", t): bad.append("S6 %s spells the host %s" % (k, mm.group(0)))
for name, doc in (("dashboards", dash), ("control", ctl)):
    for v in strings(doc):
        if re.search(r"https?://|mesh\.diegonmarcos", v): bad.append("S6 data/%s.json spells an address: %s" % (name, v[:50]))
gr = code(read(os.path.join(app, "app/build.gradle")))
if "fleetMeshUrl(" not in gr or "FLEET_MESH_B64" not in gr: bad.append("S6 build.gradle does not bake the mesh addresses from the fleet declaration")
man = read(os.path.join(app, "app/src/main/AndroidManifest.xml"))
if "android.permission.INTERNET" not in man or "networkSecurityConfig" not in man: bad.append("S6 the manifest lacks INTERNET or the network security config for the mesh")
# S7 model panels (Feeds.kt): sources declare cors / schedule / rate_limit; panels route container first, then key-free fallbacks that feed the entry
LEGACY = {"fred_series", "dbnomics_series", "imf_datamapper", "worldbank_indicator", "gdelt_artlist", "fincept_topic"}
VIEWS_OF = {"points": {"quotes", "curve", "bars", "table"}, "events": {"calendar"}, "candles": {"candles"}, "kv": {"kv"}, "filings": {"news", "table"}, "performance": {"table"}, "portfolio_performance": {"table"}, "yields_matrix": {"table"}, "em_usd": {"table"}}
SLOTS = {"each", "each_csv", "today", "from", "yyyymm", "prev_yyyymm", "cik"}
feeds_kt = ktf("Feeds.kt")
for s in S:
    c = s.get("connector")
    if not c or c in LEGACY: continue
    if not isinstance(s.get("cors"), bool): bad.append("S7 source %s does not declare cors true/false" % s["id"])
    if not isinstance((s.get("schedule") or {}).get("ttl_s"), int) or s["schedule"]["ttl_s"] < 60: bad.append("S7 source %s schedule.ttl_s is missing or under 60" % s["id"])
    rl = s.get("rate_limit")
    if not isinstance(rl, dict) or any(not isinstance(v, (int, float)) or v <= 0 for v in rl.values()): bad.append("S7 source %s rate_limit is not numeric" % s["id"])
    if '"%s" ->' % c not in feeds_kt: bad.append("S7 connector %s has no parser in Feeds.parse" % c)
p0id = prim[0]["id"] if prim else ""
v1 = (prim[0].get("paths") or {}).get("v1", "") if prim else ""
for d in dash["dashboards"]:
    for p in d["panels"]:
        f = p.get("fetch")
        if not f or p.get("derive") not in VIEWS_OF: continue
        where = "%s/%s" % (d["id"], p["id"])
        own = byid.get(p["source"], {})
        if f.get("provider") != own.get("connector"): bad.append("S7 panel %s provider %s is not its source's connector" % (where, f.get("provider")))
        if p.get("view") not in VIEWS_OF[p["derive"]]: bad.append("S7 panel %s view %s does not draw derive %s" % (where, p.get("view"), p["derive"]))
        pr = f.get("primary") or {}
        if pr.get("source") != p0id or not v1 or not str(pr.get("api", "")).startswith(v1 + "/"): bad.append("S7 panel %s does not route the container's %s surface first" % (where, v1 or "/v1"))
        for fb in f.get("fallbacks", []):
            fs = byid.get(fb.get("source"))
            if not fs: bad.append("S7 panel %s fallback %s is not declared" % (where, fb.get("source"))); continue
            if not feeds(fs, d["tab"], d["entry"]): bad.append("S7 panel %s fallback %s does not feed %s/%s" % (where, fs["id"], d["tab"], d["entry"]))
            if (fs.get("auth") or {}).get("type") != "none" or not fs.get("connector"): bad.append("S7 panel %s fallback %s is keyed or has no connector" % (where, fs["id"]))
            if fb.get("provider", fs.get("connector")) != fs.get("connector"): bad.append("S7 panel %s fallback %s provider is not its connector" % (where, fs["id"]))
        for route in [f] + list(f.get("fallbacks", [])):
            text = json.dumps({k: route.get(k) for k in ("path", "params")})
            for slot in re.findall(r"\{(\w+)\}", text):
                if slot not in SLOTS: bad.append("S7 panel %s uses the unknown slot {%s}" % (where, slot))
            if "{each}" in text and not (f.get("each") or f.get("each_days") or f.get("each_user") or route.get("each")): bad.append("S7 panel %s asks per {each} with no items" % where)
        web = [own] + [byid.get(fb.get("source"), {}) for fb in f.get("fallbacks", [])]
        if p.get("alerts") not in (None, "price", "event"): bad.append("S7 panel %s alerts %r is not price or event" % (where, p.get("alerts")))

# S9 per-item tables (Tables.kt / engine.ts): performance columns come from Performance.COLUMNS (or whole-year CAGR NY, Vol 1Y, MaxDD 1Y);
# every item has a label and every symbol_map key is an item; a matrix has a tenor column per declared tenor, DM/EM inside its items and a GDP weight each
perf_kt = ktf("Performance.kt")
pcols = re.search(r'COLUMNS = listOf\(([^)]*)\)', perf_kt)
PCOLS = re.findall(r'"([^"]+)"', pcols.group(1)) if pcols else []
for d in dash["dashboards"]:
    for p in d["panels"]:
        f = p.get("fetch") or {}
        where = "%s/%s" % (d["id"], p["id"])
        if p.get("derive") in ("performance", "portfolio_performance"):
            cols = p.get("columns", [])
            if cols[:2] != ["Name", "Last"]: bad.append("S9 panel %s columns do not start Name, Last" % where)
            for c in cols[2:]:
                if c not in PCOLS and c not in ("Vol 1Y", "MaxDD 1Y") and not re.fullmatch(r"\d+Y", c): bad.append("S9 panel %s column %s is not a Performance column" % (where, c))
            if p["derive"] == "performance":
                items = f.get("each", [])
                for it in items:
                    if it not in (f.get("labels") or {}): bad.append("S9 panel %s item %s has no label" % (where, it))
                for r in [f, f.get("primary") or {}] + list(f.get("fallbacks", [])):
                    for k in (r.get("symbol_map") or {}):
                        if k not in items: bad.append("S9 panel %s symbol_map names %s, not an item" % (where, k))
            else:
                legs = [l[0] for l in (d.get("model_portfolio") or {}).get("legs", [])]
                for l in legs:
                    if not (f.get("proxies") or {}).get(l): bad.append("S9 panel %s leg %s has no proxy" % (where, l))
        if p.get("derive") == "yields_matrix":
            ten = [t[0] for t in f.get("tenors", [])]
            # Country, Cov (the GDP coverage of each average, #1374), then the tenors
            if p.get("columns", [])[1:2] != ["Cov"]: bad.append("S9 panel %s has no Cov column after Country" % where)
            if p.get("columns", [])[2:] != ten: bad.append("S9 panel %s columns are not its tenors" % where)
            mc = f.get("min_coverage_gdp")
            if not isinstance(mc, (int, float)) or not 0 < mc <= 1: bad.append("S9 panel %s declares no min_coverage_gdp in (0, 1]" % where)
            g = f.get("groups") or {}
            for grp in ("DM", "EM"):
                for c in g.get(grp, []):
                    if c not in f.get("each", []): bad.append("S9 panel %s %s member %s is not an item" % (where, grp, c))
            for c in f.get("each", []):
                if not isinstance((f.get("gdp_usd_bn") or {}).get(c), (int, float)): bad.append("S9 panel %s country %s has no GDP weight" % (where, c))
                if c not in (f.get("labels") or {}): bad.append("S9 panel %s country %s has no label" % (where, c))

# S8 central bank calendar: every bank has its source page; each ts is date + time in tz, as UTC; the last decision is far enough ahead
cbp = os.path.join(app, "data/central-banks.json")
if not os.path.isfile(cbp): bad.append("S8 data/central-banks.json is missing")
else:
    from datetime import datetime, date, timedelta
    from zoneinfo import ZoneInfo
    cb = json.loads(read(cbp))
    today = date.fromisoformat(os.environ.get("FINTERM_TODAY", date.today().isoformat()))
    for b in cb.get("banks", []):
        if not str(b.get("source_url", "")).startswith("https://"): bad.append("S8 bank %s has no source_url" % b.get("id"))
        evs = b.get("events", [])
        if not evs: bad.append("S8 bank %s declares no decision" % b.get("id")); continue
        for e in evs:
            want = e["date"] if not b.get("time") else datetime.fromisoformat("%sT%s" % (e["date"], b["time"])).replace(tzinfo=ZoneInfo(b["tz"])).astimezone(ZoneInfo("UTC")).strftime("%Y-%m-%dT%H:%MZ")
            if e.get("ts") != want: bad.append("S8 bank %s %s ts %s is not %s" % (b["id"], e["date"], e.get("ts"), want))
        if date.fromisoformat(max(e["date"] for e in evs)) < today + timedelta(days=60): bad.append("S8 bank %s: the last declared decision is under 60 days away, add the next year" % b["id"])
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[$(basename "$APP")] Config > Sources rules"
out="$(check "$APP" "$ROOT")" && ok "S1-S6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
FOLDER="$(basename "$APP")"
mutant() {   # mutant <name> <python statements on the scratch copy, cwd = the copy> <needle>
  rm -rf "${W:?}/$FOLDER"; cp -r "$APP" "$W/$FOLDER"
  ( cd "$W/$FOLDER" && python3 -c "$2" ) || { bad "mutant '$1' could not be planted"; return; }
  if out="$(check "$W/$FOLDER" "$ROOT" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
JE="import json;L=lambda p:json.load(open(p));D=lambda p,d:json.dump(d,open(p,'w'),indent=2)"
SUB="S=lambda f,a,b:(lambda t:open(f,'w').write(t.replace(a,b)) if a in t else (_ for _ in ()).throw(SystemExit('mutation target missing: '+a)))(open(f).read())"
KT="app/src/main/java/com/diegonmarcos/capitalfinterm"
mutant "an entry loses every source"       "$JE;d=L('data/sources.json');[s.__setitem__('feeds',[f for f in s['feeds'] if f['tab']!='pm']) for s in d['sources']];D('data/sources.json',d)" "S2 FinTerm entry pm/"
mutant "a dashboard is dropped"            "$JE;d=L('data/dashboards.json');d['dashboards'].pop();D('data/dashboards.json',d)" "S2 entry"
mutant "a panel uses a source that does not feed it" "$JE;d=L('data/dashboards.json');[p.__setitem__('source','databento') for x in d['dashboards'] if x['id']=='fx' for p in x['panels'][:1]];D('data/dashboards.json',d)" "S2 panel fx/"
mutant "a connector Kotlin lacks"          "$JE;d=L('data/sources.json');d['sources'][1]['connector']='nope';D('data/sources.json',d)" "S1 source .* connector nope"
mutant "the public source outranks Cloud-Fincept" "$JE;d=L('data/sources.json');[s.__setitem__('priority',-1) for s in d['sources'] if s['id']=='fred'];D('data/sources.json',d)" "S3 the primary"
mutant "a FRED panel loses the primary route" "$JE;d=L('data/dashboards.json');[p['fetch'].pop('primary') for x in d['dashboards'] for p in x['panels'] if x['id']=='fx' and p.get('fetch')];D('data/dashboards.json',d)" "S3 panel fx/"
mutant "the primary stops feeding an entry" "$JE;d=L('data/sources.json');d['sources'][0]['feeds']=d['sources'][0]['feeds'][1:];D('data/sources.json',d)" "S3 the primary cloud_fincept does not feed"
mutant "the primary's address is typed"    "$JE;d=L('data/sources.json');d['sources'][0]['endpoint']='x';D('data/sources.json',d)" "S3 the primary's address"
mutant "keys go to plain preferences"      "$SUB;S('$KT/ui/EncryptedSecretBox.kt','SafeEncryptedPrefs.prefs(app, PREFS)','app.getSharedPreferences(PREFS, 0)')" "S4"
mutant "a key reaches a log"               "$SUB;S('$KT/ui/EncryptedSecretBox.kt','prefs.edit().putString(id, value).apply()','prefs.edit().putString(id, value).apply(); android.util.Log.d(\"k\", value)')" "S4 .*logs or prints"
mutant "the key field is not masked"       "$SUB;S('$KT/ui/SourcesPanel.kt','PasswordVisualTransformation()','VisualTransformation.None')" "S4 the key field is not masked"
mutant "a key-shaped literal is committed" "$JE;d=L('data/sources.json');d['sources'][1]['auth']['note']='abcdefghijklmnopqrstuvwxyz0123456789';D('data/sources.json',d)" "S4 data/sources.json holds a key-shaped literal"
mutant "Stop needs no confirmation (data)" "$JE;d=L('data/control.json');[a.__setitem__('confirm',False) for a in d['actions'] if a['id']=='stop'];D('data/control.json',d)" "S5 stop does not require confirmation"
mutant "Restart needs no confirmation (data)" "$JE;d=L('data/control.json');[a.__setitem__('confirm',False) for a in d['actions'] if a['id']=='restart'];D('data/control.json',d)" "S5 restart does not require confirmation"
mutant "the UI skips the confirmation"     "$SUB;S('$KT/ui/ContainerControl.kt','if (Control.needsConfirm(decl, a.id)) pending = a.id else run(a.id)','run(a.id)')" "S5 ContainerControl does not route"
mutant "the dialog never asks"             "$SUB;S('$KT/ui/ContainerControl.kt','KitConfirmDialog(','Foo(')" "S5 ContainerControl draws no confirmation dialog"
mutant "the control works off the mesh"    "$SUB;S('$KT/Control.kt','if (!onMesh) return false','')" "S5 Control.enabled does not refuse off the mesh"
mutant "the buttons ignore the mesh"       "$SUB;S('$KT/ui/ContainerControl.kt','Control.enabled(decl, a.id, state, onMesh == true)','!busy')" "S5 ContainerControl does not gate"
mutant "the control api is not mesh only"  "$JE;d=L('data/control.json');d['api']['mesh_only']=False;D('data/control.json',d)" "S5 the control api is not declared mesh_only"
mutant "the control sends a bearer"        "$SUB;S('$KT/Control.kt','http.post(actionUrl(d, base, actionId))','http.post(actionUrl(d, base, actionId) + \"\\\"Authorization\\\"\")')" "S5 the mesh control sends a bearer"
mutant "an address is typed in Kotlin"     "open('$KT/Engine.kt','a').write('\nval x = \"https://example.org/a\"\n')" "S6 .*spells the address"
mutant "a mesh host is typed in Kotlin"    "open('$KT/Mesh.kt','a').write('\nval h = \"capital-stack-finterminal-api.mesh.diegonmarcos.com\"\n')" "S6 .*spells the host"
mutant "an address is typed in the dashboards" "$JE;d=L('data/dashboards.json');d['dashboards'][0]['panels'][0]['title']='see https://example.org';D('data/dashboards.json',d)" "S6 data/dashboards.json spells an address"
mutant "the build stops baking the mesh"   "$SUB;S('app/build.gradle','fleetMeshUrl(','fleetMeshUr(')" "S6 build.gradle does not bake"

mutant "a model source drops cors"         "$JE;d=L('data/sources.json');[s.pop('cors') for s in d['sources'] if s['id']=='nyfed'];D('data/sources.json',d)" "S7 source nyfed does not declare cors"
mutant "a rate-limited feed is asked every second" "$JE;d=L('data/sources.json');[s['schedule'].__setitem__('ttl_s',1) for s in d['sources'] if s['id']=='forexfactory'];D('data/sources.json',d)" "S7 source forexfactory schedule.ttl_s"
mutant "a rate limit is not a number"      "$JE;d=L('data/sources.json');[s['rate_limit'].__setitem__('per_day','lots') for s in d['sources'] if s['id']=='bls'];D('data/sources.json',d)" "S7 source bls rate_limit"
mutant "a connector loses its parser"      "$SUB;S('$KT/Feeds.kt','\"ff_calendar\" -> ff(body)','\"ff_cal\" -> ff(body)')" "S7 connector ff_calendar has no parser"
mutant "a model panel skips the container" "$JE;d=L('data/dashboards.json');[p['fetch'].pop('primary') for x in d['dashboards'] if x['id']=='economic_calendar' for p in x['panels'] if p['id']=='week'];D('data/dashboards.json',d)" "S7 panel economic_calendar/week does not route the container"
mutant "the container path leaves /v1"     "$JE;d=L('data/dashboards.json');[p['fetch']['primary'].__setitem__('api','/api/v1/x') for x in d['dashboards'] if x['id']=='market_monitor' for p in x['panels'] if p['id']=='fx'];D('data/dashboards.json',d)" "S7 panel market_monitor/fx does not route"
mutant "a fallback does not feed the entry" "$JE;d=L('data/sources.json');[s.__setitem__('feeds',[f for f in s['feeds'] if f['tab']!='nw']) for s in d['sources'] if s['id']=='central_banks'];D('data/sources.json',d)" "S7 panel economic_calendar/week fallback central_banks does not feed"
mutant "a keyed fallback"                  "$JE;d=L('data/dashboards.json');[p['fetch'].setdefault('fallbacks',[]).append({'source':'finnhub'}) for x in d['dashboards'] if x['id']=='earnings_calendar' for p in x['panels'] if p['id']=='week'];D('data/dashboards.json',d)" "S7 panel earnings_calendar/week fallback finnhub"
mutant "a view that cannot draw the derive" "$JE;d=L('data/dashboards.json');[p.__setitem__('view','curve') for x in d['dashboards'] if x['id']=='economic_calendar' for p in x['panels'] if p['id']=='week'];D('data/dashboards.json',d)" "S7 panel economic_calendar/week view curve"
mutant "an unknown slot"                   "$JE;d=L('data/dashboards.json');[p['fetch'].__setitem__('path','/x/{ticker}') for x in d['dashboards'] if x['id']=='crypto' for p in x['panels'] if p['id']=='btc'];D('data/dashboards.json',d)" "S7 panel crypto/btc uses the unknown slot"
mutant "a performance column drifts"       "$JE;d=L('data/dashboards.json');[p['columns'].__setitem__(3,'2W') for x in d['dashboards'] if x['id']=='eq_dm_us' for p in x['panels'] if p['id']=='sectors'];D('data/dashboards.json',d)" "S9 panel eq_dm_us/sectors column 2W"
mutant "Performance drops a column"        "$SUB;S('$KT/Performance.kt','\"WTD\", \"MTD\"','\"MTD\"')" "S9 panel bonds_market/us column WTD"
mutant "a performance item loses its label" "$JE;d=L('data/dashboards.json');[p['fetch']['labels'].pop('XLK') for x in d['dashboards'] if x['id']=='eq_dm_us' for p in x['panels'] if p['id']=='sectors'];D('data/dashboards.json',d)" "S9 panel eq_dm_us/sectors item XLK has no label"
mutant "a model leg loses its proxy"       "$JE;d=L('data/dashboards.json');[p['fetch']['proxies'].pop('Bonds') for x in d['dashboards'] if x['id']=='mp_60_40' for p in x['panels'] if p['id']=='perf'];D('data/dashboards.json',d)" "S9 panel mp_60_40/perf leg Bonds"
mutant "a matrix tenor without a column"   "$JE;d=L('data/dashboards.json');[p['fetch']['tenors'].append(['40Y',40]) for x in d['dashboards'] if x['id']=='yields_matrix' for p in x['panels'] if p['id']=='spot'];D('data/dashboards.json',d)" "S9 panel yields_matrix/spot columns"
mutant "the matrix loses its Cov column"   "$JE;d=L('data/dashboards.json');[p['columns'].remove('Cov') for x in d['dashboards'] if x['id']=='yields_matrix' for p in x['panels'] if p['id']=='fwd'];D('data/dashboards.json',d)" "S9 panel yields_matrix/fwd has no Cov column"
mutant "the matrix averages without a coverage minimum" "$JE;d=L('data/dashboards.json');[p['fetch'].pop('min_coverage_gdp') for x in d['dashboards'] if x['id']=='yields_matrix' for p in x['panels'] if p['id']=='spot'];D('data/dashboards.json',d)" "S9 panel yields_matrix/spot declares no min_coverage_gdp"
mutant "a matrix country without GDP"      "$JE;d=L('data/dashboards.json');[p['fetch']['gdp_usd_bn'].pop('BR') for x in d['dashboards'] if x['id']=='yields_matrix' for p in x['panels'] if p['id']=='spot'];D('data/dashboards.json',d)" "S9 panel yields_matrix/spot country BR has no GDP"
mutant "per-item path with no items"       "$JE;d=L('data/dashboards.json');[p['fetch'].pop('each') for x in d['dashboards'] if x['id']=='crypto' for p in x['panels'] if p['id']=='spot'];D('data/dashboards.json',d)" "S7 panel crypto/spot asks per"
mutant "a provider that is not the source's" "$JE;d=L('data/dashboards.json');[p['fetch'].__setitem__('provider','jsonstat') for x in d['dashboards'] if x['id']=='macro' for p in x['panels'] if p['id']=='us'];D('data/dashboards.json',d)" "S7 panel macro/us provider jsonstat"
mutant "a decision time drifts from its zone" "$JE;d=L('data/central-banks.json');d['banks'][0]['events'][-1]['ts']='2027-12-08T18:00Z';D('data/central-banks.json',d)" "S8 bank fed 2027-12-08 ts"
mutant "a bank loses its source page"      "$JE;d=L('data/central-banks.json');d['banks'][1].pop('source_url');D('data/central-banks.json',d)" "S8 bank ecb has no source_url"
mutant "the calendar runs out"             "$JE;d=L('data/central-banks.json');d['banks'][2]['events']=d['banks'][2]['events'][:2];D('data/central-banks.json',d)" "S8 bank boe: the last declared decision"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
