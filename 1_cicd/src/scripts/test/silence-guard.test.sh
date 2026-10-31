#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ silence-guard.test — prove the guard fails, not just that it     ║
# ║ runs                                                             ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. A guard that has only ever been watched succeeding is
# indistinguishable from a guard that returns 0 unconditionally. This
# repository shipped an assertion last week that compared an expression to
# itself and printed green, and another whose search window ran past the code
# it meant to check — so one case below deliberately renames the entry point
# and demands the guard notice it is now reading nothing.
#
# Every case BREAKS the repository in one specific way, demands the guard
# notice that exact way, and throws the copy away. The break happens in a
# throwaway copy of the tree, never in the working tree.
#
# No ripgrep anywhere: it was established that the CI runner does not have it,
# and testers here have passed on its absence rather than on their assertions.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="1_cicd/src/scripts/cloud-android-silence-guard.py"
MANIFEST="1_cicd/src/data/silence-guard.json"
SOURCE="ab_cloud-libs-shared/libs/keyboard/src/main/java/helium314/keyboard/latin/TextEnhancer.kt"
BAR="ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/TranslateBarView.kt"
TRANSLATOR="ab_cloud-libs-shared/libs/translate/src/main/java/com/diegonmarcos/superapp/translate/Translator.kt"
ES="ab_cloud-libs-shared/libs/keyboard/src/main/res/values-es/strings.xml"
EN="ab_cloud-libs-shared/libs/keyboard/src/main/res/values/strings.xml"
FAILURES=0

ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# One pristine copy, made once, taken from the WORKING TREE rather than the git
# index: the guard has to be provable against the edit somebody is about to
# commit, not only against what is already committed.
PRISTINE="$WORK/pristine"
# WHICH FILES THE COPY HOLDS IS DERIVED, NOT LISTED. It used to be six cp lines,
# so an entry point added to the manifest was tested against a sandbox that did
# not contain its source. The guard then said "does not exist — nothing to
# guard", every mutation aimed at it failed for that reason instead of its own,
# and the new entry point was never once exercised. The manifest is the list.
MANIFEST_FILES="$(python3 - "$ROOT/$MANIFEST" <<'MANIFEST_PY'
import json, sys
for entry in json.load(open(sys.argv[1], encoding="utf-8"))["entry_points"]:
    print(entry["source"])
    for relative in entry.get("messages", {}).get("locales", {}).values():
        print(relative)
MANIFEST_PY
)"
if [ -z "$MANIFEST_FILES" ]; then
    echo "FAIL   $MANIFEST names no files — nothing to copy and nothing to prove."
    exit 1
fi
for relative in $MANIFEST_FILES "$GUARD" "$MANIFEST"; do
    if [ ! -f "$ROOT/$relative" ]; then
        echo "FAIL   $MANIFEST names $relative but it does not exist."
        exit 1
    fi
    mkdir -p "$PRISTINE/$(dirname "$relative")"
    cp "$ROOT/$relative" "$PRISTINE/$relative"
done

run_guard() { ( cd "$1" && CLOUD_ANDROID_ROOT="$1" python3 "$GUARD" 2>&1 ); }

sandbox() { local dir="$WORK/case$RANDOM$RANDOM"; cp -a "$PRISTINE" "$dir"; printf '%s' "$dir"; }

# expect_caught <label> <regex the failure line must match> <mutator...>
# The regex is the point: a guard that fails for some OTHER reason is not
# evidence that it caught THIS one.
expect_caught() {
    local label="$1" want="$2"; shift 2
    local dir out status
    dir="$(sandbox)"
    "$@" "$dir"
    out="$(run_guard "$dir")"; status=$?
    rm -rf "$dir"
    if [ "$status" -eq 0 ]; then
        fail "$label — guard exited 0; the break went through unnoticed"
        return
    fi
    if ! grep -qE "$want" <<<"$out"; then
        fail "$label — guard failed, but not about this. Wanted /$want/, got:"
        sed 's/^/         /' <<<"$out" | head -10
        return
    fi
    ok "$label"
    grep -E "$want" <<<"$out" | head -1 | sed 's/^/       ↳ /'
}

# ── the tree as it stands must be clean ────────────────────────────
out="$(run_guard "$PRISTINE")"
if [ $? -eq 0 ]; then
    ok "working tree passes: $(tail -1 <<<"$out")"
else
    fail "working tree does not pass its own guard:"
    sed 's/^/         /' <<<"$out" | head -14
fi

# ── mutators ───────────────────────────────────────────────────────
edit_source() { python3 - "$2/$SOURCE" "$1" <<'PY'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

if kind == "silent_return":
    # The defect itself, put back: a guard clause in the entry point that walks
    # away without a word. Written the way somebody in a hurry would write it.
    anchor = "        val id = seq.incrementAndGet()"
    assert anchor in text, "test bug: cannot find where to insert the silent return"
    text = text.replace(anchor,
        "        if (target.text.length > 100000) return\n" + anchor, 1)

elif kind == "reuse_message":
    # Two exits collapsed onto one sentence: the owner can no longer tell an
    # empty reply from a reply that changed nothing.
    assert "R.string.enhance_empty_reply" in text
    text = text.replace("R.string.enhance_empty_reply, route.provider.label",
                        "R.string.enhance_unchanged", 1)

elif kind == "new_quiet_reason":
    # A second exit sneaking out through the quiet door.
    old = 'return@post ended(context, "run $id", "field moved while enhancing", R.string.enhance_stale)'
    assert old in text, "test bug: cannot find the stale exit"
    text = text.replace(old, 'return@post endedQuietly("run $id", "field moved, not worth a message")', 1)

elif kind == "drop_format_argument":
    # The message still formats %1$s; the call no longer passes it. getString
    # throws at exactly the moment the owner needed to be told something.
    old = "R.string.enhance_offline, route.provider.label"
    assert old in text
    text = text.replace(old, "R.string.enhance_offline", 1)

elif kind == "rename_entry":
    # The guard's search window run past the code it means to check. It must
    # say so rather than reporting a clean sweep of nothing.
    text = text.replace("fun run(context: Context, connection: RichInputConnection, style: AiRouter.Style, route: AiRouter.Route = AiRouter.route(context))",
                        "fun start(context: Context, connection: RichInputConnection, style: AiRouter.Style, route: AiRouter.Route = AiRouter.route(context))")

elif kind == "comment_out_attribution":
    # An exit whose only attribution is a comment mentioning the helper.
    old = 'return@execute ended(context, "run $id", "reply is identical to the source", R.string.enhance_unchanged)'
    assert old in text
    text = text.replace(old, "return@execute  // ended(context, R.string.enhance_unchanged)", 1)

else:
    raise SystemExit("test bug: unknown mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
PY
}

drop_spanish() { python3 - "$2/$ES" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
out = re.sub(r'\n *<string name="%s".*?</string>' % re.escape(key), "", text, count=1, flags=re.S)
assert out != text, "test bug: %s not found in %s" % (key, path)
open(path, "w", encoding="utf-8").write(out)
PY
}

duplicate_english() { python3 - "$2/$EN" "$1" <<'PY'
import re, sys
path, key = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()
twin = re.search(r'<string name="enhance_unchanged">(.*?)</string>', text, re.S).group(1)
out = re.sub(r'(<string name="%s">).*?(</string>)' % re.escape(key),
             lambda m: m.group(1) + twin + m.group(2), text, count=1, flags=re.S)
assert out != text, "test bug: %s not found" % key
open(path, "w", encoding="utf-8").write(out)
PY
}

edit_bar() { python3 - "$2/$BAR" "$1" <<'KOTLIN'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

if kind == "insert_goes_silent":
    # The defect as it actually stood in this file until the guard grew to see it:
    # Insert/Replace could not reach the field and walked away without a word.
    old = ('val ic = icp?.get() ?: run { '
           'toast(context.getString(R.string.translate_bar_no_field)); return }')
    assert old in text, "test bug: cannot find the apply() field check"
    text = text.replace(old, "val ic = icp?.get() ?: return", 1)

elif kind == "swap_goes_silent":
    old = '?: run { toast(context.getString(R.string.translate_bar_no_detection)); return })'
    assert old in text, "test bug: cannot find the swap() detection check"
    text = text.replace(old, "?: return)", 1)

elif kind == "attribution_on_the_next_line":
    # The sentence is still there, one line above the exit. That is one edit away
    # from not being there at all, and the guard must not read it as attached.
    old = ('val out = translated ?: run { toast(if (editor.isNotEmpty) '
           'context.getString(R.string.translate_bar_wait) else '
           'context.getString(R.string.translate_bar_type_first)); return }')
    assert old in text, "test bug: cannot find the copy() translation check"
    text = text.replace(old,
        'toast(context.getString(R.string.translate_bar_wait))\n'
        '        val out = translated ?: return', 1)

else:
    raise SystemExit("test bug: unknown bar mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
KOTLIN
}

edit_translator() { python3 - "$2/$TRANSLATOR" "$1" <<'KOTLIN'
import sys
path, kind = sys.argv[1], sys.argv[2]
text = open(path, encoding="utf-8").read()

if kind == "translate_goes_silent":
    # THE DEFECT, PUT BACK VERBATIM. This is what `fun translate` shipped with:
    # long-press TRANSLATE with no editor attached and the key did nothing and
    # said nothing, which reads as a dead key, a missing engine, an unset target
    # language and a failed translation all at once.
    old = '''        if (ic == null)
            return ended(appCtx, "pre-flight", "the input method has no connected editor",
                R.string.translate_no_input_connection)'''
    assert old in text, "test bug: cannot find the null-InputConnection exit"
    text = text.replace(old, "        if (ic == null) return", 1)

else:
    raise SystemExit("test bug: unknown translator mutation %s" % kind)

open(path, "w", encoding="utf-8").write(text)
KOTLIN
}

# No mutation argument, so the sandbox directory arrives as $1 rather than $2 —
# expect_caught appends it after whatever the case passed.
empty_manifest() { python3 - "$1/$MANIFEST" <<'JSON'
import json, sys
# A manifest with no entry points would let the guard sweep nothing and print a
# green tick for it - the exact shape of "the ship workflow ran zero testers and
# passed" that this whole suite exists to end.
path = sys.argv[1]
data = json.load(open(path, encoding="utf-8"))
data["entry_points"] = []
json.dump(data, open(path, "w", encoding="utf-8"), indent=2)
JSON
}

move_entry_source() { python3 - "$2/$MANIFEST" "$1" <<'JSON'
import json, sys
# What a renamed or moved Kotlin file looks like to the manifest. The guard must
# say it has lost the code rather than sweep what is left and report it clean -
# a guard that quietly stops covering something is the failure it exists to catch.
path, label = sys.argv[1], sys.argv[2]
data = json.load(open(path, encoding="utf-8"))
for entry in data["entry_points"]:
    if entry["label"] == label:
        entry["source"] = entry["source"].replace(".kt", "Moved.kt")
        break
else:
    raise SystemExit("test bug: no entry point labelled %s" % label)
json.dump(data, open(path, "w", encoding="utf-8"), indent=2)
JSON
}

expect_caught "a new silent return in the entry point is caught" \
    'silent exit — .return. with no' \
    edit_source silent_return

expect_caught "an exit whose only attribution is a comment is caught" \
    'silent exit' \
    edit_source comment_out_attribution

expect_caught "two exits sharing one sentence is caught" \
    'already reports exit at line' \
    edit_source reuse_message

expect_caught "a new reason claimed for a quiet exit is caught" \
    'not on the allowed list' \
    edit_source new_quiet_reason

expect_caught "a message left without the argument it formats is caught" \
    'formats 1 argument\(s\) but the call passes 0' \
    edit_source drop_format_argument

expect_caught "the guard losing sight of the entry point is caught" \
    'cannot find the entry point' \
    edit_source rename_entry

expect_caught "an exit message with no Spanish is caught" \
    'enhance_nothing_readable. has no entry in .*values-es' \
    drop_spanish enhance_nothing_readable

expect_caught "two exit messages reading identically is caught" \
    'says exactly what' \
    duplicate_english enhance_nothing_readable

expect_caught "the long-press TRANSLATE key going silent again is caught" \
    'TRANSLATE key — long press\]: silent exit' \
    edit_translator translate_goes_silent

expect_caught "the Translate bar's Insert losing its message is caught" \
    'Insert / Replace\]: silent exit' \
    edit_bar insert_goes_silent

expect_caught "the Translate bar's language swap losing its message is caught" \
    'swap languages\]: silent exit' \
    edit_bar swap_goes_silent

expect_caught "a message one line ABOVE the exit does not count as attribution" \
    'Copy\]: silent exit' \
    edit_bar attribution_on_the_next_line

expect_caught "a manifest with no entry points is caught" \
    'lists no entry points' \
    empty_manifest

expect_caught "an entry point whose source file moved is caught" \
    'does not exist .+ nothing to guard' \
    move_entry_source "Translate bar — Copy"

if [ "$FAILURES" -eq 0 ]; then
    echo "PASS — the guard fails on every way an exit can go silent or ambiguous, in every listed action."
    exit 0
fi
echo "FAIL — see above."
exit 1
