package com.diegonmarcos.superapp.media

import androidx.appcompat.app.AppCompatActivity
import androidx.compose.ui.platform.ComposeView

/**
 * Drives the music-playing mini-island above the Dynamic Island, extracted from
 * MainActivity. Owns the [NowPlayingMonitor] + [MusicControlsPopup]; the Activity
 * just calls [resume]/[pause] from its lifecycle and hands over the island's
 * ComposeView (declared in activity_main.xml, found by the Activity).
 *
 * The island is permanently visible - a plain black pill when nothing plays,
 * lighting up (icon + marquee title + wave) when a media session is PLAYING.
 * Tap -> a Samsung-style controls popup anchored under the island.
 */
class MusicIslandController(private val activity: AppCompatActivity, private val island: ComposeView?) {

    // Lazy: allocate the MediaSessionManager binder + listener only once the
    // activity is actually visible (first resume).
    private var monitor: NowPlayingMonitor? = null
    private var popup: MusicControlsPopup? = null
    private val state = MusicIslandState()

    fun resume() {
        if (island == null) return
        island.visibility = android.view.View.VISIBLE
        if (monitor == null) {
            island.setContent {
                com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.ui.LauncherPalette.kit(activity)) {
                    MusicIsland(state, onTap = {
                        // activeController, not currentController: a PAUSED player is
                        // still a live session, so its transport (prev/play/next/seek)
                        // stays reachable from the card instead of going dead.
                        val ctrl = monitor?.activeController
                        val pkg = monitor?.currentPackage ?: ctrl?.packageName
                        if (popup == null) popup = MusicControlsPopup(activity)
                        popup?.show(island, ctrl, pkg)
                    })
                }
            }
            monitor = NowPlayingMonitor(activity) { playingPackage, songTitle, artist ->
                state.playing = if (playingPackage != null) {
                    val song = songTitle ?: runCatching {
                        activity.packageManager.getApplicationLabel(
                            activity.packageManager.getApplicationInfo(playingPackage, 0)).toString()
                    }.getOrDefault("Playing")
                    NowPlayingLine(playingPackage, if (!artist.isNullOrBlank()) "$artist \u2014 $song" else song)
                } else null
            }
        }
        monitor?.start()
    }

    fun pause() {
        monitor?.stop()
        popup?.dismiss()
    }
}
