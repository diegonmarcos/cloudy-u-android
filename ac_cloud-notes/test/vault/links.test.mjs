// #1717 LinkResolver: every link kind is parsed, resolved and followed by ONE module.
import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { FIX, fixture, src, walk } from './helpers.mjs';

const lr = await src('core/link-resolver.ts');
const vi = await src('vault-index.ts');
const root = join(FIX, 'links');
const all = walk(root);
const R = new lr.LinkResolver(all);
const index = lr.extractAllLinks('Index.md', fixture('links', 'Index.md'));
const by = (kind, target) => index.find(l => l.kind === kind && l.target === target);

test('every link kind is recognised in a note', () => {
  const kinds = new Set(index.map(l => l.kind));
  for (const k of ['wikilink', 'wiki-embed', 'heading', 'md-link', 'md-image', 'external']) assert.ok(kinds.has(k), `kind ${k} found`);
  assert.ok(by('wikilink', 'Target'));
  assert.equal(index.find(l => l.kind === 'wikilink' && l.alias === 'the target').target, 'Target');
  assert.equal(index.find(l => l.heading === 'Section Two').kind, 'wikilink');
  assert.equal(index.find(l => l.block === 'abc123').target, 'Target');
  assert.deepEqual(index.filter(l => l.kind === 'heading').map(l => l.heading), ['Index', 'index']);
  assert.deepEqual(index.filter(l => l.kind === 'wiki-embed').map(l => l.target), ['Target', 'pic.png']);
  assert.deepEqual(index.filter(l => l.kind === 'md-link').map(l => l.target),
    ['Target.md', './notes/Deep Note.md', 'notes/Deep Note.md', 'notes/sub/Leaf.md', 'missing/Gone.md']);
  assert.equal(by('md-link', 'notes/sub/Leaf.md').heading, 'Top');
  assert.equal(by('md-image', 'assets/pic.png').embed, true);
  assert.deepEqual(index.filter(l => l.kind === 'external').map(l => l.target),
    ['https://example.org/a?b=1', 'https://example.org/auto', 'mailto:someone@example.org']);
});

test('code and unsafe schemes are never links', () => {
  assert.ok(!index.some(l => l.target === 'Code' || l.target === 'Fenced'));
  assert.ok(!index.some(l => /javascript/i.test(l.target)));
});

test('canvas links: file nodes (with subpath), link nodes, links inside text cards', () => {
  const links = lr.extractAllLinks('Board.canvas', fixture('links', 'Board.canvas'));
  assert.deepEqual(links.filter(l => l.kind === 'canvas-file').map(l => [l.target, l.heading]),
    [['Target.md', 'Section Two'], ['assets/pic.png', undefined], ['Missing.md', undefined]]);
  assert.deepEqual(links.filter(l => l.kind === 'canvas-link').map(l => l.target), ['https://example.org/canvas']);
  assert.deepEqual(links.filter(l => l.inCanvas && !l.kind.startsWith('canvas')).map(l => [l.kind, l.target]),
    [['wikilink', 'Index'], ['md-link', 'notes/Deep Note.md'], ['external', 'https://example.org/card']]);
  assert.ok(links.every(l => l.inCanvas));
});

test('resolution: one rule for every kind', () => {
  assert.equal(R.resolve('Target', 'Index.md'), 'Target.md');
  assert.equal(R.resolve('notes/Deep Note', 'Index.md'), 'notes/Deep Note.md');
  assert.equal(R.resolve('./notes/Deep Note.md', 'Index.md'), 'notes/Deep Note.md');
  assert.equal(R.resolve('../Index.md', 'notes/Deep Note.md'), 'Index.md');
  assert.equal(R.resolve('./sub/Leaf.md', 'notes/Deep Note.md'), 'notes/sub/Leaf.md');
  assert.equal(R.resolve('../../Target.md', 'notes/sub/Leaf.md'), 'Target.md');
  assert.equal(R.resolve('Leaf', 'notes/Deep Note.md'), 'notes/sub/Leaf.md');
  assert.equal(R.resolve('pic.png', 'Index.md'), 'assets/pic.png');
  assert.equal(R.resolve('Board.canvas', 'Index.md'), 'Board.canvas');
  assert.equal(R.resolve('Target#Section Two', 'Index.md'), 'Target.md');
  assert.equal(R.resolve('/Target.md', 'notes/sub/Leaf.md'), 'Target.md');
  assert.equal(R.resolve('Nowhere', 'Index.md'), null);
  assert.equal(R.resolve('https://example.org', 'Index.md'), null);
  assert.equal(R.resolve('', 'Index.md'), null);
  // the index keeps its old entry point, on the same rule
  assert.equal(vi.resolveLink('Target', 'Index.md', all), 'Target.md');
});

test('following a link: open, external, anchor, create, none', () => {
  assert.deepEqual(R.followTarget('Target#Section Two', 'Index.md'), { type: 'open', path: 'Target.md', heading: 'Section Two', block: undefined });
  assert.deepEqual(R.followTarget('./notes/Deep%20Note.md', 'Index.md', true), { type: 'open', path: 'notes/Deep Note.md', heading: undefined, block: undefined });
  assert.deepEqual(R.followTarget('<notes/Deep Note.md>', 'Index.md', true).path, 'notes/Deep Note.md');
  assert.deepEqual(R.followTarget('../../Target.md#Section%20Two', 'notes/sub/Leaf.md', true), { type: 'open', path: 'Target.md', heading: 'Section Two', block: undefined });
  assert.deepEqual(R.followTarget('https://example.org/a?b=1', 'Index.md'), { type: 'external', url: 'https://example.org/a?b=1' });
  assert.deepEqual(R.followTarget('mailto:someone@example.org', 'Index.md', true), { type: 'external', url: 'mailto:someone@example.org' });
  assert.deepEqual(R.followTarget('#index', 'Index.md', true), { type: 'anchor', heading: 'index', block: undefined });
  assert.deepEqual(R.followTarget('Nowhere', 'notes/Deep Note.md'), { type: 'create', dir: 'notes', name: 'Nowhere.md' });
  assert.deepEqual(R.followTarget('missing/Gone.md', 'Index.md', true), { type: 'create', dir: 'missing', name: 'Gone.md' });
  assert.equal(R.follow({ kind: 'canvas-link', target: 'https://example.org/canvas', embed: false }, 'Board.canvas').type, 'external');
  assert.equal(R.follow({ kind: 'canvas-link', target: 'javascript:alert(1)', embed: false }, 'Board.canvas').type, 'none');
  assert.equal(R.follow({ kind: 'canvas-file', target: 'Target.md', heading: 'Section Two', embed: true }, 'Board.canvas').path, 'Target.md');
  assert.equal(R.followTarget('', 'Index.md').type, 'none');
});

test('backlinks and the index cover markdown links and canvas nodes, not only wikilinks', () => {
  const files = all.filter(p => vi.INDEXED_EXT.includes(p.split('.').pop())).map(p => ({ path: p, text: fixture('links', p) }));
  const idx = vi.buildIndex(files, all);
  assert.deepEqual(idx.backlinks['Index.md'].sort(), ['Board.canvas', 'Target.md', 'notes/Deep Note.md', 'notes/sub/Leaf.md']);
  assert.deepEqual(idx.backlinks['Target.md'].sort(), ['Board.canvas', 'Index.md', 'notes/sub/Leaf.md']);
  assert.deepEqual(idx.backlinks['notes/Deep Note.md'].sort(), ['Board.canvas', 'Index.md']);
  assert.deepEqual(idx.backlinks['assets/pic.png'].sort(), ['Board.canvas', 'Index.md']);
  assert.deepEqual(idx.backlinks['notes/sub/Leaf.md'].sort(), ['Index.md', 'notes/Deep Note.md']);
  assert.deepEqual(Object.keys(idx.unresolved).sort(), ['Missing.md', 'Nowhere', 'missing/Gone.md']);
  const kinds = new Set(idx.notes['Index.md'].links.map(l => l.kind));
  for (const k of ['wikilink', 'wiki-embed', 'md-link', 'md-image']) assert.ok(kinds.has(k), k);
  // external addresses and same-file headings are links, but never backlinks
  assert.ok(!idx.notes['Index.md'].links.some(l => /^https?:/.test(l.target) || l.target === ''));
});

test('the renderer marks markdown destinations so they are decoded, and headings carry an anchor', async () => {
  const md = await src('markdown.ts');
  const html = md.renderMarkdown('## Section Two\n[a](./notes/Deep%20Note.md) [b](<notes/Deep Note.md>) [[Target#Section Two]] [c](#section-two)');
  assert.match(html, /<h2 data-anchor="section two">/);
  assert.match(html, /data-target="\.\/notes\/Deep%20Note\.md" data-href="1"/);
  assert.match(html, /data-target="notes\/Deep Note\.md" data-href="1"/);
  assert.match(html, /data-target="Target" data-heading="Section Two"/);
  assert.match(html, /data-target="#section-two" data-href="1"/);
});

test('one resolver: no other vault module resolves links on its own', async () => {
  const { readFileSync } = await import('node:fs');
  const { SRC } = await import('./helpers.mjs');
  const rd = f => readFileSync(join(SRC, f), 'utf8');
  for (const f of ['viewers.tsx', 'canvas-view.tsx', 'panes.tsx', 'VaultShell.tsx', 'file-mode/FileView.tsx', 'canvas-mode/CanvasMode.tsx', 'sidebar/Sidebar.tsx']) {
    assert.ok(!/resolveLink\s*\(|resolveTarget\s*\(/.test(rd(f)), `${f} does not resolve links itself`);
  }
  assert.match(rd('use-vault.ts'), /new LinkResolver\(allPaths\)/);
  assert.match(rd('vault-index.ts'), /return resolveTarget\(target, fromPath, allPaths\)/);
});
