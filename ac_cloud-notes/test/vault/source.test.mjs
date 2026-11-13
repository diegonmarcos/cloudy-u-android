// #1717 VaultSource: the roots the sidebar's Source section offers, pinned to Cloud Drive's
// editor explorer roots (#1714) so the two root pickers cannot drift, and the native side that answers them.
import test from 'node:test';
import assert from 'node:assert/strict';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { APP, decl, src, SRC } from './helpers.mjs';

const vs = await src('core/vault-source.ts');
const REPO = join(APP, '..');
const drivePath = join(REPO, 'ac_cloud-drive', 'build.json');
const KT = readFileSync(join(APP, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/plugin/VaultPlugin.kt'), 'utf8');

test('the declared roots: Emulated, Termux home, Nix home, External drive, in that order', () => {
  assert.deepEqual(decl.sources.roots.map(r => r.id), ['emulated', 'termux', 'nix', 'external']);
  assert.deepEqual(decl.sources.roots.map(r => r.label), ['Emulated', 'Termux home', 'Nix home', 'External drive']);
  assert.deepEqual(Object.keys(decl.sources.reasons).sort(), [...vs.SOURCE_REASONS].sort());
  assert.equal(decl.sources.recent_files_max, 30);
});

test('same roots as the Cloud Drive editor explorer (ids, labels, icons, kinds, order) and same reason names', () => {
  assert.ok(existsSync(drivePath), 'ac_cloud-drive/build.json is in the checkout');
  const drive = JSON.parse(readFileSync(drivePath, 'utf8')).ui.manage.overview.local;
  const pick = r => ({ id: r.id, label: r.label, icon: r.icon, kind: r.kind, ...(r.fleet ? { fleet: r.fleet } : {}) });
  assert.deepEqual(decl.sources.roots.map(pick), drive.map(pick));
  const roots = readFileSync(join(REPO, 'ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/editor/EditorRoots.kt'), 'utf8');
  const reasons = /enum class Reason \{([^}]+)\}/.exec(roots)[1].split(',').map(s => s.trim()).filter(Boolean);
  assert.deepEqual([...vs.SOURCE_REASONS].sort(), reasons.sort());
});

test('an accessible root carries its places; an inaccessible one stays listed with its reason and grant action', () => {
  const roots = vs.resolveSources(decl, [
    { id: 'emulated', places: [{ label: 'Emulated', root: '/storage/emulated/0' }], reason: null },
    { id: 'termux', places: [], reason: 'APP_NOT_INSTALLED' },
    { id: 'nix', places: [], reason: 'NOT_READABLE' },
    { id: 'external', places: [], reason: 'NO_DRIVE' },
  ]);
  assert.deepEqual(roots.map(r => r.id), ['emulated', 'termux', 'nix', 'external']);
  assert.deepEqual(roots.map(r => r.accessible), [true, false, false, false]);
  assert.equal(roots[0].reason, null);
  assert.equal(roots[0].reasonText, '');
  assert.deepEqual(roots.slice(1).map(r => [r.reason, r.action]), [['APP_NOT_INSTALLED', 'none'], ['NOT_READABLE', 'pick_folder'], ['NO_DRIVE', 'pick_folder']]);
  assert.match(roots[1].reasonText, /Termux home .*not installed/);
  assert.match(roots[2].reasonText, /^Nix home is private/);
  for (const r of roots.slice(1)) assert.ok(r.reasonText.length > 10 && !r.reasonText.includes('{label}'));
  const noGrant = vs.resolveSources(decl, [{ id: 'emulated', places: [], reason: 'NO_STORAGE_GRANT' }])[0];
  assert.deepEqual([noGrant.reason, noGrant.action, noGrant.actionLabel], ['NO_STORAGE_GRANT', 'grant_storage', 'Grant access']);
});

test('missing, malformed or absent native answers never throw and never drop a root', () => {
  for (const answers of [[], [null], [{ id: 'emulated' }], [{ id: 'emulated', places: [{ label: 'x' }], reason: 'weird' }], [{ id: 'unknown', places: [] }]]) {
    const roots = vs.resolveSources(decl, answers);
    assert.equal(roots.length, 4);
    assert.ok(roots.every(r => !r.accessible && r.reason === 'NOT_READABLE'), JSON.stringify(answers));
  }
  const preview = vs.resolveSources(decl, null);
  assert.equal(preview.length, 4);
  assert.ok(preview.every(r => r.reasonText === decl.sources.no_native && r.action === 'none'));
  assert.deepEqual(vs.resolveSources({}, []), []);
});

test('the mounted root is matched back to its source', () => {
  const roots = vs.resolveSources(decl, [
    { id: 'emulated', places: [{ label: 'Emulated', root: '/storage/emulated/0' }] },
    { id: 'termux', places: [{ label: 'Shared store', root: 'store:' }] },
    { id: 'external', places: [{ label: 'USB', root: '/storage/1A2B-3C4D' }] },
  ]);
  assert.equal(vs.sourceOfRoot(roots, '/storage/emulated/0/Documents/notes').id, 'emulated');
  assert.equal(vs.sourceOfRoot(roots, '/storage/emulated/0').id, 'emulated');
  assert.equal(vs.sourceOfRoot(roots, 'store:git/notes').id, 'termux');
  assert.equal(vs.sourceOfRoot(roots, '/storage/1A2B-3C4D/vault').id, 'external');
  assert.equal(vs.sourceOfRoot(roots, 'content://com.android.externalstorage.documents/tree/x'), null);
  assert.equal(vs.sourceOfRoot(roots, '/storage/emulated/01'), null);
});

test('the native side answers every declared root and performs the grant actions', () => {
  assert.match(KT, /@PluginMethod\s+fun roots\(call/);
  assert.match(KT, /@PluginMethod\s+fun openApp\(call/);
  for (const r of decl.sources.roots) assert.ok(KT.includes(`"${r.id}"`), `VaultPlugin answers root ${r.id}`);
  const owners = decl.sources.roots.filter(x => x.owner).map(x => x.owner.package);
  assert.deepEqual(owners, ['cld.termux', 'cld.termux.nix']);
  for (const pkg of owners) assert.ok(KT.includes(`"${pkg}"`), `VaultPlugin knows ${pkg}`);
  for (const reason of vs.SOURCE_REASONS) assert.ok(KT.includes(`"${reason}"`), `VaultPlugin can answer ${reason}`);
  const man = readFileSync(join(APP, 'packages/frontend/apps/android/App/app/src/main/AndroidManifest.xml'), 'utf8');
  for (const pkg of owners) assert.ok(man.includes(`<package android:name="${pkg}"`), `the manifest can see ${pkg}`);
  assert.match(KT, /storageVolumes/);
});

test('the Source section lives in the left sidebar; the floating open-file button is gone and its import moved there', () => {
  const rd = f => readFileSync(join(SRC, f), 'utf8');
  assert.match(rd('sidebar/Sidebar.tsx'), /<SourcePanel api=\{api\} decl=\{decl\}/);
  const panel = rd('sidebar/SourcePanel.tsx');
  assert.match(panel, /data-testid="source-reason"/);
  assert.match(panel, /api\.grantSource\(r\)/);
  assert.match(panel, /new Event\('cloud-notes:open-file'\)/);
  const act = readFileSync(join(APP, 'packages/frontend/apps/android/App/app/src/main/java/app/cloudnotes/pro/MainActivity.kt'), 'utf8');
  assert.ok(!/FloatingActionButton/.test(act), 'MainActivity draws no floating button');
  const app = readFileSync(join(APP, 'packages/frontend/apps/android/src/app.tsx'), 'utf8');
  assert.match(app, /addEventListener\('cloud-notes:open-file'/);
});
