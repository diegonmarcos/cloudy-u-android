// render-check (#1560): the viewer must PAINT every declared model. Loads the real viewer page (libs:cad-core viewer.html + viewer.js, cad-mesh loader,
// cgc-3d GLTFLoader, Cloud Nav's three.js) from the same merged-assets layout the APK has, in headless Chromium (software WebGL), opens each model's
// bundled GLB and reads the GL framebuffer back: RED when the canvas is the background colour. Wireframe is checked too.
// Usage: node render-check.mjs <repo root>   (run where `playwright` is installed and its chromium fetched; CI does both)
import http from 'node:http';
import { readFileSync, existsSync, statSync } from 'node:fs';
import { chromium } from 'playwright';

const root = process.argv[2];
if (!root) { console.error('usage: node render-check.mjs <repo root>'); process.exit(2); }
const A = 'src/main/assets';
const roots = [
  `${root}/ab_cloud-libs-shared/libs/cad-core/${A}`, `${root}/ab_cloud-libs-shared/libs/cad-mesh/${A}`, `${root}/ab_cloud-libs-shared/libs/cgc-3d/${A}`,
  `${root}/ac_cloud-nav/app/${A}`, `${root}/ad_Hardtech-CAD/app/${A}`,
];
const models = JSON.parse(readFileSync(`${root}/ad_Hardtech-CAD/app/${A}/cad/models.json`, 'utf8')).models.filter((m) => m.view && m.view.format === 'glb');
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
if (models.length < 2) bad.push(`only ${models.length} GLB models declared`);
const browser = await chromium.launch({ args: ['--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'] });
for (const m of models) {
  const page = await browser.newPage({ viewport: { width: 360, height: 300 } });
  const errs = []; page.on('pageerror', (e) => errs.push(String(e))); page.on('console', (c) => { if (c.type() === 'error') errs.push(c.text()); });
  await page.addInitScript(() => { window.__m = []; window.CadHost = { post: (s) => window.__m.push(JSON.parse(s)) }; });
  await page.goto(`${base}/assets/cad/viewer.html`);
  await page.waitForFunction(() => typeof window.cadOpen === 'function', null, { timeout: 30000 });
  await page.evaluate((a) => window.cadOpen(a), { lib: 'mesh', url: `/assets/${m.view.asset}`, format: 'glb' });
  const r = await page.evaluate(() => { const shaded = window.cadPaint(); window.cadWireframe(true); const wire = window.cadPaint(); return { shaded, wire, msgs: window.__m.map((x) => x.type) }; });
  console.log(`${m.id}: shaded ${r.shaded.pct}% wire ${r.wire.pct}% of ${r.shaded.w}x${r.shaded.h} (${r.shaded.gl}) msgs=${r.msgs.join(',')}`);
  if (!r.msgs.includes('shown')) bad.push(`${m.id}: the viewer never reported shown (${errs.join('; ')})`);
  if (r.msgs.includes('error')) bad.push(`${m.id}: the viewer reported an error (${errs.join('; ')})`);
  if (!(r.shaded.pct >= 1)) bad.push(`${m.id}: the shaded canvas is background-coloured (${r.shaded.pct}% painted)`);
  if (!(r.wire.pct > 0)) bad.push(`${m.id}: the wireframe canvas is empty`);
  await page.screenshot({ path: `${process.env.RUNNER_TEMP || '.'}/render-${m.id}.png` });
  await page.close();
}
await browser.close(); srv.close();
if (bad.length) { console.error(bad.join('\n')); process.exit(1); }
console.log(`render-check: ${models.length} models paint`);
