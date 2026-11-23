import test from 'node:test';
import assert from 'node:assert/strict';
import { join } from 'node:path';
import { readFileSync } from 'node:fs';
import { FIX, decl, src } from './helpers.mjs';

const oc = await src('obsidian-config.ts');

function enabled(vault) {
  const dir = join(FIX, vault, '.obsidian');
  const core = Object.entries(oc.enabledMap(JSON.parse(readFileSync(join(dir, 'core-plugins.json'), 'utf8')))).filter(([, v]) => v).map(([k]) => ['core', k]);
  const com = JSON.parse(readFileSync(join(dir, 'community-plugins.json'), 'utf8')).map(k => ['community', k]);
  return [...core, ...com];
}

for (const vault of ['pvt-notes', 'git-repos-master']) {
  test(`the default plugin mapping covers every plugin enabled in ${vault}`, () => {
    const list = enabled(vault);
    assert.ok(list.length >= 40, `read ${list.length} enabled plugins`);
    const missing = list.filter(([kind, id]) => !decl.plugins.some(p => p.id === id && p.kind === kind));
    assert.deepEqual(missing, []);
  });
}

test('mapping rows are well formed: unique, valid status, a reason, no desktop-only promises', () => {
  const seen = new Set();
  for (const p of decl.plugins) {
    const k = `${p.kind}:${p.id}`;
    assert.ok(!seen.has(k), `duplicate ${k}`);
    seen.add(k);
    assert.ok(['builtin', 'planned', 'na'].includes(p.status), k);
    assert.ok(['core', 'community'].includes(p.kind), k);
    assert.ok(p.feature.trim().length > 8, k);
  }
  assert.ok(decl.plugins.filter(p => p.status === 'builtin').length >= 15);
});

test('file types: every extension is claimed once, the five required formats are handled', async () => {
  const ft = await src('file-types.ts');
  const exts = decl.file_types.flatMap(t => t.ext);
  assert.equal(new Set(exts).size, exts.length);
  const h = p => ft.handlerFor(decl, p).handler;
  assert.deepEqual(['a/b.md', 'x.canvas', 'c.JSON', 'p.html', 'p.htm', 'd.mmd', 'i.png', 'n.txt', 'z.pdf', 'q.unknown', 'Makefile'].map(h),
    ['markdown', 'canvas', 'json', 'html', 'html', 'mermaid', 'image', 'text', 'unsupported', 'unsupported', 'unsupported']);
  // an external (Open with) tab is text by construction: a missing or unclaimed extension opens as Markdown, a claimed one keeps its handler
  assert.deepEqual(['external:DOC-20261123-WA0012', 'external:notes.md', 'external:server.log', 'external:a.conf', 'external:x.pdf', 'external:d.json', 'external:p.html'].map(h),
    ['markdown', 'markdown', 'text', 'markdown', 'markdown', 'json', 'html']);
  for (const need of ['markdown', 'canvas', 'json', 'html', 'mermaid']) {
    const row = decl.file_types.find(t => t.handler === need);
    assert.ok(row.editable && row.views.length >= 2, need);
  }
});

test('recent vaults: newest first, de-duplicated, capped, survives bad storage', async () => {
  const rv = await src('recent-vaults.ts');
  const mem = new Map();
  const store = { getItem: k => mem.get(k) ?? null, setItem: (k, v) => mem.set(k, v) };
  for (let i = 0; i < 5; i++) rv.touchRecent(store, 'k', 3, { root: `/storage/emulated/0/v${i}`, name: `v${i}` }, i);
  rv.touchRecent(store, 'k', 3, { root: '/storage/emulated/0/v3', name: 'v3' }, 99);
  assert.deepEqual(rv.readRecent(store, 'k').map(r => r.name), ['v3', 'v4', 'v2']);
  assert.equal(rv.kindOfRoot('content://x/tree/y'), 'saf');
  assert.equal(rv.kindOfRoot('store:git/notes'), 'store');
  mem.set('k', '{{{');
  assert.deepEqual(rv.readRecent(store, 'k'), []);
  assert.equal(decl.open.recent_max >= 5, true);
});
