package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.core.FleetAlerts
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowContentProvider

/**
 * #777 — the four shade groups and the fleet Alerts channel, against the REAL
 * declaration baked into this build (BuildConfig.UI_NOTIFICATION_CENTER_B64),
 * the real FleetAlerts client (libs:core) and the real collector provider.
 *
 * Cross-app: the raise goes ContentResolver → the manifest-declared
 * FleetAlertsProvider, with the calling package set to a FOREIGN app, which is
 * exactly what Android hands the provider when another fleet app calls it.
 * Robolectric instantiates that provider lazily, on the call — the in-JVM
 * analogue of Android starting the stopped SuperApp process for it; the
 * manifest test pins the attributes that make that happen on a device
 * (exported, signature-permission guarded, the authority callers name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyGroupsAlertsTest {

    private lateinit var ctx: Application
    private lateinit var nm: NotificationManager
    private var svc: ServiceController<FloatingNavService>? = null

    private val raiser = "org.example.fleet.raiser"

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        for (p in listOf("fleet_alerts", "notify_groups", "badge_customization", "floatingnav_prefs"))
            ctx.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
        nm = ctx.getSystemService(NotificationManager::class.java)
        nm.cancelAll()
    }

    @After fun tearDown() { svc?.destroy() }

    private fun group(id: String) = NotifyGroups.declared.first { it.id == id }
    private fun posted(): List<Notification> = shadowOf(nm).allNotifications
    private fun isSummary(n: Notification) = n.flags and Notification.FLAG_GROUP_SUMMARY != 0

    /** The collector as a FOREIGN fleet app reaches it. */
    private fun collectorCalledBy(pkg: String) {
        val p = ctx.contentResolver.acquireContentProviderClient(FleetAlerts.AUTHORITY)!!.localContentProvider!!
        Shadow.extract<ShadowContentProvider>(p).setCallingPackage(pkg)
    }

    // ── the groups ──────────────────────────────────────────────────────

    @Test fun `exactly the declared groups, in the brief's order and membership`() {
        assertEquals(listOf("live", "media", "network", "actions"), NotifyGroups.declared.map { it.id })
        assertTrue("#894 no Store group or badge: Store notifications are Cloud Store's",
            NotifyGroups.declared.none { it.id == "store" } && BadgeServices.declared.none { it.id == "store_updates" })
        assertEquals(listOf("weather_today", "myhealth", "markets_prices"), group("live").members)
        assertEquals(listOf("floating_nav_quick_actions", "kde_status"), group("actions").members)
        assertEquals(listOf("media_now_playing"), group("media").members)
        assertEquals(listOf(NetworkBadgeService.BADGE_ID, DataBadgeService.BADGE_ID, BatteryBadgeService.BADGE_ID, AiBadgeService.BADGE_ID), group("network").members)
        assertTrue("Alerts is removed: no alerts group, no fleet_alerts badge",
            NotifyGroups.declared.none { it.alerts || it.id == "alerts" } && BadgeDeclaration.badges(BadgeServices.declared).none { it.id == AlertsNotifier.BADGE_ID })
        // Every member is a declared badge — a typo would be a member nothing posts.
        val badges = BadgeDeclaration.badges(BadgeServices.declared).map { it.id }.toSet()
        for (g in NotifyGroups.declared) for (m in g.members) assertTrue("$m in ${g.id}", m in badges)
    }

    @Test fun `attach gives each member its group's key and posts that group's summary first`() {
        for (g in NotifyGroups.declared.filterNot { it.alerts }) for (m in g.members) {
            val n = NotifyGroups.attach(ctx, NotificationCompat.Builder(ctx, "x").setSmallIcon(1), m).build()
            assertEquals("$m → ${g.id}", NotifyGroups.key(g), n.group)
            assertTrue("summary of ${g.id} posted",
                posted().any { isSummary(it) && it.group == NotifyGroups.key(g) })
        }
        // Control: a producer in no group is left alone.
        val loose = NotifyGroups.attach(ctx, NotificationCompat.Builder(ctx, "x").setSmallIcon(1), "nope").build()
        assertNull(loose.group)
    }

    @Test fun `a real producer posts into its group - Quick Actions lands in G2`() {
        svc = Robolectric.buildService(FloatingNavService::class.java).create().startCommand(0, 1)
        shadowOf(Looper.getMainLooper()).idle()
        val quick = shadowOf(svc!!.get()).lastForegroundNotification
        assertNotNull("FloatingNavService posted its Quick Actions badge", quick)
        assertEquals("floating_nav", quick!!.channelId)
        assertEquals(NotifyGroups.key(group("actions")), quick.group)
        assertTrue(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("actions")) })
    }

    @Test fun `a group switched off switches its members off and takes its summary down`() {
        val markets = BadgeServices.declared.first { it.id == "markets_prices" }
        assertTrue("control: on by default", BadgeCustomization.isEnabled(ctx, markets))
        NotifyGroups.postSummary(ctx, group("live"))
        assertTrue(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("live")) })

        NotifyGroups.setEnabled(ctx, group("live"), false)
        assertFalse(BadgeCustomization.isEnabled(ctx, markets))
        assertFalse("its service is no longer wanted",
            BadgeServices.wanted(ctx, MarketsBadgeService::class.java.name))
        assertFalse(posted().any { isSummary(it) && it.group == NotifyGroups.key(group("live")) })
        // Other groups untouched.
        val kde = BadgeServices.declared.first { it.id == "kde_status" }
        assertTrue(BadgeCustomization.isEnabled(ctx, kde))
    }

    @Test fun `order is the owner's and survives a re-read`() {
        NotifyGroups.move(ctx, group("actions"), -3)
        assertEquals(listOf("actions", "live", "media", "network"), NotifyGroups.ordered(ctx).map { it.id })
        NotifyGroups.move(ctx, group("live"), +1)
        assertEquals(listOf("actions", "media", "live", "network"), NotifyGroups.ordered(ctx).map { it.id })
    }

    // ── mock data is gone ───────────────────────────────────────────────

    @Test fun `no sample alerts - an empty store posts nothing and the mock feed is not baked`() {
        assertTrue(AlertsNotifier.refresh(ctx).isEmpty())
        assertTrue(posted().none { it.group == NotifyGroups.key(group("alerts")) })
        assertFalse("UI_NOTIFICATION_INFOS_B64 (the sample feed) must not be in BuildConfig",
            BuildConfig::class.java.fields.any { it.name == "UI_NOTIFICATION_INFOS_B64" })
    }

    // ── cross-app delivery ──────────────────────────────────────────────

    // ── the contract other apps depend on ───────────────────────────────

    @Test fun `the collector is exported, signature-guarded, and at the authority FleetAlerts names`() {
        val info = ctx.packageManager.resolveContentProvider(FleetAlerts.AUTHORITY, 0)
        assertNotNull("no provider at ${FleetAlerts.AUTHORITY}", info)
        assertEquals(FleetAlertsProvider::class.java.name, info!!.name)
        assertTrue(info.exported)
        val perm = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"
        assertEquals(perm, info.readPermission)
        assertEquals(perm, info.writePermission)
        assertEquals(FleetAlerts.FALLBACK_CHANNEL, AlertsNotifier.CHANNEL_ID)
    }

    @Test fun `a provider call with no calling package is refused`() {
        val p = FleetAlertsProvider()
        // Not attached: no context, no caller → nothing stored, no crash.
        assertNull(p.call(FleetAlerts.METHOD_RAISE, null, FleetAlerts.Alert("x").toBundle()))
        assertTrue(AlertStore.all(ctx).isEmpty())
    }
}
