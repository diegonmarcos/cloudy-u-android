package com.diegonmarcos.cloudstore

import com.diegonmarcos.superapp.torrent.TorrentState
import com.diegonmarcos.superapp.torrent.TorrentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1790 field parity of the Torrent page against the version before the download-manager rework (rule 16): every control, list,
 * status and data point the page drew is still drawn, the new download-manager parts are there, and the ONE removal is recorded as
 * owner-ordered: the suggested-torrent list (the #1681 catalogue section). Plain JVM: it reads the page's source.
 */
class TorrentPageParityTest {
    private fun repoFile(rel: String): File = listOf(rel, "../$rel", "../../$rel", "../../../$rel").map(::File).firstOrNull { it.exists() }
        ?: error("$rel not found from ${File(".").absolutePath}")
    private val page by lazy { repoFile("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/TorrentPage.kt").readText() }
    private val host by lazy { repoFile("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/TorrentHost.kt").readText() }

    /** What the page drew before #1790, by the code that draws it. */
    private val before = mapOf(
        "magnet field" to "TorrentTags.MAGNET_FIELD", "Add" to "TorrentTags.ADD", "Import .torrent" to "TorrentTags.IMPORT",
        ".torrent picker" to "OpenMultipleDocuments", "engine unavailable" to "EngineState.Unavailable", "status tag" to "TorrentTags.STATUS",
        "offline banner" to "TorrentTags.OFFLINE", "totals line" to "TorrentTags.TOTALS", "folder + free space" to "TorrentPaths.freeBytes(dir)",
        "Active list" to "\"Active (", "Completed list" to "\"Completed (", "row" to "TorrentTags.row(", "progress bar" to "TorrentBar(",
        "data line (state, %, rates, ETA, seeds, peers, ratio)" to "TorrentFormat.line(t)", "row error" to "t.error",
        "save path" to "t.savePath", "Pause" to "TorrentTags.pause(", "Resume / Seed" to "TorrentTags.resume(", "Remove" to "TorrentTags.remove(",
        "Remove + files (armed)" to "TorrentTags.removeFiles(", "seed switch" to "TorrentTags.seed(", "polls the engine" to "torrents.refresh()",
    )

    /** What #1790 adds: a download manager. */
    private val added = mapOf(
        "add by .torrent web address" to "TorrentIntake.addLink(", "queue position" to "TorrentTags.queue(", "Pause all" to "TorrentTags.PAUSE_ALL",
        "Resume all" to "TorrentTags.RESUME_ALL", "Open the finished file" to "TorrentTags.open(", "finished files" to "TorrentTags.files(",
        "session complaint" to "TorrentTags.note(", "add sentence" to "TorrentTags.NOTE",
    )

    /** Removed at the owner's order (#1790): the suggested-torrent list. Nothing else may leave. */
    private val ownerOrderedRemovals = mapOf("suggested-torrent list (#1681 catalogue section)" to listOf("catalogueItems(", "CatalogueView(", "TorrentCatalogue.load("))

    @Test fun `every field the page had is still drawn`() {
        val missing = before.filterValues { it !in page }.keys
        assertTrue("dropped from the Torrent page: $missing", missing.isEmpty())
    }

    @Test fun `the download manager parts are drawn`() {
        val missing = added.filterValues { it !in page }.keys
        assertTrue("missing: $missing", missing.isEmpty())
        assertTrue("magnets still reach the engine", "engine.addMagnet(t)" in host)
        assertTrue(".torrent addresses are fetched and added", "engine.addTorrentBytes(label, bytes)" in host)
    }

    @Test fun `the suggested-torrent list is gone, and only it, at the owner's order`() {
        ownerOrderedRemovals.forEach { (what, code) -> code.forEach { assertFalse("$what is back: $it", it in page) } }
        assertEquals(1, ownerOrderedRemovals.size)
        // the catalogue itself is kept for the other pages
        assertTrue(repoFile("ac_cloud-store/app/src/main/java/com/diegonmarcos/cloudstore/TorrentCatalogue.kt").exists())
        assertTrue("Search > Torrent keeps its recommendations", "override fun recommendations()" in host)
    }

    private fun status(name: String, path: String) = TorrentStatus("a", name, TorrentState.COMPLETED, 1f, 10, 10, 0, 0, 0, 0, 0, 0, path, null, false, 0, null)

    @Test fun `Open picks the main media file of a multi-file torrent and lists every file`() {
        val dir = Files.createTempDirectory("open").toFile()
        val item = File(dir, "RondoAllaTurca_201401").apply { mkdirs() }
        File(item, "Rondo_alla_turca_2.mp3").writeBytes(ByteArray(5000))
        File(item, "Rondo_alla_turca.mp3").writeBytes(ByteArray(2000))
        File(item, "Rondo_Alla_Turca_jp2.zip").writeBytes(ByteArray(9000))
        File(item, ".parts").writeBytes(ByteArray(1))
        val files = TorrentOpen.files(status("RondoAllaTurca_201401", dir.path))
        assertEquals(listOf("Rondo_Alla_Turca_jp2.zip", "Rondo_alla_turca.mp3", "Rondo_alla_turca_2.mp3"), files.map { it.relative })
        assertEquals("Rondo_alla_turca_2.mp3", TorrentOpen.main(files)!!.relative)
    }

    @Test fun `Open on a single-file torrent and on a gone one`() {
        val dir = Files.createTempDirectory("open1").toFile()
        File(dir, "a.iso").writeBytes(ByteArray(10))
        assertEquals(listOf("a.iso"), TorrentOpen.files(status("a.iso", dir.path)).map { it.relative })
        assertTrue(TorrentOpen.files(status("gone", dir.path)).isEmpty())
        assertNull(TorrentOpen.main(emptyList()))
    }
}
