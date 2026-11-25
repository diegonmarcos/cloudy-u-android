package com.diegonmarcos.superapp.apps

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.AppStoreHost
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.PhoneAppActions.Kind
import com.diegonmarcos.superapp.updater.Fleet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * #564 — Store ▸ Phone Apps buttons, asserted on what [PhoneAppActions.of]
 * RESOLVES for packages installed into Robolectric's PackageManager with a
 * real install source, not on what a source file says.
 *
 * Expected store labels and deeplinks are READ from the shipped map
 * (appstore-install-sources.json, merged into this app's assets), and the
 * fleet member is READ from the fleet manifest baked into libs:appstore — the
 * only literals here are the inputs: which installer the platform recorded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorePhoneActionsTest {

    init { FleetPrivateFixtures.install() }   // #1566 the install-source map is a fleet-private dataset
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val sources = PhoneAppActions.sources(ctx)
    private val fleet = Fleet.parse(com.diegonmarcos.superapp.appstore.BuildConfig.CONSTELLATION_FLEET_B64)

    private val hostActivity = AppStoreHost.launchActivity
    private val hostExtras = AppStoreHost.launchExtras

    @After fun restoreHost() {
        AppStoreHost.launchActivity = hostActivity
        AppStoreHost.launchExtras = hostExtras
    }

    /** Installs [pkg] with a launcher activity and, when given, the installer the platform records. */
    private fun install(pkg: String, installer: String?, flags: Int = 0) {
        val spm = shadowOf(ctx.packageManager)
        spm.installPackage(PackageInfo().apply {
            packageName = pkg
            applicationInfo = ApplicationInfo().apply { packageName = pkg; this.flags = flags }
        })
        val main = ComponentName(pkg, "$pkg.Main")
        spm.addActivityIfNotPresent(main)
        spm.addIntentFilterForActivity(main, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
        if (installer != null) spm.setInstallSourceInfo(pkg, installer, installer)
    }

    private fun actions(pkg: String, shellReady: Boolean = false) =
        PhoneAppActions.of(ctx, pkg, PhoneAppActions.fleetByPackage(fleet)[pkg], shellReady, sources)

    private fun List<PhoneAppActions.Action>.of(k: Kind) = firstOrNull { it.kind == k }

    @Test
    fun `every declared foreign store opens its OWN page for the app, aimed at that store`() {
        val stores = sources.getJSONObject("sources")
        assertTrue("the map declares no store", stores.length() > 0)
        for (installer in stores.keys()) {
            val pkg = "test.foreign.${installer.replace('.', '_')}"
            install(pkg, installer)
            val entry = stores.getJSONObject(installer)
            val origin = actions(pkg).of(Kind.ORIGIN)
            assertNotNull("$installer: no origin button", origin)
            assertEquals("$installer: origin label", entry.getString("label"), origin!!.label)
            assertNull("$installer: origin is disabled: ${origin.disabledReason}", origin.disabledReason)
            assertEquals("$installer: origin must open the store page",
                entry.getString("deeplink").replace("{pkg}", pkg), origin.intent?.dataString)
            assertEquals("$installer: origin must be aimed at the installer, not any market handler",
                installer, origin.intent?.`package`)
            assertNull("$installer: a foreign app must not offer Update", actions(pkg).of(Kind.UPDATE))
        }
    }

    @Test
    fun `an app with no recognised installer gets the unknown label and App info`() {
        val pkg = "test.sideloaded"
        install(pkg, installer = null)
        val origin = actions(pkg).of(Kind.ORIGIN)!!
        assertEquals(sources.getJSONObject("unknown").getString("label"), origin.label)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, origin.intent?.action)
        assertEquals("package:$pkg", origin.intent?.dataString)
    }

    @Test
    fun `a published fleet app gets Update and its origin opens the host's Cloud store page`() {
        val app = fleet.firstOrNull { !it.blocked }
        assertNotNull("the baked fleet has no published app", app)
        install(app!!.pkg, installer = null)
        AppStoreHost.launchActivity = Activity::class.java
        AppStoreHost.launchExtras = mapOf("store-target" to "cloud-tab")
        val acts = actions(app.pkg)
        val update = acts.of(Kind.UPDATE)
        assertNotNull("a fleet app must offer Update", update)
        assertNull("published fleet app's Update is disabled: ${update!!.disabledReason}", update.disabledReason)
        val origin = acts.of(Kind.ORIGIN)!!
        assertEquals(sources.getJSONObject("ours").getString("label"), origin.label)
        assertEquals(AppStoreHost.launchActivity!!.name, origin.intent?.component?.className)
        AppStoreHost.launchExtras.forEach { (k, v) -> assertEquals("host extra $k", v, origin.intent?.getStringExtra(k)) }
    }

    @Test
    fun `an app we installed but the fleet does not list is still ours`() {
        val pkg = "test.ours.offfleet"
        install(pkg, installer = ctx.packageName)
        AppStoreHost.launchActivity = Activity::class.java
        val acts = actions(pkg)
        assertEquals(sources.getJSONObject("ours").getString("label"), acts.of(Kind.ORIGIN)!!.label)
        assertNull("no fleet entry = nothing to update from", acts.of(Kind.UPDATE))
    }

    @Test
    fun `ours with no host page is disabled with a reason, not a dead click`() {
        val app = fleet.first()
        install(app.pkg, installer = null)
        AppStoreHost.launchActivity = null
        val origin = actions(app.pkg).of(Kind.ORIGIN)!!
        assertNull(origin.intent)
        assertNotNull("no host page and no reason = a silent no-op", origin.disabledReason)
    }

    @Test
    fun `an unpublished fleet app shows Update disabled with a reason`() {
        // The baked fleet has no unpublished entry today, so a real one is
        // taken and marked unpublished — waiting for the data to carry one
        // would leave this test passing on an early return.
        val app = fleet.first().copy(blocked = true)
        install(app.pkg, installer = null)
        val update = PhoneAppActions.of(ctx, app.pkg, app, false, sources).of(Kind.UPDATE)
        assertNotNull("an unpublished fleet app lost its Update button", update)
        assertNotNull("an unpublished fleet app's Update must say why it cannot", update!!.disabledReason)
    }

    @Test
    fun `Stop follows the shell channel, never a silent no-op`() {
        val pkg = "test.stop"
        install(pkg, "com.example.any")
        val unpaired = actions(pkg, shellReady = false).of(Kind.STOP)!!
        assertNotNull("Stop without a shell channel must say why", unpaired.disabledReason)
        assertNull(actions(pkg, shellReady = true).of(Kind.STOP)!!.disabledReason)
        // #859 Stop on SuperApp itself is live, even with no shell channel:
        // SelfStop ends its own tasks and process, no shell needed.
        assertNull("Stop on SuperApp itself must not be refused",
            PhoneAppActions.of(ctx, ctx.packageName, null, false, sources).of(Kind.STOP)!!.disabledReason)
    }

    @Test
    fun `Remove asks Android, and a system app cannot be removed`() {
        val pkg = "test.remove"
        install(pkg, null)
        val remove = actions(pkg).of(Kind.REMOVE)!!
        assertNull(remove.disabledReason)
        assertEquals(Intent.ACTION_DELETE, remove.intent?.action)
        assertEquals("package:$pkg", remove.intent?.dataString)
        val sys = "test.system"
        install(sys, null, ApplicationInfo.FLAG_SYSTEM)
        assertNotNull("a system app's Remove must say why it cannot", actions(sys).of(Kind.REMOVE)!!.disabledReason)
    }

    @Test
    fun `Open launches the app and App info opens its settings screen, in the brief's order`() {
        val pkg = "test.order"
        install(pkg, null)
        val acts = actions(pkg)
        assertEquals(pkg, acts.of(Kind.OPEN)!!.intent?.component?.packageName)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, acts.of(Kind.APP_INFO)!!.intent?.action)
        assertEquals(listOf(Kind.OPEN, Kind.STOP, Kind.REMOVE, Kind.APP_INFO, Kind.ORIGIN), acts.map { it.kind })
        val app = fleet.first { !it.blocked }
        install(app.pkg, null)
        assertEquals(Kind.UPDATE, actions(app.pkg).first().kind)
    }
}
