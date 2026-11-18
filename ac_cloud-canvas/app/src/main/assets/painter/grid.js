// painter/grid.js (#1808, own code): the pure half of Cloud Canvas > Painter, with no Three.js and no DOM, so the node tests
// (test/painter/*.test.js, run by test/test-painter.sh) exercise exactly what the page runs.
//   grids        the four cell generators (hex, square, triangle, octagon + its small square gaps), R logical cells
//   layout       2D world position, and the 3D Sphere placement (position + outward normal)
//   state        the painted cells per shape, colour, mode, grid scale, 2D/3D; paint, erase, clear, the Active Cells count
//   strokes      one stroke changes each cell at most once (lastTouchedKey)
//   codec        the persisted JSON (versioned, validated: a bad file loads as the defaults, never as garbage)
// Loaded as a classic script in the page (window.PainterGrid) and with require() in node.
(function (root, factory) {
  if (typeof module === "object" && module.exports) module.exports = factory();
  else root.PainterGrid = factory();
})(typeof self !== "undefined" ? self : this, function () {
  "use strict";

  var R = 25;
  var SQRT3 = Math.sqrt(3);
  var SHAPES = ["hex", "square", "triangle", "octagon"];
  var MODES = ["paint", "rubber", "view"];
  var LAYOUTS = ["2d", "3d"];
  var COLORS = ["#ffffff", "#ef4444", "#22c55e", "#3b82f6", "#eab308", "#a855f7"];
  var SCALE_MIN = 15, SCALE_MAX = 100, SCALE_DEFAULT = 40;
  // which key prefixes belong to which shape (the octagon grid owns its small square gap tiles too)
  var PREFIXES = { hex: ["H"], square: ["S"], triangle: ["T"], octagon: ["O", "OS"] };

  // ── grids ────────────────────────────────────────────────────────
  // Each cell: {key, geom, cx, cy, rot}; geom names the shared geometry (hex, square, triangle, octagon, small_square).
  function hexGrid(r) {
    var out = [];
    for (var q = -r; q <= r; q++) {
      var r1 = Math.max(-r, -q - r), r2 = Math.min(r, -q + r);
      for (var s = r1; s <= r2; s++) out.push({ key: "H," + q + "," + s, geom: "hex", cx: 1.5 * q, cy: (SQRT3 / 2) * q + SQRT3 * s, rot: 0 });
    }
    return out;
  }

  function squareGrid(r) {
    var out = [];
    for (var q = -r; q <= r; q++) for (var s = -r; s <= r; s++) out.push({ key: "S," + q + "," + s, geom: "square", cx: 2 * q, cy: 2 * s, rot: 0 });
    return out;
  }

  function triangleGrid(r) {
    var out = [];
    for (var q = -2 * r; q <= 2 * r; q++) {
      var isUp = Math.abs(q) % 2 === 0;
      for (var s = -r; s <= r; s++) out.push({ key: "T," + q + "," + s, geom: "triangle", cx: q * SQRT3 / 2, cy: 1.5 * s + (isUp ? 0 : 0.5), rot: isUp ? 0 : Math.PI });
    }
    return out;
  }

  function octagonGrid(r) {
    var W = 2 * Math.cos(Math.PI / 8);
    var out = [];
    for (var q = -r; q <= r; q++) for (var s = -r; s <= r; s++) out.push({ key: "O," + q + "," + s, geom: "octagon", cx: q * W, cy: s * W, rot: 0 });
    for (q = -r; q <= r; q++) for (s = -r; s <= r; s++) out.push({ key: "OS," + q + "," + s, geom: "small_square", cx: q * W + W / 2, cy: s * W + W / 2, rot: 0 });
    return out;
  }

  function generate(shape, r) {
    var n = r == null ? R : r;
    switch (shape) {
      case "hex": return hexGrid(n);
      case "square": return squareGrid(n);
      case "triangle": return triangleGrid(n);
      case "octagon": return octagonGrid(n);
    }
    throw new Error("unknown shape " + shape);
  }

  function shapeOfKey(key) {
    var p = String(key).split(",")[0];
    for (var i = 0; i < SHAPES.length; i++) if (PREFIXES[SHAPES[i]].indexOf(p) >= 0) return SHAPES[i];
    return null;
  }

  // ── layout ───────────────────────────────────────────────────────
  function world2d(cell, scale) { return { x: cell.cx * scale, y: cell.cy * scale, z: 0 }; }

  // the 3D Sphere: Rs = scale*30, lon = wx/Rs, lat = clamp(wy/Rs, +-PI/2.1); the cell sits at radius Rs (outline) or Rs+lift (fill)
  // and looks at 2*position, so it faces outward.
  function sphere(cell, scale, lift) {
    var Rs = scale * 30;
    var wx = cell.cx * scale, wy = cell.cy * scale;
    var lim = Math.PI / 2.1;
    var lon = wx / Rs, lat = Math.max(-lim, Math.min(lim, wy / Rs));
    var rad = Rs + (lift || 0);
    var x = Math.cos(lat) * Math.sin(lon), y = Math.sin(lat), z = Math.cos(lat) * Math.cos(lon);
    return { x: rad * x, y: rad * y, z: rad * z, look: { x: 2 * rad * x, y: 2 * rad * y, z: 2 * rad * z }, radius: Rs };
  }

  function camera3dDistance(scale) { return Math.max(1000, scale * 35); }

  // ── state ────────────────────────────────────────────────────────
  function defaults() {
    return { v: 1, shape: "hex", mode: "paint", layout: "2d", scale: SCALE_DEFAULT, color: COLORS[0], painted: { hex: {}, square: {}, triangle: {}, octagon: {} } };
  }

  function clampScale(s) {
    var n = Math.round(Number(s));
    if (!isFinite(n)) return SCALE_DEFAULT;
    return Math.max(SCALE_MIN, Math.min(SCALE_MAX, n));
  }

  function isColor(c) { return typeof c === "string" && /^#[0-9a-f]{6}$/i.test(c); }

  function paint(state, key, color) {
    var shape = shapeOfKey(key);
    if (!shape) return false;
    var c = (color || state.color).toLowerCase();
    if (!isColor(c)) return false;
    if (state.painted[shape][key] === c) return false;
    state.painted[shape][key] = c;
    return true;
  }

  function erase(state, key) {
    var shape = shapeOfKey(key);
    if (!shape || !(key in state.painted[shape])) return false;
    delete state.painted[shape][key];
    return true;
  }

  // Clear empties the shape on screen; the other shapes keep their painting.
  function clear(state, shape) {
    var s = shape || state.shape;
    var had = Object.keys(state.painted[s]).length;
    state.painted[s] = {};
    return had;
  }

  function activeCount(state, shape) { return Object.keys(state.painted[shape || state.shape]).length; }

  function colorOf(state, key) {
    var shape = shapeOfKey(key);
    return shape ? state.painted[shape][key] || null : null;
  }

  // ── strokes ──────────────────────────────────────────────────────
  // A stroke starts on pointerdown on the canvas (never in View mode); each move hands it the closest hit's key.
  function Stroke(state) { this.state = state; this.lastTouchedKey = null; this.touched = {}; this.changed = 0; }
  Stroke.prototype.touch = function (key) {
    if (key == null || key === this.lastTouchedKey || this.touched[key]) return false;
    this.lastTouchedKey = key;
    this.touched[key] = true;
    var st = this.state, ok = false;
    if (st.mode === "paint") ok = paint(st, key, st.color);
    else if (st.mode === "rubber") ok = erase(st, key);
    if (ok) this.changed++;
    return ok;
  };
  function startStroke(state) { return state.mode === "view" ? null : new Stroke(state); }

  // ── codec ────────────────────────────────────────────────────────
  function encode(state) {
    return JSON.stringify({ v: 1, shape: state.shape, mode: state.mode, layout: state.layout, scale: state.scale, color: state.color, painted: state.painted });
  }

  // Anything unreadable falls back to the default for that field; a painted entry survives only with a key of its own shape
  // and a #rrggbb colour.
  function decode(text) {
    var d = defaults();
    var o;
    try { o = typeof text === "string" ? JSON.parse(text) : text; } catch (e) { return d; }
    if (!o || typeof o !== "object" || o.v !== 1) return d;
    if (SHAPES.indexOf(o.shape) >= 0) d.shape = o.shape;
    if (MODES.indexOf(o.mode) >= 0) d.mode = o.mode;
    if (LAYOUTS.indexOf(o.layout) >= 0) d.layout = o.layout;
    if (o.scale != null) d.scale = clampScale(o.scale);
    if (isColor(o.color)) d.color = o.color.toLowerCase();
    var p = o.painted && typeof o.painted === "object" ? o.painted : {};
    SHAPES.forEach(function (s) {
      var m = p[s] && typeof p[s] === "object" ? p[s] : {};
      Object.keys(m).forEach(function (k) { if (shapeOfKey(k) === s && isColor(m[k])) d.painted[s][k] = m[k].toLowerCase(); });
    });
    return d;
  }

  // Launch overrides (the page's query string: shape, layout, mode, color, scale), applied over the loaded state.
  function applyOverrides(state, q) {
    if (!q) return state;
    if (SHAPES.indexOf(q.shape) >= 0) state.shape = q.shape;
    if (LAYOUTS.indexOf(q.layout) >= 0) state.layout = q.layout;
    if (MODES.indexOf(q.mode) >= 0) state.mode = q.mode;
    if (isColor(q.color)) state.color = q.color.toLowerCase();
    if (q.scale != null && q.scale !== "") state.scale = clampScale(q.scale);
    return state;
  }

  return {
    R: R, SHAPES: SHAPES, MODES: MODES, LAYOUTS: LAYOUTS, COLORS: COLORS, PREFIXES: PREFIXES,
    SCALE_MIN: SCALE_MIN, SCALE_MAX: SCALE_MAX, SCALE_DEFAULT: SCALE_DEFAULT,
    hexGrid: hexGrid, squareGrid: squareGrid, triangleGrid: triangleGrid, octagonGrid: octagonGrid, generate: generate, shapeOfKey: shapeOfKey,
    world2d: world2d, sphere: sphere, camera3dDistance: camera3dDistance,
    defaults: defaults, clampScale: clampScale, isColor: isColor, paint: paint, erase: erase, clear: clear, activeCount: activeCount, colorOf: colorOf,
    Stroke: Stroke, startStroke: startStroke,
    encode: encode, decode: decode, applyOverrides: applyOverrides,
  };
});
