#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #886 a tab comes back on the page it LAST committed              ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The bug: the tab list stored the url a tab was OPENED with and nothing rewrote it when the tab
# navigated, so after a restart the tab showed an older page (and leaving for the grid destroyed
# the WebView, back stack and all). The JVM test (BrowserTabStateTest) proves the data rule; this
# tester proves every place the rule has to be CALLED from still calls it:
#   - a committed navigation (doUpdateVisitedHistory, onPageFinished) writes the tab's url by id;
#   - the state (back stack) is saved on pause, on stop, and before a WebView is destroyed;
#   - the tab is reopened from that state, else from its committed url, never the opened-with one;
#   - the address bar navigates the tab IN PLACE (it used to close and re-add it);
#   - nothing is saved for a private tab, and a closed tab's state file goes.
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    prefs = src.get(LIB + "/BrowserTabPrefs.kt", "")
    store = src.get(LIB + "/BrowserTabStore.kt", "")
    tab = src.get(LIB + "/BrowserTab.kt", "")
    ok("override fun doUpdateVisitedHistory" in frag and "onCommitted(tabKey, view, visitedUrl)" in frag,
       "a committed navigation (and an in-page route) commits the tab's url")
    pf = frag[frag.find("override fun onPageFinished"):]
    ok("onCommitted(tabKey, view, u)" in pf[:pf.find("history.record(")], "a finished page commits the tab's url before anything else")
    ok("prefs.commit(tabKey, url, view?.title)" in frag, "onCommitted writes through prefs.commit, keyed by the tab's id")
    ok("override fun onPause" in frag and "saveTabState()" in frag[frag.find("override fun onPause"):frag.find("override fun onPause") + 200],
       "the app pausing saves the tab's state")
    ok("override fun onStop" in frag and "saveTabState()" in frag[frag.find("override fun onStop"):frag.find("override fun onStop") + 60],
       "the app stopping saves the tab's state")
    td = frag[frag.find("private fun teardownWebView"):]
    ok("saveTabState()" in td[:td.find("webView = null")], "a WebView is never destroyed before its state is saved")
    ok("BrowserWebState.save(requireContext(), wv, tab.key)" in frag and "if (!tab.isPrivate) BrowserWebState.save(" in frag,
       "the back stack is saved, and never for a private tab")
    ok("BrowserWebState.restore(ctx, this, tabKey)" in frag and "BrowserSessionSnapshots.plan(stateRestored" in frag and "loadResolved(plan.url)" in frag,
       "a tab reopens from its saved state, else from its committed url")
    ok("val url = tab.url" in frag, "the url loaded is the tab's stored (committed) url")
    ok("if (next.isNotEmpty()) webView?.loadResolved(next)" in frag and "prefs.remove(prior)" not in frag,
       "the address bar navigates the tab in place, not close-and-re-add")
    ok("BrowserWebState.delete(requireContext(), tab.key)" in frag, "closing a tab removes its saved state")
    ok("fun shouldCommit" in store and "if (!shouldCommit(url)) return tabs" in store, "only a real committed page is remembered")
    ok("BrowserTabStore.commit(cur, id, url, title)" in prefs and "if (next != cur) save(next)" in prefs, "prefs.commit persists the store's commit")
    ok("fun activeTab()" in prefs and "BrowserTabStore.active(" in prefs, "the active tab is looked up by id, with the url as the legacy fallback")
    ok("val id: String" in tab and 'put("id",' in store, "a tab has a stable id and it is serialised")
    ok("restore_tabs_on_start" in frag and "prefs.activeTab()" in frag, "reopen-last-tab uses the active tab's committed url")

BJ = "ac_cloud-browser/build.json"
FRAG = LIB + "/BrowserHostFragment.kt"
main("tab state", check, [
    ("a committed navigation stops writing the url", FRAG, "onCommitted(tabKey, view, visitedUrl)", "Unit", "commits the tab's url"),
    ("a finished page stops committing", FRAG, "onCommitted(tabKey, view, u)\n", "\n", "finished page commits"),
    ("pause stops saving the state", FRAG, "saveTabState()   // #886 the app going away", "Unit   // #886 the app going away", "pausing saves"),
    ("stop stops saving the state", FRAG, "override fun onStop() {\n        saveTabState()", "override fun onStop() {\n        Unit", "stopping saves"),
    ("the WebView is destroyed unsaved", FRAG, "private fun teardownWebView() {\n        if (!rendererDead) saveTabState()", "private fun teardownWebView() {\n        Unit", "never destroyed before its state is saved"),
    ("a private tab's back stack is saved", FRAG, "if (!tab.isPrivate) BrowserWebState.save(", "if (true) BrowserWebState.save(", "never for a private tab"),
    ("the saved state is never restored", FRAG, "val stateRestored = !tab.isPrivate && BrowserWebState.restore(ctx, this, tabKey)", "val stateRestored = false", "reopens from its saved state"),
    ("the address bar closes and re-adds the tab", FRAG, "if (next.isNotEmpty()) webView?.loadResolved(next)", "if (next.isNotEmpty()) { prefs.remove(prefs.activeUrl() ?: url); navigateTo(next) }", "in place"),
    ("a closed tab leaves its state behind", FRAG, "BrowserWebState.delete(requireContext(), tab.key)", "Unit", "removes its saved state"),
    ("an about:blank navigation overwrites the url", LIB + "/BrowserTabStore.kt", "if (!shouldCommit(url)) return tabs", "", "only a real committed page"),
    ("prefs.commit stops persisting", LIB + "/BrowserTabPrefs.kt", "if (next != cur) save(next)", "Unit", "persists the store's commit"),
    ("the tab loses its id", LIB + "/BrowserTabStore.kt", 'put("id",      t.key)', 'put("idx",     t.key)', "stable id"),
])
PYEOF
