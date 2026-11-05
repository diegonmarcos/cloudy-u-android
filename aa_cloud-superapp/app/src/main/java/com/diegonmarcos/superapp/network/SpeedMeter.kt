package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.TrafficStats
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1446 / #1508 The network dropdown's speedmeter: the live rate, the rolling 24 h average and the per-interface rates, down and up.
 *
 * It samples ONLY between [start] and [stop], which the dropdown calls while it is open: a TrafficStats (and /proc/net/dev)
 * delta every [sampleMs] (declared, build.json::home_status_bar.network_speed.sample_ms). There is no loop while the dropdown is
 * closed (battery rules #1487/#1502). The deltas are folded into one stored sample per [FLUSH_MS] (SharedPreferences
 * `speedmeter`, pruned to 24 h), kept only when the device really moved data; a bounded download test is [testNow].
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
    /** The rolling 24 h average download / upload in Mbps, null with no sample. */
    var avgDownMbps by mutableStateOf<Double?>(null)
        private set
    var avgUpMbps by mutableStateOf<Double?>(null)
        private set
    /** Interface name to (down, up) Mbps, from /proc/net/dev when the device lets the app read it; empty otherwise. */
    var ifaces by mutableStateOf<List<Triple<String, Double, Double>>>(emptyList())
        private set
    var testing by mutableStateOf(false)
        private set
    var running = false
        private set

    private var lastRx = -1L
    private var lastTx = -1L
    private var lastAt = 0L
    private var lastIf: Map<String, Pair<Long, Long>> = emptyMap()
    private var pendRx = 0L; private var pendTx = 0L; private var pendMs = 0L

    private val tick = object : Runnable {
        override fun run() { sample(); if (running) main.postDelayed(this, sampleMs) }
    }

    /** The dropdown opened: first sample now, then one per [sampleMs] until [stop]. */
    fun start() {
        if (running) return
        running = true
        recompute(load())
        lastRx = TrafficStats.getTotalRxBytes(); lastTx = TrafficStats.getTotalTxBytes()
        lastAt = SystemClock.elapsedRealtime(); lastIf = readIfaces()
        main.postDelayed(tick, sampleMs)
    }

    /** The dropdown closed: no callback stays posted. */
    fun stop() {
        running = false
        main.removeCallbacks(tick)
        flush()
    }

    private fun readIfaces(): Map<String, Pair<Long, Long>> =
        runCatching { SpeedMath.parseNetDev(java.io.File("/proc/net/dev").readText()) }.getOrDefault(emptyMap())

    private fun load(): List<SpeedSample> = SpeedMath.decode(sp.getString(KEY, "").orEmpty())

    private fun add(s: SpeedSample) {
        val all = SpeedMath.prune(load() + s, System.currentTimeMillis())
        sp.edit().putString(KEY, SpeedMath.encode(all)).apply()
        recompute(all)
    }

    private fun recompute(all: List<SpeedSample>) {
        val t = System.currentTimeMillis()
        avgDownMbps = SpeedMath.average(all, t); avgUpMbps = SpeedMath.averageTx(all, t)
        if (nowDownMbps == null) nowDownMbps = all.lastOrNull()?.mbps
    }

    private fun flush() {
        if (pendMs > 0L && pendRx + pendTx >= PASSIVE_MIN_BYTES) add(SpeedSample(System.currentTimeMillis(), pendRx, pendMs, active = false, txBytes = pendTx))
        pendRx = 0L; pendTx = 0L; pendMs = 0L
    }

    private fun sample() {
        val rx = TrafficStats.getTotalRxBytes(); val tx = TrafficStats.getTotalTxBytes(); val t = SystemClock.elapsedRealtime()
        val ms = t - lastAt
        val cur = readIfaces()
        if (rx != TrafficStats.UNSUPPORTED.toLong() && tx != TrafficStats.UNSUPPORTED.toLong() && lastRx >= 0L && ms > 0L) {
            val dr = (rx - lastRx).coerceAtLeast(0L); val dt = (tx - lastTx).coerceAtLeast(0L)
            nowDownMbps = SpeedMath.mbps(dr, ms); nowUpMbps = SpeedMath.mbps(dt, ms)
            pendRx += dr; pendTx += dt; pendMs += ms
            ifaces = cur.mapNotNull { (n, v) ->
                val p = lastIf[n] ?: return@mapNotNull null
                Triple(n, SpeedMath.mbps((v.first - p.first).coerceAtLeast(0L), ms), SpeedMath.mbps((v.second - p.second).coerceAtLeast(0L), ms))
            }
            if (pendMs >= FLUSH_MS) flush()
        }
        lastRx = rx; lastTx = tx; lastAt = t; lastIf = cur
    }

    private fun metered(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** The dropdown's Test link: one bounded download test off the main thread. A second tap while it runs is ignored. */
    fun testNow() {
        if (testing || !online()) return
        testing = true
        val cap = if (metered()) CAP_METERED_BYTES else CAP_BYTES
        Thread({
            val r = runCatching { download(cap) }.getOrNull()
            main.post {
                testing = false
                if (r != null && r.ms > 0L && r.bytes > 0L) { add(SpeedSample(System.currentTimeMillis(), r.bytes, r.ms, active = true)); nowDownMbps = SpeedMath.mbps(r.bytes, r.ms) }
            }
        }, "speedmeter-test").start()
    }

    private class Result(val bytes: Long, val ms: Long)

    /** Repeated no-cache GETs of the static host's front page on one keep-alive connection, until [cap] bytes or [TEST_MS]. */
    private fun download(cap: Long): Result {
        val t0 = SystemClock.elapsedRealtime()
        var total = 0L
        val buf = ByteArray(16 * 1024)
        while (total < cap && SystemClock.elapsedRealtime() - t0 < TEST_MS) {
            val c = URL(TEST_URL).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = 4_000; c.readTimeout = 4_000
                c.useCaches = false
                c.setRequestProperty("Cache-Control", "no-cache")
                c.setRequestProperty("Accept-Encoding", "identity")
                if (c.responseCode !in 200..299) break
                c.inputStream.use { ins ->
                    while (total < cap && SystemClock.elapsedRealtime() - t0 < TEST_MS) {
                        val n = ins.read(buf); if (n < 0) break; total += n
                    }
                }
            } finally { c.disconnect() }
        }
        return Result(total, SystemClock.elapsedRealtime() - t0)
    }

    companion object {
        private const val STORE = "speedmeter"
        private const val KEY = "samples"
        /** The fleet's Caddy static host (the public landing site). */
        const val TEST_URL = "https://diegonmarcos.com/"
        const val CAP_BYTES = 1_048_576L
        const val CAP_METERED_BYTES = 262_144L
        const val TEST_MS = 8_000L
        /** One stored sample per this much sampled time. */
        const val FLUSH_MS = 60_000L
        const val PASSIVE_MIN_BYTES = 65_536L
    }
}
