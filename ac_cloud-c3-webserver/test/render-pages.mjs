// The app shell's pages and the link chooser, drawn at the owner's phone. Same harness as render-a37.mjs.
//
//   node render-pages.mjs <chrome> <drive-url> <out-dir> <expect-file> <expect-dir>
//
// Drives headless Chrome over the DevTools protocol at the owner's phone (Samsung Galaxy A37: 384 x 832 dp,
// device pixel ratio 2.8125, a 1080 x 2340 px screenshot) with MOBILE emulation on, so the page's own
// <meta viewport> is honoured exactly as on the phone. No dependency: Node's built-in WebSocket.
//
//   node render-a37.mjs <chrome> <drive-url> <diag-url> <out-dir> <expect-file> <expect-dir> <expect-md-heading>
//
// Prints one JSON line per check: {"id":..,"ok":..,"msg":..}; exit 1 when any is not ok.
import { spawn } from 'node:child_process';
import { mkdtempSync, readFileSync, writeFileSync, mkdirSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const [chromeBin, driveUrl, outDir, expectFile, expectDir] = process.argv.slice(2);
const W = 384, H = 832, DPR = 2.8125;
mkdirSync(outDir, { recursive: true });
const results = [];
const check = (id, ok, msg) => { results.push({ id, ok: !!ok, msg }); console.log(JSON.stringify({ id, ok: !!ok, msg })); };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const profile = mkdtempSync(join(tmpdir(), 'c3ws-pages-'));
const chrome = spawn(chromeBin, ['--headless=new', '--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage', '--hide-scrollbars',
  '--remote-debugging-port=0', `--user-data-dir=${profile}`, 'about:blank'], { stdio: ['ignore', 'ignore', 'pipe'] });
let chromeErr = ''; chrome.stderr.on('data', (d) => { chromeErr = (chromeErr + d).slice(-600); });
const finish = (code) => { try { chrome.kill('SIGKILL'); } catch {} process.exit(code); };
process.on('uncaughtException', (e) => { check('RUN', false, 'the render run crashed: ' + (e && e.stack || e)); finish(1); });

let port = 0;
for (let i = 0; i < 400 && !port; i++) {
  await sleep(100);
  try { port = parseInt(readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').split('\n')[0], 10); } catch {}
}
if (!port) { check('RUN', false, 'Chrome did not open a DevTools port in 40s (exit ' + chrome.exitCode + '): ' + chromeErr.replace(/\s+/g, ' ')); finish(1); }

const targets = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const page = targets.find((t) => t.type === 'page');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
let seq = 0; const pending = new Map(); const requested = new Set(); let loaded = null;
ws.onmessage = (m) => {
  const d = JSON.parse(m.data);
  if (d.id && pending.has(d.id)) { pending.get(d.id)(d); pending.delete(d.id); }
  if (d.method === 'Network.requestWillBeSent') requested.add(d.params.request.url);
  if (d.method === 'Page.loadEventFired' && loaded) loaded();
};
const send = (method, params = {}) => new Promise((res) => { const id = ++seq; pending.set(id, res); ws.send(JSON.stringify({ id, method, params })); });
const evalJs = async (expr) => { const r = await send('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true }); if (r.result.exceptionDetails) throw new Error(JSON.stringify(r.result.exceptionDetails)); return r.result.result.value; };
const until = async (expr, ms = 15000) => { const t = Date.now(); while (Date.now() - t < ms) { try { if (await evalJs(expr)) return true; } catch {} await sleep(100); } return false; };
const go = async (url) => { const p = new Promise((r) => { loaded = r; }); await send('Page.navigate', { url }); await Promise.race([p, sleep(15000)]); loaded = null; };
const shot = async (name) => {
  const r = await send('Page.captureScreenshot', { format: 'png' });
  const buf = Buffer.from(r.result.data, 'base64'); writeFileSync(join(outDir, name + '.png'), buf);
  return { w: buf.readUInt32BE(16), h: buf.readUInt32BE(20), bytes: buf.length };
};

await send('Page.enable'); await send('Network.enable');
await send('Emulation.setDeviceMetricsOverride', { width: W, height: H, deviceScaleFactor: DPR, mobile: true, screenWidth: W, screenHeight: H });
await send('Emulation.setTouchEmulationEnabled', { enabled: true });


const origin = driveUrl.replace(/\/$/, '');
const dims = async () => evalJs(`(() => { const h = document.querySelector('header').getBoundingClientRect(), n = document.querySelector('nav').getBoundingClientRect();
  const m = document.querySelector('main'); const first = m.firstElementChild ? m.firstElementChild.getBoundingClientRect() : null;
  return { sw: document.documentElement.scrollWidth, vw: window.innerWidth, headBottom: h.bottom, navTop: n.top, navH: n.height, firstTop: first ? first.top : null, tabs: [...document.querySelectorAll('nav button')].map((b) => b.innerText.trim()), on: (document.querySelector('nav button.on') || {}).innerText }; })()`);
const A37 = (s) => s.w === 1080 && s.h === 2340 && s.bytes > 8000;

// ── P0: the app opens on the shell, with the five tabs, Home selected ──
await go(origin + '/__app__/');
await until(`document.querySelectorAll('nav button').length > 0 && document.querySelector('main section')`);
let d = await dims();
check('P0a', JSON.stringify(d.tabs) === JSON.stringify(['Pages', 'API', 'Home', 'SSHd', 'Configs']), `the bottom nav is ${JSON.stringify(d.tabs)}`);
check('P0b', d.on === 'Home', `the app opens on Home (selected: ${d.on})`);

const tabs = [['Pages', 'pages', `document.querySelector('.served')`], ['API', 'api', `document.querySelector('main section .row')`],
  ['Home', 'home', `document.querySelector('#about-index')`], ['Configs', 'configs-about', `document.querySelector('.seg')`]];
for (const [label, name, ready] of tabs) {
  await evalJs(`[...document.querySelectorAll('nav button')].find((b) => b.innerText.trim() === ${JSON.stringify(label)}).click()`);
  await until(`document.querySelector('nav button.on') && document.querySelector('nav button.on').innerText.trim() === ${JSON.stringify(label)} && ${ready}`);
  d = await dims();
  const s = await shot('shell-' + name);
  check('P-' + label, d.sw <= d.vw + 1 && A37(s) && d.firstTop !== null && d.firstTop >= d.headBottom - 1,
    `${label}: no sideways scroll (${d.sw} <= ${d.vw}), content starts below the top row (${Math.round(d.firstTop)} >= ${Math.round(d.headBottom)}), shot ${s.w}x${s.h}`);
}

// ── G: the Pages tab is ONE list of served pages (no Drive | Terminals | Repos sub-nav), each row with its four detail rows ──
await evalJs(`[...document.querySelectorAll('nav button')].find((b) => b.innerText.trim() === 'Pages').click()`);
await until(`document.querySelectorAll('.served .pg').length >= 4`);
const subnav = await evalJs(`[...document.querySelectorAll('main .seg button, main [data-sub]')].map((b) => b.innerText.trim())`);
check('G1', subnav.length === 0 && await evalJs(`!document.querySelector('[data-sub]')`), `no Drive|Terminals|Repos sub-nav on Pages (${JSON.stringify(subnav)})`);
const pgs = await evalJs(`[...document.querySelectorAll('.served .pg')].map((p) => ({ name: p.querySelector('.nm').innerText.trim(), rows: ['status', 'meta', 'source', 'links', 'config'].filter((r) => p.querySelector('[data-r=' + r + ']')),
  port: (p.querySelector('.rh .badge') || {}).innerText, toggle: !!p.querySelector('.rh button[data-toggle]'), app: (p.querySelector('[data-r=status]').innerText.match(/app\\s*(not open|open)/i) || [])[1] || null,
  startApp: !!p.querySelector('[data-start-app]'), hrefs: [...p.querySelectorAll('[data-r=links] a')].map((a) => a.getAttribute('href')), srcOpen: !!p.querySelector('[data-r=source] a'), meta: p.querySelector('[data-r=meta]').innerText }))`);
check('G2', ['Termux', 'Nix', 'Local', 'Cloud'].every((n) => pgs.some((p) => p.name === n)), `the declared endpoints all appear as rows: ${pgs.map((p) => p.name).join(', ')}`);
check('G3', pgs.every((p) => p.rows.length === 5 && p.toggle && /auth/i.test(p.meta) && /exposure/i.test(p.meta) && /host/i.test(p.meta)),
  'every served row has Status, Metadata (auth, exposure, host), Source, Links, an edit-config link and a Start/Stop serving button');
const tn = pgs.filter((p) => p.name === 'Termux' || p.name === 'Nix');
check('G4', tn.length === 2 && tn.every((p) => p.app && (p.app.toLowerCase() === 'open' || p.startApp)) && pgs.filter((p) => p.name === 'Local' || p.name === 'Cloud').every((p) => !p.app && !p.startApp),
  `Termux and Nix show app open/not open (+ Start app when not open); Local and Cloud do not (${JSON.stringify(tn.map((p) => [p.name, p.app, p.startApp]))})`);
check('G5', pgs.every((p) => p.hrefs.length === 2 && p.hrefs[0].startsWith('http://') && p.hrefs[0].includes('.app') && p.hrefs[1].startsWith('http://127.0.0.1:')),
  `every row lists its private name and its 127.0.0.1:port link (${pgs.map((p) => p.hrefs.join(' ')).join(' | ')})`);
check('G6', pgs.filter((p) => p.name !== 'Cloud').every((p) => p.srcOpen), 'a path source (Termux, Nix, Local) has an open link');
const frm = await evalJs(`(() => { const f = document.querySelector('.form-sec .form'); if (!f) return null; return { post: f.getAttribute('data-post'), fields: [...f.querySelectorAll('[data-f]')].map((x) => x.getAttribute('data-f')), labels: [...f.querySelectorAll('label')].map((l) => l.firstChild.textContent.trim()), submit: f.querySelector('[data-submit]').innerText.trim(), title: document.querySelector('.form-sec h2').innerText.trim() }; })()`);
check('G7', frm && frm.post === '/__app__/api/repos' && ['label', 'source', 'repo', 'exposure', 'auth'].every((k) => frm.fields.includes(k)) && /Add a place to serve/.test(frm.title) && /Add & serve/.test(frm.submit),
  `the Add-a-place form exists and posts to the repos-serving backend: ${JSON.stringify(frm)}`);
const und = await evalJs(`(() => { const u = document.querySelector('#undeclared'); return u ? { h: u.querySelector('h2').innerText.replace(/\\s+/g, ' ').trim(), rescan: !!u.querySelector('[data-rescan]'), items: [...u.querySelectorAll('[data-undeclared]')].map((i) => ({ declare: !!i.querySelector('button[data-body*='"op":"declare"']'), stop: !!i.querySelector('button[data-body*=stop_undeclared]') })) } : null; })()`);
check('G8', und && /Undeclared served pages\s*·\s*\d+/.test(und.h) && und.rescan && und.items.every((i) => i.declare && i.stop), `the undeclared-pages checker renders with Rescan: ${JSON.stringify(und)}`);
const sg = await shot('shell-pages-single-list');
check('G9', A37(sg), `Pages single list, shot ${sg.w}x${sg.h}`);

// Configs > Engines and Links
for (const [seg, name, ready] of [['engines', 'configs-engines', `document.querySelector('.eng')`], ['links', 'configs-links', `document.querySelector('select[data-remember]')`]]) {
  await evalJs(`document.querySelector('[data-cfg=${seg}]').click()`);
  await until(ready);
  d = await dims();
  const s = await shot('shell-' + name);
  check('P-' + seg, d.sw <= d.vw + 1 && A37(s), `Configs > ${seg}: drawn, no sideways scroll, shot ${s.w}x${s.h}`);
}

// ── S: the SSHd tab lists the three fleet doors, live ──
await evalJs(`[...document.querySelectorAll('nav button')].find((b) => b.innerText.trim() === 'SSHd').click()`);
const doors = await until(`document.querySelectorAll('section.door').length >= 3`);
check('S1', doors, doors ? 'the SSHd tab draws the fleet SSH doors' : 'the SSHd tab drew no doors');
const dn = await evalJs(`[...document.querySelectorAll('section.door')].map((s) => s.id)`);
check('S2', ['door-nix', 'door-webserver', 'door-termux'].every((i) => dn.includes(i)), `doors listed: ${JSON.stringify(dn)}`);
// ── F: the stale Files table is gone from the SSHd tab; Keep alive has its toggle and status ──
const fv = await evalJs(`!!document.querySelector('#fileview') || [...document.querySelectorAll('main h2')].some((h) => h.innerText.trim() === 'Files')`);
check('F1', !fv, fv ? 'the stale Files table is still in the SSHd tab' : 'the SSHd tab has no Files table');
const ka = await evalJs(`(() => { const s = document.querySelector('#keepalive-status'), b = document.querySelector('#keepalive button[data-power=keep_alive]'); return !!s && !!b && /^(ON|OFF)$/.test(s.innerText.trim()) && s.innerText.trim() === b.innerText.trim(); })()`);
check('F2', ka, 'Keep alive toggle and its ON/OFF status sit together on the SSHd page');
const sf = await shot('shell-sshd');
check('F4', A37(sf), `SSHd shot ${sf.w}x${sf.h}`);

// ── L: a served link asks (Pages tab) ──
await evalJs(`[...document.querySelectorAll('nav button')].find((b) => b.innerText.trim() === 'Pages').click()`);
await until(`document.querySelector('.served a')`);
await evalJs(`document.querySelector('.served a[href^="http://emulated.app"]').click()`);
const sheet = await until(`document.querySelector('#sheet.on button[data-choice]')`);
const labels = await evalJs(`[...document.querySelectorAll('#sheet button.ch')].map((b) => b.innerText.trim())`);
check('L2', sheet && JSON.stringify(labels) === JSON.stringify(['Open in browser', 'Open embedded']), `a tapped link asks: ${JSON.stringify(labels)}`);
const hit = await evalJs(`[...document.querySelectorAll('#sheet button')].map((b) => { const r = b.getBoundingClientRect(); return Math.min(r.width, r.height); })`);
check('L3', hit.every((x) => x >= 40), `every choice is at least 40 dp tall (${hit.map(Math.round).join(', ')})`);
const sl = await shot('shell-link-chooser-file-view');
check('L4', A37(sl), `link chooser, shot ${sl.w}x${sl.h}`);
// choose browser, and remember: the server's opener stub prints OPENED; the sheet closes
await evalJs(`document.querySelector('#sheet button[data-choice=browser]').click()`);
const closed = await until(`!document.querySelector('#sheet.on')`);
check('L5', closed, 'choosing Open in browser closes the sheet');

// Pages tab: a served page link asks too, and Open embedded navigates this WebView to the loopback URL
await evalJs(`[...document.querySelectorAll('nav button')].find((b) => b.innerText.trim() === 'Pages').click()`);
await until(`document.querySelector('.served a')`);
await evalJs(`document.querySelector('.served a[href^="http://emulated.app"]').click()`);
await until(`document.querySelector('#sheet.on button[data-choice]')`);
const ss = await shot('shell-link-chooser-pages');
check('L6', A37(ss), `link chooser on the Pages tab, shot ${ss.w}x${ss.h}`);
await evalJs(`document.querySelector('#sheet button[data-choice=embedded]').click()`);
// The file view is a hash-routed page: once it has booted it rewrites the URL to `/#/` itself, so the check is on the
// document (origin + path), not on the fragment, which the page sets a moment after load (the test raced it).
const nav = await until(`location.origin + location.pathname === ${JSON.stringify(origin + '/')}`, 8000);
check('L7', nav, `Open embedded loads the served page in this WebView, private name rewritten to loopback (${await evalJs('location.href')})`);

// ── R: nothing leaves the phone ──
const off = [...requested].filter((u) => !/^(http:\/\/127\.0\.0\.1[:\/]|about:|data:|blob:)/.test(u));
check('R', off.length === 0, off.length ? 'off-phone requests: ' + off.slice(0, 3).join(', ') : `no request left the phone (${requested.size} requests)`);
finish(results.every((r) => r.ok) ? 0 : 1);
