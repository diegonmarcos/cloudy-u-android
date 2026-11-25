#!/usr/bin/env bash
# vault-checkout-ref.test — every vault checkout names its ref, depth and
# credential policy, so actions/checkout does git over HTTPS only.
#
# WHY. actions/checkout without `ref` calls the REST API to find the default
# branch. The vault token is a personal token, so those calls drain the user's
# 5,000/hour quota, shared by every workflow and agent; Ship runs failed at
# "Checkout vault" with "API rate limit exceeded". An explicit `ref: main`
# (plus fetch-depth: 1, persist-credentials: false) makes it plain git.
#
# The scan reads every workflow source (cicd, templates, actions) and fails on
# any step that checks out a *vault repository without `ref:`, `fetch-depth:`
# and `persist-credentials:`. It is proven against planted good and bad
# fixtures before it reads the tree (the mutation: drop `ref:` -> red).

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
FAILURES=0
ok()   { printf 'ok     %s\n' "$1"; }
fail() { printf 'FAIL   %s\n' "$1"; FAILURES=$((FAILURES + 1)); }

# scan FILE...: prints "file:line: missing X" per offending vault checkout.
scan() {
  awk '
    function flush() {
      if (vault) {
        if (!ref) print FILENAME ":" start ": missing ref"
        if (!depth) print FILENAME ":" start ": missing fetch-depth"
        if (!persist) print FILENAME ":" start ": missing persist-credentials"
      }
      vault = ref = depth = persist = 0
    }
    FNR == 1 { flush() }
    /^ *- (name|uses):/ { flush(); start = FNR }
    /^ *repository: *[^ ]*vault/ { vault = 1 }
    /^ *ref: *[^ ]/ { ref = 1 }
    /^ *fetch-depth: *[0-9]/ { depth = 1 }
    /^ *persist-credentials: *(true|false)/ { persist = 1 }
    END { flush() }
  ' "$@"
}

WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/f"
cat > "$WORK/f/good.yml" <<'Y'
    steps:
      - name: Checkout vault
        uses: actions/checkout@v4
        with:
          repository: diegonmarcos/cloud-me-pvt-vault
          token: ${{ secrets.T }}
          path: .vault
          ref: main
          fetch-depth: 1
          persist-credentials: false
Y
grep -v 'ref: main' "$WORK/f/good.yml" > "$WORK/f/noref.yml"
[ -z "$(scan "$WORK/f/good.yml")" ] && ok "fixture: complete vault checkout passes" || fail "fixture: complete vault checkout flagged"
scan "$WORK/f/noref.yml" | grep -q 'missing ref' && ok "fixture: vault checkout without ref is red" || fail "fixture: missing ref NOT detected"

mapfile -t SRCS < <(grep -rl 'repository: .*vault' "$ROOT/1_cicd/src/cicd" "$ROOT/1_cicd/src/templates" "$ROOT/1_cicd/src/actions" 2>/dev/null)
[ "${#SRCS[@]}" -gt 0 ] && ok "scan sees ${#SRCS[@]} sources with a vault checkout" || fail "scan found no vault checkout sources (scan blind)"
bad="$(scan "${SRCS[@]}")"
[ -z "$bad" ] && ok "every vault checkout names ref, fetch-depth, persist-credentials" || { fail "vault checkouts incomplete"; printf '%s\n' "$bad"; }

[ "$FAILURES" -eq 0 ] && { echo "vault-checkout-ref: all green"; exit 0; }
echo "vault-checkout-ref: $FAILURES failure(s)"; exit 1
