package com.diegonmarcos.superapp.wifi

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * #1666 The Wi-Fi page's scope: [WifiController.attach] (the scan loop, the scan-result listener and the link sampler, every one a
 * location access) runs only while the page is composed AND its host is started. Going to the background with the page open
 * detaches (ON_STOP) and coming back attaches again (ON_START); leaving the page detaches for good.
 */
object WifiLifecycle {

    /** The pure rule, tested on the JVM: attached exactly while on screen and started. */
    class Scope(private val onAttach: () -> Unit, private val onDetach: () -> Unit) {
        var attached = false
            private set
        fun started() { if (!attached) { attached = true; onAttach() } }
        fun stopped() { if (attached) { attached = false; onDetach() } }
        fun left() = stopped()
    }

    @Composable
    fun Attach(controller: WifiController) {
        val owner = LocalLifecycleOwner.current
        DisposableEffect(controller, owner) {
            val scope = Scope({ controller.attach() }, { controller.detach() })
            val obs = LifecycleEventObserver { _, e ->
                when (e) {
                    Lifecycle.Event.ON_START -> scope.started()
                    Lifecycle.Event.ON_STOP -> scope.stopped()
                    else -> Unit
                }
            }
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) scope.started()
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs); scope.left() }
        }
    }
}
