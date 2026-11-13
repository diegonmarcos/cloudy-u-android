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
        æ:BST,`,∅,∅ s:∅,∅,∅,∅ d:∅,∅,∅,∅ f:∅,∅,∅,∅ g:∅,−,—,∅ ₴:∅,≈,+,∅ j:∅,∅,‹,› k:∅,∅,‘,’ £:∅,~,COMMA,∅
        ₿:∅,¦,BS,∅ x:∅,∅,∅,∅ ¢:∅,«,∅,∅ v:∅,»,∅,∅ ₱:∅,¿,/,∅ n:∅,!icon/unexp_select,.,; m:∅,∅,…,∅
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
        assertEquals("!icon/clipboard", ctrl.corners[UnexpectedKb.SW]!!.label); assertEquals("123+", ctrl.corners[UnexpectedKb.SE]!!.label)
        val fn = base.special("fn")!!
        assertEquals("!icon/emoji", fn.corners[UnexpectedKb.SW]!!.label); assertEquals("!icon/settings", fn.corners[UnexpectedKb.SE]!!.label)
        assertEquals("!icon/unexp_keyboard", fn.corners[UnexpectedKb.NE]!!.label)
        assertEquals("!icon/voice", base.special("enter")!!.corners[UnexpectedKb.NW]!!.label) // the mic legend belongs to the enter key
        // every icon legend has a text stand-in and the compass diagonals select (hidden legends)
        layout.layers.values.flatMap { it.all }.flatMap { it.corners.filterNotNull() }.filter { it.label.startsWith(UnexpectedKb.ICON_PREFIX) }.forEach { assertTrue(it.label, UnexpectedKb.textOf(it.label).isNotEmpty()) }
        // every theme-owned icon legend has its drawable file; the others are names of the keyboard's own icon set
        val own = setOf("clipboard", "emoji", "settings", "voice")
        layout.layers.values.flatMap { it.all }.flatMap { it.corners.filterNotNull() }.filter { it.label.startsWith(UnexpectedKb.ICON_PREFIX) }
            .map { it.label.removePrefix(UnexpectedKb.ICON_PREFIX) }.forEach { n ->
                if (n !in own) assertTrue("no drawable for $n", File("src/main/res/drawable/ic_$n.xml").isFile)
            }
        val fnCompass = layout.layer(true).special("compass")!!
        assertEquals(listOf("!icon/unexp_pgup", "!icon/unexp_pgdn", "!icon/unexp_home", "!icon/unexp_end"),
            listOf(UnexpectedKb.N, UnexpectedKb.S, UnexpectedKb.NW, UnexpectedKb.SE).map { fnCompass.corners[it]!!.label })
        val cmp = base.special("compass")!!
        assertEquals(listOf("sel_left", "sel_up", "sel_down", "sel_right"), listOf(UnexpectedKb.NW, UnexpectedKb.NE, UnexpectedKb.SW, UnexpectedKb.SE).map { cmp.corners[it]!!.action })
        assertEquals("space centre glyph", "␣", base.special("space")!!.centerLegend)
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
            // the compass key is found by the glyph it shows as its label, and is the only compass
            assertEquals(UnexpectedKb.COMPASS, UnexpectedKb.idOf(UnexpectedKb.COMPASS, 0))
            assertEquals("compass", UnexpectedKb.keyDef(UnexpectedKb.COMPASS)?.id)
            assertEquals("ctrl", UnexpectedKb.keyDef(UnexpectedKb.idOf("Ctrl", 0))?.id)
            UnexpectedKb.active = true
            try {
                assertTrue(CornerSwipe.isCompass(UnexpectedKb.idOf(UnexpectedKb.COMPASS, 0)))
                assertTrue(!CornerSwipe.isCompass(UnexpectedKb.idOf("g", 0)))
                // compass swipes pick arrows / selection by sector (west = left arrow; north-west = select left)
                assertEquals(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.ARROW_LEFT, CornerSwipe.codeForId(UnexpectedKb.COMPASS, -60, 0, 20f))
                assertEquals(helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode.SELECT_LEFT, CornerSwipe.codeForId(UnexpectedKb.COMPASS, -50, -50, 20f))
            } finally { UnexpectedKb.active = false }
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
        assertEquals(listOf("shift", "-", "delete", "Ctrl", "Fn", "space", UnexpectedKb.COMPASS, "action"), labels)
    }

    /** The key background is the theme's own flat rounded rectangle, installed for every key kind, never the stock style's drawable. */
    @Test fun keyBackgroundIsTheThemesOwn() {
        val colors = File("src/main/java/helium314/keyboard/latin/common/Colors.kt").readText()
        assertTrue(colors.contains("UnexpectedKb.keyBackground()"))
        listOf("KEY_BACKGROUND", "FUNCTIONAL_KEY_BACKGROUND", "SPACE_BAR_BACKGROUND", "ACTION_KEY_BACKGROUND").forEach { assertTrue(it, colors.contains("color == $it")) }
        assertTrue(File("src/main/java/helium314/keyboard/keyboard/UnexpectedKeyDrawable.kt").readText().contains("drawRoundRect"))
    }

    /** The design's proportions, measured on the 1080 px wide comparison: taller keys, a larger centre glyph, legends near the design's. */
    @Test fun designProportions() {
        assertEquals(1.16f * 818f / 690f, UnexpectedKb.HEIGHT_FACTOR, 0.01f) // measured on the 1080 px comparison
        assertTrue(UnexpectedKb.TEXT_FACTOR in 1.0f..2.0f && UnexpectedKb.LEGEND_FACTOR in 1.0f..2.0f)
        val sv = File("src/main/java/helium314/keyboard/latin/settings/SettingsValues.java").readText()
        assertTrue(sv.contains("UnexpectedKb.HEIGHT_FACTOR"))
    }

    @Test fun themeIsWiredWithoutHardcodedKeys() {
        // the views only ask the table; no key glyph literal of the theme lives in them
        val view = File("src/main/java/helium314/keyboard/keyboard/KeyboardView.java").readText()
        assertTrue(view.contains("CornerSwipe.legendsOf("))
        assertTrue(!view.contains("\"€\"") && !view.contains("\"₹\""))
        assertEquals("pref_unexpected_kb", UnexpectedKb.PREF)
    }
}
