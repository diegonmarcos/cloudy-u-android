// Cloud Notes (hybrid roadmap, Phase 0b): the web half of the native bottom
// nav. libs:bottomnav's island + page strips live in the Compose shell and are
// declared in build.json::ui; they know only section/page IDS. This table is
// the one place those ids become Cloud Notes workbench routes or actions, so the
// native shell never learns a URL. A `native` target is a page the Compose
// shell draws itself (Search > Ask is libs:ai's chat): the shell handles the
// tap and this half does nothing. There is no toast left: every row is a route,
// an action or a native page (a0_docs/eng-specs/cloud-notes-hybrid.md, A4.4).

export const NATIVE_NAV_EVENT = 'cloud-notes:nav';

/** Top bar > Settings > Configs: open Cloud Notes's own settings dialog. */
export const NATIVE_OPEN_SETTINGS_EVENT = 'cloud-notes:open-settings';

/** A page picked in the top bar's drawer; detail is `{ id }`. */
export const NATIVE_OPEN_DOC_EVENT = 'cloud-notes:open-doc';

/** The native object (Kotlin NativeTopBar.Host) the drawer's page list is pushed to. */
export interface CloudNotesHost {
  setDocs(json: string): void;
}

export interface DrawerDocInput {
  id: string;
  title?: string;
  trash?: boolean;
  updatedDate?: number;
}

export interface DrawerDoc {
  id: string;
  title: string;
}

/** The pages the drawer lists: not trashed, newest edit first, at most `limit`. */
export function drawerDocs(metas: DrawerDocInput[], limit = 40): DrawerDoc[] {
  return metas
    .filter(m => !m.trash)
    .sort((a, b) => (b.updatedDate ?? 0) - (a.updatedDate ?? 0))
    .slice(0, limit)
    .map(m => ({ id: m.id, title: m.title?.trim() || 'Untitled' }));
}

export interface NativeNavDetail {
  section: string;
  /** page id inside the section, or null for the section's first page */
  page?: string | null;
}

export type NativeNavTarget =
  | { kind: 'route'; to: string }
  | { kind: 'action'; action: 'new-page' | 'new-board' }
  | { kind: 'native'; what: string };

/** key = `<section>/<page>`; every build.json::ui page id has exactly one row. */
// #1717: the old workspace pages are deleted; every route row lands in the vault (the ids stay until the Compose bar is replaced).
export const NATIVE_NAV_TARGETS: Record<string, NativeNavTarget> = {
  'home/recent': { kind: 'route', to: '/vault' },
  'home/daily': { kind: 'route', to: '/vault' },
  'pages/all': { kind: 'route', to: '/vault' },
  'pages/tags': { kind: 'route', to: '/vault' },
  'pages/vault': { kind: 'route', to: '/vault' },
  'pages/collections': { kind: 'route', to: '/vault' },
  'pages/new': { kind: 'action', action: 'new-page' },
  'canvas/boards': { kind: 'route', to: '/vault' },
  'canvas/new': { kind: 'action', action: 'new-board' },
  'graph/global': { kind: 'route', to: '/vault' },
  'graph/local': { kind: 'route', to: '/vault' },
  'graph/tags': { kind: 'route', to: '/vault' },
  'search/search': { kind: 'route', to: '/vault' },
  'search/ask': { kind: 'native', what: 'AI chat (libs:ai)' },
};

/** First page of each section: what tapping the island item opens. */
export const NATIVE_NAV_FIRST_PAGE: Record<string, string> = {
  home: 'recent',
  pages: 'vault',
  canvas: 'boards',
  graph: 'global',
  search: 'search',
};

export function resolveNativeNav(
  detail: NativeNavDetail
): NativeNavTarget | null {
  const page = detail.page || NATIVE_NAV_FIRST_PAGE[detail.section];
  if (!page) return null;
  return NATIVE_NAV_TARGETS[`${detail.section}/${page}`] ?? null;
}

/** True inside the Compose shell, which sets this before the bundle boots. */
export function hasNativeNav(): boolean {
  return (
    typeof window !== 'undefined' &&
    (window as unknown as { cloudNotesNativeNav?: boolean })
      .cloudNotesNativeNav === true
  );
}

/** First path segments that are routes, not a doc id (mobile workbench-router.ts). */
const RESERVED_ROOTS = new Set([
  'home',
  'search',
  'all',
  'collection',
  'tag',
  'journals',
  'trash',
  'graph',
  'boards',
  'vault',
]);

/** `/<docId>` -> docId; any other location -> null. */
export function docIdFromPath(pathname: string): string | null {
  const parts = pathname.split('/').filter(Boolean);
  if (parts.length !== 1 || RESERVED_ROOTS.has(parts[0])) return null;
  return parts[0];
}

let lastDocId: string | null = null;

/** The note that was open last: the local graph follows it. */
export function setLastDocId(id: string | null) {
  if (id) lastDocId = id;
}

export function getLastDocId(): string | null {
  return lastDocId;
}
