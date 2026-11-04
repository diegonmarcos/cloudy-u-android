/* Events tab UI: city picker, category / When / ticketer filters, Calendar | List | Cards views, and the
   Configs > Events section (per-ticketer on/off + user-entered API key). All logic is events-core.js; all lists
   come from data/event-finder.json via NewsBridge.eventFinder(). Uses news.html's globals: esc, Api, bindSwipeCard,
   flyOutCard, imagesEnabled, fmtEventTime, errorBanner. */
var EventsUI = (function () {
    'use strict';
    var C = EventsCore;
    var CFG = null, prefs = null, skipped = {}, savedIds = {};
    var S = { events: [], status: {}, range: null, loaded: false, loading: false, token: 0, calY: 0, calM: 0, day: '', card: 0 };
    var CAL_DOW = ['S', 'M', 'T', 'W', 'T', 'F', 'S'];

    function store() { try { return window.localStorage; } catch (e) { return { getItem: function () { return null; }, setItem: function () {} }; } }
    function bridge() { return typeof window.NewsBridge !== 'undefined' ? window.NewsBridge : null; }
    function bcall(m, args) {
        var b = bridge();
        if (!b || typeof b[m] !== 'function') return null;
        try { return JSON.parse(b[m].apply(b, args || [])); } catch (e) { return null; }
    }
    function $(id) { return document.getElementById(id); }
    function color(c) { return /^#[0-9a-fA-F]{3,8}$/.test(c || '') ? c : '#8B9096'; }

    /* ---- config + ramp ---------------------------------------------------------------------------------- */
    function loadCfg() {
        if (CFG) return true;
        var c = bcall('eventFinder');
        if (!c || !c.ticketers) return false;
        CFG = c;
        var r = c.ramp, st = document.documentElement.style;
        st.setProperty('--ef-caption', (r.caption * r.text_scale) + 'px');
        st.setProperty('--ef-meta', (r.meta * r.text_scale) + 'px');
        st.setProperty('--ef-body', (r.body * r.text_scale) + 'px');
        st.setProperty('--ef-title', (r.title * r.text_scale) + 'px');
        r.steps.forEach(function (n) { st.setProperty('--ef-s' + n, (n * r.scale) + 'px'); });
        prefs = C.loadPrefs(store(), CFG);
        skipped = C.loadSkipped(store());
        return true;
    }
    function persist() { C.savePrefs(store(), prefs); }
    function city() { return C.cityById(CFG.cities, prefs.city); }
    function ticketer(id) { return CFG.ticketers.filter(function (t) { return t.id === id; })[0]; }

    /* ---- fetching ---------------------------------------------------------------------------------------- */
    // EventFetcher runs on the bridge's executor: start a job, poll its result (the JS thread never blocks on I/O).
    function fetchVia(ticketerId, req, force) {
        var b = bridge();
        if (!b || typeof b.eventFetchStart !== 'function') return Promise.resolve({ ok: false, error: 'bridge unavailable' });
        var job = bcall('eventFetchStart', [ticketerId, JSON.stringify(req), force ? '1' : '0']);
        if (!job || !job.job) return Promise.resolve({ ok: false, error: (job && job.error) || 'could not start' });
        return new Promise(function (resolve) {
            (function poll() {
                var r = bcall('eventFetchResult', [job.job]);
                if (r && !r.pending) return resolve(r);
                if (!r) return resolve({ ok: false, error: 'bridge error' });
                setTimeout(poll, 250);
            })();
        });
    }
    function engineRows(from, to) {
        try { var r = Api.events(from, to); return Array.isArray(r) ? r : []; } catch (e) { return []; }
    }
    function hasKey(id) { var r = bcall('eventHasKey', [id]); return !!(r && r.has); }

    function load(force) {
        if (!CFG || S.loading) return;
        var token = ++S.token;
        S.loading = true; renderStatus();
        C.runFinder(CFG, prefs, {
            now: Date.now(), hasKey: hasKey, engine: engineRows,
            fetch: function (id, req) { return fetchVia(id, req, force); }
        }).then(function (res) {
            if (token !== S.token) return;
            S.events = res.events; S.status = res.status; S.range = res.range; S.loaded = true; S.loading = false;
            var d = new Date(res.range.from); S.calY = d.getFullYear(); S.calM = d.getMonth(); S.day = '';
            S.card = 0; refreshSaved(); render();
        });
    }
    function refreshSaved() {
        savedIds = {};
        try { (Api.savedEvents() || []).forEach(function (e) { savedIds[e.id] = true; }); } catch (e) { /* none */ }
    }

    /* ---- derived ----------------------------------------------------------------------------------------- */
    function filtered(ignoreCategory) {
        return C.applyFilters(S.events, {
            category: ignoreCategory ? 'all' : prefs.category, sources: prefs.sources,
            from: S.range && S.range.from, to: S.range && S.range.to, skipped: prefs.view === 'cards' ? skipped : null
        });
    }
    function saveShape(e) {
        return { id: e.id, calendarId: e.source, calendarLabel: (ticketer(e.source) || {}).label || e.source, color: e.color,
            title: e.title, location: [e.venue, e.city].filter(Boolean).join(', '), start: e.start, end: e.end || e.start + 3600000, allDay: !!e.allDay };
    }
    function isSaved(e) { return !!savedIds[e.id]; }
    function toggleSave(e) {
        try { var r = Api.saveEvent(saveShape(e)); if (r && r.ok !== false) { if (r.saved) savedIds[e.id] = true; else delete savedIds[e.id]; } } catch (err) { /* stays as it was */ }
    }
    function fmtTime(e) {
        var d = new Date(e.start), two = function (n) { return n < 10 ? '0' + n : '' + n; };
        var day = d.toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' });
        return e.allDay ? day : day + ' · ' + two(d.getHours()) + ':' + two(d.getMinutes());
    }
    function catLabel(id) { var c = CFG.categories.filter(function (x) { return x.id === id; })[0]; return c ? c.label : id; }
    function srcLabel(id) { var t = ticketer(id); return t ? (t.short || t.label) : id; }

    /* ---- chrome ------------------------------------------------------------------------------------------ */
    function chips(items, sel, attr, extra) {
        return items.map(function (i) {
            return '<button type="button" class="ef-chip' + (sel(i) ? ' on' : '') + '" data-' + attr + '="' + esc(i.id) + '">' + (extra ? extra(i) : esc(i.label)) + '</button>';
        }).join('');
    }
    function renderChrome() {
        var counts = C.countByCategory(filtered(true), CFG.categories);
        var enabled = CFG.ticketers.filter(function (t) { return prefs.sources[t.id]; }).length;
        $('ef-root').innerHTML =
            '<div class="ef-head"><span class="ef-title-h">Events</span>' +
                '<button type="button" class="ef-city-btn" id="ef-city-btn">&#9679; ' + esc(city().name) + ' <span style="color:var(--text-muted)">' + esc(city().country) + '</span> &#9662;</button></div>' +
            '<div class="ef-seg" id="ef-views">' + CFG.views.map(function (v) { return '<button type="button" data-view="' + esc(v.id) + '" class="' + (prefs.view === v.id ? 'on' : '') + '">' + esc(v.label) + '</button>'; }).join('') + '</div>' +
            '<div class="ef-bar" id="ef-cats">' + chips(CFG.categories, function (c) { return prefs.category === c.id; }, 'cat',
                function (c) { return (c.id === 'all' ? '' : '<span class="ef-dot" style="background:' + esc(color(c.color)) + '"></span>') + esc(c.label) + (S.loaded ? '<span class="ef-n">' + (counts[c.id] || 0) + '</span>' : ''); }) + '</div>' +
            '<div class="ef-bar" id="ef-when">' + chips(CFG.when, function (w) { return prefs.when === w.id; }, 'when') + '</div>' +
            (prefs.when === 'custom' ? '<div class="ef-custom"><input type="date" id="ef-from" value="' + esc(prefs.custom.from) + '"><span>to</span><input type="date" id="ef-to" value="' + esc(prefs.custom.to) + '"></div>' : '') +
            '<div class="ef-bar" id="ef-src"><span class="ef-label">Sources</span>' + chips(CFG.ticketers, function (t) { return prefs.sources[t.id]; }, 'src',
                function (t) { return esc(t.short || t.label); }) + '</div>' +
            '<div class="ef-status" id="ef-status"></div><div id="ef-body"></div>';
        wireChrome();
        renderStatus();
    }
    function renderStatus() {
        var el = $('ef-status'); if (!el) return;
        if (S.loading) { el.textContent = 'Loading ' + city().name + '…'; return; }
        if (!S.loaded) { el.textContent = ''; return; }
        var bad = [], cached = 0;
        Object.keys(S.status).forEach(function (id) {
            var s = S.status[id];
            if (s.state === 'cached') cached++;
            if (s.state === 'error' || s.state === 'needs-key' || s.state === 'blocked') bad.push(srcLabel(id) + ': ' + s.message);
        });
        el.innerHTML = '<span>' + filtered().length + ' events</span>' + (cached ? '<span>cached</span>' : '') +
            bad.map(function (b) { return '<span class="bad">' + esc(b) + '</span>'; }).join('') + '<button type="button" id="ef-refresh">Refresh</button>';
        var rb = $('ef-refresh'); if (rb) rb.addEventListener('click', function () { load(true); });
    }
    function wireChrome() {
        $('ef-city-btn').addEventListener('click', openCityPicker);
        $('ef-views').addEventListener('click', function (ev) { var b = ev.target.closest('[data-view]'); if (b) { prefs.view = b.dataset.view; persist(); S.card = 0; renderChrome(); render(true); } });
        $('ef-cats').addEventListener('click', function (ev) { var b = ev.target.closest('[data-cat]'); if (b) { prefs.category = b.dataset.cat; persist(); S.card = 0; renderChrome(); render(true); } });
        $('ef-when').addEventListener('click', function (ev) { var b = ev.target.closest('[data-when]'); if (b) { prefs.when = b.dataset.when; persist(); if (prefs.when !== 'custom' || (prefs.custom.from)) { renderChrome(); load(false); } else renderChrome(); } });
        $('ef-src').addEventListener('click', function (ev) { var b = ev.target.closest('[data-src]'); if (b) { prefs.sources[b.dataset.src] = !prefs.sources[b.dataset.src]; persist(); renderChrome(); load(false); } });
        var f = $('ef-from'), t = $('ef-to');
        function custom() { prefs.custom = { from: f.value, to: t.value }; persist(); if (f.value) load(false); }
        if (f) { f.addEventListener('change', custom); t.addEventListener('change', custom); }
    }

    /* ---- views ------------------------------------------------------------------------------------------- */
    function render(chromeDone) {
        if (!$('ef-root')) return;
        if (!chromeDone || !$('ef-body')) renderChrome();
        renderStatus();
        var body = $('ef-body'), list = filtered();
        if (S.loading && !S.loaded) { body.innerHTML = ''; return; }
        if (!list.length && prefs.view !== 'calendar') {
            body.innerHTML = '<div class="ef-empty">' + (S.loaded ? 'No events for these filters in ' + esc(city().name) + '.<br>Try a wider time range, another category, or switch a source on.' : '') + '</div>';
            return;
        }
        if (prefs.view === 'calendar') renderCalendar(body, list);
        else if (prefs.view === 'cards') renderCards(body, list);
        else renderList(body, list);
    }

    function rowHtml(e) {
        var thumb = e.image && imagesEnabled() ? '<img class="ef-thumb" loading="lazy" src="' + esc(e.image) + '" alt="" onerror="this.style.visibility=\'hidden\'">'
            : '<span class="ef-thumb" style="background:' + esc(color(e.color)) + '">' + esc((e.title || '?').charAt(0).toUpperCase()) + '</span>';
        return '<div class="ef-row" data-id="' + esc(e.id) + '">' + thumb +
            '<div class="ef-main"><div class="ef-name">' + esc(e.title) + '</div>' +
            '<div class="ef-meta">' + esc(fmtTime(e)) + '</div>' +
            '<div class="ef-cap">' + esc([e.venue, e.city].filter(Boolean).join(', ')) + (e.price ? ' · ' + esc(e.price) : '') + '</div>' +
            '<div class="ef-cap"><b style="color:' + esc(color(e.color)) + '">' + esc(srcLabel(e.source)) + '</b> · ' + esc(catLabel(e.category)) + '</div></div>' +
            '<button type="button" class="ef-star' + (isSaved(e) ? ' on' : '') + '" data-save="' + esc(e.id) + '" aria-label="Save">' + (isSaved(e) ? '&#9733;' : '&#9734;') + '</button></div>';
    }
    function wireRows(mount, list) {
        var by = {}; list.forEach(function (e) { by[e.id] = e; });
        mount.querySelectorAll('[data-save]').forEach(function (b) {
            b.addEventListener('click', function (ev) { ev.stopPropagation(); var e = by[b.dataset.save]; toggleSave(e); b.classList.toggle('on', isSaved(e)); b.innerHTML = isSaved(e) ? '&#9733;' : '&#9734;'; });
        });
        mount.querySelectorAll('.ef-row').forEach(function (r) {
            r.addEventListener('click', function () { var e = by[r.dataset.id]; if (e && e.url) { try { Api.openExternal(e.url); } catch (err) { /* none */ } } });
        });
    }
    function renderList(body, list) {
        var html = '', last = '';
        list.forEach(function (e) {
            var k = C.dateKey(e.start);
            if (k !== last) { last = k; html += '<div class="ef-day">' + esc(new Date(e.start).toLocaleDateString(undefined, { weekday: 'long', month: 'long', day: 'numeric' })) + '</div>'; }
            html += rowHtml(e);
        });
        body.innerHTML = html; wireRows(body, list);
    }

    function renderCalendar(body, list) {
        var by = C.groupByDay(list), today = C.dateKey(Date.now());
        var cells = C.monthGrid(S.calY, S.calM, 0);
        var head = CAL_DOW.map(function (d) { return '<div class="cal-dow">' + d + '</div>'; }).join('');
        var grid = cells.map(function (c) {
            var ev = by[c.key] || [];
            var dots = ev.slice(0, 4).map(function (e) { return '<span class="cal-day-dot" style="background:' + esc(color(e.color)) + '"></span>'; }).join('');
            return '<button type="button" class="cal-day' + (c.other ? ' other-month' : '') + (c.key === today ? ' today' : '') + (c.key === S.day ? ' selected' : '') + '" data-day="' + c.key + '"><span>' + c.d + '</span>' + (dots ? '<span class="cal-day-dots">' + dots + '</span>' : '') + '</button>';
        }).join('');
        var title = new Date(S.calY, S.calM, 1).toLocaleDateString(undefined, { month: 'long', year: 'numeric' });
        body.innerHTML = '<div class="ef-month"><button type="button" id="ef-prev">&#8249;</button><span>' + esc(title) + '</span><button type="button" id="ef-next">&#8250;</button></div>' +
            '<div class="ef-calwrap"><div class="cal-grid">' + head + grid + '</div></div><div id="ef-daylist"></div>';
        function move(n) { var d = new Date(S.calY, S.calM + n, 1); S.calY = d.getFullYear(); S.calM = d.getMonth(); renderCalendar(body, list); }
        $('ef-prev').addEventListener('click', function () { move(-1); });
        $('ef-next').addEventListener('click', function () { move(1); });
        body.querySelectorAll('[data-day]').forEach(function (b) { b.addEventListener('click', function () { S.day = b.dataset.day; renderCalendar(body, list); }); });
        var dl = $('ef-daylist'), day = S.day && by[S.day] || [];
        if (!S.day) dl.innerHTML = '<div class="ef-empty">Tap a day to see its events.</div>';
        else if (!day.length) dl.innerHTML = '<div class="ef-empty">No events on this day.</div>';
        else { dl.innerHTML = day.map(rowHtml).join(''); wireRows(dl, day); }
    }

    function renderCards(body, list) {
        var e = list[S.card];
        if (!e) {
            body.innerHTML = '<div class="ef-empty"><strong style="display:block;color:var(--text)">All caught up</strong>You have been through all ' + list.length + ' events. Saved ones are in the Saved tab.</div>';
            return;
        }
        var img = e.image && imagesEnabled() ? '<div class="ef-card-img" style="background-image:url(\'' + esc(e.image).replace(/'/g, '%27') + '\')"></div>' : '<div class="event-card-accent" style="background:' + esc(color(e.color)) + '"></div>';
        body.innerHTML = '<div class="ef-hint">' + (S.card + 1) + ' of ' + list.length + ' · swipe right to save, left to skip</div>' +
            '<div class="px-6"><div class="event-stack"><div class="event-card" id="ef-card">' + img +
            '<div class="event-card-body"><div class="ef-card-src">' + esc(srcLabel(e.source)) + ' · ' + esc(catLabel(e.category)) + '</div>' +
            '<div class="ef-card-title">' + esc(e.title) + '</div>' +
            '<div class="ef-card-row">' + esc(fmtTime(e)) + '</div>' +
            '<div class="ef-card-row">' + esc([e.venue, e.city].filter(Boolean).join(', ')) + (e.price ? ' · ' + esc(e.price) : '') + '</div></div>' +
            '<div class="event-card-stamp save" id="ef-stamp-save">Save</div><div class="event-card-stamp skip" id="ef-stamp-skip">Skip</div>' +
            '<div class="event-card-actions"><button type="button" class="event-action-btn event-action-skip" id="ef-skip">Skip</button>' +
            '<button type="button" class="event-action-btn event-action-save" id="ef-save">' + (isSaved(e) ? 'Saved ✓' : 'Save') + '</button></div></div></div></div>';
        var card = $('ef-card');
        function decide(kind) {
            flyOutCard(card, kind === 'save' ? 1 : -1, $(kind === 'save' ? 'ef-stamp-save' : 'ef-stamp-skip'), function () {
                if (kind === 'save') { if (!isSaved(e)) toggleSave(e); }
                else { skipped[e.id] = true; C.saveSkipped(store(), skipped); }
                S.card = kind === 'skip' ? S.card : S.card + 1;     // a skipped event leaves the filtered list, so the index already points at the next
                renderStatus(); renderCards(body, filtered());
            });
        }
        $('ef-save').addEventListener('click', function () { decide('save'); });
        $('ef-skip').addEventListener('click', function () { decide('skip'); });
        bindSwipeCard(card, { stampRight: $('ef-stamp-save'), stampLeft: $('ef-stamp-skip'), ignoreSelector: '.event-action-btn',
            onRight: function () { decide('save'); }, onLeft: function () { decide('skip'); } });
    }

    /* ---- city picker ------------------------------------------------------------------------------------- */
    function openCityPicker() {
        var ov = $('ef-overlay');
        if (!ov) {
            ov = document.createElement('div'); ov.id = 'ef-overlay'; ov.className = 'ef-overlay hidden';
            ov.innerHTML = '<div class="ef-overlay-head"><input type="search" id="ef-city-q" placeholder="Search a city or country" autocomplete="off"><button type="button" id="ef-city-x">Close</button></div><div class="ef-overlay-list" id="ef-city-list"></div>';
            document.body.appendChild(ov);
            $('ef-city-x').addEventListener('click', function () { ov.classList.add('hidden'); });
            $('ef-city-q').addEventListener('input', function () { fillCities(this.value); });
            $('ef-city-list').addEventListener('click', function (ev) {
                var b = ev.target.closest('[data-city]'); if (!b) return;
                prefs.city = b.dataset.city; persist(); ov.classList.add('hidden'); S.card = 0; renderChrome(); load(false);
            });
        }
        $('ef-city-q').value = ''; fillCities(''); ov.classList.remove('hidden'); $('ef-city-q').focus();
    }
    function fillCities(q) {
        var rows = C.searchCities(CFG.cities, q);
        $('ef-city-list').innerHTML = rows.length ? rows.map(function (c) {
            return '<button type="button" class="ef-city-row' + (c.id === prefs.city ? ' on' : '') + '" data-city="' + esc(c.id) + '"><span class="n">' + esc(c.name) + '</span><span class="c">' + esc(c.countryName) + '</span></button>';
        }).join('') : '<div class="ef-empty">No city matches “' + esc(q) + '”.</div>';
    }

    /* ---- Configs > Events -------------------------------------------------------------------------------- */
    function renderConfigs() {
        var m = $('ef-configs-mount'); if (!m || !loadCfg()) return;
        m.innerHTML = CFG.ticketers.map(function (t) {
            var needsKey = t.auth && t.auth.type === 'key', has = needsKey && hasKey(t.id);
            return '<div class="ef-cfg-row" data-t="' + esc(t.id) + '"><div class="ef-cfg-top"><span><span class="ef-cfg-name">' + esc(t.label) + '</span><span class="ef-cfg-kind">' + esc(t.fetch.kind) + '</span></span>' +
                '<label class="switch"><input type="checkbox" data-on="' + esc(t.id) + '"' + (prefs.sources[t.id] ? ' checked' : '') + '><span class="slider"></span></label></div>' +
                '<div class="ef-cfg-note">' + esc(t.fetch.how) + '. ' + esc(t.note || '') + (t.terms_url ? ' <a href="#" data-terms="' + esc(t.terms_url) + '" style="text-decoration:underline">Terms</a>' : '') + '</div>' +
                (needsKey ? '<div class="ef-cfg-key"><input type="password" autocomplete="off" placeholder="' + esc(t.auth.label) + (has ? ' (saved, encrypted)' : '') + '" data-key="' + esc(t.id) + '"><button type="button" data-savekey="' + esc(t.id) + '">Save</button>' +
                    (has ? '<button type="button" class="quiet" data-clearkey="' + esc(t.id) + '">Clear</button>' : '') + '</div>' +
                    '<div class="ef-cfg-note">' + (has ? '<span class="ef-cfg-ok">Key stored encrypted on this device.</span> ' : '') + '<a href="#" data-terms="' + esc(t.auth.help_url) + '" style="text-decoration:underline">Get a key</a></div>' : '') + '</div>';
        }).join('');
        m.onclick = function (ev) {
            var a = ev.target.closest('[data-terms]'); if (a) { ev.preventDefault(); try { Api.openExternal(a.dataset.terms); } catch (e) { /* none */ } return; }
            var s = ev.target.closest('[data-savekey]');
            if (s) { var inp = m.querySelector('[data-key="' + s.dataset.savekey + '"]'); if (inp.value.trim()) { bcall('eventSetKey', [s.dataset.savekey, inp.value.trim()]); inp.value = ''; renderConfigs(); } return; }
            var c = ev.target.closest('[data-clearkey]'); if (c) { bcall('eventSetKey', [c.dataset.clearkey, '']); renderConfigs(); }
        };
        m.onchange = function (ev) { var i = ev.target.closest('[data-on]'); if (i) { prefs.sources[i.dataset.on] = i.checked; persist(); S.loaded = false; } };
    }

    /* ---- entry points ------------------------------------------------------------------------------------ */
    function open() {
        var root = $('ef-root'); if (!root) return;
        if (!loadCfg()) { root.innerHTML = '<div class="ef-empty">The events finder needs the app\'s data (open this in the app).</div>'; return; }
        renderChrome();
        if (!S.loaded) load(false); else render(true);
    }
    return { open: open, render: function () { if (CFG && $('ef-root')) { if (S.loaded) render(); else open(); } }, renderConfigs: renderConfigs };
})();
