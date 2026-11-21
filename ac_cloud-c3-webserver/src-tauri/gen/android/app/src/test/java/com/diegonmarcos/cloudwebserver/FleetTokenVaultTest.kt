package com.diegonmarcos.cloudwebserver

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1835 the fleet token the webserver owns: storage, one-time migration, masking. JVM-only (in-memory Kv, reversible fake codec). */
class FleetTokenVaultTest {
    private class MemKv : Kv {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String) { m[key] = value }
        override fun remove(key: String) { m.remove(key) }
    }
    private val xor = object : TokenCodec {
        override fun encode(plain: ByteArray) = ByteArray(plain.size) { (plain[it].toInt() xor 0x5a).toByte() }
        override fun decode(stored: ByteArray) = encode(stored)
    }
    private fun vault(kv: MemKv = MemKv()) = FleetTokenVault(kv, xor) { 1_700_000_000_000L }

    @Test fun emptyVaultIsNotSet() {
        val s = vault().status()
        assertFalse(s.isSet); assertEquals("", s.last4); assertNull(s.setAtMs); assertFalse(s.migrated)
    }

    @Test fun setStoresEncryptedAndStatusShowsLast4AndDateOnly() {
        val kv = MemKv(); val v = vault(kv)
        assertTrue(v.set("0123456789abcdef0123456789abcdef"))
        val s = v.status()
        assertTrue(s.isSet); assertEquals("cdef", s.last4); assertEquals(1_700_000_000_000L, s.setAtMs)
        assertFalse("plaintext at rest", kv.m.values.any { it.contains("0123456789abcdef") })
        assertEquals("0123456789abcdef0123456789abcdef", v.reveal())
    }

    @Test fun pasteIsTrimmedAndBadValuesRefused() {
        val v = vault()
        assertFalse(v.set("short")); assertFalse(v.set("has space inside the token value")); assertFalse(v.set(""))
        assertTrue(v.set("  abcdefabcdefabcdefabcd\n")); assertEquals("abcdefabcdefabcdefabcd", v.reveal())
    }

    @Test fun generateIs256BitHexAndRotateChangesIt() {
        val v = vault(); val a = v.generate()
        assertTrue(Regex("^[0-9a-f]{64}$").matches(a)); assertEquals(a, v.reveal())
        val b = v.rotate()
        assertNotEquals(a, b); assertEquals(b, v.reveal())
    }

    @Test fun clearRemovesTokenAndBlocksReimport() {
        val v = vault(); v.generate(); v.clear()
        assertFalse(v.status().isSet); assertTrue(v.status().migrated)
        assertFalse(v.migrate { "superapp-token-superapp-token" }); assertNull(v.reveal())
    }

    @Test fun migrationImportsOnceThenStopsReadingTheSuperApp() {
        val v = vault(); var reads = 0
        assertFalse("no SuperApp token: nothing imported, migration stays open", v.migrate { reads++; null })
        assertTrue(v.migrate { reads++; "superapp-token-superapp-token" })
        assertEquals("superapp-token-superapp-token", v.reveal())
        val before = reads
        assertFalse(v.migrate { reads++; "another-superapp-token-xx" })
        assertEquals("the SuperApp is not read again", before, reads)
        assertEquals("superapp-token-superapp-token", v.reveal())
    }

    @Test fun anExistingOwnTokenIsNeverOverwrittenByTheSuperApp() {
        val kv = MemKv(); val own = vault(kv); own.set("owner-token-owner-token-1")
        var reads = 0
        assertFalse(vault(kv).migrate { reads++; "superapp-token-superapp-token" })
        assertEquals(0, reads); assertEquals("owner-token-owner-token-1", own.reveal())
    }

    @Test fun maskShowsLast4OnlyAndNothingForShortValues() {
        assertEquals("••••cdef", FleetTokenLogic.mask("0123456789abcdef"))
        assertEquals("••••", FleetTokenLogic.mask("abc")); assertEquals("", FleetTokenLogic.mask(null))
        assertFalse(FleetTokenLogic.mask("0123456789abcdef").contains("0123"))
        assertEquals(FleetTokenLogic.Migration.NONE, FleetTokenLogic.migrationPlan(true, false, "x".repeat(20)))
        assertEquals(FleetTokenLogic.Migration.IMPORT, FleetTokenLogic.migrationPlan(false, false, "x".repeat(20)))
    }
}
