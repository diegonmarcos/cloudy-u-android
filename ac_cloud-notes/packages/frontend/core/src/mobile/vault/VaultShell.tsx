// The Obsidian-style shell inside the content area: ribbon, left sidebar (files, search),
// tabs + editor/preview pane, right sidebar (outline, backlinks, outgoing links, tags),
// status bar, command palette and quick switcher, graph and Configs > Vault. The layout,
// the commands, the hotkeys and the file-type handlers all come from build.json::vault.
import { type ReactElement, useCallback, useEffect, useMemo, useRef, useState } from 'react';

import type { VaultDecl } from './decl-types';
import { handlerFor } from './file-types';
import { Icon } from './icons';
import { wordCount } from './markdown';
import { BacklinksPane, ConfigsPane, FilesPane, OutgoingPane, OutlinePane, SearchPane, TagsPane } from './panes';
import { Palette } from './palette';
import type { VaultApi } from './use-vault';
import { VaultGraph } from './vault-graph';
import { CanvasPane, HtmlView, ImageView, JsonView, MarkdownView, MermaidView, SourceEditor } from './viewers';

const PANE_LABEL: Record<string, string> = {
  files: 'Files', search: 'Search', outline: 'Outline', backlinks: 'Backlinks', outgoing: 'Links', tags: 'Tags',
};
// Obsidian's sidebar tabs are icons (Lucide, see icons.tsx).
const PANE_ICON: Record<string, string> = {
  files: 'folder', search: 'search', outline: 'outline', backlinks: 'backlinks', outgoing: 'outgoing', tags: 'tags',
};
const VIEW_LABEL: Record<string, string> = {
  preview: 'Reading', source: 'Source', canvas: 'Canvas', tree: 'Tree', render: 'Render', image: 'Image',
};

type Special = 'graph' | 'configs' | null;

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
  const [rightOpen, setRightOpen] = useState(true);
  const [leftPane, setLeftPane] = useState(decl.layout.left_panes[0]!);
  const [rightPane, setRightPane] = useState(decl.layout.right_panes[0]!);
  const [special, setSpecial] = useState<Special>(null);
  const [palette, setPalette] = useState<'commands' | 'files' | null>(null);
  const [searchSeed, setSearchSeed] = useState<string | undefined>(undefined);

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

  const tab = api.tabs.find(t => t.path === api.active) ?? null;
  const handler = tab ? handlerFor(decl, tab.path) : null;

  const showLeft = useCallback((pane: string) => {
    setLeftPane(pane);
    setLeftOpen(open => (open && pane === leftPane && narrow ? false : true));
    if (narrow) setRightOpen(false);
  }, [leftPane, narrow]);

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
      default: return undefined;
    }
  }, [api, tab, toggleMode, showLeft]);

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
    if (kind === 'pane' && a === 'left') { setSpecial(null); showLeft(b!); }
    else if (kind === 'command') run(a!);
  };

  const openPath = (p: string) => { setSpecial(null); void api.openFile(p); if (narrow) setLeftOpen(false); };
  const apiForPanes = useMemo(() => ({ ...api, openFile: async (p: string) => { setSpecial(null); await api.openFile(p); if (narrow) { setLeftOpen(false); setRightOpen(false); } } }), [api, narrow]);

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

  const content = () => {
    if (special === 'graph') {
      return api.index
        ? <VaultGraph index={api.index} active={api.active} onOpen={openPath} />
        : <div className="cnv-pane cnv-dim">{api.indexing ? `Indexing ${api.indexing.parsed} / ${api.indexing.total}` : 'No index yet.'}</div>;
    }
    if (special === 'configs') return <ConfigsPane config={api.config} decl={decl} vaultName={api.vault?.name ?? ''} />;
    if (!tab || !handler) {
      return (
        <div className="cnv-pane cnv-dim" data-testid="vault-empty">
          <p>No file is open. Use the quick switcher or the Files pane.</p>
          {api.indexing && <p>Indexing {api.indexing.parsed} / {api.indexing.total}</p>}
        </div>
      );
    }
    if (tab.error && tab.text === null) return <div className="cnv-pane" data-testid="tab-error">{tab.error}</div>;
    const source = tab.view === 'source';
    switch (handler.handler) {
      case 'markdown': return source ? <SourceEditor tab={tab} api={api} /> : <MarkdownView tab={tab} api={api} decl={decl} />;
      case 'canvas': return source ? <SourceEditor tab={tab} api={api} /> : <CanvasPane tab={tab} api={api} />;
      case 'json': return source ? <SourceEditor tab={tab} api={api} /> : <JsonView tab={tab} />;
      case 'html': return source ? <SourceEditor tab={tab} api={api} /> : <HtmlView tab={tab} api={api} decl={decl} />;
      case 'mermaid': return source ? <SourceEditor tab={tab} api={api} /> : <MermaidView tab={tab} />;
      case 'image': return <ImageView tab={tab} api={api} />;
      case 'text': return <SourceEditor tab={tab} api={api} />;
      default: return <div className="cnv-pane" data-testid="unsupported">{tab.error ?? 'This file type is not supported yet.'}</div>;
    }
  };

  const leftBody = leftPane === 'search'
    ? <SearchPane api={apiForPanes} initial={searchSeed} />
    : <FilesPane api={apiForPanes} decl={decl} />;
  const rightBody = rightPane === 'outline' ? <OutlinePane api={api} />
    : rightPane === 'backlinks' ? <BacklinksPane api={apiForPanes} />
    : rightPane === 'outgoing' ? <OutgoingPane api={api} />
    : <TagsPane api={api} onTag={t => { setSearchSeed('#' + t); showLeft('search'); }} />;

  // Obsidian mobile has no ribbon strip: on a narrow screen the ribbon's actions are a labelled row at the top of the left
  // drawer (data-testid vault-drawer-actions); on a wide screen they stay the vertical ribbon at the far left (#1369).
  const ribbonButtons = (labelled: boolean) => decl.layout.ribbon.map(r => (
    <button key={r.id} type="button" className={labelled ? 'cnv-row' : 'cnv-btn'} title={r.label} aria-label={r.label} data-ribbon={r.id}
      onClick={() => { if (labelled) setLeftOpen(false); ribbonAction(r.action); }}>
      <Icon name={r.icon} />{labelled && <span>{r.label}</span>}
    </button>
  ));

  const sidebar = (side: 'left' | 'right', panes: string[], current: string, setCurrent: (p: string) => void, body: ReactElement) => (
    <div className={`cnv-side${side === 'right' ? ' cnv-right' : ''}${narrow ? ' cnv-drawer' : ''}`} data-testid={`sidebar-${side}`}>
      {side === 'left' && narrow && (
        <nav className="cnv-drawer-actions" aria-label="Actions" data-testid="vault-drawer-actions">{ribbonButtons(true)}</nav>
      )}
      <div className="cnv-tabbar cnv-side-tabs" role="tablist">
        {panes.map(p => (
          <button key={p} type="button" role="tab" className="cnv-tab" title={PANE_LABEL[p] ?? p} aria-label={PANE_LABEL[p] ?? p} aria-selected={p === current} onClick={() => setCurrent(p)}>
            <Icon name={PANE_ICON[p] ?? 'file'} size={16} />
          </button>
        ))}
      </div>
      {body}
    </div>
  );

  return (
    <div className="cnv-root" ref={rootRef} data-testid="vault-shell">
      <div className="cnv-body" style={{ flexDirection: narrow ? 'column' : 'row' }}>
        {!narrow && <nav className="cnv-ribbon" aria-label="Ribbon" data-testid="ribbon">{ribbonButtons(false)}</nav>}
        <div style={{ display: 'flex', flex: 1, minHeight: 0, minWidth: 0, position: 'relative' }}>
          {narrow && (leftOpen || rightOpen) && <div className="cnv-scrim" onClick={() => { setLeftOpen(false); setRightOpen(false); }} />}
          {leftOpen && sidebar('left', decl.layout.left_panes, leftPane, setLeftPane, leftBody)}
          <div className="cnv-main">
            <div className="cnv-tabbar" role="tablist" data-testid="tabbar">
              <button type="button" className="cnv-btn" title="Toggle left sidebar" aria-label="Toggle left sidebar" onClick={() => setLeftOpen(v => !v)}><Icon name="panelLeft" /></button>
              {api.tabs.map(t => (
                <span key={t.path} style={{ display: 'inline-flex', alignItems: 'center' }}>
                  <button type="button" role="tab" className="cnv-tab" aria-selected={special === null && t.path === api.active} onClick={() => { setSpecial(null); api.setActive(t.path); }}>
                    {t.path.slice(t.path.lastIndexOf('/') + 1)}{t.dirty ? ' *' : ''}
                  </button>
                  <button type="button" className="cnv-btn" style={{ minWidth: 24, minHeight: 24, padding: 2 }} aria-label={`Close ${t.path}`} onClick={() => api.closeTab(t.path)}><Icon name="close" size={14} /></button>
                </span>
              ))}
              {special && <button type="button" role="tab" className="cnv-tab" aria-selected>{special === 'graph' ? 'Graph view' : 'Vault configs'}</button>}
              <span style={{ flex: 1 }} />
              {tab && handler && handler.views.length > 1 && special === null && (
                <button type="button" className="cnv-btn" onClick={toggleMode} title="Toggle edit and preview" data-testid="mode-toggle">{VIEW_LABEL[tab.view] ?? tab.view}</button>
              )}
              <button type="button" className="cnv-btn" title="Toggle right sidebar" aria-label="Toggle right sidebar" onClick={() => setRightOpen(v => !v)}><Icon name="panelRight" /></button>
            </div>
            {content()}
          </div>
          {rightOpen && sidebar('right', decl.layout.right_panes, rightPane, setRightPane, rightBody)}
        </div>
      </div>
      <div className="cnv-status" data-testid="status-bar">
        {decl.layout.status.map(id => <span key={id}>{statusItem(id)}</span>)}
        {api.indexing && <span>indexing {api.indexing.parsed}/{api.indexing.total}</span>}
        {api.error && <span style={{ color: 'var(--text-error)' }} onClick={() => api.setError(null)}>{api.error}</span>}
      </div>
      {palette && (
        <Palette mode={palette} commands={decl.commands} paths={api.allPaths} onRun={run} onOpen={openPath} onClose={() => setPalette(null)} />
      )}
    </div>
  );
};
