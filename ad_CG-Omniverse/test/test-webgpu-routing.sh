#!/usr/bin/env bash
# test-webgpu-routing: no Chrome / Custom Tab path anywhere; world links go to Cloud-Browser; floor796 is declared pinned offline;
# the Galaxy banner is actionable (Cloud-Browser / Stay in app) and the choice is persisted per site.
set -u
HERE="$(cd "$(dirname "$0")" && pwd)"; ROOT="$(cd "$HERE/../.." && pwd)"
FAILS=0; ok(){ echo "  PASS: $1"; }; bad(){ echo "  FAIL: $1"; FAILS=$((FAILS+1)); }
W="$ROOT/ab_cloud-libs-shared/libs/web-core/src/main"; A="$HERE/../app/src/main"
if grep -rniE 'com\.android\.chrome|openInChrome|CustomTabs|extra\.SESSION' "$W" "$A" >/dev/null; then bad "a Chrome or Custom Tab reference remains"; else ok "no Chrome / Custom Tab reference"; fi
grep -q 'CLOUD_BROWSER_PACKAGE = "com.diegonmarcos.cloudbrowser"' "$W"/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt && ok "links go to Cloud-Browser" || bad "Cloud-Browser package not declared"
grep -q '"pin_offline": \["floor796"\]' "$HERE/../build.json" && ok "floor796 pinned by declaration" || bad "floor796 not declared pinned"
grep -q 'WORLD_GPU_BROWSER' "$A"/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt && grep -q 'setGpuChoice' "$A"/java/com/diegonmarcos/cgomniverse/ui/GalaxyPage.kt && ok "banner actions persist the choice" || bad "banner not actionable"
grep -q 'if (wv.layoutParams == null) wv.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)' "$W"/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt && ok "every web view fills its parent (#1694: no WRAP_CONTENT zero-height layout)" || bad "CloudWeb.configure does not give the web view MATCH_PARENT layout params"
[ "$FAILS" = 0 ] && echo "ALL PASS" || { echo "$FAILS FAIL"; exit 1; }
