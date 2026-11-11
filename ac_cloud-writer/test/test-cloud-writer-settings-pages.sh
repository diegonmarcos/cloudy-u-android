#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════╗
# ║ Cloud Writer has the keyboard's four configuration pages, and OWNS   ║
# ║ every value on them                                                  ║
# ╚══════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. Three times now, "give app B the same pages as app A" has
# been built by pointing app B at app A's screens and app A's storage. The owner
# on the third time: "all those I SAID COPY FROM CLOUD KEYBOARD!!! I NEVER SAID
# LINK WITH IT!!! WHY I CANT EDIT NOTHING?" A page that opens and is wired to the
# other application's store ALSO EXISTS, ALSO LOOKS RIGHT, and passes any test
# that only asks whether the page is there. So no assertion below is satisfied by
# a page existing.
#
#   P1  the four pages exist AS ACTIVITIES OF THIS APPLICATION, are declared in
#       this manifest, are not exported, and are reachable from the main screen.
#   P2  INDEPENDENCE, WHICH IS THE ONE THAT WOULD HAVE CAUGHT 209. Nothing in
#       this application names the keyboard's package, classes or resources;
#       nothing opens another application's Context or preferences; and every
#       value goes through ONE private SharedPreferences file belonging to this
#       application. The reverse direction too: no sharedUserId and no
#       ContentProvider, so the keyboard has no route in either.
#   P3  every row reads AND writes through WriterPrefs, and every preference key
#       a page uses is DECLARED in WriterPrefs — a page writing a raw key would
#       be a value no other screen here can find.
#   P4  the prompt preview is GENERATED from the settings, by the same call a run
#       makes, and re-read when a setting changes. A preview that is a literal
#       matching today is the defect, not the feature.
#   P5  "Pruébalo" actually runs — through WriterToolRunner, the same object the
#       main screen's button uses.
#   P6  the LanguageTool URL is the MESH one, character for character.
#   P7  the price table carries its accumulated fixes: one line per model (214),
#       no scaling of the price (217), the as-of date on the page (219), and no
#       catalogue request that could ever veto a build (247), three decimals and
#       a fixed locale (186/187).
#   P8  THE OWNER'S OWN SCREEN, CHARACTER FOR CHARACTER. The Spanish he pasted
#       from his phone is written out below and must appear in values-es exactly.
#       This is the "his eyes, not your judgement" check.
#   P9  the defaults are the ones reported, so the page a new install shows is
#       the page that was described.
#  P10  every string resource is one aapt will accept. This container has no
#       Android SDK; a bare apostrophe XML-parses cleanly here and fails the
#       resource merge on CI, which is exactly how run 34590327712 spent forty
#       minutes to publish nothing.
#
# READS NOTHING OUTSIDE ac_cloud-writer. That is deliberate and it is the point:
# under the foreign-source rule a tester that reaches into another application's
# tree has its failure DOWNGRADED to a warning and the APK ships anyway. The
# sibling test-cloud-writer-tools.sh is mixed for good reasons of its own and
# therefore cannot be relied on to fail this build. This one can.
#
# No ripgrep. grep, awk, sed and python3 only — and a missing file is FATAL
# rather than an empty read that every assertion below would agree with.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"

APP="$ROOT/ac_cloud-writer"
SRC="$APP/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter"
RES="$APP/../ab_cloud-libs-shared/libs/writer-ui/src/main/res"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
BUILD_JSON="$APP/build.json"

PAGES="TextEnhanceActivity TranslationActivity GrammarCheckActivity AiRoutingActivity"
LT_URL="https://languagetool.diegonmarcos.com/v2/check"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$MANIFEST" "$BUILD_JSON" "$RES/values/strings.xml" "$RES/values-es/strings.xml" \
         "$SRC/WriterSurface.kt" "$SRC/WriterPrefs.kt" "$SRC/WriterRegistry.kt" \
         "$SRC/WriterTools.kt" "$SRC/WriterSettingsUi.kt"; do
    [ -f "$f" ] || { echo "FAIL   $f is missing — every assertion below would read an empty file and pass"; exit 1; }
done
for p in $PAGES; do
    [ -f "$SRC/$p.kt" ] || { echo "FAIL   $SRC/$p.kt is missing — the page it draws cannot be asserted about"; exit 1; }
done
command -v python3 >/dev/null || { echo "FAIL   python3 is absent; the manifest and the string files would go unread"; exit 1; }

# CODE ONLY, NEVER PROSE. A grep over a Kotlin file matches its own KDoc, and
# this repository has shipped exactly that twice: a guard that matched the
# sentence explaining it, and a check satisfied by the commented-out line it was
# written to detect as missing. Everything below greps THIS, never the file.
# The `//` of `https://` IS NOT A COMMENT, and treating it as one is not a
# hypothetical: the first run of this file stripped the LanguageTool URL out of
# WriterPrefs and then reported that the URL was missing. A `//` preceded by a
# colon is a scheme; anywhere else on a line it starts a comment.
code() {  # code <file...>
    sed -e 's,^[[:space:]]*//.*,,' -e 's,\([^:]\)//.*,\1,' "$@" | awk '
        /\/\*/ { inblock = 1 }
        inblock == 0 { print }
        /\*\// { inblock = 0 }
    '
}

ALL_KT="$(mktemp)"; PAGE_KT="$(mktemp)"; trap 'rm -f "$ALL_KT" "$PAGE_KT"' EXIT INT TERM
code "$SRC"/*.kt >"$ALL_KT"
code $(for p in $PAGES; do printf '%s ' "$SRC/$p.kt"; done) >"$PAGE_KT"
[ -s "$ALL_KT" ] || { echo "FAIL   stripping comments left nothing to read — the comment stripper is broken, not the source"; exit 1; }

# ── P1  four pages, this application's own, declared and reachable ───────────

python3 - "$MANIFEST" $PAGES <<'PYEOF'
import sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
app = root.find("application")
# READ AS XML, NEVER GREPPED: this manifest's own comments name the four
# activities and the ITextTools action several times over, explaining why they
# are or are not there. A text search would match that reasoning.
acts = {a.get(A + "name"): a for a in app.findall("activity")}
bad = 0
for page in sys.argv[2:]:
    name = "com.diegonmarcos.cloudwriter." + page
    a = acts.get(name)
    if a is None:
        print("FAIL   P1 %s is not declared in this manifest — an undeclared activity cannot be opened" % page); bad += 1; continue
    if a.get(A + "exported") != "false":
        print("FAIL   P1 %s is exported; nothing outside this app has any business driving its settings" % page); bad += 1
    if a.findall("intent-filter"):
        print("FAIL   P1 %s declares an intent-filter — a settings page reachable by action is a page another app can stand in for" % page); bad += 1
    print("ok     P1 %s is a private activity of this application" % page)
# #800: the debug-API registrar is a <provider> (the CalcDebugApiProvider trick) and serves no
# data; what P2 forbids is a provider ANOTHER PROCESS can reach, i.e. one not exported="false".
reachable = [p.get(A + "name") for p in app.findall("provider") if p.get(A + "exported") != "false"]
if reachable:
    print("FAIL   P2 this manifest exports a <provider> (%s); that is a route into this app's storage from outside it" % ", ".join(reachable)); bad += 1
else:
    print("ok     P2 no exported ContentProvider — nothing can read this app's settings from another process")
if root.get(A + "sharedUserId"):
    print("FAIL   P2 sharedUserId is set; it puts this app and another in ONE sandbox, which is shared storage by definition"); bad += 1
else:
    print("ok     P2 no sharedUserId — the platform keeps this store and the keyboard's apart")
sys.exit(1 if bad else 0)
PYEOF
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

for p in $PAGES; do
    if grep -qF "activityClass(\"${p}\")" <(code "$SRC/WriterSurface.kt"); then
        pass "P1 the main screen opens $p"
    else
        fail "P1 the main screen never starts $p — a declared page nothing opens is a page the owner cannot reach"
    fi
done

# ── P2  INDEPENDENCE — the assertion nobody wrote before 209 ─────────────────
#
# A page wired to cloud-keyboard's store looks identical, opens identically, and
# fails none of P1. These are the ways this application could reach that store,
# and none of them may appear anywhere in its source.

# A probe must not end inside a longer name: com.diegonmarcos.cloudkeyboardlibs is the
# keyboard-engines COMPANION, whose IVoiceEngine Listen binds (#800) — an engine, not a settings store.
reach_keyboard() { grep -nE "$1([^a-z]|\$)" "$ALL_KT"; }

for probe in "helium314" "cloudkeyboard" "com.diegonmarcos.cloudkeyboard" \
             "createPackageContext" "MODE_WORLD_READABLE" "MODE_WORLD_WRITEABLE" \
             "createDeviceProtectedStorageContext" "TranslatePrefs"; do
    if reach_keyboard "$probe" >/dev/null; then
        fail "P2 this application's code names '$probe' — that is a route to another application's settings, and a page over another application's settings is task 209"
    else
        pass "P2 nothing in this application names '$probe'"
    fi
done

# ONE STORE, AND IT IS THIS APPLICATION'S OWN. WriterPrefs.prefs() must be the
# only place a SharedPreferences is opened at all; a second call anywhere is a
# second store, and a second store is where a shared file gets opened by mistake.
PREF_OPENS="$(grep -c 'getSharedPreferences' "$ALL_KT")"
if [ "$PREF_OPENS" = "1" ]; then
    pass "P2 exactly one getSharedPreferences call in the whole application"
else
    fail "P2 $PREF_OPENS getSharedPreferences calls (expected exactly 1, in WriterPrefs.prefs) — every additional one is another store this app's pages could disagree over"
fi
if grep -q 'getSharedPreferences' <(code "$SRC/WriterPrefs.kt"); then
    pass "P2 that one call is WriterPrefs' own"
else
    fail "P2 WriterPrefs does not open a SharedPreferences — the one call counted above is somewhere else"
fi
if grep -q 'getSharedPreferences(FILE, Context.MODE_PRIVATE)' <(code "$SRC/WriterPrefs.kt"); then
    pass "P2 it is MODE_PRIVATE, on this application's own named file"
else
    fail "P2 WriterPrefs' store is not a MODE_PRIVATE open of its own FILE"
fi
if grep -q 'private const val FILE = "text_tools"' <(code "$SRC/WriterPrefs.kt"); then
    pass "P2 the file is text_tools, NOT the default <package>_preferences the translate library shares"
else
    fail "P2 WriterPrefs.FILE is not the private text_tools file"
fi

# ── P3  every page row goes through WriterPrefs, and every key is declared ───

if grep -qE '\.edit\(\)|putString|putBoolean' "$PAGE_KT"; then
    fail "P3 a page edits a SharedPreferences directly instead of going through WriterPrefs"
else
    pass "P3 no page touches a preference editor directly"
fi

python3 - "$SRC" $PAGES <<'PYEOF'
import re, sys, os
src = sys.argv[1]
declared = set(re.findall(r'const val (KEY_[A-Z0-9_]+)\s*=', open(os.path.join(src, "WriterPrefs.kt"), encoding="utf-8").read()))
if not declared:
    print("FAIL   P3 WriterPrefs declares no KEY_ constants at all — the check below would pass over anything"); sys.exit(1)
bad = 0
used = set()
for page in sys.argv[2:]:
    body = open(os.path.join(src, page + ".kt"), encoding="utf-8").read()
    body = re.sub(r'/\*.*?\*/', '', body, flags=re.S)
    body = re.sub(r'//.*', '', body)
    for key in re.findall(r'WriterPrefs\.(KEY_[A-Za-z0-9_]+)', body):
        used.add(key)
        if key not in declared:
            print("FAIL   P3 %s uses WriterPrefs.%s, which WriterPrefs does not declare" % (page, key)); bad += 1
    # A page naming a preference as a bare string instead of a constant is a key
    # no other screen can find and no test can follow.
    for lit in re.findall(r'(?:put|string|flag)\(\s*this\s*,\s*"([a-z_]+)"', body):
        print("FAIL   P3 %s writes the raw key \"%s\" instead of a WriterPrefs constant" % (page, lit)); bad += 1
if len(used) < 12:
    print("FAIL   P3 the four pages between them use only %d preference keys; the pages described use far more, so this check is reading the wrong files" % len(used)); bad += 1
else:
    print("ok     P3 the four pages use %d WriterPrefs keys and every one of them is declared there" % len(used))
sys.exit(1 if bad else 0)
PYEOF
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── P4  the prompt preview is GENERATED ─────────────────────────────────────

ENH="$(code "$SRC/TextEnhanceActivity.kt")"
# THE ARGUMENT, not merely the presence of the call somewhere in the file. The
# first version of this check asked only whether `WriterPrefs.enhancePrompt(this)`
# appeared anywhere in the page — and a preview replaced by a hardcoded paragraph
# still passed it, because the refresh function further down mentions the same
# call. What has to be true is that THE ROW IS FED IT: the line after the
# preview's summary argument.
if grep -q 'WriterPrefs.enhancePrompt(this),' <<<"$(printf '%s\n' "$ENH" | grep -A1 'getString(R.string.enhance_prompt_summary),')"; then
    pass "P4 the preview row is handed WriterPrefs.enhancePrompt(this) — the same call a run makes"
else
    fail "P4 the Text Enhancements page does not build its preview from WriterPrefs.enhancePrompt(this)"
fi
# THE SUBSCRIBING READ. The page is Compose now, so "re-read it after a pick" is no
# longer a re-assignment of a TextView's text — it is PageContent naming the state
# refreshPrompt() bumps, which is what makes a bump re-run the body and hand the row
# a freshly composed prompt. That bare statement is the entire update mechanism and
# looks exactly like a line that does nothing, so it is the first thing a tidy-up
# would delete. Anchored to the whole line: a loose match would also be satisfied by
# the two reads inside refreshPrompt() itself, which is the assertion checking its
# own plumbing rather than the page.
if grep -qx '        promptGeneration.value' <<<"$ENH"; then
    pass "P4 and recomposes it when a setting changes, so the paragraph moves under the owner's thumb"
else
    fail "P4 nothing re-reads the preview after a pick; it would only update on reopening, which is indistinguishable from a literal"
fi
REFRESHERS="$(printf '%s' "$ENH" | grep -c 'refreshPrompt()')"
# Four settings feed the prompt — style, tone, length, output language — plus the
# function's own declaration. Scope does not: it chooses WHAT is sent, not what
# the model is told. Fewer than five means a menu was wired without the preview.
if [ "$REFRESHERS" -ge 5 ]; then
    pass "P4 all four prompt-shaping menus refresh it ($REFRESHERS references)"
else
    fail "P4 only $REFRESHERS references to refreshPrompt(); a menu that shapes the prompt does not update the preview"
fi
# THE LITERAL CHECK. A preview hardcoded to today's text is the false green this
# whole file exists for, so no page may contain a sentence out of the preamble.
if grep -q 'text rewriting engine' "$PAGE_KT"; then
    fail "P4 a page file contains the preamble as a literal — that is a second copy of the prompt and it will disagree with the one that is sent"
else
    pass "P4 no page carries any of the prompt as a literal"
fi

# ── P5  Pruébalo actually runs ──────────────────────────────────────────────

if grep -q 'runner.run(WriterTool.ENHANCE' <<<"$ENH"; then
    pass "P5 the try-it box calls WriterToolRunner.run — the same path as the main screen's button"
else
    fail "P5 the try-it box does not run anything through WriterToolRunner; a decorative test box is worse than none"
fi
if grep -q 'outcome.error ?: getString(R.string.run_no_reason)' <<<"$ENH"; then
    pass "P5 and a failed run reports the engine's own reason rather than falling silent"
else
    fail "P5 the try-it box has a path that ends without saying anything"
fi

# ── P6  the LanguageTool URL, and the mesh constraint around it ─────────────

# #1093 the default is no longer a literal: WriterPrefs reads BuildConfig.LANGUAGETOOL_URL, which libs:writer-ui bakes
# from the one fleet declaration (fleetUrl('languagetool') + /v2/check). The mesh-only constraint is therefore asserted on the
# chain: the pref reads the baked field, the build bakes the declared service, and the declaration is the mesh-only host.
LT_DECL="$APP/../ac_cloud-account/build.json"
LT_DECLARED=$(python3 - "$LT_DECL" <<'PY' 2>/dev/null
import json, sys
s = [x for x in json.load(open(sys.argv[1]))["fleet_endpoints"]["services"] if x["id"] == "languagetool"][0]
print("%s://%s%s" % (s.get("public_scheme", "https"), s["public_domain"], s.get("public_path", "")) + "/v2/check" if s.get("policy") == "mesh_only" else "")
PY
)
if grep -qE 'DEFAULT_GRAMMAR_REMOTE_URL[^=]*=[[:space:]]*BuildConfig\.LANGUAGETOOL_URL' <(code "$SRC/WriterPrefs.kt") \
   && grep -qF "fleetUrl('languagetool')}/v2/check" "$APP/../ab_cloud-libs-shared/libs/writer-ui/build.gradle" \
   && [ "$LT_DECLARED" = "$LT_URL" ]; then
    pass "P6 the LanguageTool default is the declared mesh endpoint $LT_URL (WriterPrefs <- BuildConfig <- fleet declaration)"
else
    fail "P6 the LanguageTool default is not $LT_URL — do not point this at a public LanguageTool; that endpoint answers on the WireGuard mesh only, and a public one would be a third party receiving the owner's text"
fi
if grep -q 'grammar_remote_unreachable' <(code "$SRC/WriterTools.kt"); then
    pass "P6 Remote mode REFUSES with a reason rather than quietly doing the AI rewrite instead"
else
    fail "P6 nothing refuses Remote mode; this application opens no socket, so an unrefused Remote either does nothing or silently runs another mode"
fi

# ── P7  the price table's accumulated fixes ────────────────────────────────

AI="$(code "$SRC/AiRoutingActivity.kt")"
COLS="$(printf '%s' "$AI" | grep -c 'R.string.ai_pricing_col_')"
WIDTHS="$(printf '%s' "$AI" | sed -n 's/.*widthsDp = listOf(\([^)]*\)).*/\1/p' | tr ',' '\n' | grep -c '[0-9]')"
if [ "$COLS" = "8" ] && [ "$WIDTHS" = "8" ]; then
    pass "P7/214 eight columns and eight widths — one line per model, the row scrolls sideways"
else
    fail "P7/214 $COLS column headings against $WIDTHS widths; a column without a width draws at zero and vanishes"
fi
if grep -qE '\* *100|100 *\*|/ *100|cents|centim' <<<"$AI"; then
    fail "P7/217 the price column scales its number. It must not: the registry holds US dollars per million tokens and the table prints that unchanged. Scaling is what made the column a hundred times too high."
else
    pass "P7/217 no scaling between the registry's price and the cell"
fi
if grep -q 'String.format(Locale.US, "%.3f"' <<<"$AI"; then
    pass "P7/186 three decimals, fixed to Locale.US so a decimal point cannot become a comma"
else
    fail "P7/186 the price format is not a three-decimal Locale.US format"
fi
if grep -q 'R.string.ai_pricing_baked, provider.pricingAsOf' <<<"$AI"; then
    pass "P7/219 the as-of date is drawn under the table, so no price is read as today's"
else
    fail "P7/219 the table does not print the date its prices were taken — that is exactly how a 0.966 was read as 0.280"
fi
# 247: a live catalogue lookup must never be able to hold up the owner's APK. Since #800 the app
# does reach the network, for the MODEL route of speech and translation and for the Routes page's
# catalogue refresh — at RUN time, on a tap or a dictated segment, never at build time. What stays
# forbidden: a socket of its own (every request goes through libs:decisions' UrlHttp, the fleet's
# one transport over Android's resolver), the AI Routing page fetching anything, and this manifest
# declaring INTERNET itself (libs:devtools declares it for its debug socket; the merge carries it).
if grep -qE 'HttpURLConnection|URL\(|OkHttp|catalogUrl\.' "$ALL_KT"; then
    fail "P7/247 this application opens a connection of its own. Every request goes through libs:decisions' UrlHttp, and no catalogue URL from the AI Routing registry is ever fetched"
else
    pass "P7/247 no socket of its own — every request goes through libs:decisions' transport"
fi
if grep -qE 'fetchCatalogue|UrlHttp' <(code "$SRC/AiRoutingActivity.kt"); then
    fail "P7/247 the AI Routing page makes a network request; its prices are the baked ones and the page must open with no third party involved"
else
    pass "P7/247 the AI Routing page makes no request"
fi
if grep -q 'android.permission.INTERNET' "$MANIFEST"; then
    fail "P7/247 the manifest declares INTERNET itself; the permission comes with libs:devtools, and a second declaration here is a second owner for it"
else
    pass "P7/247 INTERNET is not declared by this manifest"
fi

# ── P8  the owner's own screen, character for character ────────────────────
#
# Transcribed from the screenshot he pasted off his Spanish phone. Not "a
# Spanish string exists" — THESE sentences, exactly, in values-es.

ES="$RES/values-es/strings.xml"
check_es() {  # check_es <resource name> <exact Spanish>
    if grep -qF "<string name=\"$1\">$2</string>" "$ES"; then
        pass "P8 $1 reads exactly: $2"
    else
        fail "P8 $1 does not read exactly what is on the owner's phone: $2"
    fi
}
check_es settings_screen_enhance      "Mejoras de texto"
check_es enhance_scope_title          "Qué se mejora"
check_es enhance_scope_auto           "La selección o, si no hay nada seleccionado, todo el cuadro"
check_es enhance_style_title          "Estilo de mejora"
check_es enhance_tone_title           "Tono"
check_es enhance_length_title         "Extensión"
check_es enhance_language_title       "Idioma del resultado"
check_es enhance_toolbar_title        "Mostrar en la barra de herramientas"
check_es enhance_toolbar_summary      "Tecla de varita mágica en la barra de herramientas: púlsala para mejorar la selección o todo el campo"
check_es enhance_prompt_title         "La instrucción que se envía"
check_es enhance_prompt_summary       "Exactamente lo que los ajustes de arriba le piden al modelo. Solo lectura."
check_es enhance_test_title           "Pruébalo"
check_es settings_screen_translation  "Traducción"
check_es translate_category_behavior  "Barra de traducción"
check_es translate_default_target_title "Idioma de destino predeterminado"
check_es translate_default_target_active "Idioma del teclado activo"
check_es translate_auto_detect_title  "Detectar automáticamente el idioma de origen"
check_es translate_auto_detect_summary "Desactivado: el idioma de origen es el del teclado activo. La detección recurre a él de todos modos cuando el texto es demasiado corto."
check_es translate_apply_mode_title   "Intro o acción principal"
check_es translate_apply_insert       "Insertar en el cursor"
check_es translate_live_commit_title  "Escribir directamente en el campo"
check_es settings_screen_grammar      "Revisión gramatical"
check_es grammar_mode_title           "Modo de gramática"
check_es grammar_mode_remote          "Remoto"
check_es grammar_category_fixes       "Correcciones"
check_es grammar_fix_capitalize_i     'Poner en mayúscula la \"i\" inglesa'
check_es grammar_fix_sentence_caps    "Poner en mayúscula el inicio de la frase"
check_es grammar_fix_repeated_words   "Eliminar las palabras repetidas"
check_es grammar_remote_url_title     "URL del servidor de LanguageTool"
check_es grammar_pt_variant_title     "Variante del portugués"
check_es grammar_pt_variant_pt        "Europeo (pt-PT)"
check_es settings_screen_ai_routing   "Enrutamiento de modelos de IA"

# NO ENGLISH LABEL MAY REACH HIS SPANISH PHONE: every string this application
# declares must have a Spanish twin. 1_cicd's i18n guard requires this too; it is
# repeated here so the failure names the page rather than the repository.
python3 - "$RES" <<'PYEOF'
import sys, os, xml.etree.ElementTree as ET
res = sys.argv[1]
def keys(d):
    return {s.get("name") for s in ET.parse(os.path.join(res, d, "strings.xml")).getroot().findall("string")}
en, es = keys("values"), keys("values-es")
missing = sorted(en - es)
if missing:
    print("FAIL   P8 %d string(s) have no Spanish and would draw in English on his phone: %s" % (len(missing), ", ".join(missing)))
    sys.exit(1)
extra = sorted(es - en)
if extra:
    print("FAIL   P8 values-es carries %d string(s) values/ does not: %s" % (len(extra), ", ".join(extra)))
    sys.exit(1)
print("ok     P8 all %d strings exist in both locales" % len(en))
PYEOF
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── P10  every string is one aapt will actually accept ─────────────────────
#
# THIS CHECK EXISTS BECAUSE IT ALREADY HAPPENED. Run 34590327712 failed at
# :app:mergeDebugResources with "Invalid unicode escape sequence in string" on
# enhance_toolbar_writer_note and translate_writer_note: two apostrophes that
# should have been \\' and were written as a bare '. The container that maintains
# this application has no Android SDK, so a resource file that XML-parses is the
# most any local check could say — and XML is perfectly happy with a bare
# apostrophe. aapt is not, and the first thing that noticed was a 40-minute CI
# run that shipped nothing.
#
# An apostrophe or a double quote inside a string resource must be escaped, and a
# backslash must introduce an escape aapt knows. Checked in BOTH locales, because
# Spanish is where apostrophes and quotes actually get typed.

python3 - "$RES" <<'PYEOF'
import io, os, re, sys
res = sys.argv[1]
# The escapes aapt accepts. \\uXXXX needs four hex digits after it, which is the
# error the failing run actually reported.
SIMPLE = set("\\'\"nt@?#\\\\")
bad = 0
checked = 0
for d in ("values", "values-es"):
    path = os.path.join(res, d, "strings.xml")
    body = io.open(path, encoding="utf-8").read()
    for m in re.finditer(r'<string name="([^"]+)">(.*?)</string>', body, re.S):
        name, text = m.group(1), m.group(2)
        checked += 1
        i = 0
        while i < len(text):
            c = text[i]
            if c == "\\":
                nxt = text[i + 1] if i + 1 < len(text) else ""
                if nxt == "u":
                    if not re.match(r"[0-9a-fA-F]{4}", text[i + 2:i + 6]):
                        print("FAIL   P10 %s/%s: \\u is not followed by four hex digits — aapt calls this "
                              "an invalid unicode escape and fails the resource merge" % (d, name))
                        bad += 1
                elif nxt not in SIMPLE:
                    print("FAIL   P10 %s/%s: \\%s is not an escape aapt knows" % (d, name, nxt or "<end>"))
                    bad += 1
                i += 2
                continue
            if c in ("'", '"'):
                print("FAIL   P10 %s/%s: a bare %s inside a string resource. aapt refuses it; XML does not, "
                      "so nothing but a real build would have caught this. Write \\%s."
                      % (d, name, c, c))
                bad += 1
            i += 1
if bad:
    sys.exit(1)
print("ok     P10 all %d string resources in both locales are aapt-safe" % checked)
PYEOF
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

# ── P9  the defaults are the ones that were reported ───────────────────────

check_default() {  # check_default <constant> <value>
    if grep -qF "const val $1 = $2" <(code "$SRC/WriterPrefs.kt"); then
        pass "P9 $1 = $2"
    else
        fail "P9 $1 is not $2 — the page a new install shows is not the page that was described"
    fi
}
check_default DEFAULT_ENHANCE_SCOPE             "SCOPE_AUTO"
check_default DEFAULT_ENHANCE_TOOLBAR_KEY       "true"
check_default DEFAULT_TRANSLATE_AUTO_DETECT     "true"
check_default DEFAULT_TRANSLATE_APPLY_MODE      "APPLY_INSERT"
check_default DEFAULT_TRANSLATE_LIVE_COMMIT     "true"
check_default DEFAULT_GRAMMAR_MODE              "GRAMMAR_AI"
check_default DEFAULT_GRAMMAR_FIX_CAPITALIZE_I  "true"
check_default DEFAULT_GRAMMAR_FIX_SENTENCE_CAPS "true"
check_default DEFAULT_GRAMMAR_FIX_REPEATED_WORDS "true"
check_default DEFAULT_GRAMMAR_PT_VARIANT        "\"pt-PT\""

# The registry defaults the Enhance page's own menus start at, read from the
# registry rather than restated, so this cannot drift from what the app loads.
python3 - "$BUILD_JSON" <<'PYEOF'
import json, sys
w = json.load(open(sys.argv[1], encoding="utf-8"))["writer_ai"]
want = {"default_style": "clarity", "default_tone": "keep", "default_length": "keep", "default_language": "keep"}
bad = 0
for k, v in want.items():
    got = w.get(k)
    if got != v:
        print("FAIL   P9 writer_ai.%s is %r, expected %r" % (k, got, v)); bad += 1
    else:
        print("ok     P9 writer_ai.%s = %s" % (k, v))
# THE PREVIEW MUST BE A FUNCTION OF THE SETTINGS, proved on the registry the app
# reads: composing with two different tones must produce two different prompts.
# If this can ever be equal, a hardcoded preview would be indistinguishable.
def compose(tone):
    parts = [w["rewrite_preamble"], w["styles"][w["default_style"]].get("prompt", ""),
             w["tones"][tone].get("prompt", ""), w["lengths"][w["default_length"]].get("prompt", ""),
             w["languages"][w["default_language"]].get("prompt", "")]
    return " ".join(p for p in parts if p.strip())
if compose("keep") == compose("formal"):
    print("FAIL   P9 changing the tone does not change the composed prompt; the preview could be a literal and nothing would notice"); bad += 1
else:
    print("ok     P9 changing the tone changes the composed prompt, so the preview has something to show")
sys.exit(1 if bad else 0)
PYEOF
[ $? -eq 0 ] || FAILURES=$((FAILURES + 1))

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "PASS   cloud-writer draws the keyboard's four pages and owns every value on them"
    exit 0
fi
echo "FAILED $FAILURES assertion(s)"
exit 1
