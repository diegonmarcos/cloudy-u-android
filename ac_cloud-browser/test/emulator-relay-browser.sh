#!/usr/bin/env bash
# emulator-relay-browser.sh — #1801 PROOF that Cloud Browser's Search AI chat reaches the OpenRouter key only through the
# AI-key owner engine (relay use browser_search_ai, caller com.diegonmarcos.cloudbrowser).
#   A  no key in the engine: send a chat turn; logcat (tag DecisionsEngine) must hold `relay <browser> browser_search_ai ✗ no_key`
#   B  type a run-time dummy key into the engine's AI keys screen (KeysActivity) and Save; send again; logcat must hold
#      `relay <browser> browser_search_ai ✓ code=<n>` (OpenRouter answers 401 to a bad key: the engine added a key the app never had)
# The trigger is the UI path (the fleet debug API /api/search/chat/send is IPC-only: a fleet-signed door or the SuperApp gateway
# reaches it, adb does not): Search (the AI icon in the top bar, resource-id topbar_ai) > the chat box (placeholder
# "Ask anything…") > Send (content-desc). Evidence: screenshots, ui dumps, the engine's relay lines and logcat, per phase, in <outdir>.
#
# usage: emulator-relay-browser.sh <engine.apk> <browser.apk> <outdir>
set -u
. "$(dirname "${BASH_SOURCE[0]}")/../../ab_cloud-libs-shared/libs/decisions-engine/test/emulator-relay-common.sh"
ENGINE_APK="$1"; APK="$2"; relay_init "$3"
PKG=$(pkg_of "$APK")
adb install -r -g "$ENGINE_APK" || exit 2
adb install -r -g "$APK" || exit 2
ACT=$(launcher_of "$PKG"); echo "browser=$PKG ($ACT) engine=$EPKG_ENGINE"

trigger() {   # $1 phase
  adb shell am force-stop "$PKG"; adb shell am start -n "$ACT" >/dev/null; sleep 8
  tap_id topbar_ai || warn "no AI icon (topbar_ai)"; sleep 3; shot "browser-$1-search-open" 1
  # The chat box carries no resource-id: it is the field whose placeholder reads "Ask anything…" (run 44311092339's dump).
  tap_text "Ask anything…" 0 || { fail "$1 no chat box (Ask anything…)"; return 1; }
  adb shell input text "hello"; sleep 1; hide_ime
  # Send is the round button whose content-desc is "Send".
  tap_text "Send" 0 || { fail "$1 no send button (Send)"; return 1; }
  sleep 5
}
relay_phases browser "$PKG" browser_search_ai trigger '401|credential|unauthori|invalid|user not found|no auth|No OpenRouter|no key' 60
