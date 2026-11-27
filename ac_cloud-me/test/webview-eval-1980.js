#!/usr/bin/env node
/* #1980 evaluate inside a Cloud Me page WebView over Chrome DevTools (adb forward tcp:PORT localabstract:webview_devtools_remote_<pid>).
 * Usage: webview-eval-1980.js PORT URL_MATCH (count | fullscroll | scrollto FRACTION | pageinfo)
 *   URL_MATCH: a regular expression the page URL must match (the WebView of the page under test).
 *   count     -> JSON {url, total, broken, pending, placeholders, videoAsImg, scrollHeight}
 *                broken = [...document.images].filter(i=>i.complete&&i.naturalWidth===0).length   (the done-criterion)
 *                pending = images not yet loaded (!complete with a source)
 *   fullscroll-> scrolls the whole page (window and nested scrollers) in 400 px steps so every lazy image loads, then prints count
 *   scrollto  -> scrollTo(fraction of the scroll height), prints the scroll offset
 * Exit 2 when no target matches. Node 22 global WebSocket and fetch, no dependencies. */
const [port, match, cmd, arg] = process.argv.slice(2);
const COUNT = `(() => { const im = [...document.images]; return JSON.stringify({ url: location.href, total: im.length,
  broken: im.filter(i => i.complete && i.naturalWidth === 0).length,
  pending: im.filter(i => !i.complete && (i.currentSrc || i.getAttribute('src'))).length,
  placeholders: im.filter(i => i.getAttribute('data-fb') === 'done').length,
  videoAsImg: im.filter(i => /\\.(mp4|webm|mov)(\\?|$)/i.test(i.currentSrc || i.src)).length,
  scrollHeight: Math.max(document.body.scrollHeight, document.documentElement.scrollHeight) }); })()`;
const SCROLL = `(async () => { const sc = () => [...document.querySelectorAll('*')].filter(e => e.scrollHeight > e.clientHeight + 50 && /(auto|scroll)/.test(getComputedStyle(e).overflowY));
  const h = () => Math.max(document.body.scrollHeight, ...sc().map(e => e.scrollHeight));
  for (let y = 0; y < Math.min(h(), 120000); y += 400) { window.scrollTo(0, y); sc().forEach(e => { e.scrollTop = y; }); await new Promise(r => setTimeout(r, 120)); }
  await new Promise(r => setTimeout(r, 4000)); return 'scrolled'; })()`;
(async () => {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json();
  const re = new RegExp(match);
  const t = targets.filter(x => x.type === 'page' && re.test(x.url))[0];
  if (!t) { console.error('no target for ' + match + ' among ' + targets.map(x => x.url).join(' ')); process.exit(2); }
  const ws = new WebSocket(t.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  let id = 0; const pend = new Map();
  ws.onmessage = m => { const d = JSON.parse(m.data); if (d.id && pend.has(d.id)) { pend.get(d.id)(d); pend.delete(d.id); } };
  const ev = (expr, awaitPromise = false) => new Promise(res => { const i = ++id; pend.set(i, res); ws.send(JSON.stringify({ id: i, method: 'Runtime.evaluate', params: { expression: expr, awaitPromise, returnByValue: true } })); });
  let out;
  if (cmd === 'count') out = (await ev(COUNT)).result.result.value;
  else if (cmd === 'fullscroll') { await ev(SCROLL, true); out = (await ev(COUNT)).result.result.value; }
  else if (cmd === 'scrollto') out = (await ev(`(() => { const sc = [...document.querySelectorAll('*')].filter(e => e.scrollHeight > e.clientHeight + 50 && /(auto|scroll)/.test(getComputedStyle(e).overflowY)); const h = Math.max(document.body.scrollHeight, ...sc.map(e => e.scrollHeight)); const y = Math.round(h * ${Number(arg) || 0}); window.scrollTo(0, y); sc.forEach(e => { e.scrollTop = y; }); return String(y); })()`)).result.result.value;
  else if (cmd === 'pageinfo') out = (await ev('location.href + " " + document.readyState')).result.result.value;
  console.log(out);
  ws.close(); process.exit(0);
})().catch(e => { console.error(String(e)); process.exit(3); });
