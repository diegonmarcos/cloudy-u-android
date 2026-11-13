// cloud-code Profile & Config — the top-right page of every cloud panel. Account (the fleet
// Account, Cloud Account: reachable or not, holds a key or not — never the key), the OpenRouter token (handed straight to the CloudChat plugin, which
// stores it encrypted; this page only ever sees its mask), the AI defaults, the storage grant
// that used to be Home's Configs card, and About.
import toast from "components/toast";
import nav from "./nav.json";
import targets from "./targets.gen.json";
import { el, icon } from "./dom";
import { page, sheet, option, toggleRow } from "./chrome";
import * as M from "./chat/model";
import { modelPage, chatSettingsChanged } from "./chat/view";

// The permission modes and effort levels are the shared design's (libs:ai ai-design.json), as in chat/view.js.
const chat = { ...nav.chat, ...targets.chat.ai, permission: { ...nav.chat.permission, ...targets.chat.design.permission }, effort: targets.chat.design.effort };
const plugin = () => window.CloudChat;

function section(title, ...rows) {
	return el("div", { className: "cloud-section" }, el("h3", {}, title), ...rows);
}

function row(label, value, ...actions) {
	return el("div", { className: "cloud-kv" }, el("span", { className: "cloud-kv-k" }, label), el("span", { className: "cloud-kv-v" }, value), ...actions);
}

function btn(label, iconName, onclick) {
	return el("button", { className: "cloud-ctl", onclick }, icon(iconName), el("span", {}, label));
}

// #1697 Configs is {own page} | Agents, Perms, Logcat, X-Ray App, About: this page is the app's own part; the five fleet tabs open the
// native fleet Configs screen (libs:ui-nav-bars FleetConfigsActivity, own package, not exported) on the tapped tab.
const FLEET_PKG = "com.diegonmarcos.code";
const FLEET_ACTIVITY = "com.diegonmarcos.superapp.fleetconfigs.FleetConfigsActivity";
export const FLEET_TAIL = [["agents", "Agents"], ["perms", "Perms"], ["logcat", "Logcat"], ["xray", "X-Ray App"], ["about", "About"]];

function openFleetTab(id) {
	try {
		system.launchApp(FLEET_PKG, FLEET_ACTIVITY, { fleet_configs_page: id }, () => {}, () => toast("Fleet Configs is not available in this build"));
	} catch (e) {
		toast("Fleet Configs is not available in this build");
	}
}

function fleetStrip() {
	const bar = el("div", { className: "cloud-fleet-strip", style: "display:flex;gap:6px;overflow-x:auto;padding:6px 0 10px;white-space:nowrap" },
		el("button", { className: "cloud-ctl", disabled: true, "aria-current": "page" }, el("span", {}, "Profile & Config")),
		el("span", { "aria-hidden": "true", style: "align-self:center;opacity:.6" }, "|"));
	for (const [id, label] of FLEET_TAIL) bar.append(el("button", { className: "cloud-ctl", onclick: () => openFleetTab(id) }, el("span", {}, label)));
	return bar;
}

export function openProfile() {
	const pg = page("Profile & Config", chatSettingsChanged);
	const account = el("div");
	const token = el("div");
	const defaults = el("div");
	const storage = el("div");
	pg.body.append(fleetStrip(), account, token, defaults, storage, about());
	const refreshToken = async () => {
		let st = null;
		try {
			st = await plugin().tokenStatus();
		} catch (e) {
			st = { error: String(e?.message || e) };
		}
		account.replaceChildren(accountSection(st));
		token.replaceChildren(tokenSection(st, refreshToken));
	};
	refreshToken();
	const drawDefaults = () => defaults.replaceChildren(defaultsSection(drawDefaults));
	drawDefaults();
	storage.replaceChildren(storageSection());
	return pg;
}

function accountSection(st) {
	const a = targets.chat.account;
	const acc = st?.account || {};
	const key = acc.connected ? (acc.key_present ? `holds an OpenRouter key${acc.key_hint ? ` (…${acc.key_hint})` : ""}` : "holds no OpenRouter key") : "—";
	return section("Account",
		row("Fleet Account", st?.error ? st.error : acc.connected ? "connected (Cloud Account)" : "not connected",
			btn("Open Cloud Account", "account_box", () => system.launchApp(a.package, a.activity, null, () => {}, () => toast(`${a.id} is not installed (${a.package})`)))),
		row("Its OpenRouter key", key),
		el("p", { className: "cloud-muted" }, "AI is paid with the token below. The fleet Account's key stays in its own app: by fleet rule only its engines read it."));
}

function tokenSection(st, again) {
	const input = el("input", { type: "password", placeholder: "Paste an OpenRouter token", autocomplete: "off", className: "cloud-token-input" });
	const save = btn("Save", "save", async () => {
		const v = input.value;
		input.value = "";
		if (!v.trim()) return;
		try { await plugin().tokenSet(v); toast("Token saved, encrypted on this phone"); again(); } catch (e) { toast(String(e?.message || e)); }
	});
	const test = btn("Test", "check_circle", async () => {
		try {
			const r = await plugin().tokenTest();
			toast(r.ok ? `OpenRouter accepts it${r.usage != null ? ` · usage $${r.usage}` : ""}${r.limit != null ? ` / $${r.limit}` : ""}` : `Rejected (HTTP ${r.status})`);
		} catch (e) { toast(String(e?.message || e)); }
	});
	const remove = btn("Remove", "delete", async () => {
		try { await plugin().tokenClear(); again(); } catch (e) { toast(String(e?.message || e)); }
	});
	return section("OpenRouter token",
		row("Stored", st?.set ? st.masked : "not set — OpenRouter chats will fail", test, remove),
		el("div", { className: "cloud-kv" }, input, save),
		el("p", { className: "cloud-muted" }, "Kept in EncryptedSharedPreferences (Android Keystore). This page never reads it back: only its last four characters are shown."));
}

function defaultsSection(again) {
	const s = M.loadSettings(localStorage, chat);
	const fleet = targets.chat;
	const agents = M.resolveAgents(chat, fleet, undefined);
	const set = (k, v) => { M.saveSettings(localStorage, { ...s, [k]: v }); again(); };
	const agentLabel = agents.find((a) => a.id === s.agent)?.label || s.agent;
	return section("AI defaults (new conversations)",
		row("Agent", agentLabel, btn("Change", "brain", () => sheet("Default agent", agents.map((a) => option(a.label, { checked: a.id === s.agent, disabled: !a.available, sub: a.reason, run: () => set("agent", a.id) }))))),
		row("Model", s.model, btn("Change", "wand-sparkles", () => modelPage(false, (id) => set("model", id)))),
		row("Effort", chat.effort.levels.find((l) => l.id === s.effort)?.label || s.effort, btn("Change", "tune", () => sheet("Default effort", chat.effort.levels.map((l) => option(l.label, { checked: l.id === s.effort, run: () => set("effort", l.id) }))))),
		row("Permission mode", chat.permission.modes.find((m) => m.id === s.mode)?.label || s.mode, btn("Change", "zap", () => sheet("Default permission mode", chat.permission.modes.map((m) => option(m.label, { checked: m.id === s.mode, run: () => set("mode", m.id) }))))),
		row("MCP", "per agent", btn("Edit", "extension", () => sheet("MCP defaults", agents.filter((a) => a.decl).map((a) => option(a.label, { run: () => mcpFor(a) }))))),
		row("Agent gateway", fleet.gateway),
	);
}

function mcpFor(a) {
	const { source, servers } = M.mcpServers(a, null, targets.chat.mcp_fleet);
	const state = M.mcpState(localStorage, a.id, servers);
	sheet(`MCP · ${a.label}`, servers.map((sv) => toggleRow(sv.name, state[sv.name], (on) => M.mcpToggle(localStorage, a.id, sv.name, on))), source === "fleet" ? "From the fleet MCP config." : null);
}

// #575 the shared store needs all-files access, granted per app (was Home's Configs card).
function storageSection() {
	const status = el("span", {}, "Checking …");
	const refresh = () => system.isExternalStorageManager(
		(granted) => { status.textContent = granted ? "granted" : "not granted"; },
		() => { status.textContent = "not granted"; });
	refresh();
	return section("Storage",
		row("All-files access (Backlog, Git)", status, btn("Grant", "folder_open", () => system.manageAllFiles(refresh, (e) => toast(String(e))))));
}

function about() {
	const v = String(window.BuildInfo?.version || "");
	const sha = /sha-([0-9a-f]+)/.exec(v)?.[1] || "unknown";
	return section("About",
		row("Cloud Code", v.replace(/\s*\(sha-[0-9a-f]+\)/, "") || "unknown"),
		row("Commit", sha),
		row("Upstream", "Acode (vendored, see CLOUD-CODE.md)"),
		...chat.about.licences.map((l) => row(l.name, l.licence)),
		row("Source", chat.about.source));
}
