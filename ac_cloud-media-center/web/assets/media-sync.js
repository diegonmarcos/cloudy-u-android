/* cloud-media-center — playlist sync (#1469): the source declaration parser, the Apify token store, the mappers, the
 * matcher and the runner. Pure functions first (tested under node by test/test-media-center-sync.sh), browser half last.
 *
 * Declared in data/media-sources.json, nothing here names a service by hand except the three official-API mappers.
 * Metadata only: titles, artists, ISRC, covers, links. No audio or video is fetched and no DRM is touched.
 * Tokens are encrypted (AES-GCM, WebCrypto; the key is non-extractable and lives in IndexedDB). The key names
 * (apify.token, crawlee.token, apify.backend) are those of the fleet's shared Apify store (libs:scraper ApifyAccount).
 * The matcher is a port of libs:scraper Matcher (#1460): ISRC first, then title + artist (+ length) fuzzy.
 */
(function (root) {
  'use strict';
  var METHODS = ['direct', 'apify'], KINDS = ['playlist', 'board'], APIS = ['spotify', 'youtube', 'deezer'];
  var LIB_KEY = 'cmc_library_v1', CFG_KEY = 'cmc_sources_cfg_v1', SYNC_KEY = 'cmc_sync_v1';

  /* ── the declaration ─────────────────────────────────────────────────── */
  function parseSources(raw) {
    var d, errors = [];
    try { d = typeof raw === 'string' ? JSON.parse(raw) : raw; } catch (e) { return { error: 'not JSON', sources: [], areas: [], backends: {}, errors: ['not JSON'] }; }
    if (!d || typeof d !== 'object') return { error: 'not an object', sources: [], areas: [], backends: {}, errors: ['not an object'] };
    var areas = (d.areas || []).filter(function (a) { return a && a.id && a.tab; });
    var ab = d.apify || {}, backends = {};
    (ab.backends || []).forEach(function (b) {
      if (!b || !b.id || !(/^https:\/\//.test(b.api || '') || /^https:\/\//.test(b.default_url || ''))) return;
      var o = {}; Object.keys(b).forEach(function (k) { o[k] = b[k]; }); o.label = b.label || b.id; var cf = b.kind === 'cloudflare'; o.token_key = cf ? 'cloudflare.api_token' : b.api ? 'apify.token' : 'apify.self.bearer'; if (cf) o.account_key = 'cloudflare.account_id'; backends[b.id] = o;
    });
    if (!backends.cloud) errors.push('backends: Apify Cloud missing');
    var def = backends[ab.default_backend] ? ab.default_backend : 'cloud';
    if (ab.default_backend && ab.default_backend !== 'cloud') errors.push('default_backend must be cloud (Crawlee is opt-in)');
    var seen = {}, sources = [];
    (d.sources || []).forEach(function (s, i) {
      var w = 'sources[' + i + '] ' + (s && s.id);
      if (!s || !/^[a-z0-9-]+$/.test(s.id || '')) return errors.push('sources[' + i + ']: bad id');
      if (seen[s.id]) return errors.push(w + ': duplicate id'); seen[s.id] = 1;
      if (!areas.some(function (a) { return a.id === s.area; })) return errors.push(w + ': unknown area ' + s.area);
      if (KINDS.indexOf(s.kind) < 0) return errors.push(w + ': kind must be playlist or board');
      var methods = (s.methods && s.methods.length ? s.methods : [s.method]).filter(function (m) { return METHODS.indexOf(m) >= 0; });
      if (!methods.length || methods.indexOf(s.method) < 0) return errors.push(w + ': method must be direct or apify and listed in methods');
      if (methods.indexOf('apify') >= 0 && !/^[\w.~-]+\/[\w.~-]+$/.test(s.actor || '')) return errors.push(w + ': an apify source needs an actor id user/name');
      if (methods.indexOf('direct') >= 0 && APIS.indexOf(s.api) < 0) return errors.push(w + ': a direct source needs a known api (' + APIS.join('|') + ')');
      var o = {}; Object.keys(s).forEach(function (k) { o[k] = s[k]; }); o.methods = methods; sources.push(o);
    });
    return { version: d.version || 1, default_backend: def, backends: backends, areas: areas, sources: sources, app_pages: d.app_pages || {}, errors: errors };
  }
  /* the owner's per-source choices over the declaration: {enabled, method, actor, ref} */
  function effective(src, cfg) {
    var c = (cfg && cfg[src.id]) || {};
    var method = src.methods.indexOf(c.method) >= 0 ? c.method : src.method;
    var actor = typeof c.actor === 'string' && /^[\w.~-]+\/[\w.~-]+$/.test(c.actor.trim()) ? c.actor.trim() : src.actor || '';
    return { id: src.id, area: src.area, kind: src.kind, label: src.label, api: src.api, token_key: src.token_key || '', input: src.input, method: method,
      actor: actor, ref: typeof c.ref === 'string' ? c.ref.trim() : '', enabled: c.enabled === true };
  }
  function runnable(e) {
    if (!e.enabled) return 'off';
    if (e.method === 'apify') return e.actor && e.ref ? '' : 'needs an actor and a URL';
    return e.api === 'deezer' ? (e.ref ? '' : 'needs a Deezer user id') : '';
  }

  /* ── the Apify settings (shared store keys) ─────────────────────────── */
  function backendOf(decl, id) { return decl.backends[id] || decl.backends[decl.default_backend] || decl.backends.cloud; }
  function validToken(t) { t = String(t || '').trim(); return t.length >= 8 && !/\s/.test(t) ? t : ''; }
  function masked(t) { return !t ? '' : t.length <= 8 ? '••••' : t.slice(0, 4) + '••••' + t.slice(-2); }
  /* the API base ending in /v2; the self-hosted one takes the owner's URL (apify.self.url) or its declared default */
  function apiBase(backend, selfUrl) {
    if (backend.api) return backend.api;
    var u = String(selfUrl && /^https:\/\//.test(selfUrl.trim()) ? selfUrl : backend.default_url || '').trim().replace(/\/+$/, '');
    return /\/v2$/.test(u) ? u : u + '/v2';
  }
  function actorUrl(backend, actor, path) { return backend.api + '/acts/' + String(actor).replace('/', '~') + '/' + path; }

  /* ── encrypted secrets ───────────────────────────────────────────────── */
  function b64(buf) { var s = '', a = new Uint8Array(buf); for (var i = 0; i < a.length; i++) s += String.fromCharCode(a[i]); return (root.btoa || function (x) { return Buffer.from(x, 'binary').toString('base64'); })(s); }
  function unb64(s) { var r = (root.atob || function (x) { return Buffer.from(x, 'base64').toString('binary'); })(s), a = new Uint8Array(r.length); for (var i = 0; i < r.length; i++) a[i] = r.charCodeAt(i); return a; }
  function idbKey() {
    return new Promise(function (res, rej) {
      var open = root.indexedDB.open('cmc_keys', 1);
      open.onupgradeneeded = function () { open.result.createObjectStore('k'); };
      open.onerror = function () { rej(open.error); };
      open.onsuccess = function () {
        var db = open.result, get = db.transaction('k').objectStore('k').get('aes');
        get.onsuccess = function () {
          if (get.result) return res(get.result);
          root.crypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']).then(function (k) {
            var put = db.transaction('k', 'readwrite').objectStore('k').put(k, 'aes'); put.onsuccess = function () { res(k); }; put.onerror = function () { rej(put.error); };
          }, rej);
        };
        get.onerror = function () { rej(get.error); };
      };
    });
  }
  /* SecretStore(storage, keyProvider): put/get/remove of strings, ciphertext only in storage. keyProvider() -> Promise<CryptoKey>. */
  function SecretStore(storage, keyProvider) {
    var cr = root.crypto || (typeof require === 'function' ? require('crypto').webcrypto : {}), subtle = cr.subtle, kp = keyProvider || idbKey, P = 'cmc_secret_';
    return {
      put: function (name, value) {
        var iv = cr.getRandomValues(new Uint8Array(12));
        return kp().then(function (k) { return subtle.encrypt({ name: 'AES-GCM', iv: iv }, k, new TextEncoder().encode(String(value))); })
          .then(function (ct) { storage.setItem(P + name, b64(iv) + '.' + b64(ct)); });
      },
      get: function (name) {
        var raw = storage.getItem(P + name); if (!raw) return Promise.resolve('');
        var p = raw.split('.');
        return kp().then(function (k) { return subtle.decrypt({ name: 'AES-GCM', iv: unb64(p[0]) }, k, unb64(p[1])); })
          .then(function (pt) { return new TextDecoder().decode(pt); }, function () { return ''; });
      },
      remove: function (name) { storage.removeItem(P + name); return Promise.resolve(); },
      has: function (name) { return !!storage.getItem(P + name); }
    };
  }

  /* ── the matcher (port of libs:scraper Matcher, #1460) ───────────────── */
  var Match = (function () {
    var THRESHOLD = 0.79, MIN_T = 0.70, MIN_A = 0.70;
    function isrc(raw) { var n = String(raw || '').toUpperCase().replace(/[^A-Z0-9]/g, ''); return /^[A-Z]{2}[A-Z0-9]{3}[0-9]{7}$/.test(n) ? n : ''; }
    function norm(s) {
      var t = String(s || '').toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '');
      t = t.replace(/[(\[{][^)\]}]*[)\]}]/g, ' ').replace(/\s-\s.*(remaster|version|edit|mix|live|mono|stereo).*$/, '').replace(/\b(feat|ft|featuring)\b\.?.*$/, '');
      return t.replace(/[^a-z0-9À-￿]+/g, ' ').trim();
    }
    function dice(a, b) {
      if (a.length < 2 || b.length < 2) return 0; var x = {}, y = {}, i, inter = 0;
      for (i = 0; i < a.length - 1; i++) x[a.substr(i, 2)] = (x[a.substr(i, 2)] || 0) + 1;
      for (i = 0; i < b.length - 1; i++) y[b.substr(i, 2)] = (y[b.substr(i, 2)] || 0) + 1;
      Object.keys(x).forEach(function (k) { inter += Math.min(x[k], y[k] || 0); });
      return 2 * inter / ((a.length - 1) + (b.length - 1));
    }
    function jaccard(a, b) {
      var x = a.split(' '), y = b.split(' '), u = {}, n = 0; x.concat(y).forEach(function (w) { u[w] = 1; });
      x.forEach(function (w, i) { if (x.indexOf(w) === i && y.indexOf(w) >= 0) n++; });
      return n / Object.keys(u).length;
    }
    function sim(a, b) { if (!a || !b) return 0; if (a === b) return 1; return Math.max(dice(a, b), jaccard(a, b)); }
    function artistSim(a, b) {
      var split = /\s*(?:,|&|;|\/|\bfeat\.?|\bft\.?|\band\b|\bx\b)\s*/;
      var best = sim(norm(a), norm(b));
      String(a || '').toLowerCase().split(split).forEach(function (x) { String(b || '').toLowerCase().split(split).forEach(function (y) { var nx = norm(x), ny = norm(y); if (nx && ny) best = Math.max(best, sim(nx, ny)); }); });
      return best;
    }
    function durationSim(a, b) { if (!(a > 0) || !(b > 0)) return null; var d = Math.abs(a - b); return d <= 2000 ? 1 : d >= 10000 ? 0 : 1 - (d - 2000) / 8000; }
    function score(a, b) {
      var t = sim(norm(a.title), norm(b.title)), ar = artistSim(a.artist, b.artist);
      if (t < MIN_T || ar < MIN_A) return null;
      var d = durationSim(a.durationMs, b.durationMs);
      return d === null ? 0.6 * t + 0.4 * ar : 0.5 * t + 0.3 * ar + 0.2 * d;
    }
    function best(src, cands) {
      var code = isrc(src.isrc), i;
      if (code) for (i = 0; i < cands.length; i++) if (isrc(cands[i].isrc) === code) return { how: 'isrc', candidate: cands[i], score: 1 };
      var top = 0, pick = null;
      cands.forEach(function (c) { var s = score(src, c); if (s !== null && s > top) { top = s; pick = c; } });
      return pick && top >= THRESHOLD ? { how: 'fuzzy', candidate: pick, score: top } : { how: 'none', candidate: null, score: top };
    }
    return { isrc: isrc, norm: norm, sim: sim, artistSim: artistSim, score: score, best: best, THRESHOLD: THRESHOLD };
  })();
  /* mark every track of the music playlists that the local collection already has: {how, in_collection} */
  function matchPlaylist(pl, local) {
    var found = 0;
    pl.items.forEach(function (t) { var m = Match.best(t, local); t.match = m.how; if (m.how !== 'none') found++; });
    pl.matched = found; return pl;
  }
  /* music.json -> [{title, artist}] */
  function localTracks(music) {
    var out = []; ((music && music.sections) || []).forEach(function (s) { (s.folders || []).forEach(function (f) { (f.playlists || []).forEach(function (p) { (p.items || []).forEach(function (it) { out.push({ title: it[0], artist: it[1] }); }); }); }); });
    return out;
  }

  /* ── mappers: service JSON -> {id, source, area, kind, title, owner, count, url, image, items:[...]} ─ */
  function pl(source, area, kind, o) {
    return { id: source + ':' + o.id, source: source, area: area, kind: kind, title: String(o.title || 'Untitled'), owner: o.owner || '', count: o.count == null ? (o.items || []).length : o.count,
      url: o.url || '', image: o.image || '', items: o.items || [] };
  }
  function first(a) { return a && a.length ? a[0] : null; }
  function mapSpotifyPlaylists(j) {
    return ((j && j.items) || []).filter(Boolean).map(function (p) {
      return pl('spotify', 'music', 'playlist', { id: p.id, title: p.name, owner: p.owner && p.owner.display_name, count: p.tracks && p.tracks.total, url: p.external_urls && p.external_urls.spotify,
        image: first(p.images) && first(p.images).url });
    });
  }
  function mapSpotifyTracks(j) {
    return ((j && j.items) || []).map(function (e) { return e && e.track; }).filter(function (t) { return t && t.id && !t.is_local; }).map(function (t) {
      return { title: t.name, artist: (t.artists || []).map(function (a) { return a.name; }).join(', '), album: t.album && t.album.name || '', isrc: Match.isrc(t.external_ids && t.external_ids.isrc), durationMs: t.duration_ms || 0,
        url: t.external_urls && t.external_urls.spotify || '', image: t.album && first(t.album.images) && first(t.album.images).url || '' };
    });
  }
  function isoMs(iso) { var m = /^PT(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?$/.exec(iso || ''); return m ? ((+m[1] || 0) * 3600 + (+m[2] || 0) * 60 + (+m[3] || 0)) * 1000 : 0; }
  function ytThumb(t) { t = t || {}; return (t.medium || t.default || t.high || {}).url || ''; }
  function mapYouTubePlaylists(j) {
    return ((j && j.items) || []).filter(function (p) { return p && p.id; }).map(function (p) {
      var s = p.snippet || {};
      return pl('youtube', 'video', 'playlist', { id: p.id, title: s.title, owner: s.channelTitle, count: p.contentDetails && p.contentDetails.itemCount, url: 'https://www.youtube.com/playlist?list=' + p.id, image: ytThumb(s.thumbnails) });
    });
  }
  function mapYouTubeItems(j, durations) {
    return ((j && j.items) || []).map(function (e) { return { s: e.snippet || {}, id: (e.contentDetails && e.contentDetails.videoId) || (e.snippet && e.snippet.resourceId && e.snippet.resourceId.videoId) }; })
      .filter(function (e) { return e.id && e.s.title !== 'Private video' && e.s.title !== 'Deleted video'; }).map(function (e) {
        return { title: e.s.title, artist: String(e.s.videoOwnerChannelTitle || e.s.channelTitle || '').replace(/ - Topic$/, ''), isrc: '', durationMs: (durations && durations[e.id]) || 0,
          url: 'https://www.youtube.com/watch?v=' + e.id, image: ytThumb(e.s.thumbnails) };
      });
  }
  function mapDeezerPlaylists(j) {
    return ((j && j.data) || []).filter(function (p) { return p && p.id; }).map(function (p) {
      return pl('deezer', 'music', 'playlist', { id: String(p.id), title: p.title, owner: p.creator && p.creator.name, count: p.nb_tracks, url: p.link, image: p.picture_medium || p.picture });
    });
  }
  function mapDeezerTracks(j) {
    return ((j && j.data) || []).filter(function (t) { return t && t.id; }).map(function (t) {
      return { title: t.title, artist: t.artist && t.artist.name || '', isrc: Match.isrc(t.isrc), durationMs: (t.duration || 0) * 1000, url: t.link || '', image: t.album && (t.album.cover_medium || t.album.cover) || '' };
    });
  }
  /* Pinterest through an Apify actor: pin items grouped by board, or board items taken as they come. The field names differ per actor, so each is tried in turn. */
  function pick(o, names) { for (var i = 0; i < names.length; i++) { var v = names[i].split('.').reduce(function (a, k) { return a == null ? a : a[k]; }, o); if (v != null && v !== '') return v; } return ''; }
  function mapPinterest(items) {
    var boards = {}, order = [];
    (items || []).forEach(function (it) {
      if (!it || typeof it !== 'object') return;
      var isBoard = it.type === 'board' || (it.pin_count != null || it.pinCount != null) && !it.images && !it.imageUrl;
      var bname = isBoard ? pick(it, ['name', 'title']) : pick(it, ['board.name', 'boardName', 'board_name', 'board.title']);
      var burl = isBoard ? pick(it, ['url']) : pick(it, ['board.url', 'boardUrl', 'board_url']);
      if (!bname) return;
      var id = String(pick(isBoard ? it : (it.board || {}), ['id']) || burl || bname).replace(/^https?:\/\/(www\.)?pinterest\.[a-z.]+\//, '').replace(/\/$/, '');
      var b = boards[id]; if (!b) { b = boards[id] = pl('pinterest', 'images', 'board', { id: id, title: bname, owner: pick(it, ['owner.username', 'board.owner.username', 'owner', 'username']), url: burl || '', count: null, items: [] }); order.push(id); }
      if (isBoard) { var c = pick(it, ['pin_count', 'pinCount']); if (c !== '') b.count = Number(c); if (!b.image) b.image = pick(it, ['cover', 'image', 'imageUrl', 'images.orig.url']); return; }
      var img = pick(it, ['images.orig.url', 'images.736x.url', 'imageUrl', 'image', 'image_url']);
      b.items.push({ title: pick(it, ['title', 'grid_title', 'description']) || 'Pin', artist: '', isrc: '', durationMs: 0, url: pick(it, ['url', 'link']) || (it.id ? 'https://www.pinterest.com/pin/' + it.id + '/' : ''), image: img });
      if (!b.image) b.image = img;
    });
    return order.map(function (k) { var b = boards[k]; if (b.count == null || b.count < b.items.length) b.count = b.items.length; return b; });
  }
  /* Music/video playlists through an Apify actor: one item per track carrying its playlist, or one item per playlist with `tracks`. */
  function mapApifyTracks(source, area, items) {
    var by = {}, order = [];
    function add(pid, title, url, owner, t, image) {
      var p = by[pid]; if (!p) { p = by[pid] = pl(source, area, 'playlist', { id: pid, title: title, url: url, owner: owner, count: null, items: [], image: image }); order.push(pid); }
      if (t) p.items.push(t);
    }
    function track(o) {
      return { title: pick(o, ['title', 'name', 'trackName']), artist: [].concat(pick(o, ['artist', 'artistName', 'author', 'user.username', 'channelName', 'artists']) || []).map(function (a) { return typeof a === 'object' ? a.name : a; }).join(', '),
        isrc: Match.isrc(pick(o, ['isrc', 'external_ids.isrc'])), durationMs: Number(pick(o, ['durationMs', 'duration_ms'])) || (Number(pick(o, ['duration', 'durationSeconds'])) || 0) * 1000, url: pick(o, ['url', 'link', 'permalink_url']), image: pick(o, ['image', 'imageUrl', 'artwork_url', 'thumbnailUrl', 'cover']) };
    }
    (items || []).forEach(function (it) {
      if (!it || typeof it !== 'object') return;
      if (Array.isArray(it.tracks)) { var pid = String(pick(it, ['id', 'url', 'title', 'name'])); add(pid, pick(it, ['title', 'name']), pick(it, ['url']), pick(it, ['owner', 'author', 'user.username']), null, pick(it, ['image', 'artwork_url', 'imageUrl'])); it.tracks.forEach(function (t) { by[pid].items.push(track(t)); }); return; }
      var pn = pick(it, ['playlist.title', 'playlist.name', 'playlistName', 'playlist']); if (!pn) return;
      add(String(pick(it, ['playlist.id', 'playlistId', 'playlist.url', 'playlistUrl']) || pn), pn, pick(it, ['playlist.url', 'playlistUrl']), pick(it, ['playlist.owner', 'playlistOwner']), track(it), pick(it, ['image', 'imageUrl']));
    });
    return order.map(function (k) { var p = by[k]; p.items = p.items.filter(function (t) { return t.title; }); p.count = p.items.length; return p; });
  }
  function mapApify(e, items) { return e.kind === 'board' ? mapPinterest(items) : mapApifyTracks(e.id, e.area, items); }

  /* ── the runner ──────────────────────────────────────────────────────── */
  function J(deps, url, headers, opt) {
    return deps.fetch(url, Object.assign({ headers: headers || {} }, opt || {})).then(function (r) { if (!r.ok) throw new Error('HTTP ' + r.status + ' ' + url.split('?')[0]); return r.json(); });
  }
  function fillInput(input, ref) { return JSON.parse(JSON.stringify(input || {}).replace(/\{ref\}/g, String(ref).replace(/\\/g, '\\\\').replace(/"/g, '\\"'))); }
  /* deps: { fetch, secret(name) -> Promise<string>, backend: declaration entry, apifyToken: Promise<string> } */
  /* Cloudflare Browser Run: render the ref page as Markdown on Cloudflare's headless browser; the answer is one item {url, markdown} */
  function cloudflareRun(e, deps, token) {
    return deps.secret(deps.backend.account_key).then(function (acct) {
      if (!/^[0-9a-fA-F]{32}$/.test(String(acct || '').trim())) throw new Error('no Cloudflare account id (Configs > Apify)');
      var url = deps.backend.api + '/accounts/' + String(acct).trim() + '/browser-rendering/markdown';
      return J(deps, url, { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, { method: 'POST', body: JSON.stringify({ url: String(e.ref) }) });
    }).then(function (r) {
      if (!r || r.success !== true) throw new Error((r && r.errors && r.errors[0] && r.errors[0].message) || 'Cloudflare refused the request');
      return [{ url: String(e.ref), markdown: r.result }];
    });
  }
  function runApify(e, deps) {
    return deps.apifyToken().then(function (token) {
      if (!token) throw new Error('no ' + deps.backend.label + ' token (Configs > Apify)');
      if (deps.backend.kind === 'cloudflare') return cloudflareRun(e, deps, token);
      var url = actorUrl(deps.backend, e.actor, 'run-sync-get-dataset-items') + '?format=json&clean=true';
      return J(deps, url, { Authorization: 'Bearer ' + token, 'Content-Type': 'application/json' }, { method: 'POST', body: JSON.stringify(fillInput(e.input, e.ref)) });
    }).then(function (items) { return mapApify(e, items); });
  }
  function seq(list, fn) { return list.reduce(function (p, x) { return p.then(function (acc) { return fn(x).then(function (r) { acc.push(r); return acc; }); }); }, Promise.resolve([])); }
  function runDirect(e, deps) {
    if (e.api === 'deezer') {
      var base = 'https://api.deezer.com';
      return J(deps, base + '/user/' + encodeURIComponent(e.ref) + '/playlists?limit=100').then(function (j) {
        var pls = mapDeezerPlaylists(j);
        return seq(pls, function (p) { return J(deps, base + '/playlist/' + p.id.split(':')[1] + '/tracks?limit=200').then(function (t) { p.items = mapDeezerTracks(t); return p; }); });
      });
    }
    return deps.secret(e.token_key).then(function (token) {
      if (!token) throw new Error('no ' + e.label + ' token (Configs > ' + (e.area === 'music' ? 'Music' : 'Video') + ' > Direct)');
      var h = { Authorization: 'Bearer ' + token };
      if (e.api === 'spotify') {
        return J(deps, 'https://api.spotify.com/v1/me/playlists?limit=50', h).then(function (j) {
          return seq(mapSpotifyPlaylists(j), function (p) {
            return J(deps, 'https://api.spotify.com/v1/playlists/' + p.id.split(':')[1] + '/tracks?limit=100&fields=items(track(id,name,is_local,duration_ms,external_ids,external_urls,artists(name),album(name,images)))', h).then(function (t) { p.items = mapSpotifyTracks(t); return p; });
          });
        });
      }
      var yt = 'https://www.googleapis.com/youtube/v3';
      return J(deps, yt + '/playlists?part=snippet,contentDetails&mine=true&maxResults=50', h).then(function (j) {
        return seq(mapYouTubePlaylists(j), function (p) {
          return J(deps, yt + '/playlistItems?part=snippet,contentDetails&maxResults=50&playlistId=' + p.id.split(':')[1], h).then(function (t) {
            var ids = (t.items || []).map(function (x) { return x.contentDetails && x.contentDetails.videoId; }).filter(Boolean).join(',');
            return (ids ? J(deps, yt + '/videos?part=contentDetails&id=' + ids, h) : Promise.resolve({ items: [] })).then(function (v) {
              var dur = {}; (v.items || []).forEach(function (x) { dur[x.id] = isoMs(x.contentDetails && x.contentDetails.duration); });
              p.items = mapYouTubeItems(t, dur); return p;
            });
          });
        });
      });
    });
  }
  /* syncAll(decl, cfg, deps, local) -> Promise<{playlists, report:[{id, ok, count|error}]}>; one failing source never stops the others */
  function syncAll(decl, cfg, deps, local) {
    var report = [], all = [];
    return seq(decl.sources.map(function (s) { return effective(s, cfg); }).filter(function (e) { return !runnable(e); }), function (e) {
      return (e.method === 'apify' ? runApify(e, deps) : runDirect(e, deps)).then(function (pls) {
        pls.forEach(function (p) { if (e.area === 'music' && local) matchPlaylist(p, local); }); all = all.concat(pls); report.push({ id: e.id, ok: true, count: pls.length });
      }, function (err) { report.push({ id: e.id, ok: false, error: String(err && err.message || err) }); });
    }).then(function () { return { playlists: all, report: report }; });
  }
  function dueForSync(schedule, last, now) {
    var gap = { daily: 864e5, weekly: 6048e5 }[schedule]; if (!gap) return false;
    return !last || now - last >= gap;
  }

  /* ── the library rows (dense Store rows) ─────────────────────────────── */
  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
  function rowHtml(p, open) {
    var sub = [p.source, p.owner, p.count + (p.kind === 'board' ? ' pins' : ' items')].filter(Boolean);
    if (p.area === 'music' && p.matched != null) sub.push(p.matched + ' in collection');
    var items = open ? '<div class="srow__items">' + p.items.slice(0, 200).map(function (t) {
      return '<a class="srow__item" href="' + esc(t.url) + '" data-ext><span>' + esc(t.title) + '</span><small>' + esc(t.artist) + (t.match && t.match !== 'none' ? ' · ' + t.match : '') + '</small></a>';
    }).join('') + '</div>' : '';
    return '<div class="srow" data-pl="' + esc(p.id) + '"><button class="srow__main" type="button" data-open="' + esc(p.id) + '"><span class="srow__icon"' + (p.image ? ' style="background-image:url(\'' + esc(p.image) + '\')"' : '') + '></span>' +
      '<span class="srow__txt"><b>' + esc(p.title) + '</b><small>' + esc(sub.join(' · ')) + '</small></span></button>' +
      (p.url ? '<a class="srow__go" href="' + esc(p.url) + '" data-ext aria-label="Open">↗</a>' : '') + items + '</div>';
  }
  function renderLibrary(lib, st) {
    var q = (st.q || '').toLowerCase(), list = ((lib && lib.playlists) || []).filter(function (p) {
      return (!st.area || p.area === st.area) && (!q || (p.title + ' ' + p.owner + ' ' + p.source).toLowerCase().indexOf(q) >= 0);
    });
    return list.length ? list.map(function (p) { return rowHtml(p, st.open === p.id); }).join('') : '<p class="empty">' + (lib && lib.playlists && lib.playlists.length ? 'No match.' : 'Nothing synced yet. Turn a source on in Configs, then tap Sync now.') + '</p>';
  }

  /* ── browser half: the Library page ──────────────────────────────────── */
  function storage() { try { return root.localStorage || null; } catch (e) { return null; } }
  function readJson(k, d) { var s = storage(); try { return JSON.parse(s.getItem(k)) || d; } catch (e) { return d; } }
  function writeJson(k, v) { var s = storage(); try { s.setItem(k, JSON.stringify(v)); } catch (e) { /* full */ } }
  function loadLibrary() { return readJson(LIB_KEY, { updated: 0, playlists: [], report: [] }); }
  function loadSourceCfg() { return readJson(CFG_KEY, {}); }
  function saveSourceCfg(c) { writeJson(CFG_KEY, c); }
  function loadSync() { var s = readJson(SYNC_KEY, {}); return { schedule: ['off', 'daily', 'weekly'].indexOf(s.schedule) >= 0 ? s.schedule : 'off', last: Number(s.last) || 0 }; }
  function saveSync(s) { writeJson(SYNC_KEY, s); }
  function makeDeps(decl, secrets) {
    var s = storage(), choice = s && s.getItem('cmc_apify.backend');
    var backend = Object.assign({}, backendOf(decl, choice)); backend.api = apiBase(backend, s && s.getItem('cmc_apify.self.url'));
    return { fetch: function (u, o) { return root.fetch(u, o); }, secret: function (n) { return secrets.get(n); }, backend: backend, apifyToken: function () { return secrets.get(backend.token_key); } };
  }
  function syncNow(decl, secrets, music) {
    var deps = makeDeps(decl, secrets);
    return syncAll(decl, loadSourceCfg(), deps, localTracks(music)).then(function (r) {
      var lib = { updated: Date.now(), playlists: r.playlists, report: r.report }; writeJson(LIB_KEY, lib); var sy = loadSync(); sy.last = lib.updated; saveSync(sy); return lib;
    });
  }
  function bootLibrary(doc) {
    var main = doc.querySelector('main'), chips = doc.querySelector('.chips'), search = doc.querySelector('.search'), sub = doc.querySelector('.top h1 small'), bar = doc.querySelector('.syncbar');
    var st = { q: '', area: '', open: '' }, lib = loadLibrary(), decl = null, music = null, secrets = SecretStore(storage());
    function status(extra) {
      var sy = loadSync(), n = lib.playlists.length;
      sub.textContent = n + (n === 1 ? ' synced playlist' : ' synced playlists');
      bar.querySelector('.syncbar__msg').textContent = (extra || (lib.updated ? 'Synced ' + new Date(lib.updated).toLocaleString() : 'Not synced yet')) + ' · schedule: ' + sy.schedule;
    }
    function draw() { main.innerHTML = renderLibrary(lib, st); }
    function run() {
      if (!decl) return; var btn = bar.querySelector('[data-sync]'); btn.disabled = true; status('Syncing…');
      syncNow(decl, secrets, music).then(function (l) { lib = l; var bad = l.report.filter(function (r) { return !r.ok; }); draw(); status(bad.length ? bad.map(function (r) { return r.id + ': ' + r.error; }).join(' · ') : null); }, function (e) { status('Sync failed: ' + e); })
        .then(function () { btn.disabled = false; });
    }
    bar.querySelector('[data-sync]').addEventListener('click', run);
    search.addEventListener('input', function () { st.q = search.value.trim(); draw(); });
    chips.addEventListener('click', function (e) { var b = e.target.closest('.chip'); if (!b) return; [].forEach.call(chips.children, function (c) { c.setAttribute('aria-pressed', String(c === b)); }); st.area = b.getAttribute('data-chip'); draw(); });
    main.addEventListener('click', function (e) { var o = e.target.closest('[data-open]'); if (o) { st.open = st.open === o.getAttribute('data-open') ? '' : o.getAttribute('data-open'); draw(); } });
    draw(); status();
    var get = function (p) { return root.fetch('../data/' + p + '.json').then(function (r) { return r.json(); }); };
    Promise.all([get('media-sources'), get('music').catch(function () { return null; })]).then(function (r) {
      decl = parseSources(r[0]); music = r[1];
      chips.innerHTML = '<button class="chip" aria-pressed="true" data-chip="">All</button>' + decl.areas.map(function (a) { return '<button class="chip" aria-pressed="false" data-chip="' + esc(a.id) + '">' + esc(a.icon + ' ' + a.label) + '</button>'; }).join('');
      if (dueForSync(loadSync().schedule, loadSync().last, Date.now())) run();
    }).catch(function (e) { main.innerHTML = '<p class="empty">Could not load the sources: ' + esc(e) + '</p>'; });
  }
  function boot() { var doc = root.document; if (doc && doc.body.getAttribute('data-page') === 'library') bootLibrary(doc); }

  var api = { parseSources: parseSources, effective: effective, runnable: runnable, backendOf: backendOf, apiBase: apiBase, validToken: validToken, masked: masked, actorUrl: actorUrl, SecretStore: SecretStore, Match: Match, matchPlaylist: matchPlaylist,
    localTracks: localTracks, mapSpotifyPlaylists: mapSpotifyPlaylists, mapSpotifyTracks: mapSpotifyTracks, mapYouTubePlaylists: mapYouTubePlaylists, mapYouTubeItems: mapYouTubeItems, mapDeezerPlaylists: mapDeezerPlaylists,
    mapDeezerTracks: mapDeezerTracks, mapPinterest: mapPinterest, mapApifyTracks: mapApifyTracks, mapApify: mapApify, fillInput: fillInput, syncAll: syncAll, dueForSync: dueForSync, renderLibrary: renderLibrary,
    loadSourceCfg: loadSourceCfg, saveSourceCfg: saveSourceCfg, loadSync: loadSync, saveSync: saveSync, loadLibrary: loadLibrary, boot: boot, METHODS: METHODS };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else { root.MediaSync = api; if (root.document) root.document.addEventListener('DOMContentLoaded', boot); }
})(typeof window !== 'undefined' ? window : this);
