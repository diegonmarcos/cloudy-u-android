package org.fossify.phone.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VoipTest {
    private val spec = VoipSpec.parse(File("../../data/fleet/app/com.diegonmarcos.comms.dialer/voip_providers.json").readText())
    private val wab = spec.providers.first { it.id == "whatsapp_business" }

    @Test fun declaredProviderIsWhatsAppBusinessWithFallback() {
        assertEquals("https://wa.me/{number}", wab.uri)
        assertEquals(listOf("com.whatsapp.w4b", "com.whatsapp"), wab.packages)
        assertEquals("Open WhatsApp Business", wab.label)
    }

    @Test fun resolvesToW4bWhenInstalled() {
        val t = Voip.resolve(wab, "+34 600-111 222") { it == "com.whatsapp.w4b" || it == "com.whatsapp" }!!
        assertEquals("https://wa.me/34600111222", t.uri)
        assertEquals("com.whatsapp.w4b", t.packageName)
    }

    @Test fun fallsBackToWhatsAppThenToThePlainLink() {
        assertEquals("com.whatsapp", Voip.resolve(wab, "34600111222") { it == "com.whatsapp" }!!.packageName)
        assertNull(Voip.resolve(wab, "34600111222") { false }!!.packageName)
    }

    @Test fun noDigitsNoTarget() {
        assertNull(Voip.resolve(wab, " +- ") { true })
    }

    @Test fun recentsAreNewestFirstDistinctAndLimited() {
        val all = listOf(
            T9Contact("A", "111", lastMillis = 10), T9Contact("B", "222", lastMillis = 30), T9Contact("C", "333"),
            T9Contact("B2", "2 22", lastMillis = 20), T9Contact("D", "444", lastMillis = 5),
        )
        assertEquals(listOf("B", "A", "D"), Voip.recents(all, 3).map { it.name })
        assertEquals(2, Voip.recents(all, 2).size)
        assertTrue(spec.recentLimit > 0)
    }
}
