package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.WeatherBadgeService
import com.diegonmarcos.superapp.notificationcenter.WeatherFace
import com.diegonmarcos.superapp.notificationcenter.WeatherFixStore
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation
import com.diegonmarcos.superapp.notificationcenter.WeatherModel
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * The Weather card with a SEEDED location: a fix is persisted to the real [WeatherFixStore], read back the way the
 * service reads it on a restart, and drawn as the Notify page card (the real BadgeCard composable) from the real
 * [WeatherFace]. Second frame: no fix at all - the card names the missing permission and offers the button.
 * Evidence: app/build/configs-screens/weather-card-seeded.png and weather-card-nofix.png.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-night-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeatherCardScreenTest : KitPageHarness() {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()

    private fun body(): String {
        fun e(t: String, c: Double, p: Int) = """{"time":"$t","data":{"instant":{"details":{"air_temperature":$c}},"next_1_hours":{"details":{"probability_of_precipitation":$p}}}}"""
        return """{"properties":{"timeseries":[${listOf(
            e("2026-10-22T12:00:00Z", 18.2, 20), e("2026-10-22T15:00:00Z", 21.0, 10), e("2026-10-22T18:00:00Z", 19.0, 5),
            e("2026-10-22T21:00:00Z", 14.0, 0)).joinToString(",")}]}}"""
    }

    private fun data(state: WeatherFace.State): BadgeCardData.Data {
        val b = com.diegonmarcos.superapp.launcher.orderedBadges(ctx).first { it.id == WeatherBadgeService.BADGE_ID }
        val f = WeatherFace.of(ctx, state)
        val lines = if (state is WeatherFace.State.Forecast)
            state.today.forecast.hours.filter { it.first % 3 == 0 }.map { BadgeCardData.Line("%02dh".format(it.first), "%.0f°".format(it.second)) }
        else emptyList()
        val acts = f.action?.let { a ->
            val i = WeatherFace.intentFor(ctx, a)
            val pi = if (a == WeatherFace.Action.RETRY) android.app.PendingIntent.getService(ctx, 3, i, android.app.PendingIntent.FLAG_IMMUTABLE)
            else android.app.PendingIntent.getActivity(ctx, 1, i, android.app.PendingIntent.FLAG_IMMUTABLE)
            listOf(com.diegonmarcos.superapp.notificationcenter.BadgeModel.Action(a.name.lowercase(), WeatherFace.iconFor(a), f.actionLabel.orEmpty(), pi))
        }.orEmpty()
        return BadgeCardData.Data(b, b.label, f.title, f.text, null, lines, true, "", System.currentTimeMillis(), null, acts)
    }

    @Test
    fun `render the Weather card with a seeded location and without one`() {
        // Seed, then read back through a NEW store: what the service sees after the process was killed.
        val seeded = WeatherLocation.Fix(40.4168, -3.7038, 30f, System.currentTimeMillis() - 20 * 60_000L, "network")
        WeatherFixStore(ctx).save(seeded)
        WeatherFixStore(ctx).saveForecast(WeatherFixStore.Cached("Madrid", seeded.lat, seeded.lon, body(), System.currentTimeMillis()))
        val fix = WeatherFixStore(ctx).load()!!
        assertEquals(seeded, fix)
        val c = WeatherFixStore(ctx).loadForecast()!!
        val f = WeatherModel.parse(c.body, Instant.parse("2026-10-22T12:20:00Z"), ZoneId.of("UTC"))!!
        val withFix = data(WeatherFace.State.Forecast(WeatherBadgeService.WeatherToday(c.place, c.lat, c.lon, f), fix))
        assertEquals("18° · 💧20% — Madrid", withFix.headline)

        showKit { Column(androidx.compose.ui.Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCard(withFix) } }
        compose.onNodeWithTag(BadgeCardTags.card(WeatherBadgeService.BADGE_ID)).assertExists()
        snap("weather-card-seeded.png")

        val none = data(WeatherFace.State.NoLocation(WeatherLocation.Reason.PERMISSION))
        showKit { Column(androidx.compose.ui.Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCard(none) } }
        compose.onNodeWithTag(BadgeCardTags.card(WeatherBadgeService.BADGE_ID)).assertExists()
        compose.onNodeWithTag(BadgeCardTags.action(WeatherBadgeService.BADGE_ID, "app_settings")).assertExists().assertHasClickAction()
        assertEquals(androidx.compose.ui.semantics.Role.Button,
            compose.onNodeWithTag(BadgeCardTags.action(WeatherBadgeService.BADGE_ID, "app_settings")).fetchSemanticsNode()
                .config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Role))
        // The button's PendingIntent opens Configs > Perms on the location rows (#1515: the grant lives there).
        val saved = org.robolectric.Shadows.shadowOf(none.actions.single().intent).savedIntents.single()
        assertEquals(com.diegonmarcos.superapp.perms.PermsActivity::class.java.name, saved.component?.className)
        snap("weather-card-nofix.png")
        // A seeded card has no button at all.
        showKit { Column(androidx.compose.ui.Modifier.fillMaxSize().background(NotifCard.panel).padding(12.dp)) { BadgeCard(withFix) } }
        compose.onNodeWithTag(BadgeCardTags.action(WeatherBadgeService.BADGE_ID, "app_settings")).assertDoesNotExist()
    }

    private fun snap(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/configs-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
