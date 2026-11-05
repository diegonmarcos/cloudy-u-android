package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * #1446 / #1523 The network dropdown's live meter: the rate now and the best 1 s rate of the last 24 h, down and up.
 *
 * It samples ONLY between [start] and [stop], which the dropdown calls while it is open: a TrafficStats delta every
 * [sampleMs] (declared, build.json::home_status_bar.network_speed.sample_ms). There is no loop while the dropdown is
 * closed (battery rules #1487/#1502). The deltas are folded into one stored sample per [FLUSH_MS] (SharedPreferences
 * `speedmeter`, pruned to 24 h), kept only when the device really moved data; they carry the peak. The 24 h average and
 * totals come from [NetUsage], not from here. The speed test is [SpeedTest], on a tap only.
 */
class SpeedMeter(private val context: Context, private val sampleMs: Long = 1_000L) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sp = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** The latest download / upload rate in Mbps, null before the first delta. */
    var nowDownMbps by mutableStateOf<Double?>(null)
        private set
    var nowUpMbps by mutableStateOf<Double?>(null)
        private set
    /** The best 1 s rate of the last 24 h in Mbps, null when none was seen. */
    var peakDownMbps by mutableStateOf<Double?>(null)
        private set
    var peakUpMbps by mutableStateOf<Double?>(null)
        private set
    var running = false
        private set
    /** How many deltas were taken: 0 until the dropdown opens, frozen once it closes (the test of "no sampling while closed"). */
    var sampleCount = 0
        private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var storedPeakRx = 0L; private var storedPeakTx = 0L
    private var pendRx = 0L; private var pendTx = 0L; private var pendMs = 0L
    private var pendPeakRx = 0L; private var pendPeakTx = 0L

    private val tick = object : Runnable {
        override fun run() { sample(); if (running) main.postDelayed(this, sampleMs) }
    }

    /** The dropdown opened: first sample now, then one per [sampleMs] until [stop]. */
    fun start() {
        if (running) return
        running = true
        loadPeaks()
        lastRx = TrafficStats.getTotalRxBytes(); lastTx = TrafficStats.getTotalTxBytes()
        lastAt = SystemClock.elapsedRealtime()
        main.postDelayed(tick, sampleMs)
    }

    /** The dropdown closed: no callback stays posted. */
    fun stop() {
        running = false
        main.removeCallbacks(tick)
        flush()
    }

    private fun load(): List<SpeedSample> = SpeedMath.decode(sp.getString(KEY, "").orEmpty())

    private fun loadPeaks() {
        val all = load(); val t = System.currentTimeMillis()
        storedPeakRx = ((SpeedMath.peak(all, t, tx = false) ?: 0.0) * 1000).toLong()
        storedPeakTx = ((SpeedMath.peak(all, t, tx = true) ?: 0.0) * 1000).toLong()
        showPeaks()
    }

    private fun showPeaks() {
        val rx = maxOf(storedPeakRx, pendPeakRx); val tx = maxOf(storedPeakTx, pendPeakTx)
        peakDownMbps = if (rx > 0L) rx / 1000.0 else null
        peakUpMbps = if (tx > 0L) tx / 1000.0 else null
    }

    private fun flush() {
        if (pendMs > 0L && pendRx + pendTx >= PASSIVE_MIN_BYTES) {
            val all = SpeedMath.prune(load() + SpeedSample(System.currentTimeMillis(), pendRx, pendMs, active = false, txBytes = pendTx,
                peakRxKbps = pendPeakRx, peakTxKbps = pendPeakTx), System.currentTimeMillis())
            sp.edit().putString(KEY, SpeedMath.encode(all)).apply()
            storedPeakRx = maxOf(storedPeakRx, pendPeakRx); storedPeakTx = maxOf(storedPeakTx, pendPeakTx)
        }
        pendRx = 0L; pendTx = 0L; pendMs = 0L; pendPeakRx = 0L; pendPeakTx = 0L
    }

    private fun sample() {
        sampleCount++
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val t = SystemClock.elapsedRealtime()
        val ms = t - lastAt
        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong() && lastRx >= 0L && ms > 0L) {
            val dr = (rx - lastRx).coerceAtLeast(0L); val dt = (tx - lastTx).coerceAtLeast(0L)
            nowDownMbps = SpeedMath.mbps(dr, ms); nowUpMbps = SpeedMath.mbps(dt, ms)
            pendRx += dr; pendTx += dt; pendMs += ms
            pendPeakRx = maxOf(pendPeakRx, SpeedMath.kbps(dr, ms)); pendPeakTx = maxOf(pendPeakTx, SpeedMath.kbps(dt, ms))
            showPeaks()
            if (pendMs >= FLUSH_MS) flush()
        }
        lastRx = rx; lastTx = tx; lastAt = t
    }

    companion object {
        private const val STORE = "speedmeter"
        private const val KEY = "samples"
        /** One stored sample per this much sampled time. */
        const val FLUSH_MS = 60_000L
        const val PASSIVE_MIN_BYTES = 65_536L
    }
}
