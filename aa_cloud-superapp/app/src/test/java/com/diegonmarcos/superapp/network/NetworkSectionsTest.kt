package com.diegonmarcos.superapp.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The strip's network icons and the popup's sections come from one ordered list. Pinned here, and
 * both call sites are checked to lay out through it (a hand-ordered list in either would drift).
 */
class NetworkSectionsTest {

    @Test fun `#1684 the owner's order - 5G WiFi BT HS GPS, WG KDE, WebS ADB SSHd, TypC`() {
        assertEquals(listOf("cellular", "wifi", "bluetooth", "hotspot", "gps", "mesh", "kde", "webs", "adb", "ssh", "data"), NetworkSections.ORDER)
        assertEquals(listOf("WiFi", "BT", "HS", "GPS", "WG", "KDE", "WebS", "ADB", "SSHd", "TypC"),
            NetworkSections.ORDER.drop(1).map { NetworkSections.LABELS.getValue(it) })
        assertEquals(NetworkSections.ORDER, NetworkSections.GROUPS.flatten())
        assertEquals(listOf(5, 2, 3, 1), NetworkSections.GROUPS.map { it.size })
    }

    @Test fun `#1684 separators sit exactly between groups`() {
        val o = NetworkSections.ORDER
        val seps = (1 until o.size).filter { NetworkSections.separatorBetween(o[it - 1], o[it]) }.map { o[it - 1] + "|" + o[it] }
        assertEquals(listOf("gps|mesh", "kde|webs", "ssh|data"), seps)
        assertTrue("no separator before the first", !NetworkSections.separatorBetween(null, o.first()))
        assertTrue("non-network ids never separate", !NetworkSections.separatorBetween("gps", "clock"))
    }

    @Test fun `#1684 parity - every pre-1684 section and label is still there, Data only renamed`() {
        val before = listOf("cellular", "wifi", "bluetooth", "mesh", "kde", "adb", "data", "hotspot", "gps")
        assertTrue(NetworkSections.ORDER.containsAll(before))
        val labels = mapOf("wifi" to "WiFi", "bluetooth" to "BT", "mesh" to "WG", "kde" to "KDE", "adb" to "ADB", "hotspot" to "HS", "gps" to "GPS")
        for ((k, v) in labels) assertEquals(v, NetworkSections.LABELS[k])
        assertEquals("TypC", NetworkSections.LABELS[NetworkSections.DATA])
        assertEquals("Data", NetworkSections.DATA_FORMER_LABEL)
        assertTrue(NetworkSections.SETTINGS.keys.containsAll(before))
    }

    @Test fun `inOrder returns any keyed map in the shared order`() {
        val popupSections = NetworkSections.ORDER.reversed().associateWith { "section:$it" }
        val stripIcons = NetworkSections.ORDER.shuffled(java.util.Random(7)).associateWith { "icon:$it" }
        assertEquals(NetworkSections.inOrder(popupSections).map { it.removePrefix("section:") },
            NetworkSections.inOrder(stripIcons).map { it.removePrefix("icon:") })
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a section missing from either side fails loudly`() {
        NetworkSections.inOrder(NetworkSections.ORDER.drop(1).associateWith { it })
    }

    @Test fun `every section has a settings target`() {
        assertEquals(NetworkSections.ORDER.toSet(), NetworkSections.SETTINGS.keys)
        for ((k, t) in NetworkSections.SETTINGS) when (t) {
            is NetworkSections.Target.System -> {
                assertTrue("$k has a fallback chain", t.tries.size >= 2)
                assertTrue("$k ends on Settings itself", t.tries.last() == "action:android.settings.SETTINGS")
                for (s in t.tries) assertTrue("$k: $s", s.startsWith("action:") || (s.startsWith("component:") && s.contains('/')))
            }
            is NetworkSections.Target.InApp -> assertTrue(t.page.isNotBlank())
            is NetworkSections.Target.AdbOwner -> assertTrue(t.label.isNotBlank())
            is NetworkSections.Target.FleetApp -> assertTrue(t.fleetId.isNotBlank())
        }
        val s = NetworkSections.SETTINGS
        assertEquals(NetworkSections.Target.InApp("config", "wg", "Cloud Mesh ›"), s[NetworkSections.MESH])
        assertEquals(NetworkSections.Target.InApp("config", "kde", "Peer Control ›"), s[NetworkSections.KDE])
        assertEquals(NetworkSections.Target.AdbOwner("ADB Shell ›"), s[NetworkSections.ADB])
        assertEquals("action:android.settings.LOCATION_SOURCE_SETTINGS", (s[NetworkSections.GPS] as NetworkSections.Target.System).tries[0])
        assertEquals("action:android.settings.WIFI_SETTINGS", (s[NetworkSections.WIFI] as NetworkSections.Target.System).tries[0])
        assertEquals("action:android.settings.BLUETOOTH_SETTINGS", (s[NetworkSections.BLUETOOTH] as NetworkSections.Target.System).tries[0])
        assertTrue((s[NetworkSections.CELLULAR] as NetworkSections.Target.System).perSim)
    }

    @Test fun `a divider sits between every two sections, a group divider where the group changes`() {
        val l = NetworkSections.layout()
        val sections = l.filterIsInstance<NetworkSections.Item.Section>().map { it.key }
        assertEquals("#1684 dropdown order equals strip order", NetworkSections.ORDER, sections)
        for (i in 1 until l.size) if (l[i] is NetworkSections.Item.Section) {
            val prev = (l.subList(0, i - 1).lastOrNull { it is NetworkSections.Item.Section } as? NetworkSections.Item.Section)?.key
            val key = (l[i] as NetworkSections.Item.Section).key
            assertEquals(if (NetworkSections.separatorBetween(prev, key)) NetworkSections.Item.GroupDivider else NetworkSections.Item.Divider, l[i - 1])
        }
        assertTrue(l.first() is NetworkSections.Item.Section)
        assertEquals(NetworkSections.Item.Divider, l.last())   // before the trailing Network block
        assertEquals(NetworkSections.GROUPS.size - 1, l.count { it == NetworkSections.Item.GroupDivider })
        assertEquals(NetworkSections.ORDER.size, l.count { it == NetworkSections.Item.Divider || it == NetworkSections.Item.GroupDivider })
    }

    @Test fun `the popup lays out via NetworkSections layout, the strip via the declared status bar`() {
        val src = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "src/main/java/com/diegonmarcos/superapp") }.first { it.isDirectory }
        val popup = File(src, "network/NetworkInfoPopup.kt").readText()
        assertTrue("the popup must lay out via NetworkSections.layout", popup.contains("NetworkSections.layout()"))
        assertTrue("the popup draws the divider item", popup.contains("is NetworkSections.Item.Divider -> PopDivider()"))
        assertTrue("every header carries its settings link", popup.contains("NetworkSections.SETTINGS.getValue(key)"))
        val strip = File(src, "launcher/LauncherStatusStrip.kt").readText()
        // #1332: the strip draws build.json::home_status_bar; its network elements are exactly NetworkSections.ORDER, in that order.
        assertTrue("the strip must lay out via the declared status bar", strip.contains("StatusBarPrefs.layout(ctx)"))
        val buildJson = generateSequence(src) { it.parentFile }.map { File(it, "build.json") }.first { it.isFile }.readText()
        val bar = buildJson.substring(buildJson.indexOf("\"home_status_bar\""), buildJson.indexOf("\"toolbar_row\""))
        val declared = Regex("""\{"id": "([a-z]+)"[^}]*"kind": "network", "position": "left", "order": (\d+)""").findAll(bar)
            .map { it.groupValues[2].toInt() to it.groupValues[1] }.sortedBy { it.first }.map { it.second }.toList()
        assertEquals("the declared network elements are the popup's section order", NetworkSections.ORDER, declared)
    }
}
