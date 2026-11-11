// Driver for test/test-cloud-nav.sh (#562). Loads the REAL src/cloud/tabs.js
// (no copy) and the REAL nav.json, renders the bar model through them, and
// prints one `CHECK <ok|bad> <text>` line per assertion; the shell decides.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);

const tabs = await import(
	`data:text/javascript,${encodeURIComponent(read("src/cloud/tabs.js"))}`
);
const nav = JSON.parse(read("src/cloud/nav.json"));
const index = read("src/cloud/index.js");

// The renderers index.js actually has — read from its VIEWS table, not restated.
const viewsBlock = /const VIEWS = \{([\s\S]*?)\n\};/.exec(index);
const views = viewsBlock
	? [...viewsBlock[1].matchAll(/^\s*(\w+)\s*:/gm)].map((m) => m[1])
	: [];
out(views.length > 0, `index.js VIEWS table read: ${views.join(",")}`);

let model = [];
try {
	model = tabs.buildTabs(nav, views);
	out(true, `bar renders ${model.length} tabs`);
} catch (e) {
	out(false, `buildTabs refused the declaration: ${e.message}`);
}

// #562's order is the requirement; the RENDERED labels are what is compared.
// Chat replaced Home (the owner's request): same place in the bar, its own renderer.
const required = "Backlog|Editor|Git|AI|Agents|Browser|MyTerminal";
const rendered = model.map((t) => t.label).join("|");
out(rendered === required, `rendered order ${rendered} (required ${required})`);

// Every icon is a class that exists in Acode's own icon font.
const css = read("src/res/icons/style.css");
for (const t of model) {
	const has = new RegExp(`\\.icon\\.${t.icon.replace(/[-]/g, "\\-")}\\s*:{1,2}before`).test(css);
	out(has, `tab ${t.id} icon '${t.icon}' exists in src/res/icons/style.css`);
}

out(!model.some((t) => t.id === "home" || t.view === "home" || /home/i.test(t.label)), "no Home tab is left in the bar");
const aiTab = model.find((t) => t.id === "ai");
out(!!aiTab && aiTab.label === "AI" && aiTab.view === "chat" && views.includes("chat") && model.indexOf(aiTab) === 3, `AI (the agent chat) sits where Home was (4th), with a renderer (${aiTab?.view})`);
out(nav.default_tab === "ai", `the app opens on the AI tab (default_tab ${nav.default_tab})`);
out(!model.some((t) => t.label === "Chat"), "no tab is labelled Chat: Chat means Mattermost, which this app does not host");
out(!model.some((t) => /chatbot|ai chat/i.test(t.label)), "no tab is labelled ChatBot or AI Chat");
out(!/renderHome|homeStatus/.test(index), "index.js keeps no Home renderer or seam call");

// Measured fit: the declared narrowest viewport still gives every tab the
// touch floor, and the device-side verdict goes compact on overflow, never clips.
const w = tabs.tabWidth(nav.nav.min_viewport_px, model.length);
out(tabs.clearsTouchFloor(nav), `each tab is ${w.toFixed(1)} px at ${nav.nav.min_viewport_px} px, floor ${nav.nav.min_touch_target_px} px`);
out(tabs.fitLabels([{ labelPx: 40, tabPx: 51 }, { labelPx: 50, tabPx: 51 }]) === "full", "labels that fit keep full mode");
out(tabs.fitLabels([{ labelPx: 40, tabPx: 51 }, { labelPx: 56, tabPx: 51 }]) === "compact", "one overflowing label switches the bar to compact");

// Selection semantics, through the real select().
const byId = Object.fromEntries(model.map((t) => [t.id, t]));
const s0 = { active: nav.default_tab, showPanel: null };
const toBacklog = tabs.select(s0, byId.backlog, ["myterminal"]);
out(toBacklog.active === "backlog" && toBacklog.showPanel === "backlog", "Backlog shows its panel");
const toEditor = tabs.select(toBacklog, byId.editor, ["myterminal"]);
out(toEditor.active === "editor" && toEditor.showPanel === null, "Editor hides every cloud panel (Acode untouched)");
const toTerm = tabs.select(toBacklog, byId.myterminal, ["myterminal"]);
out(toTerm.launch === "myterminal" && toTerm.active === "backlog", "MyTerminal launches out and keeps the current tab");
out(new RegExp(`LAUNCH_VIEWS = \\[\\s*"myterminal"\\s*\\]`).test(index), "index.js treats myterminal as the launch view");

// ONE source (#562): Backlog and Agents are two entry files of the same
// backlog dist, rendered by the same function from the same folder. Read off
// the real index.js: the function each renderer calls and the entry it passes.
const renderer = /async function (\w+)\(panel, title, entry/.exec(index)?.[1];
out(!!renderer, `index.js has one shared backlog renderer (${renderer})`);
const entryOf = (fn) => new RegExp(`function ${fn}\\(panel\\) \\{[\\s\\S]*?${renderer}\\(panel, "[^"]+", (nav\\.\\w+\\.entry)`).exec(index)?.[1];
out(entryOf("renderBacklog") === "nav.backlog.entry", `Backlog renders nav.backlog.entry through ${renderer} (got ${entryOf("renderBacklog")})`);
out(entryOf("renderAgents") === "nav.agents.entry", `Agents renders nav.agents.entry through ${renderer} (got ${entryOf("renderAgents")})`);
const dirReads = [...index.matchAll(/stored\("backlog\.dir"/g)].length;
out(dirReads === 1 && !/nav\.agents\.source_dir|stored\("agents\./.test(index), `the backlog folder is read in one place (${dirReads}); Agents declares no second source`);
for (const k of ["backlog", "agents"]) {
	out(/^[\w-]+\.md$/.test(nav[k]?.entry || ""), `nav.${k}.entry is a markdown file of the backlog dist (${nav[k]?.entry})`);
}
out(nav.agents.entry !== nav.backlog.entry, "Agents opens a different view of that source than Backlog");

// ── #575 ONE shared repo store: cloud-drive declares it, this app only reads it ──
// nav.json names the store app by fleet id; the resolver copies that app's
// build.json::storage.shared_root (relative) into targets.gen.json; index.js
// composes every on-device path from it. A second declaration — an absolute
// /storage path anywhere under src/cloud — is the bug this pins.
const navText = read("src/cloud/nav.json");
out(!/\/storage\/emulated/.test(navText), "nav.json writes no device-absolute /storage path");
out(typeof nav.shared_store?.app === "string" && nav.shared_store.app.length > 0, `nav.shared_store.app names the store app by fleet id (${nav.shared_store?.app})`);
for (const [k, v] of [["backlog.source_dir", nav.backlog?.source_dir], ["repos.root", nav.repos?.root]]) {
	out(typeof v === "string" && !v.startsWith("/") && !v.includes(".."), `nav.${k} is relative to the store (${JSON.stringify(v)})`);
}
out(nav.backlog?.source_dir?.startsWith("cloudy-a-docs/"), "Backlog reads cloudy-a-docs FROM the store (the #575 proof case)");
// #1655 the default path must name the REAL repo (cloudy-a-docs), never the old cloud-a-docs, and the sub-tab -> file map must match the generated dist names.
out(nav.backlog.source_dir === "cloudy-a-docs/1___BACKLOG___/1.1.Product-Backlog/dist/" && nav.docs?.root === "cloudy-a-docs" && !/(^|[^y])-a-docs|cloud-a-docs/.test(navText.replace(/cloudy-a-docs/g, "")), "#1655 default backlog path uses the real repo name cloudy-a-docs");
const subMap = Object.fromEntries(nav.tabs.flatMap((t) => (t.pages || []).map((p) => [p.id, p.file])));
out(["sts", "prj", "pry", "eff", "bat", "mdl"].every((k) => subMap[k] === `P-Backlog-${k}.md`) && nav.backlog.entry === subMap.sts, "#1655 sub-tab ids map to the generated dist files (Status->P-Backlog-sts.md, prj/pry/eff/bat/mdl)");
const resolver = read("tools/resolve-targets.py");
out(/def shared_root\(app_root, fleet_id\)/.test(resolver) && /"shared_root": shared_root\(app_root, nav\["shared_store"\]\["app"\]\)/.test(resolver), "resolve-targets.py emits shared_root from nav.shared_store.app");
out(/\("storage"\) or \{\}\)\.get\("shared_root"\)/.test(resolver) && /root\.startswith\("\/"\)/.test(resolver), "the resolver reads build.json::storage.shared_root and refuses an absolute one");
// #676 clones live under the store's git folder (cloud-drive's storage.git_subdir):
// the resolver copies it, and every git-tree path index.js composes goes through it.
// A path composed at the store's ROOT is a second tree — the bug this pins shut.
out(/def git_subdir\(app_root, fleet_id\)/.test(resolver) && /"git_subdir": git_subdir\(app_root, nav\["shared_store"\]\["app"\]\)/.test(resolver), "resolve-targets.py emits git_subdir from the same store declaration (#676)");
out(/\("storage"\) or \{\}\)\.get\("git_subdir"\)/.test(resolver) && /sub\.startswith\("\/"\) or "\/" in sub/.test(resolver), "the resolver reads storage.git_subdir and refuses an absolute or nested one");
out(/function sharedRoot\(\) \{\s*return `\$\{cordova\.file\.externalRootDirectory\}\$\{targets\.shared_root\}\/`;/.test(index), "index.js composes the root from the device's externalRootDirectory + targets.shared_root");
out(/function gitPath\(relative\) \{\s*return `\$\{sharedRoot\(\)\}\$\{targets\.git_subdir\}\/\$\{relative\}`;/.test(index), "index.js composes every git-tree path through the store's declared git folder (#676)");
out(/stored\("backlog\.dir", gitPath\(nav\.backlog\.source_dir\)\)/.test(index) && /gitPath\(nav\.repos\.root\)/.test(index), "Backlog and Git default to paths under the store's git folder");
// Git tab (was Repos): labelled Git, two mounted volumes, repos enumerated per volume in two horizontal columns.
const gitTab = nav.tabs.find((t) => t.id === "git");
out(gitTab?.label === "Git" && gitTab.view === "git" && gitTab.aliases?.includes("repos") && !nav.tabs.some((t) => /repos/i.test(t.label)), "the section is labelled Git (id git, old id repos kept as alias)");
const vols = nav.repos?.volumes || [];
out(vols.length === 2 && vols[0].label === "Cloud-Shared-Store/Git" && vols[1].label === "Emulated/Git", "both volumes are declared: Cloud-Shared-Store/Git and Emulated/Git");
out(/cloud-shared-store/.test(vols[0]?.store_home || "") && /cordova\.file\.externalRootDirectory/.test(index) && vols[1]?.subdir === "Git", "shared-store path resolves under the store home (legacy mirror fallback); emulated = externalRootDirectory + Git");
out(/await listRepos\(root\)/.test(index) && /nav\.repos\.volumes\.map/.test(index) && /ensureDir\(/.test(index) && /manageAllFiles/.test(index.slice(index.indexOf("async function fillVolume"))), "repos are enumerated per volume; missing folders are created; denied volumes offer the all-files grant");
// #1589 field parity: the Backlog page keeps its own content (entry, reader, edit/repo/folder actions) and ADDS the cloud-docs tree.
out(nav.docs?.root === "cloudy-a-docs" && !nav.docs.root.startsWith("/"), "nav.docs.root declares the cloud-docs clone relative to the store git folder (#1589)");
out(/async function renderBacklogView\(panel, title, entry, \{ extra = \[\], docs = false \} = \{\}\)/.test(index) && /renderBacklogView\(panel, "Backlog", nav\.backlog\.entry, \{ docs: true \}\)/.test(index), "only the Backlog tab mounts the cloud-docs tree; Agents keeps its plain view");
out(/new FileTree\(treeBox/.test(index) && /cloud-docs-toggle/.test(index) && /classList\.toggle\("collapsed"\)/.test(index) && /cloud-backlog-split/.test(index), "the tree is a LEFT collapsible child menu beside the page body");
out(/\.html\?\$\/i\.test\(name\)/.test(index) && /DOMPurify\.sanitize\(html\)/.test(index) && /renderMarkdown\(text, \{ uri: url \}\)/.test(index) && /DOMPurify\.sanitize\(text\)/.test(index), "clicking a .md or .html renders it (markdown through the shared renderer, html sanitized)");
for (const id of ["reload", "entry", "edit", "repo", "folder", "docs-reload", "docs-folder"]) out(new RegExp(`id: "${id}"`).test(index), `Backlog drawer keeps/gains the ${id} item`);
out(/\.cloud-docs-tree/.test(read("src/cloud/cloud.scss")) && /\.cloud-backlog-split/.test(read("src/cloud/cloud.scss")), "cloud.scss styles the split and the tree");
const scss = read("src/cloud/cloud.scss");
out(/\.cloud-vols \{[^}]*flex-direction: row/.test(scss) && /\.cloud-vol \{[^}]*flex: 1 1 0/.test(scss), "the two volumes sit side by side in horizontal columns, repos stacked under each header");
out(/fsOperation\(e\.url, "\.git"\)\.exists\(\)/.test(read("src/cloud/seams.js")), "a repo is an immediate subdirectory holding .git");
out(!/\/storage\/emulated/.test(index), "index.js writes no device-absolute /storage path");
out(/openFile\(`\$\{dir\}\$\{current\}`/.test(index) && /openFolder\(gitPath\(repoName\)/.test(index), "Backlog WRITES through the store: edit-in-place and open-repository actions go through Acode's editor");
const configXml = read("config.xml");
out(/<uses-permission android:name="android\.permission\.MANAGE_EXTERNAL_STORAGE" \/>/.test(configXml), "config.xml declares MANAGE_EXTERNAL_STORAGE — without it targetSdk 36 cannot read the store");
out(/system\.manageAllFiles\(/.test(index), "the Backlog's cannot-read branch offers the all-files grant");
