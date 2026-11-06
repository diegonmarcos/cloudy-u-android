package com.diegonmarcos.superapp.configs

import android.app.Application
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.diegonmarcos.superapp.perms.AdbLine
import com.diegonmarcos.superapp.perms.GroupBy
import com.diegonmarcos.superapp.perms.PermCatalogue
import com.diegonmarcos.superapp.perms.PermKind
import com.diegonmarcos.superapp.perms.PermState
import com.diegonmarcos.superapp.perms.PermStatus
import com.diegonmarcos.superapp.perms.PermsLink
import com.diegonmarcos.superapp.perms.PermsPage
import com.diegonmarcos.superapp.perms.PermsPageQuery
import com.diegonmarcos.superapp.perms.PermsQuery
import com.diegonmarcos.superapp.perms.SortBy
import com.diegonmarcos.superapp.perms.StatusFilter
import com.diegonmarcos.superapp.ui.KitPageHarness
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1550 field parity for SuperApp Configs > Perms. The manifest (test/resources/parity/perms-parity.json) lists every field the
 * page showed before #1519 and every #1519/#1546 addition. `ui` fields must be RENDERED by PermsSummaryBar + PermsCenterSection
 * over the real catalogue (every state present); `model` fields must come out of the declared query model. One missing fails.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w600dp-h8000dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PermsParityTest : KitPageHarness() {
    private val manifest = JSONObject(File("src/test/resources/parity/perms-parity.json").readText())
    private val cat = PermCatalogue.parse(File("../../ab_cloud-libs-shared/libs/perms/src/main/assets/perms-catalogue.json").readText())
    private val states = PermState.values()

    /** Every state appears; a fixed reason per row so the bracketed reason is on screen. */
    private fun rows(all: PermState? = null) = cat.entries.mapIndexed { i, e ->
        PermRowState(e, PermStatus(all?.takeIf { i > 0 } ?: states[i % states.size], "reason-${e.id}"))
    }

    private fun page(rs: List<PermRowState>) = PermsPage.rows(rs.map { it.entry }, { e -> rs.first { it.entry.id == e.id }.status }, { true })

    private fun model(src: String): List<String> = when (src) {
        "status_words" -> StatusFilter.values().map { it.word }
        "groupby_words" -> GroupBy.values().map { it.word }
        "sort_words" -> SortBy.values().map { it.word }
        "kind_titles" -> PermKind.values().map { PermsPageQuery.kindTitle(it) }
        "section_keys" -> PermsPageQuery.query(page(rows()), PermsQuery(groupBy = GroupBy.SECTION)).map { it.key }
        "detail_keys" -> page(rows()).flatMap { r -> PermsPageQuery.detail(r, listOf("cloud-superapp"), "dangerous").map { it.first } }.distinct()
        "nothing_missing" -> listOf(if (PermsPageQuery.nothingMissing(page(rows(PermState.GRANTED)), PermsQuery(missingOnly = true)).isNotEmpty()) "yes" else "no")
        else -> error("unknown model source $src")
    }

    private fun show(rs: List<PermRowState>, highlight: Boolean = false) = showKit {
        Column {
            PermsSummaryBar(rs, compose.activity) {}
            PermsCenterSection(
                rows = rs, highlight = if (highlight) PermsLink.Request("Wi-Fi scan", setOf(rs.first().entry.id)) else null, channelName = null,
                adbResults = listOf(AdbRunResult(AdbLine("a", "pm grant x y"), true, ""), AdbRunResult(AdbLine("b", "appops set x Y allow"), false, "error")),
                adbRunning = false, shellNote = "shell-note-fixture", onClearHighlight = {}, onGrant = {}, onShell = {}, onRunAdb = {},
                onOpenAdbShell = {}, ctx = compose.activity,
            )
        }
    }

    private fun rendered(t: String) = compose.onAllNodes(hasText(t, substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun check(setup: String): List<String> {
        val fields = manifest.getJSONArray("fields")
        val missing = ArrayList<String>()
        for (i in 0 until fields.length()) {
            val f = fields.getJSONObject(i)
            val ok = when (f.getString("kind")) {
                "ui" -> if (f.optString("setup", "default") != setup) continue else {
                    val any = f.getJSONArray("any"); (0 until any.length()).any { rendered(any.getString(it)) }
                }
                "model" -> if (setup != "default") continue else f.getString("value") in model(f.getString("source"))
                else -> false
            }
            if (!ok) missing += "${f.getString("group")}: ${f.getString("field")}"
        }
        return missing
    }

    @Test fun `every Perms field is present in the default view`() {
        show(rows())
        compose.waitForIdle()
        val missing = check("default")
        assertTrue("Perms fields missing (${missing.size}): $missing", missing.isEmpty())
    }

    @Test fun `the highlight banner names the feature and the count`() {
        show(rows(), highlight = true)
        compose.waitForIdle()
        val missing = check("highlight")
        assertTrue("Perms highlight fields missing (${missing.size}): $missing", missing.isEmpty())
    }

    @Test fun `Missing only keeps fully granted groups with nothing missing`() {
        show(rows(PermState.GRANTED))
        compose.waitForIdle()
        compose.onNodeWithTag("perms:filter-missing").performClick()
        compose.waitForIdle()
        val missing = check("missing_only")
        assertTrue("Perms fields missing under Missing only (${missing.size}): $missing", missing.isEmpty())
    }
}
