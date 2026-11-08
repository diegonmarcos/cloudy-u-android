package com.diegonmarcos.cgomniverse

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stats HUD's parsing and layout: real numbers shown as they are, anything missing shown as n/a, never a crash. */
class GraphicsStatsTest {
    @Test fun anAvatarStatsObjectParses() {
        val s = GraphicsStats.parseAvatar("""{"fps":29.7,"frameMs":33.7,"renderMs":4.1,"drawCalls":42,"triangles":123456,"vertices":98765,
            "geometries":30,"textures":12,"gpu":"Adreno (TM) 740","lite":false,"canvas":[1080,1920],"background":"stars"}""")!!
        assertEquals(29.7, s.fps!!, 0.0); assertEquals(42L, s.drawCalls); assertEquals(123456L, s.triangles); assertEquals(98765L, s.vertices)
        assertEquals(12L, s.textures); assertEquals("Adreno (TM) 740", s.gpu); assertEquals("bg stars 1080x1920", s.note)
        val l = GraphicsStats.lines(s)
        assertTrue(l.contains("TRIS       123,456")); assertTrue(l.contains("FRAME      33.7 ms")); assertTrue(l.contains("GPU        Adreno (TM) 740"))
    }

    @Test fun garbageIsNullAndNullRendersNa() {
        assertNull(GraphicsStats.parseAvatar(null)); assertNull(GraphicsStats.parseAvatar("null")); assertNull(GraphicsStats.parseAvatar("not json"))
        assertTrue(GraphicsStats.lines(null).all { it.endsWith(GraphicsStats.NA) })
        val partial = GraphicsStats.lines(GraphicsStats.parseAvatar("""{"fps":60}"""))
        assertEquals("FPS        60.0", partial[0]); assertEquals("DRAWS      n/a", partial[2])
    }

    @Test fun aWorldWithTheHookShowsDrawCallsAndOneWithoutShowsNa() {
        val ok = GraphicsStats.parseWorld("""{"fps":58.2,"ms":16.9,"calls":120,"tris":45000,"hooked":true}""", "Mali-G78")
        assertEquals(120L, ok.drawCalls); assertEquals(45000L, ok.triangles); assertEquals("Mali-G78", ok.gpu); assertNull(ok.note)
        val noHook = GraphicsStats.parseWorld("""{"fps":58.2,"ms":16.9,"calls":null,"tris":null,"hooked":false}""", null)
        assertNull(noHook.drawCalls); assertEquals(58.2, noHook.fps!!, 0.0); assertEquals("draw calls not hookable", noHook.note)
        val blocked = GraphicsStats.parseWorld("null", "Mali-G78")
        assertNull(blocked.fps); assertEquals("Mali-G78", blocked.gpu); assertEquals("page blocks injection", blocked.note)
        assertEquals("DRAWS      n/a", GraphicsStats.lines(blocked)[2])
    }

    @Test fun thePageScriptIsGuardedAndIdempotent() {
        assertTrue(GraphicsStats.PAGE_SCRIPT.startsWith("(function(){try{if(window.__cgcStats)return;"))
        assertTrue(GraphicsStats.PAGE_SCRIPT.contains("drawElements") && GraphicsStats.PAGE_SCRIPT.contains("requestAnimationFrame"))
    }
}
