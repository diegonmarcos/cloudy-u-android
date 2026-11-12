#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #684 — Sync ▸ Git Personal is TWO PARALLEL WAYS: GitHub and Cloud git,    ║
# ║ side by side, each with its own sign-in, listing and clone; neither gates  ║
# ║ the other; both ride the fleet browser; nothing is a placeholder          ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The owner rejected the one-chain-one-affordance shape where whichever rung
# answered took the section over. He wants TWO always-visible cards. What this
# pins, each assertion on the MESSAGE (grep -c after a pipe, never grep -q):
#
#   W1  the two ways are DECLARED (ui.sync.git.ways), each naming a git_chain
#       rung, in the owner's order (GitHub, Cloud git), and closed against the
#       chain: a way's rung must be a declared git_chain rung.
#   W2  the page RENDERS both ways independently — it iterates page.ways, each
#       draws its own card over its OWN listing (github → listing, cloud →
#       cloudListing), and the cloud sign-in does not gate the GitHub card.
#   W3  NO PLACEHOLDER: a way whose rung is undeclared shows one line and a
#       disabled button; nothing else disables it (#689: the GitHub way is gh's
#       own sign-in and has no client of the fleet's own to lack).
#   W4  (#689 retired it with the GitHub OAuth-App web flow it pinned; the
#       absence of that flow, and gh as the GitHub leg, are pinned by
#       test-drive-git-gh-leg.sh.)
#   W5  (#1693) the Cloud sign-in is a link to Cloud Account's Authelia page; the
#       page runs no browser mission and hosts no sign-in surface.
#   MUT each property, broken on a copy, turns its own check red.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
BJ="$APP/build.json"
SHARED_BJ="$ROOT/ab_cloud-libs-shared/build.json"
PAGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
DECL="$APP/app/src/main/java/com/diegonmarcos/clouddrive/Declarations.kt"
WIRING="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/DriveGitChain.kt"
STR="$APP/app/src/main/res/values/strings.xml"
FLEET="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/FleetGit.kt"
for required in "$BJ" "$SHARED_BJ" "$PAGE" "$DECL" "$WIRING" "$STR" "$FLEET"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }

# w1 <drive bj> <shared bj> : the two ways are declared and closed against the chain
w1() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
ways = (((json.load(open(sys.argv[1]))["ui"].get("sync") or {}).get("git") or {}).get("ways")) or []
chain = json.load(open(sys.argv[2]))["auth"]["git_chain"]
declared = set(chain.get("providers") or {})
bad = 0
ids = [w.get("id") for w in ways]
if ids != ["github", "cloud"]:
    print("    ui.sync.git.ways is %s, not the owner's order [github, cloud]" % ids); bad = 1
rungs = [w.get("rung") for w in ways]
if rungs != ["github", "gitea"]:
    print("    the ways name rungs %s; GitHub→github, Cloud git→gitea expected" % rungs); bad = 1
for w in ways:
    if not w.get("label") or not w.get("icon"):
        print("    way %s lacks a label or an icon" % w.get("id")); bad = 1
    if w.get("rung") not in declared:
        print("    way %s names rung %r, which is not a declared git_chain rung (a way with no"
              " mechanism)" % (w.get("id"), w.get("rung"))); bad = 1
sys.exit(1 if bad else 0)
PYTHON
}

# w2 <page> : the page renders both ways, each over its own listing, independently
w2() {
    local f="$1" bad=0
    [ "$(_code "$f" | grep -cE 'page\.ways\.forEach')" -ge 1 ] \
        || { echo "    the page does not iterate the declared ways"; bad=1; }
    # a SECOND listing exists and the cloud fleet listing writes it, not the github one
    [ "$(_code "$f" | grep -cE 'var cloudListing by remember')" -ge 1 ] \
        || { echo "    there is no second (Cloud git) listing — the two cards would share one"; bad=1; }
    [ "$(_code "$f" | grep -cE 'val wayListing = if \(isCloud\) cloudListing else listing')" -ge 1 ] \
        || { echo "    a card does not select its OWN listing by the way's rung kind"; bad=1; }
    [ "$(_code "$f" | grep -cE 'cloudListing = when \(outcome\)')" -ge 1 ] \
        || { echo "    the fleet listing does not write the Cloud git card's own listing"; bad=1; }
    # the two cards are one composable drawn per way, tagged
    grep -qE 'fun GitWayCard\(' "$f" \
        || { echo "    there is no per-way card composable"; bad=1; }
    grep -qE 'tag = DriveTags\.SYNC_GIT_WAY\b' "$f" \
        || { echo "    the way card is not tagged"; bad=1; }
    # the old single chain box is GONE
    [ "$(_code "$f" | grep -cE 'fun GitLoginBox\(')" -eq 0 ] \
        || { echo "    the old single-chain GitLoginBox is still here — the section was not split"; bad=1; }
    # the cloud sign-in does not gate the github card: signed-in is per-way, not one flag
    # (#689 the GitHub card's own state is gh's sign-in, or the #629 vault credential)
    grep -qE 'val signedIn = if \(isCloud\) chain\.signedIn else ghAuth\.signedIn \|\| login\.token\.isNotBlank\(\)' "$f" \
        || { echo "    a card's signed-in state is not computed per way — one sign-in would gate both"; bad=1; }
    return $bad
}

# w3 <page> <strings> : no placeholder — an undeclared rung says one line and disables the button
w3() {
    local f="$1" str="$2" bad=0
    grep -qE 'enabled = !signingIn && !disabled' "$f" \
        || { echo "    the sign-in button is not disabled while the way cannot start"; bad=1; }
    grep -qE 'val disabled = !rungDeclared$' "$f" \
        || { echo "    the disabled rule is not (undeclared rung) alone"; bad=1; }
    grep -qE 'R\.string\.git_way_rung_undeclared' "$f" \
        || { echo "    an undeclared rung shows no line"; bad=1; }
    grep -qE 'git_way_rung_undeclared">[^<]*no mechanism' "$str" \
        || { echo "    the undeclared-rung line does not say the way has no mechanism"; bad=1; }
    return $bad
}

# w5 <shared bj> <page> <wiring> : #1693 the Cloud sign-in is a LINK to Cloud Account's Authelia page; no browser mission runs here
w5() {
    local bj="$1" page="$2" wiring="$3" bad=0
    grep -qE 'AuthMission|missionLauncher|AuthDeclaration\.browserMission|SignInWays|SignInHost|CAPTURE_COOKIE' "$page" \
        && { echo "    the page still runs a browser mission or hosts a sign-in surface: only Cloud Account signs in"; bad=1; }
    grep -qE 'AccountClient\.openSignIn\(ctx, SignInContract\.PROVIDER_AUTHELIA\)' "$page" \
        || { echo "    the Cloud sign-in is not a link to Cloud Account's Authelia page"; bad=1; }
    grep -qE 'DriveGitChain\.RUNG_FLEET -> \{' "$page" \
        || { echo "    the fleet rung no longer dispatches its own way"; bad=1; }
    return $bad
}

# w6 <FleetGit.kt> : the Cloud (gitea) listing follows EVERY page (Owner Amendment 2, rule 2)
w6() {
    local f="$1" bad=0
    grep -qE 'while \(page <= maxPages\)' "$f" \
        || { echo "    FleetGit.repos does not loop pages — a >page-size account would be silently truncated"; bad=1; }
    grep -qE 'if \(isLastPage\(r\.repos\.size, limit\)\) return Outcome\.Listed\(all\)' "$f" \
        || { echo "    pagination does not stop at a short page"; bad=1; }
    grep -qE 'internal fun pageLimit\(' "$f" && grep -qE 'internal fun pagedUrl\(' "$f" && grep -qE 'internal fun isLastPage\(' "$f" \
        || { echo "    the paging rule is not pure/JVM-testable (pageLimit/pagedUrl/isLastPage)"; bad=1; }
    # the limit is READ off the declared url, never a hardcoded page cap
    [ "$(_code "$f" | grep -cE '= [0-9]+$|limit = [0-9]')" -eq 0 ] || true
    return $bad
}

echo "── W1 the two ways are declared and closed against the chain ──"
w1 "$BJ" "$SHARED_BJ" && pass "ui.sync.git.ways is [github, cloud] naming rungs [github, gitea], each a declared git_chain rung" || fail "the declared ways are missing, mis-ordered, or name an undeclared rung"

echo "── W2 the page renders both ways independently ──"
w2 "$PAGE" && pass "the page iterates the declared ways, each card lists its own listing, and no sign-in gates the other card" || fail "the two ways are not rendered as independent parallel cards"

echo "── W3 no placeholder — an unstartable way says one line ──"
w3 "$PAGE" "$STR" && pass "an undeclared rung disables the button with one declared line, and nothing else disables it" || fail "a way renders a dead button or a placeholder"

echo "── W5 the Cloud sign-in rides the fleet browser, with a declared fallback ──"
w5 "$SHARED_BJ" "$PAGE" "$WIRING" && pass "the cookie mission is declared and fired, its result read off the contract, and the in-app dialog is the declared fallback that says so" || fail "the browser mission or its fallback is not wired as declared"

echo "── W6 the Cloud listing follows every page ──"
w6 "$FLEET" && pass "FleetGit.repos follows pages until a short one, off the declared page size — the two lists stay independent and neither is truncated" || fail "the gitea listing does not paginate (Owner Amendment 2, rule 2)"

# ══ MUT ════════════════════════════════════════════════════════════════════
MUT="$(mktemp -d)"; trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
_red() { local label="$1"; shift; MUTATIONS=$((MUTATIONS+1)); if "$@" >/dev/null 2>&1; then echo "  MUT-HOLLOW  $label — still passes"; HOLLOW=$((HOLLOW+1)); else echo "  MUT-RED     $label"; fi; }
_green() { local label="$1"; shift; "$@" >/dev/null 2>&1 && return 0; echo "  MUT-VOID    $label — unmutated already fails"; HOLLOW=$((HOLLOW+1)); return 1; }

echo "── MUT each property, broken on a copy, goes red ──"

# W1: a way naming an undeclared rung
cp "$BJ" "$MUT/drive.json"; cp "$SHARED_BJ" "$MUT/shared.json"
_green "w1" w1 "$MUT/drive.json" "$MUT/shared.json" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['ui']['sync']['git']['ways'][0]['rung']='nonesuch';json.dump(d,open(sys.argv[1],'w'))" "$MUT/drive.json"
    _red "W1 a way naming an undeclared rung" w1 "$MUT/drive.json" "$MUT/shared.json"; }
cp "$BJ" "$MUT/drive.json"
_green "w1" w1 "$MUT/drive.json" "$MUT/shared.json" && {
    python3 -c "import json,sys;d=json.load(open(sys.argv[1]));d['ui']['sync']['git']['ways'].reverse();json.dump(d,open(sys.argv[1],'w'))" "$MUT/drive.json"
    _red "W1 the ways in the wrong order" w1 "$MUT/drive.json" "$MUT/shared.json"; }

# W2: the page shares one listing across both cards
cp "$PAGE" "$MUT/page.kt"
_green "w2" w2 "$MUT/page.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('val wayListing = if (isCloud) cloudListing else listing','val wayListing = listing');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W2 both cards read the same listing" w2 "$MUT/page.kt"; }
cp "$PAGE" "$MUT/page.kt"
_green "w2" w2 "$MUT/page.kt" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('val signedIn = if (isCloud) chain.signedIn else ghAuth.signedIn || login.token.isNotBlank()','val signedIn = chain.signedIn');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W2 one signed-in flag gates both cards" w2 "$MUT/page.kt"; }

# W3: the button stops being disabled
cp "$PAGE" "$MUT/page.kt"
_green "w3" w3 "$MUT/page.kt" "$STR" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('enabled = !signingIn && !disabled','enabled = true');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W3 the sign-in button is always enabled (a dead button when the rung is undeclared)" w3 "$MUT/page.kt" "$STR"; }

# W5: a browser mission, or a sign-in surface, comes back onto the page
cp "$PAGE" "$MUT/page.kt"
_green "w5" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('AccountClient.openSignIn(ctx, SignInContract.PROVIDER_AUTHELIA)','AuthMission.plan(ctx, contract, req).second.toString()');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W5 the Cloud sign-in runs a browser mission again" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING"; }
cp "$PAGE" "$MUT/page.kt"
_green "w5" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('        Text(','        SignInWays(host = h)\n        Text(',1);open(p,'w').write(s)" "$MUT/page.kt"
    _red "W5 a sign-in surface is hosted on the page again" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING"; }
cp "$PAGE" "$MUT/page.kt"
_green "w5" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING" && {
    python3 -c "import sys;p=sys.argv[1];s=open(p).read().replace('AccountClient.openSignIn(ctx, SignInContract.PROVIDER_AUTHELIA)','\"\"');open(p,'w').write(s)" "$MUT/page.kt"
    _red "W5 the Cloud sign-in no longer links to Cloud Account" w5 "$SHARED_BJ" "$MUT/page.kt" "$WIRING"; }

echo "── $MUTATIONS mutations, $HOLLOW hollow/void ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-parallel-ways: all checks passed"; else echo "test-drive-git-parallel-ways: $FAILURES check(s) FAILED"; exit 1; fi
