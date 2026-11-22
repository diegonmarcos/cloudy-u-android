// cloud-code <-> Cloud Drive's repository workspace (#1855 P1, libs:repo-workspace). Cloud Drive owns git:
// this page builds no sync engine, it ASKS Drive where a repository is, lists what Drive lists, and hands a
// missing repository back to Drive's clone flow. Pure logic with an injected bridge (index.js passes the
// CloudWorkspace Cordova plugin; test/workspace.mjs passes fakes), so every branch is testable off-device.
//
// A bridge call answers {drive, repos}: drive=false means Drive is absent / unbound (the caller falls back
// to its own volume probe), drive=true with an empty list is an ANSWER ("not on any volume Drive knows").

export const DRIVE_TIMEOUT_MS = 4000;
export const VOLUME_ORDER = ["store", "emulated", "external"];
export const NONE = Object.freeze({ drive: false, repos: [] });

export function bridge() {
	return typeof window !== "undefined" && window.CloudWorkspace ? window.CloudWorkspace : null;
}

// A bridge call that never throws and never waits longer than timeoutMs: Drive absent / slow = NONE.
async function ask(b, method, args, timeoutMs) {
	if (!b || typeof b[method] !== "function") return NONE;
	try {
		const got = await Promise.race([
			Promise.resolve().then(() => b[method](...args)),
			new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs)),
		]);
		if (!got || got.drive !== true) return NONE;
		return { drive: true, repos: Array.isArray(got.repos) ? got.repos.filter((r) => r && r.name && r.path) : [] };
	} catch {
		return NONE;
	}
}

export const driveList = (b, timeoutMs = DRIVE_TIMEOUT_MS) => ask(b, "listRepos", [], timeoutMs);
export const driveFind = (b, name, timeoutMs = DRIVE_TIMEOUT_MS) => ask(b, "findRepo", [name], timeoutMs);

// The clone to use when Drive holds several: the volume order of the Git tab (store, emulated, external).
export function pickClone(repos) {
	const rank = (r) => { const i = VOLUME_ORDER.indexOf(r.volume); return i < 0 ? VOLUME_ORDER.length : i; };
	return [...(repos || [])].sort((a, b) => rank(a) - rank(b))[0] || null;
}

// "cloud-me-pvt-tasks/1___BACKLOG___/.../dist/" -> {name: "cloud-me-pvt-tasks", rest: "1___BACKLOG___/.../dist/"}.
export function splitRepoPath(relative) {
	const s = String(relative || "").replace(/^\/+/, "");
	const i = s.indexOf("/");
	return i < 0 ? { name: s, rest: "" } : { name: s.slice(0, i), rest: s.slice(i + 1) };
}

export const driveUrl = (row, rest) => `file://${row.path.replace(/\/+$/, "")}/${rest || ""}`;

// Where Drive says `relative` is (a url), or null when Drive is absent or knows no such clone.
export async function urlFromDrive(b, relative, timeoutMs = DRIVE_TIMEOUT_MS) {
	const { name, rest } = splitRepoPath(relative);
	if (!name) return null;
	const got = await driveFind(b, name, timeoutMs);
	const row = pickClone(got.repos);
	return row ? driveUrl(row, rest) : null;
}

// Drive's list as the Git tab's columns: the two declared volumes keep their columns (rule 16), keyed
// store -> shared and emulated -> emulated; repos on an attached drive get a third "External" column that
// exists only when there are some. Every row keeps name, url, branch and adds info (state / last sync).
export function columnsFor(volumes, repos) {
	const idOf = (v) => (v.id === "shared" ? "store" : v.id);
	const rowOf = (r) => ({
		name: r.name, url: `file://${r.path}`, branch: r.branch || "", volume: r.volume, path: r.path,
		info: [r.branch, r.summary || r.state].filter(Boolean).join(" · "),
	});
	const cols = volumes.map((v) => ({ id: v.id, label: v.label, volume: idOf(v), items: repos.filter((r) => r.volume === idOf(v)).map(rowOf) }));
	const known = new Set(cols.map((c) => c.volume));
	const extra = repos.filter((r) => !known.has(r.volume));
	if (extra.length) cols.push({ id: "external", label: "External", volume: "external", items: extra.map(rowOf) });
	return cols;
}

// The parent folder the column's repos live in, for the path line; "" when the column is empty.
export function parentOf(items) {
	const p = items[0]?.path;
	return p ? p.slice(0, p.lastIndexOf("/")) : "";
}

// Ask Drive to clone `name`: its clone dialog opens (Sync, Git), prefilled; returns {ok, reason} at once.
export async function requestClone(b, name) {
	if (!b || typeof b.requestClone !== "function") return { ok: false, reason: "Cloud Drive is not installed" };
	try {
		const got = await b.requestClone(name);
		return { ok: !!got?.ok, reason: String(got?.reason || "") };
	} catch (err) {
		return { ok: false, reason: String(err?.message || err) };
	}
}

// ── #1855 P3 IDE shell: Workspace search and Changes (pure; index.js passes the real Acode file system and bridge) ──

// Drive's status of one clone: {drive, repo|null}. Absent / slow Drive = {drive: false, repo: null}.
export async function driveStatus(b, path, timeoutMs = DRIVE_TIMEOUT_MS) {
	if (!b || typeof b.status !== "function") return { drive: false, repo: null };
	try {
		const got = await Promise.race([
			Promise.resolve().then(() => b.status(path)),
			new Promise((resolve) => setTimeout(() => resolve(null), timeoutMs)),
		]);
		if (!got || got.drive !== true) return { drive: false, repo: null };
		return { drive: true, repo: got.repo && got.repo.name ? got.repo : null };
	} catch {
		return { drive: false, repo: null };
	}
}

// The Workspace's search box narrows the repo picker: a case-insensitive match on the name, the branch or the path; empty = all.
export function filterRepos(repos, query) {
	const q = String(query || "").trim().toLowerCase();
	if (!q) return [...repos];
	return repos.filter((r) => [r.name, r.branch, r.path || r.url].some((v) => String(v || "").toLowerCase().includes(q)));
}

// File-name search under one repository: a bounded breadth-first walk (depth, visited and result caps, .git skipped) over an
// injected lsDir(url) -> [{name, url, isDirectory}]. A directory that cannot be read is skipped, never fatal.
export async function searchFiles(lsDir, rootUrl, query, { maxDepth = 6, maxVisited = 800, maxResults = 100 } = {}) {
	const q = String(query || "").trim().toLowerCase();
	if (!q) return { hits: [], truncated: false };
	const hits = [];
	let visited = 0;
	let truncated = false;
	let level = [rootUrl];
	for (let depth = 0; depth <= maxDepth && level.length; depth++) {
		const next = [];
		for (const dir of level) {
			if (++visited > maxVisited) { truncated = true; return { hits, truncated }; }
			let entries = [];
			try { entries = (await lsDir(dir)) || []; } catch { continue; }
			for (const e of entries) {
				if (e.name === ".git") continue;
				if (e.isDirectory) next.push(e.url);
				else if (e.name.toLowerCase().includes(q)) {
					hits.push({ name: e.name, url: e.url });
					if (hits.length >= maxResults) return { hits, truncated: true };
				}
			}
		}
		level = next;
	}
	return { hits, truncated };
}

const CLEAN_STATES = ["", "clean", "uptodate", "up_to_date", "synced"];

// The Changes page rows: one per repo with Drive's status merged over the listing, changed repos first, then by name.
// `changed` is true for any state Drive reports other than clean (a repo whose state is not read yet is not called changed).
export function changeRows(repos, statuses = {}) {
	return repos.map((r) => {
		const s = statuses[r.path] || r;
		const state = String(s.state || "");
		return {
			name: r.name, path: r.path, volume: r.volume, branch: s.branch || r.branch || "",
			state, summary: String(s.summary || ""), lastSyncMs: s.lastSyncMs || r.lastSyncMs || 0,
			changed: !CLEAN_STATES.includes(state.toLowerCase()),
		};
	}).sort((a, b) => (a.changed === b.changed ? a.name.localeCompare(b.name) : a.changed ? -1 : 1));
}

// Commit and push belong to Cloud Drive (it owns git): the hand-off opens Drive's Sync > Git page for the repository, the same
// intent as the clone flow (requestClone), so Code builds no git engine.
export const handOffToDrive = (b, name) => requestClone(b, name);
