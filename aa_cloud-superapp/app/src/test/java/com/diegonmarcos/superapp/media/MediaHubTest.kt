package com.diegonmarcos.superapp.media

import android.app.Application
import android.view.KeyEvent
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1635 - the Media Island controls and the persistent Media badge drive the session through ONE layer (MediaHub).
 *
 *   H1 controls dispatch to the right session: the picked one, else a playing Tidal / Spotify, else any playing one
 *   H2 the #1648 held-source rule is kept: a paused Telegram / browser session is never the target, a paused Spotify is
 *   H3 routing: a live controller takes every action; without one Play resumes the last session, Next / Prev go out as a
 *      system media key, Seek needs a session
 *   H4 media key codes per action
 *   H5 the persistent Media entry: the state flow is always available, with a reason when nothing can be controlled
 *   H6 never crash without notification-listener access: send / query / the card all survive, the card shows the reason
 *   H7 the idle card is unchanged when there is only one / no session (no switcher row)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MediaHubTest : KitPageHarness() {

    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private data class S(val key: String, val pkg: String, val playing: Boolean)
    private fun pick(list: List<S>, selected: String? = null) =
        MediaHub.pickTarget(list, { it.key }, { it.pkg }, { it.playing }, selected)

    @Before fun clean() = MediaHub.reset()
    @After fun after() = MediaHub.reset()

    @Test fun `H1 controls dispatch to the right session`() {
        val tg = S("tg", "org.telegram.messenger", true)
        val sp = S("sp", "com.spotify.music", true)
        val td = S("td", "com.aspiro.tidal", false)
        assertEquals(sp, pick(listOf(tg, sp, td)))                 // playing held source beats playing Telegram
        assertEquals(td, pick(listOf(tg, sp, td), selected = "td")) // the user's pick wins while it exists
        assertEquals(sp, pick(listOf(tg, sp), selected = "gone"))   // a vanished pick falls back
        assertEquals(tg, pick(listOf(tg, td)))                      // the island lights for a playing Telegram: drive it
    }

    @Test fun `H2 held-source rule kept for a paused fallback`() {
        val tg = S("tg", "org.telegram.messenger", false)
        val chrome = S("ch", "com.android.chrome", false)
        val sp = S("sp", "com.spotify.music", false)
        assertNull(pick(listOf(tg, chrome)))
        assertEquals(sp, pick(listOf(tg, chrome, sp)))
        assertEquals(setOf("com.aspiro.tidal", "com.spotify.music"), LastMediaSession.HELD_SOURCES)
    }

    @Test fun `H3 routing`() {
        for (a in MediaHub.Action.values()) assertEquals(MediaHub.Route.CONTROLLER, MediaHub.route(a, true, false))
        assertEquals(MediaHub.Route.RESUME_LAST, MediaHub.route(MediaHub.Action.PLAY_PAUSE, false, true))
        assertEquals(MediaHub.Route.MEDIA_KEY, MediaHub.route(MediaHub.Action.PLAY_PAUSE, false, false))
        assertEquals(MediaHub.Route.MEDIA_KEY, MediaHub.route(MediaHub.Action.NEXT, false, true))
        assertEquals(MediaHub.Route.MEDIA_KEY, MediaHub.route(MediaHub.Action.PREV, false, false))
        assertEquals(MediaHub.Route.NONE, MediaHub.route(MediaHub.Action.SEEK, false, true))
        assertEquals(MediaHub.Route.LAUNCH, MediaHub.route(MediaHub.Action.OPEN, false, true))
        assertEquals(MediaHub.Route.NONE, MediaHub.route(MediaHub.Action.OPEN, false, false))
    }

    @Test fun `H4 media key codes`() {
        assertEquals(KeyEvent.KEYCODE_MEDIA_NEXT, MediaHub.keyCode(MediaHub.Action.NEXT))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PREVIOUS, MediaHub.keyCode(MediaHub.Action.PREV))
        assertEquals(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, MediaHub.keyCode(MediaHub.Action.PLAY_PAUSE))
    }

    @Test fun `H5 persistent media entry state is always available`() {
        assertEquals(MediaHub.Access.UNKNOWN, MediaHub.state.value.access)
        assertNull(MediaHub.publish(emptyList()))
        assertEquals(MediaHub.Access.GRANTED, MediaHub.state.value.access)
        assertEquals("No media session is active", MediaHub.state.value.reason)
        MediaHub.publishAccessMissing()
        assertNotNull(MediaHub.state.value.reason)
        assertNull(MediaHub.state.value.target)
    }

    @Test fun `H6 never crash without listener access`() {
        MediaHub.publishAccessMissing()
        for (a in MediaHub.Action.values()) MediaHub.send(ctx, a)
        MediaHub.query(ctx)
        MediaHub.listenerEnabled(ctx)
        showKit { MusicControlsCard(ctx, null, null) {} }
        compose.onNodeWithTag("music:access").assertExists()
        compose.onNodeWithTag("music:play").assertExists()
    }

    @Test fun `H7 no switcher with no sessions`() {
        showKit { MusicControlsCard(ctx, null, null) {} }
        compose.onNodeWithText("Nothing playing").assertExists()
        compose.onNodeWithTag("music:sessions").assertDoesNotExist()
        compose.onNodeWithTag("music:access").assertDoesNotExist()
    }
}
