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
        wireKeepAlive()
        AdbReadiness.ownerInstall = { ShellBroker.onStatusChange = { st -> AdbReadiness.publish(st) } }
        AdbReadiness.ownerUsableNow = { ctx -> ShellChannels.active(ctx) != null }
        // the owner's keep-alive switch for fleet clients (the broker provider's keeper_get / keeper_set; allowlist entry flag `keeper`)
        ShellBroker.keeperHook = { ctx, set ->
            if (set != null) WirelessDebugKeeper.Prefs.setEnabled(ctx, set)
            WirelessDebugKeeper.Prefs.enabled(ctx)
        }
        AdbReadiness.install()
    }

    /**
     * #1833 ONE ADB keep-alive switch in the owner process, whichever app is the owner: the lib's implicit-trigger gate
     * (ChannelAutoArm: app start, boot, a command finding the channel down, Wireless debugging coming on, the broker's re-arm) reads
     * and writes [WirelessDebugKeeper.Prefs], the switch the broker's keeper_get / keeper_set serve to the fleet clients (SuperApp
     * Configs > Keep Wireless debugging on). The SuperApp did this in its own owner facade; the webserver owner did not, so its gate
     * followed an undeclared default (ON) while every client showed the keeper OFF, and the pairing notification kept coming.
     */
    fun wireKeepAlive() {
        com.diegonmarcos.superapp.adbdebug.ChannelAutoArm.hostSwitch = { c -> WirelessDebugKeeper.Prefs.enabled(c) }
        com.diegonmarcos.superapp.adbdebug.ChannelAutoArm.hostSet = { c, on -> WirelessDebugKeeper.Prefs.setEnabled(c, on) }
    }
}
