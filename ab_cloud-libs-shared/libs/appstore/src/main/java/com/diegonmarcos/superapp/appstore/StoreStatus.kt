package com.diegonmarcos.superapp.appstore

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The Store bar's two status controls, measured rather than inferred. The rules (mapping) are [ControlStatus]'s; this is the
 * device glue: the shell broker's status (one provider call, cached for [TTL_MS]), always run off the main thread, plus the Play
 * Protect read. #1512: the channel belongs to the SuperApp's broker, so there is no probe of an adb link of our own.
 */
internal object StoreStatus {
    private const val TTL_MS = 3_000L

    @Volatile private var cached: ControlStatus.ChannelStatus? = null
    @Volatile private var at = 0L
    @Volatile private var ppViaShell: ControlStatus.Tri? = null
    private val running = AtomicBoolean(false)

    fun channel(@Suppress("UNUSED_PARAMETER") ctx: Context): ControlStatus.ChannelStatus? = cached
    fun fresh(@Suppress("UNUSED_PARAMETER") ctx: Context): Boolean = cached != null && System.currentTimeMillis() - at < TTL_MS

    /** Cheap provider read; falls back to the last shell-read answer when the provider has none. */
    fun playProtect(ctx: Context): ControlStatus.Tri {
        val t = ControlStatus.playProtect(StorePlayProtect.reading(ctx))
        return if (t == ControlStatus.Tri.UNKNOWN) ppViaShell ?: t else t
    }

    fun invalidate() { cached = null; ppViaShell = null }

    /** Re-probe off the main thread (single flight), then [done] (call-site posts to the UI). */
    fun refresh(ctx: Context, done: () -> Unit) {
        if (!running.compareAndSet(false, true)) return
        val app = ctx.applicationContext
        thread(name = "store-status-probe") {
            try {
                val ch = ControlStatus.fromBroker(StoreShell.status(app))
                cached = ch; at = System.currentTimeMillis()
                if (ControlStatus.playProtect(StorePlayProtect.reading(app)) == ControlStatus.Tri.UNKNOWN && ch.shellUsable)
                    ppViaShell = ControlStatus.playProtect(StorePlayProtect.readingViaShell(app))
            } finally { running.set(false) }
            done()
        }
    }
}
