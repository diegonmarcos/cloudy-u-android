// Responsive check: every page of the app at 360 x 800 (phone) and 1440 x 900 (desktop), headless Chrome over
// the DevTools protocol, no dependency. A page FAILS at 360 when it scrolls sideways (scrollWidth > clientWidth),
// when the layout viewport is not 360 wide (a forced desktop viewport), or when its viewport meta is not
// width=device-width. At 1440 the file browser must keep its two panes side by side. PNGs go to <out-dir>.
//
//   node responsive-check.mjs <chrome> <drive-url> <diag-url> <out-dir>
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [chromeBin, driveUrl, diagUrl, outDir] = process.argv.slice(2);
mkdirSync(outDir, { recursive: true });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
let bad = 0;
const check = (id, ok, msg) => { if (!ok) bad++; console.log(JSON.stringify({ id, ok: !!ok, msg })); };
const profile = mkdtempSync(join(tmpdir(), 'c3ws-resp-'));
const chrome = spawn(chromeBin, ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--hide-scrollbars',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
let chromeErr = ''; chrome.stderr.on('data', (d) => { chromeErr = (chromeErr + d).slice(-600); });
const finish = (code) => { try { chrome.kill('SIGKILL'); } catch {} process.exit(code); };
process.on('uncaughtException', (e) => { check('RUN', false, 'crashed: ' + (e && e.stack || e)); finish(1); });
let port = 0;
for (let i = 0; i < 400 && !port; i++) { await sleep(100); try { port = parseInt(readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0], 10); } catch {} }
if (!port) { check('RUN', false, 'Chrome did not open a DevTools port in 40s (exit ' + chrome.exitCode + '): ' + chromeErr.replace(/\s+/g, ' ')); finish(1); }
const page = (await (await fetch(`http://127.0.0.1:${port}/json/list`)).json()).find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map(); let loaded = null;
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); } if (d.method === 'Page.loadEventFired' && loaded) loaded(); };
const send = (method, params = {}) => new Promise((res) => { const id = ++seq; pending.set(id, res); ws.send(JSON.stringify({ id, method, params })); });
const evalJs = async (e) => { const r = await send('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails)); return r.result.result.value; };
const go = async (url) => { const p = new Promise((r) => { loaded = r; }); await send('Page.navigate', { url }); await Promise.race([p, sleep(15000)]); loaded = null; await sleep(1200); };
await send('Page.enable');

const origin = (u) => u.replace(/\/$/, '');
const D = origin(driveUrl);
const pages = [
  ['browser-root', D + '/'], ['browser-markdown', D + '/#/Documents/readme.md'], ['browser-json', D + '/#/Documents/data.json'],
  ['markdown', D + '/Documents/readme.md'], ['json', D + '/Documents/data.json'], ['json-wide', D + '/Documents/wide.json'], ['yaml', D + '/Documents/c.yaml'],
  ['shell-home', D + '/__app__/tab/home'], ['shell-pages', D + '/__app__/tab/pages'], ['shell-api', D + '/__app__/tab/api'],
  ['shell-sshd', D + '/__app__/tab/sshd'], ['shell-configs', D + '/__app__/tab/configs'], ['diagnostics', origin(diagUrl) + '/'],
];
for (const [vn, W, H, mobile] of [['360', 360, 800, true], ['1440', 1440, 900, false]]) {
  await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: 1, mobile, screenWidth: W, screenHeight: H });
  await send('Emulation.setTouchEmulationEnabled', { enabled: mobile });
  for (const [name, url] of pages) {
    // a page wider than a desktop screen by its own data (a table of 8 long columns) scrolls there, as any desktop page does
    if (mobile === false && name === 'json-wide') continue;
    await go('about:blank'); await go(url);
    const m = await evalJs(`(() => { const v = document.querySelector('meta[name=viewport]'); const de = document.documentElement;
      let widest = 0, who = ''; for (const e of document.querySelectorAll('body *')) { const r = e.getBoundingClientRect(); if (r.right > widest && getComputedStyle(e).position !== 'fixed') { widest = r.right; who = e.tagName + (e.id ? '#' + e.id : ''); } }
      return { meta: v ? v.content : null, iw: innerWidth, sw: de.scrollWidth, cw: de.clientWidth, bw: document.body.scrollWidth, widest: Math.round(widest), who }; })()`);
    const shot = await send('Page.captureScreenshot', { format: 'png' });
    writeFileSync(join(outDir, `${vn}-${name}.png`), Buffer.from(shot.result.data, 'base64'));
    const metaOk = /width=device-width/.test(m.meta || '');
    check(`${vn}/${name}`, metaOk && m.sw <= m.cw && m.bw <= m.cw && m.iw === W,
      `viewport meta ${JSON.stringify(m.meta)}, innerWidth ${m.iw} (want ${W}), scrollWidth ${m.sw} vs clientWidth ${m.cw}, widest element ${m.who} to ${m.widest}px`);
  }
}
// desktop: the file browser keeps tree LEFT of viewer; phone: tree closes after a file opens
await go('about:blank'); await go(D + '/#/Documents/readme.md');
const dk = await evalJs(`(() => { const s = document.querySelector('#sidebar').getBoundingClientRect(), r = document.querySelector('#viewer').getBoundingClientRect(); return { sr: s.right, vl: r.left, vw: r.width }; })()`);
check('1440/two-panes', dk.sr <= dk.vl + 1 && dk.vw > 600, `desktop keeps tree left (ends ${Math.round(dk.sr)}) of viewer (from ${Math.round(dk.vl)}, ${Math.round(dk.vw)} wide)`);
await send('Emulation.setDeviceMetricsOverride', { width: 360, height: 800, deviceScaleFactor: 1, mobile: true, screenWidth: 360, screenHeight: 800 });
await go('about:blank'); await go(D + '/#/Documents/readme.md');
const ph = await evalJs(`(() => { const r = document.querySelector('#viewer').getBoundingClientRect(); return { bh: document.body.getBoundingClientRect().height, vh: r.height, vl: r.left, vw: r.width, side: getComputedStyle(document.querySelector('#sidebar')).display }; })()`);
check('360/viewer-full-width', ph.vl <= 1 && ph.vw >= 358 && ph.side === 'none', `phone: viewer ${Math.round(ph.vl)}+${Math.round(ph.vw)}, tree display=${ph.side}`);
// a WebView that resolves 100dvh to 0 drew the page blank: the phone layout must have a real height from plain pixels
check('360/page-has-height', ph.bh >= 600 && ph.vh >= 300, `phone: body ${Math.round(ph.bh)}px tall, viewer ${Math.round(ph.vh)}px tall (want the screen height; no dvh)`);
finish(bad ? 1 : 0);
