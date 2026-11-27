package app.sterna.ui.inbox

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1952: build.json::mail_cache and the sync engine say the same thing; neither moves alone. */
class MailCacheDeclaration1952Test {
    private val decl: String = repoFile("build.json").readText().substringAfter("\"mail_cache\": {").substringBefore("\n  },")
    private fun declared(key: String): String =
        Regex("\"$key\": (\"[^\"]*\"|[a-z0-9]+)").find(decl)?.groupValues?.get(1)?.trim('"') ?: error("build.json::mail_cache has no $key")
    private fun engine(file: String): String = repoFile("core/data/src/main/kotlin/app/sterna/core/data/$file").readText()

    @Test
    fun `the declared header and body budgets are the engine's`() {
        assertTrue("const val JMAP_BACKFILL_BATCH = ${declared("backfill_batch")}\n" in engine("mail/JmapInboxBacklog.kt"))
        assertTrue("const val FULL_SYNC_PAGES_PER_FOLDER = ${declared("other_folders_pages_per_run")}\n" in engine("mail/FullSync.kt"))
        assertTrue("private const val PAGE_SIZE = ${declared("list_page_size")}\n" in engine("mail/MailRepository.kt"))
        assertTrue("const val BODY_CACHE_MAX_BYTES_PER_ACCOUNT = ${declared("cap_mb")}L * 1024 * 1024" in engine("mail/BodyPrefetch.kt"))
        assertTrue("const val ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT = ${declared("attachments_cap_mb")}L * 1024 * 1024" in engine("mail/FullSync.kt"))
        val window = engine("mail/OfflineCache.kt")
        val default = Regex("val DEFAULT = ([A-Z]+)").find(window)!!.groupValues[1]
        assertTrue("the default window is ${declared("default_window_days")} days", Regex("$default\\(\\d+, ${declared("default_window_days")}\\)").containsMatchIn(window))
    }

    @Test
    fun `the declared list contract is the code's`() {
        assertEquals("store", declared("first_paint"))
        assertEquals("false", declared("initial_network_refresh"))
        assertTrue("override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH" in engine("mail/MailRepository.kt"))
        assertEquals("same_entry", declared("tab_return"))
        assertEquals(app.sterna.ui.TabMove.Return("inbox"), app.sterna.ui.tabMove("inbox", "rss"))
        assertEquals("all", declared("folders"))
        assertEquals("all", declared("inbox"))
    }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File("").absolutePath}")
}
