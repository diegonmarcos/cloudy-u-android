#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #913 Cloud Search Agents - DRAFT ONLY: nothing is posted, submitted or    ║
# ║ sent on the owner's behalf; the human copies a draft and sends it         ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   D1  no code path posts or submits to an external site: across the agent code (core/agents, the Agents,
#       Reports and Cloud pages, data/AgentData.kt, AgentService.kt, the debug API) there is no POST, no
#       request body, no WebView or script, no send/share/mailto/SMS intent, no form fill or click verb
#   D2  the only POST in the whole app is a model call: the agents' to OpenRouter's declared chat_url
#       (core/agents/Llm.kt: `http.post(ai.chatUrl,`) and the chat's, which is libs:ai's two backends (OpenRouter
#       and, when the person picked a chat agent, the fleet agent gateway at the baked address, the path and the
#       mode header from the shared ai.json) reaching the network only through data/SearchAi.kt's one Http door; and (#1184)
#       the code indexes' MCP client (core/Cgc.kt `http.post(url,`: JSON-RPC over POST is how MCP talks), which only
#       calls the two READ-ONLY search tools (cgc.octocode.search, cgc.octocode.graphrag) and never names the index
#       tool that would write; no other caller
#   D3  every declared agent is mode draft_only and the parser refuses any other mode; #913b no agent or source
#       declares an auto_* or action key (submit, send, apply, buy, ...), and the parser refuses one, and any key
#       that is not on its list of an agent's keys
#   D4  the agents' doors are read-only: MailSource has messages/body, PageSource has text, the browser is
#       opened only through Ipc.openExtras with the declared open action, and nothing else is started
#   D5  the review list offers Copy message and Open listing in Cloud Browser (and the person's own marks);
#       no string of the Agents pages is a Send / Submit / Post action
#   D6  the budget guard runs before every model call (ledger.check precedes llm.complete) and the audit log
#       records the reads and drafts but never a body, a page's text or a message
#   D7  the OpenRouter key: read only through Account.token (the fleet Account), never stored, logged or
#       placed anywhere but the Authorization header
#   D8  the Agents pages are dense: no minimum height anywhere in them
#   MUT each property, broken on a copy, goes red
#
# OWN-SOURCE ONLY. python3 only, no network, no build.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
FAILURES=0
for f in build.json core/src/main/kotlin/com/diegonmarcos/cloudsearch/core/agents/Runner.kt core/src/main/kotlin/com/diegonmarcos/cloudsearch/core/agents/Llm.kt \
         app/src/main/java/com/diegonmarcos/cloudsearch/ui/AgentsPages.kt app/src/main/java/com/diegonmarcos/cloudsearch/data/AgentData.kt; do
    [ -f "$APP/$f" ] || { echo "ERROR missing source: $APP/$f - this tester is unrun, not passing"; exit 1; }
done
CHECK="$(mktemp)"
trap 'rm -f "$CHECK"; rm -rf "${WORK:-}"' EXIT
cat > "$CHECK" <<'PY'
import glob, json, os, re, sys

app = sys.argv[1]
bad = []
K = os.path.join(app, "app", "src", "main", "java", "com", "diegonmarcos", "cloudsearch")
C = os.path.join(app, "core", "src", "main", "kotlin", "com", "diegonmarcos", "cloudsearch", "core")

def code(p):
    return "\n".join(l for l in open(p, encoding="utf-8").read().split("\n") if not re.match(r"\s*(\*|//|/\*)", l))

agent_core = sorted(glob.glob(os.path.join(C, "agents", "*.kt")) + [os.path.join(C, "Cloud.kt")])
agent_app = [os.path.join(K, x) for x in ("ui/AgentsPages.kt", "ui/ReportsPages.kt", "ui/CloudPages.kt", "data/AgentData.kt", "data/AgentService.kt", "debugapi/SearchDebugApi.kt")]
agent_all = agent_core + agent_app

# D1
forbidden = [
    (r'\.post\(', "an HTTP post"), (r'"POST"|"PUT"|"PATCH"|"DELETE"', "a write verb"), (r'\bdoOutput\b|outputStream|setDoOutput', "a request body"),
    (r'\bWebView\b|evaluateJavascript|loadUrl|addJavascriptInterface', "a web view or script"),
    (r'ACTION_SEND\b|ACTION_SENDTO|ACTION_SEND_MULTIPLE|createChooser|mailto:|SmsManager|sendTextMessage|sendBroadcast', "a send/share/mail/SMS action"),
    (r'fill_form|agent_fill|agent_click|\.submit\(|FormBody|MultipartBody|HttpPost|URLConnection|HttpURLConnection|java\.net\.', "a form, submit or raw connection"),
    (r'\bsend(Message|Mail|Reply)\b|\bsubmit[A-Z]\w*\(|\bpostTo\b|\breply(To|All)\b', "a send/submit/reply verb"),
]
for p in agent_all:
    t = code(p)
    for pat, what in forbidden:
        if os.path.basename(p) == "Llm.kt" and what == "an HTTP post":
            continue  # D2 owns it
        m = re.search(pat, t)
        if m:
            bad.append("D1 %s has %s (%s) — agents read and draft, the person sends" % (os.path.basename(p), what, m.group(0)))

# D2
posts = []
for p in sorted(glob.glob(os.path.join(K, "**", "*.kt"), recursive=True)) + sorted(glob.glob(os.path.join(C, "**", "*.kt"), recursive=True)):
    for m in re.finditer(r'\.post\(([^,]*),', code(p)):
        posts.append((os.path.relpath(p, app), m.group(1).strip()))
want = [("app/src/main/java/com/diegonmarcos/cloudsearch/data/SearchAi.kt", "url"), ("core/src/main/kotlin/com/diegonmarcos/cloudsearch/core/agents/Llm.kt", "ai.chatUrl"),
        ("core/src/main/kotlin/com/diegonmarcos/cloudsearch/core/Cgc.kt", "url")]
if sorted(posts) != sorted(want):
    bad.append("D2 the POSTs in the app are %s; only the agents' model call to ai.chatUrl, the chat's one Http door (libs:ai's backends) and the code indexes' MCP client are allowed" % posts)
cgc_ = code(os.path.join(C, "Cgc.kt")) if os.path.isfile(os.path.join(C, "Cgc.kt")) else ""
if 'const val SEARCH_TOOL = "cgc.octocode.search"' not in cgc_ or 'const val GRAPH_TOOL = "cgc.octocode.graphrag"' not in cgc_ or re.search(r"octocode\.index|cgc\.[a-z]+\.(write|delete|update|put)", cgc_):
    bad.append("D2 the code indexes' MCP client calls a tool other than the two read-only search tools")
sai = os.path.join(K, "data", "SearchAi.kt")
sai_code = code(sai) if os.path.isfile(sai) else ""
if "AiGatewayBackend(cfg, BuildConfig.AGENT_GATEWAY_URL, http)" not in sai_code or "direct = AiRelayBackend(" not in sai_code:
    bad.append("D2 the chat's POSTs do not go through libs:ai's two backends (OpenRouter through the AI-key owner engine #1801, and the fleet gateway at the baked address)")
if os.path.isfile(os.path.join(C, "agents", "Llm.kt")) and '"tools"' in code(os.path.join(C, "agents", "Llm.kt")):
    bad.append("D2 the agents' model call carries tools — a model may only answer in text")

# D3
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))
ag = bj.get("agents") or {}  # #913b the catalogue lives in build.json::agents (Decl puts it back under search.agents)
if not ag.get("agents"):
    bad.append("D3 build.json::agents.agents is empty")
for a in ag.get("agents") or []:
    if a.get("mode") != "draft_only":
        bad.append("D3 agent %s is mode %r — only draft_only exists" % (a.get("id"), a.get("mode")))
cfg = code(os.path.join(C, "agents", "AgentsConfig.kt"))
if not re.search(r'require\(a\.optString\("mode"\) == DRAFT_ONLY\)', cfg) or 'const val DRAFT_ONLY = "draft_only"' not in cfg:
    bad.append("D3 AgentsConfig does not refuse an agent that is not draft_only")
acting = re.compile(r"^(auto|submit|send|apply|buy|post|checkout|order|book|pay|contact|reply)", re.I)
for a in ag.get("agents") or []:
    for k in a:
        if acting.match(k):
            bad.append("D3 agent %s declares %s — an agent drafts, it never acts" % (a.get("id"), k))
    for src_ in a.get("sources") or []:
        for k in src_:
            if acting.match(k):
                bad.append("D3 agent %s source %s declares %s — an agent drafts, it never acts" % (a.get("id"), src_.get("id"), k))
if 'refuseActions(a, AGENT_KEYS,' not in cfg or 'refuseActions(s, SOURCE_KEYS,' not in cfg or "require(k in allowed)" not in cfg \
        or not re.search(r'require\(!key\.startsWith\("auto"\) && key !in FORBIDDEN_KEYS', cfg):
    bad.append("D3 AgentsConfig does not refuse an agent or source key that could make it act (auto_*, submit, send, ...)")

# D4
eng = code(os.path.join(C, "agents", "Engines.kt"))
def iface(name, text):
    m = re.search(r"interface %s \{(.*?)\n\}" % name, text, re.S)
    return sorted(re.findall(r"fun (\w+)\(", m.group(1))) if m else None
if iface("MailSource", eng) != ["body", "messages"]:
    bad.append("D4 MailSource is not exactly messages + body: %s" % iface("MailSource", eng))
if iface("PageSource", eng) != ["text"]:
    bad.append("D4 PageSource is not exactly text: %s" % iface("PageSource", eng))
rep = code(os.path.join(C, "agents", "Reports.kt"))
if iface("ReportSink", rep) != ["publish"]:
    bad.append("D4 ReportSink is not exactly publish: %s" % iface("ReportSink", rep))
data = code(os.path.join(K, "data", "AgentData.kt"))
starts = re.findall(r"startActivity\(([^)]*)\)", data)
if starts != ["i", "i"] and starts != ["i"] + ["i"]:
    # BrowserDoor.open starts the declared open intent; Launch.app starts a launcher intent. Nothing else.
    bad.append("D4 AgentData.kt starts activities other than the browser's declared open intent and a fleet app launch: %s" % starts)
if "Intent(b.openAction)" not in data or "Ipc.openExtras(b, url, group)" not in data:
    bad.append("D4 the browser is not opened through the declared action and Ipc.openExtras")
for p in agent_app:
    t = code(p)
    if os.path.basename(p) != "AgentData.kt" and re.search(r"startActivity\(", t):
        bad.append("D4 %s starts an activity — only AgentData.kt's two doors may" % os.path.basename(p))

# D5
pages = code(os.path.join(K, "ui", "AgentsPages.kt"))
for need in ("R.string.agents_copy", "R.string.agents_open_listing", "svc.browser.open(item.url, group)", "clip.setText("):
    if need not in pages:
        bad.append("D5 the review list lacks %s" % need)
strings = open(os.path.join(app, "app", "src", "main", "res", "values", "strings.xml"), encoding="utf-8").read()
for name, val in re.findall(r'<string name="((?:agents|reports|cloud)_[\w]+)">(.*?)</string>', strings):
    if re.match(r"\s*(send|submit|post|apply to|reply|contact|auto)\b", val, re.I):
        bad.append("D5 string %s (%r) reads as a send/submit action — the person sends" % (name, val))
    if re.search(r"send|submit|post", name):
        bad.append("D5 string %s is named like a send/submit action" % name)

# D6
run = code(os.path.join(C, "agents", "Runner.kt"))
ci, cc = run.find("ledger.check("), run.find("llm.complete(")
if ci < 0 or cc < 0 or ci > cc:
    bad.append("D6 the budget is not checked (ledger.check) before the model call (llm.complete)")
if "ledger.record(" not in run:
    bad.append("D6 the cost of a model call is not recorded")
for m in re.finditer(r"audit\.add\(([^\n]*)", run):
    # only the code of the call: a literal's words are not content, what a ${...} places in it is
    line = re.sub(r'"(?:[^"\\]|\\.)*"', lambda q: " ".join(re.findall(r"\$\{([^}]*)\}", q.group(0))), m.group(1))
    if re.search(r"\bbody\b|\.text\b(?!\.length)|\.html\b|\.message\b(?!\s*\?:)|\bpersonal\b(?!\.isNotEmpty)|\babout\b|\bprofile\b|\.user\b|\.system\b", line):
        bad.append("D6 an audit line carries content, not just what was touched: audit.add(%s" % m.group(1)[:100])
for kind in ("MAIL_QUERY", "MAIL_READ", "LINK_FOUND", "PAGE_READ", "LLM_CALL", "DRAFT"):
    if "AuditEvent.%s" % kind not in run:
        bad.append("D6 the run does not audit %s" % kind)

# D7
holders = sorted(os.path.relpath(p, K) for p in glob.glob(os.path.join(K, "**", "*.kt"), recursive=True) if "revealAiKey" in code(p))
if holders:
    bad.append("D7 revealAiKey must be called by nothing in this app (#1801 the AI-key owner engine holds the key), found in %s" % holders)
svc = code(os.path.join(K, "data", "AgentService.kt"))
if "Account.relay(" not in svc or "RelayLlm" not in svc:
    bad.append("D7 AgentService does not relay the model call through the AI-key owner engine (Account.relay)")
for p in agent_all:
    t = code(p)
    if re.search(r"\bLog\.[a-z]\(|println\(|printStackTrace", t):
        bad.append("D7 %s logs — a key or a message must never reach logcat" % os.path.basename(p))
    if os.path.basename(p) not in ("Llm.kt", "AgentService.kt") and re.search(r"\btoken\b|apiKey|Bearer", t) and os.path.basename(p) not in ("AgentsPages.kt", "SearchDebugApi.kt"):
        bad.append("D7 %s handles the key — only Llm.kt (header) and AgentService.kt (the read) may" % os.path.basename(p))
llm = code(os.path.join(C, "agents", "Llm.kt"))
if "Chat.headers(ai, t)" not in llm or re.search(r"put\([^)]*\bt\b", llm.replace("put(\"usage\"", "")):
    bad.append("D7 the key goes anywhere but Chat.headers (the Authorization header)")
if re.search(r"putString\([^)]*(token|key)", data + svc, re.I):
    bad.append("D7 the key is stored")

# D8
for p in agent_app[:3]:
    if re.search(r"minHeight|heightIn\(|defaultMinSize|sizeIn\(|requiredHeightIn|Modifier\.height\(", code(p)):
        bad.append("D8 %s inflates a minimum height — the Agents pages are data-dense" % os.path.basename(p))

for b in bad:
    print("  FAIL  " + b)
sys.exit(1 if bad else 0)
PY

echo "── D1-D8 against the tree ──"
if python3 "$CHECK" "$APP"; then echo "  PASS  D1-D8"; else FAILURES=$((FAILURES + 1)); fi

WORK="$(mktemp -d)"
J='app/src/main/java/com/diegonmarcos/cloudsearch'
C='core/src/main/kotlin/com/diegonmarcos/cloudsearch/core'
mutate() {  # name, file, python expression over s, expected fragment
    local name="$1" rel="$2" expr="$3" want="$4" copy="$WORK/$1"
    mkdir -p "$copy"
    cp -r "$APP/build.json" "$APP/app" "$APP/core" "$copy/"
    if ! python3 - "$copy/$rel" "$expr" <<'PY'
import sys
p, expr = sys.argv[1], sys.argv[2]
s = open(p, encoding="utf-8").read()
t = eval(expr)
if t == s:
    sys.exit(1)
open(p, "w", encoding="utf-8").write(t)
PY
    then echo "  VOID  MUT $name: the edit did not land"; FAILURES=$((FAILURES + 1)); return; fi
    local out
    out="$(python3 "$CHECK" "$copy" 2>&1)"
    if [ $? -eq 0 ]; then echo "  FAIL  MUT $name: the check passed a broken tree"; FAILURES=$((FAILURES + 1))
    elif [[ "$out" != *"$want"* ]]; then echo "  FAIL  MUT $name: red for the wrong reason: $out"; FAILURES=$((FAILURES + 1))
    else echo "  PASS  MUT $name"; fi
}
mutate runner-posts "$C/agents/Runner.kt" 's.replace("val r = llm.complete(req)", "val r = llm.complete(req); http.post(\"https://x.example\", emptyMap(), r.text ?: \"\", 1)")' "D1 Runner.kt has an HTTP post"
mutate write-verb "$C/agents/Engines.kt" 's + "\nprivate const val V = \"POST\"\n"' "D1 Engines.kt has a write verb"
mutate request-body "$J/data/AgentData.kt" 's.replace("override fun text(url: String, maxChars: Int): PageText {", "override fun text(url: String, maxChars: Int): PageText {\n        val c = java.net.URL(url).openConnection(); c.doOutput = true")' "D1 AgentData.kt has"
mutate webview-in-ui "$J/ui/AgentsPages.kt" 's + "\nprivate fun wv(c: android.content.Context) = android.webkit.WebView(c)\n"' "D1 AgentsPages.kt has a web view or script"
mutate send-intent "$J/ui/AgentsPages.kt" 's + "\nprivate val s = android.content.Intent(android.content.Intent.ACTION_SENDTO)\n"' "D1 AgentsPages.kt has a send/share/mail/SMS action"
mutate mailto "$J/ui/ReportsPages.kt" 's + "\nprivate const val M = \"mailto:x@y\"\n"' "D1 ReportsPages.kt has a send/share/mail/SMS action"
mutate send-verb "$C/agents/Runner.kt" 's.replace("fun run(input: Input): Outcome {", "fun sendReply() {}\n\n    fun run(input: Input): Outcome {")' "D1 Runner.kt has a send/submit/reply verb"
mutate second-post "$J/data/AgentService.kt" 's.replace("val out = runner.run(", "http.post(\"https://x.example\", emptyMap(), \"\", 1); val out = runner.run(")' "D2 the POSTs in the app are"
mutate post-elsewhere "$J/data/SearchAi.kt" 's.replace("s.http.post(url,", "s.http.post(cfg.modelsUrl,")' "D2 the POSTs in the app are"
mutate gateway-anywhere "$J/data/SearchAi.kt" 's.replace("AiGatewayBackend(cfg, BuildConfig.AGENT_GATEWAY_URL, http)", "AiGatewayBackend(cfg, \"http://x.example\", http)")' "D2 the chat's POSTs do not go through libs:ai's two backends"
mutate mcp-index-tool "$C/Cgc.kt" 's.replace("const val GRAPH_TOOL = \"cgc.octocode.graphrag\"", "const val GRAPH_TOOL = \"cgc.octocode.index\"")' "D2 the code indexes' MCP client calls a tool other than"
mutate tools-in-llm "$C/agents/Llm.kt" 's.replace(".put(\"max_tokens\", req.maxTokens)", ".put(\"max_tokens\", req.maxTokens).put(\"tools\", JSONArray())")' "D2 the agents' model call carries tools"
mutate auto-send-agent build.json 's.replace("\"mode\": \"draft_only\"", "\"mode\": \"auto_send\"")' "D3 agent rs_house_purchase is mode 'auto_send'"
mutate auto-submit-key build.json 's.replace("\"mode\": \"draft_only\",", "\"mode\": \"draft_only\",\n        \"auto_submit\": true,", 1)' "D3 agent rs_house_purchase declares auto_submit"
mutate source-send-key build.json 's.replace("\"mail_subject\": \"kaufen\",", "\"mail_subject\": \"kaufen\",\n            \"send_to\": \"x\",", 1)' "D3 agent rs_house_purchase source immoscout24 declares send_to"
mutate key-list-dropped "$C/agents/AgentsConfig.kt" 's.replace("require(k in allowed)", "require(true)")' "D3 AgentsConfig does not refuse an agent or source key"
mutate action-keys-allowed "$C/agents/AgentsConfig.kt" 's.replace("require(!key.startsWith(\"auto\") && key !in FORBIDDEN_KEYS", "require(key !in emptySet<String>()")' "D3 AgentsConfig does not refuse an agent or source key"
mutate mode-not-refused "$C/agents/AgentsConfig.kt" 's.replace("require(a.optString(\"mode\") == DRAFT_ONLY)", "require(true)")' "D3 AgentsConfig does not refuse"
mutate mail-gets-a-send "$C/agents/Engines.kt" 's.replace("fun body(accountId: String, id: String): MailBody?", "fun body(accountId: String, id: String): MailBody?\n    fun deliver(to: String)")' "D4 MailSource is not exactly"
mutate page-gets-a-form "$C/agents/Engines.kt" 's.replace("fun text(url: String, maxChars: Int): PageText", "fun text(url: String, maxChars: Int): PageText\n    fun fill(url: String)")' "D4 PageSource is not exactly"
mutate sink-gets-a-send "$C/agents/Reports.kt" 's.replace("fun publish(report: Report)\n}", "fun publish(report: Report)\n    fun share(report: Report)\n}")' "D4 ReportSink is not exactly"
mutate extra-start "$J/ui/CloudPages.kt" 's + "\nprivate fun go(c: android.content.Context) = c.startActivity(android.content.Intent())\n"' "D4 CloudPages.kt starts an activity"
mutate open-bypasses-door "$J/data/AgentData.kt" 's.replace("Ipc.openExtras(b, url, group)", "mapOf<String, String>()")' "D4 the browser is not opened through the declared action"
mutate copy-removed "$J/ui/AgentsPages.kt" 's.replace("R.string.agents_copy", "R.string.agents_dismiss")' "D5 the review list lacks R.string.agents_copy"
mutate open-listing-removed "$J/ui/AgentsPages.kt" 's.replace("svc.browser.open(item.url, group)", "Unit")' "D5 the review list lacks svc.browser.open"
mutate send-button-string app/src/main/res/values/strings.xml 's.replace(">Copy message<", ">Send message<")' "D5 string agents_copy"
mutate send-string-name app/src/main/res/values/strings.xml 's.replace("agents_dismiss", "agents_submit_dismiss")' "D5 string agents_submit_dismiss is named like"
mutate budget-after-call "$C/agents/Runner.kt" 's.replace("when (val ok = ledger.check(est)) {", "when (val ok = (llm.complete(req).let { ledger.check(est) })) {")' "D6 the budget is not checked"
mutate cost-not-recorded "$C/agents/Runner.kt" 's.replace("ledger.record(c); cost = c", "cost = c")' "D6 the cost of a model call is not recorded"
mutate audit-carries-body "$C/agents/Runner.kt" 's.replace("audit.add(clock(), AuditEvent.MAIL_READ, h.subject, \"id=${h.id} from=${h.fromEmail}\")", "audit.add(clock(), AuditEvent.MAIL_READ, h.subject, body.text)")' "D6 an audit line carries content"
mutate audit-carries-page "$C/agents/Runner.kt" 's.replace("\"chars=${page.text.length} title=${page.title}\")", "page.text)")' "D6 an audit line carries content"
mutate draft-not-audited "$C/agents/Runner.kt" 's.replace("AuditEvent.DRAFT", "AuditEvent.RUN_END")' "D6 the run does not audit DRAFT"
mutate second-key-door "$J/ui/AgentsPages.kt" 's + "\nprivate fun k(c: com.diegonmarcos.superapp.texttools.TextToolsClient) = c.revealAiKey(\"openrouter\")\n"' "D7 revealAiKey must be called by nothing in this app"
mutate agents-key-door "$J/data/AgentService.kt" 's.replace("Account.relay(s.app, body)", "Account.token(s.app, \"openrouter\").let { com.diegonmarcos.cloudsearch.data.Account.relay(s.app, body) }").replace("RelayLlm", "OpenRouterLlm")' "D7 AgentService does not relay"
mutate key-logged "$J/data/AgentService.kt" 's.replace("val out = runner.run(", "android.util.Log.d(\"k\", \"x\"); val out = runner.run(")' "D7 AgentService.kt logs"
mutate key-in-body "$C/agents/Llm.kt" 's.replace(".put(\"max_tokens\", req.maxTokens)", ".put(\"max_tokens\", req.maxTokens).put(\"key\", t)")' "D7 the key goes anywhere but Chat.headers"
mutate key-stored "$J/data/AgentData.kt" 's.replace("fun resetTemplate(id: String)", "fun keep(token: String) = p.edit().putString(\"token\", token).apply()\n    fun resetTemplate(id: String)")' "D7 the key is stored"
mutate key-in-runner "$C/agents/Runner.kt" 's.replace("fun run(input: Input): Outcome {", "private val token = \"\"\n\n    fun run(input: Input): Outcome {")' "D7 Runner.kt handles the key"
mutate tall-agents-page "$J/ui/AgentsPages.kt" 's + "\nprivate val tall = androidx.compose.ui.Modifier.heightIn(min = 200.dp)\n"' "D8 AgentsPages.kt inflates a minimum height"
mutate tall-reports-page "$J/ui/ReportsPages.kt" 's + "\nprivate val tall = androidx.compose.ui.Modifier.defaultMinSize(minHeight = 200.dp)\n"' "D8 ReportsPages.kt inflates a minimum height"

echo "── D1-D8 + mutations: $FAILURES failure(s) ──"
[ "$FAILURES" -eq 0 ]
