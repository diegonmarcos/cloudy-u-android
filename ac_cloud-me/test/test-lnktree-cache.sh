#!/usr/bin/env bash
# #1323 Lnktree page cache: the declaration guard. The numbers live in build.json::ui.lnktree_cache and nowhere else;
# the Kotlin parses them (LnktreeCacheConfig) and must not carry a copy. The JVM rules are LnktreeCacheTest / LnktreeCacheDeclTest.
set -euo pipefail
cd "$(dirname "$0")/.."
n=0
bad() { echo "FAIL  $1"; n=$((n + 1)); }
J=build.json
K=app/src/main/java/com/diegonmarcos/cloudme

jq -e '.ui.lnktree_cache | type == "object"' "$J" >/dev/null || { echo "FAIL  build.json::ui.lnktree_cache is not declared"; exit 1; }
jq -e '.ui.lnktree_cache._doc | type == "string" and length > 40' "$J" >/dev/null || bad "ui.lnktree_cache has no _doc"
for k in enabled section ttl_days max_cache_mb holder_max_webviews hard_refresh_scope exclude_pages prefetch; do
  jq -e ".ui.lnktree_cache | has(\"$k\")" "$J" >/dev/null || bad "ui.lnktree_cache.$k is not declared"
done
for k in enabled concurrency network max_pages max_subresources_per_page; do
  jq -e ".ui.lnktree_cache.prefetch | has(\"$k\")" "$J" >/dev/null || bad "ui.lnktree_cache.prefetch.$k is not declared"
done
[ "$(jq -r '.ui.lnktree_cache.ttl_days' "$J")" = "7" ] || bad "ui.lnktree_cache.ttl_days is not 7 (the owner's validity)"
jq -e '.ui.lnktree_cache.hard_refresh_scope | IN("page","all")' "$J" >/dev/null || bad "hard_refresh_scope must be page or all"
jq -e '.ui.lnktree_cache.prefetch.network | IN("unmetered","connected")' "$J" >/dev/null || bad "prefetch.network must be unmetered or connected"
jq -e '.ui.lnktree_cache.prefetch.concurrency | . >= 1 and . <= 8' "$J" >/dev/null || bad "prefetch.concurrency must be 1..8"
jq -e '.ui.lnktree_cache.holder_max_webviews | . >= 1' "$J" >/dev/null || bad "holder_max_webviews must be >= 1"
SEC="$(jq -r '.ui.lnktree_cache.section' "$J")"
jq -e --arg s "$SEC" '[.ui.sections[].id] | index($s)' "$J" >/dev/null || bad "ui.lnktree_cache.section '$SEC' is not a ui.sections id"
for p in $(jq -r '.ui.lnktree_cache.exclude_pages[]?' "$J"); do
  [ -n "$(find data/ui/"$SEC" -name "$p.json" 2>/dev/null)" ] || bad "exclude_pages names '$p', which is not a page of $SEC"
done

# baked and parsed, not retyped
grep -q 'UI_LNKTREE_CACHE_B64' app/build.gradle || bad "app/build.gradle does not bake ui.lnktree_cache"
grep -q 'androidx.work:work-runtime' app/build.gradle || bad "app/build.gradle lacks WorkManager (the prefetch)"
grep -q 'LnktreePrefetchScheduler.schedule' "$K/MainActivity.kt" || bad "MainActivity does not schedule the prefetch at start"
# #1947 the pull-down is a SwipeRefreshLayout around the WebView (Compose pullToRefresh never fired over a WebView), armed on cached AND non-cached pages
grep -q 'SwipeRefreshLayout' "$K/WebPage.kt" || bad "WebPage has no pull-to-refresh (SwipeRefreshLayout)"
grep -q 'LnktreeCache.hardRefresh' "$K/WebPage.kt" || bad "WebPage's pull-down does not run the hard refresh"
grep -q 'swiperefreshlayout' app/build.gradle || bad "app/build.gradle lacks swiperefreshlayout"
jq -e '.ui.lnktree_cache.strategy | IN("cache_ttl","cache_then_live","network_first","stale_while_revalidate")' "$J" >/dev/null || bad "ui.lnktree_cache.strategy must be cache_ttl, cache_then_live, network_first or stale_while_revalidate"
# nothing hard-coded: no TTL, concurrency, size or day literal in the cache Kotlin
if grep -nE '604800|TimeUnit\.DAYS|\b7 \* |\b7L\b|ttlMs = [0-9]|newFixedThreadPool\([0-9]|Semaphore\([0-9]' "$K/LnktreeCache.kt" "$K/LnktreeWeb.kt" | grep -vE '^\S+:\s*[0-9]+:\s*(//|\*)'; then
  bad "a cache number is hard-coded in Kotlin (it belongs in build.json::ui.lnktree_cache)"
fi

[ "$n" -eq 0 ] && echo "PASS  Lnktree cache declaration" || { echo "$n failure(s)"; exit 1; }
