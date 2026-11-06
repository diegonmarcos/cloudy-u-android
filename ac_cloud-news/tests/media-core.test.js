// The Media feed logic (node, no deps): node tests/media-core.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const M = require('../app/src/main/assets/media-core.js');
const fx = (n) => fs.readFileSync(path.join(__dirname, 'fixtures', n), 'utf8');
const real = JSON.parse(fs.readFileSync(path.join(__dirname, '../data/media.json'), 'utf8'));
const NOW = Date.parse('2026-11-03T08:30:00Z');
let n = 0;
const queue = [];
function t(name, fn) { queue.push([name, fn]); }

const CH = { id: '3blue1brown', label: '3Blue1Brown', channelId: 'UCYO_jab_esuFRV4b17AJtAw', playlistId: '', handle: '', category: 'Math', avatar: '' };
const quiet = { cacheGet: () => null, cacheSet: () => {} };

t('the shipped declaration parses with no error and every channel has a feed url', () => {
    const d = M.parseDeclared(real);
    assert.deepStrictEqual(d.errors, []);
    assert.strictEqual(d.channels.length, 8);
    d.channels.forEach((c) => assert.match(M.feedUrl(c), /^https:\/\/www\.youtube\.com\/feeds\/videos\.xml\?channel_id=UC[\w-]{22}$/, c.id));
    assert.deepStrictEqual(d.chips, ['all', 'shorts', 'live', 'category', 'channel']);
    assert.ok(d.feed.pageSize > 0 && d.feed.cacheHours > 0);
    assert.strictEqual(real.feed.auto_refresh.require_unmetered_network, true);
});
t('a playlist feed url and malformed declarations', () => {
    assert.strictEqual(M.feedUrl({ playlistId: 'PLZHQObOWTQDMsr9K-rj53DwVRMYO3t5Yr' }), 'https://www.youtube.com/feeds/videos.xml?playlist_id=PLZHQObOWTQDMsr9K-rj53DwVRMYO3t5Yr');
    assert.strictEqual(M.feedUrl({ channelId: 'nope' }), '');
    const d = M.parseDeclared({ channels: [{ id: 'a', channel_id: 'short' }, { id: 'b' }, { id: 'c', handle: '@ok_name' }, { id: 'c', handle: '@dup' }] });
    assert.strictEqual(d.channels.length, 1);
    assert.strictEqual(d.errors.length, 3);
});

t('the RSS fixture (yt namespace) parses: ids, titles, entities, links, dates, views, thumbnails, shorts', () => {
    const v = M.parseFeed(fx('videos.xml'), CH);
    assert.strictEqual(v.length, 4);
    const by = Object.fromEntries(v.map((x) => [x.id, x]));
    assert.strictEqual(by.aaaaaaaaaa1.title, 'Older video & its title', 'entities decoded, media:title not read as the title');
    assert.strictEqual(by.bbbbbbbbbb2.views, 2500000);
    assert.strictEqual(by.dddddddddd4.views, 1);
    assert.strictEqual(by.bbbbbbbbbb2.url, 'https://www.youtube.com/watch?v=bbbbbbbbbb2');
    assert.strictEqual(by.bbbbbbbbbb2.published, Date.parse('2026-11-02T08:30:00+00:00'), 'published wins over updated');
    assert.strictEqual(by.bbbbbbbbbb2.thumb, 'https://i2.ytimg.com/vi/bbbbbbbbbb2/hqdefault.jpg');
    assert.strictEqual(by.cccccccccc3.isShort, true);
    assert.strictEqual(by.bbbbbbbbbb2.isShort, false);
    assert.strictEqual(M.parseFeed(fx('videos.xml'), null)[0].channelName, '3Blue1Brown', 'the feed author names the channel when none is declared');
});
t('the parser tolerates other prefixes, CDATA, single quotes and garbage', () => {
    const x = '<feed><entry><x:videoId>zzzzzzzzzz9</x:videoId><title><![CDATA[Hi & bye]]></title><link href=\'https://www.youtube.com/watch?v=zzzzzzzzzz9\'/><published>2026-01-01T00:00:00Z</published></entry><entry><title>no id</title></entry></feed>';
    const v = M.parseFeed(x, null);
    assert.strictEqual(v.length, 1);
    assert.strictEqual(v[0].title, 'Hi & bye');
    assert.deepStrictEqual(M.parseFeed('<html>consent</html>', CH), []);
    assert.deepStrictEqual(M.parseFeed('', CH), []);
    assert.deepStrictEqual(M.parseFeed(null, CH), []);
});

t('handle resolution reads the channel page once and remembers it', async () => {
    const cache = {}; let fetches = 0;
    const io = { fetchText: async (u) => { fetches++; assert.strictEqual(u, 'https://www.youtube.com/@3blue1brown'); return fx('handle-page.html'); }, cacheGet: (k) => cache[k] || null, cacheSet: (k, v) => { cache[k] = v; } };
    const r = await M.resolveChannel({ id: 'x', handle: '3blue1brown' }, io);
    assert.strictEqual(r.channelId, 'UCYO_jab_esuFRV4b17AJtAw', 'the canonical link, not the related channel in the page data');
    const r2 = await M.resolveChannel({ id: 'x', handle: '@3blue1brown' }, io);
    assert.strictEqual(r2.channelId, 'UCYO_jab_esuFRV4b17AJtAw');
    assert.strictEqual(fetches, 1, 'the second resolution is served from the remembered id');
    const declared = await M.resolveChannel(CH, { fetchText: async () => { throw new Error('must not fetch'); } });
    assert.strictEqual(declared, CH, 'a declared channel_id needs no fetch');
    await assert.rejects(M.resolveChannel({ id: 'y', handle: '@nobody' }, { fetchText: async () => '<html></html>' }), /no channel id/);
    await assert.rejects(M.resolveChannel({ id: 'z', handle: '!!' }, io), /no channel_id/);
    assert.strictEqual(M.handleUrl('a b'), '');
    assert.strictEqual(M.channelIdFromHtml('"externalId":"UCcabW7890RKJzL968QWEykA"'), 'UCcabW7890RKJzL968QWEykA');
});
t('a handle-only channel loads end to end', async () => {
    const urls = [];
    const io = Object.assign({ fetchText: async (u) => { urls.push(u); return u.includes('/@') ? fx('handle-page.html') : fx('videos.xml'); } }, quiet);
    const res = await M.loadFeed([{ id: 'h', label: 'H', channelId: '', playlistId: '', handle: '@3blue1brown', category: '', avatar: '' }], io);
    assert.deepStrictEqual(urls, ['https://www.youtube.com/@3blue1brown', 'https://www.youtube.com/feeds/videos.xml?channel_id=UCYO_jab_esuFRV4b17AJtAw']);
    assert.strictEqual(res.videos.length, 4);
});

t('feed ordering: newest first, de-duplicated across channels, stable on ties', () => {
    const a = M.parseFeed(fx('videos.xml'), CH), b = M.parseFeed(fx('videos.xml'), Object.assign({}, CH, { id: 'other' }));
    const m = M.merge(a.concat(b));
    assert.deepStrictEqual(m.map((x) => x.id), ['bbbbbbbbbb2', 'cccccccccc3', 'dddddddddd4', 'aaaaaaaaaa1']);
    const tie = M.merge([{ id: 'bbbbbbbbbb2', published: 5 }, { id: 'aaaaaaaaaa1', published: 5 }]);
    assert.deepStrictEqual(tie.map((x) => x.id), ['aaaaaaaaaa1', 'bbbbbbbbbb2']);
});

t('the declared sources fill the feed from the fixture (an empty feed would fail here)', async () => {
    const d = M.parseDeclared(real);
    const seen = [];
    const res = await M.loadFeed(d.channels, Object.assign({ fetchText: async (u) => { seen.push(u); return fx('videos.xml'); } }, quiet));
    assert.strictEqual(seen.length, 8);
    assert.ok(res.videos.length > 0, 'valid sources, empty feed');
    assert.deepStrictEqual(res.errors, []);
    assert.deepStrictEqual(M.checkFeed(d.channels, res), { ok: true, reason: '' });
});
t('checkFeed turns an empty feed with valid sources into a failure with the reason', async () => {
    const d = M.parseDeclared(real);
    const empty = await M.loadFeed(d.channels, Object.assign({ fetchText: async () => '<html>consent</html>' }, quiet));
    const c = M.checkFeed(d.channels, empty);
    assert.strictEqual(c.ok, false);
    assert.match(c.reason, /8 source\(s\) declared, 0 videos/);
    const down = await M.loadFeed(d.channels, Object.assign({ fetchText: async () => { throw new Error('HTTP 404'); } }, quiet));
    assert.match(M.checkFeed(d.channels, down).reason, /HTTP 404/);
    assert.strictEqual(M.checkFeed([], { videos: [], errors: [] }).ok, true, 'no source, nothing to fail');
});
t('one failing channel does not empty the others', async () => {
    const d = M.parseDeclared(real);
    const res = await M.loadFeed(d.channels, Object.assign({ fetchText: async (u) => { if (u.includes(d.channels[0].channelId)) throw new Error('boom'); return fx('videos.xml'); } }, quiet));
    assert.strictEqual(res.errors.length, 1);
    assert.strictEqual(res.errors[0].id, d.channels[0].id);
    assert.ok(res.videos.length > 0);
    assert.strictEqual(M.checkFeed(d.channels, res).ok, true);
});

t('the Data API enriches duration, views and live, and marks short videos', () => {
    const map = M.parseApi(fx('videos-api.json'));
    assert.strictEqual(map.bbbbbbbbbb2.durationSec, 3723);
    assert.strictEqual(map.dddddddddd4.isLive, true);
    const e = M.enrich(M.parseFeed(fx('videos.xml'), CH), map);
    const by = Object.fromEntries(e.map((x) => [x.id, x]));
    assert.strictEqual(by.bbbbbbbbbb2.views, 2600000);
    assert.strictEqual(by.cccccccccc3.isShort, true);
    assert.strictEqual(by.dddddddddd4.isLive, true);
    assert.strictEqual(by.aaaaaaaaaa1.durationSec, null, 'unknown stays unknown');
    assert.strictEqual(M.apiUrl(['bbbbbbbbbb2', 'bad']), 'https://www.googleapis.com/youtube/v3/videos?part=contentDetails,statistics,snippet&id=bbbbbbbbbb2&key={key}');
    assert.strictEqual(M.apiUrl([]), '');
    assert.deepStrictEqual(M.parseApi('not json'), {});
});

t('chips: All, Shorts and Live only where known, categories, then channels; the filters', () => {
    const d = M.parseDeclared(real);
    const vs = M.parseFeed(fx('videos.xml'), Object.assign({}, CH, { id: 'cs50' }));
    const plain = M.buildChips(d.chips, d.channels, vs);
    assert.deepStrictEqual(plain.slice(0, 2).map((c) => c.id), ['all', 'shorts']);
    assert.ok(!plain.some((c) => c.id === 'live'), 'no live video known, no Live chip');
    assert.ok(plain.some((c) => c.id === 'cat:Music') && plain.some((c) => c.id === 'ch:cs50'));
    assert.ok(M.buildChips(d.chips, d.channels, M.enrich(vs, M.parseApi(fx('videos-api.json')))).some((c) => c.id === 'live'));
    const count = (id) => M.filterVideos(vs, plain.find((c) => c.id === id), d.channels).length;
    assert.strictEqual(count('shorts'), 1);
    assert.strictEqual(count('ch:cs50'), 4);
    assert.strictEqual(count('ch:npr-music'), 0);
    assert.strictEqual(count('cat:Coding'), 4);
    assert.strictEqual(count('cat:Music'), 0);
    assert.strictEqual(count('all'), 4);
});

t('the mapping into a feed card: thumbnails, duration, title, avatar, channel, views, relative time', () => {
    const d = M.parseDeclared(real);
    const ch = d.channels.find((c) => c.id === '3blue1brown');
    const v = M.enrich(M.parseFeed(fx('videos.xml'), ch), M.parseApi(fx('videos-api.json'))).find((x) => x.id === 'bbbbbbbbbb2');
    assert.deepStrictEqual(M.toCard(v, d.channels, NOW), { id: 'bbbbbbbbbb2', title: 'Newest video', url: 'https://www.youtube.com/watch?v=bbbbbbbbbb2',
        thumbMax: 'https://i.ytimg.com/vi/bbbbbbbbbb2/maxresdefault.jpg', thumbHq: 'https://i2.ytimg.com/vi/bbbbbbbbbb2/hqdefault.jpg',
        duration: '1:02:03', live: false, short: false, views: '2.6M views', age: '1 day ago', channel: '3Blue1Brown', initial: '3', avatar: '',
        embed: 'https://www.youtube-nocookie.com/embed/bbbbbbbbbb2?autoplay=1&playsinline=1' });
    const noInfo = M.toCard(M.parseFeed(fx('videos.xml'), ch).find((x) => x.id === 'aaaaaaaaaa1'), d.channels, NOW);
    assert.strictEqual(noInfo.duration, '', 'no duration known, no badge');
    const live = M.toCard({ id: 'dddddddddd4', title: 'x', isLive: true, durationSec: 5, published: 0 }, d.channels, NOW);
    assert.strictEqual(live.live, true);
    assert.strictEqual(live.duration, '', 'a live card shows LIVE, not a duration');
});
t('formatters', () => {
    assert.strictEqual(M.formatDuration(59), '0:59'); assert.strictEqual(M.formatDuration(61), '1:01'); assert.strictEqual(M.formatDuration(3600), '1:00:00'); assert.strictEqual(M.formatDuration(null), '');
    assert.strictEqual(M.formatViews(0), '0 views'); assert.strictEqual(M.formatViews(1), '1 view'); assert.strictEqual(M.formatViews(999), '999 views');
    assert.strictEqual(M.formatViews(1234), '1.2K views'); assert.strictEqual(M.formatViews(15000), '15K views'); assert.strictEqual(M.formatViews(2500000), '2.5M views');
    assert.strictEqual(M.formatViews(3200000000), '3.2B views'); assert.strictEqual(M.formatViews(null), '');
    const day = 86400000;
    assert.strictEqual(M.relTime(NOW - 30000, NOW), 'just now'); assert.strictEqual(M.relTime(NOW - 5 * 60000, NOW), '5 minutes ago');
    assert.strictEqual(M.relTime(NOW - 3600000, NOW), '1 hour ago'); assert.strictEqual(M.relTime(NOW - 3 * day, NOW), '3 days ago');
    assert.strictEqual(M.relTime(NOW - 14 * day, NOW), '2 weeks ago'); assert.strictEqual(M.relTime(NOW - 90 * day, NOW), '3 months ago');
    assert.strictEqual(M.relTime(NOW - 800 * day, NOW), '2 years ago'); assert.strictEqual(M.relTime(0, NOW), '');
});

t('the embed URL builder: the official nocookie embed, validated id, optional resume', () => {
    assert.strictEqual(M.embedUrl('bbbbbbbbbb2'), 'https://www.youtube-nocookie.com/embed/bbbbbbbbbb2?autoplay=1&playsinline=1');
    assert.strictEqual(M.embedUrl('bbbbbbbbbb2', 95.7), 'https://www.youtube-nocookie.com/embed/bbbbbbbbbb2?autoplay=1&playsinline=1&start=95');
    assert.strictEqual(M.embedUrl('bbbbbbbbbb2', 0), 'https://www.youtube-nocookie.com/embed/bbbbbbbbbb2?autoplay=1&playsinline=1');
    ['', 'short', 'bbbbbbbbbb2/../x', 'bbbbbbbbbb2"onload=', null, undefined].forEach((bad) => assert.strictEqual(M.embedUrl(bad), '', String(bad)));
});

(async () => {
    for (const [name, fn] of queue) {
        try { await fn(); n++; } catch (e) { console.error('FAIL ' + name + '\n' + (e && e.stack || e)); process.exit(1); }
    }
    console.log(n + ' media-core tests passed');
})();
