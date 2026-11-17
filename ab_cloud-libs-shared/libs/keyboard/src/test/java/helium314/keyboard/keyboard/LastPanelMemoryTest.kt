// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard

import helium314.keyboard.FakePrefs
import helium314.keyboard.keyboard.LastPanelMemory.Panel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LastPanelMemoryTest {
    private val text = 0x1
    private val number = 0x2
    private val phone = 0x3
    private val textPassword = 0x1 or 0x80
    private val numberPassword = 0x2 or 0x10

    @Test fun defaultsToLetters() {
        assertEquals(Panel.LETTERS, LastPanelMemory(FakePrefs()).remembered())
    }

    @Test fun persistsAcrossProcessRestart() {
        val disk = FakePrefs()
        for (p in Panel.entries) {
            assertTrue(LastPanelMemory(disk).remember(p))
            // a new process: a fresh memory object over the same committed store
            assertEquals(p, LastPanelMemory(disk).panelToRestore(text))
        }
    }

    @Test fun fieldTypeWinsAndKeepsTheMemory() {
        val disk = FakePrefs()
        LastPanelMemory(disk).remember(Panel.CLIPBOARD)
        for (t in listOf(number, phone, textPassword, numberPassword)) {
            assertNull(LastPanelMemory(disk).panelToRestore(t))
        }
        assertEquals(Panel.CLIPBOARD, LastPanelMemory(disk).panelToRestore(text))
    }

    @Test fun manualSwitchInDemandingFieldUpdatesMemory() {
        val disk = FakePrefs()
        LastPanelMemory(disk).remember(Panel.AUTOCOMPLETE)
        assertNull(LastPanelMemory(disk).panelToRestore(phone))
        LastPanelMemory(disk).remember(Panel.NUMPAD)
        assertEquals(Panel.NUMPAD, LastPanelMemory(disk).panelToRestore(text))
    }
}
