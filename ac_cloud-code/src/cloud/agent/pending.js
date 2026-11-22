// cloud-code agent loop (#1855 P4b): the pending-change state machine and the diff shown before any write. Pure, no imports.
// pending -> accepted -> written | failed; pending -> rejected; accepted -> stale (the file changed since it was proposed).
// There is no auto-accept: `accept` needs the explicit user marker and the only way to `written` is through `accepted`.

export const STATES = Object.freeze(["pending", "accepted", "rejected", "written", "failed", "stale"]);
const NEXT = { pending: ["accepted", "rejected"], accepted: ["written", "failed", "stale"], rejected: [], written: [], failed: [], stale: [] };
export const canMove = (from, to) => (NEXT[from] || []).includes(to);
export const isFinal = (state) => (NEXT[state] || []).length === 0;

const hash = (s) => { let h = 5381; const t = String(s); for (let i = 0; i < t.length; i++) h = ((h * 33) ^ t.charCodeAt(i)) >>> 0; return `${t.length}:${h.toString(16)}`; };
export { hash as contentHash };

/** A pending change from a parsed edit and the file's current text (null = the file does not exist yet). */
export function makeChange(edit, current) {
	const exists = current !== null && current !== undefined;
	return {
		path: edit.path, newContent: edit.newContent, bytes: edit.bytes, isNew: !exists,
		baseHash: exists ? hash(current) : "", base: exists ? current : null, state: "pending", reason: "", unchanged: exists && current === edit.newContent,
	};
}

/** Move a change to `to`; returns {ok, change, reason}. Illegal moves change nothing. `accepted` needs {by: "user"}. */
export function move(change, to, { by = "", reason = "" } = {}) {
	if (!canMove(change.state, to)) return { ok: false, change, reason: `cannot go from ${change.state} to ${to}` };
	if (to === "accepted" && by !== "user") return { ok: false, change, reason: "accepting needs the user" };
	if (to === "accepted" && change.unchanged) return { ok: false, change, reason: "the file already has this content" };
	const next = { ...change, state: to, reason };
	if (isFinal(to)) { next.newContent = ""; next.base = null; } // keep only what the log needs once decided; a big body is not kept in the session store
	return { ok: true, change: next, reason: "" };
}

/** Whether the file on disk still is what the change was proposed against. */
export const stillBase = (change, current) => (current === null || current === undefined ? change.baseHash === "" : change.baseHash === hash(current));

export const counts = (changes) => Object.fromEntries(STATES.map((s) => [s, changes.filter((c) => c.state === s).length]));
export const allDecided = (changes) => changes.length > 0 && changes.every((c) => c.state !== "pending");
export const writtenPaths = (changes) => changes.filter((c) => c.state === "written").map((c) => c.path);

// ── the diff (an LCS line diff, bounded) as unified-diff text the P4a renderer colours ──
export const DIFF_CELLS = 2_000_000;
const lines = (t) => { const a = String(t).split("\n"); if (a.length && a[a.length - 1] === "") a.pop(); return a; };

export function unifiedDiff(path, oldText, newText, ctx = 3) {
	const a = oldText === null || oldText === undefined ? [] : lines(oldText);
	const b = lines(newText);
	const head = [`--- ${oldText === null || oldText === undefined ? "/dev/null" : `a/${path}`}`, `+++ b/${path}`];
	let ops = [];
	if (a.length * b.length > DIFF_CELLS) {
		ops = [...a.map((t) => ["-", t]), ...b.map((t) => ["+", t])];
	} else {
		let p = 0;
		while (p < a.length && p < b.length && a[p] === b[p]) p++;
		let q = 0;
		while (q < a.length - p && q < b.length - p && a[a.length - 1 - q] === b[b.length - 1 - q]) q++;
		const x = a.slice(p, a.length - q);
		const y = b.slice(p, b.length - q);
		const w = y.length + 1;
		const t = new Uint32Array((x.length + 1) * w);
		for (let i = x.length - 1; i >= 0; i--) for (let j = y.length - 1; j >= 0; j--) t[i * w + j] = x[i] === y[j] ? t[(i + 1) * w + j + 1] + 1 : Math.max(t[(i + 1) * w + j], t[i * w + j + 1]);
		ops = a.slice(0, p).map((s) => [" ", s]);
		let i = 0;
		let j = 0;
		while (i < x.length && j < y.length) {
			if (x[i] === y[j]) { ops.push([" ", x[i]]); i++; j++; } else if (t[(i + 1) * w + j] >= t[i * w + j + 1]) ops.push(["-", x[i++]]); else ops.push(["+", y[j++]]);
		}
		while (i < x.length) ops.push(["-", x[i++]]);
		while (j < y.length) ops.push(["+", y[j++]]);
		ops.push(...a.slice(a.length - q).map((s) => [" ", s]));
	}
	if (!ops.some((o) => o[0] !== " ")) return `${head.join("\n")}\n(no change)\n`;
	// hunks with `ctx` lines of context
	const keep = ops.map((o, i) => o[0] !== " " || ops.slice(Math.max(0, i - ctx), i + ctx + 1).some((n) => n[0] !== " "));
	const out = [...head];
	let ai = 1;
	let bi = 1;
	let i = 0;
	while (i < ops.length) {
		if (!keep[i]) { if (ops[i][0] !== "+") ai++; if (ops[i][0] !== "-") bi++; i++; continue; }
		let k = i;
		while (k < ops.length && keep[k]) k++;
		const seg = ops.slice(i, k);
		const ac = seg.filter((o) => o[0] !== "+").length;
		const bc = seg.filter((o) => o[0] !== "-").length;
		out.push(`@@ -${ac ? ai : ai - 1},${ac} +${bc ? bi : bi - 1},${bc} @@`);
		for (const [m, s] of seg) out.push(m + s);
		ai += ac;
		bi += bc;
		i = k;
	}
	return `${out.join("\n")}\n`;
}
