#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #802 I4 bookmarks, downloads, add-to-home: stored, reachable     ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# A store nobody declared cannot move to a new phone (and fails the fleet
# config guard); a DownloadListener nobody set means a tapped download does
# nothing; a download without the page's cookies fails for any signed-in file.
# Matched in COMMENT-STRIPPED Kotlin.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import json, os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, MANIFEST, kotlin, build_json, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    g = lambda n: src.get(os.path.join(LIB, n + ".kt"), "")
    frag, bm, dl = g("BrowserHostFragment"), g("BrowserBookmarks"), g("BrowserDownloads")
    api = src.get(os.path.join(APP, "debugapi", "BrowserDebugApi.kt"), "")
    m = json.load(open(os.path.join(root, MANIFEST), encoding="utf-8"))["stores"]
    for store, cls, owner in (("browser_bookmarks", "config", bm), ("browser_downloads", "device", dl)):
        ok('getSharedPreferences("%s"' % store in owner, "%s is the store its class opens" % store)
        ok(m.get(store, {}).get("class") == cls, "%s is declared in the fleet manifest as %s" % (store, cls))
    ok("setDownloadListener" in frag and "download(dlUrl, ua, disposition, mime)" in frag, "a tapped download reaches DownloadManager")
    ok('addRequestHeader("Cookie"' in dl and 'addRequestHeader("User-Agent"' in dl, "a download carries the page's cookies and agent")
    ok('browserSettings.string("download_dir")' in frag, "downloads land in the download_dir setting")
    ok("ShortcutManagerCompat.requestPinShortcut" in frag, "add to home pins a launcher shortcut")
    ok("addToHome(url, wv?.title.orEmpty(), wv?.favicon)" in frag and "IconCompat.createWithBitmap" in frag
       and "IconCompat.createWithResource(ctx, ctx.applicationInfo.icon)" in frag,
       "#1604 the pinned shortcut uses the page's real title + favicon, falling back to the app icon")
    ok("BrowserBookmarkOps.add(all()" in bm and "BrowserBookmarkOps.moveFolder(all()" in bm and "BrowserBookmarkOps.deleteFolder(all()" in bm,
       "the store applies the pure (tested) folder rules, not its own")
    ok("fun showHistory(" in frag and "BrowserListScreen(\"History\"" in frag, "history is the Compose list page")
    ok(not __import__("re").search(r"(?<!Horizontal)ScrollView", frag), "the View history page is gone")
    for op in ("bookmarks", "bookmarks/add", "bookmarks/remove", "bookmarks/folders", "bookmarks/folder/rename",
               "bookmarks/folder/delete", "downloads", "downloads/enqueue", "downloads/clear"):
        ok('Op("%s"' % op in api and '"%s" ->' % op in api, "route %s is documented and handled" % op)
    ok('"add confirm=1: this deletes' in api, "deleting a folder over the API needs confirm=1")

FRAG = LIB + "/BrowserHostFragment.kt"
DL = LIB + "/BrowserDownloads.kt"
main("library wiring", check, [
    ("downloads lose the cookie header", DL, 'addRequestHeader("Cookie"', 'addRequestHeader("X-None"', "cookies"),
    ("the download listener is dropped", FRAG, "setDownloadListener {", "run {", "tapped download"),
    ("bookmarks drop out of the manifest", "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json", '"browser_bookmarks"', '"browser_bookmarks_x"', "browser_bookmarks is declared"),
    ("folder delete without confirm", "ac_cloud-browser/app/src/main/java/com/diegonmarcos/cloudbrowser/debugapi/BrowserDebugApi.kt", '"add confirm=1: this deletes', '"go ahead', "confirm=1"),
])
PYEOF
