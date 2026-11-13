// cloud-code — the 7-tab bottom nav over Acode (#562).
//
// WHY THE FLEET'S BARS ARE DRAWN IN THE PAGE AND NOT HOSTED NATIVELY. #565 made ab_cloud-libs-shared
// libs:bottomnav the one bottom nav for NATIVE apps, and #1154 moved the fleet to the shared Compose
// bars (ui-nav-bars, ui-nav-bars, ui-nav-bars, theme-background). This app is a
// Cordova web shell: its whole UI is DOM inside one WebView, and the Android project is GENERATED at
// build time (no committed gradle module, no Compose plugin; see build-extras.gradle), so hosting the
// WebView inside a Compose Activity would mean replacing Cordova's activity in a generated project and
// putting a second window over the editor. #1158 therefore draws the SAME bars in the page, from the
// same declaration and the libs' own numbers (nav.json::bars, held equal to the Kotlin constants by the
// nav-shape guard), in Acode's own UI layer (its icon font, fileSystem / markdown / browser /
// openFolder). Labels are never truncated: the bottom bar shows the name of the selected tab only.
//
// Mounted from main.js::loadApp with ONE call (search "cloud-code seam").
// Editor = Acode untouched: selecting it only hides these panels.
//
// App chrome (#1158, the fleet's shared navigation of #1154): bars-dom.js draws the first row (plain
// hamburger, title island with the hierarchy dropdown, AI / Profile / Configs), the second row (the
// tab's sub-pages) and the bottom bar from nav.json::bars, on the shared page gradient. The hamburger
// opens a drawer of the ACTIVE page's own items (each page registers its provider in MENUS;
// tabs.js::menuFor picks the active one). AI hands over to the SuperApp, Profile to Cloud Account,
// Configs opens Profile & Config (this app's settings).

import "./cloud.scss";
import fsOperation from "fileSystem";
import toast from "components/toast";
import openFolder from "lib/openFolder";
import openFile from "lib/openFile";
import browser from "plugins/browser";
import markdownIt from "markdown-it";
import DOMPurify from "dompurify";
import FileTree from "components/fileTree";
import { renderMarkdown } from "pages/markdownPreview/renderer";
import nav from "./nav.json";
import targets from "./targets.gen.json";
import { buildTabs, menuFor, select } from "./tabs";
import { mountBars } from "./bars-dom";
import { pageOfFile } from "./bars";
import { listAgents, listRepos } from "./seams";
import { el } from "./dom";
import { bindTitle, drawer, topBar } from "./chrome";
import { openProfile } from "./profile";
import { chatDrawer, renderChat } from "./chat/view";

const LAUNCH_VIEWS = ["myterminal"];

function stored(key, fallback) {
	return localStorage.getItem(`cloud-code.${key}`) || fallback;
}

function store(key, value) {
	localStorage.setItem(`cloud-code.${key}`, value);
}

// The page's drawer items, registered by each renderer for its own tab (the hamburger reads the
// ACTIVE tab's provider through tabs.js::menuFor). A page that registers none shows an empty drawer.
const MENUS = {};

// Sub-pages (the second row): the page each tab shows, and the loader its renderer registered for a pick.
const LOADERS = {};
const PAGE = {};

function pageFileOf(tabId, fallback) {
	const t = tabs.find((x) => x.id === tabId);
	const p = t && t.pages.find((x) => x.id === PAGE[tabId]);
	return p ? p.file : fallback;
}

// A page was loaded in place (a dist link followed): the second row follows it.
function selectedPage(tabId, pageId) {
	PAGE[tabId] = pageId;
	if (bars && state.active === tabId) bars.setPage(pageId);
}

function pickPage(tabId, pageId) {
	if (tabId !== state.active) return showTab(tabId, pageId);
	const t = tabs.find((x) => x.id === tabId);
	const p = t.pages.find((x) => x.id === pageId);
	PAGE[tabId] = pageId;
	bars.setPage(pageId);
	if (p && LOADERS[tabId]) LOADERS[tabId](p.file);
}

const chrome = {
	bar: (title, subtitle) => topBar(title, { subtitle }),
	showTab: (id) => showTab(id),
};

function openMenu() {
	const tab = tabs.find((t) => t.id === state.active);
	const got = menuFor(state, tabs, MENUS);
	drawer(tab ? tab.label : "Menu", got.items, ...(got.extra || []));
}

function panelHeader(title) {
	return chrome.bar(title, null);
}

// ── #575 THE ONE shared repo store ──────────────────────────────────────────
// cloud-drive declares the root (its build.json::storage.shared_root); the build
// copied it into targets.gen.json RELATIVE, and only the device knows where
// shared storage is. Every on-device path this app touches is composed here.
function sharedRoot() {
	return `${cordova.file.externalRootDirectory}${targets.shared_root}/`;
}

// #676 clones live in the store's ONE git folder (#606/#608: cloud-drive's
// storage.git_subdir, copied by the same resolver): composing a git-tree path
// at the store's ROOT is a second tree, the bug this function closes.
function gitPath(relative) {
	return `${sharedRoot()}${targets.git_subdir}/${relative}`;
}

// ── Backlog: render the ONE existing source, as-is ─────────────────────────
// Backlog and Agents are two entry files of the same backlog dist, read from
// the same folder (one localStorage key), through this one renderer.
async function renderBacklogView(panel, title, entry, { extra = [], docs = false } = {}) {
	const md = markdownIt({ html: false, linkify: true });
	let dir = stored("backlog.dir", gitPath(nav.backlog.source_dir));
	let current = entry;
	const body = el("div", { className: "cloud-md" });

	async function load(file) {
		body.textContent = `Loading ${file} …`;
		current = file;
		selectedPage(panel.dataset.tab, pageOfFile(tabs.find((t) => t.id === panel.dataset.tab), file));
		try {
			const text = await fsOperation(dir, file).readFile("utf8");
			body.innerHTML = DOMPurify.sanitize(md.render(text));
		} catch (err) {
			// #575 the store is shared storage: on Android 11+ this app reads it only
			// with all-files access, which the system grants per app, on a toggle.
			const grant = el("button", {
				className: "cloud-row",
				onclick: () => system.manageAllFiles(() => load(file), (e) => toast(String(e))),
			}, el("span", { className: "icon folder_open" }), "Allow access to all files, then retry");
			body.replaceChildren(
				el("p", {}, `Cannot read ${dir}${file}`),
				el("p", { className: "cloud-muted" }, String(err?.message || err)),
				el("p", { className: "cloud-muted" }, "The backlog repository is private: clone it with Cloud Drive (Sync ▸ Git ▸ cloud-me-pvt-tasks ▸ Clone / open) and it appears here through the shared store. Or point this tab at another 1.1.Product-Backlog/dist/ folder."),
				grant,
			);
		}
	}

	// The dist files link to each other ("Views: per project · per status …"):
	// follow those links in place instead of re-deriving a view list here.
	body.addEventListener("click", (e) => {
		const a = e.target.closest("a");
		if (!a) return;
		const href = a.getAttribute("href") || "";
		e.preventDefault();
		if (/^[\w./-]+\.md(#.*)?$/.test(href)) load(href.replace(/#.*$/, ""));
		else if (/^https?:/.test(href)) browser.open(href);
	});

	const change = el("button", {
		className: "icon folder_open",
		title: "Backlog folder",
		onclick: async () => {
			const { default: prompt } = await import("dialogs/prompt");
			const next = await prompt("Backlog dist folder (URL)", dir, "text");
			if (!next) return;
			dir = next.endsWith("/") ? next : `${next}/`;
			store("backlog.dir", dir);
			load(entry);
		},
	});
	// #575 WRITE goes through the same store: the file on screen opens in Acode's
	// editor (saved back in place), and the whole repository opens as a folder in
	// the sidebar. cloud-drive's git manager commits and pushes what is edited.
	const repoName = nav.backlog.source_dir.split("/")[0];
	const edit = el("button", {
		className: "icon edit",
		title: "Edit this file",
		onclick: async () => {
			await openFile(`${dir}${current}`, { render: true });
			showTab("editor");
		},
	});
	const repo = el("button", {
		className: "icon git",
		title: "Open the repository",
		onclick: () => {
			openFolder(gitPath(repoName), { name: repoName });
			showTab("editor");
		},
	});
	// #1589 Docs child menu: a LEFT collapsible file tree of the cloud-me-pvt-tasks clone
	// (the Product-Backlog repo). It is NOT the editor's sidebar — a secondary tree
	// scoped to this one repo (components/fileTree). Clicking a .md or .html renders
	// it in place through the shared preview (pages/markdownPreview renderer + html),
	// so the backlog repo is served and read inside the tab. The root is declared in
	// nav.docs.root (RELATIVE to the store git folder) and overridable per device.
	let docsDir = stored("docs.dir", gitPath(nav.docs.root));
	let docsTree = null;
	const buildDocsPane = () => {
		const treeBox = el("div", { className: "cloud-docs-treebox" });
		const aside = el("aside", { className: "cloud-docs-tree" });
		const toggle = el("button", {
			className: "cloud-docs-toggle",
			onclick: () => aside.classList.toggle("collapsed"),
		}, el("span", { className: "icon chevron_right cloud-docs-caret" }), el("span", { className: "cloud-docs-title" }, nav.docs.label || "Docs"));
		aside.append(toggle, treeBox);

		const openDoc = async (url, name) => {
			body.textContent = `Loading ${name} …`;
			try {
				const text = await fsOperation(url).readFile("utf8");
				if (/\.html?$/i.test(name)) {
					body.innerHTML = DOMPurify.sanitize(text);
				} else if (/\.md$/i.test(name)) {
					const { html } = await renderMarkdown(text, { uri: url });
					body.innerHTML = DOMPurify.sanitize(html);
				} else {
					await openFile(url, { render: true });
					showTab("editor");
				}
			} catch (err) {
				body.replaceChildren(
					el("p", {}, `Cannot read ${name}`),
					el("p", { className: "cloud-muted" }, String(err?.message || err)),
					el("button", {
						className: "cloud-row",
						onclick: () => system.manageAllFiles(() => openDoc(url, name), (e) => toast(String(e))),
					}, el("span", { className: "icon folder_open" }), "Allow access to all files, then retry"),
				);
			}
		};

		const loadTree = () => {
			if (docsTree) docsTree.destroy();
			docsTree = new FileTree(treeBox, {
				getEntries: (dirUrl) => fsOperation(dirUrl).lsDir(),
				onFileClick: openDoc,
			});
			docsTree.load(docsDir).catch((err) => {
				treeBox.replaceChildren(el("p", { className: "cloud-muted" }, `cloud-docs not found at ${docsDir.replace(/^file:\/\//, "")} — set its folder from the Backlog menu. (${err?.message || err})`));
			});
		};
		loadTree();
		aside._reload = loadTree;
		aside._setDir = (next) => { docsDir = next; loadTree(); };
		return aside;
	};

	const asidePane = docs ? buildDocsPane() : null;
	if (asidePane) panel.replaceChildren(panelHeader(title), el("div", { className: "cloud-backlog-split" }, asidePane, body), ...extra);
	else panel.replaceChildren(panelHeader(title), body, ...extra);

	const items = [
		{ id: "reload", label: "Reload", icon: "refresh", run: () => load(current) },
		{ id: "entry", label: `Back to ${entry}`, icon: "home", run: () => load(entry) },
		{ id: "edit", label: "Edit this file", icon: "edit", run: () => edit.click() },
		{ id: "repo", label: "Open the repository", icon: "git", run: () => repo.click() },
		{ id: "folder", label: "Backlog folder", icon: "folder_open", run: () => change.click() },
	];
	if (asidePane) {
		items.push({ id: "docs-reload", label: "Reload docs tree", icon: "refresh", run: () => asidePane._reload() });
		items.push({ id: "docs-folder", label: "cloud-docs folder", icon: "folder_open", run: async () => {
			const { default: prompt } = await import("dialogs/prompt");
			const next = await prompt("cloud-docs repo folder (URL)", docsDir, "text");
			if (!next) return;
			const url = next.endsWith("/") ? next : `${next}/`;
			store("docs.dir", url);
			asidePane._setDir(url);
		} });
	}
	MENUS[panel.dataset.tab] = () => ({ items });
	LOADERS[panel.dataset.tab] = load;
	await load(pageFileOf(panel.dataset.tab, entry));
}

function renderBacklog(panel) {
	return renderBacklogView(panel, "Backlog", nav.backlog.entry, { docs: true });
}

// Create `<parent>/<name>` when missing (the Git volumes). Never throws: false = could not.
async function ensureDir(parentUrl, name) {
	try {
		if (await fsOperation(parentUrl, name).exists()) return true;
		await fsOperation(parentUrl).createDirectory(name);
		return true;
	} catch {
		return false;
	}
}

// ── Git (was Repos): two volumes side by side, repos listed under each ──────
// nav.repos.volumes: Cloud-Shared-Store/Git (the store's git folder) and Emulated/Git (the primary
// emulated volume). Each is one column; a repo is an immediate subdirectory holding .git.
async function volumeRoot(v) {
	if (v.id === "emulated") {
		const base = cordova.file.externalRootDirectory;
		await ensureDir(base, v.subdir);
		return `${base}${v.subdir}/`;
	}
	// Shared store: the store's real home first (readable under the shared uid), else the legacy
	// mirror cloud-drive still clones into for this app (sharedRoot()/git_subdir).
	const home = `${v.store_home}/${targets.git_subdir}/`;
	try {
		await fsOperation(home).lsDir();
		return home;
	} catch {
		await ensureDir(sharedRoot(), targets.git_subdir);
		return gitPath(nav.repos.root);
	}
}

function repoRow(r, panel) {
	return el("button", {
		className: "cloud-row cloud-repo",
		title: r.url,
		onclick: () => {
			openFolder(r.url, { name: r.name });
			showTab("editor");
		},
	}, el("span", { className: "icon git" }), el("span", { className: "cloud-repo-name" }, r.name), el("span", { className: "cloud-repo-info" }, r.branch || ""));
}

async function fillVolume(v, body, count, panel) {
	try {
		const root = await volumeRoot(v);
		const { items } = await listRepos(root);
		count.textContent = String(items.length);
		body.replaceChildren(
			el("p", { className: "cloud-muted cloud-vol-path" }, root.replace(/^file:\/\//, "")),
			...(items.length ? items.map((r) => repoRow(r, panel)) : [el("p", { className: "cloud-muted" }, "No repos")]),
		);
	} catch (err) {
		count.textContent = "!";
		body.replaceChildren(
			el("p", { className: "cloud-muted" }, `Not available: ${err?.message || err}`),
			el("button", {
				className: "cloud-row",
				onclick: () => system.manageAllFiles(() => renderGit(panel), (e) => toast(String(e))),
			}, el("span", { className: "icon folder_open" }), "Grant all-files access, then retry"),
		);
	}
}

async function renderGit(panel) {
	const cols = nav.repos.volumes.map((v) => {
		const count = el("span", { className: "cloud-vol-count" }, "…");
		const body = el("div", { className: "cloud-list" }, "Scanning …");
		fillVolume(v, body, count, panel);
		return el("section", { className: "cloud-vol", "data-volume": v.id }, el("h3", { className: "cloud-vol-head" }, el("span", {}, v.label), count), body);
	});
	panel.replaceChildren(panelHeader("Git"), el("div", { className: "cloud-vols" }, ...cols));
	MENUS[panel.dataset.tab] = () => ({ items: [
		{ id: "rescan", label: "Rescan", icon: "refresh", run: () => renderGit(panel) },
	] });
}

// ── Agents: the backlog's own agent views, plus the live seam ──────────────
// Assignment, model and batch come from the backlog dist (one source with the
// Backlog tab); running status and token use are the seam's, stubbed for now.
async function renderAgents(panel) {
	const { items, note } = await listAgents();
	const live = items.length
		? el("div", { className: "cloud-list" }, items.map((a) => el("div", { className: "cloud-row" }, a.name)))
		: el("p", { className: "cloud-muted" }, note);
	await renderBacklogView(panel, "Agents", nav.agents.entry, { extra: [live] });
}

// ── AI (was Chat, replaced Home): src/cloud/chat ────────────────────────────
// The Home tiles duplicated the bar; its Configs card (the all-files grant) is in Profile & Config.
async function renderChatTab(panel) {
	MENUS[panel.dataset.tab] = () => chatDrawer(drawer);
	await renderChat(panel, chrome);
}

// ── Browser: local pages in Acode's own browser plugin ─────────────────────
async function renderBrowser(panel) {
	const input = el("input", { type: "url", value: stored("browser.last", nav.browser.presets[0].url) });
	const open = (url) => {
		store("browser.last", url);
		browser.open(url);
	};
	MENUS[panel.dataset.tab] = () => ({ items: nav.browser.presets.map((p, i) => ({ id: `preset${i}`, label: p.label, icon: "public", run: () => open(p.url) })) });
	panel.replaceChildren(
		panelHeader("Browser"),
		el("div", { className: "cloud-row" }, input, el("button", { className: "icon open_in_browser", onclick: () => open(input.value) })),
		el("div", { className: "cloud-list" },
			nav.browser.presets.map((p) => el("button", { className: "cloud-row", onclick: () => open(p.url) }, el("span", { className: "icon public" }), p.label)),
		),
	);
}

// ── MyTerminal: link out to the fleet app nav.json names ───────────────────
function launchMyTerminal() {
	const t = targets.myterminal;
	system.launchApp(t.package, t.activity, null, () => {}, () =>
		toast(`${t.id} is not installed (${t.package})`),
	);
}

const VIEWS = {
	editor: null,
	backlog: renderBacklog,
	git: renderGit,
	chat: renderChatTab,
	agents: renderAgents,
	browser: renderBrowser,
	myterminal: null,
};

const LAUNCH = { myterminal: launchMyTerminal };

let tabs = [];
let state = { active: nav.default_tab, showPanel: null };
const panels = {};
let bars = null;

function showTab(id, pageId) {
	const tab = tabs.find((t) => t.id === id || (t.aliases || []).includes(id));
	const next = select(state, tab, LAUNCH_VIEWS);
	if (next.launch) {
		LAUNCH[tab.view]();
		return;
	}
	state = next;
	// undefined keeps the page the tab last showed; null (the dropdown's bottom page) goes to its first.
	if (pageId !== undefined) PAGE[id] = pageId;
	if (PAGE[id] == null && tab.pages.length) PAGE[id] = tab.pages[0].id;
	bars.setTab(tab, PAGE[id]);
	for (const [tid, p] of Object.entries(panels)) p.hidden = tid !== state.showPanel;
	// The editor stays mounted under a panel (its state is kept) but must not paint through it.
	document.body.classList.toggle("cloud-panel-open", !!state.showPanel);
	if (state.showPanel) {
		const view = VIEWS[tab.view];
		view(panels[tab.id]).catch((err) => toast(String(err?.message || err)));
	}
}

// The first row's right-hand icons. AI and Profile hand over to fleet apps (resolved like MyTerminal, from the
// fleet's own declarations); a missing app is a toast, never a crash.
function launchApp(t) {
	system.launchApp(t.package, t.activity, null, () => {}, () => toast(`${t.id} is not installed (${t.package})`));
}

export default function mount() {
	tabs = buildTabs(nav, Object.keys(VIEWS));
	document.body.classList.add("cloud-nav");
	bars = mountBars({
		nav,
		tabs,
		on: {
			onTab: (id, page) => showTab(id, page),
			onPage: (id, page) => pickPage(id, page),
			onMenu: openMenu,
			onAi: () => launchApp(targets.superapp),
			onProfile: () => launchApp(targets.chat.account),
			onConfigs: openProfile,
		},
	});
	bindTitle((text) => bars.setTitle(text));
	for (const t of tabs) {
		if (VIEWS[t.view]) {
			panels[t.id] = el("section", { className: "cloud-panel", "data-tab": t.id });
			panels[t.id].hidden = true;
			document.body.append(panels[t.id]);
		}
	}
	showTab(nav.default_tab);
}
