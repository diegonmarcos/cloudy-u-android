// cloud-code — the IDE shell over Acode: five bottom pages (Workspace, Editor, Agent, Changes, Run) and the Backlog Parent Page
// in the first row's dropdown (#562, #1855 P3).
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
import { buildTabs, menuFor, resolveTarget, select, viewOf } from "./tabs";
import { mountBars } from "./bars-dom";
import { pageOfFile } from "./bars";
import { listAgents, listRepos } from "./seams";
import { el } from "./dom";
import { loadIndex, ageText, firstExisting, notOnDevice } from "./backlog-index";
import { bridge, driveList, driveStatus, urlFromDrive, columnsFor, parentOf, requestClone, filterRepos, searchFiles, changeRows, handOffToDrive, driveChanges, driveDiff, groupChanges, changeTotals, diffLines, STATUS_LABEL, requestCommit, requestPush } from "./workspace";
import { bindTitle, drawer, topBar } from "./chrome";
import { openProfile } from "./profile";
import { chatDrawer, renderChat, setAgentHost, startFromTicket, runDebugFlow } from "./chat/view";
import { locateTicket } from "./agent/context";
import { resolveInRoot } from "./agent/edits";
import { getLspDiagnostics } from "cm/lsp/diagnostics";

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
	const view = viewOf(t, pageId);
	// A launch page (MyTerminal) starts the other app and keeps the page shown.
	if (LAUNCH_VIEWS.includes(view)) return LAUNCH[view]();
	const was = viewOf(t, PAGE[tabId]);
	PAGE[tabId] = pageId;
	bars.setPage(pageId);
	// A sub-page with its own view (Agent: Chat | Batches | Models) re-renders the panel; the same view loads its file in place.
	if (view !== was) return showTab(tabId, pageId);
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

// #1855 F3/F4 every volume the Git tab lists (nav.repos.volumes), in order: the store's real home,
// its legacy mirror (gitPath), Emulated/Git. A repo cloned on any of them is found; a path the user
// set in the menu (localStorage) still wins.
function repoCandidates(relative) {
	const out = [];
	for (const v of nav.repos.volumes) {
		if (v.id === "emulated") out.push(`${cordova.file.externalRootDirectory}${v.subdir}/${relative}`);
		else out.push(`${v.store_home}/${targets.git_subdir}/${relative}`, gitPath(relative));
	}
	return out;
}

// #1855 P1 Cloud Drive owns git: a folder set in the menu wins, then Drive's findRepo (any volume, incl. external),
// then this app's own volume probe (Drive absent or not holding the clone).
async function repoUrl(key, relative) {
	const set = localStorage.getItem(`cloud-code.${key}`);
	if (set) return set;
	const fromDrive = await urlFromDrive(bridge(), relative);
	if (fromDrive) return fromDrive;
	return firstExisting(repoCandidates(relative), (u) => fsOperation(u).exists(), gitPath(relative));
}

// #1855 P1 the repo is nowhere on this device: hand it to Cloud Drive's clone flow (Sync, Git, the dialog prefilled).
function cloneWithDriveButton(repoName) {
	return el("button", {
		className: "cloud-row cloud-clone-drive",
		onclick: async () => {
			const got = await requestClone(bridge(), repoName);
			toast(got.ok ? `Cloud Drive opened: clone ${repoName} there, then come back and Refresh` : got.reason || "Cloud Drive did not open");
		},
	}, el("span", { className: "icon git" }), "Clone with Cloud Drive");
}

// ── Backlog: render the ONE existing source, as-is ─────────────────────────
// Backlog and Agents are two entry files of the same backlog dist, read from
// the same folder (one localStorage key), through this one renderer.
async function renderBacklogView(panel, title, entry, { extra = [], docs = false } = {}) {
	const md = markdownIt({ html: false, linkify: true });
	let dir = await repoUrl("backlog.dir", nav.backlog.source_dir);
	let current = entry;
	const body = el("div", { className: "cloud-md" });

	async function load(file, quiet = false) {
		if (docs && !quiet && mode !== "views") setMode("views");
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
				cloneWithDriveButton(nav.backlog.source_dir.split("/")[0]),
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
	let docsDir = await repoUrl("docs.dir", nav.docs.root);
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

	// #1699 the Backlog tab opens on the clone's ROOT index.html (rendered backlog) with Refresh,
	// an offline last-good copy and a visible reason; the dist/*.md views stay one tap away ("Views").
	const indexBox = el("div", { className: "cloud-backlog-index" });
	const indexNote = el("div", { className: "cloud-backlog-note cloud-muted" });
	const modeBtn = {
		index: el("button", { className: "cloud-row cloud-backlog-mode", onclick: () => setMode("index") }, "Index"),
		views: el("button", { className: "cloud-row cloud-backlog-mode", onclick: () => setMode("views") }, "Views"),
	};
	const refreshBtn = el("button", { className: "cloud-row cloud-backlog-mode", title: "Refresh from the clone", onclick: () => refreshIndex() }, el("span", { className: "icon refresh" }), "Refresh");
	const agentBtn = el("button", { className: "cloud-row cloud-backlog-mode", "data-verb": "start-agent", title: "Start an agent session from a ticket", onclick: () => startAgentFromBacklog() }, el("span", { className: "icon brain" }), "Start agent session");
	const modeBar = el("div", { className: "cloud-backlog-modes" }, modeBtn.index, modeBtn.views, refreshBtn, agentBtn);
	const cachePath = () => `${cordova.file.dataDirectory}${nav.backlog.last_good}`;
	const indexIo = () => ({
		readLive: () => fsOperation(docsDir.replace(/\/?$/, "/"), nav.backlog.index).readFile("utf8"),
		readLast: async () => {
			const f = fsOperation(cachePath());
			if (!(await f.exists())) return null;
			const raw = JSON.parse(await f.readFile("utf8"));
			return { text: raw.html, savedAt: raw.savedAt };
		},
		writeLast: async (text) => {
			const data = JSON.stringify({ savedAt: Date.now(), html: text });
			const f = fsOperation(cachePath());
			if (await f.exists()) await f.writeFile(data);
			else await fsOperation(cordova.file.dataDirectory).createFile(nav.backlog.last_good, data);
		},
		now: () => Date.now(),
	});
	async function refreshIndex() {
		indexNote.textContent = "Reading the repository clone …";
		// #1855 P1 after a clone the repository may have landed on another volume: ask Drive again (a menu-set folder still wins).
		if (!localStorage.getItem("cloud-code.docs.dir")) docsDir = await repoUrl("docs.dir", nav.docs.root);
		let got;
		try {
			got = await loadIndex(indexIo(), `${docsDir.replace(/^file:\/\//, "")}/${nav.backlog.index}`);
		} catch (err) {
			got = { state: "unavailable", reason: String(err?.message || err) };
		}
		const grant = el("button", {
			className: "cloud-row",
			onclick: () => system.manageAllFiles(() => refreshIndex(), (e) => toast(String(e))),
		}, el("span", { className: "icon folder_open" }), "Allow access to all files, then retry");
		console.info(`cloud-code backlog index: ${got.state}${got.reason ? ` (${got.reason})` : ""}`);
		if (got.state === "live") {
			indexNote.textContent = `cloud-me-pvt-tasks index.html, read from the clone ${ageText(got.savedAt, Date.now())}`;
		} else if (got.state === "offline") {
			indexNote.replaceChildren(`Offline copy saved ${ageText(got.savedAt, Date.now())}. ${got.reason}`, ...(notOnDevice(got) ? [cloneWithDriveButton(nav.docs.root)] : []), grant);
		} else {
			indexNote.replaceChildren(got.reason, ...(notOnDevice(got) ? [cloneWithDriveButton(nav.docs.root)] : []), grant);
		}
		if (got.html) {
			// Sandboxed: the page's own scripts may run (filters) but it cannot reach this app's DOM or storage.
			indexBox.replaceChildren(el("iframe", { className: "cloud-backlog-frame", sandbox: "allow-scripts", srcdoc: got.html, title: "Backlog" }));
		} else {
			indexBox.replaceChildren(el("p", { className: "cloud-muted" }, "Nothing to show yet. Use the Views switch for the dist pages once the clone exists."));
		}
	}
	function setMode(m) {
		mode = m;
		indexBox.style.display = m === "index" ? "" : "none";
		indexNote.style.display = m === "index" ? "" : "none";
		body.style.display = m === "views" ? "" : "none";
		modeBtn.index.classList.toggle("active", m === "index");
		modeBtn.views.classList.toggle("active", m === "views");
		if (m === "index") refreshIndex();
	}
	let mode = docs ? "index" : "views";

	const asidePane = docs ? buildDocsPane() : null;
	const main = docs ? el("div", { className: "cloud-backlog-main" }, modeBar, indexNote, indexBox, body) : body;
	if (asidePane) panel.replaceChildren(panelHeader(title), el("div", { className: "cloud-backlog-split" }, asidePane, main), ...extra);
	else panel.replaceChildren(panelHeader(title), main, ...extra);
	if (docs) setMode("index");

	const items = [
		{ id: "reload", label: "Reload", icon: "refresh", run: () => load(current) },
		{ id: "entry", label: `Back to ${entry}`, icon: "home", run: () => load(entry) },
		{ id: "edit", label: "Edit this file", icon: "edit", run: () => edit.click() },
		{ id: "repo", label: "Open the repository", icon: "git", run: () => repo.click() },
		{ id: "folder", label: "Backlog folder", icon: "folder_open", run: () => change.click() },
		{ id: "start-agent", label: "Start agent session", icon: "brain", run: () => startAgentFromBacklog() },
	];
	if (docs) {
		items.unshift({ id: "index-refresh", label: "Refresh index", icon: "refresh", run: () => { setMode("index"); refreshIndex(); } },
			{ id: "view-index", label: "Index (rendered backlog)", icon: "home", run: () => setMode("index") },
			{ id: "view-views", label: "Views (dist pages)", icon: "folder_open", run: () => setMode("views") });
	}
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
	await load(pageFileOf(panel.dataset.tab, entry), true);
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

// #1855 P3 [onPick]: the Workspace selects the repo (its tree opens below, with "Open in editor"); without it the row opens the folder in the editor as before.
function repoRow(r, panel, onPick) {
	const row = el("button", {
		className: "cloud-row cloud-repo",
		title: r.url,
		onclick: () => {
			if (onPick) return onPick(r);
			openFolder(r.url, { name: r.name });
			showTab("editor");
		},
	}, el("span", { className: "icon git" }), el("span", { className: "cloud-repo-name" }, r.name), el("span", { className: "cloud-repo-info" }, r.info || r.branch || ""));
	row._repo = r;
	return row;
}

async function fillVolume(v, body, count, panel, into, onPick) {
	try {
		const root = await volumeRoot(v);
		const { items } = await listRepos(root);
		count.textContent = String(items.length);
		body.replaceChildren(
			el("p", { className: "cloud-muted cloud-vol-path" }, root.replace(/^file:\/\//, "")),
			...(items.length ? items.map((r) => repoRow(r, panel, onPick)) : [el("p", { className: "cloud-muted" }, "No repos")]),
		);
	} catch (err) {
		count.textContent = "!";
		body.replaceChildren(
			el("p", { className: "cloud-muted" }, `Not available: ${err?.message || err}`),
			el("button", {
				className: "cloud-row",
				onclick: () => system.manageAllFiles(() => renderGit(panel, into, onPick), (e) => toast(String(e))),
			}, el("span", { className: "icon folder_open" }), "Grant all-files access, then retry"),
		);
	}
}

// #1855 P1 one column from Drive's list (repos on that volume, as Drive sees them).
function fillFromDrive(col, body, count, panel, onPick) {
	count.textContent = String(col.items.length);
	body.replaceChildren(
		el("p", { className: "cloud-muted cloud-vol-path" }, col.items.length ? parentOf(col.items) : "Cloud Drive lists no repos here"),
		...(col.items.length ? col.items.map((r) => repoRow(r, panel, onPick)) : [el("p", { className: "cloud-muted" }, "No repos")]),
	);
}

function volumeSection(id, label, count, body) {
	return el("section", { className: "cloud-vol", "data-volume": id }, el("h3", { className: "cloud-vol-head" }, el("span", {}, label), count), body);
}

// [into]: the Workspace page hosts the picker (its repo columns) in a box of its own; [onPick] selects a repo there.
async function renderGit(panel, into, onPick) {
	const parts = nav.repos.volumes.map((v) => {
		const count = el("span", { className: "cloud-vol-count" }, "…");
		const body = el("div", { className: "cloud-list" }, "Scanning …");
		return { v, count, body, section: volumeSection(v.id, v.label, count, body) };
	});
	const holder = el("div", { className: "cloud-vols" }, ...parts.map((p) => p.section));
	if (into) into.replaceChildren(holder);
	else panel.replaceChildren(panelHeader("Git"), holder);
	MENUS[panel.dataset.tab] = () => ({ items: [
		{ id: "rescan", label: "Rescan", icon: "refresh", run: () => renderGit(panel, into, onPick) },
		...(onPick ? WORKSPACE_ITEMS() : []),
	] });
	// #1855 P1 Cloud Drive owns git: when it answers with repositories, ITS list is the source (every volume, incl. an
	// attached drive, in the two columns plus External); no answer (absent, empty, slow) = this tab's own volume listing.
	const drive = await driveList(bridge());
	if (drive.drive && drive.repos.length) {
		const cols = columnsFor(nav.repos.volumes, drive.repos);
		cols.forEach((col, i) => {
			const p = parts[i] || (() => {
				const count = el("span", { className: "cloud-vol-count" }, "…");
				const body = el("div", { className: "cloud-list" });
				const made = { count, body, section: volumeSection(col.id, col.label, count, body) };
				holder.append(made.section);
				return made;
			})();
			fillFromDrive(col, p.body, p.count, panel, onPick);
		});
		return;
	}
	parts.forEach((p) => fillVolume(p.v, p.body, p.count, panel, into, onPick));
}

// ── Workspace (#1855 P3, was the Git tab): the repo picker (Cloud Drive's list over every volume) + the picked repo's tree + search ──
// Nothing of the Git tab is dropped (rule 16): the two volume columns and every repo row are renderGit's, unchanged; a tap now
// SELECTS the repo and its tree opens below, where "Open in editor" does what the row's tap did before.
let WORKSPACE_ITEMS = () => [];

async function renderWorkspace(panel) {
	const query = el("input", { type: "search", placeholder: "Search repositories and files", "aria-label": "Search the workspace" });
	const pickerBox = el("div", { className: "cloud-ws-picker" });
	const repoBox = el("div", { className: "cloud-ws-repo" });
	repoBox.style.display = "none";
	const results = el("div", { className: "cloud-ws-results cloud-list" });
	const treeBox = el("div", { className: "cloud-docs-treebox" });
	let selected = null;
	let tree = null;
	let timer = null;
	const lsDir = async (dir) => (await fsOperation(dir).lsDir()).map((e) => ({ name: e.name, url: e.url, isDirectory: !!e.isDirectory }));
	const openFileInEditor = async (url) => {
		await openFile(url);
		showTab("editor");
	};

	const applyFilter = () => {
		for (const row of pickerBox.querySelectorAll(".cloud-repo")) row.style.display = filterRepos([row._repo], query.value).length ? "" : "none";
	};
	async function runSearch() {
		const q = query.value.trim();
		results.replaceChildren();
		if (!selected || q.length < 2) return;
		const { hits, truncated } = await searchFiles(lsDir, selected.url.replace(/\/?$/, "/"), q);
		results.replaceChildren(
			...hits.map((h) => el("button", { className: "cloud-row", title: h.url, onclick: () => openFileInEditor(h.url) }, el("span", { className: "icon insert_drive_file" }), h.name, el("span", { className: "cloud-muted" }, h.url.replace(selected.url, "")))),
			el("p", { className: "cloud-muted" }, `${hits.length} file${hits.length === 1 ? "" : "s"} named like "${q}" in ${selected.name}${truncated ? " (search limit reached: narrow the name)" : ""}`),
		);
	}
	query.addEventListener("input", () => {
		applyFilter();
		clearTimeout(timer);
		timer = setTimeout(runSearch, 300);
	});

	function pick(r) {
		selected = r;
		if (tree) tree.destroy();
		repoBox.style.display = "";
		repoBox.replaceChildren(
			el("div", { className: "cloud-ws-actions" },
				el("strong", {}, r.name),
				el("button", { className: "cloud-row", onclick: () => { openFolder(r.url, { name: r.name }); showTab("editor"); } }, el("span", { className: "icon folder_open" }), "Open in editor"),
				el("button", { className: "cloud-row", onclick: () => showTab("changes") }, el("span", { className: "icon compare_arrows" }), "Changes")),
			treeBox,
		);
		tree = new FileTree(treeBox, { getEntries: (dirUrl) => fsOperation(dirUrl).lsDir(), onFileClick: openFileInEditor });
		tree.load(r.url).catch((err) => treeBox.replaceChildren(el("p", { className: "cloud-muted" }, `Cannot read ${r.url}: ${err?.message || err}`)));
		runSearch();
	}
	WORKSPACE_ITEMS = () => (selected ? [{ id: "open-folder", label: `Open ${selected.name} in the editor`, icon: "folder_open", run: () => { openFolder(selected.url, { name: selected.name }); showTab("editor"); } }] : []);
	panel.replaceChildren(panelHeader("Workspace"), el("div", { className: "cloud-ws-search" }, query), pickerBox, repoBox, results);
	await renderGit(panel, pickerBox, pick);
	applyFilter();
}

// ── Changes (#1855 P3, P4a): per-repo status and changed files read from Cloud Drive (libs:repo-workspace v2); Drive owns git ──
// One row per repo (branch, state, summary, last sync) and under it the repo's changed files (changedFiles, staged first); a tap on a
// file opens its diff (diffView), the actions are: open the repo in the editor, and Commit / Push in Cloud Drive's own Commit screen
// (requestCommit / requestPush). Staging, commit and push happen there with the user's confirmation, never from this page.
function fileRow(r, f, panel) {
	return el("button", { className: "cloud-row cloud-change-file", "data-file": f.relpath, "data-staged": f.staged ? "1" : "0", onclick: () => diffView(panel, r, f) },
		el("span", { className: `cloud-change-st st-${f.status === "?" ? "u" : f.status}`, title: STATUS_LABEL[f.status] }, f.status),
		el("span", { className: "cloud-repo-name" }, f.relpath),
		el("span", { className: "cloud-muted" }, f.staged ? "staged" : "not staged"),
		el("span", { className: "cloud-change-counts" }, el("span", { className: "cloud-diff-add" }, `+${f.added}`), " ", el("span", { className: "cloud-diff-del" }, `-${f.removed}`)));
}

async function fillFiles(r, box, panel) {
	box.replaceChildren(el("span", { className: "cloud-muted" }, "Reading the changed files from Cloud Drive …"));
	const got = await driveChanges(bridge(), r.path);
	console.log(`cloud-code changes files: ${r.name} drive=${got.drive} files=${got.files.length} names=${got.files.map((f) => f.relpath).join(",")}`);
	if (!got.drive) { box.replaceChildren(el("span", { className: "cloud-muted" }, "Cloud Drive could not list the changed files: update Cloud Drive.")); return; }
	if (!got.files.length) { box.replaceChildren(el("span", { className: "cloud-muted" }, "No changed files.")); return; }
	const g = groupChanges(got.files);
	const t = changeTotals(got.files);
	box.replaceChildren(
		el("div", { className: "cloud-muted cloud-change-totals" }, `${t.files} file${t.files === 1 ? "" : "s"} changed, +${t.added} -${t.removed}; ${g.staged.length} staged`),
		...g.staged.map((f) => fileRow(r, f, panel)), ...g.unstaged.map((f) => fileRow(r, f, panel)));
}

// The diff of one file: syntax-coloured lines, back to the list, open the file in the editor.
async function diffView(panel, r, f) {
	const pre = el("pre", { className: "cloud-diff" }, "Reading the diff from Cloud Drive …");
	panel.replaceChildren(panelHeader("Changes"),
		el("div", { className: "cloud-row" }, el("button", { className: "cloud-row", onclick: () => renderChanges(panel) }, el("span", { className: "icon arrow_back" }), "Changes"),
			el("span", { className: "cloud-repo-name" }, `${r.name}/${f.relpath}`), el("span", { className: "cloud-change-counts" }, `${f.status} +${f.added} -${f.removed}`)),
		el("div", { className: "cloud-ws-actions" },
			el("button", { className: "cloud-row", "data-verb": "open", disabled: f.status === "D", onclick: () => openFile(`file://${r.path.replace(/\/+$/, "")}/${f.relpath}`).then(() => showTab("editor")) }, el("span", { className: "icon insert_drive_file" }), "Open in editor")),
		pre);
	const text = await driveDiff(bridge(), r.path, f.relpath);
	const lines = diffLines(text);
	console.log(`cloud-code changes diff: ${r.name}/${f.relpath} lines=${lines.length}`);
	pre.replaceChildren(...(lines.length ? lines.map((l) => el("div", { className: `cloud-diff-line cloud-diff-${l.kind}` }, l.text || " ")) : [el("div", { className: "cloud-muted" }, "(no textual diff)")]));
}

async function renderChanges(panel) {
	const body = el("div", { className: "cloud-list cloud-changes" }, "Reading the repositories from Cloud Drive …");
	panel.replaceChildren(panelHeader("Changes"), body);
	MENUS[panel.dataset.tab] = () => ({ items: [{ id: "refresh", label: "Refresh", icon: "refresh", run: () => renderChanges(panel) }] });
	const drive = await driveList(bridge());
	if (!drive.drive) {
		body.replaceChildren(el("p", { className: "cloud-muted" }, "Cloud Drive owns git and is not installed or not answering: install it to see and commit changes."));
		return;
	}
	const statuses = {};
	await Promise.all(drive.repos.map(async (r) => {
		const got = await driveStatus(bridge(), r.path);
		if (got.repo) statuses[r.path] = got.repo;
	}));
	const rows = changeRows(drive.repos, statuses);
	if (!rows.length) {
		body.replaceChildren(el("p", { className: "cloud-muted" }, "Cloud Drive lists no repositories yet. Clone one with Cloud Drive."));
		return;
	}
	let autoLoaded = 0; // the files of changed repos, and of the first few whose state Drive has not read yet, load by themselves (each is a git status in Drive)
	body.replaceChildren(...rows.map((r) => {
		const files = el("div", { className: "cloud-change-files", "data-files": r.name });
		const handOff = (verb, ask) => el("button", {
			className: "cloud-row", "data-verb": verb.toLowerCase(),
			onclick: async () => {
				const got = await ask(bridge(), r.path);
				toast(got.ok ? `Cloud Drive opened on ${r.name}: ${verb.toLowerCase()} there` : got.reason || "Cloud Drive did not open");
			},
		}, el("span", { className: "icon git" }), `${verb} in Cloud Drive`);
		const row = el("div", { className: "cloud-vol", "data-repo": r.name },
			el("div", { className: "cloud-row" },
				el("span", { className: "icon git" }), el("span", { className: "cloud-repo-name" }, r.name),
				el("span", { className: "cloud-repo-info" }, r.branch),
				el("span", { className: "cloud-change-state" }, [r.changed ? "changed" : "", r.state, r.summary].filter(Boolean).join(" · ") || "status not read yet"),
				el("span", { className: "cloud-muted" }, r.lastSyncMs ? `last sync ${ageText(r.lastSyncMs, Date.now())}` : "never synced")),
			el("div", { className: "cloud-ws-actions" },
				el("button", { className: "cloud-row", onclick: () => { openFolder(`file://${r.path}`, { name: r.name }); showTab("editor"); } }, el("span", { className: "icon folder_open" }), "Open in editor"),
				el("button", { className: "cloud-row", "data-verb": "files", onclick: () => fillFiles(r, files, panel) }, el("span", { className: "icon compare_arrows" }), "Changed files"),
				handOff("Commit", requestCommit), handOff("Push", requestPush)),
			files);
		if ((r.changed || !r.state) && autoLoaded < 8) { autoLoaded++; fillFiles(r, files, panel); }
		return row;
	}));
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
	runDebugFlow()?.catch((err) => console.log(`cloud-code agent debug-flow: failed ${err?.message || err}`));
}

// ── the agent loop's host (#1855 P4b): what the pure agent modules (src/cloud/agent) use of this device ──
const dirOf = (url) => url.replace(/\/+$/, "").replace(/\/[^/]*$/, "");
const agentHost = {
	exists: (url) => fsOperation(url).exists(),
	readText: (url) => fsOperation(url).readFile("utf8"),
	list: async (url) => (await fsOperation(url).lsDir()).map((e) => ({ name: e.name, url: e.url, isDirectory: !!e.isDirectory })),
	// Writes `rel` under the repository root `rootPath` only: the path is re-validated here, parents are created one by one (never above the root).
	async writeText(rootPath, rel, text) {
		const url = resolveInRoot(rootPath, rel);
		if (!url) throw new Error("path is outside the repository root");
		const parts = rel.split("/");
		let parent = `file://${rootPath.replace(/^file:\/\//, "").replace(/\/+$/, "")}`;
		for (const seg of parts.slice(0, -1)) {
			if (!(await ensureDir(parent, seg))) throw new Error(`cannot create ${seg}`);
			parent = `${parent}/${seg}`;
		}
		const f = fsOperation(url);
		if (await f.exists()) await f.writeFile(text);
		else await fsOperation(parent).createFile(parts[parts.length - 1], text);
	},
	repos: () => driveList(bridge()),
	showTab: (id) => showTab(id),
	requestCommit: (path) => requestCommit(bridge(), path),
	openUrl: (url) => browser.open(url),
	copy: (text) => { try { cordova.plugins.clipboard.copy(text); } catch { navigator.clipboard?.writeText(text); } },
	prompt: async (title, value) => { const { default: prompt } = await import("dialogs/prompt"); return prompt(title, value, "text"); },
	selection() {
		const ed = window.editorManager?.editor;
		if (!ed) return { name: "", text: "" };
		const { from, to } = ed.state.selection.main;
		return { name: window.editorManager.activeFile?.name || "", text: from === to ? "" : ed.state.sliceDoc(from, to) };
	},
	problems() {
		const ed = window.editorManager?.editor;
		if (!ed) return [];
		try {
			return getLspDiagnostics(ed.state).slice(0, 50).map((d) => ({ file: window.editorManager.activeFile?.name || "", line: ed.state.doc.lineAt(Math.min(d.from, ed.state.doc.length)).number, severity: d.severity, message: d.message }));
		} catch {
			return [];
		}
	},
	async fetchJson(url) {
		const ctl = new AbortController();
		const timer = setTimeout(() => ctl.abort(), 8000);
		try {
			const r = await fetch(url, { headers: { accept: "application/vnd.github+json" }, signal: ctl.signal });
			let json = null;
			try { json = await r.json(); } catch { /* no body */ }
			return { status: r.status, json };
		} finally {
			clearTimeout(timer);
		}
	},
	async loadTicket(id) {
		const root = (await repoUrl("docs.dir", nav.docs.root)).replace(/\/?$/, "/");
		return locateTicket(agentHost.list, (u) => agentHost.readText(u), `${root}${nav.backlog.tasks_dir}`, id);
	},
	// the emulator walk's test flow: a JSON file only adb can place in this app's own external data dir; consumed once
	async debugFlow() {
		try {
			const dir = window.cordova?.file?.externalDataDirectory;
			if (!dir) return null;
			const f = fsOperation(dir, "agent-test-flow.json");
			if (!(await f.exists())) return null;
			const flow = JSON.parse(await f.readFile("utf8"));
			await f.delete();
			return flow;
		} catch {
			return null;
		}
	},
};
setAgentHost(agentHost);

// #1855 P4b the Backlog's "Start agent session": a ticket id from the clone, then the repository from the Workspace.
async function startAgentFromBacklog() {
	const id = await agentHost.prompt("Ticket id to start an agent session from", "");
	if (!id) return;
	const ticket = await agentHost.loadTicket(id);
	if (!ticket) return toast(`Ticket #${String(id).replace(/\D/g, "")} was not found in the backlog clone (tasks/<session>/<id>.json)`);
	startFromTicket(ticket);
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
	workspace: renderWorkspace,
	changes: renderChanges,
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
	// #1855 P3 an old id (git, ai, agents, browser, myterminal, a sub-page id) lands on its new place (tabs.js::resolveTarget).
	const to = resolveTarget(tabs, nav.redirects, id);
	if (!to) return;
	const tab = tabs.find((t) => t.id === to.tab);
	if (pageId === undefined && to.page) pageId = to.page;
	// undefined keeps the page the tab last showed; null (the dropdown's page itself) goes to its first.
	const want = pageId !== undefined ? pageId : PAGE[tab.id];
	const page = want == null ? (tab.pages[0] ? tab.pages[0].id : null) : want;
	const view = viewOf(tab, page);
	const next = select(state, tab, LAUNCH_VIEWS, view);
	if (next.launch) {
		LAUNCH[view]();
		return;
	}
	state = next;
	PAGE[tab.id] = page;
	bars.setTab(tab, PAGE[tab.id]);
	for (const [tid, p] of Object.entries(panels)) p.hidden = tid !== state.showPanel;
	// The editor stays mounted under a panel (its state is kept) but must not paint through it.
	document.body.classList.toggle("cloud-panel-open", !!state.showPanel);
	if (state.showPanel) {
		VIEWS[view](panels[tab.id]).catch((err) => toast(String(err?.message || err)));
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
