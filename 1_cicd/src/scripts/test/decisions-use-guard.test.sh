#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════╗
# ║ decisions-use-guard.test — prove the #881 guard FAILS on each     ║
# ║ rule it holds, and passes on the real tree                        ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Builds a fixture from the REAL files the guard reads (its data, the engine's declaration, the contract
# module, the fleet roster, every app's build.json and the modules that call revealAiKey), requires a pass,
# then breaks one property at a time on a copy, proves the break landed, and requires a FAIL naming that
# rule. python3 and coreutils only; no network, no build.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
GUARD="$ROOT/1_cicd/src/scripts/cloud-android-decisions-use-guard.py"
DATA=1_cicd/src/data/decisions-use-guard.json
DECL=fleet-private-data/decisions.json   # #1566 fleet-private: CI materialises it
LINK=ab_cloud-libs-shared/libs/decisions-link
AIDL=$LINK/src/main/aidl/com/diegonmarcos/superapp/decisions/link/IDecisionsEngine.aidl
CLIENT=$LINK/src/main/java/com/diegonmarcos/superapp/decisions/link/DecisionsLink.kt
FLEET=aa_cloud-superapp/data/constellation-fleet.json
CALC=ac_cloud-calc
CALCSITE=$CALC/app/src/main/java/com/diegonmarcos/cloudcalc/PlantedDecisionSite.kt
MAILSITE=ac_cloud-mail/app/src/main/kotlin/PlantedMailSite.kt
for f in "$GUARD" "$ROOT/$DATA" "$ROOT/$DECL" "$ROOT/$AIDL" "$ROOT/$CLIENT" "$ROOT/$FLEET" "$ROOT/$CALC/build.json"; do
    [ -e "$f" ] || { echo "ERROR missing source: $f — this test is unrun, not passing"; exit 1; }
done

FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

stage() {
    rm -rf "$WORK/t"; mkdir -p "$WORK/t"
    (cd "$ROOT" && { printf '%s\n' "$DATA" "$DECL" "$FLEET"; ls a[acd]_*/build.json; find "$LINK" -type f -not -path '*/build/*';
        grep -rl 'revealAiKey(' --include=*.kt --include=*.java a[acd]_* ab_cloud-libs-shared/libs 2>/dev/null | grep -v '/build/';
        python3 -c "import json,sys; d=json.load(open(sys.argv[1])); [print(u['fallback_test'].split('::')[0]) for u in (d.get('image') or {}).get('uses', {}).values() if isinstance(u, dict) and u.get('fallback_test')]" "$DECL"; } | sort -u | xargs -d '\n' cp --parents -t "$WORK/t")
}
guard() { python3 "$GUARD" "$WORK/t" >"$WORK/out" 2>&1; }
sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); assert sys.argv[2] in s, 'anchor not found'; open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$WORK/t/$1" "$2" "$3"; }
js() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$WORK/t/$1" "$2"; }
plant() { mkdir -p "$(dirname "$WORK/t/$1")"; printf '%s\n' "$2" > "$WORK/t/$1"; }
# red <label> <text the FAIL line must carry>
red() {
    if guard; then fail "$1 — the guard PASSED"; return; fi
    if grep -qF "FAIL  $2" "$WORK/out"; then ok "$1"; else fail "$1 — failed, but not with $2: $(head -c 300 "$WORK/out")"; fi
}
green() { if guard; then ok "$1"; else fail "$1 — the guard FAILED: $(head -c 300 "$WORK/out")"; fi; }
SITE_OK='package x
fun f(ctx: android.content.Context) { val v = DecisionsLink.decide(ctx, "probe", state, questions) }'

stage; green "the real tree passes: $(python3 "$GUARD" "$ROOT" | tail -1 | cut -c1-70)"

# D1 — no credential on the wire
stage; sub "$AIDL" 'String call(String method, String request);' 'String call(String method, String request, String token);'
red "D1 a token parameter on the AIDL call" "D1 $AIDL"
stage; sub "$AIDL" 'String[] methods();' 'String[] methods(); String revealApiKey();'
red "D1 an AIDL method that reveals a key" "D1 $AIDL"
stage; sub "$CLIENT" 'fun decide(context: Context, use: String,' 'fun decide(context: Context, apiKey: String, use: String,'
red "D1 an apiKey parameter on the public client" "D1 $CLIENT"
stage; sub "$CLIENT" 'fun setConsent(' 'fun bearerFor('
red "D1 a public client function named like a credential" "D1 $CLIENT"
stage; printf '\ndependencies { implementation project(%s) }\n' "':libs:text-tools'" >> "$WORK/t/$LINK/build.gradle"
red "D1 the contract module depends on the module that reveals the key" "D1 $LINK/build.gradle"
stage; sub "$CLIENT" 'private fun fail(why: String)' 'private fun fail(apiKey: String, why: String)'
green "a private name is not the public surface (the guard reads only public functions)"

# D2 — every declared use validates
stage; js "$DECL" 'd["uses"]["probe"]["class"] = "advisory"'
red "D2 a class outside the vocabulary" "D2 use probe: class"
stage; js "$DECL" 'del d["uses"]["probe"]["ttl_s"]'
red "D2 a background use without a ttl" "D2 use probe: a background use declares ttl_s"
stage; js "$DECL" 'd["uses"]["probe"]["threshold"] = 0'
red "D2 a threshold of zero" "D2 use probe: threshold"
stage; js "$DECL" 'd["uses"]["probe"]["threshold"] = 1.5'
red "D2 a threshold above one" "D2 use probe: threshold"
stage; js "$DECL" 'd["uses"]["probe"]["max_calls_per_hour"] = 0'
red "D2 a use with no hourly allowance" "D2 use probe: max_calls_per_hour"
stage; js "$DECL" 'd["uses"]["probe"]["apps"] = []'
red "D2 a use no app may call" "D2 use probe: apps"
stage; js "$DECL" 'd["uses"]["probe"]["apps"] = ["com.example.notinthefleet"]'
red "D2 a use that lists an app the fleet does not have" "D2 use probe: app"
stage; js "$DECL" 'd["uses"]["probe"]["consent"] = "sometimes"'
red "D2 a consent value outside the vocabulary" "D2 use probe: consent"
stage; js "$DECL" 'd["uses"]["probe"].update({"content": "mail", "consent": "implicit"})'
red "D2 mail content with consent on by default" "D2 use probe: content mail"
stage; js "$DECL" 'd["uses"]["probe"].update({"content": "git", "consent": "implicit"})'
red "D2 git content with consent on by default" "D2 use probe: content git"
stage; js "$DECL" 'd["sensitive_content"] = ["mail"]'
red "D2 the declaration stops treating git as sensitive" "D2 the declaration's sensitive_content"

# D3 — call sites
stage; plant "$CALCSITE" "$SITE_OK"
red "D3 an app calls a use it is not listed for" "D3 $CALCSITE:2"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.decide(ctx, "planted_undeclared_use", state, q) }'
red "D3 an app calls a use nobody declared" "D3 $CALCSITE:2: use 'planted_undeclared_use' is not declared"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.outcome(ctx, USE_NAME, state, q) }'
red "D3 a call whose use is not a string literal" "D3 $CALCSITE:2"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.decide(
    ctx,
    "undeclared_multiline",
    state, q) }'
red "D3 a call split across lines is still read" "D3 $CALCSITE:2: use 'undeclared_multiline' is not declared"
stage; plant "$CALCSITE" 'package x
// DecisionsLink.decide(ctx, "commented_out", state, q)
fun f() {}'
green "a commented-out call is not a call site"
stage; js "$CALC/build.json" 'del d["android"]["application_id"]'; plant "$CALCSITE" "$SITE_OK"
red "D3 an app whose id cannot be read cannot be checked" "D3 $CALCSITE:2: cannot resolve ac_cloud-calc"

# #1799 D3 for image call sites, D2 for image uses
CANVAS=ac_cloud-canvas
CANVASSITE=$CANVAS/app/src/main/java/com/diegonmarcos/cloudcanvas/enhance/EngineRoute.kt
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, "canvas_enhance", m, p, b) }'
red "D3 an app calls an image use it is not listed for" "D3 $CALCSITE:2"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, "planted_image_use", m, p, b) }'
red "D3 an app calls an image use nobody declared" "D3 $CALCSITE:2: image use 'planted_image_use' is not declared"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, USE, m, p, b) }'
red "D3 an image call whose use is not a string literal" "D3 $CALCSITE:2"
stage; plant "$CALCSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, "probe", m, p, b) }'
red "D3 a decision use is not an image use" "D3 $CALCSITE:2: image use 'probe' is not declared"
stage; js "$DECL" 'd["image"]["uses"]["canvas_enhance"]["apps"] = []'
red "D2 an image use no app may call" "D2 image use canvas_enhance: apps"
stage; js "$DECL" 'd["image"]["uses"]["canvas_enhance"]["max_calls_per_hour"] = 0'
red "D2 an image use with no hourly allowance" "D2 image use canvas_enhance: max_calls_per_hour"
stage; js "$DECL" 'd["image"]["uses"]["canvas_enhance"].update({"content": "mail", "consent": "implicit"})'
red "D2 an image use of mail content with consent on by default" "D2 image use canvas_enhance: content mail"
stage; js "$DECL" 'd["image"]["uses"]["canvas_enhance"]["apps"] = ["com.example.notinthefleet"]'
red "D2 an image use that lists an app the fleet does not have" "D2 image use canvas_enhance: app"
stage; js "$DECL" 'del d["image"]["uses"]["canvas_enhance"]["fallback_test"]'; plant "$CANVASSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, "canvas_enhance", m, p, b) }'
red "D4 a called image use with no fallback test" "D4 use 'canvas_enhance' is called"
stage; plant "$CANVASSITE" 'package x
fun f(ctx: Context) { DecisionsLink.image(ctx, "canvas_enhance", m, p, b) }'
green "Canvas calling its declared image use passes (an image use has no class or threshold)"

# D4 — a called use proves its fallback
stage; js "$DECL" 'd["uses"]["probe"]["apps"].append("com.diegonmarcos.cloudcalc")'; plant "$CALCSITE" "$SITE_OK"
red "D4 a called use with no fallback test" "D4 use 'probe' is called"
stage; js "$DECL" 'd["uses"]["probe"]["apps"].append("com.diegonmarcos.cloudcalc"); d["uses"]["probe"]["fallback_test"] = "no/such/File.kt::Name"'; plant "$CALCSITE" "$SITE_OK"
red "D4 a fallback test file that does not exist" "D4 use 'probe': fallback_test file"
stage; js "$DECL" 'd["uses"]["probe"]["apps"].append("com.diegonmarcos.cloudcalc"); d["uses"]["probe"]["fallback_test"] = "'$CALCSITE'::NotThere"'; plant "$CALCSITE" "$SITE_OK"
red "D4 a fallback test that does not mention what it proves" "D4 use 'probe': $CALCSITE does not mention"
stage; js "$DECL" 'd["uses"]["probe"]["apps"].append("com.diegonmarcos.cloudcalc"); d["uses"]["probe"]["fallback_test"] = "'$CALCSITE'::DecisionsLink"'; plant "$CALCSITE" "$SITE_OK"
green "a called use that declares its listed app and a fallback test passes"

# D5 — nobody new holds the key
stage; plant "$MAILSITE" 'package x
fun f(c: TextToolsClient) = c.revealAiKey("openrouter")'
red "D5 mail reads the OpenRouter key" "D5 app ac_cloud-mail"
stage; plant "ab_cloud-libs-shared/libs/health/src/main/java/Planted.kt" 'package x
fun f(c: TextToolsClient) = c.revealAiKey("openrouter")'
red "D5 a new lib reads the OpenRouter key" "D5 lib health"
stage; plant "$MAILSITE" 'package x
// c.revealAiKey("openrouter") is what this app must never do
fun f() {}'
green "a comment that names the forbidden call is not a call"
# #1801 the list is empty: every former reader relays through the AI-key owner engine, so a former one reading
# the key again is a NEW reader and goes red like any other.
stage; plant "ac_cloud-calc/app/src/main/java/com/diegonmarcos/cloudcalc/decide/Planted.kt" 'package x
fun f(c: TextToolsClient) = c.revealAiKey("openrouter")'
red "D5 a former reader (Calc) that reads the key again" "D5 app ac_cloud-calc"
stage; plant "ab_cloud-libs-shared/libs/keyboard/src/main/java/Planted.kt" 'package x
fun f(c: TextToolsClient) = c.revealAiKey("openrouter")'
red "D5 a former reader lib (keyboard) that reads the key again" "D5 lib keyboard"

# D6 — not vacuous
stage; js "$DECL" 'd["uses"] = {}'
red "D6 a declaration with no use" "D6 $DECL declares no use"
stage; rm "$WORK/t/$DECL"
red "D6 no declaration at all" "D6 $DECL is missing"
stage; rm -rf "$WORK/t/$LINK/src/main/aidl"
red "D6 the contract module's wire is gone" "D6 $LINK/src/main/aidl is missing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "PASS — the decisions-use guard fails on every broken rule and passes the real tree"; else echo "FAIL — $FAILURES case(s)"; exit 1; fi
