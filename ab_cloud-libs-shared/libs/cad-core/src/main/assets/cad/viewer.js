// libs:cad-core viewer (#1404): one three.js stage; window.cadOpen({lib, url, format}) imports ./<lib>/loader.js (its own lib)
// and frames what it returns. Events go to the host as JSON through CadHost.post.
import * as THREE from 'three';
import { OrbitControls } from '../space/OrbitControls.js';
const post = (o) => { try { window.CadHost && CadHost.post(JSON.stringify(o)); } catch (e) {} };
const status = (t) => { document.getElementById('s').textContent = t; };
const D = window.__cad || { err() {}, stageTo() {}, now: () => 0 }; // #1631 the diagnostics state of viewer.html
D.moduleLoaded = true; D.stageTo('creating the WebGL renderer');
let renderer;
try { renderer = new THREE.WebGLRenderer({ antialias: true, preserveDrawingBuffer: true }); D.rendererCreated = true; }
catch (e) { D.err('WebGLRenderer: ' + (e && e.message || e)); D.stageTo('renderer creation failed'); post({ type: 'error', message: 'the WebGL renderer could not be created: ' + (e && e.message || e) }); throw e; }
renderer.setPixelRatio(1); // software GL on emulators and low-end phones: never more than one sample per pixel
// #1658 the stage holds the WebGL canvas and, under it in the DOM, a plain 2D canvas (#v2). Presentation 'webgl' shows the WebGL canvas itself. When the
// host measures that the WebGL layer does not reach the screen (a WebView compositor that drops the GPU canvas while the framebuffer holds the model),
// it asks for '2d' (or 'host'): the WebGL canvas leaves the layout and every rendered frame is copied into the CPU-backed 2D canvas, which the page
// rasterises like any image. Gestures are taken on the stage, so they work in every presentation.
const stage = document.getElementById('stage') || document.body;
stage.appendChild(renderer.domElement);
const v2 = document.createElement('canvas'); v2.id = 'v2'; v2.style.cssText = 'position:absolute;left:0;top:0;display:none'; stage.appendChild(v2);
let present = 'webgl', ctx2 = null; D.present = present;
function blit() {
  if (present === 'webgl') return;
  const c = renderer.domElement;
  if (v2.width !== c.width || v2.height !== c.height) { v2.width = c.width; v2.height = c.height; }
  ctx2 = ctx2 || v2.getContext('2d', { alpha: false, willReadFrequently: true });
  ctx2.drawImage(c, 0, 0);
}
const scene = new THREE.Scene(); scene.background = new THREE.Color(0x0e1116);
scene.add(new THREE.HemisphereLight(0xffffff, 0x445566, 1.6));
const sun = new THREE.DirectionalLight(0xffffff, 1.8); sun.position.set(3, 5, 4); scene.add(sun);
const camera = new THREE.PerspectiveCamera(45, 1, 0.01, 1e6);
const controls = new OrbitControls(camera, stage === document.body ? renderer.domElement : stage); controls.enableDamping = false;
controls.enableRotate = true; controls.enableZoom = true; controls.enablePan = true; // one finger orbits, a pinch zooms, two fingers pan
// #1542 a lost GL context is reported, never a silent blank canvas (a phone GPU can drop it under memory pressure)
renderer.domElement.addEventListener('webglcontextlost', (e) => { e.preventDefault(); D.contextLost = true; D.err('WebGL context lost'); post({ type: 'error', message: 'WebGL context lost' }); });
renderer.domElement.addEventListener('webglcontextrestored', () => { D.contextLost = false; D.restored++; });
D.infoFn = () => ({ calls: renderer.info.render.calls, triangles: renderer.info.render.triangles, geometries: renderer.info.memory.geometries, textures: renderer.info.memory.textures });
// Render on demand (a still scene costs nothing): after a camera move, a resize and a load. A continuous loop of a 250k-triangle hull starves the device.
let queued = false;
function draw() {
  queued = false;
  try { controls.update(); renderer.render(scene, camera); blit(); D.frames++; D.lastFrameMs = D.now(); if (D.firstFrameMs < 0) D.firstFrameMs = D.lastFrameMs; }
  catch (e) { D.err('render: ' + (e && e.message || e)); post({ type: 'error', message: 'render failed: ' + (e && e.message || e) }); }
}
function render() { if (!queued) { queued = true; requestAnimationFrame(draw); } }
controls.addEventListener('change', render);
let current = null;
function resize() { const w = innerWidth || document.documentElement.clientWidth || 1, h = innerHeight || document.documentElement.clientHeight || 1; renderer.setSize(w, h, false); camera.aspect = w / Math.max(h, 1); camera.updateProjectionMatrix(); render(); }
addEventListener('resize', resize);
// #1560 the Compose WebView lays out after the page loads, and a layout change there does not always fire a window 'resize'; observe the body so a 0-sized first paint corrects itself instead of staying a black canvas.
if (window.ResizeObserver) new ResizeObserver(resize).observe(document.body); resize();
function frame(obj) {
  const box = new THREE.Box3().setFromObject(obj); const size = box.getSize(new THREE.Vector3()); const c = box.getCenter(new THREE.Vector3());
  const r = Math.max(size.x, size.y, size.z) || 1;
  camera.near = r / 1000; camera.far = r * 100; camera.updateProjectionMatrix();
  framedRadius = r; framedCenter = c.clone();
  camera.position.copy(c).add(new THREE.Vector3(r * 0.9, r * 0.55, r * 0.9)); controls.target.copy(c); controls.update(); render();
}
// #1542 fit-to-view and the wireframe toggle, driven by the host's buttons.
let wire = false;
function applyWire() { if (current) current.traverse((o) => { if (o.isMesh) (Array.isArray(o.material) ? o.material : [o.material]).forEach((m) => { m.wireframe = wire; }); }); render(); }
window.cadFit = () => { if (current) frame(current); };
window.cadWireframe = (on) => { wire = on === undefined ? !wire : !!on; applyWire(); return wire; };
window.onerror = (m) => post({ type: 'error', message: String(m) });
window.addEventListener('unhandledrejection', (e) => post({ type: 'error', message: String(e.reason && e.reason.message || e.reason) }));
// What the canvas drew, as a PNG data URL (an emulator screenshot of a WebView does not capture WebGL, so the proof pulls this).
window.cadSnapshot = () => { controls.update(); renderer.render(scene, camera); blit(); return renderer.domElement.toDataURL('image/png'); };
// #1560 what the canvas holds, read back from the GL framebuffer: the share of pixels that differ from the background. A model that loads but
// paints nothing is reported to the host (type 'painted') instead of staying a silent black box; the host logs it.
window.cadPaint = () => {
  controls.update(); renderer.render(scene, camera); blit();
  const gl = renderer.getContext(), w = gl.drawingBufferWidth, h = gl.drawingBufferHeight, px = new Uint8Array(w * h * 4);
  gl.readPixels(0, 0, w, h, gl.RGBA, gl.UNSIGNED_BYTE, px);
  let n = 0; for (let i = 0; i < px.length; i += 4) if (Math.abs(px[i] - 14) + Math.abs(px[i + 1] - 17) + Math.abs(px[i + 2] - 22) > 12) n++;
  const dbg = gl.getExtension('WEBGL_debug_renderer_info');
  return { type: 'painted', pct: +(100 * n / Math.max(w * h, 1)).toFixed(2), w, h, gl: dbg ? gl.getParameter(dbg.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER) };
};
// #1658 the presentation ('webgl' | '2d' | 'host'), set by the host from what it measured on the screen; 'host' is '2d' on the page side, the host
// also draws the frames it pulls with cadFrameShot over the WebView.
window.cadPresent = (mode) => {
  if (mode === undefined) return present;
  present = mode === 'webgl' ? 'webgl' : mode === 'host' ? 'host' : '2d'; D.present = present;
  renderer.domElement.style.display = present === 'webgl' ? 'block' : 'none'; v2.style.display = present === 'webgl' ? 'none' : 'block';
  try { controls.update(); renderer.render(scene, camera); blit(); } catch (e) { D.err('present ' + present + ': ' + (e && e.message || e)); }
  return present;
};
// The last rendered frame as "<frame number>|<JPEG data URL>", or '' when no frame was drawn since frame [since] (the host polls it in 'host' presentation).
window.cadFrameShot = (since) => (D.frames === since ? '' : D.frames + '|' + renderer.domElement.toDataURL('image/jpeg', 0.9));
// #1664 the camera, driven by the HOST's own gestures (Compose takes the touch over the viewer in every presentation and forwards it here), so a drag
// orbits, a pinch zooms and two fingers pan whether the frame reaches the screen through the WebGL canvas, the 2D canvas or an image the app draws.
// Each call moves the camera around controls.target and renders one frame (which the 2D / host presentations copy or pull). Pixels are CSS px.
const _off = new THREE.Vector3(), _sph = new THREE.Spherical(), _right = new THREE.Vector3(), _up = new THREE.Vector3();
const EPS = 1e-4;
function commit() { camera.lookAt(controls.target); camera.updateMatrixWorld(); render(); return D.frames; }
window.cadOrbit = (dx, dy) => {
  const h = Math.max(innerHeight || 1, 1);
  _off.copy(camera.position).sub(controls.target); _sph.setFromVector3(_off);
  _sph.theta -= 2 * Math.PI * (+dx || 0) / h; _sph.phi = Math.min(Math.PI - EPS, Math.max(EPS, _sph.phi - 2 * Math.PI * (+dy || 0) / h));
  _off.setFromSpherical(_sph); camera.position.copy(controls.target).add(_off); return commit();
};
// [scale] > 1 brings the camera closer (a pinch opening); the distance is kept inside the model's framing range.
window.cadZoom = (scale) => {
  const s = +scale; if (!(s > 0) || !isFinite(s)) return D.frames;
  _off.copy(camera.position).sub(controls.target);
  const r = current ? framedRadius : 1, d = Math.min(Math.max(_off.length() / s, r * 0.02), r * 40);
  _off.setLength(d); camera.position.copy(controls.target).add(_off); return commit();
};
window.cadPan = (dx, dy) => {
  const h = Math.max(innerHeight || 1, 1), d = camera.position.distanceTo(controls.target);
  const k = 2 * d * Math.tan((camera.fov * Math.PI / 180) / 2) / h;
  _right.setFromMatrixColumn(camera.matrix, 0).multiplyScalar(-(+dx || 0) * k); _up.setFromMatrixColumn(camera.matrix, 1).multiplyScalar((+dy || 0) * k);
  camera.position.add(_right).add(_up); controls.target.add(_right).add(_up); return commit();
};
// The view presets: front (looking along -Y of the model's up axis frame: from +Z), side (+X), top (+Y), iso (the framing view); reset = iso + fit.
let framedRadius = 1, framedCenter = new THREE.Vector3();
const PRESETS = { front: [0, 0, 1], back: [0, 0, -1], side: [1, 0, 0], left: [-1, 0, 0], top: [0, 1, 0.0001], iso: [0.9, 0.55, 0.9] };
window.cadView = (name) => {
  const v = PRESETS[name]; if (!v || !current) return '';
  const dir = new THREE.Vector3(v[0], v[1], v[2]).normalize(), dist = framedRadius * (name === 'iso' ? 1.4 : 1.9);
  controls.target.copy(framedCenter); camera.position.copy(framedCenter).addScaledVector(dir, dist); commit(); return name;
};
window.cadReset = () => { if (current) { frame(current); window.cadView('iso'); } return D.frames; };
// The stage background: 'dark' (the viewer's own #0e1116, the colour every paint measurement compares to) or 'light'.
let bgMode = 'dark';
window.cadBackground = (mode) => {
  bgMode = mode === undefined ? (bgMode === 'dark' ? 'light' : 'dark') : (mode === 'light' ? 'light' : 'dark');
  scene.background = new THREE.Color(bgMode === 'light' ? 0xe8ecf1 : 0x0e1116); document.body.style.background = bgMode === 'light' ? '#e8ecf1' : '#0e1116';
  render(); return bgMode;
};
// A hash of the frame the page shows (what render-check compares before and after a gesture, in each presentation).
window.cadFrameHash = () => {
  const c = present === 'webgl' ? renderer.domElement : v2;
  const t = document.createElement('canvas'); t.width = 64; t.height = 48; const g = t.getContext('2d'); g.drawImage(c, 0, 0, 64, 48);
  const d = g.getImageData(0, 0, 64, 48).data; let h = 0; for (let i = 0; i < d.length; i++) h = (h * 31 + d[i]) | 0; return h;
};
// A canvas the WebView has not laid out yet is 1x1: nothing can be judged on it. Wait for a real size; a viewer that never gets one says so.
const sized = () => renderer.domElement.width >= 16 && renderer.domElement.height >= 16;
async function waitSized() {
  for (let i = 0; i < 100 && !sized(); i++) { await new Promise((r) => setTimeout(r, 50)); resize(); }
  if (!sized()) throw new Error('the viewer has no size (' + renderer.domElement.width + 'x' + renderer.domElement.height + ', window ' + innerWidth + 'x' + innerHeight + '): the WebView is collapsed or not laid out');
}
window.cadOpen = async ({ lib, url, format }) => {
  status('Loading ' + format.toUpperCase() + '…');
  D.opened = url; D.loadMs = -1; D.shownMs = -1; D.stageTo('importing the ' + lib + ' loader');
  try {
    const m = await import('./' + lib + '/loader.js');
    D.stageTo('loading ' + url);
    const obj = await m.load(url, format);
    D.loadMs = D.now(); D.stageTo('framing the model');
    if (current) scene.remove(current);
    current = obj; scene.add(obj); applyWire();
    D.stageTo('waiting for a sized canvas'); await waitSized(); frame(obj);
    let tris = 0; obj.traverse((o) => { if (o.isMesh && o.geometry) tris += (o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3; });
    D.triangles = Math.round(tris); D.shownMs = D.now(); D.stageTo('shown');
    status(format.toUpperCase() + ' · libs:cad-' + lib);
    post({ type: 'shown', lib, format, triangles: Math.round(tris) });
    const pt = window.cadPaint(); post(pt);
    if (pt.pct < 0.1) post({ type: 'error', message: 'the canvas drew nothing (' + pt.w + 'x' + pt.h + ', ' + pt.gl + ')' });
  } catch (e) { D.err('cadOpen ' + url + ': ' + (e && e.message || e)); D.stageTo('failed: ' + (D.stage || '')); status('Failed: ' + e.message); post({ type: 'error', message: String(e.message || e) }); }
};
D.ready = true; D.stageTo('ready'); post({ type: 'ready' });
