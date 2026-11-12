package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.floatingnav.FloatingNavConfig
import com.diegonmarcos.superapp.floatingnav.FloatingNavService
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.notificationcenter.BadgeCardData
import com.diegonmarcos.superapp.notificationcenter.BadgeDeclaration
import com.diegonmarcos.superapp.notificationcenter.BadgeModel
import com.diegonmarcos.superapp.notificationcenter.KdeStatusNotifier
import com.diegonmarcos.superapp.notificationcenter.WeatherFace
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Parity: for EVERY badge the Notify card carries the notification's actions (icon, label, intent) and info (title, text,
 * sub text). The notification is posted on the badge's own channel and the card is read with the production
 * [BadgeCardData.read] (no fixture): there is one declaration per badge, so the two cannot differ. KDE and Quick
 * Actions are posted by their real builders; the other badges' builders are bound to a running service, so their
 * actions go through the same [BadgeModel.add] the services call (the source guard in NotifyCardGuardTest keeps them
 * from calling `addAction` any other way).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class NotifyCardParityTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<Application>()
    private fun nm() = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    @Before fun setUp() { BadgeCardData.override = null; nm().cancelAll() }
    @After fun tearDown() { nm().cancelAll() }

    private fun pi(code: Int) = PendingIntent.getActivity(ctx, code, Intent("act$code"), PendingIntent.FLAG_IMMUTABLE)

    private fun generic(b: BadgeDeclaration.Badge, acts: List<BadgeModel.Action>): Notification =
        BadgeModel.add(NotificationCompat.Builder(ctx, b.channel).setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("T ${b.id}").setContentText("X ${b.id}").setSubText("S ${b.id}"), acts).build()

    /** What each badge's notification carries; real builders where they run in a unit test. */
    private fun notificationOf(b: BadgeDeclaration.Badge): Notification = when (b.id) {
        "kde_status" -> { KdeConnectManager.init(ctx); KdeStatusNotifier.build(ctx) }
        "floating_nav_quick_actions" -> FloatingNavService.quickActionsNotification(ctx, FloatingNavConfig.get())
        "weather_today" -> generic(b, WeatherFace.Action.values().map {
            BadgeModel.Action(it.name.lowercase(), WeatherFace.iconFor(it), it.name, pi(it.ordinal)) })
        "media_now_playing" -> generic(b, listOf(
            BadgeModel.Action("prev", android.R.drawable.ic_media_previous, "Prev", pi(1)),
            BadgeModel.Action("play", android.R.drawable.ic_media_play, "Play", pi(2)),
            BadgeModel.Action("next", android.R.drawable.ic_media_next, "Next", pi(3))))
        "network_mesh" -> generic(b, listOf(
            BadgeModel.Action("always_on", R.drawable.ic_act_shield, "Always-on VPN", pi(1)),
            BadgeModel.Action("toggle", R.drawable.ic_act_power, "Connect", pi(2)),
            BadgeModel.Action("more", R.drawable.ic_act_more, "More", pi(3))))
        "network_data" -> generic(b, listOf(
            BadgeModel.Action("grant", R.drawable.ic_act_key, "Grant", pi(1)),
            BadgeModel.Action("refresh", R.drawable.ic_act_refresh, "Refresh", pi(2)),
            BadgeModel.Action("open", R.drawable.ic_act_open, "Open", pi(3))))
        "markets_prices" -> generic(b, b.instruments.take(4).mapIndexed { i, ins ->
            BadgeModel.Action(BadgeModel.idOf(ins.label), R.drawable.ic_p_markets_prices, ins.label, pi(i)) })
        "myhealth" -> generic(b, listOf(BadgeModel.Action("grant-access", R.drawable.ic_act_key, "Grant access", pi(1))))
        "ai_usage" -> generic(b, listOf(BadgeModel.Action("refresh", R.drawable.ic_act_refresh, "Refresh", pi(1))))
        "battery_status", "fleet_alerts" -> generic(b, emptyList())
        else -> throw AssertionError("badge '${b.id}' has no notification in NotifyCardParityTest: add it, so its card is proven equal")
    }

    /**
     * #4 — the owner's redesigned order (2026-11-08): Me Infos (Weather,
     * Health, Fin Terminal Markets), Media, Device Infos (Mesh, Data, Battery,
     * AI), then Actions (Quickmarks, KDE). The shipped build.json's groups and
     * `card.rank` must render EXACTLY this on the Notify page, deterministically
     * — never map/hash/arbitrary order. Guards the render against a rank or
     * member-order regression.
     */
    @Test
    fun `the notify page renders badges in the owner's declared order`() {
        assertEquals(
            listOf(
                // #1672 owner order: Media, Me Infos, Device Infos, Actions
                "media_now_playing",
                "weather_today", "myhealth", "markets_prices",
                "network_mesh", "network_data", "battery_status", "ai_usage",
                "floating_nav_quick_actions", "kde_status",
            ),
            orderedBadges(ctx).map { it.id },
        )
    }

    @Test
    fun `every badge card carries the actions and info of its notification`() {
        val badges = orderedBadges(ctx)
        assertTrue("no badges declared", badges.size >= 10)
        for (b in badges) {
            nm().cancelAll()
            val n = notificationOf(b)
            nm().notify(0x7100 + b.id.hashCode().and(0xff), n)
            val card = BadgeCardData.read(ctx, b)
            val want = BadgeModel.of(n)
            assertEquals("${b.id}: action count", n.actions?.size ?: 0, want.size)
            assertEquals("${b.id}: card actions == notification actions", want, card.actions)
            for (a in card.actions) {
                assertTrue("${b.id}/${a.id}: no icon", a.icon != 0)
                assertTrue("${b.id}/${a.id}: icon is not a drawable", ctx.resources.getResourceTypeName(a.icon) == "drawable")
                assertTrue("${b.id}/${a.id}: no label", a.label.isNotBlank())
            }
            val ex = n.extras
            assertEquals("${b.id}: sub text", ex.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty(), card.subText)
            if (b.card.kind == "text" || b.card.kind == "media") {
                assertEquals("${b.id}: title", ex.getCharSequence(Notification.EXTRA_TITLE).toString(), card.headline)
                assertEquals("${b.id}: text", ex.getCharSequence(Notification.EXTRA_TEXT).toString(), card.sub)
            }
        }
    }

    @Test
    fun `Quick Actions shows five icon buttons and KDE its five and its info`() {
        val qa = orderedBadges(ctx).first { it.id == "floating_nav_quick_actions" }
        nm().notify(1, FloatingNavService.quickActionsNotification(ctx, FloatingNavConfig.get()))
        assertEquals(5, BadgeCardData.read(ctx, qa).actions.size)
        val kde = orderedBadges(ctx).first { it.id == "kde_status" }
        KdeConnectManager.init(ctx)
        nm().notify(2, KdeStatusNotifier.build(ctx))
        val d = BadgeCardData.read(ctx, kde)
        assertEquals(listOf("share", "desktop", "ping", "connect", "find"), d.actions.map { it.id })
        assertTrue("KDE info line (plugins/paired/identity) missing: '${d.subText}'", d.subText.contains("plugins"))
        assertTrue(d.headline.startsWith("Cloud SA - KDE"))
    }
}
