package com.diegonmarcos.clouddrive.editor

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.manage.ExternalDrive
import com.diegonmarcos.clouddrive.manage.VolumeOverview
import com.diegonmarcos.clouddrive.manage.VolumeProbe
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1714 the Editor explorer's roots: the declared list and order, the reason for an inaccessible root, and a file's open path. */
class EditorRootsTest {
    private val tmp = java.nio.file.Files.createTempDirectory("edroots").toFile()
    private val emu = File(tmp, "emulated/0").apply { mkdirs() }
    private val termux = File(tmp, "termux").apply { mkdirs() }
    private val nix = File(tmp, "nix").apply { mkdirs() }
    @After fun clean() { tmp.deleteRecursively(); EditorHook.pending.value = null; EditorHook.jump = null }

    private class Probe(val e: File?, val t: File?, val n: File?, val d: List<ExternalDrive>) : VolumeProbe {
        override fun emulated() = e
        override fun terminalHome() = t
        override fun appHome(fleet: String, relative: String) = if (fleet == "nix-on-droid") n else null
        override fun externalDrives() = d
        override fun stat(dir: File): Pair<Long, Long>? = null
    }

    private class Acc(override val hasAllFiles: Boolean = true, val listable: (String) -> Boolean = { true }, val apps: Set<String?> = setOf(null, "nix-on-droid")) : EditorRoots.Access {
        override fun canList(path: String) = listable(path)
        override fun installed(fleet: String?) = fleet in apps
    }

    private val decl = Declarations.ManageDecl(
        listOf(
            Declarations.ManageSource("emulated", "Emulated", "smartphone", VolumeOverview.KIND_EMULATED),
            Declarations.ManageSource("termux", "Termux home", "terminal", VolumeOverview.KIND_TERMINAL_HOME),
            Declarations.ManageSource("nix", "Nix home", "terminal", VolumeOverview.KIND_APP_HOME, fleet = "nix-on-droid", relative = "files/home"),
            Declarations.ManageSource("external", "External drive", "usb", VolumeOverview.KIND_EXTERNAL),
        ), emptyList(), listOf("emulated", "termux", "nix", "external"), "emulated")
    private val sd = ExternalDrive("1111-2222", "SD card", "/storage/1111-2222", 32, 8)
    private val usb = ExternalDrive("AAAA", "USB drive", "/storage/AAAA", 64, 4)

    @Test fun `the root list is Emulated, Termux, Nix, External in the declared order, all accessible when granted`() {
        val r = EditorRoots.roots(decl, Probe(emu, termux, nix, listOf(sd, usb)), Acc())
        assertEquals(listOf("emulated", "termux", "nix", "external"), r.map { it.id })
        assertEquals(listOf("Emulated", "Termux home", "Nix home", "External drive"), r.map { it.label })
        assertTrue(r.all { it.accessible })
        assertEquals(emu.path, EditorRoots.startOf(r[0]))
        assertEquals(listOf("/storage/1111-2222", "/storage/AAAA"), r[3].places.map { it.path })
        assertEquals("/storage/AAAA", EditorRoots.startOf(r[3], 1))
    }

    @Test fun `the real declaration lists the four roots`() {
        assertEquals(listOf("emulated", "termux", "nix", "external"), Declarations.manage.switcher)
        assertTrue(Declarations.manage.local.map { it.id }.containsAll(listOf("emulated", "termux", "nix", "external")))
    }

    @Test fun `no all-files grant gives Emulated and External a grant reason and keeps the root in the list`() {
        val r = EditorRoots.roots(decl, Probe(emu, termux, nix, listOf(sd)), Acc(hasAllFiles = false))
        assertEquals(4, r.size)
        assertEquals(EditorRoots.Reason.NO_STORAGE_GRANT, r[0].reason)
        assertFalse(r[0].accessible)
        assertNull(EditorRoots.startOf(r[0]))
        assertEquals(EditorRoots.Reason.NO_STORAGE_GRANT, r[3].reason)
        assertTrue(r[1].accessible && r[2].accessible)
    }

    @Test fun `an app that is not installed or a home it cannot read is a reason, not a crash`() {
        val notInstalled = EditorRoots.roots(decl, Probe(emu, null, null, emptyList()), Acc(apps = emptySet()))
        assertEquals(EditorRoots.Reason.APP_NOT_INSTALLED, notInstalled[1].reason)
        assertEquals(EditorRoots.Reason.APP_NOT_INSTALLED, notInstalled[2].reason)
        assertEquals(EditorRoots.Reason.NO_DRIVE, notInstalled[3].reason)
        val unreadable = EditorRoots.roots(decl, Probe(emu, termux, nix, listOf(sd)), Acc(listable = { it == emu.path }))
        assertEquals(EditorRoots.Reason.NOT_READABLE, unreadable[1].reason)
        assertEquals(EditorRoots.Reason.NOT_READABLE, unreadable[2].reason)
        assertEquals(EditorRoots.Reason.NOT_READABLE, unreadable[3].reason)
        val throwing = EditorRoots.roots(decl, Probe(emu, termux, nix, listOf(sd)), Acc(listable = { error("vendor storage service") }))
        assertTrue(throwing.none { it.accessible })
    }

    @Test fun `the selector follows the file the Editor shows`() {
        val r = EditorRoots.roots(decl, Probe(emu, termux, nix, listOf(sd)), Acc())
        assertEquals("termux", EditorRoots.rootOfPath(r, termux.path + "/a/b.md")?.id)
        assertEquals("external", EditorRoots.rootOfPath(r, "/storage/1111-2222/x.txt")?.id)
        assertNull(EditorRoots.rootOfPath(r, "/elsewhere/x.txt"))
        assertNull("a sibling with a shared prefix is not inside the root", EditorRoots.rootOfPath(r, termux.path + "-other/x"))
    }

    @Test fun `opening a file from the explorer queues its path for the Editor and respects the type table`() {
        var jumped = 0
        EditorHook.jump = { jumped++ }
        val f = File(termux, "notes.md").apply { writeText("# hi") }
        assertTrue(EditorHook.canOpen(f.name))
        EditorHook.open(f.path)
        assertEquals(f.path, EditorHook.pending.value)
        assertEquals(1, jumped)
        assertFalse("Office stays refused by the existing table", EditorHook.canOpen("a.docx"))
    }
}
