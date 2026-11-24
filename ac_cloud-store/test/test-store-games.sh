#!/bin/sh
# test-media-center-games.sh — #1765 the Games tab and the bottom-nav move.
#
#   nav parity   Library left the bottom bar and is a Configs tab, still fully functional; nothing the bottom bar or Configs had before is gone;
#                the Games tab took the freed slot (same number of bottom tabs).
#   dataset      data/fleet/games-catalogue.json: schema, the twelve categories in order, unique packages, https store icons, one source per
#                category rule (open-source = F-Droid); a runtime dataset listed in data/fleet/manifest.json, never under web/ or bundled.
#   installed    the Rust command answers which packages are launchable, the page marks them Installed and offers Open; Install goes through
#                Cloud Store (F-Droid games) when installed, otherwise the store listing.
# Static, no build, no network: node runs the real page scripts. Each check is then repeated on a scratch copy with a planted defect that
# must turn it red, so a check that cannot fail does not count.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }

check() {
  node - "$1" "$2" <<'JS'
const fs = require('fs'), path = require('path');
const app = process.argv[2], repo = process.argv[3], web = path.join(app, 'web');
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };
const rd = (...p) => fs.readFileSync(path.join(...p), 'utf8');
(async () => {
  const P = require(path.join(web, 'assets/pages.js')), C = require(path.join(web, 'assets/configs.js')), G = require(path.join(web, 'assets/games.js')), S = require(path.join(web, 'assets/media-sync.js'));

  /* ── nav parity: nothing dropped ── */
  const OLD_BOTTOM = ['music', 'videos', 'gallery', 'books', 'feed', 'movies', 'documentaries', 'library'];
  const OLD_CONFIGS = ['music', 'videos', 'gallery', 'books', 'feed', 'movies', 'apify', 'general', 'agents', 'perms', 'logcat', 'xray', 'about'];
  const bottom = P.tabsHtml('music', '../').match(/href="\.\.\/([a-z]+)\/index\.html"/g).map((h) => h.replace(/.*\.\.\/|\/index\.html"/g, ''));
  const cfg = C.TABS.map((t) => t.id);
  ok(bottom.length === OLD_BOTTOM.length, 'bottom bar keeps its slot count (' + bottom.length + ')');
  ok(!bottom.includes('library') && bottom.includes('games') && bottom[bottom.length - 1] === 'games', 'Library left the bottom bar; Games took its slot');
  ok(OLD_BOTTOM.every((id) => bottom.includes(id) || cfg.includes(id)), 'every old bottom entry is still reachable (bottom bar or Configs)');
  ok(OLD_CONFIGS.every((id) => cfg.includes(id)) && cfg.length === OLD_CONFIGS.length + 1 && cfg.includes('library'), 'every old Configs tab is kept; Library is the one addition');
  ok(fs.existsSync(path.join(web, 'library/index.html')) && fs.existsSync(path.join(web, 'games/index.html')), 'the Library page file is kept; the Games page exists');
  ok(rd(web, 'games/index.html').includes('data-page="games"') && rd(web, 'index.html').includes('games/index.html'), 'Games is on the home hub');
  const msrc = S.parseSources(JSON.parse(rd(web, 'data/media-sources.json')));
  const lib = { updated: 1, playlists: [{ id: 'spotify:1', title: 'Road Mix', source: 'Spotify', owner: 'me', count: 3, kind: 'playlist', area: 'music', items: [], url: 'https://x.test/1' }] };
  const ctx = { msrc, srcCfg: {}, has: () => false, backend: 'cloud', sync: { schedule: 'daily' }, sub: 'apify', lib, libSt: { q: '', area: '', open: '' } };
  const lh = C.renderConfigs('library', {}, null, [], ctx);
  ok(lh.includes('data-lib-sync') && lh.includes('data-lib-q') && lh.includes('Road Mix') && lh.includes('data-open="spotify:1"') && lh.includes('data-ext') && /schedule: daily/.test(lh), 'Configs > Library: Sync now, search, rows with open and link-out, schedule');
  ok(msrc.areas.length > 0 && msrc.areas.every((a) => lh.includes('data-lib-area="' + a.id + '"')), 'Configs > Library: one filter chip per source area (' + msrc.areas.length + ')');
  ok(typeof S.syncNow === 'function' && /data-lib-sync/.test(rd(web, 'assets/configs.js')) && /S\.syncNow\(/.test(rd(web, 'assets/configs.js')), 'Configs > Library runs the real sync');
  const bj = JSON.parse(rd(app, 'build.json')), cp = JSON.parse(rd(repo, '1_cicd/src/data/configs-pages.json')).tabs.app_order['ac_cloud-media-center'].order;
  ok(JSON.stringify(bj.configs_pages.top_nav.map((t) => t.id)) === JSON.stringify(cfg) && JSON.stringify(cp) === JSON.stringify(cfg), 'build.json::configs_pages.top_nav, configs-pages.json and configs.js::TABS agree');

  /* ── the dataset ── */
  const cp_ = path.join(repo, 'data/fleet/games-catalogue.json');
  const raw = rd(cp_), cat = G.parseCatalogue(raw);
  ok(cat.problems.length === 0 && cat.games.length >= 60, 'catalogue parses with no dropped row (' + cat.games.length + ' games)');
  ok(JSON.stringify(cat.categories.map((c) => c.id)) === JSON.stringify(G.CATEGORIES) && JSON.stringify(cat.categories.map((c) => c.label)) === JSON.stringify(['Action', 'Adventure', 'Puzzle', 'Strategy', 'RPG', 'Racing', 'Sports', 'Arcade', 'Casual', 'Simulation', 'Board / Card', 'Open-source']), 'the twelve categories, in the declared order');
  ok(cat.categories.every((c) => cat.games.filter((g) => g.category === c.id).length >= 4), 'every category has games');
  ok(new Set(cat.games.map((g) => g.pkg)).size === cat.games.length, 'packages are unique');
  ok(cat.games.every((g) => g.name && g.developer && g.desc && g.desc.length <= 140 && /^https:\/\/(play-lh\.googleusercontent\.com|f-droid\.org|raw\.githubusercontent\.com)\//.test(g.icon)), 'every game: name, developer, short description, a store listing icon URL');
  ok(cat.games.every((g) => (g.category === 'opensource') === (g.source !== 'play') && g.open_source === (g.source !== 'play') && (g.dimension === '2d' || g.dimension === '3d') && g.store_url.startsWith(g.source === 'play' ? 'https://play.google.com/store/apps/details?id=' + g.pkg : g.source === 'github' ? 'https://github.com/' : 'https://f-droid.org/packages/' + g.pkg)), 'open-source games are F-Droid or GitHub (#1776) and the only non-Play ones, each with open_source and dimension; store URLs match the package');
  ok(G.parseCatalogue('x').games.length === 0 && G.parseCatalogue('{"schemaVersion":2}').problems.length === 1 && G.parseCatalogue(JSON.stringify({ schemaVersion: 1, categories: [{ id: 'a', label: 'A' }], games: [{ pkg: 'bad pkg' }] })).problems.length === 1, 'a bad document or row is refused, never thrown');
  const man = JSON.parse(rd(repo, 'data/fleet/manifest.json')).shared['games-catalogue'];
  ok(man && man.tier >= 3 && man.tier <= 4 && JSON.parse(raw).schemaVersion === 1, 'runtime dataset: listed in data/fleet/manifest.json (tier 3-4), integer schemaVersion');
  const bundled = []; (function walk(d) { fs.readdirSync(d, { withFileTypes: true }).forEach((e) => { const f = path.join(d, e.name); if (e.isDirectory()) walk(f); else if (/\.(png|jpe?g|webp|gif|svg|ico)$/i.test(e.name) || /games|catalogue/i.test(e.name) && e.name.endsWith('.json')) bundled.push(f); }); })(web);
  ok(bundled.length === 0, 'no image and no games dataset under web/ (nothing bundled)' + (bundled.length ? ': ' + bundled.join(', ') : ''));
  const gjs = rd(web, 'assets/games.js');
  ok(/raw\.githubusercontent\.com\/[^']*data\/fleet\/games-catalogue\.json/.test(gjs) && !/data:image|base64/.test(gjs), 'the page fetches the dataset at runtime; no inline artwork');

  /* ── installed detection ── */
  const g = cat.games[0], f = cat.games.find((x) => x.source === 'fdroid');
  let asked = null;
  const rt = { core: { invoke: (cmd, a) => { asked = [cmd, a]; return Promise.resolve([g.pkg, G.STORE_PKG]); } } };
  const inst = await G.detectInstalled(cat, rt);
  ok(asked[0] === 'installed_packages' && asked[1].pkgs.length === cat.games.length + 1 && asked[1].pkgs.includes(G.STORE_PKG) && inst[g.pkg] && inst[G.STORE_PKG] && !inst[f.pkg], 'the page asks the Rust command for every game plus Cloud Store and keeps the answer');
  ok(await G.detectInstalled(cat, { core: { invoke: () => Promise.reject(new Error('x')) } }) === null && await G.detectInstalled(cat, null) === null, 'no runtime or a failing command: no installed marks (null), never a wrong one');
  const html = G.renderGames(cat, { installed: inst });
  const rowOf = (h, pkg) => h.split('<div class="grow" data-game="' + pkg + '"')[1].split('</button></div>')[0];
  ok(/Installed/.test(rowOf(html, g.pkg)) && /data-act="open"/.test(rowOf(html, g.pkg)) && /data-act="install"/.test(rowOf(html, f.pkg)) && !/Installed/.test(rowOf(html, f.pkg)), 'an installed game shows Installed and Open; the others Install');
  ok(G.installedCount(cat, inst) === 1 + 0 && (html.match(/data-installed/g) || []).length === 1, 'installed count and marks agree');
  ok(G.storeAction(g, inst).kind === 'open' && G.storeAction(g, inst).url === 'intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;package=' + g.pkg + ';end', 'Open launches the installed game');
  const viaStore = G.storeAction(f, inst).urls;
  ok(viaStore[0].includes('action=com.diegonmarcos.cloudstore.OPEN') && viaStore[0].includes('S.install_pkg=' + f.pkg) && viaStore[1] === f.store_url, 'Install goes through Cloud Store when it is installed (F-Droid games), then the listing');
  const noStore = G.storeAction(f, {}).urls;
  ok(noStore.every((u) => !u.includes('cloudstore')) && noStore[0] === f.store_url, 'without Cloud Store: the F-Droid listing');
  const play = cat.games.find((x) => x.source === 'play');
  ok(G.storeAction(play, {}).urls[0] === 'intent://details?id=' + play.pkg + '#Intent;scheme=market;package=com.android.vending;end' && G.storeAction(play, {}).urls.pop() === play.store_url, 'a Play game installs through the Play listing intent, https page as the last fallback');
  ok(G.renderGames(cat, { section: 'opensource' }).includes('id="cat-opensource"') && !G.renderGames(cat, { section: 'opensource' }).includes('id="cat-action"') && G.renderGames(cat, { q: 'zzzzqq' }).includes('No game matches'), 'category filter and search');
  const rs = rd(app, 'src-tauri/src/lib.rs'), man2 = rd(app, 'src-tauri/gen/android/app/src/main/AndroidManifest.xml');
  ok(/#\[tauri::command\]\s*fn installed_packages/.test(rs) && /generate_handler!\[installed_packages\]/.test(rs) && /getLaunchIntentForPackage/.test(rs), 'the Rust side serves installed_packages through PackageManager');
  ok(/<queries>[\s\S]*android\.intent\.action\.MAIN[\s\S]*android\.intent\.category\.LAUNCHER[\s\S]*<\/queries>/.test(man2) && !/QUERY_ALL_PACKAGES/.test(man2), 'the manifest declares launcher package visibility, not QUERY_ALL_PACKAGES');
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('  FAIL ' + (e.stack || e)); process.exit(1); });
JS
}

echo "== games tab, nav parity, dataset, installed detection =="
check "$APP" "$REPO"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn the check red =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
mutate() {
  rm -rf "$WORK/r"; mkdir -p "$WORK/r/data/fleet" "$WORK/r/1_cicd/src/data" "$WORK/r/ac_cloud-media-center/src-tauri/gen/android/app/src/main" "$WORK/r/ac_cloud-media-center/src-tauri/src"
  cp "$REPO/data/fleet/games-catalogue.json" "$REPO/data/fleet/manifest.json" "$WORK/r/data/fleet/"
  cp "$REPO/1_cicd/src/data/configs-pages.json" "$WORK/r/1_cicd/src/data/"
  cp -R "$APP/web" "$APP/build.json" "$WORK/r/ac_cloud-media-center/"
  cp "$APP/src-tauri/src/lib.rs" "$WORK/r/ac_cloud-media-center/src-tauri/src/"
  cp "$APP/src-tauri/gen/android/app/src/main/AndroidManifest.xml" "$WORK/r/ac_cloud-media-center/src-tauri/gen/android/app/src/main/"
  (cd "$WORK/r" && sh -c "$2")
  if check "$WORK/r/ac_cloud-media-center" "$WORK/r" >/dev/null 2>&1; then echo "  FAIL mutation $1: still green"; fails=$((fails + 1)); else echo "  ok   mutation $1: red"; fi
}
M=ac_cloud-media-center
mutate library-back-on-bar "sed -i \"s/var EXTRA = \\[{ id: 'games'/var EXTRA = [{ id: 'library', label: 'L', icon: 'x' }, { id: 'games'/\" $M/web/assets/pages.js"
mutate library-tab-dropped "sed -i \"/id: 'library', label: 'Library'/d\" $M/web/assets/configs.js"
mutate configs-tab-dropped "sed -i \"/id: 'logcat'/d\" $M/web/assets/configs.js"
mutate games-tab-missing "sed -i \"s/{ id: 'games', label: 'Games', icon: '🎮' }//\" $M/web/assets/pages.js"
mutate library-sync-button-gone "sed -i 's/data-lib-sync//g' $M/web/assets/configs.js"
mutate category-dropped "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));d['categories']=d['categories'][:-1];json.dump(d,open(p,'w'))\""
mutate game-bad-icon "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));d['games'][0]['icon']='http://x/y.png';json.dump(d,open(p,'w'))\""
mutate opensource-from-play "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));[g.update(source='play') for g in d['games'] if g['category']=='opensource'][:1];json.dump(d,open(p,'w'))\""
mutate dataset-unlisted "python3 -c \"import json;p='data/fleet/manifest.json';d=json.load(open(p));del d['shared']['games-catalogue'];json.dump(d,open(p,'w'))\""
mutate artwork-bundled "printf x > $M/web/assets/cover.png"
mutate dataset-baked-in-web "cp data/fleet/games-catalogue.json $M/web/data/games-catalogue.json"
mutate command-unregistered "sed -i 's/generate_handler!\\[installed_packages\\]/generate_handler![]/' $M/src-tauri/src/lib.rs"
mutate no-package-visibility "sed -i '/<queries>/,/<\\/queries>/d' $M/src-tauri/gen/android/app/src/main/AndroidManifest.xml"
mutate install-ignores-store "sed -i \"s/if (storeInstalled \\&\\& g.source === 'fdroid') out.push(storeIntent(g.pkg));//\" $M/web/assets/games.js"
[ "$fails" -eq 0 ] || { echo "$fails mutation(s) stayed green"; exit 1; }
echo "PASS: test-media-center-games"
