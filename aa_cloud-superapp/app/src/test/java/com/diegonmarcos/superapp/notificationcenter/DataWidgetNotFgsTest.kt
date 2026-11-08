package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * Weather, MyHealth and Battery are standing information cards, not keep-alives. Android files an app under "Background
 * activities / running in the background" for a foreground-service notification, so none of these may use that path: no
 * startForeground, no startForegroundService, no foregroundServiceType, and every one is routed through
 * [DataWidgetRuntime] (plain start) and FleetNotify.dataWidgetBuilder (plain notify).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DataWidgetNotFgsTest {

    private val dir = "src/main/java/com/diegonmarcos/superapp/notificationcenter"
    private val sources = mapOf(
        "WeatherBadgeService" to "$dir/WeatherBadgeService.kt",
        "HealthBadgeService" to "$dir/HealthBadgeService.kt",
        "BatteryBadgeService" to "$dir/BatteryBadgeService.kt",
        "DataBadgeService" to "$dir/DataBadgeService.kt",
        "AiBadgeService" to "$dir/AiBadgeService.kt",
        "MarketsBadgeService" to "$dir/MarketsBadgeService.kt",
        "DataWidgetRuntime" to "$dir/DataWidgetRuntime.kt",
        "DataWidgetWakeReceiver" to "$dir/DataWidgetWakeReceiver.kt",
    )

    /** Source with comments removed, so a doc line that names startForeground is not mistaken for a call. */
    private fun code(path: String) = File(path).readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }.joinToString("\n")

    @Test fun noDataWidgetSourceTouchesTheForegroundServicePath() {
        sources.forEach { (name, path) ->
            val c = code(path)
            assertFalse("$name calls startForeground", Regex("""\bstartForeground\s*\(""").containsMatchIn(c))
            assertFalse("$name calls startForegroundService", c.contains("startForegroundService"))
            assertFalse("$name uses ServiceCompat.startForeground / startKeepAlive", c.contains("ServiceCompat") || c.contains("startKeepAlive"))
            assertFalse("$name sets a foreground-service behaviour", c.contains("setForegroundServiceBehavior"))
        }
    }

    @Test fun everyDataWidgetCardIsBuiltByTheSharedDataWidgetBuilder() {
        listOf("WeatherBadgeService", "HealthBadgeService", "BatteryBadgeService", "DataBadgeService", "AiBadgeService", "MarketsBadgeService").forEach {
            val c = code(sources.getValue(it))
            assertTrue("$it does not use FleetNotify.dataWidgetBuilder", c.contains("FleetNotify.dataWidgetBuilder("))
            assertTrue("$it does not post with NotifyGate.postCtx (a plain notify through the gate)", c.contains("NotifyGate.postCtx("))
            assertFalse("$it hand-builds its notification", c.contains("NotificationCompat.Builder("))
        }
    }

    @Test fun theManifestDeclaresNoForegroundTypeForThem() {
        val m = File("src/main/AndroidManifest.xml").readText()
        listOf("WeatherBadgeService", "HealthBadgeService", "BatteryBadgeService", "DataBadgeService", "AiBadgeService", "MarketsBadgeService").forEach { n ->
            val svc = Regex("""<service\b[^>]*notificationcenter\.$n[^>]*?/?>""", RegexOption.DOT_MATCHES_ALL).find(m)!!.value.substringBefore(">")
            assertFalse("$n still declares a foregroundServiceType", svc.contains("foregroundServiceType"))
        }
    }

    @Test fun everyDeclaredWidgetBadgeIsRoutedThroughTheRuntime() {
        assertEquals(setOf("weather_today", "myhealth", "battery_status", "network_data", "ai_usage", "markets_prices"), DataWidgetRuntime.widgets.map { it.badgeId }.toSet())
        DataWidgetRuntime.widgets.forEach { w ->
            val b = BadgeServices.declared.first { it.id == w.badgeId }
            assertEquals(w.cls.name, b.service)
            assertTrue(DataWidgetRuntime.isWidgetService(b.service))
        }
        // The one start path of BadgeServices sends a widget to the plain start.
        val bs = code("$dir/BadgeServices.kt")
        assertTrue(bs.contains("DataWidgetRuntime.isWidgetService(fqcn)) DataWidgetRuntime.start("))
    }

    @Test fun theWidgetServicesNeverGoForeground() {
        listOf(WeatherBadgeService::class.java, HealthBadgeService::class.java, BatteryBadgeService::class.java,
            DataBadgeService::class.java, AiBadgeService::class.java, MarketsBadgeService::class.java).forEach { cls ->
            val svc = Robolectric.buildService(cls).create().get()
            assertNull("${cls.simpleName} went foreground", shadowOf(svc).lastForegroundNotification)
        }
    }

    @Test fun dataAiAndMarketsAreNoLongerStickyAndNeverStartedAsForeground() {
        listOf("DataBadgeService", "AiBadgeService", "MarketsBadgeService").forEach {
            val c = code(sources.getValue(it))
            assertTrue("$it is still START_STICKY", c.contains("START_NOT_STICKY") && !c.contains("START_STICKY"))
            assertTrue("$it does not schedule through DataWidgetRuntime", c.contains("DataWidgetRuntime.scheduleNext("))
        }
        assertTrue(DataWidgetRuntime.isWidgetService(DataBadgeService::class.java.name))
        assertTrue(DataWidgetRuntime.isWidgetService(AiBadgeService::class.java.name))
        assertTrue(DataWidgetRuntime.isWidgetService(MarketsBadgeService::class.java.name))
    }

    @Test fun theTrueKeepAlivesStayForeground() {
        // FloatingNav (overlay), Network (the VPN's only indicator) and KDE (a live TCP link to the desktop) need the process.
        listOf("floatingnav/FloatingNavService.kt", "notificationcenter/NetworkBadgeService.kt", "notificationcenter/KdeStatusService.kt").forEach {
            val c = code("src/main/java/com/diegonmarcos/superapp/$it")
            assertTrue("$it must stay a foreground service", Regex("""\bstartForeground\s*\(""").containsMatchIn(c))
        }
    }

    @Test fun theBatteryCardIsOngoingYetNotAForegroundNotification() {
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        val n = com.diegonmarcos.superapp.core.FleetNotify.dataWidgetNotification(ctx, android.R.drawable.ic_dialog_info, "Battery", listOf("80%"), channelId = BatteryBadgeService.CHANNEL_ID)
        assertTrue(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
        assertEquals(0, n.flags and Notification.FLAG_FOREGROUND_SERVICE)
    }
}
