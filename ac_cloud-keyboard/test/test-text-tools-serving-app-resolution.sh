#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ The Text tools resolve WHICH app serves them, and leak no key    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The Text tools (Enhance, Translate, Grammar, Resume) are
# moving out of the keyboard and into cloud-writer, the application the owner
# asked for. The move is made survivable by ONE property: the client resolves
# the serving application from an ordered preference list at every bind instead
# of holding a single hardcoded package name.
#
# Every assertion below guards a way that property can be silently undone, and
# each failure is silent in exactly the way that matters — nothing crashes, the
# owner just finds that a tool stopped working, or worse that it kept working
# while pointing at the wrong app.
#
#   T1/T2  (#1707) the list names cloud-keyboard and no longer the retired cloud-writer.
#          consumer keeps talking to the keyboard, with nothing on screen saying
#          so. Order is the entire meaning of the list.
#   T3     no single-address constant survives. A leftover SERVICE_PKG is a
#          caller that never moves, and it would move on the day the keyboard
#          stops serving — i.e. it would break in the field, not in CI.
#   T4     the bind uses the RESOLVED package, not a constant. This is the
#          difference between "the fallback works" and "the fallback is written
#          down in a comment".
#   T5     resolution walks the list in order and filters by what actually
#          publishes the action, so an uninstalled preferred app is skipped
#          rather than bound-and-failed.
#   T6     isServingAppInstalled answers for ANY entry. Answering only for the
#          head would report a phone with a working Cloud Keyboard as having no
#          text tools and send the owner to install something that changes
#          nothing.
#   T7     <queries> stays scoped to the ACTION and names no package. A
#          package-scoped query grants visibility of that package only, so
#          cloud-writer would be invisible to resolution however the list reads.
#   T8     NO CONSUMER HARD-DEPENDS ON cloud-writer. The standing fleet rule is
#          that nothing outside an application's own source may fail that
#          application's release; a consumer manifest naming cloud-writer's
#          package would break that at install time.
#   T9/T10 no token reaches a log. This fleet ships a diagnostics path that
#          reads logcat and uploads it, so a key in the log is a key off the
#          phone. revealAiKey's result must never be logged, and the one catch
#          block that runs while a secret is in hand must log a class name and
#          not the exception.
#
# The assertions are STRUCTURAL — which call sits inside which function body —
# because that structure IS the fix. A wording check would pass over a rewrite
# that puts the defect straight back.
#
# No ripgrep, deliberately: four testers in this repository once passed only
# because ripgrep was absent and their call failed open. awk and grep only, and
# a missing file is FATAL rather than an empty read every assertion agrees with.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"

TOOLS_DIR="$ROOT/ab_cloud-libs-shared/libs/text-tools/src/main"
ADDRESSES="$TOOLS_DIR/java/com/diegonmarcos/superapp/texttools/TextTools.kt"
CLIENT="$TOOLS_DIR/java/com/diegonmarcos/superapp/texttools/TextToolsClient.kt"
LIB_MANIFEST="$TOOLS_DIR/AndroidManifest.xml"
SERVICE="$ROOT/ab_cloud-libs-shared/libs/keyboard/src/main/java/com/diegonmarcos/superapp/texttools/TextToolsService.kt"

WRITER_PKG="com.diegonmarcos.cloudwriter"
KEYBOARD_PKG="com.diegonmarcos.cloudkeyboard"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$ADDRESSES" "$CLIENT" "$LIB_MANIFEST" "$SERVICE"; do
    [ -f "$f" ] || { echo "FAIL   $f is missing — every assertion below would read an empty file and pass"; exit 1; }
done

# body <file> <signature substring>
# The declaration's own braces, from its signature to the matching close.
# Comments and string literals are blanked before counting so a brace inside
# either cannot shift the depth; an off-by-one there silently widens the window
# past the code being checked, which is how a passing grep came to prove nothing
# in this repository.
body() {
    awk -v sig="$2" '
        function strip(s) {
            gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
            sub(/\/\/.*$/, "", s)
            return s
        }
        !started && index($0, sig) { started = 1 }
        started {
            print
            line = strip($0)
            n = gsub(/\{/, "{", line); depth += n
            n = gsub(/\}/, "}", line); depth -= n
            if (seen_open || n > 0 || depth > 0) seen_open = 1
            if (seen_open && depth <= 0) exit
        }
    ' "$1"
}

# listBody <file> <signature substring>
# As above but for a parenthesised expression — listOf( … ) has no braces, so
# brace counting would run to the end of the file and every "is X inside it"
# check would be answered by the whole file.
list_body() {
    awk -v sig="$2" '
        function strip(s) {
            gsub(/"([^"\\]|\\.)*"/, "\"\"", s)
            sub(/\/\/.*$/, "", s)
            return s
        }
        !started && index($0, sig) { started = 1 }
        started {
            print
            line = strip($0)
            n = gsub(/\(/, "(", line); depth += n
            n = gsub(/\)/, ")", line); depth -= n
            if (seen_open || n > 0 || depth > 0) seen_open = 1
            if (seen_open && depth <= 0) exit
        }
    ' "$1"
}

# The extraction itself has to be provable, or every assertion built on it is
# reading whatever it happened to get. An empty body is fatal, not a pass.
check_body() {
    local text="$1" what="$2"
    if [ -z "$text" ]; then
        fail "$what — could not extract the declaration; its signature has moved and every check on it would read nothing"
        return 1
    fi
    return 0
}

echo "── Text tools: which app serves, and what never reaches a log ──"

# ── T1 ── the ordered list exists and holds the keyboard ───────────────────
# #1707 the standalone cloud-writer app is retired (its Writer is the keyboard's Writer tab), so the keyboard is the one entry.
PACKAGES="$(list_body "$ADDRESSES" 'val SERVICE_PACKAGES')"
if check_body "$PACKAGES" "T1 SERVICE_PACKAGES"; then
    if grep -q "\"$KEYBOARD_PKG\"" <<<"$PACKAGES"; then
        pass "T1 SERVICE_PACKAGES names cloud-keyboard"
    else
        fail "T1 SERVICE_PACKAGES must name $KEYBOARD_PKG — it owns the engines, the settings and the credential"
    fi
fi

# ── T2 ── the retired cloud-writer is NOT an address any more ─────────────
if [ -n "$PACKAGES" ]; then
    if grep -q "\"$WRITER_PKG\"" <<<"$PACKAGES"; then
        fail "T2 SERVICE_PACKAGES still names the retired $WRITER_PKG (#1707)"
    else
        pass "T2 the retired cloud-writer is not in SERVICE_PACKAGES"
    fi
fi

# ── T3 ── no single-address constant survives anywhere in the tree ────────
# Whole tree, not one file: the point is that no CALLER still hardcodes one app.
# NOT `2>/dev/null … || true`. grep answers 0 for a match, 1 for none and 2 for an
# ERROR, and collapsing those three into "empty output means clean" is precisely how a
# tester in this repository certified a tree that had conflict markers in it. Status 2
# is fatal here, so a bad root or an unreadable file cannot be read as a pass.
STALE="$(grep -rn 'SERVICE_PKG' --include='*.kt' --include='*.java' "$ROOT")"; RC=$?
STALE="$(printf '%s\n' "$STALE" | grep -v '/\.git/')" || true
if [ "$RC" -gt 1 ]; then
    fail "T3 the search itself failed (grep exit $RC) — an empty result here would be a verdict drawn from the error, not from the tree"
elif [ -z "$STALE" ]; then
    pass "T3 no SERVICE_PKG single-address constant survives"
else
    fail "T3 a single serving-app address survives — it would keep pointing at one app when the tools move:"
    printf '%s\n' "$STALE" | awk '{print "       " $0}'
fi

# ── T4 ── the bind uses the RESOLVED package, never a constant ────────────
BIND="$(body "$CLIENT" 'private fun bind()')"
if check_body "$BIND" "T4 TextToolsClient.bind()"; then
    if grep -q 'resolveServingPackage()' <<<"$BIND" && grep -q 'setPackage(target)' <<<"$BIND"; then
        pass "T4 bind() binds the package resolveServingPackage() chose"
    else
        fail "T4 bind() must call resolveServingPackage() and setPackage(target) — binding a constant makes the fallback a comment rather than behaviour"
    fi
    # A literal package name inside bind() is the regression this guards: it
    # would compile, it would work today, and it would silently pin the fleet
    # to whichever app was named.
    if grep -q "\"com\.diegonmarcos\." <<<"$BIND"; then
        fail "T4 bind() contains a literal package name — resolution must be the only thing that decides who is bound"
    else
        pass "T4 bind() hardcodes no package name"
    fi
fi

# ── T5 ── resolution walks the list in order, filtered by what publishes ──
RESOLVE="$(body "$CLIENT" 'private fun resolveServingPackage()')"
if check_body "$RESOLVE" "T5 TextToolsClient.resolveServingPackage()"; then
    if grep -q 'queryIntentServices' <<<"$RESOLVE" \
        && grep -q 'TextTools.SERVICE_PACKAGES' <<<"$RESOLVE" \
        && grep -q 'firstOrNull' <<<"$RESOLVE"; then
        pass "T5 resolution asks the package manager and takes the first listed app that publishes the action"
    else
        fail "T5 resolveServingPackage() must queryIntentServices and take firstOrNull over TextTools.SERVICE_PACKAGES — anything else stops being an ordered preference"
    fi
    # Iterating the INSTALLED set and picking the first of those would compile
    # and would look right, but it would hand the choice to whatever order the
    # package manager returned rather than to the declared preference.
    if grep -q 'SERVICE_PACKAGES.firstOrNull' <<<"$RESOLVE"; then
        pass "T5 the preference list, not the package manager's ordering, decides"
    else
        fail "T5 the first match must be taken over SERVICE_PACKAGES, not over the package manager's result order"
    fi
fi

# ── T6 ── isServingAppInstalled answers for ANY entry, not just the head ──
INSTALLED="$(body "$CLIENT" 'fun isServingAppInstalled()')"
if [ -z "$INSTALLED" ]; then
    # An expression-bodied function has no braces of its own; read its single line.
    INSTALLED="$(grep -n 'fun isServingAppInstalled()' "$CLIENT" | head -1 | awk -F: '{print $1}')"
    INSTALLED="$(awk -v n="$INSTALLED" 'NR==n' "$CLIENT")"
fi
if check_body "$INSTALLED" "T6 TextToolsClient.isServingAppInstalled()"; then
    if grep -q 'resolveServingPackage()' <<<"$INSTALLED"; then
        pass "T6 isServingAppInstalled() answers for any listed app"
    else
        fail "T6 isServingAppInstalled() must delegate to resolveServingPackage() — answering only for the preferred app reports a working phone as broken"
    fi
fi

# ── T7 ── the library's <queries> stays scoped to the ACTION ──────────────
QUERIES="$(awk '/<queries>/,/<\/queries>/' "$LIB_MANIFEST")"
if check_body "$QUERIES" "T7 libs:text-tools <queries>"; then
    if grep -q 'com.diegonmarcos.superapp.texttools.ITextTools' <<<"$QUERIES" \
        && ! grep -q '<package' <<<"$QUERIES"; then
        pass "T7 <queries> is scoped to the ITextTools action and names no package"
    else
        fail "T7 <queries> must stay an <intent> on the ITextTools action — a <package> entry grants visibility of that package alone and hides every other serving app from resolution"
    fi
fi

# ── T8 ── no consumer hard-depends on cloud-writer ────────────────────────
# The standing rule: nothing outside an application's own source may fail that
# application's release. A manifest that names cloud-writer's package makes an
# absent cloud-writer a consumer problem.
# Same fail-closed reasoning as T3: grep status 2 is an error, not an all-clear.
HARD_DEP="$(grep -rnE "[\"']$WRITER_PKG[\"']" --include='AndroidManifest.xml' --include='*.gradle' --include='*.gradle.kts' "$ROOT")"; RC=$?
HARD_DEP="$(printf '%s\n' "$HARD_DEP" | grep -v '/\.git/' | grep -v "^$ROOT/ac_cloud-writer/")" || true
if [ "$RC" -gt 1 ]; then
    fail "T8 the search itself failed (grep exit $RC) — an empty result here would be a verdict drawn from the error, not from the tree"
elif [ -z "$HARD_DEP" ]; then
    pass "T8 no consumer manifest or build file hard-depends on cloud-writer"
else
    fail "T8 a consumer names cloud-writer's package — an absent cloud-writer must never be able to fail another app's build or install:"
    printf '%s\n' "$HARD_DEP" | awk '{print "       " $0}'
fi

# ── T9 ── the revealed key is never logged, on either side ────────────────
# revealAiKey is the one call in the whole contract that emits a credential.
# Both its implementation and its client wrapper are checked, because either
# side logging it puts the key in the same uploaded bundle.
REVEAL_SERVICE="$(body "$SERVICE" 'override fun revealAiKey')"
REVEAL_CLIENT="$(body "$CLIENT" 'fun revealAiKey')"
if [ -z "$REVEAL_CLIENT" ]; then
    REVEAL_CLIENT="$(grep -n 'fun revealAiKey' "$CLIENT" | head -1 | awk -F: '{print $1}')"
    REVEAL_CLIENT="$(awk -v n="$REVEAL_CLIENT" 'NR>=n && NR<=n+2' "$CLIENT")"
fi
for pair in "service:$REVEAL_SERVICE" "client:$REVEAL_CLIENT"; do
    side="${pair%%:*}"
    text="${pair#*:}"
    check_body "$text" "T9 revealAiKey ($side)" || continue
    if grep -qE '\b(Log|Toast)\b' <<<"$text"; then
        fail "T9 revealAiKey ($side) reaches a log or a toast — this fleet uploads logcat, so a key that reaches the log leaves the phone"
    else
        pass "T9 revealAiKey ($side) neither logs nor toasts the credential"
    fi
done

# ── T10 ── the one catch block holding a secret logs a class, not the error ─
SET_ROUTING="$(body "$SERVICE" 'override fun setAiRouting')"
if check_body "$SET_ROUTING" "T10 TextToolsService.setAiRouting()"; then
    if grep -q 'e.javaClass.simpleName' <<<"$SET_ROUTING" \
        && ! grep -qE 'Log\.[a-z]+\([^)]*\$\{?e\}?[,)]|Log\.[a-z]+\(.*e\.message|Log\.[a-z]+\(.*", e\)' <<<"$SET_ROUTING"; then
        pass "T10 setAiRouting logs an exception class name, never the exception that was thrown over a secret"
    else
        fail "T10 setAiRouting must log e.javaClass.simpleName only — the value being written is an API key and an exception's own text can carry it"
    fi
fi

echo "── ${FAILURES} failed ──"
[ "$FAILURES" -eq 0 ]
