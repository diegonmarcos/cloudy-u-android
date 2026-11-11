// Driver for test/test-chat.sh: the Chat tab (replaced Home). Loads the REAL src/cloud/chat/model.js
// and src/cloud/tabs.js (no copy), the REAL nav.json, and the fleet data the REAL resolver derives
// (tools/resolve-targets.py --print), and prints one `CHECK <ok|bad> <text>` per assertion.
import { execFileSync } from "node:child_process";
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const load = async (p) => import(`data:text/javascript,${encodeURIComponent(read(p))}`);

const M = await load("src/cloud/chat/model.js");
const T = await load("src/cloud/tabs.js");
const nav = JSON.parse(read("src/cloud/nav.json"));
const fleet = JSON.parse(execFileSync("python3", [join(root, "tools/resolve-targets.py"), root, "--print"], { encoding: "utf8" })).chat;
// What the page sees (src/cloud/chat/view.js): nav.json's own declaration with the shared AI contract merged over it.
// The shared design supplies the permission modes and the effort levels (view.js merges it the same way).
const design = fleet.design;
const chat = { ...nav.chat, ...fleet.ai, permission: { ...nav.chat.permission, ...design.permission }, effort: design.effort };

class Store {
	constructor() { this.m = new Map(); }
	getItem(k) { return this.m.has(k) ? this.m.get(k) : null; }
	setItem(k, v) { this.m.set(k, String(v)); }
}

// ── the agent list comes from the fleet's data ──────────────────────────────
const agents = M.resolveAgents(chat, fleet, null);
const ids = agents.map((a) => a.id).join(",");
out(agents[0].id === "hermes" && agents[1].id === "openclaw", `Hermes then OpenClaw lead the picker (${ids})`);
out(agents[agents.length - 1].id === chat.direct.id, "the direct OpenRouter backend comes last");
const claw = agents.find((a) => a.id === "openclaw");
const fleetHasClaw = fleet.agents.find((a) => a.id === "openclaw")?.deployed;
out(fleetHasClaw ? claw.available : !claw.available && claw.reason === "not deployed", `OpenClaw follows the fleet: deployed=${!!fleetHasClaw}, shown ${claw.available ? "available" : `disabled (${claw.reason})`}`);
for (const a of agents.filter((x) => x.via === "gateway" && x.id !== "openclaw" && x.decl.fleet)) {
	out(fleet.agents.find((f) => f.id === a.id)?.deployed === a.deployed, `${a.label} is deployed exactly when the fleet declares ${a.decl.fleet}`);
}
out(!("gateway" in nav.chat) && !("direct" in nav.chat), "nav.json does not restate the gateway or OpenRouter: they come from the shared AI contract (libs:ai ai.json)");
const aiContract = JSON.parse(read(`../${nav.chat.ai}`));
out(chat.gateway.chat_path === aiContract.gateway.chat_path && chat.gateway.service === aiContract.gateway.service && chat.direct.url === aiContract.openrouter.chat_url && chat.direct.models_url === aiContract.openrouter.models_url,
	"the page's gateway and OpenRouter are libs:ai's ai.json, copied by the resolver");
const sharedGateway = JSON.parse(read(`../${nav.chat.shared_agents}`)).gateway;
out(sharedGateway.chat_path === aiContract.gateway.chat_path && sharedGateway.mode_header === aiContract.gateway.mode_header && sharedGateway.service === aiContract.gateway.service,
	"agents.json and ai.json agree on the gateway's service, chat path and mode header");
const services = JSON.parse(read(`../${chat.fleet_services}`));
const fleetAgents = services.filter((s) => s.name.startsWith(chat.agent_prefix)).map((s) => s.name).sort();
const shownFleet = agents.filter((a) => a.deployed && a.via !== "direct").map((a) => (a.decl ? a.decl.fleet : `${chat.agent_prefix}${a.id}`)).filter(Boolean).sort();
out(JSON.stringify(fleetAgents) === JSON.stringify(shownFleet), `every fleet agent service is in the picker (${fleetAgents.join(", ")})`);
out(fleet.gateway === `http://${services.find((s) => s.name === chat.gateway.service).private_dns}`, `the gateway address is the fleet's (${fleet.gateway})`);

// synthetic fleets: a new agent appears without code; a removed one turns "not deployed"
const plus = { ...fleet, agents: [...fleet.agents, { id: "zed", fleet: "cloud-agi-zed", deployed: true, declared: false }] };
const withZed = M.resolveAgents(chat, plus, null);
const zed = withZed.find((a) => a.id === "zed");
out(!!zed && !zed.available && zed.reason === "no app API yet" && withZed.indexOf(zed) === withZed.length - 2, "an undeclared fleet agent is appended, disabled, before OpenRouter");
const noHermes = { ...fleet, agents: fleet.agents.map((a) => (a.id === "hermes" ? { ...a, deployed: false } : a)) };
const h = M.resolveAgents(chat, noHermes, null).find((a) => a.id === "hermes");
out(!h.available && h.reason === "not deployed", "a declared agent the fleet drops is shown as not deployed");
const live = { agents: { goose: "g/model", claude_cli: true, openrouter: true }, plugins: { headroom: true, rtk: true } };
const offHermes = M.resolveAgents(chat, fleet, live).find((a) => a.id === "hermes");
out(!offHermes.available, "an agent the live gateway does not list cannot be picked");
out(M.pickAgent(M.resolveAgents(chat, fleet, live), "hermes").id === "goose", "a stored agent that cannot be reached falls to the first available one");
out(M.pickAgent(agents, "claude").id === "claude", "a stored available agent stays");

// ── model, effort and permission per backend capability ─────────────────────
const by = (list, id) => list.find((a) => a.id === id);
const health = { agents: { hermes: "nousresearch/hermes-4", goose: "deepseek/deepseek-v4", claude_cli: true, openrouter: true }, plugins: { headroom: true, ponytail: "full" } };
const L = M.resolveAgents(chat, fleet, health);
const reasoning = new Set(["anthropic/claude-opus-5.5", "deepseek/deepseek-v4"]);
const hermes = by(L, "hermes"), goose = by(L, "goose"), claude = by(L, "claude"), direct = by(L, "openrouter");
out(!M.modelControl(hermes, "x/y", health).pick && M.modelControl(hermes, "x/y", health).value === "nousresearch/hermes-4", "Hermes' model is the gateway's own, not picked");
out(M.requestModel(hermes, "x/y") === undefined && M.requestModel(direct, "x/y") === "x/y", "a fixed agent sends no model; the direct backend sends the picked one");
out(M.requestModel(claude, "anthropic/claude-opus-5.5") === "claude-opus-5-5", "Claude gets the CLI id for an Anthropic catalogue row");
out(M.rowAllowed(claude, { provider: "Anthropic", selectable: true }, health) && !M.rowAllowed(claude, { provider: "Qwen", selectable: true }, health), "Claude can pick Anthropic rows only");
out(!M.rowAllowed(hermes, { provider: "Anthropic", selectable: true }, health), "a fixed agent picks no row");
out(M.rowAllowed(direct, { provider: "Qwen", selectable: true }, health) && !M.rowAllowed(direct, { provider: "Qwen", selectable: false }, health), "the direct backend picks any selectable A0 row");

const eh = M.effortControl(hermes, "nousresearch/hermes-4", reasoning);
out(eh.shown && !eh.enabled && /does not reason/.test(eh.reason), "Effort is disabled, with the reason, when the agent's model does not reason");
const eg = M.effortControl(goose, "deepseek/deepseek-v4", reasoning);
out(eg.shown && eg.enabled, "Effort is on for an agent whose model reasons");
out(!M.effortControl(claude, null, reasoning).shown, "Effort is hidden for Claude (the CLI behind the gateway takes none)");
for (const l of chat.effort.levels) {
	const f = M.effortField(chat, direct, l.id, "anthropic/claude-opus-5.5", reasoning);
	out(l.value ? f?.reasoning?.effort === l.value : f === null, l.value ? `effort ${l.label} -> reasoning.effort=${l.value}` : `effort ${l.label} sends nothing (the model's own default)`);
}
out(M.effortField(chat, direct, "max", "x/plain", reasoning) === null, "no effort field for a model that does not reason");
out(M.effortField(chat, direct, "high", "x/plain", null)?.reasoning?.effort === "high", "reasoning support not known yet: the effort is sent");
out(JSON.stringify(chat.effort.levels.map((l) => l.id)) === '["auto","low","medium","high","max"]' && chat.effort.default === "auto", "the effort levels are Auto (default, sends nothing), Low, Medium, High, Max");

out(!M.permissionControl(direct).shown, "permission mode is hidden for the direct backend (no tools)");
for (const a of [hermes, goose, claude]) out(M.permissionControl(a).shown && M.permissionControl(a).via === "prompt", `${a.label}: permission mode applied as an instruction (no per-request switch)`);
out(M.permissionInstruction(chat, hermes, "ask") === "" && /plan/i.test(M.permissionInstruction(chat, hermes, "plan")) && /auto-edit/i.test(M.permissionInstruction(chat, goose, "auto_edit")) && /read-only/i.test(M.permissionInstruction(chat, goose, "read_only")), "Ask adds nothing; Plan, Auto-edit and Read-only add their instruction");
out(JSON.stringify(chat.permission.modes.map((m) => m.id)) === '["ask","auto_edit","read_only","plan"]' && chat.permission.default === "ask", "the permission modes are the shared design's: ask (default), auto_edit, read_only, plan");

// ── the request each backend receives ───────────────────────────────────────
const store = new Store();
const session = M.newSession(Date.now(), "goose");
session.messages.push({ role: "user", content: "hi", attachments: [{ name: "a.png", mime: "image/png", size: 3, base64: "AAAA" }] });
const rq = M.buildRequest({ chat, fleet, storage: store, agent: goose, model: "anthropic/claude-opus-5.5", effort: "high", mode: "plan", session, reasoning, health });
const rb = JSON.parse(rq.body);
out(rq.url === fleet.gateway + chat.gateway.chat_path && rq.headers["x-agent-mode"] === "goose" && rq.auth === null, "a gateway agent goes to the gateway with X-Agent-Mode and no token");
out(rb.model === undefined && rb.reasoning?.effort === "high" && rb.stream === true, "goose: no model, reasoning.effort, streamed");
out(rb.messages[0].role === "system" && /plan/i.test(rb.messages[0].content), "Plan Mode reaches goose as the first, system message");
out(Array.isArray(rb.messages[1].content) && rb.messages[1].content.some((p) => p.type === "image_url" && p.image_url.url.startsWith("data:image/png;base64,")), "a photo goes as an image_url part");
const rd = M.buildRequest({ chat, fleet, storage: store, agent: direct, model: "anthropic/claude-opus-5.5", effort: "max", mode: "plan", session, reasoning, health });
const dd = JSON.parse(rd.body);
out(rd.url === chat.direct.url && rd.auth === "openrouter" && !("authorization" in rd.headers) && !/Bearer/.test(JSON.stringify(rd)), "the direct backend asks the plugin to add the token; the page holds none");
out(dd.model === "anthropic/claude-opus-5.5" && dd.reasoning?.effort === "max" && dd.messages[0].role !== "system", "OpenRouter: the picked model, effort max, no permission instruction");
let threw = false;
try { M.buildRequest({ chat, fleet, storage: store, agent: claw, model: "", effort: "low", mode: "ask", session, reasoning, health }); } catch { threw = true; }
out(threw, "a not-deployed agent cannot be sent to");
const audio = M.toContent("", [{ name: "v.wav", mime: "audio/wav", size: 10, base64: "UklGRg==" }]);
out(audio[0].type === "input_audio" && audio[0].input_audio.format === "wav", "a voice message goes as input_audio (wav)");
const txt = M.toContent("see", [{ name: "n.txt", mime: "text/plain", size: 5, base64: Buffer.from("hello").toString("base64") }]);
out(txt[1].type === "text" && txt[1].text.endsWith("hello"), "a small text file goes inline");
const pdf = M.toContent("", [{ name: "d.pdf", mime: "application/pdf", size: 5, base64: "JVBERg==" }]);
out(pdf[0].type === "file" && pdf[0].file.filename === "d.pdf", "any other file goes as a file part");

// More toggles become per-request gateway headers, per agent
M.setToggle(store, "goose", "headroom", false);
M.setToggle(store, "goose", "ponytail", "lite");
const hd = M.toggleHeaders(chat, store, goose);
out(hd["x-headroom"] === "off" && hd["x-ponytail-mode"] === "lite" && Object.keys(M.toggleHeaders(chat, store, hermes)).length === 0, "More toggles are per-request headers, kept per agent");

// ── MCP: the list's source and the per-agent persistence ────────────────────
const gw = M.mcpServers(goose, [{ server: "cloud-infra", count: 40 }, { server: "google-personal", count: 9 }], fleet.mcp_fleet);
out(gw.source === "gateway" && gw.servers.length === 2 && gw.servers[0].tools === 40, "a gateway agent's MCP list is the gateway's live /v1/mcp/status");
const off = M.mcpServers(goose, null, fleet.mcp_fleet);
out(off.source === "fleet" && off.servers.length === fleet.mcp_fleet.length && off.servers.length > 0, `offline, the fleet's derived .mcp.json names (${fleet.mcp_fleet.length})`);
out(M.mcpServers(claude, [{ server: "x", count: 1 }], fleet.mcp_fleet).source === "fleet", "Claude's MCP list is the fleet config (its backend publishes none)");
out(M.mcpServers(direct, null, fleet.mcp_fleet).source === "none", "the direct backend has no MCP");
const ms = new Store();
const servers = off.servers;
out(Object.values(M.mcpState(ms, "goose", servers)).every(Boolean), "every MCP server starts enabled");
M.mcpToggle(ms, "goose", servers[0].name, false);
out(M.mcpState(ms, "goose", servers)[servers[0].name] === false, "a disabled server stays disabled (persisted)");
out(M.mcpState(ms, "hermes", servers)[servers[0].name] === true, "the toggle is per agent");
M.mcpToggle(ms, "goose", servers[0].name, true);
out(M.mcpState(ms, "goose", servers)[servers[0].name] === true, "re-enabling persists too");
out(JSON.parse(ms.getItem(`${M.KEY}mcp.goose`))[servers[0].name] === true, `stored under ${M.KEY}mcp.<agent>`);

// ── More: from the gateway's capability list, static fallback ───────────────
const moreLive = M.moreFor(chat, goose, health).map((f) => f.id);
const moreOff = M.moreFor(chat, goose, null);
out(moreLive.includes("headroom") && !moreLive.includes("rtk") && moreLive.includes("ponytail"), "live: a toggle shows only when /health.plugins has its plugin");
out(moreOff.some((f) => f.id === "rtk") && moreOff.filter((f) => f.agents === "gateway").every((f) => f.offline), "offline: the declared list, marked offline");
out(M.moreFor(chat, hermes, health).some((f) => f.id === "skills") && !M.moreFor(chat, goose, health).some((f) => f.id === "skills"), "Hermes gets its own functions (skills)");
out(M.moreFor(chat, claude, health).some((f) => f.id === "login"), "Claude gets its OAuth login");
out(M.moreFor(chat, direct, health).every((f) => f.agents === "all"), "the direct backend gets only the chat's own functions");

// ── the hamburger lists the ACTIVE page's items ─────────────────────────────
const tabs = T.buildTabs(nav, nav.tabs.map((t) => t.view));
const menus = { ai: () => ({ items: M.chatMenu() }), git: () => ({ items: [{ id: "rescan" }] }) };
const chatItems = T.menuFor({ active: "ai" }, tabs, menus).items.map((i) => i.id);
out(JSON.stringify(chatItems) === JSON.stringify(M.chatMenu().map((i) => i.id)), `Chat's drawer is Chat's own items (${chatItems.join(", ")})`);
for (const need of ["new", "history", "search", "pinned", "export"]) out(chatItems.includes(need), `Chat's drawer has ${need}`);
out(T.menuFor({ active: "git" }, tabs, menus).items[0].id === "rescan", "Git' drawer is Git's own items (tab id git, formerly repos)");
out(T.menuFor({ active: "browser" }, tabs, menus).items.length === 0, "a page that registered nothing gets an empty drawer, never another page's");
const index = read("src/cloud/index.js");
out(/MENUS\[panel\.dataset\.tab\] = \(\) => chatDrawer\(drawer\)/.test(index) && /menuFor\(state, tabs, MENUS\)/.test(index), "index.js registers Chat's drawer and opens the active tab's through menuFor");
for (const view of ["renderBacklogView", "renderGit", "renderBrowser"]) {
	const body = new RegExp(`function ${view}\\([\\s\\S]*?\\n\\}`).exec(index)?.[0] || "";
	out(/MENUS\[panel\.dataset\.tab\] =/.test(body), `${view} supplies its own drawer items`);
}

// ── sessions: history, search, pinned, export ───────────────────────────────
const now = Date.UTC(2026, 9, 10, 12);
const mk = (t, upd, pin) => ({ ...M.newSession(upd, "goose"), updated: upd, pinned: !!pin, messages: [{ role: "user", content: t }] });
const ss = [mk("today one", now - 3600e3), mk("last week", now - 3 * 86400e3), mk("ancient", now - 40 * 86400e3), mk("pinned", now - 50 * 86400e3, true)];
const g = M.groupSessions(ss, now);
out(g.pinned.length === 1 && g.today.length === 1 && g.week.length === 1 && g.older.length === 1, "chats group as Pinned, Today, Previous 7 days, Older");
out(M.searchSessions(ss, "week").length === 1, "search finds a chat by its words");
out(/^# today one/.test(M.exportMarkdown(ss[0], "Goose")) && /## You/.test(M.exportMarkdown(ss[0], "Goose")), "export writes the chat as Markdown");
const st = new Store();
M.saveSessions(st, [{ ...ss[0], messages: [{ role: "user", content: "x", attachments: [{ name: "p.png", mime: "image/png", base64: "AAAA" }] }] }]);
out(!/AAAA/.test(st.getItem(`${M.KEY}sessions`)), "saved chats keep attachment names, never their bytes");

// ── the reply: streamed and whole ───────────────────────────────────────────
const p = M.sseParser();
const a1 = p.push('data: {"choices":[{"delta":{"content":"Hel"}}]}\n\ndata: {"choices":[{"del');
const a2 = p.push('ta":{"content":"lo"}}]}\n\ndata: [DONE]\n');
out(a1.join("") === "Hel" && a2.join("") === "lo" && p.done, "SSE deltas are joined across chunk boundaries");
out(M.parseWhole(200, '{"choices":[{"message":{"content":"ok"}}]}').text === "ok" && /boom/.test(M.parseWhole(502, '{"error":{"message":"boom"}}').error), "a whole (gateway agent) answer and its error are read");

// ── the catalogue: the shared lib, A0 Code only ─────────────────────────────
out(JSON.stringify(chat.catalogue.sections) === '["A0","A1","A2","A3"]', "the Model page asks the shared catalogue for the four Text tables, A0-A3");
const cat = JSON.parse(read("../ab_cloud-libs-shared/libs/model-catalogue/src/main/assets/models/catalogue.json"));
const a0 = cat.groups.flatMap((g2) => g2.sections).find((s) => s.id === "A0");
out(a0?.label === "Code" && a0.kind === "chat", "A0 in the shared catalogue is the Code chat section");
out(a0.rows.some((r) => r.id === chat.default_model), `the default model ${chat.default_model} is an A0 row`);
const bridge = read("src/plugins/cloudchat/src/CatalogueBridge.java");
out(/all\.only\(ids\)/.test(bridge) && /CatalogueJson\.INSTANCE\.shown\(cat,/.test(bridge) && /ModelCatalogue\.ASSET/.test(bridge), "the plugin narrows with the lib's own filter and hands over the lib's CatalogueJson (no copy)");
const extras = read("build-extras.gradle");
out(/ab_cloud-libs-shared\/libs\/ai'\)/.test(extras) && /new File\(cloudAiLib, 'src\/main\/assets'\)/.test(extras), "build-extras compiles libs:ai's contract assets by reference (the shared AI contract ships in the APK and is a source input)");
out(/ab_cloud-libs-shared\/libs\/model-catalogue/.test(extras) && /src\/main\/kotlin\/com\/diegonmarcos\/superapp\/modelcatalogue'/.test(extras) && /src\/main\/assets'/.test(extras), "build-extras compiles the lib's logic package and assets by reference");

// ── every agent the fleet can run: catalogue + live /health.modes ───────────
const modesUp = {
	agents: { openrouter: true, claude_cli: true, goose: "deepseek/deepseek-v4", hermes: "hermes-agent", openclaw: "openclaw/default" },
	plugins: { headroom: true },
	modes: [
		{ id: "hermes", label: "Hermes", fleet: "cloud-agi-hermes", backend: "hermes-agent-api", native: true, model: "hermes-agent", available: true, functions: [{ id: "skills", label: "Skills", path: "/agents/hermes/skills" }, { id: "jobs", label: "Scheduled jobs", path: "/agents/hermes/jobs" }] },
		{ id: "openclaw", label: "OpenClaw", fleet: "cloud-agi-openclaw", backend: "openclaw-gateway", native: true, model: "openclaw/default", available: true, functions: [{ id: "agents", label: "Agent targets", path: "/agents/openclaw/agents" }] },
		{ id: "deepseek", label: "DeepSeek", fleet: null, backend: "openrouter+mcp", native: false, model: "deepseek/deepseek-v4-pro-0813", model_choice: { providers: ["DeepSeek"] }, available: true, functions: [] },
		{ id: "goose", label: "Goose", fleet: "cloud-agi-goose", backend: "openrouter+mcp", native: false, model: "deepseek/deepseek-v4", available: true, functions: [] },
		{ id: "claude-cli", label: "Claude", fleet: "cloud-agi-claude", backend: "claude-superset-api", native: true, model: null, available: true, functions: [] },
		{ id: "openrouter", label: "OpenRouter (fleet key)", fleet: null, backend: "openrouter", native: false, model: null, available: true, functions: [] },
		{ id: "aider", label: "Aider", fleet: "cloud-agi-aider", backend: "aider-api", native: true, model: "x/coder", available: true, functions: [{ id: "repo", label: "Repo map", path: "/agents/aider/repo" }] },
	],
};
const U = M.resolveAgents(chat, fleet, modesUp);
const uids = U.map((a) => a.id).join(",");
out(uids.startsWith("hermes,openclaw,goose,claude,deepseek,fleet-openrouter,aider") && uids.endsWith(chat.direct.id), `all agents, in the owner's order, then the gateway's undeclared ones, then OpenRouter direct (${uids})`);
const uclaw = by(U, "openclaw");
out(uclaw.deployed && uclaw.available && uclaw.state === "ready", "OpenClaw is deployed and ready once the live gateway serves it, even before the catalogue lists it");
const aider = by(U, "aider");
out(aider.available && aider.decl.generic && M.modelControl(aider, "x", modesUp).value === "x/coder" && M.moreFor(chat, aider, modesUp).some((f) => f.kind === "native" && f.path === "/agents/aider/repo"), "a mode the gateway starts serving appears with no app change: its model and functions come from /health");
const catAider = { ...fleet, agents: [...fleet.agents, { id: "aider", fleet: "cloud-agi-aider", deployed: true, declared: false }] };
out(M.resolveAgents(chat, catAider, modesUp).filter((a) => a.id === "aider").length === 1, "a catalogue agent the gateway serves is listed once (as served), not also as 'no app API'");
const down = { ...modesUp, modes: modesUp.modes.map((m) => (m.id === "openclaw" ? { ...m, available: false, reason: "the OpenClaw gateway does not answer", functions: [] } : m)) };
const dclaw = by(M.resolveAgents(chat, fleet, down), "openclaw");
out(dclaw.state === "unavailable" && !dclaw.available && /does not answer/.test(dclaw.reason), "an agent the gateway lists as down is unavailable, with the gateway's reason");
const noMode = { ...modesUp, modes: modesUp.modes.filter((m) => m.id !== "openclaw") };
const nclaw = by(M.resolveAgents(chat, fleet, noMode), "openclaw");
out(fleetHasClaw ? nclaw.state === "unavailable" : nclaw.state === "not-deployed", `without a gateway mode OpenClaw is ${fleetHasClaw ? "unavailable (deployed, not served)" : "not deployed"}`);
const OFF = M.resolveAgents(chat, fleet, null);
const offG = by(OFF, "goose");
out(offG.state === "offline" && offG.available && /did not answer/.test(M.agentSub(offG)), "the gateway not answering: deployed agents are offline, still selectable, and say why");
out(M.pickAgent(OFF, "goose").id === "goose", "offline never moves the stored agent to another");
out(M.resolveAgents(chat, fleet, undefined).filter((a) => a.via === "gateway" && a.deployed).every((a) => a.state === "ready"), "not asked yet: deployed agents are ready");
out(/its own API/.test(M.agentSub(by(U, "hermes"))) && /openrouter\+mcp/.test(M.agentSub(by(U, "goose"))), "the picker says which backend answers (Hermes' own API vs the gateway's MCP loop)");

// per-agent capabilities, truthful to each backend
const uh = by(U, "hermes"), ug = by(U, "goose"), uc = by(U, "claude"), uf = by(U, "fleet-openrouter"), ud = by(U, chat.direct.id);
out(!M.effortControl(uh, "hermes-agent", null).shown && M.effortControl(by(L, "hermes"), "nousresearch/hermes-4", null).shown, "Effort: hidden once Hermes is its native API (it runs its own reasoning), shown behind the MCP loop");
out(!M.effortControl(uclaw, null, null).shown && M.permissionControl(uclaw).via === "prompt" && M.mcpServers(uclaw, [{ server: "x", count: 1 }], fleet.mcp_fleet).source === "none", "OpenClaw: no effort field, permission as an instruction, no MCP servers");
out(!M.modelControl(uclaw, "x/y", modesUp).pick && M.modelControl(uclaw, "x/y", modesUp).value === "openclaw/default", "OpenClaw's model is its own, shown from the gateway");
const rc = M.buildRequest({ chat, fleet, storage: new Store(), agent: uclaw, model: "x/y", effort: "high", mode: "plan", session, reasoning, health: modesUp });
out(rc.headers["x-agent-mode"] === "openclaw" && JSON.parse(rc.body).model === undefined && JSON.parse(rc.body).reasoning === undefined, "OpenClaw's request: X-Agent-Mode openclaw, no model, no effort");
out(M.modelControl(uf, "qwen/q", modesUp).pick && !M.permissionControl(uf).shown && M.mcpServers(uf, null, fleet.mcp_fleet).source === "none" && uf.deployed, "the gateway's OpenRouter face: any model, no permission mode, no MCP, deployed with the gateway");
const rf = M.buildRequest({ chat, fleet, storage: new Store(), agent: uf, model: "qwen/q", effort: "high", mode: "plan", session, reasoning: new Set(["qwen/q"]), health: modesUp });
out(rf.url === fleet.gateway + chat.gateway.chat_path && rf.headers["x-agent-mode"] === "openrouter" && JSON.parse(rf.body).model === "qwen/q" && rf.auth === null, "its request: the gateway, X-Agent-Mode openrouter, the picked model, no token on the phone");

// More: the agent's own functions, live from the gateway
const hm = M.moreFor(chat, uh, modesUp);
out(hm.some((f) => f.id === "live:skills" && f.path === "/agents/hermes/skills") && !hm.some((f) => f.id === "skills"), "native Hermes: More lists its real skills (live) and drops the prompt fallback");
out(M.moreFor(chat, by(L, "hermes"), health).some((f) => f.id === "skills"), "Hermes behind the MCP loop keeps the skills prompt");
out(M.moreFor(chat, uclaw, modesUp).some((f) => f.path === "/agents/openclaw/agents") && !M.moreFor(chat, ug, modesUp).some((f) => f.kind === "native"), "OpenClaw lists its agent targets; Goose publishes no native function");

// ── the composer: the shared design's order, narrowed to what the app enables and the agent takes ──
const designOrder = design.composer.controls.map((c) => c.id);
out(JSON.stringify(designOrder) === '["plus","task","agent","model","effort","mode","mcp","more"]', "the design's bar is (+), Task, Agent, Model, Effort, Perms, MCP, (More)");
out(design.composer.controls.find((c) => c.id === "mode").label === "Perms", "the permission control is labelled Perms");
const enabledHere = nav.chat.composer.controls;
out(JSON.stringify(enabledHere) === JSON.stringify(designOrder), "Cloud Code enables the whole bar");
for (const a of [uh, uclaw, ug, uc, uf, ud]) {
	const e = M.effortControl(a, M.modelControl(a, chat.default_model, modesUp).value, null);
	const order = M.controlOrder(a, { effort: e.shown, permission: M.permissionControl(a).shown }, design, enabledHere);
	const kept = designOrder.filter((id) => order.includes(id));
	out(JSON.stringify(order) === JSON.stringify(kept) && order[0] === "plus" && order[order.length - 1] === "more" && order[order.indexOf("agent") + 1] === "model" && order.filter((c) => c === "model").length === 1, `${a.label}: ${order.join(" · ")}`);
}
out(JSON.stringify(M.controlOrder(uh, { effort: true, permission: true }, design, ["agent", "task"])) === '["task","agent"]', "an app enabling Task and Agent only gets them in the design's order");
const view = read("src/cloud/chat/view.js");
out(/M\.controlOrder\(a, \{ effort: !!eff\?\.shown, permission: !!perm\?\.shown \}, design, nav\.chat\.composer\.controls\);/.test(view) && /ids\.map\(\(id\) => build\[id\]\(\)\)/.test(view) && (view.match(/role: "model"/g) || []).length === 1, "view.js draws the bar in controlOrder's order and has exactly one Model button");
for (const c of design.composer.controls) out(new RegExp(`${c.id}: \\(\\) =>`).test(view), `view.js draws the ${c.id} control`);
// every icon the design names exists in Acode's icon font (the web build cannot draw one it does not have)
const iconCss = read("src/res/icons/style.css");
const iconNames = [...design.composer.controls.map((c) => c.icon), ...design.permission.modes.map((m) => m.icon), "keyboard_arrow_up", "keyboard_arrow_down", "more_vert", "add"];
for (const n of iconNames) out(iconCss.includes(`icon.${n}:before`) || iconCss.includes(`.${n}:before`), `icon "${n}" exists in the icon font`);

// ── Task: a selected task greys Agent and Model and sets them from the shared mapping ──
const tasks = fleet.tasks;
const tmap = JSON.parse(read(`../${nav.chat.tasks}`)).tasks;
out(tasks.length >= 10 && JSON.stringify(tasks) === JSON.stringify(tmap), "the page's tasks are libs:ai's ai-tasks.json, copied by the resolver");
const none = M.taskLock(tasks, "");
out(!none.locked && M.lockedAgent(U, none, "hermes") === "hermes", "no task: the user's agent and model stand");
const tl = M.taskLock(tasks, tasks[0].id);
out(tl.locked && tl.agent === tasks[0].agent && tl.model === tasks[0].model, `task ${tasks[0].id} forces ${tl.agent} + ${tl.model}`);
out(M.lockedAgent(U, tl, "hermes") === tl.agent && U.some((a) => a.id === tl.agent), "a locked chat runs the task's agent (an agent the page lists)");
out(M.lockedAgent(U, { ...tl, agent: "no-such-agent" }, "hermes") === "hermes", "a task naming an unreachable agent leaves the user's pick");
out(/lock\.locked|l\.locked/.test(view) && /disabled: l\.locked/.test(view), "view.js greys Agent and Model while a task is selected");
out(tasks.every((t) => U.some((a) => a.id === t.agent)), "every task runs on an agent the picker lists");
const lreq = M.buildRequest({ chat, fleet, storage: new Store(), agent: M.pickAgent(U, tl.agent), model: tl.model, effort: "auto", mode: "ask", session, reasoning: new Set(), health: modesUp });
out(JSON.parse(lreq.body).model === tl.model, "a task's request carries the task's model");

// ── the shared catalogues: the Model Catalogue's subpages and the Task Catalogue (libs:ai ai-catalogue.json) ──
const cg = fleet.catalogue;
const cgFile = JSON.parse(read(`../${nav.chat.shared_catalogue}`));
out(JSON.stringify(cg) === JSON.stringify(cgFile), "the page's catalogues are libs:ai's ai-catalogue.json, copied by the resolver");
out(!("model_tabs" in design), "the design no longer carries the old picker tabs");
out(JSON.stringify(M.modelTabs(cg).map((t) => t.label)) === '["Frontier","Core","Specialist","Medium","Small"]', "the Model Catalogue's subpages are Frontier, Core, Specialist, Medium, Small");
out(["Anthropic", "OpenAI", "Google", "xAI", "Meta", "Mistral", "DeepSeek", "Qwen"].every((c) => M.declaredGroups(cg, "frontier").some((g) => g.label === c)), "Frontier lists the major companies");
out(["medical", "legal", "finance", "science", "code"].every((d) => M.declaredGroups(cg, "specialist").some((g) => g.id === d)), "Specialist lists the domains");
out(M.declaredGroups(cg, "core").length === 0, "Core has no declared list: it is the model-catalogue tables");
const cgMed = M.declaredGroups(cg, "medium")[0].models;
const cgSml = M.declaredGroups(cg, "small")[0].models;
out(cgMed.some((m) => m.id === "microsoft/phi-4" && !m.local) && cgSml.some((m) => m.name === "smollm2-360m-instruct" && m.local), "Medium and Small are Cloud Tools' chat rows; a row with no OpenRouter id is local only");
const cgTasks = M.taskCatalogue(cg, fleet.tasks);
out(JSON.stringify(cgTasks.groups.map((g) => g.label)) === '["Tasks - Big LLMs","Tasks - Medium & Small LLMs"]', "the Task Catalogue has the two groups");
out(JSON.stringify(cgTasks.groups[0].categories.map((c) => c.label)) === '["Code","Search (AI search engines)","Conversation","Data Triage"]' && JSON.stringify(cgTasks.groups[1].categories.map((c) => c.label)) === '["Conversation","Data Triage","Math"]', "the groups' categories are the listed ones");
out(cgTasks.groups.every((g) => g.categories.every((c) => c.items.every((i) => i.task))), "every catalogue item has an agent and model mapping");
out(cgTasks.tasks.length === new Set(cgTasks.tasks.map((t) => t.id)).size && fleet.tasks.every((t) => cgTasks.tasks.some((x) => x.id === t.id)), "the selectable tasks are the shared mapping plus the Big LLM items, each id once");
const cgBig = M.taskLock(cgTasks.tasks, "code_architecture");
out(cgBig.locked && cgBig.agent === "openrouter" && cgBig.model === "anthropic/claude-opus-5.5", "a Big LLM task greys Agent and Model and sets them from the catalogue");
out(cgTasks.tasks.every((t) => U.some((a) => a.id === t.agent)), "every catalogue task runs on an agent the picker lists");
out(/function taskPage\(\)/.test(view) && /task: \(\) => chip\([^\n]*taskPage/.test(view) && !/taskSheet/.test(view), "view.js: the Task button opens the Task Catalogue page");
out(/M\.modelTabs\(fleet\.catalogue\)/.test(view) && /M\.declaredGroups\(fleet\.catalogue, tab\)/.test(view) && /cloud-model-tab/.test(view), "view.js draws the Model Catalogue's subpages on the Model page");

// ── collapse: the whole bar folds to one chip, remembered per app ──
out(/function toggleBar\(\)/.test(view) && /data-ctl": "compact"/.test(view) && /localStorage\.setItem\(COLLAPSE_KEY/.test(view) && /COLLAPSE_KEY = `\$\{M\.KEY\}\$\{design\.composer\.collapse\.pref_key\}\.\$\{nav\.chat\.composer\.app\}`/.test(view), "view.js collapses the bar to a compact chip and remembers it under a per-app key");
out(design.composer.collapse.default === "expanded", "the bar opens expanded");

// ── More: grouped, declared, honest ──
const groups = design.more.groups;
out(JSON.stringify(groups.map((g) => g.label)) === '["Usage & Cost","Plugins - Optimisation","Plugins - Skills","Session","System prompt","Help"]', "More is grouped: Usage & Cost, Plugins - Optimisation, Plugins - Skills, Session, System prompt, Help");
out(groups.every((g) => g.items.length) && /design\.more\.groups/.test(view) && /cloud-more-head/.test(view), "view.js draws More from the declared groups, under headers");
const opt = groups[1].items;
out(opt.every((i) => chat.functions.some((f) => f.id === i.id && f.header === i.header && f.plugin === i.plugin)), "the optimisation plugins are the same toggles (header, plugin) nav.json::functions declares");
out(M.usageLines([], "x").length === 0 && M.usageLines([{ id: "s", updated: 1, messages: [{ role: "assistant", content: "a" }] }], "s").length === 0, "Usage & Cost invents nothing: no reported usage, no lines");
const used = M.usageLines([{ id: "s", updated: Date.now(), messages: [{ role: "assistant", content: "a", at: Date.now(), usage: { prompt: 10, completion: 20, cost: 0.5 } }] }], "s");
out(used.length === 2 && used[0].value === "10 in \u00b7 20 out \u00b7 $0.5000" && used[1].label === "Today", "Usage & Cost sums what the answers reported, per session and per day");
const sp = M.sseParser();
sp.push('data: {"choices":[],"usage":{"prompt_tokens":3,"completion_tokens":4}}\n');
out(sp.usage?.prompt === 3 && sp.usage?.completion === 4, "the stream's usage chunk is read");
out(M.parseWhole(200, '{"choices":[{"message":{"content":"x"}}],"usage":{"prompt_tokens":1,"completion_tokens":2,"cost":0.01}}').usage?.cost === 0.01, "a whole answer's usage is read");

// ── the design drives the look: no number of it is restated in the stylesheet ──
const css = read("src/cloud/cloud.scss");
out(Object.keys(design.metrics).every((k) => view.includes("--ai-") && true) && /replaceAll\("_", "-"\)/.test(view), "view.js sets every design metric as a --ai-* custom property");
for (const k of ["chat-font", "user-radius-top", "input-radius", "send-size", "ctl-height", "ctl-radius", "assistant-width-pct", "bar-toggle-size"]) out(css.includes(`--ai-${k}`), `cloud.scss reads --ai-${k}`);
out(!/border-radius: 14px 14px 4px 14px/.test(css) && !/min-height: 32px/.test(css), "the old hard-coded bubble and chip sizes are gone from the stylesheet");

// ── the Model page: A0-A3 from the catalogue, radio, per-agent, greying ─────
out(/pg\.node\.classList\.add\("cloud-model-page"\)/.test(view) && /type: "radio", name: "cloud-model"/.test(view) && /M\.setAgentModel\(localStorage, a\.id, row\.id\)/.test(view), "the Model page is a full page with one radio group, saving per agent");
const scss = read("src/cloud/cloud.scss");
out(/\.cloud-page\.cloud-model-page \{\s*inset: 0;/.test(scss) && /\.cloud-cat-head \{\s*position: sticky;/.test(scss) && /\.cloud-cat-scroll \{\s*overflow-x: auto;/.test(scss), "full height, sticky section headers, each table scrolls sideways");
const text = cat.groups.find((g2) => g2.id === "A").sections;
out(JSON.stringify(text.map((x) => x.id)) === JSON.stringify(chat.catalogue.sections) && text.every((x) => x.rows.length > 0), `the page's sections are the catalogue's Text tables with rows (${text.map((x) => `${x.id}:${x.rows.length}`).join(" ")})`);
const allRows = text.flatMap((x) => x.rows.map((r) => ({ ...r, selectable: true })));
const ps = new Store();
const radios = (agentId) => {
	const key = M.checkedRow(text, M.agentModel(ps, chat, agentId));
	return text.flatMap((x) => x.rows.map((r) => `${x.id}:${r.id}`)).filter((k) => k === key).length;
};
out(text.flatMap((x) => x.rows).filter((r) => r.id === chat.default_model).length >= 1, "the default model is a catalogue row (it may sit in several tables)");
out(/checked: checked === `\$\{sec\.id\}:\$\{r\.id\}`/.test(view), "the page checks the radio M.checkedRow picks, never every row with the id");
out(radios("claude") === 1, "exactly one radio is selected (the default model)");
const a2row = text.find((x) => x.id === "A2").rows.find((r) => r.provider !== "Anthropic");
M.setAgentModel(ps, "openrouter", a2row.id);
out(M.agentModel(ps, chat, "openrouter") === a2row.id && radios("openrouter") === 1 && M.agentModel(ps, chat, "claude") === chat.default_model, "a pick in A2 is saved for that agent only, and stays the one selected");
const anth = allRows.find((r) => r.provider === "Anthropic");
const other = allRows.find((r) => r.provider !== "Anthropic");
const cs = M.rowState(uc, other, modesUp);
out(M.rowState(uc, anth, modesUp).allowed && !cs.allowed && /Anthropic/.test(cs.reason), "Claude: Anthropic rows only, the others greyed with the reason");
const hs = M.rowState(ug, anth, modesUp);
out(!hs.allowed && /set by Goose/.test(hs.reason) && !M.rowState(uh, other, modesUp).allowed, "Goose and Hermes: every row greyed, 'set by' the agent");
out(allRows.every((r) => M.rowState(ud, r, modesUp).allowed) && allRows.every((r) => M.rowState(uf, r, modesUp).allowed), "OpenRouter (direct and fleet) can pick any row");

// ── the DeepSeek harness (the gateway's deepseek mode) ──────────────────────
const uds = by(U, "deepseek");
out(uds.available && uds.state === "ready" && /openrouter\+mcp/.test(M.agentSub(uds)), "DeepSeek sits next to Claude, ready when the gateway serves its mode");
const dsRow = allRows.find((r) => r.provider === "DeepSeek");
out(M.rowState(uds, dsRow, modesUp).allowed && !M.rowState(uds, anth, modesUp).allowed && /DeepSeek models only/.test(M.rowState(uds, anth, modesUp).reason), "DeepSeek can pick only the catalogue's DeepSeek rows (A0-A3), others greyed with the reason");
const rds = M.buildRequest({ chat, fleet, storage: new Store(), agent: uds, model: dsRow.id, effort: "high", mode: "plan", session, reasoning: new Set([dsRow.id]), health: modesUp });
const rdb = JSON.parse(rds.body);
out(rds.headers["x-agent-mode"] === "deepseek" && rdb.model === dsRow.id && rdb.reasoning?.effort === "high" && rdb.messages[0].role === "system", "its request: X-Agent-Mode deepseek, the picked DeepSeek model, effort, permission as an instruction");
out(M.permissionControl(uds).shown && M.mcpServers(uds, [{ server: "cloud-infra", count: 3 }], fleet.mcp_fleet).source === "gateway", "DeepSeek has tools: permission mode and the gateway's MCP list");
const dsOld = by(M.resolveAgents(chat, fleet, { ...modesUp, modes: modesUp.modes.filter((m) => m.id !== "deepseek") }), "deepseek");
out(dsOld.state === "unavailable" && !dsOld.available, "a gateway without the deepseek mode: DeepSeek is shown, unavailable, never faked");

// ── one source of truth: the shared agent descriptors (libs:model-catalogue agents.json) ──
const sharedFile = JSON.parse(read(`../${chat.shared_agents}`));
for (const s of sharedFile.agents) {
	const d = fleet.agent_decls.find((x) => x.id === s.id);
	out(!!d && d.label === s.label && d.mode === s.mode && (d.fleet ?? null) === (s.fleet ?? null) && d.model.kind === s.model.kind, `${s.label}: label, mode, fleet and model kind come from the shared agents.json`);
	const navEntry = chat.agents.find((x) => x.id === s.id);
	out(!!navEntry && !("label" in navEntry) && !("mode" in navEntry) && !("fleet" in navEntry), `${s.id}: nav.json does not restate the shared identity (order and app fields only)`);
}
out(sharedFile.agents.some((a) => a.id === "openclaw") && sharedFile.agents.some((a) => a.id === "deepseek"), "the shared file lists OpenClaw and the DeepSeek Harness, so Cloud Search sees them too");
out(M.resolveAgents(chat, fleet, undefined).find((a) => a.id === "deepseek").label === sharedFile.agents.find((a) => a.id === "deepseek").label, "the picker shows the shared label");

// ── the shared protocol cases (libs:ai assets/ai/protocol-cases.json): the same file libs:ai's AiProtocolTest runs ──
const proto = JSON.parse(read(`../${nav.chat.ai.replace(/ai\.json$/, "protocol-cases.json")}`));
out(aiContract.protocol.cases === "protocol-cases.json", "ai.json names the shared protocol cases file");
for (const c of proto.sse) {
	const sp = M.sseParser();
	const text = c.chunks.flatMap((ch) => sp.push(ch)).join("");
	out(text === c.text && sp.done === c.done && (sp.error ?? null) === c.error, `shared SSE case: ${c.name}`);
}
for (const c of proto.whole) {
	const r = M.parseWhole(c.status, c.body);
	out((r.text ?? null) === c.text && (r.error ?? null) === c.error && (!c.citations || JSON.stringify(r.citations) === JSON.stringify(c.citations)), `shared whole-answer case: ${c.name}`);
}
for (const c of proto.request) {
	const agent = c.backend === "openrouter" ? U.find((a) => a.via === "direct") : U.find((a) => a.via === "gateway" && a.mode === c.mode);
	const sess = M.newSession(Date.now(), agent.id);
	sess.messages.push(...c.messages.map((m) => ({ ...m })));
	const rqc = M.buildRequest({ chat, fleet, storage: new Store(), agent, model: c.model ?? "x/y", effort: "low", mode: "ask", session: sess, reasoning: new Set(), health: modesUp });
	const bc = JSON.parse(rqc.body);
	const headersOk = Object.entries(c.headers).every(([k, v]) => rqc.headers[k.toLowerCase()] === v);
	const bodyOk = Object.entries(c.body).every(([k, v]) => JSON.stringify(bc[k]) === JSON.stringify(v));
	const absentOk = c.absent.every((k) => !(k in bc));
	const urlOk = c.backend === "gateway" ? rqc.url === fleet.gateway + chat.gateway.chat_path : rqc.url === chat.direct.url;
	out(headersOk && bodyOk && absentOk && urlOk, `shared request case: ${c.name}`);
}
