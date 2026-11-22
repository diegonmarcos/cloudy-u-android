package com.diegonmarcos.superapp.repoworkspace

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
 * Binds [IRepoWorkspace] of the first app in [RepoWorkspace.SERVICE_PACKAGES] that publishes it.
 * The bind lifecycle is TextToolsClient's: resolved by intent on every bind, rebound after a died
 * binding (an update), rate-limited retries while unbound. Every call BLOCKS on the calling thread
 * (up to [CALL_WAIT_MS] for the first connect): never call on a main thread.
 * Every method answers a value and never throws: not installed / not bound = empty.
 */
class RepoWorkspaceClient(context: Context) {
    private val app = context.applicationContext

    @Volatile private var ws: IRepoWorkspace? = null
    @Volatile private var lastBindAttempt = 0L
    @Volatile private var connected = CountDownLatch(1)

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            ws = IRepoWorkspace.Stub.asInterface(binder)
            connected.countDown()
            Log.i(TAG, "connected to $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            ws = null
            connected = CountDownLatch(1)
            bind()
        }

        override fun onBindingDied(name: ComponentName) {
            ws = null
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
            app.packageManager.queryIntentServices(Intent(RepoWorkspace.ACTION), 0).mapNotNull { it.serviceInfo?.packageName }.toSet()
        }.getOrDefault(emptySet<String>())
        return RepoWorkspace.SERVICE_PACKAGES.firstOrNull { it in publishing }
    }

    @Synchronized
    private fun bind() {
        lastBindAttempt = SystemClock.elapsedRealtime()
        val target = resolveServingPackage() ?: run {
            Log.w(TAG, "no installed app publishes ${RepoWorkspace.ACTION}")
            return
        }
        val ok = runCatching {
            app.bindService(Intent(RepoWorkspace.ACTION).apply { setPackage(target) }, conn, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (!ok) Log.w(TAG, "bindService to $target failed - is it force-stopped?")
    }

    private fun bound(): IRepoWorkspace? {
        ws?.let { return it }
        // Drive not installed: answer at once, never wait out the connect deadline for a peer that cannot come.
        if (resolveServingPackage() == null) return null
        if (SystemClock.elapsedRealtime() - lastBindAttempt > REBIND_MS) bind()
        runCatching { connected.await(CALL_WAIT_MS, TimeUnit.MILLISECONDS) }
        return ws
    }

    /** A serving app is installed (asked of the package manager, not of the binding). */
    fun isServingAppInstalled(): Boolean = resolveServingPackage() != null

    fun isConnected(): Boolean = bound() != null

    /** Every repository on every volume Drive knows; empty when Drive is absent. */
    fun listRepos(): List<RepoWorkspace.RepoInfo> =
        bound()?.let { w -> runCatching { RepoWorkspace.RepoInfo.decodeAll(w.listRepos()) }.getOrNull() }.orEmpty()

    /** Every clone of [name] on any volume; empty when it is nowhere (or Drive is absent). */
    fun findRepo(name: String): List<RepoWorkspace.RepoInfo> =
        bound()?.let { w -> runCatching { RepoWorkspace.RepoInfo.decodeAll(w.findRepo(name)) }.getOrNull() }.orEmpty()

    fun status(path: String): RepoWorkspace.RepoInfo? =
        bound()?.let { w -> runCatching { RepoWorkspace.RepoInfo.decode(w.status(path)) }.getOrNull() }

    /**
     * Open Drive's clone flow (Sync > Git, the clone dialog prefilled with [name]). Returns "" when the
     * request was handed over, else a readable reason. Returns at once; [activity] must be a foreground
     * context (only a foreground app may start an activity).
     */
    fun requestClone(activity: Context, name: String): String {
        if (!RepoWorkspace.validName(name)) return "not a repository name"
        val target = resolveServingPackage() ?: return RepoWorkspace.NOT_INSTALLED
        val refused = bound()?.let { w -> runCatching { w.requestClone(name) }.getOrNull() }
        if (!refused.isNullOrEmpty()) return refused
        return runCatching {
            activity.startActivity(Intent(RepoWorkspace.ACTION_CLONE).setPackage(target)
                .putExtra(RepoWorkspace.EXTRA_NAME, name.trim()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            ""
        }.getOrElse { "Cloud Drive did not open: ${it.message}" }
    }

    companion object {
        private const val TAG = "RepoWorkspaceClient"
        private const val REBIND_MS = 5_000L
        private const val CALL_WAIT_MS = 2_500L
    }
}
