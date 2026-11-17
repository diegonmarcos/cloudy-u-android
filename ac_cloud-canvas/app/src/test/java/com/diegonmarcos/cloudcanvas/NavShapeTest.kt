package com.diegonmarcos.cloudcanvas

import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The nav shape: build.json::ui declares the bottom nav Draw | Play, each id a section, and the default is one of them. */
class NavShapeTest {
    private fun ui(): JSONObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return JSONObject(File(dir!!, "build.json").readText()).getJSONObject("ui")
    }

    @Test fun bottomNavIsDrawThenPlay() {
        val u = ui()
        val bar = (0 until u.getJSONArray("bottom_nav").length()).map { u.getJSONArray("bottom_nav").getString(it) }
        assertEquals(listOf("draw", "play"), bar)
        assertEquals("draw", u.getString("default_section"))
    }

    @Test fun everyBarIdIsASectionWithAnIcon() {
        val u = ui()
        val secs = u.getJSONArray("sections")
        val ids = (0 until secs.length()).map { secs.getJSONObject(it) }
        assertEquals(listOf("draw", "play"), ids.map { it.getString("id") })
        assertTrue(ids.all { it.getString("icon").isNotBlank() && it.getString("label").isNotBlank() })
        assertEquals(Decl.SECTION_DRAW, "draw"); assertEquals(Decl.SECTION_PLAY, "play")
    }
}
