// cloud-code Chat — the page (replaced Home). Cloud Search's chat without the search boxes: one
// message list, streamed replies, one composer. Left of the Model button: + (files, photos, voice),
// Agent, Effort, permission Mode, MCP and More. Every decision is model.js's; the network, the
// token and the attachments are the CloudChat plugin's (src/plugins/cloudchat). Nothing here ever
// holds the OpenRouter token: Profile & Config hands it straight to the plugin.
import markdownIt from "markdown-it";
import DOMPurify from "dompurify";
import toast from "components/toast";
import nav from "../nav.json";
import targets from "../targets.gen.json";
import { el, icon } from "../dom";
import { option, sheet, toggleRow, page, closeOverlay } from "../chrome";
import * as M from "./model";
import * as E from "./engine";
import fsOperation from "fileSystem";

// nav.json is the tab's own declaration; the gateway and OpenRouter come from the shared AI contract (libs:ai ai.json),
// copied into targets.gen.json by tools/resolve-targets.py (chat.ai): nothing here restates an endpoint or a path.
const fleet = targets.chat;
// The shared catalogues (libs:ai ai-catalogue.json): the Task Catalogue's groups and every task a selection can set.
const taskCat = M.taskCatalogue(fleet.catalogue, fleet.tasks);
// The shared design (libs:ai ai-design.json) is the one source of the permission modes and the effort levels too.
const design = fleet.design;
const chat = { ...nav.chat, ...fleet.ai, permission: { ...nav.chat.permission, ...design.permission }, effort: design.effort };
const COLLAPSE_KEY = `${M.KEY}${design.composer.collapse.pref_key}.${nav.chat.composer.app}`;
const md = markdownIt({ html: false, linkify: true, breaks: true });
const REASONING_TTL = 24 * 3600 * 1000;

const S = {
	sessions: [],
	current: null,
	// undefined = the gateway not asked yet, null = it did not answer (model.js::resolveAgents)
	health: undefined,
	mcpLive: null,
	reasoning: null,
	streaming: null,
	pending: [],
	recording: false,
	panel: null,
	chrome: null,
	catalogue: null,
	// Cloud SuperIntel's AI engines (#1855 P2): undefined = not asked yet, {si: false} = absent (the direct gateway road stays), {si: true, providers}
	engine: undefined,
	// the request id the engine road is streaming (stop() cancels it through SuperIntel instead of the plugin)
	engineStreaming: null,
};

const plugin = () => window.CloudChat;

function persist() {
	M.saveSessions(localStorage, S.sessions);
	mirror();
}

// The chats also live in files/chat-sessions.json, the file the fleet setup contract carries to a new
// phone (store code_chat_sessions); localStorage stays the working copy.
const MIRROR_NAME = "chat-sessions.json";
const mirrorDir = () => window.cordova?.file?.dataDirectory || null;

async function mirror() {
	try {
		const dir = mirrorDir();
		const text = localStorage.getItem(`${M.KEY}sessions`);
		if (!dir || text == null) return;
		const f = fsOperation(`${dir}${MIRROR_NAME}`);
		if (await f.exists()) await f.writeFile(text);
		else await fsOperation(dir).createFile(MIRROR_NAME, text);
	} catch {
		// the file is a copy for the next phone; the chat goes on without it
	}
}

/** A restored phone: the file arrived, localStorage is empty — the file seeds it, once. */
async function seedFromMirror() {
	try {
		const dir = mirrorDir();
		if (!dir || localStorage.getItem(`${M.KEY}sessions`) != null) return;
		const f = fsOperation(`${dir}${MIRROR_NAME}`);
		if (!(await f.exists())) return;
		const text = await f.readFile("utf8");
		if (text && JSON.parse(text)) { localStorage.setItem(`${M.KEY}sessions`, text); S.current = null; }
	} catch {
		// unreadable: start empty, as before
	}
}

function current() {
	if (!S.current) {
		S.sessions = M.loadSessions(localStorage);
		const settings = M.loadSettings(localStorage, chat);
		S.current = S.sessions.sort((a, b) => b.updated - a.updated)[0] || fresh(settings);
	}
	return S.current;
}

function fresh(settings = M.loadSettings(localStorage, chat)) {
	const s = M.newSession(Date.now(), settings.agent);
	Object.assign(s, { model: settings.model, effort: settings.effort, mode: settings.mode, task: nav.chat.composer.default_task || "" });
	return s;
}

function agents() {
	return M.resolveAgents(chat, fleet, S.health);
}

/** The task in force on the open chat ({locked, task, agent, model}): a selected task forces the agent and the model. */
function lock() {
	return M.taskLock(taskCat.tasks, current().task);
}

function agent() {
	const s = current();
	const l = lock();
	const a = M.pickAgent(agents(), M.lockedAgent(agents(), l, s.agent));
	if (a && !l.locked && a.id !== s.agent) s.agent = a.id; // a locked chat leaves the user's own pick alone for when the task is cleared
	return a;
}

/** The chat model kept for this agent (the Model page saves it per agent); the task's mapped one while a task is selected. */
function modelOf(a) {
	const l = lock();
	if (l.locked && a?.id === l.agent) return l.model;
	return M.agentModel(localStorage, chat, a?.id);
}

// ── live data: the gateway's /health and MCP status, OpenRouter's reasoning models ──

async function getJson(url, auth) {
	const r = await plugin().get(url, {}, auth);
	if (r.status < 200 || r.status >= 300) throw new Error(`HTTP ${r.status}`);
	return JSON.parse(r.body);
}

async function refreshLive() {
	if (!plugin()) return;
	const g = fleet.gateway;
	const [health, mcp] = await Promise.allSettled([getJson(g + chat.gateway.health_path), getJson(g + chat.gateway.mcp_path)]);
	S.health = health.status === "fulfilled" ? health.value : null;
	S.mcpLive = mcp.status === "fulfilled" && Array.isArray(mcp.value) ? mcp.value : null;
	S.reasoning = await reasoningIds();
	draw();
}

/** SuperIntel's provider list (#1855 P2). A missing bridge or an absent SuperIntel is {si: false}: the direct road and the install hint. */
async function refreshEngine() {
	S.engine = await E.engineProviders(E.bridge());
	draw();
}

async function reasoningIds() {
	try {
		const cached = JSON.parse(localStorage.getItem(`${M.KEY}reasoning`) || "null");
		if (cached && Date.now() - cached.at < REASONING_TTL) return new Set(cached.ids);
		const r = await plugin().get(chat.direct.models_url, {});
		const ids = M.reasoningIds(r.body);
		if (ids) localStorage.setItem(`${M.KEY}reasoning`, JSON.stringify({ at: Date.now(), ids: [...ids] }));
		return ids;
	} catch {
		return null;
	}
}

// ── the page ────────────────────────────────────────────────────────────────

let $list;
let $input;
let $attach;
let $controls;
let $send;

export async function renderChat(panel, chrome) {
	await seedFromMirror();
	S.panel = panel;
	S.chrome = chrome;
	current();
	$list = el("div", { className: "cloud-chat-list" });
	$attach = el("div", { className: "cloud-chat-attach" });
	$input = el("textarea", { className: "cloud-chat-input", rows: "1", placeholder: "Message" });
	$input.addEventListener("input", () => {
		$input.style.height = "auto";
		$input.style.height = `${Math.min($input.scrollHeight, 160)}px`;
	});
	$send = el("button", { className: "cloud-chat-send", title: "Send", onclick: () => (S.streaming ? stop() : send()) }, icon("send"));
	$controls = el("div", { className: "cloud-chat-controls" });
	const composer = el("div", { className: "cloud-chat-composer" }, $attach, el("div", { className: "cloud-chat-row" }, $input, $send), $controls);
	panel.classList.add("cloud-chat-panel");
	// The design's sizes as custom properties: cloud.scss restates none of them.
	for (const [k, v] of Object.entries(design.metrics)) document.documentElement.style.setProperty(`--ai-${k.replaceAll("_", "-")}`, String(v));
	if (S.collapsed === undefined) S.collapsed = (localStorage.getItem(COLLAPSE_KEY) ?? (design.composer.collapse.default === "collapsed" ? "1" : "0")) === "1";
	panel.replaceChildren(chrome.bar("AI"), $list, composer);
	draw();
	refreshLive().catch(() => {});
	refreshEngine().catch(() => {});
}

function draw() {
	if (!S.panel) return;
	const s = current();
	const a = agent();
	if (!S.panel.hidden) S.chrome.bar(a ? a.label : "AI", M.title(s)); // the island belongs to the tab on screen
	drawMessages();
	drawControls();
}

function drawMessages() {
	const s = current();
	if (!s.messages.length) {
		const a = agent();
		$list.replaceChildren(el("div", { className: "cloud-chat-empty" },
			el("div", { className: "cloud-chat-hello" }, "How can I help?"),
			el("div", { className: "cloud-muted" }, a ? `${a.label} · ${M.modelControl(a, modelOf(a), S.health).label}` : "No agent can be reached"),
			E.roadLabel(S.engine, a) ? el("div", { className: "cloud-muted cloud-chat-road" }, E.roadLabel(S.engine, a)) : null));
		return;
	}
	$list.replaceChildren(...s.messages.map(bubble));
	$list.scrollTop = $list.scrollHeight;
}

function bubble(m) {
	const chips = (m.attachments || []).map((f) => el("span", { className: "cloud-chip" }, icon(chipIcon(f.mime)), f.name));
	if (m.role === "user") return el("div", { className: "cloud-msg user" }, el("div", { className: "cloud-msg-body" }, m.content), chips.length ? el("div", { className: "cloud-chips" }, chips) : null);
	const body = el("div", { className: "cloud-msg-body cloud-md" });
	body.innerHTML = DOMPurify.sanitize(md.render(m.content || (m.streaming ? "…" : "")));
	const meta = el("div", { className: "cloud-msg-meta cloud-muted" }, [m.agent, m.model, m.via].filter(Boolean).join(" · "), m.error ? ` · ${m.error}` : "");
	return el("div", { className: `cloud-msg assistant${m.error ? " error" : ""}` }, body, meta);
}

function chipIcon(mime = "") {
	if (mime.startsWith("image/")) return "image";
	if (mime.startsWith("audio/")) return "keyboard_voice";
	if (mime.startsWith("video/")) return "videocam";
	return "attach_file";
}

// ── the composer controls (all left of Model) ───────────────────────────────

function chip(label, iconName, onclick, { disabled, title, role } = {}) {
	return el("button", { className: "cloud-ctl", disabled: !!disabled, title: title || label, "data-ctl": role, onclick }, icon(iconName), label ? el("span", {}, label) : null);
}

function drawControls() {
	const s = current();
	const a = agent();
	const l = lock();
	// model.js::controlOrder is the one order, the design's: (+) · Task · Agent · Model · Effort · Perms · MCP · (More),
	// narrowed to what this app enables and to what the chosen agent takes.
	const eff = a ? M.effortControl(a, M.modelControl(a, modelOf(a), S.health).value, S.reasoning) : null;
	const perm = a ? M.permissionControl(a) : null;
	const modes = design.permission.modes;
	const build = {
		plus: () => chip("", "add", plusSheet, { title: "Attach files, photos or a voice message, or start a new chat", role: "plus" }),
		task: () => chip(l.task ? l.task.label : "Task", "check_circle", taskPage, { title: l.locked ? `Task: ${l.task.label}` : "Task", role: "task" }),
		agent: () => chip(a ? a.label : "Agent", "brain", agentSheet, { role: "agent", disabled: l.locked, title: l.locked ? `Set by the task: ${l.task.label}` : "Agent" }),
		model: () => {
			const mc = M.modelControl(a, modelOf(a), S.health);
			return chip(shortModel(mc.label), "wand-sparkles", () => modelPage(), { disabled: l.locked, title: l.locked ? `Set by the task: ${l.task.label}` : mc.pick ? `Model: ${mc.label}` : `Model ${mc.label} (set by ${a.label})`, role: "model" });
		},
		effort: () => {
			const lvl = chat.effort.levels.find((x) => x.id === s.effort) || chat.effort.levels.find((x) => x.id === chat.effort.default);
			return chip(lvl.id === chat.effort.default ? "Effort" : lvl.label, "tune", effortSheet, { disabled: !eff.enabled, title: eff.enabled ? "Effort" : eff.reason, role: "effort" });
		},
		mode: () => {
			const mode = modes.find((m) => m.id === s.mode) || modes.find((m) => m.id === design.permission.default);
			return chip(mode.short, design.composer.controls.find((c) => c.id === "mode").icon, modeSheet, { title: mode.label, role: "mode" });
		},
		mcp: () => chip("MCP", "extension", mcpSheet, { role: "mcp" }),
		more: () => chip("", "more_vert", moreSheet, { title: "More", role: "more" }),
	};
	const toggle = el("button", { className: "cloud-ctl-toggle", title: S.collapsed ? "Show the config bar" : "Hide the config bar", "data-ctl": "toggle", onclick: toggleBar }, icon(S.collapsed ? "keyboard_arrow_up" : "keyboard_arrow_down"));
	if (S.collapsed) {
		const label = l.locked ? l.task.label : shortModel(M.modelControl(a, modelOf(a), S.health).label);
		$controls.replaceChildren(el("button", { className: "cloud-ctl", "data-ctl": "compact", title: "Show the config bar", onclick: toggleBar }, icon(l.locked ? "check_circle" : "wand-sparkles"), el("span", {}, label)), toggle);
	} else {
		const ids = M.controlOrder(a, { effort: !!eff?.shown, permission: !!perm?.shown }, design, nav.chat.composer.controls);
		$controls.replaceChildren(el("div", { className: "cloud-ctl-row" }, ...ids.map((id) => build[id]())), toggle);
	}
	$attach.replaceChildren(...S.pending.map((f, i) => el("span", { className: "cloud-chip" }, icon(chipIcon(f.mime)), f.name,
		el("button", { className: "cloud-chip-x", title: "Remove", onclick: () => { S.pending.splice(i, 1); drawControls(); } }, icon("clearclose")))));
	if (S.recording) $attach.append(el("span", { className: "cloud-chip recording" }, icon("keyboard_voice"), "Recording… tap + to stop"));
}

/** Collapses the whole config bar to one chip (or expands it); remembered per app. */
function toggleBar() {
	S.collapsed = !S.collapsed;
	try {
		localStorage.setItem(COLLAPSE_KEY, S.collapsed ? "1" : "0");
	} catch {
		// a blocked store keeps the state for this session
	}
	drawControls();
}

/**
 * The Task button: the full Task Catalogue page (libs:ai ai-catalogue.json), the groups Big LLMs and Medium & Small LLMs with their
 * categories. Selecting a task greys Agent and Model and sets them from its mapping, then returns to Chat; "No task" clears it.
 */
function taskPage() {
	const s = current();
	const pg = page("Task catalogue", null, el("div", { className: "cloud-cat cloud-task-cat" }));
	pg.node.classList.add("cloud-task-page");
	const choose = (id) => { s.task = id; persist(); draw(); pg.close(); };
	const body = pg.node.querySelector(".cloud-task-cat");
	if (!nav.chat.composer.task_required) body.append(el("button", { className: `cloud-task-none${s.task ? "" : " active"}`, "data-task": "", onclick: () => choose("") }, design.composer.no_task));
	for (const g of taskCat.groups) {
		body.append(el("h3", { className: "cloud-task-group", "data-group": g.id }, g.label));
		for (const c of g.categories) {
			body.append(el("div", { className: "cloud-task-category", "data-category": c.id }, el("strong", {}, c.label),
				...c.items.map((i) => el("button", {
					className: `cloud-task-item${i.id === s.task ? " active" : ""}`, "data-task": i.id, disabled: !i.task, onclick: () => choose(i.id),
				}, el("span", {}, i.label), el("small", { className: "cloud-muted" }, i.task ? `${i.task.agent} \u00b7 ${i.task.model.split("/").pop()}${i.note ? ` \u00b7 ${i.note}` : ""}` : "no mapping")))));
		}
	}
}

function plusSheet() {
	if (S.recording) return stopRecording();
	sheet("Add", [
		option("Photos & videos", { iconName: "image", sub: "Android photo picker", run: () => pick("media") }),
		option("Files", { iconName: "attach_file", sub: "any file, through the system picker", run: () => pick("file") }),
		option("Voice message", { iconName: "keyboard_voice", sub: "record, then send as audio (no speech-to-text in this app)", run: startRecording }),
		option(design.composer.plus.new_chat, { iconName: "add", run: () => { S.current = fresh(); draw(); } }),
	]);
}

function shortModel(label) {
	const t = String(label || "").split("/").pop();
	return t.length > 22 ? `${t.slice(0, 21)}…` : t;
}

function agentSheet() {
	const s = current();
	const rows = agents().map((a) => option(a.label, {
		checked: a.id === s.agent, disabled: !a.available,
		sub: M.agentSub(a),
		run: () => { s.agent = a.id; persist(); draw(); },
	}));
	// The engines SuperIntel runs (#1855 P2), or the install hint: informational rows under the agents, nothing above them is removed.
	if (S.engine?.si) for (const p of S.engine.providers) rows.push(option(`${p.label || p.id} \u00b7 ${p.health || "unknown"}`, { disabled: true, iconName: "memory", sub: [p.kind, p.model, p.detail].filter(Boolean).join(" \u00b7 ") }));
	else if (S.engine) rows.push(option(E.INSTALL_SHORT, { disabled: true, iconName: "download", sub: E.INSTALL_HINT }));
	sheet("Agent", rows, [S.health === null
		? "The gateway did not answer: the fleet's declared agents are shown, marked offline."
		: "From the fleet: the agents the C3 catalogue declares and the gateway's /health serves now.", E.engineNote(S.engine)].filter(Boolean).join(" "));
}

function effortSheet() {
	const s = current();
	sheet("Effort", chat.effort.levels.map((l) => option(l.label, { checked: l.id === s.effort, sub: l.value ? `reasoning.effort = ${l.value}` : "the model's own default", run: () => { s.effort = l.id; persist(); drawControls(); } })));
}

function modeSheet() {
	const s = current();
	const a = agent();
	const perm = M.permissionControl(a);
	const note = perm.via === "prompt" ? `${a.label} has no per-request approval switch: the mode is sent as an instruction.` : null;
	sheet("Permission mode", chat.permission.modes.map((m) => option(m.label, { checked: m.id === s.mode, run: () => { s.mode = m.id; persist(); drawControls(); } })), note);
}

function mcpSheet() {
	const a = agent();
	const { source, servers } = M.mcpServers(a, S.mcpLive, fleet.mcp_fleet);
	const state = M.mcpState(localStorage, a.id, servers);
	const note = source === "none" ? `${a.label} has no MCP servers.`
		: source === "gateway" ? "Live from the gateway (/v1/mcp/status). Saved per agent; the gateway has no per-request server switch yet, so this list is advisory there."
		: "From the fleet MCP config (the derived .mcp.json). Saved per agent.";
	sheet(`MCP · ${a.label}`, servers.map((sv) => toggleRow(sv.name, state[sv.name], (on) => M.mcpToggle(localStorage, a.id, sv.name, on), sv.tools != null ? `${sv.tools} tools` : null)), note);
}

/**
 * The More sheet, grouped under headers as the design declares them (Usage & Cost, Plugins - Optimisation, Plugins - Skills, Session,
 * System prompt, Help), then the chosen agent's own functions under their own header. Usage is only what the backend reported.
 */
function moreSheet() {
	const a = agent();
	const saved = M.toggles(localStorage, a.id);
	const own = M.moreFor(chat, a, S.health);
	const fn = (id) => own.find((f) => f.id === id);
	const header = (text) => el("div", { className: "cloud-more-head" }, text);
	const rows = [];
	for (const g of design.more.groups) {
		rows.push(header(g.label));
		for (const it of g.items) {
			if (it.kind === "plugin") {
				const f = fn(it.id);
				const live = !!S.health?.plugins?.[it.plugin];
				rows.push(toggleRow(it.label + (live ? "" : " \u00b7 not listed by the gateway"), saved[it.id] ?? live, (on) => M.setToggle(localStorage, a.id, it.id, on), it.header, !f || !live));
			} else rows.push(option(it.label, { sub: it.kind === "prompt" ? it.prompt : null, run: () => moreItem(a, it) }));
		}
	}
	const shared = new Set(design.more.groups.flatMap((g) => g.items.map((i) => i.id)));
	const rest = own.filter((f) => !shared.has(f.id));
	if (rest.length) rows.push(header(`${a.label} functions`));
	for (const f of rest) {
		if (f.kind === "toggle") rows.push(toggleRow(f.label, saved[f.id] ?? !!S.health?.plugins?.[f.plugin], (on) => M.setToggle(localStorage, a.id, f.id, on), f.header));
		else if (f.kind === "select") rows.push(option(`${f.label}: ${saved[f.id] ?? "server default"}`, { sub: f.header, run: () => selectValue(a, f) }));
		else rows.push(option(f.label, { sub: f.offline ? "gateway offline" : f.kind === "native" ? `${a.label}, live: ${f.path}` : f.route ? chat.gateway[f.route] : null, run: () => invoke(a, f) }));
	}
	sheet(`More \u00b7 ${a.label}`, rows, S.health ? null : "The gateway did not answer: the declared list is shown.");
}

function moreItem(a, it) {
	const s = current();
	if (it.kind === "prompt") {
		$input.value = it.prompt;
		$input.focus();
		return;
	}
	if (it.kind === "usage") {
		const lines = M.usageLines(S.sessions, s.id);
		const text = (all) => (all.length ? all.map((l) => `${l.label}: ${l.value}`).join("\n") : "The backend has reported no usage yet. Tokens and cost appear here once an answer carries them.");
		const pre = el("pre", { className: "cloud-pre" }, text(lines));
		// What Cloud SuperIntel metered (#1855 P2) is added under the on-phone lines when it answers.
		if (S.engine?.si) E.engineUsage(E.bridge()).then((r) => { const e = E.usageLines(r.usage); if (e.length) pre.textContent = text([...lines, ...e]); }).catch(() => {});
		return page(it.label, null, pre);
	}
	if (it.kind === "export") return exportChat();
	if (it.kind === "retry") return retry();
	if (it.kind === "undo") {
		s.messages.splice(Math.max(0, s.messages.map((m) => m.role).lastIndexOf("user")));
		persist();
		return drawMessages();
	}
	if (it.kind === "system") return page(it.label, null, el("pre", { className: "cloud-pre" }, M.permissionInstruction(chat, a, s.mode) || "No standing system prompt."));
	return page(it.label, null, el("p", {}, it.text));
}

function selectValue(a, f) {
	sheet(f.label, f.values.map((v) => option(v, { run: () => { M.setToggle(localStorage, a.id, f.id, v); } })));
}

async function invoke(a, f) {
	const s = current();
	try {
		if (f.kind === "prompt") {
			$input.value = f.prompt;
			return send();
		}
		if (f.kind === "client") {
			if (f.id === "retry") return retry();
			if (f.id === "undo") {
				s.messages.splice(Math.max(0, s.messages.map((m) => m.role).lastIndexOf("user")));
				persist();
				return drawMessages();
			}
			if (f.id === "catalogue") return modelPage(true);
		}
		if (f.kind === "login") return claudeLogin();
		if (f.kind === "native") return nativeFunction(a, f);
		const url = fleet.gateway + chat.gateway[f.route];
		if (f.kind === "tools") {
			const { default: prompt } = await import("dialogs/prompt");
			const q = await prompt("Search the agent's tools", "", "search");
			if (q == null) return;
			const hits = await getJson(url + encodeURIComponent(q));
			return sheet(`Tools · ${q}`, (Array.isArray(hits) ? hits : []).map((t) => option(t.name, { sub: t.description })), Array.isArray(hits) && hits.length ? null : "No tool matched.");
		}
		if (f.kind === "sessions") return gatewaySessions(url);
		const info = await getJson(url);
		page(f.label, null, el("pre", { className: "cloud-pre" }, JSON.stringify(info, null, 2)));
	} catch (e) {
		toast(`${f.label}: ${e?.message || e}`);
	}
}

/** One of an agent's own functions, served by the gateway (GET /agents/<agent>/<fn>). */
async function nativeFunction(a, f) {
	const j = await getJson(fleet.gateway + f.path);
	const list = Array.isArray(j) ? j : Array.isArray(j?.data) ? j.data : Array.isArray(j?.sessions) ? j.sessions : Array.isArray(j?.jobs) ? j.jobs : null;
	if (list) {
		const rows = list.map((it) => option(String(it.name || it.title || it.id || "item"), { sub: [it.description, it.category, it.schedule, it.updated_at || it.created_at].filter(Boolean).join(" · ") || null }));
		return sheet(`${f.label} · ${a.label}`, rows, rows.length ? null : "Nothing listed.");
	}
	page(`${f.label} · ${a.label}`, null, el("pre", { className: "cloud-pre" }, JSON.stringify(j, null, 2)));
}

async function gatewaySessions(url) {
	const list = await getJson(url);
	const rows = (Array.isArray(list) ? list : []).map((r) => option(r.name || r.id, {
		sub: `${r.device} · ${new Date(r.mtime).toLocaleString()}`,
		run: async () => {
			try {
				const res = await plugin().get(`${url}/${encodeURIComponent(r.device)}/${encodeURIComponent(r.id)}?tail=65536`, {});
				const s = fresh();
				s.title = r.name || r.id;
				s.messages = res.body.split("\n").map((l) => { try { return JSON.parse(l); } catch { return null; } })
					.filter((o) => o && (o.role === "user" || o.role === "assistant") && typeof o.content === "string")
					.map((o) => ({ role: o.role, content: o.content }));
				S.sessions.unshift(s);
				S.current = s;
				persist();
				draw();
			} catch (e) {
				toast(String(e?.message || e));
			}
		},
	}));
	sheet("Gateway sessions", rows, rows.length ? "Tap one to continue it here." : "No session in the store.");
}

async function claudeLogin() {
	const base = fleet.claude_api;
	const r = await plugin().post(`${base}/auth/login/start`, "{}");
	const j = JSON.parse(r.body || "{}");
	if (!j.url) throw new Error(j.error || `HTTP ${r.status}`);
	system.openInBrowser(j.url);
	const { default: prompt } = await import("dialogs/prompt");
	const code = await prompt("Paste the code from the login page", "", "text");
	if (!code) return;
	const done = await plugin().post(`${base}/auth/login/code`, JSON.stringify({ code }));
	toast(JSON.parse(done.body || "{}").message || `HTTP ${done.status}`);
}

// ── + : files, photos / media, a voice message ──────────────────────────────

async function pick(kind) {
	try {
		const files = await plugin().pick(kind, true);
		for (const f of files) {
			if (f.error) toast(`${f.name}: ${f.error}`);
			else S.pending.push(f);
		}
		drawControls();
	} catch (e) {
		toast(String(e?.message || e));
	}
}

async function startRecording() {
	try {
		await plugin().recordStart();
		S.recording = true;
		drawControls();
	} catch (e) {
		toast(String(e?.message || e));
	}
}

async function stopRecording() {
	try {
		const a = await plugin().recordStop();
		S.recording = false;
		S.pending.push({ ...a, name: `voice-${Math.round(a.duration_ms / 1000)}s.wav` });
		drawControls();
	} catch (e) {
		S.recording = false;
		toast(String(e?.message || e));
		drawControls();
	}
}

// ── the Model page: libs:model-catalogue, the four Text tables (A0–A3) ──────

/**
 * A full page, not a sheet: Cloud Search's chat model page drawn in HTML from the same lib data
 * (this WebView app cannot run the Compose screen). One radio per row, exactly one selected;
 * picking saves the model for the CURRENT agent (model.js::setAgentModel) and returns to Chat,
 * Back closes without change. Rows the agent cannot run are greyed with the reason
 * (model.js::rowState). [onPick] (Profile & Config's default model) takes any selectable row.
 */
export async function modelPage(refresh = false, onPick = null) {
	const a = agent();
	const chosen = onPick ? M.loadSettings(localStorage, chat).model : modelOf(a);
	const body = el("div", { className: "cloud-cat" }, el("p", { className: "cloud-muted" }, "Loading the catalogue…"));
	const pg = page(onPick ? "Default model" : `Model · ${a ? a.label : "AI"}`, null, body);
	pg.node.classList.add("cloud-model-page");
	const choose = (row) => {
		if (onPick) onPick(row.id);
		else {
			M.setAgentModel(localStorage, a.id, row.id);
			draw();
		}
		pg.close();
	};
	let tab = null;
	let shown = null;
	// The models Cloud SuperIntel lists for this agent's provider (#1855 P2): added above the catalogue tables when it answers, loaded after the page opens.
	let engineModels = null;
	if (!onPick && a && E.engineUsable(S.engine, a) && E.bridge()) {
		E.engineModels(E.bridge(), E.providerFor(a)).then((r) => { engineModels = r.models; if (shown && pg.node.isConnected) show(shown); }).catch(() => {});
	}
	const show = (cat) => {
		shown = cat;
		const mc = a ? M.modelControl(a, chosen, S.health) : { pick: true, label: chosen };
		const head = onPick || mc.pick
			? `Selected: ${chosen}`
			: `${a.label} runs ${mc.value || "its own model"}: the rows are shown for reference.`;
		const sections = cat.groups.flatMap((x) => x.sections);
		// The shared catalogue's subpages (Frontier · Core · Specialist · Medium · Small): Core is the model-catalogue tables, the rest declared lists.
		const tabs = M.modelTabs(fleet.catalogue);
		if (!tab || !tabs.some((t) => t.id === tab)) tab = "core";
		const bar = el("div", { className: "cloud-model-tabs", role: "tablist" }, ...tabs.map((t) => el("button", {
			className: `cloud-model-tab${t.id === tab ? " active" : ""}`, role: "tab", "data-tab": t.id, "aria-selected": String(t.id === tab),
			onclick: () => { tab = t.id; show(cat); },
		}, t.label)));
		const declared = M.declaredGroups(fleet.catalogue, tab).map((g) => declaredSection(g));
		body.replaceChildren(
			el("p", { className: "cloud-cat-current" }, head),
			bar,
			...(tab === "core" ? [
				...(E.modelSection(S.engine, a, engineModels) ? [engineSection(E.modelSection(S.engine, a, engineModels))] : []),
				el("p", { className: "cloud-muted" }, `Prices as of ${cat.as_of} (${cat.origin})`),
				...sections.map((sec) => section(sec, M.checkedRow(sections, chosen))),
				el("p", { className: "cloud-muted" }, "The fleet's model catalogue (libs:model-catalogue, shared with Cloud Search): A0 Code, A1 Agentic, A2 Base, A3 Reasoning. Anthropic first, then providers by price. Prices are OpenRouter's, read at most daily."),
			] : declared),
		);
		body.querySelector("input[type=radio]:checked")?.closest("tr")?.scrollIntoView?.({ block: "center" });
	};
	try {
		show(await plugin().catalogue(chat.catalogue.sections, false));
		const live = await plugin().catalogue(chat.catalogue.sections, true);
		if (pg.node.isConnected) show(live);
		if (refresh) toast(`Prices: ${live.origin}, as of ${live.as_of}`);
	} catch (e) {
		body.replaceChildren(el("p", {}, `The catalogue could not be read: ${e?.message || e}`));
	}

	function engineSection(sec) {
		const cols = ["", "Model", "ID", "Group", "Context", "Input $/1M", "Output $/1M", "Note"];
		const mc = M.modelControl(a, chosen, S.health);
		const rows = sec.rows.map((r) => {
			// An agent mode is not a model (it is picked in Agent); a fixed-model agent runs its own.
			const st = r.group === "mode" ? { allowed: false, reason: "an agent mode: pick it in Agent" } : mc.pick ? { allowed: true, reason: "" } : { allowed: false, reason: `set by ${a.label}` };
			const radio = el("input", { type: "radio", name: "cloud-model", value: r.id, checked: chosen === r.id, disabled: !st.allowed, "aria-label": r.name });
			radio.addEventListener("change", () => choose(r));
			radio.addEventListener("click", (e) => e.stopPropagation());
			const name = el("div", {}, r.name, st.allowed ? null : el("div", { className: "cloud-cat-why" }, st.reason));
			return el("tr", { className: `${st.allowed ? "" : "dim"}${chosen === r.id ? " current" : ""}`, "data-model": r.id, onclick: st.allowed ? () => choose(r) : null },
				el("td", {}, radio), el("td", {}, name), el("td", {}, r.id), el("td", {}, r.group), el("td", {}, r.context), el("td", {}, r.input), el("td", {}, r.output), el("td", {}, r.note));
		});
		return el("section", { className: "cloud-cat-section", "data-section": sec.id },
			el("div", { className: "cloud-cat-head" }, el("strong", {}, sec.label), el("div", { className: "cloud-muted" }, sec.note)),
			el("div", { className: "cloud-cat-scroll" }, el("table", {}, el("thead", {}, el("tr", {}, cols.map((h) => el("th", {}, h)))), el("tbody", {}, rows))));
	}

	function declaredSection(g) {
		const rows = g.models.map((m) => {
			const st = onPick ? { allowed: !m.local, reason: m.local ? "phone / Ollama only" : "" } : M.rowState(a, { ...m, selectable: !m.local }, S.health);
			const radio = el("input", { type: "radio", name: "cloud-model", value: m.id, checked: chosen === m.id, disabled: !st.allowed, "aria-label": m.name });
			radio.addEventListener("change", () => choose(m));
			radio.addEventListener("click", (e) => e.stopPropagation());
			const name = el("div", {}, m.name, st.allowed ? null : el("div", { className: "cloud-cat-why" }, st.reason));
			return el("tr", { className: `${st.allowed ? "" : "dim"}${chosen === m.id ? " current" : ""}`, "data-model": m.id, onclick: st.allowed ? () => choose(m) : null },
				el("td", {}, radio), el("td", {}, name), el("td", {}, m.local ? "" : m.id), el("td", {}, m.sub));
		});
		return el("section", { className: "cloud-cat-section", "data-section": g.id },
			el("div", { className: "cloud-cat-head" }, el("strong", {}, g.label)),
			el("div", { className: "cloud-cat-scroll" }, el("table", {}, el("tbody", {}, rows))));
	}

	function section(sec, checked) {
		const cols = ["", "Provider", "Model", "OpenRouter ID", "Params", "License", "Input $/1M", "Output $/1M (Floor)"];
		const rows = sec.rows.map((r) => {
			const st = onPick ? { allowed: !!r.selectable, reason: r.selectable ? "" : "not on OpenRouter" } : M.rowState(a, r, S.health);
			const radio = el("input", { type: "radio", name: "cloud-model", value: r.id, checked: checked === `${sec.id}:${r.id}`, disabled: !st.allowed, "aria-label": `${r.provider} ${r.name}` });
			radio.addEventListener("change", () => choose(r));
			radio.addEventListener("click", (e) => e.stopPropagation()); // the row's own tap would pick twice
			const name = el("div", {}, r.name + (r.listed ? "" : " · not listed"), st.allowed ? null : el("div", { className: "cloud-cat-why" }, st.reason));
			return el("tr", { className: `${st.allowed ? "" : "dim"}${checked === `${sec.id}:${r.id}` ? " current" : ""}`, onclick: st.allowed ? () => choose(r) : null },
				el("td", {}, radio), el("td", {}, r.provider), el("td", {}, name), el("td", {}, r.id), el("td", {}, r.params), el("td", {}, r.license), el("td", {}, r.input), el("td", {}, r.output));
		});
		return el("section", { className: "cloud-cat-section", "data-section": sec.id },
			el("div", { className: "cloud-cat-head" }, el("strong", {}, `${sec.id} ${sec.label}`), sec.note ? el("div", { className: "cloud-muted" }, sec.note) : null),
			el("div", { className: "cloud-cat-scroll" }, el("table", {}, el("thead", {}, el("tr", {}, cols.map((h) => el("th", {}, h)))), el("tbody", {}, rows))));
	}
}

// ── send / stream ───────────────────────────────────────────────────────────

async function send() {
	const text = $input.value.trim();
	if ((!text && !S.pending.length) || S.streaming) return;
	const s = current();
	const a = agent();
	if (!a) return toast("No agent can be reached");
	if (!S.sessions.includes(s)) S.sessions.unshift(s);
	s.messages.push({ role: "user", content: text, attachments: S.pending.splice(0) });
	$input.value = "";
	$input.style.height = "auto";
	await stream(s, a);
}

async function retry() {
	const s = current();
	while (s.messages.length && s.messages[s.messages.length - 1].role === "assistant") s.messages.pop();
	if (!s.messages.length) return;
	await stream(s, agent());
}

function stream(s, a) {
	let req;
	try {
		req = M.buildRequest({ chat, fleet, storage: localStorage, agent: a, model: modelOf(a), effort: s.effort, mode: s.mode, session: s, reasoning: S.reasoning, health: S.health });
	} catch (e) {
		s.messages.push({ role: "assistant", content: "", error: String(e?.message || e) });
		persist();
		return drawMessages();
	}
	const reply = { role: "assistant", content: "", streaming: true, agent: a.label, model: M.modelControl(a, modelOf(a), S.health).label };
	s.messages.push(reply);
	s.updated = Date.now();
	persist();
	drawMessages();
	const node = $list.lastElementChild;
	const id = `${s.id}-${s.messages.length}`;
	S.streaming = id;
	$send.replaceChildren(icon("cancel"));
	const parser = M.sseParser();
	let sse = false;
	let status = 0;
	let whole = "";
	let frame = 0;
	const paint = () => {
		frame = 0;
		const body = node.querySelector(".cloud-msg-body");
		body.innerHTML = DOMPurify.sanitize(md.render(reply.content || "…"));
		$list.scrollTop = $list.scrollHeight;
	};
	return new Promise((resolve) => {
		// The end of the stream, on either road: ev is {type: "end"} or {type: "error", message}.
		const finish = (ev) => {
			if (ev.type === "error" && S.streaming === id) reply.error = ev.message;
			if (!sse && ev.type === "end") {
				const r = M.parseWhole(status, whole);
				if (r.error) reply.error = r.error;
				else reply.content = r.text;
				if (r.usage) reply.usage = r.usage;
			}
			if (parser.usage) reply.usage = parser.usage;
			reply.at = Date.now();
			if (parser.error) reply.error = parser.error;
			if (S.streaming !== id && !reply.content) reply.error = "stopped";
			delete reply.streaming;
			S.streaming = null;
			S.engineStreaming = null;
			$send.replaceChildren(icon("send"));
			s.updated = Date.now();
			persist();
			drawMessages();
			resolve();
		};
		// The direct road: this app's own gateway / OpenRouter request through the CloudChat plugin (kept, and the fallback of the engine road).
		const direct = () => {
			delete reply.via;
			plugin().stream({ ...req, id }, (ev) => {
				if (ev.type === "head") {
					status = ev.status;
					sse = /event-stream/.test(ev.content_type) && status < 400;
				} else if (ev.type === "data") {
					if (sse) {
						for (const d of parser.push(ev.text)) reply.content += d;
						if (!frame) frame = requestAnimationFrame(paint);
					} else whole += ev.text;
				} else finish(ev);
			});
		};
		// The engine road (#1855 P2): Cloud SuperIntel performs the request with its own backends and streams it back; no key is here.
		if (!(E.engineUsable(S.engine, a) && E.bridge())) return direct();
		let ereq;
		try {
			ereq = E.engineRequest(id, a, req);
		} catch {
			return direct();
		}
		reply.via = "Cloud SuperIntel";
		S.engineStreaming = id;
		sse = true; // the engine hands over the answer's text, never an SSE body or a whole-answer JSON
		E.bridge().chat(ereq, (ev) => {
			if (ev.type === "chunk") {
				reply.content += ev.text || "";
				if (!frame) frame = requestAnimationFrame(paint);
			} else if (ev.type === "done") {
				try {
					const u = ev.usage ? JSON.parse(ev.usage) : null;
					if (u && (u.prompt || u.completion)) reply.usage = M.usageOf({ prompt_tokens: u.prompt, completion_tokens: u.completion, cost: u.cost });
				} catch {
					// no usage is better than a wrong one
				}
				finish({ type: "end" });
			} else if (ev.type === "absent" && !reply.content) {
				// SuperIntel went away between the list and the send: nothing was sent, the direct road takes over and the hint shows.
				S.engine = { si: false, providers: [] };
				S.engineStreaming = null;
				sse = false;
				direct();
			} else finish({ type: "error", message: ev.type === "refused" ? `Cloud SuperIntel: ${ev.reason}` : ev.reason || "failed" });
		}).catch((e) => finish({ type: "error", message: String(e?.message || e) }));
	});
}

function stop() {
	const id = S.streaming;
	S.streaming = null;
	if (!id) return;
	if (S.engineStreaming === id) E.bridge()?.cancel(id)?.catch?.(() => {});
	else plugin().cancel(id).catch(() => {});
}

// ── the drawer items this page supplies (the hamburger) ─────────────────────

function sessionRow(s, after) {
	return el("div", { className: "cloud-drawer-session" },
		el("button", { className: "cloud-drawer-item", onclick: () => { closeOverlay(); S.current = s; draw(); } }, icon(s.pinned ? "pin" : "chat_bubble"), el("span", {}, M.title(s))),
		el("button", { className: "cloud-icon-btn", title: s.pinned ? "Unpin" : "Pin", onclick: () => { s.pinned = !s.pinned; persist(); after(); } }, icon(s.pinned ? "pin-off" : "pin")));
}

function sessionList(filter, title) {
	const box = el("div", { className: "cloud-drawer-sessions" });
	const fill = () => {
		const now = Date.now();
		const all = S.sessions.filter((s) => s.messages.length);
		if (filter === "pinned") {
			const pinned = all.filter((s) => s.pinned);
			box.replaceChildren(el("div", { className: "cloud-drawer-group" }, "Pinned"), ...(pinned.length ? pinned.map((s) => sessionRow(s, fill)) : [el("p", { className: "cloud-muted" }, "Nothing pinned.")]));
			return;
		}
		const g = M.groupSessions(all, now);
		const groups = [["Pinned", g.pinned], ["Today", g.today], ["Previous 7 days", g.week], ["Older", g.older]].filter(([, l]) => l.length);
		box.replaceChildren(...(title ? [el("div", { className: "cloud-drawer-group" }, title)] : []),
			...(groups.length ? groups.flatMap(([name, l]) => [el("div", { className: "cloud-drawer-group" }, name), ...l.map((s) => sessionRow(s, fill))]) : [el("p", { className: "cloud-muted" }, "No chats yet.")]));
	};
	fill();
	return box;
}

/** The Chat page's drawer: its menu (model.js::chatMenu) bound to actions, then its chat list. */
export function chatDrawer(drawer) {
	const s = current();
	const actions = {
		new: () => { S.current = fresh(); draw(); },
		history: () => drawer("Chats", [], sessionList("all")),
		search: () => searchChats(drawer),
		pinned: () => drawer("Pinned", [], sessionList("pinned")),
		export: () => exportChat(),
		rename: async () => {
			const { default: prompt } = await import("dialogs/prompt");
			const t = await prompt("Rename this chat", M.title(s), "text");
			if (t) { s.title = t; persist(); draw(); }
		},
		clear: () => {
			S.sessions = S.sessions.filter((x) => x !== s);
			S.current = null;
			persist();
			S.current = S.sessions[0] || fresh();
			draw();
		},
	};
	return {
		items: M.chatMenu().map((it) => ({ ...it, run: actions[it.id], disabled: (it.id === "export" || it.id === "rename" || it.id === "clear") && !s.messages.length })),
		extra: [sessionList("all", "Chats")],
	};
}

function searchChats(drawer) {
	const results = el("div", { className: "cloud-drawer-sessions" });
	const input = el("input", { type: "search", placeholder: "Search chats", className: "cloud-drawer-search" });
	const fill = () => results.replaceChildren(...M.searchSessions(S.sessions.filter((x) => x.messages.length), input.value).map((s) => sessionRow(s, fill)));
	input.addEventListener("input", fill);
	drawer("Search chats", [], input, results);
	fill();
	input.focus();
}

async function exportChat() {
	const s = current();
	const text = M.exportMarkdown(s, agent()?.label);
	try {
		// Acode's own new-file tab: the export opens in the editor, to save anywhere Acode can write.
		const { default: EditorFile } = await import("lib/editorFile");
		new EditorFile(`${M.title(s).replace(/[^\w.-]+/g, "-").slice(0, 40) || "chat"}.md`, { text, isUnsaved: true, render: true });
		S.chrome.showTab("editor");
	} catch (e) {
		toast(String(e?.message || e));
	}
}

export function chatSettingsChanged() {
	draw();
}
