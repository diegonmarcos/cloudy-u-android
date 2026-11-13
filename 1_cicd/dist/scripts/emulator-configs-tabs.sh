# ─── GENERATED: do not edit — edit 1_cicd/src/scripts/emulator-configs-tabs.sh ───
#!/usr/bin/env bash
# emulator-configs-tabs — #1443 screenshots on a booted emulator of one app's Configs screen: open Configs from the app's
# own entry (the first row's three dots; Cloud C3's top bar menu), then each tail tab (#1630: {own tabs} | Agents, Perms,
# Logcat, X-Ray App, About; the strip scrolls), taken from the APK a Ship run built. Exit 1 when a tail tab cannot be
# reached or the strip is not {own tabs} then the five tail tabs in that order.
# Usage: emulator-configs-tabs.sh APK PACKAGE NAME OUT_DIR
set -uo pipefail
APK="$1"; PKG="$2"; NAME="$3"; OUT="$4"; mkdir -p "$OUT"
T="timeout 120"; rc=0
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true

dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/$NAME-$1.png"; dump "$NAME-$1"; echo "shot $NAME-$1"; }
# tap_node XML REGEX — tap the centre of the first node whose text, content-desc or resource-id matches REGEX
tap_node() {
    local p; p="$(python3 - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read(); rx = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    g = lambda k: (re.search(k + r'="([^"]*)"', a) or [None, ""])[1]
    if any(rx.search(g(k)) for k in ("text", "content-desc", "resource-id")):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        if b:
            l, t, r, d = map(int, b.groups())
            if r > l and d > t: print((l + r) // 2, (t + d) // 2); break
PY
)"
    [ -n "$p" ] || return 1
    $T adb shell input tap $p; return 0
}

# a system dialog over the app (Cloud Account's wireless-debugging probe): Cancel it; a start gate: continue
dismiss() {
    for _ in 1 2 3; do
        dump cur
        # Cloud Drive's start gate (no terminal installed): continue with local files
        if grep -q 'Continue with local files' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^Continue with local files$'; sleep 6; continue; fi
        # Cloud Gallery's first-run welcome (Get Started): enter the app
        if grep -q 'text="Get Started"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Get Started$'; then sleep 5; continue; fi
        # Cloud Code's (Acode) update-check confirm: Cancel
        if grep -q 'Enable update' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^cancel$'; then sleep 2; continue; fi
        # the fleet's own install-source ask (Skip), or Android's "not allowed to install unknown apps" (Cancel)
        if grep -q 'text="Install unknown apps"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Skip$'; then sleep 3; continue; fi
        if grep -qiE 'allowed to install unknown apps' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; continue; fi
        if grep -qiE 'wireless debugging|Allow .* on this network' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$|^don.t allow$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; else break; fi
    done
}
# a first-run walk (Cloud Gallery: welcome, then "Step N of M" pages with Continue / Next / Finish): tap through to the app
onboard() {
    for _ in $(seq 1 14); do
        dump cur
        if grep -q 'package="com.android.permissioncontroller"\|package="com.google.android.permissioncontroller"' "$OUT/cur.xml"; then
            tap_node "$OUT/cur.xml" 'permission_allow_button|^allow$|while using' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; continue
        fi
        if grep -qE 'text="Step [0-9]+ of [0-9]+"|text="Get Started"' "$OUT/cur.xml"; then
            tap_node "$OUT/cur.xml" '^(Get Started|Continue|Next|Finish|Done|Start|Skip)$' || break; sleep 3
        else break; fi
    done
}
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 20
dismiss
onboard
dismiss
# first start can show a start-up permission walk (a Settings page of another package): bring the app back on top
dump cur
if ! grep -q "package=\"$PKG\"" "$OUT/cur.xml"; then
    $T adb shell input keyevent KEYCODE_BACK; sleep 2
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1; sleep 10
    dismiss
fi
shot start 2

dump cur
if tap_node "$OUT/cur.xml" 'configs'; then :; elif tap_node "$OUT/cur.xml" 'more options|settings'; then sleep 2; dump cur; tap_node "$OUT/cur.xml" '^configs$' || true; fi
dismiss
dump cur
if ! grep -qE 'text="(Agents|Perms|Logcat|X-Ray App|About)"' "$OUT/cur.xml"; then
    if tap_node "$OUT/cur.xml" 'configs'; then :; elif tap_node "$OUT/cur.xml" 'more options|settings'; then sleep 2; dump cur; tap_node "$OUT/cur.xml" '^configs$' || true; fi
    dismiss
fi
shot configs-open 6
# #1630: every strip is {own tabs} | Agents, Perms, Logcat, X-Ray App, About. The strip scrolls, so the tail can sit
# off screen: walk it right to left with swipes on the strip row, collecting the labels in on-screen order.
strip_labels() { # XML -> "y label x" per tab of the Configs strip: the first row of tab nodes under the top bar
    python3 - "$1" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read()
tabs = []
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    d = (re.search(r'content-desc="([^"]*)"', a) or [None, ""])[1]
    b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
    if d and b:
        l, t, r, dn = map(int, b.groups())
        if r > l and dn > t: tabs.append((t, l, d, (t + dn) // 2))
tops = sorted({t for t, *_ in tabs})
if len(tops) < 2: sys.exit(0)
bar = tops[0]  # the top bar's icons (AI, Account, Configs)
row = next((t for t in tops if t > bar + 20), None)
if row is None: sys.exit(0)
for t, l, d, cy in sorted(tabs, key=lambda n: n[1]):
    if t == row and len(d) <= 24: print(cy, d, l, sep="\t")
PY
}
order=()
merge() { # append labels not seen yet, in on-screen order
    local y lab x seen o
    while IFS=$'\t' read -r y lab x; do
        [ -n "$lab" ] || continue; seen=0
        for o in "${order[@]}"; do [ "$o" = "$lab" ] && seen=1; done
        [ $seen = 1 ] || order+=("$lab"); ROW_Y="$y"
    done < <(strip_labels "$1")
}
ROW_Y=""
merge "$OUT/$NAME-configs-open.xml"
W="$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)"; W="${W:-1080}"
for i in 1 2 3 4 5 6; do
    [ -n "$ROW_Y" ] || break
    $T adb shell input swipe $((W * 8 / 10)) "$ROW_Y" $((W * 2 / 10)) "$ROW_Y" 400; sleep 1; dump "strip-$i"; merge "$OUT/strip-$i.xml"
done
echo "strip order: ${order[*]}"
# tap every tail tab: from the strip's right end, swipe back left until the label is on screen
for tab in Agents Perms Logcat "X-Ray App" About; do
    dismiss; found=0
    for i in 0 1 2 3 4 5 6; do
        dump cur
        hit="$(strip_labels "$OUT/cur.xml" | awk -F'\t' -v t="$tab" '$2==t {print $3+20, $1; exit}')"
        if [ -n "$hit" ]; then $T adb shell input tap $hit; found=1; break; fi
        [ -n "$ROW_Y" ] && $T adb shell input swipe $((W * 2 / 10)) "$ROW_Y" $((W * 8 / 10)) "$ROW_Y" 400; sleep 1
    done
    if [ $found = 1 ]; then shot "tab-${tab// /-}" 5; else echo "FAIL $NAME: no $tab tab on the Configs strip"; rc=1; fi
    # back to the strip's right end for the next one
    [ -n "$ROW_Y" ] && for i in 1 2 3; do $T adb shell input swipe $((W * 8 / 10)) "$ROW_Y" $((W * 2 / 10)) "$ROW_Y" 300; done
done
# the strip must end with the five tail tabs in that order, after at least one of the app's own tabs
python3 - "${order[@]}" <<'PY' || rc=1
import sys
o = sys.argv[1:]; tail = ["Agents", "Perms", "Logcat", "X-Ray App", "About"]
pos = [o.index(t) if t in o else None for t in tail]
print("tail positions in the strip:", dict(zip(tail, pos)))
ok = None not in pos and pos == sorted(pos) and any(t != "|" for t in o[:pos[0]]) and pos[-1] == len(o) - 1
sys.exit(0 if ok else 1)
PY
[ $? -eq 0 ] || { echo "FAIL $NAME: the Configs strip is not {own tabs} | Agents, Perms, Logcat, X-Ray App, About"; rc=1; }
$T adb logcat -d -t 30000 > "$OUT/$NAME-logcat.txt"; $T adb logcat -d -b crash > "$OUT/$NAME-logcat-crash.txt" 2>&1 || true
cat "$OUT/$NAME-logcat.txt" "$OUT/$NAME-logcat-crash.txt" | grep -E "FATAL EXCEPTION" -A12 | grep -B2 -A12 "$PKG" | head -40 > "$OUT/$NAME-crash.txt" || true
[ -s "$OUT/$NAME-crash.txt" ] && { echo "FAIL $NAME: crash in logcat"; rc=1; }
exit $rc
