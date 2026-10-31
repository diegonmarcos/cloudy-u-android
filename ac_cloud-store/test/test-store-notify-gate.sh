#!/usr/bin/env bash
# #894 Only Cloud Store posts Store / install / update notifications. SuperApp posts none.
#
# libs:appstore, libs:updater and the fleet pass are compiled into BOTH apps, so the posting code is in
# the SuperApp APK too. Standing the SuperApp's schedules down (CloudStoreHandoff.ownsInstalls) missed:
# update work still persisted in WorkManager from an older build, the pass summary and install results
# that Cloud Store handed to the SuperApp's own Alerts group, the SuperApp's Store badge and the batch /
# "tap to finish installing" notifications. The rule is now held where the posting happens.
#
# Held statically (no build, no device, no network):
#  1. StoreNotifyGate: the SuperApp package is closed, Cloud Store's is not, both match the applicationIds,
#     and the Store alert key prefixes cover every dedupe key libs:updater / libs:appstore use.
#  2. EVERY file in libs:updater / libs:appstore that holds a posting primitive (notify, a notification
#     builder, a channel, setForeground, startForegroundService, the in-app feed push) asks the gate, so a
#     NEW poster added there without it turns this red. The workers, their schedulers and the foreground
#     service each ask it where they start.
#  3. FleetAlerts keeps a Store alert out of the SuperApp (raise and withdraw), and the SuperApp's collector
#     and store refuse it.
#  4. SuperApp: no Store badge, no pending-count hook, the retirement runs at start (work, notifications,
#     channels, collected alerts), the fleet pass is never its own, and its Store pages are one Install
#     Cloud Store button (no embedded Store).
#  5. Cloud Store is untouched by all of it: its package passes the gate and it still schedules the pass.
# Then each mutation below is planted in a scratch copy and must turn a check red.
# The runtime half is StoreNotifyGateTest (cloud-superapp :app:testDebugUnitTest).
#
# Usage: ./test-store-notify-gate.sh   (static, no network)
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/../.." && pwd)"
command -v python3 >/dev/null || { echo "python3 required"; exit 1; }

check() {  # check <root> -> PASS/FAIL lines, exit = #fails
python3 - "$1" <<'PY'
import os, re, sys
R = sys.argv[1]
fails = 0
def ok(c, good, bad):
    global fails
    print(("  PASS: " if c else "  FAIL: ") + (good if c else bad))
    fails += 0 if c else 1
def strip(s):
    s = re.sub(r'/\*.*?\*/', lambda m: re.sub(r'[^\n]', ' ', m.group(0)), s, flags=re.S)
    return re.sub(r'(?m)^\s*//.*$|(?<=[\s;{}])//[^\n]*', '', s)
def rd(p):
    return strip(open(os.path.join(R, p), encoding="utf-8").read())
def body(src, head, span=900):
    i = src.find(head)
    return src[i:i + span] if i >= 0 else ""

LIBS = "ab_cloud-libs-shared/libs/"
UPD = LIBS + "updater/src/main/java/com/diegonmarcos/superapp/updater/"
APS = LIBS + "appstore/src/main/java/com/diegonmarcos/superapp/appstore/"
SA = "aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/"
ST = "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/"

# 1. the gate
g = rd(LIBS + "core/src/main/java/com/diegonmarcos/superapp/core/StoreNotifyGate.kt")
ok('const val SUPERAPP_PKG = "com.diegonmarcos.superapp"' in g and "fun mayPost(packageName: String): Boolean = packageName != SUPERAPP_PKG" in g,
   "the gate closes the SuperApp package and nothing else", "StoreNotifyGate.mayPost no longer closes exactly the SuperApp package")
sa_id = re.search(r"applicationId '([^']+)'", open(os.path.join(R, "aa_cloud-superapp/app/build.gradle"), encoding="utf-8").read())
st_id = re.search(r"applicationId '([^']+)'", open(os.path.join(R, "ac_cloud-store/app/build.gradle"), encoding="utf-8").read())
ok(sa_id and ('SUPERAPP_PKG = "%s"' % sa_id.group(1)) in g and st_id and ('STORE_PKG = "%s"' % st_id.group(1)) in g,
   "the gate's package names are the apps' applicationIds", "StoreNotifyGate names a package that is not an applicationId")
m = re.search(r'STORE_ALERT_KEYS = listOf\(([^)]*)\)', g)
prefixes = re.findall(r'"([^"]+)"', m.group(1)) if m else []
ok({"store:", "updater:", "self-update"} <= set(prefixes), "Store alert keys: store:, updater:, self-update", "STORE_ALERT_KEYS lost a prefix")
keys, files = [], 0
for d in (UPD, APS):
    for root, _, names in os.walk(os.path.join(R, d)):
        for n in names:
            if not n.endswith(".kt"): continue
            t = strip(open(os.path.join(root, n), encoding="utf-8").read())
            keys += re.findall(r'dedupeKey\s*=\s*"([^"]+)"', t) + re.findall(r'\bKEY(?:_[A-Z_]+)?\s*=\s*"([^"]*:[^"]*)"', t)
ok(len(keys) >= 3 and all(any(k.startswith(p) for p in prefixes) for k in keys),
   "every dedupe key libs:updater / libs:appstore raise is a Store alert key (%d keys)" % len(keys),
   "a libs:updater / libs:appstore alert key escapes the Store prefixes: %s" % [k for k in keys if not any(k.startswith(p) for p in prefixes)])

# 2. every poster asks the gate
PRIM = re.compile(r'\.notify\(|Notification\.Builder\(|NotificationCompat\.Builder\(|createNotificationChannel\(|\bsetForeground\(|startForegroundService\(|NotificationStore\.push\(')
bad = []
for d in (UPD, APS):
    for root, _, names in os.walk(os.path.join(R, d)):
        for n in names:
            if not n.endswith(".kt"): continue
            t = strip(open(os.path.join(root, n), encoding="utf-8").read())
            if PRIM.search(t) and "StoreNotifyGate" not in t and "BatchForeground.allowed(" not in t:
                bad.append(n)
ok(not bad, "every file that posts in libs:updater / libs:appstore asks StoreNotifyGate", "posts without StoreNotifyGate: %s" % bad)
bf = rd(UPD + "BatchForegroundService.kt")
ok("fun allowed(ctx: Context): Boolean = StoreNotifyGate.mayPost(ctx)" in bf and "if (!allowed(ctx)) return" in body(bf, "fun begin(", 200),
   "the batch foreground service is never held from the SuperApp", "BatchForeground.begin ignores the gate")
ok("if (!allowed(ctx)) throw" in body(bf, "fun foregroundInfo(", 300), "no foreground notification is built for the SuperApp", "BatchForeground.foregroundInfo builds one anyway")
ok("if (!BatchForeground.allowed(this))" in body(bf, "override fun onStartCommand", 200), "the service itself stops at once in the SuperApp", "BatchForegroundService starts in the SuperApp")
for f in ("UpdateWorker.kt", "ApkInstallWorker.kt"):
    t = rd(UPD + f)
    ok("if (BatchForeground.allowed(applicationContext))\n                fgsToken = holdDataSync(" in t, f + " goes foreground only where allowed", f + " calls setForeground without the gate")
ok("StoreNotifyGate.mayPost(applicationContext)" in body(rd(UPD + "UpdateWorker.kt"), "override suspend fun doWork", 900),
   "a persisted UpdateWorker ends at once in the SuperApp", "UpdateWorker.doWork does not stand down in the SuperApp")
ok("StoreNotifyGate.mayPost(applicationContext)" in body(rd(APS + "ConstellationWorker.kt"), "override suspend fun doWork", 900),
   "a persisted ConstellationWorker ends at once in the SuperApp", "ConstellationWorker.doWork does not stand down in the SuperApp")
cw = rd(APS + "ConstellationWorker.kt")
for fn in ("fun kick(", "fun start(", "fun checkNow("):
    ok("StoreNotifyGate.mayPost(context)" in body(cw, fn, 400), "ConstellationWorker.%s) is gated" % fn[:-1], "ConstellationWorker.%s) schedules in the SuperApp" % fn[:-1])
ca = body(cw, "fun cancelAll(", 400)
ok("fun cancelAll(" in cw and 'cancelUniqueWork("$WORK_NAME-kick")' in ca and 'cancelUniqueWork("$WORK_NAME-now")' in ca and "cancelUniqueWork(WORK_NAME)" in ca,
   "ConstellationWorker.cancelAll clears periodic, launch and kick work", "ConstellationWorker.cancelAll misses a queued job")
up = rd(UPD + "Updater.kt")
ok("if (!StoreNotifyGate.mayPost(context)) { cancelAll(context); return }" in body(up, "fun start(", 400), "Updater.start schedules nothing in the SuperApp and clears old work", "Updater.start schedules in the SuperApp")
ok("ONE_SHOT_NAME" in body(up, "fun cancelAll(", 300) and "cancel(context)" in body(up, "fun cancelAll(", 300), "Updater.cancelAll clears the periodic job, its kick and the manual one-shot", "Updater.cancelAll misses a job")
pir = rd(UPD + "PackageInstallerReceiver.kt")
ok("StoreNotifyGate.mayPost(context) && runCatching" in body(pir, "private fun notifyConfirm", 200), "no 'tap to finish installing' notification from the SuperApp", "notifyConfirm posts from the SuperApp")
ok("if (!StoreNotifyGate.mayPost(context)) return" in body(pir, "private fun surface(", 600), "no install result (feed, toast or alert) from the SuperApp", "surface() speaks from the SuperApp")
ok("fun allowed" in bf and 'const val UPDATER_CHANNEL = "superapp-updater"' in pir, "the updater channel id is public for the retirement", "UPDATER_CHANNEL is not public")

# 3. FleetAlerts and the collector
fa = rd(LIBS + "core/src/main/java/com/diegonmarcos/superapp/core/FleetAlerts.kt")
ok("StoreNotifyGate.isStoreAlert(alert.dedupeKey)" in body(fa, "fun raise(", 700) and "StoreNotifyGate.mayPost(ctx) && postLocally" in fa,
   "FleetAlerts.raise: a Store alert never reaches the SuperApp's collector", "FleetAlerts.raise sends Store alerts to the SuperApp")
ok("StoreNotifyGate.isStoreAlert(dedupeKey)" in body(fa, "fun withdraw(", 300), "FleetAlerts.withdraw keeps Store alerts local", "FleetAlerts.withdraw calls the SuperApp for Store alerts")
pv = rd(SA + "notificationcenter/FleetAlertsProvider.kt")
ok("StoreNotifyGate.isStoreAlert(a.dedupeKey)" in pv, "the SuperApp's collector refuses Store alerts", "FleetAlertsProvider accepts Store alerts")
as_ = rd(SA + "notificationcenter/AlertStore.kt")
ok("StoreNotifyGate.isStoreAlert(a.dedupeKey)) return entry" in as_ and "fun dropStoreAlerts(" in as_, "AlertStore keeps none and can drop old ones", "AlertStore keeps Store alerts")

# 4. the SuperApp
app = rd(SA + "App.kt")
ok(not os.path.exists(os.path.join(R, SA + "notificationcenter/StoreBadgeNotifier.kt")) and "StoreBadgeNotifier" not in app and "StoreAuto.onPending =" not in app,
   "SuperApp has no Store badge and no pending-count hook", "SuperApp still has a Store badge / pending-count hook")
ok("StoreRetirement.run(this)" in app, "App.onCreate retires what an older build left", "App.onCreate never runs StoreRetirement")
ok("runsFleetPass = { false }" in app, "SuperApp never owns the fleet pass", "SuperApp's runsFleetPass is not { false }")
rt = rd(SA + "notificationcenter/StoreRetirement.kt")
ok("if (StoreNotifyGate.mayPost(app)) return" in rt and "ConstellationWorker.cancelAll(app)" in rt and "Updater.cancelAll(app)" in rt,
   "the retirement cancels fleet and update work, only in the SuperApp", "StoreRetirement does not cancel both kinds of work")
ok("deleteNotificationChannel" in rt and 'BADGE_CHANNEL = "store_updates"' in rt and "BatchForeground.CHANNEL" in rt and "PackageInstallerReceiver.UPDATER_CHANNEL" in rt,
   "the retirement deletes the Store, batch and updater channels", "StoreRetirement leaves a Store channel behind")
ok("dropStoreAlerts(app)" in rt and "isStoreAlert" in rt, "the retirement drops collected Store alerts and their shade entries", "StoreRetirement leaves Store alerts")
import json
bj = json.load(open(os.path.join(R, "aa_cloud-superapp/build.json"), encoding="utf-8"))["ui"]["notification_center"]
ok(all(p.get("id") != "store_updates" for p in bj["producers"]) and all(x.get("id") != "store" for x in bj["groups"]),
   "no Store producer or group is declared in the Notify centre", "build.json still declares a Store badge / group")
hand = rd(SA + "apps/CloudStoreHandoff.kt")
ok("InstallCloudStore()" in hand and "AndroidFragment(" not in hand and 'Text("Install Cloud Store")' in hand,
   "an absent Cloud Store is ONE Install Cloud Store button, not an embedded Store", "CloudStoreHandoff still embeds the Store or lost its install button")
sh = rd(SA + "ShellActivity.kt")
ok("Fleet.installAll" not in sh and "Updater.checkNow" not in sh, "SuperApp's Update-all and check only open Cloud Store", "ShellActivity installs or checks on its own")

# 5. Cloud Store keeps working
sapp = rd(ST + "App.kt")
ok("ConstellationWorker.start(this)" in sapp and "runsFleetPass = { true }" in sapp and st_id and ('STORE_PKG = "%s"' % st_id.group(1)) in g,
   "Cloud Store still schedules the pass and is not closed by the gate", "Cloud Store lost its pass or the gate closes it")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d /dev/shm/notify-gate.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  ( cd "$ROOT" && cp -r --parents ab_cloud-libs-shared/libs/updater/src/main ab_cloud-libs-shared/libs/appstore/src/main \
      ab_cloud-libs-shared/libs/core/src/main aa_cloud-superapp/build.json aa_cloud-superapp/app/build.gradle \
      ac_cloud-store/app/build.gradle ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt \
      aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/App.kt \
      aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/ShellActivity.kt \
      aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/apps \
      aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/notificationcenter "$d" )
  python3 - "$d/$f" "$old" "$new" <<'PY' || { echo "  MUTATION NOT APPLIED: $label"; return 1; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding='utf-8').read()
if old not in s: sys.exit(1)
open(p, 'w', encoding='utf-8').write(s.replace(old, new, 1))
PY
  if check "$d" >/dev/null; then echo "  MUTATION SURVIVED: $label"; return 1; fi
  echo "  mutation caught: $label"; return 0
}
L=ab_cloud-libs-shared/libs; U=$L/updater/src/main/java/com/diegonmarcos/superapp/updater; P=$L/appstore/src/main/java/com/diegonmarcos/superapp/appstore
C=$L/core/src/main/java/com/diegonmarcos/superapp/core; A=aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp
M=0
mutate "the gate closes nobody" $C/StoreNotifyGate.kt 'packageName != SUPERAPP_PKG' 'true' || M=$((M+1))
mutate "the gate names the wrong package" $C/StoreNotifyGate.kt 'SUPERAPP_PKG = "com.diegonmarcos.superapp"' 'SUPERAPP_PKG = "com.diegonmarcos.superapp.x"' || M=$((M+1))
mutate "a key prefix is lost" $C/StoreNotifyGate.kt '"store:", "updater:", "self-update"' '"store:", "updater:"' || M=$((M+1))
mutate "a new Store alert key escapes the prefixes" $U/PassLedger.kt 'KEY = "updater:pass_summary"' 'KEY = "pass_summary:x"' || M=$((M+1))
mutate "a new poster without the gate" $P/StoreAuto.kt 'object StoreAuto {' 'object StoreAuto { fun leak(c: android.content.Context) { (c.getSystemService("notification") as android.app.NotificationManager).notify(1, null) }' || M=$((M+1))
mutate "foreground service held from the SuperApp" $U/BatchForegroundService.kt 'if (!allowed(ctx)) return
        if (held' 'if (held' || M=$((M+1))
mutate "foregroundInfo builds one for the SuperApp" $U/BatchForegroundService.kt 'if (!allowed(ctx)) throw IllegalStateException("Store notifications are Cloud Store'"'"'s; this package posts none")
        else if' 'if' || M=$((M+1))
mutate "the service starts in the SuperApp" $U/BatchForegroundService.kt 'if (!BatchForeground.allowed(this)) { stopSelf(); return START_NOT_STICKY }' '' || M=$((M+1))
mutate "UpdateWorker foreground without the gate" $U/UpdateWorker.kt 'if (BatchForeground.allowed(applicationContext))' 'if (true)' || M=$((M+1))
mutate "UpdateWorker runs in the SuperApp" $U/UpdateWorker.kt 'if (!StoreNotifyGate.mayPost(applicationContext)) {' 'if (false) {' || M=$((M+1))
mutate "ConstellationWorker runs in the SuperApp" $P/ConstellationWorker.kt 'if (!StoreNotifyGate.mayPost(applicationContext)) {' 'if (false) {' || M=$((M+1))
mutate "ConstellationWorker.start schedules in the SuperApp" $P/ConstellationWorker.kt 'if (!StoreNotifyGate.mayPost(context)) { cancelAll(context); return }' '' || M=$((M+1))
mutate "ConstellationWorker.kick queues in the SuperApp" $P/ConstellationWorker.kt 'if (!StoreNotifyGate.mayPost(context)) return   // #894
                if' 'if' || M=$((M+1))
mutate "ConstellationWorker.checkNow queues in the SuperApp" $P/ConstellationWorker.kt 'fun checkNow(context: Context) {
            if (!StoreNotifyGate.mayPost(context)) return   // #894' 'fun checkNow(context: Context) {' || M=$((M+1))
mutate "cancelAll forgets the kick" $P/ConstellationWorker.kt 'wm.cancelUniqueWork("$WORK_NAME-kick")' '' || M=$((M+1))
mutate "Updater.start schedules in the SuperApp" $U/Updater.kt 'if (!StoreNotifyGate.mayPost(context)) { cancelAll(context); return }' '' || M=$((M+1))
mutate "Updater.cancelAll forgets the one-shot" $U/Updater.kt 'cancel(context)
        WorkManager.getInstance(context).cancelUniqueWork(ONE_SHOT_NAME)' 'cancel(context)' || M=$((M+1))
mutate "tap-to-finish notification from the SuperApp" $U/PackageInstallerReceiver.kt 'StoreNotifyGate.mayPost(context) && runCatching' 'runCatching' || M=$((M+1))
mutate "install result spoken from the SuperApp" $U/PackageInstallerReceiver.kt 'if (!StoreNotifyGate.mayPost(context)) return' '' || M=$((M+1))
mutate "FleetAlerts.raise delivers Store alerts to the SuperApp" $C/FleetAlerts.kt 'if (StoreNotifyGate.isStoreAlert(alert.dedupeKey))' 'if (false)' || M=$((M+1))
mutate "FleetAlerts.withdraw calls the SuperApp" $C/FleetAlerts.kt 'if (StoreNotifyGate.isStoreAlert(dedupeKey)) {' 'if (false) {' || M=$((M+1))
mutate "the collector accepts Store alerts" $A/notificationcenter/FleetAlertsProvider.kt ' || StoreNotifyGate.isStoreAlert(a.dedupeKey)' '' || M=$((M+1))
mutate "AlertStore keeps Store alerts" $A/notificationcenter/AlertStore.kt 'if (StoreNotifyGate.isStoreAlert(a.dedupeKey)) return entry' '' || M=$((M+1))
mutate "a Store badge is wired again" $A/App.kt 'StoreRetirement.run(this)' 'StoreAuto.onPending = { c, n -> Unit }' || M=$((M+1))
mutate "the retirement never runs" $A/App.kt 'StoreRetirement.run(this)' 'Unit' || M=$((M+1))
mutate "SuperApp owns the pass again" $A/App.kt 'runsFleetPass = { false }' 'runsFleetPass = { true }' || M=$((M+1))
mutate "the retirement forgets the fleet work" $A/notificationcenter/StoreRetirement.kt 'ConstellationWorker.cancelAll(app)' 'Unit' || M=$((M+1))
mutate "the retirement runs in Cloud Store too" $A/notificationcenter/StoreRetirement.kt 'if (StoreNotifyGate.mayPost(app)) return' '' || M=$((M+1))
mutate "the retirement keeps the channels" $A/notificationcenter/StoreRetirement.kt 'deleteNotificationChannel' 'getNotificationChannel' || M=$((M+1))
mutate "the retirement keeps the old alerts" $A/notificationcenter/StoreRetirement.kt 'dropStoreAlerts(app)' '0' || M=$((M+1))
mutate "the Store badge is declared again" aa_cloud-superapp/build.json '"id": "kde_status",' '"id": "store_updates", "persistent": false }, { "id": "kde_status",' || M=$((M+1))
mutate "the Store is embedded again" $A/apps/CloudStoreHandoff.kt 'if (installed) OpenCloudStore(tab) else InstallCloudStore()' 'if (installed) OpenCloudStore(tab) else androidx.fragment.compose.AndroidFragment(clazz = EMBEDDED.getValue("store-cloud"))' || M=$((M+1))
mutate "Update-all installs from the SuperApp" $A/ShellActivity.kt 'actionType == "update_all" ->' 'actionType == "update_all" -> com.diegonmarcos.superapp.updater.Fleet.installAll(applicationContext, emptyList(), com.diegonmarcos.superapp.updater.Fleet.Mode.UPDATES) ; actionType == "x" ->' || M=$((M+1))
mutate "Cloud Store gives up the pass" ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt 'runsFleetPass = { true }' 'runsFleetPass = { false }' || M=$((M+1))

echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
