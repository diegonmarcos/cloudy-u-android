package com.diegonmarcos.clouddrive.sync

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

/** #1934 the Settings control opens the repository's settings sheet: its fields are all there (Auto-sync, period, network, auth, author, rebase, message, Save, Remove). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w384dp-h2000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepoSettingsSheetTest {
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

    @Test fun theSettingsSheetShowsItsFields() {
        val ctx = RuntimeEnvironment.getApplication()
        val coordinator = GitSyncCoordinator(ctx, CoroutineScope(SupervisorJob() + Dispatchers.Main))
        val repo = ManagedRepo(id = "s1", name = "settings-repo", path = "/x/settings-repo", authKind = "https")
        compose.setContent { RepoSettingsSheet(repo, coordinator) {} }
        compose.waitForIdle()
        for (id in listOf(R.string.sync_settings_auto, R.string.sync_settings_period, R.string.sync_settings_network, R.string.sync_settings_auth, R.string.sync_settings_author,
            R.string.sync_settings_author_name, R.string.sync_settings_author_email, R.string.sync_settings_rebase, R.string.sync_settings_message, R.string.sync_settings_remove, R.string.chrome_save))
            compose.onNodeWithText(ctx.getString(id)).assertExists()
        compose.onNodeWithText("settings-repo").assertExists()
    }
}
