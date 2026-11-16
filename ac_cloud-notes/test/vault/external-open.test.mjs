// #1717 Open with: a text file handed over by another app waits in a queue and is taken once, in order.
import test from 'node:test';
import assert from 'node:assert/strict';
import { src } from './helpers.mjs';

const eo = await src('external-open.ts');

test('external docs are queued, taken once and in order', () => {
  eo.pushExternalDoc({ name: 'a.txt', content: 'one' });
  eo.pushExternalDoc({ name: 'b.md', content: 'two' });
  assert.deepEqual(eo.takeExternalDocs().map(d => d.name), ['a.txt', 'b.md']);
  assert.deepEqual(eo.takeExternalDocs(), []);
});

test('an external path is recognised and never collides with a vault path', () => {
  assert.equal(eo.isExternalPath(eo.EXTERNAL_PREFIX + 'a.txt'), true);
  assert.equal(eo.isExternalPath('notes/a.txt'), false);
});
