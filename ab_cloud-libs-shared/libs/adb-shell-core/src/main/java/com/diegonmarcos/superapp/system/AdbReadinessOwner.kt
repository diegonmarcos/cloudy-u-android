package com.diegonmarcos.superapp.system

import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.shellbroker.ShellBroker

/**
 * #1682 P3 step 5: the OWNER half of [AdbReadiness]. The readiness object itself is client-safe (libs:adb-readiness, over
 * ShellClient.status); the process that owns the ADB channel adds the two things only it can do: take the broker's in-process
 * change callback so a flip is pushed to [AdbReadiness.state] / its listeners, and answer [AdbReadiness.usableNow] from the live
 * channel lookup (a pure read). The owner's Application calls [install] once at process start.
 */
object AdbReadinessOwner {
    @Synchronized fun install() {
        AdbReadiness.ownerInstall = { ShellBroker.onStatusChange = { st -> AdbReadiness.publish(st) } }
        AdbReadiness.ownerUsableNow = { ctx -> ShellChannels.active(ctx) != null }
        AdbReadiness.install()
    }
}
