package com.diegonmarcos.superapp.adbside

import android.app.Application
import android.content.Context
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.configs.AdbOwnerLinkFragment
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.system.AdbReadiness
import com.diegonmarcos.superapp.system.ClientPlaneWorker

/**
 * #1682 P3 step 5: the CLIENT variant of the one door to the ADB owner (src/client, compiled once 1_cicd/src/data/adb-owner.json names
 * another app). Same members as the owner variant; everything the owner does in this process (pairing, the channel, the keeper, the
 * plane, the dev routes) is the owner app's, so here each one is a no-op, an honest "not here", or a link to the owner's page
 * ([ShellClient.openArmPage]). No owner lib is linked in this build.
 */
object AdbSide {
    const val OWNER = false

    class Reply(val status: String, val body: String, val json: Boolean)

    /** AdbReadiness asks the owner over ShellClient; "open the ADB page" is the owner app's own entry. The plane re-runs whenever the owner turns usable. */
    fun install(app: Application, inFrame: (Context) -> Boolean) {
        AdbReadiness.addListener { st -> if (st.armed) armPlane(app, androidx.work.ExistingWorkPolicy.KEEP) }
    }
    /** Plane at every app start (unique, KEEP): the owner's own boot arms the channel, this sends the SuperApp's grants and tuning through it. */
    fun installBoot(app: Application) { runCatching { armPlane(app, androidx.work.ExistingWorkPolicy.KEEP) } }
    fun registerBridge(app: Application) = Unit

    private fun armPlane(c: Context, policy: androidx.work.ExistingWorkPolicy) {
        runCatching {
            androidx.work.WorkManager.getInstance(c).enqueueUniqueWork(
                "privileged-plane", policy, androidx.work.OneTimeWorkRequestBuilder<ClientPlaneWorker>().build())
        }
    }

    /** The plane worker, re-armed now (Permissions page refresh). */
    fun armPlane(ctx: Context) = armPlane(ctx, androidx.work.ExistingWorkPolicy.REPLACE)

    /** The keep-alive switch is the owner's: read its REAL state over the broker (keeper_get), flip it there (keeper_set); only when the owner is
     *  unreachable does a write fall back to opening the owner's page and a read say off. */
    private fun keeper(ctx: Context, set: Boolean?): Boolean? = runCatching {
        val raw = ShellClient.over(ctx).transport.call(if (set == null) "keeper_get" else "keeper_set", if (set == true) "1" else "0", emptyMap())
        raw?.let { org.json.JSONObject(it).takeIf { o -> o.has("keeper") }?.getBoolean("keeper") }
    }.getOrNull()
    fun keeperEnabled(ctx: Context): Boolean = keeper(ctx, null) ?: false
    fun setKeeper(ctx: Context, on: Boolean) { if (keeper(ctx, on) == null) ShellClient.openArmPage(ctx) }
    fun keeperRows(ctx: Context): List<Pair<String, String>> =
        listOf("keep-alive (owner)" to (keeper(ctx, null)?.let { if (it) "on" else "off" } ?: "owner unreachable"))

    /** Configs > Network > ADB Shell (and its tabs): a page that opens the owner's. */
    fun page(pageId: String): Fragment = AdbOwnerLinkFragment.newInstance()

    val devRoutes: Set<String> get() = emptySet()
    fun devRoute(ctx: Context, op: String, query: Map<String, String>): Reply? = null
    fun devAutoConnectAtStart(app: Context) = Unit
}
