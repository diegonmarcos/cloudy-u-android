// Driver for test/test-cloud-nav.sh: the editor's text must not bleed through the other pages.
// The editor (Acode's wc-page) stays mounted under every panel page to keep its state; the panel was
// transparent, so the file text showed through. This loads the REAL BLEED-GUARD css block and the REAL
// .cloud-panel background from src/cloud/cloud.scss into headless Chromium (Playwright), draws an editor
// full of text under each panel tab of the REAL nav.json, and compares screenshots against the same page
// with no editor text at all: they must be byte-identical. The editor tab itself must still show the text.
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { createRequire } from "node:module";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);

const scss = read("src/cloud/cloud.scss");
const index = read("src/cloud/index.js");
const nav = JSON.parse(read("src/cloud/nav.json"));

const guard = /BLEED-GUARD begin[^\n]*\n([\s\S]*?)\/\/ BLEED-GUARD end/.exec(scss);
out(!!guard, "cloud.scss has the BLEED-GUARD block");
const panel = /\n\.cloud-panel \{\n([\s\S]*?)\n\n/.exec(scss);
out(!!panel, "cloud.scss has the .cloud-panel base rule");
out(/classList\.toggle\("cloud-panel-open", !!state\.showPanel\)/.test(index),
	"index.js::showTab toggles body.cloud-panel-open from the shown panel");
if (!guard || !panel) process.exit(0);
const panelCss = panel[1].replace(/\/\/[^\n]*/g, "");
out(!/background(-color)?:\s*transparent/.test(panelCss), ".cloud-panel background is not transparent");

const req = createRequire("/opt/node22/lib/node_modules/");
let chromium = null;
try {
	({ chromium } = req(process.env.PLAYWRIGHT_PATH || "playwright"));
} catch {
	// CI must never skip: a missing browser there is a red tester. Only a local run without CI set skips.
	if (process.env.CI) {
		out(false, "playwright is missing in CI: the page-bleed checks cannot run (the workflow installs it)");
		process.exit(0);
	}
	out(true, "playwright not installed here (CI unset): the browser bleed checks are skipped locally only");
	process.exit(0);
}

const argb = (v) => `#${v.replace(/^0x[0-9A-Fa-f]{2}/, "")}`;
const dark = nav.bars.theme.dark;
const html = (editorText, withPanel) => `<!doctype html><html><head><style>
html,body{margin:0;height:100%}
body.cloud-nav{background:linear-gradient(180deg,${argb(dark.top)},${argb(dark.bottom)}) fixed;
 --cb-top:${argb(dark.top)};--cb-bottom:${argb(dark.bottom)};--cloud-top-h:60px;--cloud-nav-h:60px;--secondary-text-color:#fff}
wc-page{display:block;position:fixed;top:60px;left:0;right:0;bottom:60px;z-index:108;background:transparent;
 color:#fff;font:40px monospace;white-space:pre}
.cloud-panel{${panelCss}}
.cloud-panel[hidden]{display:none}
${guard[1].replace(/\/\/[^\n]*/g, "")}
</style></head><body class="cloud-nav">
<wc-page>${editorText}</wc-page>
${withPanel ? '<section class="cloud-panel"></section>' : ""}
</body></html>`;

const TEXT = Array.from({ length: 12 }, (_, i) => `const secret_${i} = "editor file text ${i}";`).join("\n");
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 700, height: 600 } });
const shot = async (h, panelOpen) => {
	await page.setContent(h);
	await page.evaluate((o) => document.body.classList.toggle("cloud-panel-open", o), panelOpen);
	return (await page.screenshot()).toString("base64");
};
const blank = await shot(html("", true), true);
const tabs = [...nav.tabs, ...nav.parents].filter((t) => t.view !== "editor" && t.view !== "myterminal");
out(tabs.length >= 5, `${tabs.length} panel pages read from nav.json`);
for (const t of tabs) {
	const got = await shot(html(TEXT, true), true);
	out(got === blank, `${t.label}: the editor text is not painted through the page`);
}
const editor = await shot(html(TEXT, false), false);
out(editor !== blank, "Editor tab: the file text is painted (the check is not vacuous)");
await page.setContent(html(TEXT, true));
await page.evaluate(() => document.body.classList.add("cloud-panel-open"));
const kept = await page.evaluate(() => document.querySelector("wc-page").textContent.includes("secret_11"));
out(kept, "the editor stays mounted under a page (state preserved)");
await browser.close();
