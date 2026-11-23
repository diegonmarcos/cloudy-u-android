#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ cloud-mail reader: translation IN PLACE, cached, toggled, auto-translated ║
# ║ and the summary box - static proof, each check mutation-proven           ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
#   R1  no popup: the reader opens no result dialog; Translate and Resume go through runReaderTool
#   R2  Show Original / Show Translated sits in the TEXT TOOLS section (#1870), enabled only once a translation exists
#   R3  the translated fragment enters the document builder and is a key of the remembered page
#   R4  the cache: a table, a REGISTERED migration, a DAO on the database, one key per message+language
#   R5  auto-translate: off by default, gated by detection; auto-summary: off by default
#   R6  the summary box: below the reading actions, collapsible, expanded by default, language setting
#   R7  the language setting is a dropdown (no free text), English by default
#   (the fleet manifest declaration is held by the fleet-config guard, which reads the whole fleet)
#   M   mutation-proof: each defence undone in a COPY turns its check red; the real tree is green
#
# OWN-SOURCE ONLY. python3 and grep only; no gradle, no device.
set -uo pipefail
APP="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SRC="$APP/app/src/main/kotlin"
CORE="$APP/core/data/src/main/kotlin"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# check <name> <files...>: each check reads a mirror tree rooted at $1 (so mutations run on a copy)
r1() { python3 - "$1" <<'PY'
import re, sys
s = open(sys.argv[1] + "/app/sterna/ui/message/MessageScreen.kt", encoding="utf-8").read()
code = "\n".join(l for l in s.splitlines() if not l.strip().startswith(("//", "*", "/*")))
bad = []
if "TextToolPanel(" in code: bad.append("the reader still opens the result dialog")
if "textTools.run(" in code: bad.append("a reader tool still runs through the dialog's runner")
if code.count("runReaderTool(viewModel") < 2: bad.append("the icon row and the overflow do not both use runReaderTool")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
r2() { python3 - "$1" <<'PY'
import sys
s = open(sys.argv[1] + "/app/sterna/ui/message/MessageScreen.kt", encoding="utf-8").read()
# #1870: the toggle lives in the overflow's TEXT TOOLS section, after Translate (was: beside Unsubscribe).
u = s.find("MenuSectionTitle(R.string.menu_section_text_tools)"); t = s.find("R.string.message_show_original"); h = s.find("MenuSectionTitle(R.string.menu_section_sender)")
bad = []
if not (0 < u < t < h): bad.append("the toggle is not in the TEXT TOOLS section")
else:
    e = s[t:h]
    if "enabled = translation.exists" not in e: bad.append("the toggle is not disabled until a translation exists")
    if "viewModel.toggleTranslated()" not in e: bad.append("the toggle does not flip the page")
    if "R.string.message_show_translated" not in e: bad.append("the toggle has no 'Show translated' state")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
r3() { python3 - "$1" <<'PY'
import sys
s = open(sys.argv[1] + "/app/sterna/ui/message/MessageScreen.kt", encoding="utf-8").read()
bad = []
for need, why in (("var inner = translatedFragment ?: body.fragment", "the builder does not use the translated fragment"),
                  ("deceptiveLinkLabel,\n                    translatedFragment,\n                ) {", "the translation is not a key of the remembered document"),
                  ("translatedFragment = translation.fragmentFor(readerFragment),", "the page is not handed the translation only while shown and current")):
    if need not in s: bad.append(why)
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
r4() { python3 - "$1" <<'PY'
import re, sys
d = sys.argv[1] + "/app/sterna/core/data/db/"
db = open(d + "SternaDatabase.kt", encoding="utf-8").read(); mg = open(d + "Migrations.kt", encoding="utf-8").read()
en = open(d + "MessageTextCacheEntity.kt", encoding="utf-8").read()
bad = []
if "MessageTextCacheEntity::class" not in db: bad.append("the entity is not in @Database")
if "fun messageTextCacheDao()" not in db: bad.append("the DAO is not exposed")
if "MIGRATION_28_29" not in db: bad.append("MIGRATION_28_29 is not REGISTERED (Room would drop every table)")
if "val MIGRATION_28_29 = object : Migration(28, 29)" not in mg: bad.append("the 28->29 step is missing")
if 'primaryKeys = ["accountId", "emailId", "kind", "lang"]' not in en: bad.append("the cache is not keyed per message AND language")
if "sourceHash" not in en: bad.append("a row does not record what it was made from")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
r5() { python3 - "$1" <<'PY'
import sys
s = sys.argv[1] + "/app/sterna/ui/"
p = open(s + "text/MailTextToolsPrefs.kt", encoding="utf-8").read(); v = open(s + "message/MessageViewModel.kt", encoding="utf-8").read()
bad = []
for need in ("getBoolean(KEY_AUTO_TRANSLATE, false)", "getBoolean(KEY_AUTO_SUMMARY, false)"):
    if need not in p: bad.append("not off by default: " + need)
o = v[v.index("fun onReaderOpened("):v.index("fun translateNow(")]
if "MailTextToolsPrefs.autoTranslate(app) &&" not in o or "textAi.needsTranslation(" not in o:
    bad.append("auto-translate is not gated by the switch AND by language detection")
if "MailTextToolsPrefs.autoSummary(app)" not in o: bad.append("auto-summary is not its own switch")
for b in bad: print("    " + b)
sys.exit(1 if bad else 0)
PY
}
r6() { python3 - "$1" <<'PY'
import sys
s = sys.argv[1] + "/app/sterna/ui/message/"
m = open(s + "MessageScreen.kt", encoding="utf-8").read(); b = open(s + "ResumeBox.kt", encoding="utf-8").read()
bad = []
i = m.find("readingActions()\n"); j = m.find("ResumeBox(LocalReaderSummary.current, msg.id)")
if not (0 < i < j): bad.append("the summary box is not drawn after the reading actions, under the header")
if "rememberSaveable(emailId) { mutableStateOf(true) }" not in b: bad.append("the box is not expanded by default")
if ".clickable { expanded = !expanded }" not in b: bad.append("the box does not collapse")
if "if (!summary.visible) return" not in b: bad.append("the box is drawn without a summary")
if "viewModel" in b: bad.append("the box can reach a ViewModel")
for x in bad: print("    " + x)
sys.exit(1 if bad else 0)
PY
}
r7() { python3 - "$1" <<'PY'
import sys
s = sys.argv[1] + "/app/sterna/ui/"
t = open(s + "settings/TextToolsScreens.kt", encoding="utf-8").read(); l = open(s + "text/MailLanguages.kt", encoding="utf-8").read()
bad = []
if t.count("LanguageDropdown(") < 2: bad.append("the translate target and the summary language are not both dropdowns")
if "OutlinedTextField" in t: bad.append("a free-text field is back on a Text page")
if 'const val DEFAULT = "en"' not in l: bad.append("the default language is not English")
for x in bad: print("    " + x)
sys.exit(1 if bad else 0)
PY
}
UIROOT="$APP"
mirror() { # <dest>: the files the checks read, laid out under their short names
  rm -rf "$1"; mkdir -p "$1/app/sterna/ui" "$1/app/sterna/core/data"
  cp -r "$SRC/app/sterna/ui/message" "$SRC/app/sterna/ui/text" "$SRC/app/sterna/ui/settings" "$1/app/sterna/ui/"
  cp -r "$CORE/app/sterna/core/data/db" "$1/app/sterna/core/data/"
}
T="$(mktemp -d)"; trap 'rm -rf "${T:?}"' EXIT
mirror "$T/ok"
run() { # <label> <fn> <msg>
  "$2" "$T/ok" && pass "$3" || fail "$3"
}
run R1 r1 "R1 no popup on the reader; one function starts every reading tool"
run R2 r2 "R2 Show Original / Show Translated in TEXT TOOLS, disabled until a translation exists"
run R3 r3 "R3 the translated fragment enters the document and its key"
run R4 r4 "R4 cache table, registered migration, DAO, one key per message+language"
run R5 r5 "R5 auto-translate and auto-summary off by default; auto-translate gated by detection"
run R6 r6 "R6 summary box under the reading actions, collapsible, expanded by default"
run R7 r7 "R7 language settings are dropdowns, English by default"

echo "── M mutation-proof ──"
mut() { # <label> <check> <file under mirror> <old> <new>
  mirror "$T/m"
  python3 - "$T/m/$3" "$4" "$5" <<'PY' || { fail "M: $1 - the mutation did not apply (tester is stale)"; return; }
import sys
p, old, new = sys.argv[1:4]
s = open(p, encoding="utf-8").read()
if old not in s: sys.exit(3)
open(p, "w", encoding="utf-8").write(s.replace(old, new, 1))
PY
  "$2" "$T/m" >/dev/null && fail "M: $1 passed" || pass "M: $1 -> RED"
}
mut "the result dialog back on the reader" r1 app/sterna/ui/message/MessageScreen.kt "val readerSummary by viewModel.summary.collectAsStateWithLifecycle()
    val inTrash" "val readerSummary by viewModel.summary.collectAsStateWithLifecycle()
    TextToolPanel(textTools, onApply = null)
    val inTrash"
mut "the toggle enabled before a translation exists" r2 app/sterna/ui/message/MessageScreen.kt "enabled = translation.exists," "enabled = true,"
mut "the translation never reaches the document" r3 app/sterna/ui/message/MessageScreen.kt "var inner = translatedFragment ?: body.fragment" "var inner = body.fragment"
mut "the migration written but not registered" r4 app/sterna/core/data/db/SternaDatabase.kt "MIGRATION_27_28, MIGRATION_28_29," "MIGRATION_27_28,"
mut "the cache keyed by message only" r4 app/sterna/core/data/db/MessageTextCacheEntity.kt 'primaryKeys = ["accountId", "emailId", "kind", "lang"]' 'primaryKeys = ["accountId", "emailId", "kind"]'
mut "auto-translate on by default" r5 app/sterna/ui/text/MailTextToolsPrefs.kt "getBoolean(KEY_AUTO_TRANSLATE, false)" "getBoolean(KEY_AUTO_TRANSLATE, true)"
mut "auto-translate without detection" r5 app/sterna/ui/message/MessageViewModel.kt "MailTextToolsPrefs.autoTranslate(app) &&
                        textAi.needsTranslation(InPlaceHtmlTranslation.visibleText(fragment), target)" "MailTextToolsPrefs.autoTranslate(app)"
mut "the summary box collapsed by default" r6 app/sterna/ui/message/ResumeBox.kt "rememberSaveable(emailId) { mutableStateOf(true) }" "rememberSaveable(emailId) { mutableStateOf(false) }"
mut "the summary box drawn with nothing to say" r6 app/sterna/ui/message/ResumeBox.kt "if (!summary.visible) return" ""
mut "a free-text language field back" r7 app/sterna/ui/settings/TextToolsScreens.kt "internal fun MailTranslationScreen(onBack: () -> Unit) {" "internal fun MailTranslationScreen(onBack: () -> Unit) {
    val unused = OutlinedTextField"
r1 "$T/ok" >/dev/null && r2 "$T/ok" >/dev/null && r3 "$T/ok" >/dev/null && r4 "$T/ok" >/dev/null && r5 "$T/ok" >/dev/null \
  && r6 "$T/ok" >/dev/null && r7 "$T/ok" >/dev/null && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-mail-reader-translation: all checks passed"; else echo "test-mail-reader-translation: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
