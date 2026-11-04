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
 * #1446 The home strip's speedmeter: the last reading against the rolling 24 h average, in Mbps and MB/s.
 *
 * Samples: a passive TrafficStats delta every [PASSIVE_MS] while the strip is on screen (kept only when the device really
 * moved data, so idle minutes do not drag the average to zero), and one sample per active test ([testNow]). Both are
 * stored on the device (SharedPreferences `speedmeter`), pruned to 24 h. The test is a bounded download from the fleet's
 * Caddy static host: at most [CAP_BYTES] ([CAP_METERED_BYTES] on a metered network) and [TEST_MS] in total.
 */
class SpeedMeter(private val context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sp = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    /** The latest reading in Mbps (a test or a passive sample), null before any. */
    var nowMbps by mutableStateOf<Double?>(null)
        private set
    /** The rolling 24 h average in Mbps, null with no sample. */
    var avgMbps by mutableStateOf<Double?>(null)
        private set
    var testing by mutableStateOf(false)
        private set

    private var lastRx = -1L
    private var lastAt = 0L

    private val tick = object : Runnable {
        override fun run() { passive(); main.postDelayed(this, PASSIVE_MS) }
    }

    fun start() {
        val s = load(); recompute(s)
        lastRx = TrafficStats.getTotalRxBytes(); lastAt = SystemClock.elapsedRealtime()
        main.postDelayed(tick, PASSIVE_MS)
    }

    fun stop() { main.removeCallbacks(tick) }

    private fun load(): List<SpeedSample> = SpeedMath.decode(sp.getString(KEY, "").orEmpty())

    private fun add(s: SpeedSample) {
        val all = SpeedMath.prune(load() + s, System.currentTimeMillis())
        sp.edit().putString(KEY, SpeedMath.encode(all)).apply()
        nowMbps = s.mbps
        recompute(all)
    }

    private fun recompute(all: List<SpeedSample>) {
        avgMbps = SpeedMath.average(all, System.currentTimeMillis())
        if (nowMbps == null) nowMbps = all.lastOrNull()?.mbps
    }

    private fun passive() {
        val rx = TrafficStats.getTotalRxBytes(); val t = SystemClock.elapsedRealtime()
        val d = rx - lastRx; val ms = t - lastAt
        lastRx = rx; lastAt = t
        if (rx == TrafficStats.UNSUPPORTED.toLong() || d < PASSIVE_MIN_BYTES || ms <= 0L) { recompute(load()); return }
        add(SpeedSample(System.currentTimeMillis(), d, ms, active = false))
    }

    private fun metered(): Boolean = runCatching {
        (app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
    }.getOrDefault(true)

    private fun online(): Boolean = runCatching {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }.getOrDefault(false)

    /** Tap: one bounded download test off the main thread. A second tap while it runs is ignored. */
    fun testNow() {
        if (testing || !online()) return
        testing = true
        val cap = if (metered()) CAP_METERED_BYTES else CAP_BYTES
        Thread({
            val r = runCatching { download(cap) }.getOrNull()
            main.post {
                testing = false
                if (r != null && r.ms > 0L && r.bytes > 0L) add(SpeedSample(System.currentTimeMillis(), r.bytes, r.ms, active = true))
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
        const val PASSIVE_MS = 60_000L
        const val PASSIVE_MIN_BYTES = 65_536L
    }
}
