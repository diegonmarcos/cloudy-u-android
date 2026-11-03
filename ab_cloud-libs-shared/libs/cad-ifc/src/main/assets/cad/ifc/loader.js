// libs:cad-ifc (#1404): IFC through web-ifc 0.0.78 (MPL-2.0, WASM): every product's geometry streamed into three.js meshes.
import * as THREE from 'three';
import { script } from '../script.js';
const base = new URL('./vendor/', import.meta.url).href;
let api;
export async function load(url) {
  api ||= script(base + 'web-ifc-api-iife.js', 'WebIFC').then(async (W) => { const a = new W.IfcAPI(); a.SetWasmPath(base, true); await a.Init(); return a; });
  const a = await api;
  const id = a.OpenModel(new Uint8Array(await (await fetch(url)).arrayBuffer()));
  const g = new THREE.Group(), mats = new Map();
  a.StreamAllMeshes(id, (mesh) => {
    const pgs = mesh.geometries;
    for (let i = 0; i < pgs.size(); i++) {
      const pg = pgs.get(i), geom = a.GetGeometry(id, pg.geometryExpressID);
      const vd = a.GetVertexArray(geom.GetVertexData(), geom.GetVertexDataSize()), ix = a.GetIndexArray(geom.GetIndexData(), geom.GetIndexDataSize());
      const ib = new THREE.InterleavedBuffer(new Float32Array(vd), 6), geo = new THREE.BufferGeometry();
      geo.setAttribute('position', new THREE.InterleavedBufferAttribute(ib, 3, 0)); geo.setAttribute('normal', new THREE.InterleavedBufferAttribute(ib, 3, 3));
      geo.setIndex(new THREE.BufferAttribute(new Uint32Array(ix), 1));
      const k = [pg.color.x, pg.color.y, pg.color.z, pg.color.w].join();
      if (!mats.has(k)) mats.set(k, new THREE.MeshStandardMaterial({ color: new THREE.Color(pg.color.x, pg.color.y, pg.color.z), opacity: pg.color.w, transparent: pg.color.w < 1, side: THREE.DoubleSide }));
      const m = new THREE.Mesh(geo, mats.get(k)); m.matrixAutoUpdate = false; m.matrix.fromArray(pg.flatTransformation); g.add(m);
      geom.delete();
    }
  });
  a.CloseModel(id);
  if (!g.children.length) throw new Error('no IFC geometry');
  return g;
}
