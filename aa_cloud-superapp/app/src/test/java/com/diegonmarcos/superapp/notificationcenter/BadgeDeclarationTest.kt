package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #515 — the badge system's two contracts, proven on the RESOLVED set.
 *
 * This deliberately does NOT read the shipped build.json and does NOT grep a
 * layout for "Quickmarks". The #497 pane shipped CI-green while three of
 * Diego's badges were dead, because every assertion anyone had written was
 * "does this file contain this word", and the files did. A word in a file
 * proves nothing about which services come back after an update.
 *
 * So each test here hands [BadgeDeclaration] a declaration of its own making
 * and asserts what the resolver PRODUCES from it — which is the same code
 * path `BadgeRestartReceiver` and `BadgePanes` run in the app.
 *
 * Robolectric because [BadgeDeclaration] parses with `org.json`, which is a
 * stub that throws on a bare JVM unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BadgeDeclarationTest {

    private val kde = "com.example.KdeStatusService"
    private val nav = "com.example.FloatingNavService"

    /** A declaration shaped like the real one: one self-starting service, one
     *  service owning THREE badges, and two producers that are not badges. */
    private fun declaration(extra: String = ""): String = """
        {"producers":[
          {"id":"kde_status","label":"KDE Connect","badge":true,"persistent":true,
           "enabled":true,"service":"$kde","requires":[],
           "customization":[{"key":"enabled","type":"toggle","label":"Show","default":true}]},
          {"id":"media_now_playing","label":"Media Playing","badge":true,"persistent":true,
           "enabled":true,"service":"$nav","requires":["overlay"]},
          {"id":"floating_nav_quick_actions","label":"Quickmarks","badge":true,"persistent":true,
           "enabled":true,"service":"$nav","requires":["overlay"]},
          {"id":"infos_alerts","label":"Grouped alerts","badge":true,"persistent":true,
           "enabled":true,"service":"$nav","requires":["overlay"]},
          {"id":"phone_notification_listener","label":"Phone notification access","badge":false,
           "persistent":false,"enabled":true,"service":""},
          {"id":"launcher_icon_badge","label":"Launcher icon badge","badge":false,
           "persistent":false,"enabled":true,"service":""}
          $extra
        ]}
    """.trimIndent()

    // ── Contract 1: the restart path covers every persistent badge ───────

    /**
     * THE REGRESSION. Quickmarks, Media and Alerts are all owned by
     * FloatingNavService; KDE is owned by its own. Before #515 only KDE had a
     * restart path — it was the one service named by hand in App.onCreate —
     * so an app update left three badges dead and one alive, which is exactly
     * what got reported. The resolved restart set must contain BOTH services.
     */
    @Test
    fun `restart set covers every service owning a persistent badge`() {
        val all = BadgeDeclaration.parse(declaration())
        val services = BadgeDeclaration.restartServices(all)
        assertTrue("FloatingNavService owns 3 persistent badges and must be re-ensured",
            services.contains(nav))
        assertTrue("KdeStatusService must still be re-ensured", services.contains(kde))
    }

    /** FloatingNavService owns three badges and must be started ONCE. */
    @Test
    fun `restart set is de-duplicated by service`() {
        val services = BadgeDeclaration.restartServices(BadgeDeclaration.parse(declaration()))
        assertEquals("one entry per service, not per badge", services.size, services.toSet().size)
        assertEquals(listOf(kde, nav), services)
    }

    /** A badge the owner switched off is a decision; re-ensuring it on every
     *  boot would override them silently. */
    @Test
    fun `a disabled badge does not drag its service into the restart set`() {
        val onlyDisabledNav = """
            {"producers":[
              {"id":"media_now_playing","label":"Media","badge":true,"persistent":true,
               "enabled":false,"service":"$nav"}
            ]}
        """.trimIndent()
        assertTrue(BadgeDeclaration.restartServices(BadgeDeclaration.parse(onlyDisabledNav)).isEmpty())
    }

    /** A producer that is not a badge has no business starting a service. */
    @Test
    fun `non-badge producers are not in the restart set`() {
        val all = BadgeDeclaration.parse(declaration())
        val ids = BadgeDeclaration.badges(all).map { it.id }
        assertFalse(ids.contains("phone_notification_listener"))
        assertFalse(ids.contains("launcher_icon_badge"))
    }

    // ── #4 / #1068: the page orders badges by card.rank, ties in decl order ──

    /**
     * The owner's page order is `card.rank` ascending; equal ranks keep the
     * order they were declared in, and an unranked badge (default
     * [Int.MAX_VALUE]) goes last. [BadgeDeclaration.rankOrder] is the single
     * sort the Notify page and the Push pane both run, so the sequence is
     * deterministic and never map/hash order. This hands it ranks OUT of
     * declaration order plus a rank tie and asserts the resolved sequence.
     */
    @Test
    fun `badges render in card rank order, ties keep declaration order`() {
        val decl = """
            {"producers":[
              {"id":"c","label":"C","badge":true,"card":{"rank":3}},
              {"id":"a","label":"A","badge":true,"card":{"rank":1}},
              {"id":"t1","label":"T1","badge":true,"card":{"rank":2}},
              {"id":"t2","label":"T2","badge":true,"card":{"rank":2}},
              {"id":"z","label":"Z","badge":true}
            ]}
        """.trimIndent()
        val all = BadgeDeclaration.badges(BadgeDeclaration.parse(decl))
        val ordered = BadgeDeclaration.rankOrder(all, all).map { it.id }
        assertEquals(listOf("a", "t1", "t2", "c", "z"), ordered)
    }

    // ── Contract 2: BOTH sections derive from the declaration ────────────

    /**
     * #518 asked for two sections — the badge boxes, a division, then a
     * customization menu per badge. Both are rendered from
     * [BadgeDeclaration.badges], so adding a producer to the declaration must
     * put it in BOTH without a Kotlin edit. This adds one and proves it.
     */
    @Test
    fun `a badge added to the declaration appears in both sections`() {
        val before = BadgeDeclaration.badges(BadgeDeclaration.parse(declaration())).map { it.id }
        assertFalse(before.contains("health_activity"))

        val withHealth = declaration(
            """,
          {"id":"health_activity","label":"Health","badge":true,"persistent":true,
           "enabled":true,"service":"com.example.HealthBadgeService","requires":["health_connect"],
           "customization":[
             {"key":"enabled","type":"toggle","label":"Show this badge","default":true},
             {"key":"refresh_minutes","type":"choice","label":"Refresh every",
              "options":["15","30","60"],"default":"30"}]}""",
        )
        val all = BadgeDeclaration.parse(withHealth)
        val badges = BadgeDeclaration.badges(all)

        // Section 1 draws one box per entry of this list …
        assertTrue("section 1 must show the new badge",
            badges.map { it.id }.contains("health_activity"))

        // … and section 2 one menu per entry of the SAME list, in the same
        // order, from that entry's own declared controls. A badge whose
        // customization did not come through would render an empty menu,
        // which is how "derived" quietly becomes "hardcoded elsewhere".
        val health = badges.first { it.id == "health_activity" }
        assertEquals("both sections walk the same list in the same order",
            badges.map { it.id }, BadgeDeclaration.badges(all).map { it.id })
        assertEquals(listOf("enabled", "refresh_minutes"), health.customization.map { it.key })
        assertEquals("choice", health.customization[1].type)
        assertEquals(listOf("15", "30", "60"), health.customization[1].options)
        assertEquals("30", health.customization[1].default)

        // …and it is covered by the restart path on the same edit.
        assertTrue(BadgeDeclaration.restartServices(all).contains("com.example.HealthBadgeService"))
    }

    /** Declared toggle defaults arrive as text either way round, because the
     *  customization store keeps one shape for every option type. */
    @Test
    fun `toggle defaults normalise to text`() {
        val all = BadgeDeclaration.parse(declaration())
        val kdeBadge = all.first { it.id == "kde_status" }
        assertEquals("true", kdeBadge.customization.first { it.key == "enabled" }.default)
    }

    /** A producer with no id or no label is a parse error, not a producer. */
    @Test
    fun `half-blank producers are dropped rather than drawn`() {
        val junk = """{"producers":[{"id":"","label":"x","badge":true},{"id":"y","label":"","badge":true}]}"""
        assertTrue(BadgeDeclaration.parse(junk).isEmpty())
    }

    /** A build whose declaration did not arrive renders nothing rather than
     *  crashing the Configs page. */
    @Test
    fun `an unparseable declaration resolves to an empty list`() {
        assertTrue(BadgeDeclaration.parse("not json").isEmpty())
        assertTrue(BadgeDeclaration.parse("{}").isEmpty())
    }

    /** The receiver must handle BOTH events: Android kills services on package
     *  replace and sends no BOOT_COMPLETED for an update, so handling only one
     *  of them leaves exactly the gap #515 is about. */
    @Test
    fun `the restart receiver handles package replace and boot`() {
        assertTrue(BadgeRestartReceiver.HANDLED.contains(android.content.Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(BadgeRestartReceiver.HANDLED.contains(android.content.Intent.ACTION_BOOT_COMPLETED))
    }
}
