package com.diegonmarcos.cloudme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Structure guard (plain JUnit, reads the sources): a Buro / LLC page is the stack renderer's ONE vertical scroller, and the ledger block it hosts
 * is a plain column inside it, never a second vertical scroller (a LazyColumn, a verticalScroll). The page hosts the block by kind, and the pure
 * model and reports hold no Android view or Compose code.
 */
class LedgerScrollGuardTest {
    private val main: File = run {
        var d: File? = File("").absoluteFile
        while (d != null) {
            for (rel in listOf("app/src/main/java/com/diegonmarcos/cloudme", "src/main/java/com/diegonmarcos/cloudme")) File(d, rel).takeIf { it.isDirectory }?.let { return@run it }
            d = d.parentFile
        }
        throw AssertionError("ac_cloud-me sources not found")
    }
    private fun src(n: String) = File(main, n).readText()

    @Test fun `the ledger block nests no vertical scroller`() {
        assertEquals(emptyList<String>(), listOf("LedgerScreen.kt", "ExperiencesBlock.kt").filter { Regex("""verticalScroll\(|LazyColumn|LazyVerticalGrid|VerticalPager""").containsMatchIn(src(it)) })
    }

    @Test fun `the stack renderer is the one vertical scroller and hosts the ledger kind`() {
        assertEquals(1, Regex("""verticalScroll\(""").findAll(src("StackScreen.kt")).count())
        assertTrue("StackScreen does not host the ledger kind", "\"ledger\" -> LedgerBlock(o)" in src("StackScreen.kt"))
        assertTrue("LedgerBlock does not hand the done_plan view to ExperiencesBlock",
            "if (o.optString(\"view\") == \"done_plan\") { ExperiencesBlock(o); return }" in src("LedgerScreen.kt"))
    }

    @Test fun `the model, the reports and the store are free of UI code`() {
        for (f in listOf("LedgerModel.kt", "LedgerReports.kt", "LegacyRoutes.kt", "ExperiencesModel.kt")) assertTrue("$f imports UI", !Regex("""import (androidx\.compose|android\.(view|widget))""").containsMatchIn(src(f)))
    }

    @Test fun `no sample or mock rows are baked into the ledger code`() {
        for (f in listOf("LedgerModel.kt", "LedgerReports.kt", "LedgerScreen.kt", "LedgerStore.kt"))
            assertTrue("$f carries sample data", !Regex("""(?i)\b(sample|mock|demo|lorem)\b""").containsMatchIn(src(f).replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")))
    }
}
