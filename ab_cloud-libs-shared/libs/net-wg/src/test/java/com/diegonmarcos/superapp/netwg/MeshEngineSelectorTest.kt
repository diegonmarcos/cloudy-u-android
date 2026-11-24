package com.diegonmarcos.superapp.netwg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/** #1881 the engine switch: the default stays wg-go; firestack only on an explicit flag AND availability. */
class MeshEngineSelectorTest {
    private fun dirWith(text: String?): File {
        val d = File.createTempFile("mesh", "d").also { it.delete(); it.mkdirs() }
        if (text != null) File(d, MeshEngineSelector.FLAG_FILE).writeText(text)
        return d
    }

    @Test fun `no flag selects wg-go`() {
        assertEquals("wg-go", MeshEngineSelector.select(null, true))
        assertEquals("wg-go", MeshEngineSelector.select(null, false))
    }

    @Test fun `firestack needs the flag and the engine`() {
        assertEquals("firestack", MeshEngineSelector.select("firestack", true))
        assertEquals("firestack", MeshEngineSelector.select(" FireStack\n", true))
        assertEquals("wg-go", MeshEngineSelector.select("firestack", false))
    }

    @Test fun `unknown or empty values fall back to wg-go`() {
        for (v in listOf("", " ", "wg-go", "go", "1", "firestack2", "true")) {
            assertEquals(v, "wg-go", MeshEngineSelector.select(v, true))
        }
    }

    @Test fun `the flag file drives choose and a missing dir is the default`() {
        assertEquals("firestack", MeshEngineSelector.choose(dirWith("firestack\n")) { true })
        assertEquals("wg-go", MeshEngineSelector.choose(dirWith("firestack")) { false })
        assertEquals("wg-go", MeshEngineSelector.choose(dirWith("wg-go")) { true })
        assertEquals("wg-go", MeshEngineSelector.choose(dirWith(null)) { true })
        assertEquals("wg-go", MeshEngineSelector.choose(null) { true })
        assertNull(MeshEngineSelector.readFlag(dirWith(null)))
    }

    @Test fun `the default path never probes the second engine`() {
        var probed = false
        MeshEngineSelector.choose(dirWith(null)) { probed = true; true }
        MeshEngineSelector.choose(dirWith("wg-go")) { probed = true; true }
        assertFalse("default must not load libgojni", probed)
    }

    @Test fun `an absent mesh class makes load null so the selector keeps wg-go`() {
        assertNull(FirestackMeshEngine.load())
    }
}
