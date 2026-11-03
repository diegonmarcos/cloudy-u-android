// libs:cad-brep (#1404): STEP/IGES through occt-import-js 0.0.23 (LGPL-2.1; Open CASCADE compiled to WASM), triangulated in the page.
import * as THREE from 'three';
import { script } from '../script.js';
const base = new URL('./vendor/', import.meta.url).href;
let occt;
export async function load(url, format) {
  occt ||= script(base + 'occt-import-js.js', 'occtimportjs').then((f) => f({ locateFile: (n) => base + n }));
  const o = await occt;
  const buf = new Uint8Array(await (await fetch(url)).arrayBuffer());
  const p = { linearUnit: 'meter', linearDeflectionType: 'bounding_box_ratio', linearDeflection: 0.004, angularDeflection: 0.5 };
  const r = format === 'iges' ? o.ReadIgesFile(buf, p) : o.ReadStepFile(buf, p);
  if (!r.success) throw new Error('occt could not read this ' + format);
  const g = new THREE.Group();
  for (const m of r.meshes) {
    const geo = new THREE.BufferGeometry();
    geo.setAttribute('position', new THREE.Float32BufferAttribute(m.attributes.position.array, 3));
    if (m.attributes.normal) geo.setAttribute('normal', new THREE.Float32BufferAttribute(m.attributes.normal.array, 3));
    geo.setIndex(new THREE.Uint32BufferAttribute(m.index.array, 1)); if (!m.attributes.normal) geo.computeVertexNormals();
    const color = m.color ? new THREE.Color(m.color[0], m.color[1], m.color[2]) : new THREE.Color(0xcfd4dc);
    g.add(new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color, side: THREE.DoubleSide, metalness: 0.1, roughness: 0.6 })));
  }
  g.rotation.x = -Math.PI / 2; // STEP/IGES are Z-up
  return g;
}
