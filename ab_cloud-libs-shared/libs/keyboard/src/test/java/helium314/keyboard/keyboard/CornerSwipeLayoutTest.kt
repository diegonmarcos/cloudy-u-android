// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.settings.ConfigsEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1413: the "unexpected" layout parses, carries Ctrl/Alt/arrows, and its popups mirror the corner-swipe table. */
class CornerSwipeLayoutTest {
    private val layout = File("src/main/assets/layouts/main/unexpected.json") // module dir

    private fun keys() = Json.parseToJsonElement(layout.readText()).jsonArray.flatMap { it.jsonArray }.map { it.jsonObject }

    @Test fun layoutParsesAndIsSelectable() {
        assertEquals(26, keys().size)
        val names = File("src/main/res/values/donottranslate.xml").readText()
        assertTrue(names.contains("<item>unexpected</item>"))
        assertTrue(!File("src/main/assets/layouts/main/hacker.json").exists()) // owner dropped Hacker
    }

    @Test fun popupsMirrorCorners() {
        keys().forEach { k ->
            val label = k["label"]!!.jsonPrimitive.content
            val popups = ((k["popup"] as? JsonObject)?.get("relevant") as? JsonArray)
                ?.map { it.jsonObject["label"]!!.jsonPrimitive.content.removePrefix("\\").ifEmpty { "\\" } }.orEmpty()
            assertEquals(label, CornerSwipe.corners[label]!!.filter { it.isNotEmpty() }, popups)
        }
    }

    @Test fun modifiersAndArrowsUseExistingKeyCodes() {
        val all = CornerSwipe.corners.values.flatten().filter { it.isNotEmpty() }.map { CornerSwipe.codeOf(it) }.toSet()
        listOf(KeyCode.CTRL, KeyCode.ALT, KeyCode.ESCAPE, KeyCode.TAB,
            KeyCode.ARROW_LEFT, KeyCode.ARROW_RIGHT, KeyCode.ARROW_UP, KeyCode.ARROW_DOWN).forEach { assertTrue("$it", it in all) }
    }

    @Test fun cornerMapping() {
        val d = 40f
        assertEquals(CornerSwipe.NW, CornerSwipe.cornerOf(-50, -50, d))
        assertEquals(CornerSwipe.NE, CornerSwipe.cornerOf(50, -50, d))
        assertEquals(CornerSwipe.SW, CornerSwipe.cornerOf(-50, 50, d))
        assertEquals(CornerSwipe.SE, CornerSwipe.cornerOf(50, 50, d))
        assertEquals(-1, CornerSwipe.cornerOf(10, 10, d))
        // owner's screenshot: u -> 7 (NE), g -> G (NW), s -> - (SW)
        assertEquals('7'.code, CornerSwipe.codeFor("u", 50, -50, d))
        assertEquals('G'.code, CornerSwipe.codeFor("g", -50, -50, d))
        assertEquals('-'.code, CornerSwipe.codeFor("s", -50, 50, d))
        assertEquals(KeyCode.CTRL, CornerSwipe.codeFor("a", -50, -50, d))
        assertEquals(KeyCode.ARROW_LEFT, CornerSwipe.codeFor("h", -50, 50, d))
        assertEquals(KeyCode.ESCAPE, CornerSwipe.codeFor("Q", -50, -50, d))
        assertNull(CornerSwipe.codeFor("r", -50, -50, d)) // empty corner -> plain tap
        assertNull(CornerSwipe.codeFor("u", 5, -5, d))     // too short -> plain tap
    }

    @Test fun devKeyboardToggleIsInConfigsAndOnByDefault() {
        assertTrue(CornerSwipe.DEFAULT)
        assertTrue(ConfigsEntry.DEV_KEYBOARD in ConfigsEntry.entries)
    }
}
