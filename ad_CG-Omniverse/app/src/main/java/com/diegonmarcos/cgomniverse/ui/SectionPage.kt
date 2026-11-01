package com.diegonmarcos.cgomniverse.ui

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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cgomniverse.BuildConfig
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cgomniverse.Entry
import com.diegonmarcos.cgomniverse.Pages
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * A section's page: its declared entries (ui.stacks[section]) drawn by kind. A page kind (avatar, galaxy, store) owns the whole page;
 * the other kinds stack in a scrolling column. Nothing here is typed text: the words are build.json's and the data files'.
 */
@Composable
fun SectionPage(sectionId: String, galaxy: GalaxyTabs) {
    val ctx = LocalContext.current
    val entries = remember(sectionId) { Pages.entries(Decl.stacks, sectionId) }
    when (entries.firstOrNull()?.kind) {
        Pages.KIND_AVATAR -> return AvatarPage()
        Pages.KIND_GALAXY -> return GalaxyPage(galaxy.state, galaxy.select, galaxy.close, galaxy.add)
        Pages.KIND_STORE -> return StorePage(galaxy.open)
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp).testTag(Tags.PAGE),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        entries.forEach { e ->
            when (e.kind) {
                Pages.KIND_SECTION_TITLE -> GroupTitle(e.title)
                Pages.KIND_ABOUT -> AboutPanel()
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

@Composable
private fun AboutPanel() {
    KitCard {
        KitSectionHeader(stringResource(R.string.app_name), stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
    }
}

private fun openUrl(ctx: Context, url: String) {
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}
