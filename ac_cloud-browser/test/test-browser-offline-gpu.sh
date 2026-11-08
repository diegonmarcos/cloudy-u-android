#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1518 Cloud-Browser offline copy, GPU page, refresh rate: wired  ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The rules (crawl scope, charging + Wi-Fi gate, pin list, Cached sites ops, refresh-rate toggle, GPU page words) are
# JVM-tested in app/src/test/.../offline (OfflineMirrorTest, GpuPageModelTest). This holds the wiring:
#   O1 web-core holds the one copy: same-origin scope, the 30 s retry floor, serving cache-first (the cache is read BEFORE the network)
#   O2 the shared host serves a pinned site through BrowserHostHooks.intercept, and the app plugs web-core's SiteMirrorStore in
#   O3 the page menu declares Pin this site offline (a toggle) and Download full site, each with its runAction branch and its fact
#   O4 floor796.com is pinned by declaration (build.json::pin_offline, baked as BuildConfig.PIN_OFFLINE)
#   O5 Domains > Files Cached draws Cached sites with Refresh, Unpin and Delete
#   O6 Configs > GPU is a declared page: provider, renderer, adapter or reason, DevTools intent, Retest, owner steps
#   O7 the max refresh rate follows fullscreen events only, reverts on stop, is off under the battery saver (Super Saving), nothing polls
#   O8 no Chrome and no Custom Tab anywhere in what this adds
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, re, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

WC = "ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web"
GRADLE = "ac_cloud-browser/app/build.gradle"
B = "ac_cloud-browser/build.json"

def check(root, ok):
    bj = build_json(root)
    b = bj["ui"]["browser"]
    src = kotlin(root, LIB, APP, WC)
    def f(p): return src.get(p, "")
    mirror, android = f(WC + "/SiteMirror.kt"), f(WC + "/SiteMirrorAndroid.kt")
    frag, hooks = f(LIB + "/BrowserHostFragment.kt"), f(LIB + "/BrowserHostHooks.kt")
    off, gpu = f(APP + "/offline/SiteOffline.kt"), f(APP + "/offline/GpuPage.kt")
    ui, main_kt = f(APP + "/sites/SitesUi.kt"), f(APP + "/MainActivity.kt")
    gradle = re.sub(r"(?m)^\s*//[^\n]*", "", open(os.path.join(root, GRADLE), encoding="utf-8").read())
    # O1
    ok("fun sameOrigin(" in mirror and "scheme" in mirror and "hostKey" in mirror, "O1 scope is the same origin (scheme, host, port)")
    ok("RETRY_FLOOR_MS = 30_000L" in mirror and "maxOf(asked, RETRY_FLOOR_MS)" in mirror, "O1 a retry never comes sooner than 30 s")
    ok("fun allowed(env: Env, userTap: Boolean): Boolean = userTap || (env.charging && env.wifi)" in mirror, "O1 an unattended download needs the charger and Wi-Fi, a tap needs neither")
    i = android.find("fun intercept(")
    ok(i >= 0 and 0 < android.find("c.serve(", i) < android.find("http.fetch(", i), "O1 the copy is read before the network (cache-first, never expiring)")
    ok("fun refresh(" in mirror and "cache.urls(key)" in mirror, "O1 Refresh is the only thing that refetches a held file")
    # O2
    ok("return BrowserHostHooks.intercept?.invoke(r)" in frag, "O2 the shared host serves through the hook, after the tracker block")
    ok("var intercept" in hooks and "var gpuPage" in hooks and "var action" in hooks, "O2 the hooks default to nothing")
    ok("BrowserHostHooks.intercept = { req -> SiteMirrorStore.intercept(app, pinned(app), req) }" in off, "O2 the app plugs web-core's store in")
    # O3
    menu = {m["id"]: m for m in b["menu"]["items"]}
    ok(menu.get("pin_site", {}).get("kind") == "toggle" and menu["pin_site"].get("checked") == "site_pinned", "O3 Pin this site offline is a toggle on site_pinned")
    ok("download_site" in menu and menu["download_site"]["section"] == "actions", "O3 Download full site is a page action")
    ok('"pin_site", "download_site" ->' in frag and '"site_pinned" to' in frag, "O3 both have their branch and the fact is produced")
    ok('"pin_site" ->' in off and '"download_site" ->' in off and "userTap = true" in off and "userTap = false" in off, "O3 download is a tap, a pin queues the unattended run")
    # O4
    ok(bj.get("pin_offline") == ["floor796.com"], "O4 floor796.com is pinned by declaration")
    ok("PIN_OFFLINE" in gradle and "BuildConfig.PIN_OFFLINE" in off, "O4 the declaration is baked and read")
    # O5
    d = bj["ui"]["domains"]["texts"]
    ok(all(k in d for k in ("cached_sites", "refresh", "unpin", "delete", "cached_empty")), "O5 the Cached sites words are declared (ui.domains.texts)")
    ok("CachedSites.rows(" in ui and 'testTag("domains:cached:refresh:' in ui and 'testTag("domains:cached:unpin:' in ui and 'testTag("domains:cached:delete:' in ui,
       "O5 Files Cached lists the sites with Refresh, Unpin and Delete")
    ok("run.files" in ui and "human(run.bytes)" in ui, "O5 the running download shows its file count and size")
    # O6
    cfgs = b["configs"]
    ok(cfgs["native"].get("gpu") == ["gpu"] and [s["id"] for s in b["settings_sections"]][-1] == "gpu", "O6 GPU is a declared Configs page holding the gpu block")
    ok('"gpu" -> BrowserHostHooks.gpuPage?.invoke(this)' in frag and "BrowserHostHooks.gpuPage = {" in main_kt, "O6 the host draws the app's gpu block")
    ok("WebViewCompat.getCurrentWebViewPackage" in gpu and "GpuProbe.probe(ctx, force = round > 0)" in gpu and "GpuPageModel.rows(" in gpu, "O6 provider, version and probe on every Retest")
    ok("GpuPageModel.DEV_UI_ACTION" in gpu and "Open WebView DevTools" in gpu and 'Text("Retest")' in gpu and "GpuPageModel.steps" in gpu, "O6 DevTools button, Retest and the owner steps")
    ok(f(WC + "/GpuPage.kt").count("com.android.webview.SHOW_DEV_UI") == 1, "O6 the DevTools action is declared once")
    # O7
    ok("CloudChromeClient.fullscreenListener" in main_kt and "refreshRate.fullscreen(on)" in main_kt, "O7 fullscreen events drive the rate")
    ok("fun stop() { release()" in android and "refreshRate.stop()" in main_kt, "O7 leaving the app reverts the rate")
    ok("isPowerSaveMode" in android and "ACTION_POWER_SAVE_MODE_CHANGED" in android, "O7 Super Saving (the system battery saver it switches on) turns it off at once")
    ok("fun wantMax(fullscreen: Boolean, gpuCanvas: Boolean, superSaving: Boolean): Boolean = fullscreen && gpuCanvas && !superSaving" in f(WC + "/GpuPage.kt"), "O7 only a fullscreen GPU canvas, never under Super Saving")
    ok(not re.search(r"Timer\(|scheduleAtFixedRate|AlarmManager|WorkManager|while \(true\)", android), "O7 nothing polls")
    # O8
    allnew = "\n".join([mirror, android, off, gpu, hooks, f(WC + "/GpuPage.kt")])
    ok(not re.search(r"com\.android\.chrome|openInChrome|CustomTabs|CustomTabsIntent", allnew), "O8 no Chrome and no Custom Tab")

main("offline-gpu", check, [
    ("the retry floor drops", WC + "/SiteMirror.kt", "RETRY_FLOOR_MS = 30_000L", "RETRY_FLOOR_MS = 1_000L", "O1 a retry never"),
    ("the gate lets an unattended run go on battery", WC + "/SiteMirror.kt", "userTap || (env.charging && env.wifi)", "userTap || env.wifi", "O1 an unattended download"),
    ("serving asks the network first", WC + "/SiteMirrorAndroid.kt", "if (!refresh) c.serve(host, url, range, origin)?.let { return response(it) }\n        val old = c.lookup(host, url)", "val old = c.lookup(host, url)", "O1 the copy is read before"),
    ("the host stops serving the copy", LIB + "/BrowserHostFragment.kt", "return BrowserHostHooks.intercept?.invoke(r)", "return null", "O2 the shared host"),
    ("the pin default is dropped", B, '"pin_offline": ["floor796.com"]', '"pin_offline": []', "O4 floor796.com"),
    ("Delete leaves the Cached sites list", APP + "/sites/SitesUi.kt", 'testTag("domains:cached:delete:', 'testTag("domains:cached:gone:', "O5 Files Cached lists"),
    ("the GPU page loses Retest", APP + "/offline/GpuPage.kt", 'Text("Retest")', 'Text("Again")', "O6 DevTools button"),
    ("the rate stays up when the app stops", APP + "/MainActivity.kt", "refreshRate.stop()", "", "O7 leaving the app"),
    ("Chrome comes back", APP + "/offline/GpuPage.kt", "internal fun openDevTools", "private const val X = \"com.android.chrome\"\ninternal fun openDevTools", "O8 no Chrome"),
])
PYEOF
