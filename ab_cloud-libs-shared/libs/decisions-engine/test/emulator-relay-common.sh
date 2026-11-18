#!/usr/bin/env bash
# emulator-relay-common.sh — helpers the #1801 relay proofs source (emulator-relay-*.sh). Not run on its own.
#
# A proof is two phases against the AI-key owner engine (Cloud-Lib-Decisions-Engine.apk):
#   A  the engine holds NO key: the app's AI call must reach the engine and be refused, logcat tag DecisionsEngine:
#        relay <callerPackage> <use> ✗ no_key
#   B  the AI keys screen (KeysActivity, action <enginePkg>.AI_KEYS) gets a dummy key typed and saved; the same call must
#        be answered by the provider: relay <callerPackage> <use> ✓ code=<n>   (OpenRouter answers 401 to a bad key, which
#        proves the engine ADDED a key the app never had).
# Needs: ANDROID_HOME (aapt2), adb, python3. The dummy key is built at run time and never written to a file or a log.
set -u

relay_init() {   # $1 outdir
  OUT="$1"; mkdir -p "$OUT"
  FAILS=0
  EPKG_ENGINE=com.diegonmarcos.cloudlib.decisionsengine
  # phase A needs an engine that holds no key: a fresh emulator has none, a re-run on a used one is cleared (pm clear, after install)
  adb shell settings put global hide_error_dialogs 1 || true
  adb shell settings put global window_animation_scale 0; adb shell settings put global transition_animation_scale 0
  adb shell settings put system screen_off_timeout 2147483647; adb shell svc power stayon true
  adb shell locksettings set-disabled true || true
  local sz; sz=$(adb shell wm size | tr -d '\r' | sed -n 's/.*: \([0-9]*\)x\([0-9]*\)$/\1 \2/p' | tail -1); W=${sz% *}; H=${sz#* }
}
pkg_of() { "$(ls -d "$ANDROID_HOME"/build-tools/*/aapt2 | sort -V | tail -1)" dump packagename "$1"; }
launcher_of() { adb shell cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.LAUNCHER "$1" | tr -d '\r' | tail -1; }

ok()   { echo "ok     $1"; }
fail() { echo "FAIL   $1"; FAILS=$((FAILS + 1)); }
warn() { echo "WARN   $1"; }

awake() { adb shell input keyevent KEYCODE_WAKEUP; adb shell wm dismiss-keyguard; adb shell input keyevent 82; }
shot() { awake; sleep "${2:-3}"; adb exec-out screencap -p > "$OUT/$1.png"; dump > "$OUT/$1.xml"; echo "captured $1"; }
dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; adb shell cat /sdcard/ui.xml 2>/dev/null; }

# find_xy <want> <mode: eq|sub|id|edit> : the centre of the first node whose text/content-desc equals (eq) or contains (sub) <want>,
# whose resource-id ends with <want> (id), or the first EditText (edit; <want> ignored)
find_xy() {
  python3 -I - "$1" "$OUT/ui.xml" "${2:-eq}" <<'PY'
import re, sys
want, path, mode = sys.argv[1], sys.argv[2], sys.argv[3]
x = open(path, encoding="utf-8", errors="replace").read()
for m in re.finditer(r'<node [^>]*>', x):
    n = m.group(0)
    vals = [v.group(1) for v in (re.search(r' text="([^"]*)"', n), re.search(r' content-desc="([^"]*)"', n)) if v]
    rid = (re.search(r' resource-id="([^"]*)"', n) or [None, ""])[1]
    cls = (re.search(r' class="([^"]*)"', n) or [None, ""])[1]
    hit = (mode == "id" and (rid == want or rid.endswith("/" + want))) or (mode == "edit" and cls.endswith("EditText")) \
        or (mode == "eq" and want in vals) or (mode == "sub" and any(want in v for v in vals))
    if hit:
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', n)
        if b:
            a = list(map(int, b.groups())); print((a[0] + a[2]) // 2, (a[1] + a[3]) // 2); break
PY
}
# tap_node <want> <mode> [scrolls]: tap the node, scrolling the page up to <scrolls> times to reach it
tap_node() {
  local xy i
  for i in $(seq 0 "${3:-4}"); do
    dump > "$OUT/ui.xml"
    xy=$(find_xy "$1" "$2")
    if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped $2:$1 at $xy"; return 0; fi
    adb shell input swipe $((W/2)) $((H*3/4)) $((W/2)) $((H/3)) 400; sleep 1
  done
  echo "no node $2:$1"; return 1
}
tap_text() { tap_node "$1" eq "${2:-4}"; }
tap_id()   { tap_node "$1" id "${2:-0}"; }
tap_sub()  { tap_node "$1" sub "${2:-0}"; }
# wait up to $2 seconds for a node containing $1
wait_text() {
  local i
  for i in $(seq 1 "$2"); do dump > "$OUT/ui.xml"; [ -n "$(find_xy "$1" sub)" ] && return 0; sleep 1; done
  return 1
}
# type_into_first_edit <text>: tap the first EditText and type (adb input text; no spaces issue: %s)
type_into_first_edit() {
  dump > "$OUT/ui.xml"; local xy; xy=$(find_xy x edit)
  [ -n "$xy" ] || { echo "no EditText"; return 1; }
  adb shell input tap $xy; sleep 1; adb shell input text "$1"; sleep 1
}
hide_ime() { adb shell dumpsys input_method | tr -d '\r' | grep -q 'mInputShown=true' && adb shell input keyevent KEYCODE_BACK; sleep 1; return 0; }

# engine_reset: wipe the engine's data (its sealed key book) so it holds no key
engine_reset() { adb shell pm clear "$EPKG_ENGINE" >/dev/null 2>&1 || true; }

# ---- the engine's log ----
# engine_lines: every DecisionsEngine relay line so far
engine_lines() { adb logcat -d -v brief -s DecisionsEngine:I 2>/dev/null | grep -a 'relay ' || true; }
# wait_relay <callerPkg> <use> <pattern-after-use> <seconds>: poll the engine's log for "relay <pkg> <use> <pattern>" (fixed string)
wait_relay() {
  local i; for i in $(seq 1 "$4"); do
    engine_lines > "$OUT/.engine.txt"
    grep -aqF "relay $1 $2 $3" "$OUT/.engine.txt" && return 0
    sleep 1
  done
  return 1
}
# wait_relay_ok <pkg> <use> <seconds>: any "✓ code=<n>" line
wait_relay_ok() {
  local i; for i in $(seq 1 "$3"); do
    engine_lines > "$OUT/.engine.txt"
    grep -aF "relay $1 $2 " "$OUT/.engine.txt" | grep -aq '✓ code=' && return 0
    sleep 1
  done
  return 1
}
save_logs() {   # $1 label: the engine's relay lines and the full logcat
  engine_lines > "$OUT/$1-engine-relay.txt"
  adb logcat -d -v brief > "$OUT/$1-logcat.txt" 2>/dev/null || true
}

# ---- the AI keys screen ----
# set_engine_key: open KeysActivity, type a run-time dummy key, tap Save, require "Key held" afterwards
set_engine_key() {
  local part1="dummy-fleet" part2; part2=$(date +%s%N)
  local k="${part1}-${part2}"
  adb shell am start -a "$EPKG_ENGINE.AI_KEYS" --es provider openrouter >/dev/null || return 1
  sleep 4; shot keys-before 1
  type_into_first_edit "$k" || return 1
  hide_ime
  shot keys-typed 1
  tap_text "Save" 4 || return 1
  sleep 2; shot keys-saved 1
  if wait_text "Key held" 8; then ok "the engine's AI keys screen reports a key held"; return 0; fi
  fail "the AI keys screen did not report 'Key held' after Save (keys-saved.png)"; return 1
}

# ---- the whole proof ----
# relay_phases <app-label> <callerPkg> <use> <trigger-fn> <surface-regex> <wait-s>
#   <trigger-fn> runs the app's AI call from a cold start of the app; it is called once per phase.
relay_phases() {
  local label="$1" pkg="$2" use="$3" trigger="$4" surface="$5" wait="${6:-60}"
  # A: no key in the engine
  engine_reset; adb logcat -c
  "$trigger" A
  if wait_relay "$pkg" "$use" "✗ no_key" "$wait"; then ok "A $label reached the engine and was refused: $(grep -aF "relay $pkg $use" "$OUT/.engine.txt" | tail -1 | sed 's/^[^:]*: //')"
  elif grep -aF "relay $pkg $use " "$OUT/.engine.txt" | grep -aq '✓'; then fail "A $label was answered by a provider although the engine holds no key"
  else warn "A $label made no relay call with no key held: the app gates on DecisionsLink.keys before calling (no_key line absent). See ${label}-A-*.png for the app's own no-key sentence"; A_GATED=1; fi
  save_logs "$label-A"; shot "$label-A-app" 1
  # B: a key typed into the engine's own screen
  adb logcat -c
  if set_engine_key; then
    adb logcat -c
    "$trigger" B
    if wait_relay_ok "$pkg" "$use" "$wait"; then
      ok "B $label's call was answered through the engine: $(grep -aF "relay $pkg $use " "$OUT/.engine.txt" | grep -a '✓ code=' | tail -1 | sed 's/^[^:]*: //') (401 = the provider rejected the dummy key the engine added)"
    else fail "B no 'relay $pkg $use ✓ code=' line after the key was saved"; fi
    save_logs "$label-B"; shot "$label-B-app" 1
    if grep -aEiq "$surface" "$OUT/$label-B-app.xml" "$OUT/$label-B-logcat.txt" 2>/dev/null; then ok "B $label surfaced the provider's answer (matched /$surface/ on screen or in its own logcat)"
    else warn "B no text matching /$surface/ on screen or in the app's logcat; read $label-B-app.png"; fi
  else fail "B could not store a key in the engine, so the keyed call was not tried"; fi
  echo
  if [ "$FAILS" -eq 0 ]; then echo "PASS — $label's AI call goes through the engine, which adds the key (#1801)"; return 0; fi
  echo "FAIL — $FAILS check(s)"; return 1
}
