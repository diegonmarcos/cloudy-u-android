// cloud-code Backlog page (#1699): the root index.html of the cloud-me-pvt-tasks clone, with a
// refresh, an offline last-good copy and a visible reason when the repository cannot be reached.
// Pure logic with injected IO (index.js passes fsOperation; test/backlog_index.mjs passes fakes),
// so every branch is testable without a device. No data is baked into the bundle: the repository is
// private and this APK is public; the only copy kept is the one the device already read.

export const MAX_BYTES = 8 * 1024 * 1024;

// Why a read failed, in words the owner can act on. Never throws.
export function reasonOf(err, where) {
	const msg = String(err?.message || err || "unknown error");
	if (/permission|denied|EACCES|not allowed/i.test(msg)) {
		return `Cannot read ${where}: storage access was denied. Allow access to all files, then Refresh. (${msg})`;
	}
	if (/not found|ENOENT|no such|does not exist|404/i.test(msg)) {
		return `The repository clone is not on this device (${where}). Clone cloud-me-pvt-tasks with Cloud Drive (Sync, Git), then Refresh. (${msg})`;
	}
	return `Cannot reach ${where}: ${msg}`;
}

// A page is usable only when it is a non-empty HTML document within the size bound.
export function validIndex(text) {
	if (typeof text !== "string" || !text.trim()) return "the file is empty";
	if (text.length > MAX_BYTES) return "the file is larger than the display limit";
	if (!/<html[\s>]|<!doctype html/i.test(text)) return "the file is not an HTML page";
	return null;
}

/**
 * Resolve what the Backlog page shows.
 * io = { readLive(): Promise<string>, readLast(): Promise<{text, savedAt}|null>, writeLast(text): Promise<void>, now(): number }
 * Returns { state: "live" | "offline" | "unavailable", html?, reason?, savedAt? }.
 */
export async function loadIndex(io, where) {
	let reason;
	try {
		const text = await io.readLive();
		const bad = validIndex(text);
		if (!bad) {
			try { await io.writeLast(text); } catch { /* a failed cache write never hides the live page */ }
			return { state: "live", html: text, savedAt: io.now() };
		}
		reason = `The clone's index.html is unusable: ${bad}.`;
	} catch (err) {
		reason = reasonOf(err, where);
	}
	let last = null;
	try { last = await io.readLast(); } catch { last = null; }
	if (last && !validIndex(last.text)) {
		return { state: "offline", html: last.text, reason, savedAt: last.savedAt };
	}
	return { state: "unavailable", reason: `${reason} No earlier copy is stored on this device.` };
}

export function ageText(savedAt, now) {
	if (!savedAt) return "";
	const m = Math.max(0, Math.round((now - savedAt) / 60000));
	if (m < 1) return "just now";
	if (m < 90) return `${m} min ago`;
	if (m < 2880) return `${Math.round(m / 60)} h ago`;
	return `${Math.round(m / 1440)} days ago`;
}
