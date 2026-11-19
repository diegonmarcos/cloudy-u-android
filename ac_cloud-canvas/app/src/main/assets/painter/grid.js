// painter/grid.js (#1808, own code): the pure half of Cloud Canvas > Painter, with no Three.js and no DOM, so the node tests
// (test/painter/*.test.js, run by test/test-painter.sh) exercise exactly what the page runs.
//   grids        the nine cell generators: hex, square, triangle, octagon (+ its small square gaps), then rhombille, trihexagonal, snub
//                square, cairo pentagonal, elongated triangular (#1823; polygon tilings built from lattice-translated base polygons)
//   frames       shared-edge adjacency per tiling (edge keys from rounded vertex pairs) and the Hide-frames modes (off, between, outside,
//                all) as pure functions of the painted cells (#1823)
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
  var WINDOW = 30;   // the #1823 tilings keep the cells whose centre is within +-WINDOW of the origin (a smaller r, in tests, narrows it)
  var SQRT3 = Math.sqrt(3);
  // the first four are rule 16: same order, same behaviour; #1823 appends the rest
  var SHAPES = ["hex", "square", "triangle", "octagon", "rhombille", "trihexagonal", "snubsquare", "cairo", "elongated"];
  var FRAMES = ["off", "between", "outside", "all"];     // Hide frames: off = the current look (every cell outlined)
  var MODES = ["paint", "rubber", "view"];
  var LAYOUTS = ["2d", "3d"];
  var COLORS = ["#ffffff", "#ef4444", "#22c55e", "#3b82f6", "#eab308", "#a855f7"];
  var SCALE_MIN = 15, SCALE_MAX = 100, SCALE_DEFAULT = 40;
  // which key prefixes belong to which shape (the octagon grid owns its small square gap tiles too)
  var PREFIXES = { hex: ["H"], square: ["S"], triangle: ["T"], octagon: ["O", "OS"],
    rhombille: ["RA", "RB", "RC"], trihexagonal: ["XH", "XT", "XU"], snubsquare: ["NA", "NB", "NC", "ND", "NE", "NF"],
    cairo: ["CA", "CB", "CC", "CD"], elongated: ["EQ", "EU", "ED"] };

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

  // ── #1823 polygon tilings ──
  // A tiling is lattice vectors a1, a2 and base polygons (one per cell type, prefix = key prefix and geometry name); the cell
  // (type, q, s) is the base polygon moved by q*a1 + s*a2, keyed "<prefix>,q,s". Cells whose centre lies in the window |x|,|y| <= W stay.
  // TEMPLATES[prefix] = the polygon's vertices around its own centre (what the page turns into a geometry); cx, cy = the centre.
  // The first four cells keep their prototype geometries in the page; their templates here exist for the shared-edge adjacency.
  var SMALL = 2 * Math.sin(Math.PI / 8) / Math.SQRT2;
  var TEMPLATES = {
    hex: regular(6, 1, 0), square: [[1, 1], [-1, 1], [-1, -1], [1, -1]], triangle: regular(3, 1, Math.PI / 2),
    octagon: regular(8, 1, Math.PI / 8), small_square: regular(4, SMALL, 0),
  };
  function centroid(poly) {
    var x = 0, y = 0;
    for (var i = 0; i < poly.length; i++) { x += poly[i][0]; y += poly[i][1]; }
    return [x / poly.length, y / poly.length];
  }
  function latticeTiling(def, W) {
    var len = Math.min(Math.hypot(def.a1[0], def.a1[1]), Math.hypot(def.a2[0], def.a2[1]));
    var N = Math.ceil(2 * W / len) + 2;
    var out = [];
    def.types.forEach(function (t) {
      var c0 = centroid(t.poly);
      TEMPLATES[t.prefix] = t.poly.map(function (p) { return [p[0] - c0[0], p[1] - c0[1]]; });
      for (var q = -N; q <= N; q++) for (var s = -N; s <= N; s++) {
        var cx = c0[0] + q * def.a1[0] + s * def.a2[0], cy = c0[1] + q * def.a1[1] + s * def.a2[1];
        if (Math.abs(cx) <= W && Math.abs(cy) <= W) out.push({ key: t.prefix + "," + q + "," + s, geom: t.prefix, cx: cx, cy: cy, rot: 0 });
      }
    });
    return out;
  }
  function regular(n, r, a0) {
    var p = [];
    for (var k = 0; k < n; k++) p.push([r * Math.cos(a0 + 2 * Math.PI * k / n), r * Math.sin(a0 + 2 * Math.PI * k / n)]);
    return p;
  }
  function moved(poly, dx, dy) { return poly.map(function (p) { return [p[0] + dx, p[1] + dy]; }); }

  // rhombille: every hexagon (circumradius L, flat top, as hexGrid) cut into three 60/120 rhombi around its centre
  function rhombilleDef(L) {
    var h = regular(6, L, 0);
    var o = [0, 0];
    return { a1: [1.5 * L, SQRT3 / 2 * L], a2: [0, SQRT3 * L], types: [
      { prefix: "RA", poly: [o, h[0], h[1], h[2]] }, { prefix: "RB", poly: [o, h[2], h[3], h[4]] }, { prefix: "RC", poly: [o, h[4], h[5], h[0]] } ] };
  }
  // trihexagonal (kagome, 3.6.3.6): hexagons of edge L two L apart centre to centre, a triangle in each gap
  function trihexDef(L) {
    var hy = SQRT3 / 2 * L;
    return { a1: [2 * L, 0], a2: [L, SQRT3 * L], types: [
      { prefix: "XH", poly: regular(6, L, 0) },
      { prefix: "XT", poly: [[L, 0], [1.5 * L, hy], [0.5 * L, hy]] },
      { prefix: "XU", poly: [[1.5 * L, hy], [2.5 * L, hy], [2 * L, 2 * hy]] } ] };
  }
  // elongated triangular (3.3.3.4.4): a row of squares, a row of alternating triangles, the next square row shifted by half
  function elongatedDef(L) {
    var h = SQRT3 / 2 * L;
    return { a1: [L, 0], a2: [L / 2, L + h], types: [
      { prefix: "EQ", poly: [[0, 0], [L, 0], [L, L], [0, L]] },
      { prefix: "EU", poly: [[0, L], [L, L], [L / 2, L + h]] },
      { prefix: "ED", poly: [[L / 2, L + h], [1.5 * L, L + h], [L, L]] } ] };
  }
  // snub square (3.3.4.3.4): squares turned +15 and -15 degrees on a square lattice D = L*sqrt(2+sqrt3); the four triangles are the
  // vertex triples one edge apart. Returns the base faces: squares, triangles (and, for cairo, every translate that touches the cell).
  function snubFaces(L) {
    var D = L * Math.sqrt(2 + SQRT3), rr = L / Math.SQRT2, EPS = 1e-6;
    var sqA = regular(4, rr, Math.PI / 4 + Math.PI / 12), sqB = moved(regular(4, rr, Math.PI / 4 - Math.PI / 12), D / 2, D / 2);
    var pts = [], i, j, k;
    for (var tx = -1; tx <= 2; tx++) for (var ty = -1; ty <= 2; ty++) [sqA, sqB].forEach(function (q) { q.forEach(function (p) {
      var x = p[0] + tx * D, y = p[1] + ty * D;
      if (!pts.some(function (o) { return Math.hypot(o[0] - x, o[1] - y) < EPS; })) pts.push([x, y]);   // squares share their corners
    }); });
    var d = function (a, b) { return Math.hypot(a[0] - b[0], a[1] - b[1]); };
    var tris = [];
    for (i = 0; i < pts.length; i++) for (j = i + 1; j < pts.length; j++) {
      if (Math.abs(d(pts[i], pts[j]) - L) > EPS) continue;
      for (k = j + 1; k < pts.length; k++) {
        if (Math.abs(d(pts[i], pts[k]) - L) > EPS || Math.abs(d(pts[j], pts[k]) - L) > EPS) continue;
        var c = centroid([pts[i], pts[j], pts[k]]);
        if (c[0] >= -EPS && c[0] < D - EPS && c[1] >= -EPS && c[1] < D - EPS) tris.push([pts[i], pts[j], pts[k]]);
      }
    }
    return { D: D, squares: [sqA, sqB], triangles: tris, pts: pts };
  }
  function snubDef(L) {
    var f = snubFaces(L);
    var types = [{ prefix: "NA", poly: f.squares[0] }, { prefix: "NB", poly: f.squares[1] }];
    f.triangles.forEach(function (t, i) { types.push({ prefix: "N" + "CDEF".charAt(i), poly: t }); });
    return { a1: [f.D, 0], a2: [0, f.D], types: types };
  }
  // cairo pentagonal: the dual of the snub square tiling; one pentagon around each snub vertex, its corners the centres of the
  // faces that meet there, taken in angular order
  function cairoDef(L) {
    var f = snubFaces(L), D = f.D, EPS = 1e-6;
    var faces = [];
    for (var tx = -1; tx <= 1; tx++) for (var ty = -1; ty <= 1; ty++) f.squares.concat(f.triangles).forEach(function (p) { faces.push(moved(p, tx * D, ty * D)); });
    var base = [];
    f.pts.forEach(function (p) {
      if (p[0] < -EPS || p[0] >= D - EPS || p[1] < -EPS || p[1] >= D - EPS) return;
      if (!base.some(function (b) { return Math.hypot(b[0] - p[0], b[1] - p[1]) < EPS; })) base.push(p);
    });
    var types = base.map(function (v, i) {
      var around = faces.filter(function (fc) { return fc.some(function (p) { return Math.hypot(p[0] - v[0], p[1] - v[1]) < EPS; }); }).map(centroid);
      around.sort(function (a, b) { return Math.atan2(a[1] - v[1], a[0] - v[0]) - Math.atan2(b[1] - v[1], b[0] - v[0]); });
      return { prefix: "C" + "ABCD".charAt(i), poly: around };
    });
    return { a1: [D, 0], a2: [0, D], types: types };
  }

  function generate(shape, r) {
    var n = r == null ? R : r;
    var w = r == null ? WINDOW : r;
    switch (shape) {
      case "hex": return hexGrid(n);
      case "square": return squareGrid(n);
      case "triangle": return triangleGrid(n);
      case "octagon": return octagonGrid(n);
      case "rhombille": return latticeTiling(rhombilleDef(1), w);
      case "trihexagonal": return latticeTiling(trihexDef(1), w);
      case "snubsquare": return latticeTiling(snubDef(1.3), w);
      case "cairo": return latticeTiling(cairoDef(2), w);
      case "elongated": return latticeTiling(elongatedDef(1.4), w);
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

  // ── frames: shared-edge adjacency and the Hide-frames modes (#1823) ──
  // The world corners of a cell (units, y up): its template turned by rot, moved to (cx, cy). Edge k joins corner k and k+1.
  function polygon(cell) {
    var t = TEMPLATES[cell.geom], c = Math.cos(cell.rot || 0), s = Math.sin(cell.rot || 0);
    return t.map(function (p) { return [cell.cx + p[0] * c - p[1] * s, cell.cy + p[0] * s + p[1] * c]; });
  }
  function vkey(p) { return Math.round(p[0] * 1e4) + "," + Math.round(p[1] * 1e4); }
  function ekey(a, b) { var x = vkey(a), y = vkey(b); return x < y ? x + "|" + y : y + "|" + x; }

  // adjacency(cells): {edges: [[edge key per edge of cell i]], nbr: [[indices of the cells across edge k]], edgeCells: {key: [indices]}}.
  // Two cells are neighbours across an edge when they share its key (the same rounded vertex pair). The prototype's triangle grid
  // is the one tiling whose rows are not edge to edge (a row is shifted by half an edge), so an edge that no cell shares exactly also
  // meets every cell whose edge lies on the same line and overlaps it; every other tiling has exactly one neighbour per shared edge.
  function adjacency(cells) {
    var polys = cells.map(polygon), edges = [], edgeCells = {}, i, k;
    for (i = 0; i < cells.length; i++) {
      var poly = polys[i], ks = [];
      for (k = 0; k < poly.length; k++) {
        var e = ekey(poly[k], poly[(k + 1) % poly.length]);
        ks.push(e);
        (edgeCells[e] = edgeCells[e] || []).push(i);
      }
      edges.push(ks);
    }
    var nbr = edges.map(function (ks, idx) {
      return ks.map(function (e) { var l = edgeCells[e]; return l.length === 2 ? [l[0] === idx ? l[1] : l[0]] : []; });
    });
    var lines = {};
    for (i = 0; i < cells.length; i++) for (k = 0; k < polys[i].length; k++) {
      if (edgeCells[edges[i][k]].length !== 1) continue;
      var a = polys[i][k], b = polys[i][(k + 1) % polys[i].length];
      var dx = b[0] - a[0], dy = b[1] - a[1], len = Math.hypot(dx, dy);
      dx /= len; dy /= len;
      if (dx < -1e-9 || (Math.abs(dx) < 1e-9 && dy < 0)) { dx = -dx; dy = -dy; }
      var lk = Math.round(dx * 1e4) + "," + Math.round(dy * 1e4) + "," + Math.round((a[0] * dy - a[1] * dx) * 1e4);
      var t0 = a[0] * dx + a[1] * dy, t1 = b[0] * dx + b[1] * dy;
      (lines[lk] = lines[lk] || []).push({ i: i, k: k, lo: Math.min(t0, t1), hi: Math.max(t0, t1) });
    }
    Object.keys(lines).forEach(function (lk) {
      var l = lines[lk];
      for (var x = 0; x < l.length; x++) for (var y = x + 1; y < l.length; y++) {
        if (l[x].i === l[y].i || Math.min(l[x].hi, l[y].hi) - Math.max(l[x].lo, l[y].lo) < 1e-6) continue;
        nbr[l[x].i][l[x].k].push(l[y].i); nbr[l[y].i][l[y].k].push(l[x].i);
      }
    });
    return { edges: edges, nbr: nbr, edgeCells: edgeCells };
  }

  // The frame of one cell under a Hide-frames mode. `painted` = is the cell painted, `nbrPainted[k]` = is the cell across edge k painted
  // (false when there is none). outline: "line" (the white 25% frame ring) or "hidden" (black, lost in the background: unpainted cells
  // under Only outside / All). edges[k]: true when edge k is drawn in the cell's own colour (both cells painted, under Only between / All).
  function frameInfo(frames, painted, nbrPainted) {
    var between = frames === "between" || frames === "all", outside = frames === "outside" || frames === "all";
    return {
      outline: outside && !painted ? "hidden" : "line",
      edges: nbrPainted.map(function (np) { return between && !!painted && !!np; }),
    };
  }

  // The frame of cell i of `cells` (with its adjacency `adj`) from the painted cells of `state`: an edge counts as "between" when it
  // has neighbours and every one of them is painted.
  function frameOfCell(state, cells, adj, i) {
    var np = adj.nbr[i].map(function (l) { return l.length > 0 && l.every(function (j) { return colorOf(state, cells[j].key) != null; }); });
    return frameInfo(state.frames, colorOf(state, cells[i].key) != null, np);
  }

  // ── state ────────────────────────────────────────────────────────
  function emptyPainted() { var p = {}; SHAPES.forEach(function (s) { p[s] = {}; }); return p; }

  function defaults() {
    return { v: 1, shape: "hex", mode: "paint", layout: "2d", scale: SCALE_DEFAULT, color: COLORS[0], frames: "off", painted: emptyPainted() };
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
    return JSON.stringify({ v: 1, shape: state.shape, mode: state.mode, layout: state.layout, scale: state.scale, color: state.color, frames: state.frames, painted: state.painted });
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
    if (FRAMES.indexOf(o.frames) >= 0) d.frames = o.frames;
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
    if (FRAMES.indexOf(q.frames) >= 0) state.frames = q.frames;
    if (q.scale != null && q.scale !== "") state.scale = clampScale(q.scale);
    return state;
  }

  return {
    R: R, WINDOW: WINDOW, SHAPES: SHAPES, FRAMES: FRAMES, TEMPLATES: TEMPLATES, MODES: MODES, LAYOUTS: LAYOUTS, COLORS: COLORS, PREFIXES: PREFIXES,
    SCALE_MIN: SCALE_MIN, SCALE_MAX: SCALE_MAX, SCALE_DEFAULT: SCALE_DEFAULT,
    hexGrid: hexGrid, squareGrid: squareGrid, triangleGrid: triangleGrid, octagonGrid: octagonGrid, generate: generate, shapeOfKey: shapeOfKey,
    polygon: polygon, ekey: ekey, adjacency: adjacency, frameInfo: frameInfo, frameOfCell: frameOfCell,
    world2d: world2d, sphere: sphere, camera3dDistance: camera3dDistance,
    defaults: defaults, clampScale: clampScale, isColor: isColor, paint: paint, erase: erase, clear: clear, activeCount: activeCount, colorOf: colorOf,
    Stroke: Stroke, startStroke: startStroke,
    encode: encode, decode: decode, applyOverrides: applyOverrides,
  };
});
