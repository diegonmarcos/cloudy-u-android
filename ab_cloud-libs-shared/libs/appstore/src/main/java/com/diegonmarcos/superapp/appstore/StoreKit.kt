package com.diegonmarcos.superapp.appstore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.uikit.CloudKitTheme

/**
 * The Store's look for a host app's own Compose pages (Cloud Store's Configs > Torrent): the same Kit palette the Search pages sit in
 * (its card, tab fills and state colours), so the Kit* components (KitCard, KitStatusBanner, KitListRow ...) and the source pill draw
 * there exactly as they do on Search.
 */
object StoreKit {
    /** Wraps [content] in CloudKitTheme with the Store's palette. */
    @Composable
    fun Themed(content: @Composable () -> Unit) {
        val ctx = LocalContext.current
        CloudKitTheme(remember(ctx) { StoreSearchPage.palette(ctx) }, content)
    }

    /** The pill Search > Torrent draws for a source / filter: lit when [on]. */
    @Composable
    fun Pill(label: String, on: Boolean, tag: String, onClick: () -> Unit) = StoreSearchPage.SourcePill(label, on, tag, onClick)
}
