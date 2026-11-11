#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Writer routes four tools, serves none of them, and holds   ║
# ║ no credential                                                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. cloud-writer is the application the owner asked for, and it
# is the FIRST entry in TextTools.SERVICE_PACKAGES — the ordered preference
# every consumer in this fleet resolves the text tools against. That means one
# element in one manifest decides whether this application is a peer that asks
# for a rewrite or the application that owns every consumer's Enhance, Summary,
# Translate and Grammar at once. Nothing warns about crossing that line: the
# takeover is silent, instant, and total.
#
# Every assertion below guards a way this application can be silently turned
# into something worse than itself.
#
#   W1  IT DOES NOT PUBLISH THE ITextTools ACTION. The moment it does, every
#       consumer rebinds here at its next rebind. This build cannot honour that
#       — the owner's provider key lives in cloud-keyboard's sandbox and no
#       install moves it — so serving would answer "No API key" to four tools
#       that work today, having first taken them from the peer that could still
#       do them. Read as XML, never grepped: this application's own manifest
#       comment NAMES that action several times explaining why it is absent, so
#       a text search would match its own reasoning and report a hijack.
#   W2  each tool reaches the engine it claims, and the three that use a model
#       resolve THEIR OWN. A crossed pair bills a translation to the language
#       model or sends a rewrite through a translator, and the reply does not
#       say which one answered. A per-tool model that resolved the same tool
#       three times would make the owner's whole request decorative, and no
#       screen would show it.
#   W3  no silent exit from a run. A tap that does nothing and says nothing is
#       indistinguishable from a crash, a missing permission and a dead peer.
#   W4  NO CREDENTIAL EVER LANDS HERE. This application names a provider and a
#       model; the peer spends the key. A revealAiKey call or a token-shaped
#       preference key would put a second copy of the owner's credential on the
#       device, which is the one thing the binder design exists to prevent.
#   W5  Grammar Check refuses rather than falls back. Its prompt is one named
#       registry style; falling back to the default style would send "improve
#       this text" under a button labelled Grammar Check.
#   W6  the registry is internally consistent — every provider's default_model
#       is a model that provider actually lists. A default nothing resolves is a
#       route that fails mid-run with no screen having said so.
#   W7  the application id is the one the shared address list reserves. If they
#       differ, the day this application does serve, nothing will resolve it.
#   W8  it is registered in the Constellation store's derived registry. Missing
#       there is an APK the owner cannot find from his phone.
#
# The assertions are STRUCTURAL — which call sits inside which branch — because
# that structure IS the property. A wording check would pass over a rewrite that
# puts the defect straight back.
#
# No ripgrep, deliberately: four testers in this repository once passed only
# because ripgrep was absent and their call failed open. awk, grep and python3
# only, and a missing file is FATAL rather than an empty read that every
# assertion agrees with.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"

APP="$ROOT/ac_cloud-writer"
SRC="$APP/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
TOOLS="$SRC/WriterTools.kt"
PREFS="$SRC/WriterPrefs.kt"
REGISTRY="$SRC/WriterRegistry.kt"
BUILD_JSON="$APP/build.json"
ADDRESSES="$ROOT/ab_cloud-libs-shared/libs/text-tools/src/main/java/com/diegonmarcos/superapp/texttools/TextTools.kt"
FLEET="$ROOT/aa_cloud-superapp/data/constellation-fleet.json"

WRITER_PKG="com.diegonmarcos.cloudwriter"
ACTION="com.diegonmarcos.superapp.texttools.ITextTools"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

for f in "$MANIFEST" "$TOOLS" "$PREFS" "$REGISTRY" "$BUILD_JSON" "$ADDRESSES" "$FLEET"; do
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

# branch <TOOL> — one arm of the `when (tool)` in runTool, read from stdin.
# Bounded by the NEXT arm rather than by braces, because an arm is a call
# expression and has none of its own; unbounded, every "is X in this arm" would
# be answered by all four arms at once.
branch() {
    awk -v tool="$1" '
        index($0, "WriterTool." tool " ->") { on = 1; print; next }
        on && /WriterTool\.[A-Z]+ ->/ { exit }
        on { print }
    '
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

echo "── Cloud Writer: what it routes, what it refuses to serve, what it never holds ──"

# ── W1 ── it must not publish the ITextTools action ───────────────────────
#
# XML, not text. This manifest's own comment names the action repeatedly while
# explaining why the service is absent, so grep would match the explanation and
# report the exact failure it was written to prevent.
W1="$(python3 - "$MANIFEST" "$ACTION" <<'PYEOF'
import sys, xml.etree.ElementTree as ET
NS = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
action = sys.argv[2]
# #800: ListenService (the microphone foreground service) is a <service> too. What W1 forbids is a
# service that PUBLISHES the ITextTools action, so a service is read by its intent filters.
services = [s for s in root.findall(".//service")
            if any(a.get(NS + "name") == action for a in s.findall("./intent-filter/action"))]
publishers = [a for a in root.findall(".//action") if a.get(NS + "name") == action]
if services:
    print("SERVICE " + ",".join(s.get(NS + "name", "?") for s in services))
elif publishers:
    print("ACTION")
else:
    print("CLEAN")
PYEOF
)"
case "$W1" in
    CLEAN)
        pass "W1 cloud-writer publishes no ITextTools service — the keyboard keeps serving and nothing is taken from it"
        ;;
    SERVICE*)
        fail "W1 cloud-writer's manifest declares a service (${W1#SERVICE }). If it publishes $ACTION every consumer rebinds here at its next rebind, and this build holds no provider key — four working tools would start answering 'No API key'. Serving needs all four preconditions in build.json::_doc_what_serving_needs first."
        ;;
    ACTION)
        fail "W1 cloud-writer's manifest publishes $ACTION outside a <service>. See build.json::_doc_what_serving_needs."
        ;;
    *)
        fail "W1 could not read $MANIFEST as XML — the check has no subject and proves nothing"
        ;;
esac

# ── W2 ── each tool reaches its own engine, with its own model ────────────
ROUTE="$(body "$TOOLS" 'private fun runTool(')"
if check_body "$ROUTE" "W2 runTool"; then
    # TRANSLATE moved from client.translate to client.enhanceWith in the same commit that gave it
    # a model of its own (task 296 item 1, "missing the ootion to select the model that would do
    # only translation"). It had to move: client.translate reaches the serving application's
    # translation LIBRARY, and a library takes no model, so a picker in front of it would have
    # been a control that changes nothing — which is what the old form of this check existed to
    # forbid. The prohibition is not dropped, it is relocated: see the prompt check below, which
    # is now the thing standing between Translate and a rewrite.
    # #800: TRANSLATE moved again, onto the Translation ROUTE (WriterRoutes.translate: an OpenRouter
    # model with the fleet Account's token, falling back to ML Kit through libs:translate). Its
    # prompt check is below; the three rewrite-engine tools keep their binder calls.
    for pair in "ENHANCE enhanceWith" "GRAMMAR enhanceWith" "SUMMARY summariseWith"; do
        tool="${pair%% *}"; want="${pair##* }"
        arm="$(branch "$tool" <<<"$ROUTE")"
        if [ -z "$arm" ]; then
            fail "W2 the $tool arm of runTool could not be read — the when() has been reshaped and this check has no subject"
            continue
        fi
        if grep -q "client\.$want(" <<<"$arm"; then
            pass "W2 $tool routes to client.$want"
        else
            fail "W2 $tool does not call client.$want — a crossed route bills the wrong engine and the reply cannot be read back to tell"
        fi
    done

    # The three model-using arms must each resolve THEIR OWN tool. Two arms
    # naming one tool is the per-tool model quietly collapsing back into one,
    # and nothing on screen would show it: both pickers would still draw.
    for tool in ENHANCE GRAMMAR SUMMARY; do
        arm="$(branch "$tool" <<<"$ROUTE")"
        [ -n "$arm" ] || continue
        if ! grep -q "WriterPrefs\.modelFor(app, WriterTool\.$tool," <<<"$arm"; then
            fail "W2 the $tool arm does not resolve WriterPrefs.modelFor(..., WriterTool.$tool, ...) — this tool has no model of its own and the owner's per-tool choice does nothing for it"
            continue
        fi
        others=0
        for other in ENHANCE GRAMMAR SUMMARY; do
            [ "$other" = "$tool" ] && continue
            grep -q "WriterTool\.$other," <<<"$arm" && others=1
        done
        if [ "$others" = "0" ]; then
            pass "W2 $tool resolves its own model and no other tool's"
        else
            fail "W2 the $tool arm names another tool's model — two tools sharing one model choice makes the per-tool section decorative"
        fi
    done

    # TRANSLATE shares enhanceWith with two other tools, so the ONLY thing telling the model it is
    # translating rather than rewriting is the prompt. Handed WriterPrefs.enhancePrompt, this arm
    # would fix grammar, change the tone and resize the text on its way past — the four option rows
    # under the main box all feed that prompt — and the owner would get back an improved text in
    # the same language he typed. So the arm must name translatePrompt and must NOT name
    # enhancePrompt. This is the prohibition the old "takes no model" check used to carry.
    arm="$(branch TRANSLATE <<<"$ROUTE")"
    XLATE="$(body "$SRC/WriterRoutes.kt" 'fun translate(context: Context')"
    if [ -n "$arm" ] && grep -q "WriterRoutes.translate(" <<<"$arm" && grep -q "WriterRegistry.translatePrompt(" <<<"$XLATE" \
        && ! grep -qE "enhancePrompt\(|client\.enhanceWith\(" <<<"$arm$XLATE"; then
        pass "W2 TRANSLATE runs on the Translation route and sends translatePrompt, not enhancePrompt — it translates instead of rewriting"
    else
        fail "W2 the TRANSLATE arm does not send translatePrompt through WriterRoutes.translate (or sends enhancePrompt): without its own prompt Translate becomes a rewrite in the original language"
    fi
fi

# ── W3 ── no silent exit from a run ───────────────────────────────────────
RUN="$(body "$TOOLS" 'fun run(tool: WriterTool')"
if check_body "$RUN" "W3 run()"; then
    silent="$(awk '
        /^[[:space:]]*return[[:space:]]*$/ { if (prev !~ /onDone\(/) print NR ": " $0 }
        { prev = $0 }
    ' <<<"$RUN")"
    returns="$(grep -c '^[[:space:]]*return[[:space:]]*$' <<<"$RUN")"
    if [ "$returns" -lt 3 ]; then
        fail "W3 run() has $returns early returns — fewer than the blank input, the busy runner and the unavailable tool, so this check has lost its subjects"
    elif [ -z "$silent" ]; then
        pass "W3 every early return in run() is preceded by onDone( — no tap leaves without a sentence ($returns checked)"
    else
        fail "W3 run() returns without reporting: $silent"
    fi
fi

# ── W4 ── the Account token: read for one request, never kept ─────────────
#
# #800 the MODEL route of speech and translation spends the fleet Account's OpenRouter key, read
# through revealAiKey — the one binder method that emits a plaintext credential. It has exactly
# one reader here, WriterRoutes.accountToken, and what it returns is never stored or logged.
READERS="$(grep -rl 'revealAiKey' "$SRC" 2>/dev/null | sed "s|$SRC/||" | sort | tr '\n' ' ')"
if [ "$READERS" = "WriterRoutes.kt " ]; then
    pass "W4 revealAiKey has one reader, WriterRoutes.kt"
else
    fail "W4 revealAiKey is called from '${READERS:-nowhere}' — the plaintext credential must have exactly one reader, WriterRoutes.accountToken"
fi
ROUTES_CODE="$(sed -e 's,^[[:space:]]*//.*,,' -e 's,^[[:space:]]*\*.*,,' "$SRC/WriterRoutes.kt" 2>/dev/null)"
if grep -qE 'Log\.|println\(' <<<"$ROUTES_CODE" || grep -qiE 'put[A-Za-z]*\([^)]*token' <<<"$ROUTES_CODE"; then
    fail "W4 WriterRoutes logs or stores something token-shaped — the Account key is read for one request and dropped"
else
    pass "W4 WriterRoutes neither logs nor stores the token"
fi

KEYS="$(grep -o 'const val KEY_[A-Z_]*' "$PREFS" | awk '{print $3}')"
if [ -z "$KEYS" ]; then
    fail "W4 no preference key constants found in WriterPrefs.kt — the naming has changed and this check reads nothing"
elif grep -qiE 'token|api_?key|secret|credential' <<<"$KEYS"; then
    fail "W4 WriterPrefs declares a credential-shaped key: $(printf '%s ' $KEYS) — a second copy of the owner's provider key on the device is what the binder design exists to prevent"
else
    pass "W4 WriterPrefs declares no token, key or secret slot ($(printf '%s\n' "$KEYS" | wc -l) keys checked)"
fi

# ── W5 ── Grammar Check refuses rather than falls back ────────────────────
GRAMMAR="$(body "$REGISTRY" 'fun grammarPrompt()')"
if check_body "$GRAMMAR" "W5 grammarPrompt()"; then
    if grep -q 'grammarStyle ?: return null' <<<"$GRAMMAR"; then
        pass "W5 grammarPrompt() answers null rather than falling back to another style"
    else
        fail "W5 grammarPrompt() no longer returns null when the grammar style is absent — a fallback sends 'improve this text' under a button labelled Grammar Check"
    fi
fi
if grep -q 'WriterRegistry.grammarPrompt() == null' "$TOOLS"; then
    pass "W5 run() refuses Grammar Check when its prompt is missing, before anything is spent"
else
    fail "W5 run() no longer checks grammarPrompt() for null — the tool would send an empty system prompt and the model would answer the text instead of correcting it"
fi

# ── W6/W7/W8 ── the data behind all of the above ──────────────────────────
python3 - "$BUILD_JSON" "$ADDRESSES" "$FLEET" "$WRITER_PKG" <<'PYEOF'
import json, re, sys

build_json, addresses, fleet_path, writer_pkg = sys.argv[1:5]
bj = json.load(open(build_json))
failures = 0

def ok(m):  print("ok     " + m)
def bad(m):
    global failures
    print("FAIL   " + m); failures += 1

# W6 — the registry has to resolve. A default_model the provider does not list
# is a route that fails at call time, mid-run, with a progress line on screen.
registry = bj.get("writer_ai") or {}
providers = registry.get("providers") or {}
if not providers:
    bad("W6 build.json::writer_ai.providers is empty — the model pickers would draw nothing and every run would fall back to a provider that is not there")
else:
    broken = []
    for pid, p in providers.items():
        if pid.startswith("_"):
            continue
        ids = [m.get("id") for m in p.get("models", [])]
        if p.get("default_model") not in ids:
            broken.append("%s: default_model %r is not in its own %d model rows" % (pid, p.get("default_model"), len(ids)))
    if broken:
        bad("W6 " + "; ".join(broken))
    else:
        ok("W6 every provider's default_model is one of its own models (%d providers)" % len(providers))

# W6b — Grammar Check is one named style and nothing else, so the tool is only
# honest while the registry carries it. Without this the button ships as a
# control that always refuses, which is worse than not offering it.
styles = registry.get("styles") or {}
if "grammar" in styles:
    ok("W6 the registry carries the `grammar` style Grammar Check sends")
else:
    bad("W6 build.json::writer_ai.styles has no `grammar` entry — Grammar Check would refuse every run, so the button must not be offered")

# W7 — the shared address list reserves exactly one string for this
# application. If build.json says something else, the day cloud-writer serves,
# resolution will walk past it and land on the keyboard for ever.
declared = (bj.get("android") or {}).get("application_id")
listed = re.search(r"val SERVICE_PACKAGES\s*=\s*listOf\((.*?)\)", open(addresses).read(), re.S)
if not listed:
    bad("W7 could not read SERVICE_PACKAGES out of %s — this check has no subject" % addresses)
elif declared == writer_pkg and writer_pkg in re.findall(r'"([^"]+)"', listed.group(1)):
    ok("W7 application_id %s is the address TextTools.SERVICE_PACKAGES reserves" % declared)
else:
    bad("W7 application_id is %r but TextTools.SERVICE_PACKAGES reserves %r — they must match or nothing will ever resolve this application" % (declared, writer_pkg))

# W8 — the Constellation store's registry is DERIVED from these build.json
# fields, so an entry missing here means the derived file was never
# regenerated, and the owner cannot find the APK from his phone.
fleet = json.load(open(fleet_path))
apps = fleet if isinstance(fleet, list) else (fleet.get("apps") or [])
entry = next((a for a in apps if a.get("package") == writer_pkg), None)
asset = ((bj.get("release") or {}).get("gh_release") or {}).get("asset_name")
if entry is None:
    bad("W8 constellation-fleet.json carries no entry for %s — run aa_cloud-superapp/data/regen.sh --constellation-only and commit it" % writer_pkg)
elif entry.get("asset") != asset:
    bad("W8 the Constellation entry offers %r but build.json publishes %r — the store would download a name the release does not carry" % (entry.get("asset"), asset))
else:
    ok("W8 the Constellation store lists %s and offers %s" % (writer_pkg, asset))

sys.exit(1 if failures else 0)
PYEOF
[ "$?" = "0" ] || FAILURES=$((FAILURES + 1))

echo "── $FAILURES failed ──"
[ "$FAILURES" = "0" ]
