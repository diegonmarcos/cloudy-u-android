/* #1470 Cloud News: the parser and the method routing of data/source-decl.json (UMD: the page and the node tests share it).
   parse(json)            -> { apify, sources, errors } ; a bad row is an error and is dropped, never half-used
   bySection(decl, area)  -> { apify: [rows], direct: [rows] } in declared order (what Configs > Media | Events | News list)
   route(decl, area, id, prefs) -> { method, actor, input, source } or { skip: reason }
       prefs = { enabled: { id: bool }, actors: { id: 'user/name' } }  (the user's switches and actor overrides)
   Everything is data: no source is named here. */
(function (root, factory) {
    var api = factory();
    if (typeof module === 'object' && module.exports) module.exports = api;
    else root.SourcesDecl = api;
})(typeof self !== 'undefined' ? self : this, function () {
    'use strict';
    var AREAS = ['media', 'events', 'news'], METHODS = ['apify', 'direct'];
    var ACTOR = /^[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+$/;

    function parse(json) {
        var errors = [], out = { apify: {}, sources: [], errors: errors }, raw;
        try { raw = typeof json === 'string' ? JSON.parse(json) : json; } catch (e) { errors.push('not JSON'); return out; }
        if (!raw || !Array.isArray(raw.sources)) { errors.push('no sources[]'); return out; }
        out.apify = raw.apify || {};
        var seen = {};
        raw.sources.forEach(function (s, i) {
            var where = 'sources[' + i + ']' + (s && s.id ? ' ' + s.id : '');
            if (!s || typeof s.id !== 'string' || !s.id) return errors.push(where + ': no id');
            if (AREAS.indexOf(s.area) < 0) return errors.push(where + ': area ' + s.area + ' is not ' + AREAS.join('|'));
            if (METHODS.indexOf(s.method) < 0) return errors.push(where + ': method ' + s.method + ' is not ' + METHODS.join('|'));
            if (s.method === 'apify' && !ACTOR.test(s.actor || '')) return errors.push(where + ': an apify source needs an actor id user/name');
            var key = s.area + ':' + s.id;
            if (seen[key]) return errors.push(where + ': declared twice in ' + s.area);
            seen[key] = 1;
            out.sources.push({ id: s.id, area: s.area, label: s.label || s.id, method: s.method, actor: s.actor || '', enabled: s.enabled !== false,
                how: s.how || '', input: s.input || null, ref: s.ref || '' });
        });
        return out;
    }

    function bySection(decl, area) {
        var o = { apify: [], direct: [] };
        decl.sources.forEach(function (s) { if (s.area === area) o[s.method].push(s); });
        return o;
    }

    function find(decl, area, id) {
        for (var i = 0; i < decl.sources.length; i++) if (decl.sources[i].area === area && decl.sources[i].id === id) return decl.sources[i];
        return null;
    }

    function route(decl, area, id, prefs) {
        prefs = prefs || {};
        var s = find(decl, area, id);
        if (!s) return { skip: 'not declared' };
        var on = prefs.enabled && prefs.enabled[id] !== undefined ? !!prefs.enabled[id] : s.enabled;
        if (!s.enabled || !on) return { skip: 'disabled' };
        if (s.method === 'direct') return { method: 'direct', source: s };
        var actor = prefs.actors && ACTOR.test(prefs.actors[id] || '') ? prefs.actors[id] : s.actor;
        return { method: 'apify', actor: actor, input: s.input, source: s };
    }

    /* the ids a picker may offer: declared, enabled, of that area (and method, when given) */
    function enabledIds(decl, area, method) {
        return decl.sources.filter(function (s) { return s.area === area && s.enabled && (!method || s.method === method); }).map(function (s) { return s.id; });
    }

    return { parse: parse, bySection: bySection, find: find, route: route, enabledIds: enabledIds, ACTOR: ACTOR, AREAS: AREAS, METHODS: METHODS };
});
