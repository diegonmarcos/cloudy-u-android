package com.diegonmarcos.superapp.launcher.pins

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * #1840 THE DECLARED IPC ADD-TO-SUPERAPP-HOME. Cloud Browser falls back to this when the launcher in charge refuses pinned shortcuts (some
 * launchers do; the SuperApp's own PinShortcutActivity is the first path). Exported but guarded by the fleet's signature permission
 * (CONSTELLATION_DATA, in the manifest): only an app signed with the fleet key can send it. Action [ACTION], extras: [EXTRA_ID], [EXTRA_LABEL],
 * [EXTRA_URL] (http/https only), [EXTRA_ICON] (PNG bytes, at most [MAX_ICON] long), [EXTRA_PKG] (the app that opens it: the sender's package).
 * An ORDERED broadcast: the result code is RESULT_OK once the pin is on the home, RESULT_CANCELED with a reason in the result data otherwise,
 * so the sender can say "added" or "failed" instead of guessing.
 */
class AddHomeShortcutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        val url = intent.getStringExtra(EXTRA_URL)
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val reason = when {
            !PinnedShortcutStore.acceptableUrl(url) -> "not a web address"
            pkg.isBlank() -> "no app to open it"
            else -> null
        }
        if (reason != null) { resultCode = Activity.RESULT_CANCELED; resultData = reason; Log.w(TAG, "add refused: $reason"); return }
        val label = PinnedShortcutStore.cleanLabel(intent.getStringExtra(EXTRA_LABEL), hostOf(url!!))
        val id = intent.getStringExtra(EXTRA_ID).orEmpty().ifBlank { "url:" + url.hashCode().toUInt().toString(16) }
        val key = PinnedShortcutStore.keyOf(pkg, id)
        val png = intent.getByteArrayExtra(EXTRA_ICON)?.takeIf { it.size in 1..MAX_ICON }
        val iconPath = if (png != null) PinnedShortcutStore.saveIcon(context, key, png) else ""
        val ok = PinnedShortcutStore.add(context, PinnedShortcutStore.Pin(id, pkg, label, PinnedShortcutStore.Kind.IPC, url, iconPath, System.currentTimeMillis()))
        resultCode = if (ok) Activity.RESULT_OK else Activity.RESULT_CANCELED
        resultData = if (ok) "added" else "could not write the home"
        Log.i(TAG, "add ${if (ok) "done" else "failed"}: pkg=$pkg")
    }

    private fun hostOf(url: String) = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')

    companion object {
        private const val TAG = "SuperApp/Pins"
        const val ACTION = "com.diegonmarcos.superapp.action.ADD_HOME_SHORTCUT"
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_URL = "url"
        const val EXTRA_PKG = "pkg"
        const val EXTRA_ICON = "icon"
        const val MAX_ICON = 96 * 1024
    }
}
