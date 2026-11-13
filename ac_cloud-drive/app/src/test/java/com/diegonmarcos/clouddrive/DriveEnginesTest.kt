package com.diegonmarcos.clouddrive

import com.diegonmarcos.cloudlib.drivecore.DriveCore
import com.diegonmarcos.cloudlib.drivecore.Engine
import com.diegonmarcos.cloudlib.drivecore.Impl
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1729 the declared switches of the Rust engines: build.json::native_core is what the build bakes and what DriveCore applies, and Rust is the default. */
class DriveEnginesTest {
    private fun buildJson(): String {
        val roots = listOf("build.json", "../build.json", "ac_cloud-drive/build.json")
        return roots.map { File(it) }.first { it.isFile }.readText()
    }

    @Test fun everyEngineIsDeclaredAndDefaultsToRust() {
        val json = buildJson()
        val block = json.substring(json.indexOf("\"native_core\""), json.indexOf("\"_doc_not_moved\""))
        for (e in Engine.entries) assertTrue("native_core.engines.${e.id} must be declared as rust", Regex("\"${e.id}\"\\s*:\\s*\"rust\"").containsMatchIn(block))
        assertEquals("hash=rust,walk=rust,diff=rust", BuildConfig.DRIVE_CORE_DEFAULTS)
    }

    @Test fun theBakedDeclarationIsWhatDriveCoreApplies() {
        DriveCore.configure(BuildConfig.DRIVE_CORE_DEFAULTS)
        for (e in Engine.entries) assertEquals(Impl.RUST, DriveCore.impl(e))
        DriveCore.configure("walk=kotlin")
        assertEquals(Impl.KOTLIN, DriveCore.impl(Engine.WALK))
        DriveCore.configure(BuildConfig.DRIVE_CORE_DEFAULTS)
    }

    @Test fun theStartupLineNamesTheCoreAndTheSwitches() {
        val line = DriveEngines.status()
        assertTrue(line, line.startsWith("startup: core="))
        assertTrue(line, line.contains("switches=hash=rust,walk=rust,diff=rust"))
    }

    @Test fun walkAndHashAnswerWithoutTheCoreLoadedByFallingBackToKotlin() {
        // the app's JVM suite does not load libdrive_core_rs.so: the facade must still answer, and say why
        val logs = ArrayList<String>()
        val old = DriveCore.log
        DriveCore.log = { logs += it }
        val d = Files.createTempDirectory("engines").toFile()
        try {
            File(d, "a.bin").writeBytes(ByteArray(100)); File(d, "sub").mkdirs(); File(d, "sub/b.bin").writeBytes(ByteArray(23))
            assertEquals(123L, DriveCore.dirBytes(d.path))
            val h = DriveCore.hashFiles(listOf(File(d, "a.bin").path), DriveCore.SHA256).single()
            assertEquals("", h.error)
            assertEquals(64, h.sha256.length)
        } finally { DriveCore.log = old; d.deleteRecursively() }
    }

    @Test fun theSelfTestComparesRustWithKotlinAndNeverThrows() {
        val d = Files.createTempDirectory("selftest").toFile()
        try {
            val lines = DriveEngines.selfTest(d)
            assertTrue(lines.toString(), lines.any { it.startsWith("selftest: hash rust") && it.contains("known=true") && it.contains("equalToKotlin=true") })
            assertTrue(lines.toString(), lines.any { it.startsWith("selftest: walk rust files=2 dirs=1 bytes=300003") && it.contains("equalToKotlin=true") })
            assertTrue(lines.toString(), lines.any { it.startsWith("selftest: diff rust delta=2") })
            // the app's JVM suite has no libdrive_core_rs.so, so the verdict must NOT claim Rust ran
            if (DriveCore.nativeUnavailableReason() != null) assertTrue(lines.last().startsWith("selftest: FAIL"))
            else assertEquals("selftest: PASS rust", lines.last())
            assertTrue(!File(d, "drivecore-selftest").exists())
        } finally { d.deleteRecursively() }
    }
}
