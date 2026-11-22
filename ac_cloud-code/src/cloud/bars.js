// cloud-code shared bars — the pure half (#1158).
//
// No DOM, no imports: the numbers and rules of the fleet's shared navigation design (#1154: first row,
// second row, bottom bar, theme background) as functions of nav.json::bars and of values measured on
// the device. bars-dom.js draws them; test/cloud_bars.mjs drives THIS file under node.

/** "0xAARRGGBB" (the Kotlin literal the shared libs use) -> a CSS rgba() colour. */
export function argbToCss(argb) {
	const m = /^0x([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})([0-9A-Fa-f]{2})$/.exec(argb);
	if (!m) throw new Error(`not a 0xAARRGGBB colour: ${argb}`);
	const [a, r, g, b] = m.slice(1).map((h) => parseInt(h, 16));
	return `rgba(${r}, ${g}, ${b}, ${Math.round((a / 255) * 1000) / 1000})`;
}

/** WCAG relative luminance of a "#rgb", "#rrggbb" or "rgb(r, g, b)" CSS colour; null when unparseable. */
export function luminance(css) {
	let rgb = null;
	const s = String(css || "").trim();
	let m = /^#([0-9a-f]{3})$/i.exec(s);
	if (m) rgb = [...m[1]].map((c) => parseInt(c + c, 16));
	m = /^#([0-9a-f]{6})/i.exec(s);
	if (m) rgb = [0, 2, 4].map((i) => parseInt(m[1].slice(i, i + 2), 16));
	m = /^rgba?\(\s*(\d+)[\s,]+(\d+)[\s,]+(\d+)/i.exec(s);
	if (m) rgb = [m[1], m[2], m[3]].map(Number);
	if (!rgb) return null;
	const [r, g, b] = rgb.map((v) => {
		const c = v / 255;
		return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
	});
	return 0.2126 * r + 0.7152 * g + 0.0722 * b;
}

/** The app's light/dark mode: the page colour Acode's theme paints decides, the system mode is the fallback. */
export function isDark(pageCss, systemDark) {
	const l = luminance(pageCss);
	return l == null ? !!systemDark : l < 0.4;
}

/** Every CSS custom property the stylesheet reads, for one mode. dp are CSS px. */
export function cssVars(bars, dark) {
	const mode = dark ? "dark" : "light";
	const v = {};
	for (const [k, c] of Object.entries(bars.theme[mode])) v[`--cb-${k}`] = argbToCss(c);
	for (const [k, c] of Object.entries(bars.bottom_colors[mode])) v[`--cb-bottom-${k}`] = argbToCss(c);
	for (const row of ["first_row", "second_row", "bottom", "collapse"]) {
		for (const [k, n] of Object.entries(bars[row])) {
			if (typeof n === "number") v[`--cb-${row.replace("_", "-")}-${k.replace(/_/g, "-")}`] = k === "icons_right" ? String(n) : `${n}px`;
		}
	}
	return v;
}

/** The width of one bottom cell on a bar of [barWidth] holding [count] items (libs:ui-nav-bars BottomSpec.cellWidth). */
export function cellWidth(bars, barWidth, count) {
	const b = bars.bottom;
	if (count <= 0) return b.cell;
	return Math.min(b.cell, (barWidth - b.inner_pad * 2) / count);
}

/** The bar's width on a viewport of [viewportPx]: at most max_width, inset by the side gutters. */
export function barWidth(bars, viewportPx) {
	return Math.min(bars.bottom.max_width, viewportPx - bars.bottom.side_gutter * 2);
}

/** True when the bottom cells of [count] items on [viewportPx] are at least [floorPx] wide. */
export function cellsClear(bars, viewportPx, count, floorPx) {
	return cellWidth(bars, barWidth(bars, viewportPx), count) >= floorPx;
}

/** The sub-pages of a bottom page ([] for one with none). */
export function pagesOf(tab) {
	return Array.isArray(tab && tab.pages) ? tab.pages : [];
}

/** The second row is drawn only for two or more sub-pages (one tab alone is the page itself). */
export function secondRowShown(tab) {
	return pagesOf(tab).length >= 2;
}

/**
 * The title dropdown: the bottom page (depth 0) then its sub-pages (depth 1), the path to the page shown marked
 * (libs:ui-nav-bars titleHierarchy).
 * @returns {{id:string,label:string,depth:number,tabId:string,selected:boolean}[]}
 */
export function titleHierarchy(tab, currentPageId) {
	const pages = pagesOf(tab);
	const onPath = pages.some((p) => p.id === currentPageId);
	return [
		{ id: tab.id, label: tab.label, depth: 0, tabId: tab.id, selected: !onPath },
		...pages.map((p) => ({ id: p.id, label: p.label, depth: 1, tabId: tab.id, selected: p.id === currentPageId })),
	];
}

/**
 * #1855 P3 the first row's dropdown with the Parent Pages: the open bottom page and its sub-pages (as titleHierarchy), then every
 * Parent Page (Backlog) with ITS sub-pages, each tagged `group` "tab" or "parent". A Parent Page that is open is listed once, in
 * the parent group, with the path to the page shown marked; the bottom pages stay one tap away on the bottom bar.
 * @param {object[]} tabs every page (bottom and dropdown, tabs.js::buildTabs)
 */
export function dropdownEntries(tabs, tab, currentPageId) {
	const parents = tabs.filter((t) => t.placement === "dropdown");
	const out = [];
	if (tab && tab.placement !== "dropdown") out.push(...titleHierarchy(tab, currentPageId).map((e) => ({ ...e, group: "tab" })));
	for (const p of parents) {
		const open = tab && p.id === tab.id;
		out.push(...titleHierarchy(p, open ? currentPageId : null).map((e) => ({ ...e, group: "parent", selected: open ? e.selected : false })));
	}
	return out;
}

/** The page whose file is [file] (the backlog dist links change the file in place), or null. */
export function pageOfFile(tab, file) {
	const p = pagesOf(tab).find((x) => x.file === file);
	return p ? p.id : null;
}

/**
 * Scroll-collapse (the shared rule): scrolling DOWN past threshold_px hides the first row and compacts the second row and the bottom bar,
 * scrolling UP past it, or reaching the top, restores them. A change is followed by a quiet period (settle_ms): the
 * page resizes with the bars, and that resize must not read as a scroll back (it would flicker at the end of a list).
 * @param {{collapsed:boolean, acc:number, quietUntil:number}} st
 * @param {{dy:number, atTop:boolean, now:number}} ev
 */
export function collapseStep(st, ev, cfg) {
	if (ev.now < st.quietUntil) return st;
	if (ev.atTop) {
		return st.collapsed ? { collapsed: false, acc: 0, quietUntil: ev.now + cfg.settle_ms } : { ...st, acc: 0 };
	}
	if (!ev.dy) return st;
	const acc = Math.sign(ev.dy) === Math.sign(st.acc) ? st.acc + ev.dy : ev.dy;
	if (!st.collapsed && acc > cfg.threshold_px) return { collapsed: true, acc: 0, quietUntil: ev.now + cfg.settle_ms };
	if (st.collapsed && acc < -cfg.threshold_px) return { collapsed: false, acc: 0, quietUntil: ev.now + cfg.settle_ms };
	return { ...st, acc };
}

/**
 * Heights the page must clear: the rows above it and the bar below it (the bar's bottom margin included). Scrolled down
 * (collapsed) the first row is gone, the second row's tabs are compact and the bottom bar is shorter.
 */
export function clearance(bars, { collapsed, secondRow }) {
	const f = bars.first_row, s = bars.second_row, b = bars.bottom;
	const first = collapsed ? 0 : f.height;
	const second = secondRow ? s.top_inset + s.bottom_inset + s.strip_pad_v * 2 + (collapsed ? s.compact_tab_height : s.tab_height) : 0;
	return { top: first + second, bottom: (collapsed ? b.collapsed_height : b.height) + b.bottom_margin };
}
