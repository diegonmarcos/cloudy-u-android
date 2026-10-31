package com.diegonmarcos.superapp.launcher

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.boards.ResRow
import com.diegonmarcos.superapp.boards.ResourcesProvider
import com.diegonmarcos.superapp.uikit.KitListRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

internal object ResourcesTags {
    fun section(id: String) = "resources:$id"
    fun row(id: String, label: String) = "resources:$id:$label"
}

/**
 * Boards > Resources: one card per declared section (build.json boards.stack_resources[].resource), every row a live platform value.
 * The read repeats every [REFRESH_MS] for as long as the card is on screen (the effect leaves with the composition).
 */
@Composable
internal fun ResourcesBody(resource: String) {
    val ctx = LocalContext.current
    val rows by produceState(emptyList<ResRow>(), resource) {
        while (true) {
            value = withContext(Dispatchers.IO) { ResourcesProvider.read(ctx, resource) }
            delay(REFRESH_MS)
        }
    }
    Column(Modifier.fillMaxWidth().testTag(ResourcesTags.section(resource))) {
        if (rows.isEmpty()) AggCaption("Reading…")
        for (r in rows) KitListRow(r.label, secondary = r.value, tag = ResourcesTags.row(resource, r.label))
        if (resource == "battery") BatteryUsageBody()
    }
}

private const val REFRESH_MS = 2_000L
