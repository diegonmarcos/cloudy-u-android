// cloud-code bottom nav — the pure half (#562).
//
// No DOM, no imports: everything here is a function of nav.json and of numbers
// measured on the device, so test/test-cloud-nav.sh drives THIS file under node
// and reads the answers back. index.js is the only caller in the app.

/**
 * The tab model, in declared order. Throws on anything that would render a
 * dead tab: a view with no renderer, a duplicate id, a default that is not a tab.
 * @param {object} nav   parsed nav.json
 * @param {string[]} views  names index.js can render
 */
export function buildTabs(nav, views) {
	// #1855 P3 `tabs` are the bottom pages, `parents` the Parent Pages reached from the first row's dropdown.
	const tabs = [
		...nav.tabs.map((t) => ({ t, placement: "bottom" })),
		...(nav.parents || []).map((t) => ({ t, placement: "dropdown" })),
	].map(({ t, placement }) => ({
		id: t.id,
		label: t.label,
		icon: t.icon,
		view: t.view,
		aliases: t.aliases || [],
		placement,
		pages: t.pages || [],
	}));
	const seen = new Set();
	for (const t of tabs) {
		if (seen.has(t.id)) throw new Error(`duplicate tab id: ${t.id}`);
		seen.add(t.id);
		if (!views.includes(t.view))
			throw new Error(`tab ${t.id}: no renderer for view ${t.view}`);
		const pageIds = new Set(t.pages.map((p) => p.id));
		if (pageIds.size !== t.pages.length) throw new Error(`tab ${t.id}: duplicate sub-page id`);
		for (const p of t.pages) {
			if (!p.icon || !p.label) throw new Error(`tab ${t.id} page ${p.id}: a sub-page declares its name and icon`);
			if (p.view && !views.includes(p.view)) throw new Error(`tab ${t.id} page ${p.id}: no renderer for view ${p.view}`);
		}
	}
	if (!seen.has(nav.default_tab))
		throw new Error(`default_tab ${nav.default_tab} is not a tab`);
	return tabs;
}

/** The pages drawn on the bottom bar (the Parent Pages live in the first row's dropdown). */
export function bottomTabs(tabs) {
	return tabs.filter((t) => t.placement !== "dropdown");
}

/** The view a page renders: its own when the sub-page declares one, else its tab's. */
export function viewOf(tab, pageId) {
	const p = (tab.pages || []).find((x) => x.id === pageId);
	return (p && p.view) || tab.view;
}

/**
 * Where an id lands (#1855 P3: every pre-P3 id, tab, sub-page or alias, keeps working). Order: a tab id, nav.redirects, a tab alias,
 * then a sub-page id. Returns {tab, page} (page is null when the target is a whole tab), or null.
 * @param {object[]} tabs buildTabs() output
 * @param {object} redirects nav.redirects
 */
export function resolveTarget(tabs, redirects, id) {
	const byId = tabs.find((t) => t.id === id);
	if (byId) return { tab: byId.id, page: null };
	const r = redirects && Object.prototype.hasOwnProperty.call(redirects, id) ? redirects[id] : null;
	if (r && tabs.some((t) => t.id === r.tab)) return { tab: r.tab, page: r.page || null };
	const byAlias = tabs.find((t) => t.aliases.includes(id));
	if (byAlias) return { tab: byAlias.id, page: null };
	for (const t of tabs) if (t.pages.some((p) => p.id === id)) return { tab: t.id, page: id };
	return null;
}

/**
 * Width each tab gets on a viewport, and whether that clears the touch floor.
 */
export function tabWidth(viewportPx, tabCount) {
	return viewportPx / tabCount;
}

export function clearsTouchFloor(nav) {
	return (
		tabWidth(nav.nav.min_viewport_px, nav.tabs.length) >=
		nav.nav.min_touch_target_px
	);
}

/**
 * 'full' when every label fits inside its own tab, else 'compact' (icons for
 * all, label only on the active tab). Never truncate: a clipped label is the
 * #565 bug. Input is what the device measured, so the decision is the device's.
 * @param {{labelPx:number, tabPx:number}[]} measured
 */
export function fitLabels(measured) {
	return measured.every((m) => m.labelPx <= m.tabPx) ? "full" : "compact";
}

/**
 * What selecting a tab does. The Editor view hides every cloud panel (Acode is
 * shown untouched); a launch view (MyTerminal) fires its action and keeps the
 * current tab, because the target is another app.
 */
export function select(state, tab, launchViews, view = tab.view) {
	if (launchViews.includes(view)) {
		return { active: state.active, launch: tab.id, showPanel: state.showPanel };
	}
	return {
		active: tab.id,
		launch: null,
		showPanel: view === "editor" ? null : tab.id,
	};
}

/**
 * What the hamburger lists: the ACTIVE tab's own items, from the provider that tab's page
 * registered ({items, extra}). Nothing is shared between pages: another tab's provider is never
 * consulted, and a tab with none (the Editor, a launch view) gets an empty drawer.
 * @param {{active: string}} state
 * @param {{id: string}[]} tabs
 * @param {Object<string, function(): {items: object[], extra?: Node[]}>} menus
 */
export function menuFor(state, tabs, menus) {
	const tab = tabs.find((t) => t.id === state.active);
	const provider = tab && Object.prototype.hasOwnProperty.call(menus, tab.id) ? menus[tab.id] : null;
	const got = provider ? provider() : null;
	return { tab: tab ? tab.id : null, items: (got && got.items) || [], extra: (got && got.extra) || [] };
}
