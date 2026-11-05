#!/usr/bin/env bash
# #1495 One declared hierarchy: the app tree (build.json ui.sections), data/c3_hierarchy.json (generated), the declared route and
# tool mapping. The cloudy-c3-api / cloudy-c3-mcp side of the parity is asserted in cloudy-u-containers. Own-source only: python3.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
command -v python3 >/dev/null 2>&1 || { echo "ERROR python3 absent - unrun, not passing"; exit 1; }
if python3 -I "$HERE/test_c3_hierarchy.py" 2>&1; then
    echo "  PASS  H1 hierarchy: app tree = c3_hierarchy.json, no route or tool pattern with two owners, the new categories have a fragment and a catalog"
    exit 0
fi
echo "  FAIL  H1 hierarchy guard"
exit 1
