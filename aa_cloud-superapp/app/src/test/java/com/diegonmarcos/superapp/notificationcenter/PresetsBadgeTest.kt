package com.diegonmarcos.superapp.notificationcenter

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.bgpolicy.BgPrefsKv
import com.diegonmarcos.superapp.bgpolicy.SuperSaving
import com.diegonmarcos.superapp.settings.LauncherModePrefs
import com.diegonmarcos.superapp.settings.LauncherModes
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.PresetModes
import com.diegonmarcos.superapp.settings.PresetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1720 The "Presets" badge of Notify > Actions: declared once, in the Actions group, a data widget; it says the mode, sandbox
 * and theme of the single state source; its five actions show, turn off and change them through that same source.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PresetsBadgeTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun nm() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun posted() = nm().activeNotifications.firstOrNull { it.id == PresetsBadge.NOTIF_ID }?.notification
    private fun title(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification) = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    /** A post the gate cannot hold back: the gate keeps one card per second, the assertions want this one. */
    private fun fresh() { NotifyGate.reset(); PresetsBadge.refreshNow(app) }

    @Before fun clean() {
        PresetsBadge.inline = true
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        NotifyGate.reset()
        nm().cancelAll()
        BgPrefsKv(app).put(SuperSaving.K_SNAP, "")
        LauncherModePrefs(app).selected = null
    }

    @Test fun `the badge is declared once, persistent, in Actions, owned by its data-widget service`() {
        val b = BadgeServices.declared.single { it.id == PresetsBadge.BADGE_ID }
        assertEquals(PresetsBadgeService::class.java.name, b.service)
        assertEquals(PresetsBadge.CHANNEL_ID, b.channel)
        assertTrue(b in BadgeDeclaration.badges(BadgeServices.declared))
        assertEquals("actions", NotifyGroups.groupOf(PresetsBadge.BADGE_ID)!!.id)
        assertTrue("a data widget: plain start, never a foreground service", DataWidgetRuntime.isWidgetService(b.service))
        assertTrue(BadgeDeclaration.restartServices(BadgeServices.declared).contains(b.service))
    }

    @Test fun `the card names the mode, the sandbox and the theme, and says none when no mode is on`() {
        val label: (String) -> String = { "<$it>" }
        val none = PresetsBadge.card(PresetState.Now("personal", "cloud", null), label, label, label)
        assertEquals("Presets · Mode: none", none.title)
        assertEquals("Sandbox: <personal> · Theme: <cloud>", none.text)
        val on = PresetsBadge.card(PresetState.Now("guest", "cloud", "focus"), label, label, label, pending = 1)
        assertEquals("Presets · Mode: <focus> · restore pending", on.title)
        assertEquals("Sandbox: <guest> · Theme: <cloud>", on.text)
    }

    @Test fun `the notification carries the state and the five actions, and its body opens Presets`() {
        PresetModes.select(app, "focus", shell = null)
        val n = PresetsBadge.build(app)
        val focus = LauncherModes.load().first { it.id == "focus" }.label
        assertTrue(title(n), title(n).contains(focus))
        val now = PresetState.now(app)
        assertTrue(text(n), text(n).contains(LauncherProfiles.loadFromBuildConfig().first { it.id == now.sandbox }.label))
        assertTrue(text(n), text(n).contains(LauncherThemes.loadFromBuildConfig().first { it.id == now.theme }.label))
        assertEquals(listOf("Presets", "Mode off", "Next mode", "Next sandbox", "Next theme"), n.actions.map { it.title.toString() })
        assertEquals(listOf("presets", "mode-off", "next-mode", "next-sandbox", "next-theme"), BadgeModel.of(n).map { it.id })
        val body = shadowOf(n.contentIntent)
        assertTrue(body.isActivityIntent)
        assertEquals(ComponentName(app, MainActivity::class.java), body.savedIntent.component)
        assertEquals(PresetsBadge.PAGE, body.savedIntent.getStringExtra("shortcut_action"))
        for (a in n.actions.drop(1)) {
            val pi = shadowOf(a.actionIntent)
            assertTrue("${a.title} is not a broadcast", pi.isBroadcastIntent)
            assertEquals(ComponentName(app, PresetsBadgeReceiver::class.java), pi.savedIntent.component)
            assertNotNull(pi.savedIntent.getStringExtra(PresetsBadgeReceiver.EXTRA))
        }
        assertEquals(0, n.flags and Notification.FLAG_FOREGROUND_SERVICE)
    }

    @Test fun `the quick actions change the single state source, and the badge follows`() {
        PresetModes.select(app, "power", shell = null)
        fresh()
        assertTrue(title(posted()!!), title(posted()!!).contains(LauncherModes.load().first { it.id == "power" }.label))

        // Next mode: the one after Power, Power restored first, exactly one mode on
        val modes = LauncherModes.load().map { it.id }
        PresetsBadge.act(app, PresetsBadge.ACT_MODE_NEXT, shell = { null })
        assertEquals(modes[modes.indexOf("power") + 1], PresetState.mode(app))

        // Mode off
        val said = PresetsBadge.act(app, PresetsBadge.ACT_MODE_OFF, shell = { null })
        assertTrue(said, said.startsWith("Off:"))
        assertNull(PresetState.mode(app))
        fresh()
        assertTrue(title(posted()!!), title(posted()!!).contains("Mode: none"))
        assertEquals("No mode is on.", PresetsBadge.act(app, PresetsBadge.ACT_MODE_OFF, shell = { null }))

        // Next sandbox and Next theme: always exactly one, never "off"
        val sandbox = PresetState.now(app).sandbox
        PresetsBadge.act(app, PresetsBadge.ACT_SANDBOX_NEXT, shell = { null })
        assertNotEquals(sandbox, PresetState.now(app).sandbox)
        val theme = PresetState.now(app).theme
        PresetsBadge.act(app, PresetsBadge.ACT_THEME_NEXT, shell = { null })
        assertNotEquals(theme, PresetState.now(app).theme)
        fresh()
        val n = posted()!!
        assertTrue(text(n), text(n).contains(LauncherProfiles.loadFromBuildConfig().first { it.id == PresetState.now(app).sandbox }.label))
        assertTrue(text(n), text(n).contains(LauncherThemes.loadFromBuildConfig().first { it.id == PresetState.now(app).theme }.label))
    }

    @Test fun `next wraps around and starts at the first`() {
        assertEquals("a", PresetsBadge.nextOf(listOf("a", "b"), null))
        assertEquals("b", PresetsBadge.nextOf(listOf("a", "b"), "a"))
        assertEquals("a", PresetsBadge.nextOf(listOf("a", "b"), "b"))
        assertEquals("a", PresetsBadge.nextOf(listOf("a", "b"), "gone"))
        assertNull(PresetsBadge.nextOf(emptyList(), "a"))
    }

    @Test fun `switched off, the badge leaves the shade and is not posted again`() {
        fresh()
        assertNotNull(posted())
        val b = BadgeServices.declared.single { it.id == PresetsBadge.BADGE_ID }
        BadgeCustomization.set(app, b, "enabled", false)
        try {
            fresh()
            assertNull("a switched-off badge is still in the shade", posted())
            assertFalse(BadgeServices.wanted(app, PresetsBadgeService::class.java.name))
        } finally { BadgeCustomization.set(app, b, "enabled", true) }
    }
}
