// Driver for the #1855 P3 IDE shell parity (rule 16: regroup never removes). Loads the REAL tabs.js / bars.js / nav.json / index.js
// and holds them to test/fixtures/nav-pages.before.json (every pre-P3 tab, sub-page, drawer item and deep-link id). Prints one
// `CHECK <ok|bad> <text>` per assertion; test-cloud-nav.sh runs it and then runs it again against MUTATED copies, which must fail.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const mod = (p) => import(`data:text/javascript,${encodeURIComponent(read(p))}`);
const T = await mod("src/cloud/tabs.js");
const B = await mod("src/cloud/bars.js");
const nav = JSON.parse(read("src/cloud/nav.json"));
const build = JSON.parse(read("build.json"));
const index = read("src/cloud/index.js");
const dom = read("src/cloud/bars-dom.js");
const before = JSON.parse(readFileSync(join(import.meta.dirname || new URL(".", import.meta.url).pathname, "fixtures/nav-pages.before.json"), "utf8"));

const viewNames = [...nav.tabs, ...nav.parents].flatMap((t) => [t.view, ...(t.pages || []).map((p) => p.view).filter(Boolean)]);
let model = [];
try { model = T.buildTabs(nav, viewNames); } catch (e) { out(false, `buildTabs refused the new declaration: ${e.message}`); }
const tab = (id) => model.find((t) => t.id === id);

// 1. the new shell
const bottom = T.bottomTabs(model).map((t) => t.id).join();
out(bottom === "workspace,editor,agent,changes,run", `bottom bar is Workspace, Editor, Agent, Changes, Run (${bottom})`);
out(!T.bottomTabs(model).some((t) => t.id === "backlog") && tab("backlog")?.placement === "dropdown", "Backlog is off the bottom bar, a Parent Page in the first row's dropdown");
out(build.ui.bottom_nav.join() === bottom && build.ui.parent_pages.join() === "backlog" && build.ui.default_section === nav.default_tab, "build.json ui and nav.json declare the same bottom pages, Parent Pages and default");

// 2. every old sub-page keeps its name, icon and file, in some page of the new shell
for (const sp of before.sub_pages) {
	const found = model.flatMap((t) => t.pages.map((p) => ({ t, p }))).find((x) => x.p.id === sp.id);
	out(!!found && found.p.label === sp.label && found.p.icon === sp.icon && found.p.file === sp.file, `sub-page ${sp.tab}/${sp.id} kept (${found ? `now ${found.t.id}/${found.p.id}` : "MISSING"})`);
}
out(tab("backlog")?.pages.map((p) => p.id).join() === "sts,prj,pry,eff", "Backlog stays a full page with its sub-pages sts, prj, pry, eff");
out(tab("agent")?.pages.map((p) => p.id).join() === "chat,bat,mdl", "Agent holds Chat and Agents' Batches and Models as its sub-pages");
out(tab("run")?.pages.map((p) => p.id).join() === "browser,myterminal", "Run holds Browser and MyTerminal as its sub-pages");

// 3. every deep link still lands where the fixture says
for (const [id, want] of Object.entries(before.deep_links)) {
	const got = T.resolveTarget(model, nav.redirects, id);
	out(!!got && got.tab === want.tab && (got.page ?? null) === want.page && tab(got.tab)?.placement === want.placement, `deep link '${id}' lands on ${want.tab}${want.page ? "/" + want.page : ""} (${want.placement}); got ${got ? got.tab + (got.page ? "/" + got.page : "") : "nowhere"}`);
}
for (const id of before.bottom_tabs) out(T.resolveTarget(model, nav.redirects, id) !== null, `pre-P3 tab id '${id}' still resolves`);
const dl = T.resolveTarget(model, nav.redirects, before.default_tab);
out(nav.default_tab === before.default_lands.tab && dl?.page === before.default_lands.page, `the old default '${before.default_tab}' and the new default land on the Agent chat`);
// a deep link to a launch page launches, it does not become a panel
out(T.viewOf(tab("run"), "myterminal") === "myterminal" && T.select({ active: "agent", showPanel: "agent" }, tab("run"), ["myterminal"], "myterminal").launch === "run", "myterminal (deep link or Run page) launches the app and keeps the current page");
out(T.viewOf(tab("agent"), "bat") === "agents" && T.viewOf(tab("agent"), "chat") === "chat", "the Agent tab renders Chat and the backlog agent views by sub-page");
out(T.resolveTarget(model, nav.redirects, "no-such-page") === null, "an unknown id resolves to nothing (index.js ignores it)");

// 4. drawer items of every old page are still registered
for (const [page, m] of Object.entries(before.menus)) {
	const src = read(m.file);
	for (const id of m.ids) out(new RegExp(`id: ["\`]${id}["\`$]`).test(src), `drawer item '${id}' of the old ${page} page is still registered (${m.file})`);
}

// 5. the first row's dropdown reaches Backlog and every sub-page from every bottom page
for (const t of T.bottomTabs(model)) {
	const ids = B.dropdownEntries(model, t, T.viewOf(t, t.pages[0]?.id) && t.pages[0]?.id).map((e) => e.id);
	out(["backlog", "sts", "prj", "pry", "eff"].every((id) => ids.includes(id)), `dropdown on ${t.id} lists Backlog and sts, prj, pry, eff`);
}
const onBacklog = B.dropdownEntries(model, tab("backlog"), "pry");
out(onBacklog.filter((e) => e.id === "backlog").length === 1 && onBacklog.find((e) => e.id === "pry")?.selected === true && onBacklog.every((e) => e.group === "parent"), "on Backlog the dropdown lists it once, the open sub-page marked");
const dropdownClick = /e\.depth === 0 \? on\.onTab\(e\.tabId, null\) : on\.onPage\(e\.tabId, e\.id\)/.test(dom) && /dropdownEntries\(tabs, tab, pageId\)/.test(dom);
out(dropdownClick, "bars-dom.js draws the dropdown from dropdownEntries and a tap opens the page or sub-page");
out(/for \(const t of bottomTabs\(tabs\)\)/.test(dom), "bars-dom.js draws only the bottom pages as cells");
for (const r of before.first_row_roles) out(new RegExp(`"${r}"`).test(dom), `first row keeps ${r}`);

// 6. index.js wiring: ids resolve through resolveTarget, sub-page views re-render, launch pages launch
out(/resolveTarget\(tabs, nav\.redirects, id\)/.test(index) && /viewOf\(tab, page\)/.test(index) && /LAUNCH\[view\]\(\)/.test(index), "index.js resolves old ids, picks the page's view and launches launch pages");
out(/if \(view !== was\) return showTab\(tabId, pageId\)/.test(index), "index.js re-renders the panel when a sub-page has another view (Agent: Chat | Batches | Models)");
for (const v of ["workspace", "changes", "chat", "agents", "browser", "backlog"]) out(new RegExp(`^\\s*${v}: render\\w+,`, "m").test(index), `VIEWS has the ${v} renderer`);

// 7. Workspace keeps the old Git tab (rule 16) and Changes hands commit/push to Drive
out(/renderGit\(panel, pickerBox, pick\)/.test(index) && /async function renderGit\(panel, into, onPick\)/.test(index), "Workspace hosts the old Git tab's two volume columns (renderGit) as its repo picker");
out(/handOffToDrive\(bridge\(\), r\.name\)/.test(index) && /Commit/.test(index) && /Push/.test(index) && !/isomorphic-git|libgit2|jgit/i.test(index), "Changes hands commit and push to Cloud Drive; no git engine in Code");
out(/driveStatus\(bridge\(\), r\.path\)/.test(index), "Changes reads each repo's status from Drive's repo-workspace status(path)");
