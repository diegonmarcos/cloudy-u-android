// Sidebar panes: Search (left; the Files tree, Recent and Source are sidebar/Sidebar.tsx); Outline, Backlinks, Outgoing links, Tags (right);
// and Configs > Vault, the read-only view of <vault>/.obsidian.
import { useEffect, useMemo, useState } from 'react';

import type { VaultDecl } from './decl-types';
import { Icon } from './icons';
import { type PluginReport, type VaultConfigReport } from './obsidian-config';
import { searchContent, searchPaths, tagCounts } from './vault-index';
import type { VaultApi } from './use-vault';

const STATUS_LABEL = { builtin: 'built in', planned: 'planned', na: 'not applicable', unmapped: 'not mapped' } as const;

export const SearchPane = ({ api, initial }: { api: VaultApi; initial?: string }) => {
  const [q, setQ] = useState(initial ?? '');
  useEffect(() => setQ(initial ?? ''), [initial]);
  const tag = q.startsWith('#') ? q.slice(1) : null;
  const names = useMemo(() => (q && tag === null ? searchPaths(api.allPaths, q, 20) : []), [q, tag, api.allPaths]);
  const tagged = tag !== null && api.index ? (api.index.tags[tag] ?? []) : [];
  const hits = useMemo(() => (tag === null ? searchContent(Object.fromEntries(api.noteTexts.current), q, 60) : []), [q, tag, api.noteTexts, api.index]);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, flex: 1 }} data-testid="search-pane">
      <div style={{ padding: 8 }}>
        <input className="cnv-input" value={q} onChange={e => setQ(e.target.value)} placeholder="Search names and text, or #tag" data-testid="search-input" />
      </div>
      <div className="cnv-scroll">
        {names.map(p => (
          <button key={'n' + p} type="button" className="cnv-row" onClick={() => void api.openFile(p)}><Icon name="file" size={14} />{p}</button>
        ))}
        {tagged.map(p => (
          <button key={'t' + p} type="button" className="cnv-row" onClick={() => void api.openFile(p)}><Icon name="file" size={14} />{p}</button>
        ))}
        {hits.map(h => (
          <button key={h.path + h.line} type="button" className="cnv-row" style={{ flexDirection: 'column', alignItems: 'flex-start' }} onClick={() => void api.openFile(h.path)}>
            <span>{h.path}</span>
            <span className="cnv-dim">{h.text}</span>
          </button>
        ))}
        {q && names.length + hits.length + tagged.length === 0 && <div className="cnv-dim">Nothing found.</div>}
        <div style={{ height: 96 }} />
      </div>
    </div>
  );
};

export const OutlinePane = ({ api }: { api: VaultApi }) => {
  const note = api.active && api.index ? api.index.notes[api.active] : undefined;
  return (
    <div className="cnv-scroll" data-testid="outline-pane">
      {!note || note.headings.length === 0 ? <div className="cnv-dim">No headings.</div> : note.headings.map(h => (
        <div key={h.line} style={{ paddingLeft: (h.level - 1) * 12, padding: '4px 8px' }}>{h.text}</div>
      ))}
    </div>
  );
};

export const BacklinksPane = ({ api }: { api: VaultApi }) => {
  const list = api.active && api.index ? (api.index.backlinks[api.active] ?? []) : [];
  return (
    <div className="cnv-scroll" data-testid="backlinks-pane">
      {api.indexing && <div className="cnv-dim">Indexing {api.indexing.parsed} / {api.indexing.total}</div>}
      {list.length === 0 ? <div className="cnv-dim">No backlinks.</div> : list.map(p => (
        <button key={p} type="button" className="cnv-row" onClick={() => void api.openFile(p)}><Icon name="file" size={14} />{p}</button>
      ))}
    </div>
  );
};

export const OutgoingPane = ({ api }: { api: VaultApi }) => {
  const note = api.active && api.index ? api.index.notes[api.active] : undefined;
  return (
    <div className="cnv-scroll" data-testid="outgoing-pane">
      {!note || note.links.length === 0 ? <div className="cnv-dim">No outgoing links.</div> : note.links.map((l, i) => (
        <button key={i} type="button" className="cnv-row" style={{ opacity: l.resolved ? 1 : 0.55 }} data-link-kind={l.kind ?? 'wikilink'} onClick={() => api.active && void api.followLink(l.target, api.active, { heading: l.heading })}>
          <span style={{ flex: 1 }}>{l.embed ? '!' : ''}{l.target}{l.heading ? ` > ${l.heading}` : ''}{l.resolved ? '' : ' (unresolved)'}</span>
          <span className="cnv-dim">{l.kind ?? 'wikilink'}</span>
        </button>
      ))}
    </div>
  );
};

export const TagsPane = ({ api, onTag }: { api: VaultApi; onTag: (tag: string) => void }) => {
  const rows = api.index ? tagCounts(api.index) : [];
  return (
    <div className="cnv-scroll" data-testid="tags-pane">
      {rows.length === 0 ? <div className="cnv-dim">No tags.</div> : rows.map(r => (
        <button key={r.tag} type="button" className="cnv-row" style={{ paddingLeft: 8 + (r.tag.split('/').length - 1) * 12 }} onClick={() => onTag(r.tag)}>
          #{r.tag}<span className="cnv-dim"> {r.count}</span>
        </button>
      ))}
    </div>
  );
};

const PluginRows = ({ title, list }: { title: string; list: PluginReport[] }) => (
  <section>
    <h3>{title} ({list.length})</h3>
    {list.map(p => (
      <div key={p.id} style={{ padding: '6px 0', borderBottom: '1px solid var(--background-modifier-border)' }} data-testid={`plugin-${p.id}`}>
        <div style={{ display: 'flex', justifyContent: 'space-between', gap: 8 }}>
          <strong>{p.name ?? p.id}</strong>
          <span data-status={p.status}>{p.kind === 'core' && !p.enabled ? 'off, ' : ''}{STATUS_LABEL[p.status]}</span>
        </div>
        <div className="cnv-dim">{p.feature}{p.version ? ` (installed ${p.version})` : p.installed === false ? ' (not installed in this vault)' : ''}</div>
      </div>
    ))}
  </section>
);

/** Configs > Vault: what <vault>/.obsidian says. Read-only; applying it is the next milestone. */
export const ConfigsPane = ({ config, decl, vaultName }: { config: VaultConfigReport | null; decl: VaultDecl; vaultName: string }) => (
  <div className="cnv-pane" data-testid="configs-pane">
    <div className="cnv-note">
      <h2>Vault: {vaultName}</h2>
      <p className="cnv-dim">Read from <code>{decl.config_dir}</code> in this folder. Shown only: Cloud Notes does not apply these settings yet.</p>
      {config === null && <p>Reading the configuration.</p>}
      {config && !config.found && <p data-testid="configs-none">This folder has no <code>{decl.config_dir}</code> configuration.</p>}
      {config && config.found && (
        <>
          <section>
            <h3>App and appearance</h3>
            {config.summary.length === 0 ? <p className="cnv-dim">Nothing set.</p> : (
              <dl className="cnv-props">{config.summary.map(s => <div key={s.label}><dt>{s.label}</dt><dd>{s.value}</dd></div>)}</dl>
            )}
            <p className="cnv-dim">
              {config.files.map(f => `${f.file}: ${f.present ? (f.parseError ? 'unreadable' : 'read') : 'absent'}`).join(' | ')}
            </p>
          </section>
          <PluginRows title="Core plugins" list={config.corePlugins} />
          <PluginRows title="Community plugins" list={config.communityPlugins} />
          <section>
            <h3>Hotkeys ({config.hotkeys.length})</h3>
            {config.hotkeys.map(h => <div key={h.command}><code>{h.command}</code> <span className="cnv-dim">{h.keys.join(', ') || 'unbound'}</span></div>)}
          </section>
          <section>
            <h3>CSS snippets ({config.snippets.length})</h3>
            {config.snippets.map(s => <div key={s.name}>{s.name} <span className="cnv-dim">{s.enabled ? 'enabled' : 'off'}, {s.size} bytes</span></div>)}
          </section>
        </>
      )}
      <div style={{ height: 96 }} />
    </div>
  </div>
);
