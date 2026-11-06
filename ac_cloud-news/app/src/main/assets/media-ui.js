/* Media tab UI: a feed in the shape of a video app's home (own layout, nothing of YouTube's branding): large 16:9 thumbnails with a duration
   badge, two-line titles, channel avatar + name, views and relative time, chips (All / Shorts / Live where known / categories / channels),
   infinite scroll, pull-to-refresh, and in-app playback of the official embed (native overlay: MediaPlayerOverlay.kt) that docks as a
   mini player while the feed scrolls and resumes where it stopped. All logic is media-core.js; the channels, chips and cache/auto-refresh
   rules are data/media.json via MediaBridge.config(). Uses news.html's globals: esc, imagesEnabled, secureImageUrl,
   placeholderThumbDataUri, errorBanner, onOpenArticle, domainHash, State, SourcesUI.
   Battery: a fetch happens on open (a stale cache, under the declared unmetered/charging gate) and on refresh; there are no timers
   (the only setTimeout polls a running fetch job). */
var MediaUI = (function () {
    'use strict';
    var C = MediaCore;
    var INLINE_MAX_SCROLL = 4, PTR_TRIGGER = 64, PTR_MAX = 120, API_CHUNKS = 3;
    var S = { decl: null, videos: [], errors: [], chip: 'all', chips: [], shown: 0, loading: false, token: 0, opened: false, player: '', playerMode: 'inline', observer: null, enriched: {} };

    function $(id) { return document.getElementById(id); }
    function bridge() { return typeof window.MediaBridge !== 'undefined' ? window.MediaBridge : null; }
    function bcall(m, args) {
        var b = bridge();
        if (!b || typeof b[m] !== 'function') return null;
        try { return JSON.parse(b[m].apply(b, args || [])); } catch (e) { return null; }
    }
    function store() { try { return window.localStorage; } catch (e) { return { getItem: function () { return null; }, setItem: function () {} }; } }
    function available() { return !!bridge(); }

    /* ---- the fetch door ---------------------------------------------------------------------------------- */
    function fetchVia(url, mode) {
        var job = bcall('fetchStart', [url, mode]);
        if (!job || !job.job) return Promise.reject(new Error('the media bridge did not start the fetch'));
        return new Promise(function (resolve, reject) {
            (function poll() {
                var r = bcall('fetchResult', [job.job]);
                if (!r) return reject(new Error('bridge error'));
                if (r.pending) return setTimeout(poll, 200);
                if (r.ok) return resolve(r.body);
                var e = new Error(r.error || 'the fetch failed');
                e.code = r.code;
                reject(e);
            })();
        });
    }
    function makeIo(mode) {
        return {
            fetchText: function (url) { return fetchVia(url, mode); },
            cacheGet: function (k) { return store().getItem('media.' + k); },
            cacheSet: function (k, v) { store().setItem('media.' + k, v); }
        };
    }

    /* ---- loading ----------------------------------------------------------------------------------------- */
    function channels() { return S.decl ? S.decl.channels : []; }
    function offered() { return typeof SourcesUI === 'undefined' || SourcesUI.offered('media', 'youtube'); }

    function load(mode) {
        var token = ++S.token, chans = offered() ? channels() : [];
        S.loading = true;
        renderStatus();
        return C.loadFeed(chans, makeIo(mode)).then(function (res) {
            if (token !== S.token) return;
            S.videos = res.videos; S.errors = res.errors; S.enriched = {};
            S.shown = S.decl.feed.pageSize;
            S.loading = false;
            S.failure = C.checkFeed(chans, res).ok ? '' : C.checkFeed(chans, res).reason;
            renderAll();
            return enrichViaApi(token, mode);
        }).catch(function (e) {
            if (token !== S.token) return;
            S.loading = false; S.failure = String((e && e.message) || e);
            renderAll();
        });
    }

    /* The Data API is optional: with a key in the private store it adds duration, exact views and live/short flags. Never required. */
    function enrichViaApi(token, mode) {
        var has = bcall('hasKey');
        if (!has || !has.has) return Promise.resolve();
        var ids = S.videos.map(function (v) { return v.id; }), chunks = [];
        for (var i = 0; i < ids.length && chunks.length < API_CHUNKS; i += 50) chunks.push(ids.slice(i, i + 50));
        return chunks.reduce(function (p, chunk) {
            return p.then(function () {
                var url = C.apiUrl(chunk);
                if (!url || token !== S.token) return;
                return fetchVia(url, mode).then(function (body) {
                    if (token !== S.token) return;
                    var map = C.parseApi(body);
                    Object.keys(map).forEach(function (k) { S.enriched[k] = map[k]; });
                    S.videos = C.enrich(S.videos, S.enriched);
                    renderAll();
                }, function () { /* a missing or refused key leaves the feed as the RSS gave it */ });
            });
        }, Promise.resolve());
    }

    function open() {
        var errMount = $('media-error-mount');
        if (!S.decl) {
            var raw = bcall('config');
            S.decl = raw ? C.parseDeclared(raw) : null;
        }
        if (!S.decl || !S.decl.channels.length) {
            errMount.innerHTML = errorBanner('data/media.json declares no valid channel' + (S.decl && S.decl.errors.length ? ' (' + S.decl.errors[0] + ')' : ''));
            return;
        }
        initOnce();
        if (!offered()) { S.videos = []; S.opened = false; S.failure = ''; }
        if (!S.opened || !S.videos.length) { S.opened = true; load('auto'); } else renderAll();
    }
    function refresh() {
        if (!S.decl || S.loading) return Promise.resolve();
        return load('force');
    }

    /* ---- render ------------------------------------------------------------------------------------------ */
    function activeChip() { return S.chips.filter(function (c) { return c.id === S.chip; })[0] || S.chips[0]; }
    function visible() { return C.filterVideos(S.videos, activeChip(), channels()); }

    function renderAll() {
        S.chips = C.buildChips(S.decl.chips, channels(), S.videos);
        if (!S.chips.some(function (c) { return c.id === S.chip; })) S.chip = 'all';
        renderChips();
        renderStatus();
        renderList();
    }
    function renderChips() {
        var mount = $('media-channel-pills');
        if (!mount) return;
        mount.innerHTML = S.chips.map(function (c) {
            return '<button type="button" class="pill-sub' + (c.id === S.chip ? ' active' : '') + '" data-media-chip="' + esc(c.id) + '">' + esc(c.label) + '</button>';
        }).join('');
        mount.querySelectorAll('[data-media-chip]').forEach(function (b) {
            b.addEventListener('click', function () {
                if (b.dataset.mediaChip === S.chip) return;
                S.chip = b.dataset.mediaChip; S.shown = S.decl.feed.pageSize;
                renderChips(); renderList();
                $('app-main').scrollTop = 0;
            });
        });
    }
    function renderStatus() {
        var m = $('media-error-mount');
        if (!m) return;
        if (S.failure) { m.innerHTML = errorBanner(S.failure) + '<button type="button" class="pill-sub mc-retry" id="media-retry">Try again</button>'; var r = $('media-retry'); if (r) r.addEventListener('click', refresh); return; }
        var note = '';
        if (S.errors && S.errors.length && S.videos.length) note = '<div class="mc-note">' + S.errors.length + ' of ' + channels().length + ' channels could not be read (' + esc(S.errors[0].id + ': ' + S.errors[0].error) + ').</div>';
        m.innerHTML = note;
    }
    function avatarHtml(c) {
        if (c.avatar && imagesEnabled()) return '<img class="mc-av" src="' + esc(secureImageUrl(c.avatar)) + '" alt="">';
        var hue = (typeof domainHash === 'function' ? Math.abs(domainHash(c.channel)) : 0) % 360;
        return '<span class="mc-av mc-av-letter" style="background:hsl(' + hue + ',45%,32%)" aria-hidden="true">' + esc(c.initial) + '</span>';
    }
    function cardHtml(v) {
        var c = C.toCard(v, channels(), Date.now());
        var img = imagesEnabled()
            ? '<img class="mc-img" src="' + esc(secureImageUrl(c.thumbMax)) + '" data-hq="' + esc(secureImageUrl(c.thumbHq)) + '" loading="lazy" onload="MediaUI.thumbLoad(this)" onerror="MediaUI.thumbFail(this)" alt="">'
            : '<img class="mc-img mc-img-off" src="' + esc(placeholderThumbDataUri()) + '" alt="">';
        var badge = c.live ? '<span class="mc-badge mc-live">LIVE</span>' : (c.duration ? '<span class="mc-badge">' + esc(c.duration) + '</span>' : '');
        var meta = [c.channel, c.views, c.age].filter(Boolean).map(esc).join('<span class="mc-dot"></span>');
        return '<article class="mc" data-vid="' + esc(c.id) + '" tabindex="0" role="button" aria-label="' + esc(c.title) + '">' +
            '<div class="mc-thumb">' + img + badge + '</div>' +
            '<div class="mc-row">' + avatarHtml(c) + '<div class="mc-txt"><div class="mc-title">' + esc(c.title) + '</div><div class="mc-meta">' + meta + '</div></div></div>' +
        '</article>';
    }
    function renderList() {
        var mount = $('media-list');
        if (!mount) return;
        var list = visible();
        if (!list.length) {
            mount.innerHTML = S.loading ? '<div class="empty-state">Loading videos…</div>' : (S.failure ? '' : '<div class="empty-state">No videos in this view.</div>');
            return;
        }
        mount.innerHTML = '<div class="mc-list">' + list.slice(0, S.shown).map(cardHtml).join('') + '</div><div id="media-sentinel" class="mc-sentinel"></div>';
        bindCards(mount);
        watchSentinel();
    }
    function bindCards(root) {
        root.querySelectorAll('.mc:not([data-bound])').forEach(function (el) {
            el.setAttribute('data-bound', '1');
            el.addEventListener('click', function () { play(el.dataset.vid); });
            el.addEventListener('keydown', function (ev) { if (ev.key === 'Enter' || ev.key === ' ') { ev.preventDefault(); play(el.dataset.vid); } });
        });
    }

    /* ---- infinite scroll --------------------------------------------------------------------------------- */
    function watchSentinel() {
        var s = $('media-sentinel');
        if (S.observer) { S.observer.disconnect(); S.observer = null; }
        if (!s || typeof IntersectionObserver === 'undefined') return;
        S.observer = new IntersectionObserver(function (entries) {
            if (entries.some(function (e) { return e.isIntersecting; })) more();
        }, { root: $('app-main'), rootMargin: '800px 0px' });
        S.observer.observe(s);
    }
    function more() {
        var list = visible();
        if (S.shown >= list.length) return;
        var from = S.shown;
        S.shown = Math.min(list.length, S.shown + S.decl.feed.pageSize);
        var host = document.querySelector('#media-list .mc-list');
        if (!host) return;
        host.insertAdjacentHTML('beforeend', list.slice(from, S.shown).map(cardHtml).join(''));
        bindCards(host);
        watchSentinel();
    }
    /* maxres is missing for some videos (404) or comes back as a 120px grey stub: fall back to the high-quality thumbnail, then the placeholder */
    function thumbLoad(img) { if (img.naturalWidth && img.naturalWidth <= 120 && img.dataset.hq && img.src !== img.dataset.hq) img.src = img.dataset.hq; }
    function thumbFail(img) {
        if (img.dataset.hq && img.src !== img.dataset.hq) { img.src = img.dataset.hq; return; }
        img.onerror = null; img.src = placeholderThumbDataUri(); img.classList.add('mc-img-off');
    }

    /* ---- pull to refresh --------------------------------------------------------------------------------- */
    function setPtr(h, label) {
        var el = $('media-ptr');
        if (!el) return;
        el.style.height = Math.round(h) + 'px';
        el.textContent = h >= PTR_TRIGGER ? (label || 'Release to refresh') : (h > 0 ? 'Pull to refresh' : '');
    }
    function initPtr() {
        var main = $('app-main'), y0 = null, pull = 0;
        main.addEventListener('touchstart', function (e) {
            y0 = (State.tab === 'media' && main.scrollTop <= 0 && !S.loading) ? e.touches[0].clientY : null; pull = 0;
        }, { passive: true });
        main.addEventListener('touchmove', function (e) {
            if (y0 === null) return;
            var dy = e.touches[0].clientY - y0;
            pull = dy > 0 && main.scrollTop <= 0 ? Math.min(dy * 0.5, PTR_MAX) : 0;
            setPtr(pull);
        }, { passive: true });
        main.addEventListener('touchend', function () {
            if (y0 === null) return;
            var go = pull >= PTR_TRIGGER;
            y0 = null; pull = 0;
            if (!go) return setPtr(0);
            setPtr(PTR_TRIGGER, 'Refreshing…');
            refresh().then(function () { setPtr(0); });
        }, { passive: true });
    }

    /* ---- player ------------------------------------------------------------------------------------------ */
    function slot() {
        var sp = $('media-player-spacer'), r = sp.getBoundingClientRect();
        return [r.left, r.top, r.width, r.height].map(function (n) { return String(Math.max(0, Math.round(n))); });
    }
    function fitSlot() { var sp = $('media-player-spacer'); sp.style.height = Math.round(sp.clientWidth * 9 / 16) + 'px'; }
    function play(id) {
        var main = $('app-main'), b = bridge();
        var sec = (bcall('resume', [id]) || {}).sec || 0, url = C.embedUrl(id, sec);
        if (!url || !b || typeof b.play !== 'function') { onOpenArticle('https://www.youtube.com/watch?v=' + id); return; }
        main.scrollTop = 0;
        fitSlot();
        var res = bcall('play', [url].concat(slot()));
        if (!res || !res.ok) { $('media-player-spacer').style.height = '0px'; onOpenArticle('https://www.youtube.com/watch?v=' + id); return; }
        S.player = id; S.playerMode = 'inline';
    }
    function sendMode() { bcall('setMode', [S.playerMode].concat(slot())); }
    function setMode(mode) { if (S.player && S.playerMode !== mode) { S.playerMode = mode; sendMode(); } }
    function expand() { if (!S.player) return; if (State.tab !== 'media' && typeof switchTab === 'function') switchTab('media'); $('app-main').scrollTop = 0; setMode('inline'); sendMode(); }
    function onPlayerClosed() { S.player = ''; var sp = $('media-player-spacer'); if (sp) sp.style.height = '0px'; }
    function onTab(tab) { if (S.player) { if (tab !== 'media') setMode('mini'); else if ($('app-main').scrollTop <= INLINE_MAX_SCROLL) { setMode('inline'); sendMode(); } } }

    function initOnce() {
        if (S.inited) return;
        S.inited = true;
        initPtr();
        $('app-main').addEventListener('scroll', function () {
            if (S.player && State.tab === 'media') setMode(this.scrollTop > INLINE_MAX_SCROLL ? 'mini' : 'inline');
        }, { passive: true });
        window.addEventListener('resize', function () { if (S.player && S.playerMode === 'inline') { fitSlot(); sendMode(); } });
        var btn = $('media-refresh');
        if (btn) btn.addEventListener('click', refresh);
    }

    /* ---- Configs > Media: the optional Data API key ------------------------------------------------------ */
    function renderKey(mountId) {
        var m = $(mountId);
        if (!m || !bridge()) return;
        var has = (bcall('hasKey') || {}).has;
        m.innerHTML = '<div class="mc-key"><div class="mc-key-title">YouTube Data API key (optional)</div>' +
            '<p class="mc-note">The feed needs no key (public channel RSS). With a key, cards also show the duration and exact views, and Shorts and live videos are told apart. Stored encrypted, never shown again. ' + (has ? 'A key is stored.' : 'No key stored.') + '</p>' +
            '<input id="media-key-input" type="password" autocomplete="off" placeholder="API key" class="mc-key-input">' +
            '<button type="button" class="pill-sub" id="media-key-save">Save</button><button type="button" class="pill-sub" id="media-key-clear">Clear</button>' +
            '<span id="media-key-msg" class="mc-note"></span></div>';
        function save(v) {
            var r = bcall('setKey', [v]);
            $('media-key-msg').textContent = r && r.ok ? 'Saved.' : ((r && r.error) || 'Could not save.');
            if (r && r.ok) { $('media-key-input').value = ''; if (S.decl) S.opened = false; }
        }
        $('media-key-save').addEventListener('click', function () { var v = $('media-key-input').value.trim(); if (v) save(v); });
        $('media-key-clear').addEventListener('click', function () { save(''); });
    }

    return { available: available, open: open, refresh: refresh, expand: expand, onPlayerClosed: onPlayerClosed, onTab: onTab, renderKey: renderKey,
        thumbLoad: thumbLoad, thumbFail: thumbFail, rerender: function () { if (S.decl) renderAll(); } };
})();
