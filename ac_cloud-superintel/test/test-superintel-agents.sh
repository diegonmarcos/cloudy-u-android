#!/usr/bin/env bash
# test-superintel-agents - Models > Agents and Models > API of Cloud SuperIntel (#1939), statically.
#   G1  assets/agents/agents.json (the ONE declared list, shared with the composer's picker) holds Hermes, Goose, Claude Code, Google AGY, and EVERY agent of the shared
#       descriptor (libs:model-catalogue agents.json: parity, nothing dropped), each with icon, what, where, models, health key
#   G2  Google AGY is declared as not a gateway mode and the composer's list is that same file minus the non-gateway agents (SiAi reads Agents.composer, not AiAgents.load)
#   G3  the Agents page draws the DECLARED rows with the live status layered on top: it never waits on or hides behind the gateway (renders non-empty with the gateway down)
#   G4  the API page lists OpenRouter, Claude Code and Google AGY (provider, auth kind, where the key lives, Cost link, live status), also while the private rows are not served
#   G5  nothing secret-looking in the declared agents and accounts: no key/token/secret-shaped value and no field that could carry one
# then plants a mutation per rule in a scratch copy and requires RED for each.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

check() {   # check <module dir>
python3 -I - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
bad = []
def read(p):
    with open(p, encoding="utf-8") as f: return f.read()
def code(p):
    t = re.sub(r"/\*.*?\*/", "", read(p), flags=re.S)
    return "\n".join(re.sub(r"(?<![:\"])//.*$", "", l) for l in t.splitlines())
kt = os.path.join(app, "app/src/main/java/com/diegonmarcos/cloudsuperintel")
ast = os.path.join(app, "app/src/main/assets")
lib = os.path.join(app, "..", "ab_cloud-libs-shared", "libs", "model-catalogue", "src", "main", "assets", "agents", "agents.json")
fl = os.path.join(app, "../data/fleet/app/com.diegonmarcos.cloudsuperintel")
agents = json.loads(read(os.path.join(ast, "agents/agents.json")))["agents"]
byid = {a["id"]: a for a in agents}

# G1
labels = {a["label"] for a in agents}
for want in ("Hermes", "Goose", "Claude Code", "Google AGY"):
    if want not in labels: bad.append("G1 the agent list lacks %s" % want)
if os.path.exists(lib):
    for a in json.loads(read(lib))["agents"]:
        if a["id"] not in byid: bad.append("G1 the shared descriptor's agent %s (%s) is not in the app's list" % (a["id"], a["label"]))
        elif byid[a["id"]].get("mode") != a["mode"] or byid[a["id"]].get("health_key") != a["health_key"]: bad.append("G1 agent %s changed its gateway mode or health key" % a["id"])
if "openclaw" not in byid: bad.append("G1 OpenClaw is not in the list")
if len(byid) != len(agents): bad.append("G1 an agent id is declared twice")
for a in agents:
    for f in ("id", "label", "mode", "health_key", "icon", "what", "where", "models"):
        if not a.get(f): bad.append("G1 agent %s lacks %s" % (a.get("id"), f))

# G2
if byid.get("agy", {}).get("gateway") is not False: bad.append("G2 Google AGY must be declared gateway:false (not a gateway mode)")
if any(a.get("gateway") is False for a in agents if a["id"] != "agy"): bad.append("G2 only Google AGY is off the gateway")
siai = code(os.path.join(kt, "SiAi.kt"))
if "AiAgents.load" in siai or siai.count("Agents.composer(app)") < 2: bad.append("G2 SiAi must read the composer's agents through Agents.composer (the one declared list)")
ag = code(os.path.join(kt, "Agents.kt"))
if "?.gateway != false" not in ag: bad.append("G2 Agents.composer no longer drops the non-gateway agents")
if 'const val ASSET = "agents/agents.json"' not in ag: bad.append("G2 the declared list is not assets/agents/agents.json")

# G3
pg = code(os.path.join(kt, "ui/AgentsPage.kt"))
for need in ("Agents.rows(Agents.declared(ctx), AiAgents.load(ctx))", "Agents.state(d, health.second)", "AgentState.CHECKING", "chat.chooseAgent(d.id)", "AiSettingsPickers(chat, theme)", "rows.forEach"):
    if need not in pg: bad.append("G3 AgentsPage lacks %s" % need)
if re.search(r"health\.second\s*\?:\s*return|if\s*\(\s*health\.second\s*==\s*null\s*\)\s*return|rows\s*=\s*if\s*\(health", pg): bad.append("G3 AgentsPage hides its rows behind the gateway")
if "if (health == null) return AgentLive(AgentState.DOWN)" not in ag: bad.append("G3 a silent gateway must leave the agents listed as down")
shell = code(os.path.join(kt, "ui/SiShell.kt"))
if "Decl.PAGE_TOOLS_PICKERS -> AgentsPage(chat, theme" not in shell: bad.append("G3 the Agents page is not the shell's Models > Agents")

# G4
acc = json.loads(read(os.path.join(ast, "ai/api-accounts.json")))["accounts"]
accid = {a["id"]: a for a in acc}
for want in ("openrouter", "claude-code", "google-agy"):
    if want not in accid: bad.append("G4 the API accounts lack %s" % want)
for a in acc:
    for f in ("label", "provider", "auth", "key_ref"):
        if not a.get(f): bad.append("G4 account %s lacks %s" % (a["id"], f))
    if a.get("cost") is not True: bad.append("G4 account %s has no Cost link" % a["id"])
pages = json.loads(read(os.path.join(fl, "pages.json")))["pages"]
if pages["apis"].get("accounts") != "ai/api-accounts.json" or pages["apis"].get("asset") != "ai/apis.json": bad.append("G4 the apis page must declare the accounts and keep the private rows asset")
if not os.path.exists(os.path.join(app, "test/fixtures/apis.json")): bad.append("G4 the former API rows fixture is gone")
sc = code(os.path.join(kt, "ui/AiScreens.kt"))
for need in ("accounts: List<ApiAccount>", "ApiAccounts.state(a, health.second)", "onCost()", "R.string.api_key_ref", "R.string.api_auth", "if (cfg == null) item"):
    if need not in sc: bad.append("G4 the API page does not draw %s" % need)
if "onCost = { costFromLink = true }" not in shell: bad.append("G4 the Cost link is not wired to the Cost window")
if "if (it.isBlank()) NotServedPage() else ApisPage" in sc: bad.append("G4 the API page hides the accounts while the private rows are not served")

# G5
SHAPES = [r"sk-ant-[A-Za-z0-9_-]{8,}", r"sk-or-[A-Za-z0-9_-]{8,}", r"sk-[A-Za-z0-9]{20,}", r"gh[pousr]_[A-Za-z0-9]{20,}", r"AKIA[0-9A-Z]{16}",
          r"xox[abprs]-[A-Za-z0-9-]{10,}", r"eyJ[A-Za-z0-9_-]{15,}\.[A-Za-z0-9_-]{15,}", r"Bearer\s+[A-Za-z0-9._-]{16,}", r"-----BEGIN [A-Z ]*PRIVATE KEY-----", r"AIza[0-9A-Za-z_-]{20,}", r"ya29\.[0-9A-Za-z_-]{20,}"]
FORBIDDEN_FIELDS = {"token", "key", "secret", "password", "api_key", "apikey", "value", "bearer", "credential"}
for name, doc in (("agents.json", read(os.path.join(ast, "agents/agents.json"))), ("api-accounts.json", read(os.path.join(ast, "ai/api-accounts.json")))):
    for s in SHAPES:
        if re.search(s, doc): bad.append("G5 %s holds a secret-shaped value (%s)" % (name, s))
    def walk(o, path=""):
        if isinstance(o, dict):
            for k, v in o.items():
                if k.lower() in FORBIDDEN_FIELDS: bad.append("G5 %s has a field %s%s that could carry a secret" % (name, path, k))
                walk(v, path + k + ".")
        elif isinstance(o, list):
            for v in o: walk(v, path)
        elif isinstance(o, str) and re.fullmatch(r"[A-Za-z0-9+/=_-]{32,}", o): bad.append("G5 %s holds a long opaque string at %s (a key?)" % (name, path))
    walk(json.loads(doc))
for f in ("Agents.kt", "ui/AgentsPage.kt"):
    t = code(os.path.join(kt, f))
    for pat in (r"revealAiKey", r"EncryptedSharedPreferences", r"android\.util\.Log", r"Authorization"):
        if re.search(pat, t): bad.append("G5 %s matches %s: this app holds and logs no token" % (f, pat))
for b in bad: print("FAIL " + b)
sys.exit(1 if bad else 0)
PY
}

FAILS=0
ok()  { echo "  PASS: $1"; }
bad() { echo "  FAIL: $1"; FAILS=$((FAILS + 1)); }
echo "[superintel-agents] Models > Agents and Models > API list what the ticket names and lose nothing"
out="$(check "$APP")" && ok "G1-G5 hold on the real tree" || { bad "the real tree is red"; echo "$out"; }

W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mutant() {   # mutant <name> <edit run in the copy> <needle expected in the output>
  rm -rf "$W/ac_cloud-superintel" "$W/ab_cloud-libs-shared" "$W/data"; cp -r "$APP" "$W/ac_cloud-superintel"
  mkdir -p "$W/ab_cloud-libs-shared/libs"; cp -r "$APP/../ab_cloud-libs-shared/libs/model-catalogue" "$W/ab_cloud-libs-shared/libs/model-catalogue"
  mkdir -p "$W/data/fleet/app"; cp -r "$APP/../data/fleet/app/com.diegonmarcos.cloudsuperintel" "$W/data/fleet/app/"
  ( cd "$W/ac_cloud-superintel" && eval "$2" )
  if out="$(check "$W/ac_cloud-superintel" 2>&1)"; then bad "mutant '$1' stayed green"; elif grep -q -- "$3" <<<"$out"; then ok "mutant '$1' is red ($3)"; else bad "mutant '$1' is red for the wrong reason: $out"; fi
}
KT=app/src/main/java/com/diegonmarcos/cloudsuperintel
AG=app/src/main/assets/agents/agents.json
AC=app/src/main/assets/ai/api-accounts.json
PYA="import json;p='$AG';d=json.load(open(p));"
PYC="import json;p='$AC';d=json.load(open(p));"
mutant "Goose is dropped"             "python3 -c \"${PYA}d['agents']=[a for a in d['agents'] if a['id']!='goose'];json.dump(d,open(p,'w'))\"" "G1 the agent list lacks Goose"
mutant "Google AGY is dropped"        "python3 -c \"${PYA}d['agents']=[a for a in d['agents'] if a['id']!='agy'];json.dump(d,open(p,'w'))\"" "G1 the agent list lacks Google AGY"
mutant "Claude Code is relabelled"    "sed -i 's/\"Claude Code\"/\"Claude\"/' $AG" "G1 the agent list lacks Claude Code"
mutant "OpenClaw is dropped"          "python3 -c \"${PYA}d['agents']=[a for a in d['agents'] if a['id']!='openclaw'];json.dump(d,open(p,'w'))\"" "G1 the shared descriptor's agent openclaw"
mutant "a shared agent is dropped"    "python3 -c \"${PYA}d['agents']=[a for a in d['agents'] if a['id']!='deepseek'];json.dump(d,open(p,'w'))\"" "G1 the shared descriptor's agent deepseek"
mutant "an agent loses its icon"      "python3 -c \"${PYA}d['agents'][0]['icon']='';json.dump(d,open(p,'w'))\"" "G1 agent hermes lacks icon"
mutant "AGY becomes a gateway mode"   "python3 -c \"${PYA}[a.update(gateway=True) for a in d['agents'] if a['id']=='agy'];json.dump(d,open(p,'w'))\"" "G2 Google AGY must be declared gateway:false"
mutant "the composer reads its own list" "sed -i 's/Agents.composer(app)/AiAgents.load(app)/' $KT/SiAi.kt" "G2 SiAi must read the composer's agents"
mutant "the composer keeps AGY"       "sed -i 's/?.gateway != false/?.gateway == null/' $KT/Agents.kt" "G2 Agents.composer no longer drops"
mutant "the rows wait for the gateway" "sed -i 's/rows.forEach { d ->/(if (health.second == null) emptyList() else rows).forEach { d ->/' $KT/ui/AgentsPage.kt" "G3 AgentsPage lacks rows.forEach"
mutant "the status is not layered"    "sed -i 's/Agents.state(d, health.second)/AgentLive(AgentState.REACHABLE)/' $KT/ui/AgentsPage.kt" "G3 AgentsPage lacks Agents.state"
mutant "a silent gateway empties it"  "sed -i 's/if (health == null) return AgentLive(AgentState.DOWN)/if (health == null) return AgentLive(AgentState.CHECKING)/' $KT/Agents.kt" "G3 a silent gateway must leave"
mutant "Select does nothing"          "sed -i 's/chat.chooseAgent(d.id)/Unit/' $KT/ui/AgentsPage.kt" "G3 AgentsPage lacks chat.chooseAgent"
mutant "the composer pickers go"      "sed -i 's/AiSettingsPickers(chat, theme)/Unit/' $KT/ui/AgentsPage.kt" "G3 AgentsPage lacks AiSettingsPickers"
mutant "the shell shows the old page" "sed -i 's/AgentsPage(chat, theme, Modifier.fillMaxSize())/Unit/' $KT/ui/SiShell.kt" "G3 the Agents page is not the shell"
mutant "OpenRouter account dropped"   "python3 -c \"${PYC}d['accounts']=[a for a in d['accounts'] if a['id']!='openrouter'];json.dump(d,open(p,'w'))\"" "G4 the API accounts lack openrouter"
mutant "Claude Code account dropped"  "python3 -c \"${PYC}d['accounts']=[a for a in d['accounts'] if a['id']!='claude-code'];json.dump(d,open(p,'w'))\"" "G4 the API accounts lack claude-code"
mutant "Google AGY account dropped"   "python3 -c \"${PYC}d['accounts']=[a for a in d['accounts'] if a['id']!='google-agy'];json.dump(d,open(p,'w'))\"" "G4 the API accounts lack google-agy"
mutant "an account loses key_ref"     "python3 -c \"${PYC}d['accounts'][0]['key_ref']='';json.dump(d,open(p,'w'))\"" "G4 account openrouter lacks key_ref"
mutant "the Cost link is lost"        "python3 -c \"${PYC}d['accounts'][1]['cost']=False;json.dump(d,open(p,'w'))\"" "G4 account claude-code has no Cost link"
mutant "the page forgets accounts"   "python3 -c \"import json;p='../data/fleet/app/com.diegonmarcos.cloudsuperintel/pages.json';d=json.load(open(p));del d['pages']['apis']['accounts'];json.dump(d,open(p,'w'))\"" "G4 the apis page must declare the accounts"
mutant "accounts hide when not served" "sed -i 's/if (cfg == null) item/if (cfg == null \\&\\& false) item/' $KT/ui/AiScreens.kt" "G4 the API page does not draw if (cfg == null) item"
mutant "a key is pasted in"           "python3 -c \"${PYC}d['accounts'][0]['key_ref']='sk-or-v1-0123456789abcdef0123456789abcdef';json.dump(d,open(p,'w'))\"" "G5 api-accounts.json holds a secret-shaped value"
mutant "a token field appears"        "python3 -c \"${PYC}d['accounts'][0]['token']='x';json.dump(d,open(p,'w'))\"" "G5 api-accounts.json has a field accounts.token"
mutant "an opaque string appears"     "python3 -c \"${PYA}d['agents'][0]['what']='QWxhZGRpbjpvcGVuIHNlc2FtZVF3ZXJ0eTEyMzQ1Ng';json.dump(d,open(p,'w'))\"" "G5 agents.json holds a long opaque string"
mutant "the page logs"                "echo 'val x = android.util.Log' >> $KT/ui/AgentsPage.kt" "G5 ui/AgentsPage.kt matches android"
[ "$FAILS" -eq 0 ] && echo "ALL GREEN" || { echo "$FAILS FAILED"; exit 1; }
