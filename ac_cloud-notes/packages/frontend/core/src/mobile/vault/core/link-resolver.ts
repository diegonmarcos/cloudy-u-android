// LinkResolver: the ONE place a link in a vault is parsed, normalised, resolved and followed.
// Every link kind the vault knows goes through here (the index, the Markdown view, the canvas,
// the Links pane and the graph all call this module, none resolves on its own):
//   wikilink      [[Note]]  [[folder/Note]]  [[Note|alias]]  [[Note#Heading]]  [[Note#^block]]
//   wiki-embed    ![[Note]]  ![[image.png]]
//   heading       [[#Heading]]  [text](#heading)          (inside the same file)
//   md-link       [text](other.md)  [text](./a/b.md)  [text](../b.md#h)  [text](My%20Note.md)  [text](<My Note.md>)
//   md-image      ![alt](img.png)
//   external      [text](https://...)  <https://...>  mailto:
//   canvas-file   a .canvas "file" node (with its optional subpath)
//   canvas-link   a .canvas "link" node (url)
//   canvas-text   links of any kind above inside a .canvas text card
// Pure (no DOM), so it runs in the index worker and under node.
import { baseName, extOf } from '../file-types';
import { parseCanvas } from '../jsoncanvas';
import { maskCode, parseWikiTarget } from '../markdown';

export type LinkKind =
  | 'wikilink'
  | 'wiki-embed'
  | 'heading'
  | 'md-link'
  | 'md-image'
  | 'external'
  | 'canvas-file'
  | 'canvas-link';

export const LINK_KINDS: LinkKind[] = [
  'wikilink', 'wiki-embed', 'heading', 'md-link', 'md-image', 'external', 'canvas-file', 'canvas-link',
];

export interface ParsedLink {
  kind: LinkKind;
  /** note name, vault path or URL; '' for a heading link inside the same file */
  target: string;
  heading?: string;
  block?: string;
  alias?: string;
  /** shown in place (an embed, an image, a canvas file card) instead of navigated to */
  embed: boolean;
  /** the link sits in a canvas text card or node */
  inCanvas?: boolean;
}

const EXTERNAL = /^(https?:|mailto:|tel:|obsidian:)/i;
const SCHEME = /^[a-z][a-z0-9+.-]*:/i;

export const isExternal = (target: string): boolean => EXTERNAL.test(target.trim());

const dirOf = (p: string) => (p.includes('/') ? p.slice(0, p.lastIndexOf('/')) : '');

const safeDecode = (s: string) => {
  try {
    return decodeURIComponent(s);
  } catch {
    return s;
  }
};

/** Split `path#heading` / `path#^block`; a markdown href is percent-decoded and loses its <> wrapper. */
export function splitTarget(raw: string, href = false): { target: string; heading?: string; block?: string } {
  let t = raw.trim();
  if (href) {
    if (t.startsWith('<') && t.endsWith('>')) t = t.slice(1, -1);
    t = safeDecode(t);
  }
  const hashAt = t.indexOf('#');
  if (hashAt < 0) return { target: t.trim() };
  const target = t.slice(0, hashAt).trim();
  const rest = t.slice(hashAt + 1).trim();
  if (rest === '') return { target };
  return rest.startsWith('^') ? { target, block: rest.slice(1) } : { target, heading: rest };
}

/**
 * A target as written -> a vault-relative candidate. `./x` and `../x` are taken relative to the
 * folder of [from]; a leading `/` is the vault root; anything else is left for the name lookup.
 * `relative` says the author wrote an explicit relative path.
 */
export function normalizeTarget(target: string, from: string): { path: string; relative: boolean } {
  let t = target.trim().replace(/\\/g, '/');
  const relative = t.startsWith('./') || t.startsWith('../');
  if (!relative) return { path: t.replace(/^\/+/, '').replace(/\/+$/, ''), relative: false };
  const parts = dirOf(from).split('/').filter(Boolean);
  for (const seg of t.split('/')) {
    if (seg === '' || seg === '.') continue;
    if (seg === '..') parts.pop();
    else parts.push(seg);
  }
  return { path: parts.join('/'), relative: true };
}

const WIKI = /(!?)\[\[([^\]\n|]*?)(?:\|([^\]\n]*))?\]\]/g;
// [text](href "title") and ![alt](href); href is <...> (spaces allowed) or has no whitespace
const MD = /(!?)\[([^\]\n]*)\]\(\s*(<[^>\n]+>|[^)\s]+)(?:\s+"[^"]*")?\s*\)/g;
const AUTO = /<((?:https?:|mailto:)[^>\s]+)>/gi;

/** Every link in a markdown text (code is skipped), in document order. */
export function extractMarkdownLinks(body: string): ParsedLink[] {
  const text = maskCode(body);
  const found: { at: number; link: ParsedLink }[] = [];
  for (const m of text.matchAll(WIKI)) {
    const t = parseWikiTarget(m[2] ?? '');
    const alias = m[3]?.trim() || undefined;
    if (t.target === '') {
      if (t.heading || t.block) found.push({ at: m.index ?? 0, link: { kind: 'heading', target: '', heading: t.heading, block: t.block, alias, embed: false } });
      continue;
    }
    found.push({ at: m.index ?? 0, link: { kind: m[1] === '!' ? 'wiki-embed' : 'wikilink', ...t, alias, embed: m[1] === '!' } });
  }
  const noWiki = text.replace(WIKI, s => ' '.repeat(s.length));
  for (const m of noWiki.matchAll(MD)) {
    const href = m[3]!;
    const bare = href.startsWith('<') ? href.slice(1, -1) : href;
    const label = m[2]?.trim() || undefined;
    if (isExternal(bare)) {
      found.push({ at: m.index ?? 0, link: { kind: 'external', target: bare, alias: label, embed: m[1] === '!' } });
      continue;
    }
    if (SCHEME.test(bare)) continue; // javascript:, data:, file: ... never a vault link
    const t = splitTarget(href, true);
    if (t.target === '') {
      if (t.heading || t.block) found.push({ at: m.index ?? 0, link: { kind: 'heading', target: '', heading: t.heading, block: t.block, alias: label, embed: false } });
      continue;
    }
    found.push({ at: m.index ?? 0, link: { kind: m[1] === '!' ? 'md-image' : 'md-link', ...t, alias: label, embed: m[1] === '!' } });
  }
  for (const m of noWiki.matchAll(AUTO)) {
    found.push({ at: m.index ?? 0, link: { kind: 'external', target: m[1]!, embed: false } });
  }
  return found.sort((a, b) => a.at - b.at).map(f => f.link);
}

/** Every link of a .canvas file: file nodes, link nodes and the links inside text cards. */
export function extractCanvasLinks(text: string): ParsedLink[] {
  const out: ParsedLink[] = [];
  const { canvas } = parseCanvas(text);
  for (const n of canvas.nodes) {
    if (n.type === 'file' && typeof n.file === 'string' && n.file.trim() !== '') {
      const sub: { heading?: string; block?: string } = typeof n.subpath === 'string' ? splitTarget(n.subpath.startsWith('#') ? n.subpath : '#' + n.subpath) : {};
      out.push({ kind: 'canvas-file', target: n.file, heading: sub.heading, block: sub.block, embed: true, inCanvas: true });
    } else if (n.type === 'link' && typeof n.url === 'string' && n.url.trim() !== '') {
      out.push({ kind: 'canvas-link', target: n.url.trim(), embed: false, inCanvas: true });
    } else if (n.type === 'text' && typeof n.text === 'string') {
      for (const l of extractMarkdownLinks(n.text)) out.push({ ...l, inCanvas: true });
    }
  }
  return out;
}

/** Every link of a vault file, by its type. */
export function extractAllLinks(path: string, text: string): ParsedLink[] {
  return extOf(path) === 'canvas' ? extractCanvasLinks(text) : extractMarkdownLinks(text);
}

/** True for the kinds that point at a vault file (and so can have a backlink). */
export const pointsAtFile = (l: ParsedLink): boolean =>
  l.kind === 'wikilink' || l.kind === 'wiki-embed' || l.kind === 'md-link' || l.kind === 'md-image' || l.kind === 'canvas-file';

/**
 * Resolve a link target to a vault path, the way Obsidian's "shortest path" does: an explicit
 * relative path (./, ../) is tried exactly first; a path-looking target matches by path suffix,
 * a bare name by file name; a target without an extension means a note (.md). Ties go to the
 * file in the same folder, then the exact path, then the shortest path.
 */
export function resolveTarget(target: string, fromPath: string, allPaths: readonly string[]): string | null {
  const { target: noHash } = splitTarget(target);
  const norm = normalizeTarget(noHash, fromPath);
  const t = norm.path;
  if (t === '' || isExternal(target)) return null;
  const lower = t.toLowerCase();
  const wanted = extOf(t) === '' ? [lower + '.md', lower] : [lower];
  if (norm.relative) {
    const exact = allPaths.find(p => wanted.includes(p.toLowerCase()));
    if (exact) return exact;
  }
  const fromDir = dirOf(fromPath);
  // a relative target that missed falls back to its last segments (the file may have moved)
  const lookup = norm.relative ? wanted.map(w => baseName(w)) : wanted;
  const byPath = !norm.relative && t.includes('/');
  const hits = allPaths.filter(p => {
    const pl = p.toLowerCase();
    return lookup.some(w => (byPath ? pl === w || pl.endsWith('/' + w) : baseName(pl) === w));
  });
  if (hits.length === 0) return null;
  hits.sort(
    (a, b) =>
      Number(dirOf(b) === fromDir) - Number(dirOf(a) === fromDir) ||
      Number(lookup[0] === b.toLowerCase()) - Number(lookup[0] === a.toLowerCase()) ||
      a.split('/').length - b.split('/').length ||
      a.localeCompare(b)
  );
  return hits[0]!;
}

/** What following a link does. The UI performs it; nothing here touches the DOM or the disk. */
export type LinkAction =
  | { type: 'open'; path: string; heading?: string; block?: string }
  | { type: 'external'; url: string }
  | { type: 'anchor'; heading?: string; block?: string }
  | { type: 'create'; dir: string; name: string }
  | { type: 'none'; reason: string };

export class LinkResolver {
  private readonly paths: readonly string[];
  constructor(paths: readonly string[]) {
    this.paths = paths;
  }

  resolve(target: string, from: string): string | null {
    return resolveTarget(target, from, this.paths);
  }

  /** What a tap on [link] inside [from] does. A missing note is created next to [from], as before. */
  follow(link: ParsedLink, from: string): LinkAction {
    if (link.kind === 'external' || link.kind === 'canvas-link' || isExternal(link.target)) {
      return isExternal(link.target) ? { type: 'external', url: link.target } : { type: 'none', reason: 'This address is not a web, mail or vault link.' };
    }
    if (link.kind === 'heading' || link.target.trim() === '') {
      return link.heading || link.block ? { type: 'anchor', heading: link.heading, block: link.block } : { type: 'none', reason: 'The link has no target.' };
    }
    const hit = this.resolve(link.target, from);
    if (hit) return { type: 'open', path: hit, heading: link.heading, block: link.block };
    const norm = normalizeTarget(splitTarget(link.target).target, from);
    const name = baseName(norm.path);
    if (name === '') return { type: 'none', reason: 'The link has no target.' };
    // a target with a folder names where the note goes; a bare name is created next to the current file
    const dir = norm.relative || norm.path.includes('/') ? dirOf(norm.path) : dirOf(from);
    return { type: 'create', dir, name: name.includes('.') ? name : name + '.md' };
  }

  /** Follow a target as written (data-target of a rendered link), with the kind guessed from its shape. */
  followTarget(raw: string, from: string, href = false): LinkAction {
    if (isExternal(raw)) return { type: 'external', url: raw.trim() };
    const t = splitTarget(raw, href);
    return this.follow({ kind: t.target === '' ? 'heading' : href ? 'md-link' : 'wikilink', ...t, embed: false }, from);
  }
}

/** Heading text -> the id a rendered heading carries, so an anchor link can scroll to it. */
export const headingSlug = (text: string): string =>
  text.trim().toLowerCase().replace(/[^\p{L}\p{N}\s-]/gu, '').replace(/\s+/g, '-');
