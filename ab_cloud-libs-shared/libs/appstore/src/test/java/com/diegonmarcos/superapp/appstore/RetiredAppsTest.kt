package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1707 the retired-apps declaration parses, never crashes on bad data, and names Cloud Writer. */
class RetiredAppsTest {
    private fun asset(): String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "src/main/assets/${RetiredApps.ASSET}") }.first { it.isFile }.readText()

    @Test fun declaredFileHasCloudWriterRetiredIntoTheKeyboard() {
        val e = RetiredApps.parse(asset()).single { it.pkg == "com.diegonmarcos.cloudwriter" }
        assertEquals("Cloud Writer", e.label)
        assertEquals("com.diegonmarcos.cloudkeyboard", e.replacementPackage)
        assertTrue(RetiredApps.explain(e).contains("Cloud Keyboard > Writer"))
        assertTrue(RetiredApps.meta(e).startsWith("retired"))
    }

    @Test fun badInputYieldsNothingNotACrash() {
        assertTrue(RetiredApps.parse(null).isEmpty())
        assertTrue(RetiredApps.parse("not json").isEmpty())
        assertTrue(RetiredApps.parse("{\"apps\":[1,{\"label\":\"x\"}]}").isEmpty())
        assertEquals("p", RetiredApps.parse("{\"apps\":[{\"package\":\"p\"}]}").single().label)
    }
}
