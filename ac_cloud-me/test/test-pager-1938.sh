#!/usr/bin/env bash
# Tester (#1938): the section pager and the dark-mode WebView, on the sources, each rule proven red by a mutation of a copy.
#   Q1 nav-first  the strips (NavBarTopSecondRow / NavBarTopSecondRows) are drawn from the declaration and never wait on the prefetcher
#   Q2 queue      one in flight, visible page first, nav order, a failure marks the page and goes on, the retained bound releases the least recently viewed; one implementation (SectionScreen) for every section
#   Q3 declared   ui.lnktree_cache.queue declares enabled + max_retained_pages (a count)
#   D1 dark       every embedded WebView gets WebDarkMode.apply (algorithmic darkening, the site's own scheme preferred), no embedded page keeps dark text on the dark background
set -u
APP="$(cd "$(dirname "$0")/.." && pwd)"
PASS=0; FAIL=0
ok()  { PASS=$((PASS+1)); echo "  PASS: $1"; }
bad() { FAIL=$((FAIL+1)); echo "  FAIL: $1"; }
check() { python3 - "$1" <<'PY'
import json, os, re, sys
app = sys.argv[1]
T = lambda p: open(os.path.join(app, p), encoding="utf-8").read() if os.path.exists(os.path.join(app, p)) else ""
K = "app/src/main/java/com/diegonmarcos/cloudme/"
sec, q, dm, wp = T(K + "SectionScreen.kt"), T(K + "PageFetchQueue.kt"), T(K + "WebDarkMode.kt"), T(K + "WebPage.kt")
body = sec[sec.index("levels.forEachIndexed"):sec.index("PageContent(section, page")] if "levels.forEachIndexed" in sec and "PageContent(section, page" in sec else ""
if not body: print("Q1\tSectionScreen no longer draws strips then PageContent")
if re.search(r"prefetcher|queueVersion|PageFetchQueue", body.split("PageContent(section, page")[0]): print("Q1\tthe strips wait on (or read) the prefetcher")
if "NavBarTopSecondRow(" not in body or "NavBarTopSecondRows(" not in body: print("Q1\tSectionScreen does not draw both strip kinds")
if re.search(r"LaunchedEffect\([^)]*\)\s*\{\s*prefetcher\?\.run\(\)", sec) is None: print("Q2\tthe prefetcher is not run in the background (LaunchedEffect)")
if "prefetcher?.show(page.id)" not in sec: print("Q2\tthe visible page is not handed to the queue")
if "PageFetchErrorStrip" not in sec or "me:page:fetch-retry" not in sec: print("Q2\ta failed page shows no error with Retry")
if "if (inFlight != null) return null" not in q: print("Q2\tthe queue can run two fetches at once")
if "state[v] in setOf(State.IDLE, State.RELEASED)" not in q or "order.firstOrNull { state[it] == State.IDLE }" not in q: print("Q2\tthe queue is not visible-first then nav order")
if "fun fail(id: String, message: String) { state[id] = State.FAILED" not in q or "inFlight = null" not in q.split("fun fail")[1].split("\n")[0]: print("Q2\ta failure holds the queue")
if "minByOrNull { views[it] ?: 0L }" not in q or "it != visible" not in q: print("Q2\tthe release is not least-recently-viewed-first, or can drop the visible page")
if "queue = PageFetchQueue" in sec and sec.count("PagePrefetcher(") != 1: print("Q2\tmore than one pager implementation")
bj = json.load(open(os.path.join(app, "build.json"), encoding="utf-8"))["ui"]["lnktree_cache"].get("queue", {})
if bj.get("enabled") is not True or not isinstance(bj.get("max_retained_pages"), int) or bj["max_retained_pages"] < 1: print("Q3\tui.lnktree_cache.queue is not declared (enabled, max_retained_pages)")
if "WebDarkMode.apply(settings)" not in wp: print("D1\tWebPage does not apply the dark configuration to its WebView")
if "setAlgorithmicDarkeningAllowed(settings, true)" not in dm or "PREFER_WEB_THEME_OVER_USER_AGENT_DARKENING" not in dm: print("D1\tWebDarkMode does not enable algorithmic darkening and prefer the site's own scheme")
if wp.count("CloudWeb.create(") != 1: print("D1\tan embedded WebView is created outside WebPage.make")
PY
}
out=$(check "$APP"); if [ -z "$out" ]; then ok "Q1 Q2 Q3 D1 the pager and the dark-mode configuration hold"; else bad "$out"; fi
T=$(mktemp -d); trap 'rm -rf "$T"' EXIT
mut() { rm -rf "$T/m"; mkdir -p "$T/m/app/src/main/java/com/diegonmarcos/cloudme"; cp "$APP/build.json" "$T/m/"; cp "$APP/app/src/main/java/com/diegonmarcos/cloudme/"{SectionScreen,PageFetchQueue,WebDarkMode,WebPage}.kt "$T/m/app/src/main/java/com/diegonmarcos/cloudme/"
  python3 -c "$2" "$T/m"; r=$(check "$T/m"); if echo "$r" | grep -q "^$3"; then ok "mutation red: $1"; else bad "mutation stayed green: $1"; fi; }
SUB='import sys;d=sys.argv[1]+"/app/src/main/java/com/diegonmarcos/cloudme/";f=d+"%s";s=open(f).read();assert "%s" in s;open(f,"w").write(s.replace("%s","%s",1))'
sub() { printf "$SUB" "$1" "$2" "$2" "$3"; }
mut "the nav waits on the prefetcher" "$(sub SectionScreen.kt 'if (level >= from && siblings.size > 1) {' 'if (prefetcher?.queue?.stateOf(page.id) != PageFetchQueue.State.LOADING \&\& level >= from \&\& siblings.size > 1) {')" Q1
mut "the prefetcher is never run" "$(sub SectionScreen.kt 'prefetcher?.run() }' 'Unit }')" Q2
mut "two fetches may run at once" "$(sub PageFetchQueue.kt 'if (inFlight != null) return null' '')" Q2
mut "the queue ignores the visible page" "$(sub PageFetchQueue.kt 'state[v] in setOf(State.IDLE, State.RELEASED)' 'false')" Q2
mut "a failure holds the queue" "$(sub PageFetchQueue.kt 'errors[id] = message; if (inFlight == id) inFlight = null' 'errors[id] = message')" Q2
mut "the most recently viewed is released" "$(sub PageFetchQueue.kt 'minByOrNull' 'maxByOrNull')" Q2
mut "the bound is not declared" 'import json,sys;p=sys.argv[1]+"/build.json";d=json.load(open(p));d["ui"]["lnktree_cache"].pop("queue");json.dump(d,open(p,"w"))' Q3
mut "an embedded page loses the dark configuration" "$(sub WebPage.kt 'WebDarkMode.apply(settings)' '')" D1
mut "darkening is no longer enabled" "$(sub WebDarkMode.kt 'setAlgorithmicDarkeningAllowed(settings, true)' 'setAlgorithmicDarkeningAllowed(settings, false)')" D1
mut "a second WebView is created without it" "$(sub WebPage.kt 'val make = {' 'val x = CloudWeb.create(ctx, WebProfile.APP_PAGE); val make = {')" D1
echo "$PASS passed, $FAIL failed"; [ "$FAIL" -eq 0 ]
