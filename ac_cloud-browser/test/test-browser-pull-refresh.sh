#!/bin/sh
# Pull-down-to-refresh: the page is wrapped in a SwipeRefreshLayout that only claims a drag at the top of the page,
# reloads on refresh, and stops its spinner when the load commits, finishes or fails.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
F="$ROOT/ab_cloud-libs-shared/libs/browser/src/main/java/com/diegonmarcos/superapp/browser/BrowserHostFragment.kt"
G="$ROOT/ab_cloud-libs-shared/libs/browser/build.gradle"
fail=0
need() { grep -qF -- "$2" "$1" || { echo "FAIL: $3"; fail=1; }; }
need "$F" "SwipeRefreshLayout(ctx)" "page wrapped in SwipeRefreshLayout"
need "$F" "sr.addView(wv" "the WebView is the refresh layout's child"
need "$F" "pullRefreshAround(ctx, sc.webView)" "AndroidView hosts the wrapped WebView"
need "$F" "setOnChildScrollUpCallback { _, _ -> wv.scrollY > 0 || wv.canScrollVertically(-1) }" "top-of-page gate"
need "$F" "sr.setOnRefreshListener" "refresh listener"
need "$F" "wv.reload()" "refresh reloads the page"
need "$F" "override fun onPageCommitVisible" "spinner stops on commit"
need "$F" "override fun onReceivedError" "spinner stops on error"
need "$F" "pullRefresh?.isRefreshing = false" "spinner stop call"
need "$G" "androidx.swiperefreshlayout:swiperefreshlayout" "dependency declared"
[ "$fail" = 0 ] && echo "pull-refresh: ok"
exit "$fail"
