package com.diegonmarcos.superapp.appstore.ventoy

import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest

/** A drive in memory: files and directories by absolute path. */
class FakeDrive : DriveFs {
    val files = linkedMapOf<String, ByteArray>()
    val dirs = linkedSetOf("/")
    private fun parent(p: String) = p.substringBeforeLast('/', "").ifEmpty { "/" }
    override fun exists(path: String) = path in files || path in dirs
    override fun list(dir: String): List<String> {
        val d = dir.trimEnd('/').ifEmpty { "/" }
        return files.keys.filter { parent(it) == d }.map { it.substringAfterLast('/') } +
            dirs.filter { it != "/" && parent(it) == d }.map { it.substringAfterLast('/') + "/" }
    }
    override fun mkdirs(dir: String) { var p = ""; dir.split('/').filter { it.isNotEmpty() }.forEach { p += "/$it"; require(p !in files); dirs += p } }
    override fun write(path: String, body: (OutputStream) -> Unit) {
        require(parent(path) in dirs) { "no dir ${parent(path)}" }
        val o = ByteArrayOutputStream(); body(o); files[path] = o.toByteArray()
    }
    override fun readText(path: String) = files[path]?.toString(Charsets.UTF_8)
    override fun rename(from: String, toName: String) { files[parent(from) + "/" + toName] = files.remove(from)!! }
    override fun delete(path: String) = files.remove(path) != null || dirs.remove(path)
}

/** #1405 install/remove against a fake volume, the ventoy.json entry, the confirm gate and the internal-storage rule. */
class VentoyDriveTest {

    private val iso = "VENTOY-TEST-IMAGE".toByteArray()
    private val isoSha = MessageDigest.getInstance("SHA-256").digest(iso).joinToString("") { "%02x".format(it) }
    private val src: (OutputStream) -> String = { VentoyCatalogue.copyHashing(ByteArrayInputStream(iso), it) }
    private val small = VentoyDrive.Persistence(Ext2Image.MIN_BYTES, "casper-rw", false)

    @Test fun installPutsTheVerifiedImageInIsoAndLeavesNoPart() {
        val fs = FakeDrive()
        assertEquals(isoSha, VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.ISO_ONLY, null, src))
        assertTrue(iso.contentEquals(fs.files["/ISO/tiny.iso"]))
        assertEquals(listOf("/ISO/tiny.iso"), VentoyDrive.onDrive(fs))
        assertFalse(fs.files.keys.any { it.endsWith(".part") })
        assertNull(fs.readText(VentoyJson.PATH))
        assertEquals(setOf("tiny.iso"), VentoyDrive.installed(fs, listOf("tiny.iso", "other.iso")))
    }

    @Test fun aChecksumMismatchKeepsNothing() {
        val fs = FakeDrive()
        assertThrows(ChecksumMismatch::class.java) { VentoyDrive.install(fs, "tiny.iso", "0".repeat(64), VentoyDrive.Mode.ISO_ONLY, null, src) }
        assertTrue(fs.files.isEmpty())
        assertThrows(ChecksumMismatch::class.java) { VentoyDrive.install(fs, "tiny.iso", "", VentoyDrive.Mode.ISO_ONLY, null, src) }
        assertTrue(fs.files.isEmpty())
    }

    @Test fun aCutDownloadKeepsNothing() {
        val fs = FakeDrive()
        assertThrows(IllegalStateException::class.java) { VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.ISO_ONLY, null) { it.write(1); error("cut") } }
        assertTrue(fs.files.isEmpty())
    }

    @Test fun reinstallReplacesTheOldCopy() {
        val fs = FakeDrive()
        fs.mkdirs("/ISO"); fs.files["/ISO/tiny.iso"] = "old".toByteArray()
        VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.ISO_ONLY, null, src)
        assertTrue(iso.contentEquals(fs.files["/ISO/tiny.iso"]))
    }

    @Test fun persistenceWritesTheImageAndTheVentoyJsonEntry() {
        val fs = FakeDrive()
        VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.LIVE_PERSISTENT, small, src)
        val dat = fs.files["/persistence/tiny.dat"]!!
        assertEquals(Ext2Image.MIN_BYTES, dat.size.toLong())
        assertEquals(0xEF53, (dat[1024 + 56].toInt() and 0xFF) or ((dat[1024 + 57].toInt() and 0xFF) shl 8))
        assertEquals("casper-rw", String(dat, 1024 + 120, 9))
        val j = JSONObject(fs.readText(VentoyJson.PATH)!!)
        val e = j.getJSONArray("persistence").getJSONObject(0)
        assertEquals("/ISO/tiny.iso", e.getString("image"))
        assertEquals("/persistence/tiny.dat", e.getString("backend"))
        // Switching the same image to live-without-persistence drops its entry.
        VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.LIVE, null, src)
        assertFalse(JSONObject(fs.readText(VentoyJson.PATH)!!).has("persistence"))
        assertNull(fs.files["/persistence/tiny.dat"])
    }

    @Test fun removeTakesTheImageItsPersistenceAndItsEntryOnly() {
        val fs = FakeDrive()
        VentoyDrive.install(fs, "tiny.iso", isoSha, VentoyDrive.Mode.LIVE_PERSISTENT, small, src)
        VentoyDrive.install(fs, "keep.iso", isoSha, VentoyDrive.Mode.LIVE_PERSISTENT, small, src)
        fs.files["/notes.txt"] = "mine".toByteArray()
        VentoyDrive.remove(fs, "/ISO/tiny.iso")
        assertNull(fs.files["/ISO/tiny.iso"]); assertNull(fs.files["/persistence/tiny.dat"])
        assertNotNull(fs.files["/ISO/keep.iso"]); assertNotNull(fs.files["/persistence/keep.dat"]); assertNotNull(fs.files["/notes.txt"])
        val left = JSONObject(fs.readText(VentoyJson.PATH)!!).getJSONArray("persistence")
        assertEquals(1, left.length()); assertEquals("/ISO/keep.iso", left.getJSONObject(0).getString("image"))
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.remove(fs, "/notes.txt") }
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.remove(fs, "/ISO/../persistence/keep.iso") }
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.remove(fs, "/persistence/keep.iso") }
    }

    @Test fun ventoyJsonKeepsOtherPluginsAndRefusesBrokenFiles() {
        val cur = """{"theme":{"file":"/ventoy/theme.txt"},"persistence":[{"image":"/ISO/a.iso","backend":["/persistence/a1.dat","/persistence/a2.dat"]}]}"""
        val next = JSONObject(VentoyJson.withPersistence(cur, "/ISO/b.iso", "/persistence/b.dat"))
        assertEquals("/ventoy/theme.txt", next.getJSONObject("theme").getString("file"))
        assertEquals(2, next.getJSONArray("persistence").length())
        assertEquals(listOf("/persistence/a1.dat", "/persistence/a2.dat"), VentoyJson.backendsOf(cur, "/ISO/a.iso"))
        val replaced = JSONObject(VentoyJson.withPersistence(next.toString(), "/ISO/b.iso", "/persistence/b2.dat")).getJSONArray("persistence")
        assertEquals(2, replaced.length())
        assertEquals("/persistence/b2.dat", replaced.getJSONObject(1).getString("backend"))
        assertThrows(IllegalArgumentException::class.java) { VentoyJson.withPersistence("{not json", "/ISO/b.iso", "/persistence/b.dat") }
        assertThrows(IllegalArgumentException::class.java) { VentoyJson.withPersistence(null, "ISO/b.iso", "/persistence/b.dat") }
        assertThrows(IllegalArgumentException::class.java) { VentoyJson.withPersistence(null, "/ISO/b.iso", "/persistence/../b.dat") }
        assertEquals("{}", VentoyJson.withoutImage(VentoyJson.withPersistence(null, "/ISO/b.iso", "/persistence/b.dat"), "/ISO/b.iso").replace(Regex("\\s"), ""))
    }

    @Test fun safeNamesOnly() {
        assertEquals("x.iso", VentoyDrive.safeName("dir/x.iso"))
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.safeName("..") }
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.safeName(".hidden.iso") }
        assertThrows(IllegalArgumentException::class.java) { VentoyDrive.safeName("") }
        assertTrue(VentoyDrive.isVentoy(FakeDrive(), "Ventoy"))
        assertFalse(VentoyDrive.isVentoy(FakeDrive(), "SanDisk"))
        assertTrue(VentoyDrive.isVentoy(FakeDrive().apply { mkdirs("/ventoy") }, "SanDisk"))
    }

    // ── internal storage is never a target ─────────────────────────────────────────────────────────────────────────
    private fun vol(path: String?, primary: Boolean = false, removable: Boolean = true, state: String = UsbVolumeModel.STATE_MOUNTED) =
        UsbVolumeModel.Volume("1A2B-3C4D", "USB drive", path, state, removable, primary, 32_000_000_000, 1)

    @Test fun internalStorageIsNeverATarget() {
        assertEquals(VentoyTarget.Verdict.Ok, VentoyTarget.check(vol("/storage/1A2B-3C4D")))
        listOf(vol("/storage/emulated/0", primary = true), vol("/storage/1A2B-3C4D", primary = true), vol("/storage/emulated/0"), vol("/sdcard"), vol("/data/media/0"),
            vol("/storage/self/primary"), vol("/"), vol("/storage/1A2B-3C4D", removable = false), vol(null),
            vol("/storage/1A2B-3C4D", state = "unmounted"), vol("/storage/1A2B-3C4D", state = UsbVolumeModel.STATE_MOUNTED_READ_ONLY),
        ).forEach { assertTrue("$it", VentoyTarget.check(it) is VentoyTarget.Verdict.Refused) }
        assertEquals(listOf("/storage/1A2B-3C4D"), VentoyTarget.targets(listOf(vol("/storage/emulated/0", primary = true), vol("/storage/1A2B-3C4D"))).map { it.path })
        assertTrue(VentoyTarget.isDriveTree(VentoyTarget.EXTERNAL_AUTHORITY, "1A2B-3C4D:"))
        assertFalse(VentoyTarget.isDriveTree(VentoyTarget.EXTERNAL_AUTHORITY, "primary:"))
        assertFalse(VentoyTarget.isDriveTree(VentoyTarget.EXTERNAL_AUTHORITY, "home:"))
        assertFalse(VentoyTarget.isDriveTree(VentoyTarget.EXTERNAL_AUTHORITY, "1A2B-3C4D:ISO"))
        assertFalse(VentoyTarget.isDriveTree("com.android.providers.downloads.documents", "1A2B-3C4D:"))
    }

    // ── the destructive gate ───────────────────────────────────────────────────────────────────────────────────────
    @Test fun theGateArmsOnlyOnTheExactPhraseForAnExternalDrive() {
        val d = VentoyGate.Drive("SanDisk", 32_000_000_000, vol("/storage/1A2B-3C4D"))
        val shown = VentoyGate.prompt(d)
        assertTrue(shown.contains("SanDisk") && shown.contains("32.0 GB") && shown.contains(VentoyGate.WARNING))
        assertEquals("ALL DATA WILL BE ERASED", VentoyGate.WARNING)
        assertNotNull(VentoyGate.arm(d, "ERASE SanDisk", shown))
        assertNull(VentoyGate.arm(d, "", shown))
        assertNull(VentoyGate.arm(d, "erase sandisk", shown))
        assertNull(VentoyGate.arm(d, "ERASE SanDisk ", shown))
        assertNull(VentoyGate.arm(d, "ERASE SanDisk", "Type ERASE SanDisk"))
        assertNull(VentoyGate.arm(d.copy(volume = vol("/storage/emulated/0", primary = true)), "ERASE SanDisk", shown))
        assertNull(VentoyGate.arm(d.copy(sizeBytes = 0), "ERASE SanDisk", VentoyGate.prompt(d.copy(sizeBytes = 0))))
        // The token has no public constructor: the only door is arm().
        assertTrue(VentoyGate.Armed::class.java.declaredConstructors.filter { !it.isSynthetic }.none { java.lang.reflect.Modifier.isPublic(it.modifiers) })
        assertFalse(VentoyRawInstall.AVAILABLE)
    }
}
