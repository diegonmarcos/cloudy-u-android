// The Edit mode's way back to text (#1717): the WYSIWYG pane edits the HTML that renderMarkdown wrote, and this turns that
// HTML (as the browser leaves it after typing, bold, lists, pasted blocks) into Markdown again. Pure: it works on a plain
// tree (HNode); fromDom() is the only function that touches the browser. The tests prove the loop render -> serialize ->
// render gives the same HTML, so an edit never rewrites a note it did not change.

export type HEl = { t: 'el'; tag: string; attrs: Record<string, string>; kids: HNode[] };
export type HNode = { t: 'text'; v: string } | HEl;

/** A browser Node -> HNode (the only DOM-touching code here). */
export function fromDom(node: Node): HNode | null {
  if (node.nodeType === 3) return { t: 'text', v: node.textContent ?? '' };
  if (node.nodeType !== 1) return null;
  const el = node as Element;
  const attrs: Record<string, string> = {};
  for (const a of Array.from(el.attributes)) attrs[a.name] = a.value;
  if (el instanceof HTMLInputElement && el.type === 'checkbox') attrs.checked = el.checked ? 'true' : 'false';
  const kids: HNode[] = [];
  for (const c of Array.from(el.childNodes)) {
    const k = fromDom(c);
    if (k) kids.push(k);
  }
  return { t: 'el', tag: el.tagName.toLowerCase(), attrs, kids };
}

const isEl = (n: HNode): n is HEl => n.t === 'el';
const cls = (n: HNode, c: string): boolean => isEl(n) && (n.attrs.class ?? '').split(/\s+/).includes(c);
const text = (n: HNode): string => (n.t === 'text' ? n.v : n.tag === 'br' ? '\n' : n.kids.map(text).join(''));
const BLOCK = new Set(['p', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'ul', 'ol', 'li', 'blockquote', 'pre', 'table', 'hr']);
const isBlock = (n: HNode): boolean => isEl(n) && BLOCK.has(n.tag);

const dest = (u: string): string => (/\s/.test(u) ? `<${u}>` : u);

function inline(kids: HNode[]): string {
  return kids.map(inl).join('');
}

/** Markers hug the text: "**a **" would not render as bold. */
function wrap(mark: string, inner: string): string {
  const lead = /^\s*/.exec(inner)![0];
  const trail = /\s*$/.exec(inner)![0];
  const core = inner.trim();
  return core === '' ? inner : `${lead}${mark}${core}${mark}${trail}`;
}

function inl(n: HNode): string {
  if (n.t === 'text') return n.v.replace(/ /g, ' ');
  switch (n.tag) {
    case 'br': return '\n';
    case 'strong': case 'b': return wrap('**', inline(n.kids));
    case 'em': case 'i': return wrap('*', inline(n.kids));
    case 'del': case 's': case 'strike': return wrap('~~', inline(n.kids));
    case 'mark': return wrap('==', inline(n.kids));
    case 'code': return '`' + text(n) + '`';
    case 'img': return `![${n.attrs.alt ?? ''}](${dest(n.attrs['data-src'] ?? n.attrs.src ?? '')})`;
    case 'input': return '';
    case 'a': return anchor(n);
    case 'span':
      if (cls(n, 'embed')) return n.attrs['data-raw'] ?? `![[${n.attrs['data-target'] ?? text(n)}]]`;
      if (cls(n, 'math') && n.attrs['data-tex'] !== undefined) {
        return cls(n, 'math-display') ? `$$${n.attrs['data-tex']}$$` : `$${n.attrs['data-tex']}$`;
      }
      return inline(n.kids);
    default: return inline(n.kids);
  }
}

function anchor(n: HEl): string {
  const label = text(n);
  if (cls(n, 'tag')) return `#${n.attrs['data-tag'] ?? label.replace(/^#/, '')}`;
  if (cls(n, 'external-link')) return `[${label}](${dest(n.attrs.href ?? '')})`;
  if (cls(n, 'internal-link')) {
    const target = n.attrs['data-target'] ?? '';
    if (n.attrs['data-href'] === '1') return `[${label}](${dest(target)})`;
    const heading = n.attrs['data-heading'];
    const shown = n.attrs['data-alias'] ?? (heading ? `${target} > ${heading}` : target);
    // untouched: the link exactly as written; the label was typed over: the same target with the new label as its alias
    const raw = n.attrs['data-raw'];
    if (raw !== undefined && label === shown) return raw;
    return `[[${target}${heading ? `#${heading}` : ''}|${label}]]`;
  }
  return label;
}

const prefix = (p: string, s: string): string => s.split('\n').map(l => (l === '' ? p.trimEnd() : p + l)).join('\n');

/** Inline runs and blocks of one container, as Markdown blocks. */
function blocks(kids: HNode[]): string[] {
  const out: string[] = [];
  let run: HNode[] = [];
  const flush = () => {
    const t = inline(run).replace(/^\n+|\n+$/g, '');
    if (t.trim() !== '') out.push(t.split('\n').map(l => l.replace(/\s+$/, '')).join('\n'));
    run = [];
  };
  for (const k of kids) {
    if (isEl(k) && isBlock(k)) {
      flush();
      out.push(...block(k));
    } else {
      run.push(k);
    }
  }
  flush();
  return out;
}

function block(n: HEl): string[] {
  const h = /^h([1-6])$/.exec(n.tag);
  if (h) {
    const t = inline(n.kids).replace(/\n/g, ' ').trim();
    return t === '' ? [] : [`${'#'.repeat(Number(h[1]))} ${t}`];
  }
  switch (n.tag) {
    case 'hr': return ['---'];
    case 'pre': {
      if (cls(n, 'mermaid')) return ['```mermaid\n' + (n.attrs['data-mermaid'] ?? text(n)) + '\n```'];
      const code = n.kids.find((k): k is HEl => isEl(k) && k.tag === 'code');
      const lang = /(?:^|\s)language-([\w+#-]+)/.exec(code?.attrs.class ?? '')?.[1] ?? '';
      return ['```' + lang + '\n' + text(code ?? n).replace(/\n$/, '') + '\n```'];
    }
    case 'blockquote': {
      const inner = blocks(n.kids).join('\n\n');
      return inner === '' ? [] : [prefix('> ', inner)];
    }
    case 'ul': case 'ol': return [list(n, 0)];
    case 'table': return table(n);
    case 'div':
      if (cls(n, 'callout')) return [callout(n)];
      if (cls(n, 'math')) return [`$$\n${n.attrs['data-tex'] ?? text(n)}\n$$`];
      return blocks(n.kids);
    default: return blocks(n.kids);
  }
}

function callout(n: HEl): string {
  const type = n.attrs['data-callout'] ?? 'note';
  const title = n.kids.find((k): k is HEl => isEl(k) && cls(k, 'callout-title'));
  const content = n.kids.find((k): k is HEl => isEl(k) && cls(k, 'callout-content'));
  const t = title ? inline(title.kids).trim() : '';
  const body = content ? blocks(content.kids).join('\n\n') : '';
  const head = `[!${type}]${n.attrs['data-fold'] ?? ''}${t ? ' ' + t : ''}`;
  return prefix('> ', body === '' ? head : `${head}\n${body}`);
}

function list(n: HEl, depth: number): string {
  const ordered = n.tag === 'ol';
  const pad = '  '.repeat(depth);
  const lines: string[] = [];
  let i = 0;
  for (const li of n.kids) {
    if (!isEl(li) || li.tag !== 'li') continue;
    i++;
    const nested = li.kids.filter((k): k is HEl => isEl(k) && (k.tag === 'ul' || k.tag === 'ol'));
    const own = li.kids.filter(k => !(isEl(k) && nested.includes(k)));
    const box = own.find((k): k is HEl => isEl(k) && k.tag === 'input');
    const task = cls(li, 'task-list-item') || box !== undefined;
    // fromDom writes 'true' / 'false'; a plain `checked` attribute (the rendered HTML) is present with an empty value
    const done = box ? box.attrs.checked !== undefined && box.attrs.checked !== 'false' : li.attrs['data-task'] === 'x';
    const body = inline(own).replace(/^\s+|\s+$/g, '').replace(/\n+/g, ' ');
    lines.push(`${pad}${ordered ? `${i}.` : '-'} ${task ? `[${done ? 'x' : ' '}] ` : ''}${body}`);
    for (const sub of nested) lines.push(list(sub, depth + 1));
  }
  return lines.join('\n');
}

function table(n: HEl): string[] {
  const rows: HEl[] = [];
  const walk = (k: HNode) => {
    if (!isEl(k)) return;
    if (k.tag === 'tr') rows.push(k);
    else k.kids.forEach(walk);
  };
  n.kids.forEach(walk);
  if (rows.length === 0) return [];
  const cells = (r: HEl) => r.kids.filter((c): c is HEl => isEl(c) && (c.tag === 'td' || c.tag === 'th')).map(c => inline(c.kids).replace(/\n/g, ' ').trim());
  const head = cells(rows[0]!);
  const line = (c: string[]) => `| ${c.join(' | ')} |`;
  return [[line(head), line(head.map(() => '---')), ...rows.slice(1).map(r => line(cells(r)))].join('\n')];
}

/** The note body as Markdown: blocks separated by one blank line. */
export function toMarkdown(root: HNode[]): string {
  return blocks(root).join('\n\n') + '\n';
}
