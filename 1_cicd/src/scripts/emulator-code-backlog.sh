#!/usr/bin/env bash
# emulator-code-backlog — #1699 screenshots on a booted emulator of Cloud Code's Backlog page, from the APK a Ship run built.
# Three states, each asserted from the state the page logs to the WebView console (a Cordova page has no accessibility tree, so
# taps are by screen fraction) and screenshotted:
#   live        a clone's root index.html (a public fixture, never the private backlog) -> "read from the clone"
#   offline     the clone removed, the app restarted -> "Offline copy saved" + the reason, the last-good page still shown
#   unavailable app data cleared and no clone    -> the reason ("not on this device"), no crash
# #1855 P3 the walk also screenshots the first row's dropdown and the five bottom pages (Workspace, Editor, Agent, Changes, Run).
# #1855 P4a with a Cloud Drive APK (optional 4th argument) installed and a fixture repository holding a modified, a new and an untracked
# file, the Changes tab lists those files through Drive's repo-workspace v2 (asserted from the page's console line) and is screenshotted,
# as is the diff of the first file.
# #1855 P4b it then runs the agentic loop on that fixture (ticket -> session -> canned reply -> pending edit -> accept -> Changes), screenshots included.
# Usage: emulator-code-backlog.sh APK PACKAGE OUT_DIR [DRIVE_APK]
set -uo pipefail
APK="$1"; PKG="$2"; OUT="$3"; DRIVE_APK="${4:-}"; mkdir -p "$OUT"
T="timeout 120"; rc=0
CLONE=/sdcard/CloudDrive/git/cloud-me-pvt-tasks
$T adb wait-for-device
$T adb install -r -g "$APK" || { echo "FAIL install"; exit 1; }
$T adb shell settings put global window_animation_scale 0
$T adb shell svc power stayon true; $T adb shell input keyevent KEYCODE_WAKEUP; $T adb shell wm dismiss-keyguard
$T adb logcat -c || true
dump() { $T adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1; $T adb pull /sdcard/ui.xml "$OUT/$1.xml" >/dev/null 2>&1; }
shot() { sleep "${2:-4}"; $T adb exec-out screencap -p > "$OUT/backlog-$1.png"; dump "backlog-$1"; echo "shot backlog-$1"; }
tap_node() {
    local p; p="$(python3 - "$1" "$2" <<'PY'
import re, sys
x = open(sys.argv[1], errors="ignore").read(); rx = re.compile(sys.argv[2], re.I)
for m in re.finditer(r'<node ([^>]*)>', x):
    a = m.group(1)
    g = lambda k: (re.search(k + r'="([^"]*)"', a) or [None, ""])[1]
    if any(rx.search(g(k)) for k in ("text", "content-desc")):
        b = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', a)
        if b:
            l, t, r, d = map(int, b.groups())
            if r > l and d > t: print((l + r) // 2, (t + d) // 2); break
PY
)"
    [ -n "$p" ] || return 1
    $T adb shell input tap $p; return 0
}
dismiss() {
    for _ in 1 2 3; do
        dump cur
        if grep -q 'Enable update' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^cancel$'; then sleep 2; continue; fi
        if grep -q 'text="Install unknown apps"' "$OUT/cur.xml" && tap_node "$OUT/cur.xml" '^Skip$'; then sleep 3; continue; fi
        if grep -qiE 'wireless debugging|Allow .* on this network' "$OUT/cur.xml"; then tap_node "$OUT/cur.xml" '^cancel$|^don.t allow$' || $T adb shell input keyevent KEYCODE_BACK; sleep 2; else break; fi
    done
}
grant() { $T adb shell appops set "$PKG" MANAGE_EXTERNAL_STORAGE allow; $T adb shell pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null || true
    # the Workspace's volume scan asks for media access on first use: grant it so the evidence shows the page, not the prompt
    for p in READ_MEDIA_AUDIO READ_MEDIA_IMAGES READ_MEDIA_VIDEO; do $T adb shell pm grant "$PKG" "android.permission.$p" 2>/dev/null || true; done; }
tapf() { # X% Y% of the screen
    local wh w h; wh="$($T adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1)"; w="${wh%x*}"; h="${wh#*x}"
    $T adb shell input tap $((w * $1 / 100)) $((h * $2 / 100))
}
# #1855 P3 Backlog is a Parent Page in the first row's title dropdown, not a bottom tab. The dropdown is web content (no accessibility
# text), so it is reached by position: open it (the title island, top centre), tap one candidate row, and stop once the page has
# logged its state. Rows above Backlog (the open page's own hierarchy) only change page; a tap outside the dropdown closes it.
open_backlog() {
    local y
    for y in 12 15 18 21 24 27 30 33 36 39 42; do
        tapf 50 5; sleep 1
        tapf 50 "$y"; sleep 5
        if $T adb logcat -d | grep -q "cloud-code backlog index"; then echo "backlog reached through the dropdown (row at ${y}%)"; return 0; fi
    done
    echo "WARN: the Backlog page was not reached through the dropdown"; return 1
}
launch() { # FIRST=1 on a fresh app data: Acode asks once about update checks (Cancel); then open the Backlog page through the first row's dropdown
    $T adb shell am force-stop "$PKG"; $T adb logcat -c || true
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 25
    # Acode's update-check CONFIRM is web content (no accessibility text to match) and can come up late
    # (#1855 runs 45011963894, 45013174083): tap its Cancel spot twice, 8 s apart; with no dialog up the spot
    # is empty chat area, so a spare tap does nothing
    dismiss
    if [ "${1:-0}" = 1 ]; then tapf 25 63; sleep 8; tapf 25 63; sleep 3; fi
    open_backlog || true
}
expect() { # NAME REGEX: the console line the page logged for this state
    $T adb logcat -d | grep -E "cloud-code backlog index" > "$OUT/console-$1.txt" || true
    if grep -qiE "$2" "$OUT/console-$1.txt"; then echo "PASS $1: $(tail -1 "$OUT/console-$1.txt" | cut -c1-200)"; else echo "FAIL $1: console has no /$2/"; rc=1; fi
}

# a public fixture standing in for the private repo's root index.html
mkdir -p "$OUT/fx"
cat > "$OUT/fx/index.html" <<'HTML'
<!doctype html><html><head><meta charset="utf-8"><title>Backlog</title></head><body style="font-family:sans-serif">
<h1>Product Backlog (emulator fixture)</h1><p>Views: per project, per status, per effort, agent batches, per agent-model</p>
<table border="1" cellpadding="4"><tr><th>id</th><th>status</th><th>subject</th></tr>
<tr><td>#1</td><td>in_progress</td><td>Fixture ticket one</td></tr><tr><td>#2</td><td>pending</td><td>Fixture ticket two</td></tr></table></body></html>
HTML

grant
$T adb shell mkdir -p "$CLONE"; $T adb push "$OUT/fx/index.html" "$CLONE/index.html" >/dev/null
launch 1; shot live 3
expect live 'index: live'

$T adb shell rm -rf "$CLONE"
launch; shot offline 3
expect offline 'index: offline'

$T adb shell pm clear "$PKG" >/dev/null 2>&1; grant
launch 1; shot unavailable 3
expect unavailable 'index: unavailable.*No earlier copy'

# #1855 P3 the five bottom pages, one screenshot each (cells of the 5-item bar, centres at 15 / 32 / 50 / 68 / 85 % of the width)
# the dropdown is closed by tapping its island again (a tap on the page behind could press one of its buttons), then the app is
# restarted so the bottom pages are shot from the default page
tapf 50 5; shot first-row-dropdown 2; tapf 50 5; sleep 1
$T adb shell am force-stop "$PKG"; $T adb logcat -c || true
$T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
sleep 25; dismiss; sleep 10
for pair in "workspace 15" "editor 32" "agent 50" "changes 68" "run 85"; do
    # the first tap after a restart can be taken by the chat composer's focus: tap the cell twice (a tab tapped again stays selected)
    set -- $pair; tapf "$2" 92; sleep 3; tapf "$2" 92; sleep 3; tapf "$2" 92; shot "tab-$1" 4
done

# #1855 P4a Changes with real files: Drive installed, a git repository with three changes on the store volume
if [ -n "$DRIVE_APK" ] && [ -f "$DRIVE_APK" ]; then
    $T adb install -r -g "$DRIVE_APK" || { echo "FAIL install drive"; rc=1; }
    DPKG="$(${AAPT:-aapt2} dump packagename "$DRIVE_APK" 2>/dev/null || echo com.diegonmarcos.clouddrive)"
    $T adb shell appops set "$DPKG" MANAGE_EXTERNAL_STORAGE allow
    FX="$OUT/fxrepo/changes-fixture"; rm -rf "$OUT/fxrepo"; mkdir -p "$FX"
    ( cd "$FX" && git init -q -b main && git config user.email fx@example.invalid && git config user.name fx \
        && printf 'alpha\nbeta\ngamma\n' > notes.txt && printf 'keep\n' > gone.txt && git add -A && git commit -q -m base \
        && printf 'alpha\nBETA changed\ngamma\ndelta\n' > notes.txt && git rm -q gone.txt && printf 'new file\n' > added.txt && git add added.txt \
        && printf 'scratch\n' > untracked.txt )
    $T adb shell rm -rf /sdcard/CloudDrive/git/changes-fixture
    $T adb push "$FX" /sdcard/CloudDrive/git/changes-fixture >/dev/null
    $T adb shell am force-stop "$PKG"; $T adb logcat -c || true
    $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 25; dismiss; sleep 10
    tapf 68 92; sleep 3; tapf 68 92; sleep 12
    $T adb logcat -d | grep -E "cloud-code changes files" > "$OUT/console-changes.txt" || true
    if grep -qE "changes-fixture drive=true files=[1-9]" "$OUT/console-changes.txt"; then echo "PASS changes: $(grep changes-fixture "$OUT/console-changes.txt" | tail -1 | cut -c1-200)"; else echo "FAIL changes: Drive did not list the fixture's changed files"; rc=1; fi
    shot changes-files 2
    # the first file of the first changed repository: a tap on the list's first file row opens its diff (position, the page is web content)
    for y in 38 42 46 50 54 58 62 66 70; do tapf 50 "$y"; sleep 3; $T adb logcat -d | grep -q "cloud-code changes diff" && break; done
    $T adb logcat -d | grep -E "cloud-code changes diff" > "$OUT/console-diff.txt" || true
    if grep -qE "changes-fixture/.* lines=[1-9]" "$OUT/console-diff.txt"; then echo "PASS diff: $(tail -1 "$OUT/console-diff.txt" | cut -c1-200)"; else echo "FAIL diff: no diff view opened from a file row"; rc=1; fi
    shot changes-diff 2

    # #1855 P4b the agentic loop on the same fixture repository: a ticket in a fixture clone of the backlog, then an agent session started
    # from it (the app's debug hook reads agent-test-flow.json from its own external data dir, which only adb can write: the model reply is
    # CANNED, no network), a PENDING edit with its diff, Accept (a second flow resumes the session), the file written into the working
    # tree, and the Changes tab listing it. The same code paths run as for a tap; nothing is committed.
    TASKS="$CLONE/1___BACKLOG___/1.1.Product-Backlog/tasks/fixture-session"
    mkdir -p "$OUT/fxtask"
    printf '{"id":"1999","subject":"Fixture ticket for the agent loop","description":"Add agent-note.txt and note the change in notes.txt.","status":"pending"}\n' > "$OUT/fxtask/1999.json"
    $T adb shell mkdir -p "$CLONE/1___BACKLOG___/1.1.Product-Backlog/tasks"; $T adb shell mkdir -p "$TASKS"; $T adb push "$OUT/fxtask/1999.json" "$TASKS/1999.json" >/dev/null
    EXT="/sdcard/Android/data/$PKG/files"
    python3 - "$OUT" <<'PY'
import json, sys
out = sys.argv[1]
reply = ("<plan>\n1. Create agent-note.txt.\n2. Append a line to notes.txt.\n</plan>\n<edits>\n"
         + json.dumps([{"path": "agent-note.txt", "newContent": "written by the agent session\n"},
                       {"path": "notes.txt", "newContent": "alpha\nBETA changed\ngamma\ndelta\nagent was here\n"}])
         + "\n</edits>")
json.dump({"repo": "changes-fixture", "ticket_id": "1999", "reply": reply, "accept": False}, open(out + "/flow-propose.json", "w"))
json.dump({"resume": True, "accept": True}, open(out + "/flow-accept.json", "w"))
PY
    agent_flow() { # NAME FLOWFILE: restart the app with the flow dropped in its data dir, open the Agent tab, wait for the hook
        $T adb shell mkdir -p "$EXT"; $T adb push "$OUT/$2" "$EXT/agent-test-flow.json" >/dev/null || echo "WARN: could not place the flow in $EXT"
        $T adb shell am force-stop "$PKG"; $T adb logcat -c || true
        $T adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 25; dismiss; sleep 8
        tapf 50 92; sleep 3; tapf 50 92; sleep 14
        $T adb logcat -d | grep -E "cloud-code agent" > "$OUT/console-agent-$1.txt" || true
    }
    agent_flow propose flow-propose.json
    if grep -q "agent session: repo=changes-fixture ticket=#1999" "$OUT/console-agent-propose.txt" && grep -q "agent proposed: agent-note.txt" "$OUT/console-agent-propose.txt" && grep -q "agent proposed: notes.txt" "$OUT/console-agent-propose.txt"; then echo "PASS agent: session from ticket #1999, two edits proposed"; else echo "FAIL agent: no session from the fixture ticket / no proposed edits"; rc=1; fi
    if $T adb shell test -e /sdcard/CloudDrive/git/changes-fixture/agent-note.txt; then echo "FAIL agent: a file was written before Accept"; rc=1; else echo "PASS agent: nothing written while the edits are pending"; fi
    shot agent-pending 2
    agent_flow accept flow-accept.json
    if grep -q "agent written: agent-note.txt" "$OUT/console-agent-accept.txt" && grep -q "agent written: notes.txt" "$OUT/console-agent-accept.txt"; then echo "PASS agent: accepted edits written"; else echo "FAIL agent: accepted edits were not written"; rc=1; fi
    $T adb shell cat /sdcard/CloudDrive/git/changes-fixture/agent-note.txt > "$OUT/agent-note.txt" 2>/dev/null || true
    if grep -q "written by the agent session" "$OUT/agent-note.txt"; then echo "PASS agent: agent-note.txt is on the device with the proposed content"; else echo "FAIL agent: agent-note.txt missing or wrong"; rc=1; fi
    shot agent-written 2
    tapf 68 92; sleep 3; tapf 68 92; sleep 12
    $T adb logcat -d | grep -E "cloud-code changes files" > "$OUT/console-changes-agent.txt" || true
    if grep -qE "changes-fixture drive=true files=[0-9]+ names=.*agent-note.txt" "$OUT/console-changes-agent.txt"; then echo "PASS agent: Changes lists agent-note.txt"; else echo "FAIL agent: Changes does not list agent-note.txt"; rc=1; fi
    shot changes-agent 2
fi

$T adb logcat -d -t 30000 > "$OUT/logcat.txt"; $T adb logcat -d -b crash > "$OUT/logcat-crash.txt" 2>&1 || true
cat "$OUT/logcat.txt" "$OUT/logcat-crash.txt" | grep -E "FATAL EXCEPTION" -A12 | grep -B2 -A12 "$PKG" | head -40 > "$OUT/crash.txt" || true
[ -s "$OUT/crash.txt" ] && { echo "FAIL crash in logcat"; rc=1; }
exit $rc
