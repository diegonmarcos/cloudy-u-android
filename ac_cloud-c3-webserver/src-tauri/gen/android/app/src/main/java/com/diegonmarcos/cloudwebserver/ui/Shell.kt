package com.diegonmarcos.cloudwebserver.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import com.diegonmarcos.cloudwebserver.Core
import com.diegonmarcos.cloudwebserver.Decl
import com.diegonmarcos.cloudwebserver.LaunchTarget
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.themebg.themeBackground

object Tags {
    const val SHELL = "c3_shell"
    const val SECOND_ROW = "c3_second_row"
    const val CORE_STATE = "c3_core_state"
}

/**
 * #1682 P1 the native shell: the shared first row (breadcrumb, Profile, the Configs gear), the second row (the section's pages:
 * Pages = All | File Explorer | Pages, API = Topology + the declared sub-pages, Configs = General | own pages | the fleet tail) and the
 * bottom nav Pages, API, Home, SSHd, ADB Shell, all from libs:ui-nav-bars over build.json::ui ([Decl.nav]). Every page body reads
 * the Rust core's loopback admin API; nothing here lists a route the web shell does not read.
 */
@Composable
fun WebserverShell() {
    val nav = Decl.nav
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_HOME) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    // #1630 Configs ends with the fleet-default tail: {General + own tabs} | Agents, Perms, Logcat, X-Ray App, About.
    val sec = nav.section(section)?.let { s -> if (s.id == Decl.SECTION_CONFIGS) s.copy(pages = FleetConfigsExtensions.withTail(s.pages)) else s }
    val pageId = page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val entries = nav.islandEntries()
    fun open(sectionId: String, pageId: String? = null) { section = sectionId; page = pageId }
    // #1682 P4 every fleet link that opens "the ADB Shell page" lands here (ShellArmActivity -> MainActivity extra): the ADB Shell section
    val wanted = LaunchTarget.section
    androidx.compose.runtime.LaunchedEffect(wanted) { if (wanted != null && nav.section(wanted) != null) { open(wanted); LaunchTarget.section = null } }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: "",
            hierarchy = sec?.let { titleHierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e -> open(e.sectionId, if (e.depth == 0) null else e.id) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { open(Decl.SECTION_CONFIGS) },
            dark = true,
        ) {
            NavBarBottomHost(entries = entries, selectedId = section, onSelect = { open(it.id) }, modifier = Modifier.testTag(Tags.SHELL)) {
                val body: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        when (val st = Core.state) {
                            is Core.CoreState.Failed -> Column(Modifier.fillMaxSize().testTag(Tags.CORE_STATE)) {
                                ErrorNote(st.reason)
                                Note("The pages below read the server on 127.0.0.1:${Core.port}; they show what answers.")
                                Box(Modifier.weight(1f)) { SectionBody(sec?.id, pageId, ::open) }
                            }
                            else -> SectionBody(sec?.id, pageId, ::open)
                        }
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    if (sec != null && sec.pages.isNotEmpty()) {
                        NavSectionLayout(sec, pageId, onSelect = { page = it.id }, modifier = Modifier.fillMaxSize().testTag(Tags.SECOND_ROW), content = body)
                    } else body()
                    // The link chooser and the embedded view (build.json::links), over every page.
                    LinkHost()
                }
            }
        }
    }
}

/** The body of one section / page. An id nobody declares falls back to Home, never to a blank screen. */
@Composable
private fun SectionBody(section: String?, page: String?, open: (String, String?) -> Unit) {
    when (section) {
        Decl.SECTION_PAGES -> PagesSection(page ?: Decl.PAGE_ALL, open)
        Decl.SECTION_API -> ApiSection(page ?: Decl.PAGE_TOPOLOGY)
        Decl.SECTION_SSHD -> SshdPage()
        Decl.SECTION_ADB_SHELL -> WebAdbShellPage(open, page)
        Decl.SECTION_CONFIGS -> when {
            page == Decl.PAGE_SERVER -> ServerConfigPage()
            page == Decl.PAGE_DEVICE -> DevicePage()
            page == Decl.PAGE_ENGINES -> EnginesPage()
            page == Decl.PAGE_LINKS -> LinksPage()
            page != null && isFleetConfigsPage(page) -> FleetConfigsPageBody(page, dark = true, onAppConfigs = { open(Decl.SECTION_CONFIGS, Decl.PAGE_SERVER) })
            else -> FleetConfigsPageBody(page ?: "general", dark = true, onAppConfigs = { open(Decl.SECTION_CONFIGS, Decl.PAGE_SERVER) })
        }
        else -> HomePage(open)
    }
}
