package com.diegonmarcos.superapp.launcher

import android.app.Application
import android.os.Process
import com.diegonmarcos.superapp.apps.PhoneApp
import com.diegonmarcos.superapp.apps.PhoneFolders
import com.diegonmarcos.superapp.launcher.folders.FolderGridBridge
import com.diegonmarcos.superapp.launcher.pins.PinnedFolders
import com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore.Kind
import com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore.Pin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** #1860 home shortcuts are filed into the Phone folders like apps: classification, Others fallback, override wins, no collision, parity. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PinnedFoldersTest {
    private val folders = listOf(
        PhoneFolders.Folder("media", "a", "+Media Center", listOf("youtube", "pkg:com.netflix.mediaclient")),
        PhoneFolders.Folder("news", "b", "+News", listOf("lbl~news")),
        PhoneFolders.Folder("others", "z", "Others", emptyList(), sink = true),
    )
    private val me = Process.myUserHandle()
    private val browser = "com.diegonmarcos.cloudbrowser"
    private fun pin(id: String, label: String, url: String = "https://x.example/$id", pkg: String = browser) = Pin(id, pkg, label, Kind.IPC, url, "", 1)
    private fun app(p: Pin) = PinnedFolders.toApp(p, null, me)
    private fun appOf(pkg: String) = PhoneApp(pkg, null, pkg, null, me)

    /** The Phone page's path: baked grouping + pins merged, then the user's overrides. */
    private fun page(pins: List<Pin>, overrides: Map<String, String> = emptyMap(), base: Map<String, List<PhoneApp>> = folders.associate { it.id to emptyList() }) =
        FolderGridBridge.applyOverrides(PinnedFolders.merge(base, pins, folders, ::app), overrides, folders.map { it.id }.toSet())

    @Test fun `a pin is classified by its url host into the matching folder`() {
        assertEquals("media", PinnedFolders.classify(pin("a", "Clips", "https://www.youtube.com/watch?v=1"), folders))
    }

    @Test fun `a pin is classified by its label`() {
        assertEquals("news", PinnedFolders.classify(pin("b", "Daily News"), folders))
    }

    @Test fun `no match falls to Others`() {
        assertEquals("others", PinnedFolders.classify(pin("c", "My wiki", "https://wiki.example/x"), folders))
    }

    @Test fun `a SYSTEM pin without a url is read through its owner package`() {
        assertEquals("media", PinnedFolders.classify(Pin("s", "com.netflix.mediaclient", "Show", Kind.SYSTEM), folders))
    }

    @Test fun `a user override wins over the classification`() {
        val p = pin("a", "Clips", "https://www.youtube.com/watch")
        val out = page(listOf(p), mapOf(PinnedFolders.appKey(p) to "news"))
        assertEquals(listOf(PinnedFolders.appKey(p)), out["news"]!!.map { it.packageName })
        assertTrue(out["media"]!!.isEmpty())
    }

    @Test fun `a pin key never collides with a real package and is not a legal package name`() {
        val p = pin("com.netflix.mediaclient", "Netflix", "https://www.youtube.com/x", pkg = "com.netflix.mediaclient")
        assertEquals("pin:com.netflix.mediaclient/com.netflix.mediaclient", PinnedFolders.appKey(p))
        assertTrue(PinnedFolders.isAppKey(PinnedFolders.appKey(p)))
        assertFalse(PinnedFolders.isAppKey("com.netflix.mediaclient"))
        val out = page(listOf(p), base = mapOf("media" to listOf(appOf("com.netflix.mediaclient")), "news" to emptyList(), "others" to emptyList()))
        assertEquals(listOf("com.netflix.mediaclient", PinnedFolders.appKey(p)), out["media"]!!.map { it.packageName })
        // an override on the real package moves only the real app
        val moved = page(listOf(p), mapOf("com.netflix.mediaclient" to "news"), mapOf("media" to listOf(appOf("com.netflix.mediaclient")), "news" to emptyList(), "others" to emptyList()))
        assertEquals(listOf(PinnedFolders.appKey(p)), moved["media"]!!.map { it.packageName })
    }

    @Test fun `PARITY every pin in the store is in exactly one Phone folder`() {
        val pins = listOf(pin("a", "Clips", "https://youtube.com/x"), pin("b", "Daily News"), pin("c", "Wiki", "https://wiki.example"), pin("d", "Other", pkg = "other.app"))
        for (ov in listOf<Map<String, String>>(emptyMap(), mapOf(PinnedFolders.appKey(pins[2]) to "media"))) {
            val out = page(pins, ov)
            for (p in pins) assertEquals("pin ${p.key}", 1, out.values.sumOf { l -> l.count { it.packageName == PinnedFolders.appKey(p) } })
            assertEquals(pins.size, out.values.sumOf { it.size })
        }
    }

    @Test fun `merge never mutates the shared grouping and keeps every app`() {
        val base = mapOf("media" to listOf(appOf("m.one")), "news" to emptyList<PhoneApp>(), "others" to listOf(appOf("o.one")))
        val before = base.mapValues { it.value.toList() }
        val out = page(listOf(pin("a", "Clips", "https://youtube.com/x")), base = base)
        assertEquals(before, base)
        assertEquals(listOf("m.one"), out["media"]!!.map { it.packageName }.filter { !it.startsWith("pin:") })
        assertEquals(listOf("o.one"), out["others"]!!.map { it.packageName })
    }

    @Test fun `no pins returns the same grouping`() {
        val base = mapOf("media" to listOf(appOf("m.one")))
        assertTrue(PinnedFolders.merge(base, emptyList(), folders, ::app) === base)
    }

    @Test fun `hostOf reads the authority only`() {
        assertEquals("a.example", PinnedFolders.hostOf("https://u@A.example:8443/p?q#f"))
        assertEquals("", PinnedFolders.hostOf("not a url"))
    }
}
