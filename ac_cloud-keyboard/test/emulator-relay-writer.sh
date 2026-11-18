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

# Translate refuses with no target ("Pick a target under Language Output first", run 44318651005): seed Language Output =
# german in Writer's own prefs (file text_tools, key enhance_language) through run-as; the ship builds are debuggable.
seed_target() {
  local f="$OUT/text_tools.xml"
  printf '%s\n' "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>" '<map>' '    <string name="enhance_language">german</string>' '</map>' > "$f"
  adb shell am force-stop "$PKG"
  adb push "$f" /data/local/tmp/text_tools.xml >/dev/null
  adb shell run-as "$PKG" mkdir -p shared_prefs
  adb shell run-as "$PKG" cp /data/local/tmp/text_tools.xml shared_prefs/text_tools.xml
  if adb shell run-as "$PKG" cat shared_prefs/text_tools.xml | tee "$OUT/writer-prefs.txt" | grep -q 'enhance_language">german'
  then ok "Writer's Language Output seeded (german)"; else warn "could not seed Writer's Language Output through run-as"; fi
}

trigger() {   # $1 phase
  seed_target
  adb shell am force-stop "$PKG"
  adb shell am start -n "$PKG/.WriterShareActivity" -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "'Good morning, how are you today'" >/dev/null; sleep 8
  shot "writer-$1-editor" 1
  # The tools strip (Text Enhance, Grammar Check, Summary, ...) scrolls sideways; Translate is past the right edge.
  # Find the strip on EVERY attempt (its height moves with the soft keyboard), swipe it, look again. No vertical page
  # scroll in between: tap_node's own scroll would move the editor, not the strip.
  local i xy y
  for i in 0 1 2 3 4 5 6; do
    dump > "$OUT/ui.xml"
    xy=$(find_xy "Translate" sub); [ -n "$xy" ] && { adb shell input tap $xy; echo "tapped Translate at $xy"; break; }
    [ "$i" = 6 ] && { fail "$1 no Translate tool"; return 1; }
    xy=$(find_xy "Grammar Check" eq); [ -n "$xy" ] || xy=$(find_xy "Summary" eq); [ -n "$xy" ] || xy=$(find_xy "Text Enhance" eq)
    [ -n "$xy" ] || { fail "$1 no Writer tools strip"; return 1; }
    y=${xy#* }
    adb shell input swipe $((W*9/10)) "$y" $((W/10)) "$y" 400; sleep 1
  done
  sleep 10
}
relay_phases writer "$PKG" writer_routes trigger '401|credential|unauthori|user not found|no auth|fallback|on-device|no token|Translat' 60
