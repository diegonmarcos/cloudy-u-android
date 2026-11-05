#!/usr/bin/env bash
# Tester (#1508, #1523): the network table is NOT on the home status bar; it is declared rows inside the network dropdown. The
# live meter samples only while the dropdown is open (battery rules #1487/#1502), the speed test runs only on its tap, the public
# IP is looked up on open and cached, and the table draws in the ui-kit's row style with no text-size literal. Static only.
# Mutations prove each assertion red.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; J=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
WANT="transport ssid bssid band channel link rssi signal freq standard carrier nettype msignal roaming ipv4 ipv6 public_ip dns gateway private_dns wg mesh_ip warp metered validated captive use_wifi use_mobile use_total down up peak_down peak_up st_down st_up st_latency st_jitter st_last hd_mbps hd_mbs hu_mbps hu_mbs"
check() { # check <strip.kt> <popup.kt> <meter.kt> <build.json> <table-ui.kt> <speedtest.kt>
  local strip="$1" pop="$2" meter="$3" bj="$4" ui="$5" st="$6" miss="" id
  grep -qE 'SpeedChip|SpeedMeter|strip:speed|\.speed\b' "$strip" && bad "S1 the status strip still draws or runs the speed meter" || ok "S1 the strip neither draws nor runs the speed meter"
  for id in $WANT; do jq -e --arg i "$id" '[.home_status_bar.network_table.groups[].rows[].id] | index($i)' "$bj" >/dev/null 2>&1 || miss="$miss $id"; done
  [ -z "$miss" ] && ok "S2 every required row is declared in network_table" || bad "S2 network_table lacks:$miss"
  [ "$(jq -r '.home_status_bar.network_speed.sample_ms' "$bj")" = "1000" ] && ok "S3 the refresh is declared at 1000 ms" || bad "S3 sample_ms is not 1000"
  grep -q 'NetworkTable(ctx, grant)' "$pop" && grep -q 'DisposableEffect(meter) { meter.start(); onDispose { meter.stop() } }' "$pop" && ok "S4 the dropdown starts the meter on open and stops it on dispose" || bad "S4 the dropdown does not bind the meter to its visibility"
  grep -q 'PopTitle("Network")' "$pop" && awk '/PopTitle\("Network"\)/{f=1} f&&/NetworkTable\(ctx, grant\)/{print "y"; exit}' "$pop" | grep -q y && ok "S5 the table sits under the Network title" || bad "S5 NetworkTable is not under the Network title"
  grep -qE 'PASSIVE_MS|postDelayed\(tick, [^s]' "$meter" && bad "S6 the meter keeps a fixed background period" || ok "S6 no fixed background period; the period is the declared sample_ms"
  [ "$(grep -c 'postDelayed' "$meter")" = "2" ] && grep -q 'if (running) main.postDelayed' "$meter" && ok "S7 the tick re-posts only while running" || bad "S7 the tick can re-post after stop"
  grep -q 'main.removeCallbacks(tick)' "$meter" && ok "S8 stop removes the callback" || bad "S8 stop leaves the callback posted"
  [ "$(grep -c 'test\.tap(' "$pop")" = "1" ] && grep -q 'speed?.let { test.tap(it.test) }' "$pop" && ! grep -qE 'postDelayed|Timer|schedule|LaunchedEffect' "$st" && ok "S9 the speed test starts only from its tap" || bad "S9 the speed test can start without a tap"
  [ "$(grep -c 'PublicIp.get(' "$pop")" = "1" ] && grep -q 'produceState' "$pop" && [ "$(jq -r '.home_status_bar.network_speed.public_ip.ttl_ms' "$bj")" = "300000" ] && ok "S10 the public IP is looked up once on open, cached 5 min" || bad "S10 the public-IP lookup is not once-on-open with a 5 min cache"
  grep -nE '[0-9]\.?[0-9]*f?\.sp\b|TextUnit|fontSize *=' "$ui" >/dev/null && bad "S11 the table sets a text size by hand" || { grep -q 'KitStoreCaption' "$ui" && grep -q 'KitStoreRow' "$ui" && ok "S11 the table is KitStoreRow spacing and KitStoreCaption cells, no sp literal" || bad "S11 the table is not drawn from the ui-kit row"; }
  [ "$(jq -r '[.home_status_bar.network_table.groups[] | select(.id=="speed") | .columns[] | .unit] | join(",")' "$bj")" = "MB/s,Mbps,MB/s,Mbps" ] && ok "S12 the bandwidth grid has MB/s and Mbps for now and 24 h" || bad "S12 the bandwidth columns are not MB/s,Mbps,MB/s,Mbps"
  grep -qE 'HttpURLConnection|URL\(' "$meter" && bad "S13 the live meter makes network calls" || ok "S13 the live meter reads counters only"
}
echo "[speed-in-dropdown] the real tree"; check "$APP/$J/launcher/LauncherStatusStrip.kt" "$APP/$J/network/NetworkInfoPopup.kt" "$APP/$J/network/SpeedMeter.kt" "$APP/build.json" "$APP/$J/network/NetTableUi.kt" "$APP/$J/network/SpeedTest.kt"
echo "[speed-in-dropdown] mutations (each must turn it red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <file key: strip|pop|meter|bj|ui|st> <sed expr>
  cp "$APP/$J/launcher/LauncherStatusStrip.kt" "$W/strip"; cp "$APP/$J/network/NetworkInfoPopup.kt" "$W/pop"; cp "$APP/$J/network/SpeedMeter.kt" "$W/meter"; cp "$APP/build.json" "$W/bj"
  cp "$APP/$J/network/NetTableUi.kt" "$W/ui"; cp "$APP/$J/network/SpeedTest.kt" "$W/st"
  sed -i "$3" "$W/$2"; ( check "$W/strip" "$W/pop" "$W/meter" "$W/bj" "$W/ui" "$W/st" ) >"$W/out" 2>&1
  grep -q FAIL "$W/out" && ok "mutant '$1' is red" || bad "mutant '$1' stayed green"
}
mut "chip back on the bar" strip 's/^internal fun StatusStripBody/\/\/ SpeedChip(model.speed)\nprivate val x = com.diegonmarcos.superapp.network.SpeedMeter\ninternal fun StatusStripBody/'
mut "dropdown never stops it" pop 's/onDispose { meter.stop() }/onDispose { }/'
mut "row dropped" bj 's/{"id": "down", "label": "Down"}, //'
mut "background loop" meter 's/if (running) main.postDelayed/main.postDelayed/'
mut "speed test on open" pop 's/DisposableEffect(meter) { meter.start();/DisposableEffect(meter) { test.tap(speed!!.test); meter.start();/'
mut "sp literal in the table" ui 's/^private val INK = Color.White/private val INK = Color.White\nprivate val X = 12.sp/'
mut "unit column lost" bj 's/{"label": "Now", "unit": "Mbps"}, //'
mut "meter downloads" meter 's/^import android.os.Handler/import android.os.Handler\nimport java.net.HttpURLConnection/'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
