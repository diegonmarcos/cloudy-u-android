// Driver for #1855 P4b (D3, the agentic loop): the REAL pure modules src/cloud/agent/{edits,pending,log,context}.js (no copy, they import
// nothing), plus the wiring of the real chat/view.js, chat/model.js, index.js and agent/ui.js. One `CHECK <ok|bad> <text>` per assertion;
// test-cloud-nav.sh runs it, then runs it again against MUTATED copies, which must each fail.
import { readFileSync } from "node:fs";
import { join } from "node:path";
const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const load = (p) => import(`data:text/javascript,${encodeURIComponent(read(p))}`);
const X = await load("src/cloud/agent/edits.js");
const P = await load("src/cloud/agent/pending.js");
const L = await load("src/cloud/agent/log.js");
const C = await load("src/cloud/agent/context.js");

// ── path validation: nothing outside the bound repository's root ──
const bad = ["", "  ", "/etc/passwd", "../x", "a/../../x", "a/..", "./a", "a/./b", "a//b", ".git/config", "a/.git/hooks/x", "A/.GIT/x", "a\\b", "C:/x", "~/x", "file:///x", "a/b/", "a\0b", "a\nb", "%2e%2e/x", "a%2Fb", "x".repeat(301)];
for (const p of bad) out(X.pathProblem(p) !== null, `path refused: ${JSON.stringify(p).slice(0, 40)}`);
out(X.pathProblem("/etc/passwd") === "absolute path" && X.pathProblem("../x") === "dot segment" && X.pathProblem(".git/config") === "inside .git", "path refusals name their reason");
for (const p of ["a.txt", "src/a/b.js", "docs/.hidden", "dir/.gitignore", "a b/c.txt", "ü/é.md"]) out(X.pathProblem(p) === null, `path accepted: ${p}`);
const R = "/storage/emulated/0/CloudDrive/git/proj";
out(X.resolveInRoot(R, "src/a.js") === `file://${R}/src/a.js`, "resolveInRoot joins under the root");
out(X.resolveInRoot(`file://${R}/`, "a.txt") === `file://${R}/a.txt`, "a file:// root with a trailing slash works");
out(X.resolveInRoot(R, "../proj-evil/x") === null && X.resolveInRoot(R, "/abs") === null && X.resolveInRoot(R, ".git/x") === null, "resolveInRoot refuses escape, absolute and .git");
out(X.resolveInRoot("/", "a") === null && X.resolveInRoot("", "a") === null && X.resolveInRoot("rel/dir", "a") === null && X.resolveInRoot("/a/../b", "x") === null, "a root that is not a real absolute directory path resolves nothing");

// ── the reply format ──
const reply = (plan, edits) => `<plan>\n${plan}\n</plan>\n<edits>\n${typeof edits === "string" ? edits : JSON.stringify(edits)}\n</edits>`;
let r = X.parseReply(reply("1. do it", [{ path: "a.txt", newContent: "hello\n" }, { path: "src/b.js", newContent: "x" }]));
out(!r.error && r.plan === "1. do it" && r.edits.length === 2 && r.edits[0].path === "a.txt" && r.edits[0].bytes === 6 && r.rejected.length === 0, "a well-formed reply gives the plan and the edits");
out(X.parseReply("just prose").error.includes("no <edits>"), "prose with no <edits> block is an error, not an edit");
out(X.parseReply(reply("p", "[{bad json")).error.includes("not valid JSON"), "invalid JSON is an error");
out(X.parseReply(reply("p", '{"path":"a","newContent":"b"}')).error.includes("not a JSON array"), "a non-array is an error");
out(X.parseReply(`${reply("p", [])}\n${reply("q", [])}`).error.includes("more than one"), "two <edits> blocks are refused");
out(X.parseReply(reply("p", [])).edits.length === 0 && !X.parseReply(reply("p", [])).error, "an empty array is a valid 'no edit needed'");
r = X.parseReply(reply("p", [{ path: "../evil", newContent: "x" }, { path: "ok.txt", newContent: "y" }, { path: ".git/config", newContent: "z" }]));
out(r.edits.length === 1 && r.rejected.length === 2 && r.rejected.every((x) => x.reason), "unsafe paths are rejected one by one with a reason, safe ones stay");
r = X.parseReply(reply("p", [{ path: "a", newContent: "x" }, { path: "A", newContent: "y" }]));
out(r.edits.length === 1 && r.rejected[0].reason.includes("twice"), "the same path twice (case-insensitive) is refused");
r = X.parseReply(reply("p", [{ path: "a", content: "x" }, { path: "b", newContent: 3 }, { path: "c", newContent: "x", extra: 1 }, "str", null]));
out(r.edits.length === 0 && r.rejected.length === 5, "wrong keys, a non-string body, extra keys and non-objects are refused");
r = X.parseReply(reply("p", [{ path: "big", newContent: "x".repeat(X.MAX_FILE_BYTES + 1) }, { path: "edge", newContent: "x".repeat(X.MAX_FILE_BYTES) }]));
out(r.edits.length === 1 && r.edits[0].path === "edge" && r.rejected[0].reason.includes("cap"), "the per-file size cap refuses one byte over and keeps exactly the cap");
r = X.parseReply(reply("p", [{ path: "mb", newContent: "é".repeat(X.MAX_FILE_BYTES / 2 + 1) }]));
out(r.edits.length === 0, "the cap counts bytes, not characters");
r = X.parseReply(reply("p", Array.from({ length: X.MAX_EDITS + 3 }, (_, i) => ({ path: `f${i}`, newContent: "x" }))));
out(r.edits.length === X.MAX_EDITS && r.rejected.length === 3, "at most MAX_EDITS edits per reply");
r = X.parseReply(reply("p", Array.from({ length: 6 }, (_, i) => ({ path: `f${i}`, newContent: "x".repeat(X.MAX_FILE_BYTES - 10) }))));
out(r.edits.length === 4 && r.rejected.length === 2 && r.rejected[0].reason.includes("total"), "the total size cap stops a reply of many big files");
out(X.parseReply(reply("x".repeat(9000), [])).plan.length === X.MAX_PLAN_CHARS, "the plan is bounded");
out(X.FORMAT_INSTRUCTION.includes("<plan>") && X.FORMAT_INSTRUCTION.includes("<edits>") && X.FORMAT_INSTRUCTION.includes("newContent") && X.FORMAT_INSTRUCTION.includes(String(X.MAX_EDITS)), "the format instruction names the tags, the keys and the caps");

// ── the pending-change state machine ──
const e = { path: "a.txt", newContent: "new\n", bytes: 4 };
let c = P.makeChange(e, "old\n");
out(c.state === "pending" && !c.isNew && c.base === "old\n" && c.baseHash && !c.unchanged, "a proposal starts pending with its base");
out(P.makeChange(e, null).isNew === true && P.makeChange(e, null).baseHash === "", "a file that does not exist yet is a new file");
out(P.makeChange(e, "new\n").unchanged === true, "an identical file is marked unchanged");
let m = P.move(c, "accepted");
out(!m.ok && m.change.state === "pending", "accept without the user is refused (no auto-accept)");
m = P.move(c, "accepted", { by: "agent" });
out(!m.ok, "accept by anything but the user is refused");
m = P.move(c, "written", { by: "user" });
out(!m.ok, "pending cannot jump to written");
m = P.move(c, "accepted", { by: "user" });
out(m.ok && m.change.state === "accepted" && m.change.newContent === "new\n", "the user accepts: accepted, body kept for the write");
const w = P.move(m.change, "written");
out(w.ok && w.change.state === "written" && w.change.newContent === "" && w.change.base === null, "written is final and drops the body");
out(!P.move(w.change, "rejected").ok && !P.move(w.change, "pending").ok && !P.move(w.change, "accepted", { by: "user" }).ok, "a written change cannot be moved again");
const rj = P.move(c, "rejected", { by: "user" });
out(rj.ok && rj.change.state === "rejected" && !P.move(rj.change, "accepted", { by: "user" }).ok, "rejected is final");
out(P.move(m.change, "stale").ok && P.move(m.change, "failed").ok && !P.move(m.change, "rejected").ok, "an accepted change settles as written, failed or stale, not as rejected");
out(!P.move(P.makeChange(e, "new\n"), "accepted", { by: "user" }).ok, "an unchanged proposal cannot be accepted");
out(P.stillBase(c, "old\n") && !P.stillBase(c, "old!\n") && !P.stillBase(c, null) && P.stillBase(P.makeChange(e, null), null) && !P.stillBase(P.makeChange(e, null), "now exists"), "stillBase detects a file that changed (or appeared) since the proposal");
const set = [c, rj.change, w.change];
const k = P.counts(set);
out(k.pending === 1 && k.rejected === 1 && k.written === 1 && !P.allDecided(set) && P.allDecided([rj.change, w.change]) && !P.allDecided([]), "counts and allDecided");
out(JSON.stringify(P.writtenPaths(set)) === '["a.txt"]', "writtenPaths lists only written files");
out(JSON.stringify(P.STATES) === '["pending","accepted","rejected","written","failed","stale"]', "the state vocabulary is closed");

// ── the diff ──
const ud = (a, b) => P.unifiedDiff("f.txt", a, b);
let d = ud("a\nb\nc\n", "a\nB\nc\nd\n");
out(d.startsWith("--- a/f.txt\n+++ b/f.txt\n") && d.includes("\n-b\n") && d.includes("\n+B\n") && d.includes("\n+d\n") && d.includes("\n a\n") && /@@ -1,3 \+1,4 @@/.test(d), "an edit shows as a unified diff with context and a hunk header");
d = ud(null, "x\ny\n");
out(d.startsWith("--- /dev/null\n+++ b/f.txt\n") && d.includes("@@ -0,0 +1,2 @@") && d.includes("\n+x\n+y\n"), "a new file diffs against /dev/null");
out(ud("same\n", "same\n").includes("(no change)"), "an identical file says no change");
d = ud("1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\n12\n", "1\n2\n3\n4\n5\n6\n7\n8\n9\n10\n11\nTWELVE\n");
out(!d.includes("\n 1\n") && d.includes("\n 9\n") && d.includes("-12") && d.includes("+TWELVE"), "context is bounded to three lines around a change");
d = ud("a\n", "");
out(d.includes("-a"), "emptying a file shows the removal");
const big = Array.from({ length: 2500 }, (_, i) => `line ${i}`).join("\n");
const bigNew = Array.from({ length: 2500 }, (_, i) => (i % 2 ? `changed ${i}` : `line ${i}`)).join("\n");
const t0 = Date.now();
d = ud(big, bigNew);
out(d.includes("@@") && Date.now() - t0 < 5000, "a big diff is bounded in time (falls back to a whole replace past the cell cap)");
{ // the P4a renderer's classes: every line of the diff is classed add/del/hunk/meta/ctx by workspace.js
	const W = await load("src/cloud/workspace.js");
	const kinds = W.diffLines(ud("a\nb\n", "a\nc\n")).map((l) => l.kind).join(",");
	out(kinds === "meta,meta,hunk,ctx,del,add", `the diff renders with P4a's diff renderer (${kinds})`);
}

// ── the session log ──
const s = { id: "s1" };
L.addLog(s, "session", "bound", {}, 1000);
L.addLog(s, "prompt", "p", {}, 2000);
L.addLog(s, "proposed", "edit", { path: "a.txt" }, 3000);
L.addLog(s, "accepted", "ok", { path: "a.txt" }, 4000);
L.addLog(s, "written", "w", { path: "a.txt" }, 5000);
out(s.log.length === 5 && s.log[2].path === "a.txt" && s.log.map((x) => x.kind).join() === "session,prompt,proposed,accepted,written", "every action is appended in order with its path");
out(L.addLog(s, "bogus", "x") === null && s.log.length === 5, "an unknown kind is refused (closed vocabulary)");
out(L.entriesOf(s, "written").length === 1 && L.logLines(s).length === 5 && L.exportLog(s).split("\n").length === 5 && L.exportLog(s).includes("proposed a.txt"), "the log is listed and exported");
const lg = { log: [] };
for (let i = 0; i < L.MAX_ENTRIES + 25; i++) L.addLog(lg, "note", `n${i}`);
out(lg.log.length === L.MAX_ENTRIES && lg.log[lg.log.length - 1].text === `n${L.MAX_ENTRIES + 24}` && lg.log[0].text === "n25", "the log is bounded, oldest dropped first");
out(L.addLog({}, "note", "y".repeat(5000)).text.length === L.MAX_TEXT, "an entry's text is bounded");
{ const stored = JSON.parse(JSON.stringify({ sessions: [s] })); out(stored.sessions[0].log.length === 5, "the log is plain JSON: it persists with the chat session"); }

// ── context chips ──
out(JSON.stringify(C.CHIPS) === '["file","selection","repo","ticket","ci-run","problem"]', "the six chips: @file @selection @repo @ticket @ci-run @problem");
let b = C.block("file x", "y".repeat(C.BLOCK_MAX + 50));
out(b.startsWith("[@file x]\n```\n") && b.includes("truncated at") && b.length < C.BLOCK_MAX + 200, "a context block is labelled and bounded");
out(C.fileBlock("a/b.js", "code").includes("[@file a/b.js]") && C.selectionBlock("a.js", "  ") === "" && C.selectionBlock("a.js", "sel").includes("[@selection a.js]"), "@file and @selection blocks (an empty selection adds nothing)");
out(C.repoBlock({ name: "r", branch: "main", summary: "dirty 2" }, ["d src", "f a.txt"]).includes("branch: main") && C.repoBlock({ name: "r" }, []).includes("[@repo r]"), "@repo block: name, branch, state, top level");
out(C.problemBlock([]) === "" && C.problemBlock([{ file: "a.js", line: 3, severity: "error", message: "boom" }]).includes("a.js:3 [error] boom"), "@problem block lists diagnostics (none -> nothing)");
const tk = C.ticketFromJson(JSON.stringify({ id: "1855", subject: "S", description: "D", status: "x" }));
out(tk.id === "1855" && tk.subject === "S" && tk.description === "D" && C.ticketFromJson("{}") === null && C.ticketFromJson("nope") === null, "a backlog ticket JSON gives id, subject and description");
out(C.ticketBlock(tk).includes("[@ticket #1855]") && C.startContext(tk, { name: "r", branch: "main" }).includes("repository r (branch main)") && C.startContext(tk, { name: "r" }).includes("#1855 S"), "the session starts with the ticket id, subject and description as context");
out(C.normalizeTicketId("#1855") === "1855" && C.normalizeTicketId("x") === "" && C.ticketFileName(" #12 ") === "12.json", "ticket ids are normalised");
{
	const tree = { "t/": [{ name: "sA", url: "t/sA", isDirectory: true }, { name: "sB", url: "t/sB", isDirectory: true }, { name: "x.json", url: "t/x.json", isDirectory: false }], "t/sA": [{ name: "1.json", url: "t/sA/1.json", isDirectory: false }], "t/sB": [{ name: "77.json", url: "t/sB/77.json", isDirectory: false }, { name: "78.json", url: "t/sB/78.json", isDirectory: false }] };
	const files = { "t/sB/77.json": JSON.stringify({ id: "77", subject: "found", description: "d" }), "t/sB/78.json": "{not json" };
	const ls = async (u) => { if (!tree[u]) throw new Error("nope"); return tree[u]; };
	const rd = async (u) => files[u];
	out((await C.locateTicket(ls, rd, "t/", "77"))?.subject === "found", "locateTicket finds tasks/<session>/<id>.json in any session folder");
	out(await C.locateTicket(ls, rd, "t/", "78") === null && await C.locateTicket(ls, rd, "t/", "999") === null && await C.locateTicket(ls, rd, "t/", "x") === null && await C.locateTicket(ls, rd, "missing/", "77") === null, "a malformed, missing or unparsable ticket is null, never a throw");
}
// ci-run
out(JSON.stringify(C.parseRun("https://github.com/o/r/actions/runs/123456/job/9")) === JSON.stringify({ id: "123456", slug: "o/r", url: "https://github.com/o/r/actions/runs/123456" }), "@ci-run: a run URL");
out(C.parseRun("o/r#999")?.id === "999" && C.parseRun("45012416775", "o/r")?.slug === "o/r" && C.parseRun("45012416775", "")?.url === "" && C.parseRun("12") === null && C.parseRun("hello") === null && C.parseRun("") === null, "@ci-run: owner/repo#id, a bare id with the repo's slug, junk refused");
out(C.githubSlug('[core]\n\tbare = false\n[remote "origin"]\n\turl = https://github.com/diegonmarcos/cloudy-u-android.git\n\tfetch = +x\n') === "diegonmarcos/cloudy-u-android" && C.githubSlug('[remote "origin"]\n\turl = git@github.com:o/r.git\n') === "o/r" && C.githubSlug('[remote "origin"]\n\turl = https://gitlab.com/o/r\n') === "" && C.githubSlug("") === "", "the GitHub slug comes from .git/config (https and ssh); other hosts none");
out(C.runsApiUrl("o/r") === "https://api.github.com/repos/o/r/actions/runs?per_page=5" && C.actionsPageUrl("o/r") === "https://github.com/o/r/actions", "public REST url and the Actions page link");
const runs = C.parseRuns({ workflow_runs: [{ id: 5, name: "Ship", status: "completed", conclusion: "success", head_sha: "abcdef0123456", head_branch: "main", html_url: "u" }, { id: 6, name: "B", status: "in_progress", conclusion: null, head_sha: "1", head_branch: "main", html_url: "v" }] });
out(runs.length === 2 && runs[0].sha === "abcdef012" && C.runState(runs[0]) === "success" && C.runState(runs[1]) === "in_progress" && C.parseRuns(null).length === 0 && C.parseRuns({}).length === 0, "runs parse to rows with a state");
out(C.ciOutcome("o/r", 200, { workflow_runs: [] }).kind === "runs" && C.ciOutcome("o/r", 404, null).kind === "link" && C.ciOutcome("o/r", 404, null).url.endsWith("/o/r/actions") && C.ciOutcome("o/r", 403, null).kind === "limit" && C.ciOutcome("o/r", 0, null).kind === "error" && C.ciOutcome("", 200, null).kind === "link", "CI outcome: runs for public, a link for private, rate limit, error, no origin");
const note = C.ticketNote({ ticket: tk, repo: { name: "r", branch: "main" }, files: ["a.txt", "b/c.js"], run: { id: "99", url: "u", status: "completed", conclusion: "success" }, when: "2026-11-22" });
out(note.includes("#1855") && note.includes("files changed (2): a.txt, b/c.js") && note.includes("CI run: 99 u (success)") && C.ticketNote({ files: [] }).includes("CI run: not followed yet"), "the ticket note has the files changed and the run id");

// ── wiring (the real sources) ──
const view = read("src/cloud/chat/view.js");
const model = read("src/cloud/chat/model.js");
const index = read("src/cloud/index.js");
const ui = read("src/cloud/agent/ui.js");
const nav = JSON.parse(read("src/cloud/nav.json"));
const has = (t, s2) => t.includes(s2);
out(has(view, "extra: s.repo ? FORMAT_INSTRUCTION : \"\"") && has(model, "extra = \"\"") && has(model, "[permissionInstruction(chat, agent, mode), extra].filter(Boolean)"), "only a repo-bound session adds the edit-format instruction; other chats' requests are unchanged");
out(has(view, "plugin().stream({ ...req, id }") && has(view, "E.bridge().chat(ereq"), "the agent session still uses the engine road with the direct gateway fallback (P2 unchanged)");
out(has(view, "agentCtl()?.onReply(s, reply)") && has(view, "s.repo && S.host && !reply.error"), "a finished reply of a repo-bound session is parsed into pending edits");
out(has(view, "export function startAgentSession") && has(view, "s.draft = AC.startContext(s.ticket, s.repo)") && !/s\.draft[^\n]*stream\(/.test(view), "a ticket session seeds the composer with the ticket context; nothing is sent by itself");
out(has(index, "Start agent session") && has(index, "startFromTicket(ticket)") && has(index, "locateTicket(agentHost.list") && nav.backlog.tasks_dir === "1___BACKLOG___/1.1.Product-Backlog/tasks/", "the Backlog has 'Start agent session' (button and menu item) reading tasks/<session>/<id>.json");
out(has(ui, '"compare_arrows"), "Review in Changes")') && has(ui, 'host.showTab("changes")') && has(ui, "Commit in Cloud Drive") && has(ui, "host.requestCommit(") && has(ui, "Follow CI") && has(ui, "Ticket note"), "after accepting: Review in Changes, Commit in Cloud Drive, Follow CI, Ticket note");
out(["file", "selection", "repo", "ticket", "ci-run", "problem"].every((id) => has(ui, `"ci-run": async`) && new RegExp(`(^|\\s)"?${id}"?: (async )?\\(`, "m").test(ui)), "every chip has a handler");
out(has(ui, "import { diffLines } from \"../workspace\"") && has(ui, "cloud-diff-line cloud-diff-${l.kind}"), "pending edits reuse P4a's diff renderer");
out(has(ui, 'move(s.changes[i], "accepted", { by: "user" })') && !/accept\(s, [^)]*\)[^\n]*(setTimeout|forEach|onReply)/.test(ui.replace(/f\.accept[^\n]*/g, "")), "accept is only on the user's tap (the debug hook is the one other caller)");
out(has(ui, "stillBase(a.change, now)") && has(ui, "resolveInRoot(s.repo.path, rel)") && has(ui, "host.writeText(s.repo.path, path, content)"), "a write re-checks the file is unchanged and the path is inside the root");
out(has(index, "const url = resolveInRoot(rootPath, rel);") && has(index, "path is outside the repository root") && has(index, "ensureDir(parent, seg)"), "the host re-validates the path and only creates parents below the root");
out(!/git\s+(commit|push)|requestPush|isomorphic-git/.test(ui) && !has(ui, "tasks/") && !/writeFile|createFile/.test(ui), "the agent UI commits nothing, pushes nothing, and never writes the backlog or a file itself (only host.writeText)");
out(has(ui, "host.copy(text)") && !/fetch\(|XMLHttpRequest/.test(ui), "the ticket note is copied, no network from the UI itself");
out(has(index, "agent-test-flow.json") && has(index, "externalDataDirectory") && has(ui, "no network"), "the test hook reads a file only adb can place in the app's own data dir");
out(has(index, "names=${got.files.map"), "the Changes console line names the files (the walk asserts the agent's file shows up)");
// rule 16: every Chat/Agent/Backlog thing from before is still there
for (const t of ["renderBacklogView", "Refresh", "Views", "Index", "docs-folder", "Backlog folder", "Edit this file", "Open the repository"]) out(has(index, t), `parity: Backlog keeps ${t}`);
for (const t of ["plusSheet", "agentSheet", "effortSheet", "modeSheet", "mcpSheet", "moreSheet", "taskPage", "modelPage", "chatDrawer", "exportChat", "retry", "stop"]) out(new RegExp(`function ${t}\\b`).test(view), `parity: chat view keeps ${t}`);
