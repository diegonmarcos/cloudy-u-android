#!/bin/sh
# test-store-games.sh — #1895 Games moved from Cloud Media Center to Cloud Store > Search > Games (Rule 16 parity).
#
#   parity       test/games-parity.json is the list captured from the tree BEFORE the move: every moved file, the route, the page's JS API,
#                the bridge ops, the dataset's categories and every one of its packages. Each must exist in Cloud Store; the Store's
#                Search subpages are exactly Cloudy, Phone, Games, Torrent; the Media Center side (nothing left, nav unchanged) is
#                ac_cloud-media-center/test/test-media-center-no-games.sh.
#   dataset      data/fleet/games-catalogue.json: schema, the twelve categories in order, unique packages, https store icons, one source per
#                category rule (open-source = F-Droid); a runtime dataset listed in data/fleet/manifest.json, never bundled in the app.
#   installed    the CloudGames bridge answers which packages are launchable (GamesPage.kt over PackageManager), the page marks them Installed
#                and offers Open; Install goes through Cloud Store (F-Droid games) when installed, otherwise the store listing.
# Static, no build, no network: node runs the real page script. Each check is then repeated on a scratch copy with a planted defect that
# must turn it red, so a check that cannot fail does not count.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
APP="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$APP/.." && pwd)"
command -v node >/dev/null || { echo "ERROR: node is required"; exit 1; }

check() {
  node - "$1" "$2" <<'JS'
const fs = require('fs'), path = require('path');
const app = process.argv[2], repo = process.argv[3], gdir = path.join(app, 'app/src/main/assets/games');
const fails = [];
const ok = (c, m) => { if (c) console.log('  ok   ' + m); else { console.log('  FAIL ' + m); fails.push(m); } };
const rd = (...p) => fs.readFileSync(path.join(...p), 'utf8');
(async () => {
  const G = require(path.join(gdir, 'games.js'));
  const par = JSON.parse(rd(app, 'test/games-parity.json'));

  /* ── parity with the list captured before the move ── */
  par.moved_files.filter((f) => f.to.startsWith('ac_cloud-store/app/')).forEach((f) => ok(fs.existsSync(path.join(repo, f.to)), 'moved file exists in Cloud Store: ' + f.to));
  ok(fs.existsSync(path.join(app, 'test/test-store-games.sh')), 'the Games tester moved with the feature');
  ok(fs.existsSync(path.join(gdir, 'games.css')) && par.moved_styles.selectors.every((s) => rd(gdir, 'games.css').includes(s + ' {') || rd(gdir, 'games.css').includes(s + ' ')), 'every moved Games style rule (' + par.moved_styles.selectors.length + ') is in games.css');
  ok(par.js_api.every((n) => n in G), 'the page script exports every function of the old page (' + par.js_api.length + ')');
  ok(rd(gdir, 'index.html').includes('data-page="games"') && rd(gdir, 'index.html').includes('games.js') && rd(gdir, 'index.html').includes('games.css'), 'the Games page loads its script and styles');
  const bj = JSON.parse(rd(app, 'build.json')), search = bj.ui.sections.find((s) => s.id === 'search');
  ok(JSON.stringify(search.pages.map((p) => p.id)) === JSON.stringify(par.store_search_subpages) && JSON.stringify(search.pages.map((p) => p.label)) === JSON.stringify(par.store_search_labels), 'Store Search subpages are exactly Cloudy, Phone, Games, Torrent (build.json)');
  const sp = rd(repo, 'ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreSearchPage.kt');
  ok(/PAGES = listOf\(PAGE_CLOUDY, PAGE_ANDROID, PAGE_GAMES, PAGE_TORRENT\)/.test(sp) && sp.includes('PAGE_GAMES = "search-games"') && sp.includes('StorePages.gamesPane'), 'StoreSearchPage lists the four subpages in that order and draws the Games pane');
  const ma = rd(app, 'app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt'), gp = rd(app, 'app/src/main/java/com/diegonmarcos/cloudstore/GamesPage.kt');
  ok(ma.includes('StorePages.gamesPane = ') && gp.includes('WebProfile.APP_PAGE') && gp.includes('addWebMessageListener') && gp.includes('CloudWeb.assetLoader') && par.bridge_ops.every((o) => gp.includes('"' + o + '"')) && !/__TAURI__|\.core\.invoke|opener/.test(rd(gdir, 'games.js')), 'MainActivity wires the pane; GamesPage serves the assets in the fleet web view with the bridge ops ' + par.bridge_ops.join(', ') + '; the page has no Tauri call left');
  ok(!(fs.readdirSync(gdir).some((n) => /\.(png|jpe?g|webp|gif|svg|ico|ttf|woff2?)$/i.test(n))), 'no third-party artwork or font bundled with the Games page (licenses/curated.json: own code)');
  const gcat = JSON.parse(rd(repo, par.dataset.file));
  ok(JSON.stringify(gcat.categories.map((c) => c.id)) === JSON.stringify(par.dataset.categories) && par.dataset.packages.every((p) => gcat.games.some((g) => g.pkg === p)), 'every category and all ' + par.dataset.packages.length + ' packages captured before the move are still in the dataset');
  ok(/cmc_games_v1/.test(rd(gdir, 'games.js')), 'the last-known-good cache keeps its key (' + par.saved_state.key + ')');

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
  const bundled = []; (function walk(d) { fs.readdirSync(d, { withFileTypes: true }).forEach((e) => { const f = path.join(d, e.name); if (e.isDirectory()) walk(f); else if (/\.(png|jpe?g|webp|gif|svg|ico)$/i.test(e.name) || /games|catalogue/i.test(e.name) && e.name.endsWith('.json')) bundled.push(f); }); })(gdir);
  ok(bundled.length === 0, 'no image and no games dataset under assets/games (nothing bundled)' + (bundled.length ? ': ' + bundled.join(', ') : ''));
  const gjs = rd(gdir, 'games.js');
  ok(/raw\.githubusercontent\.com\/[^']*data\/fleet\/games-catalogue\.json/.test(gjs) && !/data:image|base64/.test(gjs), 'the page fetches the dataset at runtime; no inline artwork');

  /* ── installed detection ── */
  const g = cat.games[0], f = cat.games.find((x) => x.source === 'fdroid');
  let asked = null;
  const rt = { invoke: (cmd, a) => { asked = [cmd, a]; return Promise.resolve([g.pkg, G.STORE_PKG]); } };
  const inst = await G.detectInstalled(cat, rt);
  ok(asked[0] === 'installed_packages' && asked[1].pkgs.length === cat.games.length + 1 && asked[1].pkgs.includes(G.STORE_PKG) && inst[g.pkg] && inst[G.STORE_PKG] && !inst[f.pkg], 'the page asks the app bridge (installed_packages) for every game plus Cloud Store and keeps the answer');
  ok(await G.detectInstalled(cat, { invoke: () => Promise.reject(new Error('x')) }) === null && await G.detectInstalled(cat, null) === null, 'no runtime or a failing command: no installed marks (null), never a wrong one');
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
  process.exit(fails.length ? 1 : 0);
})().catch((e) => { console.log('  FAIL ' + (e.stack || e)); process.exit(1); });
JS
}

echo "== games moved to Cloud Store: parity, dataset, installed detection =="
check "$APP" "$REPO"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn the check red =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
S=ac_cloud-store; GD=$S/app/src/main/assets/games; SP=ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreSearchPage.kt
mutate() {
  rm -rf "$WORK/r"; mkdir -p "$WORK/r/data/fleet" "$WORK/r/$S/app/src/main/java/com/diegonmarcos/cloudstore" "$WORK/r/$S/test" "$WORK/r/$(dirname "$SP")"
  cp "$REPO/data/fleet/games-catalogue.json" "$REPO/data/fleet/manifest.json" "$WORK/r/data/fleet/"
  cp -R "$APP/app/src/main/assets" "$WORK/r/$S/app/src/main/"
  cp "$APP/build.json" "$WORK/r/$S/"; cp "$APP/test/games-parity.json" "$APP/test/test-store-games.sh" "$WORK/r/$S/test/"
  cp "$APP/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt" "$APP/app/src/main/java/com/diegonmarcos/cloudstore/GamesPage.kt" "$WORK/r/$S/app/src/main/java/com/diegonmarcos/cloudstore/"
  cp "$REPO/$SP" "$WORK/r/$SP"
  (cd "$WORK/r" && sh -c "$2")
  if check "$WORK/r/$S" "$WORK/r" >/dev/null 2>&1; then echo "  FAIL mutation $1: still green"; fails=$((fails + 1)); else echo "  ok   mutation $1: red"; fi
}
mutate games-page-missing "rm $GD/index.html"
mutate games-script-missing "rm $GD/games.js"
mutate games-styles-missing "rm $GD/games.css"
mutate games-subpage-dropped "python3 -c \"import json;p='$S/build.json';d=json.load(open(p));s=[x for x in d['ui']['sections'] if x['id']=='search'][0];s['pages']=[q for q in s['pages'] if q['id']!='search-games'];json.dump(d,open(p,'w'))\""
mutate subpage-order-changed "sed -i 's/PAGE_CLOUDY, PAGE_ANDROID, PAGE_GAMES, PAGE_TORRENT/PAGE_CLOUDY, PAGE_ANDROID, PAGE_TORRENT, PAGE_GAMES/' $SP"
mutate pane-not-wired "sed -i 's/StorePages.gamesPane = /StorePages.unused = /' $S/app/src/main/java/com/diegonmarcos/cloudstore/MainActivity.kt"
mutate bridge-op-dropped "sed -i 's/\"open_url\"/\"open_gone\"/' $S/app/src/main/java/com/diegonmarcos/cloudstore/GamesPage.kt"
mutate tauri-call-left "sed -i 's/function invoke(cmd, args, rt) {/function invoke(cmd, args, rt) { var t = root.__TAURI__;/' $GD/games.js"
mutate artwork-bundled "printf x > $GD/cover.png"
mutate category-dropped "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));d['categories']=d['categories'][:-1];json.dump(d,open(p,'w'))\""
mutate game-dropped "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));d['games']=d['games'][1:];json.dump(d,open(p,'w'))\""
mutate game-bad-icon "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));d['games'][0]['icon']='http://x/y.png';json.dump(d,open(p,'w'))\""
mutate opensource-from-play "python3 -c \"import json;p='data/fleet/games-catalogue.json';d=json.load(open(p));[g.update(source='play') for g in d['games'] if g['category']=='opensource'][:1];json.dump(d,open(p,'w'))\""
mutate dataset-unlisted "python3 -c \"import json;p='data/fleet/manifest.json';d=json.load(open(p));del d['shared']['games-catalogue'];json.dump(d,open(p,'w'))\""
mutate dataset-baked-in-assets "cp data/fleet/games-catalogue.json $GD/games-catalogue.json"
mutate install-ignores-store "sed -i \"s/if (storeInstalled \\&\\& g.source === 'fdroid') out.push(storeIntent(g.pkg));//\" $GD/games.js"
[ "$fails" -eq 0 ] || { echo "$fails mutation(s) stayed green"; exit 1; }
echo "PASS: test-store-games"
