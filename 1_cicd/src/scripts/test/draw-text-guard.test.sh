#!/usr/bin/env bash
# #1921 the draw-text guard must be RED for a raw measurer drawText and GREEN on the helper / labelOrigin / the native canvas.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"
GUARD="$HERE/../cloud-android-draw-text-guard.py"
fails=0
chk() {
  local r; r="$(mktemp -d)"; mkdir -p "$r/app/src/main"
  printf '%s\n' "$2" > "$r/app/src/main/X.kt"
  python3 "$GUARD" "$r" > "$r/out" 2>&1; got=$?
  if [ "$got" -eq "$3" ]; then echo "  ok   $1"; else echo "  FAIL $1 (exit $got, wanted $3)"; cat "$r/out"; fails=$((fails+1)); fi
  rm -rf "$r"
}
chk "raw measurer drawText is red"   'drawText(measurer, s, Offset(x + 3f, 2f), style)' 1
chk "textMeasurer arg is red"        'drawText(textMeasurer, s, topLeft = at)' 1
chk "safeDrawText is green"          'safeDrawText(measurer, s, Offset(x, 2f), style)' 0
chk "labelOrigin above is green"     $'val at = GraphCamera.labelOrigin(p, r, w, h)\nif (at != null) drawText(measurer, s, at, style)' 0
chk "native canvas is green"         'c.nativeCanvas.drawText(s, x, y, paint)' 0
chk "layout-result overload green"   'drawText(layout, topLeft = Offset(x, y))' 0
[ "$fails" -eq 0 ] && echo "draw-text-guard: all cases pass" || exit 1
