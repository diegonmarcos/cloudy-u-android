// painter/painter.js (#1808, own code): the Three.js r128 half of Cloud Canvas > Painter, the owner's "Interactive Hexagon Grid"
// prototype. The grid data, the state and the stroke rule are painter/grid.js (window.PainterGrid), tested in node.
//
// Each cell is two InstancedMeshes sharing one geometry: the outline (white, opacity 0.25, full scale) and the fill just above it
// (z+1 in 2D, radius+1 in 3D) at 0.94 scale, black when empty, the cell's colour when painted. Both DoubleSide.
//
// Persistence goes through the app's web message listener `CloudPainter` (PainterScreen.kt, asset origin and main frame only):
//   page -> app  {op:"ready"}                 the page is up; the app answers with a load
//                {op:"save", state}            the state changed (debounced) or a flush was asked; state = PainterGrid.encode(...)
//                {op:"error", message}
//   app -> page  {op:"load", state|null}      the stored state (null: the defaults)
//                {op:"flush"}                 save now (the app leaves the tab or goes to the background)
// With no bridge (a desktop browser) the state is kept in localStorage instead.
(function () {
  "use strict";
  var G = window.PainterGrid;
  var SAVE_DELAY_MS = 500;
  var FRUSTUM = 1500;
  var STORE_KEY = "cloud-painter-state";
  var bridge = window.CloudPainter || null;

  var query = {};
  location.search.replace(/^\?/, "").split("&").forEach(function (kv) { if (!kv) return; var p = kv.split("="); query[decodeURIComponent(p[0])] = decodeURIComponent(p[1] || ""); });

  var state = G.defaults();
  var stage = document.getElementById("stage");

  // ── renderer, scene, cameras, controls ──
  var renderer = new THREE.WebGLRenderer({ antialias: true });
  renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
  renderer.setSize(window.innerWidth, window.innerHeight);
  renderer.setClearColor(0x000000, 1);
  stage.appendChild(renderer.domElement);

  var scene = new THREE.Scene();
  scene.background = new THREE.Color(0x000000);

  function aspect() { return window.innerWidth / Math.max(1, window.innerHeight); }
  var ortho = new THREE.OrthographicCamera(-FRUSTUM * aspect() / 2, FRUSTUM * aspect() / 2, FRUSTUM / 2, -FRUSTUM / 2, 1, 20000);
  ortho.position.set(0, 0, 1500);
  var persp = new THREE.PerspectiveCamera(45, aspect(), 1, 20000);
  persp.position.set(0, 0, G.camera3dDistance(state.scale));
  var camera = ortho;

  var controls = new THREE.OrbitControls(camera, renderer.domElement);
  controls.enableDamping = true;
  controls.dampingFactor = 0.05;

  // ── geometries (unit size; the instance matrices carry gridScale) ──
  function geometries() {
    var s = 2 * Math.sin(Math.PI / 8);
    return {
      hex: new THREE.CircleGeometry(1, 6),
      square: new THREE.PlaneGeometry(2, 2),
      triangle: new THREE.CircleGeometry(1, 3).rotateZ(Math.PI / 2),
      octagon: new THREE.CircleGeometry(1, 8).rotateZ(Math.PI / 8),
      small_square: new THREE.PlaneGeometry(s, s).rotateZ(Math.PI / 4),
    };
  }
  var GEOM = geometries();

  var groups = [];          // [{geom, cells, outline, fill}]
  var fills = [];           // the fill meshes, raycast targets
  var BLACK = new THREE.Color(0x000000);
  var tmpColor = new THREE.Color();

  function disposeGrid() {
    groups.forEach(function (g) { scene.remove(g.outline); scene.remove(g.fill); g.outline.material.dispose(); g.fill.material.dispose(); g.outline.dispose && g.outline.dispose(); g.fill.dispose && g.fill.dispose(); });
    groups = []; fills = [];
  }

  // Builds the meshes of the current shape (one outline + one fill InstancedMesh per geometry it uses).
  function buildGrid() {
    disposeGrid();
    var cells = G.generate(state.shape);
    var byGeom = {};
    cells.forEach(function (c) { (byGeom[c.geom] = byGeom[c.geom] || []).push(c); });
    Object.keys(byGeom).forEach(function (name) {
      var list = byGeom[name];
      var outline = new THREE.InstancedMesh(GEOM[name], new THREE.MeshBasicMaterial({ color: 0xffffff, transparent: true, opacity: 0.25, side: THREE.DoubleSide }), list.length);
      var fill = new THREE.InstancedMesh(GEOM[name], new THREE.MeshBasicMaterial({ color: 0xffffff, side: THREE.DoubleSide }), list.length);
      fill.userData.cells = list;
      // the shared unit geometry sits at the origin, so a mesh-level frustum test would hide the whole grid once the view moves
      outline.frustumCulled = false; fill.frustumCulled = false;
      for (var i = 0; i < list.length; i++) fill.setColorAt(i, BLACK);
      scene.add(outline); scene.add(fill);
      groups.push({ geom: name, cells: list, outline: outline, fill: fill });
      fills.push(fill);
    });
    layoutGrid();
    paintAll();
  }

  var dummy = new THREE.Object3D();
  function place(cell, lift, k) {
    var sc = state.scale;
    dummy.position.set(0, 0, 0); dummy.rotation.set(0, 0, 0); dummy.scale.set(sc * k, sc * k, sc * k); dummy.up.set(0, 1, 0);
    if (state.layout === "3d") {
      var p = G.sphere(cell, sc, lift);
      dummy.position.set(p.x, p.y, p.z);
      dummy.lookAt(p.look.x, p.look.y, p.look.z);
      dummy.rotation.z += cell.rot;
    } else {
      var w = G.world2d(cell, sc);
      dummy.position.set(w.x, w.y, lift);
      dummy.rotation.z = cell.rot;
    }
    dummy.updateMatrix();
    return dummy.matrix;
  }

  function layoutGrid() {
    groups.forEach(function (g) {
      for (var i = 0; i < g.cells.length; i++) {
        g.outline.setMatrixAt(i, place(g.cells[i], 0, 1));
        g.fill.setMatrixAt(i, place(g.cells[i], 1, 0.94));
      }
      g.outline.instanceMatrix.needsUpdate = true;
      g.fill.instanceMatrix.needsUpdate = true;
      g.outline.computeBoundingSphere && g.outline.computeBoundingSphere();
      g.fill.computeBoundingSphere && g.fill.computeBoundingSphere();
    });
  }

  function paintAll() {
    groups.forEach(function (g) {
      for (var i = 0; i < g.cells.length; i++) {
        var c = G.colorOf(state, g.cells[i].key);
        g.fill.setColorAt(i, c ? tmpColor.set(c) : BLACK);
      }
      if (g.fill.instanceColor) g.fill.instanceColor.needsUpdate = true;
    });
    updateCount();
  }

  function paintOne(mesh, index) {
    var c = G.colorOf(state, mesh.userData.cells[index].key);
    mesh.setColorAt(index, c ? tmpColor.set(c) : BLACK);
    mesh.instanceColor.needsUpdate = true;
  }

  // ── cameras and controls per layout and mode ──
  function applyControls() {
    var view = state.mode === "view";
    controls.enablePan = view && state.layout === "2d";
    controls.enableRotate = view && state.layout === "3d";
    controls.enableZoom = true;
  }

  function useCamera() {
    camera = state.layout === "3d" ? persp : ortho;
    controls.object = camera;
    applyControls();
  }

  function center() {
    controls.target.set(0, 0, 0);
    if (state.layout === "3d") {
      persp.position.set(0, 0, G.camera3dDistance(state.scale));
      persp.up.set(0, 1, 0);
      persp.lookAt(0, 0, 0);
    } else {
      ortho.position.set(0, 0, 1500);
      ortho.up.set(0, 1, 0);
      ortho.zoom = 1;
      ortho.lookAt(0, 0, 0);
      ortho.updateProjectionMatrix();
    }
    controls.update();
  }

  function onResize() {
    var a = aspect();
    ortho.left = -FRUSTUM * a / 2; ortho.right = FRUSTUM * a / 2; ortho.top = FRUSTUM / 2; ortho.bottom = -FRUSTUM / 2;
    ortho.updateProjectionMatrix();
    persp.aspect = a;
    persp.updateProjectionMatrix();
    renderer.setSize(window.innerWidth, window.innerHeight);
  }
  window.addEventListener("resize", onResize);

  // ── painting ──
  var raycaster = new THREE.Raycaster();
  var ndc = new THREE.Vector2();
  var stroke = null;

  function hitKey(ev) {
    var r = renderer.domElement.getBoundingClientRect();
    ndc.x = ((ev.clientX - r.left) / r.width) * 2 - 1;
    ndc.y = -((ev.clientY - r.top) / r.height) * 2 + 1;
    raycaster.setFromCamera(ndc, camera);
    var hits = raycaster.intersectObjects(fills, false);
    if (!hits.length) return null;
    var best = hits[0];
    for (var i = 1; i < hits.length; i++) if (hits[i].distance < best.distance) best = hits[i];
    return { mesh: best.object, index: best.instanceId };
  }

  function strokeAt(ev) {
    if (!stroke) return;
    var h = hitKey(ev);
    if (!h || h.index == null) return;
    var key = h.mesh.userData.cells[h.index].key;
    if (stroke.touch(key)) { paintOne(h.mesh, h.index); updateCount(); scheduleSave(); }
  }

  // Only the canvas starts a stroke: the panels are siblings of #stage, so a touch on a control never reaches here.
  renderer.domElement.addEventListener("pointerdown", function (ev) {
    stroke = G.startStroke(state);
    if (!stroke) return;
    try { renderer.domElement.setPointerCapture(ev.pointerId); } catch (e) { /* no capture */ }
    strokeAt(ev);
  });
  renderer.domElement.addEventListener("pointermove", function (ev) { if (stroke) strokeAt(ev); });
  function endStroke() { stroke = null; }
  renderer.domElement.addEventListener("pointerup", endStroke);
  renderer.domElement.addEventListener("pointercancel", endStroke);
  renderer.domElement.addEventListener("pointerleave", endStroke);

  // ── UI ──
  var $ = function (id) { return document.getElementById(id); };
  var swatches = $("swatches");
  G.COLORS.forEach(function (c) {
    var b = document.createElement("button");
    b.type = "button"; b.className = "swatch"; b.style.background = c; b.dataset.color = c; b.setAttribute("aria-label", "Colour " + c);
    swatches.appendChild(b);
  });

  function updateCount() { $("active").textContent = String(G.activeCount(state)); }

  function syncUi() {
    [].forEach.call(document.querySelectorAll("[data-shape]"), function (b) { b.classList.toggle("on", b.dataset.shape === state.shape); });
    [].forEach.call(document.querySelectorAll("[data-mode]"), function (b) { b.classList.toggle("on", b.dataset.mode === state.mode); });
    [].forEach.call(document.querySelectorAll("[data-layout]"), function (b) { b.classList.toggle("on", b.dataset.layout === state.layout); });
    [].forEach.call(document.querySelectorAll("[data-color]"), function (b) { b.classList.toggle("on", b.dataset.color === state.color); });
    $("scale").value = String(state.scale);
    $("scaleValue").textContent = state.scale + "px";
    stage.style.cursor = state.mode === "view" ? "grab" : "crosshair";
    updateCount();
  }

  document.querySelectorAll("[data-shape]").forEach(function (b) {
    b.addEventListener("click", function () { if (state.shape === b.dataset.shape) return; state.shape = b.dataset.shape; buildGrid(); syncUi(); scheduleSave(); });
  });
  document.querySelectorAll("[data-mode]").forEach(function (b) {
    b.addEventListener("click", function () { state.mode = b.dataset.mode; applyControls(); syncUi(); scheduleSave(); });
  });
  document.querySelectorAll("[data-layout]").forEach(function (b) {
    b.addEventListener("click", function () { if (state.layout === b.dataset.layout) return; state.layout = b.dataset.layout; useCamera(); layoutGrid(); center(); syncUi(); scheduleSave(); });
  });
  swatches.addEventListener("click", function (ev) {
    var c = ev.target && ev.target.dataset && ev.target.dataset.color;
    if (!c) return;
    state.color = c; syncUi(); scheduleSave();
  });
  $("scale").addEventListener("input", function () {
    state.scale = G.clampScale($("scale").value);
    $("scaleValue").textContent = state.scale + "px";
    layoutGrid();
    if (state.layout === "3d") persp.position.setLength(G.camera3dDistance(state.scale));
    scheduleSave();
  });
  $("clear").addEventListener("click", function () { G.clear(state); paintAll(); scheduleSave(); });
  $("center").addEventListener("click", center);

  // ── persistence ──
  var timer = null, dirty = false;
  function send(o) { if (bridge) bridge.postMessage(JSON.stringify(o)); }
  function save() {
    if (timer) { clearTimeout(timer); timer = null; }
    if (!dirty) return;
    dirty = false;
    var text = G.encode(state);
    if (bridge) send({ op: "save", state: text });
    else try { localStorage.setItem(STORE_KEY, text); } catch (e) { /* storage full or off */ }
  }
  function scheduleSave() { dirty = true; if (timer) clearTimeout(timer); timer = setTimeout(save, SAVE_DELAY_MS); }

  function apply(text) {
    state = G.applyOverrides(G.decode(text), query);
    useCamera();
    buildGrid();
    center();
    syncUi();
    document.body.dataset.ready = "1";
  }

  if (bridge) {
    bridge.onmessage = function (ev) {
      var m; try { m = JSON.parse(ev.data); } catch (e) { return; }
      if (m.op === "load") apply(m.state);
      else if (m.op === "flush") save();
    };
  }
  document.addEventListener("visibilitychange", function () { if (document.visibilityState === "hidden") save(); });
  window.addEventListener("pagehide", save);

  // first paint with the defaults (plus the launch overrides), then the stored state replaces them
  apply(null);
  if (bridge) send({ op: "ready" });
  else { var stored = null; try { stored = localStorage.getItem(STORE_KEY); } catch (e) { /* none */ } if (stored) apply(stored); }

  window.cloudPainter = { state: function () { return state; }, save: save };

  (function loop() {
    requestAnimationFrame(loop);
    controls.update();
    renderer.render(scene, camera);
  })();
})();
