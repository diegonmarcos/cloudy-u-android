#!/usr/bin/env bash
# cloud-keyboard translate bar: static proof that the reliability chain is
# intact WITHOUT a gradle build (this runner cannot build). Each check is a
# regression that made the bar "flaky" once:
#
#   T1  engine reply contract {source, text[, why]} on BOTH engines + the
#       orchestrator reads slot 2 (errors used to collapse to {"und",""})
#   T2  AIDL client recovers a dead binding and retries a failed bind on use
#   T3  every ML Kit await inside translateBlocking is bounded
#   T4  long-press one-shot: keyboard-language hint + stale-field guard + supersede
#   T5  bar: debounce, engine-side generation, LRU cache, not-connected shown on open
#   T6  settings: every TranslatePrefs key is rendered by the settings screen,
#       registered in the container, and every R.string it references exists
#   T7  AIDL: translateFrom stays LAST (transaction codes follow declaration
#       order — an older companion must still answer translate()), both copies equal
#   T8  ownership rule: no "HeliBoard" wording in the translate-owned files
#   T9  output re-sync engine: the bar owns ONE span of the app's field, owns it
#       as a composing region, gives it up when the host drops it, and never
#       writes after that — the three symptoms of "re-pastes the same text"
#   T10 the shared text box: no bar clips its own text, and no bar lets an
#       editing key fall through onto the field hidden behind it
#   T11 ONE editor, ONE interception point: both bars' boxes are the same
#       TextBoxEditor stepping whole graphemes, and every caret operation —
#       including the GESTURES, which used to move the host app's caret behind
#       the bar — resolves through LatinIME.activeTextBox()
set -uo pipefail
APP="$(cd "$(dirname "$0")/.." && pwd)"
ROOT="$APP/.."
LIBS="$ROOT/ab_cloud-libs-shared"
T="$LIBS/libs/translate/src/main/java/com/diegonmarcos/superapp/translate"
M="$LIBS/libs/ml-l-text-mlkit/src/main/java/com/diegonmarcos/superapp/translate"
K="$LIBS/libs/keyboard/src/main"
J="$K/java/helium314/keyboard"
AIDL_CLIENT="$ROOT/ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/AidlTranslateEngineClient.kt"
SERVICE="$LIBS/keyboard-engines/app/src/main/java/com/diegonmarcos/cloudkeyboardlibs/TranslateEngineService.java"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
has() { grep -q -- "$2" "$1" && ok "$3" || bad "$3 ($1)"; }
lacks() { grep -q -- "$2" "$1" && bad "$3 ($1)" || ok "$3"; }

echo "== cloud-keyboard translate: reliability chain, settings, AIDL contract =="

# T1 reply contract
has "$SERVICE" 'private static String\[\] failed(Throwable t)' "T1 service maps exceptions to a 3-slot reply"
has "$SERVICE" 'return new String\[\]{"und", "", ' "T1 service reply carries the reason in slot 2"
lacks "$SERVICE" 'return new String\[\]{"und", ""};' "T1 service never squashes an error to {und,''}"
has "$M/LocalTranslateEngineClient.kt" 'private fun failed(e: Exception) = arrayOf("und", "", ' "T1 in-process client carries the reason"
has "$T/Translator.kt" 'fun Array<String>.reason(): String? = getOrNull(2)' "T1 Translator reads slot 2"
has "$T/Translator.kt" 'why != null -> Result(null, null, why)' "T1 Translator surfaces the engine reason first"
has "$M/TranslateEngine.java" 'timed out after " + timeoutS + " s' "T1 ML Kit timeouts name the stage + wait"
has "$M/TranslateEngine.java" 'Unsupported source language' "T1 explicit unsupported source is an error, not 'und'"

# T2 bind recovery
has "$AIDL_CLIENT" 'override fun onBindingDied' "T2 onBindingDied handled"
has "$AIDL_CLIENT" 'unbindService(this)' "T2 dead binding is unbound before rebinding"
has "$AIDL_CLIENT" 'override fun onNullBinding' "T2 null binder logged"
has "$AIDL_CLIENT" 'private fun engineOrRebind()' "T2 use-time rebind exists"
has "$AIDL_CLIENT" 'const val REBIND_MS' "T2 rebind is rate-limited"
has "$AIDL_CLIENT" 'Translator.notConnected(' "T2 not-connected reason comes from the one shared helper"
for fn in 'override fun translate(' 'override fun translateFrom(' 'override fun supportedLanguages()' 'override fun isConnected()'; do
  awk -v f="$fn" 'index($0,f){p=1} p&&/engineOrRebind\(\)/{found=1} p&&/^    }|^    override fun|^    private companion/{if(index($0,f)==0){exit}} END{exit !found}' "$AIDL_CLIENT" \
    && ok "T2 $fn goes through engineOrRebind()" || bad "T2 $fn bypasses engineOrRebind()"
done

# T3 bounded awaits: inside translateBlocking every Tasks.await has a TimeUnit; only prefetchDefaults may be unbounded
n_await=$(awk '/translateBlocking\(Context context, String text, String sourceTag/,/^    }$/' "$M/TranslateEngine.java" | grep -c 'Tasks.await(')
n_bound=$(awk '/translateBlocking\(Context context, String text, String sourceTag/,/^    }$/' "$M/TranslateEngine.java" | grep -c 'TimeUnit.SECONDS)')
[ "$n_await" = "$n_bound" ] && [ "$n_await" -ge 3 ] && ok "T3 $n_await/$n_await awaits in translateBlocking are bounded" || bad "T3 awaits=$n_await bounded=$n_bound"
for c in ID_TIMEOUT_S DOWNLOAD_TIMEOUT_S TRANSLATE_TIMEOUT_S; do has "$M/TranslateEngine.java" "static final long $c" "T3 $c declared"; done

# T4 one-shot
has "$T/Translator.kt" 'val hint = keyboardLang.takeIf { it.isNotEmpty() && it != target }' "T4 one-shot passes the keyboard language as detection hint"
has "$T/Translator.kt" 'if (now != text)' "T4 stale-field guard compares the field against what was sent"
python3 - "$T/Translator.kt" <<'PY' && ok "T4 the guard returns BEFORE replaceInField, and says why" \
                                      || bad "T4 stale-field guard no longer precedes replaceInField ($T/Translator.kt)"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
i = code.find('if (now != text)')
j = code.find('replaceInField(ic, hadSelection, out)')
# The guard must exist, sit before the write, and take an early exit that puts
# a reason on screen — a bare `if` that fell through would leave the stale
# replacement happening anyway.
between = code[i:j] if 0 <= i < j else ''
sys.exit(0 if between and 'return@post ended(' in between else 1)
PY
has "$T/Translator.kt" 'val gen = oneShot.incrementAndGet()' "T4 newer long-press supersedes"

# T5 bar
has "$T/TranslateBarView.kt" 'private const val DEBOUNCE_MS' "T5 debounce constant"
has "$T/TranslateBarView.kt" 'ui.postDelayed(job, DEBOUNCE_MS)' "T5 debounce applied"
has "$T/Translator.kt" 'if (gen != generation.get()) return@execute' "T5 superseded live request skips the engine"
has "$T/Translator.kt" 'override fun removeEldestEntry' "T5 LRU cache"
has "$T/TranslateBarView.kt" '!client.isConnected() -> Translator.notConnected(' "T5 not-connected shown on open"
has "$T/TranslateBarView.kt" 'fun swap()' "T5 swap direction"
has "$T/TranslateBarView.kt" 'TranslatePrefs.recentPairs(context)' "T5 recent pairs in the picker"
# label resource -> the action it must invoke. The pair is the assertion:
# a chip whose label drifted onto the wrong handler is the regression that
# matters, and the old literal-only grep could not have seen it.
while IFS='|' read -r res action; do
  python3 - "$T/TranslateBarView.kt" "$res" "$action" <<'PY' \
    && ok "T5 action chip $res -> $action" \
    || bad "T5 chip $res is missing or not wired to $action ($T/TranslateBarView.kt)"
import re, sys
code = open(sys.argv[1], encoding='utf-8').read()
m = re.search(r'Chip\(ctx\.getString\(R\.string\.%s\)[^{]*\{([^}]*)\}'
              % re.escape(sys.argv[2]), code)
sys.exit(0 if m and sys.argv[3] in m.group(1) else 1)
PY
done <<'CHIPS'
translate_bar_insert|TranslatePrefs.APPLY_INSERT
translate_bar_replace|TranslatePrefs.APPLY_REPLACE
translate_bar_copy|copy()
translate_bar_clear|clear()
CHIPS

# T6 settings surface
for key in $(grep -o 'const val KEY_[A-Z_]*' "$T/TranslatePrefs.kt" | awk '{print $3}' | grep -v KEY_RECENT_PAIRS); do
  has "$J/settings/screens/TranslationInfoScreen.kt" "TranslatePrefs.$key" "T6 settings screen renders $key"
done
has "$J/settings/SettingsContainer.kt" 'createTranslateSettings(context)' "T6 container registers translate settings"
grep -o 'R\.string\.[a-z_0-9]*' "$J/settings/screens/TranslationInfoScreen.kt" | sort -u | sed 's/R.string.//' | while read -r s; do
  grep -q "name=\"$s\"" "$K/res/values/strings.xml" || echo "$s"
done > /tmp/kb-tr-missing.$$
[ -s /tmp/kb-tr-missing.$$ ] && bad "T6 missing strings: $(tr '\n' ' ' < /tmp/kb-tr-missing.$$)" || ok "T6 every referenced R.string exists"
rm -f /tmp/kb-tr-missing.$$
has "$J/latin/LatinIME.java" 'mTranslateBar.onShown()' "T6 LatinIME calls onShown on every open"
has "$J/latin/LatinIME.java" 'mTranslateBar.bind(this::getCurrentInputConnection, kbLang' "T6 LatinIME re-binds with the subtype language"

# T7 AIDL: translateFrom last, copies identical
mapfile -t AIDLS < <(find "$ROOT/ac_cloud-keyboard" "$LIBS/keyboard-engines" -name ITranslateEngine.aidl -not -path '*/build/*' | sort)
[ "${#AIDLS[@]}" -ge 2 ] && ok "T7 ${#AIDLS[@]} AIDL copies found" || bad "T7 expected client+service AIDL copies, found ${#AIDLS[@]}"
for a in "${AIDLS[@]}"; do
  [ "$(grep -E '^\s*(String\[\]|List<String>) ' "$a" | tail -1 | grep -c translateFrom)" = 1 ] && ok "T7 translateFrom is last in ${a#$ROOT/}" || bad "T7 translateFrom not last in $a"
done
[ "${#AIDLS[@]}" -ge 2 ] && { cmp -s "${AIDLS[0]}" "${AIDLS[1]}" && ok "T7 AIDL copies identical" || bad "T7 AIDL copies differ"; }

# T8 ownership wording
for f in "$T"/*.kt "$M"/*.kt "$M"/*.java "$AIDL_CLIENT" "$SERVICE"; do
  lacks "$f" 'HeliBoard\|sync-heliboard' "T8 no HeliBoard wording in ${f##*/}"
done

# T9 output re-sync engine (TranslateBarView)
has "$T/TranslateBarView.kt" 'private enum class Output {' "T9 output ownership is a declared state, not a loose string"
for st in NONE OWNED LOST; do has "$T/TranslateBarView.kt" "^        $st," "T9 Output.$st declared"; done
has "$T/TranslateBarView.kt" 'ic.setComposingText(out, 1)' "T9 live output is a composing region, not a bare commit"
lacks "$T/TranslateBarView.kt" 'deleteSurroundingText' "T9 no retract-by-length — the call that duplicated the text"
lacks "$T/TranslateBarView.kt" 'lastOutput' "T9 the character-count guess is gone entirely"
has "$T/TranslateBarView.kt" 'if (output == Output.LOST) return' "T9 a lost span is never written to again"
has "$T/TranslateBarView.kt" 'fun onHostOutputDropped()' "T9 the host-takeover signal has a handler"
has "$T/TranslateBarView.kt" 'fun onHidden()' "T9 the region is handed over when the bar closes"
# Termination: the handler must not write to the field or to the buffer, or it
# would cause the very update it reacts to. Checked over its body, not the file.
# An empty body must FAIL, not pass by finding nothing to complain about — a
# check whose window has drifted off the code is worse than no check.
resync=$(awk '/fun onHostOutputDropped\(\)/,/^    }/' "$T/TranslateBarView.kt")
if [ -z "$resync" ]; then
  bad "T9 onHostOutputDropped body not found (anchor drifted) — loop proof unchecked"
else
  grep -qE 'ic\.|icp|buffer\.|onChanged\(' <<<"$resync" \
    && bad "T9 the drop handler writes to the field or the buffer — it can re-enter" \
    || ok "T9 the drop handler only reads state and shows views, so it cannot loop"
  grep -q 'output = Output.LOST' <<<"$resync" \
    && ok "T9 the drop handler's only transition is OWNED -> LOST" \
    || bad "T9 drop handler does not reach LOST"
fi
has "$J/latin/LatinIME.java" 'mTranslateBar.onHostOutputDropped();' "T9 LatinIME reports the dropped composing span"
has "$J/latin/LatinIME.java" 'isTranslateBarActive() && composingSpanEnd < 0' "T9 and only when the span is actually gone"
has "$J/latin/LatinIME.java" 'mTranslateBar.onHidden();' "T9 LatinIME hands the region over before hiding the bar"
grep -q 'commitTyped' <<<"$(awk '/void toggleTranslateBar/,/^    }/' "$J/latin/LatinIME.java")" \
  && ok "T9 a half-typed word is committed before the bar starts composing" \
  || bad "T9 opening the bar would replace the user's word in progress"
# The design's own proof, run: python3 translate-resync-model.py
if python3 "$(dirname "$0")/translate-resync-model.py" >/tmp/kb-tr-model.$$ 2>&1; then
  ok "T9 re-sync state machine: $(grep -c '  ok:' /tmp/kb-tr-model.$$) model assertions pass"
else
  bad "T9 re-sync state machine model: $(grep '^FAILED' /tmp/kb-tr-model.$$)"
fi
rm -f /tmp/kb-tr-model.$$

# T10 the shared text box, used by BOTH bars
has "$T/TranslateInputView.kt" 'class CappedScrollView' "T10 the capped scroller lives with the box it belongs to"
lacks "$J/latin/EnhanceBarView.kt" 'private class CappedScrollView' "T10 the enhance bar no longer keeps its own copy"
has "$J/latin/EnhanceBarView.kt" 'import com.diegonmarcos.superapp.translate.CappedScrollView' "T10 it uses the shared one"
has "$T/TranslateBarView.kt" 'CappedScrollView(context, inputView.lineHeight \* INPUT_LINES' "T10 the translate input scrolls instead of clipping"
lacks "$T/TranslateBarView.kt" 'maxLines = 3; setPadding(0, dp(6), 0, 0)' "T10 the input box is no longer capped by maxLines"
has "$T/TranslateInputView.kt" 'fun revealCaret()' "T10 the caret is kept inside the scrolled window"
# Falling through applies the key to the field BEHIND the bar — for CUT, destructively.
# Checked over onEdit's BODY, and an empty body fails: an anchor that has drifted
# off the code is worse than no check, because it reports green.
for f in "$T/TranslateBarView.kt" "$J/latin/EnhanceBarView.kt"; do
  body=$(awk '/fun onEdit\(/,/^    }/' "$f")
  if [ -z "$body" ]; then
    bad "T10 ${f##*/} onEdit body not found (anchor drifted) — fall-through unchecked"
  else
    grep -qE 'return false|isEmpty\)? (&&|return)' <<<"$body" \
      && bad "T10 ${f##*/} onEdit can fall through onto the field behind the bar" \
      || ok "T10 ${f##*/} onEdit never lets an editing key reach the hidden field"
  fi
done

# ── T11 one editor, one interception point ──────────────────────────────────
E="$T/TextBoxEditor.kt"
[ -f "$E" ] && ok "T11 the shared caret/selection buffer is a file of its own" \
            || bad "T11 $E missing — the extraction did not happen"
has "$E" 'class TextBoxEditor' "T11 the one buffer is a class"
has "$E" 'interface ImeTextBox' "T11 and the one question LatinIME asks is an interface"

# The unit. A caret that steps code units splits an emoji; a caret that steps
# codepoints splits a flag, a skin tone and a variation selector. Neither is a
# character. The platform segments graphemes and the keyboard's own pipeline
# already uses that segmenter, so the box uses it too rather than scanning.
has "$E" 'import android.icu.text.BreakIterator' "T11 the box asks the platform where characters begin"
has "$E" 'fun boundaryAt' "T11 offsets from outside are snapped, in one place"
has "$E" 'fun deleteBackward' "T11 backspace removes a whole grapheme"

# The second copy is GONE, not merely discouraged. Each of these greps found a
# real line in both bars before this change.
for f in "$T/TranslateBarView.kt" "$J/latin/EnhanceBarView.kt"; do
  n=${f##*/}
  lacks "$f" 'private val buffer = StringBuilder()' "T11 $n keeps no private buffer"
  lacks "$f" 'isLowSurrogate' "T11 $n does not re-implement surrogate arithmetic"
  lacks "$f" 'private fun wordStart' "T11 $n does not re-implement word scanning"
  has "$f" 'private val editor = TextBoxEditor()' "T11 $n uses the shared editor"
  has "$f" 'ImeTextBox' "T11 $n is reachable through the one interface"
  has "$f" 'override fun moveCaret(steps: Int, select: Boolean)' "T11 $n takes caret GESTURES, not only keys"
done

# The touch gestures live with the box too, so a gesture cannot be added to one
# bar and forgotten in the other — which is exactly how the double tap came to be
# missing from both.
has "$T/TranslateInputView.kt" 'fun attachEditing' "T11 the touch gestures live with the box, once"
has "$T/TranslateInputView.kt" 'ViewConfiguration.getDoubleTapTimeout()' "T11 double tap selects the word"
has "$T/TranslateInputView.kt" 'editor.select(anchor, offsetAt(e.x, e.y))' "T11 drag extends the selection"
has "$T/TranslateInputView.kt" 'ViewConfiguration.getLongPressTimeout()' "T11 long press selects the word and opens the menu"
for f in "$T/TranslateBarView.kt" "$J/latin/EnhanceBarView.kt"; do
  n=${f##*/}
  lacks "$f" 'setOnTouchListener' "T11 $n keeps no touch handler of its own"
  has "$f" '.attachEditing(editor,' "T11 $n uses the shared gestures"
done

# The defect left open by the previous agent, in the one copy that is left.
grep -q 'selectWhitespaceRun' <<<"$(awk '/fun selectWordAt/,/^    }/' "$E")" \
  && ok "T11 a long press on a space no longer selects the words on both sides" \
  || bad "T11 selectWordAt still runs both scans outwards from the space"

# Undo of a programmatic replacement: the owner gets their own text back.
# (#1818: a Generate lands in the box through outputBox.set -> editor.replaceAll, in the shared panel, no longer in a private generate())
has "$E" 'fun undo(): Boolean' "T11 the shared editor can undo a whole-buffer replacement"
has "$E" 'fun replaceAll' "T11 and every programmatic replacement goes through it"
grep -q 'editor.replaceAll' <<<"$(awk '/private val outputBox/,/^    }$/' "$J/latin/EnhanceBarView.kt")" \
  && ok "T11 a generated rewrite landing in the enhance box is undoable" \
  || bad "T11 the enhance bar still overwrites the box with no way back"
has "$T/TranslateBarView.kt" 'if (editor.canUndo())' "T11 translate offers Undo only when there is text to restore"
has "$J/latin/EnhanceBarView.kt" 'if (editor.canUndo())' "T11 and so does enhance — the same editor, the same menu entry"

# THE GESTURES. Everything after the guard in these two talks to the HOST app's
# connection, so the guard has to come FIRST. Body-scoped, and an empty body is
# a failure: this is exactly the check that would otherwise drift off and report
# green while the space bar moved a caret nobody could see.
for fn in onMoveCursorHorizontally onMoveDeletePointer; do
  body=$(awk "/fun $fn\\(/,/^    }/" "$J/keyboard/KeyboardActionListenerImpl.kt")
  if [ -z "$body" ]; then
    bad "T11 $fn body not found (anchor drifted) — gesture interception unchecked"
  else
    guard=$(echo "$body" | grep -n 'latinIME.onCaretSlide' | head -1 | cut -d: -f1)
    host=$(echo "$body" | grep -n 'connection\.' | head -1 | cut -d: -f1)
    if [ -z "$guard" ]; then
      bad "T11 $fn never asks who owns editing — it edits the app behind the bar"
    elif [ -n "$host" ] && [ "$guard" -gt "$host" ]; then
      bad "T11 $fn touches the host's connection before asking (line $host before $guard)"
    else
      ok "T11 $fn offers the gesture to the keyboard's own box first"
    fi
  fi
done
has "$J/keyboard/KeyboardActionListenerImpl.kt" 'latinIME.onCaretSlide(0, true)' \
  "T11 the end of a delete swipe deletes whoever's selection it actually made"

# ONE accessor, and one key mapping. Two of either is how the enhance box came
# to answer a different set of keys from the translate box.
has "$J/latin/LatinIME.java" 'private com.diegonmarcos.superapp.translate.ImeTextBox activeTextBox()' \
  "T11 LatinIME answers 'who owns editing' in exactly one place"
has "$J/latin/LatinIME.java" 'public boolean onCaretSlide(final int steps, final boolean select)' \
  "T11 and gestures go through that same answer"
onevent=$(awk '/public void onEvent\(@NonNull final Event event\)/,/^    }/' "$J/latin/LatinIME.java")
if [ -z "$onevent" ]; then
  bad "T11 onEvent body not found (anchor drifted) — key routing unchecked"
else
  grep -q 'mTranslateBar\.\|mEnhanceBar\.' <<<"$onevent" \
    && bad "T11 onEvent still names the bars one by one instead of asking activeTextBox()" \
    || ok "T11 onEvent routes keys through the accessor, not through a list of bars"
fi
lacks "$J/latin/EnhanceBarView.kt" 'KeyCode.CLIPBOARD_' "T11 there is one key-to-edit mapping, not one per bar"

echo "== $PASS ok, $FAIL failed =="
[ "$FAIL" -eq 0 ]
