#!/usr/bin/env bash
# Tester (#812, supersedes #808): the Store's space check, its cache eviction,
# the progress popup and the Store badge.
#
# Seen on the device: ~40 GB free and the Store still said "no room", because
# it measured against the declared 1073 MB cache bound; the same bound evicted
# downloaded-but-not-installed APKs while 77 updates waited, so Download all
# re-fetched in a loop. The behaviour is proven in Robolectric (StoreAutoTest
# "812 …", NotifyGroupsAlertsTest "812 …"); this guards the wiring and proves
# itself by mutation — every mutant is a realistic regression and must go RED.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
exec python3 - "$APP" "$ROOT/ab_cloud-libs-shared/libs" <<'PY'
import json, re, sys
app, lib = sys.argv[1], sys.argv[2]
A = lib + "/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
S = app + "/app/src/main/java/com/diegonmarcos/superapp/"
FILES = {"cache": lib + "/updater/src/main/java/com/diegonmarcos/superapp/updater/cache/ApkCache.kt",
         "gradle": lib + "/updater/build.gradle", "stages": A + "StoreStages.kt", "auto": A + "StoreAuto.kt",
         "page": A + "StoreCloudFragment.kt", "shell": S + "ShellActivity.kt", "app": S + "App.kt",
         "bj": app + "/build.json"}
src = {k: open(p, encoding="utf-8").read() for k, p in FILES.items()}
P = F = 0
def ok(m):
    global P; P += 1; print("  PASS: " + m)
def bad(m):
    global F; F += 1; print("  FAIL: " + m)
def code(t):
    return "\n".join(l.split("//")[0] for l in re.sub(r"/\*.*?\*/", "", t, flags=re.S).splitlines())
def fun(t, name, indent="    "):
    m = re.search(r"\n%s(?:private |internal |override )?fun %s\(.*?(?=\n%s(?:private |internal |override |@|fun |class |const |val |var |object |/\*\*)|\n\}|\Z)"
                  % (indent, re.escape(name), indent), t, re.S)
    return m.group(0) if m else ""
def apk_cache(s):
    try: return json.loads(s["bj"])["release"]["apk_cache"]
    except Exception: return {}

CHECKS = [
    ("the cache bound is derived from real free storage within declared min/max/reserve",
     lambda s: "(free + cached - reserve).coerceIn(min, maxOf(min, max))" in code(s["cache"])
               and "StatFs(" in code(s["cache"])
               and all(k in apk_cache(s) for k in ("min_bytes", "max_bytes", "reserve_bytes"))
               and apk_cache(s).get("max_bytes", 0) > 1073741824
               and '"APK_CACHE_RESERVE_BYTES"' in s["gradle"] and '"APK_CACHE_MIN_BYTES"' in s["gradle"]),
    ("eviction takes only superseded or landed entries — never pending, never a partial",
     lambda s: ".filter { it.second >= 0 }" in fun(code(s["cache"]), "evict")
               and "if (superseded(it)) 0 else if (isLanded(ctx, it)) 1 else -1" in fun(code(s["cache"]), "evict")
               and "!it.partial && it.record != null" in fun(code(s["cache"]), "evict")
               and "val max = bound(ctx)" in fun(code(s["cache"]), "evict")),
    ("the Store's room is the real free storage, not the fixed cache bound",
     lambda s: "var room: (Context) -> Long = { c -> ApkCache.room(c) }" in code(s["stages"])
               and "APK_CACHE_MAX_BYTES" not in code(s["stages"])),
    ("what does not fit waits for a round (download → install → clear), not a loop of re-fetches",
     lambda s: "s.phase = DOWNLOAD" in fun(code(s["auto"]), "run")
               and "s.count(INSTALLED) > landedBefore" in fun(code(s["auto"]), "run")
               and "deferred.forEach { fail(it, DOWNLOAD" in fun(code(s["auto"]), "run")),
    ("a no-room says the real numbers (progress line and /api/store/auto)",
     lambda s: "ApkCache.roomText(ctx)" in fun(code(s["auto"]), "download")
               and '.put("room",' in fun(code(s["auto"]), "json")),
    ("Cancel keeps the partial and the package queued for the resume",
     # #1551 the stop reason now goes through stopWhy() (user cancel vs Android stopping the job); the invariant is unchanged:
     # a cancel mid-download sets the item back to QUEUED and stops for the next pass to resume from its .part.
     lambda s: "item.status = QUEUED\n                return stop(ctx, s, stopWhy(" in code(s["auto"])
               and "cancelled by the user" in fun(code(s["auto"]), "stopWhy")
               and "UpdateProgress.beginDownload()" in fun(code(s["auto"]), "run")),
    ("no progress popup: the SuperApp never attaches the update overlay",
     lambda s: "UpdateOverlayFragment" not in code(s["shell"]) and ".add(" not in fun(code(s["shell"]), "handleUpdateState")),
    # #1524 the batch outcome lives in the Store's one Activity panel (StoreJobs rows), so there is no report() left to hold it
    ("no batch dialog: Download all and Update all report through the Activity panel, never a dialog",
     lambda s: "AlertDialog" not in fun(code(s["page"]), "downloadAll")
               and "AlertDialog" not in fun(code(s["page"]), "updateAll")
               and "StoreStages.downloadAll(ctx, targets)" in fun(code(s["page"]), "downloadAll")
               and "StoreStages.updateAll(ctx, targets)" in fun(code(s["page"]), "updateAll")),
    ("#894 no Store badge in SuperApp: Store notifications are Cloud Store's, and the chain's pending hook stays a no-op here",
     lambda s: not any(p.get("id") == "store_updates"
                       for p in json.loads(s["bj"])["ui"]["notification_center"]["producers"])
               and "StoreBadgeNotifier" not in code(s["app"])
               and "runCatching { onPending(ctx, pending(s)) }" in code(s["auto"])
               and "StoreAuto.onPending(c.applicationContext, 0)" in fun(code(s["page"]), "onResume")),
]
def validate(s): return [n for n, c in CHECKS if not c(s)]

print("== T1: real free storage, no pending eviction, no popup, Store badge ==")
v = validate(src)
for n, _ in CHECKS: (bad if n in v else ok)(n)

print("== T2: the validator sees each regression (mutation) ==")
MUTANTS = [
    ("the bound is the fixed max again", "cache", "(free + cached - reserve).coerceIn(min, maxOf(min, max))", "max"),
    ("eviction takes pending entries", "cache", ".filter { it.second >= 0 }", ""),
    ("eviction takes partials", "cache", "!it.partial && it.record != null", "it.record != null"),
    ("room goes back to the 1073 MB bound", "stages", "var room: (Context) -> Long = { c -> ApkCache.room(c) }",
     "var room: (Context) -> Long = { c -> com.diegonmarcos.superapp.updater.BuildConfig.APK_CACHE_MAX_BYTES - ApkCache.totalBytes(c) }"),
    ("no rounds: what does not fit just fails", "auto", "                    s.phase = DOWNLOAD\n", ""),
    ("the room numbers leave the API", "auto", '.put("room",', '.put("roomless",'),
    ("Cancel marks the package failed", "auto", "item.status = QUEUED\n                return stop(", "item.status = FAILED\n                return stop("),
    ("the overlay comes back", "shell", "supportFragmentManager.findFragmentByTag(UPDATE_OVERLAY_TAG)?.let {",
     "supportFragmentManager.beginTransaction().add(R.id.overlay_container, com.diegonmarcos.superapp.updater.UpdateOverlayFragment.newInstance(), UPDATE_OVERLAY_TAG).commitAllowingStateLoss()\n            supportFragmentManager.findFragmentByTag(UPDATE_OVERLAY_TAG)?.let {"),
    ("the batch report is a dialog again", "page", "            StoreStages.downloadAll(ctx, targets)\n", "            StoreStages.downloadAll(ctx, targets)\n            main.post { AlertDialog.Builder(requireActivity()).show() }\n"),
    ("Update all reports in a dialog again", "page", "            StoreStages.updateAll(ctx, targets)\n", "            StoreStages.updateAll(ctx, targets)\n            main.post { AlertDialog.Builder(requireActivity()).show() }\n"),
    ("SuperApp wires a Store badge again", "app", "StoreRetirement.run(this)", "StoreBadgeNotifier.update(this, 1)"),
    ("opening the Store does not clear the badge", "page", "StoreAuto.onPending(c.applicationContext, 0)", "Unit"),
]
for name, key, old, new in MUTANTS:
    if src[key].count(old) != 1:
        bad("mutant '%s' no longer applies — re-aim it" % name); continue
    m = dict(src); m[key] = src[key].replace(old, new)
    (ok if validate(m) else bad)(("mutation goes RED: " if validate(m) else "mutation stayed GREEN: ") + name)
print("== RESULT(#812 store no-room): %d passed, %d failed ==" % (P, F))
sys.exit(1 if F else 0)
PY
