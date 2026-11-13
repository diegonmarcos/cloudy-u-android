// The left sidebar: everything "sidebar" lives here and nowhere else (#1717).
//   A  Files    the tree of the mounted root; folders with chevrons, each file with its extension badge
//   B  Actions  new file, new folder, order by, collapse / expand all, recent files, filter
//               (and the two the old pane had: new canvas, show hidden files)
//   C  Source   the root picker (SourcePanel)
// plus the view picker pill, and the footer with the vault name, "N files, M folders" and the
// settings gear. It renders FileIndex and writes the sidebar prefs; it holds no file state.
import { type ReactNode, useMemo, useState } from 'react';

import { badgeKind, collapseAll, extBadge, FILE_FILTERS, type FileFilter, ORDERS, type OrderBy, type SidebarView } from '../core/file-index';
import type { VaultDecl } from '../decl-types';
import { Icon } from '../icons';
import { SearchPane } from '../panes';
import type { VaultApi } from '../use-vault';
import { Menu, type MenuItem } from './Menu';
import { SourcePanel } from './SourcePanel';

const VIEW_LABEL: Record<SidebarView, string> = { files: 'Files', recent: 'Recent files', search: 'Search', source: 'Source' };
const VIEW_ICON: Record<SidebarView, string> = { files: 'folderOpen', recent: 'recent', search: 'search', source: 'smartphone' };

const when = (mtime: number): string => {
  if (!mtime) return '';
  const d = new Date(mtime);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
};

export interface SidebarProps {
  api: VaultApi;
  decl: VaultDecl;
  /** open a file (the shell closes the drawer on a narrow screen) */
  onOpen: (path: string) => void;
  /** run a declared command (build.json::vault.commands) */
  onCommand: (id: string) => void;
  searchSeed?: string;
  /** narrow screens: the labelled ribbon actions, drawn at the top of the drawer */
  top?: ReactNode;
  /** the vault follows the app theme instead of staying dark */
  followTheme: boolean;
  onToggleTheme: () => void;
}

export const Sidebar = ({ api, decl, onOpen, onCommand, searchSeed, top, followTheme, onToggleTheme }: SidebarProps) => {
  const [menu, setMenu] = useState<'view' | 'order' | 'filter' | 'gear' | null>(null);
  const [dir, setDir] = useState('');
  const { prefs, setPrefs, fileIndex } = api;
  const view = prefs.view;
  const expanded = useMemo(() => new Set(prefs.expanded), [prefs.expanded]);
  const opts = useMemo(
    () => ({ filter: prefs.filter, order: prefs.order, expanded, showHidden: api.showHidden, skipDirs: decl.limits.skip_dirs }),
    [prefs.filter, prefs.order, expanded, api.showHidden, decl.limits.skip_dirs]
  );
  const rows = useMemo(() => (view === 'files' ? fileIndex.rows(opts) : []), [view, fileIndex, opts]);
  const recent = useMemo(
    () => (view === 'recent' ? fileIndex.recent(opts, decl.sources?.recent_files_max ?? 30) : []),
    [view, fileIndex, opts, decl.sources]
  );
  const counts = useMemo(() => fileIndex.counts(opts), [fileIndex, opts]);
  const anyOpen = prefs.expanded.length > 0;

  const toggle = (path: string) => {
    setDir(path);
    const next = new Set(expanded);
    if (next.has(path)) next.delete(path);
    else {
      next.add(path);
      void api.loadDir(path).catch(() => undefined);
    }
    setPrefs({ expanded: [...next] });
  };

  const collapseOrExpand = () => {
    if (anyOpen) setPrefs({ expanded: [...collapseAll()] });
    else setPrefs({ expanded: [...fileIndex.expandAll(opts)] });
  };

  const viewItems: MenuItem[] = decl.layout.left_panes
    .filter((p): p is SidebarView => p in VIEW_LABEL)
    .map(p => ({ id: p, label: VIEW_LABEL[p], icon: VIEW_ICON[p], checked: p === view }));
  const orderItems: MenuItem[] = ORDERS.map(o => ({ id: o.id, label: o.label, checked: o.id === prefs.order }));
  const filterItems: MenuItem[] = FILE_FILTERS.map(f => ({ id: f.id, label: f.label, checked: f.id === prefs.filter }));
  const gearItems: MenuItem[] = [
    { id: 'vault-configs', label: 'Vault configs (.obsidian)', icon: 'gear' },
    { id: 'command-palette', label: 'Command palette', icon: 'terminal' },
    { id: 'quick-switcher', label: 'Quick switcher', icon: 'jump' },
    { id: 'open-graph', label: 'Graph view', icon: 'graph' },
    { id: 'reindex', label: 'Rebuild the vault index', icon: 'reset' },
    { id: 'toggle-hidden', label: 'Show hidden files', icon: 'eye', checked: api.showHidden },
    { id: 'theme', label: 'Vault follows the app theme (off: always dark)', checked: followTheme, divider: true },
    { id: 'choose-source', label: 'Choose source (root to mount)', icon: 'smartphone', divider: true },
    { id: 'open-vault', label: 'Open another vault', icon: 'vault' },
  ];

  const fileRow = (path: string, name: string, depth: number, sub?: string) => (
    <button key={path} type="button" className="cnv-row" aria-current={api.active === path} data-file={path} aria-label={name} title={path}
      style={{ paddingLeft: 8 + depth * 14 }} onClick={() => onOpen(path)}>
      <span className="cnv-chev" />
      <span className="cnv-name">{name}{sub ? <span className="cnv-dim"> {sub}</span> : null}</span>
      {extBadge(path) && <span className="cnv-badge" data-kind={badgeKind(path)}>{extBadge(path)}</span>}
    </button>
  );

  const body = () => {
    if (view === 'search') return <SearchPane api={{ ...api, openFile: async (p: string) => onOpen(p) }} initial={searchSeed} />;
    if (view === 'source') return <SourcePanel api={api} decl={decl} onMounted={() => setPrefs({ view: 'files' })} />;
    if (view === 'recent') {
      return (
        <div className="cnv-scroll cnv-tree" data-testid="recent-pane">
          {recent.length === 0 && <div className="cnv-dim" style={{ padding: 8 }}>{api.walked ? 'No files match.' : 'Reading the vault.'}</div>}
          {recent.map(e => fileRow(e.path, e.name, 0, when(e.mtime)))}
          {!api.walked && recent.length > 0 && <div className="cnv-dim" style={{ padding: 8 }}>Still reading the vault; the list is not complete yet.</div>}
        </div>
      );
    }
    return (
      <div className="cnv-scroll cnv-tree" data-testid="files-pane">
        {rows.map(r => r.entry.isDir ? (
          <button key={r.entry.path} type="button" className="cnv-row" data-folder={r.entry.path} aria-expanded={r.expanded} aria-label={r.entry.name}
            style={{ paddingLeft: 8 + r.depth * 14 }} onClick={() => toggle(r.entry.path)}>
            <span className="cnv-chev" data-open={r.expanded}><Icon name="chevron" size={14} /></span>
            <span className="cnv-name">{r.entry.name}</span>
          </button>
        ) : fileRow(r.entry.path, r.entry.name, r.depth))}
        {rows.length === 0 && <div className="cnv-dim" style={{ padding: 8 }}>{prefs.filter === 'all' ? 'This folder is empty.' : 'No files match the filter.'}</div>}
        {api.truncated && <div className="cnv-dim">The vault is larger than the declared limits; the listing is partial.</div>}
      </div>
    );
  };

  return (
    <>
      {top}
      {body()}
      <nav aria-label="Actions" data-testid="vault-drawer-actions" style={{ display: 'contents' }}>
      <div className="cnv-actions" data-testid="sidebar-actions" style={{ position: 'relative' }}>
        <button type="button" className="cnv-btn" title="New file" aria-label="New file" data-action="new-file" onClick={() => void api.newNote(dir)}><Icon name="newFile" size={20} /></button>
        <button type="button" className="cnv-btn" title="New folder" aria-label="New folder" data-action="new-folder" onClick={() => void api.newFolder(dir)}><Icon name="newFolder" size={20} /></button>
        <button type="button" className="cnv-btn" title="Order by" aria-label="Order by" data-action="order" data-on={prefs.order !== 'name'} onClick={() => setMenu('order')}><Icon name="sort" size={20} /></button>
        <button type="button" className="cnv-btn" title={anyOpen ? 'Collapse all' : 'Expand all'} aria-label={anyOpen ? 'Collapse all' : 'Expand all'} data-action="collapse" onClick={collapseOrExpand}><Icon name={anyOpen ? 'collapseAll' : 'expandAll'} size={20} /></button>
        <button type="button" className="cnv-btn" title="Recent files" aria-label="Recent files" data-action="recent" data-on={view === 'recent'} aria-pressed={view === 'recent'} onClick={() => setPrefs({ view: view === 'recent' ? 'files' : 'recent' })}><Icon name="recent" size={20} /></button>
        <button type="button" className="cnv-btn" title="Filter" aria-label="Filter" data-action="filter" data-on={prefs.filter !== 'all'} onClick={() => setMenu('filter')}><Icon name="filter" size={20} /></button>
        <button type="button" className="cnv-btn" title="New canvas" aria-label="New canvas" data-action="new-canvas" onClick={() => void api.newCanvas(dir)}><Icon name="canvas" size={20} /></button>
        <button type="button" className="cnv-btn" title="Show hidden files" aria-label="Show hidden files" data-action="hidden" aria-pressed={api.showHidden} onClick={api.toggleHidden}><Icon name="eye" size={20} /></button>
        {menu === 'order' && <Menu testId="order-menu" items={orderItems} style={{ bottom: '100%', left: 8 }} onClose={() => setMenu(null)} onPick={id => setPrefs({ order: id as OrderBy })} />}
        {menu === 'filter' && <Menu testId="filter-menu" items={filterItems} style={{ bottom: '100%', right: 8 }} onClose={() => setMenu(null)} onPick={id => setPrefs({ filter: id as FileFilter })} />}
      </div>
      </nav>
      <div style={{ position: 'relative', display: 'flex', flexDirection: 'column', flex: 'none' }}>
        <button type="button" className="cnv-viewpill" data-testid="view-picker" onClick={() => setMenu('view')}>
          <Icon name={VIEW_ICON[view]} size={20} />
          <span>{VIEW_LABEL[view]}{prefs.filter !== 'all' && view !== 'source' ? <span className="cnv-dim"> {FILE_FILTERS.find(f => f.id === prefs.filter)?.label}</span> : null}</span>
          <Icon name="expandAll" size={16} />
        </button>
        {menu === 'view' && <Menu testId="view-menu" items={viewItems} style={{ bottom: '100%', left: 10, right: 10 }} onClose={() => setMenu(null)} onPick={id => setPrefs({ view: id as SidebarView })} />}
      </div>
      <div className="cnv-vaultfoot" style={{ position: 'relative' }} data-testid="vault-foot">
        <button type="button" className="cnv-vaultname" title="Choose source" aria-label="Choose source" onClick={() => setPrefs({ view: view === 'source' ? 'files' : 'source' })}>
          <strong>{api.vault?.name ?? 'No vault'} <Icon name="chevronDown" size={16} /></strong>
          <div className="cnv-dim" data-testid="vault-counts">{counts.files} files, {counts.folders} folders{api.walked ? '' : ' (reading)'}</div>
        </button>
        <button type="button" className="cnv-round" title="Settings" aria-label="Settings" data-testid="vault-gear" onClick={() => setMenu('gear')}><Icon name="gear" size={20} /></button>
        {menu === 'gear' && <Menu testId="gear-menu" items={gearItems} style={{ bottom: '100%', right: 8 }} onClose={() => setMenu(null)}
          onPick={id => (id === 'theme' ? onToggleTheme() : id === 'choose-source' ? setPrefs({ view: 'source' }) : onCommand(id))} />}
      </div>
    </>
  );
};
