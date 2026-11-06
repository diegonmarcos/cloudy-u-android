package com.diegonmarcos.cloudsuperintel.ui

import com.diegonmarcos.cloudsuperintel.SiOpen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.AiData
import com.diegonmarcos.cloudsuperintel.BuildConfig
import com.diegonmarcos.cloudsuperintel.Decl
import com.diegonmarcos.cloudsuperintel.R
import com.diegonmarcos.cloudsuperintel.SiAi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.diegonmarcos.superapp.ai.AiAttachHost
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiSessionsPanel
import com.diegonmarcos.superapp.ai.AiSettingsPickers
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.materialAiTheme
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.perms.PermsFixChip
import com.diegonmarcos.superapp.perms.rememberPermsMissing
import com.diegonmarcos.superapp.themebg.themeBackground

/** The test tags the shell is driven by. */
object SiTags {
    const val SHELL = "si_shell"
    const val CHAT = "si_chat"
    const val SECOND_ROW = "si_second_row"
    const val PAGE_TEXT = "si_page_text"
}

/** The chat page's look: the fleet AI default (Material colours) with Markdown answers on, in this app's words. */
@Composable
fun siAiTheme(): AiTheme = materialAiTheme(
    AiStrings(
        title = stringResource(R.string.app_name),
        hello = stringResource(R.string.chat_hello),
        helloSub = stringResource(R.string.chat_hello_sub),
        askAnything = stringResource(R.string.chat_hint),
        send = stringResource(R.string.chat_send),
        chatError = stringResource(R.string.chat_failed),
    ),
).copy(markdown = true)

/**
 * The shell: the shared first row (Profile at the top right opens Cloud Account; the hamburger opens the sessions
 * drawer), the second row with the page icons, the bottom nav, the shared page gradient and the shared scroll collapse,
 * all from libs (#1154, #1157). Which sections and pages exist is build.json::ui, read through [Decl.nav].
 */
@Composable
fun SiShell() {
    val ctx = LocalContext.current.applicationContext
    val personas = remember { SiAi.personas(ctx) }
    val siDefault = remember { SiAi.defaultId(ctx) }
    val notebooks = remember { NotebookModel(ctx) }
    val nav = Decl.nav
    val aiPages = remember { AiData.pages(AiData.asset(ctx, AiData.PAGES)) }
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_SI) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val sec = nav.section(section)
    val pageId = page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val theme = siAiTheme()
    // The chat the drawer, the Models page and the sessions follow: the open persona's, else the router's.
    val persona = personas.firstOrNull { it.id == pageId && (sec?.id == Decl.SECTION_SI || sec?.id == Decl.SECTION_CGCS) }
    val chat = remember(persona?.id) { SiAi.chat(ctx, persona?.id) }
    val entries = nav.islandEntries()
    // The (+) menu's Files, Image and Camera: their Perms rows are libs:perms' (build.json::perms.needs), shown as "Fix in Perms" while missing.
    val attachHost = remember { AiAttachHost(missing = { ids -> rememberPermsMissing(ids).map { it.id } }, fix = { ids -> PermsFixChip(ids, ATTACH_FEATURE) }) }
    fun open(sectionId: String, pageId: String?) { section = sectionId; page = pageId }
    // #1543 a deep link (the SuperApp AI badge -> Cost): opened once, only when the nav declares that section.
    val asked = SiOpen.pending
    androidx.compose.runtime.LaunchedEffect(asked) {
        if (asked != null) {
            nav.section(asked.section)?.let { s -> open(s.id, asked.page?.takeIf { p -> s.pages.any { it.id == p } }) }
            SiOpen.pending = null
        }
    }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e -> open(e.sectionId, if (e.depth == 0) null else e.id) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { open(Decl.SECTION_SETTINGS, null) },
            onAi = { open(Decl.SECTION_SI, siDefault) },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = entries,
                selectedId = section,
                onSelect = { open(it.id, null) },
                modifier = Modifier.testTag(SiTags.SHELL),
            ) {
                // The hamburger's left drawer: new chat, then the sessions (open, rename, delete).
                TopBarDrawer { close -> AiSessionsPanel(chat, theme, onPicked = { if (persona == null) open(Decl.SECTION_SI, siDefault); close() }) }
                val pageBody: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        when (sec?.id) {
                            Decl.SECTION_NOTEBOOK -> NotebookPage(notebooks, pageId.orEmpty(), theme, { page = it }, NotebookIds(Decl.PAGE_NB_NOTEBOOKS, Decl.PAGE_NB_SOURCES, Decl.PAGE_NB_CHAT, Decl.PAGE_NB_STUDIO))
                            // SI (the router) and every CGC expert are the same shared chat page, each over its own persona.
                            Decl.SECTION_SI, Decl.SECTION_CGCS -> persona?.let { p ->
                                LaunchedEffect(p.id) { withContext(Dispatchers.IO) { SiAi.tune(ctx, p, chat) } }
                                AiChatPage(
                                    chat, theme.copy(strings = theme.strings.copy(hello = p.label, helloSub = p.role)),
                                    Modifier.imePadding().testTag(SiTags.CHAT), suggestions = p.suggestions, attach = attachHost,
                                )
                            }
                            // The AIs and Cost pages are data (assets/ai/pages.json, by page id): the screen kind and the asset, no name here.
            else -> if (pageId != null && aiPages[pageId] != null) AiPage(aiPages.getValue(pageId)) else when (pageId) {
                                Decl.PAGE_MODELS -> TextPage(R.string.models_title, stringResource(R.string.models_body)) { AiSettingsPickers(chat, theme) }
                                Decl.PAGE_BACKEND -> TextPage(R.string.backend_title, stringResource(R.string.backend_body) + "\n\n" + stringResource(R.string.backend_gateway, BuildConfig.AI_GATEWAY_URL))
                                Decl.PAGE_ABOUT -> TextPage(R.string.about_title, stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
                            }
                        }
                    }
                }
                // The section's child pages in the mode it declares or the user chose in Configs (one row, two rows, side menu).
                if (sec != null && sec.pages.isNotEmpty()) {
                    NavSectionLayout(sec, pageId, onSelect = { page = it.id }, modifier = Modifier.fillMaxSize().testTag(SiTags.SECOND_ROW), content = pageBody)
                } else pageBody()
            }
        }
    }
}

/** The libs:perms feature the attach menu's "Fix in Perms" chip reports. */
private const val ATTACH_FEATURE = "chat"

/** A plain settings page: a title, a paragraph, and the page's own controls under them. */
@Composable
private fun TextPage(title: Int, body: String, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp).testTag(SiTags.PAGE_TEXT))
        extra()
    }
}
