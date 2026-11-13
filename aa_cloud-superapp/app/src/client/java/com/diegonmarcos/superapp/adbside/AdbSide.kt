package com.diegonmarcos.superapp.adbside

import android.app.Application
import android.content.Context
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.configs.AdbOwnerLinkFragment
import com.diegonmarcos.superapp.shellclient.ShellClient

/**
 * #1682 P3 step 5: the CLIENT variant of the one door to the ADB owner (src/client, compiled once 1_cicd/src/data/adb-owner.json names
 * another app). Same members as the owner variant; everything the owner does in this process (pairing, the channel, the keeper, the
 * plane, the dev routes) is the owner app's, so here each one is a no-op, an honest "not here", or a link to the owner's page
 * ([ShellClient.openArmPage]). No owner lib is linked in this build.
 */
object AdbSide {
    const val OWNER = false

    class Reply(val status: String, val body: String, val json: Boolean)

    /** Nothing to install: AdbReadiness asks the owner over ShellClient; "open the ADB page" is the owner app's own entry. */
    fun install(app: Application, inFrame: (Context) -> Boolean) = Unit
    fun installBoot(app: Application) = Unit
    fun registerBridge(app: Application) = Unit

    /** The owner's plane re-arms itself in the owner's process. */
    fun armPlane(ctx: Context) = Unit

    /** The keep-alive switch is the owner's: a read says off (the owner's page shows the truth), a write opens the owner's page. */
    fun keeperEnabled(ctx: Context): Boolean = false
    fun setKeeper(ctx: Context, on: Boolean) { ShellClient.openArmPage(ctx) }
    fun keeperRows(ctx: Context): List<Pair<String, String>> = listOf("owner" to "see the ADB owner app (Cloud Webserver > ADB Shell)")

    /** Configs > Network > ADB Shell (and its tabs): a page that opens the owner's. */
    fun page(pageId: String): Fragment = AdbOwnerLinkFragment.newInstance()

    val devRoutes: Set<String> get() = emptySet()
    fun devRoute(ctx: Context, op: String, query: Map<String, String>): Reply? = null
    fun devAutoConnectAtStart(app: Context) = Unit
}
