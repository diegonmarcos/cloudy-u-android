package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cloud > CONFIGS > NETWORK > Webserver. Guards: the tile sits right after DNS with an icon no other tile uses,
 * the package comes from the fleet manifest (not from a literal), an installed app is opened through its explicit
 * launch intent, and a missing one falls back to localhost:8002 in Cloud Browser.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class WebserverTileTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `W1 the tile opens the Servers sub-group with Proxy and its icon is its own`() {
        // #1737 the Network group shows individual icons again: ... Firewall, OS Perms | Webserver, Proxy | Bluetooth
        val pages = Sections.byId("config")!!.allPages.filter { it.group == "Network" && !it.hidden }
        val i = pages.indexOfFirst { it.id == "webserver" }
        assertTrue("Webserver tile missing", i > 0)
        assertEquals("network-sep-c", pages[i - 1].id)
        assertEquals("proxy", pages[i + 1].id)
        assertEquals("Network", pages[i].group)
        assertEquals("", pages[i].subgroup)
        assertEquals("action:open_webserver", pages[i].action)
        assertEquals(1, pages.count { it.iconName == pages[i].iconName })
        assertTrue(Sections.iconResFor(app, pages[i].iconName!!) != 0)
    }

    @Test
    fun `W2 the package is the fleet manifest's`() {
        assertEquals("com.diegonmarcos.cloudwebserver", WebserverLaunch.fleetPackage(WebserverLaunch.FLEET_ID))
        assertNotNull(WebserverLaunch.fleetPackage(WebserverLaunch.BROWSER_FLEET_ID))
        assertEquals("http://localhost:8002", WebserverLaunch.FALLBACK_URL)
    }

    @Test
    fun `W3 installed webserver app is opened with an explicit launch intent`() {
        val pkg = WebserverLaunch.fleetPackage(WebserverLaunch.FLEET_ID)!!
        val sp = shadowOf(app.packageManager)
        sp.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = android.content.pm.ApplicationInfo().also { it.packageName = pkg } })
        sp.addActivityIfNotPresent(android.content.ComponentName(pkg, "$pkg.Main"))
        sp.addIntentFilterForActivity(android.content.ComponentName(pkg, "$pkg.Main"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        assertEquals(WebserverLaunch.Opened.APP, WebserverLaunch.open(app))
        val started = shadowOf(app).nextStartedActivity
        assertEquals(pkg, started.component?.packageName)
    }

    @Test
    fun `W4 missing webserver app opens localhost 8002 in Cloud Browser`() {
        val browser = WebserverLaunch.fleetPackage(WebserverLaunch.BROWSER_FLEET_ID)!!
        WebserverLaunch.open(app)
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("http://localhost:8002", started.dataString)
        assertEquals(browser, started.`package`)
    }
}
