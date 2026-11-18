// The vault's graph view: notes as nodes, resolved links (and optionally tags) as edges,
// drawn with the shared graph canvas and layout the workspace graph already uses.
import { buildGraph, filterGraph, forceLayout, type GraphNode, localGraph } from '@blocksuite/cloud-notes-graph';
import { type CSSProperties, useMemo, useState } from 'react';

import { GraphCanvas } from './graph-canvas';
import { stem } from './file-types';
import type { VaultIndex } from './vault-index';

const SIZE = 1000;
const chip = (on: boolean): CSSProperties => ({
  padding: '6px 12px',
  borderRadius: 16,
  border: '1px solid var(--background-modifier-border)',
  background: on ? 'var(--background-modifier-active-hover)' : 'transparent',
  color: 'inherit',
  fontSize: 13,
});

export const VaultGraph = ({ index, active, onOpen }: { index: VaultIndex; active: string | null; onOpen: (path: string) => void }) => {
  const [showTags, setShowTags] = useState(false);
  const [local, setLocal] = useState(false);
  const [showOrphans, setShowOrphans] = useState(true);
  const [query, setQuery] = useState('');

  const full = useMemo(() => {
    const docs = Object.values(index.notes).map(n => ({ id: n.path, title: stem(n.path) }));
    const links: { from: string; to: string }[] = [];
    for (const n of Object.values(index.notes)) for (const l of n.links) if (l.resolved && index.notes[l.resolved]) links.push({ from: n.path, to: l.resolved });
    const tags = Object.entries(index.tags).map(([name, ids]) => ({ id: name, name, docIds: ids }));
    return buildGraph({ docs, links, tags });
  }, [index]);

  const model = useMemo(() => {
    let g = full;
    if (local && active && g.nodes.some(n => n.id === active)) g = localGraph(g, active, 2);
    if (!showTags) g = filterGraph(g, n => n.kind !== 'tag');
    if (!showOrphans) g = filterGraph(g, n => !n.orphan);
    return g;
  }, [full, local, active, showTags, showOrphans]);

  const positions = useMemo(() => forceLayout(model, { width: SIZE, height: SIZE, seed: 1 }), [model]);
  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    return new Set(q ? model.nodes.filter(n => n.label.toLowerCase().includes(q)).map(n => n.id) : []);
  }, [model, query]);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="vault-graph">
      <div style={{ display: 'flex', gap: 8, padding: '6px 12px', flexWrap: 'wrap', alignItems: 'center' }}>
        <input className="cnv-input" style={{ flex: 1, minWidth: 120 }} value={query} onChange={e => setQuery(e.target.value)} placeholder="Highlight a note" />
        <button type="button" style={chip(local)} onClick={() => setLocal(v => !v)}>Local</button>
        <button type="button" style={chip(showTags)} onClick={() => setShowTags(v => !v)}>Tags</button>
        <button type="button" style={chip(showOrphans)} onClick={() => setShowOrphans(v => !v)}>Orphans</button>
        <span className="cnv-dim">{model.nodes.length} nodes, {model.edges.length} links</span>
      </div>
      <div style={{ flex: 1, minHeight: 0, position: 'relative' }}>
        <GraphCanvas model={model} positions={positions} centerId={local ? active : null} matches={matches} onOpen={(n: GraphNode) => n.kind === 'doc' && onOpen(n.id)} size={SIZE} />
      </div>
    </div>
  );
};
