// cloud-code agent loop (#1855 P4b, D3): the strict, parseable reply format and the path safety around it. Pure, no imports, no DOM:
// test/agent.mjs drives this file as is. The model is asked for a plan and then whole-file edits; nothing it says is trusted:
// every path is validated against the bound repository's root before anything is proposed, sizes are capped, and a malformed or
// unsafe edit is reported with its reason (never silently dropped, never applied).

export const MAX_EDITS = 12;
export const MAX_FILE_BYTES = 64 * 1024;
export const MAX_TOTAL_BYTES = 256 * 1024;
export const MAX_PLAN_CHARS = 4000;

// The instruction a repo-bound session adds to the model's system prompt (the permission instruction stays; this is appended).
export const FORMAT_INSTRUCTION = [
	"You are editing files in one git repository. Answer in EXACTLY this format and nothing else outside the tags:",
	"<plan>",
	"A short numbered plan, plain text.",
	"</plan>",
	"<edits>",
	'[{"path": "relative/path/from/the/repo/root.ext", "newContent": "the COMPLETE new content of that file"}]',
	"</edits>",
	`Rules: <edits> holds one JSON array of at most ${MAX_EDITS} objects with exactly the keys path and newContent (the whole file, never a diff or a fragment). `,
	`Paths are relative to the repository root, use forward slashes, never start with / and never contain ".." or touch ".git". Each file is at most ${MAX_FILE_BYTES} bytes. `,
	"A file you do not change is not listed. If no edit is needed, answer an empty array [].",
].join("\n");

const enc = typeof TextEncoder !== "undefined" ? new TextEncoder() : null;
export const byteLength = (s) => (enc ? enc.encode(String(s)).length : unescape(encodeURIComponent(String(s))).length);

/** null when `rel` is a safe repository-relative path, else the reason it is not. */
export function pathProblem(rel) {
	if (typeof rel !== "string" || !rel.trim()) return "empty path";
	if (rel.length > 300) return "path too long";
	if (/[\0\r\n]/.test(rel)) return "control character in path";
	if (rel.includes("\\")) return "backslash in path";
	if (rel.startsWith("/") || /^[a-zA-Z]:/.test(rel)) return "absolute path";
	if (/^[a-z][a-z0-9+.-]*:/i.test(rel) || rel.startsWith("~")) return "not a relative path";
	if (/%2e|%2f|%5c/i.test(rel)) return "encoded path characters";
	const parts = rel.split("/");
	if (parts.some((p) => p === "" )) return "empty path segment";
	if (parts.some((p) => p === "." || p === "..")) return "dot segment";
	if (parts.some((p) => p.toLowerCase() === ".git")) return "inside .git";
	if (rel.endsWith("/")) return "a directory, not a file";
	return null;
}

/**
 * The file:// url of `rel` under `rootPath` (an absolute directory path, e.g. "/storage/emulated/0/CloudDrive/git/x"), or null when it
 * would not stay inside that root. `rootPath` itself must be a real absolute directory path (not "/" and no dot segments).
 */
export function resolveInRoot(rootPath, rel) {
	const root = String(rootPath || "").replace(/^file:\/\//, "").replace(/\/+$/, "");
	if (!root.startsWith("/") || root.split("/").some((p) => p === "." || p === "..") || root.length < 2) return null;
	if (pathProblem(rel)) return null;
	const url = `file://${root}/${rel}`;
	return url.startsWith(`file://${root}/`) ? url : null;
}

const grab = (text, tag) => {
	const re = new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`, "g");
	const all = [...String(text || "").matchAll(re)];
	return all;
};

/**
 * Parse a model reply: {plan, edits: [{path, newContent, bytes}], rejected: [{path, reason}], error}.
 * `error` is set (and edits empty) when the reply is not in the format; `rejected` lists edits refused one by one.
 */
export function parseReply(text) {
	const out = { plan: "", edits: [], rejected: [], error: "" };
	const plans = grab(text, "plan");
	const blocks = grab(text, "edits");
	if (plans.length === 1) out.plan = plans[0][1].trim().slice(0, MAX_PLAN_CHARS);
	if (blocks.length !== 1) {
		out.error = blocks.length ? "more than one <edits> block" : "no <edits> block in the reply";
		return out;
	}
	let list;
	try {
		list = JSON.parse(blocks[0][1].trim());
	} catch (e) {
		out.error = `the <edits> block is not valid JSON (${String(e.message || e).slice(0, 80)})`;
		return out;
	}
	if (!Array.isArray(list)) { out.error = "the <edits> block is not a JSON array"; return out; }
	const seen = new Set();
	let total = 0;
	list.forEach((e, i) => {
		const label = e && typeof e.path === "string" ? e.path : `#${i + 1}`;
		const no = (reason) => out.rejected.push({ path: label, reason });
		if (!e || typeof e !== "object" || Array.isArray(e)) return no("not an object");
		const keys = Object.keys(e).sort().join(",");
		if (keys !== "newContent,path") return no(`keys must be exactly path and newContent (got ${keys || "none"})`);
		if (typeof e.newContent !== "string") return no("newContent is not a string");
		if (out.edits.length >= MAX_EDITS) return no(`more than ${MAX_EDITS} edits`);
		const bad = pathProblem(e.path);
		if (bad) return no(bad);
		const key = e.path.toLowerCase();
		if (seen.has(key)) return no("the same path twice");
		const bytes = byteLength(e.newContent);
		if (bytes > MAX_FILE_BYTES) return no(`${bytes} bytes exceeds the ${MAX_FILE_BYTES} byte cap`);
		if (total + bytes > MAX_TOTAL_BYTES) return no("the total size cap is reached");
		seen.add(key);
		total += bytes;
		out.edits.push({ path: e.path, newContent: e.newContent, bytes });
	});
	return out;
}
