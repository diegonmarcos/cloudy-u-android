#!/usr/bin/env bash
# emulator-relay-writer.sh — #1801 PROOF that the Writer's Translation route (route MODEL, WriterRoutes.translate) reaches the
# OpenRouter key only through the AI-key owner engine (relay use writer_routes, caller com.diegonmarcos.cloudkeyboard, which compiles
# libs:writer-ui).
#   A  no key in the engine: a text is translated on the MODEL route; WriterRoutes gates on DecisionsLink.keys first (route blocker
#      "no token"), so no relay call is made and the route falls back on device. Required: no `✓` line for the keyboard (an
#      `✗ no_key` line is accepted if the build calls the engine anyway); the fallback is on screen (WARN names it)
#   B  type a run-time dummy key into the AI keys screen and Save; translate again; logcat must hold
#      `relay <keyboard> writer_routes ✓ code=<n>` (OpenRouter answers 401 to a bad key)
# The trigger is the UI path (WriterDebugApi /api/writer/translate is IPC-only: adb cannot reach it): a text shared into
# WriterShareActivity (SEND text/plain) opens the Writer editor; the "Translate" tool runs on the Translation route (default MODEL).
# Evidence: screenshots, ui dumps, the engine's relay lines and logcat, per phase, in <outdir>.
#
# usage: emulator-relay-writer.sh <engine.apk> <keyboard.apk> <outdir>
set -u
. "$(dirname "${BASH_SOURCE[0]}")/../../ab_cloud-libs-shared/libs/decisions-engine/test/emulator-relay-common.sh"
ENGINE_APK="$1"; APK="$2"; relay_init "$3"
PKG=$(pkg_of "$APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$APK" || exit 2
echo "keyboard=$PKG engine=$EPKG_ENGINE"

trigger() {   # $1 phase
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/.WriterShareActivity" -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Good morning, how are you today'" >/dev/null; sleep 8
  shot "writer-$1-editor" 1
  tap_sub "Translate" 3 || { fail "$1 no Translate tool"; return 1; }
  sleep 10
}
relay_phases writer "$PKG" writer_routes trigger '401|credential|unauthori|user not found|no auth|fallback|on-device|no token|Translat' 60
