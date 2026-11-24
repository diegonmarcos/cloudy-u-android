package com.diegonmarcos.superapp.media

import android.app.Application
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.network.BtLinks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1894 the idle Media badge: model from the persisted state, the persistence round trip, the playing badge unchanged. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class IdleMediaBadgeTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val buds = BtLinks.Link("AA:BB", "Galaxy Buds2 Pro", listOf("A2DP"), BtLinks.Battery(left = 80, right = 75, case = 40))

    @Test fun `every change while playing is persisted and survives a reload`() {
        val art = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        LastMediaSession.write(ctx, "com.aspiro.tidal", "Song", "Artist", 31_000, 200_000, art, 1_700_000_000_000)
        // a fresh read straight from storage, as after the process is killed or the phone reboots
        val l = LastMediaSession.load(ctx)
        assertNotNull(l)
        assertEquals("com.aspiro.tidal", l!!.pkg); assertEquals("Song", l.title); assertEquals("Artist", l.artist)
        assertEquals(31_000, l.positionMs); assertEquals(200_000, l.durationMs); assertEquals(1_700_000_000_000, l.savedAt)
        assertNotNull(l.art)
        LastMediaSession.write(ctx, "com.spotify.music", "Other", "B", 5_000, 100_000, null, 1_700_000_100_000)
        assertEquals("Other", LastMediaSession.load(ctx)!!.title)
        assertEquals(1_700_000_100_000, LastMediaSession.load(ctx)!!.savedAt)
    }

    @Test fun `the idle model shows the last track, the buds and the three actions`() {
        LastMediaSession.write(ctx, "com.aspiro.tidal", "Song", "Artist", 1_000, 2_000, null, 1L)
        val m = IdleMediaBadge.model(LastMediaSession.load(ctx), listOf(buds))
        assertEquals("Song", m.headline); assertEquals("Artist", m.sub)
        assertEquals(listOf(IdleMediaBadge.Button.BUDS, IdleMediaBadge.Button.OPEN_PLAYER, IdleMediaBadge.Button.PLAY_LAST), m.buttons)
        assertEquals("com.aspiro.tidal", m.playerPkg); assertTrue(m.playEnabled)
        assertTrue(m.buds.connected)
        assertEquals("Galaxy Buds2 Pro · connected · L 80% · R 75% · Case 40%", IdleMediaBadge.budsLine(m.buds))
    }

    @Test fun `no buds connected and nothing played still gives a complete badge`() {
        val m = IdleMediaBadge.model(null, emptyList())
        assertFalse(m.buds.connected)
        assertEquals("Buds · not connected", IdleMediaBadge.budsLine(m.buds))
        assertEquals("Nothing played yet", m.sub)
        assertEquals(3, m.buttons.size)
        assertFalse("Play needs a last track", m.playEnabled)
        assertNull(m.playerPkg)
        assertEquals("Soundcore · not connected", IdleMediaBadge.budsLine(IdleMediaBadge.model(null, emptyList(), "Soundcore").buds))
    }

    @Test fun `a plain phone accessory is not taken for the buds`() {
        val car = BtLinks.Link("CC", "Car kit", listOf("Headset"), BtLinks.Battery(main = 90))
        assertNull(IdleMediaBadge.pickBuds(listOf(car)))
        assertEquals(buds, IdleMediaBadge.pickBuds(listOf(car, buds)))
    }

    @Test fun `the buttons carry the labels the badge draws`() {
        assertEquals(listOf("Buds", "Player", "Play"), IdleMediaBadge.Button.values().map { IdleMediaBadge.label(it) })
    }
}
