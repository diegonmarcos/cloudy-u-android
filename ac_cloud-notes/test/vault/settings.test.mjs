// #1717 the Settings screen: every declared page has a body, the shell wires it, and the UI page's controls apply.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decl, SRC } from './helpers.mjs';

const pane = readFileSync(join(SRC, 'settings/SettingsPane.tsx'), 'utf8');
const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');

test('every declared Configs page has a body (the last one, vault, is the default branch)', () => {
  for (const p of decl.configs.pages.filter(x => x.id !== 'vault')) assert.ok(pane.includes(`case '${p.id}':`), `page ${p.id}`);
  assert.match(pane, /default:\s*\n\s*return <ConfigsPane/);
});

test('the pages keep what each carried: Perms reuses the Source grant panel, Vault keeps the .obsidian reader', () => {
  assert.match(pane, /<SourcePanel api=\{api\} decl=\{decl\} \/>/);
  assert.match(pane, /<ConfigsPane config=\{api\.config\}/);
});

test('the UI page drives family, size and scale, and the shell applies and persists them', () => {
  for (const id of ['ui-family', 'ui-size', 'ui-scale', 'ui-reset']) assert.ok(pane.includes(`data-testid="${id}"`), id);
  assert.match(shell, /style=\{uiVars as CSSProperties\}/);
  assert.match(shell, /localStorage\.setItem\(uiDecl\.storage_key, serializeUiPrefs\(next\)\)/);
  assert.match(shell, /<SettingsPane /);
  const css = readFileSync(join(SRC, 'style.ts'), 'utf8');
  assert.match(css, /\.cnv-root\{zoom:var\(--cnv-scale,1\)/);
});
