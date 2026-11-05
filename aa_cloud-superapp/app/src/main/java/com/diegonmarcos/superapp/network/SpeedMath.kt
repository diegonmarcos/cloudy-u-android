package com.diegonmarcos.superapp.network

/**
 * #1446 The speedmeter's arithmetic, pure JVM: unit conversion, the rolling 24 h average and the stored-sample codec.
 * One sample = [bytes] moved in [ms] at [atMs] (wall clock), either a passive TrafficStats delta or an active test.
 */
internal data class SpeedSample(val atMs: Long, val bytes: Long, val ms: Long, val active: Boolean, val txBytes: Long = 0L) {
    /** Megabits per second of this sample; 0 for an empty window. */
    val mbps: Double get() = SpeedMath.mbps(bytes, ms)
}

internal object SpeedMath {
    const val WINDOW_MS = 24L * 60 * 60 * 1000
    /** Stored samples are capped so the store stays small (one passive sample a minute at most is 1440 a day). */
    const val MAX_SAMPLES = 2000

    /** Megabits per second (1 Mbit = 1 000 000 bit) for [bytes] moved in [ms]; 0 when [ms] <= 0 or [bytes] < 0. */
    fun mbps(bytes: Long, ms: Long): Double = if (ms <= 0L || bytes < 0L) 0.0 else bytes * 8.0 / 1_000_000.0 / (ms / 1000.0)

    /** Megabytes per second from megabits per second: MB/s = Mbps / 8. */
    fun mbPerSec(mbps: Double): Double = mbps / 8.0

    /** The samples inside the window ending at [nowMs] (inclusive of the boundary), oldest first. */
    fun inWindow(samples: List<SpeedSample>, nowMs: Long, windowMs: Long = WINDOW_MS): List<SpeedSample> =
        samples.filter { it.atMs in (nowMs - windowMs)..nowMs }.sortedBy { it.atMs }

    /**
     * The rolling average in Mbps over the window: total bits over total time (duration-weighted), so a long busy sample
     * counts for more than a one-second blip. null when the window holds no measured time.
     */
    fun average(samples: List<SpeedSample>, nowMs: Long, windowMs: Long = WINDOW_MS): Double? {
        val w = inWindow(samples, nowMs, windowMs).filter { it.ms > 0L && it.bytes >= 0L }
        val ms = w.sumOf { it.ms }
        return if (ms <= 0L) null else mbps(w.sumOf { it.bytes }, ms)
    }

    /** The rolling UPLOAD average in Mbps over the window, like [average]; null when nothing was measured. */
    fun averageTx(samples: List<SpeedSample>, nowMs: Long, windowMs: Long = WINDOW_MS): Double? {
        val w = inWindow(samples, nowMs, windowMs).filter { it.ms > 0L && it.bytes >= 0L }
        val ms = w.sumOf { it.ms }
        return if (ms <= 0L) null else mbps(w.sumOf { it.txBytes }, ms)
    }

    /** One dropdown row: "Now  down 1.2Mb  up 0.3Mb"; "--" for an unknown side. */
    fun rowLine(label: String, downMbps: Double?, upMbps: Double?): String =
        "$label  \u2193 ${fmt(downMbps)}Mb  \u2191 ${fmt(upMbps)}Mb"

    /** Per-interface counters of /proc/net/dev text: name to (rx bytes, tx bytes); loopback and unparsable lines skipped. */
    fun parseNetDev(text: String): Map<String, Pair<Long, Long>> = text.lineSequence().mapNotNull { l ->
        val i = l.indexOf(':'); if (i < 0) return@mapNotNull null
        val name = l.substring(0, i).trim()
        val f = l.substring(i + 1).trim().split(Regex("\\s+"))
        val rx = f.getOrNull(0)?.toLongOrNull(); val tx = f.getOrNull(8)?.toLongOrNull()
        if (name.isEmpty() || name == "lo" || rx == null || tx == null) null else name to (rx to tx)
    }.toMap()

    /** Drop what left the window and keep the newest [MAX_SAMPLES]. */
    fun prune(samples: List<SpeedSample>, nowMs: Long, windowMs: Long = WINDOW_MS): List<SpeedSample> =
        inWindow(samples, nowMs, windowMs).takeLast(MAX_SAMPLES)

    /** "12.3" below 100, "123" from 100 up, "0.0" for nothing, "--" for unknown. */
    fun fmt(v: Double?): String = when {
        v == null || v.isNaN() -> "--"
        v >= 100.0 -> "%.0f".format(java.util.Locale.US, v)
        else -> "%.1f".format(java.util.Locale.US, v)
    }

    /** The strip's two short lines for a reading and an average: "now 42.1Mb 5.3MB/s" style. */
    fun line(tag: String, mbps: Double?): String =
        if (mbps == null) "$tag --" else "$tag ${fmt(mbps)}Mb ${fmt(mbPerSec(mbps))}MB/s"

    fun encode(samples: List<SpeedSample>): String =
        samples.joinToString("\n") { "${it.atMs},${it.bytes},${it.ms},${if (it.active) 1 else 0},${it.txBytes}" }

    /** Lines that do not parse are skipped, never thrown. */
    fun decode(text: String): List<SpeedSample> = text.lineSequence().mapNotNull { l ->
        val p = l.split(',')
        if (p.size != 4 && p.size != 5) return@mapNotNull null
        val a = p[0].toLongOrNull(); val b = p[1].toLongOrNull(); val m = p[2].toLongOrNull()
        if (a == null || b == null || m == null) null else SpeedSample(a, b, m, p[3] == "1", p.getOrNull(4)?.toLongOrNull() ?: 0L)
    }.toList()
}
