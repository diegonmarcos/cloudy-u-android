package com.diegonmarcos.superapp.appstore

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.updater.UpdateProgress

/**
 * The page's progress WATCH (no longer a panel: #1524 draws every download and install in the one StoreActivityPanel). It listens
 * for the pipeline's events and bumps [tick], which the page's [StoreTally] area re-reads on, so a download landing in the cache
 * shows there live.
 *
 * Attached with [UpdateProgress.addObserver], never setListener: that slot is the shell overlay's, and taking it would turn the
 * overlay off for as long as this page is open. It draws nothing and never touches a job.
 */
class StoreProgressPanel {
    /** Bumped on every progress or job-board event. */
    var tick by mutableIntStateOf(0)
        private set

    private val main = Handler(Looper.getMainLooper())

    private val progressObserver: (UpdateProgress.State) -> Unit = { main.post { tick++ } }
    private val boardObserver: () -> Unit = { main.post { tick++ } }

    /** Listen from now on; [detach] stops. Idempotent: re-attaching never stacks observers. */
    fun attach() {
        detach()
        UpdateProgress.addObserver(progressObserver)
        StoreJobs.install()
        StoreJobs.addObserver(boardObserver)
    }

    fun detach() {
        UpdateProgress.removeObserver(progressObserver)
        StoreJobs.removeObserver(boardObserver)
    }
}
