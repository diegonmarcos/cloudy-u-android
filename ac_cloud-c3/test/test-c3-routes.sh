#!/usr/bin/env bash
# Containers > Table data: the generator's auth-type mapping (edge auth tiers), the VM address column and the shape
# of the committed data/infra_slice.json routes. Own-source only: python3.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent - unrun, not passing"; exit 1; }
if python3 -I "$HERE/test_c3_routes.py" 2>&1 && python3 -I "$HERE/test_repos_slice.py" 2>&1; then
    echo "  PASS  T1 c3_routes: auth-type mapping, VM address and the shipped routes' shape, the generated repos slice"
    exit 0
fi
echo "  FAIL  T1 c3_routes unit tests"
exit 1
