package com.diegonmarcos.hardtechcad

import com.diegonmarcos.hardtechcad.ui.parseParts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1695 the host's Parts panel reads the page's cadParts() answer; a bad answer is no parts, never a crash. */
class HostPartsTest {
    @Test fun parsesNamesColoursAndVisibility() {
        val l = org.json.JSONArray("""[{"name":"Arm 1","color":"#ff0000","visible":true},{"name":"Leg","color":"#ffffff","visible":false}]""").let {
            parseParts(it.toString())
        }
        assertEquals(2, l.size); assertEquals("Arm 1", l[0].name); assertEquals("#ffffff", l[1].color); assertTrue(l[0].visible); assertTrue(!l[1].visible)
    }
    @Test fun badAnswersAreEmpty() {
        assertTrue(parseParts(null).isEmpty()); assertTrue(parseParts("").isEmpty()); assertTrue(parseParts("{").isEmpty()); assertTrue(parseParts("[1,2]").isEmpty())
    }
}
