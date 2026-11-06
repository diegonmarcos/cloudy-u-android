package com.diegonmarcos.clouddrive.files

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guard: the Explorer and Manage screens never read the disk or StorageManager while composing. Every call below stats folders or
 * asks the platform, so it must sit on a line that moves it to Dispatchers.IO (produceState / withContext); a bare call in a
 * composable ran on the main thread on every recomposition and made the page stutter.
 */
class NoMainThreadDiskTest {
    private val base = "src/main/java/com/diegonmarcos/clouddrive"
    private val screens = listOf("files/FilesScreen.kt", "files/ExplorerScreen.kt", "manage/ManageScreen.kt")
    private val blocking = listOf("Places.rootLabel(", "Places.discovered(", "StorageVolumes.drives(", "ExplorerSearchRoots.list(", "VolumeOverview.rootsOf(", ".listFiles(")

    @Test fun `blocking calls in the screens are always on an IO dispatcher line`() {
        val bad = screens.flatMap { f ->
            File("$base/$f").readLines().withIndex().filter { (_, l) -> blocking.any { it in l } && !l.trimStart().startsWith("//") && !l.trimStart().startsWith("*") && "Dispatchers.IO" !in l && "object ExplorerSearchRoots" !in l && "fun list()" !in l && !l.contains("listFiles()?.filter { it.isDirectory && it.name != \"emulated\"") }
                .map { (i, l) -> "$f:${i + 1}: ${l.trim().take(120)}" }
        }
        assertTrue("blocking call on the composition path:\n" + bad.joinToString("\n"), bad.isEmpty())
    }
}
