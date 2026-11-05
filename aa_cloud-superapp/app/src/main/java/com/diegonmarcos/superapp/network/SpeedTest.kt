package com.diegonmarcos.superapp.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.launcher.SpeedTestDecl
import java.net.HttpURLConnection
import java.net.URL

/**
 * #1523 The "Speed test" action of the network dropdown. It runs ONLY when tapped (never on open, never on a timer): latency
 * and jitter from a few small requests, then a bounded download and a bounded upload against the declared endpoint
 * (build.json::home_status_bar.network_speed.speed_test). The runs of the last 24 h are kept in SharedPreferences `speedtest`.
 */
internal class SpeedTest(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val sp = app.getSharedPreferences(STORE, Context.MODE_PRIVATE)

    var running by mutableStateOf(false)
        private set
    /** "latency" | "download" | "upload" while running. */
    var phase by mutableStateOf("")
        private set
    var failure by mutableStateOf<String?>(null)
        private set
    /** The runs of the last 24 h, oldest first. */
    var runs by mutableStateOf(SpeedHist.prune(SpeedHist.decode(sp.getString(KEY, "").orEmpty()), System.currentTimeMillis()))
        private set

    private fun cm() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private fun caps(): NetworkCapabilities? = runCatching { cm().let { it.getNetworkCapabilities(it.activeNetwork) } }.getOrNull()

    fun tap(d: SpeedTestDecl) {
        if (running) return
        val c = caps()
        if (c == null || !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) { failure = "offline"; return }
        val metered = !c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        val via = when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        running = true; failure = null; phase = "latency"
        Thread({
            val r = runCatching { measure(d, if (metered) d.meteredDivisor.coerceAtLeast(1) else 1, via) }
            main.post {
                running = false; phase = ""
                r.onSuccess { run ->
                    val all = SpeedHist.prune(runs + run, System.currentTimeMillis())
                    sp.edit().putString(KEY, SpeedHist.encode(all)).apply(); runs = all
                }.onFailure { failure = it.message?.take(40) ?: it.javaClass.simpleName }
            }
        }, "speedtest").start()
    }

    private fun setPhase(p: String) = main.post { phase = p }

    private fun open(u: String): HttpURLConnection = (URL(u).openConnection() as HttpURLConnection).also {
        it.connectTimeout = 4_000; it.readTimeout = 6_000; it.useCaches = false
        it.setRequestProperty("Cache-Control", "no-cache"); it.setRequestProperty("Accept-Encoding", "identity")
    }

    private fun measure(d: SpeedTestDecl, div: Int, via: String): SpeedRun {
        val buf = ByteArray(32 * 1024)
        // latency + jitter: the first request pays the handshake and is dropped
        val rtt = ArrayList<Double>()
        for (i in 0 until d.pingCount.coerceAtLeast(2)) {
            val t0 = System.nanoTime()
            val c = open(d.pingUrl)
            try { if (c.responseCode in 200..299) c.inputStream.use { while (it.read(buf) >= 0) Unit } else error("ping HTTP ${c.responseCode}") } finally { c.disconnect() }
            if (i > 0) rtt += (System.nanoTime() - t0) / 1e6
        }
        val (lat, jit) = SpeedMath.latencyJitter(rtt)
        setPhase("download")
        val dn = d.downloadBytes / div
        val c = open(d.downloadUrl.replace("{bytes}", dn.toString()))
        var total = 0L; var t1 = 0L; var tEnd = 0L
        try {
            if (c.responseCode !in 200..299) error("download HTTP ${c.responseCode}")
            val limit = System.nanoTime() + d.maxSeconds * 1_000_000_000L
            c.inputStream.use { ins ->
                while (System.nanoTime() < limit) {
                    val n = ins.read(buf); if (n < 0) break
                    if (t1 == 0L) t1 = System.nanoTime() else total += n   // the first chunk starts the clock
                }
            }
            tEnd = System.nanoTime()
        } finally { c.disconnect() }
        val down = if (t1 > 0L && total > 0L) SpeedMath.mbps(total, ((tEnd - t1) / 1_000_000L).coerceAtLeast(1L)) else error("no data")
        setPhase("upload")
        val up = runCatching { upload(d.uploadUrl, d.uploadBytes / div, d.maxSeconds) }.getOrNull()
        return SpeedRun(System.currentTimeMillis(), down, up, lat, jit, via)
    }

    private fun upload(url: String, bytes: Long, maxSeconds: Int): Double {
        val c = open(url)
        var sent = 0L
        val t0 = System.nanoTime(); val limit = t0 + maxSeconds * 1_000_000_000L
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(bytes)
            c.setRequestProperty("Content-Type", "application/octet-stream")
            val chunk = ByteArray(32 * 1024)
            c.outputStream.use { out ->
                while (sent < bytes && System.nanoTime() < limit) { val n = minOf(chunk.size.toLong(), bytes - sent).toInt(); out.write(chunk, 0, n); sent += n }
            }
            if (sent < bytes) error("upload cut at the time cap")
            if (c.responseCode !in 200..299) error("upload HTTP ${c.responseCode}")
            c.inputStream.use { while (it.read(chunk) >= 0) Unit }
        } finally { c.disconnect() }
        return SpeedMath.mbps(sent, ((System.nanoTime() - t0) / 1_000_000L).coerceAtLeast(1L))
    }

    companion object {
        private const val STORE = "speedtest"
        private const val KEY = "runs"
    }
}
