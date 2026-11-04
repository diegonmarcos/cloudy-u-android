/* #1470 Configs > Media | Events | News | Apify. Every list comes from data/source-decl.json (sources-decl.js parses and routes it):
   each area shows an Apify and a Direct subsection of dense Store rows; the Apify tab holds the backend choice (Apify Cloud |
   self-hosted Cloudy Crawlee) and its token / bearer, stored encrypted by the shared libs:apify store (NewsBridge.apify*).
   Uses news.html's globals: esc, Api. */
var SourcesUI = (function () {
    'use strict';
    var SD = null, P = null, cfgApify = null, hooks = {};
    var KEY = 'sd.prefs';

    function store() { try { return window.localStorage; } catch (e) { return { getItem: function () { return null; }, setItem: function () {} }; } }
    function bridge() { return typeof window.NewsBridge !== 'undefined' ? window.NewsBridge : null; }
    function bcall(m, args) {
        var b = bridge();
        if (!b || typeof b[m] !== 'function') return null;
        try { return JSON.parse(b[m].apply(b, args || [])); } catch (e) { return null; }
    }
    function $(id) { return document.getElementById(id); }

    function load() {
        if (SD) return SD;
        var b = bridge();
        if (!b || typeof b.sourceDecl !== 'function') return null;
        SD = SourcesDecl.parse(b.sourceDecl());
        try { P = JSON.parse(store().getItem(KEY) || '{}') || {}; } catch (e) { P = {}; }
        P.enabled = P.enabled || {}; P.actors = P.actors || {};
        return SD;
    }
    function persist() { store().setItem(KEY, JSON.stringify(P)); }

    /* an area's own on/off store (events keep theirs in the finder prefs) */
    function isOn(area, row) {
        if (hooks[area]) return hooks[area].get(row.id);
        return P.enabled[row.id] !== undefined ? !!P.enabled[row.id] : row.enabled;
    }
    function setOn(area, row, on) {
        if (hooks[area]) hooks[area].set(row.id, on); else { P.enabled[row.id] = on; persist(); }
    }
    function route(area, id) { return load() ? SourcesDecl.route(SD, area, id, P) : null; }
    /* may a picker offer this source? (declared enabled + the user's switch); undeclared ids stay offered */
    function offered(area, id) {
        if (!load()) return true;
        var s = SourcesDecl.find(SD, area, id);
        return !s || (s.enabled && isOn(area, s));
    }

    /* ---- running an Apify source ---------------------------------------------------------------------------- */
    function apifyRun(sourceId, actor, input, opts) {
        var b = bridge();
        if (!b || typeof b.apifyStart !== 'function') return Promise.resolve({ ok: false, error: 'bridge unavailable' });
        var job = bcall('apifyStart', [sourceId, actor || '', JSON.stringify(input || {}), String((opts && opts.cache_hours) || 6), opts && opts.force ? '1' : '0']);
        if (!job || !job.job) return Promise.resolve({ ok: false, error: (job && job.error) || 'could not start' });
        return new Promise(function (resolve) {
            (function poll() {
                var r = bcall('eventFetchResult', [job.job]);
                if (!r) return resolve({ ok: false, error: 'bridge error' });
                if (!r.pending) return resolve(r);
                setTimeout(poll, 500);
            })();
        });
    }

    /* ---- Media | Events | News ------------------------------------------------------------------------------- */
    function rowHtml(area, s) {
        var on = isOn(area, s), actor = s.method === 'apify' ? (P.actors[s.id] || s.actor) : '';
        var sub = (actor ? '<span class="sd-actor">' + esc(actor) + '</span> ' : '') + esc(s.how);
        return '<div class="sd-row' + (s.enabled ? '' : ' sd-off') + '" data-sd="' + esc(s.id) + '">' +
            '<div class="sd-main"' + (s.method === 'apify' ? ' data-actor-edit="' + esc(s.id) + '"' : '') + '><div class="sd-name">' + esc(s.label) + '</div><div class="sd-sub">' + sub + '</div></div>' +
            '<label class="switch"><input type="checkbox" data-sd-on="' + esc(s.id) + '"' + (on && s.enabled ? ' checked' : '') + (s.enabled ? '' : ' disabled') + '><span class="slider"></span></label>' +
            (s.method === 'apify' ? '<div class="sd-edit hidden" data-sd-edit="' + esc(s.id) + '"><input type="text" autocomplete="off" spellcheck="false" placeholder="' + esc(s.actor) + '" value="' + esc(P.actors[s.id] || '') + '"><button type="button" data-sd-save="' + esc(s.id) + '">Save</button><button type="button" class="quiet" data-sd-reset="' + esc(s.id) + '">Default</button></div>' : '') +
            '</div>';
    }
    function section(area, method, label, rows) {
        return '<div class="sd-head">' + label + '<span class="sd-count">' + rows.length + '</span></div>' +
            (rows.length ? rows.map(function (s) { return rowHtml(area, s); }).join('') : '<div class="sd-empty">None declared.</div>');
    }
    function render(area, mountId) {
        var m = $(mountId); if (!m) return;
        if (!load()) { m.innerHTML = '<div class="sd-empty">The source list needs the app\'s data (open this in the app).</div>'; return; }
        var sec = SourcesDecl.bySection(SD, area);
        m.innerHTML = section(area, 'apify', 'Apify', sec.apify) + section(area, 'direct', 'Direct', sec.direct) +
            (SD.errors.length ? '<div class="sd-empty">' + esc(SD.errors.length + ' declaration row(s) ignored: ' + SD.errors[0]) + '</div>' : '');
        m.onchange = function (ev) {
            var i = ev.target.closest('[data-sd-on]'); if (!i) return;
            var s = SourcesDecl.find(SD, area, i.dataset.sdOn); if (!s) return;
            setOn(area, s, i.checked);
            if (area === 'news' && typeof onSourcesChanged === 'function') onSourcesChanged(area);
            if (area === 'media' && typeof onSourcesChanged === 'function') onSourcesChanged(area);
        };
        m.onclick = function (ev) {
            var save = ev.target.closest('[data-sd-save]'), rst = ev.target.closest('[data-sd-reset]');
            if (save || rst) {
                var id = (save || rst).dataset.sdSave || (save || rst).dataset.sdReset, box = m.querySelector('[data-sd-edit="' + id + '"] input'), v = save ? box.value.trim() : '';
                if (v && !SourcesDecl.ACTOR.test(v)) { box.style.borderColor = 'var(--danger, #c0392b)'; return; }
                if (v) P.actors[id] = v; else delete P.actors[id];
                persist(); render(area, mountId); return;
            }
            var ed = ev.target.closest('[data-actor-edit]');
            if (ed) { var e = m.querySelector('[data-sd-edit="' + ed.dataset.actorEdit + '"]'); if (e) e.classList.toggle('hidden'); }
        };
    }

    /* ---- Apify: the backend and its secret --------------------------------------------------------------------- */
    function renderApify(mountId) {
        var m = $(mountId); if (!m) return;
        cfgApify = bcall('apifyConfig');
        if (!cfgApify) { m.innerHTML = '<div class="sd-empty">The Apify settings need the app\'s data (open this in the app).</div>'; return; }
        var c = cfgApify, cur = c.backends.filter(function (b) { return b.id === c.backend; })[0] || c.backends[0], has = !!c.hasToken[cur.id];
        var apifyRows = load() ? SD.sources.filter(function (s) { return s.method === 'apify'; }) : [];
        m.innerHTML = '<div class="sd-head">Run actors on</div>' +
            '<div class="seg" id="sd-backend">' + c.backends.map(function (b) { return '<button type="button" data-backend="' + esc(b.id) + '"' + (b.id === cur.id ? ' class="active"' : '') + '>' + esc(b.label) + '</button>'; }).join('') + '</div>' +
            '<div class="sd-sub" style="margin-top:var(--ef-s8)">' + (cur.cloudflare
                ? 'Cloudflare Browser Run: headless-browser rendering (developers.cloudflare.com/browser-rendering). A source renders its first start URL as Markdown, through your deployed edge-md-scraper Worker (Worker URL, default) or the REST API (account id plus an API token with Browser Rendering permission, billed to your Cloudflare account).'
                : cur.selfHosted
                ? 'The fleet\'s own Apify-compatible runner (Crawlee Cloud, reached over the mesh). Same API as Apify Cloud; nothing is billed by Apify.'
                : 'Apify\'s cloud. Actors run on your Apify account and bill it. Paste your API token (console.apify.com, Settings, Integrations).') + '</div>' +
            (cur.cloudflare ? '<div class="seg" id="sd-cfmode">' + [['worker', 'Worker URL'], ['rest', 'REST API']].map(function (m) { return '<button type="button" data-cfmode="' + m[0] + '"' + (c.cfMode === m[0] ? ' class="active"' : '') + '>' + m[1] + '</button>'; }).join('') + '</div>' : '') +
            (cur.cloudflare && c.cfMode === 'worker' ? '<div class="sd-head">Worker URL</div><div class="ef-cfg-key"><input type="url" id="sd-worker" autocomplete="off" spellcheck="false" placeholder="https://edge-md-scraper.example.workers.dev" value="' + esc(c.workerUrl) + '"><button type="button" id="sd-worker-save">Save</button></div>' : '') +
            (cur.cloudflare && c.cfMode === 'rest' ? '<div class="sd-head">Account id</div><div class="ef-cfg-key"><input type="text" id="sd-account" autocomplete="off" spellcheck="false" placeholder="32 hex characters" value="' + esc(c.accountId) + '"><button type="button" id="sd-account-save">Save</button></div>' : '') +
            (cur.selfHosted ? '<div class="sd-head">Base URL</div><div class="ef-cfg-key"><input type="url" id="sd-url" autocomplete="off" spellcheck="false" value="' + esc(c.selfUrl) + '"><button type="button" id="sd-url-save">Save</button></div>' : '') +
            '<div class="sd-head">' + (cur.selfHosted ? 'Fleet bearer' : 'API token') + '</div>' +
            '<div class="ef-cfg-key"><input type="password" id="sd-token" autocomplete="off" placeholder="' + (cur.selfHosted ? 'Fleet bearer' : cur.cloudflare ? (c.cfMode === 'worker' ? 'Worker bearer token' : 'Cloudflare API token') : 'Apify API token') + (has ? ' (saved, encrypted)' : '') + '"><button type="button" id="sd-token-save">Save</button>' + (has ? '<button type="button" class="quiet" id="sd-token-clear">Clear</button>' : '') + '</div>' +
            '<div class="sd-sub" id="sd-msg">' + (has ? '<span class="ef-cfg-ok">Stored encrypted on this device.</span>' : 'Nothing stored for this backend: Apify sources will ask for it.') + '</div>' +
            '<div class="sd-head">Apify sources<span class="sd-count">' + apifyRows.length + '</span></div>' +
            apifyRows.map(function (s) { return '<div class="sd-row"><div class="sd-main"><div class="sd-name">' + esc(s.label) + '</div><div class="sd-sub"><span class="sd-actor">' + esc(P.actors[s.id] || s.actor) + '</span> ' + esc(s.area) + '</div></div></div>'; }).join('');
        function say(t) { var x = $('sd-msg'); if (x) x.textContent = t; }
        m.onclick = function (ev) {
            var b = ev.target.closest('[data-backend]');
            if (b) { bcall('apifySetBackend', [b.dataset.backend]); renderApify(mountId); return; }
            if (ev.target.id === 'sd-url-save') { var r = bcall('apifySetSelfUrl', [$('sd-url').value.trim()]); if (r && r.ok) renderApify(mountId); else say((r && r.error) || 'could not save'); return; }
            var cm = ev.target.closest('[data-cfmode]');
            if (cm) { bcall('apifySetCfMode', [cm.dataset.cfmode]); renderApify(mountId); return; }
            if (ev.target.id === 'sd-worker-save') { var w = bcall('apifySetWorkerUrl', [$('sd-worker').value.trim()]); if (w && w.ok) renderApify(mountId); else say((w && w.error) || 'could not save'); return; }
            if (ev.target.id === 'sd-account-save') { var a = bcall('apifySetAccount', [$('sd-account').value.trim()]); if (a && a.ok) renderApify(mountId); else say((a && a.error) || 'could not save'); return; }
            if (ev.target.id === 'sd-token-save') { var v = $('sd-token').value.trim(); if (!v) return; var s = bcall('apifySetToken', [cur.id, v]); if (s && s.ok) renderApify(mountId); else say((s && s.error) || 'could not save'); return; }
            if (ev.target.id === 'sd-token-clear') { bcall('apifySetToken', [cur.id, '']); renderApify(mountId); }
        };
    }

    return { load: load, route: route, offered: offered, apifyRun: apifyRun, render: render, renderApify: renderApify, hooks: hooks, isOn: isOn, setOn: setOn };
})();
