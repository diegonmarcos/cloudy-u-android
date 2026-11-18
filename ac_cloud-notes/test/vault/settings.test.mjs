// #1717 the Settings screen: every declared page has a body, the shell wires it, and the UI page's controls apply.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { APP, decl, SRC } from './helpers.mjs';

const pane = readFileSync(join(SRC, 'settings/SettingsPane.tsx'), 'utf8');
const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');

test('the Configs strip: the shared fleet tabs open the Android screen, the app pages have a body (vault is the default branch)', () => {
  const fleet = decl.configs.pages.filter(p => p.native !== undefined).map(p => [p.id, p.native]);
  assert.deepEqual(fleet, [['general', 'general'], ['perms', 'perms'], ['agents', 'agents'], ['logcat', 'logcat'], ['xray', 'xray'], ['about', 'about']]);
  assert.deepEqual(decl.configs.pages.filter(p => p.native === undefined).map(p => p.id), ['notes', 'ui', 'sources', 'vault']);
  for (const p of decl.configs.pages.filter(x => x.native === undefined && x.id !== 'vault')) assert.ok(pane.includes(`case '${p.id}':`), `page ${p.id}`);
  assert.match(pane, /h\.openFleetConfigsPage\(p\.native\)/);
  assert.equal(decl.configs.default_page, 'notes');
  const kt = readFileSync(join(APP, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/NotesShell.kt'), 'utf8');
  assert.match(kt, /fun openFleetConfigsPage\(id: String\)/);
  assert.match(kt, /startId = fleetConfigsStart/);
  assert.match(pane, /default:\s*\n\s*return <ConfigsPane/);
});

test('the pages keep what each carried: Sources reuses the Source grant panel, Vault keeps the .obsidian reader', () => {
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

test('reading typography: the note stylesheet states paragraph, list, heading, rule and table spacing (the app reset zeroes margins)', () => {
  const css = readFileSync(join(SRC, 'style.ts'), 'utf8');
  for (const rule of ['.cnv-note p{margin:0 0 .9em}', '.cnv-note ul,.cnv-note ol{margin:0 0 .9em;padding-left:1.6em}', '.cnv-note hr{', '.cnv-note table{display:block', '.cnv-note pre.mermaid{']) {
    assert.ok(css.includes(rule), rule);
  }
  assert.match(css, /\.cnv-note\{max-width:700px;margin:0 auto;padding:12px 16px 0/);
});

test('the native top bar opens the vault Settings screen; the AFFiNE settings dialog is gone', () => {
  assert.match(shell, /addEventListener\('cloud-notes:open-settings', open\)/);
  assert.match(shell, /const open = \(\) => setSpecial\('configs'\)/);
  const bridge = readFileSync(join(SRC, '..', 'components/native-nav-bridge.tsx'), 'utf8');
  assert.ok(!bridge.includes("open('setting'"), 'the bridge no longer opens the dialog');
  assert.ok(!readFileSync(join(SRC, '..', 'dialogs/index.tsx'), 'utf8').includes('SettingDialog'));
});

test('the old app bars (page drawer, section bottom nav) are never brought back by the vault: no menu entry, the chrome is always the vault', () => {
  assert.ok(!shell.includes('@app-bars') && !shell.includes('@home') && !shell.includes('openSection'));
  assert.match(shell, /setVaultChrome\?\.\(true\)/);
  assert.ok(!shell.includes('setVaultChrome?.(!'), 'the chrome is not toggled by a user flag');
});

test('an empty first launch opens the Source picker, but never while the last vault is still being restored', () => {
  const hook = readFileSync(join(SRC, 'use-vault.ts'), 'utf8');
  assert.match(hook, /const \[restoring, setRestoring\] = useState\(\(\) => !!native && readLastVault\(storage\) !== null\)/);
  assert.match(hook, /openRoot\(last\.root, last\.name\)\.finally\(\(\) => setRestoring\(false\)\)/);
  assert.match(shell, /if \(!api\.vault && !api\.restoring\) setLeftOpen\(true\)/);
  assert.match(shell, /mounted\.current = api\.vault !== null \|\| api\.restoring/);
});
