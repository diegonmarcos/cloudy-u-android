#!/usr/bin/env bash
# Cloud Store: Feed moved INTO Cloud, the Torrent destination, the torrent engine lib, Search source subpages, Configs > Torrent, permissions.
# Static (no build, no device, no network). What this holds:
#   N1 the island is cloud, phone, search, torrent, access: Torrent took Feed's place and Feed is gone as a destination
#      (no feed section, no FeedPage, no TAB_FEED; an old `tab=feed` intent opens Cloud)
#   F1 the Cloud page draws the Commits and CI-CD panels (StoreFeedPanels.Panels) BELOW its action buttons (StoreBar.Bar), over the
#      Store's declared feeds (appstore-feeds.json commits + cicd), with the Configs > Sources switch honoured and a failed read
#      said in words (FeedViewer.explain), none of the old Feed page's per-row data dropped (ref, title, subtitle, state colour, link)
#   T1 the Torrent destination: add a magnet, import a .torrent, active and completed lists, progress, rates, seeds and peers,
#      pause / resume / remove, seed switch, engine-unavailable and no-network states
#   L1 libs:torrent exists as a separate shared lib: declared in the Store's build.json::modules and its app depends_on, linked by
#      app/build.gradle, in lib-consumers.json (consumed by ac_cloud-store only), watched by the Store ship workflow, classified
#      and categorised, and the lib is CODE ONLY (no assets, no res, no BuildConfig data)
#   L2 the engine API: TorrentEngine add / refresh / pause / resume / remove / setSeeding / settings.saveDir, a TorrentBackend seam and
#      a jlibtorrent backend on arm64 (prebuilt libtorrent, 64 KB aligned), unit-tested with a fake session
#   S1 Search has the Cloudy | Android | Torrent source subpages; Cloudy/Android results install, Torrent results go to the torrent
#      engine; the torrent indexers are a declared list with at least two public sources
#   C1 Configs > Torrent: download folder default Downloads/Cloudy-store/Torrent (created if missing), external drive volumes,
#      folder picker, .torrent import into the engine, seeding policy, all-files grant through libs:perms
#   P1 permissions: MANAGE_EXTERNAL_STORAGE in the manifest and all_files in build.json::perms.needs; no permission API called outside libs:perms
# Then each planted defect must turn it red.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
CS="ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore"
AS="ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
TL="ab_cloud-libs-shared/libs/torrent"
TLK="$TL/src/main/java/com/diegonmarcos/superapp/torrent"
FILES="ac_cloud-store/build.json ac_cloud-store/app/build.gradle ac_cloud-store/build.gradle ac_cloud-store/app/src/main/AndroidManifest.xml
$CS/MainActivity.kt $CS/TorrentPage.kt $CS/TorrentConfigPage.kt $CS/TorrentHost.kt $CS/ConfigsPage.kt $CS/App.kt
$AS/StoreCloudFragment.kt $AS/StoreFeedPanels.kt $AS/StoreSearchPage.kt $AS/StoreTorrentSearch.kt $AS/StoreTorrentHost.kt
ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-feeds.json
$TL/build.gradle $TL/src/main/AndroidManifest.xml $TLK/TorrentEngine.kt $TLK/TorrentModels.kt $TLK/TorrentPaths.kt $TLK/TorrentSearch.kt $TLK/JlibtorrentBackend.kt
aa_cloud-superapp/data/lib-consumers.json .github/workflows/ship-cloud-store.yml 1_cicd/src/data/lib-classes.json 1_cicd/src/data/lib-categories.json"

# check <root> -> prints PASS/FAIL lines, returns the number of failures
check() {
  python3 - "$1" "$CS" "$AS" "$TL" "$TLK" <<'PY'
import json, os, re, sys
R, CS, AS, TL, TLK = sys.argv[1:6]
fails = 0
def rd(p):
    try:
        return open(os.path.join(R, p), encoding='utf-8').read()
    except OSError:
        return None
def strip(s):
    s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    return re.sub(r'(?<![:"])//[^\n]*', '', s)
def ok(c, good, badmsg):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else badmsg))
    if not c: fails += 1
def need(*paths):
    miss = [p for p in paths if rd(p) is None]
    for p in miss:
        print("  FAIL: missing " + p)
    return not miss
bj_s = rd("ac_cloud-store/build.json")
if not need("ac_cloud-store/build.json", CS + "/MainActivity.kt", AS + "/StoreCloudFragment.kt", AS + "/StoreFeedPanels.kt"):
    sys.exit(99)
bj = json.loads(bj_s); ui = bj["ui"]; secs = {s["id"]: s for s in ui["sections"]}
pg = lambda i: [p["id"] for p in secs.get(i, {}).get("pages", [])]
ma = strip(rd(CS + "/MainActivity.kt"))

# N1
ok(ui["bottom_nav"] == ["cloud", "phone", "search", "torrent", "access"] and "feed" not in secs and "torrent" in secs,
   "N1 the island is cloud, phone, search, torrent, access; Feed is not a destination (no feed section)",
   "N1 ui.bottom_nav is %s / feed section present: %s" % (ui["bottom_nav"], "feed" in secs))
ok(rd(CS + "/FeedPage.kt") is None and "FeedPage" not in ma and "TAB_FEED" not in ma and "TorrentPage()" in ma and "TAB_TORRENT" in ma and 'LEGACY_FEED' in ma,
   "N1 MainActivity hosts TorrentPage and no longer FeedPage; an old tab=feed intent maps to Cloud",
   "N1 MainActivity still hosts the Feed page, or does not host TorrentPage / map the old feed tab")

# F1
cf = strip(rd(AS + "/StoreCloudFragment.kt")); fp = strip(rd(AS + "/StoreFeedPanels.kt"))
bar, pan = cf.find("StoreBar.Bar("), cf.find("StoreFeedPanels.Panels()")
ok(0 <= bar < pan and "FeedViewer" not in cf,
   "F1 the Cloud page draws StoreFeedPanels.Panels() below its action buttons (StoreBar.Bar)",
   "F1 the Cloud page does not draw the feed panels below its action buttons")
feeds_s = rd("ab_cloud-libs-shared/libs/appstore/src/main/assets/appstore-feeds.json")
fids = [f["id"] for f in json.loads(feeds_s)["feeds"]] if feeds_s else []
ok(fids[:2] == ["commits", "cicd"] and "FeedViewer.feeds(" in fp and "FeedViewer.load(" in fp and "FeedViewer.explain(" in fp and "StoreSourcePrefs.enabled(" in fp,
   "F1 the panels are the declared feeds (Commits, CI-CD), read with FeedViewer, switchable in Sources, a failure explained",
   "F1 StoreFeedPanels is not built from the declared commits + cicd feeds / lost the Sources switch or the failure sentence")
ok(all(x in fp for x in ("e.ref", "e.title", "e.subtitle", "e.link", "e.state", "feed.ok", "feed.bad")) and "summary(" in fp,
   "F1 no per-row data of the old Feed page is dropped (ref, title, subtitle, link, state colour) and each panel adds a tally line",
   "F1 a panel row lost a field of the old Feed page")

# T1
tp = rd(CS + "/TorrentPage.kt")
ok(tp is not None and all(x in strip(tp) for x in ("engine.addMagnet(", "OpenMultipleDocuments", "engine.pause(", "engine.resume(", "engine.remove(", "engine.setSeeding(",
        "Active", "Completed", "TorrentFormat.line(", "EngineState.Unavailable", "online(ctx)", "LinearProgressIndicator")),
   "T1 the Torrent page adds magnets, imports .torrent files, lists Active and Completed with progress/rates/seeds/peers, pauses, resumes, removes, switches seeding, and says engine-unavailable / offline",
   "T1 the Torrent page lost a capability")

# L1
mods = bj["modules"]; app = mods["app"]["depends_on"]
ok("libs:torrent" in mods and mods["libs:torrent"]["dir"].endswith("ab_cloud-libs-shared/libs/torrent") and "libs:torrent" in app,
   "L1 libs:torrent is declared in the Store's build.json module list and the app depends on it",
   "L1 libs:torrent is not in build.json::modules / app.depends_on")
g = rd("ac_cloud-store/app/build.gradle") or ""
ok("project(':libs:torrent')" in g and ":libs:torrent:testDebugUnitTest" in (rd("ac_cloud-store/build.gradle") or ""),
   "L1 app/build.gradle links the lib and storeUnitTests runs its suite",
   "L1 the app does not link libs:torrent or its tests are not in storeUnitTests")
lc = [json.loads(l.rstrip(",")) for l in (rd("aa_cloud-superapp/data/lib-consumers.json") or "").splitlines() if l.startswith('{"category"')]
t = [x for x in lc if x["id"] == "torrent"]
ok(len(t) == 1 and t[0]["compiled_by"] == ["ac_cloud-store"],
   "L1 lib-consumers.json lists torrent, compiled by ac_cloud-store only",
   "L1 lib-consumers.json does not list torrent as compiled by exactly ac_cloud-store")
ok("ab_cloud-libs-shared/libs/torrent/**" in (rd(".github/workflows/ship-cloud-store.yml") or ""),
   "L1 the Store ship workflow watches libs/torrent", "L1 the Store ship workflow does not watch libs/torrent")
ok('"torrent"' in (rd("1_cicd/src/data/lib-classes.json") or "") and '"torrent"' in (rd("1_cicd/src/data/lib-categories.json") or ""),
   "L1 torrent is classified and categorised", "L1 torrent is missing from lib-classes.json / lib-categories.json")
stray = []
base = os.path.join(R, TL, "src", "main")
for dp, dn, fn in os.walk(base):
    for f in fn:
        if not (f.endswith(".kt") or f == "AndroidManifest.xml"): stray.append(os.path.join(dp, f))
lg = "\n".join(l for l in (rd(TL + "/build.gradle") or "").splitlines() if not l.strip().startswith("//"))
ok(os.path.isdir(base) and not stray and "buildConfigField" not in lg and "assets" not in lg and "resValue" not in lg,
   "L1 the lib is code only: no assets, resources, BuildConfig data or gradle injection in the AAR",
   "L1 the lib bakes data: %s" % stray)

# L2
te = strip(rd(TLK + "/TorrentEngine.kt") or ""); tm = strip(rd(TLK + "/TorrentModels.kt") or ""); jb = rd(TLK + "/JlibtorrentBackend.kt") or ""
ok(all(("fun %s(" % f) in te for f in ("addMagnet", "addTorrentFile", "addTorrentBytes", "refresh", "pause", "resume", "remove", "setSeeding", "start", "stop"))
   and "settings" in te and "interface TorrentBackend" in tm and "SessionManager" in jb and "jlibtorrent-android-arm64" in (rd(TL + "/build.gradle") or ""),
   "L2 the engine API (add magnet / .torrent, progress, pause, resume, remove, seed, save path) sits on a TorrentBackend seam with a real libtorrent (jlibtorrent arm64) backend",
   "L2 the engine API or the libtorrent backend is incomplete")
tests = os.path.join(R, TL, "src/test/java/com/diegonmarcos/superapp/torrent")
names = os.listdir(tests) if os.path.isdir(tests) else []
ok(all(n in names for n in ("TorrentEngineTest.kt", "FakeBackend.kt", "TorrentSearchTest.kt", "MagnetTest.kt", "NoDataInAarTest.kt")),
   "L2 the engine is tested against a fake session (no real peers) with the indexer, magnet and no-data tests beside it",
   "L2 a torrent lib test is missing")

# S1
sp = strip(rd(AS + "/StoreSearchPage.kt") or ""); ts = strip(rd(AS + "/StoreTorrentSearch.kt") or ""); th = strip(rd(CS + "/TorrentHost.kt") or "")
ok(pg("search") == ["search-cloudy", "search-android", "search-torrent"] and all(x in sp for x in ('PAGE_CLOUDY = "search-cloudy"', 'PAGE_ANDROID = "search-android"', 'PAGE_TORRENT = "search-torrent"')),
   "S1 Search has the Cloudy | Android | Torrent source subpages (build.json pages and StoreSearchPage)", "S1 Search lost a source subpage")
ok("pageOf(s.id) != sub" in sp and "StoreTorrentSearch.Content(" in sp and 'if (page == PAGE_TORRENT) "torrent-download" else "install"' in sp,
   "S1 Cloudy and Android results take the install path, Torrent results the torrent handler", "S1 results are not routed by source subpage")
ok("host.download(" in ts and "engine.addMagnet(magnet)" in th and "addTorrentBytes(" in th and "TorrentIndexers.all" in th,
   "S1 a torrent result goes to the engine (magnet or fetched .torrent) from the declared indexer list", "S1 torrent results do not reach the engine")
ts_src = strip(rd(TLK + "/TorrentSearch.kt") or "")
lst = re.search(r'val all: List<TorrentIndexer> = listOf\(([^)]*)\)', ts_src)
ok(len(re.findall(r'object \w+ : TorrentIndexer', ts_src)) >= 2 and lst is not None and len([x for x in lst.group(1).split(",") if x.strip()]) >= 2,
   "S1 at least two public torrent indexers are declared in one extensible list", "S1 fewer than two torrent indexers / no declared list")

# C1
cp = strip(rd(CS + "/TorrentConfigPage.kt") or ""); pa = strip(rd(TLK + "/TorrentPaths.kt") or ""); cfg = strip(rd(CS + "/ConfigsPage.kt") or "")
ok("torrent" in pg("settings") and "ConfigsPages.TORRENT -> TorrentConfigPage()" in cfg and 'DEFAULT_RELATIVE = "Cloudy-store/Torrent"' in pa,
   "C1 Configs has a Torrent page; the default folder is Downloads/Cloudy-store/Torrent", "C1 Configs > Torrent is missing or the default folder changed")
ok("mkdirs" in pa and "defaultDir(" in pa and "TorrentPaths.prepare(" in (rd(TLK + "/TorrentSettings.kt") or ""),
   "C1 the default folder is created when missing", "C1 the folder is not created when missing")
ok("TorrentEngines.volumes(" in cp and "OpenDocumentTree" in cp and "pathFromTreeDocId" in cp and "isExternalVolume" in cp,
   "C1 an external drive can be chosen (listed volumes and the system folder picker)", "C1 no external-drive choice")
ok("OpenMultipleDocuments" in cp and "importUris(" in cp and "addTorrentBytes(" in th,
   "C1 .torrent files are picked and added to the engine", "C1 no .torrent import")
ok('PermsLink.open(ctx, "app", listOf("all_files"))' in cp and "requestPermissions" not in cp and "MANAGE_APP_ALL_FILES" not in cp,
   "C1 the all-files grant goes through libs:perms (no permission API of the Store's own)", "C1 the Store asks for the grant itself")

# P1
mf = rd("ac_cloud-store/app/src/main/AndroidManifest.xml") or ""
ok("android.permission.MANAGE_EXTERNAL_STORAGE" in mf and "all_files" in bj["perms"]["needs"] and "android.permission.INTERNET" in mf + (rd(TL + "/src/main/AndroidManifest.xml") or ""),
   "P1 INTERNET and all-files access are declared (manifest + perms.needs)", "P1 the manifest / perms.needs lost a torrent permission")
ok(all(x not in strip(rd(TLK + "/" + f) or "") for f in os.listdir(os.path.join(R, TLK)) if f.endswith(".kt") for x in ("requestPermissions", "ACTION_MANAGE_ALL_FILES", "isExternalStorageManager")),
   "P1 the lib calls no permission API (libs:perms is the one place)", "P1 the lib asks for a permission itself")
sys.exit(fails)
PY
}

PASS=0; FAILN=0
out="$(check "$ROOT")"; rc=$?
printf '%s\n' "$out"
[ $rc -eq 0 ] && echo "real tree: all checks pass" || { echo "real tree: $rc failure(s)"; FAILN=$((FAILN+rc)); }

# ── mutation: each planted defect must turn a check red ──
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
base="$W/base"; mkdir -p "$base"
for f in $FILES; do [ -f "$ROOT/$f" ] && mkdir -p "$base/$(dirname "$f")" && cp "$ROOT/$f" "$base/$f"; done
for d in "$TL/src/test/java/com/diegonmarcos/superapp/torrent"; do mkdir -p "$base/$d"; cp "$ROOT/$d"/*.kt "$base/$d/"; done
cp "$ROOT/$TLK"/*.kt "$base/$TLK/"
M=0
mutate() {  # mutate <name> <file> <from> <to>  (python replace; the check must then fail)
  local name="$1" file="$2" from="$3" to="$4" mt="$W/m"
  rm -rf "$mt"; cp -r "$base" "$mt"
  python3 - "$mt/$file" "$from" "$to" <<'PY' || { echo "  MUTANT-ERROR: $name (pattern not found)"; M=$((M+1)); return; }
import sys
p, a, b = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if a not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(a, b))
PY
  if check "$mt" >/dev/null 2>&1; then echo "  MUTANT SURVIVED: $name"; M=$((M+1)); else echo "  mutation caught: $name"; fi
}
mutate "Feed comes back to the island" ac_cloud-store/build.json '"search",
      "torrent",
      "access"' '"search",
      "feed",
      "access"'
mutate "Cloud drops the feed panels" "$AS/StoreCloudFragment.kt" 'StoreFeedPanels.Panels()' 'Unit'
mutate "feed panels above the buttons" "$AS/StoreCloudFragment.kt" 'StoreBar.Bar(StoreBar.Verbs(' 'StoreFeedPanels.Panels(); StoreBar.Bar(StoreBar.Verbs('
mutate "panels ignore the Sources switch" "$AS/StoreFeedPanels.kt" 'StoreSourcePrefs.enabled(' 'StoreSourcePrefsX.enabled('
mutate "panel row loses the link" "$AS/StoreFeedPanels.kt" 'e.link' 'e.title'
mutate "Torrent page not hosted" "$CS/MainActivity.kt" 'if (selected == TAB_TORRENT) TorrentPage()' ''
mutate "Torrent page loses import" "$CS/TorrentPage.kt" 'OpenMultipleDocuments' 'OpenDocument'
mutate "lib leaves the module list" ac_cloud-store/build.json '"libs:torrent": {"dir"' '"libs:torrentx": {"dir"'
mutate "app stops linking the lib" ac_cloud-store/app/build.gradle "project(':libs:torrent')" "project(':libs:core')"
mutate "ship stops watching the lib" .github/workflows/ship-cloud-store.yml 'ab_cloud-libs-shared/libs/torrent/**' 'ab_cloud-libs-shared/libs/torrentx/**'
mutate "lib bakes a BuildConfig field" "$TL/build.gradle" "namespace 'com.diegonmarcos.superapp.torrent'" "namespace 'com.diegonmarcos.superapp.torrent'
    defaultConfig { buildConfigField 'String', 'X', '\"y\"' }"
mutate "engine loses seed control" "$TLK/TorrentEngine.kt" 'fun setSeeding(' 'fun setSeedingX('
mutate "Search loses the Torrent subpage" "$AS/StoreSearchPage.kt" 'PAGE_TORRENT = "search-torrent"' 'PAGE_TORRENT = "search-torrentx"'
mutate "torrent results install as APKs" "$AS/StoreSearchPage.kt" 'if (page == PAGE_TORRENT) "torrent-download" else "install"' '"install"'
mutate "download does not reach the engine" "$CS/TorrentHost.kt" 'engine.addMagnet(magnet)' 'Unit'
mutate "one indexer left" "$TLK/TorrentSearch.kt" 'val all: List<TorrentIndexer> = listOf(ApiBay, Yts, InternetArchive)' 'val all: List<TorrentIndexer> = listOf(ApiBay)'
mutate "default folder renamed" "$TLK/TorrentPaths.kt" 'DEFAULT_RELATIVE = "Cloudy-store/Torrent"' 'DEFAULT_RELATIVE = "Torrent"'
mutate "no external drive choice" "$CS/TorrentConfigPage.kt" 'TorrentEngines.volumes(' 'TorrentEngines.volumez('
mutate "no all-files declaration" ac_cloud-store/build.json '"battery_opt",
      "all_files"' '"battery_opt"'
mutate "the Store asks for the grant itself" "$CS/TorrentConfigPage.kt" 'PermsLink.open(ctx, "app", listOf("all_files"))' 'ctx.requestPermissions()'
[ $M -eq 0 ] && echo "all planted defects caught" || { echo "$M defect(s) not caught"; FAILN=$((FAILN+M)); }
[ $FAILN -eq 0 ] && { echo "test-store-torrent: PASS"; exit 0; } || { echo "test-store-torrent: FAIL ($FAILN)"; exit 1; }
