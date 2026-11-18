package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.painter.PainterStore
import java.io.File
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * #1808 Painter's storage: the page's state (painted cells per shape, colour, mode, scale, 2D/3D) saved whole in app storage, read back
 * after a restart, and refused when it is not a well-formed state. The grid generators, paint, erase, clear and the stroke rule are the
 * page's own code, tested in node (test/painter/grid.test.js, run by test/test-painter.sh).
 */
class PainterTest {
    private fun dir(): File = Files.createTempDirectory("painter").toFile()

    private fun state(
        shape: String = "hex", mode: String = "paint", layout: String = "2d", scale: Int = 40, color: String = "#ffffff",
        painted: Map<String, Map<String, String>> = emptyMap(),
    ): String {
        val p = JSONObject()
        for (s in PainterStore.SHAPES) p.put(s, JSONObject(painted[s] ?: emptyMap<String, String>()))
        return JSONObject().put("v", 1).put("shape", shape).put("mode", mode).put("layout", layout).put("scale", scale).put("color", color).put("painted", p).toString()
    }

    @Test fun aFreshStoreHasNoState() {
        assertNull(PainterStore(dir()).read())
    }

    @Test fun paintedCellsPerShapeAndTheChoicesSurviveARestart() {
        val d = dir()
        val st = state("triangle", "rubber", "3d", 77, "#3b82f6", mapOf(
            "hex" to mapOf("H,0,0" to "#ef4444", "H,-3,2" to "#22c55e"),
            "square" to mapOf("S,25,-25" to "#a855f7"),
            "triangle" to mapOf("T,-50,0" to "#eab308"),
            "octagon" to mapOf("O,1,1" to "#ffffff", "OS,24,24" to "#ef4444"),
        ))
        PainterStore(d).write(st)
        val back = PainterStore(d).read()   // a new store over the same directory: the app restarted
        assertEquals(st, back)
        val o = JSONObject(back!!)
        assertEquals("triangle", o.getString("shape")); assertEquals("rubber", o.getString("mode")); assertEquals("3d", o.getString("layout"))
        assertEquals(77, o.getInt("scale")); assertEquals("#3b82f6", o.getString("color"))
        assertEquals(2, PainterStore.activeCount(back, "hex")); assertEquals(1, PainterStore.activeCount(back, "square"))
        assertEquals(1, PainterStore.activeCount(back, "triangle")); assertEquals(2, PainterStore.activeCount(back, "octagon"))
    }

    @Test fun paintEraseAndClearAreSavedAsTheyHappen() {
        val s = PainterStore(dir())
        s.write(state(painted = mapOf("hex" to mapOf("H,0,0" to "#ffffff", "H,1,0" to "#ef4444"))))
        assertEquals(2, PainterStore.activeCount(s.read()!!, "hex"))
        s.write(state(painted = mapOf("hex" to mapOf("H,1,0" to "#ef4444"))))         // H,0,0 rubbed out
        assertEquals(1, PainterStore.activeCount(s.read()!!, "hex"))
        s.write(state(painted = mapOf("square" to mapOf("S,0,0" to "#ffffff"))))        // hex cleared, square kept
        assertEquals(0, PainterStore.activeCount(s.read()!!, "hex"))
        assertEquals(1, PainterStore.activeCount(s.read()!!, "square"))
        s.reset()
        assertNull(s.read())
    }

    @Test fun writeIsAtomicAndRefusesAMalformedState() {
        val d = dir()
        val s = PainterStore(d)
        val good = state(painted = mapOf("hex" to mapOf("H,0,0" to "#ffffff")))
        s.write(good)
        for (bad in listOf(
            "not json",
            """{"v":2}""",
            state(shape = "circle"),
            state(mode = "fly"),
            state(layout = "4d"),
            state(scale = 5),
            state(scale = 101),
            state(color = "red"),
            state(painted = mapOf("hex" to mapOf("S,0,0" to "#ffffff"))),      // a square key in the hex map
            state(painted = mapOf("octagon" to mapOf("O,0,0" to "blue"))),
            state(painted = mapOf("octagon" to mapOf("Q,0,0" to "#ffffff"))),
        )) {
            try { s.write(bad); fail("accepted $bad") } catch (e: IllegalArgumentException) { assertTrue(e.message != null) }
            assertEquals(good, s.read())
        }
        assertFalse(File(d, PainterStore.FILE + ".tmp").exists())
        assertTrue(PainterStore.valid(good))
    }

    @Test fun anUnreadableFileLoadsAsNothing() {
        val d = dir()
        File(d, PainterStore.FILE).writeText("{broken")
        assertNull(PainterStore(d).read())
    }

    @Test fun launchExtrasBecomeThePageQuery() {
        assertEquals(listOf("shape", "layout", "mode", "color", "scale"), CanvasStart.PAINTER_EXTRAS)
        assertEquals(PainterStore.SHAPES, listOf("hex", "square", "triangle", "octagon"))
    }
}
