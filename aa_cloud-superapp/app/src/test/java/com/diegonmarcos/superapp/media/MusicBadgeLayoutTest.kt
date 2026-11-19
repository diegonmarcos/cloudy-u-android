package com.diegonmarcos.superapp.media

import android.app.Application
import android.content.Intent
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.superapp.ui.ChannelWaves
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import java.io.File

/** #1810 the media badge rearrangement: buds-app table, persistent white icons, larger transport, waves, and field parity (rule 16). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MusicBadgeLayoutTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `buds app mapping`() {
        assertEquals(listOf("com.oceanwing.soundcore"), MusicBadgeLayout.budsPackages("soundcore Liberty 4 Pro"))
        assertEquals(listOf("com.oceanwing.soundcore"), MusicBadgeLayout.budsPackages("Anker Space One"))
        val gb = MusicBadgeLayout.budsPackages("Galaxy Buds2 Pro (ABCD)")
        assertEquals("com.samsung.accessory.berrymgr", gb.first())
        assertEquals(listOf("com.samsung.accessory.berrymgr", "com.samsung.accessory.neobeanmanager", "com.samsung.android.app.watchmanager"), gb)
        assertTrue(MusicBadgeLayout.budsPackages("JBL Tune").isEmpty())
    }

    @Test
    fun `first installed buds package wins and none gives null`() {
        val seen = mutableListOf<String>()
        val i = Intent("x")
        val got = MusicBadgeLayout.pickLaunch(MusicBadgeLayout.budsPackages("Galaxy Buds FE")) { p -> seen += p; if (p.endsWith("neobeanmanager")) i else null }
        assertEquals(i, got)
        assertEquals(2, seen.size)
        assertNull(MusicBadgeLayout.pickLaunch(listOf("a", "b")) { null })
    }

    @Test
    fun `media app icons are persistent and colourless only while playing`() {
        for (playing in listOf(true, false)) assertTrue(MusicBadgeLayout.mediaAppsVisible(playing))
        assertTrue(MusicBadgeLayout.mediaAppsMonochrome(true))
        assertTrue(!MusicBadgeLayout.mediaAppsMonochrome(false))
        showKit { MusicControlsCard(ctx, null, null) {} }
        for (pkg in MusicControlsPopup.IDLE_LAUNCH_PKGS) compose.onNodeWithTag("music:idle:$pkg").assertExists()
    }

    @Test
    fun `prev play next are about 1_3x the side buttons and their touch targets match`() {
        assertTrue(MusicBadgeLayout.transportRatio in 1.25f..1.36f)
        showKit { MusicControlsCard(ctx, null, null) {} }
        val side = compose.onNodeWithTag("music:shazam").fetchSemanticsNode().size.width
        for (t in listOf("music:prev", "music:play", "music:next")) {
            val w = compose.onNodeWithTag(t).fetchSemanticsNode().size.width
            assertTrue("$t $w vs $side", w.toFloat() / side in 1.25f..1.36f)
        }
        assertEquals(side, compose.onNodeWithTag("music:heart").fetchSemanticsNode().size.width)
    }

    @Test
    fun `waves are three channels in three colours, moving when playing and flat when paused`() {
        assertEquals(3, ChannelWaves.channels.size)
        assertEquals(3, ChannelWaves.channels.map { it.color }.distinct().size)
        for (c in ChannelWaves.channels) {
            assertEquals(0f, ChannelWaves.sample(c, 0.3f, 5f, playing = false), 0f)
            assertNotEquals(ChannelWaves.sample(c, 0.3f, 1f, true), ChannelWaves.sample(c, 0.3f, 2f, true))
        }
    }

    @Test
    fun `field parity every field of the previous layout is still drawn`() {
        showKit { MusicControlsCard(ctx, null, null) {} }
        for (t in listOf("music:card", "music:app-icon", "music:title", "music:progress", "music:shazam", "music:prev",
            "music:play", "music:next", "music:heart", "music:idle-apps")) compose.onNodeWithTag(t).assertExists()
        // Live-only fields (route, volume, elapsed/total, bluetooth name, Vol %) need a MediaController: checked in the source.
        val src = listOf("src/main/java", "app/src/main/java").map { File(it, "com/diegonmarcos/superapp/media/MusicControlsPopup.kt") }
            .first { it.exists() }.readText()
        for (needle in listOf("music:route", "music:volume", "music:elapsed", "\"Vol \$pct%\"", "ic_route_bluetooth", "ic_route_headphones",
            "ic_route_speaker", "music:sessions", "music:access", "music:dimmed", "music:scrim", "music:art", "--:--", "MediaHub.Action.SEEK", "setRating"))
        // (#1810: the Slider widget was removed on the owner's order; the field it carried - position + seek - is the
        //  island-wave progress line now, so parity checks the seek action, not the widget's name.)
            assertTrue("missing $needle", src.contains(needle))
    }

    @Test
    fun `playing icons are greyscale (each app's own icon without colour), never a solid white tint`() {
        val m = MusicBadgeLayout.GREYSCALE_MATRIX.values
        // Pure red, green and blue each come out as a grey (r == g == b) of DIFFERENT brightness: the icon keeps its shading.
        fun apply(r: Float, g: Float, b: Float) = FloatArray(3) { row -> m[row * 5] * r + m[row * 5 + 1] * g + m[row * 5 + 2] * b + m[row * 5 + 4] }
        val greys = listOf(apply(1f, 0f, 0f), apply(0f, 1f, 0f), apply(0f, 0f, 1f))
        for (px in greys) { assertEquals(px[0], px[1], 1e-4f); assertEquals(px[1], px[2], 1e-4f) }
        assertEquals(3, greys.map { it[0] }.distinct().size)
        assertTrue(greys.all { it[0] < 0.99f })                      // not white
        val src = popupSource()
        assertTrue("no white tint on the app icons", !src.contains("ColorFilter.tint(Color.White)"))
    }

    @Test
    fun `earbuds row is a card overlay at the bottom left with a label even before the route resolves`() {
        assertEquals("Audio output", MusicBadgeLayout.routeLabel(""))
        assertEquals("soundcore Liberty 4 Pro", MusicBadgeLayout.routeLabel("soundcore Liberty 4 Pro"))
        val src = popupSource()
        assertTrue(src.contains("Modifier.align(Alignment.BottomStart)"))
    }

    @Test
    fun `progress-line waves are the Dynamic Island's own IslandWave`() {
        val src = popupSource()
        assertTrue(src.contains("ui.IslandWave(") && src.contains("val wavesRunning = snap.playing") && src.contains("running = wavesRunning"))
        assertTrue(!src.contains("ChannelWavesView("))
    }

    @Test
    fun `the progress line is the waves - no slider rectangle - and tap or drag seeks`() {
        val src = popupSource()
        assertTrue("no Slider (track/thumb rectangle) anywhere in the badge", !src.contains("Slider("))
        assertTrue(src.contains("detectTapGestures") && src.contains("detectHorizontalDragGestures"))
        assertTrue(src.contains("MusicBadgeLayout.UNPLAYED_ALPHA") && src.contains("clipRect(right = size.width * shown)"))
        assertEquals(0f, MusicBadgeLayout.seekFraction(-5f, 100f), 0f)
        assertEquals(0.25f, MusicBadgeLayout.seekFraction(25f, 100f), 0f)
        assertEquals(1f, MusicBadgeLayout.seekFraction(500f, 100f), 0f)
        assertEquals(0f, MusicBadgeLayout.seekFraction(10f, 0f), 0f)
        assertTrue(MusicBadgeLayout.UNPLAYED_ALPHA in 0.1f..0.6f)
    }

    private fun popupSource(): String = listOf("src/main/java", "app/src/main/java")
        .map { File(it, "com/diegonmarcos/superapp/media/MusicControlsPopup.kt") }.first { it.exists() }.readText()
}
