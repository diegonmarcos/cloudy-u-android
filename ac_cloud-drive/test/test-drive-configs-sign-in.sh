#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #587 — Configs ▸ Sign in IS the fleet's sign-in (libs:auth), shared BY  ║
# ║ REFERENCE with cloud-superapp, from ONE declaration; a drive sign-in    ║
# ║ applies ONLY the sections build.json::auth.applies names                ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS. The owner asked for the SAME authentication module the
# superapp's Profile has, "as a shared lib not a copy". Gradle proves the module
# links; it cannot see that this app quietly grew a provider of its own, that the
# declaration exists twice, that the Configs tab dropped the card, or that a
# sign-in started writing a WireGuard key into a drive store. This file can.
#
#   S1  libs:auth is shared by reference: declared with a `dir` in build.json::modules,
#       in app.depends_on, linked in app/build.gradle; the SAME directory the
#       superapp links (when it sits beside); and NO copy of a lifted file exists here.
#   S2  ONE declaration: the providers live in ab_cloud-libs-shared/build.json::auth
#       and nowhere in this app; nothing in this app's Kotlin names a provider id,
#       an endpoint or a client id; the lib reads the shared file, not the app's.
#   S3  the Configs tab hosts the card: ConfigsScreen composes SignInCard, the card
#       composes the lib's SignInWays with this app's Pill, and every string it
#       names exists (test-drive-shell.sh D7 keeps them alive).
#   S4  what a sign-in yields is DECLARED (auth.applies, baked as AUTH_APPLIES_B64,
#       decoded once by Declarations.authApplies) and DISPATCHED both ways:
#       every declared section has a `when` branch, every branch is declared.
#   S5  the apply writes ONLY through the engines' stores (GitCredentialStore,
#       RcloneConfig) and never a mesh/mail/AI value; the host stores no bearer.
#   S6  the JVM test exists and reads the declaration.
#   M   mutation-proof: a provider literal, a second declaration, a dropped card,
#       an undeclared dispatch branch and a WireGuard write each turn a check RED;
#       the unmutated tree stays green.
#
# OWN-SOURCE ONLY except S1's cross-app comparison (UNVERIFIABLE when the
# superapp is not beside this app, never a silent pass). python3 and grep only.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
GRADLE="$APP/app/build.gradle"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
# #603 Configs became six declared sub-pages: the card lives on ▸ General, the page that
# inherited #579's Configs cards. The assertion is unchanged, the file it reads moved.
CONFIGS="$SRC/configs/GeneralPage.kt"
CARD="$SRC/configs/SignInCard.kt"
APPLY="$SRC/configs/DriveAuthApply.kt"
STRINGS="$APP/app/src/main/res/values/strings.xml"
JVM="$APP/app/src/test/java/com/diegonmarcos/clouddrive/configs/DriveAuthApplyTest.kt"
SHARED="$ROOT/ab_cloud-libs-shared/build.json"
LIB="$ROOT/ab_cloud-libs-shared/libs/auth"
LIB_GRADLE="$LIB/build.gradle"
LIB_SRC="$LIB/src/main/java/com/diegonmarcos/cloudlib/auth"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$BJ" "$GRADLE" "$CONFIGS" "$CARD" "$APPLY" "$STRINGS" "$JVM" "$SHARED" "$LIB_GRADLE" "$LIB_SRC/SignInUi.kt" "$LIB_SRC/SignIn.kt"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done
# Code only — whole-line comments dropped. Its output is grepped through here-strings, never
# `codeof f | grep -q` (#585b): under pipefail grep -q quits on the first match and awk dies of SIGPIPE.
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }

# ── the checks as functions of their inputs, so the mutation block can run them on copies ──

# s2 <shared build.json> <app build.json> <app src dir> : one declaration, no provider literal in the app
s2() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, os, re, sys
shared, bj, src = json.load(open(sys.argv[1])), json.load(open(sys.argv[2])), sys.argv[3]
providers = shared.get("auth", {}).get("sign_in", {}).get("providers", [])
# #1083 the five ways: the two Authelia ways, Google, GitHub (an OAuth App device flow; #641's
# GitHub App grant stays deleted) and Cloudflare Access. An EQUALITY on the ordered ids, so a
# provider quietly added or removed is reported rather than absorbed.
ids = [p.get("id") for p in providers]
if ids != ["authelia", "authelia_web", "google", "github", "cloudflare"]:
    print("    shared build.json::auth.sign_in declares %r; the five ways are authelia, authelia_web, google, github and cloudflare (#1083)" % ids); sys.exit(1)
gh = providers[3]
if gh.get("kind") != "device_flow" or not gh.get("client_id") or gh.get("client_secret"):
    print("    the github provider must be a secret-less device_flow with a client_id (an OAuth App, not #641's deleted GitHub App grant)"); sys.exit(1)
if "sign_in" in json.dumps(bj.get("ui", {})) or "providers" in json.dumps(bj.get("auth", {})):
    print("    this app's build.json ALSO declares a sign-in provider list — two declarations"); sys.exit(1)
code = ""
for d, _, fs in os.walk(src):
    for f in fs:
        if f.endswith(".kt"):
            for line in open(os.path.join(d, f), encoding="utf-8"):
                if not line.strip().startswith(("//", "*", "/*")): code += line
# A JSON field NAME read off a registry row (r.str("github"): the repos registry's own `github` key,
# #1238) is not a provider id typed into the app, so accessor arguments are not counted for the id.
keyless = re.sub(r'\b(?:str|optString|has|opt|getString)\("[^"]*"\)', "", code)
bad = []
for p in providers:
    for lit, hay in [(p.get("id"), keyless), (p.get("device_code_url"), code), (p.get("token_url"), code), (p.get("userinfo_url"), code), (p.get("client_id"), code)]:
        if lit and '"%s"' % lit in hay: bad.append(lit)
if bad: print("    provider data typed into this app's Kotlin: %s" % bad); sys.exit(1)
PYTHON
}

# s4 <build.json> <DriveAuthApply.kt> : declared sections == dispatched sections, both directions
s4() {
    python3 - "$1" "$2" <<'PYTHON'
import json, re, sys
bj = json.load(open(sys.argv[1])); kt = open(sys.argv[2], encoding="utf-8").read()
declared = sorted(k for k in bj.get("auth", {}).get("applies", {}) if not k.startswith("_"))
if not declared: print("    build.json::auth.applies declares nothing"); sys.exit(1)
if any(not bj["auth"]["applies"][k].get("key") for k in declared): print("    a declared section has no key"); sys.exit(1)
m = re.search(r"fun plan\(.*?\n    \}\n", kt, re.S)
if not m: print("    DriveAuthApply.plan not found"); sys.exit(1)
dispatched = sorted(set(re.findall(r'^\s*"([a-z_]+)" ->', m.group(0), re.M)))
if dispatched != declared: print("    declared %s vs dispatched %s" % (declared, dispatched)); sys.exit(1)
PYTHON
}

echo "── S1 libs:auth shared by reference ──"
DIR="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["modules"].get("libs:auth",{}).get("dir",""))' "$BJ")"
[ -n "$DIR" ] && pass "build.json::modules declares libs:auth with dir=$DIR" || fail "libs:auth has no dir in build.json::modules"
[ -n "$DIR" ] && [ "$(cd "$APP" && cd "$DIR" && pwd -P)" = "$(cd "$LIB" && pwd -P)" ] && pass "dir resolves to the shared shelf" || fail "libs:auth dir does not resolve to ab_cloud-libs-shared/libs/auth"
python3 -c 'import json,sys; sys.exit(0 if "libs:auth" in json.load(open(sys.argv[1]))["modules"]["app"]["depends_on"] else 1)' "$BJ" && pass "libs:auth in app.depends_on" || fail "libs:auth absent from app.depends_on"
grep -q "implementation project(':libs:auth')" "$GRADLE" && pass "linked in app/build.gradle" || fail "app/build.gradle does not link :libs:auth"
[ -d "$APP/libs/auth" ] && fail "a local libs/auth copy shadows the shared module" || pass "no local copy of the module"
for f in SignIn SignInUi UserRegistry VaultConnect VaultFile ProfileJourney DeviceGrant ConfigArtifact AuthDeclaration; do
    [ -f "$LIB_SRC/$f.kt" ] && pass "lib carries $f.kt" || fail "lib lacks $f.kt"
    grep -q . <<<"$(find "$SRC" -name "$f.kt")" && fail "$f.kt is ALSO in this app — a copy, not a reference" || true
done
SA="$ROOT/aa_cloud-superapp/build.json"
if [ -f "$SA" ]; then
    SADIR="$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["modules"].get("libs:auth",{}).get("dir",""))' "$SA")"
    [ -n "$SADIR" ] && [ "$(cd "$ROOT/aa_cloud-superapp" && cd "$SADIR" && pwd -P)" = "$(cd "$LIB" && pwd -P)" ] \
        && pass "cloud-superapp links the SAME directory" || fail "cloud-superapp does not link the same libs:auth directory"
    for f in SignIn UserRegistry VaultConnect VaultFile ProfileJourney GithubImport; do
        [ -f "$ROOT/aa_cloud-superapp/app/src/main/java/com/diegonmarcos/superapp/profile/$f.kt" ] && fail "superapp still carries its own $f.kt" || true
    done
    pass "superapp carries no lifted file of its own"
else
    echo "  UNVERIFIABLE: aa_cloud-superapp is not beside this app — the cross-app comparison did not run"
fi

echo "── S2 ONE declaration, no provider literal here ──"
s2 "$SHARED" "$BJ" "$SRC" && pass "providers live only in ab_cloud-libs-shared/build.json::auth; none typed into this app" || fail "declaration or literal rule broken (see above)"
grep -q 'file("${projectDir}/../../build.json")' "$LIB_GRADLE" && pass "the lib bakes the SHARED file, never the consuming app's" || fail "libs:auth/build.gradle does not read ../../build.json"
grep -q 'buildConfigField "String", "AUTH_B64"' "$LIB_GRADLE" && grep -q 'BuildConfig.AUTH_B64' "$LIB_SRC/AuthDeclaration.kt" && pass "one blob, decoded once by AuthDeclaration" || fail "AUTH_B64 is not baked or not read"
grep -q 'sign_in\|vault_connect' "$GRADLE" && fail "app/build.gradle bakes a sign-in or vault block of its own" || pass "app/build.gradle bakes no sign-in block"

echo "── S3 Configs hosts the card; the card hosts the lib's surface in this app's pill ──"
grep -q '^    SignInCard()$' "$CONFIGS" && pass "Configs ▸ General composes SignInCard" || fail "Configs ▸ General does not compose SignInCard"
# #1693 Cloud Account is the one app that signs in: the card draws the declared ways as LINKS to it and reads its state.
card_signin_free() { ! grep -qE 'SignInWays|SignInHost|SignInResult|requestDeviceCode|pollForToken|DeviceGrant|WebAuth' <<<"$(codeof "$1")"; }
card_signin_free "$CARD" && pass "SignInCard starts no sign-in (no SignInWays, SignInHost, device grant or web auth)" || fail "SignInCard starts a sign-in again"
grep -q 'AccountClient.openSignIn(ctx, provider)' "$CARD" && grep -q 'AccountClient.signInStatus(ctx, provider)' "$CARD" && pass "each declared way links to Cloud Account and shows the state it reports" || fail "the card does not link to Cloud Account / read its state"
grep -q 'Pill(stringResource(R.string.configs_sign_in_link' "$CARD" && pass "the links are drawn with this app's Pill" || fail "the card does not draw the links with Pill"
grep -q 'DriveAuthApply.apply(app, artifact)' "$CARD" && grep -q 'AccountClient.secret(app, "$section.$key")' "$CARD" && pass "what Cloud Account holds for the declared sections goes to DriveAuthApply, the one writer" || fail "the card does not apply Cloud Account's secrets through DriveAuthApply"
python3 - "$CARD" "$APPLY" "$STRINGS" <<'PYTHON' && pass "every R.string the card names exists" || fail "a string the card names is missing"
import re, sys
used = set(re.findall(r"R\.string\.([a-z0-9_]+)", open(sys.argv[1]).read() + open(sys.argv[2]).read()))
declared = set(re.findall(r'<string name="([a-z0-9_]+)"', open(sys.argv[3]).read()))
missing = sorted(used - declared)
if missing: print("    missing: %s" % missing)
sys.exit(1 if missing else 0)
PYTHON

echo "── S4 what a sign-in yields is declared and dispatched both ways ──"
s4 "$BJ" "$APPLY" && pass "auth.applies ↔ DriveAuthApply.plan dispatch, both directions" || fail "declaration and dispatch disagree (see above)"
grep -q 'buildConfigField "String", "AUTH_APPLIES_B64"' "$GRADLE" && grep -q 'BuildConfig.AUTH_APPLIES_B64' "$SRC/Declarations.kt" && grep -q 'Declarations.authApplies' "$APPLY" \
    && pass "auth.applies baked once, decoded once by Declarations, read by DriveAuthApply" || fail "AUTH_APPLIES_B64 not baked, not decoded in Declarations, or not read"
grep -q 'throw new GradleException("build.json::auth.applies' "$GRADLE" && pass "a missing declaration is a hard build error" || fail "gradle tolerates a missing auth.applies"

echo "── S5 the apply writes only through the engines' stores ──"
grep -q 'GitCredentialStore(ctx)' <<<"$(codeof "$APPLY")" && grep -q 'RcloneConfig.upsert' <<<"$(codeof "$APPLY")" && pass "writes go through GitCredentialStore and RcloneConfig" || fail "the apply does not use the engines' stores"
grep -qiE 'wireguard|wg0_private_key|jmap|mail\.password|ai\.tokens|SharedPreferences|putString' <<<"$(codeof "$APPLY" "$CARD")" && fail "the apply reaches a mesh/mail/AI value or a raw preference store" || pass "no mesh, mail or AI value and no raw preference store on the drive apply path"
grep -qE 'result\.bearer|setAutheliaCredential' <<<"$(codeof "$CARD")" && fail "the drive card stores the bearer" || pass "the drive card stores no bearer"
grep -q 'RcloneRunner(ctx).configFile' <<<"$(codeof "$APPLY")" && pass "rclone.conf is the runner's own path, not a second copy" || fail "rclone.conf path is restated"

echo "── S6 the JVM test exists and reads the declaration ──"
grep -q '\["auth"\]!!.jsonObject\["applies"\]' "$JVM" && grep -q 'DriveAuthApply.plan(' "$JVM" && pass "DriveAuthApplyTest plans against build.json::auth.applies" || fail "DriveAuthApplyTest does not read the declaration"

echo "── M mutation-proof ──"
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT
mkdir -p "$TMP/src" && cp -r "$SRC"/. "$TMP/src"/
python3 - "$SHARED" "$TMP/src/configs/SignInCard.kt" <<'PYTHON'
import json, sys
pid = json.load(open(sys.argv[1]))["auth"]["sign_in"]["providers"][0]["id"]
p = sys.argv[2]; s = open(p).read(); open(p, "w").write(s + '\nval mutatedLiteral = "%s"\n' % pid)
PYTHON
s2 "$SHARED" "$BJ" "$TMP/src" >/dev/null && fail "M: a provider id typed into the app passed S2" || pass "M: provider literal → S2 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["ui"]["sign_in"]={"providers":[]}; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/two.json"
s2 "$SHARED" "$TMP/two.json" "$SRC" >/dev/null && fail "M: a second declaration passed S2" || pass "M: second declaration → S2 RED"
python3 -c 'import json,sys; b=json.load(open(sys.argv[1])); b["auth"]["applies"]["mesh"]={"key":"wg0_private_key","into":"x"}; json.dump(b,open(sys.argv[2],"w"))' "$BJ" "$TMP/mesh.json"
s4 "$TMP/mesh.json" "$APPLY" >/dev/null && fail "M: a declared section with no dispatch passed S4" || pass "M: undispatched declared section → S4 RED"
sed 's/^\(\s*\)"rclone" -> {$/\1"rclone" -> {\n\1}\n\1"extra" -> {/' "$APPLY" > "$TMP/extra.kt"
cmp -s "$APPLY" "$TMP/extra.kt" && fail "M: the dispatch mutation did not change the file (tester is stale)" || { s4 "$BJ" "$TMP/extra.kt" >/dev/null && fail "M: an undeclared dispatch branch passed S4" || pass "M: undeclared dispatch branch → S4 RED"; }
cp "$CARD" "$TMP/card_back.kt"; printf '\nval m = SignInWays(host = h)\n' >> "$TMP/card_back.kt"
card_signin_free "$TMP/card_back.kt" && fail "M: SignInWays back in the card passed" || pass "M: SignInWays back in the card -> S3 RED"
grep -v 'item { SignInCard() }' "$CONFIGS" > "$TMP/nocard.kt"
grep -q 'item { SignInCard() }' "$TMP/nocard.kt" && fail "M: card mutation stale" || pass "M: Configs without the card → S3 RED"
s2 "$SHARED" "$BJ" "$SRC" >/dev/null && pass "M: the unmutated tree is still green" || fail "M: the unmutated tree is red"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-configs-sign-in: all checks passed"; else echo "test-drive-configs-sign-in: $FAILURES check(s) FAILED"; fi
exit "$FAILURES"
