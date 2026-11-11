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
    private fun labels(name: String) = Json.parseToJsonElement(File("src/main/assets/layouts/functional/$name.json").readText())
        .jsonArray.flatMap { r -> r.jsonArray.map { it.jsonObject } }.map { it["label"]?.jsonPrimitive?.content ?: it.toString() }

    @Test fun devFunctionalLayoutKeepsEveryKeyAndAddsFn() {
        val base = labels("functional_keys")
        val dev = labels("functional_keys_dev")
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
