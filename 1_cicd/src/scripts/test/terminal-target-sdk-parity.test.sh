#!/usr/bin/env bash
# #1708 terminal-target-sdk-parity.py still fails when it should: a lost check, a vanished check, an
# unmeasured baseline, a stale known difference and a grown list each turn it red; an equal run and a
# declared, real difference stay green. No device, no network.
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PY="$DIR/../terminal-target-sdk-parity.py"
T="$(mktemp -d)"; trap 'rm -rf "$T"' EXIT
pass=0; fail=0
ok()  { printf '  [PASS] %s\n' "$1"; pass=$((pass+1)); }
bad() { printf '  [FAIL] %s\n' "$1"; fail=$((fail+1)); }
rows() { printf '%s\t%s\t%s\n' "$@"; }
expect() {  # $1 = want exit, $2 = what, rest = arguments
  want="$1"; what="$2"; shift 2
  set +e; python3 "$PY" "$@" > "$T/out" 2>&1; got=$?; set -e
  if [ "$got" = "$want" ]; then ok "$what"; else bad "$what (exit $got, wanted $want): $(tail -3 "$T/out" | tr '\n' ' ')"; fi
}
{ rows meta.target_sdk ok targetSdk=28; rows native.bash ok x; rows guest.git ok x; rows guest.zsh skip x; rows net.apt FAIL x; } > "$T/base.tsv"
{ rows meta.target_sdk ok targetSdk=35; rows native.bash ok x; rows guest.git ok x; rows guest.zsh skip x; rows net.apt FAIL x; rows guest.new ok x; } > "$T/same.tsv"
{ rows meta.target_sdk ok targetSdk=35; rows native.bash FAIL denied; rows guest.git ok x; rows guest.zsh skip x; rows net.apt FAIL x; } > "$T/lost.tsv"
{ rows meta.target_sdk ok targetSdk=35; rows native.bash ok x; rows guest.zsh skip x; rows net.apt FAIL x; } > "$T/missing.tsv"
{ rows meta.target_sdk FAIL targetSdk=28; } > "$T/empty.tsv"
echo '{"max": 0, "known_differences": {"termux": {}, "nix": {}}}' > "$T/none.json"
echo '{"max": 1, "known_differences": {"termux": {"native.bash": "reason"}, "nix": {}}}' > "$T/known.json"
echo '{"max": 0, "known_differences": {"termux": {"native.bash": "reason"}, "nix": {}}}' > "$T/grown.json"
expect 0 "an equal run, with a new check added, holds"                        termux "$T/base.tsv" "$T/same.tsv" "$T/none.json"
expect 1 "a check that was ok and now fails is red"                          termux "$T/base.tsv" "$T/lost.tsv" "$T/none.json"
expect 1 "a check that vanished from the new run is red"                     termux "$T/base.tsv" "$T/missing.tsv" "$T/none.json"
expect 1 "a baseline that measured nothing is red"                           termux "$T/empty.tsv" "$T/same.tsv" "$T/none.json"
expect 0 "a declared difference that really fails holds"                     termux "$T/base.tsv" "$T/lost.tsv" "$T/known.json"
expect 1 "a declared difference that passes again is stale and red"          termux "$T/base.tsv" "$T/same.tsv" "$T/known.json"
expect 1 "more known differences than max is red"                            termux "$T/base.tsv" "$T/lost.tsv" "$T/grown.json"
expect 1 "another app's declared difference does not cover this one"         nix "$T/base.tsv" "$T/lost.tsv" "$T/known.json"
expect 0 "the committed policy parses and holds on an equal run"             termux "$T/base.tsv" "$T/same.tsv" "$DIR/../../data/terminal-target-sdk-parity.json"
echo "terminal-target-sdk-parity.test: $pass passed, $fail failed"
[ "$fail" = 0 ]
