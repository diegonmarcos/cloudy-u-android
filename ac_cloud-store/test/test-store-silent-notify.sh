#!/usr/bin/env bash
# Cloud Store's notifications are SILENT: no sound, no vibration, no light, importance LOW at most.
#
# Cloud Store posts on three channels, none of them its own code: the fleet-alerts fallback (libs:core
# FleetAlerts), "tap to finish installing" (libs:updater PackageInstallerReceiver) and the batch download line
# (libs:updater BatchForeground). Since #1512 it has no pairing or shell keep-alive notification (the channel is the
# SuperApp's broker); those two ids stay in LEGACY_IDS so an old install's channels are still retired. Those libs are compiled into many fleet apps, so the rule is PER APP: libs:core
# SilentChannels creates every one of them, silent only for the packages in SILENT_PACKAGES (Cloud Store).
#
# Android never changes an EXISTING channel's sound or vibration, so Cloud Store posts on NEW ids (legacy id +
# "_silent_v2") and deletes the old ones at start (SilentChannels.retireLegacy, called first in App.onCreate).
#
# Held statically (no build, no device, no network):
#  1. SilentChannels: Cloud Store's package and no other is silent; a silent spec is a new id with importance
#     at most LOW (MIN for a progress line); the channel it creates has no sound, no vibration (and no
#     pattern) and no light; an app that is not silent keeps its id and importance untouched.
#  2. In every source Cloud Store compiles (the libs it links + its app), a channel is created ONLY through
#     SilentChannels.ensure, and every builder posts on the id ensure returned, never on a legacy constant.
#  3. No builder sets a sound, a vibration, a light or alerting defaults; every builder is quiet
#     (SilentChannels.quiet, or setSilent(true) + setOnlyAlertOnce(true)).
#  4. Migration: every legacy id ensure is called with is in LEGACY_IDS, LEGACY_IDS holds every id Cloud
#     Store ever posted on, retireLegacy deletes each of them (only in a silent package, never a new id),
#     and Cloud Store's App.onCreate runs it before anything can post.
# Then each mutation below is planted in a scratch copy and must turn a check red.
#
# Usage: ./test-store-silent-notify.sh   (static, no network)
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
    return re.sub(r'(?m)^\s*//.*$|(?<=[\s;{}(,])//[^\n]*', '', s)
def rd(p):
    p = os.path.join(R, p)
    return strip(open(p, encoding="utf-8").read()) if os.path.isfile(p) else ""
def call_end(src, i):
    """index just past the bracket that closes the '(' at or after i"""
    j = src.find("(", i)
    if j < 0: return len(src)
    depth = 0
    for k in range(j, len(src)):
        if src[k] == "(": depth += 1
        elif src[k] == ")":
            depth -= 1
            if depth == 0: return k + 1
    return len(src)
def chain(src, i):
    """a builder expression: from `Builder(` to the `.build()` that ends it"""
    j = src.find(".build()", i)
    return src[i:j + 8] if j >= 0 else src[i:i + 1500]

LIBS = "ab_cloud-libs-shared/libs/"
CORE = LIBS + "core/src/main/java/com/diegonmarcos/superapp/core/"
SC = CORE + "SilentChannels.kt"
STAPP = "ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt"

# The sources Cloud Store compiles: its app and every lib its build.json links.
import json
mods = json.load(open(os.path.join(R, "ac_cloud-store/build.json"), encoding="utf-8"))["modules"]
dirs = ["ac_cloud-store/app/src"]
for name, spec in mods.items():
    if name.startswith("_") or name == "app": continue
    d = (spec or {}).get("dir") or name.replace(":", "/")
    dirs.append(os.path.normpath(os.path.join("ac_cloud-store", d, "src")))
SRC = {}
for d in dirs:
    for root, _, names in os.walk(os.path.join(R, d)):
        if "/src/test" in root or "/src/androidTest" in root: continue
        for n in names:
            if n.endswith((".kt", ".java")):
                p = os.path.relpath(os.path.join(root, n), R)
                SRC[p] = strip(open(os.path.join(root, n), encoding="utf-8").read())
ok(len(SRC) > 100 and any(p.endswith("FleetAlerts.kt") for p in SRC) and any(p.endswith("PackageInstallerReceiver.kt") for p in SRC),
   "scanning %d sources across %d modules Cloud Store compiles" % (len(SRC), len(dirs)),
   "the scan is blind: %d sources found" % len(SRC))

# 1. the per-app rule
s = rd(SC)
ok(bool(s), "libs:core SilentChannels exists", "libs:core has no SilentChannels.kt: Cloud Store's channels are created with Android's default sound")
pk = re.search(r'SILENT_PACKAGES\s*=\s*setOf\(([^)]*)\)', s)
ok(pk is not None and [x.strip() for x in pk.group(1).split(",") if x.strip()] == ["StoreNotifyGate.STORE_PKG"],
   "exactly one package is silent: Cloud Store's", "SILENT_PACKAGES is not exactly StoreNotifyGate.STORE_PKG: other apps would change")
ok('const val SUFFIX = "_silent_v2"' in s, "silent channels carry the _silent_v2 suffix", "the silent id suffix is not _silent_v2")
sp = s[s.find("fun spec("):call_end(s, s.find("fun spec(")) + 400] if "fun spec(" in s else ""
ok("if (!isSilent(packageName)) Spec(legacyId, importance, false)" in sp,
   "an app that is not silent keeps its channel id and importance", "spec() changes the channel of an app that is not silent")
ok("Spec(legacyId + SUFFIX, if (progress) NotificationManager.IMPORTANCE_MIN else importance.coerceAtMost(NotificationManager.IMPORTANCE_LOW), true)" in sp,
   "a Cloud Store spec is a new id, importance <= LOW (MIN for a progress line)", "a silent spec can keep the legacy id or an importance above LOW")
q = s[s.find("fun quiet(ch: NotificationChannel)"):][:400] if "fun quiet(ch: NotificationChannel)" in s else ""
for need, what in (("setSound(null, null)", "no sound"), ("enableVibration(false)", "no vibration"),
                   ("vibrationPattern = null", "no vibration pattern"), ("enableLights(false)", "no light")):
    ok(need in q, "a silent channel has " + what, "SilentChannels.quiet does not set " + need)
ok("setShowBadge" not in s, "the badge is left as each caller sets it", "SilentChannels changes the badge")
en = s[s.find("fun ensure("):][:1200] if "fun ensure(" in s else ""
ok("spec(ctx.packageName, legacyId, importance, progress)" in en and "if (s.silent) quiet(this)" in en
   and "NotificationChannel(s.id, name, s.importance)" in en and "return s.id" in en,
   "ensure() creates the channel from the spec, quiet when silent, and returns the id to post on",
   "ensure() does not create the spec'd channel quietly or does not return its id")
bq = s[s.find("fun quiet(ctx: Context, b: NotificationCompat.Builder)"):][:300] if "fun quiet(ctx: Context, b:" in s else ""
ok("if (isSilent(ctx)) b.setOnlyAlertOnce(true).setSilent(true) else b" in bq,
   "a Cloud Store builder alerts at most once and is silent", "SilentChannels.quiet(builder) does not silence Cloud Store's builders")

# 2. channels only through SilentChannels, builders only on its ids
raw = [p for p, t in SRC.items() if p != SC and re.search(r'\bNotificationChannel\(|createNotificationChannel\(|NotificationChannelCompat', t)]
ok(not raw, "no channel is created outside SilentChannels", "creates a channel outside SilentChannels: %s" % raw)
LEGACY_NAMES = ("CHANNEL", "CHANNEL_ID", "NOTIF_CHANNEL", "FALLBACK_CHANNEL", "UPDATER_CHANNEL")
builders, bad_ch, loud, unquiet = 0, [], [], []
for p, t in SRC.items():
    for m in re.finditer(r'\b(?:NotificationCompat|Notification)\.Builder\(', t):
        builders += 1
        args = t[m.end():call_end(t, m.start()) - 1]
        ch = args.split(",", 1)[1].strip() if "," in args else ""
        where = "%s:%d" % (os.path.basename(p), t[:m.start()].count("\n") + 1)
        if not ch or ch.startswith('"') or ch.split(".")[-1] in LEGACY_NAMES: bad_ch.append("%s posts on %s" % (where, ch or "?"))
        c = chain(t, m.start())
        if not ("SilentChannels.quiet(" in c or (".setSilent(true)" in c and ".setOnlyAlertOnce(true)" in c)): unquiet.append(where)
        if re.search(r'setSound\(|setVibrate\(|setDefaults\(|setLights\(|DEFAULT_(SOUND|VIBRATE|LIGHTS|ALL)', c): loud.append(where)
    if ("Builder(" in t) and re.search(r'\b(?:NotificationCompat|Notification)\.Builder\(', t) and "SilentChannels.ensure(" not in t:
        bad_ch.append("%s builds a notification without SilentChannels.ensure" % os.path.basename(p))
ok(builders >= 3, "%d notification builders found" % builders, "only %d notification builders found: the scan is blind" % builders)
ok(not bad_ch, "every builder posts on the id SilentChannels.ensure returned", "; ".join(bad_ch))

# 3. no sound, no vibrate
ok(not loud, "no builder sets a sound, a vibration, a light or alerting defaults", "a builder alerts: %s" % loud)
anywhere = [p for p, t in SRC.items() if re.search(r'\.setSound\((?!null, null\))|\.setVibrate\(|\.setDefaults\(|DEFAULT_(SOUND|VIBRATE|ALL)\b|enableVibration\(true\)|enableLights\(true\)', t)]
ok(not anywhere, "nothing Cloud Store compiles turns a notification sound or vibration on", "turns sound/vibration on: %s" % anywhere)
ok(not unquiet, "every builder is quiet (SilentChannels.quiet, or setSilent + setOnlyAlertOnce)", "builder not quiet: %s" % unquiet)

# 4. migration
lg = re.search(r'LEGACY_IDS\s*=\s*listOf\(([^)]*)\)', s)
legacy = re.findall(r'"([^"]+)"', lg.group(1)) if lg else []
HISTORY = {"fleet_alerts", "superapp-updater", "store_batch", "adb_pairing", "host_shell_channel"}
ok(HISTORY <= set(legacy), "LEGACY_IDS holds every channel Cloud Store ever posted on", "LEGACY_IDS misses %s" % sorted(HISTORY - set(legacy)))
ok(not any(x.endswith("_silent_v2") for x in legacy), "no silent id is ever deleted", "LEGACY_IDS deletes a silent channel")
CONST = r'\b(?:const\s+)?val\s+([A-Z_]+)\s*=\s*("[^"]+"|[A-Z_]+)\s*$'
glob_c = {}
for t in SRC.values():
    for n, v in re.findall(CONST, t, flags=re.M):
        glob_c.setdefault(n, set()).add(v)
def resolve(name, local, seen=()):
    """a constant's string value: this file's own declaration first, then the one declaration in scope"""
    if name.startswith('"'): return name.strip('"')
    if name in seen: return None
    v = local.get(name) or (next(iter(glob_c[name])) if len(glob_c.get(name, ())) == 1 else None)
    return resolve(v, local, seen + (name,)) if v else None
used, unresolved = set(), []
for p, t in SRC.items():
    if p == SC: continue
    local = dict(re.findall(CONST, t, flags=re.M))
    for m in re.finditer(r'SilentChannels\.ensure\(', t):
        args = t[m.end():call_end(t, m.start()) - 1].split(",")
        a = args[1].strip() if len(args) > 1 else ""
        v = resolve(a.split(".")[-1] if not a.startswith('"') else a, local)
        if v: used.add(v)
        else: unresolved.append("%s: %s" % (os.path.basename(p), a))
ok(len(used) >= 3 and not unresolved, "ensure() is called for %d legacy ids: %s" % (len(used), sorted(used)),
   "ensure() legacy ids found %s, unresolved %s" % (sorted(used), unresolved))
ok(used <= set(legacy), "every legacy id Cloud Store posts on is retired", "posted on but never deleted: %s" % sorted(used - set(legacy)))
rt = s[s.find("fun retireLegacy("):][:700] if "fun retireLegacy(" in s else ""
ok("if (!isSilent(ctx)) return" in rt and "LEGACY_IDS.forEach" in rt and "deleteNotificationChannel(it)" in rt,
   "retireLegacy deletes every legacy channel, and only in a silent package", "retireLegacy does not delete the legacy channels (or runs in every app)")
app = rd(STAPP)
oc = app[app.find("override fun onCreate()"):]
ri = oc.find("SilentChannels.retireLegacy(this)")
first_post = min([i for i in (oc.find("CloudStoreShell.install"), oc.find("ConstellationWorker.start"), oc.find("StoreDebugApi")) if i >= 0] or [len(oc)])
ok(ri >= 0 and ri < first_post, "Cloud Store retires the old channels at start, before anything can post",
   "Cloud Store's App.onCreate never retires the old channels (or only after a service may have posted)")
sys.exit(fails)
PY
}

echo "-- real tree --"
check "$ROOT"; REAL=$?

TMP="$(mktemp -d /dev/shm/silent-notify.XXXXXX)"; trap 'rm -rf "$TMP"' EXIT
mutate() {  # mutate <label> <file> <old> <new>
  local label="$1" f="$2" old="$3" new="$4" d="$TMP/m"
  rm -rf "$d"; mkdir -p "$d"
  ( cd "$ROOT" && for m in core updater appstore shell-client bottomnav sysdns fleetconfig-model devtools; do
        [ -d ab_cloud-libs-shared/libs/$m/src ] && cp -r --parents ab_cloud-libs-shared/libs/$m/src "$d"; done
    cp -r --parents ac_cloud-store/build.json ac_cloud-store/app/src "$d" )
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
L=ab_cloud-libs-shared/libs; C=$L/core/src/main/java/com/diegonmarcos/superapp/core
U=$L/updater/src/main/java/com/diegonmarcos/superapp/updater; S=$L/shizuku-adb-debug-tools/src/main/java/com/diegonmarcos/superapp/adbdebug
A=ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/App.kt
M=0
if [ "$REAL" -eq 0 ]; then
mutate "the SuperApp goes silent too" $C/SilentChannels.kt 'setOf(StoreNotifyGate.STORE_PKG)' 'setOf(StoreNotifyGate.STORE_PKG, StoreNotifyGate.SUPERAPP_PKG)' || M=$((M+1))
mutate "a silent channel keeps its sound" $C/SilentChannels.kt 'setSound(null, null)' 'description = null' || M=$((M+1))
mutate "a silent channel vibrates" $C/SilentChannels.kt 'enableVibration(false)' 'enableVibration(true)' || M=$((M+1))
mutate "a silent channel keeps a pattern" $C/SilentChannels.kt 'vibrationPattern = null' 'vibrationPattern = longArrayOf(0, 250)' || M=$((M+1))
mutate "a silent channel blinks" $C/SilentChannels.kt 'enableLights(false)' 'enableLights(true)' || M=$((M+1))
mutate "a silent spec keeps HIGH" $C/SilentChannels.kt 'importance.coerceAtMost(NotificationManager.IMPORTANCE_LOW)' 'importance' || M=$((M+1))
mutate "a silent spec keeps the old id" $C/SilentChannels.kt 'Spec(legacyId + SUFFIX,' 'Spec(legacyId,' || M=$((M+1))
mutate "ensure forgets to quiet the channel" $C/SilentChannels.kt 'if (s.silent) quiet(this)' 'Unit' || M=$((M+1))
mutate "the builder helper does not silence" $C/SilentChannels.kt 'b.setOnlyAlertOnce(true).setSilent(true)' 'b' || M=$((M+1))
mutate "other apps' channels change" $C/SilentChannels.kt 'if (!isSilent(packageName)) Spec(legacyId, importance, false)' 'if (!isSilent(packageName)) Spec(legacyId, importance.coerceAtMost(2), false)' || M=$((M+1))
mutate "a legacy id is never deleted" $C/SilentChannels.kt '"adb_pairing", ' '' || M=$((M+1))
mutate "the retirement deletes nothing" $C/SilentChannels.kt 'deleteNotificationChannel(it)' 'getNotificationChannel(it)' || M=$((M+1))
mutate "the retirement runs in every app" $C/SilentChannels.kt 'if (!isSilent(ctx)) return' '' || M=$((M+1))
mutate "Cloud Store never retires" $A 'SilentChannels.retireLegacy(this)' 'Unit' || M=$((M+1))
mutate "a raw channel is created again" $U/BatchForegroundService.kt 'override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {' 'override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(android.app.NotificationChannel("x", "x", NotificationManager.IMPORTANCE_HIGH))' || M=$((M+1))
mutate "a builder posts on the legacy id" $C/FleetAlerts.kt 'NotificationCompat.Builder(ctx, channel)' 'NotificationCompat.Builder(ctx, FALLBACK_CHANNEL)' || M=$((M+1))
mutate "a builder sets the default sound" $U/PackageInstallerReceiver.kt '.setAutoCancel(true)' '.setAutoCancel(true).setDefaults(Notification.DEFAULT_SOUND)' || M=$((M+1))
mutate "a builder vibrates" $C/FleetAlerts.kt 'NotificationCompat.Builder(ctx, channel)' 'NotificationCompat.Builder(ctx, channel).setVibrate(longArrayOf(0, 100))' || M=$((M+1))
mutate "a builder is no longer quiet" $U/BatchForegroundService.kt '.setSilent(true)' '' || M=$((M+1))
fi
echo "== RESULT: real tree $REAL failure(s), $M mutation(s) not caught =="
[ "$REAL" -eq 0 ] && [ "$M" -eq 0 ]
