// libs:cad-brep (#1404, #1695): STEP/IGES through occt-import-js 0.0.23 (LGPL-2.1; Open CASCADE compiled to WASM).
// #1695 the file is read and tessellated in a Web Worker (worker.js), never on the page's main thread: the download reports its progress, the
// parse reports its stage, window.cadCancel() terminates it. The result is a GLB with ONE NODE PER SOLID (its name and colour from the file), cached
// in IndexedDB under the SHA-256 of the file, so reopening the same file skips the parse.
import { GLTFLoader } from '../../cgc/vendor/GLTFLoader.js';
const DB = 'cad-brep-glb', STORE = 'glb';
function idb() { return new Promise((ok, no) => { const r = indexedDB.open(DB, 1); r.onupgradeneeded = () => r.result.createObjectStore(STORE); r.onsuccess = () => ok(r.result); r.onerror = () => no(r.error); }); }
async function cacheGet(key) { try { const db = await idb(); return await new Promise((ok) => { const q = db.transaction(STORE).objectStore(STORE).get(key); q.onsuccess = () => ok(q.result || null); q.onerror = () => ok(null); }); } catch (e) { return null; } }
async function cachePut(key, buf) { try { const db = await idb(); await new Promise((ok) => { const t = db.transaction(STORE, 'readwrite'); t.objectStore(STORE).put(buf, key); t.oncomplete = ok; t.onerror = ok; }); } catch (e) { /* a cache that cannot be written only costs a re-parse */ } }
async function sha256(buf) { const h = new Uint8Array(await crypto.subtle.digest('SHA-256', buf)); return Array.from(h, (b) => b.toString(16).padStart(2, '0')).join(''); }
let worker = null, cancelReject = null;
export function cancel() { if (worker) { worker.terminate(); worker = null; } if (cancelReject) { cancelReject(new Error('cancelled')); cancelReject = null; } }
async function download(url, progress) {
  const r = await fetch(url); if (!r.ok) throw new Error('HTTP ' + r.status + ' for ' + url);
  const len = +r.headers.get('content-length') || 0;
  if (!r.body || !r.body.getReader) return r.arrayBuffer();
  const rd = r.body.getReader(), chunks = []; let got = 0, last = 0;
  for (;;) { const { done, value } = await rd.read(); if (done) break; chunks.push(value); got += value.length;
    if (got - last > 1048576) { last = got; progress('Reading ' + (got / 1048576).toFixed(1) + (len ? ' / ' + (len / 1048576).toFixed(1) : '') + ' MB…'); } }
  const out = new Uint8Array(got); let o = 0; for (const c of chunks) { out.set(c, o); o += c.length; } return out.buffer;
}
function parseGlb(buf) { return new Promise((ok, no) => new GLTFLoader().parse(buf, '', (g) => ok(g.scene), no)); }
export async function load(url, format, progress = () => {}) {
  const buf = await download(url, progress);
  const key = format + ':' + await sha256(buf);
  let glb = await cacheGet(key);
  if (glb) progress('Opening the cached mesh…');
  else {
    glb = await new Promise((ok, no) => {
      cancelReject = no;
      worker = new Worker(new URL('./worker.js', import.meta.url));
      worker.onmessage = (e) => { const d = e.data;
        if (d.type === 'progress') progress(d.text);
        else { worker.terminate(); worker = null; cancelReject = null; d.type === 'done' ? ok(d.glb) : no(new Error(d.message)); } };
      worker.onerror = (e) => { if (worker) worker.terminate(); worker = null; cancelReject = null; no(new Error('the STEP worker failed: ' + (e.message || 'script error'))); };
      worker.postMessage({ buffer: buf, format }, [buf]);
    });
    await cachePut(key, glb);
  }
  return parseGlb(glb);
}
