package com.diegonmarcos.superapp.appstore.ventoy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1444 the pure half of the raw Ventoy install: the terminal's manifest, the write plan, the drive pick, the script. */
class VentoyRawInstallTest {
    private val sha = "a".repeat(64)
    private fun manifest(vararg pieces: Triple<Long, Long, Long>, disk: Long = 8L shl 30, payload: Long = pieces.sumOf { it.third }) =
        """{"version":1,"diskBytes":$disk,"payloadBytes":$payload,"payloadSha256":"$sha","pieces":[${
            pieces.joinToString(",") { """{"disk":${it.first},"pay":${it.second},"len":${it.third}}""" }}]}"""

    private val good = manifest(Triple(0, 0, 512), Triple(512, 512, 1_048_064), Triple(1_048_576, 1_048_576, 2_359_296), Triple(8_556_380_160, 3_407_872, 33_554_432))

    @Test fun aValidManifestParsesAndPlansSlicesThatNeverCrossAPiece() {
        val m = VentoyRawInstall.parseManifest(good, 8L shl 30)
        assertEquals(4, m.pieces.size)
        val plan = VentoyRawInstall.slices(m)
        assertEquals(m.payloadBytes, plan.sumOf { it.len.toLong() })
        assertTrue(plan.all { it.len in 1..VentoyRawInstall.CHUNK && it.len % 512 == 0 })
        plan.forEach { s -> assertTrue("slice $s", m.pieces.any { s.disk >= it.disk && s.disk + s.len <= it.disk + it.len && s.pay - s.disk == it.pay - it.disk }) }
        assertEquals(1, plan.count { it.disk == 0L })
    }

    @Test fun aManifestForABiggerDriveOrADamagedOneIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(good, 4L shl 30) }
        // not sector aligned
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(manifest(Triple(0, 0, 500)), 8L shl 30) }
        // overlapping pieces
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(manifest(Triple(0, 0, 1024), Triple(512, 1024, 512)), 8L shl 30) }
        // outside the drive
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(manifest(Triple(8L shl 30, 0, 512)), 8L shl 30) }
        // payload not contiguous / wrong size
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(manifest(Triple(0, 0, 512), Triple(1024, 1024, 512)), 8L shl 30) }
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest(manifest(Triple(0, 0, 512), payload = 1024), 8L shl 30) }
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.parseManifest("""{"version":2,"pieces":[]}""", 8L shl 30) }
    }

    @Test fun neverGuessesWhichDriveToErase() {
        assertEquals("a" to null, VentoyRawInstall.pick(listOf("a")))
        assertNull(VentoyRawInstall.pick(emptyList<String>()).first)
        assertTrue(VentoyRawInstall.pick(emptyList<String>()).second!!.contains("no USB mass-storage drive"))
        assertNull(VentoyRawInstall.pick(listOf("a", "b")).first)
        assertTrue(VentoyRawInstall.pick(listOf("a", "b")).second!!.contains("unplug the others"))
    }

    @Test fun theTerminalScriptCarriesOnlyCheckedValues() {
        val s = VentoyRawInstall.buildScript("print(1)".toByteArray(), 8L shl 30, "https://github.com/ventoy/Ventoy/releases/download/v1/ventoy-1-linux.tar.gz", sha)
        assertTrue(s.contains("mkfs.exfat") && s.contains("--disk-bytes 8589934592") && s.contains("--sha256 $sha"))
        assertTrue(s.contains("exfatprogs"))
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.buildScript(ByteArray(0), 1, "http://x/y", sha) }
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.buildScript(ByteArray(0), 1, "https://x/y'; rm -rf ~ #", sha) }
        assertThrows(IllegalArgumentException::class.java) { VentoyRawInstall.buildScript(ByteArray(0), 1, "https://x/y", "xyz") }
    }

    @Test fun onlyAnArmedTokenReachesTheInstall() {
        val m = VentoyRawInstall::class.java.declaredMethods.single { it.name == "install" }
        assertEquals(VentoyGate.Armed::class.java, m.parameterTypes[0])
        assertTrue(VentoyRawInstall.AVAILABLE)
    }
}
