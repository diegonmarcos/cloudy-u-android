#!/bin/sh
# test-media-center-no-games.sh — #1895 Games moved out to Cloud Store > Search > Games; nothing of it is left here (Rule 16 parity).
#
#   gone         every Games piece listed in ac_cloud-store/test/games-parity.json is absent from this app: the page, the script, the bottom-nav
#                entry and route, the hub card, the build.json feature, the Rust command, the launcher package visibility, the two crates only
#                that command used, the CSS rules.
#   unchanged    the bottom bar is exactly the seven pages it had before Games was added to the freed slot, in order; the Configs tabs and the
#                Library page are untouched (the Library stays Configs' tab).
# The Cloud Store side (every piece exists there) is ac_cloud-store/test/test-store-games.sh. Static, no build: node reads the real files.
# Each check is repeated on a scratch copy with a planted defect that must turn it red.
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
const par = JSON.parse(rd(repo, 'ac_cloud-store/test/games-parity.json'));
const P = require(path.join(web, 'assets/pages.js')), C = require(path.join(web, 'assets/configs.js'));

/* ── gone ── */
par.moved_files.forEach((f) => ok(!fs.existsSync(path.join(repo, f.from)), 'no longer in Media Center: ' + f.from));
ok(!fs.existsSync(path.join(web, 'games')) && !fs.existsSync(path.join(web, 'assets/games.js')), 'no games page directory, no games script');
ok(!fs.existsSync(path.join(web, 'data/games-catalogue.json')) && !rd(app, 'build.json').includes('games-catalogue'), 'no games dataset in web/ and none declared');
const all = []; (function walk(d) { fs.readdirSync(d, { withFileTypes: true }).forEach((e) => { const f = path.join(d, e.name); if (e.isDirectory()) { if (!['node_modules', 'target', 'gen', 'icons', '__pycache__'].includes(e.name)) walk(f); } else if (/\.(js|html|css|rs|toml|xml)$/.test(e.name)) all.push(f); }); })(app);
const hits = (re) => all.filter((f) => re.test(fs.readFileSync(f, 'utf8'))).map((f) => path.relative(app, f));
ok(hits(/data-page="games"|games\/index\.html|CmcGames|games\.js|\.grow\b|\.glist\b|renderGames|games-catalogue|installed_packages|cmc_games/).filter((f) => !f.startsWith('test/')).length === 0, 'no Games code, style or reference left in web/ or src-tauri/' + (hits(/data-page="games"|games\/index\.html|CmcGames|games\.js|\.grow\b|\.glist\b|renderGames|games-catalogue|installed_packages|cmc_games/).filter((f) => !f.startsWith('test/')).join(', ')));
const bottom = P.tabsHtml('music', '../').match(/href="\.\.\/([a-z]+)\/index\.html"/g).map((h) => h.replace(/.*\.\.\/|\/index\.html"/g, ''));
ok(!bottom.includes('games') && !/id: 'games'/.test(rd(web, 'assets/pages.js')), 'the bottom nav has no Games entry and pages.js no games route');
ok(!rd(web, 'index.html').includes('Games'), 'the home hub has no Games card');
const bj = JSON.parse(rd(app, 'build.json'));
ok(!JSON.stringify(bj).includes('"Games"'), 'build.json declares no Games feature');
const rs = rd(app, 'src-tauri/src/lib.rs'), man = rd(app, 'src-tauri/gen/android/app/src/main/AndroidManifest.xml'), cargo = rd(app, 'src-tauri/Cargo.toml'), lock = rd(app, 'src-tauri/Cargo.lock');
ok(!/installed_packages|getLaunchIntentForPackage|invoke_handler|tauri::command/.test(rs) && /tauri_plugin_opener::init\(\)/.test(rs), 'the Rust side has no Games command; the opener plugin stays');
ok(!/<queries>/.test(man) && /android\.permission\.INTERNET/.test(man), 'the manifest has no Games package visibility; INTERNET stays');
ok(!/^jni\s*=|^ndk-context\s*=/m.test(cargo) && !/name = "cloud-media-center"[\s\S]{0,200}"jni"/.test(lock), 'Cargo.toml and the lock drop the two crates only the Games command used');

/* ── unchanged ── */
const OLD = par.media_center_bottom_nav_before_games;
ok(JSON.stringify(bottom) === JSON.stringify(OLD), 'the other bottom-nav entries are unchanged and in order: ' + OLD.join(', '));
const cfg = C.TABS.map((t) => t.id);
ok(JSON.stringify(cfg) === JSON.stringify(['music', 'videos', 'gallery', 'books', 'feed', 'movies', 'library', 'apify', 'general', 'agents', 'perms', 'logcat', 'xray', 'about']), 'the Configs tabs are unchanged');
ok(fs.existsSync(path.join(web, 'library/index.html')), 'the Library page file is kept');
OLD.forEach((id) => ok(fs.existsSync(path.join(web, id, 'index.html')), 'page kept: ' + id));
process.exit(fails.length ? 1 : 0);
JS
}

echo "== games gone from Media Center, everything else unchanged =="
check "$APP" "$REPO"
[ "${1:-}" = "check" ] && exit 0

echo "== mutations: each planted defect must turn the check red =="
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
fails=0
M=ac_cloud-media-center
mutate() {
  rm -rf "$WORK/r"; mkdir -p "$WORK/r/ac_cloud-store/test" "$WORK/r/$M"
  cp "$REPO/ac_cloud-store/test/games-parity.json" "$WORK/r/ac_cloud-store/test/"
  (cd "$APP" && tar cf - --exclude=target --exclude=node_modules --exclude=icons --exclude=.git web build.json src-tauri/src src-tauri/Cargo.toml src-tauri/Cargo.lock src-tauri/gen/android/app/src/main/AndroidManifest.xml) | (cd "$WORK/r/$M" && tar xf -)
  (cd "$WORK/r" && sh -c "$2")
  if check "$WORK/r/$M" "$WORK/r" >/dev/null 2>&1; then echo "  FAIL mutation $1: still green"; fails=$((fails + 1)); else echo "  ok   mutation $1: red"; fi
}
mutate games-tab-back "sed -i \"s/{ id: 'documentaries', label: 'Docs', icon: '◎' }/{ id: 'documentaries', label: 'Docs', icon: '◎' }, { id: 'games', label: 'Games', icon: 'G' }/\" $M/web/assets/pages.js"
mutate games-page-back "mkdir $M/web/games && printf '<body data-page=\"games\">' > $M/web/games/index.html"
mutate games-script-back "printf 'var CmcGames=1;' > $M/web/assets/games.js"
mutate games-hub-card-back "sed -i 's#</nav>#<a href=\"games/index.html\">Games</a></nav>#' $M/web/index.html"
mutate games-feature-back "sed -i 's#\"name\": \"Library (Configs)\"#\"name\": \"Games\"#' $M/build.json"
mutate games-command-back "printf '#[tauri::command]\\nfn installed_packages() {}\\n' >> $M/src-tauri/src/lib.rs"
mutate games-queries-back "sed -i 's#<application#<queries></queries><application#' $M/src-tauri/gen/android/app/src/main/AndroidManifest.xml"
mutate games-crate-back "printf '[target.\"cfg(target_os = \\\\\"android\\\\\")\".dependencies]\\njni = \"=0.21.1\"\\n' >> $M/src-tauri/Cargo.toml"
mutate games-css-back "printf '.glist { display: flex; }\\n' >> $M/web/assets/app.css"
mutate nav-entry-dropped "sed -i \"/id: 'movies', label: 'Movies'/d\" $M/web/assets/pages.js"
mutate configs-tab-dropped "sed -i \"/id: 'logcat'/d\" $M/web/assets/configs.js"
[ "$fails" -eq 0 ] || { echo "$fails mutation(s) stayed green"; exit 1; }
echo "PASS: test-media-center-no-games"
