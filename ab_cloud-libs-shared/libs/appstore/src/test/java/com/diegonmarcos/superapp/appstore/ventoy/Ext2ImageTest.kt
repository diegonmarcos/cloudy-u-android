package com.diegonmarcos.superapp.appstore.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** #1405 the persistence image is a file system e2fsck accepts, with the label the distro looks for. */
class Ext2ImageTest {
    private val fsck = listOf("/usr/sbin/e2fsck", "/sbin/e2fsck").map(::File).firstOrNull { it.canExecute() }

    private fun check(bytes: Long, label: String, conf: Boolean) {
        val f = File.createTempFile("vtoy", ".dat")
        try {
            f.outputStream().buffered().use { Ext2Image.write(it, bytes, label, conf) }
            assertEquals(bytes, f.length())
            assumeTrue("e2fsck is not installed", fsck != null)
            val p = ProcessBuilder(fsck!!.path, "-fn", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("e2fsck: $out", 0, p.waitFor())
            assertTrue(out, out.contains("$label: ${if (conf) 12 else 11}/"))
        } finally { f.delete() }
    }

    @Test fun oneMebibyte() = check(1L shl 20, "casper-rw", false)
    @Test fun withPersistenceConf() = check(1L shl 20, "persistence", true)
    @Test fun severalGroupsAndARaggedTail() = check(200_000_000L, "vtoycow", false)
    @Test fun aGroupTooSmallIsDropped() = check((128L shl 20) + 8192, "casper-rw", true)
}
