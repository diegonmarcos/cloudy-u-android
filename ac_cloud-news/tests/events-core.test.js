/* Events finder tests — run: node tests/events-core.test.js (the shell tester runs it under two time zones).
 * Covers the date ranges, filters, city search, request building from data/event-finder.json, robots.txt,
 * prefs, the month grid, the source parsers against tests/fixtures/, and runFinder with a fake fetch. */
'use strict';
const fs = require('fs');
const path = require('path');
const assert = require('assert');
const C = require('../app/src/main/assets/events-core.js');

const CFG = JSON.parse(fs.readFileSync(path.join(__dirname, '..', 'data', 'event-finder.json'), 'utf8'));
const fx = (n) => fs.readFileSync(path.join(__dirname, 'fixtures', n), 'utf8');
let passed = 0;
function t(name, fn) { try { fn(); passed++; } catch (e) { console.error('FAIL ' + name + '\n' + (e.stack || e)); process.exitCode = 1; } }
function ta(name, fn) { return fn().then(() => { passed++; }, (e) => { console.error('FAIL ' + name + '\n' + (e.stack || e)); process.exitCode = 1; }); }
const L = (y, m, d, h, mi) => new Date(y, m - 1, d, h || 0, mi || 0, 0, 0).getTime();
const eod = (y, m, d) => new Date(y, m - 1, d + 1).getTime() - 1;

// ---- the data itself --------------------------------------------------------------------------------------
t('data: categories are exactly the declared set, in order', () => {
    assert.deepStrictEqual(CFG.categories.map((c) => c.id), ['all', 'sports', 'raves', 'art', 'music', 'theatre', 'food', 'other']);
});
t('data: when and views', () => {
    assert.deepStrictEqual(CFG.when.map((w) => w.rule), ['today', 'week', 'weekend', 'month', 'custom']);
    assert.deepStrictEqual(CFG.views.map((v) => v.id), ['calendar', 'list', 'cards']);
});
t('data: one row per ticketer, each with a known parser, hosts and terms', () => {
    const ids = CFG.ticketers.map((x) => x.id);
    assert.strictEqual(new Set(ids).size, ids.length);
    ['eventim', 'ra', 'ticketmaster', 'dice', 'eventbrite'].forEach((id) => assert.ok(ids.includes(id), id));
    CFG.ticketers.forEach((x) => {
        assert.ok(['api', 'graphql', 'scrape', 'engine'].includes(x.fetch.kind), x.id);
        assert.ok(C.PARSERS[x.fetch.parser], x.id + ' parser');
        if (x.fetch.kind !== 'engine') { assert.ok(x.fetch.hosts.length, x.id + ' hosts'); assert.ok(x.terms_url, x.id + ' terms'); assert.ok(x.cache_hours > 0); }
        if (x.fetch.kind === 'scrape') { assert.strictEqual(x.fetch.robots, true); assert.strictEqual(x.enabled_default, false, x.id + ' scraper is opt-in'); }
        if (x.fetch.url) assert.ok(x.fetch.hosts.includes(new URL(x.fetch.url.replace(/\{\w+\}/g, 'x')).host) || /\{tld\}/.test(x.fetch.url), x.id + ' url host in hosts');
    });
});
t('data: cities are unique, have coordinates, and the default city exists', () => {
    assert.strictEqual(new Set(CFG.cities.map((c) => c.id)).size, CFG.cities.length);
    CFG.cities.forEach((c) => { assert.ok(isFinite(c.lat) && isFinite(c.lng) && c.country && c.countryName, c.id); });
    assert.ok(C.cityById(CFG.cities, CFG.defaults.city));
});
t('data: scale ramp declared, css has no font-size literal', () => {
    assert.strictEqual(CFG.ramp.scale, 0.7); assert.strictEqual(CFG.ramp.text_scale, 0.78);
    const css = fs.readFileSync(path.join(__dirname, '..', 'app', 'src', 'main', 'assets', 'events-finder.css'), 'utf8');
    assert.ok(!/font-size\s*:\s*[\d.]+(px|rem|em|pt|sp)/.test(css), 'font-size literal');
    assert.ok(!/\b(?!1px|0px)\d+(\.\d+)?px\b/.test(css.replace(/\/\*[\s\S]*?\*\//g, '')), 'px literal other than a hairline');
});

// ---- when -------------------------------------------------------------------------------------------------
const WK = CFG.defaults;
t('when: today', () => {
    const r = C.whenRange('today', L(2026, 11, 4, 10), WK);
    assert.strictEqual(r.from, L(2026, 11, 4)); assert.strictEqual(r.to, eod(2026, 11, 4));
});
t('when: this week runs to Sunday (Monday start), from today', () => {
    const r = C.whenRange('week', L(2026, 11, 4, 10), WK);
    assert.strictEqual(r.from, L(2026, 11, 4)); assert.strictEqual(r.to, eod(2026, 11, 8));
});
t('when: this week on a Sunday is just today; on Monday it is 7 days', () => {
    assert.strictEqual(C.whenRange('week', L(2026, 11, 8, 9), WK).to, eod(2026, 11, 8));
    assert.strictEqual(C.whenRange('week', L(2026, 11, 2, 9), WK).to, eod(2026, 11, 8));
});
t('when: week with Sunday as first day', () => {
    assert.strictEqual(C.whenRange('week', L(2026, 11, 4, 10), { week_starts_on: 0 }).to, eod(2026, 11, 7));
});
t('when: weekend from a weekday is Fri 17:00 to Sun end', () => {
    const r = C.whenRange('weekend', L(2026, 11, 4, 10), WK);
    assert.strictEqual(r.from, L(2026, 11, 6, 17)); assert.strictEqual(r.to, eod(2026, 11, 8));
});
t('when: weekend on Friday morning starts 17:00 today; on Saturday starts at midnight; on Sunday is today', () => {
    assert.strictEqual(C.whenRange('weekend', L(2026, 11, 6, 9), WK).from, L(2026, 11, 6, 17));
    const sat = C.whenRange('weekend', L(2026, 11, 7, 12), WK);
    assert.strictEqual(sat.from, L(2026, 11, 7)); assert.strictEqual(sat.to, eod(2026, 11, 8));
    const sun = C.whenRange('weekend', L(2026, 11, 8, 12), WK);
    assert.strictEqual(sun.from, L(2026, 11, 8)); assert.strictEqual(sun.to, eod(2026, 11, 8));
});
t('when: month is today to the last day', () => {
    const r = C.whenRange('month', L(2026, 11, 4, 10), WK);
    assert.strictEqual(r.from, L(2026, 11, 4)); assert.strictEqual(r.to, eod(2026, 11, 30));
    assert.strictEqual(C.whenRange('month', L(2026, 2, 10, 10), WK).to, eod(2026, 2, 28));
    assert.strictEqual(C.whenRange('month', L(2026, 12, 31, 10), WK).to, eod(2026, 12, 31));
});
t('when: custom range, reversed range swaps, missing end is one day, bad input falls back to today', () => {
    const now = L(2026, 11, 4, 10);
    let r = C.whenRange('custom', now, WK, { from: '2026-12-20', to: '2027-01-03' });
    assert.strictEqual(r.from, L(2026, 12, 20)); assert.strictEqual(r.to, eod(2027, 1, 3));
    r = C.whenRange('custom', now, WK, { from: '2027-01-03', to: '2026-12-20' });
    assert.strictEqual(r.from, L(2026, 12, 20)); assert.strictEqual(r.to, eod(2027, 1, 3));
    r = C.whenRange('custom', now, WK, { from: '2026-12-20', to: '' });
    assert.strictEqual(r.to, eod(2026, 12, 20));
    r = C.whenRange('custom', now, WK, { from: 'nope', to: '' });
    assert.strictEqual(r.from, L(2026, 11, 4));
});
t('when: rangeFor resolves a declared id; an unknown id is the default week-like fallback', () => {
    assert.strictEqual(C.rangeFor('weekend', CFG.when, L(2026, 11, 4, 10), WK).from, L(2026, 11, 6, 17));
    assert.ok(C.rangeFor('zzz', CFG.when, L(2026, 11, 4, 10), WK).to > 0);
});
t('when: range from is stable across the day (cache keys)', () => {
    assert.strictEqual(C.whenRange('week', L(2026, 11, 4, 1), WK).from, C.whenRange('week', L(2026, 11, 4, 23), WK).from);
});

// ---- city search ------------------------------------------------------------------------------------------
t('city: prefix beats substring, accents fold, country matches', () => {
    const names = (q) => C.searchCities(CFG.cities, q).map((c) => c.name);
    assert.strictEqual(names('ber')[0], 'Berlin');
    assert.strictEqual(names('sao')[0], 'Sao Paulo');
    assert.strictEqual(names('SAO PAULO')[0], 'Sao Paulo');
    assert.strictEqual(names('Düsseldorf')[0], 'Dusseldorf');
    assert.ok(names('germany').includes('Munich') && names('germany').length >= 6);
    assert.ok(names('york').includes('New York'));
    assert.deepStrictEqual(names('zzzz'), []);
    assert.strictEqual(C.searchCities(CFG.cities, '', 5).length, 5);
});

// ---- categories + filters ---------------------------------------------------------------------------------
t('classify: hint wins, then keywords, then other', () => {
    assert.strictEqual(C.classify({ title: 'Anything', categoryHint: 'sports' }, CFG.categories), 'sports');
    assert.strictEqual(C.classify({ title: 'Techno Rave at the Warehouse' }, CFG.categories), 'raves');
    assert.strictEqual(C.classify({ title: 'Picasso Exhibition' }, CFG.categories), 'art');
    assert.strictEqual(C.classify({ title: 'Hamlet', genre: 'theatre' }, CFG.categories), 'theatre');
    assert.strictEqual(C.classify({ title: 'Wine tasting night' }, CFG.categories), 'food');
    assert.strictEqual(C.classify({ title: 'Quarterly something' }, CFG.categories), 'other');
    assert.strictEqual(C.classify({ title: 'x', categoryHint: 'bogus' }, CFG.categories), 'other');
});
const EV = [
    { id: 'a', source: 'ra', title: 'Klub', start: L(2026, 11, 6, 23), end: 0, category: 'raves', venue: 'Berghain' },
    { id: 'b', source: 'ticketmaster', title: 'Match', start: L(2026, 11, 7, 15), end: 0, category: 'sports', venue: 'Stadion' },
    { id: 'c', source: 'ticketmaster', title: 'Concert', start: L(2026, 11, 5, 20), end: 0, category: 'music', venue: 'Hall' },
    { id: 'd', source: 'ics', title: 'Moon', start: L(2026, 12, 1, 3), end: 0, category: 'other', venue: '' }
];
t('filters: category, ticketer toggle, range, skipped, text, sort', () => {
    assert.deepStrictEqual(C.applyFilters(EV, {}).map((e) => e.id), ['c', 'a', 'b', 'd']);
    assert.deepStrictEqual(C.applyFilters(EV, { category: 'raves' }).map((e) => e.id), ['a']);
    assert.strictEqual(C.applyFilters(EV, { category: 'all' }).length, 4);
    assert.deepStrictEqual(C.applyFilters(EV, { sources: { ticketmaster: false } }).map((e) => e.id), ['a', 'd']);
    assert.deepStrictEqual(C.applyFilters(EV, { sources: { ra: true, ticketmaster: false } }).map((e) => e.id), ['a', 'd']);
    const r = C.whenRange('week', L(2026, 11, 4, 10), WK);
    assert.deepStrictEqual(C.applyFilters(EV, { from: r.from, to: r.to }).map((e) => e.id), ['c', 'a', 'b']);
    assert.deepStrictEqual(C.applyFilters(EV, { skipped: { a: true } }).map((e) => e.id), ['c', 'b', 'd']);
    assert.deepStrictEqual(C.applyFilters(EV, { q: 'BERGHAIN' }).map((e) => e.id), ['a']);
    assert.deepStrictEqual(C.applyFilters(EV, { category: 'sports', sources: { ticketmaster: false } }), []);
});
t('filters: an event still running at the range start is kept', () => {
    const e = [{ id: 'x', source: 'ra', title: 'Long', start: L(2026, 11, 3), end: L(2026, 11, 5), category: 'art' }];
    assert.strictEqual(C.applyFilters(e, { from: L(2026, 11, 4), to: L(2026, 11, 9) }).length, 1);
});
t('counts and dedupe', () => {
    assert.deepStrictEqual(C.countByCategory(EV, CFG.categories), { all: 4, sports: 1, raves: 1, art: 0, music: 1, theatre: 0, food: 0, other: 1 });
    const d = C.dedupe([{ title: 'Klub!', start: L(2026, 11, 6, 23), venue: 'Berghain', image: '' }, { title: 'klub', start: L(2026, 11, 6, 22), venue: 'BERGHAIN', image: 'i.jpg' }, { title: 'Klub', start: L(2026, 11, 7, 23), venue: 'Berghain' }]);
    assert.strictEqual(d.length, 2); assert.strictEqual(d[0].image, 'i.jpg');
});

// ---- request building -------------------------------------------------------------------------------------
const TK = CFG.ticketers.find((x) => x.id === 'ticketmaster');
const BER = C.cityById(CFG.cities, 'berlin');
const RANGE = { from: L(2026, 11, 4), to: eod(2026, 11, 8) };
t('request: ticketmaster keeps {key} for the native side and omits an empty category', () => {
    const q = C.buildRequest(TK, BER, RANGE, 'all', WK, CFG.categories);
    assert.ok(q.url.startsWith('https://app.ticketmaster.com/discovery/v2/events.json?'));
    assert.ok(q.url.includes('apikey={key}'), q.url);
    assert.ok(q.url.includes('latlong=52.52%2C13.405') && q.url.includes('radius=25') && q.url.includes('unit=km'));
    assert.ok(!q.url.includes('classificationName'));
    assert.ok(/startDateTime=\d{4}-\d{2}-\d{2}T\d{2}%3A\d{2}%3A\d{2}Z/.test(q.url));
    assert.strictEqual(q.method, 'GET'); assert.strictEqual(q.cache_hours, TK.cache_hours);
    assert.ok(C.buildRequest(TK, BER, RANGE, 'sports', WK, CFG.categories).url.includes('classificationName=Sports'));
});
t('request: coverage skips (skiddle is UK only; ra needs an area)', () => {
    const sk = CFG.ticketers.find((x) => x.id === 'skiddle'), ra = CFG.ticketers.find((x) => x.id === 'ra');
    assert.ok(C.buildRequest(sk, BER, RANGE, 'all', WK, CFG.categories).skip);
    assert.ok(!C.buildRequest(sk, C.cityById(CFG.cities, 'london'), RANGE, 'all', WK, CFG.categories).skip);
    assert.ok(C.buildRequest(ra, C.cityById(CFG.cities, 'lisbon'), RANGE, 'all', WK, CFG.categories).skip);
});
t('request: RA body keeps the area id a number', () => {
    const ra = CFG.ticketers.find((x) => x.id === 'ra');
    const q = C.buildRequest(ra, BER, RANGE, 'all', WK, CFG.categories);
    const b = JSON.parse(q.body);
    assert.strictEqual(b.variables.filters.areas.eq, 34);
    assert.ok(/^\d{4}-\d{2}-\d{2}T.*Z$/.test(b.variables.filters.listingDate.gte));
    assert.strictEqual(q.method, 'POST');
});
t('request: scrape urls (country slug, tld) and no site outside the declared tlds', () => {
    const get = (id, city) => C.buildRequest(CFG.ticketers.find((x) => x.id === id), C.cityById(CFG.cities, city), RANGE, 'all', WK, CFG.categories);
    assert.strictEqual(get('eventbrite', 'new-york').url, 'https://www.eventbrite.com/d/united-states--new-york/events/');
    assert.strictEqual(get('dice', 'london').url, 'https://dice.fm/browse/london');
    assert.strictEqual(get('eventim', 'berlin').url, 'https://www.eventim.de/city/berlin/');
    assert.ok(get('eventim', 'paris').skip);
    assert.ok(/User-Agent/.test(Object.keys(get('dice', 'london').headers).join()));
});
t('fill: exact placeholder keeps the type, embedded one stringifies, unknown stays', () => {
    assert.deepStrictEqual(C.fill({ a: '{n}', b: 'x{n}y', c: '{key}' }, { n: 5 }), { a: 5, b: 'x5y', c: '{key}' });
});

// ---- robots -----------------------------------------------------------------------------------------------
t('robots: longest match wins, Allow beats Disallow on a tie, bad bot is not us', () => {
    const r = fx('robots.txt');
    assert.strictEqual(C.robotsAllows(r, '/private/x'), false);
    assert.strictEqual(C.robotsAllows(r, '/d/london/events'), false);
    assert.strictEqual(C.robotsAllows(r, '/d/germany--berlin/events/'), true);
    assert.strictEqual(C.robotsAllows(r, '/other'), true);
    assert.strictEqual(C.robotsAllows(r, '/anything', 'badbot'), false);
    assert.strictEqual(C.robotsAllows('', '/x'), true);
    assert.strictEqual(C.robotsAllows('User-agent: *\nDisallow: /', '/x'), false);
    assert.strictEqual(C.robotsAllows('User-agent: *\nDisallow: /*.json$', '/a.json'), false);
    assert.strictEqual(C.robotsAllows('User-agent: *\nDisallow: /*.json$', '/a.json5'), true);
});

// ---- parsers against fixtures -----------------------------------------------------------------------------
t('parser: ticketmaster', () => {
    const ev = C.parseTicketmaster(fx('ticketmaster.json'));
    assert.strictEqual(ev.length, 2);
    assert.deepStrictEqual([ev[0].id, ev[0].title, ev[0].venue, ev[0].city, ev[0].price, ev[0].categoryHint], ['ticketmaster:Z1', 'Hertha BSC vs Union Berlin', 'Olympiastadion', 'Berlin', 'EUR 25', 'sports']);
    assert.strictEqual(ev[0].start, Date.parse('2026-11-07T14:30:00Z'));
    assert.strictEqual(ev[0].image, 'https://img/a_16_9.jpg');
    assert.strictEqual(ev[1].allDay, true); assert.strictEqual(ev[1].categoryHint, 'raves'); assert.strictEqual(ev[1].start, L(2026, 11, 8));
    assert.deepStrictEqual(C.parseTicketmaster('{}'), []);
});
t('parser: skiddle', () => {
    const ev = C.parseSkiddle(fx('skiddle.json'));
    assert.strictEqual(ev.length, 2);
    assert.strictEqual(ev[0].categoryHint, 'raves'); assert.strictEqual(ev[0].image, 'https://s/l.jpg'); assert.strictEqual(ev[0].venue, 'Depot');
    assert.strictEqual(ev[0].start, L(2026, 11, 6, 22)); assert.strictEqual(ev[0].end, L(2026, 11, 7, 5));
    assert.strictEqual(ev[1].allDay, true); assert.strictEqual(ev[1].categoryHint, 'theatre');
});
t('parser: ra', () => {
    const ev = C.parseRA(fx('ra.json'));
    assert.strictEqual(ev.length, 2);
    assert.deepStrictEqual([ev[0].title, ev[0].venue, ev[0].url, ev[0].categoryHint, ev[0].genre], ['Klubnacht', 'Berghain', 'https://ra.co/events/900', 'raves', 'A, B']);
    assert.strictEqual(ev[1].start, Date.parse('2026-11-07T00:00:00.000'));
    assert.strictEqual(ev[1].image, '');
});
t('parser: json-ld (ItemList, graph, entity-typed hints, bad block skipped)', () => {
    const ev = C.parseJsonLd(fx('jsonld.html'), { ticketer: { id: 'dice' } });
    assert.deepStrictEqual(ev.map((e) => e.title), ['Jazz at Cafe', 'Street Food Market', 'Hamlet']);
    assert.strictEqual(ev[0].source, 'dice'); assert.strictEqual(ev[0].categoryHint, 'music'); assert.strictEqual(ev[0].price, 'EUR 15');
    assert.strictEqual(ev[0].venue, 'Cafe'); assert.strictEqual(ev[0].city, 'Berlin'); assert.strictEqual(ev[0].image, 'https://x.test/1.jpg');
    assert.strictEqual(ev[0].start, Date.parse('2026-11-06T20:00:00+01:00')); assert.strictEqual(ev[0].end, Date.parse('2026-11-06T23:00:00+01:00'));
    assert.strictEqual(ev[1].allDay, true); assert.strictEqual(ev[1].start, L(2026, 11, 7)); assert.strictEqual(ev[1].image, 'https://x.test/2.jpg'); assert.strictEqual(ev[1].city, 'Berlin');
    assert.strictEqual(ev[2].categoryHint, 'theatre');
    assert.deepStrictEqual(C.parseJsonLd('<html>nothing</html>', {}), []);
});
t('parser: engine rows keep the engine id and colour', () => {
    const ev = C.parseEngine([{ id: 'u1', title: 'Holiday', start: '1000', end: '2000', allDay: true, color: '#fff', calendarLabel: 'DE' }, { id: 'u2', title: 'no start' }]);
    assert.strictEqual(ev.length, 1); assert.strictEqual(ev[0].id, 'ics:u1'); assert.strictEqual(ev[0].engineId, 'u1'); assert.strictEqual(ev[0].start, 1000);
});

// ---- prefs ------------------------------------------------------------------------------------------------
function mem() { const m = {}; return { getItem: (k) => (k in m ? m[k] : null), setItem: (k, v) => { m[k] = String(v); }, m }; }
t('prefs: defaults, round trip, and a corrupt or stale value falls back', () => {
    const s = mem(), d = C.loadPrefs(s, CFG);
    assert.strictEqual(d.city, 'berlin'); assert.strictEqual(d.sources.ticketmaster, true); assert.strictEqual(d.sources.ra, false);
    d.city = 'lisbon'; d.category = 'art'; d.when = 'custom'; d.view = 'cards'; d.custom = { from: '2026-12-01', to: '2026-12-05' }; d.sources.ra = true;
    C.savePrefs(s, d);
    assert.deepStrictEqual(C.loadPrefs(s, CFG), d);
    s.setItem('ef.prefs', JSON.stringify({ city: 'atlantis', category: 'zzz', when: 'never', view: 'x', sources: { ra: 'yes', gone: true } }));
    const f = C.loadPrefs(s, CFG);
    assert.strictEqual(f.city, 'berlin'); assert.strictEqual(f.category, 'all'); assert.strictEqual(f.sources.ra, false); assert.ok(!('gone' in f.sources));
    s.setItem('ef.prefs', '{not json');
    assert.strictEqual(C.loadPrefs(s, CFG).city, 'berlin');
    C.saveSkipped(s, { a: true }); assert.deepStrictEqual(C.loadSkipped(s), { a: true });
    s.setItem('ef.skipped', 'zz'); assert.deepStrictEqual(C.loadSkipped(s), {});
});

// ---- month grid -------------------------------------------------------------------------------------------
t('month grid: 42 cells, Sunday or Monday first, other-month flags', () => {
    const g = C.monthGrid(2026, 10, 0);   // November 2026 starts on a Sunday
    assert.strictEqual(g.length, 42); assert.strictEqual(g[0].key, '2026-11-01'); assert.strictEqual(g[0].other, false);
    const mo = C.monthGrid(2026, 10, 1);
    assert.strictEqual(mo[0].key, '2026-10-26'); assert.strictEqual(mo[0].other, true); assert.strictEqual(mo[6].key, '2026-11-01');
    assert.strictEqual(C.groupByDay(EV)['2026-11-06'].length, 1);
});

// ---- runFinder --------------------------------------------------------------------------------------------
const NOW = L(2026, 11, 4, 10);
function deps(over) {
    const calls = [];
    return Object.assign({ now: NOW, calls,
        hasKey: (id) => id === 'ticketmaster',
        engine: () => [{ id: 'h1', title: 'Public holiday', start: String(L(2026, 11, 5)), end: String(L(2026, 11, 5, 23)), allDay: true, color: '#f00' }],
        fetch: (id, req) => { calls.push(id + ' ' + req.url); return Promise.resolve(id === 'ticketmaster' ? { ok: true, status: 200, body: fx('ticketmaster.json'), age_ms: 0 } : { ok: false, status: 500, error: 'down' }); } }, over);
}
(async () => {
    await ta('run: ticketmaster parsed + classified, engine merged, skiddle skipped, ra off', async () => {
        const prefs = C.defaultPrefs(CFG), d = deps();
        const r = await C.runFinder(CFG, prefs, d);
        assert.strictEqual(r.status.ticketmaster.state, 'ok'); assert.strictEqual(r.status.ticketmaster.count, 2);
        assert.strictEqual(r.status.skiddle.state, 'skipped'); assert.ok(!r.status.ra);
        assert.strictEqual(r.status.ics.count, 1);
        assert.strictEqual(r.events.filter((e) => e.source === 'ticketmaster')[0].category, 'sports');
        assert.strictEqual(r.events.find((e) => e.source === 'ics').color, '#f00');
        assert.ok(r.events.every((e) => e.city));
        assert.deepStrictEqual(d.calls.length, 1);
    });
    await ta('run: no key is a status row, not a request', async () => {
        const d = deps({ hasKey: () => false });
        const r = await C.runFinder(CFG, C.defaultPrefs(CFG), d);
        assert.strictEqual(r.status.ticketmaster.state, 'needs-key'); assert.strictEqual(d.calls.length, 0);
    });
    await ta('run: a failing source does not drop the others; cached age is reported', async () => {
        const p = C.defaultPrefs(CFG); p.city = 'london';
        const d = deps({ hasKey: () => true, fetch: (id) => Promise.resolve(id === 'skiddle' ? { ok: false, status: 503, error: 'HTTP 503' } : { ok: true, status: 200, body: fx('ticketmaster.json'), age_ms: 5000 }) });
        const r = await C.runFinder(CFG, p, d);
        assert.strictEqual(r.status.skiddle.state, 'error'); assert.strictEqual(r.status.skiddle.message, 'HTTP 503');
        assert.strictEqual(r.status.ticketmaster.state, 'cached'); assert.strictEqual(r.status.ticketmaster.age_ms, 5000);
        assert.ok(r.events.length >= 2);
    });
    await ta('run: scraper is blocked by robots.txt, and fetched when allowed; parse failure is an error row', async () => {
        const p = C.defaultPrefs(CFG); p.sources = { dice: true }; p.city = 'london';
        const seen = [];
        let r = await C.runFinder(CFG, p, deps({ fetch: (id, req) => { seen.push(req.url); return Promise.resolve({ ok: true, body: /robots/.test(req.url) ? 'User-agent: *\nDisallow: /browse' : fx('jsonld.html') }); } }));
        assert.strictEqual(r.status.dice.state, 'blocked'); assert.deepStrictEqual(seen, ['https://dice.fm/robots.txt']);
        r = await C.runFinder(CFG, p, deps({ fetch: (id, req) => Promise.resolve({ ok: true, body: /robots/.test(req.url) ? '' : fx('jsonld.html') }) }));
        assert.strictEqual(r.status.dice.count, 3); assert.ok(r.events.every((e) => e.source === 'dice'));
        p.sources = { ticketmaster: true };
        r = await C.runFinder(CFG, p, deps({ fetch: () => Promise.resolve({ ok: true, body: 'not json' }) }));
        assert.strictEqual(r.status.ticketmaster.state, 'error');
    });
    console.log(process.exitCode ? 'FAILED' : 'all ' + passed + ' passing');
})();
