/* cloud-store — Search > Games (#1765; moved here from cloud-media-center, #1895).
 *
 * Two halves, like pages.js. The pure half (parseCatalogue, renderGames, installIntent, openIntent, storeAction) takes values in and gives
 * HTML / URLs out, so test/test-store-games.sh drives it under node. The browser half loads the catalogue, asks the app (the CloudGames web
 * message bridge, GamesPage.kt; it was the Tauri command installed_packages in Media Center) which games are installed and wires Install / Open.
 *
 * THE CATALOGUE IS A RUNTIME DATASET, never bundled: data/fleet/games-catalogue.json in cloudy-u-android (listed in data/fleet/manifest.json,
 * the same framework as #1749's store-phone-recommendations), fetched from raw.githubusercontent.com with revalidation and kept as the
 * last-known-good copy in the webview storage (localStorage key cmc_games_v1, kept so a cache of the old name is not lost). The icons are the stores' own public listing image URLs, loaded at runtime and cached by the
 * webview; no third-party artwork is in the APK.
 */
(function (root) {
  'use strict';
  var CATALOGUE_URL = 'https://raw.githubusercontent.com/diegonmarcos/cloudy-u-android/main/data/fleet/games-catalogue.json';
  var CACHE_KEY = 'cmc_games_v1';
  var STORE_PKG = 'com.diegonmarcos.cloudstore';
  var CATEGORIES = ['action', 'adventure', 'puzzle', 'strategy', 'rpg', 'racing', 'sports', 'arcade', 'casual', 'simulation', 'board_card', 'opensource'];
  var SOURCES = ['play', 'fdroid', 'github'];

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function norm(s) { return String(s || '').toLowerCase(); }
  function isPkg(s) { return typeof s === 'string' && /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z][A-Za-z0-9_]*)+$/.test(s); }
  function isHttps(s) { return typeof s === 'string' && /^https:\/\/[^\s]+$/.test(s); }

  /* Never throws: a bad document is an empty catalogue that says why. Rows that break the schema are dropped and named in `problems`. */
  function parseCatalogue(text) {
    var d, problems = [];
    try { d = typeof text === 'string' ? JSON.parse(text) : text; } catch (e) { return { categories: [], games: [], problems: ['not JSON'] }; }
    if (!d || d.schemaVersion !== 1 || !Array.isArray(d.categories) || !Array.isArray(d.games)) return { categories: [], games: [], problems: ['wrong schema'] };
    var cats = d.categories.filter(function (c) { return c && typeof c.id === 'string' && typeof c.label === 'string'; });
    var ids = {}; cats.forEach(function (c) { ids[c.id] = true; });
    var seen = {}, games = d.games.filter(function (g) {
      var bad = !g || !isPkg(g.pkg) || !g.name || !g.developer || !g.desc || !ids[g.category] || SOURCES.indexOf(g.source) < 0 || !isHttps(g.icon) || !isHttps(g.store_url) || seen[g.pkg];
      if (bad) problems.push('dropped ' + (g && g.pkg)); else seen[g.pkg] = true;
      return !bad;
    });
    return { categories: cats, games: games, problems: problems };
  }

  /* ── what Install / Open do ───────────────────────────────────────────── */
  function openIntent(pkg) { return 'intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=' + pkg + ';end'; }
  // Cloud Store's hand-off contract (libs:updater StoreHandoff): action OPEN, extra install_pkg + tab.
  function storeIntent(pkg) { return 'intent:#Intent;action=com.diegonmarcos.cloudstore.OPEN;package=' + STORE_PKG + ';S.install_pkg=' + pkg + ';S.tab=phone;S.from=com.diegonmarcos.cloudstore;end'; }
  // The store listing: Play through the market:// intent aimed at the Play Store, F-Droid by its listing page (its own app takes the link).
  function listingIntent(g) { return g.source === 'play' ? 'intent://details?id=' + g.pkg + '#Intent;scheme=market;package=com.android.vending;end' : g.store_url; }
  /* The ordered URLs to try for Install: Cloud Store (F-Droid games: Cloud Store installs from the F-Droid index; it has no Play source)
   * when it is installed, then the store listing, then the https listing page. Open = the launcher intent. */
  function installIntent(g, storeInstalled) {
    var out = [];
    if (storeInstalled && g.source === 'fdroid') out.push(storeIntent(g.pkg));
    out.push(listingIntent(g));
    if (out.indexOf(g.store_url) < 0) out.push(g.store_url);
    return out;
  }
  function storeAction(g, installed) { return installed && installed[g.pkg] ? { kind: 'open', url: openIntent(g.pkg) } : { kind: 'install', urls: installIntent(g, !!(installed && installed[STORE_PKG])) }; }

  /* ── pure render ──────────────────────────────────────────────────────── */
  function matches(q, g) {
    if (!q) return true;
    var hay = norm([g.name, g.developer, g.desc, g.pkg].join(' '));
    return norm(q).split(/\s+/).every(function (w) { return hay.indexOf(w) >= 0; });
  }
  function rowHtml(g, installed) {
    var inst = !!(installed && installed[g.pkg]);
    return '<div class="grow" data-game="' + esc(g.pkg) + '"' + (inst ? ' data-installed' : '') + '>' +
      '<span class="grow__icon"><img alt="" loading="lazy" decoding="async" referrerpolicy="no-referrer" src="' + esc(g.icon) + '"></span>' +
      '<span class="grow__txt"><b>' + esc(g.name) + (inst ? ' <em class="grow__inst">Installed</em>' : '') + '</b><small>' + esc(g.developer) + '</small><span>' + esc(g.desc) + '</span></span>' +
      '<button class="btn' + (inst ? '' : ' btn--accent') + '" type="button" data-act="' + (inst ? 'open' : 'install') + '" data-pkg="' + esc(g.pkg) + '">' + (inst ? 'Open' : 'Install') + '</button></div>';
  }
  // st: { q, section (category id or ''), installed: { pkg: true } | null }
  function renderGames(cat, st) {
    st = st || {};
    var html = cat.categories.filter(function (c) { return !st.section || c.id === st.section; }).map(function (c) {
      var rows = cat.games.filter(function (g) { return g.category === c.id && matches(st.q, g); });
      if (!rows.length) return '';
      return '<h2 class="section-title" id="cat-' + esc(c.id) + '">' + esc(c.label) + ' · ' + rows.length + '</h2><div class="glist">' + rows.map(function (g) { return rowHtml(g, st.installed); }).join('') + '</div>';
    }).join('');
    return html || '<p class="empty">' + (cat.games.length ? 'No game matches.' : 'The catalogue is not loaded yet. Connect to the internet and reopen this tab.') + '</p>';
  }
  function installedCount(cat, installed) { return cat.games.filter(function (g) { return installed && installed[g.pkg]; }).length; }

  /* ── browser half ─────────────────────────────────────────────────────── */
  function store() { try { return root.localStorage || null; } catch (e) { return null; } }
  function cached() { var s = store(); try { return s && s.getItem(CACHE_KEY); } catch (e) { return null; } }
  function loadCatalogue() {
    var last = cached();
    return root.fetch(CATALOGUE_URL, { cache: 'no-cache' }).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status); return r.text(); }).then(function (t) {
      var c = parseCatalogue(t); if (!c.games.length) throw new Error('empty');
      try { store().setItem(CACHE_KEY, t); } catch (e) { /* storage full: keep going */ }
      return c;
    }).catch(function () { return parseCatalogue(last); });
  }
  /* The app bridge: GamesPage.kt registers the web message listener `CloudGames` (main frame of the asset origin only). A request is
   * {id, op, args}; the reply is {id, result} or {id, error}. `rt` ({ invoke }) lets the node test stand in for the app. */
  var seq = 0, pending = {}, wired = false;
  function wire() {
    var b = root.CloudGames; if (wired || !b) return;
    wired = true;
    b.onmessage = function (e) {
      var m; try { m = JSON.parse(e.data); } catch (x) { return; }
      var p = pending[m.id]; if (!p) return; delete pending[m.id];
      if (m.error) p.rej(new Error(String(m.error))); else p.res(m.result);
    };
  }
  function invoke(cmd, args, rt) {
    if (rt && rt.invoke) return rt.invoke(cmd, args);
    wire();
    if (!root.CloudGames || !root.CloudGames.postMessage) return Promise.reject(new Error('no runtime'));
    return new Promise(function (res, rej) { var id = ++seq; pending[id] = { res: res, rej: rej }; root.CloudGames.postMessage(JSON.stringify({ id: id, op: cmd, args: args || {} })); });
  }
  function detectInstalled(cat, rt) {
    var pkgs = cat.games.map(function (g) { return g.pkg; }).concat([STORE_PKG]);
    return invoke('installed_packages', { pkgs: pkgs }, rt).then(function (list) { var m = {}; (list || []).forEach(function (p) { m[p] = true; }); return m; }).catch(function () { return null; });
  }
  function openUrl(url) {
    return invoke('open_url', { url: url }).catch(function (e) {
      if (root.CloudGames) throw e;      // the app refused it (nothing handles the URL): tryUrls goes to the next one
      root.open(url, '_blank'); return undefined;
    });
  }
  function tryUrls(urls) { return urls.reduce(function (p, u) { return p.catch(function () { return openUrl(u); }); }, Promise.reject(new Error('start'))); }

  function boot() {
    var doc = root.document; if (!doc || doc.body.getAttribute('data-page') !== 'games') return;
    var main = doc.querySelector('main'), chips = doc.querySelector('.chips'), search = doc.querySelector('.search'), sub = doc.querySelector('.top h1 small');
    var st = { q: '', section: '', installed: null }, cat = { categories: [], games: [], problems: [] };
    search.placeholder = 'Search games, developers';
    function status() { sub.textContent = cat.games.length + ' games' + (st.installed ? ' · ' + installedCount(cat, st.installed) + ' installed' : ''); }
    function draw() { main.innerHTML = renderGames(cat, st); }
    function refresh() { return detectInstalled(cat).then(function (m) { st.installed = m; status(); draw(); }); }
    loadCatalogue().then(function (c) {
      cat = c; status();
      chips.innerHTML = '<button class="chip" aria-pressed="true" data-chip="">All</button>' + cat.categories.map(function (x) { return '<button class="chip" aria-pressed="false" data-chip="' + esc(x.id) + '">' + esc(x.label) + '</button>'; }).join('');
      draw(); refresh();
    });
    doc.addEventListener('visibilitychange', function () { if (!doc.hidden && cat.games.length) refresh(); });
    var timer;
    search.addEventListener('input', function () { clearTimeout(timer); timer = setTimeout(function () { st.q = search.value.trim(); draw(); }, 150); });
    chips.addEventListener('click', function (e) {
      var b = e.target.closest('.chip'); if (!b) return;
      [].forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c === b)); });
      st.section = b.getAttribute('data-chip'); draw(); root.scrollTo(0, 0);
    });
    main.addEventListener('click', function (e) {
      var b = e.target.closest('[data-act]'); if (!b) return;
      var g = cat.games.filter(function (x) { return x.pkg === b.getAttribute('data-pkg'); })[0]; if (!g) return;
      var a = storeAction(g, st.installed);
      (a.kind === 'open' ? openUrl(a.url).catch(function () { return tryUrls([g.store_url]); }) : tryUrls(a.urls)).catch(function () { /* nothing handles it */ });
    });
    main.addEventListener('error', function (e) { var i = e.target; if (i && i.tagName === 'IMG') i.style.visibility = 'hidden'; }, true);
  }

  var api = { CATALOGUE_URL: CATALOGUE_URL, STORE_PKG: STORE_PKG, CATEGORIES: CATEGORIES, parseCatalogue: parseCatalogue, renderGames: renderGames, rowHtml: rowHtml, matches: matches, openIntent: openIntent,
    storeIntent: storeIntent, listingIntent: listingIntent, installIntent: installIntent, storeAction: storeAction, installedCount: installedCount, detectInstalled: detectInstalled, boot: boot };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else { root.CmcGames = api; if (root.document) root.document.addEventListener('DOMContentLoaded', boot); }
})(typeof window !== 'undefined' ? window : this);
