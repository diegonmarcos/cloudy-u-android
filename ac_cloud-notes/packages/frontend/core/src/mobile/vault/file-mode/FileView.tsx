// FILE MODE (#1717): the text / markdown / data side of the main pane. A note gets its
// Properties card and the rendered text; data and text files get their viewer or the editor.
// Its own pane, its own toolbar (the view switch of the file: Reading / Source, Tree / Source ...).
// This module never draws a canvas board: it shares only the TabStore, the LinkResolver and the
// VaultSource (through the vault API) with Canvas mode.
import { useState } from 'react';

import type { VaultDecl } from '../decl-types';
import { handlerFor } from '../file-types';
import { Icon } from '../icons';
import type { Tab, VaultApi } from '../use-vault';
import { HtmlView, ImageView, JsonView, MarkdownView, MermaidView, SourceEditor } from '../viewers';
import { addProperty, PROPERTY_ICON, propertyRows } from './properties';

export const FILE_VIEW_LABEL: Record<string, string> = {
  preview: 'Reading', source: 'Source', tree: 'Tree', render: 'Render', image: 'Image',
};

/** The Properties card of a note: frontmatter rows with their type icon, "Empty" placeholders, Add property. */
export const PropertiesCard = ({ tab, api }: { tab: Tab; api: VaultApi }) => {
  const [adding, setAdding] = useState(false);
  const [key, setKey] = useState('');
  const [value, setValue] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const { rows } = propertyRows(tab.text ?? '');
  const add = () => {
    const r = addProperty(tab.text ?? '', key, value);
    if ('error' in r) return setErr(r.error);
    api.setText(tab.path, r.text);
    setAdding(false); setKey(''); setValue(''); setErr(null);
  };
  return (
    <>
      <div className="cnv-proptitle">Properties</div>
      <div className="cnv-propcard" data-testid="properties">
        {rows.map(r => (
          <div key={r.key} className="cnv-proprow" data-prop={r.key} data-type={r.type}>
            <Icon name={PROPERTY_ICON[r.type]} size={18} />
            <span className="cnv-propkey" title={r.key}>{r.key}</span>
            <span className="cnv-propval" data-empty={r.empty}>{r.empty ? 'Empty' : r.value}</span>
          </div>
        ))}
        {adding ? (
          <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', marginTop: 6 }}>
            <input className="cnv-input" style={{ flex: '1 1 90px' }} placeholder="name" value={key} onChange={e => setKey(e.target.value)} data-testid="prop-key" />
            <input className="cnv-input" style={{ flex: '2 1 120px' }} placeholder="value" value={value} onChange={e => setValue(e.target.value)} data-testid="prop-value" />
            <button type="button" className="cnv-chip cnv-primary" onClick={add}>Add</button>
            <button type="button" className="cnv-chip" onClick={() => { setAdding(false); setErr(null); }}>Cancel</button>
            {err && <div style={{ color: 'var(--text-error)', flexBasis: '100%' }}>{err}</div>}
          </div>
        ) : (
          <button type="button" className="cnv-propadd" onClick={() => setAdding(true)} data-testid="prop-add">
            <Icon name="plus" size={18} /> Add property
          </button>
        )}
      </div>
    </>
  );
};

/** The file pane: what the tab's handler and view say, nothing else. */
export const FileView = ({ tab, api, decl }: { tab: Tab; api: VaultApi; decl: VaultDecl }) => {
  const handler = handlerFor(decl, tab.path);
  if (tab.error && tab.text === null) return <div className="cnv-pane" data-testid="tab-error" data-mode="file">{tab.error}</div>;
  if (tab.text === null && handler.handler !== 'unsupported') return <div className="cnv-pane cnv-dim" data-testid="tab-loading" data-mode="file">Opening {tab.path}</div>;
  const source = tab.view === 'source';
  const hiddenNote = tab.path.startsWith(decl.config_dir + '/');
  const pane = () => {
    switch (handler.handler) {
      case 'markdown':
        return source
          ? <SourceEditor tab={tab} api={api} />
          : <MarkdownView tab={tab} api={api} decl={decl} header={hiddenNote ? null : <PropertiesCard tab={tab} api={api} />} />;
      // a canvas in File mode is its JSON text (Canvas mode draws the board)
      case 'canvas': return <SourceEditor tab={tab} api={api} />;
      case 'json': return source ? <SourceEditor tab={tab} api={api} /> : <JsonView tab={tab} />;
      case 'html': return source ? <SourceEditor tab={tab} api={api} /> : <HtmlView tab={tab} api={api} decl={decl} />;
      case 'mermaid': return source ? <SourceEditor tab={tab} api={api} /> : <MermaidView tab={tab} />;
      case 'image': return <ImageView tab={tab} api={api} />;
      case 'text': return <SourceEditor tab={tab} api={api} />;
      default: return <div className="cnv-pane" data-testid="unsupported">{tab.error ?? 'This file type is not supported yet.'}</div>;
    }
  };
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="file-view" data-mode="file" data-handler={handler.handler}>
      {tab.error && <div className="cnv-banner" data-testid="tab-error">{tab.error}</div>}
      {pane()}
    </div>
  );
};
