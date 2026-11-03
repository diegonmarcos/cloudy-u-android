#!/usr/bin/env bash
# ╔══════════════════════════════════════════════════════════════════════════╗
# ║ #646 — ONE declared auth+git fallback chain: fleet first, GitHub second   ║
# ╚══════════════════════════════════════════════════════════════════════════╝
#
# The owner's ask, in his words: "lets do all 1,2,3!! if our servers are down we
# can do gh if not we can use our flow!" That is a DECLARED FALLBACK CHAIN, not
# three features — one ordered declaration, consumed by every caller, so that
# re-ranking it in DATA changes behaviour with no code edit.
#
# WHAT THIS PINS, and every check asserts on the MESSAGE rather than an exit
# status, because exit-status-only assertions have produced fake passes in this
# repository repeatedly:
#
#   C1  ONE declaration holds the ranking (auth.git_chain), closed at both ends,
#       and it is NOT auth.sign_in.providers — #641's deleted github provider
#       stays deleted.
#   C2  REORDERING THE DECLARATION REORDERS THE REAL ATTEMPTS, and a provider
#       removed from it is never tried. Executed against the REAL declaration.
#   C3  A FLEET TIMEOUT FALLS THROUGH to github rather than failing, and the
#       fall-through is VISIBLE. #647 not existing yet is the same clean
#       fall-through, not an error.
#   C4  BOTH PROVIDERS WRITE THE SAME CREDENTIAL ID — one store, one id, and the
#       id is never a literal in Kotlin.
#   C5  MOVED (#705): the two pinned binaries' build-time refusals now live in
#       ab_cloud-libs-shared/lib-apks/test/test-git-binaries.sh, because no app
#       compiles libs:gh or libs:gix any more and only the Cloud Libs ship runs
#       when they change.
#   C6  GIX IS NOT IN THIS APP (#705) and no caller routes push to it. The
#       pin-and-runner half (gitoxide has no push) moved with C5.
#   C7  NO TOKEN IS EVER RENDERED OR LOGGED here (GhRunner's half moved with C5).
#   MUT mutation-proof: each of the above goes RED when the property is broken.
#
# OWN-SOURCE ONLY. python3 and grep only, no network, no build.
set -uo pipefail

ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
SHARED="$ROOT/ab_cloud-libs-shared"
SHARED_BJ="$SHARED/build.json"
BJ="$APP/build.json"
WALKER="$SHARED/libs/git-sync/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitAuthChain.kt"
WALKER_TEST="$SHARED/libs/git-sync/src/test/java/com/diegonmarcos/cloudlib/gitsync/GitAuthChainTest.kt"
WIRING="$APP/app/src/main/java/com/diegonmarcos/clouddrive/configs/DriveGitChain.kt"
READER="$SHARED/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/AuthDeclaration.kt"
PAGE="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt"
APP_GRADLE="$APP/app/build.gradle"
SHIP_WF="$ROOT/1_cicd/src/cicd/ship-cloud-drive.yml"
FLEET_CLIENT="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/FleetGit.kt"
GIT_MODELS="$SHARED/libs/git-core/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitModels.kt"
GIT_ENGINE="$SHARED/libs/git-core/src/main/java/com/diegonmarcos/cloudlib/gitsync/GitWire.kt"
COORD="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/GitSyncCoordinator.kt"
SIGNIN="$SHARED/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/SignIn.kt"
SIGNIN_UI="$SHARED/libs/auth/src/main/java/com/diegonmarcos/cloudlib/auth/SignInUi.kt"

FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

# ── a file's CODE, with its comment lines stripped ──────────────────────────
# Every grep below that looks for the ABSENCE of something runs through this.
# Sources DOCUMENT what they no longer do (app/build.gradle explains why gix is
# not linked); a naive grep would match the explanation and call it the defect.
_code() { grep -vE '^[[:space:]]*(\*|//|/\*)' "$1"; }
for required in "$SHARED_BJ" "$BJ" "$WALKER" "$WALKER_TEST" "$WIRING" "$READER" "$PAGE" \
                "$APP_GRADLE" "$SHIP_WF" "$FLEET_CLIENT" \
                "$GIT_MODELS" "$GIT_ENGINE" "$COORD"; do
    [ -f "$required" ] || { echo "ERROR missing source: $required — this tester is unrun, not passing"; exit 1; }
done

# ── the checks as functions of their inputs, so the mutation block runs them on copies ──

# c1 <shared build.json> : ONE ordered declaration, closed at both ends
c1() {
    python3 - "$1" <<'PYTHON'
import json, sys
root = json.load(open(sys.argv[1], encoding="utf-8"))
auth = root["auth"]
bad = 0
chain = auth.get("git_chain")
if not isinstance(chain, dict):
    print("    auth.git_chain is not declared: there would be no ranking to follow"); sys.exit(1)
order = chain.get("order")
providers = chain.get("providers")
if not isinstance(order, list) or not order:
    print("    auth.git_chain.order is empty: a chain with no ranking is not a chain"); bad += 1
if not isinstance(providers, dict) or not providers:
    print("    auth.git_chain.providers is empty"); bad += 1
if isinstance(order, list) and isinstance(providers, dict):
    # CLOSED AT BOTH ENDS (#627's rule): a rung ranked with no endpoint is an
    # attempt that cannot be made; an endpoint with no rank never runs.
    ranked, declared = set(order), set(providers)
    if ranked != declared:
        print("    auth.git_chain.order %r and providers %r must name the SAME rungs — "
              "a rung in one and not the other only half exists"
              % (sorted(ranked), sorted(declared))); bad += 1
    if len(order) != len(set(order)):
        print("    auth.git_chain.order repeats a rung: %r" % order); bad += 1
    # The owner's ask is specifically ours-first — and #669 OUR OWN GIT SERVER
    # outranks our GitHub proxy: gitea needs no third party at all, and it is an
    # independent service that stays up when the sheddable proxy (#662) is not.
    if order[0] != "gitea":
        print("    the first rung is %r; the fleet's own gitea is preferred whenever reachable" % order[0]); bad += 1
    if "fleet" in order and "gitea" in order and order.index("gitea") > order.index("fleet"):
        print("    the proxy is ranked above gitea — the sheddable service above the independent one"); bad += 1
    if "github" not in order:
        print("    there is no github rung: nothing answers when our servers are down"); bad += 1
    if order.index("fleet") > order.index("github"):
        print("    github is ranked above fleet — the phone would hold a GitHub credential "
              "even when our own proxy could have minted one"); bad += 1
    for rid in order:
        p = providers.get(rid) or {}
        if not (p.get("kind") or "").strip():
            print("    rung %s declares no kind, which is the only thing code dispatches on" % rid); bad += 1
        if not (p.get("label") or "").strip():
            print("    rung %s declares no label: the page could not name it" % rid); bad += 1
# #641 STAYS DONE, as amended by #1083. The chain must not smuggle a github GIT credential back into the
# sign-in list: #1083 re-declared GitHub there as an IDENTITY-only OAuth App (grants == [identity], no
# secret), so the sign-in entry may exist but can never be a credential the chain would clone with, and
# the chain's own github rung stays gh on the device with no client of ours.
for p in auth["sign_in"]["providers"]:
    if p.get("id") == "github" and (p.get("grants") != ["identity"] or p.get("client_secret")):
        print("    the github sign-in provider is no longer identity-only/secret-less (#641 deleted the git-capable one)"); bad += 1
if (chain.get("providers") or {}).get("github", {}).get("kind") != "gh_on_device" or any(
        k in (chain.get("providers") or {}).get("github", {}) for k in ("client_id", "client_secret", "token_url")):
    print("    the chain's github rung is not plain gh_on_device without a client of its own"); bad += 1
# ONE ID. The chain must not carry its own copy of the credential id.
if "vault-git-https" in json.dumps(chain.get("providers") or {}):
    print("    a rung repeats the credential id — a second copy is how one credential "
          "silently becomes two"); bad += 1
print("    declared chain: %s" % " -> ".join(order or []))
sys.exit(1 if bad else 0)
PYTHON
}

echo "── C1 ONE ordered declaration holds the ranking ──"
c1 "$SHARED_BJ" && pass "auth.git_chain ranks fleet before github, closed at both ends, and #641's provider stays deleted" \
    || fail "the git-auth chain declaration is missing, half-declared or mis-ranked"

# c2 <shared build.json> : the DECLARATION drives the real attempt order.
#
# This does not grep for a comment claiming order is honoured — it EXECUTES the
# walker's rule (iterate the declared order, stop at the first rung that answers)
# against the REAL declaration, and then proves the outcome CHANGES when the
# declaration changes. The Kotlin walker's own runtime behaviour is pinned by
# GitAuthChainTest on the JVM; what this pins is that the ranking those attempts
# follow comes from this file and nowhere else.
c2() {
    python3 - "$1" <<'PYTHON'
import json, sys
chain = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["git_chain"]
order = chain["order"]
bad = 0

def walk(order, answers):
    """GitAuthChain.resolve's rule: declared order, first answer wins."""
    asked, answered = [], None
    for rid in order:
        if answered:
            continue
        asked.append(rid)
        if answers.get(rid) == "credential":
            answered = rid
    return asked, answered

ALL = {r: "credential" for r in order}

# Every rung able to answer: ONLY the first-ranked one is ever asked. This is the
# BASELINE the reversal below is compared against.
asked, top_answered = walk(order, ALL)
if asked != [order[0]] or top_answered != order[0]:
    print("    with every rung able to answer, %r was asked and %r answered; "
          "the first-ranked rung must win alone" % (asked, top_answered)); bad += 1

# The fleet down: the chain must reach github, ask every rung on the way, and
# github must answer. This is the case the whole declaration exists for.
down_asked, down_answered = walk(order, {"github": "credential"})
if down_answered != "github":
    print("    with only github able to answer, %r answered" % down_answered); bad += 1
if down_asked != order:
    print("    with the fleet down the chain asked %r; it must try every rung "
          "in declared order" % down_asked); bad += 1

# REORDERED: the SAME rungs, the SAME abilities, the OTHER order — and therefore
# the OTHER answer. Compared against the ALL baseline, because that is the only
# scenario in which the RANKING is what decides. If these matched, order would be
# decoration.
reversed_order = list(reversed(order))
r_asked, r_answered = walk(reversed_order, ALL)
if len(order) > 1 and r_answered == top_answered:
    print("    reversing the declaration did not change who answers (%r both ways) — "
          "the ranking is not load-bearing" % r_answered); bad += 1
if r_asked != [reversed_order[0]] or r_answered != reversed_order[0]:
    print("    reversed, the chain asked %r and %r answered instead of %r"
          % (r_asked, r_answered, reversed_order[0])); bad += 1

# REMOVED: a rung absent from the declaration is never tried, even though it is
# perfectly able to answer.
for dropped in order:
    remaining = [r for r in order if r != dropped]
    d_asked, _ = walk(remaining, ALL)
    if dropped in d_asked:
        print("    %s was asked after being removed from the declaration" % dropped); bad += 1

print("    order honoured: %s | first-ranked %s answers, reversed %s answers"
      % (" -> ".join(order), top_answered, r_answered))
sys.exit(1 if bad else 0)
PYTHON
}

# c2b <GitAuthChain.kt> <DriveGitChain.kt> : nothing re-ranks the declared list
c2b() {
    local walker="$1" wiring="$2" bad=0
    grep -qE 'for \(\(index, rung\) in chain\.withIndex\(\)\)' "$walker" \
        || { echo "    the walker does not iterate the list it was handed in order"; bad=1; }
    # A sort, a reversal or a rank of its own inside the walker would be a SECOND
    # ranking that could disagree with the declaration.
    if grep -nE 'sortedBy|sortedWith|sorted\(\)|reversed\(\)|asReversed' "$walker"; then
        echo "    the walker re-sorts the chain — the declaration is the only ranking"; bad=1
    fi
    # The wiring maps the declaration straight through, preserving order.
    grep -qE 'List<GitAuthChain\.Rung> = declared\.map \{ rung ->' "$wiring" \
        || { echo "    the wiring does not map the declared rungs straight through"; bad=1; }
    if grep -nE 'sortedBy|sortedWith|sorted\(\)|reversed\(\)' "$wiring"; then
        echo "    the wiring re-sorts the declared rungs"; bad=1
    fi
    # No rung id may be hardcoded as a ranking anywhere in the wiring: the ids it
    # names are KINDS it dispatches on, which is a different thing from a rank.
    grep -qE 'AuthDeclaration\.gitChain' "$wiring" \
        || { echo "    the wiring does not read the declared chain at all"; bad=1; }
    return $bad
}

# c8 <shared build.json> <DriveGitChain.kt> <FleetGit.kt> : the fleet rung dials the
# routes the SERVICE declares, under its base path, and the app never patches a landing
#
# #655 THE EDGE DROPS THE PREFIX ON THE AUTHELIA RETURN. `handle_path /<prefix>/*` strips
# the prefix for everything in the block INCLUDING the forward_auth subrequest, so Authelia
# builds `rd` from the already-stripped URI and a completed login can land the owner on
# /repos instead of /git/repos. That is an edge defect on 26 blocks of that Caddy config and
# it is filed there. THIS CHECK EXISTS TO KEEP IT FILED THERE: if the app ever "fixes" it by
# rewriting the return URL or re-adding the prefix client-side, the real defect becomes
# invisible and outlives the workaround. So the assertion is about the LANDING, not the
# login: the declared paths must carry the service's base path, and no Kotlin may synthesise
# or patch one.
c8() {
    local bj="$1" wiring="$2" client="$3" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
f = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["git_chain"]["providers"]["fleet"]
bad = 0
# The route that never existed. A 404 reads as a clean fall-through, so a wrong path
# here makes the fleet leg skip FOREVER while looking healthy.
if "token_url" in f:
    print("    the fleet rung declares token_url again: git-proxy-api never served a "
          "credential-mint route, and a rung that hands the phone a GitHub token is a "
          "rung on which the phone holds one"); bad = 1
# Every declared URL must sit under the service's own base path, which is what #655
# strips. A path that has already lost it would 404 for ever.
# #688 clone_url too: the fleet rung clones over git-proxy-api's /git/clone route.
for key in ("health_url", "repos_url", "tarball_url", "clone_url"):
    url = f.get(key, "")
    if not url:
        print("    the fleet rung declares no %s" % key); bad = 1
    elif "/git/" not in url and not url.endswith("/git"):
        print("    %s (%r) does not carry the service's /git base path — this is the #655 "
              "prefix-drop shape baked into the declaration" % (key, url)); bad = 1
# The session, and the header that carries it, are both declared.
if f.get("session_provider") != "authelia_web":
    print("    the fleet rung's session_provider is %r; the ordinary browser login is "
          "authelia_web" % f.get("session_provider")); bad = 1
if f.get("session_header", "").lower() != "cookie":
    print("    session_header is %r. authelia_web yields a SESSION COOKIE, not a bearer "
          "(libs:auth leaves SignInResult.bearer empty for that way), so Authorization "
          "would present an empty credential" % f.get("session_header")); bad = 1
if f.get("holds_github_credential") is not False:
    print("    the fleet rung claims to hold a GitHub credential; the whole point is that "
          "it does not"); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # NOTHING composes or repairs a fleet URL in Kotlin. Comments are stripped first so
    # the prose explaining the defect does not read as the defect.
    # ONE FILE AT A TIME: _code takes a single path, so `_code "$a" "$b"` scanned only
    # $a and the client was never looked at. That exact bug made this check hollow and
    # the mutation block below caught it.
    local synth
    for f_ in "$wiring" "$client"; do
        synth="$(_code "$f_" | grep -nE '"https?://|/git/repos|\brd=|api\.diegonmarcos' || true)"
        [ -z "$synth" ] || { echo "    $(basename "$f_") builds or repairs a fleet URL / Authelia return in Kotlin:"; \
                             printf '%s\n' "$synth" | sed 's/^/        /'; bad=1; }
    done
    # The declared header is USED, not a literal Authorization.
    grep -qE 'setRequestProperty\(FleetGit\.sessionHeader\(\), session\)' "$wiring" \
        || { echo "    the fleet leg does not send the DECLARED session header"; bad=1; }
    for f_ in "$wiring" "$client"; do
        grep -qE '"Authorization", *"Bearer' <<<"$(_code "$f_")" \
            && { echo "    $(basename "$f_") sends a literal Authorization: Bearer — authelia_web yields a cookie, so this would present an EMPTY credential and read the refusal as a fall-through"; bad=1; }
    done
    # A redirect must NOT be followed, or the portal's login page returns 200 and a dead
    # path passes for a live one.
    for f_ in "$wiring" "$client"; do
        grep -qE 'instanceFollowRedirects = false' "$f_" \
            || { echo "    $(basename "$f_") follows redirects: the portal's login page would come back as a 200"; bad=1; }
        # NOT merely that the branch exists: its BODY is what matters. A
        # `in 300..399 -> Outcome.Listed(...)` satisfies a presence-only grep while
        # reporting the edge's login page as a successful listing.
        grep -qE 'in 300\.\.399 -> (Page\.Stop\(Outcome\.Blocked|Outcome\.Blocked|GitAuthChain\.Answer\.Unreachable)' "$f_" \
            || { echo "    $(basename "$f_") does not map a 3xx to Blocked/Unreachable — the edge answers 3xx for ANY path under the prefix, even with the container stopped, so it proves nothing either way and must never read as success"; bad=1; }
    done
    return $bad
}

echo "── C8 #655 the declared routes carry the service's base path, and the app patches no landing ──"
c8 "$SHARED_BJ" "$WIRING" "$FLEET_CLIENT" \
    && pass "the fleet rung declares the service's real routes under /git, rides the declared session header, and no Kotlin builds or repairs a URL" \
    || fail "the fleet rung's routes, session header, or redirect handling do not match the service that shipped"

# c9 <shared build.json> <FleetGit.kt> <GitReposScreen.kt> <SignInUi.kt> <SignIn.kt>
# <DriveGitChain.kt> : #669 the fleet's OWN git server is the first rung, and the
# sign-in loop can CLOSE on a fresh phone
#
# The defect this pins was truthful and useless: both rungs empty on a real device,
# and the "Get a credential" affordance unable to close the loop. Three properties
# make a fresh phone reach a repo list, and each is asserted on the MESSAGE:
#  · GITEA IS A DECLARED RUNG, ranked first — ours, Authelia-fronted, no third-party
#    credential; resolved from the declaration, no Kotlin literal for host/endpoint,
#    and the response's array key is DECLARED (repos_field), not a per-provider
#    branch. The listing is fetched from the rung that ANSWERED, not the family's
#    first-ranked endpoint.
#  · THE SESSION IS DECOUPLED FROM THE ARTIFACT. The browser login's cookie is
#    delivered to the host BEFORE the config-artifact fetch: those are two facts,
#    and coupling them meant a failing config route silently discarded a good
#    session — "no fleet sign-in on this phone yet" forever.
#  · THE DIALOG LOADS THE DECLARED PORTAL, not a protected route, so a completed
#    login lands on a page that exists instead of riding #655's broken rd
#    round-trip. (No URL is repaired client-side; c8 still enforces that.)
#  · A RUNG'S FAILURE NAMES THE NEXT STEP, not just itself.
c9() {
    local bj="$1" client="$2" page="$3" ui="$4" providers_kt="$5" wiring="$6" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
auth = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]
chain = auth["git_chain"]; order = chain.get("order") or []; prov = chain.get("providers") or {}
bad = 0
if not order or order[0] != "gitea":
    print("    gitea is not the first rung: %r" % order); bad = 1
g = prov.get("gitea") or {}
if g.get("kind") != "fleet_proxy":
    print("    the gitea rung's kind is %r; it is the second instance of the fleet_proxy shape" % g.get("kind")); bad = 1
if g.get("session_provider") != "authelia_web":
    print("    gitea's session_provider is %r; the ordinary browser login is authelia_web" % g.get("session_provider")); bad = 1
if (g.get("session_header") or "").lower() != "cookie":
    print("    gitea's session_header is %r; authelia_web yields a session COOKIE" % g.get("session_header")); bad = 1
for key in ("repos_url", "health_url"):
    url = g.get(key, "")
    if not url.startswith("https://git.diegonmarcos.com/api/v1/"):
        print("    gitea's %s (%r) is not the fleet git server's own API subdomain — a"
              " subdomain has no handle_path prefix for the Authelia rd round-trip to"
              " drop (#655), which is part of why this rung can close the loop" % (key, url)); bad = 1
if not (g.get("repos_field") or "").strip():
    print("    gitea declares no repos_field: the client would look for the proxy's"
          " 'repos' key in gitea's {'data': [...]} body and list nothing, forever,"
          " while both sides look healthy"); bad = 1
if "token_url" in g:
    print("    the gitea rung declares token_url — no rung mints a credential for the phone"); bad = 1
if g.get("holds_github_credential") is not False:
    print("    the gitea rung must declare holds_github_credential false — no third-party"
          " credential exists anywhere on this leg"); bad = 1
# The browser login loads the DECLARED portal — a page that exists after login.
web = next((p for p in auth["sign_in"]["providers"] if p.get("id") == "authelia_web"), {})
purl = web.get("portal_url", "")
if not purl.startswith("https://"):
    print("    authelia_web declares no portal_url: the dialog would load a protected"
          " route and ride #655's broken rd round-trip to a 404 landing"); bad = 1
elif "api.diegonmarcos.com" in purl:
    print("    portal_url (%r) points at the protected API host, not the portal — that"
          " is the #655-exposed round-trip again" % purl); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # The client reads the DECLARED array key, and holds no per-provider literal:
    # neither the gitea host nor its 'data' key may appear in Kotlin.
    grep -qE 'optString\("repos_field"\)' "$client" \
        || { echo "    FleetGit does not read the declared repos_field"; bad=1; }
    local lit
    for f_ in "$client" "$wiring" "$page"; do
        lit="$(_code "$f_" | grep -nE 'git\.diegonmarcos|"data"' || true)"
        [ -z "$lit" ] || { echo "    $(basename "$f_") holds a gitea host or array-key literal:"; \
                           printf '%s\n' "$lit" | sed 's/^/        /'; bad=1; }
    done
    # The listing is fetched from the rung that ANSWERED.
    grep -qE 'fun repos\(session: String, rungId: String' "$client" \
        || { echo "    FleetGit.repos cannot be told which rung answered"; bad=1; }
    grep -qE 'fetchFleetListing\(outcome\.answeredBy' "$page" \
        || { echo "    the page lists from the family's first endpoint instead of the rung"; \
             echo "    that answered — after a fall-through it would ask the very service"; \
             echo "    the chain just measured as unreachable"; bad=1; }
    # THE LOOP CLOSES: the session is handed to the host BEFORE the artifact fetch.
    python3 - "$ui" <<'PYTHON' || bad=1
import sys
src = open(sys.argv[1], encoding="utf-8").read().splitlines()
deliver = next((i for i, l in enumerate(src) if "host.onWebSession(cookie)" in l), None)
fetch = next((i for i, l in enumerate(src) if "ConfigArtifact.fetchWithCookie(cookie)" in l), None)
if deliver is None:
    print("    the web dialog never delivers the session to the host"); sys.exit(1)
if fetch is not None and deliver > fetch:
    print("    the session is delivered only AFTER the artifact fetch — a failing config"
          " route discards a good session, and the git page reports 'no fleet sign-in"
          " on this phone yet' forever, however many logins complete"); sys.exit(1)
PYTHON
    # The dialog loads the declared portal, and the declaration is READ, not typed.
    grep -qF 'loadUrl(p.portalUrl.ifBlank { endpoint })' "$ui" \
        || { echo "    the web dialog does not load the declared portal page"; bad=1; }
    grep -qE 'portalUrl = p\.optString\("portal_url"\)' "$providers_kt" \
        || { echo "    portal_url is not parsed off the declaration"; bad=1; }
    grep -qE '"https?://' <<<"$(_code "$ui")" \
        && { echo "    SignInUi holds a URL literal — the portal must be declared"; bad=1; }
    # A rung's failure names the NEXT STEP, not just itself.
    grep -qF 'no fleet sign-in on this phone yet — the Authelia sign-in below starts one' "$wiring" \
        || { echo "    the no-session fall-through names no next step — the dead end the"; \
             echo "    owner spent three days in"; bad=1; }
    grep -qF 'the vault import delivers one' "$wiring" \
        || { echo "    the github decline names no next step"; bad=1; }
    return $bad
}

echo "── C9 #669 gitea first, and the sign-in loop closes on a fresh phone ──"
c9 "$SHARED_BJ" "$FLEET_CLIENT" "$PAGE" "$SIGNIN_UI" "$SIGNIN" "$WIRING" \
    && pass "gitea is the declared first rung, the listing follows the answering rung, the session outlives a failing artifact fetch, the dialog lands on the declared portal, and every failure names its next step" \
    || fail "a fresh phone with zero credentials still cannot reach a repo list"

echo "── C2 reordering the declaration reorders the REAL attempts ──"
c2 "$SHARED_BJ" && pass "the declared order is the attempted order, reversing it changes who answers, and a removed rung is never tried" \
    || fail "the attempt order does not follow the declaration"
c2b "$WALKER" "$WIRING" && pass "neither the walker nor the wiring holds a ranking of its own" \
    || fail "something re-ranks the chain outside the declaration"

# c3 <GitAuthChain.kt> : a rung that cannot be reached FALLS THROOUGH, and says so
c3() {
    local walker="$1" bad=0
    # Three distinct non-answers, each continuing the walk. If Unreachable were
    # treated as terminal, "our servers are down" would break git instead of
    # routing around it — which is the whole point of the ticket.
    for kind in Unreachable Declined NoImplementation; do
        grep -qE "is Answer\.$kind ->" "$walker" \
            || { echo "    the walker has no branch for a $kind rung"; bad=1; }
    done
    # A rung's own implementation throwing is the SAME fall-through, not a dead
    # chain: a dead socket must not take the next provider down with it.
    # The catch and its Unreachable sit on separate lines, so this reads the two
    # lines after the catch rather than one line in isolation.
    grep -qE 'Answer\.Unreachable' <<<"$(grep -A2 'catch (t: Throwable)' "$walker")" \
        || { echo "    a rung that throws is not turned into a fall-through"; bad=1; }
    # Only EXHAUSTION fails. There must be no early return that abandons the walk
    # before the list ends.
    # Counted as OCCURRENCES, not matching lines, and anywhere on the line rather
    # than only at its start: `is Answer.Unreachable -> return Outcome(` is an
    # early exit that a line-anchored grep walks straight past. That exact
    # mutation passed this check until the mutation block below caught it.
    local returns
    returns="$(_code "$walker" | grep -oE 'return Outcome\(' | wc -l | tr -d ' ')"
    [ "$returns" = "1" ] \
        || { echo "    the walker returns an Outcome from $returns places; all but one would be"; \
             echo "    an early exit that abandons the rungs after it"; bad=1; }
    # THE FALL-THROUGH IS VISIBLE. Every non-answer records a STEP with a sentence,
    # and the narrative names the skipped rungs. A chain that degrades silently is
    # the #639/#452 shape.
    grep -qE 'unreachable \(\$\{answer\.why\}\)' "$walker" \
        || { echo "    an unreachable rung is not worded for a person to read"; bad=1; }
    grep -qE 'fun narrative\(\)' "$walker" \
        || { echo "    the outcome cannot account for itself: no narrative"; bad=1; }
    grep -qE 'steps\.filter \{ it\.result != Result\.NOT_NEEDED \}\.joinToString' "$walker" \
        || { echo "    the success narrative does not list the rungs that were skipped — "; \
             echo "    a fall-through nobody can see working"; bad=1; }
    # NOT_NEEDED exists so a rung AFTER the answer is reported rather than hidden.
    grep -qE 'Result\.NOT_NEEDED' "$walker" \
        || { echo "    a rung after the answer is not accounted for at all"; bad=1; }
    return $bad
}

# c3b <DriveGitChain.kt> : #647 not existing yet is a FALL-THROUGH, not an error
c3b() {
    local wiring="$1" bad=0
    # A 404 (nothing serving that path yet) and any other unexpected status are
    # Unreachable. Only an explicit refusal is Declined — that one means something
    # different: we WERE reached, and told no.
    grep -qE 'else -> GitAuthChain\.Answer\.Unreachable\("the fleet answered HTTP \$code"\)' "$wiring" \
        || { echo "    an unexpected fleet status is not a fall-through — #647 not being built "; \
             echo "    yet would surface as an error"; bad=1; }
    grep -qE '401, 403 -> GitAuthChain\.Answer\.Declined' "$wiring" \
        || { echo "    an explicit refusal is not distinguished from unreachability"; bad=1; }
    # No fleet sign-in on the phone is indistinguishable from the fleet being down
    # and must behave identically.
    #
    # #653 THE VARIABLE IS `session`, NOT `bearer`, and the rename is the point: this
    # rung rides the Authelia SESSION COOKIE that authelia_web's browser login earns
    # (libs:auth leaves SignInResult.bearer EMPTY for that way and delivers the
    # session through onWebSession). A leg that sent "Bearer \$bearer" would present an
    # empty credential and read the refusal as a fall-through. The INVARIANT this
    # check enforces is unchanged; only the honest name of the thing moved.
    grep -qE 'session\.isBlank\(\) *\) return GitAuthChain\.Answer\.Unreachable' "$wiring" \
        || { echo "    a phone with no fleet credential does not fall through"; bad=1; }
    # An undeclared/unimplemented KIND keeps its declared position instead of
    # vanishing from the chain.
    grep -qE 'else -> GitAuthChain\.Answer\.NoImplementation' "$wiring" \
        || { echo "    a declared rung this build cannot serve is not reported in place"; bad=1; }
    # A throw anywhere in the fleet leg is a fall-through too.
    grep -qE 'catch \(t: Throwable\) \{' "$wiring" \
        || { echo "    the fleet leg lets an exception escape"; bad=1; }
    return $bad
}

echo "── C3 a fleet timeout FALLS THROUGH to github, visibly ──"
c3 "$WALKER" && pass "every non-answer continues the walk, only exhaustion fails, and each skipped rung is worded" \
    || fail "the chain does not fall through, or falls through silently"
c3b "$WIRING" && pass "an absent #647 (404 / no bearer / unimplemented kind) is a clean fall-through, not an error" \
    || fail "an unreachable fleet leg does not render as a fall-through"

# c4 <GitAuthChain.kt> <DriveGitChain.kt> <drive build.json> : ONE store, ONE id
c4() {
    local walker="$1" wiring="$2" bj="$3" bad=0
    # EXACTLY ONE WRITE. Two setSecret calls in the walker would be two places a
    # rung's credential could land, which is how the #629 split happens.
    # OCCURRENCES, not matching lines: `grep -c` counts lines, so two setSecret
    # calls separated by a semicolon read as one write. The mutation block below
    # caught precisely that.
    local writes
    writes="$(_code "$walker" | grep -oE 'setSecret\(' | wc -l | tr -d ' ')"
    [ "$writes" = "1" ] \
        || { echo "    the walker writes the credential $writes time(s); exactly one write, under one id"; bad=1; }
    grep -qE 'store\?\.setSecret\(credentialId, credential\)' "$walker" \
        || { echo "    the write does not use the credentialId it was handed"; bad=1; }
    # THE ID IS A PARAMETER, never a literal. A literal here is the second id.
    grep -qE 'credentialId: String,' "$walker" \
        || { echo "    credentialId is not a parameter of the walk"; bad=1; }
    if grep -nE '"vault-git-https"' "$walker" "$wiring"; then
        echo "    the credential id is hardcoded — it belongs to the declaration alone"; bad=1
    fi
    # The wiring reads the id off the DECLARED map the vault import already uses.
    grep -qE 'fun credentialId\(\): String = Declarations\.authCredentialIds\["git"\]\.orEmpty\(\)' "$wiring" \
        || { echo "    the wiring does not resolve the id from auth.applies.git.credential_id"; bad=1; }
    grep -qE 'credentialId = credentialId\(\)' "$wiring" \
        || { echo "    the walk is not given the declared id"; bad=1; }
    # ONE STORE. Nothing in the wiring may open a credential store of its own.
    if grep -nE 'EncryptedSharedPreferences|getSharedPreferences|MasterKey|KeyStore' "$wiring"; then
        echo "    the wiring opens a credential store of its own — libs:git-sync's is the only one"; bad=1
    fi
    grep -qE 'store = GitCredentialStore\(ctx\)' "$wiring" \
        || { echo "    the wiring does not write through libs:git-sync's own store"; bad=1; }
    # A BLANK ID STORES NOTHING rather than inventing one.
    grep -qE 'credentialId\.isNotBlank\(\)' "$walker" \
        || { echo "    a blank id is not guarded: the token would be filed under an invented id"; bad=1; }
    # And the id the whole chain shares is the one the vault import declares.
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["applies"]["git"]
if not (g.get("credential_id") or "").strip():
    print("    auth.applies.git declares no credential_id: both rungs would have nowhere to write")
    sys.exit(1)
PYTHON
    return $bad
}

# c7 <GitAuthChain.kt> <GitReposScreen.kt> : no token surfaces
# #653 the fourth input was GhDeviceLogin.kt, whose Granted phase carried the minted
# token. That file is DELETED, so there is no phase carrier left to redact.
c7() {
    local walker="$1" page="$2" bad=0
    # A redacted toString on both carriers, so a stray log line or string template
    # cannot print the secret.
    grep -qE 'override fun toString\(\): String = "Credential\(token=<redacted>\)"' "$walker" \
        || { echo "    Answer.Credential's toString is not redacted"; bad=1; }
    grep -qE 'credential=<redacted>' "$walker" \
        || { echo "    the Outcome's toString is not redacted"; bad=1; }
    # Nothing logs or persists a token outside the one store.
    local leak
    leak="$(grep -nE 'Log\.[a-z]+\(.*(token|secret)|println\(.*token|putString\(.*token' "$walker" "$page" || true)"
    [ -z "$leak" ] || { echo "    a token leaves memory:"; printf '%s\n' "$leak" | sed 's/^/        /'; bad=1; }
    # #705 GhRunner's half (token in the environment, never `gh auth token`) moved to
    # lib-apks/test/test-git-binaries.sh with libs:gh.
    # The page's chain state carries the public USER code and the narrative, and
    # no token: a data class holding the secret would put it in UI state.
    grep -qE 'private data class GitChainState\(' "$page" \
        || { echo "    the page has no declared chain state"; bad=1; }
    python3 - "$page" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'private data class GitChainState\((.*?)\n\)', src, re.S)
if not m:
    print("    could not read GitChainState to check it for a token field"); sys.exit(1)
body = m.group(1)
if re.search(r'\btoken\b|\bsecret\b|credential', body):
    print("    the page's chain state carries the credential: %r" % body.strip()); sys.exit(1)
PYTHON
    return $bad
}

echo "── C4 both rungs write the SAME credential id, into the one store ──"
c4 "$WALKER" "$WIRING" "$BJ" && pass "one setSecret, the id a parameter off auth.applies.git.credential_id, no second store" \
    || fail "the credential could be split across ids or stores"


# c6 <drive build.json> <app/build.gradle> <ship workflow> <caller...> : gix is not
# compiled into this app, and no caller routes push to it.
# #705 Cloud Drive linked libs:gix (10 MB of static gitoxide) and called it from
# nowhere; it ships as Cloud-Lib-Gix.apk alone now, so a gix change republishes that
# APK and not this one. Linking it back, or watching its directory, re-couples them.
c6() {
    local bj="$1" gradle="$2" wf="$3" bad=0; shift 3
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
m = json.load(open(sys.argv[1], encoding="utf-8")).get("modules", {})
if "libs:gix" in m:
    print("    build.json::modules declares libs:gix again"); sys.exit(1)
if "libs:gix" in (m.get("app", {}).get("depends_on") or []):
    print("    the app module depends on libs:gix again"); sys.exit(1)
PYTHON
    [ "$(_code "$gradle" | grep -cF "project(':libs:gix')")" -eq 0 ] \
        || { echo "    app/build.gradle compiles libs:gix again"; bad=1; }
    [ "$(grep -cF 'ab_cloud-libs-shared/libs/gix/' "$wf")" -eq 0 ] \
        || { echo "    the ship workflow watches libs/gix again, so a gix change republishes Cloud Drive"; bad=1; }
    # No caller routes push there. Prose about it is fine; code is not.
    local f routed
    for f in "$@"; do
        routed="$(_code "$f" | grep -nE '[Gg]ix.*[Pp]ush|[Pp]ush.*[Gg]ix')"
        [ -z "$routed" ] || {
            echo "    $(basename "$f") routes push to gix:"
            printf '%s\n' "$routed" | sed 's/^/        /'; bad=1; }
    done
    return $bad
}

echo "── C6 gix is not in this app, and nothing routes push to it ──"
c6 "$BJ" "$APP_GRADLE" "$SHIP_WF" "$WALKER" "$WIRING" "$PAGE" \
    && pass "neither declared, compiled nor watched, and no caller sends push to gix" \
    || fail "gix is coupled back into Cloud Drive, or push is routed to it"

echo "── C7 no token is ever rendered or logged ──"
c7 "$WALKER" "$PAGE" && pass "both carriers redact their toString and the page's state holds none" \
    || fail "a token can reach a log, a process listing or the screen"

# c10 <page> : #669 a LISTED repository is cloned from ITS OWN listing's URL.
#
# MEASURED, 2026-09-30, on the device itself: gitea's /api/v1/repos/search lists
# 23 repositories under owner `diego` — not the page's declared owner — and each
# item's clone_url names the fleet's own git host. The page used to rebuild every
# clone URL from the declared owner and the github remote-mode template, so a
# repository LISTED BY GITEA was CLONED FROM GITHUB: the gitea leg listed, and
# its clone silently rode the other leg. An anonymous clone from the fleet's
# gitea was proven to land on disk the same day; what stood between the owner
# and his repos was this re-templating, not the transport.
c10() {
    # grep -c, never grep -q: under pipefail, -q's early exit SIGPIPEs the _code
    # stage and a MATCH reads as a failed pipeline — a red that lies about green.
    local page="$1" bad=0
    # the personal row hands the listing item's clone URL (and ssh URL) to clone()
    [ "$(_code "$page" | grep -cE 'clone\(gh\.name, gh\.cloneUrl, gh\.sshUrl\)')" -ge 1 ] \
        || { echo "    the personal row does not clone the URL its listing declared"; bad=1; }
    # a blank listed URL is LOUD, not a silent re-template onto another host
    [ "$(_code "$page" | grep -cE 'if \(gh\.cloneUrl\.isBlank\(\)\) handoff = ctx\.getString\(R\.string\.git_clone_url_missing')" -ge 1 ] \
        || { echo "    a listing item with no clone URL falls through silently"; bad=1; }
    # the terminal handoff clones the url it is GIVEN — it cannot re-template
    [ "$(_code "$page" | grep -cE 'fun cloneViaTerminal\(name: String, url: String\)')" -ge 1 ] \
        || { echo "    cloneViaTerminal builds its own URL instead of taking the listing's"; bad=1; }
    return $bad
}

echo "── C10 #669 a listed repository clones from the leg that listed it ──"
c10 "$PAGE" && pass "the listing's clone URL travels to the clone, a blank one is loud, and the terminal clones what it is given" \
    || fail "a gitea-listed repository could clone from github.com — the wrong leg, silently"

# c11 <shared bj> <FleetGit> <GitModels> <GitEngine> <coordinator> <page> : #669 a
# FLEET-LISTED repository CLONES on the session that listed it, in-process.
#
# MEASURED, 2026-09-30, on the device: through the public edge, the Authelia session
# COOKIE is the only mechanism that both passes the gate and reaches gitea usable — a
# BEARER passes the gate but the edge FORWARDS Authorization and gitea rejects it as its
# own token (401 "invalid username, password or token"), and gitea basic auth never
# passes the gate at all (302). The terminal's git cannot present the app's session, so
# the fleet clone leg must be the IN-PROCESS engine with the session on the transport.
# Five properties, each asserted on the message:
#  · WHICH URL IS DIALED IS DECLARED: the gitea rung carries a clone_url template for
#    the public edge and a clone_url_order naming the candidates; the client resolves
#    both off the declaration and holds no template of its own.
#  · THE SESSION RIDES THE CLONE: libs:git-sync grows a GitAuth.Session (header + value,
#    toString redacted) and the engine attaches it to the HTTP transport on clone AND on
#    the later fetch/pull path — via the DECLARED header name.
#  · A BEARER NEVER REACHES GITEA: no Authorization/Bearer literal anywhere on the leg.
#  · EVERY ENGINE CALL IS SESSION-AWARE: the coordinator assembles auth in exactly one
#    place, from FleetSession (process memory) + the declared header — the store, which
#    must never hold the session, is consulted nowhere else.
#  · EVERY FAILURE IS LOUD AND NAMES ITS NEXT STEP: a redirect mid-clone reads as "the
#    gate answered — sign in", a 401/403 as "the fleet refused", no session as an offer
#    of the sign-in on the same page, and nothing falls through to another host.
c11() {
    local bj="$1" client="$2" models="$3" engine="$4" coord="$5" page="$6" bad=0
    python3 - "$bj" <<'PYTHON' || bad=1
import json, sys
g = json.load(open(sys.argv[1], encoding="utf-8"))["auth"]["git_chain"]["providers"]["gitea"]
bad = 0
t = g.get("clone_url", "")
if not t.startswith("https://git.diegonmarcos.com/"):
    print("    gitea's clone_url (%r) is not the public edge — the mesh projection is"
          " unreachable off WireGuard, and any other host is the wrong leg" % t); bad = 1
if "{owner}" not in t or "{name}" not in t:
    print("    clone_url (%r) does not template {owner}/{name} from the LISTING'S item —"
          " a fixed owner is the re-templating c10 exists to forbid" % t); bad = 1
order = g.get("clone_url_order")
if not isinstance(order, list) or not order:
    print("    no clone_url_order: which URL is attempted would be Kotlin's decision"); bad = 1
else:
    if set(order) - {"declared", "listed"}:
        print("    clone_url_order names an unknown source: %r" % order); bad = 1
    if "declared" not in order:
        print("    clone_url_order (%r) drops the declared edge template — a phone off"
              " the mesh could never clone" % order); bad = 1
sys.exit(1 if bad else 0)
PYTHON
    # The client resolves template AND order off the declaration; c9 already forbids
    # it a host literal, so together the URL is data end to end.
    grep -qE 'optString\("clone_url"\)' "$client" \
        || { echo "    FleetGit does not read the declared clone_url template"; bad=1; }
    grep -qE 'optJSONArray\("clone_url_order"\)' "$client" \
        || { echo "    FleetGit does not read the declared candidate order"; bad=1; }
    # THE SESSION AUTH EXISTS and cannot print its value.
    grep -qE 'data class Session\(val header: String, val value: String\) : GitAuth\(\)' "$models" \
        || { echo "    libs:git-sync has no Session auth (header + value)"; bad=1; }
    grep -qE 'override fun toString\(\): String = "Session\(header=\$header, value=<redacted>\)"' "$models" \
        || { echo "    GitAuth.Session's toString is not redacted — UI state or a log line could leak the cookie"; bad=1; }
    # THE CLIENT SENDS IT: since #1408 JGit runs in-process (libs:git-core), and what the
    # engine attaches to the HTTP transport (on clone AND on the shared withAuth path every later
    # fetch/pull/push rides) is checked where it ships, in ab_cloud-libs-shared/lib-apks/test/
    # libs:git-core tests. Here: the wire form of the session carries the DECLARED header name and
    # the value, and nothing else.
    grep -qE 'is GitAuth\.Session -> JSONObject\(\)\.put\("kind", KIND_SESSION\)\.put\("header", auth\.header\)\.put\("value", auth\.value\)' "$engine" \
        || { echo "    the client's wire form does not carry the session under the DECLARED header name"; bad=1; }
    # A BEARER NEVER REACHES GITEA: measured to collide with gitea's own token auth.
    local bearer f_
    for f_ in "$engine" "$coord" "$page"; do
        bearer="$(_code "$f_" | grep -nE '"Authorization"|Bearer ' || true)"
        [ -z "$bearer" ] || { echo "    $(basename "$f_") spells Authorization/Bearer on the clone leg:"; \
                              printf '%s\n' "$bearer" | sed 's/^/        /'; bad=1; }
    done
    # ONE AUTH ASSEMBLY POINT in the coordinator: the session from PROCESS MEMORY on
    # the DECLARED header; the store consulted only inside it.
    grep -qE 'const val AUTH_SESSION = "session"' "$coord" \
        || { echo "    the coordinator has no session auth kind"; bad=1; }
    grep -qE 'GitAuth\.Session\(FleetGit\.sessionHeader\(\), FleetSession\.cookie\)' "$coord" \
        || { echo "    the session auth is not assembled from the declared header + the in-memory session"; bad=1; }
    local stores
    stores="$(_code "$coord" | grep -oE 'credentials\.authFor\(' | wc -l | tr -d ' ')"
    [ "$stores" = "1" ] \
        || { echo "    the coordinator reads the credential store from $stores places; every engine call must"; \
             echo "    go through the ONE session-aware authFor, or a session repository dials anonymously"; bad=1; }
    # THE PAGE ROUTES A FLEET-LISTED ROW HERE — in-process, never the terminal, which
    # cannot present the session.
    grep -qE 'if \(wayListing\.rungId\.isNotBlank\(\)\) cloneViaFleet\(wayListing\.rungId, gh\)' "$page" \
        || { echo "    a fleet-listed row does not clone on the leg that listed it"; bad=1; }
    grep -qE 'authKind = GitSyncCoordinator\.AUTH_SESSION' "$page" \
        || { echo "    the fleet clone does not carry the session auth kind"; bad=1; }
    grep -qE 'FleetGit\.cloneUrl\(rungId, gh\.owner, gh\.name, gh\.cloneUrl\)' "$page" \
        || { echo "    the fleet clone URL is not resolved from the declaration for the ITEM'S owner/name"; bad=1; }
    python3 - "$page" <<'PYTHON' || bad=1
import re, sys
src = open(sys.argv[1], encoding="utf-8").read()
m = re.search(r'fun cloneViaFleet\(.*?\n    \}', src, re.S)
if not m:
    print("    cloneViaFleet is missing"); sys.exit(1)
if "Terminal" in m.group(0):
    print("    cloneViaFleet hands off to the terminal, whose git cannot present the"
          " app's Authelia session — the clone would always bounce off the gate"); sys.exit(1)
PYTHON
    # LOUD, WITH A NEXT STEP: no session offers the sign-in; a redirect names the gate
    # and the sign-in; a refusal names the fleet; the coordinator rewrites a failed
    # session clone through that explainer.
    grep -qE 'R\.string\.git_fleet_clone_no_session' "$page" \
        || { echo "    a clone with no session is not loud about the sign-in that starts one"; bad=1; }
    # _code, not a whole-file grep: the KDoc above the explainer QUOTES both wordings,
    # so a comment must not satisfy a check about the code (the exact hollow the
    # mutation block caught on this check's first landing). And grep -c, never -q:
    # under pipefail, -q's early exit SIGPIPEs the _code stage and a MATCH reads as
    # a failed pipeline — c10's own trap, re-caught here as an intermittent MUT-VOID.
    [ "$(_code "$client" | grep -cF 'did not satisfy')" -ge 1 ] \
        || { echo "    a redirect mid-clone is not worded as the gate answering (sign in again)"; bad=1; }
    [ "$(_code "$client" | grep -cF 'the fleet refused this clone')" -ge 1 ] \
        || { echo "    a 401/403 mid-clone is not worded as the fleet's own refusal"; bad=1; }
    grep -qE 'if \(authKind == AUTH_SESSION\) FleetGit\.explainCloneFailure\(why\) else why' "$coord" \
        || { echo "    a failed session clone reaches the page as raw transport prose with no next step"; bad=1; }
    return $bad
}

echo "── C11 #669 a fleet-listed repository clones ON the session that listed it ──"
c11 "$SHARED_BJ" "$FLEET_CLIENT" "$GIT_MODELS" "$GIT_ENGINE" "$COORD" "$PAGE" \
    && pass "the edge clone URL and its order are declared, the session rides the transport in-process on the declared header, no bearer can reach gitea, and every failure names its next step" \
    || fail "the gitea leg lists but cannot clone — or clones on the wrong mechanism"

# ══ MUT every check above goes RED when its property is broken ══════════════
#
# This block is the only part of the file that can tell a real assertion from a
# decorative one. Each mutation runs the SAME function the live check runs, on a
# COPY of the real sources, and the copy is verified GREEN FIRST: a mutation that
# goes red because the edit broke the file, or because the check was already
# failing, proves nothing at all and is reported as MUT-VOID rather than counted.

MUT="$(mktemp -d)"
trap 'rm -rf "$MUT"' EXIT
MUTATIONS=0; HOLLOW=0
W="$MUT/w"

# _stage : fresh pristine copies of every source a check reads
_stage() {
    rm -rf "$W"; mkdir -p "$W"
    cp "$SHARED_BJ" "$W/shared.json";      cp "$BJ" "$W/drive.json"
    cp "$WALKER" "$W/GitAuthChain.kt";     cp "$WIRING" "$W/DriveGitChain.kt"
    cp "$PAGE" "$W/GitReposScreen.kt";   cp "$FLEET_CLIENT" "$W/FleetGit.kt"
    cp "$APP_GRADLE" "$W/app.gradle";      cp "$SHIP_WF" "$W/ship.yml"
    cp "$SIGNIN_UI" "$W/SignInUi.kt";      cp "$SIGNIN" "$W/SignIn.kt"
    cp "$GIT_MODELS" "$W/GitModels.kt";    cp "$GIT_ENGINE" "$W/GitEngine.kt"
    cp "$COORD" "$W/GitSyncCoordinator.kt"
}

# _sub <file> <old> <new> : an EXACT replacement that MUST actually apply. A
# mutation that silently matched nothing leaves the file pristine, the check
# passes, and the pass reads as "the guard is decorative" — the wrong verdict
# about the wrong thing. This exits 2 loudly instead.
_sub() {
    python3 - "$1" "$2" "$3" <<'PYTHON'
import sys
p, old, new = sys.argv[1:4]
src = open(p, encoding="utf-8").read()
if old not in src:
    sys.stderr.write("MUTATION DID NOT APPLY: %r absent from %s\n" % (old, p)); sys.exit(2)
open(p, "w", encoding="utf-8").write(src.replace(old, new, 1))
PYTHON
}

# _json <file> <statements over `d`> : mutate a declaration
_json() {
    python3 - "$1" "$2" <<'PYTHON'
import json, sys
d = json.load(open(sys.argv[1], encoding="utf-8"))
exec(sys.argv[2])
json.dump(d, open(sys.argv[1], "w", encoding="utf-8"), indent=2)
PYTHON
}

_green() {
    local label="$1"; shift
    "$@" >/dev/null 2>&1 && return 0
    echo "  MUT-VOID    $label — the UNMUTATED copy already fails, so any red below is meaningless"
    HOLLOW=$((HOLLOW + 1)); return 1
}
_red() {
    local label="$1"; shift
    MUTATIONS=$((MUTATIONS + 1))
    if "$@" >/dev/null 2>&1; then
        echo "  MUT-HOLLOW  $label — mutated and STILL PASSES: that check proves nothing"
        HOLLOW=$((HOLLOW + 1))
    else
        echo "  MUT-RED     $label"
    fi
}

echo "── MUT each property, broken on a copy, must turn its own check red ──"

# ── C1/C2 the declaration ──
_stage && _green "c1" c1 "$W/shared.json" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["order"] = ["github", "fleet"]'
    _red "C1 github ranked above fleet" c1 "$W/shared.json"; }
_stage && _green "c1" c1 "$W/shared.json" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"].pop("fleet")'
    _red "C1 a ranked rung with no provider (open at one end)" c1 "$W/shared.json"; }
_stage && _green "c1" c1 "$W/shared.json" && {
    _json "$W/shared.json" 'd["auth"]["sign_in"]["providers"].append({"id": "github", "kind": "device_flow"})'
    _red "C1 #641's github provider smuggled back in" c1 "$W/shared.json"; }
_stage && _green "c1" c1 "$W/shared.json" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["github"]["credential_id"] = "vault-git-https"'
    _red "C1 a rung carrying its own copy of the credential id" c1 "$W/shared.json"; }
_stage && _green "c2" c2 "$W/shared.json" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["order"] = ["fleet"]; d["auth"]["git_chain"]["providers"].pop("github")'
    _red "C2 the github rung dropped — nothing answers when our servers are down" c2 "$W/shared.json"; }
_stage && _green "c2b" c2b "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/GitAuthChain.kt" 'for ((index, rung) in chain.withIndex())' \
                              'for ((index, rung) in chain.sortedBy { it.id }.withIndex())'
    _red "C2 the walker re-ranks the chain itself" c2b "$W/GitAuthChain.kt" "$W/DriveGitChain.kt"; }
_stage && _green "c2b" c2b "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" && {
    _sub "$W/DriveGitChain.kt" 'List<GitAuthChain.Rung> = declared.map { rung ->' \
                               'List<GitAuthChain.Rung> = declared.reversed().map { rung ->'
    _red "C2 the wiring reverses the declared rungs" c2b "$W/GitAuthChain.kt" "$W/DriveGitChain.kt"; }

# ── C3 the fall-through ──
_stage && _green "c3" c3 "$W/GitAuthChain.kt" && {
    _sub "$W/GitAuthChain.kt" 'is Answer.Unreachable ->' 'is Answer.Unreachable -> return Outcome('
    _red "C3 an unreachable rung ends the walk instead of continuing it" c3 "$W/GitAuthChain.kt"; }
_stage && _green "c3" c3 "$W/GitAuthChain.kt" && {
    _sub "$W/GitAuthChain.kt" 'steps.filter { it.result != Result.NOT_NEEDED }.joinToString' \
                              'steps.takeLast(1).joinToString'
    _red "C3 success stops naming the rungs it fell through (a silent fall-through)" c3 "$W/GitAuthChain.kt"; }
_stage && _green "c3" c3 "$W/GitAuthChain.kt" && {
    _sub "$W/GitAuthChain.kt" '} catch (t: Throwable) {' '} catch (t: NoSuchElementException) {'
    _red "C3 a rung that throws is no longer turned into a fall-through" c3 "$W/GitAuthChain.kt"; }
_stage && _green "c3b" c3b "$W/DriveGitChain.kt" && {
    _sub "$W/DriveGitChain.kt" 'else -> GitAuthChain.Answer.Unreachable("the fleet answered HTTP $code")' \
                               'else -> GitAuthChain.Answer.Declined("the fleet answered HTTP $code")'
    _red "C3 an absent #647 (any unexpected status) surfaces as a refusal, not a fall-through" c3b "$W/DriveGitChain.kt"; }
_stage && _green "c3b" c3b "$W/DriveGitChain.kt" && {
    _sub "$W/DriveGitChain.kt" 'if (session.isBlank()) return GitAuthChain.Answer.Unreachable' \
                               'if (session.isBlank()) return GitAuthChain.Answer.Declined'
    _red "C3 a phone with no fleet sign-in is reported as declined instead of falling through" c3b "$W/DriveGitChain.kt"; }

# ── C4 one credential, one id ──
_stage && _green "c4" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json" && {
    _sub "$W/GitAuthChain.kt" 'store?.setSecret(credentialId, credential)' \
                              'store?.setSecret(credentialId, credential); store?.setSecret("github-token", credential)'
    _red "C4 a second setSecret splits the credential in two (#629)" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json"; }
_stage && _green "c4" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json" && {
    _sub "$W/DriveGitChain.kt" 'Declarations.authCredentialIds["git"].orEmpty()' '"vault-git-https"'
    _red "C4 the id hardcoded instead of read off the declaration" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json"; }
_stage && _green "c4" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json" && {
    _json "$W/drive.json" 'd["auth"]["applies"]["git"]["credential_id"] = ""'
    _red "C4 auth.applies.git declares no credential_id — both rungs would have nowhere to write" \
         c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json"; }
_stage && _green "c4" c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json" && {
    _sub "$W/GitAuthChain.kt" 'credential != null && credentialId.isNotBlank()' 'credential != null'
    _red "C4 a blank id is unguarded, so the token is filed under an invented one" \
         c4 "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/drive.json"; }

# ── C6 gix stays out of this app ──
_stage && _green "c6" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt" && {
    _json "$W/drive.json" 'd["modules"]["libs:gix"] = {"dir": "../ab_cloud-libs-shared/libs/gix", "type": "library"}'
    _red "C6 build.json declares libs:gix again" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c6" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt" && {
    _json "$W/drive.json" 'd["modules"]["app"]["depends_on"].append("libs:gix")'
    _red "C6 the app module depends on libs:gix again" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c6" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt" && {
    _sub "$W/app.gradle" "    implementation project(':libs:auth')" "    implementation project(':libs:auth')
    implementation project(':libs:gix')"
    _red "C6 app/build.gradle compiles libs:gix again" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c6" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt" && {
    _sub "$W/ship.yml" '      - "ab_cloud-libs-shared/libs/git-sync/**"' '      - "ab_cloud-libs-shared/libs/git-sync/**"
      - "ab_cloud-libs-shared/libs/gix/**"'
    _red "C6 the ship workflow watches libs/gix again" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c6" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt" && {
    printf '\nprivate fun pushViaGix() = GixRunner.run("push")\n' >>"$W/DriveGitChain.kt"
    _red "C6 a caller routes push to gix" c6 "$W/drive.json" "$W/app.gradle" "$W/ship.yml" "$W/GitAuthChain.kt" "$W/DriveGitChain.kt" "$W/GitReposScreen.kt"; }

# ── C8 #655 the declared landing, and no client-side repair of it ──
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["fleet"]["repos_url"] = "https://api.diegonmarcos.com/repos"'
    _red "C8 the #655 prefix drop baked into the declaration (repos_url loses /git)" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["fleet"]["token_url"] = "https://api.diegonmarcos.com/pub/git/credential"'
    _red "C8 the credential-mint route that never existed, re-declared" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["fleet"]["session_header"] = "Authorization"'
    _red "C8 the session declared as a bearer header, which would send an EMPTY credential" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["fleet"]["holds_github_credential"] = True'
    _red "C8 the fleet rung claiming it holds a GitHub credential" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["fleet"].pop("clone_url")'
    _red "C8 #688 the fleet rung lost its clone_url — a fleet-listed private repo could not be cloned" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _sub "$W/DriveGitChain.kt" 'connection.setRequestProperty(FleetGit.sessionHeader(), session)' \
                               'connection.setRequestProperty("Authorization", "Bearer $session")'
    _red "C8 a literal Authorization: Bearer back in the fleet leg" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _sub "$W/FleetGit.kt" 'connection.instanceFollowRedirects = false' 'connection.instanceFollowRedirects = true'
    _red "C8 redirects followed, so the portal's login page returns 200 and a dead path passes for a live one" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    printf '\nprivate fun patchLanding(u: String) = u.replace("https://api.diegonmarcos.com/repos", "https://api.diegonmarcos.com/git/repos")\n' >>"$W/FleetGit.kt"
    _red "C8 the app repairs #655's prefix drop client-side, hiding the edge defect" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }
_stage && _green "c8" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt" && {
    _sub "$W/FleetGit.kt" 'in 300..399 -> Page.Stop(Outcome.Blocked(code))' 'in 300..399 -> Page.Ok(emptyList())'
    _red "C8 a 3xx from the edge reported as a successful empty listing" c8 "$W/shared.json" "$W/DriveGitChain.kt" "$W/FleetGit.kt"; }

# ── C7 the token ──
_stage && _green "c7" c7 "$W/GitAuthChain.kt" "$W/GitReposScreen.kt" && {
    _sub "$W/GitAuthChain.kt" 'override fun toString(): String = "Credential(token=<redacted>)"' \
                              'override fun toString(): String = "Credential(token=$token)"'
    _red "C7 Answer.Credential prints the token" c7 "$W/GitAuthChain.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c7" c7 "$W/GitAuthChain.kt" "$W/GitReposScreen.kt" && {
    printf '\nprivate val leak = Log.d("chain", "token=$token")\n' >>"$W/GitAuthChain.kt"
    _red "C7 a token reaches a log line" c7 "$W/GitAuthChain.kt" "$W/GitReposScreen.kt"; }

# ── C9 #669 gitea first, and the loop that closes ──
_c9() { c9 "$W/shared.json" "$W/FleetGit.kt" "$W/GitReposScreen.kt" "$W/SignInUi.kt" "$W/SignIn.kt" "$W/DriveGitChain.kt"; }
_stage && _green "c9" _c9 && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["order"].remove("gitea"); d["auth"]["git_chain"]["providers"].pop("gitea")'
    _red "C9 the gitea rung dropped — the fleet's own git server no longer answers first" _c9; }
_stage && _green "c9" _c9 && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["gitea"]["repos_field"] = ""'
    _red "C9 gitea's array key undeclared — the client reads 'repos' out of {'data': ...} and lists nothing forever" _c9; }
_stage && _green "c9" _c9 && {
    _sub "$W/DriveGitChain.kt" 'no fleet sign-in on this phone yet — the Authelia sign-in below starts one' \
                               'no fleet sign-in on this phone yet'
    _red "C9 the no-session failure stops naming its next step (the three-day dead end)" _c9; }
_stage && _green "c9" _c9 && {
    _sub "$W/SignInUi.kt" '                host.onWebSession(cookie)
                busy = true; failed = false; status = fetching' \
                          '                busy = true; failed = false; status = fetching'
    _red "C9 the session delivered only through a successful artifact fetch — the loop that cannot close" _c9; }
_stage && _green "c9" _c9 && {
    _json "$W/shared.json" '[p.pop("portal_url") for p in d["auth"]["sign_in"]["providers"] if p.get("id") == "authelia_web"]'
    _red "C9 no declared portal — the dialog loads a protected route and lands on #655's 404" _c9; }
_stage && _green "c9" _c9 && {
    _sub "$W/FleetGit.kt" 'optString("repos_field")' 'optString("repos_key")'
    _red "C9 the client stops reading the declared array key" _c9; }
_stage && _green "c9" _c9 && {
    _sub "$W/GitReposScreen.kt" 'fetchFleetListing(outcome.answeredBy.orEmpty())' 'fetchFleetListing("")'
    _red "C9 the listing goes to the family's first endpoint instead of the rung that answered" _c9; }

# ── C10 #669 the clone rides the leg that listed the repository ──
_stage && _green "c10" c10 "$W/GitReposScreen.kt" && {
    _sub "$W/GitReposScreen.kt" 'else clone(gh.name, gh.cloneUrl, gh.sshUrl)' 'else clone(gh.name)'
    _red "C10 the personal clone re-templated onto the declared owner and host (the wrong leg)" c10 "$W/GitReposScreen.kt"; }
_stage && _green "c10" c10 "$W/GitReposScreen.kt" && {
    _sub "$W/GitReposScreen.kt" 'if (gh.cloneUrl.isBlank()) handoff = ctx.getString(R.string.git_clone_url_missing, gh.name)' \
                                'if (false) handoff = ""'
    _red "C10 a blank listed clone URL goes quiet instead of loud" c10 "$W/GitReposScreen.kt"; }
_stage && _green "c10" c10 "$W/GitReposScreen.kt" && {
    _sub "$W/GitReposScreen.kt" 'fun cloneViaTerminal(name: String, url: String)' 'fun cloneViaTerminal(name: String)'
    _red "C10 the terminal handoff grows its own URL back" c10 "$W/GitReposScreen.kt"; }

# ── C11 #669 the clone rides the session that listed the repository ──
_c11() { c11 "$W/shared.json" "$W/FleetGit.kt" "$W/GitModels.kt" "$W/GitEngine.kt" "$W/GitSyncCoordinator.kt" "$W/GitReposScreen.kt"; }
_stage && _green "c11" _c11 && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["gitea"].pop("clone_url")'
    _red "C11 the edge clone template undeclared — gitea lists and can never clone off-mesh" _c11; }
_stage && _green "c11" _c11 && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["gitea"]["clone_url_order"] = ["listed"]'
    _red "C11 the declared edge dropped from the candidate order" _c11; }
_stage && _green "c11" _c11 && {
    _json "$W/shared.json" 'd["auth"]["git_chain"]["providers"]["gitea"]["clone_url"] = "https://github.com/{owner}/{name}.git"'
    _red "C11 the clone template re-pointed at another host — the wrong leg, declared" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitModels.kt" 'override fun toString(): String = "Session(header=$header, value=<redacted>)"' \
                           'override fun toString(): String = "Session(header=$header, value=$value)"'
    _red "C11 GitAuth.Session prints the cookie" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitEngine.kt" 'is GitAuth.Session -> JSONObject().put("kind", KIND_SESSION).put("header", auth.header).put("value", auth.value)' \
                           'is GitAuth.Session -> JSONObject().put("kind", KIND_NONE)'
    _red "C11 the clone silently drops the session and dials anonymously" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitEngine.kt" '.put("header", auth.header).put("value", auth.value)' \
                           '.put("header", "Authorization").put("value", "Bearer " + auth.value)'
    _red "C11 the session sent as a bearer — measured to collide with gitea's own token auth" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitSyncCoordinator.kt" 'GitAuth.Session(FleetGit.sessionHeader(), FleetSession.cookie)' \
                                    'credentials.authFor(repo)'
    _red "C11 the coordinator stops assembling the session auth (store-only, which cannot hold it)" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitSyncCoordinator.kt" 'val auth = authFor(repo, token)' 'val auth = credentials.authFor(repo)'
    _red "C11 an engine call bypasses the session-aware auth — a session repository dials anonymously" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitSyncCoordinator.kt" 'GitOpResult(false, if (authKind == AUTH_SESSION) FleetGit.explainCloneFailure(why) else why)' \
                                    'GitOpResult(false, why)'
    _red "C11 a failed session clone surfaces as raw transport prose with no next step" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitReposScreen.kt" 'if (wayListing.rungId.isNotBlank()) cloneViaFleet(wayListing.rungId, gh)' \
                                'if (false) Unit'
    _red "C11 a fleet-listed row re-routed off the leg that listed it" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/GitReposScreen.kt" 'handoff = ctx.getString(R.string.git_fleet_clone_no_session)' \
                                'return'
    _red "C11 a clone with no session goes quiet instead of offering the sign-in" _c11; }
_stage && _green "c11" _c11 && {
    _sub "$W/FleetGit.kt" '"the gate answered instead of the fleet git ($why) — this session did not satisfy it; the Authelia sign-in on this page starts a fresh one"' \
                          'why'
    _red "C11 a redirect mid-clone stops naming the gate and the sign-in that fixes it" _c11; }

echo "── $MUTATIONS mutations, $HOLLOW of them hollow or void ──"
[ "$HOLLOW" -eq 0 ] || FAILURES=$((FAILURES + HOLLOW))

echo
if [ "$FAILURES" -eq 0 ]; then
    echo "OK  #646 the declared git-auth chain holds: $MUTATIONS mutations all went red"
    exit 0
fi
echo "FAILED  $FAILURES check(s) — #646's chain is not what it declares"
exit 1
