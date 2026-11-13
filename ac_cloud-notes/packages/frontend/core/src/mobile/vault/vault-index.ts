// The vault index: links, backlinks, tags, headings. Pure, so it runs in a Web Worker
// (vault-index.worker.ts) and under node. Links resolve the way Obsidian's "shortest path"
// does: a path-looking target matches by path suffix, a bare name by file name; ties go to
// the file in the same folder, then to the shortest path.
import { extractAllLinks, type LinkKind, pointsAtFile, resolveTarget } from './core/link-resolver';
import { extOf, stem } from './file-types';
import {
  extractHeadings,
  extractTags,
  extractTasks,
  type Heading,
  parseFrontmatter,
  wordCount,
} from './markdown';

export interface RawLink {
  target: string;
  heading?: string;
  embed: boolean;
  /** #1717 which kind of link this is (core/link-resolver.ts) */
  kind?: LinkKind;
  block?: string;
  alias?: string;
}

export interface IndexedLink extends RawLink {
  /** vault path of the file it points at, or null when nothing matches */
  resolved: string | null;
}

export interface NoteInfo {
  path: string;
  title: string;
  links: IndexedLink[];
  tags: string[];
  headings: Heading[];
  words: number;
  tasks: { total: number; done: number };
  frontmatter: Record<string, unknown>;
}

export interface VaultIndex {
  notes: Record<string, NoteInfo>;
  /** target path -> source note paths */
  backlinks: Record<string, string[]>;
  /** tag -> note paths */
  tags: Record<string, string[]>;
  /** unresolved link target -> source note paths */
  unresolved: Record<string, string[]>;
}

export const INDEXED_EXT = ['md', 'markdown', 'canvas'];

/** Resolve a link target to a vault path. Kept as the index's name for LinkResolver's one rule (core/link-resolver.ts). */
export function resolveLink(target: string, fromPath: string, allPaths: readonly string[]): string | null {
  return resolveTarget(target, fromPath, allPaths);
}

interface Parsed {
  path: string;
  title: string;
  links: RawLink[];
  tags: string[];
  headings: Heading[];
  words: number;
  tasks: { total: number; done: number };
  frontmatter: Record<string, unknown>;
}

/** The links of a file that point at vault files, every kind (wikilinks, embeds, markdown links and images, canvas nodes). */
const fileLinks = (path: string, text: string): RawLink[] =>
  extractAllLinks(path, text)
    .filter(pointsAtFile)
    .map(l => ({ target: l.target, heading: l.heading, embed: l.embed, kind: l.kind, ...(l.block ? { block: l.block } : {}), ...(l.alias ? { alias: l.alias } : {}) }));

export function parseNote(path: string, text: string): Parsed {
  if (extOf(path) === 'canvas') {
    return { path, title: stem(path), links: fileLinks(path, text), tags: [], headings: [], words: 0, tasks: { total: 0, done: 0 }, frontmatter: {} };
  }
  const fm = parseFrontmatter(text);
  const tasks = extractTasks(fm.body);
  return {
    path,
    title: stem(path),
    links: fileLinks(path, fm.body),
    tags: extractTags(fm.body, fm.data),
    headings: extractHeadings(fm.body),
    words: wordCount(fm.body),
    tasks: { total: tasks.length, done: tasks.filter(t => t.done).length },
    frontmatter: fm.data,
  };
}

/** Incremental builder: add notes as they are read (a chunk at a time), then finish(). */
export class IndexBuilder {
  private parsed: Parsed[] = [];
  add(path: string, text: string): void {
    this.parsed.push(parseNote(path, text));
  }
  get size(): number {
    return this.parsed.length;
  }
  finish(allPaths: readonly string[]): VaultIndex {
    const idx: VaultIndex = { notes: {}, backlinks: {}, tags: {}, unresolved: {} };
    const push = (m: Record<string, string[]>, k: string, v: string) => {
      const list = (m[k] ??= []);
      if (!list.includes(v)) list.push(v);
    };
    for (const p of this.parsed) {
      const links: IndexedLink[] = p.links.map(l => ({ ...l, resolved: resolveLink(l.target, p.path, allPaths) }));
      idx.notes[p.path] = { ...p, links };
      for (const l of links) {
        if (l.resolved === null) push(idx.unresolved, l.target, p.path);
        else if (l.resolved !== p.path) push(idx.backlinks, l.resolved, p.path);
      }
      for (const t of p.tags) push(idx.tags, t, p.path);
    }
    return idx;
  }
}

export function buildIndex(files: { path: string; text: string }[], allPaths?: readonly string[]): VaultIndex {
  const b = new IndexBuilder();
  for (const f of files) b.add(f.path, f.text);
  return b.finish(allPaths ?? files.map(f => f.path));
}

/** Tag tree rows for the Tags pane: nested tags roll their counts up to the parent. */
export function tagCounts(index: VaultIndex): { tag: string; count: number }[] {
  const counts = new Map<string, Set<string>>();
  for (const [tag, paths] of Object.entries(index.tags)) {
    const parts = tag.split('/');
    for (let i = 1; i <= parts.length; i++) {
      const key = parts.slice(0, i).join('/');
      const set = counts.get(key) ?? new Set<string>();
      for (const p of paths) set.add(p);
      counts.set(key, set);
    }
  }
  return [...counts.entries()].map(([tag, s]) => ({ tag, count: s.size })).sort((a, b) => a.tag.localeCompare(b.tag));
}

export function searchPaths(paths: readonly string[], query: string, limit = 50): string[] {
  const q = query.trim().toLowerCase();
  if (q === '') return paths.slice(0, limit);
  const score = (p: string): number => {
    const low = p.toLowerCase();
    const name = stem(low);
    if (name === q) return 0;
    if (name.startsWith(q)) return 1;
    if (name.includes(q)) return 2;
    if (low.includes(q)) return 3;
    // subsequence match on the file name
    let j = 0;
    for (const ch of name) if (ch === q[j]) j++;
    return j === q.length ? 4 : 99;
  };
  return paths
    .map(p => [p, score(p)] as const)
    .filter(([, s]) => s < 99)
    .sort((a, b) => a[1] - b[1] || a[0].length - b[0].length)
    .slice(0, limit)
    .map(([p]) => p);
}

export interface ContentHit {
  path: string;
  line: number;
  text: string;
}

export function searchContent(texts: Record<string, string>, query: string, limit = 100): ContentHit[] {
  const q = query.trim().toLowerCase();
  if (q.length < 2) return [];
  const hits: ContentHit[] = [];
  for (const [path, text] of Object.entries(texts)) {
    const lines = text.split('\n');
    for (let i = 0; i < lines.length && hits.length < limit; i++) {
      if (lines[i]!.toLowerCase().includes(q)) hits.push({ path, line: i, text: lines[i]!.trim().slice(0, 200) });
    }
    if (hits.length >= limit) break;
  }
  return hits;
}
