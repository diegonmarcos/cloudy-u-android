package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.launcher.pins.AddHomeShortcutReceiver
import com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore
import com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore.Kind
import com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1840 the SuperApp home's pinned shortcuts: the stored format, add / replace / remove, the IPC's acceptance rules, and the manifest doors. */
class PinnedShortcutStoreTest {
    private fun pin(id: String, pkg: String = "com.diegonmarcos.cloudbrowser", kind: Kind = Kind.IPC) = Pin(id, pkg, "L$id", kind, "https://h.example/$id", "/i/$id.png", 5)

    @Test fun `a pin round trips and the same key replaces`() {
        val a = pin("a"); val b = pin("b", kind = Kind.SYSTEM)
        val back = PinnedShortcutStore.parse(PinnedShortcutStore.serialize(listOf(a, b)))
        assertEquals(listOf(a, b), back)
        val replaced = PinnedShortcutStore.withPin(back, a.copy(label = "New"))
        assertEquals(listOf("b", "a"), replaced.map { it.id }); assertEquals("New", replaced.last().label)
    }

    @Test fun `a pin is removable and removal leaves the others`() {
        val l = listOf(pin("a"), pin("b"))
        assertEquals(listOf("b"), PinnedShortcutStore.without(l, PinnedShortcutStore.keyOf("com.diegonmarcos.cloudbrowser", "a")).map { it.id })
    }

    @Test fun `the home keeps at most MAX pins, the oldest go, junk rows are skipped`() {
        var l = emptyList<Pin>()
        repeat(PinnedShortcutStore.MAX + 5) { l = PinnedShortcutStore.withPin(l, pin("p$it")) }
        assertEquals(PinnedShortcutStore.MAX, l.size); assertEquals("p5", l.first().id)
        assertTrue(PinnedShortcutStore.parse("[{\"id\":\"\"},{\"pkg\":\"x\"},7,{\"id\":\"i\",\"pkg\":\"p\"}]").map { it.id } == listOf("i"))
        assertTrue(PinnedShortcutStore.parse("not json").isEmpty())
    }

    @Test fun `only web addresses without credentials are accepted over the IPC`() {
        assertTrue(PinnedShortcutStore.acceptableUrl("https://a.example/p?q=1"))
        assertFalse(PinnedShortcutStore.acceptableUrl("javascript:alert(1)")); assertFalse(PinnedShortcutStore.acceptableUrl("file:///sdcard/x"))
        assertFalse(PinnedShortcutStore.acceptableUrl("https://user:pw@a.example/")); assertFalse(PinnedShortcutStore.acceptableUrl(null))
        assertEquals("host.example", PinnedShortcutStore.cleanLabel("  \n ", "host.example"))
        assertEquals(40, PinnedShortcutStore.cleanLabel("x".repeat(90), "f").length)
    }

    private fun man(): String {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "aa_cloud-superapp/app/src/main/AndroidManifest.xml").isFile && !File(d, "app/src/main/AndroidManifest.xml").isFile) d = d.parentFile
        val f = File(d, "aa_cloud-superapp/app/src/main/AndroidManifest.xml").takeIf { it.isFile } ?: File(d, "app/src/main/AndroidManifest.xml")
        return f.readText()
    }

    @Test fun `the launcher declares the pin activity and the guarded IPC receiver`() {
        val m = man()
        assertTrue(m.contains("android.content.pm.action.CONFIRM_PIN_SHORTCUT") && m.contains(".launcher.pins.PinShortcutActivity"))
        val rx = m.substring(m.indexOf(".launcher.pins.AddHomeShortcutReceiver"))
        assertTrue(rx.substring(0, 400).contains("com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"))
        assertEquals("com.diegonmarcos.superapp.action.ADD_HOME_SHORTCUT", AddHomeShortcutReceiver.ACTION)
    }
}
