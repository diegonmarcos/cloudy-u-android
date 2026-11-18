#!/usr/bin/env bash
# emulator-relay-jev.sh — #1801 PROOF that Cloud Calc's Jev section reaches the OpenRouter key only through the AI-key owner
# engine (relay use calc_jev, caller com.diegonmarcos.cloudcalc, endpoint decisions).
#   A  no key in the engine: Jev > ask; logcat (tag DecisionsEngine) must hold `relay <calc> calc_jev ✗ no_key`
#   B  type a run-time dummy key into the engine's AI keys screen (KeysActivity) and Save; ask again; logcat must hold
#      `relay <calc> calc_jev ✓ code=<n>` (OpenRouter answers 401 to a bad key: the engine added a key the app never had)
# The trigger is the UI path (JevDebugApi /api/jev/route is IPC-only: a fleet-signed door or the SuperApp gateway reaches it, adb does
# not): MainActivity extra clock_mode=jev opens the Jev mode; the input is the first EditText, then the "Ask" button.
# Evidence: screenshots, ui dumps, the engine's relay lines and logcat, per phase, in <outdir>.
#
# usage: emulator-relay-jev.sh <engine.apk> <calc.apk> <outdir>
set -u
. "$(dirname "${BASH_SOURCE[0]}")/../../ab_cloud-libs-shared/libs/decisions-engine/test/emulator-relay-common.sh"
ENGINE_APK="$1"; APK="$2"; relay_init "$3"
PKG=$(pkg_of "$APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$APK" || exit 2
ACT=$(launcher_of "$PKG"); echo "calc=$PKG ($ACT) engine=$EPKG_ENGINE"

trigger() {   # $1 phase
  adb shell am force-stop "$PKG"; adb shell am start -n "$ACT" --es clock_mode jev >/dev/null; sleep 8
  shot "jev-$1-open" 1
  type_into_first_edit "convert%s3%sft%sto%scm" || { fail "$1 no Jev input"; return 1; }
  hide_ime
  tap_text "Ask" 2 || { fail "$1 no Ask button"; return 1; }
  sleep 5
}
relay_phases jev "$PKG" calc_jev trigger '401|credential|unauthori|unavailable|user not found|no auth|No OpenRouter|no key|Computed offline' 90
