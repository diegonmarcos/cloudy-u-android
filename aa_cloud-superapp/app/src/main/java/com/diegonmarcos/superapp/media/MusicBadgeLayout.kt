package com.diegonmarcos.superapp.media

import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import android.provider.Settings

/**
 * #1810 the pure, unit-testable parts of the media badge layout: the earbuds-name to companion-app table, the button sizes
 * and the rules for the other-media-app strip. Nothing here is removed from the badge; it only rearranges and restyles.
 */
internal object MusicBadgeLayout {
    /** Side buttons (Shazam/history, favourite). */
    const val SIDE_DP = 34
    /** Prev, Play/Pause and Next: about 1.35x the side buttons; the glyph box is also the touch target, so it grows with it. */
    const val TRANSPORT_DP = 46
    val transportRatio: Float get() = TRANSPORT_DP.toFloat() / SIDE_DP

    /** The strip of the other media apps is always shown (idle or playing); only its look depends on the play state. */
    fun mediaAppsVisible(@Suppress("UNUSED_PARAMETER") playing: Boolean): Boolean = true

    /** Playing: each app's OWN icon with the colour removed (greyscale, [GREYSCALE]); not playing: the apps' own colours. */
    fun mediaAppsMonochrome(playing: Boolean): Boolean = playing

    /** Saturation 0: keeps every icon's own shape and shading, only drops the colour. A tint would paint each opaque
     *  pixel one colour and turn an icon into a solid square, which is what the first version showed. */
    val GREYSCALE_MATRIX: ColorMatrix get() = ColorMatrix().apply { setToSaturation(0f) }
    /** Lazy: building a ColorFilter touches the Android framework, which plain JVM unit tests do not have. */
    val GREYSCALE: ColorFilter by lazy { ColorFilter.colorMatrix(GREYSCALE_MATRIX) }

    /** The earbuds row sits bottom-left of the card, left of the centred transport buttons: capped so it never reaches them. */
    const val ROUTE_MAX_DP = 120
    /** Height kept free at the bottom of the card for that row (the card grew by this much in #1810). */
    const val ROUTE_ROW_DP = 22

    /** The progress line is the island waves (no slider track/thumb): its height, and the strength of the unplayed part. */
    const val WAVE_LINE_DP = 28
    const val UNPLAYED_ALPHA = 0.35f

    /** Touch x on the wave line -> playback fraction, clamped to [0, 1]; a zero-width line seeks to 0. */
    fun seekFraction(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /** A route not resolved yet still shows a label, so the row is never an empty, invisible strip. */
    fun routeLabel(label: String): String = label.ifBlank { "Audio output" }

    const val SOUNDCORE_PKG = "com.oceanwing.soundcore"
    private val SAMSUNG_PKGS = listOf(
        "com.samsung.accessory.berrymgr",           // Galaxy Buds2 / Pro / FE plug-in
        "com.samsung.accessory.neobeanmanager",     // Galaxy Buds (neobean) manager
        "com.samsung.android.app.watchmanager",     // Galaxy Wearable
    )

    /** The companion-app packages to try, in order, for the earbuds called [deviceName]; empty for an unknown brand. */
    fun budsPackages(deviceName: String): List<String> {
        val n = deviceName.lowercase()
        return when {
            "soundcore" in n || "anker" in n || "liberty" in n || "space one" in n -> listOf(SOUNDCORE_PKG)
            "galaxy buds" in n || "buds2" in n || "buds pro" in n || "buds fe" in n || "buds live" in n -> SAMSUNG_PKGS
            else -> emptyList()
        }
    }

    /** The first of [candidates] that [launchIntentFor] resolves, or null (the caller then opens Settings bluetooth). */
    fun pickLaunch(candidates: List<String>, launchIntentFor: (String) -> Intent?): Intent? {
        for (p in candidates) launchIntentFor(p)?.let { return it }
        return null
    }

    /** Taps the earbuds row: the buds app if one is installed, else Settings bluetooth. Never throws. */
    fun openBudsApp(ctx: Context, deviceName: String) {
        val intent = pickLaunch(budsPackages(deviceName)) { runCatching { ctx.packageManager.getLaunchIntentForPackage(it) }.getOrNull() }
            ?: Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        runCatching { ctx.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
