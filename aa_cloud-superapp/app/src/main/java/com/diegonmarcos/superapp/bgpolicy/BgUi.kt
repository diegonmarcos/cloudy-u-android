package com.diegonmarcos.superapp.bgpolicy

import android.app.Activity
import android.widget.Toast
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import kotlinx.coroutines.runBlocking

/**
 * #1506 The user-action side shared by Battery Hunger and Super Saving: get the shell (asking to arm it when it is down),
 * run an engine action on a worker thread, tell the user the verdict. Nothing here runs unless a button was tapped.
 */
internal object BgUi {

    /** [proceed] = go on (with the [shell], or without it when the user chose to); false = nothing should run now. */
    class Acquired(val shell: BgShell?, val proceed: Boolean)

    /** Blocking; call off the main thread. Asks to arm the shell when it is down: the ADB Shell page, the wireless-debugging switch, or (when [withoutAdb]) go on without. */
    fun acquire(activity: Activity, dialogs: BgDialogs, withoutAdb: Boolean = false): Acquired {
        val ctx = activity.applicationContext
        BgHost.shell(ctx)?.let { return Acquired(it, true) }
        val r = runBlocking {
            BgGate.run(
                ready = { BgHost.shell(ctx) != null },
                openArmFlow = { activity.runOnUiThread { AdbShellLink.open(activity) } },
                openWirelessDebugging = { activity.runOnUiThread { AdbShellLink.open(activity, ControlStatus.Action.OPEN_SETTINGS) } },
                ask = { dialogs.askGate(it, withoutAdb) },
            )
        }
        return when (r) {
            BgGate.Result.READY -> Acquired(BgHost.shell(ctx), true)
            BgGate.Result.WITHOUT_ADB -> Acquired(null, true)
            else -> Acquired(null, false)
        }
    }

    /** The shell, or null when the user was asked and went to arm it (or gave up). */
    fun ensureShell(activity: Activity, dialogs: BgDialogs): BgShell? = acquire(activity, dialogs).shell

    fun toast(activity: Activity, text: String) = activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }

    /** One line for a batch: how many took, failed, were skipped. */
    fun summary(what: String, r: List<BgOutcome>): String {
        val ok = r.count { it.ok }; val skipped = r.count { it.skipped }; val bad = r.size - ok - skipped
        return "$what: $ok done" + (if (bad > 0) ", $bad did not take (see the audit log)" else "") + (if (skipped > 0) ", $skipped skipped" else "")
    }
}
