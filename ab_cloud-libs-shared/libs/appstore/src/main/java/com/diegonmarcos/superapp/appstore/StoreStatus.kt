package com.diegonmarcos.superapp.appstore

import android.content.Context
import com.diegonmarcos.superapp.adbdebug.AdbScope
import com.diegonmarcos.superapp.adbdebug.ChannelMode
import com.diegonmarcos.superapp.adbdebug.ChannelModePrefs
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import com.diegonmarcos.superapp.adbdebug.WirelessDebugging
import com.diegonmarcos.superapp.adbdebug.ShizukuStatus
import com.diegonmarcos.superapp.adbdebug.EmbeddedAdbChannel
import com.diegonmarcos.superapp.adbdebug.PackageVerifier
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Store bar's two status controls, measured rather than inferred. The rules
 * (mapping, probe timeout, cache) are [ControlStatus]'s and unit-tested there; this
 * is the device glue: a cached live `id` round trip over whichever shell channel is
 * up, always run off the main thread, plus the Play Protect read.
 */
internal object StoreStatus {

    /** The probe belongs to ONE app: [AdbScope] serves it only to the package it was built for, so a status measured
     *  over another app's connection, keys or server is never shown here. */
    private val probes = AdbScope<ControlStatus.Probe>()
    @Volatile private var ppViaShell: ControlStatus.Tri? = null
    private val running = AtomicBoolean(false)

    private fun probe(ctx: Context): ControlStatus.Probe = probes.get(ctx.packageName) {
        ControlStatus.Probe(object : ControlStatus.Backend {
            val app = ctx.applicationContext
            override fun paired() = EmbeddedAdbChannel.everPaired(app)
            override fun id(): Pair<String, String?>? =
                ShellChannels.active(app)?.let { it.name() to it.exec(app, "id") }
            override fun adbEcho() = EmbeddedAdbChannel.probe(app)
            override fun wirelessDebugOn(): Boolean? = WirelessDebugging.isOn(app)
            override fun shizukuState() =
                if (ChannelModePrefs.current(app) == ChannelMode.SHIZUKU) ShizukuStatus.current(app) else null
        })
    }

    init {
        // Switching the mode re-probes at once; the install route already follows (it reads the mode per call).
        ChannelModePrefs.addListener { invalidate() }
    }

    fun channel(ctx: Context): ControlStatus.ChannelStatus? = probes.peek(ctx.packageName)?.peek()
    fun fresh(ctx: Context): Boolean = probes.peek(ctx.packageName)?.fresh() == true

    /** Cheap provider read; falls back to the last shell-read answer when the provider has none. */
    fun playProtect(ctx: Context): ControlStatus.Tri {
        val t = ControlStatus.playProtect(PackageVerifier.reading(ctx))
        return if (t == ControlStatus.Tri.UNKNOWN) ppViaShell ?: t else t
    }

    fun invalidate() { probes.current { it.invalidate() }; ppViaShell = null }

    /** Re-probe off the main thread (single flight), then [done] (call-site posts to the UI). */
    fun refresh(ctx: Context, done: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        thread(name = "store-status-probe") {
            try {
                val ch = probe(app).get(force = true)
                if (ControlStatus.playProtect(PackageVerifier.reading(app)) == ControlStatus.Tri.UNKNOWN &&
                    ch.shellUsable) {
                    ShellChannels.active(app)?.let {
                        ppViaShell = ControlStatus.playProtect(PackageVerifier.readingViaShell(app, it))
                    }
                }
            } finally { running.set(false) }
            done()
        }
    }
}
