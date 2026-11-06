package com.diegonmarcos.superapp.perms

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * #1406 an inline Grant for a feature card (Calc ▸ Measure ▸ WiFi): asks the system dialog for the runtime grants of
 * [entries] (catalogue rows, already filtered to this SDK and to what is missing by [PermsNeeds.missing]) through the one
 * place that asks, recording the ask like the Perms page does. Special-access rows have no dialog: they open their page.
 */
@Composable
fun rememberPermsAsk(onResult: () -> Unit = {}): (List<PermEntry>) -> Unit {
    val ctx = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> PermsRequest.handleResult(ctx, r); onResult() }
    return { entries -> PermsRequest.requestVia(ctx, entries) { ask.launch(it) } }
}
