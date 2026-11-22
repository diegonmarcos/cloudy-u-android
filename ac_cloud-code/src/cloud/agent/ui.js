// cloud-code agent loop UI (#1855 P4b, D3): the context chips row of the composer and the session's agent panel (plan, pending edits with
// their full diff and Accept / Reject per file, Review in Changes, Follow CI, Ticket note, session log). All decisions are the pure
// modules next to this file (edits, pending, log, context); the host (index.js) supplies the file system, the Drive bridge and the
// network. Nothing is written without a tap on Accept after the diff was shown, and nothing is committed here: Cloud Drive's Commit
// screen (P4a) finishes the job.
import toast from "components/toast";
import { el, icon } from "../dom";
import { option, sheet, page, closeOverlay } from "../chrome";
import { diffLines } from "../workspace";
import { FORMAT_INSTRUCTION, parseReply, resolveInRoot, MAX_FILE_BYTES } from "./edits";
import { makeChange, move, stillBase, unifiedDiff, allDecided, writtenPaths, counts } from "./pending";
import { addLog, logLines, exportLog } from "./log";
import * as C from "./context";

export { FORMAT_INSTRUCTION };

const trace = (kind, text) => console.log(`cloud-code agent ${kind}: ${text}`);

export function createAgent(host, deps) {
	// deps: {session() -> the open chat session, persist(), redraw(), setInput(text), appendInput(text), startSession(opts)}
	const log = (s, kind, text, extra) => { addLog(s, kind, text, extra); trace(kind, `${extra?.path ? `${extra.path} ` : ""}${text}`.slice(0, 200)); };

	const urlOf = (s, rel) => resolveInRoot(s.repo.path, rel);

	// ── a reply from the model -> a plan and pending edits ──
	async function onReply(s, reply) {
		if (!s.repo || !reply || reply.error || !reply.content) return;
		const got = parseReply(reply.content);
		if (got.error) { log(s, "failed", `reply not usable: ${got.error}`); deps.persist(); return; }
		if (got.plan) { s.plan = got.plan; log(s, "plan", got.plan); }
		for (const r of got.rejected) log(s, "rejected", `refused before any diff: ${r.reason}`, { path: r.path });
		s.changes = (s.changes || []).filter((c) => c.state === "pending" || c.state === "accepted");
		for (const e of got.edits) {
			const url = urlOf(s, e.path);
			if (!url) { log(s, "rejected", "refused: outside the repository root", { path: e.path }); continue; }
			let current = null;
			try { if (await host.exists(url)) current = await host.readText(url); } catch (err) { log(s, "failed", `cannot read the current file: ${err?.message || err}`, { path: e.path }); continue; }
			if (current !== null && current.length > MAX_FILE_BYTES) { log(s, "rejected", "the current file is too large to diff here", { path: e.path }); continue; }
			const c = makeChange(e, current);
			s.changes.push(c);
			log(s, "proposed", `${c.isNew ? "new file" : c.unchanged ? "no change" : "edit"}, ${c.bytes} bytes`, { path: c.path });
		}
		deps.persist();
		deps.redraw();
	}

	const find = (s, path) => (s.changes || []).findIndex((c) => c.path === path && (c.state === "pending" || c.state === "accepted"));

	function reject(s, path) {
		const i = find(s, path);
		if (i < 0) return;
		const r = move(s.changes[i], "rejected", { by: "user" });
		if (!r.ok) return toast(r.reason);
		s.changes[i] = r.change;
		log(s, "rejected", "rejected by the user", { path });
		deps.persist();
		deps.redraw();
	}

	async function accept(s, path) {
		const i = find(s, path);
		if (i < 0 || s.changes[i].state !== "pending") return;
		const content = s.changes[i].newContent;
		const a = move(s.changes[i], "accepted", { by: "user" });
		if (!a.ok) return toast(a.reason);
		s.changes[i] = a.change;
		log(s, "accepted", "accepted by the user", { path });
		const url = urlOf(s, path);
		const settle = (to, reason, kind, text) => { const r = move(s.changes[i], to, { reason }); if (r.ok) s.changes[i] = r.change; log(s, kind, text, { path }); };
		if (!url) settle("failed", "outside the root", "failed", "refused: outside the repository root");
		else {
			try {
				const now = (await host.exists(url)) ? await host.readText(url) : null;
				if (!stillBase(a.change, now)) settle("stale", "the file changed since it was proposed", "stale", "not written: the file changed since the diff was shown");
				else {
					await host.writeText(s.repo.path, path, content);
					settle("written", "", "written", "written into the working tree (not committed)");
				}
			} catch (err) {
				settle("failed", String(err?.message || err), "failed", `write failed: ${err?.message || err}`);
			}
		}
		deps.persist();
		deps.redraw();
		return s.changes[i].state;
	}

	// ── the agent panel ──
	function diffNode(s, c) {
		const text = c.newContent ? unifiedDiff(c.path, c.base, c.newContent) : "";
		const lines = diffLines(text);
		return el("pre", { className: "cloud-diff cloud-agent-diff", "data-diff": c.path },
			...(lines.length ? lines.map((l) => el("div", { className: `cloud-diff-line cloud-diff-${l.kind}` }, l.text || " ")) : [el("div", { className: "cloud-muted" }, "(the diff is not kept after the decision)")]));
	}

	function changeCard(s, c) {
		const decided = c.state !== "pending";
		return el("div", { className: `cloud-agent-edit st-${c.state}`, "data-edit": c.path, "data-state": c.state },
			el("div", { className: "cloud-row" },
				el("span", { className: "cloud-repo-name" }, c.path), el("span", { className: "cloud-change-state" }, c.state), el("span", { className: "cloud-muted" }, `${c.isNew ? "new file · " : ""}${c.bytes} bytes${c.reason ? ` · ${c.reason}` : ""}`)),
			decided ? null : diffNode(s, c),
			decided ? null : el("div", { className: "cloud-ws-actions" },
				el("button", { className: "cloud-row", "data-verb": "accept", disabled: c.unchanged, title: c.unchanged ? "the file already has this content" : "Write this file into the working tree", onclick: () => accept(s, c.path) }, icon("check"), "Accept"),
				el("button", { className: "cloud-row", "data-verb": "reject", onclick: () => reject(s, c.path) }, icon("clearclose"), "Reject")));
	}

	function panel(s) {
		if (!s.repo) return null;
		const ch = s.changes || [];
		const n = counts(ch);
		const wrote = writtenPaths(ch);
		return el("div", { className: "cloud-agent-panel" },
			el("div", { className: "cloud-row cloud-agent-head" }, icon("git"), el("strong", {}, `${s.repo.name}`), el("span", { className: "cloud-muted" }, s.repo.branch || ""), s.ticket ? el("span", { className: "cloud-chip" }, `#${s.ticket.id}`) : null,
				el("button", { className: "cloud-ctl", "data-verb": "log", onclick: () => logPage(s) }, icon("history"), el("span", {}, `Log (${(s.log || []).length})`))),
			s.plan ? el("div", { className: "cloud-agent-plan", "data-plan": "1" }, el("strong", {}, "Plan"), el("pre", {}, s.plan)) : null,
			...ch.map((c) => changeCard(s, c)),
			ch.length ? el("div", { className: "cloud-muted cloud-agent-counts" }, `${n.pending} pending · ${n.written} written · ${n.rejected} rejected${n.stale ? ` · ${n.stale} stale` : ""}${n.failed ? ` · ${n.failed} failed` : ""}`) : null,
			wrote.length || allDecided(ch) ? el("div", { className: "cloud-ws-actions" },
				el("button", { className: "cloud-row", "data-verb": "review", onclick: () => host.showTab("changes") }, icon("compare_arrows"), "Review in Changes"),
				wrote.length ? el("button", { className: "cloud-row", "data-verb": "commit", onclick: async () => { const g = await host.requestCommit(s.repo.path); toast(g.ok ? "Cloud Drive opened: commit there" : g.reason || "Cloud Drive did not open"); } }, icon("git"), "Commit in Cloud Drive") : null,
				wrote.length ? el("button", { className: "cloud-row", "data-verb": "ci", onclick: () => ciPage(s) }, icon("sync"), "Follow CI") : null,
				wrote.length ? el("button", { className: "cloud-row", "data-verb": "note", onclick: () => ticketNote(s) }, icon("copy"), "Ticket note") : null) : null);
	}

	// ── CI and ticket follow-up ──
	async function slugOf(s) {
		try { return C.githubSlug(await host.readText(`file://${s.repo.path.replace(/\/+$/, "")}/.git/config`)); } catch { return ""; }
	}

	async function ciPage(s) {
		const body = el("div", { className: "cloud-list" }, "Reading GitHub Actions …");
		page(`CI · ${s.repo.name}`, null, body);
		const slug = await slugOf(s);
		let out;
		if (!slug) out = C.ciOutcome("", 0, null);
		else {
			let got = { status: 0, json: null };
			try { got = await host.fetchJson(C.runsApiUrl(slug)); } catch { /* offline: shown as an error below */ }
			out = C.ciOutcome(slug, got.status, got.json);
		}
		log(s, "ci", out.kind === "runs" ? `${slug}: ${out.runs.length} runs, latest ${out.runs[0] ? `${out.runs[0].id} ${C.runState(out.runs[0])}` : "none"}` : `${slug || "no origin"}: ${out.kind}`);
		if (out.kind === "runs") s.ciRun = out.runs[0] ? { ...out.runs[0] } : s.ciRun;
		deps.persist();
		body.replaceChildren(...(out.kind === "runs"
			? (out.runs.length ? out.runs.map((r) => el("div", { className: "cloud-row cloud-ci-run", "data-run": r.id },
				el("span", { className: "cloud-change-state" }, C.runState(r)), el("span", { className: "cloud-repo-name" }, `${r.name} ${r.branch} ${r.sha}`), el("span", { className: "cloud-muted" }, r.id),
				el("button", { className: "cloud-ctl", title: "Open on GitHub", onclick: () => host.openUrl(r.url) }, icon("open_in_browser")),
				el("button", { className: "cloud-ctl", title: "Use as @ci-run context", onclick: () => { deps.appendInput(C.ciBlock({ id: r.id, slug, url: r.url, note: `${r.name} on ${r.branch} ${r.sha}: ${C.runState(r)}` })); toast("Added to the composer"); } }, icon("add"))))
				: [el("p", { className: "cloud-muted" }, "No runs yet.")])
			: [el("p", { className: "cloud-muted" }, out.text), out.url ? el("button", { className: "cloud-row", onclick: () => host.openUrl(out.url) }, icon("open_in_browser"), "Open the Actions page") : null]));
	}

	function ticketNote(s) {
		const text = C.ticketNote({ ticket: s.ticket, repo: s.repo, files: writtenPaths(s.changes || []), run: s.ciRun, when: new Date().toISOString().slice(0, 16) });
		host.copy(text);
		log(s, "note", "ticket note copied (the backlog is not written by the phone)");
		deps.persist();
		page("Ticket note", null, el("p", { className: "cloud-muted" }, "Copied to the clipboard. Paste it into the ticket; this app does not write to the backlog repository."), el("pre", { className: "cloud-agent-note" }, text));
	}

	function logPage(s) {
		const text = (s.log || []).length ? logLines(s).join("\n") : "(nothing logged yet)";
		page(`Session log · ${(s.log || []).length}`, null,
			el("div", { className: "cloud-ws-actions" }, el("button", { className: "cloud-row", onclick: () => { host.copy(exportLog(s)); toast("Log copied"); } }, icon("copy"), "Copy the log")),
			el("pre", { className: "cloud-agent-log", "data-log": "1" }, text));
	}

	// ── the context chips ──
	async function ensureRepo(s) {
		if (s.repo) return s.repo;
		await bindRepo();
		return deps.session().repo || null;
	}

	function bindRepo(ticket = null) {
		return new Promise(async (resolve) => {
			const got = await host.repos();
			if (!got.drive || !got.repos.length) { toast(got.drive ? "Cloud Drive lists no repositories: clone one first" : "Cloud Drive is not installed or not answering"); return resolve(null); }
			sheet("Bind a repository", got.repos.map((r) => option(r.name, { iconName: "git", sub: `${r.branch || ""} · ${r.volume}`, run: () => { deps.startSession({ repo: r, keep: !ticket, ticket }); if (ticket) host.showTab("chat"); resolve(r); } })), ticket ? `Start an agent session for #${ticket.id} in this repository (from the Workspace).` : "The session reads and proposes edits only inside this repository.");
		});
	}

	function browse(s, dirUrl, rootUrl) {
		host.list(dirUrl).then((entries) => {
			const rows = [];
			if (dirUrl.replace(/\/+$/, "") !== rootUrl.replace(/\/+$/, "")) rows.push(option("..", { iconName: "arrow_back", run: () => browse(s, dirUrl.replace(/\/+$/, "").replace(/\/[^/]*$/, ""), rootUrl) }));
			for (const e of entries.filter((x) => x.name !== ".git").sort((a, b) => (a.isDirectory === b.isDirectory ? a.name.localeCompare(b.name) : a.isDirectory ? -1 : 1)).slice(0, 200)) {
				rows.push(option(e.name, { iconName: e.isDirectory ? "folder" : "insert_drive_file", run: e.isDirectory ? () => browse(s, e.url, rootUrl) : async () => {
					const rel = e.url.replace(/\/+$/, "").slice(rootUrl.replace(/\/+$/, "").length + 1);
					try { deps.appendInput(C.fileBlock(rel, await host.readText(e.url))); log(s, "context", `@file ${rel}`); } catch (err) { toast(`Cannot read ${e.name}: ${err?.message || err}`); }
				} }));
			}
			sheet(`@file · ${dirUrl.replace(rootUrl.replace(/\/+$/, ""), "") || "/"}`, rows, "Pick a file from the repository tree: its text (bounded) is added to the message.");
		}).catch((err) => toast(`Cannot list: ${err?.message || err}`));
	}

	const chipRun = {
		file: async () => { const r = await ensureRepo(deps.session()); if (r) browse(deps.session(), `file://${r.path.replace(/\/+$/, "")}`, `file://${r.path.replace(/\/+$/, "")}`); },
		selection: () => { const sel = host.selection(); const b = C.selectionBlock(sel.name, sel.text); if (!b) return toast("Nothing is selected in the editor"); deps.appendInput(b); log(deps.session(), "context", `@selection ${sel.name || ""}`); },
		repo: async () => {
			const s = deps.session(); const r = await ensureRepo(s); if (!r) return;
			let listing = [];
			try { listing = (await host.list(`file://${r.path.replace(/\/+$/, "")}`)).filter((e) => e.name !== ".git").map((e) => `${e.isDirectory ? "d" : "f"} ${e.name}`); } catch { /* the block states what it has */ }
			deps.appendInput(C.repoBlock(r, listing)); log(deps.session(), "context", `@repo ${r.name}`);
		},
		ticket: async () => {
			const s = deps.session();
			if (s.ticket) { deps.appendInput(C.ticketBlock(s.ticket)); return log(s, "context", `@ticket #${s.ticket.id}`); }
			const id = C.normalizeTicketId(await host.prompt("Ticket id (from the backlog clone)", ""));
			if (!id) return;
			const t = await host.loadTicket(id);
			if (!t) return toast(`Ticket #${id} was not found in the backlog clone`);
			s.ticket = t; deps.appendInput(C.ticketBlock(t)); log(s, "context", `@ticket #${t.id}`); deps.persist(); deps.redraw();
		},
		"ci-run": async () => {
			const s = deps.session();
			const slug = s.repo ? await slugOf(s) : "";
			const input = await host.prompt("GitHub Actions run URL or id", "");
			if (!input) return;
			const run = C.parseRun(input, slug);
			if (!run) return toast("Not a run URL or id (https://github.com/o/r/actions/runs/123)");
			deps.appendInput(C.ciBlock(run)); log(s, "context", `@ci-run ${run.id}`);
		},
		problem: () => { const b = C.problemBlock(host.problems()); if (!b) return toast("The editor reports no problems"); deps.appendInput(b); log(deps.session(), "context", "@problem"); },
	};

	function chipsRow() {
		const s = deps.session();
		return el("div", { className: "cloud-agent-chips", "data-chips": "1" },
			el("button", { className: "cloud-ctl", "data-chip": "repo-bind", title: s.repo ? `Bound to ${s.repo.name}: change` : "Bind this chat to a repository from the Workspace", onclick: () => bindRepo() }, icon("git"), el("span", {}, s.repo ? s.repo.name : "Repo")),
			...C.CHIPS.map((id) => el("button", { className: "cloud-ctl", "data-chip": id, title: `Insert @${id} context`, onclick: () => chipRun[id]().catch((e) => toast(String(e?.message || e))) }, el("span", {}, `@${id}`))));
	}

	// ── the test hook (#1855 P4b): the emulator walk drops agent-test-flow.json into this app's own external data dir (adb only) ──
	// {ticket:{id,subject,description}, repo:"name", reply:"<plan>..</plan><edits>[..]</edits>", accept:true}: the SAME code paths run
	// (start from a ticket, onReply parses and proposes, accept writes), but the model reply comes from the file, not the network.
	async function debugFlow() {
		const f = await host.debugFlow();
		if (!f) return;
		trace("debug-flow", "found");
		let s = null;
		if (f.resume) {
			s = deps.session();
			if (!s.repo) return trace("debug-flow", "resume: the open session is not bound to a repo");
		} else {
			const got = await host.repos();
			const r = got.repos.find((x) => x.name === f.repo);
			if (!r) return trace("debug-flow", `repo ${f.repo} not listed by Cloud Drive`);
			const ticket = f.ticket || (f.ticket_id ? await host.loadTicket(f.ticket_id) : null);
			if (!ticket) return trace("debug-flow", `ticket ${f.ticket_id || ""} not found in the backlog clone`);
			s = deps.startSession({ repo: r, ticket });
			s.messages.push({ role: "user", content: deps.startContext(s), attachments: [] });
			log(s, "prompt", "ticket context sent (test reply, no network)");
			const reply = { role: "assistant", content: String(f.reply || ""), agent: "test", model: "canned", via: "debug hook", at: Date.now() };
			s.messages.push(reply);
			await onReply(s, reply);
		}
		if (f.accept) for (const c of [...(s.changes || [])]) if (c.state === "pending") await accept(s, c.path);
		trace("debug-flow", `done ${JSON.stringify(counts(s.changes || []))}`);
		deps.redraw();
	}

	return { onReply, accept, reject, panel, chipsRow, debugFlow, bindRepo, ciPage, ticketNote, logPage, closeOverlay };
}
