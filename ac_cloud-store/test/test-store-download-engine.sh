#!/usr/bin/env bash
# #1517 The Store's ONE download engine (libs:appstore dl). Static, no build, no device. The behaviour is pinned by the unit tests
# (DownloadEngineTest against a fake HTTP server, CheckGateTest, EngineGuardTest); this is the fast pre-build guard for the shape:
#  E1 only the user's own buttons mint a UserAction (DownloadsPanel, StoreProgressPanel); no other UI file stops engine work
#  E2 the engine package is pure JVM and never reads the process-wide cancel flag
#  E3 a tab switch asks (force = false), starts no second updates-first chain, and its check path never names the engine
#  E4 the transfer goes through the engine (StoreStages.fetch) and its legs keep Fleet's order mesh, release, ghcr
#  E5 the declared limits exist: 2 concurrent, a retry cap, the 30 s floor via RetryBackoff, LRU pruning that skips parts
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
python3 -I - "$ROOT" <<'PY'
import os, re, sys, json
R = sys.argv[1]
fails = 0
def code(p):
    s = open(os.path.join(R, p), encoding="utf-8").read()
    s = re.sub(r'/\*.*?\*/', '', s, flags=re.S)
    return re.sub(r'(?m)(?<![:"])//[^\n]*', '', s)
def ok(c, good, bad):
    global fails
    print(("  ok   " if c else "  FAIL ") + (good if c else bad))
    if not c: fails += 1
A = "ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
U = "ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater/"
dl = sorted(f for f in os.listdir(os.path.join(R, A + "dl")) if f.endswith(".kt"))
ok({"DownloadEngine.kt", "RangeFetch.kt", "DownloadStore.kt", "DownloadCache.kt", "CheckGate.kt", "DownloadModel.kt"} <= set(dl), "the engine's six files exist: " + ", ".join(dl), "an engine file is missing: " + ", ".join(dl))

# E1
mint = []
for dp, _, fs in os.walk(os.path.join(R, "ab_cloud-libs-shared/libs")):
    if "/src/main/" not in dp or not any(x in dp for x in ("/appstore/", "/updater/")): continue
    for f in fs:
        if f.endswith(".kt") and f not in ("DownloadsPanel.kt", "StoreProgressPanel.kt", "DownloadEngine.kt"):
            if "UserAction.tap(" in code(os.path.relpath(os.path.join(dp, f), R)): mint.append(f)
ok(not mint, "E1 only DownloadsPanel and StoreProgressPanel mint a UserAction", "E1 these mint a UserAction: " + ", ".join(mint))
eng = code(A + "dl/DownloadEngine.kt")
ok(re.search(r"fun pause\([^)]*by: UserAction\)", eng) and re.search(r"fun cancel\([^)]*by: UserAction\)", eng) and re.search(r"fun cancelAll\(by: UserAction\)", eng), "E1 pause, cancel and cancelAll require a UserAction", "E1 a stopping verb no longer requires a UserAction")

# E2
bad = [f for f in dl if re.search(r"(?m)^import (android\.|androidx\.fragment|androidx\.lifecycle|androidx\.compose)", code(A + "dl/" + f))]
ok(not bad, "E2 the engine package imports no android, fragment, lifecycle or compose type", "E2 UI imports in: " + ", ".join(bad))
flag = [f for f in dl + ["../StoreEngine.kt"] if re.search(r"cancelRequested|requestCancel|cancelNow", code(A + "dl/" + f))]
ok(not flag, "E2 the engine never reads the process-wide cancel flag", "E2 reads the global cancel flag: " + ", ".join(flag))

# E3
frag = code(A + "StoreCloudFragment.kt")
eff = frag[frag.index("LaunchedEffect(tab) {"):][:900]
eff = eff[:eff.index("\n        }\n")] if "\n        }\n" in eff else eff
ok("checkAll(ctx, rows, force = false)" in eff and "StoreEngine" not in eff, "E3 the tab effect asks for a cached check and never names the engine", "E3 the tab effect forces a check or touches the engine")
ok("if (force) ConstellationWorker.kick" in frag, "E3 only a forced (user) refresh kicks the auto chain", "E3 a tab switch kicks the auto chain again")
ok("StoreChecks.mayStartUpdatesFirst()" in code(A + "StorePriority.kt"), "E3 startUpdatesAsync is throttled", "E3 startUpdatesAsync starts a chain per call again")
se = code(A + "StoreEngine.kt")
chk = se[se.index("object StoreChecks"):]
ok(not re.search(r"\b(StoreEngine|DownloadEngine|engineOrNull)\b", chk), "E3 StoreChecks (the update-check path) does not reference the engine", "E3 the check path touches the engine")

# E4
st = code(A + "StoreStages.kt")
ok("StoreEngine.fetch(ctx, app)" in st and "StoreJobs.runner.download(app.pkg)" not in st, "E4 StoreStages.fetch hands the transfer to the engine", "E4 StoreStages.fetch does not use the engine")
legs = code(U + "source/EngineLegs.kt")
order = re.findall(r'Leg\("(\w+)"\)', legs)
ok(order == ["mesh", "release", "ghcr"], "E4 the engine's legs are mesh, release, ghcr", "E4 the legs are " + str(order))
fk = code(U + "Fleet.kt")
ok("listOf(MeshMirrorSource, ReleaseSource, GhcrSource)" in fk, "E4 Fleet's declared source order is unchanged", "E4 Fleet's source order moved: update EngineLegs with it")

# E5
b = json.load(open(os.path.join(R, "ac_cloud-store/build.json")))
d = b.get("downloads", {})
ok(d.get("max_concurrent") == 2 and d.get("max_retries", 0) >= 1 and d.get("cache_max_mb", 0) > 0, "E5 build.json::downloads declares 2 concurrent, a retry cap and a cache bound", "E5 build.json::downloads is missing or wrong")
ok("RetryBackoff.afterFailures" in eng and "Thread.sleep" not in eng, "E5 backoff is RetryBackoff (30 s floor), with no sleeping loop", "E5 the engine has its own backoff or sleeps")
cache = code(A + "dl/DownloadCache.kt")
ok('endsWith(".apk") && !it.name.endsWith(".part")' in cache, "E5 pruning only ever considers finished .apk files, never a .part", "E5 pruning may delete a .part")
sys.exit(1 if fails else 0)
PY
