// TabStore: the ONE model of the open tabs. Order, the active tab, each tab's view and mode,
// and the back / forward history; persisted per mounted root so a restart restores the same
// tabs in the same order with the same one active. Pure reducers over a plain state object
// (the buffers stay in use-vault.ts); storage is injected.
import type { VaultDecl } from '../decl-types';
import { handlerFor } from '../file-types';
import type { KeyValueStore } from '../recent-vaults';
import { CANVAS_ZOOM, storedZoom, TEXT_ZOOM } from './zoom';

/** The three modes of the top-right switch (build.json::vault.view_modes says which a file type offers). */
export type ViewMode = 'raw' | 'edit' | 'view';
export const VIEW_MODES: readonly ViewMode[] = ['raw', 'edit', 'view'];

export interface TabMeta {
  path: string;
  /** one of the handler's views (what the mode shows; see view_modes) */
  view: string;
  /** Raw (the source), Edit (WYSIWYG, or the editable board) or View (rendered, read only; a canvas is locked) */
  mode: ViewMode;
  /** HTML pages: the CDN toggle, per tab */
  cdn: boolean;
  /** ticket 1788 pinch zoom, kept per tab: the text scale of a note or data file, the board zoom of a canvas (1 = untouched) */
  zoom: number;
}

export interface TabState {
  tabs: TabMeta[];
  active: string | null;
  /** paths activated before / after the current one (history buttons) */
  back: string[];
  forward: string[];
}

export const EMPTY_TABS: TabState = { tabs: [], active: null, back: [], forward: [] };
const HISTORY_MAX = 50;

type Decl = Pick<VaultDecl, 'file_types' | 'view_modes'>;

/** The modes a file type offers, in switch order (Raw, Edit, View). */
export function modesFor(decl: Decl, path: string): ViewMode[] {
  const map = decl.view_modes[handlerFor(decl, path).handler] ?? {};
  return VIEW_MODES.filter(m => map[m] !== undefined);
}

/** The tab view that shows [mode] for this file, or null when the file has no such mode. */
export function viewForMode(decl: Decl, path: string, mode: ViewMode): string | null {
  return decl.view_modes[handlerFor(decl, path).handler]?.[mode] ?? null;
}

/** The mode a file opens in: a canvas opens editable, a source-only file in Raw, everything else rendered (View). */
export function modeOfPath(decl: Decl, path: string): ViewMode {
  const h = handlerFor(decl, path);
  const offered = modesFor(decl, path);
  const want: ViewMode = h.handler === 'canvas' ? 'edit' : h.default_view === 'source' ? 'raw' : 'view';
  return offered.includes(want) ? want : (offered[0] ?? 'raw');
}

/** The mode a tab is in now; a mode the file type does not offer falls back to the file's own. */
export function modeOfTab(decl: Decl, tab: Pick<TabMeta, 'path' | 'mode'> | null | undefined): ViewMode | null {
  if (!tab) return null;
  return modesFor(decl, tab.path).includes(tab.mode) ? tab.mode : modeOfPath(decl, tab.path);
}

/** A tab in [mode] (its view follows). */
export function withMode(decl: Decl, tab: TabMeta, mode: ViewMode): TabMeta {
  const view = viewForMode(decl, tab.path, mode);
  return view === null ? tab : { ...tab, mode, view };
}

/** Edit and View swap, as Ctrl+E does: from View to Edit when the file has one, from Edit back to View, a Raw file goes to View. */
export function toggledMode(decl: Decl, tab: Pick<TabMeta, 'path' | 'mode'>): ViewMode {
  const offered = modesFor(decl, tab.path);
  const now = modeOfTab(decl, tab)!;
  const order: ViewMode[] = now === 'view' ? ['edit', 'raw'] : ['view', 'edit', 'raw'];
  return order.find(m => m !== now && offered.includes(m)) ?? now;
}

const pushHistory = (list: string[], path: string | null): string[] =>
  path === null || list[list.length - 1] === path ? list : [...list, path].slice(-HISTORY_MAX);

/** Open [path] (or activate its tab). New tabs go to the end; the oldest tab leaves when [max] is passed. */
export function openTab(s: TabState, decl: Pick<VaultDecl, 'file_types' | 'html' | 'view_modes'>, path: string, max: number): TabState {
  const existing = s.tabs.find(t => t.path === path);
  if (existing) return activateTab(s, path);
  const h = handlerFor(decl, path);
  const mode = modeOfPath(decl, path);
  const tab: TabMeta = { path, view: viewForMode(decl, path, mode) ?? h.default_view, mode, cdn: decl.html.cdn.default, zoom: 1 };
  const tabs = [...s.tabs, tab].slice(-Math.max(1, max));
  return { tabs, active: path, back: pushHistory(s.back, s.active), forward: [] };
}

export function activateTab(s: TabState, path: string): TabState {
  if (!s.tabs.some(t => t.path === path) || s.active === path) return s;
  return { ...s, active: path, back: pushHistory(s.back, s.active), forward: [] };
}

export function closeTab(s: TabState, path: string): TabState {
  const i = s.tabs.findIndex(t => t.path === path);
  if (i < 0) return s;
  const tabs = s.tabs.filter(t => t.path !== path);
  const active = s.active === path ? (tabs[Math.min(i, tabs.length - 1)]?.path ?? null) : s.active;
  return { tabs, active, back: s.back.filter(p => p !== path), forward: s.forward.filter(p => p !== path) };
}

export function patchTab(s: TabState, path: string, patch: Partial<Omit<TabMeta, 'path'>>): TabState {
  return { ...s, tabs: s.tabs.map(t => (t.path === path ? { ...t, ...patch } : t)) };
}

/** Move the tab at [from] to [to] (tab reordering). */
export function moveTab(s: TabState, from: number, to: number): TabState {
  if (from === to || from < 0 || to < 0 || from >= s.tabs.length || to >= s.tabs.length) return s;
  const tabs = [...s.tabs];
  const [t] = tabs.splice(from, 1);
  tabs.splice(to, 0, t!);
  return { ...s, tabs };
}

/** A file was renamed or moved: its tab and history follow it. */
export function renameTab(s: TabState, from: string, to: string): TabState {
  const swap = (p: string) => (p === from ? to : p);
  return { tabs: s.tabs.map(t => (t.path === from ? { ...t, path: to } : t)), active: s.active === null ? null : swap(s.active), back: s.back.map(swap), forward: s.forward.map(swap) };
}

export const canGoBack = (s: TabState): boolean => s.back.some(p => s.tabs.some(t => t.path === p));
export const canGoForward = (s: TabState): boolean => s.forward.some(p => s.tabs.some(t => t.path === p));

export function goBack(s: TabState): TabState {
  const back = [...s.back];
  while (back.length) {
    const p = back.pop()!;
    if (p !== s.active && s.tabs.some(t => t.path === p)) {
      return { ...s, active: p, back, forward: s.active === null ? s.forward : [...s.forward, s.active] };
    }
  }
  return s;
}

export function goForward(s: TabState): TabState {
  const forward = [...s.forward];
  while (forward.length) {
    const p = forward.pop()!;
    if (p !== s.active && s.tabs.some(t => t.path === p)) {
      return { ...s, active: p, forward, back: s.active === null ? s.back : [...s.back, s.active] };
    }
  }
  return s;
}

// ---------------------------------------------------------------- persistence

export const tabsKey = (root: string) => `cloud-notes:vault:tabs:${root}`;
/** The root that was open last: the vault view reopens it on start so the tabs can be restored. */
export const LAST_VAULT_KEY = 'cloud-notes:vault:last';

export function serializeTabs(s: TabState): string {
  return JSON.stringify({ v: 1, tabs: s.tabs.map(t => ({ path: t.path, view: t.view, mode: t.mode, cdn: t.cdn, zoom: t.zoom })), active: s.active });
}

/** Stored tabs -> state. Anything malformed is dropped, never thrown; views are checked against the file type. */
export function parseTabs(raw: string | null, decl: Pick<VaultDecl, 'file_types' | 'html' | 'view_modes'>, max: number): TabState {
  try {
    const o = JSON.parse(raw ?? 'null') as { tabs?: unknown; active?: unknown } | null;
    if (!o || !Array.isArray(o.tabs)) return EMPTY_TABS;
    const seen = new Set<string>();
    const tabs: TabMeta[] = [];
    for (const t of o.tabs as Partial<TabMeta>[]) {
      if (!t || typeof t.path !== 'string' || t.path === '' || seen.has(t.path)) continue;
      seen.add(t.path);
      const h = handlerFor(decl, t.path);
      // a tab stored before the Raw | Edit | View switch has no mode: its view says which one it was in
      const offered = modesFor(decl, t.path);
      const legacy: ViewMode | null = typeof t.view !== 'string' || !h.views.includes(t.view) ? null : t.view === 'source' ? 'raw' : t.view === 'canvas' ? 'edit' : null;
      const stored = (t as { mode?: unknown }).mode;
      const mode: ViewMode = VIEW_MODES.includes(stored as ViewMode) && offered.includes(stored as ViewMode)
        ? (stored as ViewMode)
        : legacy && offered.includes(legacy) ? legacy : modeOfPath(decl, t.path);
      const range = h.handler === 'canvas' ? CANVAS_ZOOM : TEXT_ZOOM;
      tabs.push({ path: t.path, view: viewForMode(decl, t.path, mode) ?? h.default_view, mode, cdn: t.cdn === true, zoom: storedZoom((t as { zoom?: unknown }).zoom, range) });
    }
    const kept = tabs.slice(-Math.max(1, max));
    const active = typeof o.active === 'string' && kept.some(t => t.path === o.active) ? o.active : (kept[kept.length - 1]?.path ?? null);
    return { tabs: kept, active, back: [], forward: [] };
  } catch {
    return EMPTY_TABS;
  }
}

export function saveTabs(store: KeyValueStore, root: string, s: TabState): void {
  try {
    store.setItem(tabsKey(root), serializeTabs(s));
  } catch {
    /* a full or blocked store never breaks the editor */
  }
}

export function loadTabs(store: KeyValueStore, root: string, decl: Pick<VaultDecl, 'file_types' | 'html' | 'view_modes'>, max: number): TabState {
  try {
    return parseTabs(store.getItem(tabsKey(root)), decl, max);
  } catch {
    return EMPTY_TABS;
  }
}

export function readLastVault(store: KeyValueStore): { root: string; name: string } | null {
  try {
    const o = JSON.parse(store.getItem(LAST_VAULT_KEY) ?? 'null') as { root?: unknown; name?: unknown } | null;
    return o && typeof o.root === 'string' && o.root !== '' && typeof o.name === 'string' ? { root: o.root, name: o.name } : null;
  } catch {
    return null;
  }
}

export function writeLastVault(store: KeyValueStore, vault: { root: string; name: string } | null): void {
  try {
    store.setItem(LAST_VAULT_KEY, vault ? JSON.stringify({ root: vault.root, name: vault.name }) : 'null');
  } catch {
    /* ignored */
  }
}
