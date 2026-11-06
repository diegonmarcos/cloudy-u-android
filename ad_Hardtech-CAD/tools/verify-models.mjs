// verify-models: the second, independent parser of the model guard (the first is check_models.py, pure Python).
// Usage: node verify-models.mjs <Hardtech-CAD app dir>   (run from a folder where `@gltf-transform/core` is installed; CI copies this file there)
// For every model declared in app/src/main/assets/cad/models.json: its GLB view bundled in the assets must exist, have the declared size and
// sha256, PARSE with @gltf-transform/core (no extension registered, so a file that needs Draco / meshopt decoding, which the app's viewer
// does not set up, fails here exactly as it would on the phone), hold a NON-ZERO triangle count of finite, non-degenerate geometry that
// equals the declared stats. Exit 1 with one line per failure.
import { readFileSync, existsSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { NodeIO } from '@gltf-transform/core';

const app = process.argv[2];
if (!app) { console.error('usage: node verify-models.mjs <app dir>'); process.exit(2); }
const assets = `${app}/app/src/main/assets`;
const manifest = JSON.parse(readFileSync(`${assets}/cad/models.json`, 'utf8'));
const bad = [];
const fail = (id, msg) => bad.push(`FAIL ${id}: ${msg}`);
const io = new NodeIO();

for (const m of manifest.models) {
  const v = m.view;
  if (!v || v.format !== 'glb' || !v.asset) { fail(m.id, `no renderable derivative (view ${JSON.stringify(v)})`); continue; }
  const file = `${assets}/${v.asset}`;
  if (!existsSync(file)) { fail(m.id, `${file} is missing`); continue; }
  const buf = readFileSync(file);
  if (buf.subarray(0, 24).toString('latin1').startsWith('version https://git-lfs')) { fail(m.id, `${v.asset} is an LFS pointer`); continue; }
  if (buf.length !== v.size) fail(m.id, `${v.asset} is ${buf.length} bytes, declared ${v.size}`);
  const sha = createHash('sha256').update(buf).digest('hex');
  if (sha !== v.sha256) fail(m.id, `${v.asset} sha256 ${sha} is not the declared ${v.sha256}`);
  let doc;
  try { doc = await io.readBinary(new Uint8Array(buf)); } catch (e) { fail(m.id, `${v.asset} does not parse: ${e.message}`); continue; }
  let tris = 0, verts = 0, nonFinite = 0;
  const lo = [Infinity, Infinity, Infinity], hi = [-Infinity, -Infinity, -Infinity];
  for (const mesh of doc.getRoot().listMeshes()) for (const p of mesh.listPrimitives()) {
    if (p.getMode() !== 4) continue;
    const pos = p.getAttribute('POSITION'); if (!pos) continue;
    const idx = p.getIndices();
    tris += Math.floor((idx ? idx.getCount() : pos.getCount()) / 3); verts += pos.getCount();
    const a = pos.getArray();
    for (let i = 0; i < a.length; i += 3) for (let k = 0; k < 3; k++) {
      const x = a[i + k];
      if (!Number.isFinite(x)) { nonFinite++; continue; }
      if (x < lo[k]) lo[k] = x;
      if (x > hi[k]) hi[k] = x;
    }
  }
  if (tris <= 0) { fail(m.id, `${v.asset} parses but holds ${tris} triangles`); continue; }
  if (nonFinite) fail(m.id, `${v.asset} has ${nonFinite} non-finite coordinates`);
  if (!(hi[0] > lo[0] || hi[1] > lo[1] || hi[2] > lo[2])) fail(m.id, `${v.asset} is a single point`);
  const st = m.stats || {};
  if (st.triangles !== tris) fail(m.id, `declared ${st.triangles} triangles, the file holds ${tris}`);
  if (st.vertices !== verts) fail(m.id, `declared ${st.vertices} vertices, the file holds ${verts}`);
  console.log(`ok ${m.id}: ${v.asset} parses with @gltf-transform/core, ${tris} triangles, ${verts} vertices`);
}
if (!manifest.models.length) bad.push('FAIL models.json declares no model');
if (bad.length) { console.error(bad.join('\n')); process.exit(1); }
console.log(`verified ${manifest.models.length} models: every one has a renderable GLB that parses with a non-zero triangle count`);
