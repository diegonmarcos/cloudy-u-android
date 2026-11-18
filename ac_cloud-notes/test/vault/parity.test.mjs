// #1717 field parity (rule 16): the redesign reorganises, it removes nothing. Every command,
// ribbon action, pane, status item, file type, limit, API member, test id, label, canvas tool,
// native method and link kind of the previous version (fixtures/vault/parity-baseline.json,
// generated from the commit before the redesign) is still present. The ONE removal is the
// floating open-file button (an explicit owner instruction); what it did is asserted to be
// reachable from the sidebar's Source section.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { APP, decl, fixture, src, SRC } from './helpers.mjs';

const base = JSON.parse(fixture('parity-baseline.json'));
const walk = d => readdirSync(d, { withFileTypes: true }).flatMap(e => (e.isDirectory() ? walk(join(d, e.name)) : [join(d, e.name)]));
const files = walk(SRC).filter(f => /\.tsx?$/.test(f));
const ui = files.filter(f => f.endsWith('.tsx')).map(f => readFileSync(f, 'utf8')).join('\n');
const all = files.map(f => readFileSync(f, 'utf8')).join('\n');
const KT_DIR = join(APP, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro');
const kt = f => readFileSync(join(KT_DIR, f), 'utf8');

test('declaration: every command, ribbon action, pane, status item, file type and limit is kept', () => {
  const cmd = new Map(decl.commands.map(c => [c.id, c]));
  for (const c of base.commands) assert.deepEqual(cmd.get(c.id), c, `command ${c.id}`);
  assert.deepEqual(decl.layout.ribbon, base.ribbon);
  for (const p of base.left_panes) assert.ok(decl.layout.left_panes.includes(p), `left pane ${p}`);
  assert.deepEqual(decl.layout.right_panes, base.right_panes);
  assert.deepEqual(decl.layout.status, base.status);
  assert.equal(decl.layout.tab_max, base.tab_max);
  assert.equal(decl.layout.wide_breakpoint_px, base.wide_breakpoint_px);
  // #1717 the one owner-ordered change to the file types: XML leaves the plain-text handler for its own (it gets a View, the tree)
  const expected = base.file_types.map(t => (t.handler === 'text' ? { ...t, ext: t.ext.filter(e => e !== 'xml') } : t));
  const xml = decl.file_types.find(t => t.handler === 'xml');
  assert.ok(xml && xml.ext.includes('xml') && xml.views.includes('tree') && xml.views.includes('source'), 'xml handler');
  assert.deepEqual(decl.file_types.filter(t => t.handler !== 'xml'), expected);
  assert.deepEqual(decl.limits, base.limits);
  assert.deepEqual(decl.config_files, base.config_files);
  assert.deepEqual(decl.config_dirs, base.config_dirs);
  for (const [k, v] of Object.entries(base.open)) assert.deepEqual(decl.open[k], v, `open.${k}`);
  for (const [k, v] of Object.entries(base.html)) assert.deepEqual(decl.html[k], v, `html.${k}`);
  assert.deepEqual(decl.plugins.map(p => p.id), base.plugins);
});

test('every command is still executed by the shell and offered in a menu', () => {
  const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');
  for (const c of decl.commands) assert.ok(shell.includes(`case '${c.id}':`), `run() handles ${c.id}`);
  assert.match(shell, /decl\.commands\.map\(c => \(\{ id: c\.id, label: c\.label/);
  assert.match(shell, /<Palette mode=\{palette\} commands=\{decl\.commands\}/);
});

test('the vault API lost no member', () => {
  const hook = readFileSync(join(SRC, 'use-vault.ts'), 'utf8');
  const ret = hook.slice(hook.lastIndexOf('  return {'));
  for (const k of base.api) assert.match(ret, new RegExp(`[\\s{,]${k}\\s*[,:}\\n]`), `api.${k}`);
});

// the owner ordered the VaultView start screen removed (#1717): its four ids go, and what they did is in the Source section.
const START_SCREEN = ['vault-start', 'open-folder', 'path-input', 'vault-error'];
test('the start screen is gone; its open, path and error roles live in the Source section and the status bar', () => {
  for (const id of START_SCREEN) assert.ok(!ui.includes(`data-testid="${id}"`), `${id} is gone`);
  assert.ok(!existsSync(join(SRC, 'VaultView.tsx')) || !readFileSync(join(SRC, 'VaultView.tsx'), 'utf8').includes('cnv-start'));
  const panel = readFileSync(join(SRC, 'sidebar/SourcePanel.tsx'), 'utf8');
  for (const id of ['source-pick', 'source-path-input']) assert.ok(panel.includes(`data-testid="${id}"`), id);
  assert.match(panel, /api\.error &&/);
});

test('every test id, label and canvas tool of the previous UI is still rendered', () => {
  for (const id of base.testids.filter(i => !START_SCREEN.includes(i))) {
    const dynamic = id.endsWith('-');
    assert.ok(dynamic ? ui.includes('data-testid={`' + id) || ui.includes(`data-testid="${id}`) : ui.includes(`data-testid="${id}"`), `test id ${id}`);
  }
  const declText = JSON.stringify(decl);
  for (const l of base.labels) assert.ok(ui.includes(l) || declText.includes(`"${l}"`), `label ${l}`);
  const board = readFileSync(join(SRC, 'canvas-view.tsx'), 'utf8');
  for (const t of base.canvas_tools) assert.ok(board.includes(t), `canvas tool ${t}`);
});

test('exports: nothing dropped; the two that moved are named', () => {
  const moved = {
    'panes.tsx:FilesPane': ['sidebar/Sidebar.tsx', 'export const Sidebar', 'data-testid="files-pane"'],
    'viewers.tsx:CanvasPane': ['canvas-mode/CanvasMode.tsx', 'export const CanvasMode', '<CanvasView'],
  };
  for (const [file, names] of Object.entries(base.exports)) {
    const now = readFileSync(join(SRC, file), 'utf8');
    for (const n of names) {
      const m = moved[`${file}:${n}`];
      if (m) {
        const target = readFileSync(join(SRC, m[0]), 'utf8');
        assert.ok(target.includes(m[1]) && target.includes(m[2]), `${file}:${n} moved to ${m[0]}`);
      } else {
        assert.match(now, new RegExp(`export (const|function|class|async function) ${n}\\b`), `${file} still exports ${n}`);
      }
    }
  }
});

test('native: every plugin method and web event is kept', () => {
  const fsTs = readFileSync(join(SRC, 'vault-fs.ts'), 'utf8');
  const native = fsTs.slice(fsTs.indexOf('export interface VaultNative'), fsTs.indexOf('export interface VaultEntry'));
  for (const m of base.native_methods) assert.match(native, new RegExp(`\\b${m}\\(`), `VaultNative.${m}`);
  const vault = kt('plugin/VaultPlugin.kt');
  for (const m of base.kotlin_vault_methods) assert.match(vault, new RegExp(`@PluginMethod\\s+fun ${m}\\(`), `VaultPlugin.${m}`);
  const ext = kt('plugin/ExternalFilePlugin.kt');
  for (const m of base.kotlin_external_methods) assert.match(ext, new RegExp(`@PluginMethod\\s+fun ${m}\\(`), `ExternalFilePlugin.${m}`);
  for (const e of base.events) assert.ok(all.includes(e), `event ${e}`);
});

test('link kinds: every kind the previous version handled is handled, plus the ones it missed', async () => {
  const lr = await src('core/link-resolver.ts');
  for (const k of base.link_kinds) assert.ok(lr.LINK_KINDS.includes(k), `link kind ${k}`);
  for (const k of ['heading', 'canvas-link']) assert.ok(lr.LINK_KINDS.includes(k), `new link kind ${k}`);
});

test('the owner-ordered removals: the floating button, the file explorer and its import are gone; picking and typing a root stay in Source', () => {
  const act = kt('MainActivity.kt');
  assert.ok(!act.includes('setupOpenFileFab'), 'no floating open-file button');
  const panel = readFileSync(join(SRC, 'sidebar/SourcePanel.tsx'), 'utf8');
  assert.ok(!panel.includes('cloud-notes:open-file') && !panel.includes('source-import'), 'no import action in Source');
  for (const id of ['source-pick', 'source-path-input']) assert.ok(panel.includes(`data-testid="${id}"`), id);
  const app = readFileSync(join(APP, 'packages/frontend/apps/android/src/app.tsx'), 'utf8');
  for (const s of ['ExternalFileOpener', 'importVaultFolder', 'openExternalFile', 'Import as vault', 'listDir']) assert.ok(!app.includes(s), `app.tsx no longer has ${s}`);
  // open-with stays: the view-intent listener and the pending file landing as a vault tab
  for (const s of ['OpenWithListener', 'cloud-notes:view-intent', 'takePendingOpen', 'pushExternalDoc']) assert.ok(app.includes(s), `app.tsx keeps ${s}`);
});

test('the sidebar has every action the redesign asks for', () => {
  const side = readFileSync(join(SRC, 'sidebar/Sidebar.tsx'), 'utf8');
  for (const a of ['new-file', 'new-folder', 'order', 'collapse', 'recent', 'filter', 'new-canvas', 'hidden']) assert.ok(side.includes(`data-action="${a}"`), `action ${a}`);
  for (const id of ['view-picker', 'vault-counts', 'vault-gear', 'sidebar-actions', 'recent-pane', 'files-pane']) assert.ok(side.includes(`data-testid="${id}"`), id);
  assert.match(side, /\{counts\.files\} files, \{counts\.folders\} folders/);
  assert.match(side, /className="cnv-badge" data-kind=\{badgeKind\(path\)\}>\{extBadge\(path\)\}/);
  const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');
  for (const b of decl.layout.bottom_bar) assert.ok(shell.includes(`data-bar="${b}"`), `bottom bar ${b}`);
  for (const id of ['breadcrumb', 'top-pill', 'mode-switch', 'tab-switcher', 'tab-count', 'bottom-bar', 'sidebar-toggle']) assert.ok(shell.includes(`data-testid="${id}"`), id);
});
