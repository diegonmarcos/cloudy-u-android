#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ #1419 Google OAuth goes to a Custom Tab, never the WebView       ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# Google refuses sign-in in embedded WebViews (disallowed_useragent). The compliant fix: accounts.google.com is
# handed to a Custom Tab (the system browser), the tab reloads on return, the agent is never disguised.
#   O1 the policy routes https accounts.google.com (and subdomains, never look-alikes) to Decision.CustomTab
#   O2 the tab's shouldOverrideUrlLoading AND the popup's both launch the Custom Tab for it
#   O3 the hand-off uses the Custom Tabs launch contract (PasskeySupport.secureBrowserIntent, session extra)
#   O4 coming back reloads the tab (onResume)
#   O5 BrowserSignInTest pins the routing as behaviour
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
    pol = src.get(LIB + "/BrowserNavPolicy.kt", "")
    frag = src.get(LIB + "/BrowserHostFragment.kt", "")
    pk = src.get(LIB + "/PasskeySupport.kt", "")
    ok('if (isGoogleOAuth(u)) return Decision.CustomTab(u)' in pol, "O1 decide routes Google OAuth to a Custom Tab")
    ok('oauthHost == "accounts.google.com" || oauthHost.endsWith(".accounts.google.com")' in pol and 'u.startsWith("https://")' in pol, "O1 only https accounts.google.com and its subdomains")
    ok("is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); return true }" in frag, "O2 the tab hands OAuth to the Custom Tab")
    ok("is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); dialog.dismiss(); true }" in frag, "O2 the popup hands OAuth to the Custom Tab")
    ok("oauthHandedOff = true\n        runCatching { startActivity(PasskeySupport.secureBrowserIntent(launch)) }" in frag and 'EXTRA_CUSTOM_TAB_SESSION = "android.support.customtabs.extra.SESSION"' in pk, "O3 the Custom Tabs launch contract")
    ok("if (oauthHandedOff) { oauthHandedOff = false; webView?.reload() }" in frag and "oauthHandedOff = true" in frag, "O4 the tab reloads on return")
    t = open(os.path.join(root, "ac_cloud-browser/app/src/test/java/com/diegonmarcos/cloudbrowser/BrowserSignInTest.kt")).read()
    ok("Google OAuth hosts go to a Custom Tab" in t and "accounts.google.com.evil.example" in t, "O5 the routing is pinned as behaviour")

POL = LIB + "/BrowserNavPolicy.kt"
FRAG = LIB + "/BrowserHostFragment.kt"
main("oauth-customtab", check, [
    ("OAuth loads in the WebView", POL, "if (isGoogleOAuth(u)) return Decision.CustomTab(u)", "", "O1 decide"),
    ("look-alike hosts match", POL, 'oauthHost == "accounts.google.com" || oauthHost.endsWith(".accounts.google.com")', 'oauthHost.startsWith("accounts.google.com")', "O1 only https"),
    ("plain http matches", POL, 'if (!u.startsWith("https://")) return false', 'if (!u.startsWith("http")) return false', "O1 only https"),
    ("the tab ignores the decision", FRAG, "is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); return true }", "is BrowserNavPolicy.Decision.CustomTab -> return false", "O2 the tab"),
    ("the popup ignores the decision", FRAG, "is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); dialog.dismiss(); true }", "is BrowserNavPolicy.Decision.CustomTab -> false", "O2 the popup"),
    ("no launch", FRAG, "runCatching { startActivity(PasskeySupport.secureBrowserIntent(launch)) }\n            .onFailure { oauthHandedOff", "runCatching { Unit }\n            .onFailure { oauthHandedOff", "O3"),
    ("no reload on return", FRAG, "if (oauthHandedOff) { oauthHandedOff = false; webView?.reload() }", "", "O4"),
])
PYEOF
