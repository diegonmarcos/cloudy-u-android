package com.diegonmarcos.clouddrive.manage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.DriveTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.content.ComponentName
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Local Apps and Overview > X-Ray Memory drawn at the owner's phone (A37, 384 x 832 dp): the cached list is on screen, Refresh
 * replaces it; the X-Ray Memory action is in Overview. Writes app/build/drive-screens/local-apps@a37.png, overview-xray@a37.png and
 * xray-memory-sample.md (a report built from sample inputs, the same builder the phone uses).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ManageScreensTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private fun until(what: String, cond: () -> Boolean) {
        repeat(60) { if (cond()) return; compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)) }; Thread.sleep(50); compose.waitForIdle() }
        assertTrue("timed out waiting for $what", cond())
    }

    private fun rows(vararg p: String) = AppSnapshot.Snapshot(p.mapIndexed { i, pkg -> AppRow(pkg, pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }, false, 90_000_000L / (i + 1), 40_000_000L / (i + 1), 5_000_000L, i == 0, if (i == 0) 210_000L else null, "2.${i}.0", if (i == 0) "☁" else null, if (i == 0) "https://example.org" else null) }, true, null, System.currentTimeMillis() - 3 * 60_000)

    private fun idle() = repeat(3) { compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)) }; compose.waitForIdle() }

    private fun shot(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/drive-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun localAppsShowsTheCachedListThenRefreshReplacesIt() {
        val ctx = RuntimeEnvironment.getApplication()
        val cache = LocalAppsCache(File(ctx.cacheDir, "t/local-apps.json")).also { it.write(rows("com.cached.alpha", "com.cached.bravo")) }
        var reads = 0
        val gate = java.util.concurrent.CountDownLatch(1)
        compose.setContent {
            DriveTheme {
                AppsPage(DrivePrefs(ctx), collect = { reads++; gate.await(8, java.util.concurrent.TimeUnit.SECONDS); rows("com.fresh.charlie", "com.fresh.delta") }, cache = cache)
            }
        }
        idle()
        // the first read is held: the cached list is what the page shows
        compose.onNodeWithText("Alpha", substring = true).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.APPS_REFRESH).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.APPS_UPDATED).assertTextContains("updated", substring = true)
        shot("local-apps@a37.png")
        gate.countDown()
        until("the fresh list") { compose.onAllNodesWithText("Charlie", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Charlie", substring = true).assertIsDisplayed()
        until("the cache write") { cache.read()?.rows?.firstOrNull()?.pkg == "com.fresh.charlie" }
        val before = reads
        compose.onNodeWithTag(DriveTags.APPS_REFRESH).performClick()
        until("Refresh re-reads") { reads > before }
    }

    @Test fun overviewHasTheXrayMemoryAction() {
        compose.setContent { DriveTheme { Column { XrayMemoryRow() } } }
        idle()
        compose.onNodeWithTag(DriveTags.XRAY_ROW).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.XRAY_GENERATE).assertIsDisplayed()
        shot("overview-xray@a37.png")
        File(System.getProperty("user.dir"), "build/drive-screens").apply { mkdirs() }.resolve("xray-memory-sample.md").writeText(XrayMemoryReport.markdown(XrayMemoryReportTest().full()))
    }
}
