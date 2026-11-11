#!/bin/sh
# test-media-center-sync.sh — playlist sync (#1469): the source declaration parser, the Spotify / YouTube / Deezer /
# Pinterest / Apify mappers (fixtures in test/fixtures), the ISRC + fuzzy matcher, the Apify backend routing, the
# encrypted token store, the Configs tab order, the Library rows. Static, no network, no browser; node only.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }
node - "$APP" <<'JS'
const fs = require('fs'), path = require('path');
const app = process.argv[2], web = path.join(app, 'web'), fx = (n) => JSON.parse(fs.readFileSync(path.join(app, 'test/fixtures', n), 'utf8'));
const S = require(path.join(web, 'assets/media-sync.js'));
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };
const raw = fs.readFileSync(path.join(web, 'data/media-sources.json'), 'utf8');

(async () => {
  /* 1. the declaration */
  const d = S.parseSources(raw);
  ok(d.errors.length === 0 && d.sources.length >= 6, 'sources: the shipped declaration parses clean (' + d.sources.length + ' sources)');
  ok(d.default_backend === 'cloud' && d.backends.selfhosted && d.backends.cloud.token_key === 'apify.token' && d.backends.selfhosted.token_key === 'apify.self.bearer' && d.backends.cloudflare.token_key === 'cloudflare.worker_token' && d.backends.cloudflare.rest_token_key === 'cloudflare.api_token' && d.backends.cloudflare.account_key === 'cloudflare.account_id', 'sources: Apify Cloud is the default, Crawlee and Cloudflare Browser Run opt-in, one token key each (Cloudflare also an account id)');
  ok(['music', 'video', 'images'].every((a) => d.sources.some((s) => s.area === a)), 'sources: Music, Video and Images areas are all covered');
  ok(['spotify', 'youtube', 'pinterest', 'soundcloud', 'deezer', 'apple-music'].every((i) => d.sources.some((s) => s.id === i)), 'sources: Spotify, YouTube, Pinterest, SoundCloud, Deezer, Apple Music');
  ok(d.sources.every((s) => s.methods.includes(s.method) && (s.methods.includes('apify') ? /\//.test(s.actor) : true)), 'sources: each declares method apify|direct and an actor id');
  ok(d.sources.find((s) => s.id === 'spotify').method === 'direct' && d.sources.find((s) => s.id === 'youtube').method === 'direct' && d.sources.find((s) => s.id === 'pinterest').method === 'apify', 'sources: direct for Spotify and YouTube, Apify for Pinterest');
  const bad = S.parseSources(fx('media-sources-bad.json'));
  ok(bad.sources.length === 1 && bad.sources[0].id === 'ok' && bad.errors.length === 6, 'sources: duplicate id, missing actor, unknown area, unknown api, bad kind and bad method are refused (' + bad.errors.length + ' errors)');
  ok(S.parseSources('{nope').error === 'not JSON' && S.parseSources('[]').sources.length === 0, 'sources: junk input is refused, not thrown');
  const nodef = JSON.parse(raw); nodef.apify.default_backend = 'selfhosted';
  ok(S.parseSources(nodef).errors.some((e) => /opt-in/.test(e)), 'sources: Crawlee cannot be the declared default');
  const sp = d.sources.find((s) => s.id === 'spotify');
  ok(S.effective(sp, { spotify: { method: 'apify', actor: 'me/own', ref: ' https://x ', enabled: true } }).actor === 'me/own' && S.effective(sp, { spotify: { method: 'soap' } }).method === 'direct', 'sources: the owner may switch method and actor, junk falls back to the declaration');
  ok(S.runnable(S.effective(sp, {})) === 'off' && S.runnable(S.effective(sp, { spotify: { enabled: true } })) === '' && /URL/.test(S.runnable(S.effective(sp, { spotify: { enabled: true, method: 'apify' } }))), 'sources: off / ready / needs a URL');

  /* 2. mappers */
  const spl = S.mapSpotifyPlaylists(fx('spotify-playlists.json'))[0];
  ok(spl.id === 'spotify:37i9dQ' && spl.title === 'Road Mix' && spl.count === 3 && spl.owner === 'diego' && spl.area === 'music' && spl.image.endsWith('/a'), 'spotify: a playlist maps to a library row');
  const st = S.mapSpotifyTracks(fx('spotify-tracks.json'));
  ok(st.length === 2 && st[0].isrc === 'USSM19200456' && st[0].durationMs === 220000 && st[1].isrc === '', 'spotify: tracks keep the cleaned ISRC; local files and nulls are skipped');
  const ypl = S.mapYouTubePlaylists(fx('youtube-playlists.json'))[0];
  ok(ypl.url === 'https://www.youtube.com/playlist?list=PLabc' && ypl.count === 2 && ypl.area === 'video', 'youtube: a playlist maps to a library row');
  const yi = S.mapYouTubeItems(fx('youtube-items.json'), { aaaaaaaaaaa: 3723000 });
  ok(yi.length === 2 && yi[0].artist === 'BBC Earth' && yi[0].durationMs === 3723000 && yi[1].url === 'https://www.youtube.com/watch?v=ccccccccccc', 'youtube: private videos dropped, "- Topic" stripped, durations joined');
  ok(S.mapDeezerPlaylists(fx('deezer-playlists.json'))[0].id === 'deezer:42' && S.mapDeezerTracks(fx('deezer-tracks.json'))[0].isrc === 'USEP19800001', 'deezer: playlists and tracks');
  const pin = S.mapPinterest(fx('pinterest-pins.json'));
  ok(pin.length === 3 && pin[0].title === 'Architecture' && pin[0].items.length === 2 && pin[0].count === 2 && pin[0].owner === 'diego' && pin[0].image.endsWith('/a.jpg') && pin[0].kind === 'board' && pin[0].area === 'images', 'pinterest: pins group into their board');
  ok(pin[1].title === 'Food' && pin[1].items[0].image.endsWith('/c.jpg') && pin[2].title === 'Empty board' && pin[2].count === 12 && pin[2].items.length === 0, 'pinterest: alternative field names, and a board item keeps its pin count');
  ok(S.mapPinterest(null).length === 0 && S.mapPinterest([null, 3, {}]).length === 0, 'pinterest: junk gives no boards');
  const am = S.mapApifyTracks('soundcloud', 'music', fx('apify-music.json'));
  ok(am.length === 2 && am[0].title === 'Set 1' && am[0].items.length === 2 && am[0].items[0].durationMs === 200000 && am[1].items.length === 1, 'apify: track items group by playlist; playlist items with tracks; empty titles dropped');

  /* 3. matcher (ported from Browser Music, #1460) */
  const M = S.Match;
  ok(M.isrc('us-sm1-92-00456') === 'USSM19200456' && M.isrc('nope') === '', 'match: ISRC is cleaned and validated');
  const local = [{ title: 'Know Your Enemy', artist: 'Rage Against The Machine' }, { title: 'Pretty Fly (For a White Guy)', artist: 'The Offspring', isrc: 'USEP19800001' }];
  ok(M.best({ title: 'x', artist: 'y', isrc: 'USEP19800001' }, local).how === 'isrc', 'match: ISRC first');
  ok(M.best({ title: 'Know Your Enemy - Remastered 2011', artist: 'Rage Against The Machine', durationMs: 1 }, local).how === 'fuzzy', 'match: fuzzy ignores remaster suffixes');
  ok(M.best({ title: 'Totally Other', artist: 'Nobody' }, local).how === 'none' && M.best({ title: 'Know Your Enemy', artist: 'Zzz Qqq' }, local).how === 'none', 'match: unrelated or wrong artist is no match');
  const mp = S.matchPlaylist({ items: st.map((t) => Object.assign({}, t)) }, S.localTracks({ sections: [{ folders: [{ playlists: [{ items: [['Know Your Enemy', 'Rage Against The Machine']] }] }] }] }));
  ok(mp.matched === 1 && mp.items[0].match === 'fuzzy' && mp.items[1].match === 'none', 'match: a synced playlist is marked against the collection');

  /* 4. the runner: routing, backend, errors */
  const calls = [];
  const mk = (map) => (u, o) => { calls.push({ u, o }); const k = Object.keys(map).find((x) => u.includes(x)); return k ? Promise.resolve({ ok: true, json: () => Promise.resolve(map[k]) }) : Promise.resolve({ ok: false, status: 404, json: () => Promise.resolve({}) }); };
  const tokens = { 'apify.token': 'cloud-token-1', 'apify.self.bearer': 'crawl-token-1', 'spotify.token': 'sp-token-1', 'cloudflare.api_token': 'cf-token-1', 'cloudflare.worker_token': 'wk-token-1', 'cloudflare.account_id': '0123456789abcdef0123456789abcdef' };
  const deps = (backend, map) => { const b = Object.assign({}, S.backendOf(d, backend)); b.api = S.apiBase(b, ''); return { fetch: mk(map), secret: (n) => Promise.resolve(tokens[n] || ''), backend: b, apifyToken: () => Promise.resolve(tokens[b.token_key]) }; };
  const cfg = { spotify: { enabled: true }, pinterest: { enabled: true, ref: 'https://www.pinterest.com/diego/' }, youtube: { enabled: true } };
  let r = await S.syncAll(d, cfg, deps('cloud', { 'api.spotify.com/v1/me/playlists': fx('spotify-playlists.json'), '/tracks': fx('spotify-tracks.json'), 'run-sync-get-dataset-items': fx('pinterest-pins.json') }), []);
  const sp1 = calls.find((c) => c.u.includes('api.spotify.com/v1/me/playlists'));
  ok(sp1 && sp1.o.headers.Authorization === 'Bearer sp-token-1', 'run: Spotify direct uses the official API with the encrypted token');
  const ap = calls.find((c) => c.u.includes('run-sync-get-dataset-items'));
  ok(ap.u.startsWith('https://api.apify.com/v2/acts/epctex~pinterest-scraper/') && ap.o.headers.Authorization === 'Bearer cloud-token-1' && JSON.parse(ap.o.body).startUrls[0].url === 'https://www.pinterest.com/diego/', 'run: Pinterest goes to Apify Cloud (the default) with {ref} filled in');
  ok(r.playlists.length === 4 && r.report.find((x) => x.id === 'spotify').ok && r.report.find((x) => x.id === 'pinterest').count === 3, 'run: playlists and boards are collected');
  const yr = r.report.find((x) => x.id === 'youtube');
  ok(yr && !yr.ok && /no .*token/.test(yr.error) && r.playlists.some((p) => p.source === 'pinterest'), 'run: one source failing (no token) does not stop the others');
  calls.length = 0;
  r = await S.syncAll(d, { pinterest: cfg.pinterest }, deps('selfhosted', { 'run-sync-get-dataset-items': fx('pinterest-pins.json') }), []);
  const cr = calls[0];
  ok(cr.u.startsWith('https://crawlee-cloud.mesh.diegonmarcos.com/v2/acts/') && cr.o.headers.Authorization === 'Bearer crawl-token-1', 'run: choosing Cloudy Crawlee routes the same actor to its runner with its own token');
  calls.length = 0;
  r = await S.syncAll(d, { pinterest: cfg.pinterest }, deps('cloudflare', { 'browser-rendering/markdown': { success: true, result: '# Pins' } }), []);
  const cfRest = (map) => { const dd = deps('cloudflare', map); dd.backend.mode = 'rest'; dd.backend.token_key = dd.backend.rest_token_key; dd.apifyToken = () => Promise.resolve(tokens['cloudflare.api_token']); return dd; };
  calls.length = 0;
  r = await S.syncAll(d, { pinterest: cfg.pinterest }, cfRest({ 'browser-rendering/markdown': { success: true, result: '# Pins' } }), []);
  const cf = calls[0];
  ok(cf.u === 'https://api.cloudflare.com/client/v4/accounts/0123456789abcdef0123456789abcdef/browser-rendering/markdown' && cf.o.headers.Authorization === 'Bearer cf-token-1' && JSON.parse(cf.o.body).url === 'https://www.pinterest.com/diego/', 'run: Cloudflare Browser Run (REST API mode) renders the ref page with its account id and API token');
  calls.length = 0;
  const dw = deps('cloudflare', {}); dw.backend.mode = 'worker'; dw.backend.worker_url = 'https://edge-md-scraper.example.workers.dev/'; dw.apifyToken = () => Promise.resolve(tokens['cloudflare.worker_token']);
  dw.fetch = (u, o) => { calls.push({ u, o }); return Promise.resolve({ ok: true, text: () => Promise.resolve('# Pins') }); };
  r = await S.syncAll(d, { pinterest: cfg.pinterest }, dw, []);
  ok(calls[0].u === 'https://edge-md-scraper.example.workers.dev/?url=' + encodeURIComponent('https://www.pinterest.com/diego/') && calls[0].o.headers.Authorization === 'Bearer wk-token-1', 'run: Cloudflare Browser Run (Worker URL mode, the default) GETs the owner Worker with its bearer');
  ok(r.report.find((x) => x.id === 'pinterest'), 'run: the Cloudflare run is reported like any other');
  r = await S.syncAll(d, { pinterest: { enabled: true } }, deps('cloud', {}), []);
  ok(r.report.length === 0, 'run: a source without its URL is not run');
  r = await S.syncAll(d, { youtube: { enabled: true } }, Object.assign(deps('cloud', { '/playlists?': fx('youtube-playlists.json'), '/playlistItems': fx('youtube-items.json'), '/videos?': { items: [{ id: 'aaaaaaaaaaa', contentDetails: { duration: 'PT1H2M3S' } }] } }), { secret: () => Promise.resolve('yt-token-1') }), []);
  ok(r.playlists.length === 1 && r.playlists[0].items.length === 2 && r.playlists[0].items[0].durationMs === 3723000, 'run: YouTube direct reads playlists, items and durations');
  ok(S.apiBase({ default_url: 'https://x.example' }, 'http://bad') === 'https://x.example/v2' && S.apiBase({ default_url: 'https://x.example' }, 'https://own.example/v2/') === 'https://own.example/v2' && S.apiBase(d.backends.cloud, 'https://z') === 'https://api.apify.com/v2', 'run: the shared store URL rule (https only, /v2, Cloud fixed)');
  ok(S.dueForSync('off', 0, 1e12) === false && S.dueForSync('daily', 0, 1e12) && !S.dueForSync('daily', 1e12 - 1000, 1e12) && S.dueForSync('weekly', 1e12 - 7 * 864e5, 1e12), 'schedule: off never, daily and weekly by age');

  /* 5. tokens are encrypted */
  const mem = (() => { const m = {}; return { getItem: (k) => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, removeItem: (k) => { delete m[k]; }, m }; })();
  const key = await require('crypto').webcrypto.subtle.generateKey({ name: 'AES-GCM', length: 256 }, false, ['encrypt', 'decrypt']);
  const ss = S.SecretStore(mem, () => Promise.resolve(key));
  await ss.put('apify.token', 'apify_api_SECRET123');
  ok(!JSON.stringify(mem.m).includes('SECRET123') && ss.has('apify.token'), 'secrets: only ciphertext reaches storage');
  ok(await ss.get('apify.token') === 'apify_api_SECRET123' && await ss.get('nothing') === '', 'secrets: round trip, absent is empty');
  mem.m['cmc_secret_apify.token'] = mem.m['cmc_secret_apify.token'].replace(/.$/, 'A');
  ok(await ss.get('apify.token') === '', 'secrets: tampered ciphertext reads as empty');
  ok(S.validToken(' abc ') === '' && S.validToken('abcdefgh1') === 'abcdefgh1' && S.masked('abcdefgh1234') === 'abcd••••34', 'secrets: token shape and masking');

  /* 6. the Library rows */
  const lib = { playlists: r.playlists.concat(pin) };
  const html = S.renderLibrary(lib, {});
  ok((html.match(/class="srow"/g) || []).length === lib.playlists.length && html.includes('Architecture') && html.includes('data-ext'), 'library: one dense row per synced playlist, with an open link');
  ok(S.renderLibrary(lib, { area: 'images' }).includes('Architecture') && !S.renderLibrary(lib, { area: 'images' }).includes('Docs') && S.renderLibrary(lib, { q: 'zzzz' }).includes('No match'), 'library: area filter and search');
  ok(S.renderLibrary(lib, { open: 'pinterest:b1' }).includes('srow__item') && S.renderLibrary({ playlists: [] }, {}).includes('Nothing synced'), 'library: a row expands to its items; empty state');
  ok(S.renderLibrary({ playlists: [{ id: 'x', title: '<img onerror=1>', source: 's', owner: '', count: 0, kind: 'playlist', items: [], url: '' }] }, {}).indexOf('<img') < 0, 'library: titles are escaped');

  /* 7. no DRM, no media fetch: the module names no download path */
  const src = fs.readFileSync(path.join(web, 'assets/media-sync.js'), 'utf8');
  ok(!/widevine|decrypt-media|\.m3u8|\.mpd|youtube-dl|yt-dlp|createWriteStream/i.test(src.replace(/AES-GCM[^\n]*decrypt/g, '')), 'legal: no DRM, stream or download code in the sync module');

  /* 8. Configs: order, subsections, Apify backend, token flow */
  const C = require(path.join(web, 'assets/configs.js'));
  const ids = C.TABS.map((t) => t.id);
  ok(JSON.stringify(ids) === '["music","videos","gallery","books","feed","movies","apify","general","agents","perms","logcat","xray","about"]'
    && JSON.stringify(C.TABS.filter((t) => t.sep).map((t) => t.id)) === '["agents"]'
    && C.TABS.filter((t) => t.id === 'xray')[0].label === 'X-Ray App',
    'configs: media areas, then Apify, then General, then | Agents, Perms, Logcat, X-Ray App, About (#1630; ' + C.TABS.map((t) => (t.sep ? '| ' : '') + t.id).join(', ') + ')');
  const ctx = { msrc: d, srcCfg: { spotify: { enabled: true } }, has: (n) => n === 'apify.token', backend: 'cloud', sync: { schedule: 'off' }, sub: 'apify' };
  const music = C.renderConfigs('music', C.normalizeTab('music', {}), null, [], ctx);
  ok(/data-sub="apify"/.test(music) && /data-sub="direct"/.test(music) && /data-subsec="apify"/.test(music) && /data-src="soundcloud"/.test(music) && /data-src="spotify"/.test(music), 'configs: Music has Apify | Direct subsections with its sources');
  const sec = (h, n) => h.split('data-subsec="' + n + '"')[1].split('</section>')[0];
  ok(sec(music, 'direct').includes('data-secret="spotify.token"') && !sec(music, 'apify').includes('data-src="spotify"') && sec(music, 'apify').includes('data-sf="actor"'), 'configs: Spotify (direct) sits under Direct with an encrypted token field; actor ids under Apify');
  ok(C.renderConfigs('videos', {}, null, [], ctx).includes('data-src="youtube"') && !C.renderConfigs('videos', {}, null, [], ctx).includes('data-src="spotify"'), 'configs: Video lists only YouTube');
  ok(C.renderConfigs('gallery', {}, null, [], ctx).includes('data-src="pinterest"'), 'configs: Images / Boards lists Pinterest');
  const ap2 = C.renderConfigs('apify', {}, null, [], ctx);
  ok(ap2.includes('Apify Cloud (default)') && ap2.includes('Self-hosted Cloudy Crawlee') && ap2.includes('data-secret="apify.token"') && ap2.includes('saved, encrypted') && C.renderConfigs('apify', {}, null, [], Object.assign({}, ctx, { backend: 'selfhosted' })).includes('data-secret="apify.self.bearer"'), 'configs/apify: backend choice (Cloud default | Crawlee) and the token of the chosen backend');
  const ap3 = C.renderConfigs('apify', {}, null, [], Object.assign({}, ctx, { backend: 'cloudflare' }));
  ok(ap3.includes('Cloudflare Browser Run') && ap3.includes('data-cf-worker') && ap3.includes('data-secret="cloudflare.worker_token"') && !ap3.includes('cloudflare.account_id'), 'configs/apify: Cloudflare Browser Run defaults to Worker URL mode (Worker URL + bearer)');
  const ap4 = C.renderConfigs('apify', {}, null, [], Object.assign({}, ctx, { backend: 'cloudflare', cfMode: 'rest' }));
  ok(ap4.includes('data-secret="cloudflare.account_id"') && ap4.includes('data-secret="cloudflare.api_token"') && !ap4.includes('data-cf-worker'), 'configs/apify: REST API mode asks for an account id and an API token');
  ok(C.renderConfigs('general', {}, null, [], ctx).includes('data-sched') && C.renderConfigs('perms', {}, null, [], ctx).includes('Internet') && C.renderConfigs('about', {}, null, [], ctx).includes('DRM'), 'configs: General (schedule), Perms and About render');
  ok(fs.existsSync(path.join(web, 'library/index.html')) && require(path.join(web, 'assets/pages.js')).tabsHtml('music', '../').includes('library/index.html'), 'library: page exists and is on the bottom bar');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('  FAIL ' + (e.stack || e)); process.exit(1); });
JS
echo "PASS: test-media-center-sync"
