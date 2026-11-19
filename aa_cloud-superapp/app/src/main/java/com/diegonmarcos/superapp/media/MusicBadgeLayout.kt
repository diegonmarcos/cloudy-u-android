package com.diegonmarcos.superapp.media

import android.content.Context
import android.content.Intent
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

    /** Playing: plain white monochrome icons (no colours); not playing: the apps' own icons. */
    fun mediaAppsWhite(playing: Boolean): Boolean = playing

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
