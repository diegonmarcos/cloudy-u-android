// #1717 Configs > UI: font family, size and overall scale. Parse, clamp, snap, CSS variables.
import test from 'node:test';
import assert from 'node:assert/strict';
import { decl, src } from './helpers.mjs';

const up = await src('core/ui-prefs.ts');
const ui = decl.configs.ui;

test('the declaration has the shared pages, then UI, then Vault', () => {
  assert.deepEqual(decl.configs.pages.map(p => p.id), ['general', 'perms', 'about', 'ui', 'vault']);
  assert.equal(decl.configs.default_page, 'general');
  assert.ok(ui.font_families.length >= 3 && ui.font_families.every(f => f.id && f.label && f.stack));
});

test('defaults come from the declaration', () => {
  assert.deepEqual(up.defaultUiPrefs(ui), { family: ui.font_families[0].id, size: ui.font_size.default, scale: ui.scale.default });
  assert.deepEqual(up.parseUiPrefs(null, ui), up.defaultUiPrefs(ui));
  assert.deepEqual(up.parseUiPrefs('not json', ui), up.defaultUiPrefs(ui));
  assert.deepEqual(up.parseUiPrefs('[1]', ui), up.defaultUiPrefs(ui));
});

test('values are clamped to the declared range and snapped to the step', () => {
  const p = up.parseUiPrefs(JSON.stringify({ family: 'mono', size: 999, scale: 1 }), ui);
  assert.equal(p.family, 'mono');
  assert.equal(p.size, ui.font_size.max);
  assert.equal(p.scale, ui.scale.min);
  assert.equal(up.parseUiPrefs(JSON.stringify({ scale: 103 }), ui).scale, 105);
  assert.equal(up.parseUiPrefs(JSON.stringify({ size: 'big' }), ui).size, ui.font_size.default);
  assert.equal(up.parseUiPrefs(JSON.stringify({ family: 'comic' }), ui).family, ui.font_families[0].id);
});

test('round trip', () => {
  const p = { family: 'serif', size: 18, scale: 120 };
  assert.deepEqual(up.parseUiPrefs(up.serializeUiPrefs(p), ui), p);
});

test('the CSS variables carry the family stack, the size, proportional UI sizes and the scale', () => {
  const v = up.uiCssVars({ family: 'mono', size: 20, scale: 125 }, ui);
  assert.equal(v['--font-interface'], ui.font_families.find(f => f.id === 'mono').stack);
  assert.equal(v['--font-text'], v['--font-interface']);
  assert.equal(v['--font-text-size'], '20px');
  assert.equal(v['--font-ui-medium'], '18.8px');
  assert.equal(v['--cnv-scale'], '1.25');
  const d = up.uiCssVars(up.defaultUiPrefs(ui), ui);
  assert.equal(d['--font-ui-medium'], '15px');
  assert.equal(d['--cnv-scale'], '1');
});
