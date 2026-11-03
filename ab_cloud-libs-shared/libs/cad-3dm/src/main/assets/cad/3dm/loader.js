// libs:cad-3dm (#1404): Rhino 3DM through rhino3dm.js 8.35.0 (MIT, WASM): meshes directly; Breps, Extrusions and SubDs by the
// render meshes the file carries (Rhino saves them unless "save small" was used).
import * as THREE from 'three';
import rhino3dm from './vendor/rhino3dm.module.min.js';
const base = new URL('./vendor/', import.meta.url).href;
let rh;
export async function load(url) {
  rh ||= rhino3dm({ locateFile: (n) => base + n });
  const r = await rh;
  const doc = r.File3dm.fromByteArray(new Uint8Array(await (await fetch(url)).arrayBuffer()));
  if (!doc) throw new Error('rhino3dm could not read this 3dm');
  const g = new THREE.Group(), L = new THREE.BufferGeometryLoader(), objs = doc.objects();
  const add = (mesh, color) => { if (!mesh) return; const geo = L.parse(mesh.toThreejsJSON()); if (!geo.attributes.normal) geo.computeVertexNormals(); g.add(new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color, side: THREE.DoubleSide }))); };
  for (let i = 0; i < objs.count; i++) {
    const o = objs.get(i), geom = o.geometry(), a = o.attributes(), c = a.drawColor(doc);
    const color = new THREE.Color(c.r / 255, c.g / 255, c.b / 255);
    if (geom instanceof r.Mesh) add(geom, color);
    else if (geom instanceof r.Brep) { const fs = geom.faces(); for (let k = 0; k < fs.count; k++) add(fs.get(k).getMesh(r.MeshType.Any), color); }
    else if (geom instanceof r.Extrusion) add(geom.getMesh(r.MeshType.Any), color);
  }
  if (!g.children.length) throw new Error('no render meshes in this 3dm');
  g.rotation.x = -Math.PI / 2; return g;
}
