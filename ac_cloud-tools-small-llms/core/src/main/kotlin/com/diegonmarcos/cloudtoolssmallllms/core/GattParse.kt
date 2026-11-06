package com.diegonmarcos.cloudtoolssmallllms.core

/** Decoded values of the standard GATT characteristics (Bluetooth SIG formats). A parser id is declared per read in assets/sensors.json. */
object GattParse {
    /** One decoded value; [key] is an id of assets/sensors.json::gatt_keys, which names it and gives its unit. */
    data class Value(val key: String, val value: Double)

    /** The values of [bytes] under [parser], or null for an unknown parser or a payload too short for its flags (the app then shows hex). */
    fun parse(parser: String, bytes: ByteArray): List<Value>? = try {
        when (parser) {
            "heart_rate" -> heartRate(bytes)
            "health_temperature" -> healthTemperature(bytes)
            "env_temperature" -> listOf(Value("temp_c", s16(bytes, 0) * 0.01))
            "env_humidity" -> listOf(Value("humidity", u16(bytes, 0) * 0.01))
            "env_pressure" -> listOf(Value("pressure_hpa", u32(bytes, 0) / 1000.0))
            "weight" -> weight(bytes)
            "pulse_oximeter" -> listOf(Value("spo2", sfloat(bytes, 1)), Value("bpm", sfloat(bytes, 3)))
            "blood_pressure" -> bloodPressure(bytes)
            else -> null
        }
    } catch (_: IndexOutOfBoundsException) { null }

    /** 0x2A37: flags bit0 = uint16 rate (else uint8), bit1 contact detected, bit2 contact supported, bit3 energy (uint16 kJ), bit4 R-R intervals (uint16, 1/1024 s). */
    fun heartRate(b: ByteArray): List<Value> {
        val f = u8(b, 0)
        var i = 1
        val out = ArrayList<Value>()
        if (f and 0x01 != 0) { out += Value("bpm", u16(b, i).toDouble()); i += 2 } else { out += Value("bpm", u8(b, i).toDouble()); i += 1 }
        if (f and 0x04 != 0) out += Value("contact", if (f and 0x02 != 0) 1.0 else 0.0)
        if (f and 0x08 != 0) { out += Value("energy_kj", u16(b, i).toDouble()); i += 2 }
        if (f and 0x10 != 0) while (i + 1 < b.size) { out += Value("rr_ms", u16(b, i) * 1000.0 / 1024.0); i += 2 }
        return out
    }

    /** 0x2A1C: flags bit0 = Fahrenheit, then an IEEE-11073 FLOAT; reported in Celsius. */
    private fun healthTemperature(b: ByteArray): List<Value> {
        val t = float32(b, 1)
        return listOf(Value("temp_c", if (u8(b, 0) and 0x01 != 0) (t - 32.0) * 5.0 / 9.0 else t))
    }

    /** 0x2A9D: flags bit0 = imperial (0.01 lb), else SI (0.005 kg); reported in kg. */
    private fun weight(b: ByteArray): List<Value> {
        val raw = u16(b, 1)
        return listOf(Value("weight_kg", if (u8(b, 0) and 0x01 != 0) raw * 0.01 * 0.45359237 else raw * 0.005))
    }

    /** 0x2A35: flags bit0 = kPa (else mmHg), then systolic, diastolic, MAP as IEEE-11073 SFLOAT; reported in mmHg. */
    private fun bloodPressure(b: ByteArray): List<Value> {
        val k = if (u8(b, 0) and 0x01 != 0) 7.50062 else 1.0
        return listOf(Value("bp_sys", sfloat(b, 1) * k), Value("bp_dia", sfloat(b, 3) * k))
    }

    fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xFF
    fun u16(b: ByteArray, i: Int): Int = u8(b, i) or (u8(b, i + 1) shl 8)
    fun s16(b: ByteArray, i: Int): Int = u16(b, i).toShort().toInt()
    fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    /** IEEE-11073 16-bit SFLOAT: 4-bit exponent and 12-bit mantissa, both signed; the reserved codes (NaN, NRes, +-infinity) map to NaN / infinity. */
    fun sfloat(b: ByteArray, i: Int): Double {
        val raw = u16(b, i)
        val m = raw and 0x0FFF
        when (m) { 0x7FF, 0x800, 0x801 -> return Double.NaN; 0x7FE -> return Double.POSITIVE_INFINITY; 0x802 -> return Double.NEGATIVE_INFINITY }
        val mant = if (m >= 0x800) m - 0x1000 else m
        val e = raw shr 12
        return mant * Math.pow(10.0, (if (e >= 8) e - 16 else e).toDouble())
    }

    /** IEEE-11073 32-bit FLOAT: 8-bit signed exponent, 24-bit signed mantissa. */
    fun float32(b: ByteArray, i: Int): Double {
        val raw = u32(b, i)
        val m = (raw and 0xFFFFFF).toInt()
        when (m) { 0x7FFFFF, 0x800000, 0x800001 -> return Double.NaN; 0x7FFFFE -> return Double.POSITIVE_INFINITY; 0x800002 -> return Double.NEGATIVE_INFINITY }
        val mant = if (m >= 0x800000) m - 0x1000000 else m
        return mant * Math.pow(10.0, (raw shr 24).toByte().toDouble())
    }
}
