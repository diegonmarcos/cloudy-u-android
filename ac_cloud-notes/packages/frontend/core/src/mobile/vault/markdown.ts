// Obsidian-flavoured Markdown, the parts the vault view needs: frontmatter, wikilinks,
// embeds, tags, headings, tasks, callouts, mermaid fences and math marks. Pure and
// dependency-free so the index can run in a worker and the tests can run under node.
// Raw HTML in a note is ESCAPED, never passed through: a note cannot script the app.

export type FrontmatterValue = string | number | boolean | null | FrontmatterValue[];

export interface Frontmatter {
  data: Record<string, FrontmatterValue>;
  /** the note text after the frontmatter block */
  body: string;
  /** true when a block was present (even an empty one) */
  present: boolean;
}

const scalar = (s: string): FrontmatterValue => {
  const t = s.trim();
  if (t === '' || t === '~' || t === 'null') return null;
  if (t === 'true') return true;
  if (t === 'false') return false;
  if (/^-?\d+(\.\d+)?$/.test(t)) return Number(t);
  const q = /^(["'])(.*)\1$/.exec(t);
  if (q) return q[2]!;
  if (t.startsWith('[') && t.endsWith(']')) {
    const inner = t.slice(1, -1).trim();
    return inner === '' ? [] : inner.split(',').map(scalar);
  }
  return t;
};

/** A deliberately small YAML subset: `key: value`, inline lists, `- item` lists. */
export function parseFrontmatter(src: string): Frontmatter {
  const m = /^---[ \t]*\r?\n([\s\S]*?)\r?\n?---[ \t]*(?:\r?\n|$)/.exec(src);
  if (!m) return { data: {}, body: src, present: false };
  const data: Record<string, FrontmatterValue> = {};
  let key: string | null = null;
  for (const line of m[1]!.split(/\r?\n/)) {
    if (/^\s*#/.test(line) || line.trim() === '') continue;
    const item = /^\s+-\s*(.*)$/.exec(line) ?? (key && /^-\s+(.*)$/.exec(line));
    if (item && key) {
      const cur = data[key];
      const list: FrontmatterValue[] = Array.isArray(cur) ? cur : [];
      list.push(scalar(item[1]!));
      data[key] = list;
      continue;
    }
    const kv = /^([^\s:][^:]*):\s*(.*)$/.exec(line);
    if (kv) {
      key = kv[1]!.trim();
      data[key] = kv[2]!.trim() === '' ? [] : scalar(kv[2]!);
      if (kv[2]!.trim() === '') data[key] = null;
    }
  }
  return { data, body: src.slice(m[0].length), present: true };
}

export interface WikiLink {
  /** note name or path, without heading/alias */
  target: string;
  heading?: string;
  block?: string;
  alias?: string;
  embed: boolean;
}

/** `text` with fenced and inline code blanked out (same length) so scanners skip it. */
export function maskCode(text: string): string {
  let out = text.replace(/(^|\n)(```|~~~)[^\n]*\n[\s\S]*?(\n\2[^\n]*(?=\n|$)|$)/g, s =>
    s.replace(/[^\n]/g, ' ')
  );
  out = out.replace(/`[^`\n]*`/g, s => ' '.repeat(s.length));
  return out;
}

const WIKI = /(!?)\[\[([^\]\n|]+?)(?:\|([^\]\n]*))?\]\]/g;

export function parseWikiTarget(raw: string): Pick<WikiLink, 'target' | 'heading' | 'block'> {
  const hashAt = raw.indexOf('#');
  if (hashAt < 0) return { target: raw.trim() };
  const target = raw.slice(0, hashAt).trim();
  const rest = raw.slice(hashAt + 1).trim();
  return rest.startsWith('^') ? { target, block: rest.slice(1) } : { target, heading: rest };
}

export function extractLinks(body: string): WikiLink[] {
  const out: WikiLink[] = [];
  const text = maskCode(body);
  for (const m of text.matchAll(WIKI)) {
    const t = parseWikiTarget(m[2]!);
    // [[#heading]] links inside the same note have an empty target; keep them out of the graph
    if (t.target === '') continue;
    out.push({ ...t, alias: m[3]?.trim() || undefined, embed: m[1] === '!' });
  }
  return out;
}

const TAG = /(^|[\s(,;])#([\p{L}\p{N}_\-/]*[\p{L}_\-/][\p{L}\p{N}_\-/]*)/gu;

export function extractTags(body: string, fm: Record<string, FrontmatterValue> = {}): string[] {
  const set = new Set<string>();
  const text = maskCode(body).replace(WIKI, s => ' '.repeat(s.length));
  for (const m of text.matchAll(TAG)) set.add(m[2]!);
  const raw = fm.tags ?? fm.tag;
  const fmTags = Array.isArray(raw) ? raw : typeof raw === 'string' ? raw.split(/[,\s]+/) : [];
  for (const t of fmTags) if (typeof t === 'string' && t.trim()) set.add(t.trim().replace(/^#/, ''));
  return [...set];
}

export interface Heading {
  level: number;
  text: string;
  /** 0-based line within the text it was extracted from */
  line: number;
}

export function extractHeadings(body: string): Heading[] {
  const out: Heading[] = [];
  const lines = maskCode(body).split('\n');
  lines.forEach((l, i) => {
    const m = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(l);
    if (m) out.push({ level: m[1]!.length, text: m[2]!.trim(), line: i });
  });
  return out;
}

export interface TaskItem {
  line: number;
  done: boolean;
  text: string;
}

export function extractTasks(body: string): TaskItem[] {
  const out: TaskItem[] = [];
  maskCode(body)
    .split('\n')
    .forEach((l, i) => {
      const m = /^\s*[-*+]\s+\[(.)\]\s+(.*)$/.exec(l);
      if (m) out.push({ line: i, done: m[1] !== ' ', text: m[2]! });
    });
  return out;
}

export function wordCount(body: string): number {
  const t = maskCode(body).replace(/[#>*_`~\[\]()!|-]/g, ' ').trim();
  return t === '' ? 0 : t.split(/\s+/).length;
}

// ---------------------------------------------------------------- rendering

export const escapeHtml = (s: string) =>
  s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;');

/** Hrefs a note may carry: web, mail, and relative paths. Never javascript: or data:. */
export function safeHref(href: string): string | null {
  const h = href.trim();
  if (/^(https?:|mailto:)/i.test(h)) return h;
  if (/^[a-z][a-z0-9+.-]*:/i.test(h)) return null;
  return h;
}

export const IMAGE_EXT = /\.(png|jpe?g|gif|svg|webp|bmp|avif)$/i;

function inline(src: string): string {
  const stash: string[] = [];
  const hold = (html: string) => `\u0000${stash.push(html) - 1}\u0000`;
  let s = src;
  s = s.replace(/`([^`\n]+)`/g, (_m, c: string) => hold(`<code>${escapeHtml(c)}</code>`));
  // math before emphasis so `_` and `*` inside TeX survive
  s = s.replace(/\$\$([^$\n]+)\$\$/g, (_m, t: string) =>
    hold(`<span class="math math-display" data-tex="${escapeHtml(t)}">${escapeHtml(t)}</span>`)
  );
  s = s.replace(/(^|[^\\$\w])\$([^\s$][^$\n]*?[^\s$\\]|[^\s$])\$(?![\w$])/g, (_m, pre: string, t: string) =>
    pre + hold(`<span class="math" data-tex="${escapeHtml(t)}">${escapeHtml(t)}</span>`)
  );
  s = s.replace(WIKI, (_m, bang: string, raw: string, alias?: string) => {
    const t = parseWikiTarget(raw);
    const label = alias?.trim() || (t.heading ? `${t.target} > ${t.heading}` : t.target);
    const attrs =
      `data-target="${escapeHtml(t.target)}"` +
      (t.heading ? ` data-heading="${escapeHtml(t.heading)}"` : '') +
      (alias ? ` data-alias="${escapeHtml(alias.trim())}"` : '');
    if (bang) {
      const kind = IMAGE_EXT.test(t.target) ? 'image' : 'note';
      return hold(`<span class="embed" data-embed="${kind}" ${attrs}>${escapeHtml(label)}</span>`);
    }
    return hold(`<a class="internal-link" href="#" ${attrs}>${escapeHtml(label)}</a>`);
  });
  // #1717 a destination may be wrapped in <...> so it can hold spaces ([x](<My Note.md>))
  const bare = (url: string) => (url.startsWith('<') && url.endsWith('>') ? url.slice(1, -1) : url);
  s = s.replace(/!\[([^\]\n]*)\]\((<[^>\n]+>|[^)\s]+)(?:\s+"[^"]*")?\)/g, (_m, alt: string, url: string) => {
    const u = safeHref(bare(url));
    return u === null ? escapeHtml(alt) : hold(`<img alt="${escapeHtml(alt)}" data-src="${escapeHtml(u)}">`);
  });
  s = s.replace(/\[([^\]\n]+)\]\((<[^>\n]+>|[^)\s]+)(?:\s+"[^"]*")?\)/g, (_m, label: string, url: string) => {
    const u = safeHref(bare(url));
    if (u === null) return escapeHtml(label);
    const ext = /^(https?:|mailto:)/i.test(u);
    // #1717 data-href marks a markdown destination (percent-encoded, relative): LinkResolver decodes it, a wikilink target is taken as written
    return hold(
      `<a class="${ext ? 'external-link' : 'internal-link'}" href="${ext ? escapeHtml(u) : '#'}"` +
        (ext ? ' rel="noopener noreferrer" target="_blank"' : ` data-target="${escapeHtml(u)}" data-href="1"`) +
        `>${escapeHtml(label)}</a>`
    );
  });
  s = escapeHtml(s);
  s = s.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  s = s.replace(/(^|[^\w*])\*(?!\s)(.+?)(?<!\s)\*(?!\w)/g, '$1<em>$2</em>');
  s = s.replace(/(^|[^\w])_(?!\s)(.+?)(?<!\s)_(?!\w)/g, '$1<em>$2</em>');
  s = s.replace(/~~(.+?)~~/g, '<del>$1</del>');
  s = s.replace(/==(.+?)==/g, '<mark>$1</mark>');
  s = s.replace(TAG, (_m, pre: string, tag: string) => `${pre}<a class="tag" href="#" data-tag="${tag}">#${tag}</a>`);
  s = s.replace(/\s\^[\w-]+$/, '');
  // eslint-disable-next-line no-control-regex
  return s.replace(/\u0000(\d+)\u0000/g, (_m, i: string) => stash[Number(i)]!);
}

export const CALLOUT_TYPES = [
  'note', 'abstract', 'summary', 'tldr', 'info', 'todo', 'tip', 'hint', 'important', 'success', 'check', 'done',
  'question', 'help', 'faq', 'warning', 'caution', 'attention', 'failure', 'fail', 'missing', 'danger', 'error',
  'bug', 'example', 'quote', 'cite',
];

export interface RenderOptions {
  /** drop the leading frontmatter block (default true) */
  stripFrontmatter?: boolean;
}

/** Markdown to an HTML string. Block set: headings, lists, tasks, quotes, callouts, fences, tables, rules. */
export function renderMarkdown(src: string, opts: RenderOptions = {}): string {
  const text = (opts.stripFrontmatter === false ? src : parseFrontmatter(src).body).replace(/\r\n?/g, '\n');
  const lines = text.split('\n');
  const out: string[] = [];
  let i = 0;
  const isBlank = (l: string | undefined) => l === undefined || l.trim() === '';
  const startsBlock = (l: string) =>
    /^(#{1,6}\s|>|\s*([-*+]|\d+[.)])\s|```|~~~|\s*([-*_])(\s*\3){2,}\s*$|\$\$)/.test(l);

  while (i < lines.length) {
    const line = lines[i]!;
    if (isBlank(line)) { i++; continue; }

    const fence = /^(```|~~~)\s*([^\s`]*)[^\n]*$/.exec(line);
    if (fence) {
      const lang = fence[2]!.toLowerCase();
      const body: string[] = [];
      i++;
      while (i < lines.length && !lines[i]!.startsWith(fence[1]!)) body.push(lines[i++]!);
      i++;
      const code = body.join('\n');
      if (lang === 'mermaid') {
        out.push(`<pre class="mermaid" data-mermaid="${escapeHtml(code)}">${escapeHtml(code)}</pre>`);
      } else if (lang === 'math' || lang === 'latex') {
        out.push(`<div class="math math-display" data-tex="${escapeHtml(code)}">${escapeHtml(code)}</div>`);
      } else {
        out.push(`<pre><code${lang ? ` class="language-${escapeHtml(lang)}"` : ''}>${escapeHtml(code)}</code></pre>`);
      }
      continue;
    }
    if (line.trim().startsWith('$$')) {
      const body: string[] = [];
      const first = line.trim().slice(2);
      if (first.endsWith('$$') && first.length >= 2) {
        body.push(first.slice(0, -2));
        i++;
      } else {
        if (first.trim()) body.push(first);
        i++;
        while (i < lines.length && !lines[i]!.includes('$$')) body.push(lines[i++]!);
        if (i < lines.length) body.push(lines[i++]!.replace(/\$\$.*$/, ''));
      }
      const tex = body.join('\n').trim();
      out.push(`<div class="math math-display" data-tex="${escapeHtml(tex)}">${escapeHtml(tex)}</div>`);
      continue;
    }
    const h = /^(#{1,6})\s+(.+?)\s*#*\s*$/.exec(line);
    if (h) {
      // #1717 data-anchor carries the heading text so a [[Note#Heading]] or (#heading) link can scroll to it
      out.push(`<h${h[1]!.length} data-anchor="${escapeHtml(h[2]!.trim().toLowerCase())}">${inline(h[2]!)}</h${h[1]!.length}>`);
      i++;
      continue;
    }
    if (/^\s*([-*_])(\s*\1){2,}\s*$/.test(line)) { out.push('<hr>'); i++; continue; }

    if (line.startsWith('>')) {
      const q: string[] = [];
      while (i < lines.length && lines[i]!.startsWith('>')) q.push(lines[i++]!.replace(/^>\s?/, ''));
      const co = /^\[!([\w-]+)\]([+-]?)[ \t]*(.*)$/.exec(q[0]!);
      if (co) {
        const type = co[1]!.toLowerCase();
        const title = co[3]!.trim() || type.charAt(0).toUpperCase() + type.slice(1);
        const known = CALLOUT_TYPES.includes(type) ? type : 'note';
        out.push(
          `<div class="callout" data-callout="${escapeHtml(known)}"><div class="callout-title">${inline(title)}</div>` +
            `<div class="callout-content">${renderMarkdown(q.slice(1).join('\n'), { stripFrontmatter: false })}</div></div>`
        );
      } else {
        out.push(`<blockquote>${renderMarkdown(q.join('\n'), { stripFrontmatter: false })}</blockquote>`);
      }
      continue;
    }
    if (/^\s*\|.*\|\s*$/.test(line) && /^\s*\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?\s*$/.test(lines[i + 1] ?? '')) {
      const cells = (l: string) => l.trim().replace(/^\|/, '').replace(/\|$/, '').split('|').map(c => c.trim());
      const head = cells(line);
      i += 2;
      const rows: string[][] = [];
      while (i < lines.length && /^\s*\|.*\|\s*$/.test(lines[i]!)) rows.push(cells(lines[i++]!));
      out.push(
        '<table><thead><tr>' + head.map(c => `<th>${inline(c)}</th>`).join('') + '</tr></thead><tbody>' +
          rows.map(r => '<tr>' + r.map(c => `<td>${inline(c)}</td>`).join('') + '</tr>').join('') +
          '</tbody></table>'
      );
      continue;
    }
    const li = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(line);
    if (li) {
      const ordered = /\d/.test(li[2]!);
      const base = li[1]!.length;
      const items: string[] = [];
      while (i < lines.length) {
        const m = /^(\s*)([-*+]|\d+[.)])\s+(.*)$/.exec(lines[i]!);
        if (!m || m[1]!.length < base || /\d/.test(m[2]!) !== ordered && m[1]!.length === base) break;
        if (m[1]!.length > base) {
          // nested list: collect the deeper run and render it recursively into the previous item
          const nested: string[] = [];
          while (i < lines.length && /^\s+([-*+]|\d+[.)])\s/.test(lines[i]!) && lines[i]!.search(/\S/) > base) {
            nested.push(lines[i++]!);
          }
          if (items.length) items[items.length - 1] += renderMarkdown(nested.join('\n'), { stripFrontmatter: false });
          continue;
        }
        const task = /^\[(.)\]\s+(.*)$/.exec(m[3]!);
        items.push(
          task
            ? `<li class="task-list-item" data-task="${task[1] === ' ' ? ' ' : 'x'}"><input type="checkbox" disabled${task[1] === ' ' ? '' : ' checked'}> ${inline(task[2]!)}`
            : `<li>${inline(m[3]!)}`
        );
        i++;
      }
      const tag = ordered ? 'ol' : 'ul';
      out.push(`<${tag}>${items.map(x => x + '</li>').join('')}</${tag}>`);
      continue;
    }
    const para: string[] = [line];
    i++;
    while (i < lines.length && !isBlank(lines[i]) && !startsBlock(lines[i]!)) para.push(lines[i++]!);
    out.push(`<p>${para.map(inline).join('<br>')}</p>`);
  }
  return out.join('\n');
}
