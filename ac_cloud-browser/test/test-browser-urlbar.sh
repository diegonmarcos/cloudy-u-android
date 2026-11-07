#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #886 the address bar's suggestions lay out, and are two sections ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The bug: the dropdown was an AutoCompleteTextView's PopupWindow, anchored to the field and sized by the
# platform, so it could land on top of the bar and under the keyboard, and could not be dismissed except by
# typing. The rebuild, certified here:
#   U1 no PopupWindow dropdown anywhere in the browser (AutoCompleteTextView, ListPopupWindow, showDropDown);
#   U2 the panel is a Compose child of the frame under the bar (so it cannot cover it), its height capped
#      by that frame (so it stays above the keyboard, which resizes the window: adjustResize, and the island
#      steps aside), it scrolls, and a scrim tap, back and Go all dismiss it;
#   U3 three LABELLED sections in order: Web search (the default engine's search row + its suggestions), History
#      (ranked), Favorites (the matching Fav entries), each capped;
#   U4 the engine's suggestion feed is its own file, gated by the `search_suggestions` setting, https only,
#      never from a private tab, and BrowserSuggest / BrowserHistory still contain no network client.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys, os, re
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, build_json, main

NET = ("HttpURLConnection", "OkHttp", "java.net.URL(", "Retrofit", "openConnection", "Socket(")

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    panel = src.get(LIB + "/BrowserSuggestPanel.kt", "")
    sug = src.get(LIB + "/BrowserSuggest.kt", "")
    rem = src.get(LIB + "/BrowserRemoteSuggest.kt", "")
    allsrc = "\n".join(src.values())
    for bad in ("AutoCompleteTextView", "ListPopupWindow", "showDropDown", "setDropDownBackgroundDrawable"):
        ok(bad not in allsrc, "U1 no %s in the browser or the app" % bad)
    ok("PopupWindow(" not in allsrc, "U1 no PopupWindow")
    ok("BrowserSuggestOverlay(suggestState" in frag and "AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webView) }" in frag, "U2 the panel is a child of the frame under the bar")
    ok(0 <= frag.find("AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webView) }") < frag.find("BrowserSuggestOverlay(suggestState"), "U2 the panel sits OVER the page, in the page frame")
    ok("BoxWithConstraints" in panel and "heightIn(max = maxHeight)" in panel, "U2 its height is capped by the frame, so it ends above the keyboard")
    ok("LazyColumn(modifier)" in panel, "U2 a long list scrolls")
    ok("clickable(onClick = onDismiss)" in panel, "U2 a tap on the dimmed page dismisses it")
    ok("suggestState.visible -> dismissSuggestions()" in frag, "U2 back dismisses it first")
    ok("dismissSuggestions()" in frag[frag.find("onSubmit = { q ->\n"):frag.find("onSubmit = { q ->\n") + 300], "U2 Go dismisses it")
    addr = src.get(LIB + "/BrowserAddressOverlay.kt", "")
    ok("BrowserAddressOverlay(" in frag and "addressOpen = true" in frag, "U5 a tap on the address opens the Brave-style overlay")
    ok('Text("Copy")' in addr and 'Text("Edit")' in addr and "clipboard.setText(AnnotatedString(url))" in addr, "U5 the current-address card has Copy and Edit")
    ok("TextRange(0, url.length)" in addr, "U5 Edit puts the whole URL in the search box, selected")
    manifest = open(os.path.join(root, "ac_cloud-browser/app/src/main/AndroidManifest.xml"), encoding="utf-8").read()
    ok('android:windowSoftInputMode="adjustResize"' in manifest, "U2 the window resizes with the keyboard")
    mainact = src.get(APP + "/MainActivity.kt", "")
    ok("WindowInsets.ime" in mainact and "if (!imeVisible) Island()" in mainact, "U2 the island steps aside while the keyboard is up")
    ok("Title(searchTitle(engineLabel))" in panel and 'Title("History")' in panel and 'Title("Favorites")' in panel, "U3 three titled sections: Web search, History, Favorites")
    ok(panel.find("Title(searchTitle") < panel.find('Title("History")') < panel.find('Title("Favorites")'), "U3 they come in that order")
    ok("Web search" in panel and "searchLimit: Int = SEARCH_LIMIT" in sug and "historyLimit: Int = HISTORY_LIMIT" in sug and "favLimit: Int = FAV_LIMIT" in sug, "U3 each section is capped")
    ok("fun sections(" in sug and "Search ${engine.label} for" in sug, "U3 the first section opens with the engine's own search row")
    ok("fun matchScore(" in sug and "fun recency(" in sug and "sortedByDescending { it.first }" in sug, "U3 history is ranked by match, then recency")
    ok("v.title" in sug and "subtitle" in sug, "U3 history rows carry title and url")
    ok('browserSettings.bool("search_suggestions") == true' in frag, "U4 the feed is asked only when the setting is on")
    ok("BrowserRemoteSuggest.fetch(e.suggest, q)" in frag, "U4 it asks the engine's own declared feed")
    ok("startsWith(\"https://\")" in rem and "!privateTab" in rem and "!BrowserSearch.isUrlLike(query)" in rem, "U4 https only, never a private tab, never a URL")
    ok(any(n in rem for n in NET), "U4 the network client lives in BrowserRemoteSuggest")
    for f in ("BrowserSuggest", "BrowserHistory"):
        ok(not any(n in src.get(LIB + "/%s.kt" % f, "") for n in NET), "U4 %s still has no network client" % f)
    others = [p for p, t in src.items() if any(n in t for n in ("HttpURLConnection",)) and "BrowserRemoteSuggest" not in p
              and "ScrapeRemote" not in p and "search" not in p.lower() and "debugapi" not in p and "Offline" not in p and "Updater" not in p and "AuthMission" not in p
              and "agentapi/AgentFetchProvider" not in p  # #913 the fleet agent door's read-only GET, held by test-browser-agent-door.sh
              and "/download/DlHttpEngine.kt" not in p and "/download/DlQueue.kt" not in p
              and "/FavFeed.kt" not in p
              and "/sites/SitesAndroid.kt" not in p]  # #1493 Cloudy + #1531 Weby icons: GETs of public pages and icons, no user data, cached; the same one GET of the public pages.json (front-html), cached; no user data  # #1458 one GET of the public pages.json (front-html), no user data, held by test-browser-pages-hub.sh  # the download manager fetches only what the user chose to download (a file, a .torrent)
    ok(not others, "U4 no other browser file opens a connection", str(others))
    s = [x for x in build_json(root)["ui"]["browser"]["settings"] if x["key"] == "search_suggestions"]
    ok(s and s[0]["type"] == "bool" and "sent to it" in s[0]["doc"], "U4 the setting says in words that what you type goes to the engine")
    ddg = [e for e in build_json(root)["ui"]["browser"]["search_engines"] if e["id"] == "duckduckgo"][0]
    ok(ddg.get("suggest", "").startswith("https://duckduckgo.com/"), "U4 DuckDuckGo declares its suggestion feed")

FRAG = LIB + "/BrowserHostFragment.kt"
PANEL = LIB + "/BrowserSuggestPanel.kt"
main("url bar", check, [
    ("the popup dropdown comes back", FRAG, "private val suggestState = SuggestState()", "private val suggestState = SuggestState()\n    private val rogue = android.widget.AutoCompleteTextView(null)", "no AutoCompleteTextView"),
    ("the panel leaves the page frame", FRAG, "AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webView) }", "AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webViewX) }", "child of the frame"),
    ("the panel grows past the frame", PANEL, "heightIn(max = maxHeight)", "heightIn(max = 4000.dp)", "capped by the frame"),
    ("the list stops scrolling", PANEL, "LazyColumn(modifier)", "Column(modifier)", "scrolls"),
    ("the scrim stops dismissing", PANEL, "clickable(onClick = onDismiss)", "clickable(onClick = {})", "dimmed page dismisses"),
    ("back leaves the panel up", FRAG, "suggestState.visible -> dismissSuggestions()", "false -> dismissSuggestions()", "back dismisses"),
    ("the window stops resizing", "ac_cloud-browser/app/src/main/AndroidManifest.xml", 'android:windowSoftInputMode="adjustResize"', "", "resizes with the keyboard"),
    ("the island covers the keyboard room", APP + "/MainActivity.kt", "if (!imeVisible) Island()", "Island()", "steps aside"),
    ("the History title goes", PANEL, 'add(Title("History"))', 'add(Title(""))', "three titled sections"),
    ("the Favorites section goes", PANEL, 'add(Title("Favorites"))', 'add(Title(""))', "three titled sections"),
    ("Favorites jumps ahead of History", PANEL, 'if (sections.history.isNotEmpty()) { add(Title("History"))', 'if (sections.favourites.isNotEmpty()) { add(Title("Favorites")); sections.favourites.forEach { add(Item(it)) } }\n            if (sections.history.isNotEmpty()) { add(Title("History"))', "come in that order"),
    ("the fav cap goes", LIB + "/BrowserSuggest.kt", "favLimit: Int = FAV_LIMIT", "favLimit: Int = 500", "each section is capped"),
    ("history is no longer ranked", LIB + "/BrowserSuggest.kt", "sortedByDescending { it.first }", "map { it }", "ranked by match"),
    ("the feed ignores the setting", FRAG, 'browserSettings.bool("search_suggestions") == true', "true", "only when the setting is on"),
    ("the feed allows plain http", LIB + "/BrowserRemoteSuggest.kt", 'it.startsWith("https://")', 'it.startsWith("http")', "https only"),
    ("a private tab is asked about", LIB + "/BrowserRemoteSuggest.kt", "enabled && !privateTab &&", "enabled &&", "never a private tab"),
    ("a socket creeps into suggestions", LIB + "/BrowserSuggest.kt", "object BrowserSuggest {", "object BrowserSuggest {\n    private fun leak() = java.net.URL(\"https://x\").openConnection()", "still has no network client"),
])
PYEOF
