#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #689 — the GitHub leg of Sync ▸ Git is gh ITSELF, and the fleet has NO     ║
# ║ GitHub OAuth App: no client id, no client secret, no oauth landing, ever   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# 5e53a5364 built a GitHub web sign-in on an OAuth App of the fleet's own (a
# declared web_client with client_id/client_secret, a secret baked from the
# vault, a redirect landing under the fleet's API host, and a browser capture
# for it). The decision it contradicted stands: the GitHub leg is the pinned
# GitHub CLI (libs:gh), whose `gh auth login` carries GitHub CLI's OWN client
# id, so the fleet registers, declares, bakes and stores none of its own. #689
# deleted that path; this tester makes it impossible to rebuild quietly. Every
# check asserts on the MESSAGE, and every mutation is PROVEN APPLIED before its
# red is counted (a mutation that does not mutate reads exactly like a guard
# that works).
#
#   G1  NO DECLARATION carries a GitHub OAuth-App client for git: no git_chain
#       rung (at any depth) holds client_id / client_secret / redirect_uri /
#       authorize_url / token_url / web_client / secret_env / secret_vault_path;
#       no auth or git-page value names a GitHub OAuth endpoint or an oauth
#       landing; the gh pin declares no client id or device endpoint of ours.
#   G2  NO SOURCE rebuilds it: across the auth, gh and git-sync libraries, the
#       drive app and the browser app (code, gradle, manifests, resources),
#       nothing names the deleted flow, its client, its secret seam, its oauth
#       landing, or the browser's redirect-landing capture.
#   G3  THE GITHUB LEG IS gh: the github rung declares where gh points; GhRunner
#       runs gh's own `auth login` (never --with-token, never a token in its
#       env) and answers a clone's credential through gh's own git-credential
#       helper, never into a Result; the page dispatches the GitHub way to it,
#       lists with `gh repo list`, clones gh's rows in-process into the ONE
#       store with the listing's own URL, and says every failure LOUDLY with
#       its next step; a gh that cannot even start is an outcome the page
#       words, never an exception that crashes it. #705: gh runs in the gh
#       ENGINE (Cloud-Lib-Gh.apk) and the page reaches it through GhEngine, so
#       the next step for a missing or broken gh is the Store, not a reinstall
#       of Cloud Drive (test-drive-gh-engine.sh pins the client itself).
#   G4  THE PIN'S PATTERNS ARE MEASURED: the device-code and page patterns the
#       phone runs (gh-binary.json::login_output, baked into GhOutput) are
#       EXECUTED here against the transcript measured on the pinned binary.
#   MUT each property, broken on a copy (and proven broken), goes red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SHARED="$ROOT/ab_cloud-libs-shared"
SHARED_BJ="$SHARED/build.json"
BJ="$APP/build.json"
PIN="$SHARED/libs/gh/data/gh-binary.json"
GH_GRADLE="$SHARED/libs/gh/build.gradle"
RUNNER="$SHARED/libs/gh/src/main/java/com/diegonmarcos/cloudlib/gh/GhRunner.kt"
PAGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
LIST="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitHubRepos.kt"
COORD="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitSyncCoordinator.kt"
WIRING="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/DriveGitChain.kt"
STR="$APP/app/src/main/res/values/strings.xml"
for required in "$SHARED_BJ" "$BJ" "$PIN" "$GH_GRADLE" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$WIRING" "$STR"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# The source set G2 reads: the libraries the git leg is built from and the two apps that
# carry it. Tests are excluded on purpose — they PLANT the forbidden shapes to prove red.
SCAN_ROOTS=(
    "$SHARED/libs/auth" "$SHARED/libs/gh" "$SHARED/libs/git-sync"
    "$APP/app/src/main" "$APP/app/build.gradle"
    "$ROOT/ac_cloud-browser/app/src/main" "$ROOT/ac_cloud-browser/app/build.gradle"
)
mapfile -t SCAN < <(find "${SCAN_ROOTS[@]}" -type f \( -name '*.kt' -o -name '*.java' -o -name '*.gradle' -o -name '*.xml' \) \
    -not -path '*/src/test/*' -not -path '*/build/*' 2>/dev/null | sort)
[ "${#SCAN[@]}" -ge 20 ] || { echo "ERROR G2 found only ${#SCAN[@]} source files under the scan roots — this tester would scan nothing"; exit 1; }

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
_code() { grep -vE '^[[:space:]]*(\*|//|/\*|<!--)' "$1"; }

# g1 <shared bj> <drive bj> <gh pin> : no declaration carries a GitHub OAuth-App client for git
g1() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import json, re, sys
shared, drive, pin = (json.load(open(p, encoding="utf-8")) for p in sys.argv[1:4])
bad = 0
CLIENT_KEYS = {"client_id", "client_secret", "redirect_uri", "authorize_url", "token_url",
               "web_client", "secret_env", "secret_vault_path", "client_ids", "device_code_url"}
OAUTH_VALUE = re.compile(r"github\.com/login/oauth|oauth/landed|login/oauth/(authorize|access_token)", re.I)

def walk(node, path):
    """Every (key-path, key, value) below node, _doc prose excluded: prose may NAME the rule."""
    if isinstance(node, dict):
        for k, v in node.items():
            if k.startswith("_doc"):
                continue
            yield path + [k], k, v
            yield from walk(v, path + [k])
    elif isinstance(node, list):
        for i, v in enumerate(node):
            yield from walk(v, path + [str(i)])

auth = shared.get("auth") or {}
chain = (auth.get("git_chain") or {}).get("providers") or {}
if not chain:
    print("    auth.git_chain.providers is empty — nothing to guard, so this proves nothing"); sys.exit(1)
for rid, rung in chain.items():
    for kp, k, v in walk(rung, [rid]):
        if k in CLIENT_KEYS:
            print("    git rung %s declares %s — an OAuth-App client of the fleet's own; the GitHub leg is gh's own sign-in"
                  % (rid, ".".join(kp))); bad = 1
# #1083 declared GitHub again in auth.sign_in, as an IDENTITY sign-in (the owner's own OAuth App,
# scope read:user, grants identity only). That provider is the one place an oauth endpoint may sit,
# and only while it can never become a git credential: identity grant only, no `repo`-class scope.
# Everything else in the auth block, and the whole git page, still names no oauth endpoint.
sign_in_gh = [p for p in (auth.get("sign_in", {}).get("providers") or []) if p.get("id") == "github"]
for p in sign_in_gh:
    if p.get("grants") != ["identity"] or re.search(r"\brepo\b|\bgist\b|\bworkflow\b|write:|admin:", p.get("scope") or ""):
        print("    auth.sign_in github is no longer identity-only (grants %r, scope %r): it would be a git credential of the fleet's own"
              % (p.get("grants"), p.get("scope"))); bad = 1
def _exempt(kp):
    return len(kp) >= 4 and kp[:3] == ["auth", "sign_in", "providers"] and kp[3].isdigit() and \
        (auth.get("sign_in", {}).get("providers") or [])[int(kp[3])].get("id") == "github"
for label, tree in (("auth", auth), ("ui.sync.git", ((drive.get("ui") or {}).get("sync") or {}).get("git") or {})):
    for kp, k, v in walk(tree, [label]):
        if _exempt(kp):
            continue
        if isinstance(v, str) and OAUTH_VALUE.search(v):
            print("    %s = %r names a GitHub OAuth endpoint or an oauth landing" % (".".join(kp), v)); bad = 1
# no OTHER sign-in provider may be GitHub's in any spelling (#641's rule, carried here too)
for p in auth.get("sign_in", {}).get("providers") or []:
    if p.get("id") != "github" and "github" in json.dumps({k: v for k, v in p.items() if not k.startswith("_doc")}).lower():
        print("    auth.sign_in provider %s names GitHub" % p.get("id")); bad = 1
# the gh pin carries gh's binary and how it speaks — never a client or endpoint of ours
for kp, k, v in walk(pin, ["gh-binary"]):
    if k in CLIENT_KEYS or (isinstance(v, str) and OAUTH_VALUE.search(v)):
        print("    the gh pin declares %s — gh carries its own client id; the fleet declares none" % ".".join(kp)); bad = 1
gh = chain.get("github") or {}
if gh.get("kind") != "gh_on_device":
    print("    the github rung's kind is %r; the GitHub leg is gh on the device" % gh.get("kind")); bad = 1
sys.exit(1 if bad else 0)
PYTHON
}

# g2 <file...> : no source rebuilds the OAuth-App flow
g2() {
    local f bad=0 hit
    local forbidden='oauth/landed|login/oauth/(authorize|access_token)|OAuthWeb|web_client|webClient|GITHUB_OAUTH_CLIENT|b16-github-oauth|redirect_prefix|redirect_url|REDIRECT_PREFIX|REDIRECT_URL|CAPTURE_REDIRECT|returnRedirect|Capture\.Redirect'
    for f in "$@"; do
        hit="$(_code "$f" | grep -nE "$forbidden" || true)"
        [ -z "$hit" ] || { echo "    ${f#$ROOT/} rebuilds the GitHub OAuth-App path:"; printf '%s\n' "$hit" | sed 's/^/        /'; bad=1; }
        # a GitHub client id or secret in code: the Google device grant (#611) is the only
        # client this fleet declares, and it is not GitHub's.
        hit="$(_code "$f" | grep -niE 'github[^"]{0,60}client_?(id|secret)|client_?(id|secret)[^"]{0,60}github' || true)"
        [ -z "$hit" ] || { echo "    ${f#$ROOT/} names a GitHub client id or secret:"; printf '%s\n' "$hit" | sed 's/^/        /'; bad=1; }
    done
    return $bad
}

# g3 <shared bj> <GhRunner.kt> <page> <GitHubRepos.kt> <coordinator> <strings> <DriveGitChain.kt>
g3() {
    local bj="$1" runner="$2" page="$3" list="$4" coord="$5" str="$6" wiring="$7" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
gh = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["git_chain"]["providers"].get("github") or {}
bad = 0
if not (gh.get("host") or "").strip():
    print("    the github rung declares no host for gh to sign in to, list from and answer a credential for"); bad = 1
if not isinstance(gh.get("list_limit"), int) or gh["list_limit"] <= 0:
    print("    the github rung declares no positive list_limit for gh repo list: %r" % gh.get("list_limit")); bad = 1
if gh.get("holds_github_credential") is not True:
    print("    the github rung must say the phone holds a GitHub credential on it (gh's own)"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # THE RUNNER: gh's own login, gh's own credential helper, nothing of ours.
    grep -qE 'listOf\("auth", "login", "--hostname", host,' "$runner" \
        || { echo "    GhRunner does not run gh's own auth login against the declared host"; bad=1; }
    grep -qE -- '"--insecure-storage", "--skip-ssh-key", "--clipboard=false"\),' "$runner" \
        || { echo "    GhRunner's login argv is not the measured one (keyring-less storage, no ssh prompt, no desktop clipboard)"; bad=1; }
    [ "$(_code "$runner" | grep -cE -- '--with-token')" -eq 0 ] \
        || { echo "    GhRunner feeds gh a token to log in with — that is a credential of ours, not gh's own sign-in"; bad=1; }
    python3 - "$runner" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
bad = 0
def body(name):
    m = re.search(r"\n    fun %s\(.*?\n    }\n" % name, src, re.S)
    return m.group(0) if m else ""
login, cred = body("login"), body("credential")
if not login:
    print("    GhRunner has no login()"); bad = 1
elif "token = null" not in login:
    print("    login() may hand gh a GH_TOKEN; gh refuses to sign in while one is set"); bad = 1
if not cred:
    print("    GhRunner has no credential()"); bad = 1
else:
    if 'listOf("auth", "git-credential", "get")' not in cred:
        print("    credential() does not ask gh's own git-credential helper"); bad = 1
    if "Result(" in cred or "run(" in cred:
        print("    credential() builds a Result: the secret would sit in output a caller can show"); bad = 1
    if "GhOutput.credential(answer)" not in cred:
        print("    credential() does not read the answer straight into a Credential"); bad = 1
if 'override fun toString(): String = "Credential(username=$username, secret=<redacted>)"' not in src:
    print("    GhRunner.Credential's toString is not redacted"); bad = 1
# a gh that cannot be exec'd is an OUTCOME the page words, never an exception thrown into its
# coroutine (which would crash the app instead of saying why)
for name in ("run", "login"):
    b = body(name)
    if "catch (e: Exception)" not in b or "Result(EXEC_FAILED," not in b:
        print("    %s() lets an exec failure escape as an exception: the page would crash instead of saying why" % name); bad = 1
if "catch (e: Exception)" not in cred:
    print("    credential() lets an exec failure escape as an exception"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # THE PAGE: the GitHub way is gh's; list with gh; clone gh's rows on gh's credential.
    grep -qE 'DriveGitChain\.RUNG_GITHUB -> startGhLogin\(\)' "$page" \
        || { echo "    the GitHub way does not start gh's own sign-in"; bad=1; }
    # #1693 gh's own `auth login` runs in Cloud Account (GhRunner above is the runner it uses): the page LINKS to it.
    grep -qF 'AccountClient.openSignIn(ctx, SignInContract.PROVIDER_GITHUB)' "$page" \
        || { echo    "    the page's GitHub sign-in is not a link to Cloud Account"; bad=1; }
    grep -qE 'ghEngine\.login\(' "$page" \
        && { echo    "    the page runs gh auth login itself again (only Cloud Account signs in)"; bad=1; }
    grep -qE 'ghEngine\.repoList\(ghLimit, GitHubRepos\.GH_FIELDS\)' "$page" \
        || { echo "    the GitHub listing is not gh repo list"; bad=1; }
    grep -qE 'else if \(wayListing\.viaGh\) cloneViaGh\(gh\)' "$page" \
        || { echo "    a row gh listed does not clone on gh's credential (it would fall to a terminal with none)"; bad=1; }
    python3 - "$page" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r"\n    fun cloneViaGh\(.*?\n    }\n", src, re.S)
if not m:
    print("    there is no cloneViaGh"); sys.exit(1)
b = m.group(0); bad = 0
for need, why in (("ghEngine.credential(ghHost)", "does not ask gh for the credential"),
                  ("coordinator.cloneInto(", "does not clone through the engine that lands in the ONE store"),
                  ("url = repo.cloneUrl,", "does not clone the listing's own URL (re-templating clones the wrong leg, #669)"),
                  ("authKind = GitSyncCoordinator.AUTH_HTTPS,", "does not record an https credential for the later sync"),
                  ("R.string.git_gh_clone_no_credential", "stays silent when gh holds no credential")):
    if need not in b:
        print("    cloneViaGh %s" % why); bad = 1
if "TerminalGit" in b or "cloneViaTerminal" in b:
    print("    cloneViaGh hands the clone to a terminal whose git holds no GitHub credential"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    grep -qE 'val dir = SharedStore\.repoDir\(name\)' "$coord" \
        || { echo "    cloneInto no longer lands in the ONE store's git folder (#676)"; bad=1; }
    # LOUD: every failure of the gh leg is a line that names the next step.
    local sid want
    while IFS='|' read -r sid want; do
        grep -qE "R\\.string\\.$sid\\b" "$page" \
            || { echo "    the page never says $sid"; bad=1; }
        grep -qE "name=\"$sid\">[^<]*$want" "$str" \
            || { echo "    $sid does not name its next step ($want)"; bad=1; }
    done <<'STEPS'
git_gh_not_signed_in|tap Sign in with GitHub
git_gh_clone_no_credential|tap Sign in with GitHub
git_gh_list_failed|tap Retry
git_gh_list_unreadable|tap Retry
git_gh_unconfirmed|Sign in with GitHub again
git_gh_missing|install it from Store ▸ Cloud Constellation ▸ Libs
git_gh_engine_old|update it from Store ▸ Cloud Constellation ▸ Libs
git_gh_status_failed|from Store ▸ Cloud Constellation ▸ Libs
STEPS
    # the listing reads exactly the fields it asks gh for — closed both ways.
    python3 - "$list" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'const val GH_FIELDS = "([^"]+)"', src)
b = re.search(r"\n    fun parseGh\(.*?\n    }\n", src, re.S)
if not m or not b:
    print("    GitHubRepos has no GH_FIELDS or no parseGh"); sys.exit(1)
asked = set(m.group(1).split(","))
read = set(re.findall(r'o(?:\.(?:str|bool|long)\(|\[)"([A-Za-z]+)"', b.group(0)))
if asked != read:
    print("    gh repo list is asked for %s but parseGh reads %s" % (sorted(asked - read), sorted(read - asked))); sys.exit(1)
PYTHON
    grep -qE 'fun ghHost\(\): String = githubRung\(\)\?\.config\?\.optString\("host"\)' "$wiring" \
        || { echo "    gh's host is not the github rung's declaration"; bad=1; }
    return $bad
}

# g4 <gh pin> <gh build.gradle> <GhRunner.kt> : the patterns the phone runs, run here on the measured transcript
g4() {
    local pin="$1" gradle="$2" runner="$3" bad=0
    python3 - "$pin" <<'PYTHON' || bad=1
import json, re, sys
lo = json.load(open(sys.argv[1], encoding="utf-8")).get("login_output") or {}
code_p, url_p = lo.get("device_code") or "", lo.get("verification_url") or ""
bad = 0
for name, p in (("device_code", code_p), ("verification_url", url_p)):
    if not p:
        print("    login_output.%s is not declared" % name); bad = 1
    elif "\\" in p or '"' in p:
        print("    login_output.%s carries a backslash or a quote; the bake writes it as a plain string literal" % name); bad = 1
if bad:
    sys.exit(1)
# MEASURED 2026-09-30, pinned gh 2.101.0 on aarch64, no TTY, stdin /dev/null. The second is the
# same flow without --clipboard=false: gh's clipboard complaint must never read as a code.
transcripts = [
    ("\n! First copy your one-time code: B1D3-EA43\nOpen this URL to continue in your web browser: https://github.com/login/device\n",
     "B1D3-EA43"),
    ("\n! Failed to copy one-time code to clipboard\n  No clipboard utilities available. Please install xsel, xclip, wl-clipboard or Termux:API add-on for termux-clipboard-get/set.\n! First copy your one-time code: 9ADE-CAAB\nOpen this URL to continue in your web browser: https://github.com/login/device\n",
     "9ADE-CAAB"),
]
for text, code in transcripts:
    lines = text.split("\n")
    codes = [m.group(0) for m in (re.search(code_p, l) for l in lines) if m]
    urls = [m.group(0) for m in (re.search(url_p, l) for l in lines) if m]
    if codes[:1] != [code]:
        print("    the declared code pattern reads %r from gh's measured output, not %r" % (codes[:1], code)); bad = 1
    if urls[:1] != ["https://github.com/login/device"]:
        print("    the declared page pattern reads %r from gh's measured output" % urls[:1]); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    grep -qE "buildConfigField 'String', 'GH_LOGIN_CODE_PATTERN', \"\\\\\"\\\$\{ghPin\.login_output\.device_code\}\\\\\"\"" "$gradle" \
        || { echo "    libs:gh does not bake the pin's device-code pattern"; bad=1; }
    grep -qE 'private val DEVICE_CODE = Regex\(BuildConfig\.GH_LOGIN_CODE_PATTERN\)' "$runner" \
        && grep -qE 'private val VERIFICATION_URL = Regex\(BuildConfig\.GH_LOGIN_URL_PATTERN\)' "$runner" \
        || { echo "    GhOutput does not read gh's output with the pin's own patterns"; bad=1; }
    grep -qE 'URI\(it\)\.host \}\.getOrNull\(\) == host' "$runner" \
        || { echo "    a page gh names is opened without checking it is on the declared host"; bad=1; }
    return $bad
}

echo "── G1 no declaration carries a GitHub OAuth-App client for git ──"
g1 "$SHARED_BJ" "$BJ" "$PIN" && pass "no git rung declares a client id, secret, redirect or web client; no oauth endpoint or landing is declared; the gh pin declares none of ours" \
    || fail "a GitHub OAuth-App client is declared again"
echo "── G2 no source rebuilds the OAuth-App path (${#SCAN[@]} files) ──"
g2 "${SCAN[@]}" && pass "no auth/gh/git-sync library, drive or browser source names the deleted web flow, its client, its secret seam, its landing or its redirect capture" \
    || fail "the GitHub OAuth-App path is back in the source"
echo "── G3 the GitHub leg is gh, and every failure says its next step ──"
g3 "$SHARED_BJ" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING" && pass "gh's own login, gh repo list, and a clone on gh's credential into the ONE store with the listing's URL; failures are loud" \
    || fail "the GitHub leg is not gh, or a failure is silent"
echo "── G4 the pin's patterns read gh's measured output ──"
g4 "$PIN" "$GH_GRADLE" "$RUNNER" && pass "the declared code and page patterns read B1D3-EA43 / 9ADE-CAAB and github.com/login/device off the measured transcripts, baked and host-checked" \
    || fail "the phone would misread gh's sign-in output"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }
# PROOF THE MUTATION APPLIED: the copy differs from the original AND carries the planted text.
_applied() {
    python3 -c 'import sys; a, b = (open(f, "rb").read() for f in sys.argv[1:3]); sys.exit(0 if a != b and sys.argv[3].encode() in b else 1)' "$1" "$2" "$3" \
        || { echo "  MUT-NOOP    the mutation did not apply ($3)"; HOLLOW=$((HOLLOW+1)); return 1; }
}
_json() { python3 -c "import json,sys; p=sys.argv[1]; d=json.load(open(p)); exec(sys.argv[2]); json.dump(d, open(p, 'w'), indent=1)" "$1" "$2"; }
_sub() { python3 -c "import sys; p=sys.argv[1]; s=open(p).read(); open(p,'w').write(s.replace(sys.argv[2], sys.argv[3], 1))" "$1" "$2" "$3"; }
# the scan list with one file swapped for its mutated copy
_swap() { local from="$1" to="$2" f; for f in "${SCAN[@]}"; do [ "$f" = "$from" ] && printf '%s\n' "$to" || printf '%s\n' "$f"; done; }

echo "── MUT each property, broken on a copy, goes red ──"

# G1 — the redirect comes back (the brief's first mutation)
cp "$SHARED_BJ" "$MUT/shared.json"
_green g1 g1 "$MUT/shared.json" "$BJ" "$PIN" && {
    _json "$MUT/shared.json" 'd["auth"]["git_chain"]["providers"]["github"]["redirect_uri"] = "https://api.diegonmarcos.com/git/oauth/landed"'
    _applied "$SHARED_BJ" "$MUT/shared.json" 'git/oauth/landed' \
        && _red "G1 the oauth-landed redirect re-declared on the github git rung" g1 "$MUT/shared.json" "$BJ" "$PIN"; }
# G1 — a client id under the github git provider (the brief's second mutation)
cp "$SHARED_BJ" "$MUT/shared.json"
_green g1 g1 "$MUT/shared.json" "$BJ" "$PIN" && {
    _json "$MUT/shared.json" 'd["auth"]["git_chain"]["providers"]["github"]["client_id"] = "Ov23liPLANTEDCLIENT0"'
    _applied "$SHARED_BJ" "$MUT/shared.json" 'Ov23liPLANTEDCLIENT0' \
        && _red "G1 a client_id re-declared under the github git provider" g1 "$MUT/shared.json" "$BJ" "$PIN"; }
# G1 — the whole web_client block, nested, with an empty secret (the exact shape 5e53a5364 shipped)
cp "$SHARED_BJ" "$MUT/shared.json"
_green g1 g1 "$MUT/shared.json" "$BJ" "$PIN" && {
    _json "$MUT/shared.json" 'd["auth"]["git_chain"]["providers"]["github"]["web_client"] = {"authorize_url": "https://github.com/login/oauth/authorize", "client_id": "", "client_secret": ""}'
    _applied "$SHARED_BJ" "$MUT/shared.json" 'web_client' \
        && _red "G1 a secretless web_client block re-declared (declared-but-empty is still declared)" g1 "$MUT/shared.json" "$BJ" "$PIN"; }
# G1 — a client secret smuggled onto ANOTHER git rung
cp "$SHARED_BJ" "$MUT/shared.json"
_green g1 g1 "$MUT/shared.json" "$BJ" "$PIN" && {
    _json "$MUT/shared.json" 'd["auth"]["git_chain"]["providers"]["gitea"]["client_secret"] = "PLANTED-SECRET"'
    _applied "$SHARED_BJ" "$MUT/shared.json" 'PLANTED-SECRET' \
        && _red "G1 a client_secret on the gitea rung" g1 "$MUT/shared.json" "$BJ" "$PIN"; }
# G1 — the gh pin grows a client id of ours
cp "$PIN" "$MUT/pin.json"
_green g1 g1 "$SHARED_BJ" "$BJ" "$MUT/pin.json" && {
    _json "$MUT/pin.json" 'd["client_ids"] = ["Iv1.PLANTED00000000"]'
    _applied "$PIN" "$MUT/pin.json" 'Iv1.PLANTED' \
        && _red "G1 the gh pin declares a client id of the fleet's own" g1 "$SHARED_BJ" "$BJ" "$MUT/pin.json"; }
# G1 — a GitHub OAuth endpoint on the drive's git page
cp "$BJ" "$MUT/drive.json"
_green g1 g1 "$SHARED_BJ" "$MUT/drive.json" "$PIN" && {
    _json "$MUT/drive.json" 'd["ui"]["sync"]["git"]["api"]["token_route"] = "https://github.com/login/oauth/access_token"'
    _applied "$BJ" "$MUT/drive.json" 'login/oauth/access_token' \
        && _red "G1 a GitHub OAuth token endpoint on the drive's git page" g1 "$SHARED_BJ" "$MUT/drive.json" "$PIN"; }

# G2 — the web flow's file planted back into libs:auth
printf 'package com.diegonmarcos.cloudlib.auth\n\nobject OAuthWeb { const val LANDING = "https://api.diegonmarcos.com/git/oauth/landed" }\n' >"$MUT/OAuthWeb.kt"
_green g2 g2 "${SCAN[@]}" && {
    _applied "$PIN" "$MUT/OAuthWeb.kt" 'object OAuthWeb' \
        && _red "G2 OAuthWeb.kt planted back into libs:auth" g2 "${SCAN[@]}" "$MUT/OAuthWeb.kt"; }
# G2 — the drive reads a web client off the rung again
cp "$WIRING" "$MUT/DriveGitChain.kt"
_green g2 g2 "${SCAN[@]}" && {
    _sub "$MUT/DriveGitChain.kt" '    private fun githubRung()' '    fun ghClient() = githubRung()?.config?.optJSONObject("web_client")
    private fun githubRung()'
    _applied "$WIRING" "$MUT/DriveGitChain.kt" 'optJSONObject("web_client")' \
        && { mapfile -t SWAPPED < <(_swap "$WIRING" "$MUT/DriveGitChain.kt"); _red "G2 DriveGitChain reads a web_client again" g2 "${SWAPPED[@]}"; }; }
# G2 — the browser mission's redirect capture comes back in libs:auth
AUTH_MISSION="$SHARED/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/AuthMission.kt"
cp "$AUTH_MISSION" "$MUT/AuthMission.kt"
_green g2 g2 "${SCAN[@]}" && {
    _sub "$MUT/AuthMission.kt" '    const val CAPTURE_COOKIE = "cookie"' '    const val CAPTURE_COOKIE = "cookie"
    const val CAPTURE_REDIRECT = "redirect"'
    _applied "$AUTH_MISSION" "$MUT/AuthMission.kt" 'CAPTURE_REDIRECT' \
        && { mapfile -t SWAPPED < <(_swap "$AUTH_MISSION" "$MUT/AuthMission.kt"); _red "G2 the mission's redirect capture re-added" g2 "${SWAPPED[@]}"; }; }
# G2 — a GitHub client secret baked in libs:auth's gradle
AUTH_GRADLE="$SHARED/libs/auth/build.gradle"
cp "$AUTH_GRADLE" "$MUT/auth.gradle"
_green g2 g2 "${SCAN[@]}" && {
    printf "\ndef githubClientSecret = System.getenv('GH_APP_SECRET')\n" >>"$MUT/auth.gradle"
    _applied "$AUTH_GRADLE" "$MUT/auth.gradle" 'githubClientSecret' \
        && { mapfile -t SWAPPED < <(_swap "$AUTH_GRADLE" "$MUT/auth.gradle"); _red "G2 a GitHub client secret baked in libs:auth" g2 "${SWAPPED[@]}"; }; }

# G3 — the GitHub way stops starting gh
cp "$PAGE" "$MUT/page.kt"
_green g3 g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/page.kt" 'DriveGitChain.RUNG_GITHUB -> startGhLogin()' 'DriveGitChain.RUNG_GITHUB -> Unit'
    _applied "$PAGE" "$MUT/page.kt" 'RUNG_GITHUB -> Unit' \
        && _red "G3 the GitHub way no longer starts gh's own sign-in" g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — gh's rows fall to the terminal clone
cp "$PAGE" "$MUT/page.kt"
_green g3 g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/page.kt" '                            else if (wayListing.viaGh) cloneViaGh(gh)
' ''
    python3 -c 'import sys; sys.exit(0 if "else if (wayListing.viaGh) cloneViaGh(gh)" not in open(sys.argv[1]).read() else 1)' "$MUT/page.kt" \
        && _applied "$PAGE" "$MUT/page.kt" 'else clone(gh.name, gh.cloneUrl, gh.sshUrl)' \
        && _red "G3 a row gh listed falls through to the terminal clone" g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — gh's clone re-templates the URL
cp "$PAGE" "$MUT/page.kt"
_green g3 g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/page.kt" '                url = repo.cloneUrl,' '                url = page.cloneUrl(repo.name, Declarations.REMOTE_HTTPS),'
    _applied "$PAGE" "$MUT/page.kt" 'url = page.cloneUrl(repo.name' \
        && _red "G3 gh's clone re-templates the URL instead of the listing's own" g3 "$SHARED_BJ" "$RUNNER" "$MUT/page.kt" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — login fed a token (not gh's own sign-in)
cp "$RUNNER" "$MUT/GhRunner.kt"
_green g3 g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/GhRunner.kt" '"--insecure-storage", "--skip-ssh-key", "--clipboard=false"),' '"--insecure-storage", "--skip-ssh-key", "--clipboard=false", "--with-token"),'
    _applied "$RUNNER" "$MUT/GhRunner.kt" '"--with-token")' \
        && _red "G3 gh auth login is fed a token of ours" g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — the credential lands in a Result a caller could show
cp "$RUNNER" "$MUT/GhRunner.kt"
_green g3 g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/GhRunner.kt" '            if (process.waitFor() == 0) GhOutput.credential(answer) else null' '            val kept = Result(process.waitFor(), answer)
            if (kept.ok) GhOutput.credential(answer) else null'
    _applied "$RUNNER" "$MUT/GhRunner.kt" 'val kept = Result(' \
        && _red "G3 gh's credential answer kept in a Result" g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — an exec failure escapes run() as an exception
cp "$RUNNER" "$MUT/GhRunner.kt"
_green g3 g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/GhRunner.kt" '    } catch (e: Exception) {
        Result(EXEC_FAILED, "gh could not run: ${e.message ?: e.javaClass.simpleName}")
    }' '    } finally {
    }'
    _applied "$RUNNER" "$MUT/GhRunner.kt" '} finally {' \
        && _red "G3 a gh that cannot start throws into the page instead of answering" g3 "$SHARED_BJ" "$MUT/GhRunner.kt" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING"; }
# G3 — a failure line loses its next step
cp "$STR" "$MUT/strings.xml"
_green g3 g3 "$SHARED_BJ" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$MUT/strings.xml" "$WIRING" && {
    _sub "$MUT/strings.xml" 'gh not signed in — tap Sign in with GitHub.' 'gh not signed in.'
    _applied "$STR" "$MUT/strings.xml" '>gh not signed in.<' \
        && _red "G3 'gh not signed in' no longer names its next step" g3 "$SHARED_BJ" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$MUT/strings.xml" "$WIRING"; }
# G3 — the listing asks gh for a field it never reads
cp "$LIST" "$MUT/GitHubRepos.kt"
_green g3 g3 "$SHARED_BJ" "$RUNNER" "$PAGE" "$MUT/GitHubRepos.kt" "$COORD" "$STR" "$WIRING" && {
    _sub "$MUT/GitHubRepos.kt" 'const val GH_FIELDS = "name,' 'const val GH_FIELDS = "homepageUrl,name,'
    _applied "$LIST" "$MUT/GitHubRepos.kt" 'GH_FIELDS = "homepageUrl,' \
        && _red "G3 gh repo list asked for a field parseGh never reads" g3 "$SHARED_BJ" "$RUNNER" "$PAGE" "$MUT/GitHubRepos.kt" "$COORD" "$STR" "$WIRING"; }
# G3 — the rung loses gh's host
cp "$SHARED_BJ" "$MUT/shared.json"
_green g3 g3 "$MUT/shared.json" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING" && {
    _json "$MUT/shared.json" 'd["auth"]["git_chain"]["providers"]["github"]["host"] = ""; d["auth"]["git_chain"]["providers"]["github"]["_planted"] = "no-host"'
    _applied "$SHARED_BJ" "$MUT/shared.json" 'no-host' \
        && _red "G3 the github rung declares no host for gh" g3 "$MUT/shared.json" "$RUNNER" "$PAGE" "$LIST" "$COORD" "$STR" "$WIRING"; }

# G4 — the code pattern no longer reads gh's measured output
cp "$PIN" "$MUT/pin.json"
_green g4 g4 "$MUT/pin.json" "$GH_GRADLE" "$RUNNER" && {
    _json "$MUT/pin.json" 'd["login_output"]["device_code"] = "[a-z]{4}-[a-z]{4}"'
    _applied "$PIN" "$MUT/pin.json" '[a-z]{4}-[a-z]{4}' \
        && _red "G4 a code pattern that misreads gh's measured output" g4 "$MUT/pin.json" "$GH_GRADLE" "$RUNNER"; }
# G4 — a pattern the bake cannot write as a plain literal
cp "$PIN" "$MUT/pin.json"
_green g4 g4 "$MUT/pin.json" "$GH_GRADLE" "$RUNNER" && {
    _json "$MUT/pin.json" 'd["login_output"]["verification_url"] = "https://\\S+"'
    _applied "$PIN" "$MUT/pin.json" 'https://\\' \
        && _red "G4 a backslash in a baked pattern" g4 "$MUT/pin.json" "$GH_GRADLE" "$RUNNER"; }
# G4 — the page opened without the host check
cp "$RUNNER" "$MUT/GhRunner.kt"
_green g4 g4 "$PIN" "$GH_GRADLE" "$MUT/GhRunner.kt" && {
    _sub "$MUT/GhRunner.kt" '?.takeIf { runCatching { java.net.URI(it).host }.getOrNull() == host }' ''
    python3 -c 'import sys; sys.exit(0 if "getOrNull() == host" not in open(sys.argv[1]).read() else 1)' "$MUT/GhRunner.kt" \
        && _applied "$RUNNER" "$MUT/GhRunner.kt" 'VERIFICATION_URL.find(line)?.value' \
        && _red "G4 any URL gh prints would be opened" g4 "$PIN" "$GH_GRADLE" "$MUT/GhRunner.kt"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void/no-op ──"
[ "$MUTATIONS" -ge 22 ] || { echo "  only $MUTATIONS mutations ran — a mutation block that stops early proves less than it prints"; FAILURES=$((FAILURES + 1)); }
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-gh-leg: all checks passed"; else echo "test-drive-git-gh-leg: $FAILURES check(s) FAILED"; exit 1; fi
