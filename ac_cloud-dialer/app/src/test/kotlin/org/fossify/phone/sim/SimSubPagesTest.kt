package org.fossify.phone.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimSubPagesTest {
    private fun rec(id: Int, slot: Int, embedded: Boolean, iccid: String? = "89340711000012345$id", name: String = "SIM $id") =
        SubRecord(id, slot, "Acme", name, "214", "07", "es", null, iccid, embedded)

    @Test fun onePageForEachPhysicalSlotAndEachEsimProfileIncludingInactive() {
        val src = FakeSubSource(
            activeList = listOf(rec(1, 0, false), rec(2, 1, true)),
            accessibleList = listOf(rec(2, 1, true), rec(7, -1, true, name = "Travel eSIM"), rec(8, -1, true, name = "Backup eSIM")),
            slotCount = 3, defaultData = 2,
        )
        val cards = SimDetect.cards(src)
        assertEquals(listOf(0, 1, 2, -1, -1), cards.map { it.slot })
        assertEquals(listOf(true, true, false, false, false), cards.map { it.active })
        assertEquals(listOf(false, true, false, true, true), cards.map { it.embedded })
        assertEquals(listOf("Backup eSIM", "Travel eSIM"), cards.drop(3).map { it.displayName })
        assertTrue(cards[1].defaultData); assertEquals("LTE", cards[1].networkType)
        assertNull(cards[3].networkType); assertEquals("89049032000001000000000000001234", cards[3].eid)
        assertNull(cards[2].subscriptionId)
        assertEquals(5, cards.map { SimFields.key(it) }.toSet().size)
    }

    @Test fun keyIsIccidThenSubscriptionIdThenSlot() {
        val cards = SimDetect.cards(FakeSubSource(activeList = listOf(rec(1, 0, false), rec(3, 1, false, iccid = null)), slotCount = 3))
        assertEquals("iccid_893407110000123451", SimFields.key(cards[0]))
        assertEquals("sub_3", SimFields.key(cards[1]))
        assertEquals("slot_2", SimFields.key(cards[2]))
    }

    @Test fun detectedRowsShowStatusTailAndNaWithWhatIsNeeded() {
        val inactive = SimDetect.cards(FakeSubSource(accessibleList = listOf(rec(7, -1, true)), slotCount = 0)).single()
        val rows = SimFields.detected(inactive, SimManual()).associate { it.label to it.value }
        assertEquals("Inactive", rows["Status"])
        assertTrue(rows["Slot"]!!.startsWith("n/a (needs"))
        assertTrue(rows["Number"]!!.startsWith("n/a (needs"))
        assertTrue(rows["Signal"]!!.startsWith("n/a (needs"))
        assertEquals("eSIM", rows["Type"])
        assertTrue(rows["ICCID"]!!.endsWith(inactive.iccid!!.takeLast(4)))
        assertFalse(rows["ICCID"]!!.contains(inactive.iccid!!))
        val empty = SimDetect.cards(FakeSubSource(slotCount = 1)).single()
        assertEquals("Empty slot", SimFields.detected(empty, SimManual()).first().value)
    }

    @Test fun editableFieldsAreDeclaredAndPersistPerSubscription() {
        val prefs = FakePrefs(); val vault = FakeVault()
        val store = SimManualStore(prefs, vault, simSchema)
        for (id in listOf("lineType", "dataAllowance", "minutes", "sms", "roamingPlan", "monthlyPrice", "renewalDate", "planNotes",
            "ownerName", "ownerEmail", "providerSite", "providerPhone", "providerWhatsapp", "notes", "plan", "provider")) {
            assertTrue("$id declared", simSchema.fields.any { it.id == id })
        }
        assertEquals(listOf("Voice + data", "Data only", "Voice only", "SMS only"), simSchema.fields.first { it.id == "lineType" }.options.take(4))
        assertTrue(store.save("iccid_A", "ownerName", "Ana Gil"))
        assertTrue(store.save("iccid_A", "lineType", "Data only"))
        assertTrue(store.save("iccid_B", "ownerName", "Bo Li"))
        assertFalse(store.save("iccid_A", "nonsense", "x"))
        val a = fresh(prefs, vault).load("iccid_A")
        assertEquals("Ana Gil", a["ownerName"]); assertEquals("Data only", a["lineType"]); assertEquals("", a["notes"])
        assertEquals("Bo Li", fresh(prefs, vault).load("iccid_B")["ownerName"])
    }

    private fun fresh(prefs: FakePrefs, vault: FakeVault) = SimManualStore(prefs, vault, simSchema)

    @Test fun pinAndPukNeverReachThePlainPrefsOnlyTheVault() {
        val prefs = FakePrefs(); val vault = FakeVault()
        val store = SimManualStore(prefs, vault, simSchema)
        assertEquals(setOf("pin", "puk"), simSchema.secretIds)
        assertTrue(store.save("iccid_A", "pin", "4711")); assertTrue(store.save("iccid_A", "puk", "87654321"))
        store.save("iccid_A", "ownerName", "Ana")
        assertEquals("4711", store.secret("iccid_A", "pin")); assertEquals("87654321", store.secret("iccid_A", "puk"))
        assertEquals("4711", vault.held["iccid_A.pin"])
        val everything = prefs.map.entries.joinToString("|") { "${it.key}=${it.value}" }
        assertFalse(everything.contains("4711")); assertFalse(everything.contains("87654321"))
        assertTrue(prefs.map.keys.none { it.endsWith(".pin") || it.endsWith(".puk") })
        assertFalse("pin" in store.load("iccid_A").values.keys)
        assertNull(store.secret("iccid_A", "ownerName"))
    }

    @Test fun withoutTheVaultASecretIsNotSavedAnywhere() {
        val prefs = FakePrefs(); val vault = FakeVault(reachable = false)
        val store = SimManualStore(prefs, vault, simSchema)
        assertFalse(store.save("iccid_A", "pin", "4711"))
        assertNull(store.secret("iccid_A", "pin"))
        assertTrue(prefs.map.isEmpty())
        assertTrue(simSchema.vault.unavailable.startsWith("n/a (needs"))
    }

    @Test fun maskHidesEveryPinDigit() {
        assertEquals("••••", SimMask.secret("4711")); assertEquals("-", SimMask.secret(""))
        assertEquals("•••4567", SimMask.mask("1234567"))
    }
}
