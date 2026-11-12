// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1606: Dev Keyboard fn key: double-click holds fn, tap disables; the dev functional layout loses no key. */
class FnKeyTest {
    private fun labels(json: String) = Json.parseToJsonElement(json)
        .jsonArray.flatMap { r -> r.jsonArray.map { it.jsonObject } }.map { it["label"]?.jsonPrimitive?.content ?: it.toString() }

    @Test fun devFunctionalLayoutKeepsEveryKeyAndAddsFn() {
        // the dev layout has no file of its own: it is the default functional layout plus the fn key (CornerSwipe.devFunctional)
        val text = File("src/main/assets/layouts/functional/${CornerSwipe.FUNCTIONAL_BASE}.json").readText()
        val base = labels(text)
        val dev = labels(CornerSwipe.devFunctional(text))
        assertEquals("fn sits right after the symbol/alpha switch", dev.indexOf("symbol_alpha") + 1, dev.indexOf("fn"))
        val remaining = dev.toMutableList()
        base.forEach { assertTrue("lost $it", remaining.remove(it)) } // field parity: nothing removed
        assertEquals(listOf("fn"), remaining)
        assertEquals("functional_keys_dev", CornerSwipe.FUNCTIONAL_LAYOUT)
    }

    @Test fun fnDoubleClickWindow() {
        assertFalse(KeyboardActionListenerImpl.isFnDoubleTap(0L, 1000L))
        assertTrue(KeyboardActionListenerImpl.isFnDoubleTap(1000L, 1000L + KeyboardActionListenerImpl.FN_DOUBLE_TAP_MS))
        assertFalse(KeyboardActionListenerImpl.isFnDoubleTap(1000L, 1001L + KeyboardActionListenerImpl.FN_DOUBLE_TAP_MS))
    }
}
