package com.diegonmarcos.superapp.browser

import android.content.Context
import android.webkit.WebView
import com.diegonmarcos.cloudlib.browsercore.BrowserCore
import com.diegonmarcos.cloudlib.browsercore.TabSnapshot
import java.io.File

/**
 * #1841 the persisted session: for every normal tab a [TabSnapshot] (url, back/forward stack, scroll, title, favicon, the file holding
 * WebView's own saved state) in the browser core's store (SQLite with libbrowser_core.so, a JSON file without it), next to the per-tab
 * WebView state file [BrowserWebState] writes. A tab comes back from, in this order: its WebView state (back stack, scroll, form input
 * where WebView keeps it), else its snapshot's page at its scroll, else the tab's committed url. This is what a renderer that died, a
 * process that was killed, an app that was switched away from and a tab that was dropped from memory all restore from.
 * A private tab never gets a snapshot.
 */
class BrowserSessionSnapshots(private val ctx: Context) {
    private val store: BrowserCore.SessionStore by lazy {
        BrowserCore.SessionStore(File(ctx.filesDir, "browser-core/session.db").path)
    }

    /** Read [wv]'s page as a snapshot; null when the page is not worth keeping (blank, about:, a POST result). */
    fun read(wv: WebView, tabKey: String, favicon: String = ""): TabSnapshot? = runCatching {
        val list = wv.copyBackForwardList()
        val url = wv.url ?: list.currentItem?.url
        if (!BrowserTabStore.shouldCommit(url)) return null
        val history = (0 until list.size).mapNotNull { list.getItemAtIndex(it)?.url }
        val stateFile = BrowserWebState.file(ctx, tabKey)
        TabSnapshot(
            key = tabKey, url = url!!, title = wv.title.orEmpty(), scrollX = wv.scrollX, scrollY = wv.scrollY, favicon = favicon,
            history = history, index = list.currentIndex.coerceAtLeast(0),
            stateFile = if (stateFile.isFile) stateFile.path else "", updatedAt = System.currentTimeMillis(),
        )
    }.getOrNull()

    fun save(s: TabSnapshot): Boolean = runCatching { store.put(s) }.getOrDefault(false)

    fun load(tabKey: String): TabSnapshot? = runCatching { store.get(tabKey) }.getOrNull()

    fun delete(tabKey: String) { runCatching { store.delete(tabKey) } }

    fun prune(liveKeys: Collection<String>): Int = runCatching { store.prune(liveKeys) }.getOrDefault(0)

    fun count(): Int = runCatching { store.count() }.getOrDefault(0)

    /** True while the Rust store answers. */
    val usingCore: Boolean get() = store.usingCore

    /** Where a tab's page comes from when its WebView is (re)built. */
    enum class Source(val label: String) { WEBVIEW_STATE("webview-state"), SNAPSHOT("snapshot"), TAB_URL("tab-url") }

    /** The decision, pure: [hasState] says the WebView state file restored; [snap] the stored snapshot; [tabUrl] the tab's committed url. */
    data class Plan(val source: Source, val url: String, val scrollX: Int, val scrollY: Int)

    companion object {
        /**
         * The restore plan. When WebView's own state restored ([stateRestored]) it already carries the back stack and the scroll, so nothing
         * is loaded; otherwise the snapshot's page (when it is a real page) at its scroll, otherwise the tab's url from the tab store.
         */
        fun plan(stateRestored: Boolean, snap: TabSnapshot?, tabUrl: String): Plan = when {
            stateRestored -> Plan(Source.WEBVIEW_STATE, snap?.url ?: tabUrl, 0, 0)
            snap != null && BrowserTabStore.shouldCommit(snap.url) -> Plan(Source.SNAPSHOT, snap.url, snap.scrollX, snap.scrollY)
            else -> Plan(Source.TAB_URL, tabUrl, 0, 0)
        }
    }
}
