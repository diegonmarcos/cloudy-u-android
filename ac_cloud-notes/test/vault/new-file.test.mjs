// Ticket 1788: New file always creates the note in Notes-Inbox at the root of the mounted source, whatever folder is focused.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decl, SRC, src } from './helpers.mjs';

const nf = await src('core/new-file.ts');

const memFs = (existing = []) => {
  const dirs = new Set(existing);
  const calls = [];
  return {
    dirs, calls,
    async ensure(p) { return dirs.has(p); },
    async createFolder(parent, name) { calls.push(['createFolder', parent, name]); const p = parent ? `${parent}/${name}` : name; dirs.add(p); return p; },
  };
};

test('the declared folder is Notes-Inbox', () => {
  assert.equal(decl.new_file_dir, 'Notes-Inbox');
});

test('the inbox is created at the root when it is missing', async () => {
  const fs = memFs(['Projects']);
  const r = await nf.ensureInbox(fs, decl.new_file_dir);
  assert.deepEqual(r, { path: 'Notes-Inbox', created: true });
  assert.deepEqual(fs.calls, [['createFolder', '', 'Notes-Inbox']]);
});

test('an existing inbox is reused, not created again', async () => {
  const fs = memFs(['Notes-Inbox']);
  const r = await nf.ensureInbox(fs, decl.new_file_dir);
  assert.deepEqual(r, { path: 'Notes-Inbox', created: false });
  assert.deepEqual(fs.calls, []);
});

test('file names: Untitled.md, then Untitled 1.md, Untitled 2.md', () => {
  assert.equal(nf.freeFileName(new Set(), 'Untitled', '.md'), 'Untitled.md');
  assert.equal(nf.freeFileName(new Set(['Untitled.md']), 'Untitled', '.md'), 'Untitled 1.md');
  assert.equal(nf.freeFileName(new Set(['Untitled.md', 'Untitled 1.md']), 'Untitled', '.md'), 'Untitled 2.md');
});

test('every way to make a new file goes through the inbox, and the focused folder is ignored', () => {
  const hook = readFileSync(join(SRC, 'use-vault.ts'), 'utf8');
  const body = hook.slice(hook.indexOf('const newNote = useCallback('), hook.indexOf('const newCanvas'));
  assert.match(body, /async \(_dir = ''\) =>/, 'the directory argument is accepted and ignored');
  assert.match(body, /ensureInbox\(vault\.fs, decl\.new_file_dir\)/);
  assert.match(body, /createFile\(inbox\.path, await freeName\(inbox\.path, 'Untitled', '\.md'\)\)/);
  const shell = readFileSync(join(SRC, 'VaultShell.tsx'), 'utf8');
  assert.match(shell, /case 'new-note': return void api\.newNote\(''\)/);
  const side = readFileSync(join(SRC, 'sidebar/Sidebar.tsx'), 'utf8');
  assert.match(side, /data-action="new-file" onClick=\{\(\) => void api\.newNote\(dir\)\}/); // the focused folder is passed and ignored
});
