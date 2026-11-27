package com.diegonmarcos.superapp.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1923 the Media notification actions: the action set, package resolution, absent-app hiding, the last-player choice, with mutants. */
class MediaLaunchersTest {
    private val all = MediaLaunchers.BUDS_PKGS.toSet() + setOf("com.aspiro.tidal", "com.spotify.music")

    private fun ids(installed: (String) -> Boolean, last: String? = "com.aspiro.tidal") =
        MediaLaunchers.visible(installed, last).map { it.first.id }

    @Test fun `the action set is play last, tidal, spotify, buds app, buds in that order with icons`() {
        assertEquals(listOf("play-last", "open-tidal", "open-spotify", "open-buds-app", "open-buds"), MediaLaunchers.ALL.map { it.id })
        assertTrue(MediaLaunchers.ALL.all { it.icon != 0 })
        assertEquals(listOf("com.aspiro.tidal"), MediaLaunchers.ALL[1].packages)
        assertEquals(listOf("com.spotify.music"), MediaLaunchers.ALL[2].packages)
        assertEquals("com.samsung.android.app.watchmanager", MediaLaunchers.BUDS_PKGS.first())
        assertEquals(4, ids({ it in all }).size)
        assertEquals(listOf(0, 1, 2), MediaLaunchers.compact(4).toList())
    }

    @Test fun `packages resolve at runtime and the first installed wins`() {
        val buds = MediaLaunchers.ALL.last()
        assertEquals("com.samsung.android.app.watchmanager", MediaLaunchers.resolve(buds) { it in all })
        assertEquals("com.samsung.accessory.berrymgr", MediaLaunchers.resolve(buds) { it == "com.samsung.accessory.berrymgr" })
        assertNull(MediaLaunchers.resolve(buds) { false })
    }

    @Test fun `an absent app is hidden and the others stay`() {
        assertEquals(listOf("play-last", "open-spotify", "open-buds"), ids({ it != "com.aspiro.tidal" }, "com.spotify.music"))
        assertEquals(listOf("play-last", "open-tidal", "open-spotify"), ids({ it in all - MediaLaunchers.BUDS_PKGS.toSet() }, "com.spotify.music"))
        assertEquals(emptyList<String>(), ids({ false }))
        // no known last player: Play last is hidden, never a dead button
        assertEquals(listOf("open-tidal", "open-spotify", "open-buds"), ids({ it in all }, last = null))
        // the last player itself uninstalled: Play last hidden
        assertFalse("play-last" in ids({ it != "com.aspiro.tidal" }, last = "com.aspiro.tidal"))
    }

    @Test fun `the last player is the playing one, else the persisted one, else the first session`() {
        assertEquals("com.spotify.music", MediaLaunchers.lastPlayer("com.aspiro.tidal", listOf("com.aspiro.tidal" to false, "com.spotify.music" to true)))
        assertEquals("com.aspiro.tidal", MediaLaunchers.lastPlayer("com.aspiro.tidal", listOf("com.spotify.music" to false)))
        assertEquals("com.spotify.music", MediaLaunchers.lastPlayer(null, listOf("com.spotify.music" to false)))
        assertNull(MediaLaunchers.lastPlayer(null, emptyList()))
    }

    /** Mutants: each broken resolver must fail the same expectations, proving the tests are not vacuous. */
    @Test fun `mutants are caught`() {
        fun ok(vis: (Boolean) -> List<String>) = vis(true) == listOf("play-last", "open-spotify", "open-buds")
        // real
        assertTrue(ok { _ -> ids({ it != "com.aspiro.tidal" }, "com.spotify.music") })
        // mutant 1: ignores the installed check
        assertFalse(ok { _ -> MediaLaunchers.ALL.map { it.id } })
        // mutant 2: lastPlayer prefers the persisted package over the playing one
        val mutant = { p: String?, s: List<Pair<String, Boolean>> -> p ?: s.firstOrNull { it.second }?.first }
        val s = listOf("com.aspiro.tidal" to false, "com.spotify.music" to true)
        assertEquals("com.spotify.music", MediaLaunchers.lastPlayer("com.aspiro.tidal", s))
        assertFalse(mutant("com.aspiro.tidal", s) == MediaLaunchers.lastPlayer("com.aspiro.tidal", s))
    }
}
