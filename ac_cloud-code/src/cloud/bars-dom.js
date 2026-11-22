// cloud-code shared bars — the DOM half (#1158): the fleet's default top and bottom navigation (#1154)
// drawn in the WebView from nav.json, because the shared Compose libs cannot sit in a Cordova page
// (see index.js). Layout, sizes and colours come from bars.js / nav.json::bars (equal to the libs'
// constants, held by the nav-shape guard); this file only creates the elements and wires the events.
//
//   #cloud-top1  first row   plain hamburger | title island + hierarchy dropdown | AI, Profile, Configs
//   #cloud-top2  second row  smaller icon pills with their names, violet selection
//   #cloud-nav   bottom bar  Cloud Search-style pill, coloured selection, an icon and its name per item
// The page gradient is on <body>; scrolling down hides the first row and compacts the other two (only the
// selected item keeps its name). A bottom name that would not fit its cell is never truncated: then the bar
// names the selected item only (tabs.js::fitLabels).
import { el, icon } from "./dom";
import { cssVars, clearance, collapseStep, dropdownEntries, isDark, pagesOf, secondRowShown } from "./bars";
import { bottomTabs, fitLabels } from "./tabs";

const ICONS = { menu: "menu", ai: "wand-sparkles", profile: "account_circle", configs: "more_vert" };
const IGNORE_SCROLL = "#cloud-top1, #cloud-top2, #cloud-nav, .cloud-dropdown, .cloud-overlay";

/**
 * @param {object} o
 * @param {object} o.nav parsed nav.json
 * @param {object[]} o.tabs buildTabs() output
 * @param {{onTab, onPage, onMenu, onAi, onProfile, onConfigs}} o.on handlers
 */
export function mountBars({ nav, tabs, on }) {
	const bars = nav.bars;
	const root = document.documentElement;
	let tab = null;
	let pageId = null;
	let dark = true;
	let collapse = { collapsed: false, acc: 0, quietUntil: 0 };

	const $menu = plain("menu", "Menu", "menu", on.onMenu);
	const $title = el("button", { className: "cloud-island", "data-role": "title", "aria-haspopup": "menu", onclick: () => toggleDropdown() });
	const $drop = el("div", { className: "cloud-dropdown", role: "menu", hidden: true });
	const $top1 = el("header", { id: "cloud-top1" },
		el("div", { className: "cloud-slot cloud-slot-left" }, $menu),
		el("div", { className: "cloud-island-wrap" }, $title, $drop),
		el("div", { className: "cloud-slot cloud-slot-right" },
			plain("ai", "AI", "ai", on.onAi),
			plain("profile", "Profile", "profile", on.onProfile),
			plain("configs", "Configs", "configs", on.onConfigs)));
	const $strip = el("div", { className: "cloud-strip", role: "tablist" });
	const $top2 = el("div", { id: "cloud-top2", hidden: true }, $strip);
	const $nav = el("nav", { id: "cloud-nav", role: "tablist" });
	const cells = {};
	for (const t of bottomTabs(tabs)) {
		cells[t.id] = el("button", { className: "cloud-tab", role: "tab", "data-tab": t.id, "aria-label": t.label, onclick: () => on.onTab(t.id) },
			el("span", { className: `icon cloud-tab-icon ${t.icon}` }),
			el("span", { className: "cloud-tab-label" }, t.label));
		$nav.append(cells[t.id]);
	}
	document.body.append($top1, $top2, $nav);
	document.body.classList.add("cloud-bars");

	function plain(role, label, name, onclick) {
		return el("button", { className: "cloud-plain", title: label, "aria-label": label, "data-role": role, onclick }, icon(ICONS[name]));
	}

	function applyTheme() {
		const page = getComputedStyle(document.body).getPropertyValue("--secondary-color");
		const sys = window.matchMedia ? window.matchMedia("(prefers-color-scheme: dark)").matches : true;
		dark = isDark(page, sys);
		for (const [k, v] of Object.entries(cssVars(bars, dark))) root.style.setProperty(k, v);
		document.body.classList.toggle("cloud-dark", dark);
		document.body.classList.toggle("cloud-light", !dark);
	}

	// Names for every item when they all fit their cells, else the selected one only (measured here, never assumed).
	function fit() {
		if (collapse.collapsed) return;
		$nav.classList.remove("compact");
		const measured = Object.values(cells).map((b) => ({ labelPx: b.querySelector(".cloud-tab-label").scrollWidth, tabPx: b.clientWidth }));
		$nav.classList.toggle("compact", fitLabels(measured) === "compact");
	}

	function layout() {
		const c = clearance(bars, { collapsed: collapse.collapsed, secondRow: !$top2.hidden });
		root.style.setProperty("--cloud-top-h", `${c.top}px`);
		root.style.setProperty("--cloud-nav-h", `${c.bottom}px`);
		document.body.classList.toggle("cloud-collapsed", collapse.collapsed);
		fit();
	}
	window.addEventListener("resize", fit);

	function drawStrip() {
		const pages = pagesOf(tab);
		$top2.hidden = !secondRowShown(tab);
		$strip.replaceChildren(...pages.map((p) => {
			const on_ = p.id === pageId;
			return el("button", {
				className: `cloud-subtab${on_ ? " active" : ""}`, role: "tab", "aria-selected": on_ ? "true" : "false", "aria-label": p.label,
				"data-page": p.id, onclick: () => (on_ ? null : on.onPage(tab.id, p.id)),
			}, el("span", { className: `icon cloud-subtab-icon ${p.icon}` }), el("span", { className: "cloud-subtab-label" }, p.label));
		}));
	}

	function closeDropdown() {
		$drop.hidden = true;
		$title.setAttribute("aria-expanded", "false");
	}

	function toggleDropdown() {
		if (!$drop.hidden) return closeDropdown();
		$drop.replaceChildren(...dropdownEntries(tabs, tab, pageId).map((e) => el("button", {
			className: `cloud-dropdown-item${e.selected ? " selected" : ""}${e.group === "parent" ? " parent" : ""}${e.group === "parent" && e.depth === 0 ? " parent-head" : ""}`, role: "menuitem", "data-entry": e.id, "data-group": e.group,
			style: `padding-left: ${16 + 16 * e.depth}px`,
			onclick: () => { closeDropdown(); e.depth === 0 ? on.onTab(e.tabId, null) : on.onPage(e.tabId, e.id); },
		}, e.label)));
		$drop.hidden = false;
		$title.setAttribute("aria-expanded", "true");
	}
	document.addEventListener("click", (e) => { if (!$drop.hidden && !e.target.closest(".cloud-island-wrap")) closeDropdown(); });

	// Scrolling: any scroller in the page (a panel, the chat list, Acode's editor); per-scroller last position.
	const lastY = new WeakMap();
	document.addEventListener("scroll", (e) => {
		const t = e.target === document ? document.scrollingElement : e.target;
		if (!(t instanceof Element) || t.closest(IGNORE_SCROLL)) return;
		const y = t.scrollTop;
		const dy = y - (lastY.has(t) ? lastY.get(t) : y);
		lastY.set(t, y);
		const next = collapseStep(collapse, { dy, atTop: y <= 0, now: Date.now() }, bars.collapse);
		if (next === collapse) return;
		const changed = next.collapsed !== collapse.collapsed;
		collapse = next;
		if (changed) layout();
	}, true);

	applyTheme();
	layout();

	return {
		/** Select a bottom page (and, when it has sub-pages, one of them). */
		setTab(t, page) {
			tab = t;
			pageId = page || (pagesOf(t)[0] && pagesOf(t)[0].id) || null;
			for (const [id, b] of Object.entries(cells)) {
				b.classList.toggle("active", id === t.id);
				b.setAttribute("aria-selected", id === t.id ? "true" : "false");
			}
			$title.textContent = t.label;
			closeDropdown();
			applyTheme();
			collapse = { collapsed: false, acc: 0, quietUntil: 0 };
			drawStrip();
			layout();
		},
		/** Mark a sub-page of the current tab as shown (null: none of them). */
		setPage(page) {
			pageId = page;
			drawStrip();
		},
		/** The page title in the island (the AI tab names its agent). */
		setTitle(text) {
			$title.textContent = text;
		},
	};
}
