package com.diegonmarcos.superapp.nfc

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1264 the NDEF parser / builder, the hex dump, the scan history export and the declared catalog, on the plain JVM. */
class NdefTest {
    private fun hex(s: String) = Bytes.parse(s)!!

    @Test fun parsesAWellKnownTextRecordFromSpecBytes() {
        // D1 01 08 'T' 02 'en' 'Hello': MB ME SR, TNF 1, type T, payload status 0x02 (UTF-8, lang length 2).
        val recs = Ndef.parse(hex("D1 01 08 54 02 65 6E 48 65 6C 6C 6F"))
        assertEquals(1, recs.size)
        val d = recs[0].decoded
        assertEquals("Text", d.kind)
        assertEquals("Hello", d.summary)
        assertEquals("en", d.fields.first { it.first == "Language" }.second)
        assertTrue(recs[0].mb && recs[0].me && recs[0].shortRecord)
    }

    @Test fun uriPrefixCodesExpand() {
        val recs = Ndef.parse(hex("D1 01 0C 55 04") + "example.com".toByteArray())
        assertEquals("https://example.com", recs[0].decoded.summary)
        assertEquals("tel:+34600", Ndef.uri(byteArrayOf(5) + "+34600".toByteArray()))
    }

    @Test fun uriBuilderPicksTheLongestPrefix() {
        val r = Ndef.uriRecord("https://www.diegonmarcos.com")
        assertEquals(2, r.payload[0].toInt())
        assertEquals("https://www.diegonmarcos.com", Ndef.uri(r.payload))
        assertEquals(0, Ndef.uriRecord("geo:1,2").payload[0].toInt())
    }

    @Test fun messageRoundTripsWithFlags() {
        val recs = listOf(Ndef.textRecord("hola", "es"), Ndef.uriRecord("https://a.b"), Ndef.aarRecord("com.diegonmarcos.cloudcalc"))
        val bytes = Ndef.encode(recs)
        val back = Ndef.parse(bytes)
        assertEquals(3, back.size)
        assertTrue(back[0].mb); assertFalse(back[0].me); assertTrue(back[2].me)
        assertEquals("hola", back[0].decoded.summary)
        assertEquals("https://a.b", back[1].decoded.summary)
        assertEquals("Android application record", back[2].decoded.kind)
        assertEquals("com.diegonmarcos.cloudcalc", back[2].decoded.summary)
        assertArrayEquals(bytes, Ndef.encode(back))
    }

    @Test fun longPayloadUsesAFourByteLength() {
        val big = "x".repeat(300)
        val bytes = Ndef.encode(listOf(Ndef.mimeRecord("text/plain", big.toByteArray())))
        assertEquals(0, bytes[0].toInt() and 0x10) // SR clear
        val r = Ndef.parse(bytes)[0]
        assertFalse(r.shortRecord)
        assertEquals(300, r.payload.size)
        assertEquals("MIME text/plain", r.decoded.kind)
    }

    @Test fun smartPosterNestsUriAndTitle() {
        val sp = Ndef.parse(Ndef.encode(listOf(Ndef.smartPosterRecord("https://x.org", "Docs", "en"))))[0].decoded
        assertEquals("Smart poster", sp.kind)
        assertEquals("https://x.org", sp.summary)
        assertEquals(2, sp.nested.size)
        assertTrue(sp.fields.any { it.first == "Title (en)" && it.second == "Docs" })
    }

    @Test fun externalAndMimeAndEmpty() {
        val ext = Ndef.parse(Ndef.encode(listOf(Ndef.externalRecord("Example.com:Thing", byteArrayOf(1, 2)))))[0]
        assertEquals("External", ext.decoded.kind)
        assertEquals("example.com:thing", ext.decoded.summary)
        assertEquals("01 02", ext.decoded.fields.first { it.first == "Payload" }.second)
        val bin = Ndef.parse(Ndef.encode(listOf(Ndef.mimeRecord("image/png", ByteArray(4)))))[0]
        assertEquals("4 bytes of image/png", bin.decoded.summary)
        val empty = Ndef.parse(Ndef.encode(emptyList()))
        assertEquals("Empty", empty[0].decoded.kind)
    }

    @Test fun wifiCredentialDecodesButHidesTheKey() {
        val r = Ndef.parse(Ndef.encode(listOf(Ndef.wifiRecord("HomeNet", "s3cretpass"))))[0]
        val d = r.decoded
        assertEquals("Wi-Fi network", d.kind)
        assertEquals("HomeNet", d.summary)
        assertEquals("WPA2-Personal", d.fields.first { it.first == "Authentication" }.second)
        assertEquals("AES", d.fields.first { it.first == "Encryption" }.second)
        assertEquals("10 characters (hidden)", d.fields.first { it.first == "Network key" }.second)
        assertFalse(d.fields.any { it.second.contains("s3cretpass") })
        assertFalse(Ndef.summary(listOf(r)).contains("s3cretpass"))
        assertEquals("Open", Ndef.wifi(Ndef.wifiRecord("Cafe", "").payload).fields.first { it.first == "Authentication" }.second)
    }

    @Test fun bluetoothHandoverSelectWithClassicOob() {
        // OOB: total length (2, LE), address LE 11:22:33:44:55:66, EIR name "Spk".
        val oob = hex("10 00 66 55 44 33 22 11 04 09") + "Spk".toByteArray()
        val bt = Ndef.mimeRecord("application/vnd.bluetooth.ep.oob", oob).let { NdefRecord(it.tnf, it.type, "0".toByteArray(), it.payload) }
        val d = Ndef.parse(Ndef.encode(listOf(bt)))[0].decoded
        assertEquals("Bluetooth pairing", d.kind)
        assertEquals("Spk", d.summary)
        assertEquals("11:22:33:44:55:66", d.fields.first { it.first == "Address" }.second)

        // Hs 1.2 with one alternative carrier record pointing at "0".
        val ac = Ndef.record(Ndef.TNF_WELL_KNOWN, "ac".toByteArray(), hex("01 01 30 00"))
        val hs = Ndef.record(Ndef.TNF_WELL_KNOWN, "Hs".toByteArray(), byteArrayOf(0x12) + Ndef.encode(listOf(ac)))
        val hd = Ndef.parse(Ndef.encode(listOf(hs, bt)))
        assertEquals("Handover select", hd[0].decoded.kind)
        assertEquals("1.2", hd[0].decoded.fields.first { it.first == "Version" }.second)
        assertEquals("Carrier \"0\" active", hd[0].decoded.nested[0].decoded.summary)
        assertEquals("Bluetooth pairing", hd[1].decoded.kind)
    }

    @Test fun bluetoothLeOob() {
        val p = hex("08 1B 66 55 44 33 22 11 01 02 1C 00")
        val d = Ndef.btLeOob(p)
        assertEquals("11:22:33:44:55:66 (random)", d.summary)
        assertEquals("Peripheral only", d.fields.first { it.first == "LE role" }.second)
    }

    @Test fun chunkedRecordsAreReassembled() {
        // first chunk: MB CF SR TNF1 "T" ; middle: CF SR TNF6 ; last: ME SR TNF6
        val bytes = hex("B1 01 03 54 02 65 6E") + hex("36 00 02") + "Hi".toByteArray() + hex("56 00 01") + "!".toByteArray()
        val r = Ndef.parse(bytes)
        assertEquals(1, r.size)
        assertTrue(r[0].chunked)
        assertEquals("Hi!", r[0].decoded.summary)
    }

    @Test(expected = NdefFormatException::class) fun truncatedRecordIsAnError() { Ndef.parse(hex("D1 01 08 54 02 65")) }

    @Test(expected = NdefFormatException::class) fun missingMessageBeginIsAnError() { Ndef.parse(hex("51 01 01 55 00")) }

    @Test fun undecodablePayloadIsReportedNotThrown() {
        val r = NdefRecord(Ndef.TNF_WELL_KNOWN, "U".toByteArray(), ByteArray(0), byteArrayOf(0x7F))
        assertTrue(r.decoded.summary.startsWith("Undecodable"))
    }

    @Test fun bytesHexParseAndDump() {
        assertArrayEquals(byteArrayOf(0xAB.toByte(), 0, 4), Bytes.parse("0xAB, 00:04"))
        assertNull(Bytes.parse("ABC"))
        assertNull(Bytes.parse("ZZ"))
        assertEquals("0000  48 69 00                 Hi.", Bytes.dump(byteArrayOf(0x48, 0x69, 0)))
    }

    @Test fun historyExportsJsonAndCsvAndReadsBack() {
        val e = listOf(ScanEntry(1700000000000, "04A1B2", "NTAG215", listOf("NfcA", "Ndef"), "Text: a, \"b\"", ""), ScanEntry(1, "E0", "ISO 15693", listOf("NfcV"), "", "io\nerr"))
        val json = NfcHistory.json(e)
        assertTrue(json.startsWith("[{\"at_ms\":1700000000000,\"uid\":\"04A1B2\""))
        assertEquals(e, NfcHistory.parse(json))
        val csv = NfcHistory.csv(e).lines()
        assertEquals("at_ms,uid,type,techs,ndef,note", csv[0])
        assertEquals("1700000000000,04A1B2,NTAG215,NfcA Ndef,\"Text: a, \"\"b\"\"\",", csv[1])
        assertEquals(emptyList<ScanEntry>(), NfcHistory.parse("not json"))
    }

    @Test fun writeFormsBuildValidMessages() {
        assertEquals("Hello", Ndef.parse(build("Text", "Hello", ""))[0].decoded.summary)
        assertEquals("Android application record", Ndef.parse(build("App (AAR)", "com.x.y", ""))[0].decoded.kind)
        assertArrayEquals(byteArrayOf(0xD0.toByte(), 0, 0), build("Erase", "", ""))
        assertTrue(runCatching { build("URI", " ", "") }.isFailure)
        assertTrue(runCatching { build("MIME", "nope", "") }.isFailure)
    }

    @Test fun declaredCatalogParsesAndHoldsNoKeys() {
        val f = listOf("../../../data/fleet/nfc-catalog.json", "data/fleet/nfc-catalog.json").map(::File).first { it.exists() }
        val text = f.readText()
        val c = NfcCatalog.parse(text)
        assertEquals("NXP Semiconductors", c.manufacturer(0x04))
        assertEquals("Unknown (0xEE)", c.manufacturer(0xEE))
        assertEquals("NTAG215", c.version(hex("0004040201001103"))!!.name)
        assertTrue(c.sakName(0x00).contains("Type 2"))
        assertEquals("Texas Instruments", c.iso15693(0x07))
        for (banned in listOf("mifare_keys", "emv", "originality", "\"key\"")) assertFalse("catalog must not carry $banned", text.contains(banned))
    }
}
