#!/bin/sh
# ╔══════════════════════════════════════════════════════════════════╗
# ║ Each of the seven is REACHABLE, not merely present               ║
# ╚══════════════════════════════════════════════════════════════════╝
#
# The JVM unit tests (app/src/test/) assert the RULES: ordering, the pin
# refusing a close, first-run-only seeding, which engine a typed query
# resolves to. None of that proves a finger can reach any of it.
#
# This tester covers the other half — that the mechanism is wired into
# the UI. A data model with no way to use it is not a feature, and
# "declared done with a data model and no way to use it" is on this
# repository's list of shipped false greens.
#
# Everything is matched against COMMENT-STRIPPED Kotlin, because a grep
# cannot tell a call from prose and a guard here has already been
# satisfied by its own KDoc.
set -eu

ROOT=$(cd "$(dirname "$0")/../.." && pwd)
export ROOT
python3 - <<'PYEOF'
import os, sys

ROOT = os.environ["ROOT"]
PKG  = os.path.join(ROOT, "ab_cloud-libs-shared", "libs", "browser",
                    "src/main/java/com/diegonmarcos/superapp/browser")

fails = []
def check(ok, label, detail=""):
    print(("  PASS  " if ok else "  FAIL  ") + label + (("\n          " + detail) if (detail and not ok) else ""))
    if not ok:
        fails.append(label)

def strip_kotlin_comments(src):
    """Remove // and NESTING /* */ comments, while PRESERVING string
    literals verbatim.

    String-awareness is not a nicety here. A naive stripper treats the
    // inside "https://..." as the start of a comment and eats the rest
    of the line, so every assertion about a URL in Kotlin source silently
    becomes an assertion about nothing. Kotlin block comments also NEST,
    and one stray /* in a KDoc otherwise swallows the whole file."""
    out, i, n, depth = [], 0, len(src), 0
    while i < n:
        two = src[i:i+2]
        if depth:
            if two == "/*": depth += 1; i += 2; continue
            if two == "*/": depth -= 1; i += 2; continue
            i += 1; continue
        if src[i:i+3] == '"""':
            j = src.find('"""', i + 3)
            j = n if j < 0 else j + 3
            out.append(src[i:j]); i = j; continue
        if src[i] in ('"', "'"):
            q = src[i]; j = i + 1
            while j < n and src[j] != q:
                j += 2 if src[j] == "\\" else 1
            j = min(j + 1, n)
            out.append(src[i:j]); i = j; continue
        if two == "/*": depth += 1; i += 2; continue
        if two == "//":
            j = src.find("\n", i); i = n if j < 0 else j; continue
        out.append(src[i]); i += 1
    return "".join(out)

SRC = {}
for name in ("BrowserHostFragment", "BrowserTabGrid", "BrowserTabPrefs",
             "BrowserSuggest", "BrowserHistory", "BrowserSearch",
             "BrowserGridRows", "BrowserTab", "BrowserSettingsCatalogue"):
    p = os.path.join(PKG, name + ".kt")
    if not os.path.isfile(p):          # fail CLOSED
        check(False, "source exists: " + name + ".kt")
        SRC[name] = ""
        continue
    SRC[name] = strip_kotlin_comments(open(p, encoding="utf-8").read())

def has(name, *needles):
    return all(nd in SRC.get(name, "") for nd in needles)

# ── 1. hold-and-drag reorder, and it persists ────────────────────────
check(has("BrowserTabGrid", "LazyVerticalGrid", "detectDragGesturesAfterLongPress"),
      "1: the tab grid is a Compose LazyVerticalGrid with a long-press drag")
# Matched as the WHOLE declaration. Asserting "isLongPressDragEnabled"
# and "return true" as two separate needles passed against a flipped
# `= false`, because some other method in the file also returns true —
# two greps ANDed prove co-occurrence, never proximity.
check(has("BrowserTabGrid", "pointerInput(key)", "detectDragGesturesAfterLongPress(", "onDragStart = { drag.begin(key) }"),
      "1: long-press starts the drag (what he asked for)")
# An import is not a reorder. The drop has to reach the store.
check(has("BrowserTabGrid", "onReorder(model.visibleTabUrls())"),
      "1: the drop calls back to persist, not just animate")
check(has("BrowserHostFragment", "onReorder = { urls -> prefs.reorder(urls) }"),
      "1: the host wires that callback to BrowserTabPrefs.reorder")
check(has("BrowserTabPrefs", "fun reorder(", "BrowserTabOrder.stamp", "save("),
      "1: reorder stamps an explicit index and WRITES it")

# ── 2. pinning, and the close it refuses ─────────────────────────────
check(has("BrowserTabPrefs", "BrowserTabOps.close(read(), url) ?: return false"),
      "2: remove() defers to the rule that refuses a pinned tab")
check(has("BrowserTab", "if (tab.pinned) return null"),
      "2: the refusal itself")
check(not has("BrowserTabGrid", "ID_CLOSE") and not has("BrowserTabGrid", '" ✕ "'),
      "2: a card has no close button at all; swipe closes it")
check(has("BrowserTabGrid", "detectHorizontalDragGestures", "SwipeGesture.needsConfirm(tabNow)", "onSwipe(tabNow)"),
      "2: a card swipes left or right to close, a pinned one is confirmed first")
check(has("BrowserHostFragment", "Snackbar", '"Undo"', "swipeClose.begin(tab)", "fun commitSwipe("),
      "2: a swipe-close offers Undo, and the teardown waits for the window to end")
check(has("BrowserTabGrid", "browser:tabcard:pin", "onPin(tab)", "fontSize = 24.sp"),
      "2: the card has a pin icon that toggles the pin, and a larger options icon")
check(has("BrowserHostFragment", 'if (tab.pinned) "Unpin tab" else "Pin tab"'),
      "2: pin/unpin is reachable from the card menu")
check(has("BrowserTab", "tabs[from].pinned == tabs[to].pinned"),
      "2: a drag cannot lift an unpinned tab above a pinned one")

# ── 3. groups: named, collapsible, reachable ─────────────────────────
check(has("BrowserHostFragment", "promptForGroup", "prefs.setGroupById("),
      "3: a tab can be put in a named group from the UI")
check(has("BrowserHostFragment", "onToggleGroup", "setGroupCollapsed"),
      "3: tapping a group header collapses it")
check(has("BrowserGridRows", "GroupHeader", "if (!isCollapsed)"),
      "3: a collapsed group contributes its header only")
check(has("BrowserTabGrid", "GroupHeader", "GridItemSpan(if (it is BrowserGridRow.GroupHeader) maxLineSpan else 1)"),
      "3: the header is actually drawn, spanning the grid")

# ── 5. history: recorded, viewable, clearable, LOCAL ─────────────────
check(has("BrowserHostFragment", "history.record("),
      "5: visits are recorded on page finish")
check(has("BrowserHostFragment", "fun showHistory(", 'BrowserTabsBar.Id.HISTORY -> showHistory()'),
      "5: the history view is reachable from the tab grid")
check(has("BrowserHostFragment", "history.clear()"),
      "5: he can clear it")
# The privacy requirement, asserted structurally rather than promised.
net = ("HttpURLConnection", "OkHttp", "java.net.URL(", "Retrofit",
       "openConnection", "Socket(")
leaks = [n for n in net for f in ("BrowserHistory", "BrowserSuggest") if n in SRC.get(f, "")]
check(not leaks,
      "5: history and suggestions contain NO network client",
      "found: %r" % (leaks,))

# ── 6. suggestion dropdown, drawing on local sources ─────────────────
# #886 the dropdown is a Compose panel laid out IN the page area (a child of the frame holding the
# WebView), not an AutoCompleteTextView's PopupWindow, which landed on top of the bar and the keyboard.
check(has("BrowserHostFragment", "BrowserSuggestOverlay(suggestState", "AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webView) }"),
      "6: the address field has a dropdown, drawn inside the page frame")
check(not has("BrowserHostFragment", "AutoCompleteTextView") and not has("BrowserHostFragment", "showDropDown("),
      "6: no PopupWindow dropdown is left to overlap the bar or the keyboard")
check(has("BrowserHostFragment", "BrowserSuggest.sections(", "prefs.all(), history.all()"),
      "6: it is fed from open tabs and local history")
check(has("BrowserHostFragment", "BrowserAddressOverlay(", "refreshSuggestions(suggestState"),
      "6: the DETAIL-mode address bar drives that panel")
check(has("BrowserSuggest", "Source.TAB", "Source.HISTORY", "Source.SEARCH"),
      "6: those are the only three sources there are")

# ── 7. the address bar searches ──────────────────────────────────────
check(has("BrowserHostFragment", "BrowserSearch.resolve(q, engine())"),
      "7: the address bar resolves through the engine, not straight to a URL")
# #802 the engine picker became a catalogue setting: an enum whose values ARE the app's
# engine list (values_from), drawn by the generic settings page.
check(has("BrowserSettingsCatalogue", 'o.optString("values_from") == "search_engines"', "engineIds"),
      "7: the engine is a setting, and its list comes from the app's config")
check(has("BrowserSearch", "if (isUrlLike(s)) normalizeUrl(s) else searchUrl(s, engine)"),
      "7: a URL navigates, anything else searches")

print()
if fails:
    print("FAILED %d assertion(s):" % len(fails))
    for f in fails:
        print("  - " + f)
    sys.exit(1)
print("feature wiring: all assertions passed")
PYEOF
