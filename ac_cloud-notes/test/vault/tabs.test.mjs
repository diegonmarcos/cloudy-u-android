// #1717 TabStore: order, active tab, history, persistence across restarts, and the view modes.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decl, src, SRC } from './helpers.mjs';

const ts = await src('core/tab-store.ts');
const store = () => {
  const m = new Map();
  return { getItem: k => (m.has(k) ? m.get(k) : null), setItem: (k, v) => void m.set(k, String(v)), _m: m };
};
const open = (s, ...paths) => paths.reduce((acc, p) => ts.openTab(acc, decl, p, decl.layout.tab_max), s);
const paths = s => s.tabs.map(t => t.path);

test('tabs open in order, the last opened is active, reopening activates without duplicating', () => {
  let s = open(ts.EMPTY_TABS, 'a.md', 'b.canvas', 'c.json');
  assert.deepEqual(paths(s), ['a.md', 'b.canvas', 'c.json']);
  assert.equal(s.active, 'c.json');
  s = ts.openTab(s, decl, 'a.md', decl.layout.tab_max);
  assert.deepEqual(paths(s), ['a.md', 'b.canvas', 'c.json']);
  assert.equal(s.active, 'a.md');
});

test('persistence: tabs, their order, views and the active tab survive a restart', () => {
  const st = store();
  let s = open(ts.EMPTY_TABS, 'notes/a.md', 'board.canvas', 'data.json', 'page.html');
  s = ts.patchTab(s, 'notes/a.md', { view: 'source' });
  s = ts.patchTab(s, 'page.html', { cdn: true });
  s = ts.moveTab(s, 3, 0);
  s = ts.activateTab(s, 'board.canvas');
  ts.saveTabs(st, '/storage/emulated/0/vault', s);
  // "restart": a new state read from storage only
  const back = ts.loadTabs(st, '/storage/emulated/0/vault', decl, decl.layout.tab_max);
  assert.deepEqual(paths(back), ['page.html', 'notes/a.md', 'board.canvas', 'data.json']);
  assert.equal(back.active, 'board.canvas');
  assert.equal(back.tabs.find(t => t.path === 'notes/a.md').view, 'source');
  assert.equal(back.tabs.find(t => t.path === 'page.html').cdn, true);
  assert.equal(back.tabs.find(t => t.path === 'data.json').view, 'tree');
  // each root keeps its own tabs
  assert.deepEqual(ts.loadTabs(st, 'store:git/other', decl, decl.layout.tab_max), ts.EMPTY_TABS);
});

test('the last mounted root is remembered so the tabs can be restored on start', () => {
  const st = store();
  assert.equal(ts.readLastVault(st), null);
  ts.writeLastVault(st, { root: 'content://tree/1', name: 'notes' });
  assert.deepEqual(ts.readLastVault(st), { root: 'content://tree/1', name: 'notes' });
  ts.writeLastVault(st, null);
  assert.equal(ts.readLastVault(st), null);
  st.setItem(ts.LAST_VAULT_KEY, '{bad');
  assert.equal(ts.readLastVault(st), null);
});

test('stored tabs that are malformed never throw and never restore garbage', () => {
  for (const raw of [null, '', 'not json', '[]', '{"tabs":"x"}', '{"tabs":[1,null,{"path":7},{"path":""}]}']) {
    assert.deepEqual(ts.parseTabs(raw, decl, 12), ts.EMPTY_TABS, String(raw));
  }
  const s = ts.parseTabs(JSON.stringify({ tabs: [{ path: 'a.md', view: 'canvas' }, { path: 'a.md' }, { path: 'b.canvas', view: 'zzz' }], active: 'gone.md' }), decl, 12);
  assert.deepEqual(s.tabs, [{ path: 'a.md', view: 'preview', cdn: false }, { path: 'b.canvas', view: 'canvas', cdn: false }]);
  assert.equal(s.active, 'b.canvas');
  const many = { tabs: Array.from({ length: 40 }, (_, i) => ({ path: `n${i}.md` })), active: 'n39.md' };
  assert.equal(ts.parseTabs(JSON.stringify(many), decl, decl.layout.tab_max).tabs.length, decl.layout.tab_max);
  const full = { getItem: () => { throw new Error('blocked'); }, setItem: () => { throw new Error('full'); } };
  assert.doesNotThrow(() => ts.saveTabs(full, 'r', ts.EMPTY_TABS));
  assert.deepEqual(ts.loadTabs(full, 'r', decl, 12), ts.EMPTY_TABS);
});

test('closing a tab activates its neighbour; the tab limit drops the oldest', () => {
  let s = open(ts.EMPTY_TABS, 'a.md', 'b.md', 'c.md');
  s = ts.closeTab(s, 'c.md');
  assert.equal(s.active, 'b.md');
  s = ts.closeTab(s, 'a.md');
  assert.deepEqual(paths(s), ['b.md']);
  s = ts.closeTab(s, 'b.md');
  assert.equal(s.active, null);
  let t = ts.EMPTY_TABS;
  for (let i = 0; i < decl.layout.tab_max + 3; i++) t = ts.openTab(t, decl, `n${i}.md`, decl.layout.tab_max);
  assert.equal(t.tabs.length, decl.layout.tab_max);
  assert.equal(t.tabs[0].path, 'n3.md');
});

test('history: back and forward walk the tabs that were active', () => {
  let s = open(ts.EMPTY_TABS, 'a.md', 'b.canvas', 'c.md');
  assert.equal(ts.canGoBack(s), true);
  assert.equal(ts.canGoForward(s), false);
  s = ts.goBack(s);
  assert.equal(s.active, 'b.canvas');
  s = ts.goBack(s);
  assert.equal(s.active, 'a.md');
  assert.equal(ts.canGoBack(s), false);
  s = ts.goForward(s);
  assert.equal(s.active, 'b.canvas');
  s = ts.closeTab(s, 'c.md');
  assert.equal(ts.canGoForward(s), false);
  assert.equal(ts.goForward(s), s);
});

test('modes: .md opens in File mode, .canvas in Canvas mode', () => {
  assert.equal(ts.modeOfPath(decl, 'notes/a.md'), 'file');
  assert.equal(ts.modeOfPath(decl, 'board.canvas'), 'canvas');
  for (const p of ['d.json', 't.csv', 'c.yaml', 'p.html', 'g.mmd', 'i.png', 'x.txt', 'u.pdf']) assert.equal(ts.modeOfPath(decl, p), 'file', p);
  const s = open(ts.EMPTY_TABS, 'notes/a.md', 'board.canvas');
  const tab = p => s.tabs.find(t => t.path === p);
  assert.equal(ts.modeOfTab(decl, tab('notes/a.md')), 'file');
  assert.equal(ts.modeOfTab(decl, tab('board.canvas')), 'canvas');
  assert.equal(ts.modeOfTab(decl, null), null);
  assert.deepEqual(decl.layout.modes.map(m => m.id), ['file', 'canvas']);
});

test('modes: switching tabs switches the mode', () => {
  let s = open(ts.EMPTY_TABS, 'notes/a.md', 'board.canvas', 'data.json');
  const mode = () => ts.modeOfTab(decl, s.tabs.find(t => t.path === s.active));
  assert.equal(mode(), 'file');
  s = ts.activateTab(s, 'board.canvas');
  assert.equal(mode(), 'canvas');
  s = ts.activateTab(s, 'notes/a.md');
  assert.equal(mode(), 'file');
  s = ts.goBack(s);
  assert.equal(mode(), 'canvas');
});

test('the mode switch: a canvas can be shown as its JSON (File mode) and back; a note has no Canvas mode', () => {
  assert.deepEqual(ts.modesFor(decl, 'board.canvas'), ['canvas', 'file']);
  assert.deepEqual(ts.modesFor(decl, 'a.md'), ['file']);
  assert.equal(ts.viewForMode(decl, 'board.canvas', 'file'), 'source');
  assert.equal(ts.viewForMode(decl, 'board.canvas', 'canvas'), 'canvas');
  assert.equal(ts.viewForMode(decl, 'a.md', 'canvas'), null);
  let s = open(ts.EMPTY_TABS, 'board.canvas');
  s = ts.patchTab(s, 'board.canvas', { view: ts.viewForMode(decl, 'board.canvas', 'file') });
  assert.equal(ts.modeOfTab(decl, s.tabs[0]), 'file');
});

test('the two modes are separate modules that share only the vault API', () => {
  const rd = f => readFileSync(join(SRC, f), 'utf8');
  const imports = f => [...rd(f).matchAll(/from '([^']+)'/g)].map(m => m[1]);
  const file = ['file-mode/FileView.tsx', 'file-mode/properties.ts', 'viewers.tsx'];
  const canvas = ['canvas-mode/CanvasMode.tsx', 'canvas-mode/canvas-history.ts', 'canvas-view.tsx'];
  for (const f of file) assert.ok(!imports(f).some(i => /canvas-mode|canvas-view|jsoncanvas/.test(i)), `${f} imports nothing of Canvas mode`);
  for (const f of canvas) assert.ok(!imports(f).some(i => /file-mode|viewers|json-tree|html-sandbox/.test(i)), `${f} imports nothing of File mode`);
  const shell = rd('VaultShell.tsx');
  assert.match(shell, /mode === 'canvas' \? <CanvasMode tab=\{tab\} api=\{api\} \/> : <FileView tab=\{tab\} api=\{api\} decl=\{decl\} \/>/);
  assert.match(shell, /data-testid="mode-switch"/);
  assert.match(rd('file-mode/FileView.tsx'), /data-mode="file"/);
  assert.match(rd('canvas-mode/CanvasMode.tsx'), /data-mode="canvas"/);
  assert.match(rd('canvas-mode/CanvasMode.tsx'), /data-rail="undo"[\s\S]*data-rail="redo"/);
});

test('canvas history: undo and redo of the board, bounded', async () => {
  const ch = await src('canvas-mode/canvas-history.ts');
  let h = ch.EMPTY_HISTORY;
  assert.equal(ch.undo(h, 'a'), null);
  h = ch.recordChange(h, 'a', 'b');
  h = ch.recordChange(h, 'b', 'c');
  assert.equal(ch.recordChange(h, 'c', 'c'), h);
  let r = ch.undo(h, 'c');
  assert.equal(r.text, 'b');
  r = ch.undo(r.history, 'b');
  assert.equal(r.text, 'a');
  assert.equal(ch.canUndo(r.history), false);
  r = ch.redo(r.history, 'a');
  assert.equal(r.text, 'b');
  assert.equal(ch.canRedo(ch.recordChange(r.history, 'b', 'x')), false);
  for (let i = 0; i < 300; i++) h = ch.recordChange(h, 's' + i, 's' + (i + 1));
  assert.ok(h.past.length <= 100);
  assert.ok(ch.zoomIn(4) <= ch.ZOOM_MAX && ch.zoomOut(0.1) >= ch.ZOOM_MIN && ch.zoomIn(1) > 1 && ch.zoomOut(1) < 1);
});

test('properties card: typed rows, Empty placeholders, add property', async () => {
  const pr = await src('file-mode/properties.ts');
  const note = '---\ncreated: 2026-03-08 14:51\nparent:\nchild:\ntags:\ncount: 3\ndone: true\n---\nBody';
  const { present, rows } = pr.propertyRows(note);
  assert.equal(present, true);
  assert.deepEqual(rows.map(r => [r.key, r.type, r.empty]), [
    ['created', 'date', false], ['parent', 'text', true], ['child', 'text', true], ['tags', 'tags', true], ['count', 'number', false], ['done', 'checkbox', false],
  ]);
  assert.equal(rows[0].value, '2026-03-08 14:51');
  assert.deepEqual(pr.propertyRows('No frontmatter'), { present: false, rows: [] });
  const added = pr.addProperty(note, 'status', 'draft');
  assert.match(added.text, /done: true\nstatus: draft\n---\nBody$/);
  assert.deepEqual(pr.propertyRows(added.text).rows.at(-1), { key: 'status', type: 'text', value: 'draft', empty: false });
  assert.equal(pr.addProperty('Body only', 'k', 'v').text, '---\nk: v\n---\nBody only');
  assert.ok('error' in pr.addProperty(note, 'tags', 'x'));
  assert.ok('error' in pr.addProperty(note, 'a:b', 'x'));
  assert.ok('error' in pr.addProperty(note, '  ', 'x'));
});
