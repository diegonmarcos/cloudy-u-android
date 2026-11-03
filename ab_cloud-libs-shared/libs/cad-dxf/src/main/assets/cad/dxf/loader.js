// libs:cad-dxf (#1404): DXF through dxf-parser 1.1.2 (MIT). LINE, LWPOLYLINE/POLYLINE, CIRCLE, ARC as lines; 3DFACE/SOLID as faces.
import * as THREE from 'three';
import { script } from '../script.js';
const base = new URL('./vendor/', import.meta.url).href;
export async function load(url) {
  const Parser = await script(base + 'dxf-parser.js', 'DxfParser');
  const dxf = new Parser().parseSync(await (await fetch(url)).text());
  const seg = [], tri = [];
  const v = (p) => [p.x || 0, p.y || 0, p.z || 0];
  const line = (a, b) => seg.push(...a, ...b);
  for (const e of dxf.entities || []) {
    if (e.type === 'LINE' && e.vertices?.length >= 2) line(v(e.vertices[0]), v(e.vertices[1]));
    else if ((e.type === 'LWPOLYLINE' || e.type === 'POLYLINE') && e.vertices?.length) {
      const vs = e.vertices.map(v); for (let i = 1; i < vs.length; i++) line(vs[i - 1], vs[i]); if (e.shape || e.closed) line(vs[vs.length - 1], vs[0]);
    } else if (e.type === 'CIRCLE' || e.type === 'ARC') {
      const c = v(e.center), r = e.radius, a0 = e.type === 'ARC' ? e.startAngle : 0; let a1 = e.type === 'ARC' ? e.endAngle : Math.PI * 2;
      if (a1 <= a0) a1 += Math.PI * 2; const n = 48;
      for (let i = 0; i < n; i++) { const t0 = a0 + (a1 - a0) * i / n, t1 = a0 + (a1 - a0) * (i + 1) / n; line([c[0] + r * Math.cos(t0), c[1] + r * Math.sin(t0), c[2]], [c[0] + r * Math.cos(t1), c[1] + r * Math.sin(t1), c[2]]); }
    } else if ((e.type === '3DFACE' || e.type === 'SOLID') && e.vertices?.length >= 3) {
      const vs = e.vertices.map(v); tri.push(...vs[0], ...vs[1], ...vs[2]); if (vs.length > 3) tri.push(...vs[0], ...vs[2], ...vs[3]);
    }
  }
  const g = new THREE.Group();
  if (seg.length) { const geo = new THREE.BufferGeometry(); geo.setAttribute('position', new THREE.Float32BufferAttribute(seg, 3)); g.add(new THREE.LineSegments(geo, new THREE.LineBasicMaterial({ color: 0x9fd3ff }))); }
  if (tri.length) { const geo = new THREE.BufferGeometry(); geo.setAttribute('position', new THREE.Float32BufferAttribute(tri, 3)); geo.computeVertexNormals(); g.add(new THREE.Mesh(geo, new THREE.MeshStandardMaterial({ color: 0xcfd4dc, side: THREE.DoubleSide }))); }
  if (!g.children.length) throw new Error('no drawable DXF entities');
  g.rotation.x = -Math.PI / 2; return g;
}
