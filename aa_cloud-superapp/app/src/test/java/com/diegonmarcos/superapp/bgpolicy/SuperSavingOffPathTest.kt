package com.diegonmarcos.superapp.bgpolicy

import android.app.Application
import android.app.NotificationManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.MainActivity
import com.diegonmarcos.superapp.settings.FocusMode
import com.diegonmarcos.superapp.settings.LauncherModePrefs
import com.diegonmarcos.superapp.settings.PresetModes
import com.diegonmarcos.superapp.settings.PresetState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1720 The Super Saving notification, end to end on the real classes: the upgrade repair at app start, the posted
 * notification, the PendingIntent of its Off action (an explicit broadcast to the receiver the manifest declares), the
 * receiver's work, and the notification leaving when the mode is off. Also the body tap (it had no content intent).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class SuperSavingOffPathTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private fun nm() = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private fun indicator() = nm().activeNotifications.firstOrNull { it.id == SsIndicator.NOTIFICATION_ID }?.notification
    private val kv get() = BgPrefsKv(app)

    @Before fun clean() {
        com.diegonmarcos.superapp.notificationcenter.PresetsBadge.inline = true
        nm().cancelAll()
        kv.put(SuperSaving.K_SNAP, "")
        LauncherModePrefs(app).selected = null
    }

    /** The store of a phone before #1720: Power picked on the tile, Super Saving's journal open with its one stuck entry. */
    private fun doubleState() {
        LauncherModePrefs(app).selected = "power"
        kv.put(SuperSaving.K_SNAP, """{"steps":{"sync":{"state":"FAILED"}},"at":1}""")
    }

    @Test fun `app start repairs the double state and posts the notification of the one active mode`() {
        doubleState()
        SuperSavingRuntime.onAppStart(app)
        assertEquals("super_saving", PresetState.mode(app))
        assertTrue(SuperSavingRuntime.active(app))
        assertFalse("Focus is not on beside it", FocusMode.active(app))
        assertNotNull("the Off notification is not up", indicator())
        assertEquals(PresetModes.STATE_VERSION, LauncherModePrefs(app).stateVersion)
    }

    @Test fun `the Off action is an explicit immutable broadcast to the declared receiver, and the body opens Presets`() {
        doubleState(); SuperSavingRuntime.onAppStart(app)
        val n = indicator()!!
        assertEquals("one action: Off", 1, n.actions.size)
        val off = shadowOf(n.actions[0].actionIntent)
        assertTrue("the Off action is not a broadcast", off.isBroadcastIntent)
        assertTrue("the Off action must be immutable", off.flags and android.app.PendingIntent.FLAG_IMMUTABLE != 0)
        val sent = off.savedIntent
        assertEquals(SuperSavingOffReceiver.ACTION, sent.action)
        assertEquals(ComponentName(app, SuperSavingOffReceiver::class.java), sent.component)
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("the receiver of the Off action is not in the manifest", manifest.contains("android:name=\".bgpolicy.SuperSavingOffReceiver\""))
        // the body: it had no content intent, so a tap on the notification itself did nothing
        val body = shadowOf(n.contentIntent)
        assertTrue(body.isActivityIntent)
        assertEquals(ComponentName(app, MainActivity::class.java), body.savedIntent.component)
        assertEquals(SsIndicator.PRESETS_PAGE, body.savedIntent.getStringExtra("shortcut_action"))
    }

    @Test fun `the receiver's Off turns the mode off, empties the journal and takes the notification down`() {
        doubleState(); SuperSavingRuntime.onAppStart(app)
        val text = SuperSavingOffReceiver.handle(app, shell = null)
        assertTrue(text, text.startsWith("Off:"))
        assertNull("the mode is still on after Off", PresetState.mode(app))
        assertFalse(SuperSaving.isActive(kv))
        assertNull("the notification is still up after Off", indicator())
        assertEquals("a second Off has nothing to do", "Super Saving is not on.", SuperSavingOffReceiver.handle(app, shell = null))
    }

    @Test fun `an Off that cannot finish says why in the notification and keeps the mode on`() {
        // a real apply without the shell: the master sync switch is the step that takes
        ContentResolver.setMasterSyncAutomatically(true)
        val on = PresetModes.select(app, SuperSavingRuntime.ENGINE, shell = null)
        assertEquals(on.message, "super_saving", PresetState.mode(app))
        assertFalse(ContentResolver.getMasterSyncAutomatically())
        // an entry that needs the shell joins the journal (as when the mode was turned on with ADB up)
        val j = org.json.JSONObject(kv.get(SuperSaving.K_SNAP)!!)
        j.getJSONObject("steps").put("screen_timeout", org.json.JSONObject().put("old", org.json.JSONObject().put("system/screen_off_timeout", "60000")))
        kv.put(SuperSaving.K_SNAP, j.toString())
        val text = SuperSavingOffReceiver.handle(app, shell = null)
        assertTrue(text, text.contains("did not finish"))
        assertEquals("super_saving", PresetState.mode(app))
        assertTrue("what could be restored without the shell was", ContentResolver.getMasterSyncAutomatically())
        val shown = indicator()!!.extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString()
        assertTrue(shown, shown.contains("did not finish") && shown.contains("Presets"))
    }

    @Test fun `picking a mode while Super Saving is on restores it first and takes its notification down`() {
        ContentResolver.setMasterSyncAutomatically(true)
        PresetModes.select(app, SuperSavingRuntime.ENGINE, shell = null)
        assertNotNull(indicator())
        val o = PresetModes.select(app, "focus", shell = null)
        assertEquals("focus", PresetState.mode(app))
        assertTrue(FocusMode.active(app)); assertFalse(SuperSavingRuntime.active(app))
        assertTrue("Super Saving's sync step was restored first", o.restored.any { it.id == "sync" } && ContentResolver.getMasterSyncAutomatically())
        assertNull(indicator())
    }
}
