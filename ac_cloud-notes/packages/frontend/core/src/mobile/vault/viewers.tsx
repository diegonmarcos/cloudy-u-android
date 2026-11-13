// The file-mode renderers: one per build.json::vault.file_types handler (markdown, json, html,
// mermaid, image, text). Each gets the tab and the vault API; text-based ones edit the tab's
// buffer, the tab is saved by autosave or Save. Links go through the LinkResolver (api.followLink,
// api.resolver); nothing here resolves a link itself. The canvas board is NOT here: it is
// Canvas mode (canvas-view/CanvasMode.tsx).
import { type MouseEvent as RMouseEvent, type ReactNode, useEffect, useMemo, useRef, useState } from 'react';

import type { VaultDecl } from './decl-types';
import { enhanceMarkdown, renderMermaidInto } from './enhance';
import { buildSandboxFrame } from './html-sandbox';
import { jsonRows, parseJson } from './json-tree';
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

export const JsonView = ({ tab }: { tab: Tab }) => {
  const parsed = useMemo(() => parseJson(tab.text ?? ''), [tab.text]);
  const [open, setOpen] = useState<Set<string>>(new Set());
  if (!parsed.ok) {
    return (
      <div className="cnv-pane">
        <div className="cnv-banner" data-testid="json-error">Not valid JSON: {parsed.error}. Fix it in the Source view.</div>
      </div>
    );
  }
  const rows = jsonRows(parsed.value, open);
  return (
    <div className="cnv-pane" data-testid="json-tree">
      {rows.map(r => (
        <div
          key={r.pointer || '$'}
          className="cnv-tree-row"
          style={{ paddingLeft: r.depth * 16, cursor: r.expandable ? 'pointer' : 'default' }}
          onClick={() => {
            if (!r.expandable) return;
            setOpen(prev => {
              const next = new Set(prev);
              if (next.has(r.pointer)) next.delete(r.pointer);
              else next.add(r.pointer);
              return next;
            });
          }}
        >
          <span style={{ width: 12 }}>{r.expandable ? (r.expanded ? 'v' : '>') : ''}</span>
          <span style={{ opacity: 0.75 }}>{r.key}</span>
          <span style={{ color: r.kind === 'string' ? 'var(--color-green)' : r.kind === 'number' ? 'var(--color-orange)' : r.kind === 'boolean' || r.kind === 'null' ? 'var(--color-purple)' : undefined }}>
            {r.preview}
          </span>
        </div>
      ))}
      <div style={{ height: 96 }} />
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
