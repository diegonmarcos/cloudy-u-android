// #1470 the source-declaration parser and the method routing (node, no deps): node tests/sources-decl.test.js
const assert = require('assert');
const fs = require('fs');
const path = require('path');
const SD = require('../app/src/main/assets/sources-decl.js');
const C = require('../app/src/main/assets/events-core.js');
const real = JSON.parse(fs.readFileSync(path.join(__dirname, '../data/source-decl.json'), 'utf8'));
let n = 0;
function t(name, fn) { fn(); n++; }

const ok = (rows) => SD.parse({ sources: rows });
const row = (o) => Object.assign({ id: 'a', area: 'news', method: 'direct' }, o);

t('the shipped declaration parses with no error', () => { const d = SD.parse(real); assert.deepStrictEqual(d.errors, []); assert.ok(d.sources.length > 15); });
t('every area has both subsections declared where the owner asked', () => {
    const d = SD.parse(real);
    assert.ok(SD.bySection(d, 'media').direct.some((s) => s.id === 'youtube'));
    assert.strictEqual(SD.bySection(d, 'events').direct.length, 1, 'only the on-phone ICS feeds are direct');
    assert.deepStrictEqual(SD.bySection(d, 'events').apify.map((s) => s.id), ['ticketmaster', 'skiddle', 'ra', 'eventbrite', 'dice', 'eventim']);
    assert.ok(SD.bySection(d, 'news').direct.length >= 10);
    assert.ok(SD.bySection(d, 'news').direct.length > SD.bySection(d, 'news').apify.length);
});
t('every apify row has an actor and every ref of the events area is a real ticketer', () => {
    const d = SD.parse(real), cfg = JSON.parse(fs.readFileSync(path.join(__dirname, '../data/event-finder.json'), 'utf8'));
    d.sources.filter((s) => s.method === 'apify').forEach((s) => assert.ok(SD.ACTOR.test(s.actor), s.id));
    d.sources.filter((s) => s.area === 'events').forEach((s) => assert.ok(cfg.ticketers.some((x) => x.id === s.id), s.id));
});
t('the default backend is Apify Cloud, self-hosted and Cloudflare Browser Run are declared and opt-in', () => {
    assert.strictEqual(real.apify.default_backend, 'cloud');
    assert.deepStrictEqual(real.apify.backends.map((b) => b.id), ['cloud', 'selfhosted', 'cloudflare', 'phone', 'headless']);
    assert.ok(/^https:\/\//.test(real.apify.backends[1].default_url));
});
t('bad rows are errors and dropped', () => {
    const d = ok([row({ id: '' }), row({ area: 'tv' }), row({ method: 'scrape' }), row({ method: 'apify' }), row({ method: 'apify', actor: 'noslash' }),
        row({ id: 'g' }), row({ id: 'g' }), row({ id: 'ok', method: 'apify', actor: 'u/n' })]);
    assert.strictEqual(d.errors.length, 6);
    assert.deepStrictEqual(d.sources.map((s) => s.id), ['g', 'ok']);
});
t('garbage input never throws', () => { assert.ok(SD.parse('{{').errors.length); assert.ok(SD.parse({}).errors.length); assert.ok(SD.parse(null).errors.length); });
t('enabled defaults to true, false hides', () => {
    const d = ok([row({ id: 'a' }), row({ id: 'b', enabled: false })]);
    assert.deepStrictEqual(SD.enabledIds(d, 'news'), ['a']);
});
t('routing: direct, apify with declared actor, unknown, disabled', () => {
    const d = ok([row({ id: 'd' }), row({ id: 'p', area: 'events', method: 'apify', actor: 'x/y', input: { q: '{city_slug}' } }), row({ id: 'off', enabled: false })]);
    assert.strictEqual(SD.route(d, 'news', 'd').method, 'direct');
    const r = SD.route(d, 'events', 'p');
    assert.strictEqual(r.method, 'apify'); assert.strictEqual(r.actor, 'x/y'); assert.deepStrictEqual(r.input, { q: '{city_slug}' });
    assert.strictEqual(SD.route(d, 'news', 'nope').skip, 'not declared');
    assert.strictEqual(SD.route(d, 'events', 'd').skip, 'not declared', 'area matters');
    assert.strictEqual(SD.route(d, 'news', 'off').skip, 'disabled');
});
t('routing: user switch cannot enable a source the declaration disabled; actor override must be user/name', () => {
    const d = ok([row({ id: 'off', enabled: false }), row({ id: 'p', method: 'apify', actor: 'x/y' }), row({ id: 'q' })]);
    assert.strictEqual(SD.route(d, 'news', 'off', { enabled: { off: true } }).skip, 'disabled');
    assert.strictEqual(SD.route(d, 'news', 'q', { enabled: { q: false } }).skip, 'disabled');
    assert.strictEqual(SD.route(d, 'news', 'p', { actors: { p: 'me/mine' } }).actor, 'me/mine');
    assert.strictEqual(SD.route(d, 'news', 'p', { actors: { p: 'bad actor' } }).actor, 'x/y');
});
t('the declared Apify rows build inputs the events core can fill', () => {
    const d = SD.parse(real), city = C.cityById(JSON.parse(fs.readFileSync(path.join(__dirname, '../data/event-finder.json'), 'utf8')).cities, 'berlin');
    SD.bySection(d, 'events').apify.forEach((s) => {
        const u = JSON.stringify(C.fill(s.input, { city_slug: C.slugify(city.name), country_slug: 'germany', country: 'de', tld: 'de' }));
        assert.ok(!/\{\w+\}/.test(u), s.id + ' leaves a placeholder: ' + u);
    });
});

// the events core takes the route: an apify route calls deps.apify with the declared actor, direct goes through deps.fetch
const cfg = JSON.parse(fs.readFileSync(path.join(__dirname, '../data/event-finder.json'), 'utf8'));
const d = SD.parse(real);
const prefs = C.defaultPrefs(cfg); Object.keys(prefs.sources).forEach((k) => { prefs.sources[k] = false; }); prefs.sources.eventbrite = true; prefs.sources.ics = true;
const calls = [];
const deps = {
    now: Date.UTC(2026, 10, 4, 10), route: (id) => SD.route(d, 'events', id), hasKey: () => false, engine: () => [],
    fetch: (id, req) => { calls.push(['fetch', id]); return Promise.resolve({ ok: false, error: 'no network in tests' }); },
    apify: (id, actor, input) => { calls.push(['apify', id, actor, JSON.stringify(input)]); return Promise.resolve({ ok: true, items: [{ name: 'Night', url: 'https://x.test/e/1', startDate: '2026-11-05T20:00:00+01:00', venue: { name: 'Hall' }, image: 'https://x.test/i.jpg' }], age_ms: 0 }); },
};
C.runFinder(cfg, prefs, deps).then((res) => {
    assert.deepStrictEqual(calls.map((c) => c[0] + ':' + c[1]), ['apify:eventbrite']);
    assert.strictEqual(calls[0][2], 'aitorsm/eventbrite');
    assert.ok(/eventbrite\.com\/d\/germany--berlin\/events/.test(calls[0][3]), calls[0][3]);
    assert.strictEqual(res.status.eventbrite.count, 1);
    assert.strictEqual(res.events.filter((e) => e.source === 'eventbrite').length, 1);
    n++;
    // a source flipped to direct goes the old way
    const d2 = SD.parse(real); d2.sources.find((s) => s.id === 'eventbrite').method = 'direct';
    calls.length = 0;
    return C.runFinder(cfg, prefs, Object.assign({}, deps, { route: (id) => SD.route(d2, 'events', id) }));
}).then(() => {
    assert.ok(calls.some((c) => c[0] === 'fetch' && c[1] === 'eventbrite'), JSON.stringify(calls)); n++;
    console.log('sources-decl: ' + n + ' checks ok');
}).catch((e) => { console.error(e); process.exit(1); });
