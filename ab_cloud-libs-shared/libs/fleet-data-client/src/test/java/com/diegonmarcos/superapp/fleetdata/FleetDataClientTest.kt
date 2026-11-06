package com.diegonmarcos.superapp.fleetdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

class FleetDataClientTest {
    private class MemLkg : LastKnownGood {
        val m = HashMap<String, ByteArray>()
        override fun load(key: String) = m[key]
        override fun store(key: String, bytes: ByteArray) { m[key] = bytes }
    }
    private fun src(map: Map<String, String?>, seen: MutableList<String> = mutableListOf()) = DataSource { a, p ->
        seen += "$a|$p"; map[p]?.let { ByteArrayInputStream(it.toByteArray()) as InputStream }
    }
    private val good = """{"schemaVersion":1,"n":7}"""
    private val parse = { s: String -> Regex("\"n\":(\\d+)").find(s)!!.groupValues[1].toInt() }

    @Test fun routingSendsTiersOneAndTwoToAccountAndTheRestToStore() {
        assertEquals(FleetDataRouting.ACCOUNT, FleetDataRouting.authorityFor("personal/wallet"))
        assertEquals(FleetDataRouting.ACCOUNT, FleetDataRouting.authorityFor("fleet/probes"))
        assertEquals(FleetDataRouting.ACCOUNT, FleetDataRouting.authorityFor("infra/ai"))
        assertEquals(FleetDataRouting.STORE, FleetDataRouting.authorityFor("constellation-fleet"))
        assertEquals(FleetDataRouting.STORE, FleetDataRouting.authorityFor("tools/sensors"))
        assertEquals("content://${FleetDataRouting.STORE}/shared/app-perms", FleetDataRouting.uri("app-perms", null))
        assertEquals("content://${FleetDataRouting.ACCOUNT}/app/a.b/personal/x", FleetDataRouting.uri("personal/x", "a.b"))
    }

    @Test fun datasetNamesCannotEscape() {
        assertTrue(FleetDataRouting.validDataset("tools/sensors"))
        assertFalse(FleetDataRouting.validDataset("../x")); assertFalse(FleetDataRouting.validDataset("a//b"))
        assertFalse(FleetDataRouting.validDataset("A")); assertFalse(FleetDataRouting.validDataset(""))
    }

    @Test fun resolvesTheOwningAuthorityAndPath() {
        val seen = mutableListOf<String>()
        assertEquals(7, FleetDataReader(src(mapOf("shared/app-perms" to good), seen), MemLkg()).get("app-perms", null, parse))
        assertEquals(listOf("${FleetDataRouting.STORE}|shared/app-perms"), seen)
    }

    @Test fun fallsBackToTheLastKnownGoodAndNeverThrows() {
        val lkg = MemLkg()
        assertEquals(7, FleetDataReader(src(mapOf("shared/x" to good)), lkg).get("x", null, parse))
        val gone = FleetDataReader(DataSource { _, _ -> throw SecurityException("no permission") }, lkg)
        assertEquals(7, gone.get("x", null, parse))
        assertNull(gone.get("never-seen", null, parse))
        assertNull(FleetDataReader(src(emptyMap()), MemLkg()).get("x", null, parse))
    }

    @Test fun aParserErrorKeepsTheOldCopy() {
        val lkg = MemLkg()
        FleetDataReader(src(mapOf("shared/x" to good)), lkg).get("x", null, parse)
        val r = FleetDataReader(src(mapOf("shared/x" to """{"schemaVersion":1,"broken":true}""")), lkg)
        assertEquals(7, r.get("x", null, parse))
    }

    @Test fun rejectsAnUnknownOrMissingSchemaVersion() {
        val lkg = MemLkg()
        FleetDataReader(src(mapOf("shared/x" to good)), lkg).get("x", null, parse)
        val newer = FleetDataReader(src(mapOf("shared/x" to """{"schemaVersion":2,"n":9}""")), lkg, supportedSchema = 1)
        assertEquals(7, newer.get("x", null, parse))
        assertNull(FleetDataReader(src(mapOf("shared/y" to """{"n":9}""")), MemLkg()).get("y", null, parse))
        assertTrue(SchemaCheck.check("""{"schemaVersion":2}""", 1) is SchemaCheck.Result.Bad)
        assertTrue(SchemaCheck.check("[1]") is SchemaCheck.Result.Bad)
        assertTrue(SchemaCheck.check("""{"schemaVersion":3}""", 3) is SchemaCheck.Result.Ok)
    }

    @Test fun streamsAFileOverOneMegabyte() {
        val big = """{"schemaVersion":1,"n":3,"pad":"${"x".repeat(3 * 1024 * 1024)}"}"""
        assertEquals(3, FleetDataReader(src(mapOf("shared/big" to big)), MemLkg()).get("big", null, parse))
        val huge = FleetDataReader(src(mapOf("shared/big" to big)), MemLkg(), maxBytes = 1024 * 1024)
        assertNull(huge.get("big", null, parse))
    }

    @Test fun signatureVerifierReportsNoKeyWithoutOne() {
        assertEquals(Ed25519Check.Verdict.NoKey, Ed25519Check.verify(null, ByteArray(1), null))
        val kp = java.security.KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val msg = "hello".toByteArray()
        val sig = java.security.Signature.getInstance("Ed25519").apply { initSign(kp.private); update(msg) }.sign()
        val b64 = java.util.Base64.getEncoder()
        assertEquals(Ed25519Check.Verdict.Valid, Ed25519Check.verify(b64.encodeToString(kp.public.encoded), msg, b64.encodeToString(sig)))
        assertEquals(Ed25519Check.Verdict.Invalid, Ed25519Check.verify(b64.encodeToString(kp.public.encoded), "bye".toByteArray(), b64.encodeToString(sig)))
    }
}
