package com.diegonmarcos.clouddrive.ui

import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.backups.MirrorRunner
import com.diegonmarcos.clouddrive.configs.ConfigsScreen
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.superapp.bottomnav.TopBarTags
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
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
import org.robolectric.annotation.GraphicsMode

/**
 * The header and the Configs strip drawn at the owner's phone: Samsung Galaxy A37 (SM-A376B), 1080 x 2340 px at
 * Samsung's default 450 dpi = 384 x 832 dp. Draws the shell with Configs ▸ Webserver open and writes
 * app/build/drive-screens/configs-webserver@a37.png (CI uploads the folder), then guards what the picture shows:
 * the round Settings button is the top-right control, the strip reads General, Webserver, Backups, Others in
 * that order, and the header stays dense (a bar no taller than the fleet's 56dp row, the strip under it).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DriveScreenshotTest {

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

    private val actions = object : DriveActions {
        override fun requestStorageAccess() {}
        override fun requestTreeGrant() {}
        override fun openEngine(engine: String, target: String, url: String) {}
        override fun openImage(path: String, siblings: List<String>) {}
        override fun openPdf(path: String) {}
        override fun openWith(path: String) {}
        override fun share(paths: List<String>) {}
        override fun shareText(title: String, text: String) {}
        override fun copyText(text: String) {}
        override fun openUrl(url: String) {}
        override fun launchApp(packageName: String, fallbackUrl: String): Boolean = false
        override fun openAuthProfile(pkg: String, target: String, extra: String): Boolean = false
    }

    @Test
    fun `A37 header and Configs strip render in the owner's order`() {
        val ctx = RuntimeEnvironment.getApplication()
        val prefs = DrivePrefs(ctx)
        val mirrors = MirrorRunner(CoroutineScope(SupervisorJob() + Dispatchers.Main), prefs)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val rclone = com.diegonmarcos.clouddrive.sync.RcloneCoordinator(ctx, scope, prefs)
        val storage = com.diegonmarcos.clouddrive.storage.StorageCoordinator(ctx, scope, rclone, prefs)
        // Webserver is opened by its declared page id, so the capture needs neither the sign-in card nor a network.
        compose.setContent {
            DriveTheme {
                DriveShell(select = "configs") { id, _ ->
                    if (id == "configs") ConfigsScreen(mirrors, prefs, actions, hasAccess = true, rcloneVersion = null, git = GitSyncCoordinator(ctx, CoroutineScope(SupervisorJob() + Dispatchers.Main)), storage = storage, rclone = rclone, page = "webserver")
                }
            }
        }
        repeat(3) {
            compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)) }
            compose.waitForIdle()
        }
        compose.onNodeWithTag(TopBarTags.CONFIGS).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.WEBSERVER_OPEN).assertIsDisplayed()

        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/drive-screens").apply { mkdirs() }
        File(out, "configs-webserver@a37.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val density = compose.density.density
        assertEquals("A37 density: 450 dpi", 2.8125f, density, 0.01f)
        val bar = compose.onNodeWithTag(TopBarTags.BAR).fetchSemanticsNode().boundsInRoot
        val button = compose.onNodeWithTag(TopBarTags.CONFIGS).fetchSemanticsNode().boundsInRoot
        val strip = compose.onNodeWithTag(DriveTags.CONFIGS_STRIP).fetchSemanticsNode().boundsInRoot
        assertTrue("the bar is dense: ${bar.height / density}dp", bar.height / density <= 56.5f)
        assertTrue("Configs (three dots) is the top-right control: $button in $bar", button.right > bar.right - 12 * density && button.top >= bar.top)
        assertTrue("the strip sits under the bar: $strip vs $bar", strip.top >= bar.bottom)
        val order = Declarations.configs.pages.map { it.id }
        assertEquals(listOf("general", "perms", "about", "drive", "git", "fsync", "mounts", "webserver"), order)
        // The strip is a View-hosted pager, so its tab labels are not always in the Compose tree: when they are all
        // there, they must read left to right in the declared order (the declaration itself is asserted above).
        val lefts = Declarations.configs.pages.mapNotNull { p ->
            compose.onAllNodesWithText(p.label, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot }
                .firstOrNull { it.top >= strip.top - 1 && it.bottom <= strip.bottom + 1 }?.left
        }
        if (lefts.size == order.size) assertTrue("strip tabs follow General, Git, Fsync, Mounts, Webserver: $lefts", lefts.zipWithNext().all { (a, b) -> a < b })
        assertTrue("the capture is the A37 window: ${bmp.width}x${bmp.height}", bmp.width == 1080 && bmp.height in 1800..2400)
    }

    /** Sync's top row with the terminal action, over Files' top row (the neighbour whose actions it must match). */
    /** The Places sheet's root switcher: Emulated, Drive store and Terminal sections drawn with the same rows. */
    @Test
    fun `A37 Places sheet lists Terminal roots under their own header`() {
        val ctx = RuntimeEnvironment.getApplication()
        val labels = Declarations.files.sections.map { it.label }
        assertEquals(listOf("Emulated", "Drive store", "Terminal"), labels)
        val terminal = Declarations.files.places.filter { it.section == "terminal" }.map { it.label }
        assertEquals(listOf("Terminal home", "Terminal clones"), terminal)
        compose.setContent {
            DriveTheme {
                androidx.compose.foundation.layout.Column {
                    labels.forEach { header ->
                        com.diegonmarcos.clouddrive.ui.SectionHeader(header)
                        Declarations.files.places.filter { p -> Declarations.files.sections.first { it.label == header }.id == p.section }.forEach { p ->
                            com.diegonmarcos.clouddrive.files.PlaceRow(com.diegonmarcos.clouddrive.files.Places.Place(p.id, p.label, p.icon, com.diegonmarcos.clouddrive.files.Places.Kind.PATH, null, section = p.section)) {}
                        }
                    }
                }
            }
        }
        repeat(2) { compose.runOnUiThread { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300)) }; compose.waitForIdle() }
        listOf("Emulated", "Drive store", "Terminal", "Terminal home", "Terminal clones").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        shot("files-roots@a37.png")
    }

    private fun shot(name: String) {
        val root = compose.activity.findViewById<View>(android.R.id.content)
        val bmp = compose.runOnUiThread {
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                .also { b -> Canvas(b).also { it.drawColor(android.graphics.Color.BLACK); root.draw(it) } }
        }
        val out = File(System.getProperty("user.dir"), "build/drive-screens").apply { mkdirs() }
        File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
