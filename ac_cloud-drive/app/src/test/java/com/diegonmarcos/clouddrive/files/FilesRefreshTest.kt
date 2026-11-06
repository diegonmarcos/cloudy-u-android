package com.diegonmarcos.clouddrive.files

import android.os.Looper
import com.diegonmarcos.clouddrive.DrivePrefs
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The Explorer's pull-to-refresh calls [FilesController.refreshPane]: it re-scans the folder the pane shows, so a file that appeared on disk after the first load is listed. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FilesRefreshTest {
    private fun names(c: FilesController, loc: Location) = c.listings.value[loc.key]?.entries?.map { it.name }?.sorted().orEmpty()

    private fun settle(c: FilesController, loc: Location, want: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < end) { shadowOf(Looper.getMainLooper()).idle(); if (c.listings.value[loc.key]?.loading == false && want()) return; Thread.sleep(20) }
    }

    @Test fun `pulling to refresh re-scans the shown folder`() {
        val root = Files.createTempDirectory("pull").toFile()
        val other = Files.createTempDirectory("pull2").toFile()
        File(root, "a.txt").writeText("a")
        val loc = Location.Local(root.absolutePath)
        val c = FilesController(RuntimeEnvironment.getApplication(), FilesUiState.initial(loc, Location.Local(other.absolutePath), "name", false, false), CoroutineScope(SupervisorJob() + Dispatchers.Main), DrivePrefs(RuntimeEnvironment.getApplication()))
        c.ensureLoaded(loc)
        settle(c, loc) { names(c, loc) == listOf("a.txt") }
        assertEquals(listOf("a.txt"), names(c, loc))
        File(root, "b.txt").writeText("b")
        c.refreshPane(PaneId.A)
        settle(c, loc) { names(c, loc).size == 2 }
        assertEquals(listOf("a.txt", "b.txt"), names(c, loc))
    }
}
