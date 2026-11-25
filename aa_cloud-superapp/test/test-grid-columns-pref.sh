#!/usr/bin/env bash
# Icons per row (Configs > Launcher > Controls): every grid reads GridColumns,
# nothing reads the build-time column count directly any more, the two steppers
# exist, the Phone default is 7 with Cloud unchanged, and the keys are declared
# in fleet-config.json. Behaviour (defaults, migration, clamp, 360 dp fit) is in
# GridColumnsTest.kt.
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"; REPO="$(cd "$APP/.." && pwd)"
KT="$APP/app/src/main/java/com/diegonmarcos/superapp"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
for f in "$APP/build.json" "$KT/settings/GridColumns.kt" "$KT/settings/LauncherConfigFragment.kt" \
         "$REPO/fleet-private-data/fleet-config.json"; do
  [ -f "$f" ] || { echo "ERROR: missing $f" >&2; exit 2; }
done

# T1 defaults: Phone 7, Cloud 6
python3 - "$APP/build.json" <<'P' && ok "T1 ui.phone_grid_columns=7, ui.tile_columns=6" || bad "T1 defaults"
import json,sys
u=json.load(open(sys.argv[1]))["ui"]
sys.exit(0 if (u["phone_grid_columns"],u["tile_columns"])==(7,6) else 1)
P

# T2 only GridColumns reads the baked column counts
hits=$(grep -rln "UI_PHONE_GRID_COLUMNS\|UI_TILE_COLUMNS" --include=*.kt "$KT" | grep -v "settings/GridColumns.kt" || true)
[ -z "$hits" ] && ok "T2 no grid reads BuildConfig columns directly" || bad "T2 direct BuildConfig reads: $hits"

# T3 each grid family uses the pref
# TileGridFragment draws the one small tile at the fixed TileGridFragment.CHIP_COLUMNS (owner: 5 across,
# everything on one A37 screen), so it must NOT read the stepper.
grep -q "val cols = CHIP_COLUMNS" "$KT/launcher/TileGridFragment.kt" && ! grep -q "GridColumns.cloud(" "$KT/launcher/TileGridFragment.kt" \
  && ok "T3 launcher/TileGridFragment.kt draws the fixed CHIP_COLUMNS" || bad "T3 launcher/TileGridFragment.kt"
# The aggregator page reads the pref in its panel bodies (AggregatorPanels.kt) and redraws live from the fragment.
grep -q "GridColumns.cloud(" "$KT/launcher/AggregatorPanels.kt" && grep -q "redrawOnGridColumns(GridColumns.Kind.CLOUD)" "$KT/launcher/AggregatorStackFragment.kt" \
  && ok "T3 launcher/AggregatorStackFragment.kt (+ AggregatorPanels.kt) reads cloud pref and redraws live" || bad "T3 launcher/AggregatorStackFragment.kt"
for f in apps/PhoneAppsFragment.kt apps/SuitePhoneAppsFragment.kt; do
  grep -q "GridColumns.phone(" "$KT/$f" && grep -q "redrawOnGridColumns(GridColumns.Kind.PHONE)" "$KT/$f" \
    && ok "T3 $f reads phone pref and redraws live" || bad "T3 $f"
done

# T4 the stepper rows exist, one per kind, writing only on tap
cfg="$KT/settings/LauncherConfigFragment.kt"
grep -q "GridColumns.set(ctx, GridColumns.Kind.CLOUD" "$cfg" && grep -q "GridColumns.set(ctx, GridColumns.Kind.PHONE" "$cfg" \
  && ok "T4 two separate steppers in Launcher > Controls" || bad "T4 steppers"

# T5 prefs are declared in the fleet manifest
grep -q "grid_cols_cloud" "$REPO/fleet-private-data/fleet-config.json" \
  && grep -q "grid_cols_phone" "$REPO/fleet-private-data/fleet-config.json" \
  && ok "T5 keys declared in fleet-config.json" || bad "T5 fleet-config"

echo "pass=$PASS fail=$FAIL"; [ "$FAIL" -eq 0 ]
