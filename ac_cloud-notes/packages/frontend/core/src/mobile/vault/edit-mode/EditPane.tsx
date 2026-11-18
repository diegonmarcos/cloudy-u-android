// EDIT mode (#1717): the WYSIWYG editor of a note (.md) and of a page (.html), like a mail composer and Obsidian's live preview.
//   Markdown  the rendered note is editable in place; every change is written back as Markdown (core/md-serialize.ts), the
//             frontmatter block stays byte for byte as it was, and links, embeds, callouts, math and mermaid fences keep
//             what the note wrote.
//   HTML      the page in a script-less frame (core/html-edit.ts) with its own CSS, edited in place and written back as a
//             whole document.
// Both share the formatting toolbar. Nothing here runs a page script.
import { type ReactNode, useCallback, useEffect, useMemo, useRef } from 'react';

import { editSandbox, markInjected, toFileText } from '../core/html-edit';
import { fromDom, toMarkdown } from '../core/md-serialize';
import type { VaultDecl } from '../decl-types';
import { Icon } from '../icons';
import { parseFrontmatter, renderMarkdown } from '../markdown';
import { buildSandboxFrame } from '../html-sandbox';
import type { Tab, VaultApi } from '../use-vault';

const WRITE_DELAY_MS = 250;

type Exec = (cmd: string, arg?: string) => void;

const Toolbar = ({ exec, extra }: { exec: Exec; extra?: ReactNode }) => {
  const b = (id: string, label: string, icon: string, cmd: string, arg?: string) => (
    <button key={id} type="button" className="cnv-btn" title={label} aria-label={label} data-edit={id} onMouseDown={e => e.preventDefault()} onClick={() => exec(cmd, arg)}>
      <Icon name={icon} size={18} />
    </button>
  );
  return (
    <div className="cnv-edit-bar" role="toolbar" aria-label="Formatting" data-testid="edit-toolbar">
      {b('bold', 'Bold', 'bold', 'bold')}
      {b('italic', 'Italic', 'italic', 'italic')}
      {b('strike', 'Strikethrough', 'strike', 'strikeThrough')}
      <select className="cnv-edit-select" aria-label="Block style" data-testid="edit-block" defaultValue="" onChange={e => { if (e.target.value) exec('formatBlock', e.target.value); e.target.value = ''; }}>
        <option value="">Style</option>
        <option value="p">Paragraph</option>
        <option value="h1">Heading 1</option>
        <option value="h2">Heading 2</option>
        <option value="h3">Heading 3</option>
        <option value="blockquote">Quote</option>
      </select>
      {b('ul', 'Bulleted list', 'list', 'insertUnorderedList')}
      {b('ol', 'Numbered list', 'listOrdered', 'insertOrderedList')}
      {b('hr', 'Horizontal rule', 'minus', 'insertHorizontalRule')}
      {b('undo', 'Undo', 'undo', 'undo')}
      {b('redo', 'Redo', 'redo', 'redo')}
      {extra}
    </div>
  );
};

/** Raw frontmatter of a note, byte for byte: what precedes the body. */
const frontmatterOf = (text: string): string => text.slice(0, text.length - parseFrontmatter(text).body.length);

const MarkdownEditor = ({ tab, api }: { tab: Tab; api: VaultApi }) => {
  const ref = useRef<HTMLDivElement>(null);
  const written = useRef<string | null>(null);
  const timer = useRef<number | null>(null);
  const text = tab.text ?? '';

  // (re)load the editor when the note changes underneath it (another tab, a reload); never while the user is typing
  useEffect(() => {
    const el = ref.current;
    if (!el || written.current === text) return;
    el.innerHTML = renderMarkdown(parseFrontmatter(text).body);
    // embeds, math and mermaid fences are shown as marks that cannot be typed into; they are written back as they were
    el.querySelectorAll('.embed, .math, pre.mermaid').forEach(n => n.setAttribute('contenteditable', 'false'));
    // task boxes are live in Edit: ticking one is an edit like any other (the input event reaches the editor's onInput)
    el.querySelectorAll<HTMLInputElement>('input[type=checkbox]').forEach(i => { i.disabled = false; });
    written.current = text;
  }, [text, tab.path]);

  const flush = useCallback(() => {
    const el = ref.current;
    if (!el) return;
    const tree = Array.from(el.childNodes).map(fromDom).filter((n): n is NonNullable<typeof n> => n !== null);
    const next = frontmatterOf(tab.text ?? '') + toMarkdown(tree);
    written.current = next;
    if (next !== tab.text) api.setText(tab.path, next);
  }, [api, tab.path, tab.text]);

  useEffect(() => () => { if (timer.current !== null) window.clearTimeout(timer.current); }, []);

  const onInput = () => {
    if (timer.current !== null) window.clearTimeout(timer.current);
    timer.current = window.setTimeout(flush, WRITE_DELAY_MS);
  };
  const exec: Exec = (cmd, arg) => {
    ref.current?.focus();
    document.execCommand(cmd, false, arg);
    onInput();
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="markdown-editor">
      <Toolbar exec={exec} />
      <div className="cnv-pane">
        <div ref={ref} className="cnv-note cnv-editable" contentEditable suppressContentEditableWarning spellCheck data-testid="wysiwyg" onInput={onInput} onBlur={flush} />
        <div style={{ height: 96 }} />
      </div>
    </div>
  );
};

const HtmlEditor = ({ tab, api, decl }: { tab: Tab; api: VaultApi; decl: VaultDecl }) => {
  const frameRef = useRef<HTMLIFrameElement>(null);
  const original = useRef(tab.text ?? '');
  const timer = useRef<number | null>(null);
  // the document is loaded once per tab: typing must not reload the frame under the cursor
  const srcdoc = useMemo(() => markInjected(buildSandboxFrame(original.current, decl.html, false).srcdoc), [tab.path, decl.html]);

  const flush = useCallback(() => {
    const doc = frameRef.current?.contentDocument;
    if (!doc?.documentElement) return;
    api.setText(tab.path, toFileText(original.current, doc.documentElement.outerHTML));
  }, [api, tab.path]);

  const attach = () => {
    const doc = frameRef.current?.contentDocument;
    if (!doc) return;
    doc.designMode = 'on';
    doc.addEventListener('input', () => {
      if (timer.current !== null) window.clearTimeout(timer.current);
      timer.current = window.setTimeout(flush, WRITE_DELAY_MS);
    });
    doc.addEventListener('focusout', flush);
  };
  useEffect(() => () => { if (timer.current !== null) window.clearTimeout(timer.current); }, []);

  const exec: Exec = (cmd, arg) => {
    const doc = frameRef.current?.contentDocument;
    frameRef.current?.contentWindow?.focus();
    doc?.execCommand(cmd, false, arg);
    flush();
  };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }} data-testid="html-editor">
      <Toolbar exec={exec} />
      <div className="cnv-banner" data-testid="html-edit-banner">
        <span>Editing the page as it looks. Scripts do not run while you edit; the page is saved as a whole document.</span>
      </div>
      {/* allow-same-origin WITHOUT allow-scripts: the app can edit the document, nothing in it can run */}
      <iframe key={tab.path} ref={frameRef} className="cnv-iframe" title={tab.path} sandbox={editSandbox(decl.html.edit_sandbox)} srcDoc={srcdoc} referrerPolicy="no-referrer" onLoad={attach} data-testid="html-edit-frame" />
    </div>
  );
};

export const EditPane = ({ tab, api, decl, kind }: { tab: Tab; api: VaultApi; decl: VaultDecl; kind: 'markdown' | 'html' }) => {
  return kind === 'markdown' ? <MarkdownEditor tab={tab} api={api} /> : <HtmlEditor tab={tab} api={api} decl={decl} />;
};
