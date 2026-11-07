#!/usr/bin/env node
// Screenshot render-verification for the CAD/Galaxy 3D stack (#1404).
// Proves three real renders — looping and self-diagnosing until each verifiably paints,
// then asserting the pixels are a genuine scene (non-blank, multi-colour), not an empty canvas:
//   A) 796floor  — the Omniverse/Galaxy default world "floor796" (ad_CG-Omniverse Store.kt /
//                  data/fwa.md), i.e. the https://floor796.com canvas site the Galaxy loads.
//   B) CAD boat  — the one-lilo 36 m sailing-yacht GLB (hardtech-db-models) through libs:cad-mesh.
//   C) CAD house — a real detached-house IFC through libs:cad-ifc (web-ifc.wasm, bundled).
//
// It drives the REAL committed viewer assets (cad-core viewer.html/viewer.js, the cad-mesh /
// cad-ifc loaders, the three.js build in ac_cloud-nav, the web-ifc.wasm in cad-ifc), served over
// a tiny static HTTP server so wasm/fetch/CORS behave as on device. Chromium + Playwright must be
// available (require('playwright'); chromium.launch() needs no options) — do NOT run
// `playwright install`. Software WebGL is forced with the ANGLE/SwiftShader flags below.
//
// Run:  node render-verify.mjs            (proofs -> ./render-proof next to this file)
//   env PROOF_DIR=/path     where to write 796floor.png, cad-boat.png, cad-house.png
//   env FLOOR796_URL=...    override the floor796 world URL (default from the store catalogue)
//   env CAD_BOAT_GLB=...    local boat .glb (else fetched from the hardtech-db-models LFS mirror)
//   env CAD_HOUSE_IFC=...   local house .ifc (else fetched from the pinned sample URL)
//   env MAX_ATTEMPTS=6      render retries per target before declaring failure
// Exit 0 iff all three verifiably render.

import http from 'node:http';
import path from 'node:path';
import os from 'node:os';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import { mkdir, writeFile, readFile, cp, access, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const require = createRequire(import.meta.url);

function loadPlaywright() {
  for (const p of ['playwright', 'playwright-core', '/opt/node-tools/node_modules/playwright']) {
    try { return require(p); } catch (_) {}
  }
  throw new Error('playwright not found — it must be pre-installed (do not run `playwright install`)');
}
const { chromium } = loadPlaywright();

// --- repo root: the directory that contains ab_cloud-libs-shared ---
function repoRoot() {
  let d = __dirname;
  for (let i = 0; i < 12; i++) {
    if (existsSync(path.join(d, 'ab_cloud-libs-shared'))) return d;
    const up = path.dirname(d); if (up === d) break; d = up;
  }
  throw new Error('cannot locate repo root (ab_cloud-libs-shared) from ' + __dirname);
}
const ROOT = repoRoot();
const LIBS = path.join(ROOT, 'ab_cloud-libs-shared', 'libs');
const SPACE = path.join(ROOT, 'ac_cloud-nav', 'app', 'src', 'main', 'assets', 'space');

const FLOOR796_URL = process.env.FLOOR796_URL || 'https://floor796.com';
const BOAT_LFS = 'https://media.githubusercontent.com/media/diegonmarcos/hardtech-db-models/main/boats/glb/one-lilo-36m-sailing-yacht.view.glb';
// Canonical buildingSMART "FZK-Haus" detached house (walls, roof, 22 windows, 19 doors), pinned.
const HOUSE_URL = 'https://raw.githubusercontent.com/ThatOpen/engine_web-ifc/main/tests/ifcfiles/public/AC20-FZK-Haus.ifc';
const MAX = parseInt(process.env.MAX_ATTEMPTS || '6', 10);
const PROOF_DIR = process.env.PROOF_DIR || path.join(__dirname, 'render-proof');
const CACHE = path.join(__dirname, '.cache');

const MIME = { '.js': 'text/javascript', '.mjs': 'text/javascript', '.html': 'text/html',
  '.css': 'text/css', '.wasm': 'application/wasm', '.glb': 'model/gltf-binary',
  '.gltf': 'model/gltf+json', '.ifc': 'text/plain', '.json': 'application/json',
  '.png': 'image/png', '.bin': 'application/octet-stream' };

async function download(url, dest) {
  if (existsSync(dest) && (await stat(dest)).size > 1024) return dest;
  const res = await fetch(url);
  if (!res.ok) throw new Error(`fetch ${url} -> HTTP ${res.status}`);
  const buf = Buffer.from(await res.arrayBuffer());
  await mkdir(path.dirname(dest), { recursive: true });
  await writeFile(dest, buf);
  return dest;
}

// Assemble a webroot mirroring the Android asset layout from the REAL committed assets.
async function buildWebroot() {
  const WR = path.join(os.tmpdir(), 'cad-render-webroot-' + process.pid);
  const put = async (src, rel) => { const d = path.join(WR, rel); await mkdir(path.dirname(d), { recursive: true }); await cp(src, d, { recursive: true }); };
  await mkdir(WR, { recursive: true });
  // three.js + controls
  await put(path.join(SPACE, 'three.module.min.js'), 'space/three.module.min.js');
  await put(path.join(SPACE, 'three.core.min.js'), 'space/three.core.min.js');
  await put(path.join(SPACE, 'OrbitControls.js'), 'space/OrbitControls.js');
  // cad-core viewer
  const CAD = path.join(LIBS, 'cad-core/src/main/assets/cad');
  for (const f of ['viewer.html', 'viewer.js', 'script.js']) await put(path.join(CAD, f), 'cad/' + f);
  // loaders + vendor wasm
  await put(path.join(LIBS, 'cad-mesh/src/main/assets/cad/mesh'), 'cad/mesh');
  await put(path.join(LIBS, 'cad-ifc/src/main/assets/cad/ifc'), 'cad/ifc');
  // three.js examples vendor (GLTFLoader + its deps) used by cad-mesh
  await put(path.join(LIBS, 'cgc-3d/src/main/assets/cgc/vendor'), 'cgc/vendor');
  // models
  await mkdir(path.join(WR, 'models'), { recursive: true });
  const boat = process.env.CAD_BOAT_GLB || await download(BOAT_LFS, path.join(CACHE, 'boat.glb'));
  const house = process.env.CAD_HOUSE_IFC || await download(HOUSE_URL, path.join(CACHE, 'house.ifc'));
  await cp(boat, path.join(WR, 'models/boat.glb'));
  await cp(house, path.join(WR, 'models/house.ifc'));
  return WR;
}

function serve(root) {
  const srv = http.createServer(async (req, res) => {
    try {
      const u = decodeURIComponent(req.url.split('?')[0]);
      const fp = path.join(root, u === '/' ? '/cad/viewer.html' : u);
      if (!fp.startsWith(root)) { res.writeHead(403); return res.end(); }
      const buf = await readFile(fp);
      res.writeHead(200, { 'content-type': MIME[path.extname(fp)] || 'application/octet-stream' });
      res.end(buf);
    } catch { res.writeHead(404); res.end('404'); }
  });
  return new Promise((r) => srv.listen(0, () => r(srv)));
}

// In-page: is a canvas (or a snapshot image) a genuine, non-blank, multi-colour render?
// (corner pixel = background; count non-background fraction, distinct colour buckets, luminance spread.)
const SCORE_FN = `(function(getPixels){
  const d = getPixels(); if(!d) return {ok:false, reason:'no pixels'};
  const bg=[d[0],d[1],d[2]]; let nb=0,s=0,s2=0,tt=0; const b=new Set();
  for(let i=0;i<d.length;i+=4){ const r=d[i],g=d[i+1],bl=d[i+2], l=0.299*r+0.587*g+0.114*bl;
    s+=l; s2+=l*l; tt++; if(Math.abs(r-bg[0])+Math.abs(g-bg[1])+Math.abs(bl-bg[2])>24) nb++;
    b.add((r>>4)<<8 | (g>>4)<<4 | (bl>>4)); }
  const f=nb/tt, m=s/tt, sd=Math.sqrt(Math.max(0,s2/tt-m*m));
  return {ok: f>=0.03 && b.size>=6 && sd>=6, nonBgFrac:+f.toFixed(4), distinctColors:b.size, lumStd:+sd.toFixed(2)};
})`;

async function newPage(browser) {
  const ctx = await browser.newContext({ deviceScaleFactor: 2, viewport: { width: 1000, height: 750 } });
  const page = await ctx.newPage();
  const logs = [];
  page.on('console', (m) => logs.push('[' + m.type() + '] ' + m.text()));
  page.on('pageerror', (e) => logs.push('[pageerror] ' + e.message));
  return { page, ctx, logs };
}

async function verifyCad({ browser, base, lib, url, format }) {
  const { page, ctx, logs } = await newPage(browser);
  const events = [];
  await page.exposeFunction('__cadEvent', (o) => events.push(o));
  await page.addInitScript(() => { window.CadHost = { post: (s) => window.__cadEvent(JSON.parse(s)) }; });
  await page.goto(base + '/cad/viewer.html', { waitUntil: 'load', timeout: 60000 });
  await page.waitForFunction(() => typeof window.cadOpen === 'function', { timeout: 30000 });
  await page.evaluate(({ lib, url, format }) => window.cadOpen({ lib, url, format }), { lib, url, format });
  const status = await page.waitForFunction(() => {
    const el = document.getElementById('s'); return el && /·|Failed/.test(el.textContent) ? el.textContent : false;
  }, { timeout: 120000 }).then((h) => h.jsonValue()).catch(() => null);
  await page.waitForTimeout(1000);
  // Score the pixels of cadSnapshot() — it renders AND reads the WebGL buffer in one tick, so the
  // preserveDrawingBuffer:false clear between frames cannot blank the read-back.
  const out = await page.evaluate(async (SCORE) => {
    const dataUrl = window.cadSnapshot();
    const img = new Image(); await new Promise((r, j) => { img.onload = r; img.onerror = j; img.src = dataUrl; });
    const n = 512, t = document.createElement('canvas'); t.width = n; t.height = n;
    const x = t.getContext('2d'); x.drawImage(img, 0, 0, n, n);
    const data = x.getImageData(0, 0, n, n).data;
    const score = eval(SCORE)(() => data);
    return { dataUrl, score };
  }, SCORE_FN).catch((e) => ({ err: String(e) }));
  const stats = out.score || { ok: false, reason: out.err || 'snapshot failed' };
  const shot = out.dataUrl ? Buffer.from(out.dataUrl.split(',')[1], 'base64') : await page.screenshot();
  await ctx.close();
  return { stats, status, events, logs, shot };
}

async function verifyFloor({ browser, url }) {
  const { page, ctx, logs } = await newPage(browser);
  await page.goto(url, { waitUntil: 'domcontentloaded', timeout: 60000 });
  await page.waitForLoadState('networkidle', { timeout: 60000 }).catch(() => {});
  await page.waitForSelector('canvas', { timeout: 30000 }).catch(() => {});
  await page.waitForTimeout(6000); // the canvas engine streams its scene in
  const stats = await page.evaluate((SCORE) => {
    const score = (c) => {
      const w = c.width, h = c.height; if (!w || !h) return null;
      const n = 512, t = document.createElement('canvas'); t.width = n; t.height = n;
      const x = t.getContext('2d'); x.drawImage(c, 0, 0, n, n);
      let d; try { d = x.getImageData(0, 0, n, n).data; } catch { return { ok: false, reason: 'readback blocked' }; }
      return eval(SCORE)(() => d);
    };
    const cs = [...document.querySelectorAll('canvas')].sort((a, b) => b.width * b.height - a.width * a.height);
    for (const c of cs) { const r = score(c); if (r && r.ok) return { ...r, canvases: cs.length }; }
    return { ...(cs[0] ? score(cs[0]) : { ok: false, reason: 'no canvas' }), canvases: cs.length };
  }, SCORE_FN);
  const shot = await page.screenshot();
  await ctx.close();
  return { stats, logs, shot };
}

async function main() {
  await mkdir(PROOF_DIR, { recursive: true });
  const WR = await buildWebroot();
  const srv = await serve(WR);
  const base = 'http://127.0.0.1:' + srv.address().port;
  const browser = await chromium.launch({ args: [
    '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader',
    '--ignore-gpu-blocklist', '--enable-webgl', '--no-sandbox', '--disable-dev-shm-usage' ] });

  const targets = [
    { kind: 'floor', name: '796floor', url: FLOOR796_URL, file: '796floor.png' },
    { kind: 'cad', name: 'boat', lib: 'mesh', url: base + '/models/boat.glb', format: 'glb', file: 'cad-boat.png' },
    { kind: 'cad', name: 'house', lib: 'ifc', url: base + '/models/house.ifc', format: 'ifc', file: 'cad-house.png' },
  ];
  const report = [];
  let allPass = true;
  for (const t of targets) {
    let pass = false, last = null;
    for (let i = 1; i <= MAX && !pass; i++) {
      try {
        const r = t.kind === 'cad'
          ? await verifyCad({ browser, base, lib: t.lib, url: t.url, format: t.format })
          : await verifyFloor({ browser, url: t.url });
        last = r; pass = !!(r.stats && r.stats.ok);
        if (r.shot) await writeFile(path.join(PROOF_DIR, t.file), r.shot);
        console.log(`[${t.name}] attempt ${i}/${MAX}: ok=${pass} ${JSON.stringify(r.stats)}${r.status ? ' status=' + r.status : ''}`);
        if (!pass) {
          (r.logs || []).slice(-8).forEach((l) => console.log('   ' + l));
          (r.events || []).forEach((e) => console.log('   evt ' + JSON.stringify(e)));
        }
      } catch (e) { last = { err: String(e) }; console.log(`[${t.name}] attempt ${i}/${MAX} threw: ${e.message}`); }
    }
    report.push({ name: t.name, pass, stats: last && last.stats });
    if (!pass) allPass = false;
  }
  await browser.close(); srv.close();
  console.log('\n=== SUMMARY ===');
  report.forEach((r) => console.log(`${r.pass ? 'PASS' : 'FAIL'} ${r.name} ${JSON.stringify(r.stats || {})}`));
  console.log('proofs: ' + PROOF_DIR);
  process.exit(allPass ? 0 : 1);
}

main().catch((e) => { console.error(e); process.exit(1); });
