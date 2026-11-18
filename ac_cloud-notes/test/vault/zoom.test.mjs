// Ticket 1788: pinch zoom. The numbers a two-finger gesture turns into, the per-tab persistence, and the wiring.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decl, SRC, src } from './helpers.mjs';

const z = await src('core/zoom.ts');
const ts = await src('core/tab-store.ts');

test('ranges: text 0.6 to 3, a board 0.1 to 4', () => {
  assert.deepEqual(z.TEXT_ZOOM, { min: 0.6, max: 3 });
  assert.deepEqual(z.CANVAS_ZOOM, { min: 0.1, max: 4 });
});

test('pinch: the zoom follows the ratio of the finger distance and is clamped', () => {
  assert.equal(z.pinchZoom(100, 200, 1, z.TEXT_ZOOM), 2);
  assert.equal(z.pinchZoom(100, 50, 1, z.TEXT_ZOOM), 0.6);   // not below the minimum
  assert.equal(z.pinchZoom(100, 900, 1, z.TEXT_ZOOM), 3);    // not above the maximum
  assert.equal(z.pinchZoom(100, 150, 2, z.TEXT_ZOOM), 3);    // starts from the current zoom
  assert.equal(z.pinchZoom(0, 150, 1.5, z.TEXT_ZOOM), 1.5);  // a degenerate start changes nothing
});

test('steps (menu): in and out by 1.2, rounded, clamped; junk becomes 1', () => {
  assert.equal(z.stepZoom(1, 'in', z.TEXT_ZOOM), 1.2);
  assert.equal(z.stepZoom(1.2, 'in', z.TEXT_ZOOM), 1.44);
  assert.equal(z.stepZoom(0.65, 'out', z.TEXT_ZOOM), 0.6);
  assert.equal(z.stepZoom(2.9, 'in', z.TEXT_ZOOM), 3);
  assert.equal(z.clampZoom(NaN, z.TEXT_ZOOM), 1);
  assert.equal(z.storedZoom('big', z.TEXT_ZOOM), 1);
  assert.equal(z.storedZoom(99, z.TEXT_ZOOM), 3);
});

test('the zoom is kept per tab and survives a restart; a canvas keeps its own range', () => {
  const open = (s, ...paths) => paths.reduce((acc, p) => ts.openTab(acc, decl, p, decl.layout.tab_max), s);
  let s = open(ts.EMPTY_TABS, 'a.md', 'b.md', 'board.canvas');
  assert.ok(s.tabs.every(t => t.zoom === 1));
  s = ts.patchTab(s, 'a.md', { zoom: 1.8 });
  s = ts.patchTab(s, 'board.canvas', { zoom: 0.25 });
  const st = { v: null, getItem() { return this.v; }, setItem(k, x) { this.v = x; } };
  ts.saveTabs(st, 'root', s);
  const back = ts.loadTabs(st, 'root', decl, 12);
  const zoomOf = p => back.tabs.find(t => t.path === p).zoom;
  assert.equal(zoomOf('a.md'), 1.8);
  assert.equal(zoomOf('b.md'), 1);          // another tab is untouched
  assert.equal(zoomOf('board.canvas'), 0.25);
  // junk and out-of-range values are repaired, not trusted
  const bad = ts.parseTabs(JSON.stringify({ tabs: [{ path: 'a.md', zoom: 'x' }, { path: 'b.md', zoom: 50 }, { path: 'board.canvas', zoom: 0.01 }] }), decl, 12);
  assert.deepEqual(bad.tabs.map(t => t.zoom), [1, 3, 0.1]);
});

test('wiring: Raw and View pinch-scale the text (not Edit), a canvas zooms its board, the menu zooms too', () => {
  const rd = f => readFileSync(join(SRC, f), 'utf8');
  const file = rd('file-mode/FileView.tsx');
  assert.match(file, /usePinch\(box, tab\.zoom, TEXT_ZOOM/);
  assert.match(file, /const zoomable = tab\.mode !== 'edit'/);
  assert.match(file, /api\.patchTab\(tab\.path, \{ zoom: z \}\)/);
  const pinch = rd('use-pinch.ts');
  assert.match(pinch, /e\.touches\.length === 2/);
  assert.match(pinch, /passive: false/);
  const canvas = rd('canvas-mode/CanvasMode.tsx');
  assert.match(canvas, /zoom=\{tab\.zoom\}/);
  assert.match(canvas, /onZoom=\{k => api\.patchTab\(tab\.path, \{ zoom: k \}\)\}/);
  const shell = rd('VaultShell.tsx');
  for (const id of ['zoom-in', 'zoom-out', 'zoom-reset']) assert.ok(shell.includes(`id: '${id}'`) && shell.includes(`'${id}'`), id);
});

test('the toolbar: Raw | Edit | View are icon-only with their names as labels, and the separate Pen button is gone (owner order, ticket 1788)', () => {
  const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');
  assert.match(shell, /aria-label=\{m\.label\} onClick=\{\(\) => tab && api\.setMode\(tab\.path, m\.id\)\}>\s*<Icon name=\{m\.icon\} size=\{18\} \/>\s*<\/button>/);
  assert.ok(!shell.includes('data-testid="mode-toggle"'), 'no Pen / toggle button in the bar');
  assert.ok(!shell.includes('{mode === m.id ? m.label : null}'), 'no visible text on the mode buttons');
  assert.match(shell, /id: 'toggle-mode'/); // the command and the menu entry stay
  assert.match(shell, /data-testid="more-button"/); // the three-dots overflow stays
});
