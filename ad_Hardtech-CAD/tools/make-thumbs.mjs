// make-thumbs (#1664): the preview image of every declared model, rendered FROM ITS DERIVED GLB by the app's own viewer page (libs:cad-core viewer.html
// + viewer.js, the same merged-assets layout the APK has) in headless Chromium: iso view, dark stage, 480x360 JPEG, written next to the GLB as
// <asset dir>/<id>.thumb.jpg and declared as view.thumb in models.json. No data is typed: the picture is derived from the bundled view, like the view
// is derived from the original. CI (.github/workflows/hardtech-cad-models.yml) runs it with --check: RED when a declared model has no thumbnail, the
// file is missing or is not a JPEG of the declared size, or a fresh render is blank.
// Usage: node make-thumbs.mjs <repo root> [--check]
import http from 'node:http';
import { readFileSync, writeFileSync, existsSync, statSync } from 'node:fs';
import { createRequire } from 'node:module';

const root = process.argv[2];
const CHECK = process.argv.includes('--check');
if (!root) { console.error('usage: node make-thumbs.mjs <repo root> [--check]'); process.exit(2); }
let chromium;
try { ({ chromium } = await import('playwright')); } catch { ({ chromium } = createRequire('/opt/node-tools/')('playwright')); }
const A = 'src/main/assets';
const APP = `${root}/ad_Hardtech-CAD/app/${A}`;
const roots = [`${root}/ab_cloud-libs-shared/libs/cad-core/${A}`, `${root}/ab_cloud-libs-shared/libs/cad-mesh/${A}`, `${root}/ab_cloud-libs-shared/libs/cgc-3d/${A}`, `${root}/ac_cloud-nav/app/${A}`, APP];
const W = 480, H = 360;
const manifestPath = `${APP}/cad/models.json`;
const doc = JSON.parse(readFileSync(manifestPath, 'utf8'));
const mime = { '.js': 'text/javascript', '.html': 'text/html', '.glb': 'model/gltf-binary', '.json': 'application/json' };
const srv = http.createServer((q, r) => {
  const rel = decodeURIComponent(q.url.split('?')[0]).replace(/^\/assets\//, '');
  const f = roots.map((d) => `${d}/${rel}`).find((x) => !rel.includes('..') && existsSync(x) && statSync(x).isFile());
  if (!f) { r.writeHead(404).end('no ' + rel); return; }
  r.writeHead(200, { 'content-type': mime[f.slice(f.lastIndexOf('.'))] || 'application/octet-stream' }).end(readFileSync(f));
}).listen(0, '127.0.0.1');
await new Promise((ok) => srv.once('listening', ok));
const base = `http://127.0.0.1:${srv.address().port}`;
const bad = [];
const jpegSize = (b) => { // the SOF0/SOF2 frame header of a JPEG: [w, h], or null
  if (b[0] !== 0xff || b[1] !== 0xd8) return null;
  for (let i = 2; i < b.length - 9;) { if (b[i] !== 0xff) return null; const m = b[i + 1], len = b.readUInt16BE(i + 2); if (m === 0xc0 || m === 0xc2) return [b.readUInt16BE(i + 7), b.readUInt16BE(i + 5)]; i += 2 + len; }
  return null;
};
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
let changed = false;
for (const m of doc.models) {
  if (!m.view || m.view.format !== 'glb' || !m.view.asset) { bad.push(`${m.id}: no GLB view to render a thumbnail from`); continue; }
  const thumb = m.view.asset.replace(/\.view\.glb$|\.glb$/, '') + '.thumb.jpg';
  const page = await browser.newPage({ viewport: { width: W, height: H } });
  await page.addInitScript(() => { window.__m = []; window.CadHost = { post: (s) => window.__m.push(JSON.parse(s)) }; });
  await page.goto(`${base}/assets/cad/viewer.html`);
  await page.waitForFunction(() => typeof window.cadOpen === 'function', null, { timeout: 30000 });
  await page.evaluate((a) => window.cadOpen(a), { lib: 'mesh', url: `/assets/${m.view.asset}`, format: 'glb' });
  const r = await page.evaluate(() => { window.cadView('iso'); document.getElementById('s').textContent = ''; const p = window.cadPaint(); return { p, url: document.querySelector('canvas').toDataURL('image/jpeg', 0.85) }; });
  await page.close();
  if (!(r.p.pct >= 1)) { bad.push(`${m.id}: the render for its thumbnail is blank (${r.p.pct}%)`); continue; }
  const file = `${APP}/${thumb}`;
  if (CHECK) {
    if (m.view.thumb !== thumb) bad.push(`${m.id}: view.thumb is ${m.view.thumb}, expected ${thumb}`);
    if (!existsSync(file)) { bad.push(`${m.id}: ${thumb} is missing`); continue; }
    const sz = jpegSize(readFileSync(file));
    if (!sz || sz[0] !== W || sz[1] !== H) bad.push(`${m.id}: ${thumb} is not a ${W}x${H} JPEG (${sz})`);
    console.log(`ok ${m.id}: ${thumb} (${statSync(file).size} bytes), fresh render ${r.p.pct}% painted`);
  } else if (m.view.thumb_source === 'photo' && existsSync(file)) {
    // #1695 the owner's own photo of the model is its preview (view.thumb_source: photo): keep it, never overwrite it with a render
    console.log(`kept ${thumb} (the owner's photo, ${statSync(file).size} bytes); fresh render ${r.p.pct}% painted`);
  } else {
    writeFileSync(file, Buffer.from(r.url.split(',')[1], 'base64'));
    if (m.view.thumb !== thumb) { m.view.thumb = thumb; changed = true; }
    console.log(`wrote ${thumb} (${statSync(file).size} bytes, ${r.p.pct}% painted)`);
  }
}
await browser.close(); srv.close();
if (changed) writeFileSync(manifestPath, JSON.stringify(doc, null, 2) + '\n');
if (bad.length) { console.error(bad.join('\n')); process.exit(1); }
