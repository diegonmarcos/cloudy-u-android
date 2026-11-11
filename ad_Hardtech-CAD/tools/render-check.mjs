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
  // #1658 the 2D presentation (what the host switches to when the WebGL layer does not reach the screen): the WebGL canvas leaves the layout, the
  // CPU-backed 2D canvas holds the model, a drag still orbits it, and the host's frame pull returns a JPEG.
  const p2 = await page.evaluate(() => {
    window.cadWireframe(false); const mode = window.cadPresent('2d'); const v = document.getElementById('v2'), g = document.querySelector('canvas');
    const d = v.getContext('2d').getImageData(0, 0, v.width, v.height).data; let n = 0, h = 0;
    for (let i = 0; i < d.length; i += 4) { if (Math.abs(d[i] - 14) + Math.abs(d[i + 1] - 17) + Math.abs(d[i + 2] - 22) > 12) n++; h = (h * 31 + d[i]) | 0; }
    return { mode, pct: 100 * n / (v.width * v.height), h, w: v.width, hgt: v.height, glShown: getComputedStyle(g).display !== 'none', v2Shown: getComputedStyle(v).display !== 'none',
      shot: window.cadFrameShot(-1).slice(0, 40), same: window.cadFrameShot(window.cadDiag().frames), diag: window.cadDiag().present };
  });
  await page.mouse.move(180, 150); await page.mouse.down(); await page.mouse.move(250, 170, { steps: 6 }); await page.mouse.up(); await page.waitForTimeout(300);
  const h2 = await page.evaluate(() => { const v = document.getElementById('v2'); const d = v.getContext('2d').getImageData(0, 0, v.width, v.height).data; let h = 0; for (let i = 0; i < d.length; i += 4) h = (h * 31 + d[i]) | 0; return h; });
  console.log(`${m.id}: 2d presentation ${p2.pct.toFixed(2)}% of ${p2.w}x${p2.hgt}, orbit moved the picture: ${h2 !== p2.h}`);
  if (p2.mode !== '2d' || p2.diag !== '2d' || p2.glShown || !p2.v2Shown) bad.push(`${m.id}: cadPresent('2d') did not swap the canvases (${JSON.stringify(p2)})`);
  if (!(p2.pct >= 1)) bad.push(`${m.id}: the 2D presentation is background-coloured (${p2.pct}% painted)`);
  if (h2 === p2.h) bad.push(`${m.id}: a drag did not orbit the model in the 2D presentation`);
  if (!/^\d+\|data:image\/jpeg;base64,/.test(p2.shot) || p2.same !== '') bad.push(`${m.id}: cadFrameShot is not "<frame>|<jpeg data url>" / '' for an unchanged frame (${p2.shot} / ${p2.same.slice(0, 20)})`);
  await page.screenshot({ path: `${process.env.RUNNER_TEMP || '.'}/render-${m.id}-2d.png` });
  await page.close();
}
// #1658 a WebView that is laid out AFTER its page loaded: the canvas starts at 1x1. The viewer waits for a real size and judges the paint on it, never on the
// unsized canvas (which reported "the canvas drew nothing" over a model that then rendered); a viewer that never gets a size says so.
{
  const page = await browser.newPage({ viewport: { width: 1, height: 1 } });
  await page.addInitScript(() => { window.__m = []; window.CadHost = { post: (s) => window.__m.push(JSON.parse(s)) }; });
  await page.goto(`${base}/assets/cad/viewer.html`);
  await page.waitForFunction(() => typeof window.cadOpen === 'function', null, { timeout: 30000 });
  await page.evaluate((a) => { window.__open = window.cadOpen(a); }, { lib: 'mesh', url: `/assets/${models[0].view.asset}`, format: 'glb' });
  await page.waitForTimeout(1500);
  const early = await page.evaluate(() => window.__m.map((x) => x.type));
  await page.setViewportSize({ width: 360, height: 300 });
  const late = await page.evaluate(async () => { await window.__open; return window.__m.map((x) => x.type + (x.pct !== undefined ? ' ' + x.pct + '% of ' + x.w + 'x' + x.h : '') + (x.message ? ' ' + x.message : '')); });
  console.log(`late layout: before the size ${early.join(',')}; after ${late.join(', ')}`);
  if (early.includes('shown') || early.includes('error')) bad.push(`late layout: the viewer judged an unsized canvas (${early.join(',')})`);
  if (!late.some((x) => x.startsWith('shown')) || late.some((x) => x.startsWith('error')) || !late.some((x) => /^painted .* of 360x300$/.test(x))) bad.push(`late layout: not shown on the sized canvas (${late.join(', ')})`);
  await page.close();
  const never = await browser.newPage({ viewport: { width: 1, height: 1 } });
  await never.addInitScript(() => { window.__m = []; window.CadHost = { post: (s) => window.__m.push(JSON.parse(s)) }; });
  await never.goto(`${base}/assets/cad/viewer.html`);
  await never.waitForFunction(() => typeof window.cadOpen === 'function', null, { timeout: 30000 });
  const msg = await never.evaluate(async (a) => { await window.cadOpen(a); return window.__m.filter((x) => x.type === 'error').map((x) => x.message).join(' | '); }, { lib: 'mesh', url: `/assets/${models[0].view.asset}`, format: 'glb' });
  console.log(`never laid out: ${msg}`);
  if (!msg.includes('the viewer has no size')) bad.push(`never laid out: the viewer did not say it has no size (${msg})`);
  await never.close();
}
await browser.close(); srv.close();
if (bad.length) { console.error(bad.join('\n')); process.exit(1); }
console.log(`render-check: ${models.length} models paint`);
