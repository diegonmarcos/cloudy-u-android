// libs:cad-core viewer (#1404): one three.js stage; window.cadOpen({lib, url, format}) imports ./<lib>/loader.js (its own lib)
// and frames what it returns. Events go to the host as JSON through CadHost.post.
import * as THREE from 'three';
import { OrbitControls } from '../space/OrbitControls.js';
const post = (o) => { try { window.CadHost && CadHost.post(JSON.stringify(o)); } catch (e) {} };
const status = (t) => { document.getElementById('s').textContent = t; };
const renderer = new THREE.WebGLRenderer({ antialias: true });
renderer.setPixelRatio(1); // software GL on emulators and low-end phones: never more than one sample per pixel
document.body.appendChild(renderer.domElement);
const scene = new THREE.Scene(); scene.background = new THREE.Color(0x0e1116);
scene.add(new THREE.HemisphereLight(0xffffff, 0x445566, 1.6));
const sun = new THREE.DirectionalLight(0xffffff, 1.8); sun.position.set(3, 5, 4); scene.add(sun);
const camera = new THREE.PerspectiveCamera(45, 1, 0.01, 1e6);
const controls = new OrbitControls(camera, renderer.domElement); controls.enableDamping = false;
// Render on demand (a still scene costs nothing): after a camera move, a resize and a load. A continuous loop of a 250k-triangle hull starves the device.
let queued = false;
function draw() { queued = false; controls.update(); renderer.render(scene, camera); }
function render() { if (!queued) { queued = true; requestAnimationFrame(draw); } }
controls.addEventListener('change', render);
let current = null;
function resize() { const w = innerWidth, h = innerHeight; renderer.setSize(w, h, false); camera.aspect = w / Math.max(h, 1); camera.updateProjectionMatrix(); render(); }
addEventListener('resize', resize); resize();
function frame(obj) {
  const box = new THREE.Box3().setFromObject(obj); const size = box.getSize(new THREE.Vector3()); const c = box.getCenter(new THREE.Vector3());
  const r = Math.max(size.x, size.y, size.z) || 1;
  camera.near = r / 1000; camera.far = r * 100; camera.updateProjectionMatrix();
  camera.position.copy(c).add(new THREE.Vector3(r * 0.9, r * 0.55, r * 0.9)); controls.target.copy(c); controls.update(); render();
}
window.onerror = (m) => post({ type: 'error', message: String(m) });
window.addEventListener('unhandledrejection', (e) => post({ type: 'error', message: String(e.reason && e.reason.message || e.reason) }));
// What the canvas drew, as a PNG data URL (an emulator screenshot of a WebView does not capture WebGL, so the proof pulls this).
window.cadSnapshot = () => { controls.update(); renderer.render(scene, camera); return renderer.domElement.toDataURL('image/png'); };
window.cadOpen = async ({ lib, url, format }) => {
  status('Loading ' + format.toUpperCase() + '…');
  try {
    const m = await import('./' + lib + '/loader.js');
    const obj = await m.load(url, format);
    if (current) scene.remove(current);
    current = obj; scene.add(obj); frame(obj);
    let tris = 0; obj.traverse((o) => { if (o.isMesh && o.geometry) tris += (o.geometry.index ? o.geometry.index.count : o.geometry.attributes.position.count) / 3; });
    status(format.toUpperCase() + ' · libs:cad-' + lib);
    post({ type: 'shown', lib, format, triangles: Math.round(tris) });
  } catch (e) { status('Failed: ' + e.message); post({ type: 'error', message: String(e.message || e) }); }
};
post({ type: 'ready' });
