// CANVAS MODE (#1717): the .canvas board side of the main pane. Its own pane (the dotted board
// with groups, cards, images, embedded notes and edges) and its own toolbar: the control rail on
// the right edge (settings; zoom in, reset, fit, zoom out; undo, redo). This module never renders
// a note or a data file: it shares only the TabStore, the LinkResolver and the VaultSource
// (through the vault API) with File mode.
import { useCallback, useEffect, useRef, useState } from 'react';

import { CanvasView } from '../canvas-view';
import { Icon } from '../icons';
import { Menu, type MenuItem } from '../sidebar/Menu';
import type { Tab, VaultApi } from '../use-vault';
import { canRedo, canUndo, type CanvasHistory, EMPTY_HISTORY, recordChange, redo, undo } from './canvas-history';

export const CanvasMode = ({ tab, api }: { tab: Tab; api: VaultApi }) => {
  const [settings, setSettings] = useState(false);
  // undo / redo of this board; a new tab starts a new history
  const history = useRef<{ path: string; h: CanvasHistory }>({ path: tab.path, h: EMPTY_HISTORY });
  const [, bump] = useState(0);
  useEffect(() => {
    if (history.current.path !== tab.path) history.current = { path: tab.path, h: EMPTY_HISTORY };
  }, [tab.path]);
  const resolveFile = useCallback((target: string) => api.resolver.resolve(target, tab.path), [api.resolver, tab.path]);
  const h = history.current.path === tab.path ? history.current.h : EMPTY_HISTORY;
  const text = tab.text ?? '';

  const change = useCallback((next: string) => {
    history.current = { path: tab.path, h: recordChange(history.current.path === tab.path ? history.current.h : EMPTY_HISTORY, text, next) };
    api.setText(tab.path, next);
    bump(n => n + 1);
  }, [api, tab.path, text]);

  const step = (fn: typeof undo) => {
    const r = fn(h, text);
    if (!r) return;
    history.current = { path: tab.path, h: r.history };
    api.setText(tab.path, r.text);
    bump(n => n + 1);
  };

  if (tab.error && tab.text === null) return <div className="cnv-pane" data-testid="tab-error" data-mode="canvas">{tab.error}</div>;
  if (tab.text === null) return <div className="cnv-pane cnv-dim" data-testid="tab-loading" data-mode="canvas">Opening {tab.path}</div>;

  const items: MenuItem[] = [
    { id: 'source', label: 'Show the canvas as JSON (File mode)', icon: 'file' },
    { id: 'save', label: 'Save now', icon: 'check', disabled: !tab.dirty },
    { id: 'reindex', label: 'Rebuild the vault index', icon: 'reset' },
  ];

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0, position: 'relative' }} data-testid="canvas-mode" data-mode="canvas">
      {tab.error && <div className="cnv-banner" data-testid="tab-error">{tab.error}</div>}
      <CanvasView
        text={text}
        onChange={change}
        onOpenFile={(target, subpath) => void api.followLink(target, tab.path, { heading: subpath?.replace(/^#/, '') })}
        onOpenLink={(target, opts) => void api.followLink(target, tab.path, opts)}
        resolveFile={resolveFile}
        readDataUrl={api.readDataUrl}
        readText={api.readNote}
        rail={ctl => (
          <>
            <button type="button" className="cnv-btn" title="Canvas settings" aria-label="Canvas settings" data-rail="settings" onClick={() => setSettings(true)}><Icon name="gear" size={20} /></button>
            <div className="cnv-rail-pill">
              <button type="button" className="cnv-btn" title="Zoom in" aria-label="Zoom in" data-rail="zoom-in" onClick={ctl.zoomIn}><Icon name="plus" size={20} /></button>
              <button type="button" className="cnv-btn" title={`Reset zoom (${Math.round(ctl.k * 100)}%)`} aria-label="Reset zoom" data-rail="reset" onClick={ctl.reset}><Icon name="reset" size={20} /></button>
              <button type="button" className="cnv-btn" title="Fit to screen" aria-label="Fit to screen" data-rail="fit" onClick={ctl.fit}><Icon name="fit" size={20} /></button>
              <button type="button" className="cnv-btn" title="Zoom out" aria-label="Zoom out" data-rail="zoom-out" onClick={ctl.zoomOut}><Icon name="minus" size={20} /></button>
            </div>
            <div className="cnv-rail-pill">
              <button type="button" className="cnv-btn" title="Undo" aria-label="Undo" data-rail="undo" disabled={!canUndo(h)} onClick={() => step(undo)}><Icon name="undo" size={20} /></button>
              <button type="button" className="cnv-btn" title="Redo" aria-label="Redo" data-rail="redo" disabled={!canRedo(h)} onClick={() => step(redo)}><Icon name="redo" size={20} /></button>
            </div>
          </>
        )}
      />
      {settings && (
        <Menu testId="canvas-settings" items={items} style={{ top: 56, right: 56 }} onClose={() => setSettings(false)}
          onPick={id => (id === 'source' ? api.setMode(tab.path, 'file') : id === 'save' ? void api.save(tab.path) : api.rebuildIndex())} />
      )}
    </div>
  );
};
