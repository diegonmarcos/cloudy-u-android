package com.diegonmarcos.capitalfinterm.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.capitalfinterm.AssetData
import com.diegonmarcos.capitalfinterm.FreeStack
import com.diegonmarcos.capitalfinterm.FreeState
import com.diegonmarcos.capitalfinterm.R
import com.diegonmarcos.capitalfinterm.SourceKeys
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatePill
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1416 About > Data sources: every source of universe.json `free_stack` with its coverage, licence / terms, rate limit, key need and
 * status (ready, needs key with the reason, or off behind its feature flag). A key is never shown: only whether one is saved.
 */
@Composable
fun DataSourcesPanel() {
    val ctx = LocalContext.current
    val decl = remember { runCatching { FreeStack.parse(AssetData.read(ctx, AssetData.UNIVERSE)) }.getOrNull() }
    val keys = remember { SourceKeys(EncryptedSecretBox(ctx)) }
    val p = LocalKitPalette.current
    GroupTitle(stringResource(R.string.data_sources_title))
    decl?.sources?.forEach { s ->
        val st = FreeStack.state(s, hasKey = s.key?.let { keys.has(it.slot) } ?: true)
        val pill = when (st) {
            FreeState.READY -> KitState.OK
            else -> KitState.WARN
        }
        KitCard(Modifier.testTag(Tags.dataSource(s.id))) {
            KitSettingsRow(title = s.name, subtitle = s.coverage, trailing = { KitStatePill(if (st == FreeState.READY) "ready" else if (st == FreeState.NEEDS_KEY) "needs key" else "off", pill) })
            Text(stringResource(R.string.data_sources_licence, s.licence), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.data_sources_rate, FreeStack.rateText(s), FreeStack.keyText(s)), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
            Text(stringResource(R.string.data_sources_status, FreeStack.statusText(s, st)), color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}
