package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.WeatherFace.Action
import com.diegonmarcos.superapp.notificationcenter.WeatherFace.State
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Fix
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The card: a known position always shows a forecast (or says it is loading it); no position names the missing piece. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WeatherFaceTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private val fix = Fix(40.4168, -3.7038, 30f, 1_700_000_000_000L, "network")
    private val today = WeatherBadgeService.WeatherToday("Madrid", 40.4168, -3.7038,
        WeatherModel.Forecast(18.2, 22.0, 11.0, 20, listOf(15 to 21.0)))

    @Test fun forecastStateShowsTheForecastWithNoButton() {
        val f = WeatherFace.of(ctx, State.Forecast(today, fix))
        assertEquals("18° · 💧20% — Madrid", f.title)
        assertTrue(f.text.contains("22") && f.text.contains("11"))
        assertNull(f.action)
    }

    @Test fun aKnownPositionNeverShowsTheOldWaitingText() {
        val f = WeatherFace.of(ctx, State.Loading("40.42, -3.70", fix))
        assertTrue(f.title.contains("40.42"))
        assertFalse(f.text.contains("waiting for a known", ignoreCase = true))
        assertEquals(Action.RETRY, f.action)
    }

    @Test fun noPositionNamesThePermissionOrProviderAndOffersTheFix() {
        fun face(r: Reason) = WeatherFace.of(ctx, State.NoLocation(r))
        face(Reason.PERMISSION).let { assertTrue(it.text.contains("permission")); assertEquals(Action.APP_SETTINGS, it.action) }
        face(Reason.LOCATION_OFF).let { assertTrue(it.text.contains("switched off")); assertEquals(Action.LOCATION_SETTINGS, it.action) }
        face(Reason.BACKGROUND).let { assertTrue(it.text.contains("all the time")); assertEquals(Action.APP_SETTINGS, it.action) }
        face(Reason.NO_FIX).let { assertTrue(it.text.contains("Wi-Fi")); assertEquals(Action.RETRY, it.action) }
        for (r in Reason.values()) {
            val f = face(r)
            assertNotNull(f.action); assertTrue(!f.actionLabel.isNullOrBlank())
            assertFalse(f.text.contains("waiting for a known", ignoreCase = true))
        }
    }

    @Test fun eachButtonOpensTheRightScreen() {
        val app = WeatherFace.intentFor(ctx, Action.APP_SETTINGS)
        // #1515 the location grants live in Configs > Perms: the button opens the Perms page on the location rows.
        assertEquals(com.diegonmarcos.superapp.perms.PermsActivity::class.java.name, app.component?.className)
        assertEquals(listOf("fine_location", "coarse_location", "background_location"),
            app.getStringArrayExtra("com.diegonmarcos.perms.IDS")?.toList())
        assertEquals(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS, WeatherFace.intentFor(ctx, Action.LOCATION_SETTINGS).action)
        assertEquals(WeatherBadgeService::class.java.name, WeatherFace.intentFor(ctx, Action.RETRY).component?.className)
    }

    @Test fun positionLineSaysSourceAndAge() {
        assertEquals("Position: network, 12 min ago", WeatherFace.position(ctx, fix, fix.timeMs + 12 * 60_000L))
    }
}
