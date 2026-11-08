package com.diegonmarcos.superapp.cgc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3D background category: each option id is a cgc.js background type, drawn by the model-file engine. */
class BackgroundOpTest {
    private val spec = CgcSpec.parse("""{
      "engine": "model-file",
      "default": {"bg": "solid"},
      "categories": [{"id": "bg", "label": "Background", "kind": "background", "options": [
        {"id": "solid", "label": "Dark", "color": "#121212"},
        {"id": "gradient", "label": "Sky"},
        {"id": "stars", "label": "Space"},
        {"id": "studio", "label": "Studio"}]}]
    }""")

    @Test fun theBackgroundKindIsWiredAndDrawnByTheModelFileEngine() {
        val c = spec.category("bg")!!
        assertFalse(c.soon)
        assertTrue(CgcSpec.KIND_BACKGROUND in CgcSpec.WIRED_KINDS)
        assertTrue(CgcSpec.KIND_BACKGROUND in ModelFileEngine.kinds)
        assertFalse(CgcConfig.soon(c, ModelFileEngine))
    }

    @Test fun eachOptionBecomesABackgroundOpOfThatType() {
        for (t in listOf("solid", "gradient", "stars", "studio")) {
            val cfg = CgcConfig.set(spec, spec.defaults, "bg", t, ModelFileEngine)
            assertEquals(t, CgcConfig.ops(spec, cfg, ModelFileEngine).getJSONObject("background").getString("type"))
        }
        val solid = CgcConfig.ops(spec, spec.defaults, ModelFileEngine).getJSONObject("background")
        assertEquals("#121212", solid.getString("color"))
        assertFalse("no colour declared, none sent", CgcConfig.ops(spec, mapOf("bg" to "stars"), ModelFileEngine).getJSONObject("background").has("color"))
    }

    @Test fun anUndeclaredBackgroundIsRefusedAndASaveRoundTrips() {
        assertEquals(spec.defaults, CgcConfig.set(spec, spec.defaults, "bg", "lava", ModelFileEngine))
        val cfg = CgcConfig.set(spec, spec.defaults, "bg", "stars", ModelFileEngine)
        assertEquals("stars", CgcConfig.load(spec, CgcConfig.save(cfg))["bg"])
    }
}
