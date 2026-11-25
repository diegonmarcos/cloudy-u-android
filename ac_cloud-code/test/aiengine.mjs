// Driver for #1855 P2 (Cloud SuperIntel holds the AI engines): the REAL src/cloud/chat/engine.js with a fake CloudAiEngine bridge
// (SuperIntel's answers) over the REAL model.js request the direct road builds, the wiring of the REAL view.js (the engine road is preferred
// when SuperIntel answers, the direct gateway road is kept as the fallback, the 'Install Cloud SuperIntel' hint shows when it is absent), the
// plugin / lib / manifest wiring of both apps, and rule-16 field parity of the Chat tab against test/fixtures/chat-fields.before.json.
import { execFileSync } from "node:child_process";
import { readFileSync, existsSync } from "node:fs";
import { join } from "node:path";
const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const load = async (p) => import(`data:text/javascript,${encodeURIComponent(read(p))}`);
const E = await load("src/cloud/chat/engine.js");
const M = await load("src/cloud/chat/model.js");
const nav = JSON.parse(read("src/cloud/nav.json"));
const fleet = JSON.parse(execFileSync("python3", [join(root, "tools/resolve-targets.py"), root, "--print"], { encoding: "utf8" })).chat;
const design = fleet.design;
const chat = { ...nav.chat, ...fleet.ai, permission: { ...nav.chat.permission, ...design.permission }, effort: design.effort };
const view = read("src/cloud/chat/view.js");
const before = JSON.parse(read("test/fixtures/chat-fields.before.json"));

class Store {
	constructor() { this.m = new Map(); }
	getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
	setItem(k, v) { this.m.set(k, String(v)); }
}
const P = (id, health = "up", extra = {}) => ({ id, label: id, kind: id, health, reachable: health === "up", model: "", detail: "", ...extra });
const snap = (providers, si = true) => ({ si, providers });
const fake = (o = {}) => ({
	providers: async () => ({ si: true, providers: [P("gateway"), P("claude-cli"), P("openrouter")] }),
	models: async (id) => ({ si: true, models: [{ id: `${id}/m`, name: "M", group: "g", contextLength: 128000, pricePrompt: "0.000003", priceCompletion: "0.000015", note: "n" }] }),
	usage: async () => ({ si: true, usage: {} }),
	...o,
});

// ── the bridge never throws, never waits, and tells absent from empty ──
out(E.bridge() === null, "no window.CloudAiEngine -> no bridge");
let r = await E.engineProviders(fake());
out(r.si === true && r.providers.length === 3, "providers: SuperIntel answers its three providers");
r = await E.engineProviders(null);
out(r.si === false && r.providers.length === 0, "providers: no bridge (plugin absent) -> si=false");
r = await E.engineProviders(fake({ providers: async () => ({ si: false, providers: [P("gateway")] }) }));
out(r.si === false && r.providers.length === 0, "providers: si=false (SuperIntel not installed) is no answer even with rows");
r = await E.engineProviders(fake({ providers: async () => { throw new Error("binder died"); } }));
out(r.si === false, "providers: a throwing bridge never throws");
r = await E.engineProviders(fake({ providers: () => new Promise(() => {}) }), 40);
out(r.si === false, "providers: a bridge that never answers is timed out");
r = await E.engineProviders(fake({ providers: async () => ({ si: true, providers: [P("gateway"), null, { label: "x" }] }) }));
out(r.si === true && r.providers.length === 1, "providers: malformed rows are dropped");
r = await E.engineProviders(fake({ providers: async () => ({ si: true, providers: [] }) }));
out(r.si === true && r.providers.length === 0, "providers: SuperIntel answering none is an answer (si=true, empty)");
r = await E.engineModels(fake(), "openrouter");
out(r.si === true && r.models.length === 1 && r.models[0].id === "openrouter/m", "models: asked per provider id");
r = await E.engineModels(null, "openrouter");
out(r.si === false && r.models.length === 0, "models: no bridge -> si=false, empty");
r = await E.engineUsage(fake({ usage: async () => ({ si: true, usage: { all: { requests: 2 } } }) }));
out(r.si === true && r.usage.all.requests === 2, "usage: the cost summary comes through");
r = await E.engineUsage(null);
out(r.si === false && Object.keys(r.usage).length === 0, "usage: no bridge -> si=false, {}");

// ── the provider an agent runs on, and whether the engine road is taken ──
const agents = M.resolveAgents(chat, fleet, null);
const byId = (id) => agents.find((a) => a.id === id);
out(E.providerFor(byId("hermes")) === "gateway" && E.providerFor(byId("goose")) === "gateway" && E.providerFor(byId("openclaw")) === "gateway", "gateway agents run on the gateway provider");
out(E.providerFor(byId("claude")) === "claude-cli", "the claude agent (mode claude-cli) runs on the claude-cli provider");
out(E.providerFor(agents.find((a) => a.via === "direct")) === "openrouter", "the direct backend runs on the openrouter provider");
out(E.providerFor(agents.find((a) => a.via === "none")) === null && E.providerFor(null) === null, "an agent with no app API has no engine provider");
const all = snap([P("gateway"), P("claude-cli"), P("openrouter")]);
out(E.engineUsable(all, byId("hermes")) && E.engineUsable(all, byId("claude")) && E.engineUsable(all, agents.find((a) => a.via === "direct")), "SuperIntel answers and runs each provider: the engine road is taken");
out(!E.engineUsable(undefined, byId("hermes")) && !E.engineUsable(snap([], false), byId("hermes")) && !E.engineUsable(null, byId("hermes")), "not asked / absent SuperIntel: the direct road stays");
out(!E.engineUsable(snap([P("gateway", "down")]), byId("hermes")), "a gateway SuperIntel cannot reach (down): the direct road stays");
out(!E.engineUsable(snap([P("openrouter", "nokey")]), agents.find((a) => a.via === "direct")), "no OpenRouter key in Cloud Account (nokey): the direct road stays");
out(E.engineUsable(snap([P("gateway", "unknown")]), byId("hermes")), "an unknown health is still tried through SuperIntel");
out(!E.engineUsable(snap([P("openrouter")]), byId("hermes")), "SuperIntel without that agent's provider: the direct road stays");
out(!E.engineUsable(all, agents.find((a) => a.via === "none")), "an agent with no app API never takes the engine road");

// ── the hint ──
out(/Install Cloud SuperIntel/.test(E.INSTALL_HINT) && E.INSTALL_SHORT === "Install Cloud SuperIntel", "the install hint names Cloud SuperIntel");
out(E.engineNote(undefined) === "", "not asked yet: no note (nothing is claimed)");
out(E.engineNote(snap([], false)) === E.INSTALL_HINT && E.engineNote(null) === E.INSTALL_HINT, "absent SuperIntel: the note is the install hint");
out(/Fleet gateway \(up\)/.test(E.engineNote(snap([P("gateway", "up", { label: "Fleet gateway" })]))), "a present SuperIntel: the note lists its providers with their health");
out(/no provider/.test(E.engineNote(snap([]))), "SuperIntel answering no provider is said");
out(/Install Cloud SuperIntel/.test(E.roadLabel(snap([], false), byId("hermes"))) && /direct gateway/.test(E.roadLabel(snap([], false), byId("hermes"))), "the empty chat says: direct gateway, Install Cloud SuperIntel");
out(E.roadLabel(all, byId("hermes")) === "via Cloud SuperIntel" && /cannot run/.test(E.roadLabel(snap([P("gateway", "down")]), byId("hermes"))) && E.roadLabel(undefined, byId("hermes")) === "", "the empty chat names the road in force");

// ── the engine request is the direct road's request, minus the transport ──
const session = { messages: [{ role: "user", content: "hello", attachments: [{ name: "a.txt", mime: "text/plain", data: Buffer.from("file body").toString("base64") }] }] };
const reasoning = new Set(["anthropic/claude-opus-5.5"]);
const health = { modes: [{ id: "goose", model: "g/m" }, { id: "claude-cli" }, { id: "hermes" }], plugins: { headroom: true } };
const goose = M.resolveAgents(chat, fleet, health).find((a) => a.id === "goose");
const rq = M.buildRequest({ chat, fleet, storage: new Store(), agent: goose, model: "anthropic/claude-opus-5.5", effort: "high", mode: "plan", session, reasoning, health });
let er = JSON.parse(E.engineRequest("r1", goose, rq));
const rqBody = JSON.parse(rq.body);
out(er.id === "r1" && er.provider === "gateway" && er.mode === "goose", "a gateway agent's request: provider gateway, its mode as `mode`");
out(JSON.stringify(er.messages) === JSON.stringify(rqBody.messages), "the messages (attachments, the permission-mode instruction) are the direct road's, unchanged");
out(!("x-agent-mode" in er.headers) && Object.keys(er.headers).every((k) => k !== "x-agent-mode"), "the mode header is not forwarded (the engine sets it from `mode`)");
const rqToggles = M.buildRequest({ chat, fleet, storage: (() => { const s = new Store(); M.setToggle(s, "goose", "headroom", true); return s; })(), agent: goose, model: "", effort: "", mode: "ask", session, reasoning, health });
const erT = JSON.parse(E.engineRequest("r2", goose, rqToggles));
const toggleHeaders = Object.keys(rqToggles.headers).filter((k) => k !== "x-agent-mode");
out(toggleHeaders.every((k) => erT.headers[k] === rqToggles.headers[k]), `the More toggles' headers ride along (${toggleHeaders.join(", ") || "none declared on"})`);
const claude = M.resolveAgents(chat, fleet, health).find((a) => a.id === "claude");
const rc = M.buildRequest({ chat, fleet, storage: new Store(), agent: claude, model: "anthropic/claude-opus-5.5", effort: "high", mode: "plan", session, reasoning, health });
er = JSON.parse(E.engineRequest("r3", claude, rc));
out(er.provider === "claude-cli" && er.mode === "claude-cli" && er.model === JSON.parse(rc.body).model && er.model.length > 0, "the claude agent's request carries the CLI model id the direct road computed");
const direct = agents.find((a) => a.via === "direct");
const rd = M.buildRequest({ chat, fleet, storage: new Store(), agent: direct, model: "anthropic/claude-opus-5.5", effort: "max", mode: "plan", session, reasoning, health });
er = JSON.parse(E.engineRequest("r4", direct, rd));
out(er.provider === "openrouter" && er.mode === "" && er.model === "anthropic/claude-opus-5.5" && er.effort === "max", "the direct backend's request: provider openrouter, the picked model, the effort");
out(!/authorization|bearer|api[_-]?key/i.test(JSON.stringify(er)) && !/authorization|bearer|api[_-]?key/i.test(JSON.stringify(JSON.parse(E.engineRequest("r7", goose, rq)))), "no key or credential is part of an engine request");
const rEff = JSON.parse(E.engineRequest("r5", goose, rq));
out(rEff.effort === (JSON.parse(rq.body).reasoning?.effort || ""), "the effort level is the direct road's field");
out(JSON.parse(E.engineRequest("r6", goose, { headers: {}, body: JSON.stringify({ messages: [] }) })).model === "", "a fixed-model agent sends no model");

// ── the model page's section and the usage page ──
out(E.perMillion("0.000003") === "$3" && E.perMillion("0") === "free" && E.perMillion("") === "-" && E.perMillion("-1") === "var" && E.perMillion("0.0000005") === "$0.5", "prices read as $ per 1M tokens, like the Model page");
out(E.tokens(128000) === "128K" && E.tokens(1000000) === "1M" && E.tokens(0) === "-" && E.tokens("x") === "-", "context length reads as the page's 128K / 1M / -");
const sec = E.modelSection(all, byId("hermes"), [{ id: "hermes", name: "Hermes", group: "mode", contextLength: 0, pricePrompt: "", priceCompletion: "", note: "nous" }, { id: "g/m", name: "GM", group: "model", contextLength: 8000, pricePrompt: "0.000001", priceCompletion: "0", note: "free" }]);
out(sec && sec.id === "engine-gateway" && sec.rows.length === 2 && sec.rows[1].input === "$1" && sec.rows[1].output === "free" && sec.rows[1].context === "8K", "the model section lists SuperIntel's rows for the agent's provider");
out(E.modelSection(snap([], false), byId("hermes"), [{ id: "a" }]) === null && E.modelSection(all, byId("hermes"), []) === null && E.modelSection(all, null, [{ id: "a" }]) === null, "no section when SuperIntel is absent, lists no model, or there is no agent");
const ul = E.usageLines({ today: { requests: 1, prompt: 10, completion: 5, cost: 0.25 }, month: { requests: 3, prompt: 30, completion: 15 }, all: { requests: 3, prompt: 30, completion: 15 } });
out(ul.length === 3 && /1 requests · 10 in · 5 out · \$0\.2500/.test(ul[0].value) && !/\$/.test(ul[1].value), "usage lines show only what was metered (no cost invented)");
out(E.usageLines({}).length === 0 && E.usageLines(null).length === 0, "no metered usage -> no lines");

// ── view.js: the engine road is preferred, the direct road is kept ──
const has = (s, t) => s.includes(t);
out(has(view, 'import * as E from "./engine";'), "view.js imports the engine module");
out(has(view, "E.engineUsable(S.engine, a) && E.bridge()") && has(view, "E.bridge().chat(ereq"), "a send takes the engine road when SuperIntel answers");
out(has(view, "plugin().stream({ ...req, id }") && has(view, "const direct = () =>") && /return direct\(\);/.test(view), "the direct gateway road is kept and is the default");
out(has(view, 'ev.type === "absent"') && has(view, "direct();"), "SuperIntel vanishing mid-send falls back to the direct road");
out(has(view, "E.engineProviders(E.bridge())") && has(view, "refreshEngine().catch"), "the provider list is read from SuperIntel when the tab opens");
out(has(view, "E.engineModels(E.bridge(), E.providerFor(a))") && has(view, "E.modelSection(S.engine, a, engineModels)"), "the Model page lists SuperIntel's models for the agent's provider");
out(has(view, "E.INSTALL_SHORT") && has(view, "E.INSTALL_HINT") && has(view, "E.engineNote(S.engine)") && has(view, "E.roadLabel(S.engine, a)"), "the 'Install Cloud SuperIntel' hint shows in the Agent sheet and on the empty chat");
out(has(view, "S.engineStreaming === id") && has(view, "E.bridge()?.cancel(id)"), "stop cancels an engine stream through SuperIntel");
out(has(view, "E.engineUsage(E.bridge())"), "More > Usage adds what SuperIntel metered");
out(!/CloudAiEngine/.test(view.replace(/E\.bridge/g, "")) && !/openrouter[_-]?(key|token)/i.test(view.replace(/chat\.direct/g, "")), "view.js never touches a key on the engine road");

// ── rule 16: nothing the Chat tab had is gone ──
const fn = (name) => new RegExp(`^(?:export )?(?:async )?function ${name}\\b`, "m").test(view);
out(before.view_functions.every(fn), `every function of the previous view.js is still there (${before.view_functions.length})`);
for (const f of before.view_functions) if (!fn(f)) out(false, `view.js lost function ${f}`);
out(before.view_exports.every((f) => new RegExp(`^export (?:async )?function ${f}\\b`, "m").test(view)), `every export of view.js is still exported (${before.view_exports.join(", ")})`);
const modelSrc = read("src/cloud/chat/model.js");
out(before.model_exports.every((f) => new RegExp(`^export (?:async )?function ${f}\\b`, "m").test(modelSrc)), `every export of model.js is still exported (${before.model_exports.length})`);
out(before.plugin_calls.every((c) => view.includes(`plugin().${c}(`)), `every CloudChat plugin call is still made (${before.plugin_calls.join(", ")})`);
out(before.sheet_and_page_titles.every((t) => view.includes(t.slice(1, -1))), `every sheet and page title is still opened (${before.sheet_and_page_titles.length})`);
out(before.option_labels.every((t) => view.includes(t)), "the (+) menu's Files, Photos & videos and Voice message are still offered");
out(before.controls.every((c) => view.includes(c)), "the composer's control roles are still declared");
for (const kind of ["attachments", "effortSheet", "modeSheet", "mcpSheet", "moreSheet", "taskPage", "plusSheet", "agentSheet", "exportChat", "retry", "modelPage", "chatDrawer", "gatewaySessions"]) {
	out(new RegExp(`function ${kind}\\b|${kind}\\b`).test(view), `Chat feature still wired: ${kind}`);
}
out(has(view, "plugin().get(url, {}, auth)") && has(view, "getJson(g + chat.gateway.health_path)") && has(view, "getJson(g + chat.gateway.mcp_path)"), "the login flow's and gateway's live reads (health, MCP status) are unchanged");

// ── the plumbing: plugin, lib, manifests, registrations ──
const exists = (p) => existsSync(join(root, p));
const pkg = JSON.parse(read("package.json"));
out(pkg.cordova.plugins["cordova-plugin-cloudaiengine"] !== undefined && pkg.devDependencies["cordova-plugin-cloudaiengine"] === "file:src/plugins/cloudaiengine", "the plugin is registered in package.json");
const lock = JSON.parse(read("package-lock.json"));
out(lock.packages["node_modules/cordova-plugin-cloudaiengine"]?.resolved === "src/plugins/cloudaiengine" && !!lock.packages["src/plugins/cloudaiengine"], "the plugin is in package-lock.json");
const pxml = read("src/plugins/cloudaiengine/plugin.xml");
out(has(pxml, 'target="CloudAiEngine"') && has(pxml, '<feature name="CloudAiEngine">') && exists("src/plugins/cloudaiengine/www/cloudaiengine.js"), "plugin.xml clobbers window.CloudAiEngine and registers its service");
const java = read("src/plugins/cloudaiengine/src/CloudAiEnginePlugin.java");
out(has(java, "AiEngineClient") && has(java, "setKeepCallback") && ["providers", "models", "chat", "cancel", "usage"].every((a) => has(java, `"${a}".equals(action)`)), "the plugin bridges providers, models, chat (streamed), cancel and usage");
out(!/revealAiKey|Authorization|SharedPreferences|openrouter\.ai|http:\/\/|https:\/\//i.test(java), "the plugin holds no key, no address and no network call");
const bj = JSON.parse(read("build.json"));
out(bj.modules["libs:ai-engine"]?.dir === "../ab_cloud-libs-shared/libs/ai-engine" && has(read("build-extras.gradle"), "implementation project(':libs:ai-engine')"), "Cloud Code links libs:ai-engine");
const lib = "../ab_cloud-libs-shared/libs/ai-engine";
const aidl = read(`${lib}/src/main/aidl/com/diegonmarcos/superapp/aiengine/IAiEngine.aidl`);
const order = [...aidl.matchAll(/^\s*(?:int|String\[\]|String|void)\s+(\w+)\(/gm)].map((m) => m[1]);
out(JSON.stringify(order) === JSON.stringify(["version", "providers", "models", "chat", "cancel", "usage"]), `the AIDL method order (the wire format) is version, providers, models, chat, cancel, usage (${order.join(", ")})`);
const cb = read(`${lib}/src/main/aidl/com/diegonmarcos/superapp/aiengine/IAiEngineCallback.aidl`);
out(/oneway interface IAiEngineCallback/.test(cb) && JSON.stringify([...cb.matchAll(/^\s*void\s+(\w+)\(/gm)].map((m) => m[1])) === JSON.stringify(["onChunk", "onDone", "onError"]), "the callback is oneway: onChunk, onDone, onError");
const lmf = read(`${lib}/src/main/AndroidManifest.xml`);
out(/protectionLevel="signature"/.test(lmf) && has(lmf, "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA") && has(lmf, "superapp.aiengine.IAiEngine"), "the lib declares the CONSTELLATION_DATA signature permission and the package-visibility query");
const siMf = read("../ac_cloud-superintel/app/src/main/AndroidManifest.xml");
out(/<service\s+android:name="\.engine\.EngineService"\s+android:permission="com\.diegonmarcos\.cloud\.permission\.CONSTELLATION_DATA"\s+android:exported="true">/.test(siMf) && has(siMf, "com.diegonmarcos.superapp.aiengine.IAiEngine"), "Cloud SuperIntel serves the binder behind the signature permission");
const siBj = JSON.parse(read("../ac_cloud-superintel/build.json"));
out(!!siBj.modules["libs:ai-engine"] && siBj.modules.app.depends_on.includes("libs:ai-engine") && has(read("../ac_cloud-superintel/app/build.gradle"), "implementation project(':libs:ai-engine')"), "Cloud SuperIntel links libs:ai-engine");
const lc = JSON.parse(read("../1_cicd/src/data/lib-classes.json"));
const libsMap = lc.libs || lc;
out(libsMap["ai-engine"]?.class === "contract", "libs:ai-engine is classed a contract lib");
const fc = JSON.parse(read("../fleet-private-data/fleet-config.json"));
out(fc.apps.code.libs.includes("lib-ai-engine") && fc.apps.superintel.libs.includes("lib-ai-engine"), "fleet-config.json lists lib-ai-engine for Code and SuperIntel");
const svc = read("../ac_cloud-superintel/app/src/main/java/com/diegonmarcos/cloudsuperintel/engine/EngineService.kt");
out(has(svc, "AiGatewayBackend") && has(svc, "AiOpenRouterBackend") && has(svc, "OpenRouterKey.source(app)") && !/return\s+key\(\)|put\([^)]*key\(\)/.test(svc), "SuperIntel performs requests with libs:ai's backends and the key never leaves it");
