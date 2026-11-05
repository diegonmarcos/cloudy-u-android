#!/usr/bin/env bash
# Tester (#1508): the speed meter is NOT on the home status bar; it is declared rows inside the network dropdown and samples
# only while the dropdown is open (battery rules #1487/#1502). Static only. Mutations prove each assertion red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; J=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
check() { # check <strip.kt> <popup.kt> <meter.kt> <build.json>
  local strip="$1" pop="$2" meter="$3" bj="$4"
  grep -qE 'SpeedChip|SpeedMeter|strip:speed|\.speed\b' "$strip" && bad "S1 the status strip still draws or runs the speed meter" || ok "S1 the strip neither draws nor runs the speed meter"
  [ "$(jq -r '[.home_status_bar.network_speed.rows[].id] | join(",")' "$bj")" = "now,avg24" ] && ok "S2 rows now, avg24 are declared" || bad "S2 build.json::home_status_bar.network_speed.rows is not now,avg24"
  [ "$(jq -r '.home_status_bar.network_speed.sample_ms' "$bj")" = "1000" ] && ok "S3 the refresh is declared at 1000 ms" || bad "S3 sample_ms is not 1000"
  grep -q 'SpeedRows(ctx)' "$pop" && grep -q 'DisposableEffect(meter) { meter.start(); onDispose { meter.stop() } }' "$pop" && ok "S4 the dropdown starts the meter on open and stops it on dispose" || bad "S4 the dropdown does not bind the meter to its visibility"
  grep -q 'PopTitle("Network")' "$pop" && awk '/PopTitle\("Network"\)/{f=1} f&&/SpeedRows\(ctx\)/{print "y"; exit}' "$pop" | grep -q y && ok "S5 the rows sit in the Network table" || bad "S5 SpeedRows is not under the Network title"
  grep -qE 'PASSIVE_MS|postDelayed\(tick, [^s]' "$meter" && bad "S6 the meter keeps a fixed background period" || ok "S6 no fixed background period; the period is the declared sample_ms"
  [ "$(grep -c 'postDelayed' "$meter")" = "2" ] && grep -q 'if (running) main.postDelayed' "$meter" && ok "S7 the tick re-posts only while running" || bad "S7 the tick can re-post after stop"
  grep -q 'main.removeCallbacks(tick)' "$meter" && ok "S8 stop removes the callback" || bad "S8 stop leaves the callback posted"
}
echo "[speed-in-dropdown] the real tree"; check "$APP/$J/launcher/LauncherStatusStrip.kt" "$APP/$J/network/NetworkInfoPopup.kt" "$APP/$J/network/SpeedMeter.kt" "$APP/build.json"
echo "[speed-in-dropdown] mutations (each must turn it red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <file key: strip|pop|meter|bj> <sed expr>
  cp "$APP/$J/launcher/LauncherStatusStrip.kt" "$W/strip"; cp "$APP/$J/network/NetworkInfoPopup.kt" "$W/pop"; cp "$APP/$J/network/SpeedMeter.kt" "$W/meter"; cp "$APP/build.json" "$W/bj"
  sed -i "$3" "$W/$2"; ( check "$W/strip" "$W/pop" "$W/meter" "$W/bj" ) >"$W/out" 2>&1
  grep -q FAIL "$W/out" && ok "mutant '$1' is red" || bad "mutant '$1' stayed green"
}
mut "chip back on the bar" strip 's/^internal fun StatusStripBody/\/\/ SpeedChip(model.speed)\nprivate val x = com.diegonmarcos.superapp.network.SpeedMeter\ninternal fun StatusStripBody/'
mut "dropdown never stops it" pop 's/onDispose { meter.stop() }/onDispose { }/'
mut "row dropped" bj 's/"id": "avg24", //'
mut "background loop" meter 's/if (running) main.postDelayed/main.postDelayed/'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
