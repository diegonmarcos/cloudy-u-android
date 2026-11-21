package com.diegonmarcos.superapp.browser

/**
 * #1841 the renderer-gone policy, pure. A WebView's renderer process dies under memory pressure (the OOM killer takes a background
 * renderer first) or crashes (GPU driver, chrome://crash). The host answers every death by rebuilding the tab's WebView from its
 * snapshot ([BrowserSessionSnapshots]); this guard stops a page that crashes its renderer on every load from looping: after [max]
 * deaths of one tab inside [windowMs] the tab is rebuilt on a plain error page instead of the page that kills it.
 */
class BrowserRenderGuard(private val max: Int = 3, private val windowMs: Long = 60_000L, private val now: () -> Long = System::currentTimeMillis) {
    enum class Action { RECREATE_FROM_SNAPSHOT, GIVE_UP_SHOW_ERROR }

    private val deaths = HashMap<String, ArrayDeque<Long>>()

    /** A renderer of [tabKey] died: what to do. */
    @Synchronized fun onGone(tabKey: String): Action {
        val t = now()
        val q = deaths.getOrPut(tabKey) { ArrayDeque() }
        while (q.isNotEmpty() && t - q.first() > windowMs) q.removeFirst()
        q.addLast(t)
        return if (q.size > max) Action.GIVE_UP_SHOW_ERROR else Action.RECREATE_FROM_SNAPSHOT
    }

    /** The tab reached a healthy page finish: its death count starts over. */
    @Synchronized fun onHealthy(tabKey: String) { deaths.remove(tabKey) }

    @Synchronized fun deathsOf(tabKey: String): Int = deaths[tabKey]?.size ?: 0

    companion object {
        /** The page shown when a tab keeps killing its renderer: never a blank or black view. */
        fun errorPage(host: String): String =
            "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><body style='font-family:sans-serif;background:#1A0033;color:#fff;padding:24px'>" +
                "<h2>This page keeps stopping</h2><p>The page on <b>" + host.replace("<", "").replace("&", "") +
                "</b> made the browser's renderer stop several times in a minute, so it was not loaded again.</p><p>Pull down or use Reload to try once more.</p>"
    }
}
