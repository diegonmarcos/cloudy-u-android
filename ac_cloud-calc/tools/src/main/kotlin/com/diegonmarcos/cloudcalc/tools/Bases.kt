package com.diegonmarcos.cloudcalc.tools

import java.math.BigInteger

/**
 * #1409 Decimals ▸ Convert ▸ Base: a number in any base 2..36, exactly. A value is a fraction [num]/[den] with [den] a
 * power of the base it was typed in, so a typed fraction is never rounded on the way in; printing it in another base
 * stops after [maxFrac] digits and marks the cut with "…" when the expansion does not end. Negatives print with a minus
 * sign, or as the [bits]-wide two's complement pattern when asked ([twos], integers only).
 */
object Bases {
    const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"
    const val MIN = 2
    const val MAX = 36
    val BIT_WIDTHS = listOf(8, 16, 32, 64)

    data class Value(val num: BigInteger, val den: BigInteger) {
        val isInteger: Boolean get() = num.mod(den).signum() == 0
    }

    /** [text] in [base] ("-1a.8", spaces and underscores ignored, a trailing "…" ignored); null when it is not one. */
    fun parse(text: String, base: Int): Value? {
        if (base !in MIN..MAX) return null
        var t = text.trim().replace(" ", "").replace("_", "").removeSuffix("…").lowercase()
        val neg = t.startsWith("-")
        t = t.removePrefix("-")
        if (t.isEmpty() || t == "." || t.count { it == '.' } > 1) return null
        val b = BigInteger.valueOf(base.toLong())
        var num = BigInteger.ZERO
        var den = BigInteger.ONE
        var frac = false
        for (c in t) {
            if (c == '.') { frac = true; continue }
            val v = DIGITS.indexOf(c)
            if (v < 0 || v >= base) return null
            num = num.multiply(b).add(BigInteger.valueOf(v.toLong()))
            if (frac) den = den.multiply(b)
        }
        return Value(if (neg) num.negate() else num, den)
    }

    /** [v] written in [base], at most [maxFrac] fraction digits ("…" when more would follow). */
    fun format(v: Value, base: Int, maxFrac: Int = 24): String {
        val b = BigInteger.valueOf(base.toLong())
        val a = v.num.abs()
        val (ip, rem0) = a.divideAndRemainder(v.den)
        val sb = StringBuilder(if (v.num.signum() < 0) "-" else "").append(ip.toString(base))
        var rem = rem0
        if (rem.signum() != 0) {
            sb.append('.')
            var n = 0
            while (rem.signum() != 0 && n < maxFrac) {
                val (q, r) = rem.multiply(b).divideAndRemainder(v.den)
                sb.append(DIGITS[q.toInt()])
                rem = r; n++
            }
            if (rem.signum() != 0) sb.append('…')
        }
        return sb.toString()
    }

    /** The [bits]-wide two's complement of the integer [v] in [base]; null when it does not fit (or is not an integer). */
    fun twos(v: Value, base: Int, bits: Int): String? {
        if (!v.isInteger) return null
        val n = v.num.divide(v.den)
        val half = BigInteger.ONE.shiftLeft(bits - 1)
        if (n < half.negate() || n >= half) return null
        return (if (n.signum() < 0) n.add(BigInteger.ONE.shiftLeft(bits)) else n).toString(base)
    }

    /** A [bits]-wide two's complement pattern typed in [base] back to its signed value; null when it is wider. */
    fun fromTwos(text: String, base: Int, bits: Int): Value? {
        val p = parse(text, base) ?: return null
        if (!p.isInteger || p.num.signum() < 0) return null
        val n = p.num.divide(p.den)
        if (n.bitLength() > bits) return null
        return Value(if (n.testBit(bits - 1)) n.subtract(BigInteger.ONE.shiftLeft(bits)) else n, BigInteger.ONE)
    }
}
