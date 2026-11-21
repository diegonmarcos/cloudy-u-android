package com.diegonmarcos.superapp.browser

import java.io.File

/**
 * #1841 did the last run end badly? A marker file exists while the browser is on screen (written at start, removed when the activity stops
 * or is destroyed). A start that finds the marker still there means the previous run died while showing (a crash, a renderer loop, a force
 * stop): the host then does NOT reopen the last page (it may be the page that kills the renderer, which would black out every start), opens
 * the tab grid on a safe state and offers "Restore previous tabs". A file, not a preference: no new store to migrate.
 */
object BrowserSessionHealth {
    private fun marker(dir: File) = File(dir, "browser-session.running")

    /** Call once per process at start: true when the previous run did not end cleanly. Arms the marker for this run. */
    fun startAndCheck(dir: File): Boolean = runCatching {
        dir.mkdirs()
        val m = marker(dir)
        val unclean = m.exists()
        m.writeText(System.currentTimeMillis().toString())
        unclean
    }.getOrDefault(false)

    /** The browser left the screen normally (stop / destroy). */
    fun markClean(dir: File) { runCatching { marker(dir).delete() } }

    /** The browser is on screen again (resume after a stop): armed again. */
    fun markRunning(dir: File) { runCatching { marker(dir).writeText(System.currentTimeMillis().toString()) } }
}
