package app.sterna.core.data.mail

import app.sterna.core.data.db.EMAIL_PARTS_CREATE_SQL
import app.sterna.core.data.db.EMAIL_PARTS_ORPHAN_SWEEP_SQL
import app.sterna.core.data.db.InlineState
import app.sterna.core.data.filter.SourceText
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.sql.DriverManager
import java.util.Base64

/** #1869 step 6: inline images as files under offline-attachments, refs in the stored map, lazy upgrade of old rows. */
class InlineImageFiles1869Test {
    @get:Rule val tmp = TemporaryFolder()

    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 1, 2, 3)

    @Test fun `a written image is a file in the message's directory and its ref opens it`() {
        val root = tmp.newFolder("offline-attachments")
        val ref = InlineImageFiles.write(root, "acc", "m1", "blob-1", "image/png", png)!!
        assertTrue(InlineImageFiles.isRef(ref))
        val file = File(root, "acc/m1/cid-blob-1")
        assertArrayEquals("the bytes are in the message's attachment directory (the attachments cap counts them)", png, file.readBytes())
        val (type, opened) = InlineImageFiles.open(root, ref)!!
        assertEquals("image/png", type)
        assertEquals(file.canonicalPath, opened.canonicalPath)
        assertTrue("no temp file left behind", File(root, "acc/m1").listFiles()!!.none { it.name.endsWith(".part") })
    }

    @Test fun `a ref that leaves the root or names a missing file opens nothing`() {
        val root = tmp.newFolder("offline-attachments")
        File(tmp.root, "secret").writeText("x")
        assertNull(InlineImageFiles.open(root, InlineImageFiles.ref("image/png", "../secret")))
        assertNull(InlineImageFiles.open(root, InlineImageFiles.ref("image/png", "acc/m1/cid-none")))
        assertNull(InlineImageFiles.open(root, "data:image/png;base64,AAAA"))
        assertNull(InlineImageFiles.parseRef(InlineImageFiles.REF_PREFIX + "image/png;"))
    }

    @Test fun `a ref only ever serves an image type`() {
        val (type, _) = InlineImageFiles.parseRef(InlineImageFiles.ref("text/html\r\nX: y", "a/b/c"))!!
        assertEquals("image/jpeg", type)
        assertEquals("image/svg+xml", InlineImageFiles.parseRef(InlineImageFiles.ref("IMAGE/SVG+XML", "a/b/c"))!!.first)
    }

    @Test fun `a ref whose file was evicted reads as missing, a data URI stays`() {
        val root = tmp.newFolder("offline-attachments")
        val kept = InlineImageFiles.write(root, "acc", "m1", "k1", "image/png", png)!!
        val gone = InlineImageFiles.write(root, "acc", "m1", "k2", "image/png", png)!!
        File(root, "acc/m1/cid-k2").delete()
        val shown = InlineImageFiles.present(root, mapOf("a" to kept, "b" to gone, "c" to "data:image/gif;base64,R0lG"))
        assertEquals(setOf("a", "c"), shown.keys)
        assertEquals("no root (tests): refs cannot be served, so they read as missing", setOf("c"), InlineImageFiles.present(null, shown).keys)
    }

    @Test fun `export and print get self-contained data URIs back`() {
        val root = tmp.newFolder("offline-attachments")
        val ref = InlineImageFiles.write(root, "acc", "m1", "k1", "image/png", png)!!
        val out = InlineImageFiles.selfContained(root, mapOf("a" to ref, "c" to "data:image/gif;base64,R0lG"))
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), out["a"])
        assertEquals("data:image/gif;base64,R0lG", out["c"])
        assertArrayEquals(png, InlineImageFiles.decodeDataUri(out["a"]!!)!!.second)
    }

    @Test fun `an old row is upgraded to files, its map to refs, with one parts row per image`() {
        val root = tmp.newFolder("offline-attachments")
        val legacy = mapOf("logo@x" to InlineImageFiles.dataUri("image/png", png), "odd" to "https://not-ours")
        val (upgraded, rows) = InlineImageFiles.upgrade(root, "acc", "m1", legacy) { cid -> if (cid == "logo@x") "blob-9" else cid }!!
        assertTrue(InlineImageFiles.isRef(upgraded["logo@x"]!!))
        assertEquals("a value that is not a data URI is kept as it is", "https://not-ours", upgraded["odd"])
        assertArrayEquals(png, File(root, "acc/m1/cid-blob-9").readBytes())
        assertEquals(1, rows.size)
        assertEquals("logo@x", rows.single().cid)
        assertEquals("acc/m1/cid-blob-9", rows.single().file)
        assertEquals(png.size.toLong(), rows.single().fileBytes)
        assertTrue(rows.single().inline)
        assertNull("nothing to upgrade twice", InlineImageFiles.upgrade(root, "acc", "m1", upgraded) { it })
    }

    @Test fun `the inline state of a stored body`() {
        assertEquals(InlineState.NONE, InlineImageFiles.stateOf(emptyList(), emptyMap()))
        assertEquals(InlineState.PENDING, InlineImageFiles.stateOf(listOf("a", "b"), mapOf("a" to "x")))
        assertEquals(InlineState.COMPLETE, InlineImageFiles.stateOf(listOf("a", "b"), mapOf("a" to "x", "b" to "y")))
    }

    @Test fun `the orphan sweep drops parts whose body is gone and keeps the rest`() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use {
                it.executeUpdate("CREATE TABLE email_bodies (id TEXT NOT NULL, accountId TEXT NOT NULL, PRIMARY KEY(accountId, id))")
                it.executeUpdate(EMAIL_PARTS_CREATE_SQL)
                it.executeUpdate("INSERT INTO email_bodies VALUES ('m1', 'a')")
                it.executeUpdate("INSERT INTO email_parts VALUES ('a', 'm1', 'k', 'c', 'image/png', NULL, 3, 1, 'a/m1/cid-k', 3)")
                it.executeUpdate("INSERT INTO email_parts VALUES ('a', 'm2', 'k', 'c', 'image/png', NULL, 3, 1, 'a/m2/cid-k', 3)")
                it.executeUpdate("INSERT INTO email_parts VALUES ('b', 'm1', 'k', 'c', 'image/png', NULL, 3, 1, 'b/m1/cid-k', 3)")
                it.executeUpdate(EMAIL_PARTS_ORPHAN_SWEEP_SQL)
                it.executeQuery("SELECT accountId || '/' || emailId FROM email_parts").use { rs ->
                    val left = buildList { while (rs.next()) add(rs.getString(1)) }
                    assertEquals(listOf("a/m1"), left)
                }
            }
        }
    }

    @Test fun `the repository writes files only for plain mail and never on the open path`() {
        val repo = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue(repo.contains("val root = offlineAttachmentsDir?.takeIf { cryptoKindOf(email) == null }"))
        val cached = repo.substringAfter("suspend fun cachedMessage(accountId: String, emailId: String): MessageBody? {").substringBefore("suspend fun readerSnapshot(")
        assertTrue("the upgrade is launched, not awaited", cached.contains("upgradeInlineImagesLater(accountId, emailId)"))
        assertTrue(cached.contains("InlineImageFiles.present(offlineAttachmentsDir, decoded.inlineImages)"))
        val upgrade = repo.substringAfter("private fun upgradeInlineImagesLater(").substringBefore("suspend fun selfContainedInlineImages(")
        assertTrue(upgrade.contains("bgScope.launch {"))
        assertTrue("ciphertext is never upgraded to files", upgrade.contains("if (cryptoKindOf(email) != null) return@runCatching"))
        assertTrue("both eviction paths sweep the parts index", Regex("runCatching \\{ emailPartDao\\?\\.deleteOrphans\\(\\) \\}").findAll(repo).count() >= 2)
    }
}
