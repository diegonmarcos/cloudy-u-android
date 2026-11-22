package com.diegonmarcos.superapp.aiengine

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Binds [IAiEngine] of the first app in [AiEngine.SERVICE_PACKAGES] that publishes it.
 * The bind lifecycle is TextToolsClient's: resolved by intent on every bind, rebound after a died
 * binding (an update), rate-limited retries while unbound. Every call BLOCKS on the calling thread
 * (up to [CALL_WAIT_MS] for the first connect): never call on a main thread (chat() included: it only
 * waits for the bind, the stream itself arrives on the listener).
 * Every method answers a value and never throws: not installed / not bound = empty.
 */
class AiEngineClient(context: Context) {
    private val app = context.applicationContext

    @Volatile private var engine: IAiEngine? = null
    @Volatile private var lastBindAttempt = 0L
    @Volatile private var connected = CountDownLatch(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IAiEngine.Stub.asInterface(binder)
            connected.countDown()
            Log.i(TAG, "connected to $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
            connected = CountDownLatch(1)
            bind()
        }

        override fun onBindingDied(name: ComponentName) {
            engine = null
            connected = CountDownLatch(1)
            runCatching { app.unbindService(this) }
            bind()
        }

        override fun onNullBinding(name: ComponentName) {
            Log.w(TAG, "$name returned a null binder")
        }
    }

    init { bind() }

    private fun resolveServingPackage(): String? {
        val publishing = runCatching {
            app.packageManager.queryIntentServices(Intent(AiEngine.ACTION), 0).mapNotNull { it.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet<String>())
        return AiEngine.SERVICE_PACKAGES.firstOrNull { it in publishing }
    }

    @Synchronized
    private fun bind() {
        lastBindAttempt = SystemClock.elapsedRealtime()
        val target = resolveServingPackage() ?: run {
            Log.w(TAG, "no installed app publishes ${AiEngine.ACTION}")
            return
        }
        val ok = runCatching {
            app.bindService(Intent(AiEngine.ACTION).apply { setPackage(target) }, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) Log.w(TAG, "bindService to $target failed - is it force-stopped?")
    }

    private fun bound(): IAiEngine? {
        engine?.let { return it }
        // SuperIntel not installed: answer at once, never wait out the connect deadline for a peer that cannot come.
        if (resolveServingPackage() == null) return null
        if (SystemClock.elapsedRealtime() - lastBindAttempt > REBIND_MS) bind()
        runCatching { connected.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS) }
        return engine
    }

    /** A serving app is installed (asked of the package manager, not of the binding). */
    fun isServingAppInstalled(): Boolean = resolveServingPackage() != null

    fun isConnected(): Boolean = bound() != null

    /** The server's contract version; 0 when SuperIntel is absent. */
    fun version(): Int = bound()?.let { e -> runCatching { e.version() }.getOrDefault(0) } ?: 0

    /** Every provider with its live health; empty when SuperIntel is absent. */
    fun providers(): List<AiEngine.ProviderInfo> =
        bound()?.let { e -> runCatching { AiEngine.ProviderInfo.decodeAll(e.providers()) }.getOrNull() }.orEmpty()

    /** The models of [providerId]; empty when unknown (or SuperIntel is absent). */
    fun models(providerId: String): List<AiEngine.ModelInfo> =
        bound()?.let { e -> runCatching { AiEngine.ModelInfo.decodeAll(e.models(providerId)) }.getOrNull() }.orEmpty()

    /** The cost summary JSON; "{}" when nothing was metered or SuperIntel is absent. */
    fun usage(): String = bound()?.let { e -> runCatching { e.usage() }.getOrNull() }?.ifBlank { null } ?: "{}"

    /**
     * Start a chat; the stream arrives on [listener]. Returns "" when SuperIntel accepted the request, else a readable
     * reason and the listener is NEVER called (so a caller can fall back to its own road at once).
     */
    fun chat(requestJson: String, listener: AiEngine.Listener): String {
        if (resolveServingPackage() == null) return AiEngine.NOT_INSTALLED
        val e = bound() ?: return "Cloud SuperIntel did not answer: " + AiEngine.NOT_INSTALLED
        val cb = object : IAiEngineCallback.Stub() {
            override fun onChunk(text: String?) { listener.onChunk(text.orEmpty()) }
            override fun onDone(usageJson: String?) { listener.onDone(usageJson.orEmpty()) }
            override fun onError(reason: String?) { listener.onError(reason.orEmpty().ifEmpty { "failed" }) }
        }
        return runCatching { e.chat(requestJson, cb) }.getOrElse { "Cloud SuperIntel did not take the request: ${it.message}" }.orEmpty()
    }

    /** Stop the running chat [requestId]; false when SuperIntel could not be reached. */
    fun cancel(requestId: String): Boolean =
        bound()?.let { e -> runCatching { e.cancel(requestId); true }.getOrDefault(false) } ?: false

    companion object {
        private const val TAG = "AiEngineClient"
        private const val REBIND_MS = 5_000L
        private const val CALL_WAIT_MS = 2_500L
    }
}
