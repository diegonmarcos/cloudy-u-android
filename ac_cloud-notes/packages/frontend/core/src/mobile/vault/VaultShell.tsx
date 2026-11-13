// The Obsidian-style shell inside the content area (#1717 redesign). LAYOUT ONLY: it places the
// panes and routes commands; every piece of state is one of the four models behind the vault API
// (VaultSource, FileIndex, TabStore, LinkResolver).
//   left    the sidebar (sidebar/Sidebar.tsx): files tree, action row, view picker, Source, vault footer
//   top     round sidebar toggle | breadcrumb | pill: File / Canvas mode switch, edit, overflow menu
//   main    the active tab in its mode: FILE (file-mode/FileView.tsx) or CANVAS (canvas-mode/CanvasMode.tsx)
//   right   outline, backlinks, outgoing links, tags
//   bottom  the floating pill: back, forward, search, new, tab count (tab switcher), menu
// plus the tab strip and the ribbon on wide screens, the status bar, the command palette and quick
// switcher, the graph and Configs > Vault. The commands, hotkeys, panes and file-type handlers all
// come from build.json::vault.
import { type ReactElement, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import { CanvasMode } from './canvas-mode/CanvasMode';
import { collapseAll } from './core/file-index';
import { modesFor, type ViewMode } from './core/tab-store';
import type { VaultDecl } from './decl-types';
import { FILE_VIEW_LABEL, FileView } from './file-mode/FileView';
import { baseName, handlerFor, stem } from './file-types';
import { Icon } from './icons';
import { wordCount } from './markdown';
import { BacklinksPane, ConfigsPane, OutgoingPane, OutlinePane, TagsPane } from './panes';
import { Palette } from './palette';
import { Menu, type MenuItem } from './sidebar/Menu';
import { Sidebar } from './sidebar/Sidebar';
import { requestImport } from './sidebar/SourcePanel';
import type { VaultApi } from './use-vault';
import { VaultGraph } from './vault-graph';

const PANE_LABEL: Record<string, string> = { outline: 'Outline', backlinks: 'Backlinks', outgoing: 'Links', tags: 'Tags' };
// Obsidian's sidebar tabs are icons (Lucide, see icons.tsx).
const PANE_ICON: Record<string, string> = { outline: 'outline', backlinks: 'backlinks', outgoing: 'outgoing', tags: 'tags' };
const VIEW_LABEL: Record<string, string> = { ...FILE_VIEW_LABEL, canvas: 'Canvas' };

type Special = 'graph' | 'configs' | null;

/** The native shell's bridge (NotesShell.Host); absent in a browser. */
interface NotesHost {
  setVaultChrome?: (on: boolean) => void;
  openSection?: (id: string) => void;
}
const host = (): NotesHost | undefined => (window as unknown as { CloudNotesHost?: NotesHost }).CloudNotesHost;

const THEME_KEY = 'cloud-notes:vault:follow-theme';
const APPBARS_KEY = 'cloud-notes:vault:app-bars';
const flag = (key: string): boolean => {
  try { return localStorage.getItem(key) === '1'; } catch { return false; }
};
const setFlag = (key: string, on: boolean) => {
  try { localStorage.setItem(key, on ? '1' : '0'); } catch { /* ignored */ }
};

function hotkeyMatches(hotkey: string, e: KeyboardEvent): boolean {
  const parts = hotkey.toLowerCase().split('+');
  const key = parts[parts.length - 1]!;
  return (
    (e.ctrlKey || e.metaKey) === parts.includes('ctrl') &&
    e.shiftKey === parts.includes('shift') &&
    e.key.toLowerCase() === key
  );
}

export const VaultShell = ({ api, decl }: { api: VaultApi; decl: VaultDecl }) => {
  const rootRef = useRef<HTMLDivElement>(null);
  const [narrow, setNarrow] = useState(false);
  const [leftOpen, setLeftOpen] = useState(true);
  const [rightOpen, setRightOpen] = useState(false);
  const [rightPane, setRightPane] = useState(decl.layout.right_panes[0]!);
  const [special, setSpecial] = useState<Special>(null);
  const [palette, setPalette] = useState<'commands' | 'files' | null>(null);
  const [searchSeed, setSearchSeed] = useState<string | undefined>(undefined);
  const [menu, setMenu] = useState<'more' | 'main' | 'new' | null>(null);
  const [switcher, setSwitcher] = useState(false);
  const [followTheme, setFollowTheme] = useState(() => flag(THEME_KEY));
  const [appBars, setAppBars] = useState(() => flag(APPBARS_KEY));

  useEffect(() => {
    const el = rootRef.current;
    if (!el) return;
    const measure = () => {
      const isNarrow = el.clientWidth < decl.layout.wide_breakpoint_px;
      setNarrow(isNarrow);
      if (isNarrow) { setLeftOpen(false); setRightOpen(false); }
    };
    measure();
    const ro = new ResizeObserver(() => setNarrow(el.clientWidth < decl.layout.wide_breakpoint_px));
    ro.observe(el);
    return () => ro.disconnect();
  }, [decl.layout.wide_breakpoint_px]);

  // The vault draws its own top and bottom bars, so the app's bars step aside while it is shown
  // (unless the user keeps them: menu > Show app navigation bars). They return when the vault closes.
  useEffect(() => {
    try { host()?.setVaultChrome?.(!appBars); } catch { /* no native shell */ }
    return () => { try { host()?.setVaultChrome?.(false); } catch { /* no native shell */ } };
  }, [appBars]);

  const tab = api.tabs.find(t => t.path === api.active) ?? null;
  const handler = tab ? handlerFor(decl, tab.path) : null;
  const mode: ViewMode | null = special === null ? api.mode : null;
  const modes = tab ? modesFor(decl, tab.path) : [];
  const { prefs, setPrefs } = api;

  const showLeft = useCallback((view: 'files' | 'recent' | 'search' | 'source') => {
    setPrefs({ view });
    setLeftOpen(true);
    if (narrow) setRightOpen(false);
  }, [setPrefs, narrow]);

  useEffect(() => {
    const onSearch = (e: Event) => { setSearchSeed(String((e as CustomEvent).detail ?? '')); showLeft('search'); };
    window.addEventListener('cloud-notes:vault-search', onSearch);
    return () => window.removeEventListener('cloud-notes:vault-search', onSearch);
  }, [showLeft]);

  const toggleMode = useCallback(() => {
    if (!tab || !handler || handler.views.length < 2) return;
    const next = handler.views[(handler.views.indexOf(tab.view) + 1) % handler.views.length]!;
    api.patchTab(tab.path, { view: next });
  }, [tab, handler, api]);

  const run = useCallback((id: string) => {
    switch (id) {
      case 'command-palette': return setPalette('commands');
      case 'quick-switcher': return setPalette('files');
      case 'open-vault': return api.closeVault();
      case 'new-note': return void api.newNote('');
      case 'new-canvas': return void api.newCanvas('');
      case 'new-folder': return void api.newFolder('');
      case 'save': return tab ? void api.save(tab.path) : undefined;
      case 'toggle-mode': return toggleMode();
      case 'close-tab': return tab ? api.closeTab(tab.path) : undefined;
      case 'toggle-left': return setLeftOpen(v => !v);
      case 'toggle-right': return setRightOpen(v => !v);
      case 'search-in-files': return showLeft('search');
      case 'open-graph': return setSpecial('graph');
      case 'vault-configs': return setSpecial('configs');
      case 'toggle-hidden': return api.toggleHidden();
      case 'toggle-cdn': return tab && handler?.handler === 'html' ? api.patchTab(tab.path, { cdn: !tab.cdn }) : undefined;
      case 'reindex': return api.rebuildIndex();
      case 'collapse-all': return setPrefs({ expanded: [...collapseAll()] });
      case 'expand-all': return setPrefs({ expanded: [...api.fileIndex.expandAll({ showHidden: api.showHidden, skipDirs: decl.limits.skip_dirs })] });
      case 'recent-files': return showLeft('recent');
      case 'choose-source': return showLeft('source');
      case 'tab-switcher': return setSwitcher(true);
      case 'go-back': setSpecial(null); return api.goBack();
      case 'go-forward': setSpecial(null); return api.goForward();
      case 'mode-file': return tab ? api.setMode(tab.path, 'file') : undefined;
      case 'mode-canvas': return tab ? api.setMode(tab.path, 'canvas') : undefined;
      case 'import-file': return requestImport();
      default: return undefined;
    }
  }, [api, tab, handler, toggleMode, showLeft, setPrefs, decl.limits.skip_dirs]);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const cmd = decl.commands.find(c => c.hotkey && hotkeyMatches(c.hotkey, e));
      if (cmd) { e.preventDefault(); run(cmd.id); }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [decl.commands, run]);

  const ribbonAction = (action: string) => {
    const [kind, a, b] = action.split(':');
    if (kind === 'pane' && a === 'left') { setSpecial(null); showLeft(b === 'search' ? 'search' : 'files'); }
    else if (kind === 'command') run(a!);
  };

  const openPath = useCallback((p: string) => {
    setSpecial(null);
    void api.openFile(p);
    if (narrow) { setLeftOpen(false); setRightOpen(false); }
  }, [api, narrow]);
  const apiForPanes = useMemo(() => ({ ...api, openFile: async (p: string) => openPath(p) }), [api, openPath]);

  const note = api.active && api.index ? api.index.notes[api.active] : undefined;
  const statusItem = (id: string) => {
    switch (id) {
      case 'backlinks': return `${api.active ? (api.index?.backlinks[api.active]?.length ?? 0) : 0} backlinks`;
      case 'words': return `${note?.words ?? (tab?.text ? wordCount(tab.text) : 0)} words`;
      case 'chars': return `${tab?.text?.length ?? 0} characters`;
      case 'path': return api.active ?? api.vault?.name ?? '';
      default: return '';
    }
  };
  const status = (inline: boolean) => (
    <div className={inline ? 'cnv-status cnv-status-inline' : 'cnv-status'} data-testid="status-bar">
      {decl.layout.status.map(id => <span key={id}>{statusItem(id)}</span>)}
      {mode && <span data-testid="status-mode">{mode === 'canvas' ? 'Canvas mode' : 'File mode'}</span>}
      {api.indexing && <span>indexing {api.indexing.parsed}/{api.indexing.total}</span>}
      {api.error && <span style={{ color: 'var(--text-error)' }} onClick={() => api.setError(null)}>{api.error}</span>}
    </div>
  );

  const content = () => {
    if (special === 'graph') {
      return api.index
        ? <VaultGraph index={api.index} active={api.active} onOpen={openPath} />
        : <div className="cnv-pane cnv-dim">{api.indexing ? `Indexing ${api.indexing.parsed} / ${api.indexing.total}` : 'No index yet.'}</div>;
    }
    if (special === 'configs') return <ConfigsPane config={api.config} decl={decl} vaultName={api.vault?.name ?? ''} />;
    if (!tab) {
      return (
        <div className="cnv-pane cnv-dim" data-testid="vault-empty">
          <p>No file is open. Use the quick switcher or the Files pane.</p>
          {api.indexing && <p>Indexing {api.indexing.parsed} / {api.indexing.total}</p>}
        </div>
      );
    }
    // the two view modes: each its own module, pane and toolbar
    return mode === 'canvas' ? <CanvasMode tab={tab} api={api} /> : <FileView tab={tab} api={api} decl={decl} />;
  };

  const rightBody = rightPane === 'outline' ? <OutlinePane api={api} />
    : rightPane === 'backlinks' ? <BacklinksPane api={apiForPanes} />
    : rightPane === 'outgoing' ? <OutgoingPane api={api} />
    : <TagsPane api={api} onTag={t => { setSearchSeed('#' + t); showLeft('search'); }} />;

  // Obsidian mobile has no ribbon strip: on a narrow screen the ribbon's actions live in the left drawer (action row, view pill,
  // gear menu: Quick switcher, Graph, Command palette, Vault configs, Open another vault); on a wide screen they stay the
  // vertical ribbon at the far left (#1369).
  const ribbonButtons = (labelled: boolean) => decl.layout.ribbon.map(r => (
    <button key={r.id} type="button" className="cnv-btn" title={r.label} aria-label={r.label} data-ribbon={r.id}
      onClick={() => { if (labelled && !r.action.startsWith('pane:')) setLeftOpen(false); ribbonAction(r.action); }}>
      <Icon name={r.icon} />
    </button>
  ));

  const leftSidebar = (
    <div className={`cnv-side${narrow ? ' cnv-drawer' : ''}`} data-testid="sidebar-left">
      <Sidebar
        api={api} decl={decl} onOpen={openPath} onCommand={run} searchSeed={searchSeed}
        followTheme={followTheme} onToggleTheme={() => setFollowTheme(v => { setFlag(THEME_KEY, !v); return !v; })}
      />
    </div>
  );

  const rightSidebar = (body: ReactElement) => (
    <div className={`cnv-side cnv-right${narrow ? ' cnv-drawer' : ''}`} data-testid="sidebar-right">
      <div className="cnv-tabbar cnv-side-tabs" role="tablist">
        {decl.layout.right_panes.map(p => (
          <button key={p} type="button" role="tab" className="cnv-tab" title={PANE_LABEL[p] ?? p} aria-label={PANE_LABEL[p] ?? p} aria-selected={p === rightPane} onClick={() => setRightPane(p)}>
            <Icon name={PANE_ICON[p] ?? 'file'} size={16} />
          </button>
        ))}
      </div>
      {body}
    </div>
  );

  // breadcrumb: folder / folder / bold name (the special views name themselves)
  const crumbs = special ? [special === 'graph' ? 'Graph view' : 'Vault configs'] : tab ? [...tab.path.split('/').slice(0, -1), stem(tab.path)] : [api.vault?.name ?? ''];

  const moreItems: MenuItem[] = [
    { id: 'save', label: 'Save', icon: 'check', disabled: !tab?.dirty, hint: 'Ctrl+S' },
    { id: 'toggle-mode', label: tab && handler && handler.views.length > 1 ? `Toggle edit and preview (now ${VIEW_LABEL[tab.view] ?? tab.view})` : 'Toggle edit and preview', icon: 'edit', disabled: !handler || handler.views.length < 2 },
    { id: 'mode-file', label: 'File mode', icon: 'file', checked: mode === 'file', disabled: !modes.includes('file'), title: 'View mode', divider: true },
    { id: 'mode-canvas', label: 'Canvas mode', icon: 'canvas', checked: mode === 'canvas', disabled: !modes.includes('canvas') },
    ...(handler?.handler === 'html' ? [{ id: 'toggle-cdn', label: decl.html.cdn.label, checked: tab?.cdn === true }] : []),
    { id: 'toggle-right', label: 'Outline, backlinks, links and tags', icon: 'panelRight', checked: rightOpen, divider: true },
    { id: 'search-in-files', label: 'Search in files', icon: 'search' },
    { id: 'close-tab', label: 'Close tab', icon: 'close', disabled: !tab },
  ];
  const mainItems: MenuItem[] = [
    ...decl.commands.map(c => ({ id: c.id, label: c.label, hint: c.hotkey })),
    { id: '@app-bars', label: 'Show app navigation bars', checked: appBars, divider: true, title: 'App' },
    { id: '@home', label: 'Leave the vault view (Home)', icon: 'back' },
  ];
  const newItems: MenuItem[] = [
    { id: 'new-note', label: 'New note', icon: 'newFile' },
    { id: 'new-canvas', label: 'New canvas', icon: 'canvas' },
    { id: 'new-folder', label: 'New folder', icon: 'newFolder' },
  ];
  const pickMain = (id: string) => {
    if (id === '@app-bars') return setAppBars(v => { setFlag(APPBARS_KEY, !v); return !v; });
    if (id === '@home') {
      try {
        const h = host();
        if (h?.openSection) return h.openSection('home');
      } catch { /* fall through to the web route */ }
      window.dispatchEvent(new CustomEvent('cloud-notes:nav', { detail: { section: 'home', page: null } }));
      return;
    }
    run(id);
  };

  const tabLabel = (path: string) => baseName(path);

  return (
    <div className="cnv-root" ref={rootRef} data-testid="vault-shell" data-mode={mode ?? 'none'} {...(followTheme ? {} : { 'data-theme': 'dark' })}>
      <div className="cnv-body" style={{ flexDirection: 'row' }}>
        {!narrow && <nav className="cnv-ribbon" aria-label="Ribbon" data-testid="ribbon">{ribbonButtons(false)}</nav>}
        <div style={{ display: 'flex', flex: 1, minHeight: 0, minWidth: 0, position: 'relative' }}>
          {narrow && (leftOpen || rightOpen) && <div className="cnv-scrim" onClick={() => { setLeftOpen(false); setRightOpen(false); }} />}
          {leftOpen && leftSidebar}
          <div className="cnv-main">
            <div className="cnv-top" data-testid="topbar">
              <button type="button" className="cnv-round" title="Toggle left sidebar" aria-label="Toggle left sidebar" aria-pressed={leftOpen} data-testid="sidebar-toggle" onClick={() => setLeftOpen(v => !v)}><Icon name="panelLeft" size={22} /></button>
              <div className="cnv-crumbs" data-testid="breadcrumb">
                {crumbs.map((c, i) => (
                  <span key={i} style={{ display: 'contents' }}>
                    {i > 0 && <span className="cnv-crumb-sep">/</span>}
                    <span className={i === crumbs.length - 1 ? 'cnv-crumb-name' : undefined}>{c}{i === crumbs.length - 1 && tab?.dirty && !special ? ' *' : ''}</span>
                  </span>
                ))}
              </div>
              <div className="cnv-pill" data-testid="top-pill">
                {/* the File / Canvas mode switch: the active mode is always visible here */}
                <span role="group" aria-label="View mode" data-testid="mode-switch" style={{ display: 'inline-flex', gap: 2 }}>
                  {decl.layout.modes.map(m => (
                    <button key={m.id} type="button" className="cnv-mode" data-mode-button={m.id} aria-pressed={mode === m.id} disabled={!tab || special !== null || !modes.includes(m.id)}
                      title={`${m.label} mode`} onClick={() => tab && api.setMode(tab.path, m.id)}>
                      <Icon name={m.icon} size={14} />{m.label}
                    </button>
                  ))}
                </span>
                {tab && handler && handler.views.length > 1 && special === null && mode === 'file' && handler.handler !== 'canvas' && (
                  <button type="button" className="cnv-btn" onClick={toggleMode} title={`Toggle edit and preview (now ${VIEW_LABEL[tab.view] ?? tab.view})`} aria-label="Toggle edit and preview" data-testid="mode-toggle" data-view={tab.view} aria-pressed={tab.view === 'source'}><Icon name="edit" size={20} /></button>
                )}
                {special !== null && (
                  <button type="button" className="cnv-btn" title="Close" aria-label="Close" onClick={() => setSpecial(null)}><Icon name="close" size={20} /></button>
                )}
                <button type="button" className="cnv-btn" title="More" aria-label="More" data-testid="more-button" onClick={() => setMenu('more')}><Icon name="more" size={20} /></button>
              </div>
            </div>
            {!narrow && api.tabs.length > 0 && (
              <div className="cnv-tabstrip" role="tablist" data-testid="tabbar">
                {api.tabs.map(t => (
                  <span key={t.path} style={{ display: 'inline-flex', alignItems: 'center' }}>
                    <button type="button" role="tab" className="cnv-tab" data-tab={t.path} aria-selected={special === null && t.path === api.active} onClick={() => { setSpecial(null); api.setActive(t.path); }}>
                      <Icon name={handlerFor(decl, t.path).handler === 'canvas' ? 'canvas' : 'file'} size={12} /><span>{tabLabel(t.path)}{t.dirty ? ' *' : ''}</span>
                    </button>
                    <button type="button" className="cnv-btn" style={{ minWidth: 24, minHeight: 24, padding: 2 }} aria-label={`Close ${t.path}`} onClick={() => api.closeTab(t.path)}><Icon name="close" size={14} /></button>
                  </span>
                ))}
              </div>
            )}
            {content()}
          </div>
          {rightOpen && rightSidebar(rightBody)}
        </div>
      </div>
      {!narrow && status(false)}

      <nav className="cnv-bottombar" aria-label="Navigation" data-testid="bottom-bar">
        <button type="button" className="cnv-btn" title="Back" aria-label="Back" data-bar="back" disabled={!api.canGoBack} onClick={() => run('go-back')}><Icon name="back" size={24} /></button>
        <button type="button" className="cnv-btn" title="Forward" aria-label="Forward" data-bar="forward" disabled={!api.canGoForward} onClick={() => run('go-forward')}><Icon name="chevron" size={24} /></button>
        <button type="button" className="cnv-btn" title="Search" aria-label="Search" data-bar="search" onClick={() => setPalette('files')}><Icon name="search" size={24} /></button>
        <button type="button" className="cnv-btn" title="New" aria-label="New" data-bar="new" onClick={() => setMenu('new')}><Icon name="plus" size={26} /></button>
        <button type="button" className="cnv-btn" title={`Tabs (${api.tabs.length})`} aria-label="Tabs" data-bar="tabs" onClick={() => setSwitcher(true)}><span className="cnv-tabcount" data-testid="tab-count">{api.tabs.length}</span></button>
        <button type="button" className="cnv-btn" title="Menu" aria-label="Menu" data-bar="menu" onClick={() => setMenu('main')}><Icon name="menu" size={24} /></button>
      </nav>
      {menu === 'more' && <Menu testId="more-menu" items={moreItems} style={{ top: 60, right: 12 }} onClose={() => setMenu(null)} onPick={run} header={narrow ? status(true) : undefined} />}
      {menu === 'new' && <Menu testId="new-menu" items={newItems} style={{ bottom: 74, left: '50%', transform: 'translateX(-50%)' }} onClose={() => setMenu(null)} onPick={run} />}
      {menu === 'main' && <Menu testId="main-menu" items={mainItems} style={{ bottom: 74, right: 12 }} onClose={() => setMenu(null)} onPick={pickMain} />}

      {switcher && (
        <div className="cnv-sheet" data-testid="tab-switcher" onClick={() => setSwitcher(false)}>
          <div className="cnv-sheet-box" onClick={e => e.stopPropagation()}>
            <div className="cnv-sheet-head">
              <span>{api.tabs.length} open {api.tabs.length === 1 ? 'tab' : 'tabs'}</span>
              <button type="button" className="cnv-btn" aria-label="Close tab switcher" onClick={() => setSwitcher(false)}><Icon name="close" /></button>
            </div>
            <div className="cnv-scroll">
              {api.tabs.length === 0 && <div className="cnv-dim">No tabs are open.</div>}
              {api.tabs.map((t, i) => (
                <div key={t.path} style={{ display: 'flex', alignItems: 'center' }}>
                  <button type="button" className="cnv-row" data-switch-tab={t.path} aria-current={special === null && t.path === api.active}
                    onClick={() => { setSpecial(null); api.setActive(t.path); setSwitcher(false); }}>
                    <Icon name={handlerFor(decl, t.path).handler === 'canvas' ? 'canvas' : 'file'} size={16} />
                    <span style={{ flex: 1, minWidth: 0, overflowWrap: 'anywhere' }}>{tabLabel(t.path)}{t.dirty ? ' *' : ''}<br /><span className="cnv-dim">{t.path}</span></span>
                    <span className="cnv-dim">{handlerFor(decl, t.path).handler === 'canvas' && t.view !== 'source' ? 'Canvas' : 'File'}</span>
                  </button>
                  <button type="button" className="cnv-btn" aria-label="Move tab up" disabled={i === 0} onClick={() => api.moveTab(i, i - 1)}><span style={{ display: 'inline-flex', transform: 'rotate(-90deg)' }}><Icon name="chevron" size={14} /></span></button>
                  <button type="button" className="cnv-btn" aria-label={`Close ${t.path}`} onClick={() => api.closeTab(t.path)}><Icon name="close" size={14} /></button>
                </div>
              ))}
            </div>
          </div>
        </div>
      )}
      {palette && (
        <Palette mode={palette} commands={decl.commands} paths={api.allPaths} onRun={run} onOpen={openPath} onClose={() => setPalette(null)} />
      )}
    </div>
  );
};
