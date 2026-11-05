package com.diegonmarcos.superapp.browser

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable

/**
 * #1518 what the consuming app plugs into the shared host (libs:browser is shared by reference, so it names no app's feature):
 * a request answered from an offline copy, the Configs "gpu" block, and the page-menu actions the app owns. All default to
 * nothing, so an app that sets none gets the plain browser.
 */
object BrowserHostHooks {
    /** A request answered from the app's offline copy of a pinned site; null = the network. Runs OFF the main thread. */
    @Volatile var intercept: ((WebResourceRequest) -> WebResourceResponse?)? = null

    /** The Configs page whose native block is "gpu". */
    @Volatile var gpuPage: (@Composable ColumnScope.() -> Unit)? = null

    /** A page-menu action the app owns (pin_site, download_site): id and page url in, the toast out (null = not handled). */
    @Volatile var action: ((id: String, url: String) -> String?)? = null

    /** A fact the app owns for the menu (site_pinned). */
    @Volatile var fact: ((id: String, url: String) -> Boolean)? = null
}
