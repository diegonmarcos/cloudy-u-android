package com.diegonmarcos.superapp.launcher.folders

import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #1553 The two-table core: schema parse, assignment→folder resolution, unassigned→Others, and that scopes are
 * independent data. Pure logic over [FolderTables]; no device, no provider.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class FolderTablesTest {

    // TABLE 1 for a scope: two sections, three folders.
    private val foldersJson = """
        {"folders":[
          {"section":"Work","id":"dev","label":"Dev","icon":"ic_a","order":1},
          {"section":"Work","id":"ops","label":"Ops","icon":"ic_b","order":2},
          {"section":"Life","id":"media","label":"Media","icon":"ic_c","order":1}
        ]}""".trimIndent()
    // TABLE 2: three apps assigned, one (com.x.unset) deliberately absent.
    private val assignJson = """
        {"assignments":[
          {"package":"com.x.ide","folder":"dev","order":1},
          {"package":"com.x.term","folder":"dev","order":0},
          {"package":"com.x.player","folder":"media","order":1}
        ]}""".trimIndent()

    private val apps = listOf(
        FolderApp("com.x.ide", "IDE"), FolderApp("com.x.term", "Term"),
        FolderApp("com.x.player", "Player"), FolderApp("com.x.unset", "Unset"),
    )

    @Test fun `T1 the two tables parse to their schema`() {
        val folders = FolderTables.parseFolders(foldersJson)
        assertEquals(listOf("dev", "ops", "media"), folders.map { it.id })
        assertEquals("Work", folders.first().section)
        assertEquals(1, folders.first().order)
        val a = FolderTables.parseAssignments(assignJson)
        assertEquals(3, a.size)
        assertEquals(FolderAssignment("com.x.term", "dev", 0), a.first { it.pkg == "com.x.term" })
        // bare-array shape also parses, and a malformed/absent dataset is empty, not a crash
        assertEquals(1, FolderTables.parseFolders("""[{"section":"S","id":"f","label":"F","order":5}]""").size)
        assertTrue(FolderTables.parseFolders(null).isEmpty() && FolderTables.parseAssignments("nonsense{").isEmpty())
    }

    @Test fun `T2 assignment resolves each app into its folder, ordered`() {
        val sections = FolderTables.resolve(FolderTables.parseFolders(foldersJson), FolderTables.parseAssignments(assignJson), emptyMap(), apps)
        val dev = sections.flatMap { it.folders }.first { it.def.id == "dev" }
        assertEquals("ordered by assignment order then label", listOf("com.x.term", "com.x.ide"), dev.apps.map { it.pkg })
        val media = sections.flatMap { it.folders }.first { it.def.id == "media" }
        assertEquals(listOf("com.x.player"), media.apps.map { it.pkg })
        // sections carry their folders; Work (dev, ops→empty dropped) before Life
        assertEquals(listOf("Work", "Life", OTHERS_SECTION), sections.map { it.title })
    }

    @Test fun `T3 an unassigned app lands in Others`() {
        val sections = FolderTables.resolve(FolderTables.parseFolders(foldersJson), FolderTables.parseAssignments(assignJson), emptyMap(), apps)
        val others = sections.last()
        assertEquals(OTHERS_SECTION, others.title)
        assertEquals(listOf("com.x.unset"), others.folders.single().apps.map { it.pkg })
        assertEquals(OTHERS_FOLDER_ID, others.folders.single().def.id)
        // an assignment to a folder that does not exist also falls to Others
        val stray = FolderTables.resolve(FolderTables.parseFolders(foldersJson),
            listOf(FolderAssignment("com.x.unset", "ghost", 0)), emptyMap(), apps)
        assertTrue(stray.last().folders.single().apps.any { it.pkg == "com.x.unset" })
    }

    @Test fun `T4 a user override wins over the served assignment`() {
        val sections = FolderTables.resolve(FolderTables.parseFolders(foldersJson), FolderTables.parseAssignments(assignJson),
            mapOf("com.x.ide" to "ops"), apps)
        assertEquals(listOf("com.x.ide"), sections.flatMap { it.folders }.first { it.def.id == "ops" }.apps.map { it.pkg })
        assertTrue("ide left dev", sections.flatMap { it.folders }.first { it.def.id == "dev" }.apps.none { it.pkg == "com.x.ide" })
    }

    @Test fun `T5 scopes are independent data`() {
        // Different tables + apps per scope resolve to different results — the resolver is scope-agnostic and the
        // repository feeds it scope-specific data (proved end-to-end in FolderRepositoryTest).
        val cloud = FolderTables.resolve(
            FolderTables.parseFolders("""{"folders":[{"section":"Fleet","id":"infra","label":"Infra","order":1}]}"""),
            FolderTables.parseAssignments("""{"assignments":[{"package":"c.app","folder":"infra","order":1}]}"""),
            emptyMap(), listOf(FolderApp("c.app", "Cloud App")))
        val phone = FolderTables.resolve(FolderTables.parseFolders(foldersJson), FolderTables.parseAssignments(assignJson), emptyMap(), apps)
        assertEquals(listOf("Fleet"), cloud.map { it.title })
        assertTrue(phone.map { it.title }.contains("Work"))
        assertTrue("no cross-scope leakage", cloud.flatMap { it.folders }.flatMap { it.apps }.none { it.pkg.startsWith("com.x") })
    }
}
