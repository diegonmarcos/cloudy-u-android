package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudkgstore.BuildConfig
import com.diegonmarcos.cloudkgstore.Decl
import com.diegonmarcos.cloudkgstore.KgAi
import com.diegonmarcos.cloudkgstore.KgConfig
import com.diegonmarcos.cloudkgstore.KgServices
import com.diegonmarcos.cloudkgstore.R
import com.diegonmarcos.superapp.ai.AiChatPage
import com.diegonmarcos.superapp.ai.AiSessionsPanel
import com.diegonmarcos.superapp.ai.AiSettingsPickers
import com.diegonmarcos.superapp.ai.AiStrings
import com.diegonmarcos.superapp.ai.AiTheme
import com.diegonmarcos.superapp.ai.materialAiTheme
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.themebg.themeBackground

/** The assistant's look: the fleet AI default (Material colours) with Markdown answers on, in this app's words. */
@Composable
fun kgAiTheme(): AiTheme = materialAiTheme(
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
 * The shell: the shared first row (Profile at the top right opens Cloud Account; the hamburger opens the assistant's sessions
 * drawer; the AI icon opens the assistant), the second row (two rows in Cloud), the bottom nav API, Scrapped, Home, Me, Cloud, the shared page
 * gradient and the shared scroll collapse, all from libs (#1154, #1157). Which sections and pages exist is build.json::ui, read
 * through [Decl.nav]; what each page shows is the kg dataset (Cloud Store), read through [KgServices].
 */
@Composable
fun KgShell() {
    val ctx = LocalContext.current.applicationContext
    val svc = remember { KgServices.get(ctx) }
    val nav = Decl.nav
    var section by rememberSaveable { mutableStateOf(nav.default()?.id ?: Decl.SECTION_HOME) }
    var page by rememberSaveable { mutableStateOf<String?>(null) }
    val sec = nav.section(section)
    val pageId = page?.takeIf { p -> sec?.pages?.any { it.id == p } == true } ?: sec?.pages?.firstOrNull()?.id
    val theme = kgAiTheme()
    // #1547 the page declaration is served by Cloud Store: retry (off the main thread) until it arrives, then rebuild what depends on it.
    var ready by remember { mutableStateOf(svc.kg !== KgConfig.EMPTY) }
    LaunchedEffect(Unit) { while (!ready) { delay(3_000); ready = withContext(Dispatchers.IO) { svc.refresh() } } }
    val chat = remember(ready) { KgAi.chat(ctx) }
    val entries = nav.islandEntries()
    fun open(sectionId: String, pageId: String?) { section = sectionId; page = pageId }

    Box(Modifier.fillMaxSize().themeBackground(dark = true).windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))) {
        NavBarTopFirstRowScaffold(
            title = sec?.label ?: stringResource(R.string.app_name),
            hierarchy = sec?.let { titleHierarchy(it, pageId) }.orEmpty(),
            onNavigate = { e -> open(e.sectionId, if (e.depth == 0) null else e.id) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { open(Decl.SECTION_SETTINGS, null) },
            onAi = { open(Decl.SECTION_ASK, null) },
            dark = true,
        ) {
            NavBarBottomHost(
                entries = entries,
                selectedId = section,
                onSelect = { open(it.id, null) },
                modifier = Modifier.testTag(KgTags.SHELL),
            ) {
                // The hamburger's left drawer: new chat, then the assistant's sessions (open, rename, delete).
                TopBarDrawer { close -> AiSessionsPanel(chat, theme, onPicked = { open(Decl.SECTION_ASK, null); close() }) }
                val pageBody: @Composable () -> Unit = {
                    Box(Modifier.fillMaxSize()) {
                        val kg = pageId?.let { svc.kg.page(it) }
                        when {
                            sec?.id == Decl.SECTION_SCRAPPED -> ScrappedPage(svc)
                            sec?.id == Decl.SECTION_HOME -> HomePage(nav.bottomNav.filter { it != Decl.SECTION_HOME }.mapNotNull { id -> nav.section(id)?.let { id to it.label } }) { open(it, null) }
                            sec?.id == Decl.SECTION_ME -> MePage()
                            sec?.id == Decl.SECTION_ASK ->
                                AiChatPage(chat, theme.copy(strings = theme.strings.copy(hello = stringResource(R.string.chat_hello))), Modifier.imePadding().testTag(KgTags.CHAT), suggestions = svc.kg.ask.suggestions)
                            kg != null -> when (kg.kind) {
                                KgConfig.KIND_PHONE_APPS -> PhoneAppsPage()
                                KgConfig.KIND_NEWS, KgConfig.KIND_VERTICALS -> WebPage(kg, svc)
                                else -> CloudPage(kg, svc)
                            }
                            else -> when (pageId) {
                                Decl.PAGE_MODELS -> TextPage(R.string.models_title, stringResource(R.string.models_body)) { AiSettingsPickers(chat, theme) }
                                Decl.PAGE_BACKEND -> TextPage(R.string.backend_title, stringResource(R.string.backend_body) + "\n\n" + stringResource(R.string.backend_gateway, BuildConfig.AI_GATEWAY_URL))
                                Decl.PAGE_ABOUT -> TextPage(R.string.about_title, stringResource(R.string.about_body, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHORT_SHA))
                            }
                        }
                    }
                }
                // The section's child pages in the mode it declares or the user chose in Configs (one row, two rows, side menu).
                if (sec != null && sec.pages.isNotEmpty()) {
                    NavSectionLayout(sec, pageId, onSelect = { page = it.id }, modifier = Modifier.fillMaxSize().testTag(KgTags.SECOND_ROW), content = pageBody)
                } else pageBody()
            }
        }
    }
}

/** A plain settings page: a title, a paragraph, and the page's own controls under them. */
@Composable
private fun TextPage(title: Int, body: String, extra: @Composable () -> Unit = {}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp).testTag(KgTags.PAGE_TEXT))
        extra()
    }
}
