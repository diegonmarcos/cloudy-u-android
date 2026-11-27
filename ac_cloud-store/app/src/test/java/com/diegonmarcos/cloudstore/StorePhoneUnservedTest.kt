package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.PhoneAppActions
import com.diegonmarcos.superapp.appstore.SourceResolver
import com.diegonmarcos.superapp.appstore.StoreActivity
import com.diegonmarcos.superapp.appstore.StoreKeepAliveText
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.appstore.StoreTally
import org.junit.After
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

/**
 * #1946 Cloud Store ▸ Phone with the install-source dataset (fleet/appstore-install-sources) NOT served: the installed launchable apps
 * are runtime data (PackageManager) and still draw; only what needs the map degrades, each greyed control carries a reason line naming
 * the dataset, and the status never claims "all up to date" while nothing was checked.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class StorePhoneUnservedTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val pm get() = ctx.packageManager
    private val apps = listOf("org.example.alpha" to "Alpha", "org.example.beta" to "Beta")

    @Before fun noDatasetServed() {
        PhoneAppActions.testSources = null   // and no provider answers FleetData.get: the phone has not been served
        for ((pkg, label) in apps) {
            shadowOf(pm).installPackage(PackageInfo().apply {
                packageName = pkg; versionName = "1.0"; longVersionCode = 1L
                applicationInfo = ApplicationInfo().apply { packageName = pkg }
            })
            shadowOf(pm).setInstallSourceInfo(pkg, "com.android.vending", "com.android.vending")
            shadowOf(pm).addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), ResolveInfo().apply {
                activityInfo = ActivityInfo().apply { packageName = pkg; name = "$pkg.Main"; applicationInfo = ApplicationInfo().apply { packageName = pkg } }
                nonLocalizedLabel = label
            })
        }
    }

    @After fun reset() { PhoneAppActions.testSources = null }

    @Test fun `the phone rows still list the installed launchable apps when the dataset is not served`() {
        val probe = StorePhoneFragment().probe(ctx)
        assertTrue("the page must know the map is missing", probe.sourcesMissing)
        for ((pkg, _) in apps) {
            val row = probe.rows.firstOrNull { it.pkg == pkg }
            assertNotNull("$pkg is installed and launchable but missing from the Phone rows", row)
            assertTrue("$pkg must be listed as installed", row!!.installed)
        }
        // What is not the map's stays live: at least one control per row (App info) has no reason.
        assertTrue("every control of the row is greyed", probe.rows.first { it.pkg == apps[0].first }.controls.any { it.second == null })
    }

    @Test fun `controls that need the map are greyed with a reason naming the dataset`() {
        val probe = StorePhoneFragment().probe(ctx)
        val origin = probe.rows.first { it.pkg == apps[0].first }.controls.single { it.first == ctx.getString(com.diegonmarcos.superapp.appstore.R.string.store_phone_origin_store) }
        assertNotNull("the origin store control is drawn greyed, never hidden", origin.second)
        assertTrue("the reason must name the dataset: ${origin.second}", PhoneAppActions.DATASET in origin.second!!)
        // A declared external app that is not on the phone: Install and its store page, both drawn, both greyed with the reason.
        val cfg = SourceResolver.unserved()
        val missing = PhoneAppActions.forMissing(ctx, SourceResolver.resolve(cfg, "org.example.gone"), null, null, cfg)
        assertEquals(listOf(PhoneAppActions.Kind.INSTALL, PhoneAppActions.Kind.ORIGIN), missing.map { it.kind })
        for (a in missing) {
            assertNotNull("${a.label} must be greyed, not live", a.disabledReason)
            assertTrue("${a.label}: the reason must name the dataset", PhoneAppActions.DATASET in a.disabledReason!!)
        }
    }

    @Test fun `the status never says all up to date while nothing was checked`() {
        val why = StoreTally.notChecked(listFailed = false, sourcesMissing = true)
        assertNotNull(why)
        val pill = StoreActivity.overall(emptyList(), 0, why).text()
        assertFalse("the pill claims 'All up to date' with no check behind it: $pill", pill.contains("up to date", ignoreCase = true))
        assertTrue(pill, pill.startsWith("Not checked: "))
        val tally = StoreTally.aggregate(emptyList(), why).updatesLine()
        assertFalse(tally, tally.contains("all current"))
        val keep = StoreKeepAliveText.lines(null, 0, 0L, 0L, why)
        assertFalse(keep.toString(), keep.any { it.contains("up to date", ignoreCase = true) })
        assertNotNull(StoreTally.notChecked(listFailed = true, sourcesMissing = false))
        assertNull(StoreTally.notChecked(listFailed = false, sourcesMissing = false))
        // With the dataset served and a check behind it, the old wording is unchanged.
        assertEquals("All up to date", StoreActivity.overall(emptyList(), 0, null).text())
    }
}
