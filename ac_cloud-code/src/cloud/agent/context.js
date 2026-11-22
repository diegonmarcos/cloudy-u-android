// cloud-code agent loop (#1855 P4b): the context chips (@file @selection @repo @ticket @ci-run @problem), the ticket the session starts
// from, and the CI / ticket follow-up. Pure, no imports; the host (index.js) supplies the file reads and the network.

export const CHIPS = Object.freeze(["file", "selection", "repo", "ticket", "ci-run", "problem"]);
export const BLOCK_MAX = 8000;

/** A bounded, labelled context block: ```ctx:<label>``` fence, truncated with a visible marker. */
export function block(label, body, max = BLOCK_MAX) {
	const t = String(body ?? "");
	const cut = t.length > max;
	const safe = String(label).replace(/[`\n\r]/g, " ").slice(0, 120);
	return `[@${safe}]\n\`\`\`\n${cut ? t.slice(0, max) : t}${cut ? `\n... truncated at ${max} of ${t.length} characters` : ""}\n\`\`\`\n`;
}

export const fileBlock = (relpath, text) => block(`file ${relpath}`, text);
export const selectionBlock = (name, text) => (String(text || "").trim() ? block(`selection ${name || "editor"}`, text) : "");
export const repoBlock = (repo, listing) => block(`repo ${repo.name}`, [`name: ${repo.name}`, `branch: ${repo.branch || "?"}`, repo.summary || repo.state ? `state: ${repo.summary || repo.state}` : "", "top level:", ...(listing || []).slice(0, 80)].filter(Boolean).join("\n"));
export const problemBlock = (list) => (list && list.length ? block("problems", list.slice(0, 50).map((p) => `${p.file ? `${p.file}:` : ""}${p.line ?? "?"} [${p.severity || "error"}] ${p.message}`).join("\n")) : "");
export const ciBlock = (run) => block(`ci-run ${run.slug ? `${run.slug}#` : ""}${run.id}`, [`GitHub Actions run ${run.id}`, run.slug ? `repo: ${run.slug}` : "", run.url ? `url: ${run.url}` : "", run.note || "(text only: no log was fetched)"].filter(Boolean).join("\n"));

// ── the ticket ──
/** A backlog ticket JSON (the cloud-me-pvt-tasks file) -> {id, subject, description}; null when it is not one. */
export function ticketFromJson(text) {
	let j;
	try { j = typeof text === "string" ? JSON.parse(text) : text; } catch { return null; }
	if (!j || typeof j !== "object" || !j.id) return null;
	return { id: String(j.id), subject: String(j.subject || ""), description: String(j.description || "") };
}
export const ticketBlock = (t) => block(`ticket #${t.id}`, `#${t.id} ${t.subject}\n\n${t.description}`);
/** The first message of a ticket-bound session. */
export const startContext = (t, repo) => `${ticketBlock(t)}\nWork on this ticket in the repository ${repo.name} (branch ${repo.branch || "main"}). Propose a plan, then the file edits.`;
export const normalizeTicketId = (s) => { const m = String(s || "").match(/\d+/); return m ? m[0] : ""; };
/** Where the ticket file may be, from a directory listing of tasks/: [{name, isDirectory}] -> session dir names to look in. */
export const ticketFileName = (id) => `${normalizeTicketId(id)}.json`;

// ── CI ──
/** "https://github.com/o/r/actions/runs/123/job/9" | "o/r#123" | "123" -> {id, slug, url} or null (slug "" when only an id was given). */
export function parseRun(input, defaultSlug = "") {
	const s = String(input || "").trim();
	let m = s.match(/github\.com\/([\w.-]+\/[\w.-]+)\/actions\/runs\/(\d+)/i);
	if (m) return { id: m[2], slug: m[1], url: `https://github.com/${m[1]}/actions/runs/${m[2]}` };
	m = s.match(/^([\w.-]+\/[\w.-]+)#(\d+)$/);
	if (m) return { id: m[2], slug: m[1], url: `https://github.com/${m[1]}/actions/runs/${m[2]}` };
	m = s.match(/^\d{5,}$/);
	if (m) return { id: s, slug: defaultSlug, url: defaultSlug ? `https://github.com/${defaultSlug}/actions/runs/${s}` : "" };
	return null;
}

/** "owner/repo" from a .git/config text (the origin url), or "". */
export function githubSlug(configText) {
	const m = String(configText || "").match(/\[remote "origin"\][^[]*?url\s*=\s*(\S+)/);
	if (!m) return "";
	const u = m[1].match(/github\.com[:/]([\w.-]+\/[\w.-]+?)(?:\.git)?\/?$/i);
	return u ? u[1] : "";
}

export const runsApiUrl = (slug, n = 5) => `https://api.github.com/repos/${slug}/actions/runs?per_page=${n}`;
export const actionsPageUrl = (slug) => `https://github.com/${slug}/actions`;

/** The public REST answer -> rows {id, name, status, conclusion, sha, branch, url}; [] when it is not a run list. */
export function parseRuns(json) {
	const list = json && Array.isArray(json.workflow_runs) ? json.workflow_runs : [];
	return list.slice(0, 10).map((r) => ({ id: String(r.id), name: String(r.name || r.display_title || ""), status: String(r.status || ""), conclusion: String(r.conclusion || ""), sha: String(r.head_sha || "").slice(0, 9), branch: String(r.head_branch || ""), url: String(r.html_url || "") }));
}
export const runState = (r) => (r.status === "completed" ? r.conclusion || "completed" : r.status || "unknown");

/** What to show for a repo's CI given the API answer's HTTP status: {kind: "runs"|"link"|"limit"|"error", ...}. 404/401/403-without-limit = private. */
export function ciOutcome(slug, status, json) {
	if (!slug) return { kind: "link", text: "The repository has no GitHub origin: no runs to follow.", url: "" };
	if (status === 200) return { kind: "runs", runs: parseRuns(json) };
	if (status === 404 || status === 401) return { kind: "link", text: `${slug} is private or has no runs the public API shows: open its Actions page.`, url: actionsPageUrl(slug) };
	if (status === 403 || status === 429) return { kind: "limit", text: "GitHub's public API rate limit is reached: open the Actions page.", url: actionsPageUrl(slug) };
	return { kind: "error", text: `GitHub answered ${status || "nothing"}.`, url: actionsPageUrl(slug) };
}

/** The ticket note the user copies into the backlog (nothing is written by the phone in this phase). */
export function ticketNote({ ticket, repo, files, run, when }) {
	const list = (files || []).slice(0, 40);
	return [
		`Agent session note${ticket ? ` for #${ticket.id}` : ""}${when ? ` (${when})` : ""}`,
		repo ? `repo: ${repo.name}${repo.branch ? ` (${repo.branch})` : ""}` : "",
		`files changed (${(files || []).length}): ${list.join(", ") || "none"}${(files || []).length > list.length ? ", ..." : ""}`,
		run ? `CI run: ${run.id}${run.url ? ` ${run.url}` : ""}${run.status ? ` (${runState(run)})` : ""}` : "CI run: not followed yet",
	].filter(Boolean).join("\n");
}

/**
 * Find ticket `id` in a backlog clone: tasks/<session-id>/<id>.json. `ls(url) -> [{name, url, isDirectory}]`, `read(url) -> text`, both injected.
 * Bounded (at most 300 session folders); a folder that cannot be listed is skipped. Returns the ticket or null.
 */
export async function locateTicket(ls, read, tasksUrl, id, maxDirs = 300) {
	const want = ticketFileName(id);
	if (want === ".json") return null;
	let dirs;
	try { dirs = await ls(tasksUrl); } catch { return null; }
	let n = 0;
	for (const d of dirs.filter((e) => e.isDirectory)) {
		if (++n > maxDirs) break;
		let files;
		try { files = await ls(d.url); } catch { continue; }
		const f = files.find((e) => !e.isDirectory && e.name === want);
		if (f) { try { const t = ticketFromJson(await read(f.url)); if (t && t.id === normalizeTicketId(id)) return t; } catch { /* unreadable: keep looking */ } }
	}
	return null;
}
