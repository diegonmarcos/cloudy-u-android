// FileIndex: the ONE model of what is in the mounted root. It is fed the entries the walk and
// the lazy folder listings produce and answers everything the sidebar shows: the tree rows
// (filtered, ordered, with the open folders), the flat "recent" list, the counts, and the
// collapse / expand sets. Pure, so the sidebar is only a renderer and the tests run under node.
import { extOf } from '../file-types';

export interface IndexEntry {
  /** vault-relative path with '/' */
  path: string;
  name: string;
  isDir: boolean;
  size: number;
  /** last modified, epoch ms (0 when the backend does not say) */
  mtime: number;
}

export type FileFilter = 'all' | 'canvas' | 'md' | 'data';
export const FILE_FILTERS: { id: FileFilter; label: string }[] = [
  { id: 'all', label: 'All files' },
  { id: 'md', label: 'Only notes (.md)' },
  { id: 'canvas', label: 'Only canvases (.canvas)' },
  { id: 'data', label: 'Only data files (json, csv, yaml ...)' },
];

export type OrderBy = 'name' | 'mtime';
export const ORDERS: { id: OrderBy; label: string }[] = [
  { id: 'name', label: 'File name (A to Z)' },
  { id: 'mtime', label: 'Modified time (new to old)' },
];

export const MD_EXT = ['md', 'markdown'];
export const CANVAS_EXT = ['canvas'];
/** Data files: structured text a note vault carries beside its notes. */
export const DATA_EXT = ['json', 'jsonc', 'geojson', 'csv', 'tsv', 'yaml', 'yml', 'toml', 'xml', 'ini'];

export const RECENT_MAX = 30;

export function matchesFilter(path: string, filter: FileFilter): boolean {
  if (filter === 'all') return true;
  const ext = extOf(path);
  if (filter === 'md') return MD_EXT.includes(ext);
  if (filter === 'canvas') return CANVAS_EXT.includes(ext);
  return DATA_EXT.includes(ext);
}

/** The badge a file row carries: its extension, upper case, or '' for a file without one. */
export const extBadge = (path: string): string => extOf(path).toUpperCase();

/** Badge family, for its colour: note, canvas, data, image, web, code/text, other. */
export function badgeKind(path: string): 'md' | 'canvas' | 'data' | 'image' | 'web' | 'text' | 'other' {
  const ext = extOf(path);
  if (MD_EXT.includes(ext)) return 'md';
  if (CANVAS_EXT.includes(ext)) return 'canvas';
  if (DATA_EXT.includes(ext)) return 'data';
  if (['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'avif', 'svg'].includes(ext)) return 'image';
  if (['html', 'htm', 'xhtml'].includes(ext)) return 'web';
  if (['txt', 'css', 'js', 'ts', 'sh', 'py', 'log', 'mmd', 'mermaid'].includes(ext)) return 'text';
  return 'other';
}

const parentOf = (path: string) => (path.includes('/') ? path.slice(0, path.lastIndexOf('/')) : '');

export interface ViewOptions {
  filter: FileFilter;
  order: OrderBy;
  /** folders shown open */
  expanded: ReadonlySet<string>;
  showHidden: boolean;
  /** folder names never shown (build.json::vault.limits.skip_dirs) */
  skipDirs: readonly string[];
}

export interface TreeRow {
  entry: IndexEntry;
  depth: number;
  /** folders only */
  expanded: boolean;
}

const byName = (a: IndexEntry, b: IndexEntry) => a.name.localeCompare(b.name, undefined, { numeric: true, sensitivity: 'base' }) || a.path.localeCompare(b.path);
const byMtime = (a: IndexEntry, b: IndexEntry) => b.mtime - a.mtime || byName(a, b);

export class FileIndex {
  private readonly byPath = new Map<string, IndexEntry>();
  private readonly children = new Map<string, IndexEntry[]>();
  /** true once the whole root was walked: a filter may then hide folders with no match below them */
  readonly complete: boolean;

  constructor(entries: Iterable<IndexEntry>, complete = false) {
    this.complete = complete;
    for (const e of entries) {
      if (!e || typeof e.path !== 'string' || e.path === '') continue;
      this.byPath.set(e.path, e);
    }
    // a file whose folder was never listed still gets its ancestors, so the tree has no orphans
    for (const e of [...this.byPath.values()]) {
      let dir = parentOf(e.path);
      while (dir !== '' && !this.byPath.has(dir)) {
        this.byPath.set(dir, { path: dir, name: dir.slice(dir.lastIndexOf('/') + 1), isDir: true, size: 0, mtime: 0 });
        dir = parentOf(dir);
      }
    }
    for (const e of this.byPath.values()) {
      const p = parentOf(e.path);
      const list = this.children.get(p);
      if (list) list.push(e);
      else this.children.set(p, [e]);
    }
  }

  get(path: string): IndexEntry | undefined {
    return this.byPath.get(path);
  }

  has(path: string): boolean {
    return this.byPath.has(path);
  }

  private visible(e: IndexEntry, o: Pick<ViewOptions, 'showHidden' | 'skipDirs'>): boolean {
    if (!o.showHidden && e.name.startsWith('.')) return false;
    if (e.isDir && o.skipDirs.includes(e.name)) return false;
    return true;
  }

  /** Every file under the root that the options let through (hidden folders hide what is in them). */
  files(o: Pick<ViewOptions, 'showHidden' | 'skipDirs'> & { filter?: FileFilter }): IndexEntry[] {
    const out: IndexEntry[] = [];
    const visit = (dir: string) => {
      for (const e of this.children.get(dir) ?? []) {
        if (!this.visible(e, o)) continue;
        if (e.isDir) visit(e.path);
        else if (matchesFilter(e.path, o.filter ?? 'all')) out.push(e);
      }
    };
    visit('');
    return out;
  }

  folders(o: Pick<ViewOptions, 'showHidden' | 'skipDirs'>): IndexEntry[] {
    const out: IndexEntry[] = [];
    const visit = (dir: string) => {
      for (const e of this.children.get(dir) ?? []) {
        if (!e.isDir || !this.visible(e, o)) continue;
        out.push(e);
        visit(e.path);
      }
    };
    visit('');
    return out;
  }

  /** "N files, M folders" of the mounted root. */
  counts(o: Pick<ViewOptions, 'showHidden' | 'skipDirs'>): { files: number; folders: number } {
    return { files: this.files(o).length, folders: this.folders(o).length };
  }

  private hasMatchBelow(dir: string, o: ViewOptions): boolean {
    for (const e of this.children.get(dir) ?? []) {
      if (!this.visible(e, o)) continue;
      if (e.isDir ? this.hasMatchBelow(e.path, o) : matchesFilter(e.path, o.filter)) return true;
    }
    return false;
  }

  /** Direct children of [dir] as the tree shows them: folders first, then files, in the chosen order. */
  list(dir: string, o: ViewOptions): IndexEntry[] {
    const sort = o.order === 'mtime' ? byMtime : byName;
    const all = (this.children.get(dir) ?? []).filter(e => this.visible(e, o));
    const dirs = all
      .filter(e => e.isDir)
      // with a filter on, a folder with nothing matching below it is hidden once the walk is complete
      .filter(e => o.filter === 'all' || !this.complete || this.hasMatchBelow(e.path, o))
      .sort(o.order === 'mtime' ? (a, b) => this.newestBelow(b, o) - this.newestBelow(a, o) || byName(a, b) : byName);
    const files = all.filter(e => !e.isDir && matchesFilter(e.path, o.filter)).sort(sort);
    return [...dirs, ...files];
  }

  /** A folder orders by the newest file under it (its own mtime when it has none). */
  private newestBelow(dir: IndexEntry, o: ViewOptions): number {
    let newest = dir.mtime;
    for (const e of this.children.get(dir.path) ?? []) {
      if (!this.visible(e, o)) continue;
      const t = e.isDir ? this.newestBelow(e, o) : e.mtime;
      if (t > newest) newest = t;
    }
    return newest;
  }

  /** The tree as flat rows: a folder's children follow it only while it is expanded. */
  rows(o: ViewOptions): TreeRow[] {
    const out: TreeRow[] = [];
    const visit = (dir: string, depth: number) => {
      for (const e of this.list(dir, o)) {
        const open = e.isDir && o.expanded.has(e.path);
        out.push({ entry: e, depth, expanded: open });
        if (open) visit(e.path, depth + 1);
      }
    };
    visit('', 0);
    return out;
  }

  /** The [max] most recently modified files across every folder under the root, newest first. */
  recent(o: Pick<ViewOptions, 'showHidden' | 'skipDirs'> & { filter?: FileFilter }, max = RECENT_MAX): IndexEntry[] {
    return this.files(o).sort(byMtime).slice(0, Math.max(0, max));
  }

  /** Expand all: every visible folder. Collapse all is the empty set. */
  expandAll(o: Pick<ViewOptions, 'showHidden' | 'skipDirs'>): Set<string> {
    return new Set(this.folders(o).map(f => f.path));
  }

  /** The folders to open so [path] is visible in the tree. */
  reveal(path: string, expanded: ReadonlySet<string>): Set<string> {
    const next = new Set(expanded);
    let dir = parentOf(path);
    while (dir !== '') {
      next.add(dir);
      dir = parentOf(dir);
    }
    return next;
  }
}

export const collapseAll = (): Set<string> => new Set<string>();

// ---------------------------------------------------------------- sidebar view state (persisted)

export type SidebarView = 'files' | 'recent' | 'search' | 'source';

export interface SidebarPrefs {
  view: SidebarView;
  filter: FileFilter;
  order: OrderBy;
  expanded: string[];
}

export const DEFAULT_PREFS: SidebarPrefs = { view: 'files', filter: 'all', order: 'name', expanded: [] };

export function parsePrefs(raw: string | null): SidebarPrefs {
  try {
    const o = JSON.parse(raw ?? 'null') as Partial<SidebarPrefs> | null;
    if (!o || typeof o !== 'object') return DEFAULT_PREFS;
    return {
      view: (['files', 'recent', 'search', 'source'] as const).includes(o.view as SidebarView) ? (o.view as SidebarView) : 'files',
      filter: FILE_FILTERS.some(f => f.id === o.filter) ? (o.filter as FileFilter) : 'all',
      order: ORDERS.some(x => x.id === o.order) ? (o.order as OrderBy) : 'name',
      expanded: Array.isArray(o.expanded) ? o.expanded.filter((x): x is string => typeof x === 'string').slice(0, 2000) : [],
    };
  } catch {
    return DEFAULT_PREFS;
  }
}

export const prefsKey = (root: string) => `cloud-notes:vault:sidebar:${root}`;
