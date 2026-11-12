#!/usr/bin/env bash
# #1685 the home model's Auto-rotate switch: one declaration, default on, one
# switch for the 3D AND the 4D model, a live Configs control, the prefs key
# declared in fleet-config.json (class device), and no render loop when off.
# Behaviour (resume-from-orientation, drag on/off, stop when idle) is proven in
# app/src/test/.../themes/cloud/ModelSpinTest.kt.
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$APP/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
HOME3D="$KT/launcher/themes/cloud/Home3DFragment.kt"
SPIN="$KT/launcher/themes/cloud/ModelSpin.kt"
POLY="$KT/launcher/themes/cloud/PolytopeMath.kt"
PREFS="$KT/settings/LauncherSettingsPrefs.kt"
CFG="$KT/settings/LauncherConfigFragment.kt"
FC="$REPO/ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json"
UT="$APP/app/src/test/java/com/diegonmarcos/superapp/launcher/themes/cloud/ModelSpinTest.kt"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$HOME3D" "$SPIN" "$POLY" "$PREFS" "$CFG" "$FC" "$UT"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

# T1 declared ONCE in build.json, default on
python3 - "$APP/build.json" <<'P' && ok "T1 build.json background.auto_rotate declared, default true" || bad "T1 declaration"
import json,sys
b=json.load(open(sys.argv[1]))["ui"]["launcher_settings"]["background"]
a=b.get("auto_rotate",{})
sys.exit(0 if a.get("key")=="background_auto_rotate" and a.get("default") is True and a.get("label")=="Auto-rotate" else 1)
P
n=$(grep -c '"auto_rotate"' "$APP/build.json")
[ "$n" = "1" ] && ok "T1 exactly one auto_rotate declaration" || bad "T1 $n declarations"

# T2 the key is declared in the fleet manifest, class device
python3 - "$FC" <<'P' && ok "T2 fleet-config launcher_settings.keys.background_auto_rotate = device" || bad "T2 fleet-config"
import json,sys
s=json.load(open(sys.argv[1]))["stores"]["launcher_settings"]
sys.exit(0 if s.get("keys",{}).get("background_auto_rotate")=="device" else 1)
P

# T3 prefs read the declaration (no hardcoded default in the renderer)
grep -q 'Config.background.autoRotateKey' "$PREFS" && grep -q 'optJSONObject("auto_rotate")' "$PREFS" \
  && grep -q 'fun observeAutoRotate' "$PREFS" && ok "T3 prefs expose the declared key + a live observer" || bad "T3 prefs"

# T4 one switch drives BOTH 3D and 4D: the renderer passes every angle from ModelSpin
grep -q 'a4Override = spin.a4' "$HOME3D" && grep -q 'b4Override = spin.b4' "$HOME3D" \
  && grep -q 'rotXOverride = spin.rotX' "$HOME3D" && grep -q 'a4Override ?: ' "$POLY" \
  && ok "T4 3D axes and 4D planes both come from the one spin state" || bad "T4 renderer"
grep -q 'autoRotate && animate' "$HOME3D" && grep -q 'observeAutoRotate' "$HOME3D" \
  && ok "T4 Auto-rotate (AND the battery motion gate) drives motion, followed live" || bad "T4 gate"

# T5 battery: one loop, gated on visibility, which breaks when still
c="$(grep -cE 'withFrameNanos[[:space:]]*\{' "$HOME3D")"
[ "$c" = "1" ] && ok "T5 exactly one frame loop" || bad "T5 $c loops"
grep -q 'rememberDrawing()' "$HOME3D" && grep -q 'while (isActive && drawing)' "$HOME3D" \
  && grep -q 'if (!stillMoving && !cycling) break' "$HOME3D" \
  && ok "T5 frames only while Home is visible; loop stops when off and idle" || bad "T5 loop gating"

# T6 Rule 16: every gesture stays (drag-to-spin gate, coast) and the Configs row exists
grep -q 'detectDragGestures' "$HOME3D" && grep -q 'if (interactive) Modifier.pointerInput' "$HOME3D" \
  && grep -q '0.95f' "$SPIN" && ok "T6 drag-to-spin and coast kept" || bad "T6 gestures"
grep -q 'settingsPrefs.backgroundAutoRotate = on' "$CFG" && ok "T6 Configs > Background has the Auto-rotate switch" || bad "T6 Configs row"

# T7 the behaviour tests exist
for t in autoRotateTurnsBoth3dAnd4d offStopsBothAndStopsTheLoop dragWorksWithAutoRotateOn dragWorksWithAutoRotateOffAndThenStays; do
  grep -q "fun $t" "$UT" && ok "T7 unit test $t" || bad "T7 missing $t"
done

echo "pass=$PASS fail=$FAIL"; [ "$FAIL" -eq 0 ]
