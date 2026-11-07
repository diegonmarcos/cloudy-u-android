// Driver for test/test-cloud-nav.sh (#1158). Loads the REAL src/cloud/bars.js and nav.json and checks the
// shared navigation design (#1154) as Cloud Code draws it; prints one `CHECK <ok|bad> <text>` per assertion.
import { readFileSync } from "node:fs";
import { join } from "node:path";

const root = process.argv[2];
const read = (p) => readFileSync(join(root, p), "utf8");
const out = (ok, text) => console.log(`CHECK ${ok ? "ok" : "bad"} ${text}`);
const B = await import(`data:text/javascript,${encodeURIComponent(read("src/cloud/bars.js"))}`);
const nav = JSON.parse(read("src/cloud/nav.json"));
const bars = nav.bars;
const tabs = nav.tabs.map((t) => ({ ...t, pages: t.pages || [] }));
const scss = read("src/cloud/cloud.scss");
const dom = read("src/cloud/bars-dom.js");
const index = read("src/cloud/index.js");
const css = read("src/res/icons/style.css");
const hasIcon = (n) => new RegExp(`\\.icon\\.${n.replace(/[-]/g, "\\-")}\\s*:{1,2}before`).test(css);

// ── every custom property the stylesheet reads is produced, in both modes ──
const used = [...new Set([...scss.matchAll(/var\((--cb-[\w-]+)/g)].map((m) => m[1]))];
for (const dark of [true, false]) {
	const vars = B.cssVars(bars, dark);
	const missing = used.filter((u) => !(u in vars));
	out(missing.length === 0 && used.length > 20, `${dark ? "dark" : "light"}: cssVars produces all ${used.length} --cb-* properties cloud.scss reads${missing.length ? ` (missing ${missing.join(",")})` : ""}`);
}
out(B.cssVars(bars, true)["--cb-first-row-icons-right"] === "3" && B.cssVars(bars, true)["--cb-second-row-tab-height"] === "36px", "cssVars: the icon count is unitless (the slot width multiplies by it), every size is px");
out(B.argbToCss("0xB30F172A") === "rgba(15, 23, 42, 0.702)" && B.argbToCss("0xFF7C3AED") === "rgba(124, 58, 237, 1)", "argbToCss reads the Kotlin 0xAARRGGBB literals");

// ── readable pairs (the libs hold the same ones at WCAG AA) ──
const ratio = (a, b) => { const [x, y] = [B.luminance(B.argbToCss(a)), B.luminance(B.argbToCss(b))]; return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); };
for (const mode of ["dark", "light"]) {
	const t = bars.theme[mode];
	out(ratio(t.onTabSelected, t.tabSelected) >= 4.5, `${mode}: selected tab text on its violet is ${ratio(t.onTabSelected, t.tabSelected).toFixed(1)}:1 (AA 4.5)`);
	out(ratio(t.onIsland, t.island) >= 4.5, `${mode}: title on the island is ${ratio(t.onIsland, t.island).toFixed(1)}:1`);
	out(ratio(t.ink, t.top) >= 4.5, `${mode}: ink on the page gradient is ${ratio(t.ink, t.top).toFixed(1)}:1`);
}
out(bars.theme.dark.tabSelected === "0xFF7C3AED", "the selected second-row tab is the SuperApp violet #7C3AED, opaque");

// ── the second row is clearly smaller than the bottom bar ──
const r = [bars.second_row.tab_height / bars.bottom.cell, bars.second_row.icon / bars.bottom.icon, bars.second_row.label / bars.bottom.label];
out(r.every((x) => x <= 0.75), `second row / bottom bar: height ${r[0].toFixed(2)}, icon ${r[1].toFixed(2)}, name ${r[2].toFixed(2)} (all <= 0.75)`);
out(bars.second_row.label >= 8, "the second row's name stays above the fleet's 8 legibility floor");

// ── the bottom bar: cells shrink evenly when crowded, and stay usable at 360 ──
out(B.cellWidth(bars, 328, 5) === 56 && B.cellWidth(bars, 328, 7) < 56, "a roomy bar keeps the 56 cell, a crowded one shrinks them evenly");
out(B.cellsClear(bars, 360, tabs.length, 44), `${tabs.length} cells are ${B.cellWidth(bars, B.barWidth(bars, 360), tabs.length).toFixed(1)} px on 360 (the fleet's crowded-bar floor is 44, nav-shape N1)`);
out(B.barWidth(bars, 1000) === bars.bottom.max_width, "the bar never grows past max_width");

// ── sub-pages and the title dropdown ──
for (const t of tabs) for (const p of t.pages) {
	out(!!p.icon && hasIcon(p.icon) && !!p.label && /^P-Backlog-[a-z]+\.md$/.test(p.file), `sub-page ${t.id}/${p.id} has a name, a file and the icon '${p.icon}' (exists in the icon font)`);
}
out(B.secondRowShown(tabs.find((t) => t.id === "backlog")) && B.secondRowShown(tabs.find((t) => t.id === "agents")), "Backlog and Agents show the second row");
out(["editor", "git", "ai", "browser", "myterminal"].every((id) => !B.secondRowShown(tabs.find((t) => t.id === id))), "a page with fewer than two sub-pages has no second row");
for (const k of ["backlog", "agents"]) {
	const t = tabs.find((x) => x.id === k);
	out(t.pages[0].file === nav[k].entry, `${k}: the first sub-page's file is the tab's entry (${nav[k].entry})`);
}
const h = B.titleHierarchy(tabs.find((t) => t.id === "backlog"), "prj");
out(h.length === 5 && h[0].depth === 0 && h[0].label === "Backlog" && h.slice(1).every((e) => e.depth === 1) && h.filter((e) => e.selected).map((e) => e.id).join() === "prj", "the dropdown lists the bottom page then its sub-pages, the open one marked");
out(B.titleHierarchy(tabs.find((t) => t.id === "git"), null).length === 1, "a page without sub-pages lists itself");
out(B.pageOfFile(tabs.find((t) => t.id === "backlog"), "P-Backlog-eff.md") === "eff" && B.pageOfFile(tabs.find((t) => t.id === "backlog"), "x.md") === null, "a followed dist link selects its sub-page");

// ── scroll-collapse ──
const c = bars.collapse;
let st = { collapsed: false, acc: 0, quietUntil: 0 };
st = B.collapseStep(st, { dy: 8, atTop: false, now: 1000 }, c);
out(!st.collapsed, "a small scroll down does not collapse");
st = B.collapseStep(st, { dy: 8, atTop: false, now: 1010 }, c);
out(st.collapsed, "scrolling down past the threshold hides the first row and compacts the other two");
const quiet = B.collapseStep(st, { dy: -40, atTop: false, now: 1020 }, c);
out(quiet.collapsed, "a scroll right after a change (the page resizing) is ignored");
st = B.collapseStep(st, { dy: -20, atTop: false, now: 1010 + c.settle_ms + 1 }, c);
out(!st.collapsed, "scrolling up restores all three");
let down = B.collapseStep({ collapsed: false, acc: 0, quietUntil: 0 }, { dy: 30, atTop: false, now: 5000 }, c);
down = B.collapseStep(down, { dy: 0, atTop: true, now: 5000 + c.settle_ms + 1 }, c);
out(!down.collapsed, "reaching the top restores them");
const full = B.clearance(bars, { collapsed: false, secondRow: true }), small = B.clearance(bars, { collapsed: true, secondRow: true });
const sr = bars.second_row;
out(full.top === bars.first_row.height + sr.top_inset + sr.bottom_inset + sr.strip_pad_v * 2 + sr.tab_height
	&& small.top === sr.top_inset + sr.bottom_inset + sr.strip_pad_v * 2 + sr.compact_tab_height
	&& small.bottom === bars.bottom.collapsed_height + bars.bottom.bottom_margin && small.bottom < full.bottom,
	`collapsed: top ${full.top} -> ${small.top} (no first row, compact tabs), bottom ${full.bottom} -> ${small.bottom}`);
out(sr.compact_tab_height < sr.tab_height && bars.bottom.collapsed_height < bars.bottom.height && bars.bottom.collapsed_cell < bars.bottom.cell, "scrolled down, the second row's tabs and the bottom bar are shorter");
out(B.clearance(bars, { collapsed: false, secondRow: false }).top === bars.first_row.height, "no second row: the page clears the first row only");
out(/\.cloud-subtab-label/.test(scss) && /selected-only/.test(scss) && /body\.cloud-collapsed/.test(scss), "scrolled down only the selected item keeps its name (second row and bottom bar)");

// ── light/dark ──
out(B.isDark("#313131", false) && !B.isDark("rgb(255, 255, 255)", true) && B.isDark("", true) && !B.isDark("var(--x)", false), "dark/light follows the page colour Acode paints, the system mode otherwise");

// ── the wiring (read from the sources, not restated) ──
out(["menu", "ai", "profile", "configs"].every((r) => dom.includes(`"${r}"`)) && /id: "cloud-top1"/.test(dom) && /id: "cloud-top2"/.test(dom) && /id: "cloud-nav"/.test(dom), "bars-dom.js draws the three bars with hamburger, AI, Profile and Configs");
out(!/touchstart|touchmove|touchend|swipe/i.test(dom + read("src/cloud/chrome.js")), "the hamburger opens by tap only: no edge-swipe handler");
out(/ai: "wand-sparkles"/.test(dom) && /profile: "account_circle"/.test(dom) && /configs: "more_vert"/.test(dom) && hasIcon("wand-sparkles") && hasIcon("account_circle") && hasIcon("more_vert") && hasIcon("menu"), "AI sparkles, Profile avatar, Configs vertical dots and the hamburger are plain glyphs that exist in the icon font");
out(/onAi: \(\) => launchApp\(targets\.superapp\)/.test(index) && /onProfile: \(\) => launchApp\(targets\.chat\.account\)/.test(index) && /onConfigs: openProfile/.test(index), "AI -> the SuperApp, Profile -> Cloud Account, Configs -> Cloud Code's settings");
out(nav.superapp?.target === "cloud-superapp" && nav.chat?.account?.app === "cloud-account", "the AI and Profile targets are fleet ids (cloud-superapp, cloud-account), resolved at build time");
out(/linear-gradient\(180deg, var\(--cb-top\), var\(--cb-bottom\)\)/.test(scss), "the page gradient is the theme-background top -> bottom");
out(/&\.active \{\s*color: var\(--cb-bottom-accent\)/.test(scss) && /color: var\(--cb-bottom-idle\)/.test(scss) && /translateY\(calc\(-1 \* var\(--cb-bottom-lift\)\)\)/.test(scss), "bottom bar: the selected item takes the accent colour, the others the idle ink, icons lifted over their names");
out(!/\.cloud-subtab[^{]*\{[^}]*border-radius:\s*(50%|999)/.test(scss), "the selected second-row tab is never a circle or a full pill");
