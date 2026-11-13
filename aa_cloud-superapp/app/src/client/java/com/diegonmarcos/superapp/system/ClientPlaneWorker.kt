package com.diegonmarcos.superapp.system

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.diegonmarcos.superapp.configs.BrokerGrantChannel

/**
 * #1682 P3 (CLIENT build): the privileged plane the SuperApp used to run from its own boot worker, now sent to the ADB owner as
 * allowlisted ShellClient.exec calls: the declared pm-grant list, the device-tuning pass and the fleet deviceidle whitelist
 * ([PrivilegedPlaneRun], the same bodies the owner build runs). No polling: it is enqueued at app start and when the owner's readiness
 * turns usable (AdbSide.install); a down owner is "no shell channel" (tuning records PENDING) and the next trigger retries.
 */
class ClientPlaneWorker(ctx: Context, p: WorkerParameters) : Worker(ctx, p) {
    override fun doWork(): Result {
        val ch = BrokerGrantChannel(applicationContext)
        if (!runCatching { ch.up() }.getOrDefault(false)) return Result.success()
        val exec: (String) -> String? = { cmd -> runCatching { ch.exec(cmd) }.getOrNull() }
        runCatching { PrivilegedPlaneRun.selfGrant(applicationContext, exec) }
        runCatching { PrivilegedPlaneRun.tune(applicationContext, exec) }
        return Result.success()
    }
}
