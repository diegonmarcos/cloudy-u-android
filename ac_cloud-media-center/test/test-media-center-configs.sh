#!/bin/sh
# test-media-center-configs.sh — the header Settings button and the Configs /
# Profile pages (web/assets/configs.js) work.
#
# Static, no build, no network, no browser: node runs the real page scripts
# (sources-core.js + configs.js) in a vm against the committed web/*.html on a
# small DOM stand-in, and clicks through them:
#   - every page's header gets the round ⚙ button; tapping it opens the dropdown
#   - its Profile and Configs entries lead to pages that exist and render
#   - Configs has one section per media tab and each renders
#   - Movies inputs (servers, torrent settings, source on/off) persist
#   - a user torrent source can be added and removed
# Each run is then repeated on scratch copies with a planted defect, which
# must turn it red, so a check that cannot fail does not count.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }

check() {
  node - "$1" <<'JS'
const fs = require('fs'), path = require('path'), vm = require('vm');
const web = path.join(process.argv[2], 'web');
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };

/* ── a small DOM stand-in: enough for configs.js ─────────────────────── */
const VOID = new Set(['input', 'br', 'img', 'meta', 'link', 'hr']);
const ent = (s) => s.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
class Node_ {
  constructor(tag, attrs) { this.tagName = tag; this.attrs = attrs || {}; this.kids = []; this.parent = null; this.listeners = {}; }
  get children() { return this.kids.filter((k) => k.tagName); }
  getAttribute(n) { return n in this.attrs ? this.attrs[n] : null; }
  setAttribute(n, v) { this.attrs[n] = String(v); }
  removeAttribute(n) { delete this.attrs[n]; }
  hasAttribute(n) { return n in this.attrs; }
  get hidden() { return this.hasAttribute('hidden'); }
  set hidden(v) { if (v) this.attrs.hidden = ''; else delete this.attrs.hidden; }
  get className() { return this.attrs.class || ''; }
  set className(v) { this.attrs.class = v; }
  get value() {
    if (this._value !== undefined) return this._value;
    if (this.tagName === 'select') { const o = this.querySelectorAll('option'); const s = o.find((x) => x.hasAttribute('selected')) || o[0]; return s ? s.getAttribute('value') : ''; }
    return this.attrs.value || '';
  }
  set value(v) { this._value = String(v); }
  get checked() { return this._checked !== undefined ? this._checked : this.hasAttribute('checked'); }
  set checked(v) { this._checked = !!v; }
  get textContent() { return this.kids.map((k) => (k.tagName ? k.textContent : k.text)).join(''); }
  set textContent(t) { this.kids = [{ text: String(t) }]; }
  set innerHTML(h) { this.kids = []; parse(h, this); }
  get innerHTML() { return this.kids.map((k) => (k.tagName ? '<' + k.tagName + '>' + k.innerHTML + '</' + k.tagName + '>' : k.text)).join(''); }
  appendChild(c) { c.parent = this; this.kids.push(c); return c; }
  contains(n) { for (let x = n; x; x = x.parent) if (x === this) return true; return false; }
  matches(sel) {
    const m = /^([a-z0-9]*)((?:\.[\w-]+)*)((?:\[[^\]]+\])*)$/i.exec(sel.trim()); if (!m) throw new Error('selector ' + sel);
    if (m[1] && m[1] !== this.tagName) return false;
    const cls = (this.attrs.class || '').split(/\s+/);
    if (m[2] && !m[2].split('.').filter(Boolean).every((c) => cls.includes(c))) return false;
    return (m[3].match(/\[[^\]]+\]/g) || []).every((a) => { const p = /^\[([^=\]]+)(?:="?([^"\]]*)"?)?\]$/.exec(a); return p[2] === undefined ? this.hasAttribute(p[1]) : this.attrs[p[1]] === p[2]; });
  }
  querySelectorAll(sel) { const out = []; const walk = (n) => n.children.forEach((c) => { if (c.matches(sel)) out.push(c); walk(c); }); walk(this); return out; }
  querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
  closest(sel) { for (let x = this; x && x.tagName; x = x.parent) if (x.tagName !== '#doc' && x.matches(sel)) return x; return null; }
  addEventListener(t, f) { (this.listeners[t] = this.listeners[t] || []).push(f); }
}
function parse(html, root) {
  const re = /<!--[\s\S]*?-->|<!doctype[^>]*>|<\/([a-z0-9-]+)\s*>|<([a-z0-9-]+)((?:\s+[^\s=>\/]+(?:\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+))?)*)\s*\/?>|([^<]+)/gi;
  let cur = root, m;
  while ((m = re.exec(html))) {
    if (m[4] !== undefined) { cur.kids.push({ text: ent(m[4]) }); continue; }
    if (m[1]) { for (let x = cur; x !== root; x = x.parent) if (x.tagName === m[1].toLowerCase()) { cur = x.parent; break; } continue; }
    if (!m[2]) continue;
    const attrs = {}; const ar = /([^\s=]+)(?:\s*=\s*("([^"]*)"|'([^']*)'|([^\s>]+)))?/g; let a;
    while ((a = ar.exec(m[3] || ''))) attrs[a[1].toLowerCase()] = ent(a[3] !== undefined ? a[3] : a[4] !== undefined ? a[4] : a[5] !== undefined ? a[5] : '');
    const el = new Node_(m[2].toLowerCase(), attrs); cur.appendChild(el);
    if (!VOID.has(el.tagName) && !/\/>$/.test(m[0])) cur = el;
  }
}
function fire(target, type) {
  const ev = { type, target, defaultPrevented: false, stopped: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; } };
  for (let x = target; x && !ev.stopped; x = x.parent) (x.listeners[type] || []).forEach((f) => f(ev));
  return ev;
}

/* ── load a page the way the webview does ────────────────────────────── */
const tick = () => new Promise((r) => setImmediate(r));
function page(rel, storage, hash) {
  const doc = new Node_('#doc'); parse(fs.readFileSync(path.join(web, rel), 'utf8'), doc);
  const body = doc.querySelector('body');
  doc.body = body; doc.createElement = (t) => new Node_(t); doc.head = doc.querySelector('head');
  const win = { document: doc, localStorage: storage, navigator: { language: 'en-US' }, location: { hash: hash || '', href: '' }, history: { replaceState: (a, b, h) => { win.location.hash = h; } },
    fetch: (u) => { const f = path.join(web, path.dirname(rel), u); return fs.existsSync(f) ? Promise.resolve({ ok: true, json: () => Promise.resolve(JSON.parse(fs.readFileSync(f, 'utf8'))) }) : Promise.reject(new Error('404 ' + u)); },
    setTimeout, clearTimeout, console };
  win.window = win; vm.createContext(win);
  ['sources-core.js', 'media-sync.js', 'configs.js'].forEach((s) => vm.runInContext(fs.readFileSync(path.join(web, 'assets', s), 'utf8'), win, { filename: s }));
  win.CmcConfig.boot();
  return { win, doc, body, main: doc.querySelector('main') };
}
function mem() { const m = {}; return { getItem: (k) => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, removeItem: (k) => { delete m[k]; }, _m: m }; }

(async () => {
  /* 1. the Settings button on every page, and its dropdown */
  const pages = ['index.html'].concat(fs.readdirSync(web).filter((d) => fs.existsSync(path.join(web, d, 'index.html'))).map((d) => d + '/index.html'));
  ok(pages.length >= 10, 'every page found (' + pages.length + ')');
  for (const rel of pages) {
    const p = page(rel, mem());
    const row = p.doc.querySelector('.top__row'), btn = row && row.querySelector('[data-cfgbtn]'), menu = row && row.querySelector('.cfgmenu');
    ok(btn && menu && menu.hidden, rel + ': header has the ⚙ button, dropdown closed');
    if (!btn) continue;
    fire(btn, 'click');
    ok(!menu.hidden && btn.getAttribute('aria-expanded') === 'true', rel + ': tapping ⚙ opens the dropdown');
    const items = menu.querySelectorAll('a[data-menu]').map((a) => a.getAttribute('data-menu'));
    ok(JSON.stringify(items) === '["profile","configs"]', rel + ': the dropdown holds Profile and Configs');
    for (const a of menu.querySelectorAll('a[data-menu]')) {
      const dest = path.normalize(path.join(path.dirname(rel), a.getAttribute('href')));
      const html = fs.existsSync(path.join(web, dest)) ? fs.readFileSync(path.join(web, dest), 'utf8') : '';
      ok(html.includes('data-page="' + a.getAttribute('data-menu') + '"'), rel + ': ' + a.getAttribute('data-menu') + ' entry navigates to ' + dest);
    }
    fire(p.main, 'click');
    ok(menu.hidden, rel + ': tapping elsewhere closes it');
  }

  /* 2. Profile renders the fleet account entry */
  const pr = page('profile/index.html', mem()); await tick(); await tick();
  ok(pr.main.querySelector('[data-account]') && pr.main.querySelector('[data-account]').getAttribute('href').includes('com.diegonmarcos.cloudaccount'), 'profile: links out to cloud-account');
  ok(/TIDAL/.test(pr.main.textContent), 'profile: shows the linked media profiles');

  /* 3. Configs: one section per media tab, each renders */
  const store = mem();
  const c = page('configs/index.html', store); await tick(); await tick();
  const chipIds = c.doc.querySelectorAll('.chip').map((b) => b.getAttribute('data-chip'));
  ok(JSON.stringify(chipIds) === '["music","videos","gallery","books","feed","movies","apify","general","perms","about"]', 'configs: a section per tab (' + chipIds.join(', ') + ')');
  for (const id of chipIds) {
    fire(c.doc.querySelector('.chip[data-chip="' + id + '"]'), 'click'); await tick();
    const fields = c.main.querySelectorAll('[data-key]');
    ok(c.main.querySelector('.cfg[data-cfg="' + id + '"]') && (fields.length >= 1 || ['apify', 'general', 'perms', 'about'].includes(id)), 'configs: ' + id + ' renders its settings (' + fields.length + ' fields)');
  }
  fire(c.doc.querySelector('.chip[data-chip="movies"]'), 'click'); await tick();
  const secs = c.main.querySelectorAll('[data-msec]').map((s) => s.getAttribute('data-msec'));
  ok(JSON.stringify(secs) === '["sources","playback","torrent"]', 'configs/movies: Sources, Playback, Torrents');
  const need = ['tmdb_key', 'region', 'jellyfin.url', 'jellyfin.token', 'emby.url', 'emby.token', 'plex.url', 'plex.token', 'drive.url', 'buffer', 'offline',
    'torrent.folder', 'torrent.max_conns', 'torrent.down_kbps', 'torrent.up_kbps', 'torrent.seed_ratio', 'torrent.seed_minutes', 'torrent.sequential'];
  const have = c.main.querySelectorAll('[data-key]').map((i) => i.getAttribute('data-key'));
  ok(need.every((k) => have.includes(k)), 'configs/movies: every source and torrent input is there');
  ok(c.main.querySelector('[data-src-on="archive-torrent"]') && c.main.querySelector('[data-src-on="jellyfin"]'), 'configs/movies: built-in sources can be switched on/off');

  /* 4. Movies inputs persist */
  const set = (k, v) => { const i = c.main.querySelector('[data-key="' + k + '"]'); if (i.getAttribute('type') === 'checkbox') i.checked = v; else i.value = v; fire(i, 'change'); };
  set('jellyfin.url', 'https://jf.example'); set('tmdb_key', 'k123'); set('region', 'pt'); set('torrent.max_conns', '20'); set('torrent.sequential', false); set('offline', true);
  const off = c.main.querySelector('[data-src-on="archive-torrent"]'); off.checked = false; fire(off, 'change');
  const again = page('configs/index.html', store, '#movies'); await tick(); await tick();
  const saved = again.win.CmcConfig.loadTab('movies');
  ok(saved.jellyfin.url === 'https://jf.example' && saved.tmdb_key === 'k123' && saved.region === 'PT', 'configs/movies: server, key and region persist');
  ok(saved.torrent.max_conns === 20 && saved.torrent.sequential === false && saved.offline === true, 'configs/movies: torrent and playback settings persist');
  ok(saved.disabled.includes('archive-torrent'), 'configs/movies: a switched-off source stays off');
  ok(again.main.querySelector('[data-key="jellyfin.url"]').getAttribute('value') === 'https://jf.example', 'configs/movies: reopening shows the saved value');
  ok(JSON.parse(store.getItem('cmc_sources_v1')).jellyfin.url === 'https://jf.example', 'configs/movies: stored under the player\'s key (cmc_sources_v1)');
  const mv = page('music/index.html', store); ok(mv.win.CmcConfig.loadTab('music').size === 'normal', 'configs: other tabs keep their defaults');

  /* 5. a user torrent source: add, then remove */
  const m = again.main, nv = (k, v) => { m.querySelector('[data-unew="' + k + '"]').value = v; };
  nv('name', 'My Jackett'); nv('type', 'torznab'); nv('url', 'https://jackett.local/api'); nv('apikey', 'abc');
  fire(m.querySelector('[data-uadd]'), 'click');
  const list = JSON.parse(store.getItem('cmc_user_sources_v1') || '[]');
  ok(list.length === 1 && list[0].name === 'My Jackett' && m.querySelectorAll('[data-user]').length === 1, 'configs/movies: a user torrent source is added and listed');
  nv('name', 'Bad'); nv('type', 'template'); nv('url', 'https://x.example/search');
  fire(m.querySelector('[data-uadd]'), 'click');
  ok(JSON.parse(store.getItem('cmc_user_sources_v1')).length === 1 && /\{query\}/.test(m.querySelector('.cfg__umsg').textContent), 'configs/movies: an invalid source is refused with a reason');
  fire(m.querySelector('[data-udel="' + list[0].id + '"]'), 'click');
  ok(JSON.parse(store.getItem('cmc_user_sources_v1')).length === 0 && m.querySelectorAll('[data-user]').length === 0, 'configs/movies: the user source is removed');

  /* 6. the player's ⚙ opens Configs → Movies */
  const pl = fs.readFileSync(path.join(web, 'assets/player.js'), 'utf8');
  ok(pl.includes('href="../configs/index.html#movies"'), "player: its ⚙ opens Configs → Movies");
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('  FAIL ' + (e.stack || e)); process.exit(1); });
JS
}

echo "== configs: the real tree =="
check "$APP" || exit 1

plant() {  # $1 = label, $2 = file under web/assets, $3 = python replace (old), $4 = new
  scratch="$(mktemp -d)"
  trap 'rm -rf "$scratch"' EXIT
  cp -R "$APP/web" "$scratch/web"
  python3 - "$scratch/web/assets/$2" "$3" "$4" <<'PY'
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
assert old in s, "mutation anchor missing: " + old
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
  if check "$scratch" >/dev/null 2>&1; then echo "  FAIL mutation: $1 stayed green"; rm -rf "$scratch"; exit 1; fi
  echo "  ok   mutation: $1 turns it red"
  rm -rf "$scratch"
  trap - EXIT
}
echo "== mutations: each planted defect must turn it red =="
plant "the ⚙ does not open" configs.js "set(menu.hidden); });" "});"
plant "Movies inputs are not saved" configs.js "saveTab(tab, cfg); return;" "return;"
plant "a user source cannot be removed" configs.js "storeU(L.removeUserSource(users, d.getAttribute('data-udel'))); return;" "return;"
plant "no Movies torrent section" configs.js "if (sec.id === 'torrent') html += '<h4>Built-in" "if (false) html += '<h4>Built-in"
echo "PASS: test-media-center-configs"
