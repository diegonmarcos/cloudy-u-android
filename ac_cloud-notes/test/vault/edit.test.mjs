// #1717 Edit mode: which files offer it, how the HTML frame is kept script-less, and that the page round-trips.
import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join } from 'node:path';
import { decl, SRC, src } from './helpers.mjs';

const ts = await src('core/tab-store.ts');
const he = await src('core/html-edit.ts');
const sb = await src('html-sandbox.ts');

test('Edit is offered for notes, pages and canvases only; the others stay Raw / View', () => {
  for (const p of ['a.md', 'a.html', 'a.canvas']) assert.ok(ts.modesFor(decl, p).includes('edit'), p);
  for (const p of ['a.json', 'a.xml', 'a.mmd', 'a.txt', 'a.png']) assert.ok(!ts.modesFor(decl, p).includes('edit'), p);
  assert.equal(ts.viewForMode(decl, 'a.md', 'edit'), 'preview');
  assert.equal(ts.viewForMode(decl, 'a.html', 'edit'), 'render');
});

test('the editing frame can be edited by the app and can never run a script', () => {
  assert.ok(decl.html.edit_sandbox.includes('allow-same-origin'));
  assert.ok(!decl.html.edit_sandbox.includes('allow-scripts'));
  assert.equal(he.editSandbox('allow-scripts allow-same-origin allow-forms'), 'allow-same-origin allow-forms');
  assert.ok(!he.editSandbox(decl.html.sandbox).includes('allow-scripts'), 'even the View sandbox loses allow-scripts');
  const pane = readFileSync(join(SRC, 'edit-mode/EditPane.tsx'), 'utf8');
  assert.match(pane, /sandbox=\{editSandbox\(decl\.html\.edit_sandbox\)\}/);
});

test('the CSP meta View adds is marked on the way in and gone on the way out; the doctype is kept', () => {
  const page = '<!DOCTYPE html>\n<html><head><title>t</title></head><body><p>hi</p></body></html>\n';
  const frame = sb.buildSandboxFrame(page, decl.html, false);
  const marked = he.markInjected(frame.srcdoc);
  assert.match(marked, /<meta data-cnv-injected="1" http-equiv="Content-Security-Policy"/);
  // what the browser hands back after an edit: documentElement without a doctype, our meta first in the head
  const edited = marked.replace(/^<!DOCTYPE html>\s*/i, '').replace('<p>hi</p>', '<p>hi <b>there</b></p>');
  const out = he.toFileText(page, edited.trim());
  assert.ok(out.startsWith('<!DOCTYPE html>\n<html>'));
  assert.ok(!out.includes('Content-Security-Policy') && !out.includes('data-cnv-injected'));
  assert.ok(out.includes('<p>hi <b>there</b></p>'));
  // a page without a doctype is not given one
  assert.ok(!he.toFileText('<p>x</p>', '<html><body><p>x</p></body></html>').includes('DOCTYPE'));
});

test('the Markdown editor writes the frontmatter back byte for byte and wires the formatting toolbar', () => {
  const pane = readFileSync(join(SRC, 'edit-mode/EditPane.tsx'), 'utf8');
  assert.match(pane, /const frontmatterOf = \(text: string\): string => text\.slice\(0, text\.length - parseFrontmatter\(text\)\.body\.length\)/);
  assert.match(pane, /frontmatterOf\(tab\.text \?\? ''\) \+ toMarkdown\(tree\)/);
  for (const id of ['bold', 'italic', 'strike', 'ul', 'ol', 'hr', 'undo', 'redo']) assert.ok(pane.includes(`'${id}'`), id);
  assert.match(pane, /contentEditable/);
  const file = readFileSync(join(SRC, 'file-mode/FileView.tsx'), 'utf8');
  assert.match(file, /tab\.mode === 'edit' && \(handler\.handler === 'markdown' \|\| handler\.handler === 'html'\)/);
});
