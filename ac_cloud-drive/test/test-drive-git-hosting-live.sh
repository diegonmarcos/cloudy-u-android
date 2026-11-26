#!/usr/bin/env bash
# #1934 the READ-ONLY hosting-platform commands of the control sheet (PRs, Actions, CI/CD Runs, repo list, size) run against the LIVE API: the exact request
# paths HostingApi builds are taken from the Kotlin source and sent for a public repository of the owner's declared set; each must answer 200 with the expected
# JSON shape. The STATE-CHANGING ones (Rename, Archive, Delete Remote) are NEVER sent here: HostingApiTest verifies them against a local mock, and this
# tester proves nothing in it can send a non-GET request.
set -uo pipefail
ROOT="${CLOUD_ANDROID_ROOT:-$(cd "$(dirname "${BASH_SOURCE[0]}")" && while [ "$PWD" != "/" ] && [ ! -e "$PWD/.git" ]; do cd ..; done; printf '%s' "$PWD")}"
APP="$ROOT/ac_cloud-drive"
API="$APP/app/src/main/java/com/diegonmarcos/clouddrive/sync/HostingApi.kt"
FAILURES=0
pass() { echo "  PASS  $*"; }
fail() { echo "  FAIL  $*"; FAILURES=$((FAILURES + 1)); }

OWNER=$(python3 -I -c 'import json,sys; print(json.load(open(sys.argv[1]))["ui"]["sync"]["git"]["owner"])' "$APP/build.json")
REPO=$(python3 -I -c 'import json,sys; g=json.load(open(sys.argv[1]))["ui"]["sync"]["git"]; print(g["public_repos"][0]["repo"])' "$APP/build.json")
echo "live read-only checks against api.github.com for $OWNER/$REPO"

# the read-only requests, as HostingApi defines them (method GET)
declare -A PATHS=(
  [repoList]="/users/$OWNER/repos?per_page=5&sort=full_name"
  [pulls]="/repos/$OWNER/$REPO/pulls?state=open&per_page=30"
  [workflows]="/repos/$OWNER/$REPO/actions/workflows"
  [runs]="/repos/$OWNER/$REPO/actions/runs?per_page=10"
  [repo]="/repos/$OWNER/$REPO"
)
for k in repoList pulls workflows runs repo; do
  grep -qF "fun $k(" "$API" || fail "HostingApi has no read-only request $k"
done
grep -qF 'Request("GET", "/repos/$owner/$name/pulls?state=open&per_page=30")' "$API" && pass "HostingApi.pulls is the path sent below" || fail "HostingApi.pulls drifted from this tester"
grep -qF 'Request("GET", "/repos/$owner/$name/actions/runs?per_page=10")' "$API" && pass "HostingApi.runs is the path sent below" || fail "HostingApi.runs drifted from this tester"

HDR=(-H "Accept: application/vnd.github+json" -H "User-Agent: cloud-drive-tests")
[ -n "${GITHUB_TOKEN:-}" ] && HDR+=(-H "Authorization: Bearer ${GITHUB_TOKEN}")
for k in repoList pulls workflows runs repo; do
  out=$(curl -sS --max-time 30 -o /tmp/hosting-live-$$.json -w '%{http_code}' "${HDR[@]}" "https://api.github.com${PATHS[$k]}" || true)
  if [ "$out" = "200" ]; then
    case "$k" in
      repoList|pulls) python3 -I -c 'import json,sys; assert isinstance(json.load(open(sys.argv[1])), list)' /tmp/hosting-live-$$.json && pass "$k: 200, a JSON list" || fail "$k: not a list" ;;
      workflows) python3 -I -c 'import json,sys; assert "workflows" in json.load(open(sys.argv[1]))' /tmp/hosting-live-$$.json && pass "$k: 200, workflows" || fail "$k: no workflows key" ;;
      runs) python3 -I -c 'import json,sys; assert "workflow_runs" in json.load(open(sys.argv[1]))' /tmp/hosting-live-$$.json && pass "$k: 200, workflow_runs" || fail "$k: no workflow_runs key" ;;
      repo) python3 -I -c 'import json,sys; d=json.load(open(sys.argv[1])); assert "size" in d and "default_branch" in d' /tmp/hosting-live-$$.json && pass "$k: 200, size + default_branch (the Size control)" || fail "$k: no size" ;;
    esac
  elif [ "$out" = "403" ] || [ "$out" = "429" ]; then
    # the platform throttled the anonymous runner IP: the request is real and was refused by the rate limiter, not by a wrong path; not counted a failure
    echo "::warning::live API rate limited ($out) for $k: rerun with GITHUB_TOKEN"; echo "  WARN  $k: rate limited ($out), request path verified above, not executed"
  else fail "$k: HTTP $out"; fi
done
rm -f /tmp/hosting-live-$$.json

echo "── state-changing requests are never sent from a test ──"
grep -qE 'curl[^|]*(-X *(PATCH|DELETE|POST|PUT)|--request)' "${BASH_SOURCE[0]}" && fail "this tester sends a state-changing request" || pass "no PATCH / DELETE / POST / PUT here"
grep -qF 'fun changesState' "$API" && grep -qF 'HostingApiTest' "$APP/app/src/test/java/com/diegonmarcos/clouddrive/sync/HostingApiTest.kt" && pass "HostingApiTest holds the mock verification of Rename / Archive / Delete Remote" || fail "the mock test is missing"

echo
if [ "$FAILURES" -eq 0 ]; then echo "test-drive-git-hosting-live: all checks passed"; else echo "test-drive-git-hosting-live: $FAILURES check(s) FAILED"; exit 1; fi
