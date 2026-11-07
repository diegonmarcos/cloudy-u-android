package org.fossify.phone.helpers

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * #1547 the Dialer's declarations (call_providers, keypad, sim-fields, voip_providers, spam_patterns) are NOT baked into the APK: Cloud Store
 * serves them as this app's own datasets (repo data/fleet/app/<package>/<dataset>.json) through libs:fleet-data-client. Reads never throw;
 * null means "not served yet" (provider absent, first run before the first sync) and is never cached, so the next call retries.
 */
object FleetDataset {
    fun <T : Any> get(ctx: Context, dataset: String, parse: (String) -> T): T? =
        runCatching { FleetData.get(ctx, dataset, own = true) { parse(it) } }.getOrNull()
}

/** The dataset as Compose state: null until served (retried every few seconds off the main thread), then the parsed value. */
@Composable
fun <T : Any> rememberFleetDataset(dataset: String, parse: (String) -> T): T? {
    val ctx = LocalContext.current
    var value by remember(dataset) { mutableStateOf<T?>(null) }
    LaunchedEffect(dataset) {
        while (value == null) {
            value = withContext(Dispatchers.IO) { FleetDataset.get(ctx, dataset, parse) }
            if (value == null) delay(3_000)
        }
    }
    return value
}
