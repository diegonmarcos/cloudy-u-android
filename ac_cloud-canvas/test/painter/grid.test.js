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
