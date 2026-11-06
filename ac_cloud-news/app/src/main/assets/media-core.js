/* Cloud News, Media tab: the logic of the YouTube feed (UMD: the page and the node tests share it). No DOM, no bridge.
   The DIRECT path: each channel's public Atom feed (`/feeds/videos.xml?channel_id=` or `playlist_id=`, no key), an @handle resolved once
   from the channel page, merged newest first. The optional YouTube Data API (a key in the private store) only ENRICHES (duration, views, live).
   parseDeclared(json)            -> { channels, chips, feed, errors }                     (data/media.json)
   feedUrl(ch) / handleUrl(h)     -> https url or ''
   parseFeed(xml, ch)             -> [video]   (yt:videoId, media:group/thumbnail/statistics, /shorts/ links)
   channelIdFromHtml(html)        -> 'UC...' or ''
   resolveChannel(ch, io)         -> Promise<ch with channelId>   io = { fetchText(url, opts), cacheGet(k), cacheSet(k, v) }
   loadFeed(channels, io)         -> Promise<{ videos, errors, fetched }>   (one bad channel never empties the rest)
   checkFeed(channels, res)       -> { ok, reason }   an EMPTY feed with valid sources is a failure, never "no videos yet"
   apiUrl(ids) / parseApi(json) / enrich(videos, map)
   merge, buildChips, filterVideos, toCard, embedUrl, formatDuration, formatViews, relTime */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.MediaCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';
    var FEED_BASE = 'https://www.youtube.com/feeds/videos.xml';
    var EMBED_BASE = 'https://www.youtube-nocookie.com/embed/';
    var API_BASE = 'https://www.googleapis.com/youtube/v3/videos';
    var ID_RE = /^[A-Za-z0-9_-]{11}$/, CHANNEL_RE = /^UC[0-9A-Za-z_-]{22}$/, PLAYLIST_RE = /^[A-Za-z0-9_-]{10,60}$/, HANDLE_RE = /^@?[A-Za-z0-9._-]{3,30}$/;
    var CHIP_KINDS = ['all', 'shorts', 'live', 'category', 'channel'];
    var POOL = 3;

    /* ---- declaration ------------------------------------------------------------------------------------ */
    function parseDeclared(json) {
        var errors = [], raw, out = { channels: [], chips: CHIP_KINDS.slice(), feed: { cacheHours: 6, minRefreshMinutes: 5, pageSize: 12 }, errors: errors };
        try { raw = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { errors.push('not JSON'); return out; }
        if (!raw || !Array.isArray(raw.channels)) { errors.push('no channels[]'); return out; }
        var f = raw.feed || {};
        out.feed = { cacheHours: +f.cache_hours > 0 ? +f.cache_hours : 6, minRefreshMinutes: +f.min_refresh_minutes >= 0 ? +f.min_refresh_minutes : 5, pageSize: +f.page_size > 0 ? Math.floor(+f.page_size) : 12 };
        if (Array.isArray(raw.chips) && raw.chips.length) out.chips = raw.chips.filter(function (k) { return CHIP_KINDS.indexOf(k) >= 0; });
        var seen = {};
        raw.channels.forEach(function (c, i) {
            var where = 'channels[' + i + ']' + (c && c.id ? ' ' + c.id : '');
            if (!c || typeof c.id !== 'string' || !c.id) return errors.push(where + ': no id');
            if (seen[c.id]) return errors.push(where + ': declared twice');
            var cid = String(c.channel_id || ''), pid = String(c.playlist_id || ''), h = String(c.handle || '');
            if (cid && !CHANNEL_RE.test(cid)) return errors.push(where + ': channel_id is not UC + 22 characters');
            if (pid && !PLAYLIST_RE.test(pid)) return errors.push(where + ': playlist_id is malformed');
            if (h && !HANDLE_RE.test(h)) return errors.push(where + ': handle is malformed');
            if (!cid && !pid && !h) return errors.push(where + ': needs channel_id, playlist_id or handle');
            seen[c.id] = 1;
            out.channels.push({ id: c.id, label: c.label || c.id, channelId: cid, playlistId: pid, handle: h, category: c.category || '',
                avatar: /^https:\/\//.test(c.avatar || '') ? c.avatar : '' });
        });
        return out;
    }

    /* ---- urls ------------------------------------------------------------------------------------------- */
    function feedUrl(ch) {
        if (ch && CHANNEL_RE.test(ch.channelId || '')) return FEED_BASE + '?channel_id=' + ch.channelId;
        if (ch && PLAYLIST_RE.test(ch.playlistId || '')) return FEED_BASE + '?playlist_id=' + ch.playlistId;
        return '';
    }
    function handleUrl(h) {
        h = String(h || '').trim();
        if (!HANDLE_RE.test(h)) return '';
        return 'https://www.youtube.com/' + (h.charAt(0) === '@' ? h : '@' + h);
    }
    /* The official embed only (no download, no ad handling). start > 0 resumes. */
    function embedUrl(id, startSec) {
        if (!ID_RE.test(id || '')) return '';
        var s = Math.floor(+startSec || 0);
        return EMBED_BASE + id + '?autoplay=1&playsinline=1' + (s > 0 ? '&start=' + s : '');
    }
    function apiUrl(ids) {
        var ok = (ids || []).filter(function (i) { return ID_RE.test(i); }).slice(0, 50);
        return ok.length ? API_BASE + '?part=contentDetails,statistics,snippet&id=' + ok.join(',') + '&key={key}' : '';
    }

    /* ---- feed parsing (namespace prefixes are matched by local name: yt:videoId, media:thumbnail, ...) ---- */
    function decode(s) {
        return String(s).replace(/&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos);/g, function (m, e) {
            if (e === 'amp') return '&'; if (e === 'lt') return '<'; if (e === 'gt') return '>'; if (e === 'quot') return '"'; if (e === 'apos') return "'";
            var n = e.charAt(1) === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10);
            try { return String.fromCodePoint(n); } catch (x) { return m; }
        });
    }
    function cdata(s) { return s.replace(/^\s*<!\[CDATA\[([\s\S]*?)\]\]>\s*$/, '$1'); }
    function tagText(block, name) {
        var m = new RegExp('<(?:[A-Za-z0-9_-]+:)?' + name + '(?:\\s[^>]*)?>([\\s\\S]*?)</(?:[A-Za-z0-9_-]+:)?' + name + '>').exec(block);
        return m ? decode(cdata(m[1])).trim() : '';
    }
    function tagAttr(block, name, attr) {
        var m = new RegExp('<(?:[A-Za-z0-9_-]+:)?' + name + '\\b([^>]*)>').exec(block);
        if (!m) return '';
        var a = new RegExp('\\b' + attr + '\\s*=\\s*("([^"]*)"|\'([^\']*)\')').exec(m[1]);
        return a ? decode(a[2] !== undefined ? a[2] : a[3]) : '';
    }
    function authorName(block) { var i = block.indexOf('<author'); return i < 0 ? '' : tagText(block.slice(i), 'name'); }
    function parseFeed(xml, ch) {
        if (typeof xml !== 'string' || xml.indexOf('<entry') < 0) return [];
        var head = xml.slice(0, xml.indexOf('<entry'));
        var author = authorName(head);
        var out = [], re = /<entry\b[^>]*>([\s\S]*?)<\/entry>/g, m;
        while ((m = re.exec(xml))) {
            var b = m[1], id = tagText(b, 'videoId');
            if (!ID_RE.test(id)) continue;
            var group = /<media:group>[\s\S]*?<\/media:group>/.exec(b), own = group ? b.replace(group[0], '') : b;
            var title = tagText(own, 'title') || (group ? tagText(group[0], 'title') : '');
            if (!title) continue;
            var link = '', lm = /<link\b[^>]*>/g, l;
            while ((l = lm.exec(own))) {
                var href = tagAttr(l[0], 'link', 'href'), rel = tagAttr(l[0], 'link', 'rel');
                if (href && (!link || rel === 'alternate' || !rel)) link = href;
            }
            if (!link) link = 'https://www.youtube.com/watch?v=' + id;
            var pub = Date.parse(tagText(own, 'published') || tagText(own, 'updated'));
            var views = parseInt(tagAttr(b, 'statistics', 'views'), 10);
            var entryAuthor = authorName(own);
            out.push({ id: id, title: title, url: link, thumb: tagAttr(b, 'thumbnail', 'url') || thumbHq(id), published: isNaN(pub) ? 0 : pub,
                views: isNaN(views) ? null : views, durationSec: null, isShort: /\/shorts\//.test(link), isLive: false,
                channelKey: ch ? ch.id : '', channelName: (ch && ch.label) || entryAuthor || author });
        }
        return out;
    }
    function thumbHq(id) { return 'https://i.ytimg.com/vi/' + id + '/hqdefault.jpg'; }
    function thumbMax(id) { return 'https://i.ytimg.com/vi/' + id + '/maxresdefault.jpg'; }

    /* ---- @handle -> channel id (one page fetch, remembered) ------------------------------------------------- */
    var HTML_PATTERNS = [
        /<link[^>]+rel=["']canonical["'][^>]+href=["']https:\/\/www\.youtube\.com\/channel\/(UC[0-9A-Za-z_-]{22})["']/,
        /<meta[^>]+itemprop=["'](?:identifier|channelId)["'][^>]+content=["'](UC[0-9A-Za-z_-]{22})["']/,
        /"externalId":"(UC[0-9A-Za-z_-]{22})"/,
        /"canonicalBaseUrl":"\/channel\/(UC[0-9A-Za-z_-]{22})"/
    ];
    function channelIdFromHtml(html) {
        html = String(html || '');
        for (var i = 0; i < HTML_PATTERNS.length; i++) { var m = HTML_PATTERNS[i].exec(html); if (m) return m[1]; }
        return '';
    }
    function resolveChannel(ch, io) {
        if (CHANNEL_RE.test(ch.channelId || '') || PLAYLIST_RE.test(ch.playlistId || '')) return Promise.resolve(ch);
        var url = handleUrl(ch.handle);
        if (!url) return Promise.reject(new Error('no channel_id, playlist_id or valid handle'));
        var key = 'handle:' + url.toLowerCase(), hit = io.cacheGet ? io.cacheGet(key) : null;
        if (hit && CHANNEL_RE.test(hit)) return Promise.resolve(Object.assign({}, ch, { channelId: hit }));
        return io.fetchText(url, { page: true }).then(function (html) {
            var id = channelIdFromHtml(html);
            if (!id) throw new Error('no channel id on the page of ' + ch.handle);
            if (io.cacheSet) io.cacheSet(key, id);
            return Object.assign({}, ch, { channelId: id });
        });
    }

    /* ---- loading ------------------------------------------------------------------------------------------ */
    function merge(list) {
        var best = {};
        list.forEach(function (v) {
            var o = best[v.id];
            if (!o || (v.durationSec != null && o.durationSec == null) || (v.views != null && o.views == null)) best[v.id] = o ? Object.assign({}, o, v) : v;
        });
        return Object.keys(best).map(function (k) { return best[k]; }).sort(function (a, b) {
            return b.published - a.published || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0);
        });
    }
    function loadFeed(channels, io) {
        var res = { videos: [], errors: [], fetched: 0 }, i = 0;
        function one(ch) {
            return resolveChannel(ch, io).then(function (c) {
                return io.fetchText(feedUrl(c), {}).then(function (xml) {
                    var vs = parseFeed(xml, ch);
                    if (!vs.length) throw new Error('the feed had no entries');
                    res.fetched++;
                    res.videos = res.videos.concat(vs);
                });
            }).catch(function (e) { res.errors.push({ id: ch.id, error: String((e && e.message) || e) }); });
        }
        function next() { if (i >= channels.length) return Promise.resolve(); return one(channels[i++]).then(next); }
        var workers = [];
        for (var k = 0; k < Math.min(POOL, channels.length); k++) workers.push(next());
        return Promise.all(workers).then(function () { res.videos = merge(res.videos); return res; });
    }
    /* "No videos yet" is only honest when there is no source to read. */
    function checkFeed(channels, res) {
        if (!channels.length) return { ok: true, reason: '' };
        if (res.videos.length) return { ok: true, reason: '' };
        var why = res.errors.length ? res.errors[0].id + ': ' + res.errors[0].error : 'every source answered empty';
        return { ok: false, reason: channels.length + ' source(s) declared, 0 videos (' + why + ')' };
    }

    /* ---- Data API enrichment (optional) ------------------------------------------------------------------- */
    function parseDuration(iso) {
        var m = /^P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$/.exec(iso || '');
        if (!m) return null;
        var s = (+m[1] || 0) * 86400 + (+m[2] || 0) * 3600 + (+m[3] || 0) * 60 + (+m[4] || 0);
        return s > 0 ? s : null;
    }
    function parseApi(json) {
        var raw, map = {};
        try { raw = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { return map; }
        ((raw && raw.items) || []).forEach(function (it) {
            if (!it || !ID_RE.test(it.id || '')) return;
            var live = ((it.snippet || {}).liveBroadcastContent || 'none') === 'live';
            var views = parseInt((it.statistics || {}).viewCount, 10);
            map[it.id] = { durationSec: live ? null : parseDuration((it.contentDetails || {}).duration), views: isNaN(views) ? null : views, isLive: live };
        });
        return map;
    }
    function enrich(videos, map) {
        return videos.map(function (v) {
            var e = map[v.id];
            if (!e) return v;
            return Object.assign({}, v, { durationSec: e.durationSec != null ? e.durationSec : v.durationSec, views: e.views != null ? e.views : v.views,
                isLive: e.isLive, isShort: v.isShort || (e.durationSec != null && e.durationSec <= 60) });
        });
    }

    /* ---- chips + filter ----------------------------------------------------------------------------------- */
    function buildChips(order, channels, videos) {
        var chips = [], cats = [];
        channels.forEach(function (c) { if (c.category && cats.indexOf(c.category) < 0) cats.push(c.category); });
        order.forEach(function (kind) {
            if (kind === 'all') chips.push({ id: 'all', kind: 'all', label: 'All' });
            else if (kind === 'shorts') { if (videos.some(function (v) { return v.isShort; })) chips.push({ id: 'shorts', kind: 'shorts', label: 'Shorts' }); }
            else if (kind === 'live') { if (videos.some(function (v) { return v.isLive; })) chips.push({ id: 'live', kind: 'live', label: 'Live' }); }
            else if (kind === 'category') cats.forEach(function (c) { chips.push({ id: 'cat:' + c, kind: 'category', label: c, value: c }); });
            else if (kind === 'channel') channels.forEach(function (c) { chips.push({ id: 'ch:' + c.id, kind: 'channel', label: c.label, value: c.id }); });
        });
        return chips;
    }
    function filterVideos(videos, chip, channels) {
        if (!chip || chip.kind === 'all') return videos;
        if (chip.kind === 'shorts') return videos.filter(function (v) { return v.isShort; });
        if (chip.kind === 'live') return videos.filter(function (v) { return v.isLive; });
        if (chip.kind === 'channel') return videos.filter(function (v) { return v.channelKey === chip.value; });
        var ids = channels.filter(function (c) { return c.category === chip.value; }).map(function (c) { return c.id; });
        return videos.filter(function (v) { return ids.indexOf(v.channelKey) >= 0; });
    }

    /* ---- cards -------------------------------------------------------------------------------------------- */
    function formatDuration(sec) {
        if (sec == null || isNaN(sec) || sec <= 0) return '';
        sec = Math.floor(sec);
        var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
        function p(n) { return n < 10 ? '0' + n : '' + n; }
        return h ? h + ':' + p(m) + ':' + p(s) : m + ':' + p(s);
    }
    function formatViews(n) {
        if (n == null || isNaN(n)) return '';
        if (n === 1) return '1 view';
        function c(x, u) { var t = x >= 10 ? Math.floor(x) : Math.floor(x * 10) / 10; return t + u + ' views'; }
        if (n < 1000) return n + ' views';
        if (n < 1e6) return c(n / 1e3, 'K');
        if (n < 1e9) return c(n / 1e6, 'M');
        return c(n / 1e9, 'B');
    }
    function relTime(ms, now) {
        if (!ms) return '';
        var s = Math.max(0, Math.floor(((now == null ? Date.now() : now) - ms) / 1000));
        function u(n, w) { return n + ' ' + w + (n === 1 ? '' : 's') + ' ago'; }
        if (s < 60) return 'just now';
        if (s < 3600) return u(Math.floor(s / 60), 'minute');
        if (s < 86400) return u(Math.floor(s / 3600), 'hour');
        if (s < 7 * 86400) return u(Math.floor(s / 86400), 'day');
        if (s < 30 * 86400) return u(Math.floor(s / (7 * 86400)), 'week');
        if (s < 365 * 86400) return u(Math.floor(s / (30 * 86400)), 'month');
        return u(Math.floor(s / (365 * 86400)), 'year');
    }
    /* video -> what one feed card shows */
    function toCard(v, channels, now) {
        var ch = channels.filter(function (c) { return c.id === v.channelKey; })[0] || {};
        var name = v.channelName || ch.label || '';
        return { id: v.id, title: v.title, url: v.url, thumbMax: thumbMax(v.id), thumbHq: v.thumb || thumbHq(v.id),
            duration: v.isLive ? '' : formatDuration(v.durationSec), live: !!v.isLive, short: !!v.isShort,
            views: formatViews(v.views), age: relTime(v.published, now), channel: name,
            initial: (name.trim().charAt(0) || '?').toUpperCase(), avatar: ch.avatar || '', embed: embedUrl(v.id, 0) };
    }

    return { parseDeclared: parseDeclared, feedUrl: feedUrl, handleUrl: handleUrl, embedUrl: embedUrl, apiUrl: apiUrl, parseFeed: parseFeed,
        channelIdFromHtml: channelIdFromHtml, resolveChannel: resolveChannel, loadFeed: loadFeed, checkFeed: checkFeed, merge: merge,
        parseApi: parseApi, parseDuration: parseDuration, enrich: enrich, buildChips: buildChips, filterVideos: filterVideos, toCard: toCard,
        formatDuration: formatDuration, formatViews: formatViews, relTime: relTime, thumbMax: thumbMax, thumbHq: thumbHq,
        ID_RE: ID_RE, EMBED_BASE: EMBED_BASE };
});
