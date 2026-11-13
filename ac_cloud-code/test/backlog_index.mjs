// Driver for the #1699 Backlog index: the REAL src/cloud/backlog-index.js with fake IO, plus a
// field-parity check of the REAL index.js against the pre-#1699 Backlog page (rule 16).
import { readFileSync } from "node:fs";
import { join } from "node:path";
const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const m = await import(`data:text/javascript,${encodeURIComponent(read("src/cloud/backlog-index.js"))}`);
const nav = JSON.parse(read("src/cloud/nav.json"));
const index = read("src/cloud/index.js");
const PAGE = "<!doctype html><html><body>backlog</body></html>";
const mk = (o) => ({ readLive: async () => PAGE, readLast: async () => null, writeLast: async () => {}, now: () => 1000, ...o });

let saved = null;
let r = await m.loadIndex(mk({ writeLast: async (t) => { saved = t; } }), "x");
out(r.state === "live" && r.html === PAGE && saved === PAGE, "live: the clone's index.html is shown and stored as the last-good copy");
r = await m.loadIndex(mk({ readLive: async () => { throw new Error("ENOENT no such file"); }, readLast: async () => ({ text: PAGE, savedAt: 5 }) }), "repo/index.html");
out(r.state === "offline" && r.html === PAGE && /not on this device/.test(r.reason) && r.savedAt === 5, "offline: clone missing -> last-good copy shown with the reason");
r = await m.loadIndex(mk({ readLive: async () => { throw new Error("Permission denied"); } }), "repo/index.html");
out(r.state === "unavailable" && /access was denied/.test(r.reason) && !r.html, "unavailable: no clone and no copy -> a reason, no page, no throw");
r = await m.loadIndex(mk({ readLive: async () => "   " }), "w");
out(r.state === "unavailable" && /empty/.test(r.reason), "an empty index.html is refused with a reason");
r = await m.loadIndex(mk({ readLive: async () => "plain text" }), "w");
out(r.state === "unavailable" && /not an HTML page/.test(r.reason), "a non-HTML file is refused with a reason");
r = await m.loadIndex(mk({ readLive: async () => { throw new Error("x"); }, readLast: async () => { throw new Error("cache broken"); } }), "w");
out(r.state === "unavailable", "a broken cache read never throws");
r = await m.loadIndex(mk({ writeLast: async () => { throw new Error("disk full"); } }), "w");
out(r.state === "live", "a failed cache write never hides the live page");
r = await m.loadIndex(mk({ readLive: async () => { throw new Error("x"); }, readLast: async () => ({ text: "garbage", savedAt: 1 }) }), "w");
out(r.state === "unavailable", "a corrupt last-good copy is not shown");
out(m.ageText(0, 1) === "" && m.ageText(1000, 1000 + 5 * 60000) === "5 min ago", "age text");

// Declaration + wiring.
out(nav.backlog.index === "index.html" && /\.html$/.test(nav.backlog.last_good) && !nav.backlog.last_good.includes("/"), "nav.backlog declares the root index and the last-good file");
out(/import \{ loadIndex, ageText \} from "\.\/backlog-index"/.test(index) && /sandbox: "allow-scripts"/.test(index) && /id: "index-refresh"/.test(index) && /cordova\.file\.dataDirectory/.test(index), "index.js renders the index in a sandboxed frame, with Refresh and the data-dir copy");
out(/stored\("docs\.dir", gitPath\(nav\.docs\.root\)\)/.test(index) && /nav\.backlog\.index/.test(index), "the index is read from the cloud-me-pvt-tasks clone (nav.docs.root)");
out(!/new WebView|CloudWeb|android\.webkit/.test(index), "no native WebView is constructed (the page is Cordova's own; webview guard excludes ac_cloud-code)");

// Rule 16 field parity: everything the page had before #1699 is still there.
const before = JSON.parse(readFileSync(join(root, "test/fixtures/backlog-fields.before.json"), "utf8"));
for (const id of before.menu_ids) out(new RegExp(`id: "${id}"`).test(index), `parity: drawer item ${id} kept`);
for (const k of before.nav_backlog_keys) out(k in nav.backlog, `parity: nav.backlog.${k} kept`);
for (const t of before.snippets) out(index.includes(t), `parity: ${t.slice(0, 60)} kept`);
const subs = Object.fromEntries(nav.tabs.flatMap((t) => (t.pages || []).map((p) => [p.id, p.file])));
for (const [id, f] of Object.entries(before.sub_pages)) out(subs[id] === f, `parity: sub-page ${id} -> ${f} kept`);
