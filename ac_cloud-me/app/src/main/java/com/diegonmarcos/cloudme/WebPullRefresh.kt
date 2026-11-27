package com.diegonmarcos.cloudme

/**
 * #1947 pull-down at the top of an embedded page reloads it, as in any browser. The rules are pure (unit-tested); WebPage wires them to a
 * SwipeRefreshLayout around the WebView (an android.webkit.WebView does not dispatch nested scroll to Compose, so Modifier.pullToRefresh never fired).
 */
object WebPullRefresh {
    /** Armed on EVERY embedded page, cached or not. */
    @Suppress("UNUSED_PARAMETER")
    fun armed(cached: Boolean): Boolean = true

    /** The refresh layout may claim the drag only when nothing in the page can scroll up: the WebView itself, or an inner element under the finger. */
    fun canChildScrollUp(webScrollY: Int, webCanScrollUp: Boolean, innerScrolled: Boolean): Boolean = webScrollY > 0 || webCanScrollUp || innerScrolled

    /** Evaluated on touch-down at (x, y) CSS px: true when the document or any ancestor of the touched element is scrolled down. */
    fun innerScrolledJs(x: Float, y: Float): String =
        "(function(){var s=document.scrollingElement;if(s&&s.scrollTop>0)return true;var e=document.elementFromPoint($x,$y);" +
            "while(e){if(e.scrollTop>0)return true;e=e.parentElement}return false})()"

    /** The hard refresh: a cached page's files are deleted first, then the WebView cache is emptied and the page reloaded from the network. */
    suspend fun run(cached: Boolean, hardRefresh: suspend () -> Unit, clearCache: () -> Unit, reload: () -> Unit) {
        if (cached) hardRefresh()
        clearCache()
        reload()
    }
}
