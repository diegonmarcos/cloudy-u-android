package com.diegonmarcos.superapp.bgpolicy

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreQuick
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * #1506 The Background policy's questions as Compose state, so a worker thread can ask and wait: arm the shell, confirm a bulk
 * action, pick a component. [BgDialogHost] draws whatever is pending; every answer is a tap. No View dialogs.
 */
internal class BgDialogs {
    class Ask<T>(val title: String, val message: String, val answer: (T) -> Unit)
    class Pick(val title: String, val items: List<String>, val onPick: (Int) -> Unit)

    var gate by mutableStateOf<Ask<BgGate.Choice>?>(null)
    var confirm by mutableStateOf<Ask<Boolean>?>(null)
    var pick by mutableStateOf<Pick?>(null)
    var offerWithout by mutableStateOf(false)

    /** Suspends the calling (worker) thread's coroutine until the user answers the arm prompt. */
    suspend fun askGate(message: String, withoutAdb: Boolean = false, title: String = GATE_TITLE): BgGate.Choice = suspendCancellableCoroutine { c ->
        offerWithout = withoutAdb
        gate = Ask(title, message) { gate = null; if (c.isActive) c.resume(it) }
        c.invokeOnCancellation { gate = null }
    }

    companion object { const val GATE_TITLE = "Background policy needs ADB" }

    fun askConfirm(title: String, message: String, yes: () -> Unit) {
        confirm = Ask<Boolean>(title, message) { confirm = null; if (it) yes() }
    }
}

@Composable
internal fun BgDialogHost(d: BgDialogs) {
    val p = LocalKitPalette.current
    d.gate?.let { a ->
        AlertDialog(
            onDismissRequest = { a.answer(BgGate.Choice.CANCEL) },
            title = { Text(a.title) }, text = { Text(a.message) },
            confirmButton = { KitStoreQuick("Re-arm (ADB Shell page)", KitStoreRow.BLUE_FILL, Modifier.testTag("bg-gate:rearm")) { a.answer(BgGate.Choice.REARM) } },
            dismissButton = {
                Column {
                    KitStoreQuick("Wireless debugging", KitStoreRow.AMBER_FILL, Modifier.testTag("bg-gate:wireless")) { a.answer(BgGate.Choice.OPEN_WIRELESS_DEBUGGING) }
                    Spacer(Modifier.height(KitStoreRow.gap))
                    if (d.offerWithout) {
                        KitStoreQuick("Continue without ADB", KitStoreRow.AMBER_FILL, Modifier.testTag("bg-gate:without")) { a.answer(BgGate.Choice.WITHOUT_ADB) }
                        Spacer(Modifier.height(KitStoreRow.gap))
                    }
                    KitStoreQuick("Cancel", KitStoreRow.RULE, Modifier.testTag("bg-gate:cancel")) { a.answer(BgGate.Choice.CANCEL) }
                }
            },
        )
    }
    d.confirm?.let { a ->
        AlertDialog(
            onDismissRequest = { a.answer(false) },
            title = { Text(a.title) }, text = { Text(a.message) },
            confirmButton = { KitStoreQuick("Do it", KitStoreRow.BLUE_FILL, Modifier.testTag("bg-confirm:yes")) { a.answer(true) } },
            dismissButton = { KitStoreQuick("Cancel", KitStoreRow.RULE, Modifier.testTag("bg-confirm:no")) { a.answer(false) } },
        )
    }
    d.pick?.let { k ->
        AlertDialog(
            onDismissRequest = { d.pick = null },
            title = { Text(k.title) },
            text = {
                Column {
                    k.items.forEachIndexed { i, label ->
                        KitStoreRowLine(Modifier.testTag("bg-pick:$i"), onClick = { d.pick = null; k.onPick(i) }) { KitStoreCaption(label, color = p.textPrimary) }
                    }
                }
            },
            confirmButton = { KitStoreQuick("Close", KitStoreRow.RULE, Modifier.testTag("bg-pick:close")) { d.pick = null } },
        )
    }
}
