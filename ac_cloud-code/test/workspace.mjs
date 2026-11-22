// Driver for #1855 P1: the REAL src/cloud/workspace.js with a fake CloudWorkspace bridge (Cloud Drive's answers),
// plus the wiring of the REAL index.js (repoUrl asks Drive first, the Git tab uses Drive's list, the Backlog's
// "not on this device" state offers "Clone with Cloud Drive") and rule-16 field parity of the Git tab and the
// Backlog states against test/fixtures/git-fields.before.json.
import { readFileSync } from "node:fs";
import { join } from "node:path";
const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const w = await import(`data:text/javascript,${encodeURIComponent(read("src/cloud/workspace.js"))}`);
const b = await import(`data:text/javascript,${encodeURIComponent(read("src/cloud/backlog-index.js"))}`);
const nav = JSON.parse(read("src/cloud/nav.json"));
const index = read("src/cloud/index.js");
const before = JSON.parse(read("test/fixtures/git-fields.before.json"));

const R = (name, volume, path, extra = {}) => ({ name, path: path || `/p/${volume}/${name}`, volume, branch: "main", lastSyncMs: 0, state: "", summary: "", ...extra });
const fake = (repos, o = {}) => ({
	listRepos: async () => ({ drive: true, repos }),
	findRepo: async (n) => ({ drive: true, repos: repos.filter((r) => r.name.toLowerCase() === n.toLowerCase()) }),
	requestClone: async (n) => ({ ok: true, reason: "", n }),
	...o,
});

// splitRepoPath / driveUrl
out(JSON.stringify(w.splitRepoPath("cloud-me-pvt-tasks/1___BACKLOG___/1.1.Product-Backlog/dist/")) === JSON.stringify({ name: "cloud-me-pvt-tasks", rest: "1___BACKLOG___/1.1.Product-Backlog/dist/" }), "splitRepoPath: the repo name is the first segment, the rest follows");
out(JSON.stringify(w.splitRepoPath("cloud-me-pvt-tasks")) === JSON.stringify({ name: "cloud-me-pvt-tasks", rest: "" }), "splitRepoPath: a bare repo name has an empty rest");

// urlFromDrive: Drive answers where the clone is, on ANY volume (incl. external)
const tasks = (v) => R("cloud-me-pvt-tasks", v);
out(await w.urlFromDrive(fake([tasks("emulated")]), "cloud-me-pvt-tasks/a/b/") === "file:///p/emulated/cloud-me-pvt-tasks/a/b/", "Drive's findRepo gives the url of a clone on Emulated, with the sub-path");
out(await w.urlFromDrive(fake([tasks("external")]), "cloud-me-pvt-tasks") === "file:///p/external/cloud-me-pvt-tasks/", "a clone on an external drive is found");
out(await w.urlFromDrive(fake([tasks("external"), tasks("emulated"), tasks("store")]), "cloud-me-pvt-tasks/x/") === "file:///p/store/cloud-me-pvt-tasks/x/", "several clones: store, then emulated, then external");
out(await w.urlFromDrive(fake([]), "cloud-me-pvt-tasks/x/") === null, "Drive answers none -> null (the caller falls back to its volume probe)");
out(await w.urlFromDrive(null, "cloud-me-pvt-tasks/x/") === null, "no bridge (plugin absent) -> null");
out(await w.urlFromDrive(fake([], { findRepo: async () => { throw new Error("binder died"); } }), "r/x") === null, "a throwing bridge never throws");
out(await w.urlFromDrive(fake([], { findRepo: () => new Promise(() => {}) }), "r/x", 40) === null, "a bridge that never answers is timed out");
out(await w.urlFromDrive(fake([], { findRepo: async () => ({ drive: false, repos: [R("r", "store")] }) }), "r/x") === null, "drive=false (Drive absent) is no answer even with rows");

// driveList
let l = await w.driveList(fake([R("a", "store"), { name: "", path: "x" }, null]));
out(l.drive === true && l.repos.length === 1, "driveList drops malformed rows");
l = await w.driveList(fake([], { listRepos: async () => ({ drive: false, repos: [] }) }));
out(l.drive === false, "driveList: Drive absent -> drive=false");

// columnsFor: the two declared columns stay (rule 16), external is added only when present
const vols = nav.repos.volumes;
let cols = w.columnsFor(vols, [R("a", "store"), R("b", "emulated"), R("c", "emulated")]);
out(cols.length === 2 && cols[0].id === "shared" && cols[1].id === "emulated" && cols[0].label === "Cloud-Shared-Store/Git" && cols[1].label === "Emulated/Git", "both declared columns are kept, in order, with their labels");
out(cols[0].items.length === 1 && cols[1].items.length === 2, "repos land in the column of their volume (store -> Cloud-Shared-Store/Git)");
cols = w.columnsFor(vols, [R("u", "external")]);
out(cols.length === 3 && cols[2].id === "external" && cols[2].items[0].name === "u" && cols[0].items.length === 0, "a repo on an external drive adds an External column, the two others stay (empty)");
cols = w.columnsFor(vols, [R("a", "store", "/s/git/a", { branch: "dev", summary: "dirty 2" })]);
const row = cols[0].items[0];
out(row.name === "a" && row.url === "file:///s/git/a" && row.branch === "dev" && row.info === "dev · dirty 2" && w.parentOf(cols[0].items) === "/s/git", "a row keeps name, url, branch and adds state; the column shows its parent folder");

// requestClone
out((await w.requestClone(fake([]), "cloud-me-pvt-tasks")).ok === true, "requestClone hands the name to Drive");
out(/not installed/.test((await w.requestClone(null, "r")).reason), "requestClone without the bridge says Drive is not installed");
out((await w.requestClone(fake([], { requestClone: async () => { throw new Error("boom"); } }), "r")).ok === false, "a throwing requestClone is a reason, not an exception");

// the Backlog state that offers the clone
out(b.notOnDevice({ state: "unavailable", reason: "The repository clone is not on this device (x). Clone ..." }) === true, "Backlog: 'not on this device' offers Clone with Cloud Drive");
out(b.notOnDevice({ state: "offline", reason: "The repository clone is not on this device (x)." }) === true, "Backlog: offline copy + not on this device also offers it");
out(b.notOnDevice({ state: "live", reason: "" }) === false && b.notOnDevice({ state: "unavailable", reason: "Cannot read x: storage access was denied." }) === false && b.notOnDevice(null) === false, "Backlog: live, denied and unusable states do not offer a clone");

// wiring of the real index.js
out(/import \{[^}]*urlFromDrive[^}]*\} from "\.\/workspace"/.test(index) && /const fromDrive = await urlFromDrive\(bridge\(\), relative\);/.test(index), "repoUrl asks Drive's findRepo first");
out(/firstExisting\(repoCandidates\(relative\), \(u\) => fsOperation\(u\)\.exists\(\), gitPath\(relative\)\)/.test(index) && /const set = localStorage\.getItem\(`cloud-code\.\$\{key\}`\);\n\tif \(set\) return set;\n\tconst fromDrive/.test(index), "the menu-set folder still wins and the volume probe stays as the fallback");
out(/requestClone\(bridge\(\), repoName\)/.test(index) && /"Clone with Cloud Drive"/.test(index) && /cloneWithDriveButton\(nav\.docs\.root\)/.test(index) && /cloneWithDriveButton\(nav\.backlog\.source_dir\.split\("\/"\)\[0\]\)/.test(index), "the Backlog offers 'Clone with Cloud Drive' (the Index and the Views reads)");
out(nav.docs.root === "cloud-me-pvt-tasks", "the clone request names cloud-me-pvt-tasks");
out(/const drive = await driveList\(bridge\(\)\);\n\tif \(drive\.drive && drive\.repos\.length\) \{/.test(index) && /parts\.forEach\(\(p\) => fillVolume\(p\.v, p\.body, p\.count, panel, into, onPick\)\)/.test(index), "the Git tab uses Drive's list when Drive answers, its own volume listing otherwise");

// rule 16 parity: nothing the Git tab and the Backlog had is gone
out(JSON.stringify(vols.map((v) => v.id)) === JSON.stringify(before.volume_ids) && JSON.stringify(vols.map((v) => v.label)) === JSON.stringify(before.volume_labels), "parity: the two volume columns are declared unchanged");
for (const f of before.repo_row_fields) out(index.includes(f), `parity: Git repo row keeps ${f}`);
for (const t of before.snippets) out(index.includes(t), `parity: Git tab keeps ${t.slice(0, 50)}`);
for (const t of before.backlog_states) out(index.includes(t), `parity: Backlog state keeps ${t.slice(0, 50)}`);

// ── #1855 P3 Workspace search and Changes (pure functions of workspace.js) ──
{
	const rs = [{ name: "cloud-me-pvt-tasks", branch: "main", path: "/a/Git/cloud-me-pvt-tasks" }, { name: "notes", branch: "dev", path: "/b/notes" }];
	out(w.filterRepos(rs, "").length === 2 && w.filterRepos(rs, "TASKS").length === 1 && w.filterRepos(rs, "dev")[0].name === "notes" && w.filterRepos(rs, "/b/").length === 1 && w.filterRepos(rs, "zzz").length === 0, "Workspace search narrows repos by name, branch or path, case-insensitively");
	const tree = { "r/": [{ name: ".git", url: "r/.git/", isDirectory: true }, { name: "a.md", url: "r/a.md" }, { name: "src", url: "r/src/", isDirectory: true }], "r/src/": [{ name: "A.md", url: "r/src/A.md" }, { name: "deep", url: "r/src/deep/", isDirectory: true }, { name: "x.js", url: "r/src/x.js" }], "r/src/deep/": [{ name: "b.md", url: "r/src/deep/b.md" }] };
	const ls = async (d) => { if (!tree[d]) throw new Error("denied"); return tree[d]; };
	const got = await w.searchFiles(ls, "r/", "md");
	out(got.hits.map((h) => h.url).join() === "r/a.md,r/src/A.md,r/src/deep/b.md" && !got.truncated, "file search walks the repo breadth-first by name, skips .git");
	out((await w.searchFiles(ls, "r/", "")).hits.length === 0 && (await w.searchFiles(ls, "r/", "md", { maxResults: 1 })).truncated === true && (await w.searchFiles(ls, "r/", "md", { maxVisited: 1 })).truncated === true && (await w.searchFiles(ls, "r/", "md", { maxDepth: 0 })).hits.length === 1, "file search is bounded (results, directories visited, depth) and an empty query finds nothing");
	out((await w.searchFiles(async () => { throw new Error("x"); }, "r/", "md")).hits.length === 0, "an unreadable directory is skipped, never fatal");
	const rows = w.changeRows([{ name: "b", path: "/b", volume: "store", state: "", branch: "main" }, { name: "a", path: "/a", volume: "emulated" }, { name: "c", path: "/c", volume: "store" }], { "/a": { state: "clean", branch: "dev" }, "/c": { state: "dirty", summary: "3 changed", lastSyncMs: 5, branch: "main" } });
	out(rows.map((r) => r.name).join() === "c,a,b" && rows[0].changed && rows[0].summary === "3 changed" && !rows[1].changed && rows[1].branch === "dev", "Changes rows put changed repos first and carry Drive's branch, state, summary and last sync");
	const calls = [];
	const fake = { requestClone: async (n) => { calls.push(n); return { ok: true, reason: "" }; }, status: async (p) => ({ drive: true, repo: { name: "x", path: p, state: "dirty" } }) };
	out((await w.handOffToDrive(fake, "cloud-me-pvt-tasks")).ok && calls[0] === "cloud-me-pvt-tasks" && (await w.handOffToDrive(null, "x")).ok === false, "commit and push hand off to Drive's Sync > Git page for that repo (Drive absent = a reason)");
	out((await w.driveStatus(fake, "/p")).repo.state === "dirty" && (await w.driveStatus(null, "/p")).drive === false && (await w.driveStatus({ status: async () => { throw new Error("x"); } }, "/p")).drive === false && (await w.driveStatus({ status: () => new Promise(() => {}) }, "/p", 20)).drive === false, "status(path) answers Drive's row; absent, failing and slow Drive = drive false");
}

// ── #1855 P4a contract v2: changed files, diffs, Drive's own Commit / Push screens ──
{
	const files = [
		{ relpath: "b.txt", status: "M", staged: false, added: 2, removed: 1 }, { relpath: "a.txt", status: "A", staged: true, added: 5, removed: 0 },
		{ relpath: "u.txt", status: "?", staged: false, added: 3 }, { relpath: "bad", status: "X", staged: true }, null, { status: "M" },
	];
	const v2 = { changedFiles: async (p) => ({ drive: true, files: p === "/r" ? files : [] }), diff: async (p, rel, max) => ({ drive: true, text: `diff --git a/${rel} b/${rel}\n+x\n`, max }),
		requestCommit: async (p) => ({ ok: p === "/r", reason: p === "/r" ? "" : "not listed" }), requestPush: async () => ({ ok: true, reason: "" }) };
	const got = await w.driveChanges(v2, "/r");
	out(got.drive && got.files.length === 3 && got.files.every((f) => f.relpath && w.STATUS_LABEL[f.status]) && got.files.find((f) => f.relpath === "u.txt").removed === 0, "driveChanges keeps valid rows (relpath, status, staged, counts) and drops malformed ones");
	out((await w.driveChanges(null, "/r")).drive === false && (await w.driveChanges({ changedFiles: async () => { throw new Error("old drive"); } }, "/r")).drive === false && (await w.driveChanges({ changedFiles: () => new Promise(() => {}) }, "/r", 20)).drive === false, "changedFiles: no bridge, a v1 Drive (throws) and a slow Drive are drive=false, never an exception");
	const g = w.groupChanges(got.files);
	out(g.staged.map((f) => f.relpath).join() === "a.txt" && g.unstaged.map((f) => f.relpath).join() === "b.txt,u.txt", "staged files are grouped apart from the rest, by path");
	const t = w.changeTotals(got.files);
	out(t.files === 3 && t.added === 10 && t.removed === 1, "totals count files and +/- lines");
	out((await w.driveDiff(v2, "/r", "a.txt")).startsWith("diff --git a/a.txt") && (await w.driveDiff(null, "/r", "a.txt")) === "" && (await w.driveDiff({ diff: async () => { throw new Error("x"); } }, "/r", "a")) === "", "driveDiff returns the text, and \"\" when Drive is absent or old");
	out(w.diffKind("+x") === "add" && w.diffKind("-x") === "del" && w.diffKind("@@ -1 +1 @@") === "hunk" && w.diffKind("+++ b/x") === "meta" && w.diffKind("--- a/x") === "meta" && w.diffKind("diff --git a b") === "meta" && w.diffKind(" ctx") === "ctx" && w.diffKind("... diff truncated") === "note", "diff lines are classified for syntax colour");
	const dl = w.diffLines("--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\n+new\n");
	out(dl.length === 5 && dl.map((l) => l.kind).join() === "meta,meta,hunk,del,add" && w.diffLines("").length === 0, "diffLines splits a unified diff, no trailing empty line");
	const c = await w.requestCommit(v2, "/r");
	out(c.ok && (await w.requestCommit(v2, "/other")).reason === "not listed" && (await w.requestPush(v2, "/r")).ok, "requestCommit / requestPush hand the path to Drive and relay its refusal");
	out(/not installed/.test((await w.requestPush(null, "/r")).reason) && (await w.requestCommit({ requestCommit: async () => { throw new Error("old"); } }, "/r")).ok === false, "no bridge or an old Drive: a reason, not an exception");
	// the native bridge exposes the four v2 calls
	const plug = read("src/plugins/cloudworkspace/www/cloudworkspace.js");
	const java = read("src/plugins/cloudworkspace/src/CloudWorkspacePlugin.java");
	for (const m of ["changedFiles", "diff", "requestCommit", "requestPush"]) out(plug.includes(`${m}: function`) && java.includes(`"${m}".equals(action)`), `the CloudWorkspace plugin exposes ${m}`);
	for (const m of ["listRepos", "findRepo", "requestClone", "status"]) out(plug.includes(`${m}: function`) && java.includes(`"${m}".equals(action)`), `parity: the v1 call ${m} is kept`);
	// Changes keeps its P3 fields and adds files + diff + Drive's Commit/Push screens
	out(/handOff\("Commit", requestCommit\)/.test(index) && /handOff\("Push", requestPush\)/.test(index) && !/handOffToDrive\(bridge/.test(index), "Changes: Commit / Push open Drive's own Commit screen (requestCommit / requestPush), not the clone hand-off");
	out(/diffView/.test(index) && /cloud-diff-line cloud-diff-\$\{l\.kind\}/.test(index) && /"Open in editor"/.test(index), "Changes: a file opens a syntax-coloured diff with Open in editor");
}
