package com.diegonmarcos.superapp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1648: the held now-playing card binds only to Tidal / Spotify; the open-all-players icons are untouched. */
class HeldMediaSourceTest {
    private fun pick(vararg s: Pair<String, Boolean>) = LastMediaSession.pickHeld(s.toList(), { it.first }, { it.second })?.first

    @Test fun `only Tidal and Spotify are held sources`() {
        assertTrue(LastMediaSession.isHeldSource("com.aspiro.tidal"))
        assertTrue(LastMediaSession.isHeldSource("com.spotify.music"))
        assertEquals(setOf("com.aspiro.tidal", "com.spotify.music"), LastMediaSession.HELD_SOURCES)
    }

    @Test fun `Telegram and browsers do not populate the card`() {
        for (p in listOf("org.telegram.messenger", "org.telegram.messenger.web", "org.thunderstorm.messenger", "com.android.chrome",
            "org.mozilla.firefox", "com.sec.android.app.sbrowser", "com.brave.browser", "com.google.android.youtube", null))
            assertFalse("$p", LastMediaSession.isHeldSource(p))
        assertNull(pick("org.telegram.messenger" to true))
        assertNull(pick("com.android.chrome" to true, "org.telegram.messenger" to true))
    }

    @Test fun `with several sessions the playing Tidal or Spotify one wins over a playing Telegram or browser`() {
        assertEquals("com.spotify.music", pick("org.telegram.messenger" to true, "com.android.chrome" to true, "com.spotify.music" to false))
        assertEquals("com.aspiro.tidal", pick("com.spotify.music" to false, "org.telegram.messenger" to true, "com.aspiro.tidal" to true))
    }

    @Test fun `the open-all-players icon row still lists every player`() {
        val pk = MusicControlsPopup.IDLE_LAUNCH_PKGS
        assertTrue(pk.containsAll(listOf("com.aspiro.tidal", "com.spotify.music", "com.google.android.apps.youtube.music",
            "com.google.android.youtube", "com.vimeo.android.videoapp")))
    }

    @Test fun `a stored session from a non-held app is dropped on load`() {
        val src = java.io.File("src/main/java/com/diegonmarcos/superapp/media/LastMediaSession.kt").readText()
        val load = src.substringAfter("fun load(ctx: Context)").substringBefore("fun resume(")
        assertTrue(load.contains("if (!isHeldSource(pkg))") && load.contains("return null"))
    }
}
