#!/usr/bin/env bash
# Tester (#1508): Samsung RAM Plus is a DECLARED, READ-ONLY Perms row (build.json::ui.permissions.device[ram_plus]) with a
# measured state (Settings.Global ram_expand_size, SwapTotal fallback), a data-driven wish (want, default on) and a deep-link
# prompt; the app never writes the setting. Static only.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; J=app/src/main/java/com/diegonmarcos/superapp
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
command -v jq >/dev/null 2>&1 || { echo "ERROR: jq required" >&2; exit 2; }
check() { # check <build.json> <RamPlus.kt> <PermissionsFragment.kt>
  local bj="$1" rp="$2" frag="$3" row
  row="$(jq -c '[.ui.permissions.device[]? | select(.key == "ram_plus")] | first // empty' "$bj")"
  [ -n "$row" ] && ok "R1 the ram_plus row is declared in ui.permissions.device[]" || bad "R1 no ram_plus row declared"
  [ "$(echo "$row" | jq -r '.want // empty')" = "on" ] && ok "R2 the desired state is data (want = on)" || bad "R2 want is not declared as on"
  echo "$row" | jq -e '.remedy_intents | length >= 1 and (.[0] | test("lool"))' >/dev/null 2>&1 && ok "R3 the Device care > Memory deep link is declared" || bad "R3 remedy_intents lacks the Device care rung"
  grep -q 'const val KEY = "ram_expand_size"' "$rp" && grep -q 'SwapTotal' "$rp" && ok "R4 reads ram_expand_size with a SwapTotal fallback" || bad "R4 detection key or fallback missing"
  grep -qE 'Settings\.Global\.putString|Settings\.Global\.putInt|settings put' "$rp" && bad "R5 RamPlus writes a setting" || ok "R5 RamPlus is read-only"
  grep -q '"ram_plus" ->' "$frag" && grep -q 'RamPlus.read(ctx)' "$frag" && ok "R6 the page measures the row live (no remembered flag)" || bad "R6 the page does not measure ram_plus"
  grep -q 'RamPlus.intent(ctx, spec.intents)' "$frag" && ok "R7 the row prompts through the declared deep link" || bad "R7 no deep-link prompt"
  grep -q 'Settings.ACTION_SETTINGS' "$rp" && ok "R8 falls back to Settings when no Samsung page resolves" || bad "R8 no Settings fallback"
}
echo "[ram-plus] the real tree"; check "$APP/build.json" "$APP/$J/system/RamPlus.kt" "$APP/$J/configs/PermissionsFragment.kt"
echo "[ram-plus] mutations (each must turn it red)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
mut() { # mut <name> <file: bj|rp|frag> <sed>
  cp "$APP/build.json" "$W/bj"; cp "$APP/$J/system/RamPlus.kt" "$W/rp"; cp "$APP/$J/configs/PermissionsFragment.kt" "$W/frag"
  sed -i "$3" "$W/$2"; ( check "$W/bj" "$W/rp" "$W/frag" ) >"$W/out" 2>&1
  grep -q FAIL "$W/out" && ok "mutant '$1' is red" || bad "mutant '$1' stayed green"
}
mut "want removed" bj 's/"want": "on",\(.*\)$/\1/;/ram_plus/,/_doc/s/"want": "on",//'
mut "row dropped" bj 's/"key": "ram_plus"/"key": "ram_minus"/'
mut "silent write" rp 's/^object RamPlus {/object RamPlus { fun w(c: Context) = Settings.Global.putInt(c.contentResolver, "x", 1)/'
mut "fallback dropped" rp 's/SwapTotal/Swap/g'
mut "prompt dropped" frag 's/RamPlus.intent(ctx, spec.intents)/null/'
echo; echo "== RESULT: $PASS passed, $FAIL failed =="
[ "$FAIL" -eq 0 ]
