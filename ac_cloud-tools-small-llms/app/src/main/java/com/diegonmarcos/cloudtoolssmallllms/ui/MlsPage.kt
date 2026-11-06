package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.MlGroup
import com.diegonmarcos.cloudtoolssmallllms.core.MlLib
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreDot
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine

/**
 * MLs ([page] = one of the ui.ml_pages): the Cloud Store's ML libs of that group as its dense rows (ui-kit KitStoreRow, its
 * type ramp), under the group's blurb and one heading per application. Every name and line is the Store's own declaration.
 */
@Composable
fun MlsPage(groups: List<MlGroup>, page: String, note: String? = null) {
    val group = groups.firstOrNull { it.page == page }
    LazyColumn(Modifier.fillMaxSize().testTag(Tags.MLS)) {
        if (group == null) {
            if (note != null) item(key = "note") {
                Text(stringResource(R.string.mls_unavailable, note), fontSize = KitStoreRow.meta, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV))
            }
            return@LazyColumn
        }
        item(key = "blurb") {
            Text(group.blurb, fontSize = KitStoreRow.meta, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.padV))
        }
        group.libs.groupBy { it.application.orEmpty() }.forEach { (application, libs) ->
            item(key = "h_$application") {
                KitStoreCaption(application.replaceFirstChar { it.uppercase() }, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight).testTag(Tags.mlHeader(application)))
            }
            items(libs, key = { it.id }) { MlRow(it) }
        }
        item(key = "count") {
            KitStoreCaption(stringResource(R.string.mls_count, group.libs.size), color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(KitStoreRow.padH))
        }
    }
}

/** One lib as the Store's row: a state dot, the name over its description. */
@Composable
private fun MlRow(lib: MlLib) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.testTag(Tags.mlRow(lib.id))) {
        KitStoreRowLine {
            KitStoreDot(stringResource(R.string.mls_dot), scheme.onSurfaceVariant)
            Column(Modifier.weight(1f)) {
                KitStoreName(lib.label, color = scheme.onSurface)
                lib.description?.let { KitStoreCaption(it, color = scheme.onSurfaceVariant) }
            }
        }
        Box(Modifier.fillMaxWidth().height(ToolsMetrics.hairline).background(KitStoreRow.RULE))
    }
}
