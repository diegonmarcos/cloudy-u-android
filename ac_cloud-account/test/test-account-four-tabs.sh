#!/usr/bin/env bash
# Tester (Cloud Account redesign task 8; first written as #778 "Configs > Account is exactly FOUR tabs" and kept
# under that file name): the Account pages of libs:account keep the promises the View-based page made.
#
# The page it began with (ProfileFragment, FleetCockpitView, ProfileJourneyView) was deleted by the redesign
# (a0_docs/eng-specs/cloud-account-redesign.md section 7). Its behaviours did not go with it; they moved:
#   Connect tab             -> home > connect          AccountConnectPage  + ConnectWays (the sign-in logic, a plain object)
#   Profiles tab / About    -> home > profile          AccountProfilePage (the About topic) + profiles > working
#   Drift tab               -> profiles > working/diff ProfilesWorkingPage / ProfilesDiffPage
#   Runtime tab             -> configs > apps / perms  AppsPage / AccountPermsPage, read by AccountRuntime
# AccountDriftTest / AccountStoreTest / AccountModelTest / AccountTabsComposeTest run the rules on the JVM;
# ac_cloud-account's test-account-app.sh pins the hosting, the islands and the connect dispatch. This file pins
# what those cannot, against the CURRENT sources (a check that reads a file that is gone passes on nothing):
#   A  CONNECT is the declared forges' ways plus the file way, every way dispatched on its kind alone
#      (ConnectWays.handler), a kind with no handler drawing its declared reason, no kind typed in a page, no way
#      label typed in a page.
#   A2 IMPORT FILE is no second importer: AccountHost.classify, then ConnectWays.land (every sign-in's landing);
#      every other verdict refused with a sentence.
#   B  NO GitHub OAuth app on this surface.
#   C  THE MASK: a masked row carries only a length, the rule fails closed, a masked value is drawn as a
#      fingerprint on the About topic / Profiles > working / Profiles > diff and can be neither edited nor saved,
#      and the debug API neither prints nor sets a masked field.
#   D  DRIFT: the declared pairs (S/R, L/S, L/R), the upload target BESIDE the generated server file, runtime ->
#      declared never taking an unobserved, read-only or empty value (on the model AND on the diff page's Capture),
#      the commit naming the sha it replaces with the token in a header only; /api/account/* advertised and served.
#   E  a pasted token is used once: filed only after it read the vault, never logged, never in a URL, its box
#      emptied the moment it is submitted and kept out of the keyboard's learned words.
#   G  GitHub WebAuth is gh's own sign-in in the gh engine; every way is the same tile.
#   V  EVERY SIGN-IN LANDS THE SAME WAY: the schema gate, the vault's registry, S, and the way that fetched it.
#   F  THE ABOUT TOPIC AND THE WORKING PROFILE DRAW THE WHOLE DECLARED SCHEMA: the declared skeleton matches
#      cloud-vault's schema.json + sources.json when the vault sits beside; every declared field has a row.
#   F2 NO VAULT SECTION IS DROPPED (sectionsFor), and Setup > apps lists every state of the working profile's apps.
#   H  RUNTIME IS PER APP: every declared app has a live reader (its `runtime` block says how it is reached); the
#      Setup index, wizard, cockpit cards, repos and the View-based page are gone.
# Every rule is mutation-proved below; each mutation is checked to have APPLIED.
set -uo pipefail
# #1693 moved here from aa_cloud-superapp/test with libs:account (Cloud Account's alone now). APP is still the
# SuperApp tree: its build.json holds ui.profile, which libs:account bakes for Cloud Account.
APP="${SA_APP:-$(cd "$(dirname "$0")/../../aa_cloud-superapp" && pwd)}"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  ok: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }

BJ="$APP/build.json"                                  # ui.profile.{connect,drift,infos,tabs}: still baked by libs:account for every host
ACCJ="$APP/../ac_cloud-account/build.json"            # ui.account: the forges, their ways, the file way
PKG="$APP/../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/profile"
CW="$PKG/ConnectWays.kt"      # the sign-in logic of the deleted ProfileFragment, as a plain object
AP="$PKG/AccountPages.kt"     # home > profile, home > connect, settings
PP="$PKG/ProfilesPages.kt"    # profiles > devices / working / diff
APG="$PKG/AppsPage.kt"        # configs > apps
HK="$PKG/AccountHost.kt"
IM="$PKG/InfoMask.kt"
CP="$PKG/VaultCockpit.kt"
GS="$PKG/GitSshVault.kt"
GE="$PKG/GhEngine.kt"
AT="$PKG/AccountTabs.kt"      # the View-free tabs of #778; mounted by no page now, still compiled and JVM-tested
AM="$PKG/AccountModel.kt"     # the one model the pages and the debug API share
AD="$PKG/AccountDrift.kt"     # the three-file engine
AR="$PKG/AccountRuntime.kt"   # per-app live readers / pushers
AU="$PKG/ForgeClient.kt"      # L -> server commit
ADA="$PKG/AccountDebugApi.kt" # /api/account/*
HOST_APP="$APP/../ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/App.kt"
MAIN="$APP/../ac_cloud-account/app/src/main/java/com/diegonmarcos/cloudaccount/MainActivity.kt"
ICF="$APP/../ab_cloud-libs-shared/libs/account/src/main/java/com/diegonmarcos/superapp/settings/ImportConfigsFragment.kt"   # #1693 Connect > Import's classifier and sentences (in libs:account now)
RES="$APP/../ab_cloud-libs-shared/libs/account/src/main/res"
for f in "$BJ" "$ACCJ" "$CW" "$AP" "$PP" "$APG" "$HK" "$IM" "$CP" "$GS" "$GE" "$AT" "$AM" "$AD" "$AR" "$AU" "$ADA" "$HOST_APP" "$MAIN" "$ICF" "$RES/values/strings.xml" "$RES/values-es/strings.xml"; do
    [ -f "$f" ] || { echo "FAIL: $f missing - this tester is unrun, not passing"; exit 1; }
done
# Code only - whole-line comments dropped, so prose about what must not happen does not read as it happening.
# Captured text is grepped through here-strings (never `echo | grep -q` under pipefail, #585b).
codeof() { awk '{ l=$0; sub(/^[[:space:]]+/,"",l); if (l ~ /^\/\// || l ~ /^\*/ || l ~ /^\/\*/) next; print }' "$@"; }
# A member function of an object/class (4-space indent): from its `fun name(` line to the closing `    }` when the
# line opens a brace, else (an expression body) to the next blank line.
memof() { awk -v n="$2" '
    f==0 && $0 ~ "^    (private |internal |override )?fun " n "\\(" { f=1; brace=($0 ~ /\{[[:space:]]*$/); print; next }
    f==1 { if (!brace && $0 ~ /^[[:space:]]*$/) exit; print; if (brace && $0 ~ /^    }[[:space:]]*$/) exit }' "$1"; }
# A top-level function from `fun Name(` to the closing `}` in column 0.
topof() { awk -v n="$2" '
    f==0 && $0 ~ "^(private |internal )?fun " n "\\(" { f=1; print; next }
    f==1 { print; if ($0 ~ /^}[[:space:]]*$/) exit }' "$1"; }
TMP="$(mktemp -d)"; trap 'rm -rf "${TMP:?}"' EXIT

# --- A - Connect: the declared ways, dispatched on their kind -----------------------------------------------
ways_ok() {   # $1 = ac_cloud-account build.json, $2 = ConnectWays.kt; prints the first broken rule
    python3 - "$1" "$2" <<'PYA'
import json, re, sys
acc = json.load(open(sys.argv[1]))["ui"]["account"]
cw = open(sys.argv[2], encoding="utf-8").read()
def die(m): print(m); sys.exit(1)
forges = acc.get("forges") or []
if not forges: die("no forge declared")
if len({f.get("id") for f in forges}) != len(forges): die("forge ids are not unique")
consts = dict(re.findall(r'const val (\w+) = "(\w+)"', cw))
m = re.search(r'fun handler\(kind: String\): Handler = when \(kind\) \{(.*?)\n    \}', cw, flags=re.S)
if not m: die("ConnectWays.handler is gone")
wired = {consts.get(k, k) for k in re.findall(r'^\s+(\w+) -> Handler\.(?!Unwired)', m.group(1), flags=re.M)}
seen = []
for f in forges:
    if not str(f.get("api", "")).startswith("https://"): die("forge %s: api %r is not https" % (f.get("id"), f.get("api")))
    if not f.get("auth"): die("forge %s declares no auth scheme" % f.get("id"))
    ways = f.get("ways") or []
    if not ways: die("forge %s declares no way" % f.get("id"))
    for k in ways:
        if not isinstance(k, str) or not k.strip(): die("forge %s: a way is not a kind string" % f.get("id"))
        if k in seen: die("kind %s is declared twice" % k)
        if k not in wired: die("kind %s has no handler branch in ConnectWays.handler" % k)
        seen.append(k)
if not any(f.get("repo") for f in forges): die("no forge declares a vault repo: Fetch now would have nowhere to read")
gh = [f for f in forges if f.get("id") == "github"]
if not gh or "gh_auth_login" not in (gh[0].get("ways") or []): die("the GitHub forge declares no gh_auth_login (WebAuth) way")
fl = (acc.get("connect") or {}).get("file") or {}
if fl.get("kind") not in wired or fl.get("kind") in seen: die("connect.file.kind %r is not a wired kind of its own" % fl.get("kind"))
if not str(fl.get("label", "")).strip(): die("the file way has no label")
# A kind that is limited says why: its entry in ConnectWays.NOTES names the limit.
notes = re.search(r'private val NOTES = mapOf\((.*?)\n    \)', cw, flags=re.S)
const_of = {"github_ssh": "GITHUB_SSH", "authelia_web": "AUTHELIA_WEB", "vault_file": "VAULT_FILE"}
for k, c in const_of.items():
    if (k in seen or k == fl.get("kind")) and (not notes or not re.search(r'\b%s to ' % c, notes.group(1))):
        die("kind %s is limited but carries no note saying why" % k)
PYA
}
echo "== A: Connect is the declared ways, each dispatched on its kind =="
msg=$(ways_ok "$ACCJ" "$CW") && ok "A: every declared forge way and the file way has its own handler branch; a limited way carries its note" || bad "A: $msg"
AP_CODE=$(codeof "$AP")
grep -qF 'when (val h = ConnectWays.handler(way.kind))' <<<"$AP_CODE" && grep -qF 'is ConnectWays.Handler.Unwired -> KitSelectableTile(way.label, h.why' <<<"$AP_CODE" \
    && ok "A: the page dispatches on handler(way.kind); an unwired kind draws its declared reason" || bad "A: the page does not dispatch on the kind, or hides why a kind is unwired"
grep -qF 'ConnectWays.ways()' <<<"$AP_CODE" && grep -qF 'for (way in group)' <<<"$AP_CODE" \
    && ok "A: the page iterates the declared ways" || bad "A: the page does not iterate ConnectWays.ways()"
KINDS=$(jq -r '([.ui.account.forges[].ways[]] + [.ui.account.connect.file.kind])[]' "$ACCJ")
lit=""; for k in $KINDS; do
    grep -qF "\"$k\"" <<<"$(codeof "$AP" "$PP" "$APG" "$AT")" && lit="$lit $k"
    [ "$(codeof "$CW" | grep -cF "\"$k\"")" = 1 ] || lit="$lit $k(not named once in ConnectWays)"
done
[ -z "$lit" ] && ok "A: each kind is named once, as a ConnectWays constant, and typed in no page" || bad "A: kind literal(s):$lit"
LABELS=$(python3 - "$CW" "$ACCJ" <<'PYL'
import json, re, sys
cw = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'private val LABELS = mapOf\((.*?)\n    \)', cw, re.S)
vals = re.findall(r'to "([^"]+)"', m.group(1)) if m else []
vals.append(json.load(open(sys.argv[2]))["ui"]["account"]["connect"]["file"]["label"])
print("\n".join(vals))
PYL
)
lab=""; n=0; while IFS= read -r l; do
    [ -n "$l" ] || continue; n=$((n+1))
    grep -qF "\"$l\"" <<<"$(codeof "$AP" "$PP" "$APG")" && lab="$lab [$l]"
done <<<"$LABELS"
[ "$n" -ge 6 ] || lab="$lab (only $n labels found in ConnectWays)"
[ -z "$lab" ] && ok "A: no way label is typed in a page ($n labels live in ConnectWays and the declared file way)" || bad "A: label(s) typed in a page:$lab"

echo "-- A-mutation: a kind with no handler, a forge without ways or https, the file way gone, a kind declared twice, a branch dropped --"
amut() {   # $1 = python over acc (the ui.account block); 0 iff ways_ok goes red; 2 iff nothing changed
    cp "$ACCJ" "$TMP/a.json"
    python3 - "$TMP/a.json" "$1" <<'PY'
import json,sys
p=sys.argv[1]; d=json.load(open(p)); acc=d["ui"]["account"]; exec(sys.argv[2]); json.dump(d,open(p,"w"))
PY
    cmp -s "$ACCJ" "$TMP/a.json" && return 2
    ! ways_ok "$TMP/a.json" "$CW" >/dev/null
}
for m in 'acc["forges"][1]["ways"].append("file_magic")' \
         'acc["forges"][0]["ways"] = []' \
         'acc["forges"][0]["api"] = "http://api.github.com"' \
         'acc["connect"].pop("file")' \
         'acc["forges"][1]["ways"].append("github_pat")' \
         'acc["connect"]["file"]["label"] = ""' \
         'acc["forges"][0]["ways"].remove("gh_auth_login")'; do
    amut "$m"; rc=$?
    case $rc in 0) ok "A-mutation: caught - $m";; 2) bad "A-mutation: did not apply - $m";; *) bad "A-mutation: NOT caught - $m";; esac
done
for e in '/^        GITEA_TOKEN -> Handler.GiteaToken$/d' 's/^        VAULT_FILE -> Handler.File$/        VAULT_FILE -> Handler.Unwired("x")/' '/^        GITHUB_SSH to "read only/d'; do
    sed "$e" "$CW" > "$TMP/a.kt"
    if cmp -s "$CW" "$TMP/a.kt"; then bad "A-mutation: did not apply - ${e:0:60}"
    else ways_ok "$ACCJ" "$TMP/a.kt" >/dev/null && bad "A-mutation: NOT caught - ${e:0:60}" || ok "A-mutation: caught - ${e:0:60}"; fi
done

# --- A2 - Import file: the host's classifier, the sign-ins' landing --------------------------------------------
file_ok() {   # $1 = ConnectWays.kt, $2 = AccountPages.kt, $3 = AccountHost.kt, $4 = ImportConfigsFragment.kt; prints the first broken rule
    local iv; iv=$(memof "$1" importFile | codeof /dev/stdin)
    [ -n "$iv" ] || { echo "ConnectWays.importFile is gone"; return 1; }
    grep -qF 'AccountHost.classify(text)' <<<"$iv" || { echo "the file is not read through AccountHost.classify"; return 1; }
    grep -qF 'is VaultFile.Verdict.Bundle -> land(ctx, v.bundle, VAULT_FILE)' <<<"$iv" || { echo "the export does not land through land() with the file way"; return 1; }
    grep -qE 'VaultConnect\.Imported|VaultFile\.classify\(' <<<"$iv" && { echo "importFile writes the import or classifies itself - a second importer"; return 1; }
    grep -qF 'else -> record(ctx, fail(AccountHost.refusal(ctx, v)' <<<"$iv" || { echo "a non-export file is not refused with its reason, and recorded"; return 1; }
    grep -qF '.ifBlank { "not a decrypted vault export" }' <<<"$iv" || { echo "a refusal can be a blank line"; return 1; }
    local pg; pg=$(codeof "$2")
    grep -qF 'ActivityResultContracts.OpenDocument()' <<<"$pg" && grep -qF 'ConnectWays.importFile(ctx, text)' <<<"$pg" || { echo "the file way is not the system document picker feeding importFile"; return 1; }
    grep -qF 'filePicker.launch(' <<<"$pg" || { echo "the file way does not open the picker"; return 1; }
    local hk; hk=$(codeof "$3")
    # #1693 Connect > Import moved into libs:account: its classifier and sentences ARE the defaults now.
    grep -qF '{ c, v -> com.diegonmarcos.superapp.settings.ImportConfigsFragment.refusal(c, v) }' <<<"$hk" || { echo "the default refusal is not Import's 'null for the export, a sentence for everything else'"; return 1; }
    grep -qF '{ text -> com.diegonmarcos.superapp.settings.ImportConfigsFragment.classify(text) }' <<<"$hk" || { echo "the default classifier is not Import's"; return 1; }
    grep -qF 'VaultFile.classify(text, VaultConnect.knownSchemaVersions' <<<"$(codeof "$4")" || { echo "the default classifier does not know the declared schema versions"; return 1; }
    local rf; rf=$(awk '/fun refusal\(ctx: Context, v: VaultFile.Verdict\): String\? = when \(v\) \{/{f=1} f{print} f&&/^        }$/{exit}' "$4")
    for v in Empty NotJson Encrypted UnknownSchema Unrecognised Blob; do
        grep -q "Verdict\.$v -> ctx.getString(R.string\." <<<"$rf" || { echo "Import's refusal() gives no sentence for $v"; return 1; }
    done
    grep -qF 'Verdict.Bundle -> null' <<<"$rf" || { echo "refusal() refuses the export itself"; return 1; }
    return 0
}
echo "== A2: Import file reads through the host's classifier and lands like a sign-in =="
msg=$(file_ok "$CW" "$AP" "$HK" "$ICF") && ok "A2: picker -> AccountHost.classify -> land(); every other verdict refused with a sentence and recorded" || bad "A2: $msg"
echo "-- A2-mutation: a second importer, a lost landing, a silent refusal, a blank refusal, a missing verdict sentence --"
f2mut() {   # $1 = file (cw|ap|hk|icf), $2 = sed expression; 0 iff file_ok goes red; 2 iff nothing changed
    cp "$CW" "$TMP/cw.kt"; cp "$AP" "$TMP/ap.kt"; cp "$HK" "$TMP/hk.kt"; cp "$ICF" "$TMP/icf.kt"
    sed -i "$2" "$TMP/$1.kt"
    local src; case $1 in cw) src=$CW;; ap) src=$AP;; hk) src=$HK;; icf) src=$ICF;; esac
    cmp -s "$src" "$TMP/$1.kt" && return 2
    ! file_ok "$TMP/cw.kt" "$TMP/ap.kt" "$TMP/hk.kt" "$TMP/icf.kt" >/dev/null
}
for m in 'cw|s/is VaultFile.Verdict.Bundle -> land(ctx, v.bundle, VAULT_FILE)/is VaultFile.Verdict.Bundle -> { VaultConnect.Imported.bundle = v.bundle; Outcome(true, "x") }/' \
         'cw|s/fun importFile(ctx: Context, text: String): Outcome = when (val v = AccountHost.classify(text)) {/fun importFile(ctx: Context, text: String): Outcome = when (val v = VaultFile.classify(text, emptySet(), emptySet())) {/' \
         'cw|s/else -> record(ctx, fail(AccountHost.refusal(ctx, v)/else -> Outcome(false, AccountHost.refusal(ctx, v)/' \
         'cw|s/.orEmpty().ifBlank { "not a decrypted vault export" }/.orEmpty()/' \
         'ap|s/filePicker.launch(arrayOf("application\/json", "text\/\*", "\*\/\*"))/Unit/' \
         'hk|s/{ c, v -> com.diegonmarcos.superapp.settings.ImportConfigsFragment.refusal(c, v) }/{ _, _ -> null }/' \
         'icf|/is VaultFile.Verdict.Encrypted -> ctx.getString(R.string.import_encrypted/d'; do
    amt="${m%%|*}"; expr="${m#*|}"
    f2mut "$amt" "$expr"; rc=$?
    case $rc in 0) ok "A2-mutation: caught - $amt: ${expr:0:70}";; 2) bad "A2-mutation: did not apply - $amt: ${expr:0:70}";; *) bad "A2-mutation: NOT caught - $amt: ${expr:0:70}";; esac
done

# --- B - no GitHub OAuth app on the Account surface ----------------------------------------------------------
OAUTH_RE='client_id|client_secret|clientSecret|clientId|oauth/landed|login/oauth|web_client|webClient|OAuthWeb'
oauth_free() {   # $1 = ac_cloud-account build.json, $2 = SuperApp build.json, $3..$n = sources; prints the first hit
    local acc="$1" sa="$2" hit; shift 2
    # _doc* keys are prose the app never reads (they may STATE this rule); every other key is scanned.
    hit=$(jq -c '.ui.account | walk(if type == "object" then with_entries(select(.key | startswith("_doc") | not)) else . end)' "$acc" | grep -oE "$OAUTH_RE" | head -1)
    [ -n "$hit" ] && { echo "ui.account declares $hit"; return 1; }
    hit=$(jq -c '.ui.profile | walk(if type == "object" then with_entries(select(.key | startswith("_doc") | not)) else . end)' "$sa" | grep -oE "$OAUTH_RE" | head -1)
    [ -n "$hit" ] && { echo "ui.profile declares $hit"; return 1; }
    hit=$(codeof "$@" | grep -oE "$OAUTH_RE" | head -1)
    [ -n "$hit" ] && { echo "the Account sources name $hit"; return 1; }
    return 0
}
echo "== B: the Account surface declares and names no GitHub OAuth app =="
msg=$(oauth_free "$ACCJ" "$BJ" "$CW" "$AP" "$PP" "$IM" "$CP" "$GS" "$GE") && ok "B: no client id, client secret, OAuth landing or web client on the Account surface" || bad "B: $msg"
jq -e '.ui.profile.connect.lines[].ways[] | select(.kind == "cloud_account" and .provider == "github") | .note | test("gh")' "$BJ" >/dev/null \
    && ok "B: GitHub WebAuth is declared as gh's own sign-in (run in Cloud Account, #1693), not an app of the fleet's" || bad "B: the GitHub WebAuth way does not say it is gh's own sign-in"
echo "-- B-mutation: a client id declared, a landing declared, a web client named in Kotlin --"
jq '.ui.account.forges[0].client_id = "Iv1.planted"' "$ACCJ" > "$TMP/b1.json"
oauth_free "$TMP/b1.json" "$BJ" "$CW" >/dev/null && bad "B-mutation: a declared client_id was NOT caught" || ok "B-mutation: a declared client_id is caught"
jq '.ui.profile.connect.redirect = "https://api.example.test/git/oauth/landed"' "$BJ" > "$TMP/b2.json"
oauth_free "$ACCJ" "$TMP/b2.json" "$CW" >/dev/null && bad "B-mutation: a declared OAuth landing was NOT caught" || ok "B-mutation: a declared OAuth landing is caught"
sed 's/fun pat(ctx: Context, token: String): Outcome =/fun pat(ctx: Context, token: String, c: Any = OAuthWeb.parse(null)): Outcome =/' "$CW" > "$TMP/b3.kt"
cmp -s "$CW" "$TMP/b3.kt" && bad "B-mutation: the OAuthWeb mutation did not apply" \
    || { oauth_free "$ACCJ" "$BJ" "$TMP/b3.kt" >/dev/null && bad "B-mutation: OAuthWeb named in ConnectWays was NOT caught" || ok "B-mutation: OAuthWeb named in ConnectWays is caught"; }
sed 's/        const val LOGIN_POLL = "loginPoll"/        const val LOGIN_POLL = "loginPoll"\n        const val GH_CLIENT_ID = "Iv1.planted"; val clientId = GH_CLIENT_ID/' "$GE" > "$TMP/b4.kt"
cmp -s "$GE" "$TMP/b4.kt" && bad "B-mutation: the GhEngine client-id mutation did not apply" \
    || { oauth_free "$ACCJ" "$BJ" "$CW" "$TMP/b4.kt" >/dev/null && bad "B-mutation: a client id in GhEngine was NOT caught" || ok "B-mutation: a client id planted in GhEngine is caught"; }

# --- C - the mask --------------------------------------------------------------------------------------------
echo "== C: a masked value is a length or a fingerprint on every page, and can be neither edited nor saved =="
masked_row_blank() { grep -qF 'Row(path, "", Kind.MASKED, text.length)' "$1"; }
masked_row_blank "$IM" && ok "C: a masked row carries its length and an EMPTY text - the secret never leaves InfoMask" || bad "C: a masked row still carries its text"
pages_mask_ok() {   # $1 = AccountPages.kt, $2 = ProfilesPages.kt; prints the first broken rule
    local av wk df
    av=$(topof "$1" AboutValue | codeof /dev/stdin); wk=$(topof "$2" ProfilesWorkingPage | codeof /dev/stdin); df=$(topof "$2" ProfilesDiffPage | codeof /dev/stdin)
    [ -n "$av" ] && [ -n "$wk" ] && [ -n "$df" ] || { echo "AboutValue / ProfilesWorkingPage / ProfilesDiffPage not found"; return 1; }
    grep -qF 'InfoMask.Kind.MASKED -> KitFingerprint(' <<<"$av" || { echo "the About topic does not draw a masked row as a fingerprint"; return 1; }
    grep -qF 'row.text' <<<"$(grep -F 'InfoMask.Kind.MASKED' <<<"$av")" && { echo "the About topic draws a masked row's text"; return 1; }
    grep -qF 'val editable = (row.kind == InfoMask.Kind.SHOWN || row.kind == InfoMask.Kind.EMPTY)' <<<"$(codeof "$1")" || { echo "a masked About row is editable"; return 1; }
    grep -qF 'TextButton(enabled = !InfoMask.declared.hides(path, value), onClick = {' <<<"$(codeof "$1")" || { echo "the About edit dialog can save a masked value"; return 1; }
    grep -qF 'val masked = InfoMask.declared.hides(full, text)' <<<"$wk" && grep -qF 'val editable = !masked' <<<"$wk" || { echo "Profiles > working lets a masked key be edited"; return 1; }
    grep -qF 'if (masked) KitFingerprint(fingerprint(text), tag = full)' <<<"$wk" || { echo "Profiles > working draws a masked key as something other than a fingerprint"; return 1; }
    grep -qF 'TextButton(enabled = !InfoMask.declared.hides(path, value), modifier = Modifier.testTag(ProfilesPageTags.SAVE)' <<<"$(codeof "$2")" || { echo "the working-profile edit can save a masked value"; return 1; }
    grep -qF 'val secret = InfoMask.declared.hides("settings.${f.path}", f.a ?: f.b ?: "")' <<<"$df" || { echo "Profiles > diff does not ask the mask"; return 1; }
    grep -qF 'if (secret) "file and phone hold a secret: fingerprints only" else' <<<"$df" && [ "$(grep -c 'if (secret) KitFingerprint(' <<<"$df")" -ge 2 ] || { echo "Profiles > diff may draw a secret-class value"; return 1; }
    return 0
}
msg=$(pages_mask_ok "$AP" "$PP") && ok "C: About, Profiles > working and Profiles > diff draw a masked value as a fingerprint and refuse to edit or save one" || bad "C: $msg"
grep -qF 'if (r.kind == InfoMask.Kind.SHOWN) put("text", r.text.take(120)) else put("size", r.size)' "$ADA" \
    && grep -qF 'InfoMask.declared.hides(path, value)) "' "$ADA" \
    && ok "C: the debug API prints only shown values and refuses to set a masked field" || bad "C: the debug API can print or set a masked value"
grep -qF 'pathRes.any { it == null } || valueRes.any { it == null }' "$IM" && grep -qF '(pathRes.isEmpty() && valueRes.isEmpty())' "$IM" \
    && ok "C: the rule fails closed (no pattern, or one that does not compile, masks every leaf)" || bad "C: the mask does not fail closed"
# The rule itself, ported from InfoMask.rows/hides, run over a bundle.
mask_leaks() {   # $1 = build.json, $2 = bundle.json, $3 = known-secret path regex; prints each leak; 1 iff any
    python3 - "$1" "$2" "$3" <<'PYC'
import json, re, sys
m = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["mask"]
pr = [re.compile(p, re.I) for p in m["paths"]]; vr = [re.compile(p) for p in m["values"]]
co = int(m.get("collapse_over", 12)); SEP = " › "
secret = re.compile(sys.argv[3], re.I)
tokenish = re.compile(r"(?i:bearer)\s+\S{16,}|gh[pousr]_[A-Za-z0-9]{20,}|sk-[A-Za-z0-9_-]{20,}|PrivateKey|-----BEGIN|[A-Za-z0-9+/=]{40,}|[A-Za-z0-9_-]{40,}")
def join(p, k): return k if not p else p + SEP + k
def text(v):
    if v is None: return "null"
    if isinstance(v, bool): return "true" if v else "false"
    if isinstance(v, (dict, list)): return json.dumps(v)
    return str(v)
leaks = []
def walk(sec, v, path):
    if isinstance(v, dict) and v.get("pending") is True: return
    if isinstance(v, (dict, list)) and len(v) > co: return
    if isinstance(v, dict) and v:
        for k, x in v.items(): walk(sec, x, join(path, k)); return
    if isinstance(v, list) and v:
        for i, x in enumerate(v): walk(sec, x, join(path, "[%d]" % i)); return
    full, t = join(sec, path), text(v)
    hidden = any(r.search(full) for r in pr) or any(r.search(t) for r in vr)
    if not hidden and (secret.search(full) or tokenish.search(t)): leaks.append(full)
b = json.load(open(sys.argv[2])); b = b.get("bundle", b)
for s in b:
    if s.startswith("_") or s == "schema_version": continue
    walk(s, b[s], "")
for l in leaks: print(l)
sys.exit(1 if leaks else 0)
PYC
}
SECRET_PATHS='^(git › (github_token|ssh_private_key)|mail › passwords › .*|ai › tokens › .*|mesh › profiles › .*|autocomplete › .* › text)$'
# A synthetic bundle, assembled here so the committed file holds no token-shaped literal.
python3 - "$TMP/fixture.json" <<'PYF'
import json, sys
tok = "gh" + "p_" + "a1B2c3D4e5" * 4
json.dump({"schema_version": 1,
  "git": {"github_token": tok, "ssh_private_key": "-----BEGIN " + "OPENSSH PRIVATE KEY-----\nAAAA", "repos": [{"repo": "front"}]},
  "mail": {"passwords": {"ME_PASSWORD": "correct-horse-battery-staple"}, "accounts": {"a": {"name": "me", "pass_env": "ME_PASSWORD"}}},
  "mesh": {"profiles": {"config-v4-full": "[Interface]\nPrivateKey = " + "K" * 43 + "=\nAddress = 10.9.9.9/32"}},
  "ai": {"tokens": {"x": "sk" + "-or-v1-" + "0123456789abcdef" * 3}},
  "autocomplete": {"default": [{"text": "clip %d" % i} for i in range(20)], "urls_pub": [{"text": "Authorization: Bearer " + "q" * 24}]},
  "about": {"profile": {"name": "Test Person"}}}, open(sys.argv[1], "w"))
PYF
out=$(mask_leaks "$BJ" "$TMP/fixture.json" "$SECRET_PATHS") && ok "C: the declared rule masks every secret of the synthetic bundle" || bad "C: the declared rule leaks: $(echo $out)"
VAULT_BUNDLE=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/Shared/configs/profile-secrets.json}" "$APP/../../cloud-vault/Shared/configs/profile-secrets.json" "$APP/../../../cloud-vault/Shared/configs/profile-secrets.json"; do
    [ -f "$v" ] && VAULT_BUNDLE="$v" && break
done
if [ -n "$VAULT_BUNDLE" ]; then
    out=$(mask_leaks "$BJ" "$VAULT_BUNDLE" "$SECRET_PATHS") \
        && ok "C: the declared rule masks every secret of the REAL vault export ($(du -h "$VAULT_BUNDLE" | cut -f1); paths only, no value printed)" \
        || bad "C: the declared rule leaks in the real vault export at: $(echo $out)"
else
    echo "  UNVERIFIABLE: cloud-vault is not checked out beside this repo - the real-export check did not run (the synthetic one above did)"
fi
echo "-- C-mutation: a path pattern dropped, a masked row given its text, a page that draws or edits it --"
jq '.ui.profile.infos.mask.paths |= map(select(. != "pass"))' "$BJ" > "$TMP/c1.json"
cmp -s "$BJ" "$TMP/c1.json" && bad "C-mutation: the pattern drop did not apply" \
    || { mask_leaks "$TMP/c1.json" "$TMP/fixture.json" "$SECRET_PATHS" >/dev/null && bad "C-mutation: dropping 'pass' leaked nothing - the rule check cannot fail" || ok "C-mutation: dropping 'pass' leaks the mail password -> RED"; }
# The value patterns overlap on purpose (a WireGuard key is both "PrivateKey" and a long base64 run), so the proof
# drops them ALL: the profile's path names no secret.
jq '.ui.profile.infos.mask.values = []' "$BJ" > "$TMP/c2.json"
mask_leaks "$TMP/c2.json" "$TMP/fixture.json" "$SECRET_PATHS" >/dev/null && bad "C-mutation: emptying the value patterns leaked nothing" || ok "C-mutation: no value pattern -> the WireGuard profile leaks -> RED"
sed 's/Row(path, "", Kind.MASKED, text.length)/Row(path, text, Kind.MASKED, text.length)/' "$IM" > "$TMP/c3.kt"
cmp -s "$IM" "$TMP/c3.kt" && bad "C-mutation: the masked-text mutation did not apply" \
    || { masked_row_blank "$TMP/c3.kt" && bad "C-mutation: a masked row carrying its text was NOT caught" || ok "C-mutation: a masked row carrying its text -> RED"; }
cmut() {   # $1 = ap|pp, $2 = sed; 0 iff pages_mask_ok goes red; 2 iff nothing changed
    cp "$AP" "$TMP/ap.kt"; cp "$PP" "$TMP/pp.kt"; sed -i "$2" "$TMP/$1.kt"
    local src; case $1 in ap) src=$AP;; pp) src=$PP;; esac
    cmp -s "$src" "$TMP/$1.kt" && return 2
    ! pages_mask_ok "$TMP/ap.kt" "$TMP/pp.kt" >/dev/null
}
for m in 'ap|s/InfoMask.Kind.MASKED -> KitFingerprint(/InfoMask.Kind.MASKED -> Text(row.text + /' \
         'ap|s/val editable = (row.kind == InfoMask.Kind.SHOWN || row.kind == InfoMask.Kind.EMPTY)/val editable = true/' \
         'ap|s/TextButton(enabled = !InfoMask.declared.hides(path, value), onClick = {/TextButton(enabled = true, onClick = {/' \
         'pp|s/val editable = !masked/val editable = true/' \
         'pp|s/if (masked) KitFingerprint(fingerprint(text), tag = full)/if (masked) Text(text)/' \
         'pp|s/TextButton(enabled = !InfoMask.declared.hides(path, value), modifier = Modifier.testTag(ProfilesPageTags.SAVE)/TextButton(enabled = true, modifier = Modifier.testTag(ProfilesPageTags.SAVE)/' \
         'pp|s/if (secret) "file and phone hold a secret: fingerprints only" else/if (false) "file and phone hold a secret: fingerprints only" else/' \
         'pp|s/if (secret) KitFingerprint(f.a?.let(::fingerprint), tag = "file:${f.path}")/Unit/'; do
    f="${m%%|*}"; e="${m#*|}"
    cmut "$f" "$e"; rc=$?
    case $rc in 0) ok "C-mutation: caught - $f: ${e:0:70}";; 2) bad "C-mutation: did not apply - $f: ${e:0:70}";; *) bad "C-mutation: NOT caught - $f: ${e:0:70}";; esac
done

# --- D - Drift ------------------------------------------------------------------------------------------------
echo "== D: Drift keeps S, R and L in one pattern, compares the declared pairs, and every sync direction is one model function =="
drift_decl_ok() {   # $1 = build.json; prints the first broken rule
    python3 - "$1" <<'PYD'
import json, sys
d = json.load(open(sys.argv[1]))["ui"]["profile"].get("drift") or {}
def die(m): print(m); sys.exit(1)
pairs = d.get("pairs") or []
if [p.get("id") for p in pairs] != ["SR", "LS", "LR"]: die("pairs are %s, not SR, LS, LR" % [p.get("id") for p in pairs])
for p in pairs:
    if {p.get("a"), p.get("b")} - {"S", "R", "L"} or p.get("a") == p.get("b"): die("pair %s compares %s with %s" % (p.get("id"), p.get("a"), p.get("b")))
    if not str(p.get("label", "")).strip(): die("pair %s has no label" % p.get("id"))
u = d.get("upload") or {}
path = str(u.get("path", ""))
if not path: die("no upload path")
if path.endswith("profile-secrets.json"): die("the upload targets the GENERATED server file (emit.py guards it against hand edits)")
if not all(x in str(u.get("api", "")) for x in ("{repo}", "{path}")): die("the upload api is not a {repo}/{path} template")
if "{device}" not in str(u.get("message", "")): die("the commit message does not name the device")
if not isinstance(d.get("runtime_deadline_ms"), int) or d["runtime_deadline_ms"] <= 0: die("no runtime deadline")
PYD
}
msg=$(drift_decl_ok "$BJ") && ok "D: the pairs (S/R, L/S, L/R), the upload target beside the generated file, and the runtime deadline are declared" || bad "D: $msg"
LOCALPY=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/Shared/configs/local.py}" "$APP/../../cloud-vault/Shared/configs/local.py" "$APP/../../../cloud-vault/Shared/configs/local.py"; do [ -f "$v" ] && LOCALPY="$v" && break; done
if [ -n "$LOCALPY" ]; then
    vp=$(grep -oE '^LOCAL = CONFIGS / "[^"]+"' "$LOCALPY" | cut -d'"' -f2)
    [ -n "$vp" ] && [ "$(jq -r '.ui.profile.drift.upload.path' "$BJ")" = "Shared/configs/$vp" ] \
        && ok "D: the upload path is the one cloud-vault's local.py checks (Shared/configs/$vp)" || bad "D: the upload path is not the file cloud-vault's local.py checks ($vp)"
else
    echo "  UNVERIFIABLE: cloud-vault is not beside this repo - the upload path was not compared with local.py's"
fi
grep -q 'UI_PROFILE_DRIFT_B64' "$APP/../ab_cloud-libs-shared/libs/account/build.gradle" && grep -qF 'BuildConfig.UI_PROFILE_DRIFT_B64' "$AM" \
    && ok "D: the drift declaration is baked and read" || bad "D: UI_PROFILE_DRIFT_B64 is not baked or not read"
engine_ok() {   # $1 = AccountDrift.kt, $2 = AccountModel.kt, $3 = ForgeClient.kt, $4 = ProfilesPages.kt; prints the first broken rule
    grep -qF 'if (p.a == Slot.R || p.b == Slot.R) AccountRuntime.observed(runtime()?.apps) else null' "$2" || { echo "a comparison with R is not limited to what R observed"; return 1; }
    local rd; rd=$(awk '/    fun runtimeToDeclared\(/{f=1} f{print} f&&/^    }$/{exit}' "$1")
    grep -qF 'p !in observed -> skipped[p] = Skip.NOT_OBSERVED' <<<"$rd" || { echo "an unobserved field can be pulled into L"; return 1; }
    grep -qF 'p in readOnly -> skipped[p] = Skip.READ_ONLY' <<<"$rd" || { echo "a read-only field can be pulled into L"; return 1; }
    grep -qF 'v == null -> skipped[p] = Skip.HOLDS_NONE' <<<"$rd" || { echo "a runtime holding nothing can erase a declared value"; return 1; }
    grep -qF 'AccountDrift.runtimeToDeclared(shown(), AccountDrift.leaves(r.body), AccountRuntime.observed(r.apps), AccountRuntime.readOnly(r.apps), paths)' "$2" || { echo "the model's pull does not pass the runtime's observed and read-only sets"; return 1; }
    local cap; cap=$(awk '/    fun captureFromPhone\(/{f=1} f{print} f&&/^    }$/{exit}' "$4")
    grep -qF 'AccountRuntime.observed(rt?.apps), AccountRuntime.readOnly(rt?.apps), paths)' <<<"$cap" || { echo "the diff page's Capture passes everything as observed and nothing as read-only"; return 1; }
    grep -qF 'sha, "account($device): captured' <<<"$(tr '\n' ' ' <<<"$cap" | sed 's/  */ /g')" || { echo "the diff page's Capture commits without naming the sha it replaces"; return 1; }
    grep -qF 'fun sha256(body: JSONObject): String' "$1" && grep -qF 'canonical(body)' <<<"$(grep -A2 'fun sha256' "$1")" || { echo "the file hash is not over canonical JSON"; return 1; }
    grep -qF 'put("Authorization", forge.auth.scheme + " " + token)' "$3" || { echo "the upload token does not ride a header"; return 1; }
    grep -qE '(url|api|path).*\$\{?token' "$3" && { echo "the upload token reaches a URL"; return 1; }
    grep -qF 'if (sha != null) body.put("sha", sha)' "$3" || { echo "an update does not name the sha it replaces"; return 1; }
    return 0
}
msg=$(engine_ok "$AD" "$AM" "$AU" "$PP") && ok "D: R comparisons cover what R observed; runtime->declared never takes unobserved, read-only or empty values (model and diff page); the commit names its sha and keeps the token in a header" || bad "D: $msg"
actions_ok() {   # $1 = ProfilesPages.kt; each diff action is one model/engine call
    local df; df=$(topof "$1" ProfilesDiffPage | codeof /dev/stdin)
    grep -qF 'KitAction("Apply all → phone", ProfilesPageTags.APPLY_ALL, !busy) { io { applyToPhone(AccountDrift.drifted(fields)) } }' <<<"$df" || { echo "Apply all is not the drifted set pushed onto the phone"; return 1; }
    grep -qF 'KitAction("Capture all ← file", ProfilesPageTags.CAPTURE_ALL, !busy) { io { captureFromPhone(AccountDrift.drifted(fields)) } }' <<<"$df" || { echo "Capture all is not the drifted set captured"; return 1; }
    grep -qF 'ProfilesPageTags.appApply(app)' <<<"$df" && grep -qF 'ProfilesPageTags.appCapture(app)' <<<"$df" || { echo "no per-app sync"; return 1; }
    grep -qF 'ProfilesPageTags.rowApply(f.path)' <<<"$df" && grep -qF 'ProfilesPageTags.rowCapture(f.path)' <<<"$df" || { echo "no per-item sync"; return 1; }
    grep -qF 'return said(model.pushServerToRuntime(paths.toList()))' <<<"$df" || { echo "the push is not the model's server -> runtime"; return 1; }
    grep -qF 'AccountDrift.pushPlan(fileLeaves, phoneLeaves.keys, paths, model.apps)' <<<"$df" || { echo "the push is not planned over the observed set and the app owners"; return 1; }
    return 0
}
msg=$(actions_ok "$PP") && ok "D: apply / capture per item, per app and all - each one model or engine call" || bad "D: $msg"
api_ok() {   # $1 = AccountDebugApi.kt, $2 = the Account host's App.kt
    local op; for op in tabs profiles runtime drift refresh populate edit save sync upload; do
        grep -qF "Op(\"$op\"" "$1" || { echo "/api/account/$op is not advertised"; return 1; }
        grep -qE "^ *(\"\", )?\"$op\" ->" "$1" || { echo "/api/account/$op is not served"; return 1; }
    done
    grep -qF 'AccountDebugApi.register(this)' "$2" || { echo "the account routes are never registered"; return 1; }
    grep -qF 'm.pushServerToRuntime(paths)' "$1" && grep -qF 'm.pullRuntimeToLocal(paths)' "$1" && grep -qF 'm.discardLocal()' "$1" || { echo "sync does not call the model"; return 1; }
    return 0
}
msg=$(api_ok "$ADA" "$HOST_APP") && ok "D: /api/account/{tabs,profiles,runtime,drift} and the action ops are advertised, served and registered by the Account host" || bad "D: $msg"
echo "-- D-mutation: the R scope dropped, an empty runtime value pulled, the capture unguarded, the token in the URL, an update without its sha, an action unwired, a route dropped, the generated file as target --"
dm() {   # $1 = which file var, $2 = sed; 0 iff its check goes red; 2 iff nothing changed
    local f; eval f=\$$1; sed "$2" "$f" > "$TMP/d.kt"; cmp -s "$f" "$TMP/d.kt" && return 2
    case $1 in AD) ! engine_ok "$TMP/d.kt" "$AM" "$AU" "$PP" >/dev/null;; AM) ! engine_ok "$AD" "$TMP/d.kt" "$AU" "$PP" >/dev/null;; AU) ! engine_ok "$AD" "$AM" "$TMP/d.kt" "$PP" >/dev/null;;
               PP) ! { engine_ok "$AD" "$AM" "$AU" "$TMP/d.kt" && actions_ok "$TMP/d.kt"; } >/dev/null;;
               ADA) ! api_ok "$TMP/d.kt" "$HOST_APP" >/dev/null;; esac
}
for m in 'AM|s/if (p.a == Slot.R || p.b == Slot.R) AccountRuntime.observed(runtime()?.apps) else null/null/' \
         'AD|s/                v == null -> skipped\[p\] = Skip.HOLDS_NONE/                v == null -> Unit/' \
         'AD|s/                p in readOnly -> skipped\[p\] = Skip.READ_ONLY/                false -> Unit/' \
         'PP|s/AccountRuntime.observed(rt?.apps), AccountRuntime.readOnly(rt?.apps), paths)/phoneLeaves.keys, emptySet(), paths)/' \
         'AU|s/forge.contents(path) + refQuery(ref)/forge.contents(path) + refQuery(ref) + "\&access_token=$token"/' \
         'AU|s/if (sha != null) body.put("sha", sha)/Unit/' \
         'PP|s/KitAction("Capture all ← file", ProfilesPageTags.CAPTURE_ALL, !busy) { io { captureFromPhone(AccountDrift.drifted(fields)) } }/KitAction("Capture all ← file", ProfilesPageTags.CAPTURE_ALL, !busy) { }/' \
         'PP|s/ProfilesPageTags.rowCapture(f.path)/ProfilesPageTags.CAPTURE_ALL/' \
         'ADA|s/            "drift" -> m.report()/            "drift_old" -> m.report()/'; do
    v="${m%%|*}"; e="${m#*|}"
    dm "$v" "$e"; rc=$?
    case $rc in 0) ok "D-mutation: caught - $v: ${e:0:70}";; 2) bad "D-mutation: did not apply - $v: ${e:0:70}";; *) bad "D-mutation: NOT caught - $v: ${e:0:70}";; esac
done
for jm in '.ui.profile.drift.upload.path = "Shared/configs/profile-secrets.json"' '.ui.profile.drift.pairs |= .[1:]' '.ui.profile.drift.pairs[0].b = "S"'; do
    jq "$jm" "$BJ" > "$TMP/d.json"
    if cmp -s "$BJ" "$TMP/d.json"; then bad "D-mutation: did not apply - $jm"
    else drift_decl_ok "$TMP/d.json" >/dev/null && bad "D-mutation: NOT caught - $jm" || ok "D-mutation: caught - $jm"; fi
done

# --- E - the pasted token is used once -----------------------------------------------------------------------
echo "== E: a pasted token is a one-request credential =="
pat_clean() {   # $1 = ConnectWays.kt, $2 = AccountPages.kt; 0 iff the PAT path stores after the read, logs and URL-embeds nothing, and the box is emptied
    local pt fw wf
    pt=$(memof "$1" pat | codeof /dev/stdin); fw=$(memof "$1" fetchWith | codeof /dev/stdin); wf=$(topof "$2" WayForm | codeof /dev/stdin)
    [ -n "$pt" ] && [ -n "$fw" ] && [ -n "$wf" ] || { echo "pat / fetchWith / WayForm not found"; return 1; }
    grep -qE 'Prefs\(|putSecret|putString|\.edit\(\)|Log\.|println\(|writeText|setAutheliaCredential' <<<"$pt$fw" && { echo "the PAT path stores, logs or writes outside the vault"; return 1; }
    grep -qF 'if (token.isBlank()) fail(ctx.getString(R.string.connect_pat_empty)) else fetchWith(ctx, "github", token.trim(), GITHUB_PAT, file = true)' <<<"$pt" || { echo "a blank token is not refused before any request"; return 1; }
    grep -qF 'ForgeClient(f, token).get(d.secretsFile, d.branch)' <<<"$fw" || { echo "the token does not ride the forge client (a header)"; return 1; }
    grep -qF 'if (o.ok && file) AccountVault(ctx).putConnection("forge.${f.id}.token", token)' <<<"$fw" || { echo "the token is filed before (or without) the read that proved it"; return 1; }
    grep -qE 'url *=.*\$token|token=|\?access_token' <<<"$fw" && { echo "the token reaches a URL"; return 1; }
    [ "$(grep -cF 'secret = ""; pass = ""' <<<"$wf")" -ge 2 ] || { echo "the credential box is not emptied on submit and on cancel"; return 1; }
    grep -qF 'val s = secret; val pw = pass' <<<"$wf" || { echo "the submit does not take the value before emptying the box"; return 1; }
    [ "$(grep -cF 'visualTransformation = PasswordVisualTransformation()' <<<"$wf")" -ge 2 ] || { echo "a credential box is not masked"; return 1; }
    grep -qF 'autoCorrectEnabled = false' <<<"$wf" && [ "$(grep -cF 'keyboardOptions = keep' <<<"$wf")" -ge 2 ] || { echo "a credential box is not kept out of the keyboard's learned words"; return 1; }
    return 0
}
pat_clean "$CW" "$AP" && ok "E: the token rides the forge client's header, is filed only after it read the vault, reaches no log or URL, and its box is emptied, masked and kept out of the keyboard's words" || bad "E: the PAT path keeps, logs, embeds or leaves the token on screen"
grep -qF 'api.github.com' <<<"$(codeof "$CW" "$AP" "$PP" "$GE")" && bad "E: the GitHub API host is a Kotlin literal" || ok "E: the forge API is the declared one, no host in Kotlin"
grep -qF 'GitSshVault.fetchArtifact(ctx.cacheDir, key, passphrase, decl().secretsFile)' "$CW" \
    && ok "E: the SSH way reads the declared vault file" || bad "E: the SSH way does not read the declared vault file"
grep -qE 'Log\.[a-z]\(|println\(' <<<"$(codeof "$CW")" && bad "E: ConnectWays logs (a token could reach the log)" || ok "E: ConnectWays never logs"
echo "-- E-mutation: the token stored, the token logged, the token filed before the read, a box left filled, the keyboard learning it --"
epm() {   # $1 = cw|ap, $2 = sed
    cp "$CW" "$TMP/e-cw.kt"; cp "$AP" "$TMP/e-ap.kt"
    local f; case $1 in cw) f=$CW;; ap) f=$AP;; esac
    sed -i "$2" "$TMP/e-$1.kt"; cmp -s "$f" "$TMP/e-$1.kt" && return 2
    ! pat_clean "$TMP/e-cw.kt" "$TMP/e-ap.kt" >/dev/null
}
for m in 'cw|s/        if (f.repo == null) return record(ctx, fail("forge .$forgeId. declares no vault repo yet"))/        if (f.repo == null) return record(ctx, fail("x")); ConfigsPrefs(ctx).putSecret("git", "github_token", token)/' \
         'cw|s/        val d = decl()$/        val d = decl(); android.util.Log.i("x", token)/' \
         'cw|s/if (o.ok \&\& file) AccountVault/if (file) AccountVault/' \
         'ap|s/                secret = ""; pass = ""$/                Unit/' \
         'ap|s/{ secret = ""; pass = ""; onCancel() }/{ onCancel() }/' \
         'ap|s/keyboardOptions = keep, singleLine = !ssh/singleLine = !ssh/' \
         'ap|s/visualTransformation = PasswordVisualTransformation(), keyboardOptions = keep, singleLine = true/keyboardOptions = keep, singleLine = true/'; do
    w="${m%%|*}"; e="${m#*|}"
    epm "$w" "$e"; rc=$?
    case $rc in 0) ok "E-mutation: caught - $w: ${e:0:80}";; 2) bad "E-mutation: did not apply - $w: ${e:0:80}";; *) bad "E-mutation: NOT caught - $w: ${e:0:80}";; esac
done

# --- G - GitHub WebAuth is gh's own sign-in; every way is the same tile ---------------------------------------
echo "== G: GitHub WebAuth is gh's own sign-in in the gh engine, and every way is the same tile =="
tiles_ok() {   # $1 = AccountPages.kt; every handler branch draws the one KitSelectableTile
    local pg; pg=$(topof "$1" AccountConnectPage | codeof /dev/stdin)
    local n; n=$(grep -cE '^ *(ConnectWays\.Handler\.[A-Za-z]+(, ConnectWays\.Handler\.[A-Za-z]+)*|is ConnectWays\.Handler\.Unwired) ->' <<<"$pg")
    [ "$n" -ge 5 ] || { echo "the dispatch has $n branches, not one per handler"; return 1; }
    local sel; sel=$(grep -cF 'KitSelectableTile(' <<<"$pg")
    [ "$sel" -ge 5 ] || { echo "only $sel tiles are drawn: a way draws something other than the shared tile"; return 1; }
    grep -qE 'Button\(' <<<"$(sed -n '/when (val h = ConnectWays.handler(way.kind))/,/^                        }/p' <<<"$pg")" && { echo "a way draws a plain button"; return 1; }
    return 0
}
msg=$(tiles_ok "$AP") && ok "G: gh, PAT/SSH/token, Authelia, file and unwired ways all draw the one KitSelectableTile" || bad "G: $msg"
gh_ok() {   # $1 = ConnectWays.kt, $2 = GhEngine.kt, $3 = SuperApp build.json, $4 = AccountPages.kt, $5 = Cloud Account build.json; prints the first broken rule
    # #1693 only Cloud Account signs in: the SuperApp's GitHub line keeps its WebAuth row as a LINK to Cloud Account
    # (kind cloud_account) and binds no gh engine; Cloud Account declares engines.gh and runs gh's own sign-in.
    local acc="${5:-$ACCJ}"
    local gl gp; gl=$(memof "$1" ghLogin | codeof /dev/stdin); gp=$(memof "$1" ghPrompt | codeof /dev/stdin)
    jq -e '.ui.profile.connect.lines[] | select(.id == "github") | .ways[] | select(.id == "webauth" and .kind == "cloud_account" and .provider == "github")' "$3" >/dev/null \
        || { echo "the GitHub line's WebAuth row is not a link to Cloud Account's GitHub sign-in"; return 1; }
    jq -e '.engines.gh == null' "$3" >/dev/null || { echo "the SuperApp still declares engines.gh: only Cloud Account binds the gh sign-in"; return 1; }
    jq -e '.engines.gh | .fleet == "lib-gh" and .min_contract >= 1 and (.rung | length > 0)' "$acc" >/dev/null || { echo "Cloud Account's engines.gh is not declared against the lib-gh row with its git-chain rung (AccountModel.ghHost reads it)"; return 1; }
    grep -qF 'if (!SignInOwner.isOwner(ctx)) return Outcome(false' <<<"$gl" || { echo "ghLogin starts gh's sign-in in a host that is not Cloud Account"; return 1; }
    grep -qF 'if (!owner) {' <<<"$(codeof "$4")" && grep -qF 'SignInOwner.open(ctx, provider)' <<<"$(codeof "$4")" || { echo "a non-owner host's Connect tiles do not link to Cloud Account"; return 1; }
    grep -qF 'ConnectWays.Handler.GhLogin -> KitSelectableTile(' <<<"$(codeof "$4")" && grep -qF 'ConnectWays.ghLogin(ctx) { code, page ->' <<<"$(codeof "$4")" || { echo "the WebAuth tile does not start ghLogin"; return 1; }
    grep -qF 'GhEngine(ctx)' <<<"$gl" && grep -qF 'engine.login(host)' <<<"$gl" && grep -qF 'engine.token(host)' <<<"$gl" || { echo "ghLogin does not run gh's own login through the engine"; return 1; }
    grep -qF 'is GhEngine.Check.NotInstalled -> return record(ctx, fail(ctx.getString(R.string.connect_gh_missing' <<<"$gl" && grep -qF 'is GhEngine.Check.TooOld -> return record(ctx, fail(ctx.getString(R.string.connect_gh_old' <<<"$gl" \
        || { echo "a missing or old engine is not its own sentence"; return 1; }
    grep -qF 'fetchWith(ctx, "github", token, GH_AUTH_LOGIN, file = false)' <<<"$gl" || { echo "the gh token does not read the vault export once, unfiled, and land like a sign-in"; return 1; }
    grep -qE 'putSecret|setAutheliaCredential|Prefs\(|Log\.|putConnection' <<<"$gl$gp" && { echo "the gh path stores or logs something"; return 1; }
    grep -qF 'AuthDeclaration.browserMission?.pkg' <<<"$gp" && grep -qF '.setPackage(browser)' <<<"$gp" || { echo "the sign-in page does not open in the fleet browser"; return 1; }
    grep -qF 'copySensitive(ctx, ctx.getString(R.string.connect_gh_code_clip), code)' <<<"$gp" || { echo "gh's one-time code is not copied as sensitive"; return 1; }
    grep -qF 'BuildConfig.GH_ENGINE_ACTION' "$2" || { echo "GhEngine does not find the engine by its declared action"; return 1; }
    return 0
}
msg=$(gh_ok "$CW" "$GE" "$BJ" "$AP") && ok "G: GitHub WebAuth = gh auth login in the gh engine, run only in Cloud Account (other hosts link there), page in the fleet browser, code copied as sensitive, token read once and never filed" || bad "G: $msg"
for loc in values values-es; do
    for s in connect_gh_missing connect_gh_old connect_gh_failed connect_gh_no_token connect_gh_prompt connect_gh_no_browser connect_gh_code_clip connect_pat_empty; do
        grep -q "name=\"$s\"" "$RES/$loc/strings.xml" || bad "G: $s missing from $loc"
    done
done
echo "-- G-mutation: a plain button for a way, WebAuth unwired, the page in any browser, the token stored or filed --"
gmut() {   # $1 = which check, $2 = file (cw|ap), $3 = sed
    cp "$CW" "$TMP/g-cw.kt"; cp "$AP" "$TMP/g-ap.kt"
    local f; case $2 in cw) f=$CW;; ap) f=$AP;; esac
    sed -i "$3" "$TMP/g-$2.kt"; cmp -s "$f" "$TMP/g-$2.kt" && return 2
    case $1 in gh) ! gh_ok "$TMP/g-cw.kt" "$GE" "$BJ" "$TMP/g-ap.kt" >/dev/null;; tiles) ! tiles_ok "$TMP/g-ap.kt" >/dev/null;; esac
}
for m in 'gh|cw|s/\.setPackage(browser)//' \
         'gh|cw|s/engine.token(host) ?: return record/engine.token(host).also { ConfigsPrefs(ctx).putSecret("git", "github_token", it.orEmpty()) } ?: return record/' \
         'gh|cw|s/fetchWith(ctx, "github", token, GH_AUTH_LOGIN, file = false)/fetchWith(ctx, "github", token, GH_AUTH_LOGIN, file = true)/' \
         'gh|cw|s/        if (code.isNotBlank()) copySensitive(ctx, ctx.getString(R.string.connect_gh_code_clip), code)//' \
         'gh|ap|s/ConnectWays.ghLogin(ctx) { code, page ->/ConnectWays.fetchNow(ctx) { code, page ->/' \
         'gh|cw|s/if (!SignInOwner.isOwner(ctx)) return Outcome(false/if (false) return Outcome(false/' \
         'gh|ap|s/if (!owner) {/if (false) {/' \
         'tiles|ap|s/ConnectWays.Handler.File -> KitSelectableTile(way.label, way.note, false, {/ConnectWays.Handler.File -> androidx.compose.material3.Button(onClick = {/'; do
    w="${m%%|*}"; r="${m#*|}"; fl="${r%%|*}"; e="${r#*|}"
    gmut "$w" "$fl" "$e"; rc=$?
    case $rc in 0) ok "G-mutation: caught - $w/$fl: ${e:0:80}";; 2) bad "G-mutation: did not apply - $w/$fl: ${e:0:80}";; *) bad "G-mutation: NOT caught - $w/$fl: ${e:0:80}";; esac
done
jq '(.ui.profile.connect.lines[] | select(.id == "github") | .ways) |= map(select(.kind != "cloud_account"))' "$BJ" > "$TMP/g1.json"
cmp -s "$BJ" "$TMP/g1.json" && bad "G-mutation: dropping GitHub WebAuth did not apply" \
    || { gh_ok "$CW" "$GE" "$TMP/g1.json" "$AP" >/dev/null && bad "G-mutation: GitHub WebAuth dropped was NOT caught" || ok "G-mutation: GitHub WebAuth dropped from the declaration -> RED"; }
# #1693 field parity: the SuperApp's GitHub line keeps every row it had (WebAuth + SSH / PAT), only WebAuth's kind changed.
jq -e '[.ui.profile.connect.lines[] | select(.id == "github") | .ways[].id] == ["webauth", "ssh_pat"]' "$BJ" >/dev/null \
    && ok "G: field parity - the GitHub line still has its WebAuth and SSH / PAT rows" || bad "G: the GitHub line lost a row (rule 16)"
jq '.engines.gh = {"fleet": "lib-gh", "action": "{package}.ENGINE", "min_contract": 1, "login_poll_ms": 1000, "login_max_ms": 960000}' "$BJ" > "$TMP/g2.json"
gh_ok "$CW" "$GE" "$TMP/g2.json" "$AP" >/dev/null && bad "G-mutation: engines.gh back in the SuperApp was NOT caught" || ok "G-mutation: engines.gh back in the SuperApp -> RED"
jq 'del(.engines.gh)' "$ACCJ" > "$TMP/g3.json"
gh_ok "$CW" "$GE" "$BJ" "$AP" "$TMP/g3.json" >/dev/null && bad "G-mutation: Cloud Account without engines.gh was NOT caught" || ok "G-mutation: Cloud Account without engines.gh -> RED"

# --- V - every sign-in lands the same way ---------------------------------------------------------------------
echo "== V: every way lands through one function: the schema gate, the vault's registry, S, and the way that fetched it =="
land_ok() {   # $1 = ConnectWays.kt, $2 = AccountModel.kt, $3 = AccountPages.kt; prints the first broken rule
    local ld lb si; ld=$(memof "$1" land | codeof /dev/stdin); si=$(memof "$1" signedIn | codeof /dev/stdin)
    lb=$(awk '/fun landBundle\(/{f=1} f{print} f&&/^        }$/{exit}' "$2" | codeof /dev/stdin)
    grep -qF 'AccountModel.landBundle(ctx, body, via)' <<<"$ld" || { echo "ConnectWays.land is not the model's landBundle"; return 1; }
    grep -qF 'VaultConnect.knownSchemaVersions' <<<"$ld" && grep -qF 'R.string.vault_connect_schema_unknown' <<<"$ld" || { echo "an unknown schema is not refused with its sentence"; return 1; }
    grep -qF 'unknownSchemaVersion(body, com.diegonmarcos.cloudlib.auth.VaultConnect.knownSchemaVersions)?.let { return it }' <<<"$lb" || { echo "landBundle has no schema gate before it stores"; return 1; }
    grep -qF 'UserRegistry.fromVault(bundle)' <<<"$lb" && grep -qF 'UserRegistry.adopt(ctx, it)' <<<"$lb" || { echo "a landing does not adopt the vault's registry"; return 1; }
    grep -qF 'imported.via = via' <<<"$lb" || { echo "a landing does not record its way"; return 1; }
    grep -qF 'get(ctx).landServer(bundle, via)' <<<"$lb" || { echo "a landing does not store the file as S"; return 1; }
    # Every caller of land() says which way fetched: the third argument is never absent.
    local calls; calls=$(codeof "$1" | grep -E '[^a-zA-Z]land\(ctx, ')
    [ "$(grep -c . <<<"$calls")" -ge 3 ] || { echo "fewer than three landings"; return 1; }
    [ "$(grep -vcE 'land\(ctx, [A-Za-z.]+, [^)]+\)' <<<"$calls")" = 0 ] || { echo "a landing does not say which way fetched it"; return 1; }
    grep -qF 'UserRegistry.remember(app, it)' <<<"$si" && grep -qF 'SignIn.Current.session = SignIn.Session(result.provider.id, who)' <<<"$si" || { echo "a sign-in does not remember the registry and set the session"; return 1; }
    grep -qF 'if (result.bearer.isNotBlank()) ConfigsPrefs(app).setAutheliaCredential(who, result.bearer) else null' <<<"$si" || { echo "the bearer is not stored paired with the address it proved"; return 1; }
    [ "$(codeof "$1" | grep -c 'setAutheliaCredential(')" = 1 ] || { echo "there is not exactly one bearer store"; return 1; }
    local pg; pg=$(codeof "$3")
    grep -qF 'ConnectWays.Handler.AutheliaWeb ->' <<<"$pg" && grep -qF 'SignInWays(host = signInHost' <<<"$pg" && grep -qF 'ConnectWays.signedIn(ctx, result)' <<<"$pg" || { echo "the Authelia way does not hand its result to ConnectWays.signedIn"; return 1; }
    return 0
}
msg=$(land_ok "$CW" "$AM" "$AP") && ok "V: file, forge, gh, PAT, SSH, token and fetch-now all land through land() -> landBundle; the Authelia sign-in stores its bearer once, paired with its address" || bad "V: $msg"
AUTHSRC="$APP/../ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth"
fr=$(awk '/    fun fromVault\(/{f=1} f{print} f&&/^    }$/{exit}' "$AUTHSRC/UserRegistry.kt" | codeof /dev/stdin)
grep -qF 'optString("peer")' <<<"$fr" && grep -qF '"vault_device", device' <<<"$fr" && grep -qF 'optJSONObject("electronics")' <<<"$fr" \
    && ok "V: the vault's registry is electronics.*.<device>.peer, keyed by the vault device" || bad "V: fromVault does not read electronics' peers"
pj=$(codeof "$AUTHSRC/ProfileJourney.kt")
grep -qF '|| vaultFetched' <<<"$pj" && grep -qF '!s.artifactInMemory && !s.vaultFetched -> Lock.REFETCH' <<<"$pj" \
    && ok "V: holding the vault export is a sign-in, and the next step is not locked behind a re-fetch" || bad "V: the journey ignores a vault landing"
echo "-- V-mutation: no schema gate, no registry adoption, a landing without its way, a second bearer store --"
vmut() {   # $1 = file (cw|am), $2 = sed
    cp "$CW" "$TMP/v-cw.kt"; cp "$AM" "$TMP/v-am.kt"
    local f; case $1 in cw) f=$CW;; am) f=$AM;; esac
    sed -i "$2" "$TMP/v-$1.kt"; cmp -s "$f" "$TMP/v-$1.kt" && return 2
    ! land_ok "$TMP/v-cw.kt" "$TMP/v-am.kt" "$AP" >/dev/null
}
for m in 'am|s/            imported.via = via/            Unit/' \
         'am|s/UserRegistry.adopt(ctx, it)/Unit/' \
         'am|s/get(ctx).landServer(bundle, via)/Unit/' \
         'cw|s/AccountModel.landBundle(ctx, body, via)?.let { v ->/null?.let { v ->/' \
         'cw|s/        val o = land(ctx, body, via)/        val o = land(ctx, body)/' \
         'cw|s/if (stored != null) fail(stored) else Outcome(true, "✓ signed in through/ConfigsPrefs(app).setAutheliaCredential(who, result.bearer); if (stored != null) fail(stored) else Outcome(true, "✓ signed in through/'; do
    w="${m%%|*}"; e="${m#*|}"
    vmut "$w" "$e"; rc=$?
    case $rc in 0) ok "V-mutation: caught - $w: ${e:0:80}";; 2) bad "V-mutation: did not apply - $w: ${e:0:80}";; *) bad "V-mutation: NOT caught - $w: ${e:0:80}";; esac
done

# --- F - the About topic and the working profile draw the whole schema ----------------------------------------
echo "== F: the declared schema is drawn whole - every declared section and field, filled or empty =="
schema_rows_cover() {   # $1 = build.json, $2 = bundle.json ("" = none); prints each declared field with no row; 1 iff any
    python3 - "$1" "$2" <<'PYF'
import json, sys
s = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["schema"]["sections"]
b = {}
if sys.argv[2]:
    b = json.load(open(sys.argv[2])); b = b.get("bundle", b)
SEP = " › "
def unfilled(v): return v is None or (isinstance(v, str) and not v.strip()) or (isinstance(v, (dict, list)) and not v)
def rows(sec, data):   # port of InfoMask.schemaRows: one row per field (or its leaves), then the extras
    out = []
    for f in sec["fields"]:
        v = data
        for seg in f.split(SEP): v = v.get(seg) if isinstance(v, dict) else None
        out.append(f if unfilled(v) else f)   # a filled field walks to >= 1 row under its own path
    tops = {f.split(SEP)[0] for f in sec["fields"]}
    out += [k for k in (data or {}) if k not in tops]
    return out
miss = []
if not s: miss.append("(no section declared)")
for sec in s:
    if not sec.get("fields"): miss.append(sec["id"] + " (no field declared)")
    r = rows(sec, b.get(sec["id"]))
    miss += ["%s › %s" % (sec["id"], f) for f in sec["fields"] if f not in r]
for m in miss: print(m)
sys.exit(1 if miss else 0)
PYF
}
schema_code_ok() {   # $1 = InfoMask.kt, $2 = AccountPages.kt, $3 = ProfilesPages.kt
    local sr; sr=$(awk '/fun schemaRows\(/{f=1} f{print} f&&/^    }$/{exit}' "$1" | codeof /dev/stdin)
    grep -qF 'for (field in section.fields) {' <<<"$sr" || { echo "schemaRows does not walk every declared field"; return 1; }
    grep -qF 'if (unfilled(v)) out += Row(field, "", Kind.EMPTY) else walk(section.id, v, field, out)' <<<"$sr" || { echo "an unfilled field is not an EMPTY row"; return 1; }
    grep -qF 'if (k !in tops) walk(section.id, o.opt(k), k, out)' <<<"$sr" || { echo "keys beyond the schema are dropped"; return 1; }
    local pg; pg=$(topof "$2" AccountProfilePage | codeof /dev/stdin)
    grep -qF 'InfoMask.declared.schemaRows(about, shown?.opt(about.id))' <<<"$pg" && grep -qF 'for (row in rows) {' <<<"$pg" || { echo "the About topic does not iterate every row of the declared schema"; return 1; }
    grep -qE '^ *return$|return@Column' <<<"$pg" && { echo "the profile page returns early, before the About topic"; return 1; }
    grep -qF 'InfoMask.Kind.EMPTY -> Text(if (editable) "Add" else "—"' <<<"$(codeof "$2")" || { echo "an EMPTY About row is not drawn as an empty, addable field"; return 1; }
    local wt; wt=$(awk '/^private fun workingTopics\(/{f=1} f{print} f&&/^}$/{exit}' "$3" | codeof /dev/stdin)
    grep -qF 'val schemaIds = InfoMask.schema.map { it.id }.filter { settings?.has(it) == true }' <<<"$wt" && grep -qF 'for (id in rest) out += id to settings?.optJSONObject(id)' <<<"$wt" \
        || { echo "the working profile drops a schema topic or an app that is not one"; return 1; }
    grep -qF 'out += "apps" to profile?.optJSONObject("apps")' <<<"$wt" && grep -qF 'out += "perms" to profile?.optJSONObject("perms")' <<<"$wt" && grep -qF 'out += "system" to profile?.optJSONObject("system")' <<<"$wt" || { echo "the working profile drops apps, perms or system"; return 1; }
    grep -qF 'if (leaves.isEmpty()) KitSettingsRow("Nothing captured yet")' <<<"$(codeof "$3")" || { echo "an empty topic is hidden instead of drawn as empty"; return 1; }
    return 0
}
msg=$(schema_code_ok "$IM" "$AP" "$PP") && ok "F: the About topic walks every declared field (empty ones addable, extras kept) and the working profile draws every topic, empty or not" || bad "F: $msg"
grep -q 'UI_PROFILE_INFOS_B64' "$APP/../ab_cloud-libs-shared/libs/account/build.gradle" && grep -qF 'parseSchema(baked?.optJSONObject("schema"))' "$IM" \
    && ok "F: the schema is baked with the mask and read off it" || bad "F: the schema is not baked or not read"
out=$(schema_rows_cover "$BJ" "") && ok "F: with nothing fetched, every declared field still draws (as empty) - $(jq '[.ui.profile.infos.schema.sections[].fields[]] | length' "$BJ") fields in $(jq '.ui.profile.infos.schema.sections | length' "$BJ") sections" || bad "F: fields without a row: $(echo $out)"
out=$(schema_rows_cover "$BJ" "$TMP/fixture.json") && ok "F: over the synthetic bundle every declared field draws" || bad "F: fields without a row: $(echo $out)"
VAULT_DIR=""
for v in "${CLOUD_VAULT:+$CLOUD_VAULT/Shared/configs}" "$APP/../../cloud-vault/Shared/configs" "$APP/../../../cloud-vault/Shared/configs"; do [ -f "$v/schema.json" ] && VAULT_DIR="$v" && break; done
schema_drift() {   # $1 = build.json, $2 = Shared/configs dir; prints the drift; 1 iff any
    python3 - "$1" "$2" <<'PYD'
import json, os, sys
mine = json.load(open(sys.argv[1]))["ui"]["profile"]["infos"]["schema"]
d = sys.argv[2]; vs = json.load(open(os.path.join(d, "schema.json")))
# emit.py's own rule (resolvers()): a resolver is any object carrying `kind` - no list of
# kinds here, so a resolver kind the vault adds (peer_devices, 11950b4) is a field, not a crash.
# #727 EVERY section directory, not only schema.json's list: schema.json's sections in
# its order, then each Shared/configs/<dir>/sources.json it does not list yet (STAGED).
listed = [s["id"] for s in vs["sections"]]
staged = sorted(x for x in os.listdir(d) if os.path.isfile(os.path.join(d, x, "sources.json")) and x not in listed)
want = []
for s in vs["sections"] + [{"id": x, "label": x.capitalize(), "staged": True} for x in staged]:
    items = json.load(open(os.path.join(d, s["id"], "sources.json")))["items"]; fields = []
    def walk(o, p):
        if "kind" in o: fields.append(p); return
        for k, v in o.items():
            if not k.startswith("_"): walk(v, (p + " › " if p else "") + k)
    walk(items, ""); want.append({"id": s["id"], "label": s.get("label", s["id"]), "fields": fields, "staged": bool(s.get("staged"))})
bad = []
if mine.get("schema_version") != vs.get("schema_version"): bad.append("schema_version %s != vault %s" % (mine.get("schema_version"), vs.get("schema_version")))
if [x["id"] for x in mine["sections"]] != [x["id"] for x in want]: bad.append("sections %s != vault %s" % ([x["id"] for x in mine["sections"]], [x["id"] for x in want]))
for a, b in zip(mine["sections"], want):
    a = {"id": a["id"], "label": a.get("label"), "fields": a.get("fields"), "staged": bool(a.get("staged"))}
    if a != b: bad.append("%s: declared %s != vault %s" % (a["id"], a, b))
for x in bad: print(x)
sys.exit(1 if bad else 0)
PYD
}
if [ -n "$VAULT_DIR" ]; then
    out=$(schema_drift "$BJ" "$VAULT_DIR") && ok "F: the declared skeleton IS cloud-vault's schema.json + sources.json (no drift)" || bad "F: the skeleton drifted from the vault: $out"
    out=$(schema_rows_cover "$BJ" "$VAULT_DIR/profile-secrets.json") && ok "F: over the REAL vault export every declared field draws (paths only, no value printed)" || bad "F: fields without a row in the real export: $(echo $out)"
else
    echo "  UNVERIFIABLE: cloud-vault is not checked out beside this repo (set CLOUD_VAULT to point at one) - the skeleton's drift check did not run (the shape checks above did; InfoMaskTest pins the section ids)"
fi
echo "-- F-mutation: a field dropped from the skeleton, a section dropped, fields skipped, extras dropped, a topic hidden --"
jq '.ui.profile.infos.schema.sections[2].fields |= .[1:]' "$BJ" > "$TMP/f1.json"
if cmp -s "$BJ" "$TMP/f1.json"; then bad "F-mutation: the field drop did not apply"
elif [ -n "$VAULT_DIR" ]; then schema_drift "$TMP/f1.json" "$VAULT_DIR" >/dev/null && bad "F-mutation: a dropped field was NOT caught by the drift check" || ok "F-mutation: a field dropped from the skeleton -> drift RED"
else echo "  UNVERIFIABLE: F-mutation field-drop needs cloud-vault beside"; fi
jq '.ui.profile.infos.schema.sections[0].fields = []' "$BJ" > "$TMP/f2.json"
schema_rows_cover "$TMP/f2.json" "" >/dev/null && bad "F-mutation: a section with no fields was NOT caught" || ok "F-mutation: a section emptied of fields -> RED"
fmut() {   # $1 = file var (IM|AP|PP), $2 = sed
    local f; eval f=\$$1; sed "$2" "$f" > "$TMP/f.kt"; cmp -s "$f" "$TMP/f.kt" && return 2
    case $1 in IM) ! schema_code_ok "$TMP/f.kt" "$AP" "$PP" >/dev/null;; AP) ! schema_code_ok "$IM" "$TMP/f.kt" "$PP" >/dev/null;; PP) ! schema_code_ok "$IM" "$AP" "$TMP/f.kt" >/dev/null;; esac
}
for m in "IM|s/        for (field in section.fields) {/        for (field in section.fields.take(1)) {/" \
         "IM|s/            if (unfilled(v)) out += Row(field, \"\", Kind.EMPTY) else walk(section.id, v, field, out)/            if (!unfilled(v)) walk(section.id, v, field, out)/" \
         "IM|s/if (k !in tops) walk(section.id, o.opt(k), k, out)/if (false) walk(section.id, o.opt(k), k, out)/" \
         "AP|s/InfoMask.declared.schemaRows(about, shown?.opt(about.id))/emptyList<InfoMask.Row>()/" \
         "PP|s/if (leaves.isEmpty()) KitSettingsRow(\"Nothing captured yet\")/Unit/" \
         "PP|s/    out += \"perms\" to profile?.optJSONObject(\"perms\")//"; do
    f="${m%%|*}"; e="${m#*|}"
    fmut "$f" "$e"; rc=$?
    case $rc in 0) ok "F-mutation: caught - $f: ${e:0:70}";; 2) bad "F-mutation: did not apply - $f: ${e:0:70}";; *) bad "F-mutation: NOT caught - $f: ${e:0:70}";; esac
done

# --- F2 - no vault section dropped; Setup > apps lists every state --------------------------------------------
echo "== F2: every vault section is drawable (the staged ones too), and Setup > apps lists every state of the working profile's apps =="
union_ok() {   # $1 = InfoMask.kt; prints the first broken rule
    local sf; sf=$(awk '/fun sectionsFor\(/{f=1} f{print} f&&/^        }$/{exit}' "$1" | codeof /dev/stdin)
    grep -qF 'schema.forEach { out[it.id] = it }' <<<"$sf" || { echo "sectionsFor drops the declared skeleton"; return 1; }
    grep -qF 'fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }' <<<"$sf" || { echo "sectionsFor drops a section the fetch's schema names"; return 1; }
    grep -qF 'bundle?.keys()?.forEach { k ->' <<<"$sf" && grep -qF 'out.getOrPut(k) { SchemaSection(k, k, emptyList()) }' <<<"$sf" \
        || { echo "sectionsFor drops a top-level key the bundle carries"; return 1; }
    grep -qF 'return out.values.toList()' <<<"$sf" || { echo "sectionsFor does not return what it gathered"; return 1; }
    return 0
}
apps_ok() {   # $1 = AppsPage.kt, $2 = AccountPages.kt; prints the first broken rule
    local c; c=$(codeof "$1")
    for sec in 'Section("Not installed · fleet"' 'Section("Not installed · vendor / F-Droid"' 'Section("Not installed · no source declared"' 'Section("Installed"'; do
        grep -qF "$sec" <<<"$c" || { echo "Setup > apps has no shelf $sec"; return 1; }
    done
    grep -qF 'SetupRunbook(ctx).appsPlan()' <<<"$c" || { echo "the shelves are not the Store's plan over the working profile"; return 1; }
    grep -qF 'SetupRunbook(ctx).handToStore()' <<<"$c" || { echo "Install missing does not hand over to the Store"; return 1; }
    grep -qF '"no source declared: add it to the source map"' <<<"$c" || { echo "an app with no source does not say so"; return 1; }
    grep -qE 'ACTION_INSTALL_PACKAGE|installPackage\(' <<<"$(codeof "$1" "$2" "$AR" "$AT")" && { echo "the Account installs on its own"; return 1; }
    return 0
}
msg=$(union_ok "$IM") && ok "F2: the schema is the skeleton U the fetch's schema U every top-level bundle key - no section is dropped" || bad "F2: $msg"
msg=$(apps_ok "$APG" "$AP") && ok "F2: Setup > apps shelves installed / fleet / vendor / no-source apps, hands the missing to the Store and installs nothing itself" || bad "F2: $msg"
for need in peers; do   # #570 apps left the staged list: it is in cloud-vault schema.json now
    jq -e --arg i "$need" '.ui.profile.infos.schema.sections[] | select(.id == $i and .staged == true and (.fields | length > 0))' "$BJ" >/dev/null \
        && ok "F2: the staged vault section '$need' is in the skeleton" || bad "F2: the staged vault section '$need' is missing from the skeleton"
done
echo "-- F2-mutation: a source of sections dropped, a shelf dropped, the hand-over unwired --"
for e in 's/            bundle?.keys()?.forEach { k ->/            emptyList<String>().forEach { k ->/' \
         's/            fetched.forEach { (id, label) -> out.getOrPut(id) { SchemaSection(id, label, emptyList()) } }/            Unit/' \
         's/            schema.forEach { out\[it.id\] = it }/            Unit/'; do
    sed "$e" "$IM" > "$TMP/u.kt"
    if cmp -s "$IM" "$TMP/u.kt"; then bad "F2-mutation: did not apply - ${e:0:70}"
    else union_ok "$TMP/u.kt" >/dev/null && bad "F2-mutation: NOT caught - ${e:0:70}" || ok "F2-mutation: caught - ${e:0:70}"; fi
done
for e in 's/Section("Not installed · no source declared"/Section("Not installed"/' \
         's/SetupRunbook(ctx).handToStore()/false/' \
         's/"no source declared: add it to the source map"/"?"/'; do
    sed "$e" "$APG" > "$TMP/a.kt"
    if cmp -s "$APG" "$TMP/a.kt"; then bad "F2-mutation: did not apply - ${e:0:70}"
    else apps_ok "$TMP/a.kt" "$AP" >/dev/null && bad "F2-mutation: NOT caught - ${e:0:70}" || ok "F2-mutation: caught - ${e:0:70}"; fi
done
jq '.ui.profile.infos.schema.sections |= map(select(.id != "peers"))' "$BJ" > "$TMP/s.json"
if cmp -s "$BJ" "$TMP/s.json"; then bad "F2-mutation: the staged-section drop did not apply"
elif [ -n "$VAULT_DIR" ]; then schema_drift "$TMP/s.json" "$VAULT_DIR" >/dev/null && bad "F2-mutation: the peers section dropped was NOT caught by the drift check" || ok "F2-mutation: the peers section dropped from the skeleton -> drift RED"
else echo "  UNVERIFIABLE: F2-mutation staged-section drop needs cloud-vault beside (InfoMaskTest pins the ids in CI)"; fi

# --- H - Runtime is per app; the Setup index, wizard and cockpit are gone --------------------------------------
echo "== H: the runtime is read per declared app, live; the old Setup index, wizard and cockpit are gone =="
runtime_ok() {   # $1 = AccountRuntime.kt, $2 = SuperApp build.json; prints the first broken rule
    local decl disp rt
    # An app declared `reports: false` exposes nothing to read, so it has no reader - only a status.
    decl=$(jq -r '.ui.vault_connect.cockpit.sections[] | select(.runtime.reports != false) | .apply' "$2" | sort | paste -sd' ' -)
    disp=$(awk '/    private fun readOne\(/{f=1} f&&/return when \(section.apply\) \{/{g=1;next} g&&/^            else ->/{exit} g' "$1" | grep -oE '^            "[a-z-]+" ->' | sed -E 's/^ *"([a-z-]+)" ->/\1/' | sort | paste -sd' ' -)
    [ "$decl" = "$disp" ] || { echo "declared apps [$decl] != read [$disp]"; return 1; }
    local missing; missing=$(jq -r '[.ui.vault_connect.cockpit.sections[] | select((.runtime.served_by // "") == "") | .id] | join(",")' "$2")
    [ -z "$missing" ] || { echo "app(s) $missing declare no runtime served_by"; return 1; }
    rt=$(awk '/    private fun readOne\(/{f=1} f{print} f&&/^    }$/{exit}' "$1")
    grep -qF 'return base.copy(status = Status.NOT_INSTALLED, detail = rt.servedBy)' <<<"$rt" || { echo "an absent serving package is not 'not installed'"; return 1; }
    grep -qF 'if (!rt.reports) return base.copy(status = Status.NOT_REPORTING, detail = rt.why.ifBlank { rt.servedBy })' <<<"$rt" || { echo "an app that exposes nothing is not 'not reporting' (with its declared why)"; return 1; }
    [ "$(jq -r '.ui.vault_connect.cockpit.sections[] | select(.id == "keyboard") | .runtime.served_by' "$2")" = text_tools ] || { echo "the keyboard is not read over text tools"; return 1; }
    grep -qF 'client!!.clipboardLists()' <<<"$rt" || { echo "the keyboard reader does not read its lists"; return 1; }
    grep -qF 'coverage(runCatching { readOne(ctx, section, declared, deadlineMs) }' "$1" && grep -qF 'VaultCockpit.layout.vaultFields)' "$1" || { echo "a reading is not counted against vault_fields"; return 1; }
    grep -qF 'while (!client.isConnected() && SystemClock.elapsedRealtime() < until) Thread.sleep(100)' <<<"$rt" || { echo "the binder read does not wait (under a deadline) for a stopped app to wake"; return 1; }
    grep -qF 'readOnly = if (rt.writable) emptySet() else values.keys' <<<"$rt" || { echo "a non-writable app's fields can be pulled back"; return 1; }
    return 0
}
msg=$(runtime_ok "$AR" "$BJ") && ok "H: every declared app ($(jq -r '[.ui.vault_connect.cockpit.sections[].id] | join(", ")' "$BJ")) has a live reader with its status" || bad "H: $msg"
setup_gone() {   # $1 = build.json, $2 = the profile package dir; prints what survives
    local g f; for f in "$CW" "$AP" "$PP" "$APG" "$AT" "$MAIN"; do
        for g in 'private fun renderFleetIndex(' 'private fun renderImported(' 'private fun renderWizard(' 'private fun renderRepos(' 'private fun renderSetup(' 'private fun renderInfos(' 'setup-index:' 'renderDeviceSelector(' 'renderConfigApply' 'renderPerson' 'ConfigAutoImport' 'setup_config_header' 'setup_person_header' 'ProfileFragment' 'FleetCockpitView' 'ProfileJourneyView'; do
            grep -qF "$g" <<<"$(codeof "$f")" && { echo "$g survives in $(basename "$f")"; return 1; }
        done
    done
    for g in Wizard.kt ConfigAutoImport.kt ProfileFragment.kt FleetCockpitView.kt ProfileJourneyView.kt; do
        [ -f "$2/$g" ] && { echo "$g survives"; return 1; }
    done
    jq -e '.ui.profile.wizard' "$1" >/dev/null && { echo "ui.profile.wizard survives"; return 1; }
    grep -qF 'name="setup_config_header"' "$RES/values/strings.xml" && { echo "the 'Your config' string survives"; return 1; }
    return 0
}
msg=$(setup_gone "$BJ" "$PKG") && ok "H: the Setup index, wizard, cockpit cards, repos and the View-based page are deleted, not hidden" || bad "H: $msg"
echo "-- H-mutation: a reader dropped, a served_by dropped, the wake-wait dropped, the wizard declared again, the old page back --"
for e in 's/^            "drive" -> {/            "drive_x" -> {/' \
         's/            while (!client.isConnected() \&\& SystemClock.elapsedRealtime() < until) Thread.sleep(100)/            Unit/' \
         's/            "keyboard" -> {/            "keyboard_x" -> {/' \
         's/            coverage(runCatching { readOne(ctx, section, declared, deadlineMs) }.getOrElse {/            (runCatching { readOne(ctx, section, declared, deadlineMs) }.getOrElse {/' \
         's/                    readOnly = if (rt.writable) emptySet() else values.keys)/                    readOnly = emptySet())/'; do
    sed "$e" "$AR" > "$TMP/h.kt"
    if cmp -s "$AR" "$TMP/h.kt"; then bad "H-mutation: did not apply - ${e:0:70}"
    else runtime_ok "$TMP/h.kt" "$BJ" >/dev/null && bad "H-mutation: NOT caught - ${e:0:70}" || ok "H-mutation: caught - ${e:0:70}"; fi
done
jq '(.ui.vault_connect.cockpit.sections[] | select(.id == "ai")) |= del(.runtime)' "$BJ" > "$TMP/h.json"
cmp -s "$BJ" "$TMP/h.json" && bad "H-mutation: the served_by drop did not apply" \
    || { runtime_ok "$AR" "$TMP/h.json" >/dev/null && bad "H-mutation: an app with no served_by was NOT caught" || ok "H-mutation: an app with no runtime served_by -> RED"; }
jq '.ui.profile.wizard = {"steps": []}' "$BJ" > "$TMP/h2.json"
setup_gone "$TMP/h2.json" "$PKG" >/dev/null && bad "H-mutation: the wizard declared again was NOT caught" || ok "H-mutation: the wizard declared again -> RED"
mkdir -p "$TMP/pkg" && : > "$TMP/pkg/ProfileFragment.kt"
setup_gone "$BJ" "$TMP/pkg" >/dev/null && bad "H-mutation: the View-based page back was NOT caught" || ok "H-mutation: the View-based page back -> RED"

echo
echo "passed=$PASS failed=$FAIL"
[ "$FAIL" = 0 ]
