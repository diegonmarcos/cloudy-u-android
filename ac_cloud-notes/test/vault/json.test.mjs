import test from 'node:test';
import assert from 'node:assert/strict';
import { fixture, src } from './helpers.mjs';

const jt = await src('json-tree.ts');
const text = fixture('sample', 'data.json');

test('tree: root open, children collapsed until expanded, pointers are RFC 6901', () => {
  const p = jt.parseJson(text);
  assert.equal(p.ok, true);
  const closed = jt.jsonRows(p.value, new Set());
  assert.deepEqual(closed.map(r => r.key), ['$', 'name', 'version', 'tags', 'nested', 'list']);
  assert.equal(closed.find(r => r.key === 'tags').preview, '[2]');
  assert.equal(closed.find(r => r.key === 'nested').preview, '{3}');
  const open = jt.jsonRows(p.value, new Set(['/nested', '/list', '/list/1']));
  assert.ok(open.some(r => r.pointer === '/nested/ok' && r.kind === 'boolean'));
  assert.ok(open.some(r => r.pointer === '/nested/none' && r.kind === 'null'));
  assert.ok(open.some(r => r.pointer === '/list/1/x' && r.preview === '2'));
});

test('text editor side: a syntax error is reported, format keeps the data', () => {
  const bad = jt.parseJson('{"a":');
  assert.equal(bad.ok, false);
  assert.ok(bad.error.length > 0);
  assert.deepEqual(JSON.parse(jt.formatJson(text)), JSON.parse(text));
});

test('copyable JSONPath for every row, as the fleet JSON viewer shows it', () => {
  const rows = jt.jsonRows(jt.parseJson(text).value, new Set(['/nested', '/list', '/list/1']));
  const path = p => rows.find(r => r.pointer === p).path;
  assert.equal(path(''), '$');
  assert.equal(path('/name'), '$.name');
  assert.equal(path('/list'), '$.list');
  assert.equal(path('/list/1'), '$.list[1]');
  assert.equal(path('/list/1/x'), '$.list[1].x');
  assert.equal(jt.pathChild('$', 'odd key', false), "$['odd key']");
  assert.equal(jt.pathChild('$', "it's", false), "$['it\\'s']");
  assert.equal(jt.pathChild('$.a', '0', true), '$.a[0]');
});

test('search: keys and leaf values, case-insensitive, matches shown with every ancestor open', () => {
  const v = jt.parseJson(text).value;
  const hit = jt.searchRows(v, new Set(), 'OK');
  assert.deepEqual(hit.map(r => r.pointer), ['', '/nested', '/nested/ok']);
  assert.ok(hit.find(r => r.pointer === '/nested').expanded);
  assert.deepEqual(jt.searchRows(v, new Set(), 'vault').map(r => r.pointer), ['', '/name']); // a value match
  assert.deepEqual(jt.searchRows(v, new Set(), 'zzz'), []);
  assert.deepEqual(jt.searchRows(v, new Set(), '  ').map(r => r.key), ['$', 'name', 'version', 'tags', 'nested', 'list']); // blank = the tree
  assert.deepEqual(jt.searchRows(v, new Set(), 'x').map(r => r.pointer), ['', '/list', '/list/0', '/list/0/x', '/list/1', '/list/1/x']);
});

test('copy: the value at a pointer, a string without quotes, anything else as JSON', () => {
  const v = jt.parseJson(text).value;
  assert.equal(jt.copyText(jt.valueAtPointer(v, '/name')), 'vault');
  assert.equal(jt.copyText(jt.valueAtPointer(v, '/version')), '3');
  assert.equal(jt.copyText(jt.valueAtPointer(v, '/nested/none')), 'null');
  assert.equal(JSON.parse(jt.copyText(jt.valueAtPointer(v, '/list'))).length, 2);
  assert.equal(jt.valueAtPointer(v, '/missing/deeper'), undefined);
  assert.equal(jt.valueAtPointer(jt.parseJson('{"a/b":{"~":1}}').value, '/a~1b/~0'), 1);
});
