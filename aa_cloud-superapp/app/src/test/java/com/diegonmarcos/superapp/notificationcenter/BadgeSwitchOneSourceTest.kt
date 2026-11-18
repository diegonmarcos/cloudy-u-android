package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.configs.BadgePanes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1794 — the Configs switch ("Show this badge"), the status line ("OFF · Switched off below") and the posting decision
 * ([BadgeServices.wanted] / [BadgeServices.switchedOff], which the restart path and the producers obey) come from ONE
 * source, [BadgeCustomization.isEnabled].
 *
 * The bug: isEnabled also ANDed a per-group switch (`notify_groups:<group>.enabled`) whose UI was removed in #1603b. A
 * group left off by an older build kept every member OFF under an ON switch, with no control to fix it. The test seeds
 * exactly that stored state and requires the three answers to agree with the switch, in both directions, for every
 * declared badge (the shipped declaration, never a copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgeSwitchOneSourceTest {

    private lateinit var ctx: Application

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        for (p in listOf("notify_groups", "badge_customization"))
            ctx.getSharedPreferences(p, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun badges() = BadgeDeclaration.badges(BadgeServices.declared)

    /** What the owner sees on the switch, what the status line says, and whether the badge may be posted. */
    private data class Answers(val switch: Boolean, val statusOff: Boolean, val posts: Boolean)

    private fun answers(b: BadgeDeclaration.Badge) = Answers(
        switch = BadgePanes.shown(ctx, b),
        statusOff = BadgeServices.status(ctx, b).state == BadgeServices.State.DISABLED,
        posts = b.service.isBlank() || !BadgeServices.switchedOff(ctx, b.service),
    )

    private fun assertAgree(b: BadgeDeclaration.Badge, on: Boolean) {
        val a = answers(b)
        assertEquals("${b.id}: switch", on, a.switch)
        assertEquals("${b.id}: status line reads OFF exactly when the switch is off", !on, a.statusOff)
        if (on) assertTrue("${b.id}: switch ON yet the posting decision says switched off", a.posts)
    }

    /** The owner's phone: every #777 group switch stored OFF by an older build, every badge switch ON. */
    private fun seedStaleGroupSwitches() {
        val e = ctx.getSharedPreferences("notify_groups", Context.MODE_PRIVATE).edit()
        for (g in NotifyGroups.declared) e.putBoolean("${g.id}.enabled", false)
        e.commit()
        for (b in badges()) BadgeCustomization.setEnabled(ctx, b, true)
    }

    @Test fun `a stale group switch no longer turns an ON badge OFF`() {
        seedStaleGroupSwitches()
        assertTrue("examined no badges", badges().isNotEmpty())
        for (b in badges()) assertAgree(b, on = true)
        for (g in NotifyGroups.declared) assertTrue("${g.id}: derived from members, so ON", NotifyGroups.isEnabled(ctx, g))
    }

    @Test fun `the stale group keys are cleared on the restart path`() {
        seedStaleGroupSwitches()
        BadgeServices.ensureAll(ctx)
        val left = ctx.getSharedPreferences("notify_groups", Context.MODE_PRIVATE).all.keys.filter { it.endsWith(".enabled") }
        assertTrue("stale group switches left: $left", left.isEmpty())
        assertEquals("idempotent", 0, NotifyGroups.clearStaleGroupSwitches(ctx))
    }

    @Test fun `each switch moves all three answers together, both ways`() {
        seedStaleGroupSwitches()
        for (b in badges()) {
            BadgePanes.setShown(ctx, b, false)
            assertAgree(b, on = false)
            BadgePanes.setShown(ctx, b, true)
            assertAgree(b, on = true)
        }
    }

    @Test fun `turn-all moves every badge, and a group follows its members`() {
        BadgePanes.setAllShown(ctx, badges(), false)
        for (b in badges()) assertAgree(b, on = false)
        for (g in NotifyGroups.declared.filter { g -> g.members.any { id -> badges().any { it.id == id } } })
            assertFalse("${g.id}: every member off, so the group is off", NotifyGroups.isEnabled(ctx, g))
        BadgePanes.setAllShown(ctx, badges(), true)
        for (b in badges()) assertAgree(b, on = true)
    }

    @Test fun `a service with an ON badge is wanted - the restart path starts it`() {
        seedStaleGroupSwitches()
        // A badge that declares no grant: nothing but its switch decides whether its service is wanted.
        val markets = badges().first { it.requires.isEmpty() && it.service.isNotBlank() }
        assertTrue(BadgeServices.wanted(ctx, markets.service))
        assertTrue(markets.service in BadgeServices.ensureAll(ctx))
        BadgePanes.setShown(ctx, markets, false)
        assertFalse(BadgeServices.wanted(ctx, markets.service))
        assertNotEquals(BadgeServices.State.LIVE, BadgeServices.status(ctx, markets).state)
    }
}
