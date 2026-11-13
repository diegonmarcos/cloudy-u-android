#!/usr/bin/env bash
# #307 — Cloud Mail's own mini-browser.
#
# A link in an email used to leave the app. It now opens in a contained window with an editable
# address bar and a four-entry menu (Cloud Browser, Brave, translate, view as desktop/mobile).
# What this pins is the DECISION, not the pixels: one opener that every call site goes through, a
# scheme split that never pretends a WebView can dial a phone, and the two hand-offs that must
# stay external (OAuth, content:// attachments) still being external.
set -u

APP="$(cd "$(dirname "$0")/.." && pwd)"
UI="$APP/app/src/main/kotlin/app/sterna/ui"
BROWSER="$UI/browser"
OPENER="$BROWSER/InAppBrowser.kt"
WINDOW="$BROWSER/MiniBrowserActivity.kt"
MANIFEST="$APP/app/src/main/AndroidManifest.xml"
RES="$APP/app/src/main/res"

PASS=0
FAIL=0
ok() { PASS=$((PASS + 1)); echo "PASS: $1"; }
bad() { FAIL=$((FAIL + 1)); echo "FAIL: $1"; }

# Code only — a rule satisfied by a comment mentioning it is not satisfied.
code() { grep -vE '^[[:space:]]*(//|\*|/\*)' "$1" 2>/dev/null; }

has() { code "$2" | grep -qF -- "$1" && ok "$3" || bad "$3"; }
hasnt() { code "$2" | grep -qF -- "$1" && bad "$3" || ok "$3"; }
hasre() { code "$2" | grep -qE -- "$1" && ok "$3" || bad "$3"; }

echo "== #307 mini-browser =="

# B1 — the window and the opener both exist as their own files. The opener being separate from the
# window is the point: a call site depends on "open this link", not on the browser's implementation.
[ -f "$OPENER" ] && ok "B1 InAppBrowser.kt exists" || bad "B1 InAppBrowser.kt exists"
[ -f "$WINDOW" ] && ok "B1 MiniBrowserActivity.kt exists" || bad "B1 MiniBrowserActivity.kt exists"

# B2 — http(s) stays in the app. That IS the feature; without this line the whole task is undone.
has 'setOf("http", "https")' "$OPENER" "B2 http/https are the in-app schemes"
has 'MiniBrowserActivity.openMiniBrowser(context, uri)' "$OPENER" "B2 openLink routes web pages in-app"

# B3 — and the schemes a WebView cannot serve still leave. A blank page where a dialler belongs is
# worse than the old behaviour, not better.
has 'setOf("mailto", "tel", "sms", "geo")' "$WINDOW" "B3 mailto/tel/sms/geo hand off to the system"
has '"http", "https" -> false' "$WINDOW" "B3 a web page is not overridden"
has 'else -> true' "$WINDOW" "B3 intent:/javascript:/file:/content: are swallowed"

# B4 — the address bar is editable. A read-only URL strip is a title, not a nav bar.
has 'BasicTextField' "$WINDOW" "B4 the address bar is an editable field"

# B5 — all four menu entries, by string resource so a rename cannot silently drop one.
for entry in browser_open_in_cloud_browser browser_open_in_brave browser_translate_page \
    browser_view_desktop browser_view_mobile; do
    has "R.string.$entry" "$WINDOW" "B5 menu entry $entry"
done
has 'InAppBrowser.CLOUD_BROWSER_PACKAGE' "$WINDOW" "B5 Cloud Browser is named explicitly"
has 'InAppBrowser.BRAVE_PACKAGE' "$WINDOW" "B5 Brave is named explicitly"

# B6 — "view as desktop" is the fleet's one desktop mode (#1371 libs:web-core: a desktop UA with the WebView's own
# Chrome version, plus a desktop viewport); off restores the platform's own UA.
has 'CloudWeb.desktopMode(wv, desktop)' "$WINDOW" \
    "B6 desktop mode switches the user agent"

# B7 — translate through Google's own redirect, not a locally-built .translate.goog host. The proxy
# host mangles dots and dashes in a way only Google can keep correct.
has 'translate.google.com/translate?sl=auto&tl=' "$OPENER" "B7 translate uses the redirect form"
has 'Uri.encode(url)' "$OPENER" "B7 the wrapped address is percent-encoded"

# B8 — a WebView renders pages, it does not download files. Without the listener the release-APK
# link on the update screen is silently nothing at all once it stops leaving the app.
has 'setDownloadListener' "$WINDOW" "B8 downloads are handed to the system"

# B9 — registered, and NOT exported. A contained window other apps can aim a URL at is not
# contained.
grep -qF '.ui.browser.MiniBrowserActivity' "$MANIFEST" && ok "B9 the activity is registered" ||
    bad "B9 the activity is registered"
grep -A2 -F '.ui.browser.MiniBrowserActivity' "$MANIFEST" | grep -qF 'android:exported="false"' &&
    ok "B9 the activity is not exported" || bad "B9 the activity is not exported"

# B10 — Android 11+ package visibility. Without these an explicit setPackage() cannot resolve at
# all, and "open in Brave" reads as broken rather than as not installed.
for pkg in com.diegonmarcos.cloudbrowser com.brave.browser; do
    grep -qF "<package android:name=\"$pkg\" />" "$MANIFEST" && ok "B10 <queries> declares $pkg" ||
        bad "B10 <queries> declares $pkg"
done

# B11 — every web-link exit goes through the one opener. A call site that keeps its own
# ACTION_VIEW is how the two halves drifted last time (#156, #307).
hasnt 'private fun openExternally' "$UI/message/MessageScreen.kt" \
    "B11 the reader has no private opener of its own"
has 'InAppBrowser.openLink(context, uri)' "$UI/message/MessageScreen.kt" \
    "B11 the reader's link chokepoint routes in-app"
has 'InAppBrowser.openLink(context, Uri.parse(url))' "$UI/settings/SettingsScreen.kt" \
    "B11 settings reference pages open in-app"
has 'InAppBrowser.openLink(context, Uri.parse(url))' "$UI/connect/ConnectScreen.kt" \
    "B11 connect help pages open in-app"
has 'InAppBrowser.openLink(context, Uri.parse(MS_APP_PASSWORD_URL))' \
    "$UI/components/AppPasswordHelpLink.kt" "B11 the app-password help link opens in-app"

# B12 — OAuth deliberately does NOT. Its redirect returns on a scheme this browser refuses to
# navigate, and a sign-in belongs in the browser that already holds the session. If someone
# "finishes the job" by routing these too, sign-in breaks silently.
has 'Intent(MailOAuthContract.ACTION_CODE_OPEN)' "$UI/connect/ConnectScreen.kt" \
    "B12 the OAuth authorization hand-off stays external (#1703: Cloud Account opens the browser)"
hasre 'Intent\(Intent\.ACTION_VIEW, Uri\.parse\(target\)\)' "$UI/settings/SettingsScreen.kt" \
    "B12 the device-code hand-off stays external"

# B13 — content:// attachments stay with the system too: those are files, not pages.
hasnt 'InAppBrowser.openLink' "$UI/attachment/AttachmentOpen.kt" \
    "B13 attachments are not routed through the browser"

# B14 — the opener must not be called `start`. NavHostSourceRulesTest derives its hand-off token
# list from the sources: a `: Boolean` function containing startActivity( has its OWN NAME added as
# a token, so `start` would make the substring "start(" mean "leaves the app" across the whole tree
# and flag every unrelated .start() in it.
hasnt 'fun start(context: Context' "$WINDOW" "B14 the opener is not named start"
has 'fun openMiniBrowser(context: Context' "$WINDOW" "B14 the opener is named in full"

# B15 — EVERY locale the app ships, not just English and Spanish. This tester originally checked
# two files and passed while the release build failed: TranslationParityTest diffs values/ against
# every values-*/ directory that has a strings.xml, and the app ships nine. Ten English strings in
# two files is a red ship, not a half-translated one, so the list below is derived from disk rather
# than written out — a tenth locale added tomorrow is covered without touching this file.
LOCALES="$(find "$RES" -mindepth 2 -maxdepth 2 -name strings.xml -path '*/values*/*' | sort)"
for key in browser_title browser_close browser_reload browser_menu browser_no_app \
    browser_open_in_cloud_browser browser_open_in_brave browser_translate_page \
    browser_view_desktop browser_view_mobile; do
    absent=""
    for file in $LOCALES; do
        grep -qF "\"$key\"" "$file" || absent="$absent $(basename "$(dirname "$file")")"
    done
    if [ -z "$absent" ]; then
        ok "B15 $key in every shipped locale"
    else
        bad "B15 $key missing from:$absent"
    fi
done

echo "-- $PASS passed, $FAIL failed"
exit "$FAIL"
