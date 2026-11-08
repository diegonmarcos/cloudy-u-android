package com.diegonmarcos.superapp.media

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LastMediaSessionTest {
    @Test fun `a playing session advances with elapsed time, a paused one holds`() {
        assertEquals(12_000L, LastMediaSession.positionAt(10_000, 1_000, 1f, true, 3_000))
        assertEquals(14_000L, LastMediaSession.positionAt(10_000, 1_000, 2f, true, 3_000))
        assertEquals(10_000L, LastMediaSession.positionAt(10_000, 1_000, 1f, false, 9_000))
    }

    @Test fun `fraction is position over duration, clamped, zero without a duration`() {
        val a = LastMediaSession.Last("p", "t", "a", 30_000, 120_000, null)
        assertEquals(0.25f, a.fraction, 0.0001f)
        assertEquals(0f, a.copy(durationMs = 0).fraction, 0f)
        assertEquals(1f, a.copy(positionMs = 999_999).fraction, 0f)
    }

    /**
     * #3 — the last song is held for ANY media app, not an allowlist of two.
     * A non-Spotify / non-Tidal package is persisted and read back, and one tap
     * on Play resumes it by sending a MEDIA_PLAY button to that exact package —
     * the default path, with no per-app special-casing.
     */
    @Test fun `resume replays the last session for any package, not just Spotify or Tidal`() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val pkg = "com.example.genericplayer"
        ctx.getSharedPreferences("last_media_session", Context.MODE_PRIVATE).edit()
            .putString("pkg", pkg).putString("title", "Some Song").putString("artist", "Some Artist")
            .putLong("pos", 1_000).putLong("dur", 200_000).apply()

        val last = LastMediaSession.load(ctx)
        assertEquals("the stored package is read back whatever it is", pkg, last?.pkg)

        val resumed = LastMediaSession.resume(ctx)
        assertTrue("resume dispatched a play for the arbitrary package", resumed)
        val sent = shadowOf(ctx).broadcastIntents.filter { it.action == Intent.ACTION_MEDIA_BUTTON }
        assertTrue("a MEDIA_BUTTON broadcast was sent", sent.isNotEmpty())
        assertEquals("the media button targets the stored package", pkg, sent.last().`package`)
    }
}
