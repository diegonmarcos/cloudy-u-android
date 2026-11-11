#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Cloud Writer #800: the status probe, routes, Listen, documents   ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Static assertions over the source (no JDK, no SDK, no gradle). The pure
# logic behind them — routing and fallback, the segmenter, the OpenRouter
# wire, the document store, the markdown tools — is the plain-JVM :core
# module, unit-tested and PIT-mutated in the JVM step; this file checks the
# Android wiring around it, which no JVM suite can see.
#
# test-cloud-writer-routes-not-vacuous.sh plants one defect per assertion in
# a copy and requires the matching line here to go red.
#
#   S1  the status probe waits for the ITextTools bind before reading the
#       snapshot (the #800 false "older than the contract")
#   S2  each repairable state names the serving app
#   S3  the status card offers the one-tap Store repair
#   S4  build.json::update is complete
#   R1  writer_routes declares model-first routes whose default models can do the job
#       (no decision model), the on-device voice engine and the debug group
#   R2  translation and speech both decide through core Routing (model → on-device)
#   R3  Listen streams every frame to the on-device engine and segments for the model
#   R4  the screen names the route that answered
#   R5  /api/writer/{listen/status,translate,route} are registered, by a private provider
#   R6  Listen survives the screen off: a microphone foreground service and its permissions
#   R7  Configs > Routes is a private page the home screen opens
#   R8  no debug reply can carry the Account token
#   D1  documents are core DocStore files, listed, searched, created, saved, deleted
#   D2  the editor draws Markdown rich over the SAME characters (identity offsets)
#   D3  the formatting toolbar is core's Markdown actions
#   D4  the outline lists core headings and jumps to them
#   D5  find counts and steps through core matches, wrapping
#   D6  share (ACTION_SEND) and export (.md and .txt through the document picker)
#   D7  the theme is System / Light / Dark and every screen follows it
#   D8  Listen writes at the caret while the editor shows, to the file once it stops
#   D9  a tool's answer replaces exactly the range it read
#   D10 shared or PROCESS_TEXT text becomes a new document
#   D11 the fleet UI kit draws inside this app's theme

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")"
APP="$ROOT/ac_cloud-writer"
SRC="$APP/../ab_cloud-libs-shared/libs/writer-ui/src/main/java/com/diegonmarcos/cloudwriter"
RES="$APP/../ab_cloud-libs-shared/libs/writer-ui/src/main/res"

FAILURES=0
pass() { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# Comments dropped: prose may NAME a call the code does not make.
code() { sed -e 's,^[[:space:]]*//.*,,' -e 's,^[[:space:]]*\*.*,,' -e 's,^[[:space:]]*/\*.*,,' "$@"; }

for f in "$SRC/ServingApp.kt" "$SRC/WriterSurface.kt" "$APP/build.json" "$RES/values/strings.xml"; do
    [ -f "$f" ] || { echo "FAIL   $f is missing — every assertion below would read nothing and pass"; exit 1; }
done

SERVING="$(code "$SRC/ServingApp.kt")"
MAINACT="$(code "$SRC"/*.kt)"

# ── S1 ── wait for the bind, THEN ask ─────────────────────────────────────
PROBE="$(awk '/fun probe\(/,/^        }$/' <<<"$SERVING")"
if [ -z "$PROBE" ]; then
    fail "S1 ServingApp.probe could not be read — the check has no subject"
else
    wait_at="$(grep -n 'awaitBound(' <<<"$PROBE" | head -1 | cut -d: -f1)"
    ask_at="$(grep -n 'aiRoutingApp()' <<<"$PROBE" | head -1 | cut -d: -f1)"
    if [ -n "$wait_at" ] && [ -n "$ask_at" ] && [ "$wait_at" -lt "$ask_at" ]; then
        pass "S1 the probe waits for the bind before it reads aiRoutingSnapshot"
    else
        fail "S1 the probe reads aiRoutingSnapshot without first waiting for the bind — a binder that has not connected yet answers null and a current peer is reported as older than the contract"
    fi
fi
if grep -q 'while (!client.isConnected())' <<<"$MAINACT" && grep -q 'Thread.sleep(' <<<"$MAINACT"; then
    pass "S1 awaitBound polls the binder until the deadline"
else
    fail "S1 awaitBound no longer polls client.isConnected() — the wait is gone and the race is back"
fi

# ── S2 ── every repairable state names the app ────────────────────────────
python3 - "$RES/values/strings.xml" "$RES/values-es/strings.xml" <<'PY' || FAILURES=$((FAILURES + 1))
import re, sys
bad = 0
for path in sys.argv[1:]:
    s = open(path, encoding="utf-8").read()
    for key in ("status_serving_app_too_old", "status_serving_app_silent"):
        m = re.search(r'<string name="%s">(.*?)</string>' % key, s)
        if not m:
            print("FAIL   S2 %s has no %s" % (path, key)); bad += 1
        elif "%1$s" not in m.group(1):
            print("FAIL   S2 %s in %s does not name the serving app (%%1$s) — the owner is told to update 'it' without being told which app" % (key, path)); bad += 1
if not bad:
    print("ok     S2 the too-old and silent states name the serving app in both languages")
sys.exit(1 if bad else 0)
PY
if grep -q 'ServingApp.State.TOO_OLD -> getString' <<<"$MAINACT" && grep -q 'ServingApp.State.SILENT -> getString' <<<"$MAINACT"; then
    pass "S2 the status line distinguishes silent from too old"
else
    fail "S2 the status line no longer separates a silent peer from a too-old one — one is a restart, the other an update"
fi

# ── S3 ── one tap to the Store ────────────────────────────────────────────
if grep -q 'ServingApp.openStore(' <<<"$MAINACT" && grep -q 'storeRepair.value = serving.needsStore' <<<"$MAINACT"; then
    pass "S3 a repairable status offers the one-tap Store button"
else
    fail "S3 the status card no longer offers the Store repair for a missing, silent or too-old serving app"
fi

# ── S4 ── the Store target is declared ────────────────────────────────────
python3 - "$APP/build.json" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
u = json.load(open(sys.argv[1])).get("update") or {}
need = ["store_package", "store_activity", "store_extras", "fallback_url", "bind_wait_ms"]
missing = [k for k in need if not u.get(k)]
if missing:
    print("FAIL   S4 build.json::update lacks %s — the status card's Store tap has nowhere to go" % ", ".join(missing))
    sys.exit(1)
if not str(u["fallback_url"]).startswith("https://"):
    print("FAIL   S4 build.json::update.fallback_url is not https"); sys.exit(1)
print("ok     S4 build.json::update declares the Store target, the fallback and the bind wait")
PY

# ── R1 ── the declaration ─────────────────────────────────────────────────
python3 - "$APP/build.json" <<'PY' || FAILURES=$((FAILURES + 1))
import json, sys
b = json.load(open(sys.argv[1]))
r = b.get("writer_routes") or {}
bad = 0
for f in ("speech", "translation"):
    blk = r.get(f) or {}
    if blk.get("default_route") != "model":
        print("FAIL   R1 writer_routes.%s.default_route is %r — the owner's default is the model, with on-device as the fallback" % (f, blk.get("default_route"))); bad += 1
    m = str(blk.get("default_model", ""))
    if not m or "jev" in m or m.startswith("typesafe/"):
        print("FAIL   R1 writer_routes.%s.default_model %r is empty or a decision model — decision models cannot transcribe or translate" % (f, m)); bad += 1
ve = r.get("voice_engine") or {}
if not ve.get("package") or not ve.get("action"):
    print("FAIL   R1 writer_routes.voice_engine lacks package/action — the on-device speech route has nothing to bind"); bad += 1
if (r.get("debug_api") or {}).get("group") != "writer":
    print("FAIL   R1 writer_routes.debug_api.group is not 'writer' — the brief's /api/writer/* routes would live elsewhere"); bad += 1
o = r.get("openrouter") or {}
if not str(o.get("chat_url", "")).startswith("https://") or not str(o.get("models_url", "")).startswith("https://"):
    print("FAIL   R1 writer_routes.openrouter lacks https chat_url/models_url"); bad += 1
langs = (b.get("writer_ai") or {}).get("languages") or {}
untagged = [k for k, v in langs.items() if k != "keep" and not (isinstance(v, dict) and v.get("tag"))]
if untagged:
    print("FAIL   R1 writer_ai.languages rows without an on-device tag: %s" % ", ".join(untagged)); bad += 1
if not bad:
    print("ok     R1 writer_routes: model-first, capable default models, the voice engine, /api/writer, every language tagged")
sys.exit(1 if bad else 0)
PY

# ── R2 ── one decision, in core ───────────────────────────────────────────
ROUTES_KT="$(code "$SRC/WriterRoutes.kt")"
XL="$(awk '/fun translate\(context: Context/,/^    }$/' <<<"$ROUTES_KT")"
TR="$(awk '/fun transcribe\(context: Context/,/^    }$/' <<<"$ROUTES_KT")"
if grep -q 'Routing.run(' <<<"$XL" && grep -q 'Routing.run(' <<<"$TR" \
    && grep -q 'OpenRouter.translate(' <<<"$XL" && grep -q 'OpenRouter.transcribe(' <<<"$TR"; then
    pass "R2 translation and speech both decide through core Routing, with the model as the first route"
else
    fail "R2 translate() or transcribe() no longer goes through Routing.run with its OpenRouter call — the fallback and the answering route would be decided ad hoc"
fi
if grep -q 'textTools(context).translate(text, tag)' <<<"$XL"; then
    pass "R2 the on-device translation is libs:translate through the serving app"
else
    fail "R2 the on-device translation route no longer calls libs:translate (ITextTools.translate)"
fi

# ── R3 ── Listen ──────────────────────────────────────────────────────────
LISTEN="$(code "$SRC/Listen.kt")"
if grep -q 'if (onDevice) v.feed(buf, n)' <<<"$LISTEN" && grep -q 'if (modelLive) segmenter.feed(buf, n)' <<<"$LISTEN"; then
    pass "R3 every frame feeds the on-device engine (live partials, fallback) and the segmenter on the model route"
else
    fail "R3 Listen no longer feeds both the on-device engine and the segmenter — the live text or the fallback is gone"
fi
if grep -q 'WriterRoutes.transcribe(app, pcm, blocker)' <<<"$LISTEN" && grep -q 'WriterRoutes.translate(app, heardText, target)' <<<"$LISTEN"; then
    pass "R3 segments are transcribed on the Speech route and auto-translated on the Translation route"
else
    fail "R3 Listen's segments skip the Speech route or the auto-translation"
fi

# ── R4 ── the answering route is on screen ────────────────────────────────
if grep -q 'R.string.route_fell_back' <<<"$MAINACT" && grep -q 'R.string.route_answered_by' <<<"$MAINACT" \
    && grep -q 'lastRoute = answer.route' <<<"$LISTEN"; then
    pass "R4 the Translate result and Listen's status name the route that answered"
else
    fail "R4 the route that answered is no longer shown — a fallback would be invisible"
fi

# ── R5/R6/R7 ── the manifest and the debug API ────────────────────────────
DEBUG="$(code "$APP/app/src/main/java/com/diegonmarcos/cloudwriter/WriterDebugApi.kt")"
for op in '"listen/status"' '"translate"' '"route"'; do
    if grep -q "$op ->" <<<"$DEBUG"; then pass "R5 /api/writer/${op//\"/} is served"; else fail "R5 /api/writer/${op//\"/} is not served"; fi
done
python3 - "$APP/app/src/main/AndroidManifest.xml" <<'PY' || FAILURES=$((FAILURES + 1))
import sys, xml.etree.ElementTree as ET
A = "{http://schemas.android.com/apk/res/android}"
root = ET.parse(sys.argv[1]).getroot()
app = root.find("application")
perms = {p.get(A + "name") for p in root.findall("uses-permission")}
bad = 0
prov = [p for p in app.findall("provider") if p.get(A + "name", "").endswith("WriterDebugApiProvider")]
if not prov or prov[0].get(A + "exported") != "false":
    print("FAIL   R5 WriterDebugApiProvider is missing or exported — /api/writer/* would never register, or would be reachable"); bad += 1
svc = [s for s in app.findall("service") if s.get(A + "name", "").endswith("ListenService")]
if not svc or svc[0].get(A + "foregroundServiceType") != "microphone" or svc[0].get(A + "exported") != "false":
    print("FAIL   R6 ListenService is missing, not a microphone foreground service, or exported"); bad += 1
for need in ("android.permission.RECORD_AUDIO", "android.permission.FOREGROUND_SERVICE_MICROPHONE",
             "com.diegonmarcos.cloudkeyboardlibs.BIND_ENGINE"):
    if need not in perms:
        print("FAIL   R6 the manifest does not request %s" % need); bad += 1
acts = [a for a in app.findall("activity") if a.get(A + "name", "").endswith("RoutesActivity")]
if not acts or acts[0].get(A + "exported") != "false":
    print("FAIL   R7 RoutesActivity is missing or exported"); bad += 1
if not bad:
    print("ok     R5/R6/R7 private debug provider, microphone service with its permissions, private Routes page")
sys.exit(1 if bad else 0)
PY
if grep -qF 'activityClass("RoutesActivity")' <<<"$MAINACT"; then
    pass "R7 the home screen opens Configs > Routes"
else
    fail "R7 nothing opens RoutesActivity — the route switches exist but cannot be reached"
fi

# ── R8 ── no token in a debug reply ───────────────────────────────────────
RJ="$(awk '/fun routeJson\(/,/^    }$/' <<<"$ROUTES_KT")"
SJ="$(awk '/fun statusJson\(/,/^    }$/' <<<"$LISTEN")"
if [ -n "$RJ" ] && [ -n "$SJ" ] && ! grep -qE 'accountToken|revealAiKey' <<<"$RJ$SJ$DEBUG"; then
    pass "R8 /api/writer/* replies read key presence, never the token"
else
    fail "R8 a debug reply reads the Account token (or its builder could not be found)"
fi

# ── D ── the document UI ──────────────────────────────────────────────────
MAINKT="$(code "$SRC/WriterSurface.kt")"
UIKT="$(code "$SRC/ui/DocumentsUi.kt" "$SRC/ui/Theme.kt" "$SRC/ui/WriterComponents.kt" "$APP/../ab_cloud-libs-shared/libs/writer-core/src/main/kotlin/com/diegonmarcos/cloudwriter/core/Format.kt")"
d() { # d <id> <message> <pattern>...  — every pattern must be in MainActivity/ui code
    local id="$1" msg="$2"; shift 2
    local p
    for p in "$@"; do
        if ! grep -qF -- "$p" <<<"$MAINKT$UIKT"; then fail "$id $msg (missing: $p)"; return; fi
    done
    pass "$id $msg"
}
d D1 "documents are core DocStore files under filesDir/documents" \
    'store = DocStore(ListenEngine.docsDir(this))' 'store.search(q)' 'store.create("")' 'store.write(id, field.value.text)' 'store.delete(id)'
d D2 "the editor draws Markdown rich over the same characters" \
    'Markdown.spans(v.text)' 'visualTransformation = look' 'OffsetMapping.Identity'
d D3 "the toolbar applies core's Markdown actions" \
    'Markdown.heading(text, start, end, 1)' 'Markdown.wrap(text, start, end, "**")' 'Markdown.wrap(text, start, end, "*")' 'Markdown.prefixLines(text, start, end, "- ")' 'Markdown.prefixLines(text, start, end, "> ")' 'Markdown.prefixLines(text, start, end, "- [ ] ")' 'Markdown.link(text, start, end)' 'FormatAction.GROUPS.forEachIndexed' 'a.apply(t, s, e)'
d D4 "the outline lists core headings and jumps to them" \
    'Markdown.outline(text)' 'selection = TextRange(h.offset)'
d D5 "find counts and steps through core matches, wrapping" \
    'Markdown.find(v.text, q)' '((findIndex.value + by) % n + n) % n'
d D6 "share and export as .md and .txt" \
    'Intent(Intent.ACTION_SEND)' 'CreateDocument("text/markdown")' 'CreateDocument("text/plain")' 'contentResolver.openOutputStream(uri)'
d D7 "System / Light / Dark, followed by every screen" \
    'darkTheme: Boolean = WriterTheme.isDark()' 'WriterTheme.set(activity, id)' 'LIGHT -> false' 'DARK -> true'
ONSTOP="$(awk '/fun onStop\(\)/,/^    }$/' <<<"$MAINKT")"
if grep -qF 'field.value = insertAtCaret(field.value, segment)' <<<"$MAINKT" && grep -qF 'ListenEngine.sink = null' <<<"$ONSTOP"; then
    pass "D8 Listen writes at the caret while the editor shows, to the file once it stops"
else
    fail "D8 Listen no longer inserts at the caret, or onStop leaves the editor as its sink — a stopped activity recomposes nothing and the dictation would never be saved"
fi
d D9 "a tool's answer replaces exactly the range it read" \
    'val (from, to) = rangeFor(tool, v)' 'ToolResult(tool, produced, note, from, to)' 'text.substring(0, from) + r.text + text.substring(to)'
d D10 "shared text becomes a new document" \
    'open(store.create(shared))'
d D11 "the fleet UI kit draws inside this app's theme" \
    'KitBridge {' 'list.isEmpty() -> item(key = "empty")' 'EmptyDocsIllustration()' 'CloudKitTheme('

echo "── $FAILURES failed ──"
[ "$FAILURES" = "0" ]
