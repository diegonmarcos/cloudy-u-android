#!/usr/bin/env bash
# CI screenshots of the Cloud Keyboard on an emulator (ship-cloud-keyboard.yml, job keyboard_screenshots), with the checks that
# make them evidence rather than pictures: each state is also read back from the keyboard (KeyboardDebugReceiver, a debuggable
# build only) and compared with what the ticket asks for.
#
#   usage: keyboard-screenshots.sh <Cloud-Keyboard.apk> <out-dir>
#
#   00-start               the test screen, keyboard up
#   01..03 toolbar-page-N  the first row, page by page (the 24 keys: 11 / 11 / 2; Draft Answer on page 1, Emoji on page 2)
#   04..06 grammar-*       the Grammar mode icon after each tap: Highlight, Off, Auto Correct again
#   07..10 autocomplete-*  the Autocomplete panel: IDs, Addresses, URLs, Secrets (masked)
#   11 emoji-panel         the emoji page with its search field
#   12 emoji-search-open   after a tap on the search field: the letter pad is back, the results box is above it
#   13 emoji-search-heart  the same, typed "heart"
#   14..15 configs-*       Configs, with the Android keyboard settings entry
#   16 ai-routing-refreshed  Configs ▸ AI Routing after the OpenRouter model list refreshed (updated stamp)
#   17..19 ai-table-*      the Translation Only, Grammar Only and Text Enhancement model tables
#   20 translation-routing the Translation routing switch (ML libs vs OpenRouter, active engine, fallback)
#   21 grammar-routing     the Grammar Check routing switch
#   22 enhance-model       Text Enhancement: the model in use, read-only, with the link to AI Routing
#   23..25 highlight-*     Grammar Check Highlight: the popup on a tapped mark, after Ignore, and a re-check after it
#   26..28 answer-*        Draft Answer: the panel on the newest clipboard item, the clipboard source picker, a draft
#   29..30 configs-answer  Configs with Draft Answer after Text Enhancement, and the Draft Answer page
#
# Made-up rows only. Nothing here is a credential.
set -uo pipefail
APK="${1:?apk}"; OUT="${2:?out dir}"
PKG=com.diegonmarcos.cloudkeyboard
IME="$PKG/helium314.keyboard.latin.LatinIME"
mkdir -p "$OUT"
FAILS=0
ok()   { echo "ok     $1"; }
bad()  { echo "FAIL   $1"; FAILS=$((FAILS + 1)); }
shot() { sleep 1; adb exec-out screencap -p > "$OUT/$1.png"; echo "shot   $1 ($(wc -c < "$OUT/$1.png") bytes)"; }
kb()   { adb shell "am broadcast -a com.diegonmarcos.cloudkeyboard.debug.CMD -p $PKG --es cmd '$1' --es arg '${2:-}'" >/dev/null; sleep 1.5; }
last() { adb logcat -d -s KbdShot:I | grep -- "$1 ->" | tail -1; }
state() { kb state; last state; }

adb logcat -c
# The fleet UI-mode override must not leak into these shots.
adb shell settings delete global cloud_nav_ui_mode >/dev/null 2>&1
adb install -r -g "$APK" >/dev/null && ok "installed" || { bad "install"; exit 1; }
# The app restarts itself when ACTION_BOOT_COMPLETED reaches it (SystemBroadcastReceiver: "Killing my process"), and on a slow
# emulator that broadcast is still being delivered when the script starts: it killed the first run's screen. Let the boot
# dispatch finish, then start the screen, and start it again if the process is gone.
for _ in $(seq 1 60); do [ "$(adb shell getprop sys.boot_completed | tr -d '\r')" = 1 ] && break; sleep 2; done
sleep 45
# make Cloud Keyboard THE input method (the image ships Gboard); the package manager may need a moment to know the service
cur=""
for i in $(seq 1 15); do
    adb shell ime enable "$IME" 2>&1 | tail -1
    adb shell ime set "$IME" 2>&1 | tail -1
    cur="$(adb shell settings get secure default_input_method | tr -d '\r')"
    [ "$cur" = "$IME" ] && break
    sleep 4
done
echo "default input method: $cur"; adb shell ime list -s
[ "$cur" = "$IME" ] && ok "Cloud Keyboard is the input method" || bad "Cloud Keyboard is not the input method ($cur)"
adb shell settings put secure show_ime_with_hard_keyboard 1
shown() { adb shell dumpsys input_method | grep -q 'mInputShown=true'; }
for attempt in 1 2 3; do
    adb shell am start -n "$PKG/.ShotActivity" >/dev/null
    for _ in $(seq 1 25); do shown && break; sleep 1; done
    sleep 8
    if [ -n "$(adb shell pidof "$PKG" | tr -d '\r')" ] && shown && adb shell dumpsys input_method | grep -q "mCurMethodId=$IME"; then break; fi
    echo "attempt $attempt: the screen or the keyboard is gone, starting again"
done
shown && ok "the keyboard is up" || bad "the keyboard never came up"
adb shell dumpsys input_method | grep -E "mCurMethodId|mInputShown|mCurId" | head -5
sleep 2
shot 00-start

# ── the toolbar, page by page ─────────────────────────────────────────────
kb geometry
G="$(last geometry)"; echo "$G"
read -r SL ST SW SH < <(sed -n 's/.*strip=\([0-9]*\),\([0-9]*\),\([0-9]*\),\([0-9]*\).*/\1 \2 \3 \4/p' <<<"$G")
if [ -z "${SW:-}" ]; then bad "the toolbar strip was not found"; SL=0; ST=1500; SW=1080; SH=240; fi
Y=$((ST + SH / 4)); X1=$((SL + SW * 8 / 10)); X2=$((SL + SW * 2 / 10))
shot 01-toolbar-page-1
adb shell input swipe "$X1" "$Y" "$X2" "$Y" 250; sleep 1; shot 02-toolbar-page-2
adb shell input swipe "$X1" "$Y" "$X2" "$Y" 250; sleep 1; shot 03-toolbar-page-3
adb shell input swipe "$X2" "$Y" "$X1" "$Y" 250; adb shell input swipe "$X2" "$Y" "$X1" "$Y" 250; sleep 1

# ── Grammar mode: Auto -> Highlight -> Off -> Auto ────────────────────────
state | grep -q "grammar=auto_correct" && ok "grammar starts on Auto Correct" || bad "grammar does not start on Auto Correct: $(last state)"
kb code GRAMMAR_MODE; state | grep -q "grammar=highlight" && ok "tap 1: Highlight" || bad "tap 1 is not Highlight: $(last state)"; shot 04-grammar-highlight
kb code GRAMMAR_MODE; state | grep -q "grammar=off" && ok "tap 2: Off" || bad "tap 2 is not Off: $(last state)"; shot 05-grammar-off
kb code GRAMMAR_MODE; state | grep -q "grammar=auto_correct" && ok "tap 3: Auto Correct again" || bad "tap 3 is not Auto Correct: $(last state)"; shot 06-grammar-auto

# ── Autocomplete: its own panel, its own pages ────────────────────────────
SEED='{"ids":[{"label":"National ID","value":"X0000000T"},{"label":"Passport","value":"P0000000"}],"addresses":[{"label":"Home","value":"1 Example Street, Sampletown"},{"label":"Work","value":"2 Sample Avenue, Exampleville"}],"urls":[{"label":"Site","value":"https://example.org"},{"label":"Blog","value":"https://blog.example.org"}],"secrets":[{"label":"Wi-Fi","value":"fake-passphrase"},{"label":"Router","value":"fake-router-pin"}]}'
kb seed "$SEED"; last seed | grep -q "seeded 8" && ok "the local store took 8 made-up rows" || bad "seed: $(last seed)"
kb code AUTOCOMPLETE; sleep 2
state | grep -q "mode=PANEL(AUTOCOMPLETE)" && ok "the Autocomplete icon opened its own panel" || bad "not in the Autocomplete panel: $(last state)"
last state | grep -q "rows=8" && ok "the panel holds the 8 rows" || bad "the panel rows: $(last state)"
n=7
for p in ids addresses urls secrets; do
    kb page "$p"; state | grep -q "autocompletePage=$p" && ok "page $p" || bad "page $p: $(last state)"
    shot "$(printf '%02d' $n)-autocomplete-$p"; n=$((n + 1))
done
kb code ALPHA; state | grep -q "mode=TYPING" && ok "ABC returns to typing" || bad "still in a panel: $(last state)"

# ── Emoji page search ─────────────────────────────────────────────────────
kb code EMOJI; sleep 1; state | grep -q "mode=PANEL(EMOJI)" && ok "emoji panel" || bad "not in the emoji panel: $(last state)"
shot 11-emoji-panel
kb search; sleep 1
state | grep -q "mode=TYPING" && ok "a tap on the search field gave the letter pad back" || bad "the letter pad did not come back: $(last state)"
last state | grep -q "emojiBarActive=true" && ok "the results box is open above it" || bad "the results box is not open: $(last state)"
shot 12-emoji-search-open
kb text heart; sleep 2
shot 13-emoji-search-heart
H="$(last state | sed -n 's/.*emojiBarHeightPx=\([0-9]*\).*/\1/p')"; kb state; H="$(last state | sed -n 's/.*emojiBarHeightPx=\([0-9]*\).*/\1/p')"
D="$(last state | sed -n 's/.*density=\([0-9.]*\).*/\1/p')"
WANT="$(awk -v d="${D:-1}" 'BEGIN { printf "%d", 95 * d + 0.5 }')"
if [ -n "$H" ] && [ "$H" -ge $((WANT - 3)) ] && [ "$H" -le $((WANT + 3)) ]; then ok "the results box is ${H}px = 95dp (half of the old 190dp) at density $D"; else bad "the results box is ${H:-?}px, wanted $WANT (95dp at density $D)"; fi
kb code ALPHA

# ── Configs ───────────────────────────────────────────────────────────────
adb shell am start -n "$PKG/helium314.keyboard.settings.SettingsActivity" >/dev/null; sleep 3
shot 14-configs-top
adb shell input swipe 540 1800 540 600 400; sleep 1
shot 15-configs-scrolled

# ── Configs ▸ AI Routing: refresh, the three task tables, the routing switches ─
# Made-up choices, real catalog ids: one model per task, so each table shows a selected (pinned, filled) row.
adb shell am start -n "$PKG/.ShotActivity" >/dev/null; sleep 4
kb aimodel translate=google/gemini-2.5-flash; kb aimodel grammar=openai/gpt-4o-mini; kb aimodel enhance=anthropic/claude-sonnet-4.5
kb airoute translate=ml; last airoute
kb airoute grammar=openrouter; last airoute
# No force-stop (-S): killing the package drops Cloud Keyboard as the input method. A clear-task start re-creates the
# activity, so the open_at extra is read again; an empty name opens Configs' own list.
CLEAR=0x10008000
openat() {
    if [ -n "$1" ]; then adb shell am start -f $CLEAR -n "$PKG/helium314.keyboard.settings.SettingsActivity" --es open_at "$1" >/dev/null
    else adb shell am start -f $CLEAR -n "$PKG/helium314.keyboard.settings.SettingsActivity" >/dev/null; fi
    sleep "${2:-4}"
}
read -r SW SH < <(adb shell wm size | sed -n 's/.*: *\([0-9]*\)x\([0-9]*\).*/\1 \2/p' | tail -1)
SW=${SW:-1080}; SH=${SH:-1920}
up() { adb shell input swipe $((SW / 2)) $((SH * 85 / 100)) $((SW / 2)) $((SH * 30 / 100)) 500; sleep 2; }
openat ai_routing 4
for _ in $(seq 1 30); do adb logcat -d -s AiCatalog:I AiCatalog:W | grep -qE "refreshed|offline|failed" && break; sleep 1; done
R="$(adb logcat -d -s AiCatalog:I AiCatalog:W | grep -E "refreshed|offline|failed" | tail -1)"; echo "$R"
grep -q "refreshed [1-9][0-9]* models" <<<"$R" && ok "AI Routing refreshed the OpenRouter model list" || bad "the model list did not refresh: $R"
sleep 2
shot 16-ai-routing-refreshed
up; shot 17-ai-table-translate
up; shot 18-ai-table-grammar
up; shot 19-ai-table-enhance
openat translation 4; shot 20-translation-routing
openat grammar_check 4; shot 21-grammar-routing
openat text_enhance 4; shot 22-enhance-model

# ── Grammar Check Highlight: tap a mark → popup; Ignore → the mark goes and does not come back ─
adb shell ime set "$IME" >/dev/null 2>&1
adb shell am start -f $CLEAR -n "$PKG/.ShotActivity" --ez edittext true >/dev/null
for _ in $(seq 1 25); do shown && break; sleep 1; done; sleep 4
kb grammar highlight
kb text "hello i "; sleep 2
kb marks; M="$(last marks)"; echo "$M"
grep -q "marks=1 " <<<"$M" && ok "Highlight marked the lowercase i" || bad "no highlight mark: $M"
kb select 6; sleep 2
kb marks; M="$(last marks)"; echo "$M"
grep -q "popup=true" <<<"$M" && ok "a tap into the mark opened the popup in the keyboard window" || bad "the popup did not open: $M"
shot 23-highlight-popup
kb popup ignore; last popup
kb marks; M="$(last marks)"; echo "$M"
grep -q "marks=0 ignored=1 popup=false" <<<"$M" && ok "Ignore removed the mark and remembered it" || bad "Ignore: $M"
grep -q "field=\[[Hh]ello i \]" <<<"$M" && ok "Ignore left the text as it was" || bad "Ignore changed the text: $M"
shot 24-highlight-after-ignore
kb select end; kb text "ok i "; sleep 2   # "i i" would be a repeated word, a different (real) mark
kb marks; M="$(last marks)"; echo "$M"
grep -q "marks=0 ignored=1" <<<"$M" && ok "re-check: the ignored i is not marked again" || bad "the ignored mark came back: $M"
shot 25-highlight-recheck

# ── Draft Answer: the Enhance panel in reply mode, input = the newest clipboard item ─
# Made-up messages. CI holds no AI key, so the draft in shot 28 is put in the draft box by the debug receiver
# (answer-draft); the real Generate is the same box filled by AiRouter.complete on the Text Enhancement model.
kb clip "Can you send me the quarterly report by tomorrow?"; sleep 1
kb clip "Hi! Are you free for lunch on Friday at 1pm? Ana"; sleep 1
kb code ANSWER_BAR; sleep 2
kb answer; A="$(last answer)"; echo "$A"
grep -q "answer=true input=\[Hi! Are you free for lunch on Friday at 1pm? Ana\] custom=false" <<<"$A" \
  && ok "Draft Answer opened on the newest clipboard item" || bad "Draft Answer input: $A"
shot 26-answer-panel-clipboard
kb answer-pick; sleep 1
kb answer; A="$(last answer)"; echo "$A"
grep -q "picker=[1-9]" <<<"$A" && ok "the clipboard source picker lists the history" || bad "source picker: $A"
shot 27-answer-source-picker
kb answer-draft "Hi Ana, Friday at 1pm works for me. See you then!"; sleep 1
kb answer; A="$(last answer)"; echo "$A"
grep -q "draft=\[Hi Ana, Friday at 1pm works for me. See you then!\]" <<<"$A" && ok "the draft is in the panel" || bad "draft: $A"
shot 28-answer-draft
kb code ANSWER_BAR
openat "" 4; shot 29-configs-answer-entry
openat draft_answer 4; shot 30-configs-draft-answer

if adb logcat -d | grep -q "FATAL EXCEPTION.*"; then bad "a crash is in the log"; adb logcat -d | grep -A12 "FATAL EXCEPTION" | head -40; else ok "no crash in the log"; fi
adb logcat -d > "$OUT/logcat.txt" 2>/dev/null
adb logcat -d -s KbdShot:I > "$OUT/kbdshot.txt" 2>/dev/null
echo; echo "== $FAILS failed =="
[ "$FAILS" = 0 ]
