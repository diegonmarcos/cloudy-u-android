// step-fixture (#1695): a tiny STEP AP214 file GENERATED here (no binary fixture in the repo or the APK): two named boxes as two solids, each with
// its own colour, in one assembly-free shape representation. Usage: import { stepBoxes } from './step-fixture.mjs'; stepBoxes() -> string.
export function stepBoxes(boxes = [{ name: 'Frame', min: [0, 0, 0], max: [0.2, 0.1, 0.05], rgb: [0.8, 0.1, 0.1] }, { name: 'Arm', min: [0.3, 0, 0], max: [0.6, 0.02, 0.02], rgb: [0.1, 0.1, 0.1] }]) {
  const L = []; let n = 0; const e = (s) => { n++; L.push(`#${n} = ${s} ;`); return `#${n}`; };
  const f = (x) => (Number.isInteger(x) ? x.toFixed(1) : String(x));
  const ctx = (() => {
    const len = e("( LENGTH_UNIT() NAMED_UNIT(*) SI_UNIT($,.METRE.) )");
    const ang = e("( NAMED_UNIT(*) PLANE_ANGLE_UNIT() SI_UNIT($,.RADIAN.) )");
    const sol = e("( NAMED_UNIT(*) SI_UNIT($,.STERADIAN.) SOLID_ANGLE_UNIT() )");
    const unc = e(`UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.E-07),${len},'distance_accuracy_value','')`);
    return e(`( GEOMETRIC_REPRESENTATION_CONTEXT(3) GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((${unc})) GLOBAL_UNIT_ASSIGNED_CONTEXT((${len},${ang},${sol})) REPRESENTATION_CONTEXT('ctx','3D') )`);
  })();
  const pt = (p) => e(`CARTESIAN_POINT('',(${p.map(f).join(',')}))`);
  const dir = (d) => e(`DIRECTION('',(${d.map(f).join(',')}))`);
  const solids = [], styled = [];
  for (const b of boxes) {
    const [x0, y0, z0] = b.min, [x1, y1, z1] = b.max;
    const P = [[x0, y0, z0], [x1, y0, z0], [x1, y1, z0], [x0, y1, z0], [x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]];
    const V = P.map((p) => e(`VERTEX_POINT('',${pt(p)})`));
    const edges = new Map();
    const edge = (a, c) => { const k = a < c ? `${a}-${c}` : `${c}-${a}`;
      if (!edges.has(k)) { const [s, t] = a < c ? [a, c] : [c, a]; const d = P[t].map((v, i) => v - P[s][i]); const len = Math.hypot(...d);
        const line = e(`LINE('',${pt(P[s])},${e(`VECTOR('',${dir(d.map((v) => v / len))},${f(len)})`)})`);
        edges.set(k, { id: e(`EDGE_CURVE('',${V[s]},${V[t]},${line},.T.)`), s }); }
      const ed = edges.get(k); return e(`ORIENTED_EDGE('',*,*,${ed.id},${ed.s === a ? '.T.' : '.F.'})`); };
    // faces as outward loops (counter-clockwise seen from outside) with their outward normals
    const F = [[[0, 3, 2, 1], [0, 0, -1]], [[4, 5, 6, 7], [0, 0, 1]], [[0, 1, 5, 4], [0, -1, 0]], [[2, 3, 7, 6], [0, 1, 0]], [[1, 2, 6, 5], [1, 0, 0]], [[3, 0, 4, 7], [-1, 0, 0]]];
    const faces = F.map(([q, nrm]) => {
      const loop = e(`EDGE_LOOP('',(${q.map((v, i) => edge(v, q[(i + 1) % 4])).join(',')}))`);
      const ref = Math.abs(nrm[0]) > 0.5 ? [0, 1, 0] : [1, 0, 0];
      const plane = e(`PLANE('',${e(`AXIS2_PLACEMENT_3D('',${pt(P[q[0]])},${dir(nrm)},${dir(ref)})`)})`);
      return e(`ADVANCED_FACE('',(${e(`FACE_OUTER_BOUND('',${loop},.T.)`)}),${plane},.T.)`);
    });
    const solid = e(`MANIFOLD_SOLID_BREP('${b.name}',${e(`CLOSED_SHELL('',(${faces.join(',')}))`)})`);
    solids.push(solid);
    const col = e(`COLOUR_RGB('',${b.rgb.map(f).join(',')})`);
    const sty = e(`SURFACE_STYLE_USAGE(.BOTH.,${e(`SURFACE_SIDE_STYLE('',(${e(`SURFACE_STYLE_FILL_AREA(${e(`FILL_AREA_STYLE('',(${e(`FILL_AREA_STYLE_COLOUR('',${col})`)}))`)})`)}))`)})`);
    styled.push(e(`STYLED_ITEM('',(${e(`PRESENTATION_STYLE_ASSIGNMENT((${sty}))`)}),${solid})`));
  }
  const origin = e(`AXIS2_PLACEMENT_3D('',${pt([0, 0, 0])},${dir([0, 0, 1])},${dir([1, 0, 0])})`);
  const rep = e(`ADVANCED_BREP_SHAPE_REPRESENTATION('fixture',(${[origin, ...solids].join(',')}),${ctx})`);
  e(`MECHANICAL_DESIGN_GEOMETRIC_PRESENTATION_REPRESENTATION('',(${styled.join(',')}),${ctx})`);
  const appctx = e("APPLICATION_CONTEXT('automotive design')");
  const prod = e(`PRODUCT('fixture','fixture','',(${e(`PRODUCT_CONTEXT('',${appctx},'mechanical')`)}))`);
  const pd = e(`PRODUCT_DEFINITION('design','',${e(`PRODUCT_DEFINITION_FORMATION('','',${prod})`)},${e(`PRODUCT_DEFINITION_CONTEXT('part definition',${appctx},'design')`)})`);
  e(`SHAPE_DEFINITION_REPRESENTATION(${e(`PRODUCT_DEFINITION_SHAPE('','',${pd})`)},${rep})`);
  return ['ISO-10303-21;', 'HEADER;', "FILE_DESCRIPTION(('fixture'),'2;1');", "FILE_NAME('fixture.step','2026-11-12T00:00:00',(''),(''),'step-fixture.mjs','','');",
    "FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));", 'ENDSEC;', 'DATA;', ...L, 'ENDSEC;', 'END-ISO-10303-21;', ''].join('\n');
}
