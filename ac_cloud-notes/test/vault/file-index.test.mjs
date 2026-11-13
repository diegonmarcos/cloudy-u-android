// #1717 FileIndex: tree rows, recent-30, filters, order-by, collapse / expand, counts.
import test from 'node:test';
import assert from 'node:assert/strict';
import { src } from './helpers.mjs';

const fi = await src('core/file-index.ts');
const f = (path, mtime = 0, size = 1) => ({ path, name: path.split('/').pop(), isDir: false, size, mtime });
const d = (path, mtime = 0) => ({ path, name: path.split('/').pop(), isDir: true, size: 0, mtime });
const entries = [
  d('b-folder'), d('a-folder'), d('a-folder/inner'), d('.obsidian'), d('node_modules'), d('empty'),
  f('zeta.md', 50), f('Alpha.md', 10), f('note10.md', 30), f('note2.md', 20),
  f('board.canvas', 90), f('data.json', 70), f('table.csv', 60), f('conf.yaml', 40), f('pic.png', 80), f('README', 5),
  f('a-folder/one.md', 100), f('a-folder/inner/deep.canvas', 110), f('a-folder/inner/deep.json', 15),
  f('b-folder/two.md', 25), f('.obsidian/app.json', 999), f('node_modules/x.md', 998), f('.hidden.md', 997),
];
const base = { filter: 'all', order: 'name', expanded: new Set(), showHidden: false, skipDirs: ['.git', 'node_modules', '.trash'] };
const idx = new fi.FileIndex(entries, true);
const names = rows => rows.map(r => r.entry.path);

test('tree: folders first then files, natural name order, hidden and skipped folders left out', () => {
  assert.deepEqual(names(idx.rows(base)), [
    'a-folder', 'b-folder', 'empty',
    'Alpha.md', 'board.canvas', 'conf.yaml', 'data.json', 'note2.md', 'note10.md', 'pic.png', 'README', 'table.csv', 'zeta.md',
  ]);
});

test('expanding a folder shows its children one level deeper; collapse all closes everything', () => {
  const open = idx.rows({ ...base, expanded: new Set(['a-folder', 'a-folder/inner']) });
  assert.deepEqual(names(open).slice(0, 5), ['a-folder', 'a-folder/inner', 'a-folder/inner/deep.canvas', 'a-folder/inner/deep.json', 'a-folder/one.md']);
  assert.deepEqual(open.slice(0, 5).map(r => r.depth), [0, 1, 2, 2, 1]);
  assert.equal(open[0].expanded, true);
  assert.equal(fi.collapseAll().size, 0);
  assert.equal(idx.rows({ ...base, expanded: fi.collapseAll() }).every(r => r.depth === 0), true);
});

test('expand all opens every visible folder (not hidden or skipped ones)', () => {
  const all = idx.expandAll(base);
  assert.deepEqual([...all].sort(), ['a-folder', 'a-folder/inner', 'b-folder', 'empty']);
  const rows = idx.rows({ ...base, expanded: all });
  assert.ok(names(rows).includes('a-folder/inner/deep.canvas') && names(rows).includes('b-folder/two.md'));
  assert.deepEqual([...idx.expandAll({ ...base, showHidden: true })].sort(), ['.obsidian', 'a-folder', 'a-folder/inner', 'b-folder', 'empty']);
});

test('order by modified time: newest first, a folder by the newest file below it', () => {
  const rows = idx.rows({ ...base, order: 'mtime' });
  assert.deepEqual(names(rows), [
    'a-folder', 'b-folder', 'empty',
    'board.canvas', 'pic.png', 'data.json', 'table.csv', 'zeta.md', 'conf.yaml', 'note10.md', 'note2.md', 'Alpha.md', 'README',
  ]);
  assert.deepEqual(fi.ORDERS.map(o => o.id), ['name', 'mtime']);
});

test('filters: only .canvas, only .md, only data files, all', () => {
  assert.deepEqual(fi.FILE_FILTERS.map(x => x.id).sort(), ['all', 'canvas', 'data', 'md']);
  const all = new Set(idx.expandAll(base));
  const shown = filter => names(idx.rows({ ...base, filter, expanded: all }));
  assert.deepEqual(shown('canvas'), ['a-folder', 'a-folder/inner', 'a-folder/inner/deep.canvas', 'board.canvas']);
  assert.deepEqual(shown('md'), ['a-folder', 'a-folder/one.md', 'b-folder', 'b-folder/two.md', 'Alpha.md', 'note2.md', 'note10.md', 'zeta.md']);
  assert.deepEqual(shown('data'), ['a-folder', 'a-folder/inner', 'a-folder/inner/deep.json', 'conf.yaml', 'data.json', 'table.csv']);
  assert.equal(shown('all').length, idx.rows({ ...base, expanded: all }).length);
  for (const [p, kind] of [['a.json', 'data'], ['a.csv', 'data'], ['a.yaml', 'data'], ['a.yml', 'data'], ['a.toml', 'data'], ['a.md', 'md'], ['a.canvas', 'canvas']]) {
    assert.ok(fi.matchesFilter(p, kind), `${p} is ${kind}`);
  }
  assert.ok(!fi.matchesFilter('a.md', 'data') && !fi.matchesFilter('a.png', 'md'));
});

test('a filter keeps unlisted folders until the walk is complete', () => {
  const partial = new fi.FileIndex(entries, false);
  assert.ok(names(partial.rows({ ...base, filter: 'canvas' })).includes('empty'));
  assert.ok(!names(idx.rows({ ...base, filter: 'canvas' })).includes('empty'));
});

test('recent: the 30 most recently modified files across all folders, newest first', () => {
  const many = [...entries];
  for (let i = 0; i < 40; i++) many.push(f(`bulk/n${i}.md`, 1000 + i));
  const big = new fi.FileIndex(many, true);
  const recent = big.recent(base);
  assert.equal(fi.RECENT_MAX, 30);
  assert.equal(recent.length, 30);
  assert.deepEqual(recent.slice(0, 3).map(e => e.path), ['bulk/n39.md', 'bulk/n38.md', 'bulk/n37.md']);
  assert.ok(recent.every((e, i) => i === 0 || recent[i - 1].mtime >= e.mtime), 'descending by mtime');
  assert.ok(recent.every(e => !e.isDir));
  // across folders, hidden and skipped trees stay out
  assert.deepEqual(idx.recent(base).slice(0, 4).map(e => e.path), ['a-folder/inner/deep.canvas', 'a-folder/one.md', 'board.canvas', 'pic.png']);
  assert.ok(!idx.recent(base).some(e => e.path.startsWith('.obsidian') || e.path.startsWith('node_modules') || e.name.startsWith('.')));
  // the filter applies to the recent list too
  assert.deepEqual(idx.recent({ ...base, filter: 'canvas' }).map(e => e.path), ['a-folder/inner/deep.canvas', 'board.canvas']);
  assert.equal(idx.recent(base, 2).length, 2);
});

test('counts: N files, M folders of the mounted root', () => {
  assert.deepEqual(idx.counts(base), { files: 14, folders: 4 });
  assert.deepEqual(idx.counts({ ...base, showHidden: true }), { files: 16, folders: 5 });
});

test('extension badges', () => {
  assert.equal(fi.extBadge('a/b/Note.md'), 'MD');
  assert.equal(fi.extBadge('board.canvas'), 'CANVAS');
  assert.equal(fi.extBadge('README'), '');
  assert.deepEqual(['x.md', 'x.canvas', 'x.json', 'x.png', 'x.html', 'x.txt', 'x.pdf'].map(fi.badgeKind), ['md', 'canvas', 'data', 'image', 'web', 'text', 'other']);
});

test('robust input: orphans get their folders, bad entries are skipped, prefs survive garbage', () => {
  const odd = new fi.FileIndex([f('x/y/z.md', 1), null, { path: '' }, undefined].filter(e => e !== undefined), true);
  assert.deepEqual(names(odd.rows({ ...base, expanded: new Set(['x', 'x/y']) })), ['x', 'x/y', 'x/y/z.md']);
  assert.deepEqual([...odd.reveal('x/y/z.md', new Set())].sort(), ['x', 'x/y']);
  assert.deepEqual(fi.parsePrefs('not json'), fi.DEFAULT_PREFS);
  assert.deepEqual(fi.parsePrefs(JSON.stringify({ view: 'recent', filter: 'md', order: 'mtime', expanded: ['a', 3] })), { view: 'recent', filter: 'md', order: 'mtime', expanded: ['a'] });
  assert.deepEqual(fi.parsePrefs(JSON.stringify({ view: 'nope', filter: 'zip', order: 'size' })), fi.DEFAULT_PREFS);
});
