package com.diegonmarcos.superapp.nfc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

private fun hex(s: String) = Bytes.parse(s)!!

/** #1322 MIFARE Classic (public keys only), the MAD, DESFire identification and the "locked is not an error" summary, on the plain JVM. */
class MifareTest {
    private val catalog: NfcCatalog = NfcCatalog.parse(
        listOf("../../../data/fleet/nfc-catalog.json", "data/fleet/nfc-catalog.json").map(::File).first { it.exists() }.readText()
    )

    // ---- SAK -> type ----

    @Test fun sakMapsToClassicTypes() {
        assertEquals("MIFARE Classic 1K", catalog.sakName(0x08))
        assertEquals("MIFARE Classic 4K", catalog.sakName(0x18))
        assertEquals("MIFARE Mini", catalog.sakName(0x09))
        assertTrue(catalog.sakName(0x88).startsWith("MIFARE Classic 1K"))
        assertTrue(catalog.sakName(0x28).startsWith("MIFARE Classic 1K"))
        assertTrue(catalog.sakName(0x38).startsWith("MIFARE Classic 4K"))
        for (s in listOf(0x01, 0x08, 0x09, 0x10, 0x11, 0x18, 0x19, 0x28, 0x38, 0x88, 0x98)) assertTrue("SAK %02X is Classic".format(s), catalog.isClassicSak(s))
        for (s in listOf(0x00, 0x20, 0x04, 0x60)) assertFalse("SAK %02X is not Classic".format(s), catalog.isClassicSak(s))
        assertTrue(catalog.isDesfireSak(0x20))
        assertFalse(catalog.isDesfireSak(0x08))
    }

    @Test fun layoutOfTheFamilies() {
        assertEquals(5, MifareLayout.sectorCount(320)); assertEquals(20, MifareLayout.blockCount(320))
        assertEquals(16, MifareLayout.sectorCount(1024)); assertEquals(64, MifareLayout.blockCount(1024))
        assertEquals(32, MifareLayout.sectorCount(2048)); assertEquals(128, MifareLayout.blockCount(2048))
        assertEquals(40, MifareLayout.sectorCount(4096)); assertEquals(256, MifareLayout.blockCount(4096))
        assertEquals(124, MifareLayout.firstBlock(31, 4096)); assertEquals(128, MifareLayout.firstBlock(32, 4096)); assertEquals(240, MifareLayout.firstBlock(39, 4096))
        assertEquals(4, MifareLayout.blocksIn(31, 4096)); assertEquals(16, MifareLayout.blocksIn(32, 4096)); assertEquals(4, MifareLayout.blocksIn(39, 1024))
    }

    // ---- the declared public key list ----

    @Test fun catalogKeysAreExactlyTheFourPublicOnes() {
        assertEquals(listOf("FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7"), catalog.classicKeys.map { Bytes.hex(it.bytes) })
        assertTrue(catalog.classicKeys.all { it.bytes.size == 6 })
    }

    // ---- MAD ----

    private val ndefB1 = hex("14 01" + " 03 E1".repeat(7))
    private val ndefB2 = hex(("03 E1 ").repeat(8))

    @Test fun madCrcMatchesTheKnownNdefDirectory() {
        assertEquals(0x14, MadDecoder.crc8(ndefB1.copyOfRange(1, 16) + ndefB2))
        val mad = MadDecoder.decode(ndefB1, ndefB2, 0xC1)!!
        assertEquals(1, mad.version); assertTrue(mad.multiApp); assertTrue(mad.crcOk)
        assertEquals(15, mad.entries.size)
        assertEquals(MadEntry(1, 0xE103), mad.entries.first()); assertEquals(MadEntry(15, 0xE103), mad.entries.last())
        assertEquals("NFC Forum NDEF", catalog.madAid(0xE103))
        assertEquals("free sector", catalog.madAid(0))
        assertTrue(catalog.madAid(0x1234).contains("cluster 0x12"))
    }

    @Test fun madRejectsBadCrcAndMissingDirectory() {
        val bad = ndefB1.copyOf().also { it[0] = 0x15 }
        assertFalse(MadDecoder.decode(bad, ndefB2, 0xC1)!!.crcOk)
        assertNull("DA bit clear means no MAD", MadDecoder.decode(ndefB1, ndefB2, 0x41))
        assertNull("version 0 is not a MAD", MadDecoder.decode(ndefB1, ndefB2, 0x80))
    }

    @Test fun mad2AddsSectors17To39() {
        fun block(aid: Int, n: Int, head: Boolean): ByteArray {
            val b = ByteArray(16)
            for (i in 0 until n) { val o = (if (head) 2 else 0) + i * 2; b[o] = (aid and 0xFF).toByte(); b[o + 1] = (aid shr 8).toByte() }
            return b
        }
        val m0 = block(0x4001, 7, true); val m1 = block(0x4001, 8, false); val m2 = block(0x4001, 8, false)
        m0[1] = 0
        m0[0] = MadDecoder.crc8(m0.copyOfRange(1, 16) + m1 + m2).toByte()
        val mad = MadDecoder.decode(ndefB1, ndefB2, 0x82, listOf(m0, m1, m2))!!
        assertEquals(2, mad.version); assertTrue(mad.crcOk)
        assertEquals(15 + 23, mad.entries.size)
        assertEquals(39, mad.entries.last().sector)
        assertEquals(0x4001, mad.entries.last().aid)
    }

    // ---- DESFire ----

    private fun versionBytes(hwType: Int, hwMajor: Int, storage: Int) =
        byteArrayOf(0x04, hwType.toByte(), 1, hwMajor.toByte(), 0, storage.toByte(), 5, 0x04, hwType.toByte(), 1, 1, 4, storage.toByte(), 5) +
            hex("04 11 22 33 44 55 66") + hex("AA BB CC DD EE") + byteArrayOf(0x2A, 0x14)

    @Test fun desfireVersionParsesAndIsNamedFromTheCatalog() {
        val v = Desfire.parseVersion(versionBytes(1, 1, 0x1A))!!
        assertEquals("MIFARE DESFire EV1", Desfire.describe(v, catalog))
        assertEquals("8 KB", v.storage)
        assertEquals("04:11:22:33:44:55:66", Bytes.hex(v.uid, ":"))
        assertEquals("week 2A of 2014", Desfire.productionText(v))
        assertEquals("MIFARE DESFire EV2", Desfire.describe(Desfire.parseVersion(versionBytes(1, 0x12, 0x1A))!!, catalog))
        assertEquals("MIFARE DESFire EV3", Desfire.describe(Desfire.parseVersion(versionBytes(1, 0x33, 0x1A))!!, catalog))
        assertEquals("MIFARE DESFire Light", Desfire.describe(Desfire.parseVersion(versionBytes(8, 0x30, 0x16))!!, catalog))
        assertTrue(Desfire.describe(Desfire.parseVersion(versionBytes(0x77, 2, 0x16))!!, catalog).contains("0x77"))
        assertEquals("2 KB", Desfire.parseVersion(versionBytes(1, 1, 0x16))!!.storage)
        assertNull(Desfire.parseVersion(ByteArray(27)))
    }

    private fun ok(data: ByteArray, sw2: Int) = data + byteArrayOf(0x91.toByte(), sw2.toByte())

    @Test fun desfireProbeFollowsContinuationsAndListsApplicationsWithoutAuth() {
        val full = versionBytes(1, 1, 0x1A)
        var step = 0
        val sent = ArrayList<String>()
        val tx = { c: ByteArray ->
            sent += Bytes.hex(c)
            when (c[1].toInt() and 0xFF) {
                0x60 -> { step = 1; ok(full.copyOfRange(0, 7), 0xAF) }
                0xAF -> if (step == 1) { step = 2; ok(full.copyOfRange(7, 14), 0xAF) } else if (step == 2) { step = 3; ok(full.copyOfRange(14, 28), 0x00) } else ok(hex("56 34 12 AB CD EF"), 0x00)
                0x6A -> { step = 9; ok(hex("56 34 12 AB CD EF"), 0x00) }
                else -> byteArrayOf(0x6D, 0)
            }
        }
        val info = Desfire.probe(tx, catalog)!!
        assertEquals("MIFARE DESFire EV1", info.name)
        assertEquals(listOf("123456", "EFCDAB"), info.appIds)
        assertEquals("9060000000", sent.first())
        assertTrue(sent.contains("906A000000"))
        assertTrue(Desfire.section(info).rows.any { it.first == "Applications" && it.second.contains("123456") })
    }

    @Test fun desfireAppListNeedingAuthIsNotAnError() {
        val full = versionBytes(1, 0x12, 0x1A)
        var n = 0
        val tx = { c: ByteArray ->
            when (c[1].toInt() and 0xFF) {
                0x60 -> { n = 1; ok(full.copyOfRange(0, 7), 0xAF) }
                0xAF -> { n++; if (n == 2) ok(full.copyOfRange(7, 14), 0xAF) else ok(full.copyOfRange(14, 28), 0x00) }
                else -> ok(ByteArray(0), 0xAE)
            }
        }
        val info = Desfire.probe(tx, catalog)!!
        assertEquals("MIFARE DESFire EV2", info.name)
        assertNull(info.appIds)
        assertTrue(info.appNote.contains("needs authentication"))
    }

    @Test fun nonDesfireIsNotNamedDesfire() {
        assertNull(Desfire.probe({ byteArrayOf(0x6D, 0x00) }, catalog))
        assertNull(Desfire.probe({ ok(ByteArray(0), 0x1C) }, catalog))
    }

    // ---- MIFARE Classic probe: a hotel card is locked, and that is fine ----

    private class FakeCard(val size: Int, val opens: Map<Int, ByteArray>, val trailerKeyB: String = "") : ClassicIo {
        val tried = ArrayList<String>()
        override fun auth(sector: Int, key: ByteArray, keyB: Boolean): Boolean {
            tried += Bytes.hex(key)
            return opens[sector]?.contentEquals(key) == true && !keyB
        }
        override fun read(block: Int): ByteArray? {
            val last = (block + 1) % 4 == 0 || (size == 4096 && block >= 128 && (block - 127) % 16 == 0)
            return if (last) hex("00 00 00 00 00 00 FF 07 80 C1 $trailerKeyB").copyOf(16) else ByteArray(16) { (block + it).toByte() }
        }
    }

    @Test fun hotelCardAllLockedIsNormalNotAnError() {
        val card = FakeCard(1024, emptyMap())
        val scan = MifareClassicProbe.scan(card, 1024, catalog.classicKeys, 20000)
        assertEquals(16, scan.sectors.size)
        assertTrue(scan.sectors.none { it.open })
        // Only the four public keys, each as key A and key B, per sector: nothing else is ever tried.
        assertEquals(setOf("FFFFFFFFFFFF", "000000000000", "A0A1A2A3A4A5", "D3F7D3F7D3F7"), card.tried.toSet())
        assertEquals(16 * 8, card.tried.size)
        val (text, anyOpen) = ClassicReport.summary(scan)
        assertFalse(anyOpen)
        assertTrue(text, text.startsWith("All 16 sectors are locked (private key)"))
        assertTrue(text, text.contains("normal for a hotel key card"))
        assertFalse(text.contains("error", true)); assertFalse(text.contains("fail", true))
        val sec = ClassicReport.section("MIFARE Classic 1K", 1024, "Classic", scan, catalog)
        assertTrue(sec.rows.any { it.first == "Sector 0" && it.second == "locked (private key)" })
        assertEquals("", sec.dump)
    }

    @Test fun openSectorIsDumpedAndTrailerKeysAreHidden() {
        val ndefKey = catalog.classicKeys.last().bytes
        val card = FakeCard(1024, mapOf(1 to ndefKey), trailerKeyB = "BB BB BB BB BB BB")
        val scan = MifareClassicProbe.scan(card, 1024, catalog.classicKeys, 20000)
        assertEquals(listOf(1), scan.sectors.filter { it.open }.map { it.sector })
        assertEquals("NFC Forum NDEF", scan.sectors[1].keyName)
        val sec = ClassicReport.section("MIFARE Classic 1K", 1024, "Classic", scan, catalog)
        assertTrue(sec.rows.any { it.first == "Sector 1" && it.second.startsWith("open (key A: NFC Forum NDEF") })
        assertTrue(sec.rows.any { it.first == "Sector 2" && it.second == "locked (private key)" })
        assertTrue(sec.dump.contains("-- sector 1"))
        assertTrue(sec.dump.contains("004  "))
        assertTrue(sec.dump.contains("007  xx xx xx xx xx xx FF 07 80 C1 xx xx xx xx xx xx"))
        assertFalse("key B must never be displayed", sec.dump.contains("BB BB BB"))
        assertTrue(ClassicReport.summary(scan).first.startsWith("1 of 16 sectors open with public keys; 15 locked (private key)"))
    }

    @Test fun madIsDecodedWhenSectorZeroOpens() {
        val key = catalog.classicKeys.first().bytes
        val io = object : ClassicIo {
            override fun auth(sector: Int, key: ByteArray, keyB: Boolean) = sector == 0 && key.contentEquals(catalog.classicKeys.first().bytes) && !keyB
            override fun read(block: Int): ByteArray? = when (block) { 1 -> ndefB1; 2 -> ndefB2; 3 -> hex("00 00 00 00 00 00 78 77 88 C1 00 00 00 00 00 00"); else -> ByteArray(16) }
        }
        val scan = MifareClassicProbe.scan(io, 1024, catalog.classicKeys, 20000)
        assertTrue(scan.sectors[0].open)
        val sec = ClassicReport.section("MIFARE Classic 1K", 1024, "Classic", scan, catalog)
        assertTrue(sec.rows.any { it.first == "MAD" && it.second == "version 1, multi-application, CRC ok" })
        assertTrue(sec.rows.any { it.first == "MAD sector 1" && it.second == "NFC Forum NDEF" })
        assertEquals(key.size, 6)
    }

    @Test fun timeBudgetStopsTheProbeAndSaysSo() {
        var t = 0L
        val scan = MifareClassicProbe.scan(FakeCard(4096, emptyMap()), 4096, catalog.classicKeys, 100) { t += 60; t }
        assertTrue(scan.notTried > 0)
        assertTrue(ClassicReport.summary(scan).first.contains("not tried (time budget)"))
    }

    // ---- unsupported phone ----

    @Test fun unsupportedPhoneSaysSoClearly() {
        assertEquals("This phone's NFC chip cannot read MIFARE Classic; only the UID/SAK are available", MifareLayout.unsupportedLine(true, false, false))
        assertNull("phone can read it", MifareLayout.unsupportedLine(true, true, true))
        assertNull("not a Classic SAK", MifareLayout.unsupportedLine(false, false, false))
        assertNotNull(MifareLayout.unsupportedLine(true, false, true))
    }
}
