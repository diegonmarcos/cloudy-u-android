#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #629 — the git credential comes from the VAULT, and the browser grant is  ║
# ║ a fallback that says so when the provider refuses it                      ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# WHY THIS EXISTS, in the owner's words: "why GitHub Apps??? it is just a Git
# Auth to get its ssh key!!!" He was right, and #641 finished the argument: the
# provider is DELETED, not documented.
#
# ON THE PROVIDER SIDE the browser road was never open. The declared client was a
# GitHub APP (the client_id prefix GitHub gives App clients) and GitHub Apps ship
# with Device Flow OFF, so every press of Start answered `device_flow_disabled -
# Device Flow must be explicitly enabled for this App`. That is a switch in the
# provider's own settings and NO code can reach it, so after the owner hit it a
# third time the provider and its two remedy rows were removed. A browser git
# login would need a GitHub OAuth App — a different kind of app, which does not
# exist yet — so there is NO fallback: the vault credential is the only git auth.
#
# ON OUR SIDE the credential was already on the phone. cloud-vault
# Shared/configs/git/sources.json declares git.github_token (and an ssh key pair),
# the #566 emitter really does put it in the artifact, and #608's DriveAuthApply
# already writes it into libs:git-sync's keystore credential store. The browser
# ceremony was duplicating a credential we had — and adding a provider setting as
# a single point of failure on top of it.
#
#   A1  the vault credential is READ, from the engines' own store under the
#       DECLARED credential_id, and the listing runs off it with no tap.
#   A2  a vault import writes it even when NOTHING is cloned yet — the fresh-phone
#       case, where the old plan() reported not-ok and threw the token away.
#   A3  #641 INVERTED: there is NO browser grant on the page at all — no sign-in
#       surface, no Start button, no provider named — and the credential-absent
#       line says so in its own words, with no breadcrumb to another page.
#   A4  there is no second credential store: the token goes into, and comes out
#       of, libs:git-sync's GitCredentialStore and nowhere else.
#   R1  #641 INVERTED: the grant_remedies MECHANISM stays declared in one place
#       (ab_cloud-libs-shared/build.json::auth.grant_remedies) with libs:auth as
#       its single consumer, and the two rows that existed only to word the
#       GitHub App's dead end are gone, as is the provider itself.
#   MUT mutation-proof: a credential resolved from somewhere other than the
#       vault-delivered config, a sign-in surface put back on the page, the
#       github provider re-declared and an emptied remedy table each go RED.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SRC="$APP/app/src/main/java/com/diegonmarcos/clouddrive"
PAGE="$SRC/sync/GitReposScreen.kt"
APPLY="$SRC/configs/DriveAuthApply.kt"
BJ="$APP/build.json"
STR="$APP/app/src/main/res/values/strings.xml"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
AUTH_DECL="$ROOT/ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/AuthDeclaration.kt"
SIGNIN_UI="$ROOT/ab_cloud-libs-shared/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
for required in "$PAGE" "$APPLY" "$BJ" "$STR" "$SHARED_BJ" "$AUTH_DECL" "$SIGNIN_UI"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# a1 <GitReposScreen.kt> : the vault credential is the one the page starts from
a1() {
    local f="$1" bad=0
    grep -qE 'DriveAuthApply\.vaultGitToken\(ctx\.applicationContext\)' "$f" \
        || { echo "    the page does not read the vault-delivered credential"; bad=1; }
    grep -qE 'login = login\.copy\(identity = page\.owner, token = vault, fromVault = true\)' "$f" \
        || { echo "    a vault credential does not become the page's login"; bad=1; }
    grep -qE 'fetchListing\(vault\)' "$f" \
        || { echo "    the personal listing is not fetched with the vault credential — the user would still have to tap"; bad=1; }
    grep -qE 'login\.fromVault -> stringResource\(R\.string\.git_login_from_vault' "$f" \
        || { echo "    the card does not state that the credential is the vault's"; bad=1; }
    return $bad
}

# a3 <GitReposScreen.kt> <strings.xml> : #641 no browser login on the page, in any shape
a3() {
    local f="$1" str="$2" bad=0
    # #653 NARROWED, ON PURPOSE, AND STILL LOAD-BEARING. This forbade ANY libs:auth
    # sign-in surface on the page, because when it was written the only login the page
    # could host was a GITHUB device grant — the thing #641 had just deleted. That flow
    # no longer exists anywhere in this app, and the surface the page must now host is
    # the fleet's OWN browser login: `authelia_web`, which is what makes the
    # credential-free path possible at all. Forbidding it would forbid the fix.
    #
    # So the assertion becomes the one that always mattered: NO GITHUB SIGN-IN SURFACE.
    # A sign-in surface is permitted ONLY when it is scoped to the DECLARED
    # session_provider of the fleet rung, read off the declaration rather than typed —
    # so the page cannot grow a second provider without changing the declaration, and
    # cannot name github at all.
    # #1693 NARROWED FURTHER: Cloud Account is the one app that signs in, so the page hosts NO libs:auth sign-in
    # surface at all (scoped or not): its buttons are links (AccountClient.openSignIn).
    if grep -qE 'SignInWays|SignInHost|SignInResult|webauth' "$f"; then
        echo "    a sign-in surface is hosted on the page: only Cloud Account signs in (the page links to it)"; bad=1
    fi
    # github must not be nameable as a sign-in way here under any spelling.
    grep -qiE 'policy *= *listOf\([^)]*github|Open\.Device|DeviceFlowDialog|SignIn\.Kind\.DEVICE_FLOW' "$f" \
        && { echo "    a GitHub / device-flow sign-in surface is back on the page"; bad=1; }
    # `webauth` was the deleted GitHub way's own id and must never reappear as one.
    grep -qE 'webauth' "$f" \
        && { echo "    the deleted GitHub webauth way is named on the page again"; bad=1; }
    # The page states which of the TWO states it is in, and nothing else.
    grep -qE 'R\.string\.git_login_vault_absent' "$f" \
        || { echo "    the page does not say anything when there is no vault credential"; bad=1; }
    # #646 INVERTED, on measured grounds. #641 demanded this line end "there is no browser
    # login" — true then, because the only GitHub client we had declared was a GitHub APP and
    # GitHub Apps ship Device Flow OFF. The official gh binary carries GITHUB'S OWN app ids and
    # both return live device codes, so that sentence is now false and must be GONE, replaced by
    # the declared chain. The half of A3 that still holds is above and below: no libs:auth
    # sign-in surface on this page, and no breadcrumb to a provider setting.
    grep -qE 'git_login_vault_absent">[^<]*declared chain' "$str" \
        || { echo "    the credential-absent line does not name the declared chain as the way out"; bad=1; }
    grep -qE 'git_login_vault_absent">[^<]*no browser login' "$str" \
        && { echo "    the credential-absent line still denies a browser login, which #646 measured to be false"; bad=1; }
    # #639: no breadcrumbs. The page may not send the owner to another page or to a provider setting.
    grep -qE 'settings/apps|settings/developers|Configs . Sign in' "$f" "$str" \
        && { echo "    the page points at a provider setting or another page instead of saying its own state"; bad=1; }
    return $bad
}

# a4 <DriveAuthApply.kt> : one store, the declared id, and a token with no clone still lands
a4() {
    local f="$1" bad=0
    grep -qE 'val credentialIds: Map<String, String> get\(\) = Declarations\.authCredentialIds' "$f" \
        || { echo "    the credential id is not read off the declaration"; bad=1; }
    grep -qE 'credentialIds\[step\.section\]\?\.takeIf \{ it\.isNotBlank\(\) \}\?\.let \{ store\.setSecret\(it, token!!\) \}' "$f" \
        || { echo "    the vault credential is not written under its declared id"; bad=1; }
    grep -qE 'targets\.isEmpty\(\) && credential\.isNotBlank\(\) ->' "$f" \
        || { echo "    a fresh phone (no clone yet) still throws the vault token away"; bad=1; }
    grep -qE 'store\.authFor\(ManagedRepo\(id = id' "$f" \
        || { echo "    the credential is not read back through libs:git-sync's own auth"; bad=1; }
    # No second store: nothing here may open prefs, a file or a keystore of its own.
    if grep -qE 'EncryptedSharedPreferences|getSharedPreferences|MasterKey|KeyStore' "$f"; then
        echo "    this file opens a credential store of its own — libs:git-sync's is the only one"; bad=1
    fi
    return $bad
}

# r1 <shared build.json> : the remedy mapping is declared, in one place, and covers the real error
r1() {
    python3 - "$1" <<'PYTHON'
import json, re, sys
auth = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]
rows = auth.get("grant_remedies") or []
bad = 0
if not rows:
    print("    auth.grant_remedies is not declared: the error would be shown with no remedy"); bad += 1
# #641 INVERTED. The rows that existed only to word the GitHub App's dead end are gone with
# the provider; a row matching it again means the provider came back.
dead = [r for r in rows if "device_flow_disabled" in (r.get("match") or "")
        or "Device Flow must be explicitly enabled" in (r.get("match") or "")]
if dead:
    print("    %d remedy row(s) still word the deleted GitHub device grant: %r"
          % (len(dead), [r.get("match") for r in dead])); bad += 1
for r in rows:
    if not (r.get("match") or "").strip():
        print("    a remedy row matches nothing"); bad += 1
    if not (r.get("remedy") or "").strip():
        print("    row %s carries no remedy, which is the whole point" % r.get("match")); bad += 1
    if "settings/apps" in ((r.get("remedy") or "") + " " + (r.get("url") or "")):
        print("    row %s still points at a GitHub App setting" % r.get("match")); bad += 1
# The providers themselves, in the same file: Google's device grant and GitHub's identity-only OAuth App.
providers = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["sign_in"]["providers"]
flows = [p for p in providers if p.get("kind") == "device_flow"]
# #1083 GitHub is back in the SHARED sign-in list as the owner's own OAuth App (Ov23li id, secret-less,
# identity only); #641's deleted provider was a GitHub APP (Iv1./Iv23 ids, Device Flow OFF). The git
# credential still comes from the vault/chain, so the sign-in github may never grant anything but identity.
if [p.get("id") for p in flows] != ["google", "github"]:
    print("    the device grants are %r; exactly Google's and the GitHub OAuth App's are expected"
          % [p.get("id") for p in flows]); bad += 1
gh = next((p for p in providers if p.get("id") == "github"), {})
if gh.get("grants") != ["identity"] or gh.get("client_secret") or not (gh.get("client_id") or "").startswith("Ov23li"):
    print("    the github provider must be identity-only, secret-less and an OAuth App (Ov23li client id)"); bad += 1
if re.search(r"Iv1\.|Iv23", open(sys.argv[1], encoding="utf-8").read()):
    print("    a GitHub App client id is back in the shared declaration"); bad += 1
print("    declared remedies: %d" % len(rows))
sys.exit(1 if bad else 0)
PYTHON
}

# r2 <AuthDeclaration.kt> <SignInUi.kt> : one reader, one consumer
r2() {
    local decl="$1" ui="$2" bad=0
    grep -qE 'fun remedyFor\(message: String' "$decl" || { echo "    libs:auth has no lookup for a declared remedy"; bad=1; }
    grep -qE 'fun explain\(message: String' "$decl" || { echo "    libs:auth has no one wording rule for an error plus its remedy"; bad=1; }
    grep -qE 'root\.optJSONArray\("grant_remedies"\)' "$decl" || { echo "    the declaration is not parsed"; bad=1; }
    grep -qE 'AuthDeclaration\.explain\(phase\.message\)' "$ui" || { echo "    the device-grant failure is still worded without its remedy"; bad=1; }
    # The remedy text may exist in exactly one place: the declaration.
    if grep -rqE 'Device Flow must be explicitly enabled|settings/apps' "$decl" "$ui"; then
        echo "    the remedy sentence is hardcoded in Kotlin — it belongs to the declaration alone"; bad=1
    fi
    return $bad
}

echo "── A the vault is the primary git credential path ──"
a1 "$PAGE" && pass "the page authenticates from the vault-delivered credential and lists with it, untapped" || fail "the git credential does not come from the vault-delivered config"
a3 "$PAGE" "$STR" && pass "#653 no GITHUB sign-in surface on the page (the one it hosts is scoped to the declared session_provider), no provider-setting breadcrumb, and the credential-absent line names the declared chain" || fail "the page hosts a GitHub or unscoped sign-in surface, points at a provider setting, or misstates the credential-absent case"
a4 "$APPLY" && pass "one store, the declared credential_id, and a fresh phone keeps the token" || fail "the vault credential is not stored/read through libs:git-sync's own store"
python3 - "$BJ" <<'PYTHON'
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["applies"]["git"]
bad = 0
if g.get("key") != "github_token":
    print("    auth.applies.git.key is %s; the artifact's git section carries github_token" % g.get("key")); bad += 1
if not (g.get("credential_id") or "").strip():
    print("    auth.applies.git declares no credential_id: the vault token would have nowhere to live"); bad += 1
sys.exit(1 if bad else 0)
PYTHON
if [ $? -eq 0 ]; then pass "the artifact key and the credential id are both declared"; else fail "auth.applies.git is not declared for the vault path"; fi

echo "── R the error→remedy mapping is DECLARED ──"
r1 "$SHARED_BJ" && pass "#641 the remedy mechanism is declared, the GitHub rows are gone and so is the provider" || fail "the remedy table or the provider list still carries the deleted GitHub device grant"
r2 "$AUTH_DECL" "$SIGNIN_UI" && pass "libs:auth is the one reader and the one consumer; no remedy string in Kotlin" || fail "the remedy is not read from the declaration / is hardcoded"

echo "── MUT mutations ──"
MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT

copy="$MUT/page1.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('DriveAuthApply.vaultGitToken(ctx.applicationContext)','DrivePrefs(ctx).getString()')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if a1 "$copy" >/dev/null 2>&1; then fail "MUT a credential from somewhere other than the vault passed — A1 does not hold"; else pass "MUT the credential resolved outside the vault-delivered config goes RED"; fi

# #653 THE ASSERTION THAT MUST SURVIVE THE NARROWING. A3 now PERMITS a sign-in surface
# scoped to the declared session_provider, so the old mutation (any SignInWays at all)
# would no longer be caught — and a guard that is narrowed without re-proving the half it
# keeps has become decoration. These three mutations prove the kept half is live: a GITHUB
# sign-in surface on this page still goes RED, under three different spellings.
copy="$MUT/page2.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('        Text(','        SignInWays(host = fleetHost, policy = listOf(\"github\"))\n        Text(',1)
open(p,'w',encoding='utf-8').write(s)" "$copy"
cmp -s "$PAGE" "$copy" && fail "MUT the mutation did not change the page (tester is stale)" \
    || { if a3 "$copy" "$STR" >/dev/null 2>&1; then fail "MUT a github sign-in surface back on the page passed — A3 does not hold"; else pass "MUT a github-scoped sign-in surface on the page goes RED"; fi; }

# The same thing said with the DEVICE-FLOW dialog instead of a policy id.
copy="$MUT/page2b.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('        Text(','        if (p.kind == SignIn.Kind.DEVICE_FLOW) DeviceFlowDialog(p)\n        Text(',1)
open(p,'w',encoding='utf-8').write(s)" "$copy"
cmp -s "$PAGE" "$copy" && fail "MUT the device-flow mutation did not change the page (tester is stale)" \
    || { if a3 "$copy" "$STR" >/dev/null 2>&1; then fail "MUT a device-flow dialog back on the page passed — A3 does not hold"; else pass "MUT a device-flow dialog on the page goes RED"; fi; }

# And the loophole the narrowing could have opened: an UNSCOPED sign-in surface, which
# offers every declared way and would therefore offer a github one the moment a github
# provider is ever declared again.
copy="$MUT/page2c.kt"; cp "$PAGE" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('        Text(','        SignInWays(host = fleetHost)\n        Text(',1)
open(p,'w',encoding='utf-8').write(s)" "$copy"
cmp -s "$PAGE" "$copy" && fail "MUT the unscoped mutation did not change the page (tester is stale)" \
    || { if a3 "$copy" "$STR" >/dev/null 2>&1; then fail "MUT an UNSCOPED sign-in surface passed — A3's scoping requirement is inert"; else pass "MUT dropping the declared scope from the page's sign-in goes RED"; fi; }

# #646 repointed at the line that exists now. The invariant is unchanged: prose that sends the
# owner to a provider setting instead of stating the page's own state must go RED.
copy="$MUT/breadcrumb.xml"; cp "$STR" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('the declared chain can fetch one','enable Device Flow at github.com/settings/apps')
open(p,'w',encoding='utf-8').write(s)" "$copy"
cmp -s "$STR" "$copy" && fail "MUT the breadcrumb mutation did not change the string table (tester is stale)" \
    || { if a3 "$PAGE" "$copy" >/dev/null 2>&1; then fail "MUT prose pointing at the provider setting passed — A3 does not hold"; else pass "MUT a breadcrumb back to github.com/settings/apps goes RED"; fi; }

# #646 AND THE NEW HALF: the deleted claim must not creep back. A build that re-denies the
# browser login is a build whose page contradicts the chain it now ships.
copy="$MUT/redenied.xml"; cp "$STR" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('failing that, the declared chain can fetch one','there is no browser login')
open(p,'w',encoding='utf-8').write(s)" "$copy"
cmp -s "$STR" "$copy" && fail "MUT the re-denial mutation did not change the string table (tester is stale)" \
    || { if a3 "$PAGE" "$copy" >/dev/null 2>&1; then fail "MUT re-denying the browser login passed — the #646 inversion is not pinned"; else pass "MUT the page re-denying that a browser login exists goes RED"; fi; }

copy="$MUT/apply.kt"; cp "$APPLY" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('targets.isEmpty() && credential.isNotBlank() ->','targets.isEmpty() && false ->')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if a4 "$copy" >/dev/null 2>&1; then fail "MUT a fresh phone discarding the vault token passed — A4 does not hold"; else pass "MUT the fresh-phone case throwing the token away goes RED"; fi

copy="$MUT/shared.json"; cp "$SHARED_BJ" "$copy"
python3 -c "
import json,sys;p=sys.argv[1];d=json.load(open(p,encoding='utf-8'))
d['auth']['sign_in']['providers'].append({'id':'github','label':'GitHub','kind':'device_flow','device_code_url':'https://github.com/login/device/code','token_url':'https://github.com/login/oauth/access_token','client_id':'Ov23liOg9JhezyYUCHmS','client_secret':'','scope':'repo','grants':['identity','repo_artifact']})
d['auth']['grant_remedies'].insert(0,{'match':'device_flow_disabled','remedy':'Enable it at github.com/settings/apps.','url':'https://github.com/settings/apps'})
json.dump(d,open(p,'w',encoding='utf-8'))" "$copy"
if r1 "$copy" >/dev/null 2>&1; then fail "MUT the re-declared github provider passed — R1 does not hold"; else pass "MUT the github device grant and its remedy row re-declared go RED"; fi

copy="$MUT/noremedy.json"; cp "$SHARED_BJ" "$copy"
python3 -c "
import json,sys;p=sys.argv[1];d=json.load(open(p,encoding='utf-8'))
d['auth']['grant_remedies']=[]
json.dump(d,open(p,'w',encoding='utf-8'))" "$copy"
if r1 "$copy" >/dev/null 2>&1; then fail "MUT an emptied remedy table passed — the MECHANISM is not pinned"; else pass "MUT the remedy mechanism deleted goes RED (Google still needs it)"; fi

copy="$MUT/ui.kt"; cp "$SIGNIN_UI" "$copy"
python3 -c "
import sys;p=sys.argv[1];s=open(p,encoding='utf-8').read()
s=s.replace('AuthDeclaration.explain(phase.message)','phase.message')
open(p,'w',encoding='utf-8').write(s)" "$copy"
if r2 "$AUTH_DECL" "$copy" >/dev/null 2>&1; then fail "MUT an unworded failure passed — R2 does not hold"; else pass "MUT the failure shown without its remedy goes RED"; fi

a1 "$PAGE" >/dev/null 2>&1 && a3 "$PAGE" "$STR" >/dev/null 2>&1 && a4 "$APPLY" >/dev/null 2>&1 \
    && r1 "$SHARED_BJ" >/dev/null 2>&1 && r2 "$AUTH_DECL" "$SIGNIN_UI" >/dev/null 2>&1 \
    && pass "MUT control: the unmutated sources pass every mutated check" \
    || fail "MUT control: the unmutated sources do NOT pass — the mutations above prove nothing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-vault-credential: all checks passed"; else echo "test-drive-git-vault-credential: $FAILURES check(s) FAILED"; exit 1; fi
