// cgc-3d: the CG apps' shared 3D model viewer and editor runtime (three.js in a WebView; open source only).
//   load GLB / glTF / VRM (@pixiv/three-vrm) / FBX; orbit + pinch + rotate camera; animation player (bundled clips.json, the
//   model's own clips, Mixamo-style clips retargeted); morph targets / blendshapes; material colour swaps; modular mesh show/hide;
//   export GLB (GLTFExporter) and a PNG snapshot.
// Driven by a declarative scene config (boot) and by scene ops (apply), both JSON. The host page maps "three" to a three.js build
// with an import map (the CG apps use Cloud Nav's copy: ../space/three.module.min.js) and serves this folder as ../cgc/.
// Host bridge (optional): window.CgcHost.event(json) receives {type: ready|loaded|clip|error|saved, ...}; exports go out in chunks
// through CgcHost.saveBegin(name, mime) / saveChunk(base64) / saveEnd(); without a host, exports download in the page.
import * as THREE from 'three';
import { OrbitControls } from '../space/OrbitControls.js';
import { GLTFLoader } from './vendor/GLTFLoader.js';
import { FBXLoader } from './vendor/FBXLoader.js';
import { GLTFExporter } from './vendor/GLTFExporter.js';
import { MeshoptDecoder } from './vendor/meshopt_decoder.module.js';
import { RoomEnvironment } from './vendor/RoomEnvironment.js';
import { VRMLoaderPlugin, VRMUtils } from './vendor/three-vrm.module.min.js';
import { buildClip, retargetMixamo } from './cgc-anim.js';

const host = () => (typeof window !== 'undefined' && window.CgcHost) || null;
function emit(type, data) {
  const msg = JSON.stringify({ type, ...(data || {}) });
  const h = host();
  if (h && h.event) h.event(msg);
  if (typeof window !== 'undefined') window.dispatchEvent(new CustomEvent('cgc', { detail: JSON.parse(msg) }));
}

const DEFAULTS = {
  background: '#121212',
  exposure: 1.0,
  camera: { fov: 28, minDistance: 0.6, maxDistance: 9, autoRotateSpeed: 2.2 },
  lights: 'studio',
  ground: { shadow: true },
  insets: { top: 0, bottom: 0 },
  clips: { url: './clips.json', default: 'idle' },
};

/** Creates the viewer in [container] (an element; the canvas fills it). [base] resolves the config's relative URLs. */
export function createViewer(container, cfgIn = {}) {
  const cfg = { ...DEFAULTS, ...cfgIn, camera: { ...DEFAULTS.camera, ...(cfgIn.camera || {}) }, insets: { ...DEFAULTS.insets, ...(cfgIn.insets || {}) } };
  // #1373 the render budget: a phone at DPR 2.75 renders 7.5x the CSS pixels; capped at 1.5 (AA only below that) the stage stays
  // sharp and a software-GL device (the CI emulator, an old GPU) is not starved, which froze the app's UI on launch.
  const dpr = Math.min(window.devicePixelRatio || 1, cfg.maxPixelRatio || 1.5);
  const renderer = new THREE.WebGLRenderer({ antialias: dpr < 1.5, alpha: false, preserveDrawingBuffer: false, powerPreference: 'low-power' });
  renderer.setPixelRatio(dpr);
  renderer.outputColorSpace = THREE.SRGBColorSpace;
  renderer.toneMapping = THREE.ACESFilmicToneMapping;
  renderer.toneMappingExposure = cfg.exposure;
  renderer.shadowMap.enabled = !!(cfg.ground && cfg.ground.shadow);
  renderer.shadowMap.type = THREE.PCFShadowMap;
  container.appendChild(renderer.domElement);
  renderer.domElement.style.touchAction = 'none';

  const scene = new THREE.Scene();
  scene.background = new THREE.Color(cfg.background);
  const pmrem = new THREE.PMREMGenerator(renderer);
  scene.environment = pmrem.fromScene(new RoomEnvironment(), 0.04).texture;
  scene.environmentIntensity = 0.55;

  // studio light: warm key (casts the contact shadow), cool fill, rim from behind
  const key = new THREE.DirectionalLight(0xfff4e8, 2.1); key.position.set(0.35, 4.2, 1.4);
  key.castShadow = renderer.shadowMap.enabled; key.shadow.mapSize.set(512, 512); key.shadow.radius = 5; key.shadow.bias = -0.0004;
  Object.assign(key.shadow.camera, { left: -1.2, right: 1.2, top: 2.3, bottom: -0.3, near: 0.1, far: 10 });
  const fill = new THREE.DirectionalLight(0xdfe8ff, 0.7); fill.position.set(-2.4, 1.6, 1.8);
  const rim = new THREE.DirectionalLight(0xffffff, 1.4); rim.position.set(-0.8, 2.6, -2.8);
  scene.add(key, fill, rim, new THREE.HemisphereLight(0xffffff, 0x303030, 0.35));
  const ground = new THREE.Mesh(new THREE.CircleGeometry(1.4, 48), new THREE.ShadowMaterial({ opacity: 0.38 }));
  ground.rotation.x = -Math.PI / 2; ground.receiveShadow = true; ground.visible = renderer.shadowMap.enabled; scene.add(ground);

  const camera = new THREE.PerspectiveCamera(cfg.camera.fov, 1, 0.02, 60);
  const controls = new OrbitControls(camera, renderer.domElement);
  Object.assign(controls, { enableDamping: true, dampingFactor: 0.08, enablePan: false, minDistance: cfg.camera.minDistance,
    maxDistance: cfg.camera.maxDistance, autoRotateSpeed: cfg.camera.autoRotateSpeed, minPolarAngle: 0.15, maxPolarAngle: Math.PI - 0.25 });

  const state = { model: null, vrm: null, mixer: null, actions: new Map(), clipDefs: [], current: null, home: null,
    insets: { ...cfg.insets }, recolored: new Map() };
  let last = performance.now();

  function resize() {
    const w = container.clientWidth || window.innerWidth, h = container.clientHeight || window.innerHeight;
    renderer.setSize(w, h, false);
    renderer.domElement.style.width = '100%'; renderer.domElement.style.height = '100%';
    camera.aspect = w / h;
    // keep the model centred in the part of the view the host's chrome leaves free (top bar, bottom sheet)
    const shift = ((state.insets.bottom || 0) - (state.insets.top || 0)) / 2;
    if (shift) camera.setViewOffset(w, h, 0, shift, w, h); else camera.clearViewOffset();
    camera.updateProjectionMatrix();
  }
  new ResizeObserver(resize).observe(container);
  window.addEventListener('resize', resize);

  function frameModel() {
    if (!state.model) return;
    const box = new THREE.Box3().setFromObject(state.model);
    const size = box.getSize(new THREE.Vector3()), c = box.getCenter(new THREE.Vector3());
    // fit the model into the part of the view the insets leave free, with a margin
    const H = container.clientHeight || window.innerHeight;
    const free = Math.max(0.3, (H - (state.insets.top || 0) - (state.insets.bottom || 0)) / H);
    const h = Math.max(size.y, size.x * 0.8 / Math.max(camera.aspect, 0.3), 0.1);
    const dist = (h * 0.52) / (free * Math.tan(THREE.MathUtils.degToRad(camera.fov / 2))) + size.z / 2;
    state.home = { target: new THREE.Vector3(c.x, box.min.y + size.y * 0.5, c.z), pos: new THREE.Vector3(c.x, box.min.y + size.y * 0.53, c.z + dist) };
    ground.position.y = box.min.y + 0.002; ground.scale.setScalar(Math.max(size.x, size.z, 0.5) * 1.1);
    key.target.position.copy(state.home.target); scene.add(key.target);
    resetCamera();
  }
  function resetCamera() {
    if (!state.home) return;
    controls.autoRotate = false;
    camera.position.copy(state.home.pos); controls.target.copy(state.home.target); controls.update();
    emit('camera', { autoRotate: false });
  }

  function loaderFor(kind) {
    if (kind === 'fbx') return new FBXLoader();
    const l = new GLTFLoader(); l.setMeshoptDecoder(MeshoptDecoder); l.register((p) => new VRMLoaderPlugin(p)); return l;
  }
  function kindOf(url, kind) { return (kind || String(url).split('?')[0].split('.').pop() || 'glb').toLowerCase(); }

  async function loadClipDefs() {
    if (state.clipDefs.length || !cfg.clips || !cfg.clips.url) return;
    try { state.clipDefs = (await (await fetch(new URL(cfg.clips.url, import.meta.url))).json()).clips || []; } catch (e) { emit('error', { where: 'clips', message: String(e) }); }
  }

  /** Loads the model at [url] (kind glb | gltf | vrm | fbx, from the extension when omitted), replacing the current one. */
  async function load(url, kind) {
    const k = kindOf(url, kind);
    await loadClipDefs();
    const res = await loaderFor(k).loadAsync(url);
    let root, vrm = null, own = [];
    if (k === 'fbx') { root = res; own = res.animations || []; root.scale.multiplyScalar(cfgIn.fbxScale || 0.01); }
    else { vrm = res.userData && res.userData.vrm; root = vrm ? vrm.scene : res.scene; own = res.animations || []; if (vrm) VRMUtils.rotateVRM0(vrm); }
    if (state.model) { scene.remove(state.model); state.mixer && state.mixer.stopAllAction(); }
    root.traverse((o) => { if (o.isMesh) { o.castShadow = true; o.frustumCulled = false; } });
    scene.add(root);
    Object.assign(state, { model: root, vrm, mixer: new THREE.AnimationMixer(root), actions: new Map(), current: null, recolored: new Map() });
    for (const c of own) state.actions.set(c.name || `clip${state.actions.size}`, state.mixer.clipAction(c));
    for (const d of state.clipDefs) state.actions.set(d.id, state.mixer.clipAction(buildClip(THREE, root, d, vrm)));
    frameModel();
    const info = describe();
    emit('loaded', info);
    if (cfg.clips && cfg.clips.default && state.actions.has(cfg.clips.default)) play(cfg.clips.default);
    return info;
  }

  function meshes() { const out = []; state.model && state.model.traverse((o) => { if (o.isMesh) out.push(o); }); return out; }
  function describe() {
    const ms = meshes(), morphs = new Set(), mats = new Set();
    ms.forEach((m) => { Object.keys(m.morphTargetDictionary || {}).forEach((n) => morphs.add(n)); [].concat(m.material).forEach((x) => mats.add(x.name)); });
    return { meshes: ms.map((m) => m.name), materials: [...mats], morphs: [...morphs], clips: [...state.actions.keys()], vrm: !!state.vrm };
  }
  // a target names a mesh, a material, or a node whose subtree is meant (a VRM part, an FBX group)
  function targets(name) {
    const out = [];
    state.model && state.model.traverse((o) => {
      if (o.name === name) o.traverse((c) => { if (c.isMesh) out.push(c); });
      else if (o.isMesh && [].concat(o.material).some((m) => m.name === name)) out.push(o);
    });
    return [...new Set(out)];
  }

  /** Plays clip [id] (cross-fades from the current one); null stops. */
  function play(id, fade = 0.35) {
    const next = id ? state.actions.get(id) : null;
    if (state.current && state.current !== next) state.current.fadeOut(fade);
    if (next) { next.reset().setEffectiveWeight(1).fadeIn(fade).play(); }
    state.current = next;
    emit('clip', { id: id || null });
  }

  /** Retargets a Mixamo-style clip file (FBX or GLB) onto the model and registers it as [id]. */
  async function addMixamoClip(id, url, sourceHipsHeight) {
    const k = kindOf(url);
    const res = await loaderFor(k).loadAsync(url);
    const clip = (res.animations || [])[0];
    if (!clip || !state.model) return false;
    state.actions.set(id, state.mixer.clipAction(retargetMixamo(THREE, clip, state.model, sourceHipsHeight || 100, state.vrm)));
    return true;
  }

  function setMorph(name, v) {
    let n = 0;
    if (state.vrm && state.vrm.expressionManager && state.vrm.expressionManager.getExpression(name)) { state.vrm.expressionManager.setValue(name, v); n++; }
    meshes().forEach((m) => { const i = m.morphTargetDictionary && m.morphTargetDictionary[name]; if (i !== undefined) { m.morphTargetInfluences[i] = v; n++; } });
    return n;
  }

  // A colour swap keeps the texture's shading: the map is turned into its luminance once (normalised to a mid-light grey), and
  // the material colour then tints it, so a red hoodie can become any colour and keep its folds.
  function neutralMap(tex) {
    const img = tex && tex.image;
    if (!img || !img.width) return null;
    const c = document.createElement('canvas'); c.width = img.width; c.height = img.height;
    const g = c.getContext('2d'); g.drawImage(img, 0, 0);
    const d = g.getImageData(0, 0, c.width, c.height), p = d.data;
    let sum = 0; for (let i = 0; i < p.length; i += 4) sum += 0.2126 * p[i] + 0.7152 * p[i + 1] + 0.0722 * p[i + 2];
    const mean = sum / (p.length / 4) || 1, k = 205 / mean;
    for (let i = 0; i < p.length; i += 4) { const l = Math.min(255, (0.2126 * p[i] + 0.7152 * p[i + 1] + 0.0722 * p[i + 2]) * k); p[i] = p[i + 1] = p[i + 2] = l; }
    g.putImageData(d, 0, 0);
    const t = new THREE.CanvasTexture(c); t.colorSpace = THREE.SRGBColorSpace; t.flipY = tex.flipY; t.wrapS = tex.wrapS; t.wrapT = tex.wrapT;
    t.channel = tex.channel; return t;
  }
  /** Recolours [name]'s materials to [hex] (null = their own); [keepTexture] multiplies the texture as is (a tint) instead. */
  function setColor(name, hex, keepTexture = false) {
    let n = 0;
    targets(name).forEach((m) => [].concat(m.material).forEach((mat) => {
      if (!state.recolored.has(mat)) state.recolored.set(mat, { map: mat.map, color: mat.color.clone(), neutral: null });
      const r = state.recolored.get(mat);
      if (hex == null || hex === '') { mat.map = r.map; mat.color.copy(r.color); }
      else {
        if (keepTexture) mat.map = r.map;
        else { if (r.map && !r.neutral) r.neutral = neutralMap(r.map); if (r.neutral) mat.map = r.neutral; }
        mat.color.set(hex);
      }
      mat.needsUpdate = true; n++;
    }));
    return n;
  }
  function setVisible(name, on) { const t = targets(name); t.forEach((m) => { m.visible = !!on; }); return t.length; }

  /** Applies scene ops: {clip, colors: {target: hex|null}, tints: {target: hex|null}, visible: {target: bool}, morphs: {name: 0..1}, autoRotate, camera: "reset"}. */
  function apply(ops) {
    const o = typeof ops === 'string' ? JSON.parse(ops) : ops || {};
    if (o.colors) for (const [t, hex] of Object.entries(o.colors)) setColor(t, hex);
    if (o.tints) for (const [t, hex] of Object.entries(o.tints)) setColor(t, hex, true);
    if (o.visible) for (const [t, on] of Object.entries(o.visible)) setVisible(t, on);
    if (o.morphs) for (const [n, v] of Object.entries(o.morphs)) setMorph(n, v);
    if ('clip' in o && (o.clip == null || state.actions.has(o.clip)) && (!state.current || state.current !== state.actions.get(o.clip))) play(o.clip);
    if ('autoRotate' in o) { controls.autoRotate = !!o.autoRotate; emit('camera', { autoRotate: controls.autoRotate }); }
    if (o.camera === 'reset') resetCamera();
    if (o.insets) { state.insets = { ...state.insets, ...o.insets }; resize(); frameModel(); }
  }

  function toBase64(buf) {
    const b = new Uint8Array(buf); let s = '';
    for (let i = 0; i < b.length; i += 0x8000) s += String.fromCharCode.apply(null, b.subarray(i, i + 0x8000));
    return btoa(s);
  }
  function save(name, mime, buf) {
    const h = host();
    if (h && h.saveBegin) {
      h.saveBegin(name, mime);
      const b = new Uint8Array(buf), step = 3 * 256 * 1024;
      for (let i = 0; i < b.length; i += step) h.saveChunk(toBase64(b.subarray(i, i + step)));
      h.saveEnd();
    } else {
      const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([buf], { type: mime })); a.download = name; a.click();
    }
    emit('saved', { name, mime, bytes: buf.byteLength });
  }
  /** Exports the model (current colours, visibility and every clip) as a binary glTF. */
  async function exportGLB(name = 'avatar.glb') {
    if (!state.model) return 0;
    const clips = [...state.actions.values()].map((a) => a.getClip());
    const buf = await new GLTFExporter().parseAsync(state.model, { binary: true, animations: clips, onlyVisible: true });
    save(name, 'model/gltf-binary', buf);
    return buf.byteLength;
  }
  /** A PNG of the current view (rendered on demand, so the drawing buffer need not be preserved). */
  async function snapshotPNG(name = 'avatar.png') {
    renderer.render(scene, camera);
    const blob = await new Promise((r) => renderer.domElement.toBlob(r, 'image/png'));
    const buf = await blob.arrayBuffer();
    save(name, 'image/png', buf);
    return buf.byteLength;
  }

  // #1373 at most cfg.fps frames a second (30 by default) and none while the page is hidden: the scene animates at 30 just as
  // well, and the other half of the GPU stays with the app's own UI.
  let frameMs = 1000 / (cfg.fps || 30);
  let lastDraw = 0;
  // #1373 lite profile: a software rasteriser (SwiftShader / llvmpipe, the CI emulator, some old phones) or frames measured
  // slow get DPR 1, no shadow pass and 15 fps, so the WebView's GPU fence never stalls the app's own frames (am_anr
  // "Buffer processing hung up due to stuck fence" in the first avatar proof).
  let lite = false, slow = 0;
  function goLite(why) {
    if (lite) return; lite = true;
    renderer.setPixelRatio(1); renderer.shadowMap.enabled = false; key.castShadow = false; ground.visible = false;
    frameMs = 1000 / 15; resize(); emit('quality', { lite: true, why });
  }
  try {
    const gl = renderer.getContext(); const ext = gl.getExtension('WEBGL_debug_renderer_info');
    const name = String(ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : gl.getParameter(gl.RENDERER));
    if (/swiftshader|llvmpipe|softpipe|software/i.test(name)) goLite('software renderer: ' + name);
  } catch (e) { /* no renderer string: the frame timer decides */ }
  function tick() {
    const now = performance.now();
    if (document.hidden || now - lastDraw < frameMs - 2) return;
    lastDraw = now;
    const dt = Math.min((now - last) / 1000, 0.1); last = now;
    state.mixer && state.mixer.update(dt);
    state.vrm && state.vrm.update(dt);
    controls.update();
    const t0 = performance.now();
    renderer.render(scene, camera);
    if (!lite && state.model) { slow = performance.now() - t0 > 45 ? slow + 1 : 0; if (slow >= 4) goLite('slow frames'); }
  }
  renderer.setAnimationLoop(tick);
  resize();

  return { load, play, apply, setMorph, setColor, setVisible, addMixamoClip, exportGLB, snapshotPNG, resetCamera, describe,
    get three() { return { THREE, scene, camera, renderer, controls }; } };
}

/**
 * Page entry: builds a viewer in document.body from [config] ({model: {url, kind}, background, camera, clips, insets, ...}),
 * loads the model and exposes window.cgc = {apply, exportGLB, snapshotPNG, play, load, describe, resetCamera} for the host.
 */
export async function boot(config) {
  const el = document.getElementById('cgc') || document.body;
  const v = createViewer(el, config || {});
  window.cgc = v;
  const sz = () => { const c = v.three.renderer.domElement; return { css: [el.clientWidth, el.clientHeight], canvas: [c.width, c.height], dpr: window.devicePixelRatio }; };
  emit('ready', sz());
  if (config && config.model && config.model.url) {
    // the empty stage is presented first; the GLB is decoded and uploaded after that frame, never inside the page's first one
    await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)));
    try { await v.load(config.model.url, config.model.kind); emit('size', sz()); } catch (e) { emit('error', { where: 'load', message: String(e && e.message || e) }); }
  }
  return v;
}
