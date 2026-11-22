// cloud-code agent loop (#1855 P4b): the per-session action log. Every agent action (prompt sent, plan, each edit proposed /
// accepted / rejected, files written, CI followed, ticket note) is appended to the chat session (s.log), so it persists with the
// session in the chat sessions store. Pure, no imports; bounded so the store cannot grow without limit.

export const KINDS = Object.freeze(["session", "prompt", "plan", "proposed", "accepted", "rejected", "written", "failed", "stale", "ci", "note", "context"]);
export const MAX_ENTRIES = 400;
export const MAX_TEXT = 600;

/** Append an entry {at, kind, text, path?}; unknown kinds are refused (the log is a closed vocabulary). */
export function addLog(session, kind, text, extra = {}, now = Date.now()) {
	if (!KINDS.includes(kind)) return null;
	if (!Array.isArray(session.log)) session.log = [];
	const e = { at: now, kind, text: String(text || "").slice(0, MAX_TEXT), ...(extra.path ? { path: String(extra.path).slice(0, 300) } : {}) };
	session.log.push(e);
	if (session.log.length > MAX_ENTRIES) session.log.splice(0, session.log.length - MAX_ENTRIES);
	return e;
}

export const logLines = (session) => (session.log || []).map((e) => `${new Date(e.at).toISOString().slice(11, 19)}  ${e.kind.padEnd(8)} ${e.path ? `${e.path}  ` : ""}${e.text}`);
export const exportLog = (session) => (session.log || []).map((e) => `${new Date(e.at).toISOString()} ${e.kind}${e.path ? ` ${e.path}` : ""}: ${e.text}`).join("\n");
export const entriesOf = (session, kind) => (session.log || []).filter((e) => e.kind === kind);
