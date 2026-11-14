#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #588 — a backgrounded self-update INSTALLS, or says why it cannot        ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# Live finding: an unattended update downloaded the new SuperApp, committed a
# PackageInstaller session, logged "confirm launch deferred to notification"
# and the phone stayed on the old build — while a fleet library in the same
# pass installed silently through the embedded-adb shell. Fleet apps tapped
# later answered INSTALL_FAILED_ABORTED (-115) "Session was abandoned".
#
#   T1  THE SELF-UPDATE TAKES THE SHELL RUNG FIRST: UpdateWorker installs
#       through installSelf(), which tries ShellInstall.shellInstall before
#       any PackageInstaller session, and honours "never prompt".
#   T2  THE REAPER NEVER ABANDONS A COMMITTED SESSION below the cap: a
#       committed session is the one behind a tap-to-finish notification.
#   T3  A HIDDEN CONFIRMATION IS REPORTED: notifyConfirm says whether the
#       notification can be SEEN (app + channel enabled), and the background
#       branch surfaces an ERROR instead of pointing at a notification that
#       does not exist.
#   T4  THE LEGACY CODE TABLE MATCHES AOSP: every mapped code carries the name
#       PackageManager gives that value, and -115 is named ABORTED.
#   MUT each property, broken on a copy, turns its check red — and the
#       mutation is verified to have changed the copy before it counts.
#
# OWN-SOURCE ONLY. grep/sed/awk, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
U="$ROOT/ab_cloud-libs-shared/libs/updater/src/main/java/com/diegonmarcos/superapp/updater"
WORKER="$U/UpdateWorker.kt"
INSTALLER="$U/install/UpdateInstaller.kt"
# #1735 the reap policy moved into InstallSessions (pure, unit-tested); UpdateInstaller reaps through it
SESSIONS="$U/install/InstallSessions.kt"
RECEIVER="$U/PackageInstallerReceiver.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# a file's CODE, comment lines stripped, so prose about a defect never reads as one
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }
# the body of `fun <name>` up to the next top-level-in-class `fun`/`companion`
_fun() { _code "$1" | awk -v n="fun $2(" 'index($0,n){on=1} on&&/^    (private |internal )?(fun|companion)/&&!index($0,n){exit} on'; }

for f in "$WORKER" "$INSTALLER" "$SESSIONS" "$RECEIVER"; do
    [ -f "$f" ] || { echo "ERROR missing source: $f — this tester is unrun, not passing"; exit 1; }
done

t1() {
    local w="$1" bad=0 body
    [ "$(_code "$w" | grep -cE '^[[:space:]]+installSelf\(apk\)')" -ge 1 ] \
        || { echo "    doWork does not install through installSelf"; bad=1; }
    [ "$(_fun "$w" doWork | grep -cE 'UpdateInstaller\(')" -eq 0 ] \
        || { echo "    doWork opens a PackageInstaller session directly, skipping the shell rung"; bad=1; }
    body="$(_fun "$w" installSelf)"
    local shell_at session_at
    shell_at="$(printf '%s\n' "$body" | grep -nE 'ShellInstall\.shellInstall\(' | head -1 | cut -d: -f1)"
    session_at="$(printf '%s\n' "$body" | grep -nE 'UpdateInstaller\(' | head -1 | cut -d: -f1)"
    [ -n "$shell_at" ] && [ -n "$session_at" ] && [ "$shell_at" -lt "$session_at" ] \
        || { echo "    installSelf does not try the shell channel before the session"; bad=1; }
    [ "$(printf '%s\n' "$body" | grep -cE 'AutoUpdatePrefs\.requireSilent\(')" -ge 1 ] \
        || { echo "    installSelf ignores the \"never prompt\" preference"; bad=1; }
    return $bad
}

t2() {
    [ "$(_fun "$1" victims | grep -cE 'idle\.filter \{ !it\.committed && it\.createdMs > 0 && now - it\.createdMs > STALE_SESSION_MS \}')" -eq 1 ] \
        || { echo "    the stale branch can abandon a committed session awaiting the user"; return 1; }
}

t3() {
    local r="$1" bad=0 nc
    nc="$(_fun "$r" notifyConfirm)"
    grep -qE 'fun notifyConfirm\(.*\): Boolean' <<<"$nc" \
        || { echo "    notifyConfirm does not report whether the notification is visible"; bad=1; }
    grep -qE 'nm\.areNotificationsEnabled\(\)' <<<"$nc" \
        || { echo "    visibility ignores the app-level notification switch"; bad=1; }
    grep -qE 'importance != NotificationManager\.IMPORTANCE_NONE' <<<"$nc" \
        || { echo "    visibility ignores a muted Updater channel"; bad=1; }
    [ "$(_code "$r" | grep -cE 'val shown = notifyConfirm\(')" -eq 1 ] \
        || { echo "    onReceive discards notifyConfirm's answer"; bad=1; }
    [ "$(_code "$r" | grep -A22 -E 'if \(!shown\) \{' | grep -cE 'severity = NotificationStore\.Sev\.ERROR')" -ge 1 ] \
        || { echo "    a hidden confirmation is not surfaced as an ERROR"; bad=1; }
    return $bad
}

# AOSP PackageManager values (frameworks/base core/java/android/content/pm/PackageManager.java)
AOSP='-1 ALREADY_EXISTS
-2 INVALID_APK
-3 INVALID_URI
-4 INSUFFICIENT_STORAGE
-5 DUPLICATE_PACKAGE
-7 UPDATE_INCOMPATIBLE
-8 SHARED_USER_INCOMPATIBLE
-9 MISSING_SHARED_LIBRARY
-12 OLDER_SDK
-15 TEST_ONLY
-20 MEDIA_UNAVAILABLE
-23 PACKAGE_CHANGED
-24 UID_CHANGED
-25 VERSION_DOWNGRADE
-29 DEPRECATED_SDK_VERSION
-100 NOT_APK
-101 BAD_MANIFEST
-102 UNEXPECTED_EXCEPTION
-103 NO_CERTIFICATES
-104 INCONSISTENT_CERTIFICATES
-105 CERTIFICATE_ENCODING
-108 MANIFEST_MALFORMED
-110 INTERNAL_ERROR
-113 NO_MATCHING_ABIS
-115 ABORTED
-116 SESSION_INVALID
-117 BAD_DEX_METADATA
-118 BAD_SIGNATURE
-124 RESOURCES_ARSC_COMPRESSED'

t4() {
    local r="$1" bad=0 code name want
    while read -r code name; do
        want="$(printf '%s\n' "$AOSP" | awk -v c="$code" '$1==c{print $2}')"
        if [ -z "$want" ]; then echo "    $code is mapped but not in the AOSP fixture — add it there first"; bad=1
        elif ! grep -qE "^INSTALL_(FAILED|PARSE_FAILED)_${want}\b" <<<"$name"; then
            echo "    $code is labelled $name, AOSP calls it $want"; bad=1; fi
    done < <(_fun "$r" legacyStatusName | sed -nE 's/^[[:space:]]+(-[0-9]+) -> "([A-Z_]+).*/\1 \2/p')
    [ "$(_fun "$r" legacyStatusName | grep -cE '^[[:space:]]+-115 -> "INSTALL_FAILED_ABORTED')" -eq 1 ] \
        || { echo "    -115 (the code the phone reported) is not named"; bad=1; }
    return $bad
}

echo "── T1 the self-update tries the privileged shell before a session ──"
t1 "$WORKER" && pass "doWork → installSelf → ShellInstall.shellInstall, then UpdateInstaller unless never-prompt" \
    || fail "a backgrounded self-update can only end at a confirm dialog"
echo "── T2 the reaper spares committed sessions ──"
t2 "$SESSIONS" && pass "stale reap is limited to uncommitted sessions" \
    || fail "a pending tap-to-finish session gets abandoned (-115)"
echo "── T3 a confirmation nobody can see is reported ──"
t3 "$RECEIVER" && pass "notifyConfirm reports visibility; hidden → ERROR surfaced" \
    || fail "the row can claim a notification that does not exist"
echo "── T4 legacy codes carry AOSP's names ──"
t4 "$RECEIVER" && pass "every mapped legacy code matches PackageManager; -115 named" \
    || fail "an install failure is reported under the wrong code"

echo "── MUT mutation proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
# mut <label> <check-fn> <src> <sed-expr>
mut() {
    local label="$1" fn="$2" src="$3" expr="$4" copy="$TMP/m.kt"
    sed -E "$expr" "$src" > "$copy"
    if cmp -s "$src" "$copy"; then fail "MUT $label: the mutation did not apply — proves nothing"; return; fi
    if "$fn" "$copy" >/dev/null 2>&1; then fail "MUT $label: still green with the defect planted"
    else pass "MUT $label: red"; fi
}
mut "self-update straight to a session" t1 "$WORKER" 's/^([[:space:]]+)installSelf\(apk\)$/\1UpdateInstaller(applicationContext).install(apk)/'
mut "shell rung removed"               t1 "$WORKER" 's/ShellInstall\.shellInstall\(applicationContext, apk\)/null/'
mut "never-prompt ignored"             t1 "$WORKER" 's/AutoUpdatePrefs\.requireSilent\(applicationContext\)/false/'
mut "reaper takes committed sessions"  t2 "$SESSIONS" 's/!it\.committed && //'
mut "visibility not checked"           t3 "$RECEIVER" 's/nm\.areNotificationsEnabled\(\) &&/true \&\&/'
mut "hidden confirm stays silent"      t3 "$RECEIVER" 's/val shown = notifyConfirm\(/notifyConfirm(/'
mut "-115 unnamed"                     t4 "$RECEIVER" '/^[[:space:]]+-115 -> /d'
mut "-118 back to ABORTED"             t4 "$RECEIVER" 's/-118 -> "INSTALL_FAILED_BAD_SIGNATURE"/-118 -> "INSTALL_FAILED_ABORTED"/'

echo
if [ "$FAILURES" -eq 0 ]; then echo "self-update-background: all checks green"; exit 0; fi
echo "self-update-background: $FAILURES failure(s)"; exit 1
