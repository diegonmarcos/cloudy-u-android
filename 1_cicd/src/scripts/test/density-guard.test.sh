#!/usr/bin/env bash
# density-guard.test — prove cloud-android-density-guard.py FAILS on each defect and PASSES the clean fleet.
set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-density-guard.py"
POLICY="$ROOT/1_cicd/src/data/density-guard.json"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"
trap 'rm -rf "${WORK:?}"' EXIT

OWNER="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["owner"])' "$POLICY")"
APP="ac_cloud-store/app/src/main/java/demo"

fixture() {  # $1 = dir
  mkdir -p "$1/$(dirname "$OWNER")" "$1/$APP"
  cat > "$1/$OWNER" <<'KT'
fun kitTypography() = Typography()
private fun TextStyle.dense() = copy(lineHeight = TextUnit.Unspecified)
fun KitDenseMaterial() { LocalMinimumInteractiveComponentSize provides KitDensity.interactiveFloor }
KT
  cat > "$1/$APP/Main.kt" <<'KT'
fun Shell() {
    KitDenseMaterial(darkColorScheme()) { Scaffold(contentWindowInsets = WindowInsets(0)) { } }
    MaterialTheme(colorScheme = c, typography = kitTypography()) { }
}
KT
}
expect() {  # name want(0|1) dir
  python3 "$GUARD" "$3" --policy "$POLICY" >"$WORK/out" 2>&1; local rc=$?
  [ "$rc" = "$2" ] && ok "$1" || { fail "$1 (exit $rc, wanted $2)"; cat "$WORK/out"; }
}
mutate() {  # name file-to-append text
  local d="$WORK/$1"; fixture "$d"; printf '%s\n' "$3" >> "$d/$2"; expect "$1" 1 "$d"
}

fixture "$WORK/clean"; expect "clean fleet passes" 0 "$WORK/clean"
mutate bare-material-theme "$APP/Main.kt" 'fun X() { MaterialTheme(colorScheme = darkColorScheme()) { } }'
mutate scaffold-without-insets "$APP/Main.kt" 'fun Y() { Scaffold(topBar = { }) { } }'
mutate stock-typography "$APP/Main.kt" 'val T = Typography()'
mutate nested-system-bars "$APP/Main.kt" 'fun Z() { Box(Modifier.systemBarsPadding()) }'
mutate nested-safe-drawing-insets "$APP/Main.kt" 'fun Z2() { Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) }'
d="$WORK/dense-typography"; fixture "$d"; echo 'val T = Typography().dense()' >> "$d/$APP/Main.kt"; expect "dense Typography passes" 0 "$d"
# a ComposeView / setContent root in the SuperApp without the dense theme is a defect; under it, it is fine
d="$WORK/root-bare"; fixture "$d"; mkdir -p "$d/aa_cloud-superapp/app/src/main/java"
echo 'fun h(c: Context) = ComposeView(c).apply { setContent { Text("x") } }' > "$d/aa_cloud-superapp/app/src/main/java/H.kt"; expect "a bare SuperApp ComposeView root fails" 1 "$d"
for ok_marker in 'CloudKitTheme(p) { }' 'KitDenseMaterial(s) { }' 'c.kitComposeView(p) { }'; do
  d="$WORK/root-dense"; fixture "$d"; mkdir -p "$d/aa_cloud-superapp/app/src/main/java"
  printf 'fun h(c: Context) = ComposeView(c).apply { setContent { %s } }\n' "$ok_marker" > "$d/aa_cloud-superapp/app/src/main/java/H.kt"; expect "a SuperApp ComposeView root under $ok_marker passes" 0 "$d"
done
# the apps with their own theme are scanned
for app in ac_cloud-tasks ac_cloud-calc ac_cloud-tools-small-llms ac_cloud-writer ac_cloud-gallery ac_cloud-drive ac_cloud-search; do
  d="$WORK/root-$app"; fixture "$d"; mkdir -p "$d/$app/app/src/main/java"
  echo 'fun X() { MaterialTheme(colorScheme = c) { } }' > "$d/$app/app/src/main/java/T.kt"; expect "$app is scanned" 1 "$d"
done
# #1532 the Writer takes its sizes from the type ramp: a literal sp fails there, an em factor and another app's sp do not
d="$WORK/writer-sp"; fixture "$d"; mkdir -p "$d/ac_cloud-writer/app/src/main/java"
echo 'val s = TextStyle(fontSize = 14.sp)' > "$d/ac_cloud-writer/app/src/main/java/T.kt"; expect "a literal sp in the Writer fails" 1 "$d"
d="$WORK/writer-sp-decimal"; fixture "$d"; mkdir -p "$d/ac_cloud-writer/app/src/main/java"
echo 'val s = TextStyle(fontSize = 13.5.sp)' > "$d/ac_cloud-writer/app/src/main/java/T.kt"; expect "a decimal sp in the Writer fails" 1 "$d"
d="$WORK/writer-em"; fixture "$d"; mkdir -p "$d/ac_cloud-writer/app/src/main/java"
echo 'val s = TextStyle(lineHeight = 1.6f.em, fontSize = 1.15.em)' > "$d/ac_cloud-writer/app/src/main/java/T.kt"; expect "an em factor in the Writer passes" 0 "$d"
d="$WORK/other-sp"; fixture "$d"; mkdir -p "$d/ac_cloud-calc/app/src/main/java"
echo 'val s = TextStyle(fontSize = 14.sp)' > "$d/ac_cloud-calc/app/src/main/java/T.kt"; expect "an sp literal outside the Writer is not this rule's business" 0 "$d"
# a bare Material button on a Configs page fails; the kit button passes; outside the scope it is not this rule's business
CFG="aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/configs"
d="$WORK/cfg-button"; fixture "$d"; mkdir -p "$d/$CFG"; echo 'fun B() { Button(onClick = {}) { Text("x") } }' > "$d/$CFG/B.kt"; expect "a bare Material Button on a Configs page fails" 1 "$d"
d="$WORK/cfg-textbutton"; fixture "$d"; mkdir -p "$d/$CFG"; echo 'fun B() { TextButton(onClick = {}) { Text("x") } }' > "$d/$CFG/B.kt"; expect "a bare TextButton on a Configs page fails" 1 "$d"
d="$WORK/cfg-kitbutton"; fixture "$d"; mkdir -p "$d/$CFG"; echo 'fun B() { KitButton(onClick = {}) { Text("x") } }' > "$d/$CFG/B.kt"; expect "KitButton on a Configs page passes" 0 "$d"
d="$WORK/owner-floor"; fixture "$d"; sed -i 's/KitDensity.interactiveFloor/36.dp/' "$d/$OWNER"; expect "the owner losing the interactive-floor switch fails" 1 "$d"
# a comment naming the defect is not a defect
d="$WORK/comment"; fixture "$d"; echo '// MaterialTheme(colorScheme = x) and Scaffold( are banned' >> "$d/$APP/Main.kt"; expect "comments are ignored" 0 "$d"
# a test may name them
d="$WORK/intest"; fixture "$d"; mkdir -p "$d/ac_cloud-store/app/src/test/java"; echo 'fun t() { MaterialTheme(colorScheme = c) }' > "$d/ac_cloud-store/app/src/test/java/T.kt"; expect "tests are skipped" 0 "$d"
# owner decays
for needle in "fun kitTypography" "fun KitDenseMaterial" "LocalMinimumInteractiveComponentSize" "lineHeight = TextUnit.Unspecified"; do
  d="$WORK/owner-${needle//[^a-zA-Z]/_}"; fixture "$d"; python3 - "$d/$OWNER" "$needle" <<'PY'
import sys
p,n=sys.argv[1:]; s=open(p).read().replace(n,"REMOVED"); open(p,"w").write(s)
PY
  expect "owner lost '$needle'" 1 "$d"
done
d="$WORK/noowner"; fixture "$d"; rm "$d/$OWNER"; expect "owner file missing" 1 "$d"
mkdir -p "$WORK/empty"; expect "nothing scanned is refused" 2 "$WORK/empty"
python3 "$GUARD" "$ROOT" >/dev/null 2>&1 && ok "real tree passes" || { fail "real tree"; python3 "$GUARD" "$ROOT"; }
[ "$FAILURES" = 0 ] && echo "density-guard.test: all ok" || { echo "$FAILURES failed"; exit 1; }
