package com.diegonmarcos.superapp.launcher.folders

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1553 The populated-tables regression guard the owner asked for: the two SERVED tables are FULL, not an empty
 * structure that leans on Others.
 *
 * It reads the real committed datasets (data/fleet/launcher-folders/<scope>.json and
 * launcher-folder-assignments/<scope>.json) and the authoritative cloud app list
 * (aa_cloud-superapp/data/constellation-fleet.json — the same source the runtime enumerates via
 * BuildConfig.CONSTELLATION_FLEET_B64), then asserts:
 *   • the folder hierarchy is non-trivial — every expected section is present (not a stub);
 *   • EVERY cloud app (constellation kind=="app", non-blank package) is allocated to a declared folder, and
 *     NOTHING falls to Others (the key guard: a new cloud-* app with no assignment fails the build);
 *   • the phone hierarchy is sectioned and every declared fleet app is allocated too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderCoverageTest {

    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "data/fleet").isDirectory }

    private fun dataset(rel: String) = File(repoRoot, rel).readText()

    /** The authoritative cloud app set: constellation entries of kind "app" (default) with a non-blank package. */
    private fun cloudApps(): List<FolderApp> {
        val arr = JSONObject(dataset("aa_cloud-superapp/data/constellation-fleet.json")).getJSONArray("apps")
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("kind", "app") == "app" && it.optString("package").isNotBlank() }
            .map { FolderApp(it.getString("package"), it.optString("display_name").ifBlank { it.optString("label") }) }
            .distinctBy { it.pkg }
    }

    @Test fun `C1 the cloud folder hierarchy is a full taxonomy, not a stub`() {
        val folders = FolderTables.parseFolders(dataset("data/fleet/launcher-folders/cloud.json"))
        val sections = folders.map { it.section }.distinct()
        val expected = listOf(
            "Services & Buro", "Communication", "Media", "Productivity", "Tools",
            "Navigation", "Finance", "CAD/3D", "Web", "System & Fleet",
        )
        assertEquals("every declared section is present, in order", expected, sections)
        assertTrue("the taxonomy is comprehensive (>= 30 folders)", folders.size >= 30)
        // Owner's shape example: section "Services & Buro" contains a folder "Gov & ID".
        assertTrue(folders.any { it.section == "Services & Buro" && it.label == "Gov & ID" })
    }

    @Test fun `C2 EVERY cloud app is allocated to a declared folder — nothing lands in Others`() {
        val folders = FolderTables.parseFolders(dataset("data/fleet/launcher-folders/cloud.json"))
        val assignments = FolderTables.parseAssignments(dataset("data/fleet/launcher-folder-assignments/cloud.json"))
        val apps = cloudApps()
        assertTrue("there is a real fleet to cover", apps.size >= 40)

        val declared = folders.map { it.id }.toSet()
        val assignedPkgs = assignments.associate { it.pkg to it.folderId }

        // Direct coverage: each app has an assignment, and that assignment names a folder that exists.
        val unassigned = apps.filter { assignedPkgs[it.pkg] == null }
        assertTrue("these cloud apps have NO folder assignment: ${unassigned.map { it.pkg }}", unassigned.isEmpty())
        val toGhost = apps.mapNotNull { assignedPkgs[it.pkg] }.filter { it !in declared }
        assertTrue("assignments point at folders that do not exist: $toGhost", toGhost.isEmpty())

        // End-to-end: the resolver places every app into a real section; the Others folder stays empty.
        val resolved = FolderTables.resolve(folders, assignments, emptyMap(), apps)
        val placed = resolved.filter { it.title != OTHERS_SECTION }.flatMap { it.folders }.flatMap { it.apps }.map { it.pkg }.toSet()
        assertEquals("every cloud app is placed in a declared folder", apps.map { it.pkg }.toSet(), placed)
        assertTrue("Others is empty for the cloud scope", resolved.none { it.title == OTHERS_SECTION })
    }

    @Test fun `C3 the phone hierarchy is sectioned and every declared fleet app is allocated`() {
        val folders = FolderTables.parseFolders(dataset("data/fleet/launcher-folders/phone.json"))
        val assignments = FolderTables.parseAssignments(dataset("data/fleet/launcher-folder-assignments/phone.json"))
        assertTrue("phone hierarchy has multiple sections", folders.map { it.section }.distinct().size >= 5)
        assertTrue("phone hierarchy is non-trivial", folders.size >= 20)
        assertTrue("phone apps are allocated", assignments.size >= 30)
        val declared = folders.map { it.id }.toSet()
        val toGhost = assignments.map { it.folderId }.filter { it !in declared }
        assertTrue("phone assignments point at folders that do not exist: $toGhost", toGhost.isEmpty())
    }
}
