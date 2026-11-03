#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #893 Google sign-in finishes in Cloud Browser                    ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Root causes (BrowserSignInTest proves the rules; this proves they are CALLED):
#   S1 ERR_CACHE_MISS on accounts.google.com/gsi/transform: a POST result was committed as the tab's url and
#      saved in its WebView state, then replayed from the cache. The store refuses it (shouldCommit) and a
#      restored state whose current page is one is refused (BrowserWebState.restore) -> the tab loads its url.
#   S2 #1420 the user agent is never disguised (no `; wv` stripping); Google OAuth goes to a Custom Tab
#      instead (test-browser-oauth-customtab.sh).
#   S3 third-party cookies default ON (WebView's own default is off) and are off only when the user blocks them.
#   S4 child windows: setSupportMultipleWindows + javaScriptCanOpenWindowsAutomatically + onCreateWindow with a
#      WebViewTransport and onCloseWindow; the popup gets the same UA / cookie rules.
#   S5 shouldOverrideUrlLoading asks BrowserNavPolicy.decide (web pages load, app links never leave).
set -eu
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
cd "$(dirname "$0")"
python3 - <<'PYEOF'
import os, sys
sys.path.insert(0, ".")
from browser_tester import LIB, APP, kotlin, main

def check(root, ok):
    src = kotlin(root, LIB, APP)
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    store = src.get(LIB + "/BrowserTabStore.kt", "")
    ws = src.get(LIB + "/BrowserWebState.kt", "")
    pol = src.get(LIB + "/BrowserNavPolicy.kt", "")
    ok("BrowserNavPolicy.isFormPostResult(u)) return false" in store, "S1 shouldCommit refuses a POST result page")
    ok("!BrowserNavPolicy.isFormPostResult(list.currentItem?.url)" in ws, "S1 a restored state sitting on a POST result is refused")
    ok('path.startsWith("/gsi/")' in pol and 'host == "accounts.google.com"' in pol, "S1 the policy knows Google's gsi endpoints")
    ok("if (!BrowserNavPolicy.isFormPostResult(it.url)) it.reload()" in frag, "S1 a settings change does not reload a POST result")
    dev = src.get(LIB + "/BrowserDevTools.kt", "")
    ok('ViewModePlan.of(desktopMode, config.userAgents["desktop"], config.userAgents["mobile"]' in frag and "val ua = (if (desktop) desktopUa else mobileUa) ?: current" in dev, "S2 both modes use the declared agent as it is")
    ok("cleanUserAgent" not in dev and "fun cleanUserAgent" not in pol, "S2 the agent is never disguised")
    ok('setAcceptThirdPartyCookies(wv, browserSettings.bool("block_third_party_cookies") != true)' in frag, "S3 third-party cookies are on unless blocked")
    ok("cookiesOf(this).setAcceptCookie(true)" in frag, "S3 cookies are accepted")
    cw = open(os.path.join(root, "ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt")).read()
    ok(frag.count("CloudWeb.create(ctx, WebProfile.BROWSER)") >= 2 and "setSupportMultipleWindows(profile == WebProfile.BROWSER)" in cw and "javaScriptCanOpenWindowsAutomatically = profile == WebProfile.BROWSER" in cw, "S4 the page may open child windows (#1371 the BROWSER profile)")
    ok(frag.count("override fun onCreateWindow(") >= 2 and "WebView.WebViewTransport" in frag and "transport.webView = child" in frag and "msg.sendToTarget()" in frag,
       "S4 onCreateWindow hands a child WebView to the transport (opener kept)")
    ok("override fun onCloseWindow" in frag, "S4 window.close() closes the child")
    ok("applyViewMode(child)" in frag and "setAcceptThirdPartyCookies(child" in frag, "S4 the popup gets the same agent and cookie rules")
    ok("BrowserNavPolicy.decide(target)" in frag and "BrowserNavPolicy.Decision.Block -> return true" in frag, "S5 app links never leave the browser")
    ok('low.startsWith("http://") || low.startsWith("https://")' in pol and "Decision.Load" in pol, "S5 web pages load as they are")

FRAG = LIB + "/BrowserHostFragment.kt"
main("sign-in", check, [
    ("a POST result is committed again", LIB + "/BrowserTabStore.kt", "if (BrowserNavPolicy.isFormPostResult(u)) return false", "", "refuses a POST result"),
    ("a POST state is restored again", LIB + "/BrowserWebState.kt", "!BrowserNavPolicy.isFormPostResult(list.currentItem?.url)", "true", "refused"),
    ("gsi leaves the policy", LIB + "/BrowserNavPolicy.kt", 'path.startsWith("/gsi/")', 'path.startsWith("/gsx/")', "gsi endpoints"),
    ("the agent is disguised again", LIB + "/BrowserDevTools.kt", "val ua = (if (desktop) desktopUa else mobileUa) ?: current", "val ua = BrowserNavPolicy.cleanUserAgent((if (desktop) desktopUa else mobileUa) ?: current)", "never disguised"),
    ("third-party cookies go off again", FRAG, '!= true)\n        applyViewMode', '== true)\n        applyViewMode', "third-party cookies are on"),
    ("child windows are off", "ab_cloud-libs-shared/libs/web-core/src/main/java/com/diegonmarcos/cloudlib/web/CloudWeb.kt", "setSupportMultipleWindows(profile == WebProfile.BROWSER)", "setSupportMultipleWindows(false)", "child windows"),
    ("onCreateWindow is gone", FRAG, "override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {\n                    val transport", "override fun onCreateWindowX(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {\n                    val transport", "onCreateWindow hands"),
    ("the transport is never sent", FRAG, "msg.sendToTarget()", "Unit", "opener kept"),
    ("an intent: link leaves the browser", FRAG, "BrowserNavPolicy.Decision.Block -> return true", "BrowserNavPolicy.Decision.Block -> Unit", "never leave"),
    ("a POST result is reloaded", FRAG, "if (!BrowserNavPolicy.isFormPostResult(it.url)) it.reload()", "it.reload()", "does not reload"),
])
PYEOF
