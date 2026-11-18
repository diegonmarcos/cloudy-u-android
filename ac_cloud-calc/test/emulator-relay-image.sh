#!/usr/bin/env bash
# emulator-relay-image.sh — #1801 PROOF that the image engine's OpenRouter route reaches the key only through the AI-key owner
# engine (relay use image_scan_recognize). The caller the engine sees is the image engine APK, com.diegonmarcos.cloudlib.mllimagemlkit,
# not the app that asked: Cloud Calc's Camera > Identify binds it.
#   A  no key in the engine: Identify a photo on the default route (openrouter); logcat (tag DecisionsEngine) must hold
#      `relay com.diegonmarcos.cloudlib.mllimagemlkit image_scan_recognize ✗ no_key` (or, when Calc itself gates on the key before
#      binding, no relay call at all and no ✓: reported as WARN)
#   B  type a run-time dummy key into the AI keys screen and Save; Identify again; logcat must hold `... ✓ code=<n>` (401)
# The trigger is the UI path (CameraDebugApi /api/image/recognize is IPC-only: adb cannot reach it): MainActivity extra
# clock_mode=cam_identify opens Identify; "Take photo" photographs the emulator's virtual scene.
# Evidence: screenshots, ui dumps, the engine's relay lines and logcat, per phase, in <outdir>.
#
# usage: emulator-relay-image.sh <engine.apk> <image-engine.apk> <calc.apk> <outdir>
set -u
. "$(dirname "${BASH_SOURCE[0]}")/../../ab_cloud-libs-shared/libs/decisions-engine/test/emulator-relay-common.sh"
ENGINE_APK="$1"; IMG_APK="$2"; APK="$3"; relay_init "$4"
PKG=$(pkg_of "$APK"); IPKG=$(pkg_of "$IMG_APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$IMG_APK" || exit 2
adb install -r -g "$APK" || exit 2
ACT=$(launcher_of "$PKG"); echo "calc=$PKG ($ACT) image-engine=$IPKG engine=$EPKG_ENGINE"

trigger() {   # $1 phase
  adb shell am force-stop "$PKG"; adb shell am start -n "$ACT" --es clock_mode cam_identify >/dev/null; sleep 8
  shot "image-$1-open" 1
  tap_text "Take photo" 1 || { fail "$1 no Take photo button"; return 1; }
  sleep 10
}
relay_phases image "$IPKG" image_scan_recognize trigger 'Not recognised|fell back|OpenRouter route failed|401|What is in this photo|Count:' 90
