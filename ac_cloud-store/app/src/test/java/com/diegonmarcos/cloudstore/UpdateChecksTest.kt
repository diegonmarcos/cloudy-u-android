package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.fragment.app.FragmentActivity
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #1078 Background update checks stay reliable through the user's own choices: the notification prompt, the install-source
 * screen and the battery-optimization screen are the system's; the app never grants or exempts itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateChecksTest {
    private val compose = createAndroidComposeRule<FragmentActivity>()
    private val app get() = RuntimeEnvironment.getApplication()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                shadowOf(RuntimeEnvironment.getApplication().packageManager)
                    .addActivityIfNotPresent(ComponentName(RuntimeEnvironment.getApplication(), FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @Test
    fun `the settings intents are the system screens, not a request to exempt or grant`() {
        assertEquals(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS, UpdateChecks.batteryIntent().action)
        val src = UpdateChecks.installSourceIntent(app)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, src.action)
        assertEquals("package:" + app.packageName, src.dataString)
    }

    @Test
    fun `the manifest asks for no DEVICE_POWER, and the battery exemption only for the wireless keeper`() {
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions?.toList().orEmpty()
        // #1467 the exemption IS requested now, but only for "Keep wireless debugging on" (libs:shizuku-adb-debug-tools, through the
        // Permissions page): the owner asked for it so battery management stops killing the keeper. The fleet check stays on WorkManager.
        assertTrue(requested.contains("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"))
        assertFalse(requested.contains("android.permission.DEVICE_POWER"))
    }

    @Test
    fun `a reboot or an update starts nothing in Cloud Store`() {
        // #1502 no BOOT_COMPLETED / MY_PACKAGE_REPLACED receiver of this app's own: WorkManager keeps its persisted jobs, and a job
        // exists only with auto-update on over an unmetered network.
        for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val names = app.packageManager.queryBroadcastReceivers(Intent(action).setPackage(app.packageName), 0)
                .map { it.activityInfo.name }.filterNot { it.startsWith("androidx.work.") }
            assertTrue("a receiver for $action would start Cloud Store: $names", names.isEmpty())
        }
    }

    @Test
    fun `the battery prompt is asked once and never when already exempt`() {
        assertTrue(UpdateChecks.shouldPromptBattery(app))
        UpdateChecks.markBatteryPrompted(app)
        assertFalse(UpdateChecks.shouldPromptBattery(app))
    }

    @Test
    fun `an exempt app is not prompted and has no battery need`() {
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
        assertFalse(UpdateChecks.shouldPromptBattery(app))
        assertFalse(UpdateChecks.missing(app).contains(UpdateChecks.Need.BATTERY))
    }

    @Test
    fun `the card has a row per need and a button only for what is still missing`() {
        val pm = app.getSystemService(android.content.Context.POWER_SERVICE) as PowerManager
        shadowOf(pm).setIgnoringBatteryOptimizations(app.packageName, true)
        compose.setContent { KitDenseMaterial(darkColorScheme()) { Surface(Modifier.fillMaxSize()) { UpdateChecksCard() } } }
        compose.waitForIdle()
        compose.onNodeWithTag(UpdateChecks.TAG_CARD, useUnmergedTree = true).assertExists()
        for (n in UpdateChecks.Need.values()) compose.onNodeWithTag(UpdateChecks.row(n), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(UpdateChecks.action(UpdateChecks.Need.BATTERY), useUnmergedTree = true).assertDoesNotExist()
        if (!UpdateChecks.notificationsGranted(app))
            compose.onNodeWithTag(UpdateChecks.action(UpdateChecks.Need.NOTIFICATIONS), useUnmergedTree = true).assertExists()
    }
}
