package com.diegonmarcos.superapp.bgpolicy

import android.app.Activity
import android.app.AlertDialog
import android.widget.Toast
import com.diegonmarcos.superapp.adbdebug.AdbShellLink
import com.diegonmarcos.superapp.adbdebug.ControlStatus
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * #1506 The user-action side shared by Battery Hunger and Super Saving: get the shell (asking to arm it when it is down),
 * run an engine action on a worker thread, tell the user the verdict. Nothing here runs unless a button was tapped.
 */
internal object BgUi {

    /** Blocking; call off the main thread. The shell, or null: the user was asked and sent to the ADB Shell page (or gave up). */
    fun ensureShell(activity: Activity): BgShell? {
        val ctx = activity.applicationContext
        BgHost.shell(ctx)?.let { return it }
        val r = runBlocking {
            BgGate.run(
                ready = { BgHost.shell(ctx) != null },
                openArmFlow = { activity.runOnUiThread { AdbShellLink.open(activity) } },
                openWirelessDebugging = { activity.runOnUiThread { AdbShellLink.open(activity, ControlStatus.Action.OPEN_SETTINGS) } },
                ask = { ask(activity, it) },
            )
        }
        return if (r == BgGate.Result.READY) BgHost.shell(ctx) else null
    }

    private suspend fun ask(activity: Activity, message: String): BgGate.Choice =
        suspendCancellableCoroutine { cont ->
            activity.runOnUiThread {
                if (activity.isFinishing) { if (cont.isActive) cont.resume(BgGate.Choice.CANCEL); return@runOnUiThread }
                fun answer(c: BgGate.Choice) { if (cont.isActive) cont.resume(c) }
                val d = AlertDialog.Builder(activity)
                    .setTitle("Background policy needs ADB")
                    .setMessage(message)
                    .setPositiveButton("Re-arm (ADB Shell page)") { _, _ -> answer(BgGate.Choice.REARM) }
                    .setNeutralButton("Wireless debugging") { _, _ -> answer(BgGate.Choice.OPEN_WIRELESS_DEBUGGING) }
                    .setNegativeButton("Cancel") { _, _ -> answer(BgGate.Choice.CANCEL) }
                    .setOnCancelListener { answer(BgGate.Choice.CANCEL) }
                    .show()
                cont.invokeOnCancellation { runCatching { d.dismiss() } }
            }
        }

    fun toast(activity: Activity, text: String) = activity.runOnUiThread { Toast.makeText(activity, text, Toast.LENGTH_LONG).show() }

    /** One line for a batch: how many took, failed, were skipped. */
    fun summary(what: String, r: List<BgOutcome>): String {
        val ok = r.count { it.ok }; val skipped = r.count { it.skipped }; val bad = r.size - ok - skipped
        return "$what: $ok done" + (if (bad > 0) ", $bad did not take (see the audit log)" else "") + (if (skipped > 0) ", $skipped skipped" else "")
    }
}
