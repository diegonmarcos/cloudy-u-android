#!/usr/bin/env bash
# test-superintel-ais - the AIs and Cost sections of Cloud SuperIntel (#1499), statically.
#   A1  the bottom nav is Notebook, CGCs, SI, AIs, Cost; AIs has APIs, MCP, Dotfiles, Plugins; Cost has its page; assets/ai/pages.json holds exactly those page ids
#   A2  APIs: the four owner rows in order, every field, SAMPLE labelled, masked tokens, no token field, the real rows and the token only from the private store
#   A3  no real-secret-shaped value anywhere in the app tree (tokens, keys, bearer, private key blocks)
#   A4  Cost reads the SAME source as the SuperApp Notify AI badge: libs:ai-usage's AiUsageClient/AiUsageModel, the badge's declared base among Cost's bases,
#       no client, URL or route of its own in the app, and the SuperApp no longer carries its own copy
#   A5  the rows are ui-kit KitStoreRow in the shared type ramp: no sp literal, no fontSize number, no colour literal; no name of data in Kotlin
#   A6  the derived assets (MCP, Dotfiles, Plugins) carry no env value and no secret, and equal the derivation when the SOT is at hand
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir>
python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
bj = json.loads(read(os.path.join(app, "build.json")))
ai = os.path.join(app, "app/src/main/assets/ai")
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")
def sec(i): return next(s for s in bj["ui"]["sections"] if s["id"] == i)

# A1
if bj["ui"]["bottom_nav"] != ["notebook", "cgcs", "si", "tools", "ais", "cost"]: bad.append("A1 the bottom nav must be Notebook, CGCs, SI, Tools, AIs, Cost")
if [p["id"] for p in sec("ais")["pages"]] != ["apis", "mcp", "dotfiles", "plugins"]: bad.append("A1 AIs must have APIs, MCP, Dotfiles, Plugins in that order")
if [p["id"] for p in sec("cost")["pages"]] != ["cost"]: bad.append("A1 Cost must have its one page")
pages = json.loads(read(os.path.join(ai, "pages.json")))["pages"]
declared = [p["id"] for i in ("ais", "cost") for p in sec(i)["pages"]]
if sorted(pages) != sorted(declared): bad.append("A1 pages.json ids %s are not the declared AIs and Cost pages %s" % (sorted(pages), sorted(declared)))
for k, v in pages.items():
    if v["kind"] not in ("rows", "apis", "cost"): bad.append("A1 page %r has an unknown kind" % k)
    if not os.path.exists(os.path.join(app, "app/src/main/assets", v["asset"])): bad.append("A1 page %r asset %s is missing" % (k, v["asset"]))

# A2
apis = json.loads(read(os.path.join(ai, "apis.json")))
if [r["label"] for r in apis["rows"]] != ["Claude Account 1", "Claude API 1", "OpenRouter 1", "OpenRouter 2"]: bad.append("A2 the API rows are not the owner's four, in order")
if "sample" not in apis.get("sample_label", "").lower() or "SAMPLE" not in apis["_doc"]: bad.append("A2 the bundled APIs are not labelled sample")
for r in apis["rows"]:
    for f in ("id", "label", "provider", "login", "key_name", "token_masked", "created", "last_used", "links"):
        if not r.get(f): bad.append("A2 row %s lacks %s" % (r.get("id"), f))
    if not r.get("token_masked", "").startswith("••••"): bad.append("A2 row %s token is not masked" % r.get("id"))
    if set(r) - {"id", "label", "provider", "login", "key_name", "token_masked", "created", "last_used", "links"}: bad.append("A2 row %s carries a field beyond the masked set (a token?)" % r.get("id"))
    if not r.get("login", "").endswith("@example.invalid"): bad.append("A2 row %s login is not a fake address" % r.get("id"))
if not apis.get("private", {}).get("authority") or "{id}" not in apis["private"].get("token_key", ""): bad.append("A2 no private source declared for the real rows and the token")
apis_kt = code(os.path.join(kt, "Apis.kt"))
if "if (r.sample) null else src.get(" not in apis_kt: bad.append("A2 reveal does not come only from the private source")

# A3
SHAPES = [r"sk-ant-[A-Za-z0-9_-]{8,}", r"sk-or-[A-Za-z0-9_-]{8,}", r"sk-[A-Za-z0-9]{20,}", r"gh[pousr]_[A-Za-z0-9]{20,}", r"AKIA[0-9A-Z]{16}",
          r"xox[abprs]-[A-Za-z0-9-]{10,}", r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}", r"Bearer\s+[A-Za-z0-9._-]{16,}", r"-----BEGIN [A-Z ]*PRIVATE KEY-----"]
for dp, dn, fn in os.walk(os.path.join(app, "app/src/main")):
    for f in fn:
        if f.endswith((".json", ".kt", ".xml", ".gradle")):
            t = read(os.path.join(dp, f))
            for s in SHAPES:
                if re.search(s, t): bad.append("A3 %s holds a secret-shaped value (%s)" % (f, s))

# A4
cost = json.loads(read(os.path.join(ai, "cost.json")))
cost_kt = code(os.path.join(kt, "Cost.kt"))
sa = os.path.join(app, "../aa_cloud-superapp")
if os.path.isdir(sa):
    badge = json.loads(read(os.path.join(sa, "build.json")))["ui"]["c3_ops"]["base_url"]
    if badge not in cost["usage"]["bases"]: bad.append("A4 the badge's endpoint %s is not among Cost's bases" % badge)
    if os.path.exists(os.path.join(sa, "app/src/main/java/com/diegonmarcos/superapp/notificationcenter/AiUsageClient.kt")): bad.append("A4 the SuperApp still carries its own AiUsageClient")
    if "AiUsageClient." not in code(os.path.join(sa, "app/src/main/java/com/diegonmarcos/superapp/notificationcenter/AiBadgeService.kt")): bad.append("A4 the badge no longer reads through AiUsageClient")
if "import com.diegonmarcos.superapp.notificationcenter.AiUsageClient" not in cost_kt or "import com.diegonmarcos.superapp.notificationcenter.AiUsageModel" not in cost_kt: bad.append("A4 Cost does not use the shared client and model")
if "AiUsageClient.fetchAny" not in cost_kt: bad.append("A4 Cost does not fetch through AiUsageClient")
for pat in (r"/ai/usage", r"HttpURLConnection", r"URL\(", r"OkHttp", r"https?://"):
    for f in ("AiData.kt", "Apis.kt", "Cost.kt", "ui/AiScreens.kt"):
        if re.search(pat, code(os.path.join(kt, f))): bad.append("A4 %s has its own %s: Cost reads through libs:ai-usage only" % (f, pat))
if "libs:ai-usage" not in bj["modules"] or "libs:ai-usage" not in bj["modules"]["app"]["depends_on"]: bad.append("A4 build.json does not link libs:ai-usage")
if "project(':libs:ai-usage')" not in code(os.path.join(app, "app/build." + "gradle")): bad.append("A4 the app's build script does not link libs:ai-usage")
if os.path.isdir(sa) and "project(':libs:ai-usage')" not in code(os.path.join(sa, "app/build." + "gradle")): bad.append("A4 the SuperApp's build script does not link libs:ai-usage")

# A5
ui = code(os.path.join(kt, "ui/AiScreens.kt"))
for need in ("KitStoreRowLine(", "KitStoreName(", "KitStoreCaption(", "CloudKitTheme("):
    if need not in ui: bad.append("A5 AiScreens does not use %s" % need)
if re.search(r"\b\d+(\.\d+)?\.sp\b|\bTextUnit\(|fontSize\s*=\s*\d", ui): bad.append("A5 AiScreens spells an sp literal: the type ramp is KitStoreRow's")
if re.search(r"\bColor\(0x|Color\.(Red|Green|Blue|White|Black)", ui): bad.append("A5 AiScreens spells a colour literal")
for f in ("AiData.kt", "Apis.kt", "Cost.kt", "ui/AiScreens.kt", "ui/SiShell.kt"):
    t = code(os.path.join(kt, f))
    for name in ("Claude Account", "OpenRouter 1", "Dotfiles", "Plugins", "cloudy-c3-mcp", "settings.project"):
        if name in t: bad.append("A5 %s spells the data name %r: it is data" % (f, name))
if "pages.json" in code(os.path.join(kt, "Decl.kt")) or re.search(r'PAGE_(APIS|MCP|DOTFILES|PLUGINS|COST)', code(os.path.join(kt, "Decl.kt"))): bad.append("A5 Decl.kt names an AIs or Cost page: they are data")

# A6
for n in ("mcp", "dotfiles", "plugins"):
    d = json.loads(read(os.path.join(ai, n + ".json")))
    for g in d["groups"]:
        if g["title"].endswith("Env names"):
            for r in g["rows"]:
                if r["end"] or r["caption"] != "name only, value never shown": bad.append("A6 env row %s carries a value" % r["title"])
    if re.search(r"https?://|10\.0\.0\.", read(os.path.join(ai, n + ".json"))): bad.append("A6 %s.json spells an address" % n)
sot = os.environ.get("CLAUDE_SOT_DIR", "")
if sot and os.path.isdir(sot):
    import subprocess
    if subprocess.call([sys.executable, "-I", os.path.join(app, "tools/derive-ai-data.py"), sot, "--check"]) != 0: bad.append("A6 the derived assets differ from the SOT: rerun tools/derive-ai-data.py")

for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }

echo "[superintel-ais] AIs and Cost"
out="$(check "$APP")" && ok "A1-A6 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

# mutations on a scratch copy next to the SuperApp (A4 reads ../aa_cloud-superapp): each rule must go red
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <shell edit run in the copy> <needle expected in the output>
  rm -rf "$W/ac_cloud-superintel" "$W/aa_cloud-superapp"; cp -r "$APP" "$W/ac_cloud-superintel"
  [ -d "$APP/../aa_cloud-superapp" ] && { mkdir -p "$W/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/notificationcenter"; cp "$APP/../aa_cloud-superapp/build.json" "$W/aa_cloud-superapp/"; cp "$APP/../aa_cloud-superapp/app/build.gradle" "$W/aa_cloud-superapp/app/"; cp "$APP/../aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/notificationcenter/AiBadgeService.kt" "$W/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/notificationcenter/"; }
  ( cd "$W/ac_cloud-superintel" && eval "$2" )
  if out="$(check "$W/ac_cloud-superintel" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudsuperintel
AI=app/src/main/assets/ai
PYJ="import json;p='build.json';d=json.load(open(p));"
mutant "nav order changes"        "python3 -c \"${PYJ}d['ui']['bottom_nav']=['notebook','si','cgcs','ais','cost'];json.dump(d,open(p,'w'))\"" "A1 the bottom nav must be"
mutant "AIs loses Plugins"        "python3 -c \"${PYJ}[s['pages'].pop() for s in d['ui']['sections'] if s['id']=='ais'];json.dump(d,open(p,'w'))\"" "A1 AIs must have"
mutant "a page loses its data"    "sed -i '/\"plugins\"/d' $AI/pages.json"                                     "A1 pages.json ids"
mutant "an API row is renamed"    "sed -i 's/OpenRouter 2/OpenRouter 3/' $AI/apis.json"                        "A2 the API rows are not"
mutant "a token is unmasked"      "sed -i '0,/••••-sample/s//plain/' $AI/apis.json"                            "A2 row .* token is not masked"
mutant "a token field appears"    "sed -i '0,/\"token_masked\"/s//\"token\": \"x\", \"token_masked\"/' $AI/apis.json" "A2 row .* beyond the masked set"
mutant "sample label dropped"     "sed -i 's/SAMPLE data only/data only/' $AI/apis.json"                       "A2 the bundled APIs are not labelled sample"
mutant "reveal leaves the store"  "sed -i 's/if (r.sample) null else src.get(/if (r.sample) null else cfg.get(/' $KT/Apis.kt"  "A2 reveal does not come only"
mutant "a key shape is planted"   "echo '{\"k\": \"sk-or-v1-aaaaaaaaaaaaaaaaaaaaaaaa\"}' > $AI/planted.json"      "A3 planted.json holds a secret-shaped value"
mutant "a bearer is planted"      "echo '// Bearer abcdefghijklmnopqrstuvwxyz012345' >> $KT/Cost.kt"           "A3 Cost.kt holds a secret-shaped value"
mutant "badge base not in Cost"   "sed -i 's#/c3-infra-api#/other-api#' $AI/cost.json"                          "A4 the badge's endpoint"
mutant "Cost grows its own route" "echo 'val u = \"/ai/usage\"' >> $KT/Cost.kt"                                 "A4 Cost.kt has its own /ai/usage"
mutant "Cost grows its own client" "echo 'val c = HttpURLConnection::class' >> $KT/Cost.kt"                     "A4 Cost.kt has its own HttpURLConnection"
mutant "Cost leaves the client"   "sed -i 's/AiUsageClient.fetchAny/Mine.fetchAny/' $KT/Cost.kt"                "A4 Cost does not fetch through AiUsageClient"
mutant "lib not linked"           "sed -i \"s/project(':libs:ai-usage')/project(':libs:nope')/\" app/build.gradle" "A4 the app's build script does not link libs:ai-usage"
mutant "an sp literal creeps in"  "echo 'val x = 12.sp' >> $KT/ui/AiScreens.kt"                                 "A5 AiScreens spells an sp literal"
mutant "a colour literal"         "echo 'val x = Color(0xFF000000)' >> $KT/ui/AiScreens.kt"                     "A5 AiScreens spells a colour literal"
mutant "rows leave the Store row" "sed -i 's/KitStoreRowLine(/Foo(/g' $KT/ui/AiScreens.kt"                      "A5 AiScreens does not use KitStoreRowLine("
mutant "a name is spelled"        "echo 'val n = \"Claude Account 1\"' >> $KT/Apis.kt"                          "A5 Apis.kt spells the data name"
mutant "a page id in Decl"        "echo 'const val PAGE_MCP = \"mcp\"' >> $KT/Decl.kt"                          "A5 Decl.kt names an AIs or Cost page"
mutant "an env value appears"     "sed -i '0,/\"name only, value never shown\"/s//\"x\"/' $AI/dotfiles.json"    "A6 env row"
mutant "an address appears"       "sed -i '0,/\"title\"/s//\"u\": \"http:\\/\\/10.0.0.6\", \"title\"/' $AI/mcp.json" "A6 mcp.json spells an address"

[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
