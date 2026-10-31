package com.diegonmarcos.clouddrive.usb

import com.diegonmarcos.clouddrive.usb.UsbVolumeModel.DriveState
import com.diegonmarcos.clouddrive.usb.UsbVolumeModel.Kind
import com.diegonmarcos.clouddrive.usb.UsbVolumeModel.Volume
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1375 volume detection and mapping, pinned. Every rule here has a planted mutant in
 * .github/workflows/drive-usb-mutation.yml that this suite must turn red.
 */
class UsbVolumeModelTest {

    private val phone = Volume("primary", "Internal shared storage", "/storage/internal", "mounted", removable = false, primary = true, 128_000_000_000, 40_000_000_000)
    private val usb = Volume("1A2B-3C4D", "SanDisk USB drive", "/storage/1A2B-3C4D", "mounted", removable = true, primary = false, 32_000_000_000, 12_300_000_000)
    private val sd = Volume("AAAA-BBBB", "SD card", "/storage/AAAA-BBBB", "mounted", removable = true, primary = false, 64_000_000_000, 1_000_000_000)

    @Test fun `only a removable, non-primary, mounted volume with a path is a drive`() {
        assertTrue(UsbVolumeModel.isExternalDrive(usb))
        assertFalse("the phone's own storage", UsbVolumeModel.isExternalDrive(phone))
        assertFalse("unmounted", UsbVolumeModel.isExternalDrive(usb.copy(state = "unmounted")))
        assertFalse("ejecting", UsbVolumeModel.isExternalDrive(usb.copy(state = "ejecting")))
        assertTrue("read-only still opens", UsbVolumeModel.isExternalDrive(usb.copy(state = "mounted_ro")))
        assertFalse("no path", UsbVolumeModel.isExternalDrive(usb.copy(path = null)))
        assertFalse("blank path", UsbVolumeModel.isExternalDrive(usb.copy(path = " ")))
        assertFalse("removable but primary (adopted)", UsbVolumeModel.isExternalDrive(usb.copy(primary = true)))
    }

    @Test fun `USB is told from SD by description or path`() {
        assertEquals(Kind.USB, UsbVolumeModel.kindOf(usb))
        assertEquals(Kind.SD_CARD, UsbVolumeModel.kindOf(sd))
        assertEquals(Kind.USB, UsbVolumeModel.kindOf(sd.copy(description = "Generic", path = "/storage/usbotg")))
        assertEquals(Kind.USB, UsbVolumeModel.kindOf(sd.copy(description = "usb storage")))
    }

    @Test fun `the label is the description, else the uuid, else the kind`() {
        assertEquals("SanDisk USB drive", UsbVolumeModel.labelOf(usb))
        assertEquals("1A2B-3C4D", UsbVolumeModel.labelOf(usb.copy(description = "  ")))
        assertEquals("USB drive", UsbVolumeModel.labelOf(usb.copy(description = "", uuid = null, path = "/storage/usb0")))
        assertEquals("SD card", UsbVolumeModel.labelOf(sd.copy(description = "", uuid = null)))
    }

    @Test fun `size hint is free of total in SI units`() {
        assertEquals("12.3 GB free of 32.0 GB", UsbVolumeModel.sizeHint(32_000_000_000, 12_300_000_000))
        assertEquals("", UsbVolumeModel.sizeHint(0, 0))
        assertEquals("free never exceeds total", "1.0 GB free of 1.0 GB", UsbVolumeModel.sizeHint(1_000_000_000, 5_000_000_000))
        assertEquals("999 B", UsbVolumeModel.human(999))
        assertEquals("1.0 kB", UsbVolumeModel.human(1000))
        assertEquals("500 MB", UsbVolumeModel.human(500_000_000))
        assertEquals("2.0 TB", UsbVolumeModel.human(2_000_000_000_000))
    }

    @Test fun `drives lists USB first, then by label, once per path, with their size`() {
        val ds = UsbVolumeModel.drives(listOf(phone, sd, usb, usb.copy(uuid = "dup")))
        assertEquals(listOf("/storage/1A2B-3C4D", "/storage/AAAA-BBBB"), ds.map { it.path })
        assertEquals(Kind.USB, ds[0].kind)
        assertEquals("vol-/storage/1A2B-3C4D", ds[0].id)
        assertEquals("12.3 GB free of 32.0 GB", ds[0].sizeHint)
        assertFalse(ds[0].readOnly)
        assertTrue(UsbVolumeModel.drives(listOf(usb.copy(state = "mounted_ro")))[0].readOnly)
        val two = UsbVolumeModel.drives(listOf(usb.copy(description = "Zeta USB", path = "/storage/Z"), usb.copy(description = "alpha USB", path = "/storage/A")))
        assertEquals(listOf("alpha USB", "Zeta USB"), two.map { it.label })
    }

    @Test fun `after an attach the newly mounted USB drive is opened`() {
        val usb2 = usb.copy(uuid = "9999-0000", path = "/storage/9999-0000", description = "Zz USB")  // sorts AFTER the old drive: the new one must win by path, not by order
        assertEquals("/storage/9999-0000", UsbVolumeModel.toOpen(setOf("/storage/1A2B-3C4D"), listOf(phone, usb, usb2))?.path)
        assertEquals("the only USB drive even if seen before", "/storage/1A2B-3C4D", UsbVolumeModel.toOpen(setOf("/storage/1A2B-3C4D"), listOf(usb))?.path)
        assertNull("an SD card is not what was plugged in", UsbVolumeModel.toOpen(emptySet(), listOf(phone, sd)))
        assertNull("not mounted yet", UsbVolumeModel.toOpen(emptySet(), listOf(usb.copy(state = "checking"))))
    }

    @Test fun `mass storage is class 8 on the device or any interface`() {
        assertTrue(UsbVolumeModel.isMassStorage(0, listOf(8)))
        assertTrue(UsbVolumeModel.isMassStorage(8, emptyList()))
        assertTrue(UsbVolumeModel.isMassStorage(0, listOf(3, 8)))
        assertFalse("a keyboard", UsbVolumeModel.isMassStorage(0, listOf(3)))
        assertFalse("a hub", UsbVolumeModel.isMassStorage(9, listOf(9)))
    }

    @Test fun `detach states - eject then unmount is safe, a pull while mounted is not`() {
        var s = DriveState.MOUNTED
        s = UsbVolumeModel.next(s, UsbVolumeModel.ACTION_MEDIA_EJECT); assertEquals(DriveState.EJECTING, s)
        s = UsbVolumeModel.next(s, UsbVolumeModel.ACTION_MEDIA_UNMOUNTED); assertEquals(DriveState.SAFE_TO_REMOVE, s)
        s = UsbVolumeModel.next(s, UsbVolumeModel.ACTION_MEDIA_REMOVED); assertEquals(DriveState.REMOVED, s)
        assertEquals(DriveState.REMOVED_UNSAFELY, UsbVolumeModel.next(DriveState.MOUNTED, UsbVolumeModel.ACTION_MEDIA_REMOVED))
        assertEquals(DriveState.REMOVED_UNSAFELY, UsbVolumeModel.next(DriveState.SAFE_TO_REMOVE, UsbVolumeModel.ACTION_MEDIA_BAD_REMOVAL))
        assertEquals(DriveState.MOUNTED, UsbVolumeModel.next(DriveState.REMOVED, UsbVolumeModel.ACTION_MEDIA_MOUNTED))
        assertEquals("unknown action keeps the state", DriveState.EJECTING, UsbVolumeModel.next(DriveState.EJECTING, "x"))
        assertEquals(DriveState.REMOVED, UsbVolumeModel.next(DriveState.MOUNTED, UsbVolumeModel.ACTION_USB_DEVICE_DETACHED, sessionClosed = true))
        assertEquals(DriveState.REMOVED_UNSAFELY, UsbVolumeModel.next(DriveState.MOUNTED, UsbVolumeModel.ACTION_USB_DEVICE_DETACHED, sessionClosed = false))
    }

    @Test fun `streams close on every leaving broadcast and not on mount`() {
        listOf(UsbVolumeModel.ACTION_MEDIA_EJECT, UsbVolumeModel.ACTION_MEDIA_UNMOUNTED, UsbVolumeModel.ACTION_MEDIA_REMOVED,
            UsbVolumeModel.ACTION_MEDIA_BAD_REMOVAL, UsbVolumeModel.ACTION_USB_DEVICE_DETACHED).forEach { assertTrue(it, UsbVolumeModel.mustClose(it)) }
        assertFalse(UsbVolumeModel.mustClose(UsbVolumeModel.ACTION_MEDIA_MOUNTED))
        assertEquals(5, UsbVolumeModel.MEDIA_ACTIONS.size)
    }

    @Test fun `media uri to path`() {
        assertEquals("/storage/1A2B-3C4D", UsbVolumeModel.pathOfMediaUri("file:///storage/1A2B-3C4D"))
        assertEquals("/storage/1A2B-3C4D", UsbVolumeModel.pathOfMediaUri("file:///storage/1A2B-3C4D/"))
        assertNull(UsbVolumeModel.pathOfMediaUri("content://x"))
        assertNull(UsbVolumeModel.pathOfMediaUri(null))
    }

    @Test fun `raw paths and copy names`() {
        assertEquals("/", UsbVolumeModel.rawParent("/a"))
        assertEquals("/a", UsbVolumeModel.rawParent("/a/b/"))
        assertEquals("/", UsbVolumeModel.rawParent("/"))
        assertEquals("/a", UsbVolumeModel.rawChild("/", "a"))
        assertEquals("/a/b", UsbVolumeModel.rawChild("/a/", "b"))
        assertTrue(runCatching { UsbVolumeModel.rawChild("/", "..") }.isFailure)
        assertTrue(runCatching { UsbVolumeModel.rawChild("/", "a/b") }.isFailure)
        val taken = setOf("a.txt", "a (1).txt")
        assertEquals("a (2).txt", UsbVolumeModel.freeName("a.txt") { it in taken })
        assertEquals("b.txt", UsbVolumeModel.freeName("b.txt") { it in taken })
        assertEquals("noext (1)", UsbVolumeModel.freeName("noext") { it == "noext" })
        assertEquals(".bashrc (1)", UsbVolumeModel.freeName(".bashrc") { it == ".bashrc" })
    }
}
