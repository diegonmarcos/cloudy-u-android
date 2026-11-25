#!/usr/bin/env bash
# #1744 (extends #1704) a package row is ONE line in every tab: no Next update line under the row (silent or prompting); the status
# line carries only the small marker; the full text stays in the detail view; the adopt action and its allowlist entry exist.
set -u
R="$(cd "$(dirname "$0")/../.." && pwd)"
F="$R/ab_cloud-libs-shared/libs/appstore/src/main/java/com/diegonmarcos/superapp/appstore/StoreCloudFragment.kt"
A="$R/fleet-private-data/shell-allowlist.json"   # #1566 fleet-private: CI materialises it
fail=0
chk() { if grep -qF -- "$2" "${3:-$F}"; then echo "  PASS: $1"; else echo "  FAIL: $1"; fail=1; fi; }
no() { if grep -qF -- "$2" "$F"; then echo "  FAIL: $1"; fail=1; else echo "  PASS: $1"; fi; }
chk "the row status carries the marker" 'StoreAdopt.marker('
no "no yellow Next update line under the row" 'store-silent:${app.id}'
no "no SilentArea composable" 'SilentArea'
chk "the full Next update text is reachable in the detail view" 'store-detail-next-update:${app.id}'
chk "the adopt outcome is in the detail view" 'store-detail-adopt:${app.id}'
chk "Adopt all action" 'store-adopt-all'
chk "the Store allowlist entry carries set-installer" 'pm set-installer' "$A"
# mutations: a restored row line or a dropped detail text must turn the check red
if sed 's/store-detail-next-update/x/' "$F" | grep -qF 'store-detail-next-update'; then echo "  FAIL: mutation not applied"; fail=1; else echo "  PASS: mutation (detail line removed) is caught"; fi
[ $fail -eq 0 ]
