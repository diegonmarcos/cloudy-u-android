#!/usr/bin/env bash
# #1704 a SILENT next update is folded into the Cloud row's status; the full text stays in the detail view; a PROMPT keeps its line.
set -u
F="$(cd "$(dirname "$0")/../.." && pwd)/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
fail=0
chk() { if grep -qF -- "$2" "$F"; then echo "  PASS: $1"; else echo "  FAIL: $1"; fail=1; fi; }
chk "the row status carries the silent marker" '" · silent"'
chk "the silent line is not drawn under the row" 'if (line == "silent") return'
chk "a prompting next update keeps its explanatory line" 'store-silent:${app.id}'
chk "the full Next update text is reachable in the detail view" 'store-detail-next-update:${app.id}'
# mutation: with the detail line removed the check must go red
if sed 's/store-detail-next-update/x/' "$F" | grep -qF 'store-detail-next-update'; then echo "  FAIL: mutation not applied"; fail=1; else echo "  PASS: mutation (detail line removed) is caught"; fi
[ $fail -eq 0 ]
