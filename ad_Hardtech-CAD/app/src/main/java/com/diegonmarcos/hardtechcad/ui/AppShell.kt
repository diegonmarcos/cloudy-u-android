package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.hardtechcad.Decl
import com.diegonmarcos.hardtechcad.OpenedFile
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object Tags {
    const val SHELL = "app_shell"
    const val PAGE = "app_page"
    const val TICKER = "holdings_ticker"
    const val QUANTITY = "holdings_quantity"
    const val COST = "holdings_cost"
    fun entry(title: String): String = "entry:$title"
}

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account, the gear opens the About section), the shared
 * bottom nav, the shared page gradient and the shared scroll collapse, all from libs (#1154, #1157). Which sections exist is
 * build.json::ui, read through [Decl.nav]; what a section's page shows is ui.stacks, drawn by [SectionPage].
 */
@Composable
fun AppShell() {
    val nav = Decl.nav
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_ABOUT) }
    val sec = nav.section(section)
    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, null) }.orEmpty(),
            onNavigate = { e -> section = e.sectionId },
            onAccount = rememberAccountLauncher(),
            onConfigs = { section = Decl.SECTION_ABOUT },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = nav.islandEntries(),
                selectedId = section,
                onSelect = { section = it.id },
                modifier = Modifier.testTag(Tags.SHELL),
            ) {
                // #1695 a STEP / STP opened from another app is drawn over the section until it is closed
                val opened = OpenedFile.pending.value
                if (opened != null) OpenedModel(opened) { OpenedFile.pending.value = null } else SectionPage(section)
            }
        }
    }
}
