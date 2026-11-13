package com.diegonmarcos.superapp.adbside

import android.app.Application
import android.content.Context
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.adbdebug.AdbPairingService
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.ChannelAutoArm
import com.diegonmarcos.superapp.adbdebug.HostShell
import com.diegonmarcos.superapp.configs.AdbCockpitModel
import com.diegonmarcos.superapp.configs.AdbCockpitOps
import com.diegonmarcos.superapp.configs.AdbOwnerHost
import com.diegonmarcos.superapp.configs.AdbShellFragment
import com.diegonmarcos.superapp.configs.DeviceControls
import com.diegonmarcos.superapp.devcontrol.AdbBridgeApi
import com.diegonmarcos.superapp.devcontrol.AdbDevRoutes
import com.diegonmarcos.superapp.shellbroker.ShellBroker
import com.diegonmarcos.superapp.system.AdbReadiness
import com.diegonmarcos.superapp.system.AdbReadinessOwner
import com.diegonmarcos.superapp.system.PrivilegedPlaneHost
import com.diegonmarcos.superapp.system.PrivilegedPlaneWorker
import com.diegonmarcos.superapp.system.WirelessDebugKeepAlive
import com.diegonmarcos.superapp.system.WirelessDebugKeeper
import com.diegonmarcos.superapp.xray.EngineProfiler

/**
 * #1682 P3 step 5: THE one door between the SuperApp's own code and the ADB OWNER side. This is the OWNER variant (src/owner, compiled
 * while 1_cicd/src/data/adb-owner.json names aa_cloud-superapp): the SuperApp holds the channel, the broker, the keeper and the plane.
 * The CLIENT variant (src/client, compiled once the owner is another app) has the same members and talks to the owner over
 * libs:shell-client only. Nothing outside this file (and the files app/build.gradle excludes in client mode) names an owner class.
 */
object AdbSide {
    /** true = this build holds the ADB channel. */
    const val OWNER = true

    /** A dev-API reply (status line, body, json?), the same shape in both variants. */
    class Reply(val status: String, val body: String, val json: Boolean)

    /** Process start (Application.onCreate): every owner hook the old App.kt installed, in the same order. [inFrame] opens the in-shell ADB page. */
    fun install(app: Application, inFrame: (Context) -> Boolean) {
        // ONE auto-arm switch: Configs > Keep Wireless debugging on (default build.json::privileged_channel.default_on = off).
        ChannelAutoArm.hostSwitch = { c -> WirelessDebugKeeper.Prefs.enabled(c) }
        // #1513 the keeper's self-grant of WRITE_SECURE_SETTINGS goes through the shell broker (allowlisted for exactly that grant).
        HostShell.grantRunner = { c, cmd -> ShellBroker.exec(c, ShellBroker.SELF, cmd) }
        HostShell.channelReady = { c -> AdbReadiness.check(c).usable }
        // #1682 P3 step 4d: the keeper lives in libs:adb-shell-core; the SuperApp hands it the declared control flags and the X-Ray profiler.
        AdbOwnerHost.declaredFlag = { id, flag -> DeviceControls.declaredFlag(id, flag) }
        AdbOwnerHost.profiler = { engine, path, block -> EngineProfiler.time(engine, path) { block() } }
        AdbOwnerHost.waker = { engine, source -> EngineProfiler.wake(engine, source) }
        AdbOwnerHost.errors = { engine, where, what -> EngineProfiler.error(engine, where, what) }
        AdbOwnerHost.plane = PrivilegedPlaneHost
        // the readiness source is client-safe (libs:adb-readiness); this process owns the channel, so it adds the owner half.
        AdbReadinessOwner.install()
        AdbReadiness.connector = { c -> AdbCockpitOps.connect(c) }
        AdbReadiness.hostOpener = inFrame
        AdbShellLink.hostOpener = inFrame
    }

    /** After the other start-up hooks: the plane re-arm on every launch and the keeper sync (verbatim from the old App.kt). */
    fun installBoot(app: Application) {
        // Privileged plane re-arm on every launch (unique, KEEP): BOOT_COMPLETED is delayed or dropped on some OEMs, and the first
        // successful connect right after the one-time pairing must not wait for a reboot. Cheap when already connected.
        // The Shizuku-style pairing service ends with the channel up; the plane (pm grant list) is this app's to arm.
        AdbPairingService.onConnected = { c -> enqueuePlane(c, androidx.work.ExistingWorkPolicy.REPLACE) }
        runCatching {
            enqueuePlane(app, androidx.work.ExistingWorkPolicy.KEEP)
            // #290: the platform clears Wireless Debugging mid-session; the keeper re-arms it and reconnects the channel while the
            // owner's keep-alive switch is on, and stands down entirely when it is off.
            WirelessDebugKeeper.sync(app, WirelessDebugKeepAlive.Trigger.PERIODIC)
        }
    }

    /** #ipc the SuperApp bridge (adb/exec) over the fleet IPC layer: terminals and Drive call it. */
    fun registerBridge(app: Application) { runCatching { AdbBridgeApi.register(app) } }

    private fun enqueuePlane(c: Context, policy: androidx.work.ExistingWorkPolicy) {
        androidx.work.WorkManager.getInstance(c).enqueueUniqueWork(
            "privileged-plane", policy, androidx.work.OneTimeWorkRequestBuilder<PrivilegedPlaneWorker>().build())
    }

    /** The self-healing worker (connect + pm grant the privileged list), re-armed now. */
    fun armPlane(ctx: Context) { runCatching { enqueuePlane(ctx, androidx.work.ExistingWorkPolicy.REPLACE) } }

    /** Keep Wireless debugging on: the owner's switch, its status rows. */
    fun keeperEnabled(ctx: Context): Boolean = WirelessDebugKeeper.Prefs.enabled(ctx)
    fun setKeeper(ctx: Context, on: Boolean) = WirelessDebugKeeper.Prefs.setEnabled(ctx, on)
    fun keeperRows(ctx: Context): List<Pair<String, String>> = WirelessDebugKeeper.Status.rows(ctx)

    /** Configs > Network > ADB Shell and its four tabs ([pageId] = adb-shell | adb-status | adb-term | adb-perms | adb-all). */
    fun page(pageId: String): Fragment =
        if (pageId == "adb-shell") AdbShellFragment.newInstance() else AdbShellFragment.newInstance(AdbCockpitModel.Tab.of(pageId))

    /** The loopback dev API's ADB routes (pairing, connect, diagnostics, exec ...). */
    val devRoutes: Set<String> get() = AdbDevRoutes.ROUTES
    fun devRoute(ctx: Context, op: String, query: Map<String, String>): Reply? =
        AdbDevRoutes.handle(ctx, op, query)?.let { Reply(it.status, it.body, it.json) }
    fun devAutoConnectAtStart(app: Context) = AdbDevRoutes.autoConnectAtStart(app)
}
