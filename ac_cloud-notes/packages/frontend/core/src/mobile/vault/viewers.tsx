// The file-mode renderers: one per build.json::vault.file_types handler (markdown, json, html,
// mermaid, image, text). Each gets the tab and the vault API; text-based ones edit the tab's
// buffer, the tab is saved by autosave or Save. Links go through the LinkResolver (api.followLink,
// api.resolver); nothing here resolves a link itself. The canvas board is NOT here: it is
// Canvas mode (canvas-view/CanvasMode.tsx).
import { type MouseEvent as RMouseEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import type { VaultDecl } from './decl-types';
import { enhanceMarkdown, renderMermaidInto } from './enhance';
import { Icon } from './icons';
import { buildSandboxFrame } from './html-sandbox';
import { copyText, parseJson, searchRows, valueAtPointer } from './json-tree';
import { renderMarkdown } from './markdown';
import type { Tab, VaultApi } from './use-vault';

/** Scroll [root] to the heading a link asked for (h1..h6 carry data-anchor). */
const scrollToAnchor = (root: HTMLElement | null, heading?: string) => {
  if (!root || !heading) return;
  const want = heading.trim().toLowerCase();
  const hit = [...root.querySelectorAll<HTMLElement>('[data-anchor]')].find(h => h.dataset.anchor === want);
  hit?.scrollIntoView({ block: 'start' });
};

export const SourceEditor = ({ tab, api }: { tab: Tab; api: VaultApi }) => (
  <textarea
    className="cnv-editor"
    spellCheck={false}
    value={tab.text ?? ''}
    onChange={e => api.setText(tab.path, e.target.value)}
    data-testid="source-editor"
  />
);

export const MarkdownView = ({ tab, api, decl, header }: { tab: Tab; api: VaultApi; decl: VaultDecl; header?: ReactNode }) => {
  const ref = useRef<HTMLDivElement>(null);
  const text = tab.text ?? '';
  const html = useMemo(() => renderMarkdown(text), [text]);
  void decl;

  useEffect(() => {
    const root = ref.current;
    if (!root) return;
    let alive = true;
    void enhanceMarkdown(root, {
      path: tab.path,
      alive: () => alive,
      resolve: (t, from) => api.resolver.resolve(t, from),
      readText: api.readNote,
      readDataUrl: api.readDataUrl,
    }).then(() => {
      const a = alive ? api.takeAnchor(tab.path) : null;
      if (a) scrollToAnchor(root, a.heading);
    }).catch(() => undefined);
    const onAnchor = (e: Event) => {
      const d = (e as CustomEvent).detail as { path?: string; heading?: string } | undefined;
      if (d?.path === tab.path) { api.takeAnchor(tab.path); scrollToAnchor(root, d.heading); }
    };
    window.addEventListener('cloud-notes:vault-anchor', onAnchor);
    return () => {
      alive = false;
      window.removeEventListener('cloud-notes:vault-anchor', onAnchor);
    };
  }, [html, tab.path, api.resolver, api.readNote, api.readDataUrl, api.takeAnchor]);

  const onClick = (e: RMouseEvent<HTMLDivElement>) => {
    const t = e.target as HTMLElement;
    const link = t.closest<HTMLElement>('a.internal-link');
    if (link) {
      e.preventDefault();
      if (link.dataset.target !== undefined) {
        void api.followLink(link.dataset.target, tab.path, { href: link.dataset.href === '1', heading: link.dataset.heading });
      }
      return;
    }
    const ext = t.closest<HTMLAnchorElement>('a.external-link');
    if (ext) {
      e.preventDefault();
      void api.followLink(ext.getAttribute('href') ?? '', tab.path);
      return;
    }
    const tag = t.closest<HTMLElement>('a.tag');
    if (tag) {
      e.preventDefault();
      window.dispatchEvent(new CustomEvent('cloud-notes:vault-search', { detail: '#' + (tag.dataset.tag ?? '') }));
    }
  };

  return (
    <div className="cnv-pane" data-testid="markdown-preview">
      {header}
      <div ref={ref} className="cnv-note" onClick={onClick} dangerouslySetInnerHTML={{ __html: html }} />
      <div style={{ height: 96 }} />
    </div>
  );
};

export const MermaidView = ({ tab }: { tab: Tab }) => {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (ref.current) void renderMermaidInto(ref.current, tab.text ?? '');
  }, [tab.text]);
  return (
    <div className="cnv-pane">
      <div ref={ref} data-testid="mermaid-render" />
    </div>
  );
};

const copyToClipboard = async (text: string): Promise<boolean> => {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    // a WebView without the async clipboard: the old selection route
    const ta = document.createElement('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    const ok = document.execCommand('copy');
    ta.remove();
    return ok;
  }
};

/** JSON in View mode: the collapsible tree with syntax colour, search, and copy of the selected path or value (the fleet viewer's feature set). */
export const JsonView = ({ tab }: { tab: Tab }) => {
  const parsed = useMemo(() => parseJson(tab.text ?? ''), [tab.text]);
  const [open, setOpen] = useState<Set<string>>(new Set());
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState<string | null>(null);
  const [copied, setCopied] = useState<string | null>(null);
  if (!parsed.ok) {
    return (
      <div className="cnv-pane">
        <div className="cnv-banner" data-testid="json-error">Not valid JSON: {parsed.error}. Fix it in Raw mode.</div>
      </div>
    );
  }
  const rows = searchRows(parsed.value, open, query);
  const sel = rows.find(r => r.pointer === selected) ?? null;
  const copy = (what: 'path' | 'value') => {
    if (!sel) return;
    const text = what === 'path' ? sel.path : copyText(valueAtPointer(parsed.value, sel.pointer));
    void copyToClipboard(text).then(ok => setCopied(ok ? `Copied the ${what}` : 'Copy is not available'));
  };
  const colour = (k: string) => (k === 'string' ? 'var(--color-green)' : k === 'number' ? 'var(--color-orange)' : k === 'boolean' || k === 'null' ? 'var(--color-purple)' : undefined);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="json-tree">
      <div className="cnv-edit-bar">
        <input className="cnv-input" type="search" placeholder="Search keys and values" aria-label="Search" data-testid="json-search" value={query} onChange={e => setQuery(e.target.value)} />
      </div>
      <div className="cnv-pane" style={{ fontFamily: 'var(--font-monospace)' }}>
        {rows.length === 0 && <div className="cnv-dim" data-testid="json-none" style={{ padding: 12 }}>Nothing matches.</div>}
        {rows.map(r => (
          <div
            key={r.pointer || '$'}
            className="cnv-tree-row"
            data-json-path={r.path}
            aria-current={r.pointer === selected}
            style={{ paddingLeft: 8 + r.depth * 16, cursor: 'pointer' }}
            onClick={() => {
              setSelected(r.pointer);
              setCopied(null);
              if (!r.expandable || query.trim() !== '') return;
              setOpen(prev => {
                const next = new Set(prev);
                if (next.has(r.pointer)) next.delete(r.pointer);
                else next.add(r.pointer);
                return next;
              });
            }}
          >
            <span className="cnv-chev" data-open={r.expanded} style={{ opacity: r.expandable ? 1 : 0 }}><Icon name="chevron" size={14} /></span>
            <span style={{ color: 'var(--color-blue)' }}>{r.key}</span>
            <span className="cnv-dim">:</span>
            <span style={{ color: colour(r.kind), overflowWrap: 'anywhere' }}>{r.preview}</span>
          </div>
        ))}
        <div style={{ height: 96 }} />
      </div>
      <div className="cnv-edit-bar" data-testid="json-copybar" style={{ borderTop: '1px solid var(--background-modifier-border)', borderBottom: 0 }}>
        <span className="cnv-dim" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} data-testid="json-selected">{copied ?? sel?.path ?? 'Tap a line to select it'}</span>
        <button type="button" className="cnv-chip" data-testid="json-copy-path" disabled={!sel} onClick={() => copy('path')}>Copy path</button>
        <button type="button" className="cnv-chip" data-testid="json-copy-value" disabled={!sel} onClick={() => copy('value')}>Copy value</button>
      </div>
    </div>
  );
};

export const HtmlView = ({ tab, api, decl }: { tab: Tab; api: VaultApi; decl: VaultDecl }) => {
  const frame = useMemo(() => buildSandboxFrame(tab.text ?? '', decl.html, tab.cdn), [tab.text, tab.cdn, decl.html]);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
      <div className="cnv-banner" data-testid="html-banner">
        <span>
          {tab.cdn ? 'CDN scripts and fonts are allowed for this page.' : 'Offline: scripts run, the network is blocked.'}
          {frame.external.length > 0 && ` ${frame.external.length} remote address(es) in the page.`}
        </span>
        <label style={{ display: 'inline-flex', gap: 6, alignItems: 'center' }}>
          <input type="checkbox" checked={tab.cdn} onChange={e => api.patchTab(tab.path, { cdn: e.target.checked })} data-testid="cdn-toggle" />
          {decl.html.cdn.label}
        </label>
      </div>
      {/* opaque origin: no allow-same-origin, so the page cannot reach the app or its bridge */}
      <iframe key={String(tab.cdn)} className="cnv-iframe" title={tab.path} sandbox={frame.sandbox} srcDoc={frame.srcdoc} referrerPolicy="no-referrer" data-testid="html-frame" />
    </div>
  );
};

export const ImageView = ({ tab, api }: { tab: Tab; api: VaultApi }) => {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    let alive = true;
    void api.readDataUrl(tab.path).then(u => alive && setUrl(u));
    return () => {
      alive = false;
    };
  }, [tab.path, api.readDataUrl]);
  return <div className="cnv-pane">{url ? <img src={url} alt={tab.path} style={{ maxWidth: '100%' }} /> : <span className="cnv-dim">Loading image</span>}</div>;
};

/** XML in View mode: a collapsible element tree (name, attributes, text), built by the browser's own XML parser. */
export const XmlView = ({ tab }: { tab: Tab }) => {
  const parsed = useMemo(() => {
    const doc = new DOMParser().parseFromString(tab.text ?? '', 'application/xml');
    const err = doc.querySelector('parsererror');
    return err ? { ok: false as const, error: (err.textContent ?? 'Not valid XML').split('\n')[0]! } : { ok: true as const, root: doc.documentElement };
  }, [tab.text]);
  if (!parsed.ok) {
    return (
      <div className="cnv-pane">
        <div className="cnv-banner" data-testid="xml-error">Not valid XML: {parsed.error}. Fix it in Raw mode.</div>
      </div>
    );
  }
  return (
    <div className="cnv-pane" data-testid="xml-tree">
      <XmlNode el={parsed.root} depth={0} />
    </div>
  );
};

const XmlNode = ({ el, depth }: { el: Element; depth: number }) => {
  const [open, setOpen] = useState(depth < 2);
  const kids = [...el.childNodes].filter(n => n.nodeType === 1 || (n.nodeType === 3 && (n.textContent ?? '').trim() !== '') || n.nodeType === 4 || n.nodeType === 8);
  const onlyText = kids.length === 1 && kids[0]!.nodeType === 3;
  const attrs = [...el.attributes];
  const head = (
    <span>
      <span className="cnv-xml-tag">&lt;{el.nodeName}</span>
      {attrs.map(a => <span key={a.name}> <span className="cnv-xml-attr">{a.name}</span>=<span className="cnv-xml-val">&quot;{a.value}&quot;</span></span>)}
      <span className="cnv-xml-tag">{kids.length === 0 ? ' /&gt;' : '&gt;'}</span>
      {onlyText && <> <span>{kids[0]!.textContent!.trim()}</span> <span className="cnv-xml-tag">&lt;/{el.nodeName}&gt;</span></>}
    </span>
  );
  if (kids.length === 0 || onlyText) return <div className="cnv-tree-row" style={{ paddingLeft: depth * 16 }}>{head}</div>;
  return (
    <div>
      <div className="cnv-tree-row" style={{ paddingLeft: depth * 16, cursor: 'pointer' }} data-xml-node={el.nodeName} aria-expanded={open} onClick={() => setOpen(v => !v)}>
        <span className="cnv-chev" data-open={open}><Icon name="chevron" size={14} /></span>{head}
        {!open && <span className="cnv-dim"> {kids.length} items</span>}
      </div>
      {open && kids.map((n, i) => n.nodeType === 1
        ? <XmlNode key={i} el={n as Element} depth={depth + 1} />
        : <div key={i} className="cnv-tree-row cnv-dim" style={{ paddingLeft: (depth + 1) * 16 }}>{n.nodeType === 8 ? `<!--${n.textContent}-->` : (n.textContent ?? '').trim()}</div>)}
      {open && <div className="cnv-tree-row" style={{ paddingLeft: depth * 16 }}><span className="cnv-xml-tag">&lt;/{el.nodeName}&gt;</span></div>}
    </div>
  );
};
