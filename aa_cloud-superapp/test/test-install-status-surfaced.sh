#!/usr/bin/env bash
# Tester: the constellation INSTALL path — every installer status reaches the
# user, and the one that needs an Intent launched actually launches it.
#
# THE FAILURE THIS EXISTS TO KEEP FIXED. Installing "Cloud Office"
# (Cloud-Sheets.apk, 267,216,449 bytes) downloaded the whole quarter-gigabyte,
# committed a PackageInstaller session, and then produced NOTHING: no install,
# no error, no change on screen. PackageInstaller reports the outcome of a
# commit asynchronously, so an outcome nobody surfaces is an outcome nobody has.
# Three shapes of that, all of them invisible at the 6-33 MB the rest of the
# fleet ships at:
#
#   1. STATUS_PENDING_USER_ACTION with no EXTRA_INTENT did `releaseGate();
#      return` — no toast, no notification, no state change. The progress row
#      kept reading "Installing…" for an install that was already over.
#   2. The confirm Intent was launched with startActivity ONLY when a
#      process-importance probe said "foreground". Android 10+ blocks a
#      background activity start and does NOT throw, so when that sample went
#      stale between the probe and the call the dialog silently never appeared
#      and there was no fallback behind it. Staging 267 MB takes long enough
#      that the user has usually left the screen by then, so the biggest app in
#      the fleet lost that race the most reliably.
#   3. Two statuses reached the user as a bare number ("status=7", "status=-999")
#      which names nothing and points nowhere.
#
# The assertion that carries the weight is T1: NO branch of the status handler
# may exist without a user-visible call in it. A new status handled silently
# fails this file, which is the only way a reviewer finds out before the owner
# does.
#
# Usage: ./test-install-status-surfaced.sh    (static only, no network, no device)
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"   # → ~/git/cloud-u-android
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has()   { grep -qF "$2" "$ROOT/$1" 2>/dev/null && ok "$3" || bad "$3 ($1)"; }
# CODE ONLY. This file documents the constructs it removed, by name, in the
# comments explaining why — so a plain grep for a banned construct matches the
# very comment that proves it is gone. Strip comments first and the assertion
# means what it says.
code()  { sed -e 's|//.*||' -e 's|^[[:space:]]*\*.*||' "$ROOT/$1" 2>/dev/null; }
hasnt_code() { code "$1" | grep -qF "$2" && bad "$3 ($1)" || ok "$3"; }
has_code()   { code "$1" | grep -qF "$2" && ok "$3" || bad "$3 ($1)"; }

LIB="ab_cloud-libs-shared/libs"
UPD="$LIB/updater/src/main/java/com/diegonmarcos/superapp/updater"
RCV="$UPD/PackageInstallerReceiver.kt"
INST="$UPD/install/UpdateInstaller.kt"
SRC="$UPD/source/ApkSource.kt"
FLEET="$UPD/Fleet.kt"
# #564: both Store tabs install through FleetInstall.run — the one body that
# decides whether the advisory clears (test-store-classification.sh T6 holds
# that nothing else calls Fleet.install).
PAGE="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/FleetInstall.kt"

echo "== T1: NO status branch is silent =="
# THE ASSERTION THAT WOULD HAVE CAUGHT THIS. Walk the `when (status)` block by
# brace depth and require every branch body to contain a call that puts
# something in front of the user — surface() (toast + notification + in-app
# feed) or notifyConfirm() (the tap-to-install notification). A branch that
# only logs, or only returns, is exactly the bug.
SILENT=$(awk '
  # ONLY the outer dispatch. The failure branch contains a SECOND
  # `when (status)` — the table that turns a status into a sentence — and
  # matching that one too reset the walk mid-branch and reported the branch it
  # was standing in as silent.
  /when \(status\) \{/ && !seen { seen=1; inwhen=1; depth=1; next }
  inwhen {
    n=gsub(/\{/,"{"); m=gsub(/\}/,"}")
    if (branch=="" && $0 ~ /-> \{/) {
      # remember the branch label as it opens
      lbl=$0; sub(/[[:space:]]*->.*/,"",lbl); gsub(/^[[:space:]]+/,"",lbl)
      branch=lbl; bdepth=depth; body=""
    }
    if (branch!="") body=body $0 "\n"
    depth += n - m
    if (branch!="" && depth<=bdepth) {
      if (body !~ /surface\(/ && body !~ /notifyConfirm\(/) print branch
      branch=""
    }
    if (depth<=0) inwhen=0
  }
' "$ROOT/$RCV")
if [ -z "$SILENT" ]; then
  ok "every branch of when(status) reaches surface() or notifyConfirm()"
else
  bad "silent status branch(es): $(echo "$SILENT" | tr '\n' ' ')"
fi

echo "== T2: STATUS_PENDING_USER_ACTION launches its Intent =="
# Not a failure at all — the system is asking to be asked. Ignoring it is what
# makes a committed session wait forever for a confirmation never requested.
has_code "$RCV" 'context.startActivity(confirm)'      "the confirm Intent is actually launched"
has_code "$RCV" 'Intent.FLAG_ACTIVITY_NEW_TASK'       "launched with NEW_TASK (a receiver has no task of its own)"
# The old silent bail-out. `?: run { releaseGate(); return }` said nothing at
# all on a status that means the install is stuck pending an unaskable question.
hasnt_code "$RCV" '?: run { releaseGate(); return }'  "no silent bail-out when EXTRA_INTENT is missing"

echo "== T3: the notification is the guarantee, the dialog is the optimisation =="
# ORDER IS THE FIX. notifyConfirm must run BEFORE the isForeground branch, so a
# stale foreground sample cannot leave the user with nothing. When it lived
# inside the background `else`, a probe that guessed wrong was unrecoverable.
NL=$(grep -n 'notifyConfirm(context, confirm' "$ROOT/$RCV" | head -1 | cut -d: -f1)
FL=$(grep -n 'if (isForeground(context))' "$ROOT/$RCV" | head -1 | cut -d: -f1)
if [ -n "$NL" ] && [ -n "$FL" ] && [ "$NL" -lt "$FL" ]; then
  ok "notifyConfirm posts before the foreground branch (line $NL < $FL)"
else
  bad "notifyConfirm must run before isForeground (got notify=${NL:-none} foreground=${FL:-none})"
fi
# startActivity silently no-ops on a blocked background start, so its failure
# must not be the only thing standing between the user and the install.
has_code "$RCV" 'runCatching { context.startActivity(confirm) }' "a refused dialog start is caught, not assumed impossible"
# A tap-to-install notification for a session that already ended is an
# instruction to do something that cannot be done.
# #1551 per package: a terminal answer clears ITS app's prompt, never another app's.
CLR=$(code "$RCV" | grep -c 'clearConfirmNotification(context, gateKey)')
[ "$CLR" -ge 2 ] && ok "both terminal branches clear the stale confirm notification" \
                 || bad "expected the confirm notification cleared on SUCCESS and on failure (found $CLR)"

echo "== T4: every status Android defines is NAMED, not numbered =="
# The platform's own list. A bare "status=7" tells the owner nothing, and the
# owner cannot read logcat for another app on this phone.
for S in STATUS_FAILURE STATUS_FAILURE_ABORTED STATUS_FAILURE_BLOCKED \
         STATUS_FAILURE_CONFLICT STATUS_FAILURE_INCOMPATIBLE STATUS_FAILURE_INVALID \
         STATUS_FAILURE_STORAGE STATUS_FAILURE_TIMEOUT STATUS_SUCCESS \
         STATUS_PENDING_USER_ACTION; do
  has_code "$RCV" "PackageInstaller.$S" "$S is handled by name"
done
# "the installer answered without a status" must not read as a status.
has_code "$RCV" 'NO_STATUS' "a missing EXTRA_STATUS is its own named case"

echo "== T5: a 267 MB install is sized and space-checked BEFORE it is attempted =="
# Without setSize the platform reserves nothing and evicts no cache ahead of
# the staging write. Invisible at 25 MB; decisive at 267 MB.
has_code "$INST" 'setSize(expected + splits.sumOf { it.length() })'          "the session is told how big the APK is"
has_code "$INST" 'openWrite("base.apk", 0, expected)' "the write declares the same length the session was sized with"
has_code "$INST" 'freeStagingBytes()'         "free space is read before committing"
has_code "$INST" 'free in 0 until expected'   "a shortfall that makes staging impossible refuses early"
# -1 means "could not read it". Refusing an install over a number we failed to
# obtain is its own unexplained failure.
has_code "$INST" 'getOrDefault(-1L)'          "an unreadable free-space figure never blocks an install"

echo "== T6: the client verifies the BYTES before spending an install attempt =="
# The publish side hard-verifies a sha256 and ships a "<asset>.sha256" sidecar.
# A length check cannot tell a 267 MB APK from a different 267 MB APK — this
# store has already been wrong that exact way (two builds, identical size).
has_code "$SRC" 'Fleet.releaseSha256(app)'          "the release path reads the published digest"
has_code "$SRC" 'VerifiedApk.byDigest(target, sha)' "and verifies the download against it"
has_code "$SRC" 'VerifiedApk.bySize(target, declared)' "size remains the fallback for assets with no sidecar"
has_code "$SRC" 'ApkCache.drop(target)'             "bytes that fail verification are dropped, not resumed onto"

echo "== T7: no success is reported without observing success =="
# Fleet.install returns as soon as a channel ACCEPTED the APK. For the
# PackageInstaller channel that means a session was handed over — the outcome
# lands later at the receiver. Clearing the advisory on a mere commit wiped the
# record a later failure was supposed to leave.
has_code "$FLEET" 'fun observesOutcome(channelName: String)' "the two meanings of 'the channel returned' are distinguished"
has_code "$FLEET" 'channelName == ShellInstall.name'         "only the channel that reads pm's answer counts as observed"
has_code "$PAGE"  'if (Fleet.observesOutcome(Fleet.commit(ctx, app, apk))) Advisory.recordSuccess' "the store clears the advisory only on an observed install"

echo "== T8 (#625): the APK cache is PERSISTENT, and retention is proven, not assumed =="
# The cache used to be context.cacheDir. Android reclaims cacheDir under storage
# pressure without asking and Settings > Clear cache wipes it, so a 267 MB
# download really did vanish between fetch and install — which is exactly the
# "the store deletes my download" that was reported. There was no
# delete-on-failure bug: the deleting process was the OS.
CACHE="$UPD/cache/ApkCache.kt"
has_code "$CACHE" 'ctx.noBackupFilesDir' "the cache lives in a dir the OS does not reclaim"
hasnt_code "$CACHE" 'ctx.cacheDir' "and never in cacheDir"
has_code "$CACHE" 'BuildConfig.APK_CACHE_DIR' "its name is declared in build.json, not written here"
has_code "$CACHE" 'BuildConfig.APK_CACHE_MAX_BYTES' "so is its size bound"
has_code "$CACHE" 'BuildConfig.APK_CACHE_EVICT' "so is its eviction policy"
# Retention is gated on the DEVICE agreeing, not on a broadcast saying so.
has_code "$CACHE" 'if (onDisk != rec.sha256)' "a file that is not the artifact downloaded is never read as installed"
has_code "$CACHE" 'if (code != rec.versionCode)' "nor is a package installed at another versionCode"
has_code "$RCV" 'ApkCache.reapIfInstalled' "the success branch asks the cache, it does not delete by itself"
hasnt_code "$RCV" 'context.cacheDir' "the 'only our own cache' check followed the move"
# ONE engine owns download-all-then-install; see ApkCacheRetentionTest for the
# ordering assertion itself.
BATCH="$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/BatchInstall.kt"
# ORDERING, not merely presence: the LAST stage call must come before the FIRST
# install call in the engine's own body — the textual form of the guarantee
# app/src/test/.../apps/ApkCacheRetentionTest.kt asserts by execution.
stage_last=$(code "$BATCH" | grep -n 'engine.stage(' | tail -1 | cut -d: -f1)
inst_first=$(code "$BATCH" | grep -n 'engine.install(' | head -1 | cut -d: -f1)
if [ -n "$stage_last" ] && [ -n "$inst_first" ] && [ "$stage_last" -lt "$inst_first" ]; then
    ok "the batch downloads every app before it installs any"
else
    bad "BatchInstall interleaves download and install (stage@${stage_last:-none} install@${inst_first:-none})"
fi
has_code "$LIB/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StorePhoneFragment.kt" \
    'BatchInstall.run(' "Store > Phone install-all/update-all runs through that one engine"

echo
echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
