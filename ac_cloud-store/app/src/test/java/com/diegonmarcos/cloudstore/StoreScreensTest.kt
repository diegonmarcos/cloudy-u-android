package com.diegonmarcos.cloudstore

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.fragment.app.FragmentActivity
import androidx.fragment.compose.AndroidFragment
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.appstore.FleetIdentity
import com.diegonmarcos.superapp.appstore.FleetIdentityPage
import com.diegonmarcos.superapp.appstore.StoreCloudFragment
import com.diegonmarcos.superapp.appstore.StorePages
import com.diegonmarcos.superapp.appstore.StorePhoneFragment
import com.diegonmarcos.superapp.appstore.BuildConfig as StoreBuild
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.uikit.KitDenseMaterial
import com.diegonmarcos.superapp.updater.Fleet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Evidence for every Cloud Store page: the real chrome (the fleet top row from build.json::ui.top_bar, the island and the
 * section's strip, all from the baked NavDecl) drawn over each page at Galaxy A37 size (384x832dp / 450dpi), written to
 * app/build/store-screens/<section>-<page>.png (the store-screens workflow uploads the folder). Pages: #1096 Access >
 * Identity (top, and scrolled to its last row, grouped by uid over apps that share one) and #1078 Configs > Notifications
 * (the update-checks card). Every seeded value is an obvious fake and a seed that does not land fails the test, so no
 * screenshot shows an empty state by accident. A [MUST] page that does not render is red; the others are shot best-effort
 * (a failure is written as <name>.err, and the first CI run says which those are).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StoreScreensTest(private val section: String, private val shot: String) {

    private val compose = createAndroidComposeRule<FragmentActivity>()

    /** The page id, without the "-bottom" variant suffix. */
    private val page get() = shot.removeSuffix(BOTTOM)

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, FragmentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private lateinit var registry: FleetIdentity.Registry
    private lateinit var live: Map<String, FleetIdentity.Seen>

    @Before fun seed() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        StorePages.hostDrawsStrip = true
        // Fleet apps installed on the "phone" with a few requested permissions: Access > Android / Cloud Perms read them.
        val fleet = Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64).filter { it.pkg != ctx.packageName }.take(6)
        for (a in fleet) shadowOf(ctx.packageManager).installPackage(PackageInfo().apply {
            packageName = a.pkg
            applicationInfo = ApplicationInfo().apply { packageName = a.pkg }
            requestedPermissions = arrayOf("android.permission.CAMERA", "android.permission.POST_NOTIFICATIONS", "android.permission.READ_MEDIA_IMAGES")
        })
        assertTrue("the fleet seed did not install: Perms pages would show only 'not installed'",
            fleet.isNotEmpty() && fleet.all { Fleet.installedId(ctx, it) != null })

        // Identity: 20 fake apps, 5 of them in two shared uids (3 + 2), one mismatch row, 3 not installed; tall enough to scroll.
        val members = (1..20).map { """{"id":"app$it","pkg":"org.fake.app$it","label":"Fake App ${"%02d".format(it)}"}""" }
        registry = FleetIdentity.parse("""{"groups":[
            {"id":"shell","label":"Fake shell group","shared_user_id":"org.fake.shared.shell","members":["org.fake.app1","org.fake.app2","org.fake.app3"]},
            {"id":"media","label":"Fake media group","shared_user_id":"org.fake.shared.media","members":["org.fake.app4","org.fake.app5"]}],
            "members":[${members.joinToString(",")}]}""")
        val seen = HashMap<String, FleetIdentity.Seen>()
        for (i in 1..3) seen["org.fake.app$i"] = FleetIdentity.Seen(10101, "org.fake.shared.shell", "A1B2C3D$i")
        for (i in 4..5) seen["org.fake.app$i"] = FleetIdentity.Seen(10102, "org.fake.shared.media", "E5F6A7B$i")
        for (i in 6..17) seen["org.fake.app$i"] = FleetIdentity.Seen(10110 + i, null, "C0FFEE%02d".format(i))
        seen["org.fake.app17"] = FleetIdentity.Seen(10127, "org.fake.legacy.uid", "C0FFEE17")   // declared own uid, installed in a group: a mismatch row
        live = seen
        val rows = FleetIdentity.rows(registry, live)
        assertEquals("the identity seed did not land: shared uid groups", 2, FleetIdentity.byUid(rows).count { it.shared })
        assertTrue("the identity seed has no mismatch row", rows.any { it.problem != null })
        assertTrue("the identity seed has no not-installed row", rows.any { it.seen == null })
    }

    @Composable private fun PageFor() {
        when ("$section/$page") {
            "cloud/cloud" -> AndroidFragment<StoreCloudFragment>(Modifier.fillMaxSize())
            "phone/installed", "phone/declared", "search/search-cloudy", "search/search-android", "search/search-torrent" -> AndroidFragment<StorePhoneFragment>(Modifier.fillMaxSize())
            "torrent/torrent" -> TorrentPage()
            "access/identity" -> FleetIdentityPage(registry, live)
            "access/mesh", "access/ipc", "access/android", "access/cloud" -> AccessPage(page)
            "settings/sources", "settings/adb", "settings/ai", "settings/notifications", "settings/torrent", "settings/about" -> ConfigsPage(page)
            else -> error("no page for $section/$page")
        }
    }

    @Composable private fun Chrome() {
        val nav = MainActivity.NAV
        val sec = nav.section(section)!!
        val topBar = MainActivity.TOP_BAR
        assertNotNull("build.json::ui.top_bar is not baked: the top row would be missing from the shot", topBar)
        KitDenseMaterial(darkColorScheme()) {
            Surface(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
                    NavBarTopFirstRowScaffold(title = "Cloud Store", hierarchy = emptyList(), onNavigate = {}, onAccount = {}, onConfigs = {}) {
                        NavBarBottomHost(
                            entries = nav.islandEntries { rememberVectorPainter(Icons.Filled.Star) },
                            selectedId = section, onSelect = {},
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                if (sec.pages.size > 1) NavBarTopSecondRow(sec.pages, page, onSelect = {}, underTopChrome = false)
                                Box(Modifier.weight(1f).fillMaxWidth()) { PageFor() }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun write(name: String) {
        val bmp = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).also { c -> c.drawColor(android.graphics.Color.BLACK); root.draw(c) }
            }
        }
        File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the page renders under the top row, island and strip`() {
        OUT.mkdirs()
        val name = "$section-$shot"
        val must = "$section/$shot" in MUST
        try {
            compose.setContent { Chrome() }
            compose.waitForIdle()
            val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(false)
            assertTrue("$name drew nothing", nodes.isNotEmpty())
            if ("$section/$page" == "access/identity") {
                val rows = FleetIdentity.sorted(FleetIdentity.rows(registry, live), FleetIdentity.Sort.UID)
                for (g in FleetIdentity.byUid(rows).filter { it.shared })
                    compose.onNodeWithTag(FleetIdentity.TAG_SHARED + g.key, useUnmergedTree = true).assertExists()
                val last = compose.onNodeWithTag("kit:listrow:" + rows.last().tag, useUnmergedTree = true)
                if (shot.endsWith(BOTTOM)) {
                    last.assertIsNotDisplayed()   // the page is taller than its pane, else the scrolled shot proves nothing
                    last.performScrollTo()
                    compose.waitForIdle()
                    last.assertIsDisplayed()
                }
            }
            if (section == "cloud") {
                // the Cloud page's Commits / CI-CD panels read GitHub on open (no seam to seed): shoot what they settled on (rows, or their own "why" line), not "loading..."
                runCatching { compose.waitUntil(30_000) { compose.onAllNodesWithText("loading…", useUnmergedTree = true).fetchSemanticsNodes().isEmpty() } }
            }
            if ("$section/$page" == "settings/notifications") {
                // the update-checks card, with the battery need still open (nothing is exempt in the seed)
                compose.onNodeWithTag(UpdateChecks.TAG_CARD, useUnmergedTree = true).assertExists()
                for (n in UpdateChecks.Need.values()) compose.onNodeWithTag(UpdateChecks.row(n), useUnmergedTree = true).assertExists()
                compose.onNodeWithTag(UpdateChecks.action(UpdateChecks.Need.BATTERY), useUnmergedTree = true).assertExists()
            }
            write(name)
        } catch (t: Throwable) {
            File(OUT, "$name.err").writeText("${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(300)}\n")
            assertTrue("$name did not render: ${t.javaClass.simpleName}: ${t.message?.take(300)}", !must)
        }
    }

    companion object {
        private const val BOTTOM = "-bottom"
        private val OUT = File("build/store-screens")

        /** Pages that must render (the rest are best-effort until the first CI run shows which of them can). */
        private val MUST = setOf(
            "access/identity", "access/identity-bottom", "settings/notifications",
            "settings/sources", "settings/about", "access/cloud", "access/ipc",
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/{1}")
        fun pages(): List<Array<Any>> = listOf(
            "cloud/cloud", "phone/installed", "phone/declared", "search/search-cloudy", "search/search-android", "search/search-torrent", "torrent/torrent",
            "access/mesh", "access/ipc", "access/identity", "access/identity-bottom", "access/android", "access/cloud",
            "settings/sources", "settings/adb", "settings/ai", "settings/notifications", "settings/torrent", "settings/about",
        ).map { it.split('/').toTypedArray<Any>() }
    }
}
