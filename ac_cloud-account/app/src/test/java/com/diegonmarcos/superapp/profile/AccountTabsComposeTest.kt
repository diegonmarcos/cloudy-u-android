package com.diegonmarcos.superapp.profile

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.diegonmarcos.superapp.profile.AccountRuntime.AppRead
import com.diegonmarcos.superapp.profile.AccountRuntime.Status
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #778 — Profiles, Runtime and Drift draw every declared surface with its test tag, and their
 * buttons drive the model: the tags the architect's UI-tree check and the debug API name.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AccountTabsComposeTest : KitPageHarness() {

    @get:Rule val tmp = TemporaryFolder()

    private fun model(): AccountModel {
        val m = AccountModel(compose.activity.applicationContext, AccountStore(tmp.root, AccountStore.PlainIo))
        m.landServer(JSONObject().put("about", JSONObject().put("profile", JSONObject().put("name", "Ada"))), "test")
        val (body, apps) = AccountRuntime.snapshot(VaultCockpit.layout.sections.map {
            AppRead(it.id, it.label, Status.NOT_REPORTING, "", emptyMap())
        })
        m.store.write(Slot.R, body, "runtime", "t", apps)
        return m
    }

    private fun scrolled(content: @androidx.compose.runtime.Composable () -> Unit) = showKit {
        Column(Modifier.verticalScroll(rememberScrollState())) { content() }
    }

    @Test fun `P1 Profiles - its actions and one card per declared topic`() {
        val m = model()
        scrolled { ProfilesTab(m, "Connect", { _, _ -> }, {}) }
        compose.onNodeWithTag(AccountTags.tab("profiles")).assertExists()
        for (tag in listOf(AccountTags.POPULATE_RUNTIME, AccountTags.POPULATE_SERVER, AccountTags.SAVE, AccountTags.EXPORT))
            compose.onNodeWithTag(tag).assertExists()
        assertTrue(InfoMask.schema.isNotEmpty())
        for (section in InfoMask.schema) compose.onNodeWithTag(AccountTags.topic(section.id)).assertExists()
        compose.onNodeWithTag(AccountTags.POPULATE_SERVER).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("Populate from the server file leaves an unsaved working copy", m.dirty)
        compose.onNodeWithTag(AccountTags.SAVE).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(false, m.dirty)
    }

    @Test fun `R1 Runtime - refresh and one card per declared app, with its status`() {
        val m = model()
        scrolled { RuntimeTab(m) }
        compose.onNodeWithTag(AccountTags.tab("runtime")).assertExists()
        compose.onNodeWithTag(AccountTags.REFRESH).assertExists()
        // The tab reads every app once when composed; wait for that read to land (it writes R).
        compose.waitUntil(10_000) { m.last.startsWith("✓ runtime read") }
        compose.waitForIdle()
        for (section in VaultCockpit.layout.sections) {
            compose.onNodeWithTag(AccountTags.runtimeApp(section.id)).assertExists()
            compose.onNodeWithTag(AccountTags.runtimeStatus(section.id)).assertExists()
        }
    }

    /** #781 Runtime is the per-app runtime and NOTHING below it: no "Your config (per peer)", no contact card. */
    @Test fun `R2 Runtime - no Your config block and no contact card`() {
        val m = model()
        scrolled { RuntimeTab(m) }
        compose.waitUntil(10_000) { m.last.startsWith("✓ runtime read") }
        compose.waitForIdle()
        for (gone in listOf("Your config", "per peer", "Erase my profile", "What is stored and where", "contact card — auto-saved"))
            compose.onAllNodesWithText(gone, substring = true, ignoreCase = true).assertCountEquals(0)
        val strings = com.diegonmarcos.superapp.account.R.string::class.java.fields.map { it.name }.toSet()
        assertTrue("R.string is readable", "account_runtime_title" in strings)
        for (name in listOf("setup_config_header", "setup_config_none", "setup_person_header", "journey_apply"))
            assertTrue("string $name is deleted, not hidden", name !in strings)
    }

    @Test fun `D1 Drift - files, pairs, and each pair's own actions`() {
        val m = model()
        scrolled { DriftTab(m, "galaxy") { _, _ -> } }
        compose.onNodeWithTag(AccountTags.tab("drift")).assertExists()
        for (slot in Slot.values()) compose.onNodeWithTag(AccountTags.exportFile(slot)).assertExists()
        compose.onNodeWithTag(AccountTags.EXPORT_REPORT).assertExists()
        for (p in AccountModel.pairs()) compose.onNodeWithTag(AccountTags.pair(p.id)).assertExists()
        // S↔R: server → runtime and runtime → declared.
        compose.onNodeWithTag(AccountTags.pair("SR")).performScrollTo().performClick()
        compose.onNodeWithTag(AccountTags.PUSH_ALL).assertExists()
        compose.onNodeWithTag(AccountTags.PULL_ALL).assertExists()
        compose.onNodeWithTag(AccountTags.UPLOAD).assertDoesNotExist()
        // L↔S: upload and discard, nothing that touches the runtime.
        compose.onNodeWithTag(AccountTags.pair("LS")).performScrollTo().performClick()
        compose.onNodeWithTag(AccountTags.UPLOAD).assertExists()
        compose.onNodeWithTag(AccountTags.DISCARD).assertExists()
        compose.onNodeWithTag(AccountTags.PUSH_ALL).assertDoesNotExist()
        for (app in m.apps) compose.onNodeWithTag(AccountTags.driftApp(app.id)).assertExists()
        // Discard is a command: it saves L as the server file.
        m.edit("about › profile › name", "Eve"); m.save()
        compose.onNodeWithTag(AccountTags.DISCARD).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals("Ada", AccountDrift.leaves(m.savedLocal()!!.body)["about › profile › name"])
        compose.onNodeWithTag(AccountTags.RESULT).performScrollTo().assertIsDisplayed()
    }
}
