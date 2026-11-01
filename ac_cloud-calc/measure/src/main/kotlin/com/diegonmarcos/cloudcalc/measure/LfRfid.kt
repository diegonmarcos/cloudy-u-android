package com.diegonmarcos.cloudcalc.measure

import org.json.JSONArray
import org.json.JSONObject

// #1407 Measure > LF-RFID-Tags: the 125/134 kHz maths, no Android. A phone's NFC radio is 13.56 MHz only, so the tab drives an
// external USB reader (Proxmark3, Flipper Zero, RDM6300-style EM4100 serial readers); this file decodes what they print and
// builds what they are sent. The app's LfRfidScreen owns the USB link.

enum class LfType(val label: String) { EM4100("EM4100"), HID26("HID H10301"), FDXB("FDX-B"), T5577("T5577") }

data class LfTag(val type: LfType, val id: String, val fields: Map<String, String> = emptyMap(), val blocks: List<String> = emptyList(), val name: String = "")

enum class LfReader(val label: String, val canWrite: Boolean, val eol: String) {
    PROXMARK3("Proxmark3", true, "\n"), FLIPPER("Flipper Zero", true, "\r\n"), EM_SERIAL("RDM6300 / EM4100 serial", false, "")
}

object Em4100 {
    private val HEX10 = Regex("^[0-9A-F]{10}$")
    fun valid(id: String) = HEX10.matches(id.uppercase())
    fun fields(id: String): Map<String, String> {
        val v = id.uppercase().toLong(16)
        val low = v and 0xFFFFFFL
        return linkedMapOf("Version" to id.uppercase().substring(0, 2), "Decimal" to (v and 0xFFFFFFFFL).toString(),
            "Wiegand 26" to "%03d,%05d".format((low shr 16).toInt(), (low and 0xFFFF).toInt()))
    }

    /** The 64-bit on-air frame: 9 header ones, 10 rows of 4 data bits + even parity, 4 column parities, a stop zero. */
    fun encodeFrame(id: String): Long {
        require(valid(id)) { "EM4100 ID is 10 hex digits" }
        val v = id.uppercase().toLong(16)
        var bits = "111111111"
        var cols = 0
        for (r in 0 until 10) {
            val nib = ((v shr (36 - 4 * r)) and 0xF).toInt()
            bits += nib.toString(2).padStart(4, '0') + (Integer.bitCount(nib) and 1)
            cols = cols xor nib
        }
        bits += cols.toString(2).padStart(4, '0') + "0"
        return java.lang.Long.parseUnsignedLong(bits, 2)
    }

    fun decodeFrame(frame: Long): String? {
        val b = java.lang.Long.toBinaryString(frame).padStart(64, '0')
        if (!b.startsWith("111111111") || b[63] != '0') return null
        var id = 0L
        var cols = 0
        for (r in 0 until 10) {
            val row = b.substring(9 + 5 * r, 14 + 5 * r)
            val nib = row.substring(0, 4).toInt(2)
            if ((Integer.bitCount(nib) and 1) != row[4] - '0') return null
            id = (id shl 4) or nib.toLong()
            cols = cols xor nib
        }
        if (b.substring(59, 63).toInt(2) != cols) return null
        return "%010X".format(id)
    }

    /** RDM6300 frame: STX, 10 ASCII hex ID, 2 ASCII hex XOR checksum, ETX. */
    fun parseRdm6300(frame: ByteArray): String? {
        val s = frame.dropWhile { it != 0x02.toByte() }
        if (s.size < 14 || s[13] != 0x03.toByte()) return null
        val txt = String(s.subList(1, 13).toByteArray(), Charsets.US_ASCII).uppercase()
        if (!Regex("^[0-9A-F]{12}$").matches(txt)) return null
        var x = 0
        for (i in 0 until 5) x = x xor txt.substring(2 * i, 2 * i + 2).toInt(16)
        return if (x == txt.substring(10).toInt(16)) txt.substring(0, 10) else null
    }
}

object Hid26 {
    /** H10301: even parity over bits 1..12 (MSB first), FC 8 bits, CN 16 bits, odd parity over bits 13..24. */
    fun encode(fc: Int, cn: Int): Int {
        require(fc in 0..255 && cn in 0..65535) { "FC 0-255, CN 0-65535" }
        val data = (fc shl 16) or cn
        val ep = Integer.bitCount(data shr 12) and 1
        val op = (Integer.bitCount(data and 0xFFF) + 1) and 1
        return (ep shl 25) or (data shl 1) or op
    }

    fun decode(raw: Int): Pair<Int, Int>? {
        if (raw shr 26 != 0) return null
        val data = (raw shr 1) and 0xFFFFFF
        if (encode(data shr 16, data and 0xFFFF) != raw) return null
        return (data shr 16) to (data and 0xFFFF)
    }
}

object FdxB {
    /** The 64-bit identification code, LSB first on air: national ID bits 0-37, country bits 38-47, data flag 48, animal flag 63. */
    fun encode(country: Int, national: Long, animal: Boolean = true): Long {
        require(country in 0..999 && national in 0..274877906943L) { "country 0-999, national ID 38 bits" }
        return national or (country.toLong() shl 38) or (if (animal) Long.MIN_VALUE else 0L)
    }

    fun decode(code: Long): Pair<Int, Long> = ((code ushr 38) and 0x3FF).toInt() to (code and 0x3FFFFFFFFFL)

    fun format(country: Int, national: Long) = "%03d-%012d".format(country, national)
}

data class T5577Config(val rate: Int, val modulation: String, val maxBlock: Int, val password: Boolean, val aor: Boolean, val otp: Boolean, val st: Boolean, val masterKey: Int)

object T5577 {
    private val RATES = intArrayOf(8, 16, 32, 40, 50, 64, 100, 128)
    private val MODS = mapOf(0 to "Direct", 1 to "PSK1", 2 to "PSK2", 3 to "PSK3", 4 to "FSK1", 5 to "FSK2", 6 to "FSK1a", 7 to "FSK2a", 8 to "Manchester", 16 to "Biphase", 24 to "Biphase57")
    private fun bits(v: Long, from: Int, n: Int) = ((v ushr (32 - from - n)) and ((1L shl n) - 1)).toInt()

    /** Block 0, bit 0 = MSB: key 0-3, rate 11-13, modulation 15-19, AOR 22, OTP 23, max block 24-26, PWD 27, ST 28. */
    fun parseConfig(hex: String): T5577Config {
        val v = block(hex)
        return T5577Config(RATES[bits(v, 11, 3)], MODS[bits(v, 15, 5)] ?: "Unknown(${bits(v, 15, 5)})",
            bits(v, 24, 3), bits(v, 27, 1) == 1, bits(v, 22, 1) == 1, bits(v, 23, 1) == 1, bits(v, 28, 1) == 1, bits(v, 0, 4))
    }

    fun block(hex: String): Long {
        require(Regex("^[0-9A-Fa-f]{8}$").matches(hex)) { "a T5577 block is 8 hex digits" }
        return hex.toLong(16)
    }

    private val DUMP_LINE = Regex("^\\s*\\[?[=+]?\\]?\\s*(\\d{1,2})\\s*\\|\\s*([0-9A-Fa-f]{8})\\b")

    /** Proxmark `lf t55xx dump` rows: ` 00 | 00148040 | 0000…`. Returns block index → hex, page 0 only. */
    fun parseDump(text: String): List<String> {
        val out = sortedMapOf<Int, String>()
        for (l in text.lines()) DUMP_LINE.find(l)?.let { m -> val i = m.groupValues[1].toInt(); if (i in 0..7 && i !in out) out[i] = m.groupValues[2].uppercase() }
        return out.values.toList()
    }

    /** Blocks for an EM4100 clone: config 00148040 then the 64-bit frame split in two. */
    fun em4100Blocks(id: String): List<String> {
        val f = Em4100.encodeFrame(id)
        return listOf("00148040", "%08X".format(f ushr 32), "%08X".format(f and 0xFFFFFFFFL))
    }
}

object LfCommands {
    private fun hex(s: String, n: Int, what: String): String { val u = s.uppercase(); require(Regex("^[0-9A-F]{$n}$").matches(u)) { "$what is $n hex digits" }; return u }

    fun search(r: LfReader) = when (r) { LfReader.PROXMARK3 -> "lf search"; LfReader.FLIPPER -> "rfid read"; LfReader.EM_SERIAL -> "" }

    fun clone(r: LfReader, tag: LfTag): String = when (r) {
        LfReader.PROXMARK3 -> when (tag.type) {
            LfType.EM4100 -> "lf em 410x clone --id ${hex(tag.id, 10, "EM4100 ID")}"
            LfType.HID26 -> "lf hid clone -w H10301 --fc ${tag.fields["FC"]} --cn ${tag.fields["CN"]}"
            LfType.FDXB -> "lf fdxb clone --country ${tag.fields["Country"]} --national ${tag.fields["National ID"]}"
            LfType.T5577 -> error("write T5577 blocks one by one")
        }
        LfReader.FLIPPER -> when (tag.type) {
            LfType.EM4100 -> "rfid write EM4100 ${hex(tag.id, 10, "EM4100 ID")}"
            LfType.HID26 -> "rfid write H10301 %02X%04X".format(tag.fields["FC"]!!.toInt(), tag.fields["CN"]!!.toInt())
            else -> error("${tag.type.label}: clone it with a Proxmark3")
        }
        LfReader.EM_SERIAL -> error("this reader is read-only")
    }

    fun emulate(tag: LfTag) = when (tag.type) {
        LfType.EM4100 -> "rfid emulate EM4100 ${hex(tag.id, 10, "EM4100 ID")}"
        LfType.HID26 -> "rfid emulate H10301 %02X%04X".format(tag.fields["FC"]!!.toInt(), tag.fields["CN"]!!.toInt())
        else -> error("${tag.type.label}: no Flipper emulation here")
    }

    fun t55xxWrite(block: Int, data: String, password: String? = null): String {
        require(block in 0..7) { "block 0-7" }
        return "lf t55xx write -b $block -d ${hex(data, 8, "block data")}" + (password?.let { " -p ${hex(it, 8, "password")}" } ?: "")
    }

    const val T55XX_DETECT = "lf t55xx detect"
    const val T55XX_DUMP = "lf t55xx dump"
    const val EM_READER = "lf em 410x reader"
    const val HID_READER = "lf hid reader"
    const val FDX_READER = "lf fdxb reader"
}

object LfParse {
    private val EM = Regex("EM\\s?410x(?:\\s+ID)?\\s*:?\\s*([0-9A-Fa-f]{10})\\b|^\\s*EM4100\\s+([0-9A-Fa-f ]{10,14})\\s*$", RegexOption.MULTILINE)
    private val HID = Regex("H10301.*?FC:?\\s*(\\d+)\\s+CN:?\\s*(\\d+)|^\\s*H10301\\s+([0-9A-Fa-f]{2})\\s*([0-9A-Fa-f]{2})\\s*([0-9A-Fa-f]{2})\\s*$", RegexOption.MULTILINE)
    private val FDX = Regex("(?:Animal ID|FDX-?B)[^0-9]*(\\d{3})-(\\d{1,12})\\b", RegexOption.IGNORE_CASE)

    fun em(id: String) = LfTag(LfType.EM4100, id.uppercase(), Em4100.fields(id))
    fun hid(fc: Int, cn: Int) = LfTag(LfType.HID26, "%03d:%05d".format(fc, cn), linkedMapOf("FC" to fc.toString(), "CN" to cn.toString(), "Raw" to "%07X".format(Hid26.encode(fc, cn))))
    fun fdx(country: Int, national: Long) = LfTag(LfType.FDXB, FdxB.format(country, national), linkedMapOf("Country" to country.toString(), "National ID" to national.toString()))
    fun t5577(blocks: List<String>): LfTag {
        val c = T5577.parseConfig(blocks[0])
        return LfTag(LfType.T5577, blocks[0], linkedMapOf("Rate" to "RF/${c.rate}", "Modulation" to c.modulation, "Max block" to c.maxBlock.toString(), "Password" to (if (c.password) "on" else "off")), blocks)
    }

    /** Whatever a Proxmark3 or a Flipper printed: the first tag found, T5577 dumps first. */
    fun parse(text: String): LfTag? {
        T5577.parseDump(text).takeIf { it.isNotEmpty() }?.let { return t5577(it) }
        FDX.find(text)?.let { return fdx(it.groupValues[1].toInt(), it.groupValues[2].toLong()) }
        HID.find(text)?.let { m ->
            val g = m.groupValues
            return if (g[1].isNotEmpty()) hid(g[1].toInt(), g[2].toInt()) else hid(g[3].toInt(16), (g[4] + g[5]).toInt(16))
        }
        EM.find(text)?.let { m -> return em((m.groupValues[1].ifEmpty { m.groupValues[2] }).replace(" ", "")) }
        return null
    }
}

object LfLibrary {
    fun toJson(tags: List<LfTag>): String = JSONArray().apply {
        tags.forEach { t -> put(JSONObject().put("name", t.name).put("type", t.type.name).put("id", t.id).put("fields", JSONObject(t.fields)).put("blocks", JSONArray(t.blocks))) }
    }.toString(2)

    fun fromJson(s: String): List<LfTag> {
        val a = JSONArray(s)
        return (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            val f = o.optJSONObject("fields") ?: JSONObject()
            val b = o.optJSONArray("blocks") ?: JSONArray()
            LfTag(LfType.valueOf(o.getString("type")), o.getString("id"), f.keys().asSequence().associateWith { f.getString(it) }, (0 until b.length()).map { b.getString(it) }, o.optString("name"))
        }
    }

    private fun q(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun toCsv(tags: List<LfTag>): String = (listOf("name,type,id,blocks") + tags.map { listOf(it.name, it.type.name, it.id, it.blocks.joinToString(" ")).joinToString(",") { c -> q(c) } }).joinToString("\n")

    /** Fields are rebuilt from type + id (CSV keeps only the identity). */
    fun fromCsv(s: String): List<LfTag> = s.lines().drop(1).filter { it.isNotBlank() }.map { line ->
        val cells = mutableListOf<String>(); val cur = StringBuilder(); var inQ = false; var i = 0
        while (i < line.length) {
            val ch = line[i]
            when {
                inQ && ch == '"' && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
                ch == '"' -> inQ = !inQ
                ch == ',' && !inQ -> { cells += cur.toString(); cur.clear() }
                else -> cur.append(ch)
            }
            i++
        }
        cells += cur.toString()
        val type = LfType.valueOf(cells[1]); val id = cells[2]
        val blocks = cells.getOrElse(3) { "" }.split(' ').filter { it.isNotEmpty() }
        val base = when (type) {
            LfType.EM4100 -> LfParse.em(id)
            LfType.HID26 -> id.split(':').let { LfParse.hid(it[0].toInt(), it[1].toInt()) }
            LfType.FDXB -> id.split('-').let { LfParse.fdx(it[0].toInt(), it[1].toLong()) }
            LfType.T5577 -> LfParse.t5577(blocks)
        }
        base.copy(name = cells[0])
    }
}
