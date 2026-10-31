#!/usr/bin/env bash
# #903 Store batch — A RUNNING BATCH HOLDS A dataSync FOREGROUND SERVICE.
#
# MEASURED 2026-10-07 (Android 14, One UI): "Download all → Install all"
# (23 apps, ~1.1 GB) ran on a plain background thread with NO foreground
# service. The moment the user left the Store, Android put the process in
# CAC (cached) and Samsung's FreecessController froze it (logcat
# FreecessHandler/handleLcdOnFreeze); netpolicy blocked its network until the
# owner's device tuning allowlisted it. The batch died mid-download
# ("✗ cloud-lib-ml-l-image-mlkit · failed at downloading · DNS: cannot
# resolve …") and the Store's loopback API stopped answering. A foreground
# service is what Android AND Samsung exempt from the freeze and from the
# background network restriction.
#
#   F1  the service exists (libs:updater BatchForegroundService), enters the
#       foreground with ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)
#       and holds an ongoing notification; a refused start does not stop the batch
#   F2  every host that runs the Store page (ac_cloud-store, aa_cloud-superapp)
#       declares the service with android:foregroundServiceType="dataSync" and
#       holds FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC; libs:updater
#       declares both too, so no consumer of the workers can forget
#   F3  the batch start/stop paths hold it: downloadAll / updateAll (not a dry
#       run), the auto chain (StoreAuto.run, begin in the try, end in its
#       finally), and a single verb (StoreStages.named → a row's Install, the
#       API's download/install/auto) — refcounted, so a verb inside a batch
#       neither starts nor stops the service
#   F4  the notification text IS the Store bar line: StoreStages wires
#       BatchForeground.text to StoreStages.progress()?.text (what
#       /api/store/progress returns), the host's icon and launch target, and
#       progress re-posts are throttled to >= 1 s
#   F5  the CoroutineWorkers that download or install (ConstellationWorker,
#       UpdateWorker, ApkInstallWorker) declare getForegroundInfo() and enter
#       BatchForeground.foregroundInfo(...) (type dataSync) through holdDataSync,
#       i.e. only for user-initiated runs within core DataSyncBudget (#fgs-timeout:
#       Android 15's 6 h/24 h dataSync cap); the service handles onTimeout
#   F6  the fleet-alerts guard classifies the service's re-post as `progress`
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$(cd "$APP/.." && pwd)"
LIBS="$ROOT/ab_cloud-libs-shared/libs"
UPD="$LIBS/updater/src/main/java/com/diegonmarcos/superapp/updater"
STORE="$LIBS/appstore/src/main/java/com/diegonmarcos/superapp/appstore"
SVC="$UPD/BatchForegroundService.kt"
STAGES="$STORE/StoreStages.kt"
AUTO="$STORE/StoreAuto.kt"
CW="$STORE/ConstellationWorker.kt"
UW="$UPD/UpdateWorker.kt"
AIW="$UPD/ApkInstallWorker.kt"
LIBMAN="$LIBS/updater/src/main/AndroidManifest.xml"
STOREMAN="$ROOT/ac_cloud-store/app/src/main/AndroidManifest.xml"
SUPERMAN="$APP/app/src/main/AndroidManifest.xml"
POLICY="$ROOT/1_cicd/src/data/fleet-alerts-guard.json"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$SVC" "$STAGES" "$AUTO" "$CW" "$UW" "$AIW" "$LIBMAN" "$STOREMAN" "$SUPERMAN" "$POLICY"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

echo "== F1: the service enters the foreground as dataSync =="
grep -q 'class BatchForegroundService : Service()' "$SVC" \
  && ok "BatchForegroundService is a Service in libs:updater" || bad "no BatchForegroundService"
grep -q 'ServiceCompat.startForeground(this, BatchForeground.ID, BatchForeground.notification(this),' "$SVC" \
  && grep -q 'ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0' "$SVC" \
  && ok "onStartCommand → ServiceCompat.startForeground(..., FOREGROUND_SERVICE_TYPE_DATA_SYNC)" \
  || bad "the service does not start foreground with type dataSync"
grep -q '.setOngoing(true)' "$SVC" && grep -q '.setOnlyAlertOnce(true)' "$SVC" && grep -q '.setSilent(true)' "$SVC" \
  && ok "the notification is ongoing, silent, alerts once" || bad "the notification is not an ongoing silent one"
grep -q 'ContextCompat.startForegroundService(app, Intent(app, BatchForegroundService::class.java))' "$SVC" \
  && grep -q 'onFailure { Log.w(TAG, "foreground service not started' "$SVC" \
  && grep -q 'Log.w("BatchForeground", "startForeground refused' "$SVC" \
  && ok "a refused start (background on 12+, no permission) is logged and the batch goes on" \
  || bad "a refused foreground start would stop the batch"
grep -q 'ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)' "$SVC" \
  && ok "onDestroy removes the foreground notification" || bad "the notification outlives the service"

echo "== F2: every host declares the service, type dataSync, with the permissions =="
for m in "$STOREMAN" "$SUPERMAN" "$LIBMAN"; do
  n="$(basename "$(dirname "$(dirname "$(dirname "$m")")")")"
  awk '/android:name="com.diegonmarcos.superapp.updater.BatchForegroundService"/,/\/>/' "$m" | grep -q 'android:foregroundServiceType="dataSync"' \
    && ok "$n: BatchForegroundService declared with foregroundServiceType=dataSync" \
    || bad "$n: BatchForegroundService not declared as dataSync"
  grep -q 'android.permission.FOREGROUND_SERVICE"' "$m" && grep -q 'android.permission.FOREGROUND_SERVICE_DATA_SYNC"' "$m" \
    && ok "$n: FOREGROUND_SERVICE + FOREGROUND_SERVICE_DATA_SYNC" || bad "$n: a foreground-service permission is missing"
done
for m in "$STOREMAN" "$SUPERMAN"; do
  awk '/androidx.work.impl.foreground.SystemForegroundService/,/\/>/' "$m" | grep -q 'android:foregroundServiceType="dataSync"' \
    && ok "$(basename "$(dirname "$(dirname "$(dirname "$m")")")"): WorkManager's SystemForegroundService merged with type dataSync" \
    || bad "WorkManager's foreground service has no dataSync type in $m"
done
grep -q '"android.permission.POST_NOTIFICATIONS"' "$LIBMAN" \
  && ok "POST_NOTIFICATIONS is declared by libs:updater (not granted = notification hidden, service still runs)" \
  || bad "POST_NOTIFICATIONS missing from libs:updater"

echo "== F3: the batch paths hold the service, refcounted =="
grep -q 'private val held = AtomicInteger(0)' "$SVC" \
  && grep -q 'if (held.incrementAndGet() != 1) return' "$SVC" \
  && grep -q 'if (held.get() <= 0 || held.decrementAndGet() != 0) return' "$SVC" \
  && ok "begin/end are refcounted: the first begin starts, the last end stops" || bad "begin/end are not refcounted"
# The process crashed (ForegroundServiceDidNotStartInTimeException) whenever end() stopped the
# service before its onStartCommand reached startForeground: every verb takes the hold, so an
# import of 128 apps hit that window and the Store died mid-batch, three times.
grep -q 'if (foreground) runCatching { app.stopService(Intent(app, BatchForegroundService::class.java)) }' "$SVC" \
  && ok "end() stops only a service that is already foreground" || bad "end() can stop the service before it is foreground (process crash)"
python3 - "$SVC" <<'PYC' && ok "onStartCommand: startForeground first, then stopSelf when the hold already ended" || bad "onStartCommand can stop before startForeground, or never stops after a too-early end()"
import re, sys
s = open(sys.argv[1]).read()
b = re.search(r'override fun onStartCommand\(.*?\n    \}\n', s, re.S).group(0)
fg, flag = b.find('ServiceCompat.startForeground('), b.find('BatchForeground.foreground = true')
late = b.find('if (!BatchForeground.isHeld()) stopSelf()')
assert 0 <= fg < flag < late, (fg, flag, late)
# the only stop before startForeground is the not-a-Store-package exit (begin() never starts it there)
pre = b[:fg].replace('if (!BatchForeground.allowed(this)) { stopSelf(); return START_NOT_STICKY }', '')
assert 'stopSelf()' not in pre, "a stop before startForeground"

PYC
grep -q 'inline fun <T> hold(ctx: Context, on: Boolean = true, body: () -> T): T' "$SVC" \
  && grep -q 'try { return body() } finally { if (on) end(ctx) }' "$SVC" \
  && ok "hold() ends on every exit (done, failed, cancelled, thrown)" || bad "hold() can leak the service"
grep -q 'BatchForeground.hold(ctx, !dryRun) { downloadAllRun(ctx, apps, online, dryRun) }' "$STAGES" \
  && grep -q 'BatchForeground.hold(ctx, !dryRun) { updateAllRun(ctx, apps, online, dryRun) }' "$STAGES" \
  && ok "downloadAll / updateAll hold it for the whole batch (never for a dry run)" || bad "a batch does not hold the service"
grep -q 'private fun named(ctx: Context, app: Fleet.App, stage: String, version: String, verb: () -> Stage): Stage = BatchForeground.hold(ctx) {' "$STAGES" \
  && ok "a single verb (a row's Install, /api/store/<op>) holds it through StoreStages.named" || bad "a single install chain does not hold the service"
# #889 aa3727925 gave named() a ctx and missed clear(): libs:appstore stopped compiling and no Store APK shipped.
left=$(grep -nE '\bnamed\(' "$STAGES" | grep -v 'fun named(' | grep -vE 'named\(ctx, app, ' || true)
[ -z "$left" ] && ok "every StoreStages.named call passes ctx (the hold needs it)" || bad "a named() call without ctx: $left"
awk '/fun run\(ctx: Context, apps: List<Fleet.App>, trigger: String/,/^    }$/' "$AUTO" > /tmp/.store-auto-run.$$
grep -q 'com.diegonmarcos.superapp.updater.BatchForeground.begin(ctx)' /tmp/.store-auto-run.$$ \
  && awk '/} finally {/,/^        }$/' /tmp/.store-auto-run.$$ | grep -q 'com.diegonmarcos.superapp.updater.BatchForeground.end(ctx)' \
  && ok "the auto chain (StoreAuto.run) begins in its try and ends in its finally" || bad "the auto chain does not hold the service"
rm -f /tmp/.store-auto-run.$$
grep -q 'app.stopService(Intent(app, BatchForegroundService::class.java))' "$SVC" \
  && ok "end() stops the service" || bad "end() does not stop the service"

echo "== F4: the notification text is the Store bar line, updated at most once a second =="
grep -q 'BatchForeground.text = { progress()?.text }' "$STAGES" \
  && ok "StoreStages wires the text to StoreStages.progress()?.text — the /api/store/progress line" \
  || bad "the notification text is not the progress line"
grep -q 'BatchForeground.icon = { AppStoreHost.notificationIcon }' "$STAGES" \
  && grep -q 'AppStoreHost.launchActivity?.let { Intent(ctx, it)' "$STAGES" \
  && ok "the host's icon and launch target (AppStoreHost) are reused" || bad "the host's icon / launch target are not reused"
grep -q '.setContentText(line)' "$SVC" && grep -q 'val line = runCatching { text() }.getOrNull() ?: "working…"' "$SVC" \
  && ok "the notification draws that line" || bad "the notification does not draw the line"
grep -q 'const val THROTTLE_MS = 1_000L' "$SVC" && grep -q 'now - lastAt >= THROTTLE_MS' "$SVC" \
  && grep -q 'UpdateProgress.addObserver(observer)' "$SVC" && grep -q 'UpdateProgress.removeObserver(observer)' "$SVC" \
  && ok "progress re-posts ride UpdateProgress's observer, throttled to >= 1 s, removed at end" \
  || bad "progress updates are not throttled / not unsubscribed"

echo "== F5: the workers that download or install declare foreground info =="
for w in "$CW" "$UW" "$AIW"; do
  grep -q 'override suspend fun getForegroundInfo(): ForegroundInfo = BatchForeground.foregroundInfo(applicationContext)' "$w" \
    && grep -q 'fgsToken = holdDataSync(userInitiated = .*) { BatchForeground.foregroundInfo(it) }' "$w" \
    && grep -q 'DataSyncBudget.end(applicationContext, fgsToken)' "$w" \
    && ok "$(basename "$w"): getForegroundInfo() + budgeted holdDataSync(BatchForeground.foregroundInfo)" \
    || bad "$(basename "$w"): no foreground info"
done
grep -q 'ForegroundInfo(ID, notification(ctx), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)' "$SVC" \
  && ok "the workers' ForegroundInfo is type dataSync" || bad "the workers' ForegroundInfo has no dataSync type"
grep -q 'override fun onTimeout(startId: Int, fgsType: Int)' "$SVC" \
  && ok "BatchForegroundService stops itself on the dataSync timeout" || bad "BatchForegroundService has no onTimeout"

echo "== F6: the fleet-alerts guard knows the re-post is progress, not an alert =="
python3 - "$POLICY" <<'EOF' && ok "fleet-alerts-guard.json lists BatchForegroundService.kt as kind=progress" || bad "BatchForegroundService.kt is not classified in fleet-alerts-guard.json"
import json, sys
d = json.load(open(sys.argv[1]))
e = [p for p in d["posters"] if p["path"].endswith("updater/BatchForegroundService.kt")]
sys.exit(0 if e and e[0]["kind"] == "progress" and e[0]["notify_calls"] == 1 else 1)
EOF

echo "== RESULT(#903 store batch foreground service): $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
