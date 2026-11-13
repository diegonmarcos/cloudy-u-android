// TabStore: the ONE model of the open tabs. Order, the active tab, each tab's view and mode,
// and the back / forward history; persisted per mounted root so a restart restores the same
// tabs in the same order with the same one active. Pure reducers over a plain state object
// (the buffers stay in use-vault.ts); storage is injected.
import type { VaultDecl } from '../decl-types';
import { handlerFor } from '../file-types';
import type { KeyValueStore } from '../recent-vaults';

/** The two view modes. A tab's mode follows its file type; nothing else decides it. */
export type ViewMode = 'file' | 'canvas';

export interface TabMeta {
  path: string;
  /** one of the handler's views */
  view: string;
  /** HTML pages: the CDN toggle, per tab */
  cdn: boolean;
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

type Decl = Pick<VaultDecl, 'file_types'>;

/** The mode a file opens in: Canvas for a .canvas board, File for everything else. */
export function modeOfPath(decl: Decl, path: string): ViewMode {
  return handlerFor(decl, path).handler === 'canvas' ? 'canvas' : 'file';
}

/** The mode a tab is in now: a canvas shown as its JSON source is in File mode (the text editor). */
export function modeOfTab(decl: Decl, tab: Pick<TabMeta, 'path' | 'view'> | null | undefined): ViewMode | null {
  if (!tab) return null;
  return handlerFor(decl, tab.path).handler === 'canvas' && tab.view !== 'source' ? 'canvas' : 'file';
}

/** The modes a tab can switch to (top-right mode switch): a canvas has both, any other file only File. */
export function modesFor(decl: Decl, path: string): ViewMode[] {
  return handlerFor(decl, path).handler === 'canvas' ? ['canvas', 'file'] : ['file'];
}

/** The tab view that shows [mode] for this file, or null when the file has no such mode. */
export function viewForMode(decl: Decl, path: string, mode: ViewMode): string | null {
  const h = handlerFor(decl, path);
  if (h.handler === 'canvas') return mode === 'canvas' ? 'canvas' : 'source';
  return mode === 'file' ? h.default_view : null;
}

const pushHistory = (list: string[], path: string | null): string[] =>
  path === null || list[list.length - 1] === path ? list : [...list, path].slice(-HISTORY_MAX);

/** Open [path] (or activate its tab). New tabs go to the end; the oldest tab leaves when [max] is passed. */
export function openTab(s: TabState, decl: Pick<VaultDecl, 'file_types' | 'html'>, path: string, max: number): TabState {
  const existing = s.tabs.find(t => t.path === path);
  if (existing) return activateTab(s, path);
  const h = handlerFor(decl, path);
  const tab: TabMeta = { path, view: h.default_view, cdn: decl.html.cdn.default };
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
  return JSON.stringify({ v: 1, tabs: s.tabs.map(t => ({ path: t.path, view: t.view, cdn: t.cdn })), active: s.active });
}

/** Stored tabs -> state. Anything malformed is dropped, never thrown; views are checked against the file type. */
export function parseTabs(raw: string | null, decl: Pick<VaultDecl, 'file_types' | 'html'>, max: number): TabState {
  try {
    const o = JSON.parse(raw ?? 'null') as { tabs?: unknown; active?: unknown } | null;
    if (!o || !Array.isArray(o.tabs)) return EMPTY_TABS;
    const seen = new Set<string>();
    const tabs: TabMeta[] = [];
    for (const t of o.tabs as Partial<TabMeta>[]) {
      if (!t || typeof t.path !== 'string' || t.path === '' || seen.has(t.path)) continue;
      seen.add(t.path);
      const h = handlerFor(decl, t.path);
      tabs.push({ path: t.path, view: typeof t.view === 'string' && h.views.includes(t.view) ? t.view : h.default_view, cdn: t.cdn === true });
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

export function loadTabs(store: KeyValueStore, root: string, decl: Pick<VaultDecl, 'file_types' | 'html'>, max: number): TabState {
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
