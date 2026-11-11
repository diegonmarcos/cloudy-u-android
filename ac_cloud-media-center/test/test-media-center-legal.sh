#!/bin/sh
# test-media-center-legal.sh — the Movies player plays LEGAL sources only, from
# the one source list it shares with My Movies (diegonmarcos.github.io).
#
#   1. guard: no unlicensed streaming embed or public piracy index anywhere in
#      this repo's tracked files (denylist: test/legal-denylist.json, copied by
#      data/regen.py from the web repo at build.json::data.shared.commit; only
#      that file, this guard and the owner-declared torrent indexers of #1633
#      (two literal paths in ab_cloud-libs-shared/libs/torrent) may name them)
#   2. the shared core (web/assets/sources-core.js) under node: source list
#      parsing, IMDb<->TMDB conversion, provider mapping, buffering, torrents
#   3. wiring: the copies carry the pin; the Movies and Docs pages load the player
# Then each check is turned red on a planted defect.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
ROOT="$(git -C "$APP" rev-parse --show-toplevel)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }
command -v python3 >/dev/null || { echo "ERROR: python3 is required"; exit 1; }

guard() {  # $1 = git root, $2 = denylist json
  python3 - "$1" "$2" <<'PY'
import json, subprocess, sys
root, deny_file = sys.argv[1:3]
deny = json.load(open(deny_file))["denylist"]
T = "ab_cloud-libs-shared/libs/torrent/src/%s/java/com/diegonmarcos/superapp/torrent/%s"
TORRENT_INDEXERS_1633 = [T % ("main", "TorrentSearch.kt"), T % ("test", "TorrentSearchTest.kt")]
assert len(deny) >= 10, "denylist is suspiciously short"
args = ["git", "-C", root, "grep", "-I", "-i", "-n", "-F"]
for d in deny: args += ["-e", d]
args += ["--", ".", ":(exclude,glob)**/legal-denylist.json", ":(exclude,glob)**/test-media-center-legal.sh", ":(exclude,glob)**/legal-guard.sh"]
# #1633: the owner ordered torrent search with built-in declared indexers plus
# user-managed sources. Those indexer declarations, and the unit test that
# parses their replies, are the ONLY other files allowed to name a denied
# domain: two literal paths from the repo root, nothing else in libs/torrent
# and no same-named file anywhere else.
args += [":(exclude,top,literal)" + p for p in TORRENT_INDEXERS_1633]
r = subprocess.run(args, capture_output=True, text=True)
hits = [l for l in r.stdout.splitlines() if l.strip()]
for l in hits[:40]: print("  DENIED " + l[:200])
print(("  FAIL %d line(s) name an unlicensed embed / piracy index" % len(hits)) if hits else "  ok   no denied domain in the repo")
sys.exit(1 if hits else 0)
PY
}

check() {  # $1 = app dir
  node - "$1" <<'JS' || return 1
const path = require('path'), fs = require('fs'), assert = require('assert');
const app = process.argv[2];
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };
const t = (m, f) => { try { f(); ok(true, m); } catch (e) { ok(false, m + ' — ' + e.message); } };
const bj = JSON.parse(fs.readFileSync(path.join(app, 'build.json'), 'utf8'));
const sh = bj.data.shared;
const L = require(path.join(app, 'web/assets/sources-core.js'));
const raw = JSON.parse(fs.readFileSync(path.join(app, 'web/data/sources.json'), 'utf8'));
const deny = JSON.parse(fs.readFileSync(path.join(app, 'test/legal-denylist.json'), 'utf8')).denylist;
let decl;
t('source list parses: known kinds, unique ids, ordered, no denied domain', () => {
  decl = L.parseSources(raw, deny);
  assert.ok(decl.sources.length >= 8);
  for (const k of L.KINDS) assert.ok(decl.sources.some((s) => s.kind === k), k);
  assert.throws(() => L.parseSources({ sources: [{ id: 'x', kind: 'nope' }] }));
});
t('no credential committed in the source list', () => assert.doesNotMatch(JSON.stringify(raw), /"token"\s*:\s*"[^"]|api_key=[0-9a-f]{16}/));
t('IMDb -> TMDB via /find (movie and tv)', () => {
  const res = { movie_results: [{ id: 603, title: 'The Matrix', release_date: '1999-03-31' }], tv_results: [{ id: 1399, name: 'GoT' }] };
  assert.equal(L.tmdbFromFind(res, 'movie').tmdbId, 603);
  assert.equal(L.tmdbFromFind(res, 'series').tmdbId, 1399);
  assert.equal(L.tmdbFromFind({ movie_results: [], tv_results: [] }, 'movie'), null);
  assert.match(L.findUrl('tt0133093', 'K'), /\/find\/tt0133093\?external_source=imdb_id/);
});
t('provider mapping: categories, priority order, deep link else TMDB page', () => {
  const g = L.mapProviders({ results: { ES: { link: 'L', flatrate: [{ provider_id: 337, provider_name: 'Disney', display_priority: 2 }, { provider_id: 8, provider_name: 'Netflix', display_priority: 1, logo_path: '/n.jpg' }], buy: [{ provider_id: 424242, provider_name: 'X' }] } } }, 'es', 'Dune', decl.providerLinks);
  assert.deepEqual(g.map((x) => x.category), ['flatrate', 'buy']);
  assert.equal(g[0].services[0].link, 'https://www.netflix.com/search?q=Dune');
  assert.equal(g[1].services[0].link, 'L');
});
t('buffering policy and ready rule', () => {
  assert.deepEqual(L.BUFFER_MODES.map((m) => m.id), ['30s', '2m', '10m', 'full', 'offline']);
  const r = { length: 1, start: () => 0, end: () => 40 };
  assert.equal(L.bufferedAhead(r, 10), 30);
  assert.equal(L.readyToPlay(L.bufferPolicy('30s'), 30, 10, 999), true);
  assert.equal(L.readyToPlay(L.bufferPolicy('offline'), 1e9, 0, 9), false);
});
t('torrents: magnet / .torrent only; Internet Archive torrent URL', () => {
  assert.equal(L.parseTorrentInput('magnet:?xt=urn:btih:08ada5a7a6183aae1e09d831df6748d566095a10').kind, 'magnet');
  assert.ok(L.parseTorrentInput('https://example.org/page').error);
  assert.equal(L.archiveTorrentUrl(decl.sources.find((s) => s.id === 'archive-torrent'), 'x'), 'https://archive.org/download/x/x_archive.torrent');
});
t('user sources: add / edit / remove, {query} template, Torznab and RSS parsing, badges', () => {
  let r = L.addUserSource([], { name: 'J', type: 'torznab', url: 'http://j/api', apikey: 'K' }, () => 'a');
  r = L.addUserSource(r.list, { name: 'T', type: 'template', url: 'https://s/?q={query}' }, () => 'b');
  assert.equal(r.list.length, 2);
  assert.ok(L.addUserSource(r.list, { name: 'x', type: 'template', url: 'https://s/' }).error);
  r = L.updateUserSource(r.list, 'a', { enabled: false }); assert.equal(r.list[0].enabled, false);
  r = L.removeUserSource(r.list, 'b'); assert.deepEqual(r.list.map((s) => s.id), ['a']);
  assert.equal(L.userSearchUrl({ type: 'template', url: 'https://s/?q={query}' }, 'a b'), 'https://s/?q=a%20b');
  assert.equal(L.userSearchUrl({ type: 'torznab', url: 'http://j/api', apikey: 'K' }, 'x'), 'http://j/api?t=search&apikey=K&q=x');
  const tz = L.parseTorrentFeed('<rss><channel><item><title>A</title><enclosure url="http://j/1.torrent" length="5"/><torznab:attr name="seeders" value="3"/></item></channel></rss>');
  assert.deepEqual([tz[0].title, tz[0].torrent, tz[0].seeders, tz[0].size], ['A', 'http://j/1.torrent', 3, 5]);
  const rs = L.parseTorrentFeed('<rss><item><title>B</title><link>magnet:?xt=urn:btih:1111111111111111111111111111111111111111</link></item></rss>');
  assert.match(rs[0].magnet, /^magnet:/);
  assert.equal(raw.user_sources, undefined, 'no user-source presets shipped');
});
const pl = fs.readFileSync(path.join(app, 'web/assets/player.js'), 'utf8');
const cf = fs.readFileSync(path.join(app, 'web/assets/configs.js'), 'utf8');
ok(/searchUserSources/.test(pl) && /localStorage\.getItem\(UKEY/.test(pl) && /setItem\(USER_KEY/.test(cf) && /data-udel/.test(cf) && /data-uon/.test(cf), 'player searches user sources; Configs → Movies stores them on the device, enable/disable/delete');
const core = fs.readFileSync(path.join(app, 'web/assets/sources-core.js'), 'utf8');
ok(core.includes(sh.repo + '@' + sh.commit), 'sources-core.js is the copy at build.json::data.shared.commit');
for (const pg of ['movies', 'documentaries']) {
  const h = fs.readFileSync(path.join(app, 'web', pg, 'index.html'), 'utf8');
  ok(h.includes('../assets/sources-core.js') && h.includes('../assets/player.js'), pg + ': loads the shared core and the player');
}
const P = require(path.join(app, 'web/assets/pages.js'));
const mo = JSON.parse(fs.readFileSync(path.join(app, 'web/data/movies.json'), 'utf8'));
const id = mo.shelves[0].ids[0];
ok(P.renderMovieDetail(mo, mo.movies[id]).includes('data-play="' + id + '"'), 'the detail sheet offers the source picker');
process.exit(fails.length ? 1 : 0);
JS
}

echo "== legal guard over the repo =="
guard "$ROOT" "$APP/test/legal-denylist.json"
echo "== shared source list, core and wiring =="
check "$APP"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn a check red =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
git -C "$WORK" init -q; mkdir -p "$WORK/g"
printf '<iframe src="https://vid%s.cc/embed/movie/tt1"></iframe>\n' src > "$WORK/g/planted.html"
git -C "$WORK" add -A
if guard "$WORK" "$APP/test/legal-denylist.json" >/dev/null 2>&1; then echo "  FAIL mutation embed-planted: still green"; fails=$((fails + 1)); else echo "  ok   mutation embed-planted: red"; fi
# #1633 exemption is path-scoped: the same line is green only at the declared
# indexer path, and red in the same lib, in a same-named file elsewhere, and in
# this app's own source.
TS="src/main/java/com/diegonmarcos/superapp/torrent/TorrentSearch.kt"
plant() {  # $1 = name, $2 = path in a fresh repo, $3 = red|green
  rm -rf "$WORK/p"; mkdir -p "$WORK/p/$(dirname "$2")"; git -C "$WORK/p" init -q
  printf 'val homepage = "https://the%sbay.org"\n' pirate > "$WORK/p/$2"
  git -C "$WORK/p" add -A
  if guard "$WORK/p" "$APP/test/legal-denylist.json" >/dev/null 2>&1; then got=green; else got=red; fi
  if [ "$got" = "$3" ]; then echo "  ok   mutation $1: $got"; else echo "  FAIL mutation $1: $got, expected $3"; fails=$((fails + 1)); fi
}
plant indexer-declared-1633 "ab_cloud-libs-shared/libs/torrent/$TS" green
plant indexer-elsewhere-in-torrent-lib "ab_cloud-libs-shared/libs/torrent/src/main/java/com/diegonmarcos/superapp/torrent/TorrentSources.kt" red
plant indexer-in-other-lib "ab_cloud-libs-shared/libs/other/$TS" red
plant indexer-in-media-center "ac_cloud-media-center/web/assets/player.js" red
plant indexer-same-name-in-media-center "ac_cloud-media-center/ab_cloud-libs-shared/libs/torrent/$TS" red
mutate() {
  rm -rf "$WORK/app"; mkdir -p "$WORK/app"
  cp -R "$APP/build.json" "$APP/web" "$APP/test" "$WORK/app/"
  (cd "$WORK/app" && sh -c "$2")
  if check "$WORK/app" >/dev/null 2>&1; then echo "  FAIL mutation $1: still green"; fails=$((fails + 1)); else echo "  ok   mutation $1: red"; fi
}
mutate unknown-kind "python3 -c \"import json;p='web/data/sources.json';d=json.load(open(p));d['sources'][0]['kind']='stream';json.dump(d,open(p,'w'))\""
mutate denied-source "python3 -c \"import json;p='web/data/sources.json';d=json.load(open(p));d['sources'][0]['search']='https://'+'vid'+'src.cc/{query}';json.dump(d,open(p,'w'))\""
mutate player-unloaded "sed -i 's#<script src=\"../assets/player.js\"></script>##' web/movies/index.html"
mutate user-sources-unwired "sed -i 's/searchUserSources/searchNothing/' web/assets/player.js"
mutate pin-moved "python3 -c \"import json;p='build.json';d=json.load(open(p));d['data']['shared']['commit']='0000000';json.dump(d,open(p,'w'))\""
[ "$fails" -eq 0 ] || { echo "$fails mutation(s) stayed green"; exit 1; }
echo "all checks green, all mutations red"
