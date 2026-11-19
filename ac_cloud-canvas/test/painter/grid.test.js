// painter grid tests (#1808): the pure half of Cloud Canvas > Painter (app/src/main/assets/painter/grid.js), run by
// test/test-painter.sh with node's own runner (node --test), no npm and no packages. Covers the four generators (cell counts, key
// formats, positions), the 3D Sphere placement, paint, erase, clear, the stroke rule and persistence (encode/decode).
"use strict";
const test = require("node:test");
const assert = require("node:assert/strict");
const path = require("node:path");
const G = require(path.join(__dirname, "../../app/src/main/assets/painter/grid.js"));

const SQRT3 = Math.sqrt(3);
const near = (a, b, msg) => assert.ok(Math.abs(a - b) < 1e-9, `${msg}: ${a} != ${b}`);
const keysUnique = (cells) => new Set(cells.map((c) => c.key)).size === cells.length;

// ── generators ──
test("hex grid with R=25 has 1951 cells keyed H,q,r", () => {
  const cells = G.generate("hex");
  assert.equal(G.R, 25);
  assert.equal(cells.length, 1951);
  assert.equal(cells.length, 3 * 25 * 26 + 1);
  assert.ok(keysUnique(cells));
  for (const c of cells) {
    const m = /^H,(-?\d+),(-?\d+)$/.exec(c.key);
    assert.ok(m, c.key);
    const q = +m[1], r = +m[2];
    assert.ok(Math.abs(q) <= 25 && r >= Math.max(-25, -q - 25) && r <= Math.min(25, -q + 25), c.key);
    near(c.cx, 1.5 * q, c.key + " cx");
    near(c.cy, (SQRT3 / 2) * q + SQRT3 * r, c.key + " cy");
    assert.equal(c.geom, "hex"); assert.equal(c.rot, 0);
  }
});

test("square grid with R=25 has 2601 cells keyed S,q,r at (2q, 2r)", () => {
  const cells = G.generate("square");
  assert.equal(cells.length, 2601);
  assert.ok(keysUnique(cells));
  for (const c of cells) {
    const m = /^S,(-?\d+),(-?\d+)$/.exec(c.key);
    assert.ok(m, c.key);
    near(c.cx, 2 * +m[1], "cx"); near(c.cy, 2 * +m[2], "cy");
    assert.equal(c.geom, "square");
  }
});

test("triangle grid with R=25 has 101*51 = 5151 cells keyed T,q,r, up on even |q|, down (rot PI, +0.5) on odd", () => {
  const cells = G.generate("triangle");
  assert.equal(cells.length, 101 * 51);
  assert.equal(cells.length, 5151);
  assert.ok(keysUnique(cells));
  for (const c of cells) {
    const m = /^T,(-?\d+),(-?\d+)$/.exec(c.key);
    assert.ok(m, c.key);
    const q = +m[1], r = +m[2];
    assert.ok(Math.abs(q) <= 50 && Math.abs(r) <= 25);
    const isUp = Math.abs(q) % 2 === 0;
    near(c.cx, q * SQRT3 / 2, "cx");
    near(c.cy, 1.5 * r + (isUp ? 0 : 0.5), "cy");
    assert.equal(c.rot, isUp ? 0 : Math.PI);
    assert.equal(c.geom, "triangle");
  }
  // negative odd q is a down triangle too (|q| % 2)
  const t = cells.find((c) => c.key === "T,-1,0");
  assert.equal(t.rot, Math.PI);
});

test("octagon grid with R=25 has 2601 octagons O,q,r and 2601 small squares OS,q,r in the gaps", () => {
  const cells = G.generate("octagon");
  const W = 2 * Math.cos(Math.PI / 8);
  assert.equal(cells.length, 2601 + 2601);
  const oct = cells.filter((c) => c.geom === "octagon"), sq = cells.filter((c) => c.geom === "small_square");
  assert.equal(oct.length, 2601); assert.equal(sq.length, 2601);
  assert.ok(keysUnique(cells));
  for (const c of oct) {
    const m = /^O,(-?\d+),(-?\d+)$/.exec(c.key); assert.ok(m, c.key);
    near(c.cx, +m[1] * W, "cx"); near(c.cy, +m[2] * W, "cy");
  }
  for (const c of sq) {
    const m = /^OS,(-?\d+),(-?\d+)$/.exec(c.key); assert.ok(m, c.key);
    near(c.cx, +m[1] * W + W / 2, "cx"); near(c.cy, +m[2] * W + W / 2, "cy");
  }
});

test("every cell key maps back to its shape; an unknown prefix maps to none", () => {
  for (const s of G.SHAPES) for (const c of G.generate(s)) assert.equal(G.shapeOfKey(c.key), s);
  assert.equal(G.shapeOfKey("X,1,2"), null);
  assert.throws(() => G.generate("circle"));
});

test("a smaller R scales the counts by the same formulas", () => {
  assert.equal(G.generate("hex", 2).length, 19);
  assert.equal(G.generate("square", 2).length, 25);
  assert.equal(G.generate("triangle", 2).length, 9 * 5);
  assert.equal(G.generate("octagon", 2).length, 50);
});

// ── layout ──
test("2D world position is the cell centre times the grid scale", () => {
  const w = G.world2d({ cx: 1.5, cy: -2 }, 40);
  assert.deepEqual(w, { x: 60, y: -80, z: 0 });
});

test("3D Sphere: radius scale*30, lon = wx/Rs, lat clamped to PI/2.1, the fill one unit further out, facing outward", () => {
  const scale = 40, Rs = 1200;
  const c = { cx: 3, cy: 4 };
  const p = G.sphere(c, scale, 0);
  assert.equal(p.radius, Rs);
  near(Math.hypot(p.x, p.y, p.z), Rs, "outline radius");
  const lon = (3 * scale) / Rs, lat = (4 * scale) / Rs;
  near(p.x, Rs * Math.cos(lat) * Math.sin(lon), "x"); near(p.y, Rs * Math.sin(lat), "y"); near(p.z, Rs * Math.cos(lat) * Math.cos(lon), "z");
  near(p.look.x, 2 * p.x, "look x"); near(p.look.y, 2 * p.y, "look y"); near(p.look.z, 2 * p.z, "look z");
  const f = G.sphere(c, scale, 1);
  near(Math.hypot(f.x, f.y, f.z), Rs + 1, "fill radius");
  // a cell far up the grid is clamped below the pole
  const top = G.sphere({ cx: 0, cy: 1e6 }, scale, 0);
  near(Math.asin(top.y / Rs), Math.PI / 2.1, "clamped latitude");
  assert.equal(G.camera3dDistance(20), 1000);
  assert.equal(G.camera3dDistance(40), 1400);
});

// ── paint, erase, clear ──
test("defaults: hex, Paint, 2D, scale 40, white, nothing painted", () => {
  const s = G.defaults();
  assert.equal(s.shape, "hex"); assert.equal(s.mode, "paint"); assert.equal(s.layout, "2d"); assert.equal(s.scale, 40); assert.equal(s.color, "#ffffff");
  assert.deepEqual(G.COLORS, ["#ffffff", "#ef4444", "#22c55e", "#3b82f6", "#eab308", "#a855f7"]);
  for (const sh of G.SHAPES) assert.equal(G.activeCount(s, sh), 0);
});

test("paint sets a cell to the colour and counts it; repainting the same colour changes nothing", () => {
  const s = G.defaults();
  assert.equal(G.paint(s, "H,0,0", "#EF4444"), true);
  assert.equal(G.colorOf(s, "H,0,0"), "#ef4444");
  assert.equal(G.paint(s, "H,0,0", "#ef4444"), false);
  assert.equal(G.paint(s, "H,0,0", "#22c55e"), true);
  assert.equal(G.colorOf(s, "H,0,0"), "#22c55e");
  assert.equal(G.activeCount(s), 1);
  assert.equal(G.paint(s, "Z,0,0", "#22c55e"), false);
  assert.equal(G.paint(s, "H,1,0", "red"), false);
});

test("erase returns a cell to black (unpainted) and lowers the count", () => {
  const s = G.defaults();
  G.paint(s, "H,0,0"); G.paint(s, "H,1,0");
  assert.equal(G.activeCount(s), 2);
  assert.equal(G.erase(s, "H,0,0"), true);
  assert.equal(G.colorOf(s, "H,0,0"), null);
  assert.equal(G.erase(s, "H,0,0"), false);
  assert.equal(G.activeCount(s), 1);
});

test("each shape keeps its own painting; Clear empties only the shape on screen", () => {
  const s = G.defaults();
  G.paint(s, "H,0,0"); G.paint(s, "S,0,0"); G.paint(s, "S,1,1"); G.paint(s, "T,0,0"); G.paint(s, "O,0,0"); G.paint(s, "OS,0,0");
  assert.equal(G.activeCount(s, "hex"), 1); assert.equal(G.activeCount(s, "square"), 2);
  assert.equal(G.activeCount(s, "triangle"), 1); assert.equal(G.activeCount(s, "octagon"), 2);
  s.shape = "square";
  assert.equal(G.clear(s), 2);
  assert.equal(G.activeCount(s, "square"), 0);
  assert.equal(G.activeCount(s, "hex"), 1); assert.equal(G.activeCount(s, "octagon"), 2);
});

// ── strokes ──
test("a stroke changes each cell at most once, Paint paints, Rubber erases, View starts no stroke", () => {
  const s = G.defaults();
  s.color = "#3b82f6";
  const st = G.startStroke(s);
  assert.equal(st.touch("H,0,0"), true);
  assert.equal(st.touch("H,0,0"), false);           // the same cell again (lastTouchedKey)
  assert.equal(st.touch("H,1,0"), true);
  s.color = "#eab308";
  assert.equal(st.touch("H,0,0"), false);           // back over a cell already changed in this stroke
  assert.equal(G.colorOf(s, "H,0,0"), "#3b82f6");
  assert.equal(st.touch(null), false);
  assert.equal(st.changed, 2);
  const st2 = G.startStroke(s);
  assert.equal(st2.touch("H,0,0"), true);           // a new stroke may change it again
  assert.equal(G.colorOf(s, "H,0,0"), "#eab308");
  s.mode = "rubber";
  const rub = G.startStroke(s);
  assert.equal(rub.touch("H,0,0"), true);
  assert.equal(rub.touch("H,5,5"), false);          // erasing an empty cell changes nothing
  assert.equal(G.activeCount(s), 1);
  s.mode = "view";
  assert.equal(G.startStroke(s), null);
});

// ── persistence ──
test("encode/decode round-trips every painted shape, the colour, mode, scale and 2D/3D", () => {
  const s = G.defaults();
  G.paint(s, "H,2,-3", "#ef4444"); G.paint(s, "S,-25,25", "#22c55e"); G.paint(s, "T,-50,0", "#a855f7"); G.paint(s, "OS,24,24", "#eab308");
  s.shape = "triangle"; s.mode = "rubber"; s.layout = "3d"; s.scale = 77; s.color = "#3b82f6";
  const back = G.decode(G.encode(s));
  assert.deepEqual(back, JSON.parse(G.encode(s)));
  assert.equal(back.shape, "triangle"); assert.equal(back.mode, "rubber"); assert.equal(back.layout, "3d"); assert.equal(back.scale, 77); assert.equal(back.color, "#3b82f6");
  assert.equal(G.colorOf(back, "OS,24,24"), "#eab308");
  assert.equal(G.activeCount(back, "hex"), 1);
});

test("decode falls back to the defaults on garbage and drops foreign or malformed cells", () => {
  assert.deepEqual(G.decode("not json"), G.defaults());
  assert.deepEqual(G.decode(null), G.defaults());
  assert.deepEqual(G.decode('{"v":2,"shape":"square"}'), G.defaults());
  const d = G.decode(JSON.stringify({ v: 1, shape: "circle", mode: "fly", layout: "4d", scale: 999, color: "blue",
    painted: { hex: { "H,0,0": "#FFFFFF", "S,0,0": "#ffffff", "H,1,1": "red" }, square: "x", cube: { "S,0,0": "#ffffff" } } }));
  assert.equal(d.shape, "hex"); assert.equal(d.mode, "paint"); assert.equal(d.layout, "2d"); assert.equal(d.scale, 100); assert.equal(d.color, "#ffffff");
  assert.deepEqual(d.painted.hex, { "H,0,0": "#ffffff" });
  assert.deepEqual(d.painted.square, {});
  assert.equal(G.clampScale(3), 15); assert.equal(G.clampScale("abc"), 40);
});

test("launch overrides (the page query) apply over the loaded state, unknown values ignored", () => {
  const s = G.applyOverrides(G.defaults(), { shape: "octagon", layout: "3d", mode: "view", color: "#A855F7", scale: "60" });
  assert.equal(s.shape, "octagon"); assert.equal(s.layout, "3d"); assert.equal(s.mode, "view"); assert.equal(s.color, "#a855f7"); assert.equal(s.scale, 60);
  const t = G.applyOverrides(G.defaults(), { shape: "blob", scale: "" });
  assert.equal(t.shape, "hex"); assert.equal(t.scale, 40);
});

// ── #1823: five more tilings and the Hide-frames modes ──
const OLD_FOUR = ["hex", "square", "triangle", "octagon"];
const NEW_FIVE = ["rhombille", "trihexagonal", "snubsquare", "cairo", "elongated"];
const TYPES = { rhombille: ["RA", "RB", "RC"], trihexagonal: ["XH", "XT", "XU"], snubsquare: ["NA", "NB", "NC", "ND", "NE", "NF"], cairo: ["CA", "CB", "CC", "CD"], elongated: ["EQ", "EU", "ED"] };
const area = (poly) => { let a = 0; for (let i = 0; i < poly.length; i++) { const p = poly[i], q = poly[(i + 1) % poly.length]; a += p[0] * q[1] - q[0] * p[1]; } return a / 2; };

test("tiling list parity: the four prototype shapes stay first, in order, then the five new tilings", () => {
  assert.deepEqual(G.SHAPES.slice(0, 4), OLD_FOUR);
  assert.deepEqual(G.SHAPES.slice(4), NEW_FIVE);
  assert.equal(G.SHAPES.length, 9);
  assert.deepEqual(G.PREFIXES.octagon, ["O", "OS"]);
  // the old counts and key formats are untouched (the first tests of this file hold them); every new cell key maps back to its tiling
  for (const s of NEW_FIVE) {
    const cells = G.generate(s);
    assert.ok(cells.length > 500, s); assert.ok(keysUnique(cells), s);
    assert.deepEqual([...new Set(cells.map((c) => c.geom))].sort(), TYPES[s].slice().sort(), s);
    for (const c of cells) { assert.equal(G.shapeOfKey(c.key), s); assert.match(c.key, /^[A-Z]{1,2},-?\d{1,3},-?\d{1,3}$/); assert.ok(G.TEMPLATES[c.geom]); }
  }
  const prefixes = G.SHAPES.flatMap((s) => G.PREFIXES[s]);
  assert.equal(new Set(prefixes).size, prefixes.length, "no key prefix shared by two tilings");
});

test("the tiling cell shapes: rhombille 60/120 rhombi, trihexagonal hexagons + triangles, snub square squares + triangles, cairo pentagons, elongated squares + triangles", () => {
  const sides = (s) => Object.fromEntries(TYPES[s].map((p) => [p, G.TEMPLATES[p].length]));
  G.generate("rhombille"); assert.deepEqual(Object.values(sides("rhombille")), [4, 4, 4]);
  G.generate("trihexagonal"); assert.deepEqual(sides("trihexagonal"), { XH: 6, XT: 3, XU: 3 });
  G.generate("snubsquare"); assert.deepEqual(sides("snubsquare"), { NA: 4, NB: 4, NC: 3, ND: 3, NE: 3, NF: 3 });
  G.generate("cairo"); assert.deepEqual(Object.values(sides("cairo")), [5, 5, 5, 5]);
  G.generate("elongated"); assert.deepEqual(sides("elongated"), { EQ: 4, EU: 3, ED: 3 });
  // rhombi: all edges equal, angles 60/120 (short diagonal = edge)
  for (const p of TYPES.rhombille) {
    const t = G.TEMPLATES[p], d = (a, b) => Math.hypot(t[a][0] - t[b][0], t[a][1] - t[b][1]);
    near(d(0, 1), d(1, 2), "rhombus edges"); near(d(2, 3), d(0, 1), "rhombus edges");
    const diags = [d(0, 2), d(1, 3)].sort();
    near(diags[0], d(0, 1), "short diagonal = edge"); near(diags[1], d(0, 1) * SQRT3, "long diagonal = edge*sqrt3");
  }
  // cairo pentagons are convex and congruent in area
  const ar = TYPES.cairo.map((p) => Math.abs(area(G.TEMPLATES[p])));
  ar.forEach((a) => near(a, ar[0], "cairo areas"));
  for (const p of TYPES.cairo) {
    const t = G.TEMPLATES[p]; const sg = [];
    for (let i = 0; i < 5; i++) { const a = t[i], b = t[(i + 1) % 5], c = t[(i + 2) % 5]; sg.push(Math.sign((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]))); }
    assert.ok(sg.every((x) => x === sg[0] && x !== 0), "convex " + p);
  }
});

test("adjacency per new tiling: each edge is shared by at most two cells, and every unshared edge lies on the patch border", () => {
  for (const s of NEW_FIVE) {
    const cells = G.generate(s), adj = G.adjacency(cells);
    let shared = 0, edges = 0;
    for (const e of Object.keys(adj.edgeCells)) {
      const n = adj.edgeCells[e].length;
      assert.ok(n === 1 || n === 2, s + " edge in " + n + " cells");
      edges++; if (n === 2) shared++;
    }
    assert.ok(shared > edges / 2, s);
    cells.forEach((c, i) => {
      assert.equal(adj.nbr[i].length, G.TEMPLATES[c.geom].length);
      adj.nbr[i].forEach((l, k) => {
        assert.ok(l.length <= 1, s);
        if (l.length === 0) assert.ok(Math.max(Math.abs(c.cx), Math.abs(c.cy)) > G.WINDOW - 4.5, `${s} ${c.key}: an interior edge with no neighbour`);
        else { assert.ok(adj.nbr[l[0]].some((m) => m.includes(i)), s + " symmetric"); }
      });
    });
  }
});

test("adjacency counts on a small patch (r=4 window): interior cells have as many neighbours as edges, per tiling", () => {
  const sums = {};
  for (const s of NEW_FIVE) {
    const cells = G.generate(s, 6), adj = G.adjacency(cells);   // window 6: only the central cells are interior
    let full = 0;
    cells.forEach((c, i) => { if (adj.nbr[i].every((l) => l.length === 1)) full++; });
    sums[s] = [cells.length, full];
    assert.ok(full > 0 && full < cells.length, s + " " + full + "/" + cells.length);
  }
  // trihexagonal: hexagon centres at a 2-spacing lattice; a vertex is shared by exactly two hexagons and two triangles
  const th = G.generate("trihexagonal"), thAdj = G.adjacency(th);
  th.forEach((c, i) => { if (c.geom === "XH" && Math.abs(c.cx) < 20 && Math.abs(c.cy) < 20) assert.deepEqual(thAdj.nbr[i].map((l) => th[l[0]].geom).sort(), ["XT", "XT", "XT", "XU", "XU", "XU"]); });
  // elongated: a square touches 2 squares and 2 triangles
  const el = G.generate("elongated"), elAdj = G.adjacency(el);
  el.forEach((c, i) => { if (c.geom === "EQ" && Math.abs(c.cx) < 20 && Math.abs(c.cy) < 20) assert.deepEqual(elAdj.nbr[i].map((l) => el[l[0]].geom).sort(), ["EQ", "EQ", "ED", "EU"].sort()); });
  // snub square: a triangle touches 3 cells, a square 4 triangles; cairo: every pentagon has 5 neighbours
  const sn = G.generate("snubsquare"), snAdj = G.adjacency(sn);
  sn.forEach((c, i) => { if (Math.abs(c.cx) < 20 && Math.abs(c.cy) < 20) assert.equal(snAdj.nbr[i].filter((l) => l.length).length, G.TEMPLATES[c.geom].length); });
  const ca = G.generate("cairo"), caAdj = G.adjacency(ca);
  ca.forEach((c, i) => { if (Math.abs(c.cx) < 20 && Math.abs(c.cy) < 20) assert.deepEqual(caAdj.nbr[i].map((l) => l.length), [1, 1, 1, 1, 1]); });
});

test("adjacency of the prototype grids: hex 6, square 4, octagon 8 + its gap squares 4, triangle 3 (rows are half-shifted)", () => {
  const deg = (s, geom) => {
    const cells = G.generate(s), adj = G.adjacency(cells);
    const c = cells.findIndex((x) => x.geom === geom && Math.abs(x.cx) < 3 && Math.abs(x.cy) < 3);
    return adj.nbr[c].filter((l) => l.length).length;
  };
  assert.equal(deg("hex", "hex"), 6); assert.equal(deg("square", "square"), 4);
  assert.equal(deg("octagon", "octagon"), 8); assert.equal(deg("octagon", "small_square"), 4);
  assert.equal(deg("triangle", "triangle"), 3);
});

test("Hide frames: the default is the current look (off), the modes are off | between | outside | all, and frames persist", () => {
  const s = G.defaults();
  assert.equal(s.frames, "off");
  assert.deepEqual(G.FRAMES, ["off", "between", "outside", "all"]);
  s.frames = "all";
  assert.equal(G.decode(G.encode(s)).frames, "all");
  assert.equal(G.decode(JSON.stringify({ v: 1, frames: "nope" })).frames, "off");
  assert.equal(G.decode(JSON.stringify({ v: 1 })).frames, "off");            // a state saved before the option existed
  assert.equal(G.applyOverrides(G.defaults(), { frames: "between" }).frames, "between");
  assert.equal(G.applyOverrides(G.defaults(), { frames: "x" }).frames, "off");
  // off = every cell outlined, nothing merged, for painted and unpainted cells alike
  for (const painted of [false, true]) {
    const f = G.frameInfo("off", painted, [true, false, true]);
    assert.equal(f.outline, "line"); assert.deepEqual(f.edges, [false, false, false]);
  }
});

test("frame state per mode for sample paint patterns, on every tiling", () => {
  for (const shape of G.SHAPES) {
    const cells = G.generate(shape), adj = G.adjacency(cells);
    const st = G.defaults(); st.shape = shape;
    // a pair of neighbours A-B far from the border, a lone cell C elsewhere, everything else unpainted
    const inner = (i) => Math.max(Math.abs(cells[i].cx), Math.abs(cells[i].cy)) < 10 && adj.nbr[i].every((l) => l.length >= 1);
    const A = cells.findIndex((c, i) => inner(i));
    const eAB = adj.nbr[A].findIndex((l) => l.length === 1), B = adj.nbr[A][eAB][0];
    const C = cells.findIndex((c, i) => i !== A && i !== B && inner(i) && !adj.nbr[A].some((l) => l.includes(i)) && !adj.nbr[B].some((l) => l.includes(i)));
    G.paint(st, cells[A].key, "#ef4444"); G.paint(st, cells[B].key, "#22c55e"); G.paint(st, cells[C].key, "#3b82f6");
    const U = adj.nbr[A].find((l, k) => k !== eAB && l.length === 1)[0];                                  // an unpainted neighbour of A
    const mergedA = (f) => f.edges.map((e, k) => (e ? k : -1)).filter((k) => k >= 0);
    for (const mode of G.FRAMES) {
      st.frames = mode;
      const fa = G.frameOfCell(st, cells, adj, A), fc = G.frameOfCell(st, cells, adj, C), fu = G.frameOfCell(st, cells, adj, U), fb = G.frameOfCell(st, cells, adj, B);
      const between = mode === "between" || mode === "all", outside = mode === "outside" || mode === "all";
      // painted cells always keep their frame line
      assert.equal(fa.outline, "line", shape + " " + mode); assert.equal(fc.outline, "line"); assert.equal(fb.outline, "line");
      // an unpainted cell loses its frame under Only outside / All only
      assert.equal(fu.outline, outside ? "hidden" : "line", `${shape} ${mode} unpainted outline`);
      // Only between / All: the A-B shared edge is merged on both sides, and only that edge (every other neighbour of A is unpainted)
      assert.deepEqual(mergedA(fa), between ? [eAB] : [], `${shape} ${mode} A merged`);
      assert.equal(fb.edges.filter(Boolean).length, between ? 1 : 0, `${shape} ${mode} B merged`);
      assert.equal(fc.edges.some(Boolean), false, "a lone painted cell merges nothing");
      assert.equal(fu.edges.some(Boolean), false, "an unpainted cell merges nothing");
    }
    // live update: painting a third cell next to A-B grows the merged set, erasing it shrinks it back
    st.frames = "between";
    const before = G.frameOfCell(st, cells, adj, A).edges.filter(Boolean).length;
    const eAU = adj.nbr[A].findIndex((l) => l.includes(U));
    G.paint(st, cells[U].key, "#a855f7");
    assert.equal(G.frameOfCell(st, cells, adj, A).edges.filter(Boolean).length, before + 1, shape + " paint");
    assert.equal(G.frameOfCell(st, cells, adj, A).edges[eAU], true);
    G.erase(st, cells[U].key);
    assert.equal(G.frameOfCell(st, cells, adj, A).edges.filter(Boolean).length, before, shape + " erase");
    // Clear: nothing painted, so All merges nothing and hides every outline
    G.clear(st); st.frames = "all";
    assert.equal(G.frameOfCell(st, cells, adj, A).outline, "hidden");
    assert.equal(G.frameOfCell(st, cells, adj, A).edges.some(Boolean), false);
  }
});

test("frameInfo truth table: merged needs both cells painted, hidden needs an unpainted cell", () => {
  const rows = { off: [["line", false], ["line", false]], between: [["line", false], ["line", true]], outside: [["hidden", false], ["line", false]], all: [["hidden", false], ["line", true]] };
  for (const mode of G.FRAMES) {
    const un = G.frameInfo(mode, false, [true]), pa = G.frameInfo(mode, true, [true]), pn = G.frameInfo(mode, true, [false]);
    assert.deepEqual([un.outline, un.edges[0]], rows[mode][0], mode + " unpainted");
    assert.deepEqual([pa.outline, pa.edges[0]], rows[mode][1], mode + " painted beside painted");
    assert.deepEqual([pn.outline, pn.edges[0]], ["line", false], mode + " painted beside unpainted");
  }
});
