/* Events finder, pure logic (no DOM, no bridge): date ranges, city search, category classification, filters,
   request building from data/event-finder.json, the source parsers, robots.txt, prefs, month grid, and the
   orchestration (runFinder) with the fetch injected. UMD so the page loads it with a <script> and
   tests/events-core.test.js requires it; fixtures in tests/fixtures/ drive the parsers.

   An event is { id, source, title, start, end, allDay, venue, city, category, image, url, price, color }:
   start/end are epoch millis (numbers), category is a categories[].id from the data. */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.EventsCore = api;
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';
    var DAY = 86400000;

    function pad(n) { return n < 10 ? '0' + n : '' + n; }
    function startOfDay(ms) { var d = new Date(ms); return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime(); }
    function endOfDay(ms) { var d = new Date(ms); return new Date(d.getFullYear(), d.getMonth(), d.getDate() + 1).getTime() - 1; }
    function addDays(ms, n) { var d = new Date(ms); return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes()).getTime(); }
    function isoSec(ms) { return new Date(ms).toISOString().replace(/\.\d{3}Z$/, 'Z'); }
    function dateKey(ms) { var d = new Date(ms); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
    function parseDateKey(s) { var m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s || ''); return m ? new Date(+m[1], +m[2] - 1, +m[3]).getTime() : NaN; }

    /* ---- When ---------------------------------------------------------------------------------------------- */
    // rule: today | week | weekend | month | custom. cfg = defaults (week_starts_on, weekend{start_day,start_hour}).
    // All ranges start at the start of today at the earliest (stable request keys, so the cache can hit all day).
    function whenRange(rule, now, cfg, custom) {
        cfg = cfg || {};
        var ws = cfg.week_starts_on == null ? 1 : cfg.week_starts_on;
        var wk = cfg.weekend || { start_day: 5, start_hour: 17 };
        var today = startOfDay(now), d = new Date(now), dow = d.getDay();
        if (rule === 'today') return { from: today, to: endOfDay(now) };
        if (rule === 'week') {
            var toEnd = (ws + 6 - dow + 7) % 7;               // days left until the last day of the week
            return { from: today, to: endOfDay(addDays(today, toEnd)) };
        }
        if (rule === 'weekend') {
            var sd = wk.start_day, ed = wk.end_day == null ? 0 : wk.end_day, hrs = (wk.start_hour || 0) * 3600000;
            var len = (ed - sd + 7) % 7, inside = (dow - sd + 7) % 7 <= len;
            var fromDay = inside ? today : addDays(today, (sd - dow + 7) % 7);
            var toDay = inside ? addDays(today, (ed - dow + 7) % 7) : addDays(fromDay, len);
            return { from: new Date(fromDay).getDay() === sd ? fromDay + hrs : fromDay, to: endOfDay(toDay) };
        }
        if (rule === 'month') return { from: today, to: new Date(d.getFullYear(), d.getMonth() + 1, 1).getTime() - 1 };
        if (rule === 'custom') {
            var f = parseDateKey(custom && custom.from), t = parseDateKey(custom && custom.to);
            if (isNaN(f)) f = today;
            if (isNaN(t)) t = f;
            if (t < f) { var x = f; f = t; t = x; }
            return { from: f, to: endOfDay(t) };
        }
        return { from: today, to: endOfDay(now) };
    }

    function rangeFor(whenId, whenRows, now, cfg, custom) {
        var row = null;
        for (var i = 0; i < whenRows.length; i++) if (whenRows[i].id === whenId) row = whenRows[i];
        return whenRange(row ? row.rule : 'week', now, cfg, custom);
    }

    /* ---- text ---------------------------------------------------------------------------------------------- */
    function fold(s) { return String(s == null ? '' : s).normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase(); }
    function slugify(s) { return fold(s).replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, ''); }

    // City picker search: accent-insensitive, name prefix first, then word prefix, then substring of name/country.
    function searchCities(cities, q, limit) {
        var n = fold(q).trim();
        if (!n) return cities.slice(0, limit || cities.length);
        var scored = [];
        cities.forEach(function (c, i) {
            var name = fold(c.name), country = fold(c.countryName), cc = fold(c.country), s = 0;
            if (name === n) s = 100;
            else if (name.indexOf(n) === 0) s = 80;
            else if (name.split(/[\s-]+/).some(function (w) { return w.indexOf(n) === 0; })) s = 60;
            else if (name.indexOf(n) > -1) s = 40;
            else if (country.indexOf(n) === 0 || cc === n) s = 30;
            else if (country.indexOf(n) > -1) s = 20;
            if (s) scored.push({ c: c, s: s, i: i });
        });
        scored.sort(function (a, b) { return b.s - a.s || a.i - b.i; });
        var out = scored.map(function (x) { return x.c; });
        return limit ? out.slice(0, limit) : out;
    }

    function cityById(cities, id) {
        for (var i = 0; i < cities.length; i++) if (cities[i].id === id) return cities[i];
        return null;
    }

    /* ---- categories ---------------------------------------------------------------------------------------- */
    // The hint (a ticketer's own taxonomy, already mapped to a category id) wins; otherwise the first category
    // whose keywords occur in the title/venue; otherwise 'other'.
    function classify(ev, categories) {
        var ids = {};
        categories.forEach(function (c) { ids[c.id] = c; });
        if (ev.categoryHint && ids[ev.categoryHint] && ev.categoryHint !== 'all') return ev.categoryHint;
        var hay = ' ' + fold((ev.title || '') + ' ' + (ev.venue || '') + ' ' + (ev.genre || '')) + ' ';
        for (var i = 0; i < categories.length; i++) {
            var c = categories[i];
            for (var k = 0; k < (c.keywords || []).length; k++) if (hay.indexOf(fold(c.keywords[k])) > -1) return c.id;
        }
        return 'other';
    }

    function categoryParam(categories, categoryId, ticketerId) {
        for (var i = 0; i < categories.length; i++) if (categories[i].id === categoryId) return (categories[i].map || {})[ticketerId] || '';
        return '';
    }

    function dedupe(events) {
        var seen = {}, out = [];
        events.forEach(function (e) {
            var k = fold(e.title).replace(/[^a-z0-9]+/g, '') + '|' + dateKey(e.start) + '|' + fold(e.venue).replace(/[^a-z0-9]+/g, '');
            if (seen[k]) { if (!seen[k].image && e.image) seen[k].image = e.image; return; }
            seen[k] = e; out.push(e);
        });
        return out;
    }

    // f: { category, sources: {id: bool} | null, from, to, skipped: {id: true} | null, q }
    function applyFilters(events, f) {
        var q = f.q ? fold(f.q) : '';
        return events.filter(function (e) {
            if (f.category && f.category !== 'all' && e.category !== f.category) return false;
            if (f.sources && f.sources[e.source] === false) return false;
            if (f.from != null && (e.end || e.start) < f.from) return false;
            if (f.to != null && e.start > f.to) return false;
            if (f.skipped && f.skipped[e.id]) return false;
            if (q && fold(e.title + ' ' + (e.venue || '')).indexOf(q) < 0) return false;
            return true;
        }).sort(function (a, b) { return a.start - b.start || (a.title < b.title ? -1 : 1); });
    }

    function countByCategory(events, categories) {
        var n = { all: events.length };
        categories.forEach(function (c) { if (c.id !== 'all') n[c.id] = 0; });
        events.forEach(function (e) { n[e.category] = (n[e.category] || 0) + 1; });
        return n;
    }

    /* ---- request building ---------------------------------------------------------------------------------- */
    // "{name}" inside a longer string is replaced by the text; a string that is exactly "{name}" takes the raw value
    // (so a number stays a number in a GraphQL body). Unknown placeholders ({key}) are left for EventFetcher.kt.
    function fill(t, vars) {
        if (typeof t === 'string') {
            var whole = /^\{(\w+)\}$/.exec(t);
            if (whole && vars[whole[1]] !== undefined) return vars[whole[1]];
            return t.replace(/\{(\w+)\}/g, function (m, k) { return vars[k] === undefined ? m : String(vars[k]); });
        }
        if (Array.isArray(t)) return t.map(function (x) { return fill(x, vars); });
        if (t && typeof t === 'object') { var o = {}; Object.keys(t).forEach(function (k) { o[k] = fill(t[k], vars); }); return o; }
        return t;
    }

    function covers(ticketer, city) {
        if (ticketer.needs_city_field && city[ticketer.needs_city_field] == null) return false;
        return !ticketer.coverage || ticketer.coverage.indexOf(city.country) > -1;
    }

    function encQuery(q) {
        return Object.keys(q).filter(function (k) { return q[k] !== '' && q[k] != null; }).map(function (k) {
            return encodeURIComponent(k) + '=' + encodeURIComponent(String(q[k])).replace(/%7Bkey%7D/g, '{key}');
        }).join('&');
    }

    function requestVars(city, range, categoryId, ticketer, categories, radiusKm) {
        var tld = ticketer.fetch.tld ? ticketer.fetch.tld[city.country] : '';
        return {
            lat: city.lat, lng: city.lng, radius_km: radiusKm, radius_mi: Math.max(1, Math.round(radiusKm * 0.621371)),
            from_iso: isoSec(range.from), to_iso: isoSec(range.to), from_date: dateKey(range.from), to_date: dateKey(range.to),
            city_slug: city.slug || slugify(city.name), country_slug: slugify(city.countryName), country: city.country.toLowerCase(),
            tld: tld || '', ra_area: city.ra_area, category_param: categoryParam(categories, categoryId, ticketer.id)
        };
    }

    // -> { method, url, headers, body, cache_hours, ticketer } or { skip: reason }
    function buildRequest(ticketer, city, range, categoryId, cfg, categories) {
        var f = ticketer.fetch;
        if (f.kind === 'engine') return { skip: 'engine' };
        if (!covers(ticketer, city)) return { skip: 'not covered in ' + city.name };
        var vars = requestVars(city, range, categoryId, ticketer, categories, cfg.radius_km || 25);
        if (f.kind === 'scrape' && f.tld && !vars.tld) return { skip: 'no site for ' + city.country };
        var url = fill(f.url, vars), q = f.query ? fill(f.query, vars) : null;
        if (q && Object.keys(q).length) url += (url.indexOf('?') > -1 ? '&' : '?') + encQuery(q);
        return { method: f.method || 'GET', url: url, headers: fill(f.headers || {}, vars), body: f.body ? JSON.stringify(fill(f.body, vars)) : '', cache_hours: ticketer.cache_hours };
    }

    /* ---- robots.txt ---------------------------------------------------------------------------------------- */
    // The most specific matching rule wins (longest pattern; Allow beats Disallow on a tie); `*` group only
    // unless the agent group matches. An unreachable/empty robots.txt allows (standard).
    function robotsAllows(txt, path, agent) {
        agent = fold(agent || 'cloudnews');
        var groups = [], cur = null, lastWasAgent = false;
        String(txt || '').split(/\r?\n/).forEach(function (line) {
            line = line.replace(/#.*$/, '').trim();
            var m = /^([A-Za-z-]+)\s*:\s*(.*)$/.exec(line);
            if (!m) return;
            var k = m[1].toLowerCase(), v = m[2].trim();
            if (k === 'user-agent') {
                if (!lastWasAgent) { cur = { agents: [], rules: [] }; groups.push(cur); }
                cur.agents.push(v.toLowerCase()); lastWasAgent = true;
            } else if ((k === 'allow' || k === 'disallow') && cur) {
                lastWasAgent = false;
                if (v) cur.rules.push({ allow: k === 'allow', pat: v });
            } else lastWasAgent = false;
        });
        var pick = groups.filter(function (g) { return g.agents.some(function (a) { return a !== '*' && agent.indexOf(a) > -1; }); });
        if (!pick.length) pick = groups.filter(function (g) { return g.agents.indexOf('*') > -1; });
        var best = null;
        pick.forEach(function (g) {
            g.rules.forEach(function (r) {
                var re = new RegExp('^' + r.pat.replace(/[.+?^${}()|[\]\\]/g, '\\$&').replace(/\*/g, '.*').replace(/\\\$$/, '$'));
                if (re.test(path) && (!best || r.pat.length > best.pat.length || r.pat.length === best.pat.length && r.allow)) best = r;
            });
        });
        return best ? best.allow : true;
    }

    /* ---- parsers ------------------------------------------------------------------------------------------- */
    function num(x) { var n = Number(x); return isFinite(n) ? n : 0; }
    function priceText(min, cur) { return min ? (cur ? cur + ' ' : '') + min : ''; }

    function tmHint(seg, genre) {
        seg = fold(seg); genre = fold(genre);
        if (seg === 'sports') return 'sports';
        if (seg === 'music') return /dance|electronic|techno|house/.test(genre) ? 'raves' : 'music';
        if (seg.indexOf('arts') === 0) return /fine art|museum|photograph/.test(genre) ? 'art' : 'theatre';
        return '';
    }

    function bestImage(images) {
        var best = null;
        (images || []).forEach(function (im) {
            if (!im || !im.url) return;
            var score = (im.ratio === '16_9' ? 1e6 : 0) + num(im.width);
            if (!best || score > best.score) best = { url: im.url, score: score };
        });
        return best ? best.url : '';
    }

    function parseTicketmaster(body, ctx) {
        var j = typeof body === 'string' ? JSON.parse(body) : body;
        var list = (j && j._embedded && j._embedded.events) || [];
        return list.map(function (e) {
            var st = (e.dates && e.dates.start) || {}, start;
            if (st.dateTime) start = Date.parse(st.dateTime);
            else if (st.localDate) { var p = st.localDate.split('-'), t = (st.localTime || '00:00:00').split(':'); start = new Date(+p[0], +p[1] - 1, +p[2], +t[0], +t[1]).getTime(); }
            if (!start) return null;
            var cl = (e.classifications || [])[0] || {}, v = ((e._embedded || {}).venues || [])[0] || {}, pr = (e.priceRanges || [])[0] || {};
            return { id: 'ticketmaster:' + e.id, source: 'ticketmaster', title: e.name, start: start, end: 0, allDay: !!st.noSpecificTime,
                venue: v.name || '', city: (v.city && v.city.name) || '', image: bestImage(e.images), url: e.url || '',
                price: priceText(pr.min, pr.currency), categoryHint: tmHint(cl.segment && cl.segment.name, cl.genre && cl.genre.name), genre: cl.genre && cl.genre.name || '' };
        }).filter(Boolean);
    }

    var SKIDDLE = { LIVE: 'music', FEST: 'music', CLUB: 'raves', COMEDY: 'theatre', THEATRE: 'theatre', ARTS: 'art', SPORT: 'sports', BARS: 'food' };
    function parseSkiddle(body) {
        var j = typeof body === 'string' ? JSON.parse(body) : body;
        return ((j && j.results) || []).map(function (e) {
            var start = Date.parse((e.startdate || e.date) + (/T/.test(e.startdate || '') ? '' : 'T00:00:00'));
            if (!start || isNaN(start)) return null;
            var end = e.enddate ? Date.parse(e.enddate) : 0;
            var v = e.venue || {};
            return { id: 'skiddle:' + e.id, source: 'skiddle', title: e.eventname, start: start, end: end || 0, allDay: !/T/.test(e.startdate || ''),
                venue: v.name || '', city: v.town || '', image: e.largeimageurl || e.imageurl || '', url: e.link || '',
                price: e.entryprice || '', categoryHint: SKIDDLE[e.EventCode] || '', genre: '' };
        }).filter(Boolean);
    }

    function parseRA(body) {
        var j = typeof body === 'string' ? JSON.parse(body) : body;
        var data = (((j || {}).data || {}).eventListings || {}).data || [];
        return data.map(function (row) {
            var e = row.event || {};
            var start = Date.parse(e.startTime || e.date || row.listingDate);
            if (!start || isNaN(start)) return null;
            var v = e.venue || {};
            return { id: 'ra:' + (e.id || row.id), source: 'ra', title: e.title, start: start, end: e.endTime ? Date.parse(e.endTime) : 0, allDay: false,
                venue: v.name || '', city: (v.area && v.area.name) || '', image: e.flyerFront || '', url: e.contentUrl ? 'https://ra.co' + e.contentUrl : '',
                price: '', categoryHint: 'raves', genre: (e.artists || []).map(function (a) { return a.name; }).join(', ') };
        }).filter(Boolean);
    }

    var LD_HINT = { MusicEvent: 'music', TheaterEvent: 'theatre', DanceEvent: 'theatre', ComedyEvent: 'theatre', SportsEvent: 'sports', VisualArtsEvent: 'art', ExhibitionEvent: 'art', FoodEvent: 'food' };
    function ldFirst(x) { return Array.isArray(x) ? x[0] : x; }
    function ldImage(x) { x = ldFirst(x); return !x ? '' : typeof x === 'string' ? x : x.url || ''; }
    function ldWalk(node, out) {
        if (!node) return;
        if (Array.isArray(node)) { node.forEach(function (n) { ldWalk(n, out); }); return; }
        if (typeof node !== 'object') return;
        var t = [].concat(node['@type'] || []);
        if (t.some(function (x) { return /Event$|^Festival$/.test(x); })) out.push(node);
        ['@graph', 'itemListElement', 'item', 'mainEntity', 'subEvent'].forEach(function (k) { if (node[k]) ldWalk(node[k], out); });
    }
    function parseJsonLd(html, ctx) {
        var out = [], re = /<script[^>]*type=["']application\/ld\+json["'][^>]*>([\s\S]*?)<\/script>/gi, m;
        while ((m = re.exec(String(html || '')))) { try { ldWalk(JSON.parse(m[1]), out); } catch (e) { /* one bad block must not drop the page */ } }
        var src = (ctx && ctx.ticketer && ctx.ticketer.id) || 'web';
        return out.map(function (e) {
            if (!e.startDate || !e.name) return null;
            var dateOnly = /^\d{4}-\d{2}-\d{2}$/.test(e.startDate);
            var start = dateOnly ? parseDateKey(e.startDate) : Date.parse(e.startDate);
            if (isNaN(start)) return null;
            var loc = ldFirst(e.location) || {}, adr = loc.address || {}, off = ldFirst(e.offers) || {};
            var types = [].concat(e['@type']), hint = '';
            types.forEach(function (x) { if (LD_HINT[x]) hint = LD_HINT[x]; });
            var url = e.url || off.url || '';
            return { id: src + ':' + (e['@id'] || url || slugify(e.name) + '-' + start), source: src, title: e.name, start: start,
                end: e.endDate && !/^\d{4}-\d{2}-\d{2}$/.test(e.endDate) ? Date.parse(e.endDate) || 0 : 0, allDay: dateOnly,
                venue: loc.name || '', city: typeof adr === 'string' ? adr : adr.addressLocality || '', image: ldImage(e.image), url: url,
                price: priceText(off.price || off.lowPrice, off.priceCurrency), categoryHint: hint, genre: '' };
        }).filter(Boolean);
    }

    // The ICS engine's rows (NewsBridge.events): { id, calendarLabel, color, title, location, start, end, allDay }.
    function parseEngine(rows) {
        return (rows || []).map(function (e) {
            return { id: 'ics:' + e.id, source: 'ics', title: e.title, start: num(e.start), end: num(e.end), allDay: !!e.allDay,
                venue: e.location || '', city: '', image: '', url: '', price: '', categoryHint: '', genre: e.calendarLabel || '', color: e.color || '', engineId: e.id, raw: e };
        }).filter(function (e) { return e.start; });
    }

    var PARSERS = { ticketmaster: parseTicketmaster, skiddle: parseSkiddle, ra: parseRA, jsonld: parseJsonLd, engine: parseEngine };

    /* ---- prefs --------------------------------------------------------------------------------------------- */
    var PREFS_KEY = 'ef.prefs', SKIP_KEY = 'ef.skipped';
    function defaultPrefs(cfg) {
        var s = {};
        cfg.ticketers.forEach(function (t) { s[t.id] = !!t.enabled_default; });
        var d = cfg.defaults;
        return { city: d.city, category: d.category, when: d.when, view: d.view, custom: { from: '', to: '' }, sources: s };
    }
    function loadPrefs(storage, cfg) {
        var p = defaultPrefs(cfg), saved = null;
        try { saved = JSON.parse(storage.getItem(PREFS_KEY) || 'null'); } catch (e) { saved = null; }
        if (!saved) return p;
        if (cityById(cfg.cities, saved.city)) p.city = saved.city;
        if (cfg.categories.some(function (c) { return c.id === saved.category; })) p.category = saved.category;
        if (cfg.when.some(function (w) { return w.id === saved.when; })) p.when = saved.when;
        if (cfg.views.some(function (v) { return v.id === saved.view; })) p.view = saved.view;
        if (saved.custom && typeof saved.custom === 'object') p.custom = { from: String(saved.custom.from || ''), to: String(saved.custom.to || '') };
        Object.keys(p.sources).forEach(function (id) { if (saved.sources && typeof saved.sources[id] === 'boolean') p.sources[id] = saved.sources[id]; });
        return p;
    }
    function savePrefs(storage, prefs) { storage.setItem(PREFS_KEY, JSON.stringify(prefs)); }
    function loadSkipped(storage) { try { return JSON.parse(storage.getItem(SKIP_KEY) || '{}') || {}; } catch (e) { return {}; } }
    function saveSkipped(storage, map) { storage.setItem(SKIP_KEY, JSON.stringify(map)); }

    /* ---- month grid (the same 6 x 7 grid the Saved calendar draws) ----------------------------------------- */
    function monthGrid(y, m, weekStart) {
        weekStart = weekStart || 0;
        var first = new Date(y, m, 1).getDay(), lead = (first - weekStart + 7) % 7, cells = [];
        for (var i = 0; i < 42; i++) {
            var d = new Date(y, m, 1 - lead + i);
            cells.push({ y: d.getFullYear(), m: d.getMonth(), d: d.getDate(), other: d.getMonth() !== m, key: dateKey(d.getTime()) });
        }
        return cells;
    }
    function groupByDay(events) {
        var by = {};
        events.forEach(function (e) { (by[dateKey(e.start)] = by[dateKey(e.start)] || []).push(e); });
        return by;
    }

    /* ---- the run ------------------------------------------------------------------------------------------- */
    // deps: { fetch(ticketerId, req) -> Promise<{ok,status,body,age_ms,error}>, engine(from,to) -> rows, hasKey(id) -> bool, now }
    // Never rejects: a source that fails is a status row, the others still land.
    function runFinder(cfg, prefs, deps) {
        var city = cityById(cfg.cities, prefs.city);
        var range = rangeFor(prefs.when, cfg.when, deps.now, cfg.defaults, prefs.custom);
        var status = {}, all = [];
        var jobs = cfg.ticketers.filter(function (t) { return prefs.sources[t.id]; }).map(function (t) {
            var st = status[t.id] = { state: 'ok', count: 0, message: '', age_ms: 0 };
            function done(rows) {
                var evs = rows.map(function (e) { e.category = classify(e, cfg.categories); if (!e.color) e.color = t.color; return e; });
                st.count = evs.length; all = all.concat(evs);
            }
            function fail(state, msg) { st.state = state; st.message = msg; }
            return Promise.resolve().then(function () {
                if (t.fetch.kind === 'engine') return done(PARSERS.engine(deps.engine(range.from, range.to)));
                var req = buildRequest(t, city, range, 'all', cfg.defaults, cfg.categories);
                if (req.skip) return fail('skipped', req.skip);
                if (t.auth && t.auth.type === 'key' && !deps.hasKey(t.id)) return fail('needs-key', 'Add your ' + t.label + ' key in Configs');
                var pre = Promise.resolve(true);
                if (t.fetch.robots) {
                    var u = new URL(req.url);
                    pre = deps.fetch(t.id, { method: 'GET', url: u.origin + '/robots.txt', headers: { 'User-Agent': (t.fetch.headers || {})['User-Agent'] || '' }, cache_hours: 24, body: '' })
                        .then(function (r) { return !r.ok || robotsAllows(r.body, u.pathname + u.search, 'cloudnews'); });
                }
                return pre.then(function (allowed) {
                    if (!allowed) return fail('blocked', 'robots.txt disallows this page');
                    return deps.fetch(t.id, req).then(function (r) {
                        if (!r.ok) return fail('error', r.error || ('HTTP ' + r.status));
                        st.age_ms = r.age_ms || 0;
                        if (st.age_ms > 0) st.state = 'cached';
                        done(PARSERS[t.fetch.parser](r.body, { ticketer: t, city: city }));
                    });
                });
            }).catch(function (e) { fail('error', (e && e.message) || String(e)); });
        });
        return Promise.all(jobs).then(function () {
            all.forEach(function (e) { if (!e.city) e.city = city.name; });
            return { events: dedupe(all).slice(0, cfg.defaults.max_events || 400), status: status, range: range, city: city };
        });
    }

    return {
        whenRange: whenRange, rangeFor: rangeFor, searchCities: searchCities, cityById: cityById, classify: classify, categoryParam: categoryParam,
        dedupe: dedupe, applyFilters: applyFilters, countByCategory: countByCategory, fill: fill, covers: covers, buildRequest: buildRequest,
        robotsAllows: robotsAllows, PARSERS: PARSERS, parseTicketmaster: parseTicketmaster, parseSkiddle: parseSkiddle, parseRA: parseRA,
        parseJsonLd: parseJsonLd, parseEngine: parseEngine, defaultPrefs: defaultPrefs, loadPrefs: loadPrefs, savePrefs: savePrefs,
        loadSkipped: loadSkipped, saveSkipped: saveSkipped, monthGrid: monthGrid, groupByDay: groupByDay, runFinder: runFinder,
        dateKey: dateKey, parseDateKey: parseDateKey, slugify: slugify, fold: fold, startOfDay: startOfDay, endOfDay: endOfDay
    };
});
