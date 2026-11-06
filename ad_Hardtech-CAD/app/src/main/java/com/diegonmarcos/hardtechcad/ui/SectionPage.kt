package com.diegonmarcos.hardtechcad.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.BuildConfig
import com.diegonmarcos.hardtechcad.Decl
import com.diegonmarcos.hardtechcad.Entry
import com.diegonmarcos.hardtechcad.Pages
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** A section's page: its declared entries (ui.stacks[section]) drawn by kind. Nothing here is typed text: the words are build.json's. */
@Composable
fun SectionPage(sectionId: String) {
    val ctx = LocalContext.current
    val entries = remember(sectionId) { Pages.entries(Decl.stacks, sectionId) }
    var openLibrary by rememberSaveable(sectionId) { mutableStateOf<String?>(null) }
    openLibrary?.let { id -> LibraryPage(id) { openLibrary = null }; return }
    // #1542 a `models` entry: the models table, and the model page a tap opens (it takes the whole screen, like a library page)
    val catalog = if (entries.any { it.kind == Pages.KIND_MODELS }) rememberModelCatalog() else null
    var openModel by rememberSaveable(sectionId) { mutableStateOf<String?>(null) }
    openModel?.let { id -> catalog?.models?.firstOrNull { it.id == id } }?.let { m -> ModelPage(m, catalog!!.categories) { openModel = null }; return }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(Tags.PAGE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { e ->
            when (e.kind) {
                Pages.KIND_SECTION_TITLE -> GroupTitle(e.title)
                Pages.KIND_HOLDINGS -> HoldingsPanel()
                Pages.KIND_ABOUT -> AboutPanel()
                Pages.KIND_MODELS -> ModelsPage(catalog) { openModel = it.id }
                Pages.KIND_LIBRARY -> LibraryRow(e) { openLibrary = e.library }
                else -> EntryRow(e, Pages.resolve(e, Decl.targets)) { url -> openUrl(ctx, url) }
            }
        }
    }
}

/** One `entry`: title and one-line description, opening its resolved target; with none it is greyed and says why. */
@Composable
private fun EntryRow(e: Entry, url: String?, onOpen: (String) -> Unit) {
    if (url == null) {
        KitCard(Modifier.alpha(0.5f).testTag(Tags.entry(e.title))) {
            KitSettingsRow(
                title = e.title,
                subtitle = e.subtitle + "\n" + e.note.ifBlank { stringResource(R.string.entry_planned) },
            )
        }
    } else {
        KitCard(Modifier.testTag(Tags.entry(e.title))) {
            KitSettingsRow(
                title = e.title,
                subtitle = e.subtitle,
                onClick = { onOpen(url) },
                trailing = { Text(stringResource(R.string.entry_open), color = LocalKitPalette.current.accent) },
            )
        }
    }
}

/** #1404 a `library` entry: opens its model-library sub-page. */
@Composable
private fun LibraryRow(e: Entry, onOpen: () -> Unit) {
    KitCard(Modifier.testTag(Tags.entry(e.title))) {
        KitSettingsRow(
            title = e.title,
            subtitle = e.subtitle,
            onClick = onOpen,
            trailing = { Text(stringResource(R.string.entry_open), color = LocalKitPalette.current.accent) },
        )
    }
}

@Composable
private fun AboutPanel() {
    KitCard {
        KitSectionHeader(stringResource(R.string.app_name), stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
