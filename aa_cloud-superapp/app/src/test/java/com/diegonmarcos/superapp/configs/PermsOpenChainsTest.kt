package com.diegonmarcos.superapp.configs

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** #1688 every Open link of Perms is a declared chain that resolves before launch and ends on App info. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermsOpenChainsTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()

    @Test fun `home chain is Home settings, Default apps, role request, App info`() {
        val c = PermsOpenChains.chains.getValue("home")
        assertEquals(Settings.ACTION_HOME_SETTINGS, c[0].action)
        assertEquals(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS, c[1].action)
        assertEquals(PermsOpenChains.ROLE_HOME, c[2].role)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, c[3].action)
    }

    @Test fun `every chain ends on App info`() {
        for ((id, c) in PermsOpenChains.chains) {
            assertTrue(id, c.last().action == Settings.ACTION_APPLICATION_DETAILS_SETTINGS && c.last().scoped)
        }
    }

    @Test fun `nothing resolves gives a reason and no launch`() {
        var launched = 0
        val r = PermsOpenChains.open(ctx, "home") { launched++; true }
        assertFalse(r.ok); assertEquals(0, launched); assertTrue(r.reason.contains("not on this device"))
    }

    @Test fun `the first resolving candidate wins`() {
        val pm = shadowOf(ctx.packageManager)
        val cn = ComponentName("com.android.settings", "com.android.settings.HomeSettings")
        pm.addActivityIfNotPresent(cn)
        pm.addIntentFilterForActivity(cn, IntentFilter(Settings.ACTION_HOME_SETTINGS).apply { addCategory(Intent.CATEGORY_DEFAULT) })
        var got: Intent? = null
        val r = PermsOpenChains.open(ctx, "home") { got = it; true }
        assertTrue(r.reason, r.ok); assertEquals(Settings.ACTION_HOME_SETTINGS, got?.action)
    }

    @Test fun `every Open of the page uses a declared chain`() {
        val src = File("src/main/java/com/diegonmarcos/superapp/configs/PermissionsFragment.kt").readText()
        for (id in Regex("openChain\\(\"([a-z_]+)\"\\)").findAll(src).map { it.groupValues[1] })
            assertTrue("undeclared chain $id", id in PermsOpenChains.chains)
        val sp = File("src/main/java/com/diegonmarcos/superapp/system/SpecialPerms.kt").readText()
        assertTrue(sp.contains("PermsOpenChains.open(activity, \"home\")"))
    }
}
