package com.diegonmarcos.cloudcalc

import com.diegonmarcos.superapp.perms.PermsChip
import android.content.ComponentName
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.cloudcalc.audio.WavWriter
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.ModeScreen
import com.diegonmarcos.cloudcalc.ui.ToolkitTags
import com.diegonmarcos.superapp.light.Light
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Toolkit under Robolectric: the shared Light page, the Voice Recorder's gate and list, and the Tracker link's empty state. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolkitAppTest {
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

    private val app get() = RuntimeEnvironment.getApplication()
    private val shown = mutableStateOf<Declarations.Mode?>(null)
    private var contentSet = false

    private fun show(modeId: String) {
        if (!contentSet) {
            compose.setContent { CalcTheme { shown.value?.let { key(it.id) { ModeScreen(it) } } } }
            contentSet = true
        }
        compose.runOnIdle { shown.value = Declarations.mode(modeId)!! }
        compose.waitForIdle()
    }

    private fun recordingsDir(): File = File(app.filesDir, JSONObject(Declarations.mode("recorder")!!.spec).getString("dir")).apply { mkdirs() }

    @After fun clean() { recordingsDir().listFiles()?.forEach { it.delete() } }

    @Test fun `the Light page is the shared one, and it reports a phone with no flashlight instead of pretending`() {
        show("light")
        compose.onNodeWithText("Light").assertExists()
        compose.onNodeWithTag("light-rear").assertExists()
        compose.onNodeWithTag("light-front").assertExists()
        compose.onNodeWithTag("light-mode-0").assertExists()
        compose.onNodeWithTag("light-keep").assertExists()
        compose.onNodeWithTag("light-rear").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("light-error").assertExists()
        assertFalse("the one engine says the torch is off", Light.engine(app).state.rearOn)
    }

    @Test fun `the Voice Recorder shows why and the Fix-in-Perms chip, and asks nothing itself (#1515)`() {
        show("recorder")
        compose.onNodeWithText(app.getString(R.string.recorder_why)).assertExists()
        compose.onNodeWithTag(PermsChip.TAG).assertExists()
        assertEquals(0, compose.onAllNodesWithTag(ToolkitTags.RECORD).fetchSemanticsNodes().size)
    }

    @Test fun `with the microphone allowed the recorder lists what is in its folder and deletes on request`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val f = File(recordingsDir(), "rec_20260101_120000.wav")
        WavWriter(f, 16000).use { it.write(ShortArray(16000)) }
        show("recorder")
        compose.onNodeWithTag(ToolkitTags.RECORD).assertExists()
        compose.onNodeWithTag(ToolkitTags.ELAPSED).assertExists()
        compose.onNodeWithTag(ToolkitTags.play(f.name)).assertExists()
        compose.onNodeWithTag(ToolkitTags.share(f.name)).assertExists()
        compose.onNodeWithText("20260101_120000").assertExists()
        compose.onNodeWithTag(ToolkitTags.delete(f.name)).performClick()
        compose.waitForIdle()
        assertFalse(f.exists())
        compose.onNodeWithText(app.getString(R.string.recorder_none)).assertExists()
    }

    @Test fun `with the microphone allowed and no recordings the list says so`() {
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO)
        show("recorder")
        compose.onNodeWithText(app.getString(R.string.recorder_none)).assertExists()
        assertTrue(recordingsDir().listFiles().isNullOrEmpty())
    }

    @Test fun `without Cloud Navigation the Tracker offers nothing to tap and says why`() {
        show("tracker")
        compose.onNodeWithTag(ToolkitTags.TRACKER_MISSING).assertExists()
        val links = JSONObject(Declarations.mode("tracker")!!.spec).getJSONArray("pages")
        (0 until links.length()).forEach { i ->
            assertEquals(0, compose.onAllNodesWithTag(ToolkitTags.trackerPage(links.getJSONObject(i).getString("target"))).fetchSemanticsNodes().size)
        }
    }

    @Test fun `the Tracker's package is Cloud Navigation's, from the fleet manifest`() {
        assertEquals("com.diegonmarcos.cloudnav", BuildConfig.TRACKER_PACKAGE)
    }
}
