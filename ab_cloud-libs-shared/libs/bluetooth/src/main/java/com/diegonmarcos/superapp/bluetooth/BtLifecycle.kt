package com.diegonmarcos.superapp.bluetooth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * #1666 The Bluetooth page's scope. The controller is a process singleton ([BluetoothHub]), so a scan or a classic inquiry the
 * user started used to outlive the page. Now leaving the page or the app going to the background (ON_STOP) stops both; the user
 * starts them again with Scan (the Locate card resumes its own scan when it is shown again).
 */
object BtLifecycle {

    /** The pure rule, tested on the JVM: what [release] stops. */
    fun release(c: BleScanOwner) { c.stopScan(); c.stopDiscovery() }

    /** The two calls [release] makes, so the rule is testable without an adapter. */
    interface BleScanOwner { fun stopScan(); fun stopDiscovery() }

    @Composable
    fun Scope(controller: BleController) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(controller, owner) {
            val o = object : BleScanOwner {
                override fun stopScan() { if (controller.state.value.scanning) controller.stopScan() }
                override fun stopDiscovery() { if (controller.state.value.discovering) controller.stopDiscovery() }
            }
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) release(o) }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs); release(o) }
        }
    }
}
