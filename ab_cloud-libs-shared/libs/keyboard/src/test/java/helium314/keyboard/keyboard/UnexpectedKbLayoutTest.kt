// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Unexpected theme's keyboard is declared as data (assets/unexpected_kb/layout.json): these tests pin the corner glyphs of every
 * key against a reference written down from the design screenshots, check the runtime table is exactly what the file declares, and
 * check the keyboard layouts derived from the file.
 */
class UnexpectedKbLayoutTest {
    private val file = File("src/main/assets/unexpected_kb/layout.json") // module dir
    private val layout get() = UnexpectedKb.parse(file.readText())

    /**
     * Reference per key: "centre:nw,ne,sw,se", read off the design screenshots. "∅" = no glyph, BS = backslash, COMMA = comma,
     * BST = the two characters \t (the Fn layer's tab legend).
     */
    private val baseRef = """
        1:∅,∅,∅,! 2:∅,∅,∅,@ 3:∅,∅,∅,# 4:∅,∅,∅,$ 5:∅,∅,∅,% 6:∅,∅,^,∅ 7:∅,∅,&,∅ 8:∅,∅,*,∅ 9:∅,∅,(,∅ 0:∅,∅,),∅
        q:∅,∅,Esc,∅ w:∅,∅,",∅ e:∅,∅,£,∅ r:∅,∅,∅,∅ t:∅,∅,∅,∅ y:∅,∅,∅,∅ u:∅,∅,∅,∅ i:∅,∅,∅,∅ o:∅,∅,∅,∅ p:∅,∅,∅,∅
        a:⇥,`,∅,∅ s:∅,∅,∅,∅ d:∅,∅,∅,∅ f:∅,∅,∅,∅ g:∅,-,_,∅ h:∅,=,+,∅ j:∅,∅,{,} k:∅,∅,[,] l:∅,~,',∅
        z:∅,|,BS,∅ x:∅,∅,∅,∅ c:∅,<,∅,∅ v:∅,>,∅,∅ b:∅,?,/,∅ n:∅,:,COMMA,; m:∅,∅,.,∅
    """

    private val fnRef = """
        F1:∅,∅,∅,¡ F2:∅,∅,∅,@ F3:∅,∅,∅,# F4:∅,∅,∅,$ F5:∅,∅,∅,‰ F6:∅,∅,¬,∅ F7:∅,∅,&,∅ F8:∅,∅,°,∅ F9:∅,∅,„,∅ F10:∅,∅,”,∅
        q:∅,∅,Ins,∅ w:∅,∅,„,∅ €:∅,∅,∅,∅ ₹:∅,∅,∅,∅ t:∅,∅,∅,∅ ¥:∅,∅,∅,∅ µ:∅,∅,∅,∅ i:∅,∅,∅,∅ œ:∅,∅,F11,∅ ₽:∅,∅,F12,∅
        æ:BST,`,∅,∅ s:∅,∅,∅,∅ d:∅,∅,∅,∅ f:∅,∅,∅,∅ g:∅,−,—,∅ ₴:∅,≈,+,∅ j:∅,∅,‹,› k:∅,∅,‚,‘ £:∅,~,COMMA,∅
        ₿:∅,¦,BS,∅ x:∅,∅,∅,∅ ₵:∅,«,∅,∅ v:∅,»,∅,∅ ₱:∅,¿,/,∅ n:∅,⇔,.,; m:∅,∅,…,∅
    """

    private fun parseRef(ref: String): Map<String, List<String>> = ref.trim().split(Regex("\\s+")).associate { tok ->
        val centre = tok.substringBefore(":")
        val parts = tok.substringAfter(":").split(",").let { raw ->
            // the only glyph containing a comma is written COMMA, so a plain split gives exactly four parts
            raw.map { when (it) { "∅" -> ""; "BS" -> "\\"; "COMMA" -> ","; "BST" -> "\\t"; else -> it } }
        }
        centre to parts
    }

    private fun legendsOf(layer: UnexpectedKb.Layer) = layer.rows.flatten().associate { k -> k.center to k.corners.take(4).map { it?.label ?: "" } }

    @Test fun baseLayerCornersMatchTheDesign() {
        val got = legendsOf(layout.layer(false))
        val ref = parseRef(baseRef)
        assertEquals(ref.keys.toList(), got.keys.toList()) // same keys, same order
        ref.forEach { (k, v) -> assertEquals("base key $k", v, got.getValue(k)) }
    }

    @Test fun fnLayerCornersMatchTheDesign() {
        val got = legendsOf(layout.layer(true))
        val ref = parseRef(fnRef)
        assertEquals(ref.keys.toList(), got.keys.toList())
        ref.forEach { (k, v) -> assertEquals("fn key $k", v, got.getValue(k)) }
    }

    @Test fun fiveRowsWithTheDesignedShape() {
        val base = layout.layer(false)
        assertEquals(listOf(10, 10, 9, 7), base.rows.map { it.size })
        assertEquals(listOf("delete", "ctrl", "fn", "space", "compass", "enter"), base.special.map { it.id })
        assertEquals(listOf(10, 10, 9, 7), layout.layer(true).rows.map { it.size })
        assertEquals("1234567890", base.rows[0].joinToString("") { it.center })
        assertEquals((1..10).map { "F$it" }, layout.layer(true).rows[0].map { it.center })
        // small labels of the bottom row
        val ctrl = base.special("ctrl")!!
        assertEquals("⎘", ctrl.corners[UnexpectedKb.SW]!!.label); assertEquals("123+", ctrl.corners[UnexpectedKb.SE]!!.label)
        val fn = base.special("fn")!!
        assertEquals("☺", fn.corners[UnexpectedKb.SW]!!.label); assertEquals("⚙", fn.corners[UnexpectedKb.SE]!!.label)
        val compass = base.special("compass")!!
        assertEquals(listOf("↑", "↓", "←", "→"), listOf(UnexpectedKb.N, UnexpectedKb.S, UnexpectedKb.W, UnexpectedKb.E).map { compass.corners[it]!!.label })
        assertEquals("πλ∇¬", layout.layer(true).special("ctrl")!!.corners[UnexpectedKb.SE]!!.label)
    }

    /** Every key's corner glyphs the views draw (the runtime table) are exactly the file's, base and Fn layer. */
    @Test fun runtimeTableIsTheDeclaredFile() {
        val l = layout
        UnexpectedKb.use(l)
        try {
            for (fnLayer in listOf(false, true)) {
                UnexpectedKb.fn = fnLayer
                l.layer(fnLayer).all.forEach { k ->
                    assertEquals("${k.id} fn=$fnLayer", k.corners.map { it?.label ?: "" }, UnexpectedKb.labelsOf(k.id))
                }
                // the key view finds a key by its label (any case) or by code for the space bar and backspace
                assertEquals("g", UnexpectedKb.idOf("G", 0))
            }
            UnexpectedKb.fn = false
            assertEquals("space", UnexpectedKb.idOf(null, 32))
            assertEquals("delete", UnexpectedKb.idOf("", -7))
            assertNull(UnexpectedKb.idOf("zz", 0))
        } finally { UnexpectedKb.fn = false }
    }

    @Test fun everyActionResolvesToACode() {
        val names = mutableSetOf<String>()
        layout.layers.values.forEach { layer -> layer.all.forEach { k -> k.corners.filterNotNull().forEach { c -> if (c.action.isNotEmpty()) names += c.action }
            k.action?.let { names += it } } }
        names.forEach { a ->
            val code = CornerSwipe.codeOf(a)
            if (a.codePointCount(0, a.length) > 1) assertTrue("named action $a must map to a key code", code < 0)
            else assertEquals(a.codePointAt(0), code)
        }
    }

    @Test fun swipeDirections() {
        val d = 40f
        assertEquals(UnexpectedKb.NE, UnexpectedKb.slotOf(50, -50, d, false))
        assertEquals(UnexpectedKb.SW, UnexpectedKb.slotOf(-50, 50, d, false))
        assertEquals(-1, UnexpectedKb.slotOf(5, 5, d, false))
        assertEquals(UnexpectedKb.N, UnexpectedKb.slotOf(0, -60, d, true))
        assertEquals(UnexpectedKb.S, UnexpectedKb.slotOf(0, 60, d, true))
        assertEquals(UnexpectedKb.W, UnexpectedKb.slotOf(-60, 0, d, true))
        assertEquals(UnexpectedKb.E, UnexpectedKb.slotOf(60, 0, d, true))
        assertEquals(UnexpectedKb.NW, UnexpectedKb.slotOf(-50, -50, d, true))
        assertEquals(UnexpectedKb.SE, UnexpectedKb.slotOf(50, 50, d, true))
    }

    @Test fun derivedLayoutsAreValidAndCarryEveryKey() {
        val l = layout
        for (fnLayer in listOf(false, true)) {
            val main = Json.parseToJsonElement(UnexpectedKb.mainLayoutJson(l, fnLayer)).jsonArray
            val centres = main.map { r -> r.jsonArray.map { it.jsonObject["label"]!!.jsonPrimitive.content } }
            assertEquals(l.layer(fnLayer).rows.map { r -> r.map { it.center } }, centres)
            if (fnLayer) assertEquals(listOf(-10028), main[0].jsonArray.take(1).map { it.jsonObject["code"]!!.jsonPrimitive.content.toInt() })
        }
        val fnc = Json.parseToJsonElement(UnexpectedKb.functionalJson(l, false)).jsonArray
        val labels = fnc.flatMap { r -> r.jsonArray.map { it.jsonObject } }.map { it["label"]?.jsonPrimitive?.content ?: "-" }
        assertEquals(listOf("shift", "-", "delete", "Ctrl", "Fn", "space", "◎", "action"), labels)
    }

    @Test fun themeIsWiredWithoutHardcodedKeys() {
        // the views only ask the table; no key glyph literal of the theme lives in them
        val view = File("src/main/java/helium314/keyboard/keyboard/KeyboardView.java").readText()
        assertTrue(view.contains("CornerSwipe.legendsOf("))
        assertTrue(!view.contains("\"€\"") && !view.contains("\"₹\""))
        assertEquals("pref_unexpected_kb", UnexpectedKb.PREF)
    }
}
