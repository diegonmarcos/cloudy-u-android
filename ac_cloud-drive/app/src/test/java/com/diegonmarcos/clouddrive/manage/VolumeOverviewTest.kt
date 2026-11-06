package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.superapp.usbdrive.UsbVolumeModel
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1537 Manage ▸ Overview: the volume list from fake StorageManager / StatFs / rclone data, n/a with the reason, the declared order. */
class VolumeOverviewTest {
    private val tmp = Files.createTempDirectory("vol").toFile()
    private val emulated = File(tmp, "emulated").apply { mkdirs() }
    private val termux = File(tmp, "termux").apply { mkdirs() }

    private fun vol(uuid: String?, desc: String, path: String?, removable: Boolean = true, primary: Boolean = false, state: String = UsbVolumeModel.STATE_MOUNTED, total: Long = 32_000, free: Long = 8_000) =
        UsbVolumeModel.Volume(uuid, desc, path, state, removable, primary, total, free)

    private class Fake(
        val emu: File?, val term: File?, val nix: File?, val drives: List<ExternalDrive>, val stats: Map<String, Pair<Long, Long>>,
    ) : VolumeProbe {
        override fun emulated() = emu
        override fun terminalHome() = term
        override fun appHome(fleet: String, relative: String) = if (fleet == "nix-on-droid") nix else null
        override fun externalDrives() = drives
        override fun stat(dir: File) = stats[dir.path]
    }

    private val local = listOf(
        Declarations.ManageSource("emulated", "Emulated", "smartphone", VolumeOverview.KIND_EMULATED),
        Declarations.ManageSource("termux", "Termux home", "terminal", VolumeOverview.KIND_TERMINAL_HOME),
        Declarations.ManageSource("nix", "Nix home", "terminal", VolumeOverview.KIND_APP_HOME, fleet = "nix-on-droid", relative = "files/home"),
        Declarations.ManageSource("external", "External drive", "usb", VolumeOverview.KIND_EXTERNAL),
    )

    @Test fun externalDrivesComeFromStorageManagerVolumes() {
        val volumes = listOf(
            vol(null, "Internal", "/storage/emulated/0", removable = false, primary = true),
            vol("1111-2222", "SD card", "/storage/1111-2222"),
            vol("AAAA", "SanDisk USB drive", "/storage/AAAA", total = 64_000, free = 4_000),
            vol("EJECTED", "Old card", "/storage/EJECTED", state = "unmounted"),
            vol("NOPATH", "No path", null),
        )
        val drives = VolumeOverview.externalFrom(volumes)
        assertEquals("only mounted removable volumes with a path, USB first", listOf("SanDisk USB drive", "SD card"), drives.map { it.label })
        assertEquals(64_000L, drives.first().total)
    }

    @Test fun localRowsFollowTheDeclaredOrderWithUsage() {
        val drives = VolumeOverview.externalFrom(listOf(vol("1111-2222", "SD card", "/storage/1111-2222", total = 32_000, free = 8_000)))
        val nix = File(tmp, "nix").apply { mkdirs() }
        val probe = Fake(emulated, termux, nix, drives, mapOf(emulated.path to (30_000L to 100_000L), termux.path to (30_000L to 100_000L), nix.path to (30_000L to 100_000L)))
        val rows = VolumeOverview.local(local, probe)
        assertEquals(listOf("emulated", "termux", "nix", "external:1111-2222"), rows.map { it.id })
        assertEquals(listOf("Emulated", "Termux home", "Nix home", "SD card"), rows.map { it.label })
        val emu = rows.first()
        assertEquals(70_000L, emu.used); assertEquals(30_000L, emu.free); assertEquals(100_000L, emu.total)
        assertTrue(emu.hasBar); assertEquals(0.7f, emu.fraction, 0.001f)
        assertNull(emu.unavailable)
        val sd = rows.last()
        assertEquals(24_000L, sd.used); assertEquals(8_000L, sd.free); assertEquals(32_000L, sd.total); assertEquals("/storage/1111-2222", sd.path)
        assertTrue(rows.none { it.cloud })
    }

    @Test fun anUnavailableVolumeIsNaWithItsReasonNeverMissing() {
        val rows = VolumeOverview.local(local, Fake(emulated, null, null, emptyList(), mapOf(emulated.path to (1L to 0L))))
        assertEquals(listOf("emulated", "termux", "nix", "external"), rows.map { it.id })
        assertEquals("a zero total is n/a", "the volume reports no size", rows[0].unavailable)
        assertTrue(rows[1].unavailable!!.contains("terminal"))
        assertTrue(rows[2].unavailable!!.contains("nix-on-droid"))
        assertEquals("no external drive is mounted", rows[3].unavailable)
        assertTrue(rows.none { it.hasBar })
        val unreadable = VolumeOverview.local(local.take(1), Fake(emulated, null, null, emptyList(), emptyMap()))
        assertTrue(unreadable.single().unavailable!!.contains("unreadable"))
    }

    @Test fun rootsOfResolvesTheDeclaredKinds() {
        val drives = listOf(ExternalDrive("a", "A", "/storage/a", 1, 1), ExternalDrive("b", "B", "/storage/b", 1, 1))
        val probe = Fake(emulated, termux, null, drives, emptyMap())
        assertEquals(listOf(emulated), VolumeOverview.rootsOf(local[0], probe))
        assertEquals(listOf(termux), VolumeOverview.rootsOf(local[1], probe))
        assertEquals(emptyList<File>(), VolumeOverview.rootsOf(local[2], probe))
        assertEquals(listOf(File("/storage/a"), File("/storage/b")), VolumeOverview.rootsOf(local[3], probe))
    }

    private val remotes = listOf(
        Declarations.RemoteDecl("oracle_s3", "s3", "", "objectstorage.example", "oci-apps", "", "ok", "", "", true),
        Declarations.RemoteDecl("gdrive", "google-drive", "", "drive.google.com", "none", "", "unreachable", "no current host carries the token", "", false),
    )
    private fun provider(id: String, remote: String, others: Boolean = false) = Declarations.MountProviderDecl(id, id.uppercase(), "cloud", remote, "s3", "", "", "", false, "", others)
    private val cloudSources = listOf(
        Declarations.ManageSource("cloud-s3", "Cloud-S3", "cloud", VolumeOverview.KIND_REMOTE, remote = "oracle_s3"),
        Declarations.ManageSource("gdrive", "Google Drive", "cloud", VolumeOverview.KIND_REMOTE, remote = "gdrive"),
        Declarations.ManageSource("mounts", "Mounts", "cloud", VolumeOverview.KIND_MOUNTS),
    )

    @Test fun cloudTargetsAreTheDeclaredRemotesThenTheOtherMounts() {
        val providers = listOf(provider("r2", "r2"), provider("oracle-s3", "oracle_s3"), provider("hetzner", "hetzner_s3"), provider("others", "", others = true))
        val t = VolumeOverview.cloudTargets(cloudSources, remotes, providers)
        assertEquals(listOf("cloud-s3", "gdrive", "mounts:r2", "mounts:hetzner"), t.map { it.id })
        assertEquals("a mount whose remote is already a row is not listed twice", 1, t.count { it.remote == "oracle_s3" })
        assertNotNull(t.first { it.id == "cloud-s3" }.declared)
        assertNull(t.first { it.id == "mounts:r2" }.declared)
    }

    @Test fun cloudUsageUsesRcloneAboutAndExplainsWhatItCannot() {
        val t = VolumeOverview.cloudTargets(cloudSources, remotes, listOf(provider("r2", "r2")))
        var asked = emptyList<String>()
        val about: (String) -> Result<CloudAbout> = { name ->
            asked = asked + name
            when (name) {
                "oracle_s3" -> Result.success(CloudAbout(used = 5_000, total = null, free = null))
                "r2" -> Result.failure(IllegalStateException("couldn't find section in config file\nsecond line"))
                else -> Result.success(CloudAbout(null, null, null))
            }
        }
        val s3 = VolumeOverview.cloudUsage(t[0], about)
        assertEquals(5_000L, s3.used); assertNull(s3.unavailable); assertFalse("no total, no bar", s3.hasBar); assertTrue(s3.cloud)
        val gd = VolumeOverview.cloudUsage(t[1], about)
        assertEquals("a remote declared unreachable shows its declared reason", "no current host carries the token", gd.unavailable)
        assertFalse("and is not asked", "gdrive" in asked)
        val r2 = VolumeOverview.cloudUsage(t[2], about)
        assertEquals("couldn't find section in config file", r2.unavailable)
        val none = VolumeOverview.cloudUsage(VolumeOverview.cloudTargets(listOf(cloudSources[0].copy(remote = "other")), emptyList(), emptyList()).single(), about)
        assertEquals("this backend reports no usage", none.unavailable)
        val full = VolumeOverview.cloudUsage(t[0], { Result.success(CloudAbout(used = 25, total = 100, free = 75)) })
        assertTrue(full.hasBar); assertEquals(0.25f, full.fraction, 0.001f)
    }
}
