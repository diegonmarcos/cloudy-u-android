package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import com.diegonmarcos.cloudwriter.core.ControlKind
import com.diegonmarcos.cloudwriter.core.Dest
import com.diegonmarcos.cloudwriter.core.Focus
import com.diegonmarcos.cloudwriter.core.FormatAction
import com.diegonmarcos.cloudwriter.core.Transition
import com.diegonmarcos.cloudwriter.core.WriterGrid
import com.diegonmarcos.cloudwriter.core.WriterScreens
import com.diegonmarcos.cloudwriter.core.WriterTabs
import com.diegonmarcos.cloudwriter.ui.BarDivider
import com.diegonmarcos.cloudwriter.ui.WriterBar
import com.diegonmarcos.cloudwriter.ui.WriterButton
import com.diegonmarcos.cloudwriter.ui.WriterChip
import com.diegonmarcos.cloudwriter.ui.WriterDims
import com.diegonmarcos.cloudwriter.ui.WriterDocCard
import com.diegonmarcos.cloudwriter.ui.WriterEmptyState
import com.diegonmarcos.cloudwriter.ui.WriterFilterChip
import com.diegonmarcos.cloudwriter.ui.WriterIconButton
import com.diegonmarcos.cloudwriter.ui.WriterLabel
import com.diegonmarcos.cloudwriter.ui.WriterSearchField
import androidx.activity.ComponentActivity
import android.content.ContextWrapper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AltRoute
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FormatBold
import androidx.compose.material.icons.rounded.FormatItalic
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Spellcheck
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Summarize
import androidx.compose.material.icons.rounded.Title
import androidx.compose.material.icons.rounded.Toc
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudwriter.core.DocList
import com.diegonmarcos.cloudwriter.core.DocMeta
import com.diegonmarcos.cloudwriter.core.DocSort
import com.diegonmarcos.cloudwriter.core.HtmlText
import com.diegonmarcos.cloudwriter.core.Import
import com.diegonmarcos.cloudwriter.core.ImportKind
import com.diegonmarcos.cloudwriter.core.SaveState
import com.diegonmarcos.cloudwriter.core.DocStore
import com.diegonmarcos.cloudwriter.core.Edit
import com.diegonmarcos.cloudwriter.core.Markdown
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.cloudwriter.core.OcrEngine
import com.diegonmarcos.cloudwriter.ui.BlockGap
import com.diegonmarcos.cloudwriter.ui.ChoiceRow
import com.diegonmarcos.cloudwriter.ui.CloudWriterTheme
import com.diegonmarcos.cloudwriter.ui.FeatureCard
import com.diegonmarcos.cloudwriter.ui.KitBridge
import com.diegonmarcos.cloudwriter.ui.MarkdownLook
import com.diegonmarcos.cloudwriter.ui.NoteText
import com.diegonmarcos.cloudwriter.ui.PageGutter
import com.diegonmarcos.cloudwriter.ui.SectionHeader
import com.diegonmarcos.cloudwriter.ui.WriterTheme
import com.diegonmarcos.cloudwriter.ui.asValue
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.themebg.themeBackground
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.cloudwriter.ui.insertAtCaret
import androidx.compose.material.icons.rounded.FormatSize
import androidx.core.content.FileProvider
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.mlocr.OcrImage
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitBadge
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitDocRow
import com.diegonmarcos.superapp.uikit.KitEditorStatus
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitFilterChips
import com.diegonmarcos.superapp.uikit.KitSearchBar
import com.diegonmarcos.superapp.uikit.KitSkeletonRows
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.KitToolAction
import com.diegonmarcos.superapp.uikit.KitToolbar
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** #1532 the screens are core's [Dest], so the navigation rules (Back, the transitions) are JVM-tested. */
private typealias Screen = Dest

/**
 * Cloud Writer, v2 (#800) — from an MVP of two boxes to a writing app.
 *
 *   DOCUMENTS  every document, newest first, searched as you type; the status card naming the app
 *              that serves the text tools (and the one-tap Store repair when it needs one); a new
 *              document from the button, from a share, or from a text selection in any app
 *   EDITOR     Markdown kept as plain text and DRAWN rich (headings, bold, italic, code, quotes,
 *              list markers — ui.MarkdownLook over core Markdown.spans), a formatting toolbar, the
 *              four tools on the selection or the whole document with a result you Replace, Insert
 *              or Copy, the outline (jump to a heading), find with match count and stepping,
 *              Listen (dictation into the document, live partial text, pause/resume, auto-translate
 *              beside or instead), share and export as .md or .txt, autosave
 *   CONFIGS    theme (system / light / dark), the five settings pages, the per-tool models
 *
 * WHAT DID NOT CHANGE. Every tool call is still [WriterToolRunner.run] with the scope rule of the
 * Text Enhance page; the per-tool models, the four options rows and the four settings pages are the
 * same composables over the same WriterPrefs keys. The documents are core [DocStore] files under
 * filesDir/documents — there were no documents before this, so nothing needed migrating.
 *
 * Plain mutableStateOf on the activity rather than a ViewModel: android:configChanges keeps this
 * activity alive across rotation and the uiMode flip, and the threading below (an executor and a
 * main Handler) writes into these exactly as the previous screen did.
 */
class WriterSurface(
    val activity: ComponentActivity,
    /** What leaves the surface at the first screen's Back: the app's activity finishes, a host that embeds it navigates away. */
    private val onExit: () -> Unit = { activity.finish() },
    /** #1652 pages the HOST owns, by id (the keyboard's user Dictionary): [Page] draws these for an id it does not know. */
    private val hostPages: Map<String, @Composable () -> Unit> = emptyMap(),
) : ContextWrapper(activity) {

    // #1418 the four tool tabs' boxes, kept across tab switches (WYSIWYG by default).
    private val enhIn = BoxState()
    private val enhOut = BoxState()
    private val draftIn = BoxState()
    private val draftOut = BoxState()
    private val trIn = BoxState()
    private val trOut = BoxState()
    private val grIn = BoxState()
    private val grOut = BoxState()
    /** The last thing a tool tab said: a run's outcome, a load, an OCR read, an export. */
    private val tabNote: MutableState<String?> = mutableStateOf(null)
    private val tabBusy: MutableState<Boolean> = mutableStateOf(false)
    private val ocrGeneration: MutableState<Int> = mutableStateOf(0)

    /** A tool's answer waiting for the owner: the text and the range of the document it replaces. */
    private class ToolResult(val tool: WriterTool, val text: String, val note: String, val from: Int, val to: Int)

    private val runner = WriterToolRunner(this)
    private val store = DocStore(ListenEngine.docsDir(this))
    /** The HOST's window-inset treatment of the shell (the app passes its status-bar padding): shared pages never handle the top inset themselves. */
    private var chrome: Modifier = Modifier

    /** #1652 the document the Dictation page's transcript lands in: created on its first Start, reused after. */
    private var dictationDoc: String? = null

    /** For what must not run on the main thread: the status probe, the list, the share-in save. */
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private val screen: MutableState<Screen> = mutableStateOf(Screen.DOCS)
    private val configsReturn: MutableState<Screen> = mutableStateOf(Screen.DOCS)
    /** #868 the document the Writer item reopens: the last one opened, until it is deleted. */
    private var lastDocId: String? = null
    /** #868 the Tools strip's selected page (a ui.sections[tools].pages id). */
    private val toolsPage: MutableState<String> = mutableStateOf("")
    private val docs: MutableState<List<DocMeta>> = mutableStateOf(emptyList())
    private val query: MutableState<String> = mutableStateOf("")
    /** #1457 false until the first list read returns: the list shows a skeleton, not the empty state. */
    private val docsLoaded: MutableState<Boolean> = mutableStateOf(false)
    private val docSort: MutableState<DocSort> = mutableStateOf(DocSort.RECENT)
    /** #1457 the editor's autosave state and when it last saved. */
    private val saveState: MutableState<SaveState> = mutableStateOf(SaveState.SAVED)
    private val savedAt: MutableState<Long> = mutableStateOf(0L)
    /** #1457 the last import (file, image or camera) outcome, said under the actions. */
    private val importNote: MutableState<Pair<String, KitState>?> = mutableStateOf(null)
    /** #1457 the selected Configs page (a ui.sections[settings].pages id). */
    private val configsPage: MutableState<String> = mutableStateOf("general")
    private val docId: MutableState<String?> = mutableStateOf(null)
    private val field: MutableState<TextFieldValue> = mutableStateOf(TextFieldValue(""))

    private val status: MutableState<String?> = mutableStateOf(null)
    private val report: MutableState<String?> = mutableStateOf(null)
    private val busy: MutableState<Boolean> = mutableStateOf(false)
    private val result: MutableState<ToolResult?> = mutableStateOf(null)
    private val confirmDelete: MutableState<String?> = mutableStateOf(null)

    private val findOpen: MutableState<Boolean> = mutableStateOf(false)
    private val findQuery: MutableState<String> = mutableStateOf("")
    private val findIndex: MutableState<Int> = mutableStateOf(0)
    private val outlineOpen: MutableState<Boolean> = mutableStateOf(false)
    private val optionsOpen: MutableState<Boolean> = mutableStateOf(false)
    private val listenOpen: MutableState<Boolean> = mutableStateOf(false)
    private val listenPrefs: MutableState<Int> = mutableStateOf(0)
    /** #1532 focus mode: the chrome steps away and every paragraph but the current one is dimmed. */
    private val focusMode: MutableState<Boolean> = mutableStateOf(false)

    /**
     * The first intent of the hosting activity (a share, a PROCESS_TEXT selection, a deep link).
     * Read once at creation: the host's activity uses the default launch mode, so a share starts a
     * fresh instance and its intent arrives here.
     */
    fun adoptIntent(intent: Intent?) {
        adoptSharedText(intent)
        openRequestedSection(intent)
    }

    /** A new intent for an already-running host activity (a bottom-nav press on a tool page asks for its section). */
    fun onNewIntent(intent: Intent?) {
        openRequestedSection(intent)
    }

    /** #1164 a bottom-nav item pressed on a tool page (WriterSettingsActivity) asks for its section; the editor is reopened by its own item only. */
    private fun openRequestedSection(from: Intent?) {
        val id = from?.getStringExtra(EXTRA_SECTION) ?: return
        from.removeExtra(EXTRA_SECTION)
        if (id != WriterNav.WRITER) goSection(id)
        // #1457 a Configs page by id (general, perms, about, appearance, models, ocr), for deep links and the emulator walk.
        from.getStringExtra(EXTRA_PAGE)?.let { if (id == WriterNav.SETTINGS) configsPage.value = it }
        from.removeExtra(EXTRA_PAGE)
    }

    private fun onStart() {
        // Listen may have written into the open document's file while the screen was off.
        if (screen.value == Screen.EDITOR) docId.value?.let { id -> store.read(id)?.let { field.value = TextFieldValue(it, TextRange(it.length)) } }
        attachSink()
    }

    private fun onResume() {
        refreshStatus()
        reloadDocs()
    }

    private fun onStop() {
        saveNow()
        // From here on Listen appends to the document FILE: a stopped activity recomposes nothing.
        ListenEngine.sink = null
    }

    /**
     * Text shared in from another application, or selected in one and sent here by PROCESS_TEXT,
     * becomes a NEW document and opens in the editor. Read in onCreate: this activity uses the
     * default launch mode, so a share starts a fresh instance and its intent arrives here.
     */
    private fun adoptSharedText(from: Intent?) {
        if (from == null) return
        // #1418 an image shared in: OCR it into the Enhancer's input box (the Configs engine).
        if (from.action == Intent.ACTION_SEND && from.type?.startsWith("image/") == true) {
            @Suppress("DEPRECATION")
            val uri = from.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM) ?: return
            screen.value = Screen.ENHANCER
            ocrInto(this, worker, enhIn, uri) { tabNote.value = it }
            return
        }
        val shared = when (from.action) {
            Intent.ACTION_SEND -> from.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_PROCESS_TEXT -> from.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!shared.isNullOrBlank()) open(store.create(shared))
    }

    // ── navigation and documents ─────────────────────────────────────────

    private fun open(id: String) {
        saveNow()
        docId.value = id
        lastDocId = id
        field.value = TextFieldValue(store.read(id).orEmpty())
        report.value = null
        findOpen.value = false
        findQuery.value = ""
        focusMode.value = false
        screen.value = Screen.EDITOR
        attachSink()
    }

    private fun newDoc() = open(store.create(""))

    private fun back() {
        val from = screen.value
        if (from == Screen.EDITOR && focusMode.value) {
            focusMode.value = false
            return
        }
        val next = WriterScreens.back(from, configsReturn.value, docId.value != null)
        if (next == null) {
            onExit()
            return
        }
        if (from == Screen.EDITOR) {
            saveNow()
            ListenEngine.sink = null
            docId.value = null
            reloadDocs()
        }
        screen.value = next
    }

    /** #868 an island item: the bar is on every screen but the editor, whose own bars take its place. */
    private fun goSection(id: String) {
        when (id) {
            WriterNav.DOCUMENTS -> screen.value = Screen.DOCS
            WriterNav.WRITER -> lastDocId?.takeIf { store.read(it) != null }?.let { open(it) } ?: newDoc()
            WriterNav.TOOLS -> screen.value = Screen.TOOLS
            WriterNav.ENHANCER -> screen.value = Screen.ENHANCER
            WriterNav.DRAFTER -> screen.value = Screen.DRAFTER
            WriterNav.TRANSLATOR -> screen.value = Screen.TRANSLATOR
            WriterNav.GRAMMAR -> screen.value = Screen.GRAMMAR
            WriterNav.SETTINGS -> openConfigs()
        }
    }

    private fun openConfigs() {
        configsReturn.value = screen.value
        screen.value = Screen.CONFIGS
    }

    private fun saveNow() {
        if (screen.value != Screen.EDITOR) return
        val id = docId.value ?: return
        store.write(id, field.value.text)
    }

    private fun reloadDocs() {
        val q = query.value
        worker.execute {
            val list = store.search(q)
            main.post { docs.value = list; docsLoaded.value = true }
        }
    }

    private fun delete(id: String) {
        store.delete(id)
        if (lastDocId == id) lastDocId = null
        if (docId.value == id) {
            docId.value = null
            screen.value = Screen.DOCS
        }
        reloadDocs()
    }

    /**
     * Where Listen's text goes while this document is on screen: at the caret, through the field,
     * so the owner sees it arrive and the autosave keeps the file. A session started on another
     * document keeps writing into THAT document's file.
     */
    private fun attachSink() {
        if (screen.value != Screen.EDITOR) return
        ListenEngine.sink = { segment ->
            val open = docId.value
            val target = ListenEngine.status.docId
            if (open != null && target == open) field.value = insertAtCaret(field.value, segment)
            else if (target != null) worker.execute { store.append(target, segment) }
        }
    }

    // ── the app ──────────────────────────────────────────────────────────

    // ── the public surface (#1652) ───────────────────────────────────────

    /**
     * THE WHOLE APP: the shared first row, the bottom nav (the five pages), the Tools and Configs
     * sections, the editor. This is exactly what cloud-writer's MainActivity draws.
     */
    @Composable
    fun Content(chromeModifier: Modifier = Modifier) {
        chrome = chromeModifier
        CloudWriterTheme {
            Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { App() }
        }
    }

    /**
     * ONE NAMED PAGE, WITHOUT THE APP'S NAVIGATION: no first row, no bottom nav. For a host that
     * has its own (the keyboard's Writer tab presents the former bottom-nav pages as TOP-nav
     * subpages). [id] is one of [ALL_PAGES]: the five bottom-nav pages ([BOTTOM_NAV_PAGES]), the
     * Tools pages ([TOOLS_PAGES]), [PAGE_DICTATION], [PAGE_EDITOR], [PAGE_TOOLS] (the Tools index),
     * [PAGE_CONFIGS]; any other id is looked up in `hostPages` (the keyboard's Dictionary).
     * Wrapped in cloud-writer's own theme unless [themed] is false.
     */
    @Composable
    fun Page(id: String, themed: Boolean = true) {
        if (themed) {
            CloudWriterTheme {
                Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { PageBody(id) }
            }
        } else {
            PageBody(id)
        }
    }

    /** The localized title of a page id, for a host's top-nav tab. */
    fun pageLabel(id: String): String = when (id) {
        PAGE_HOME -> getString(R.string.nav_home)
        PAGE_EDITOR -> getString(R.string.nav_writer)
        PAGE_TOOLS -> getString(R.string.nav_tools)
        PAGE_CONFIGS -> getString(R.string.nav_settings)
        PAGE_ENHANCER -> getString(R.string.nav_enhancer)
        PAGE_DRAFTER -> getString(R.string.nav_drafter)
        PAGE_TRANSLATOR -> getString(R.string.nav_translator)
        PAGE_GRAMMAR -> getString(R.string.nav_grammar)
        PAGE_TOOL_ENHANCE -> getString(R.string.settings_screen_enhance)
        PAGE_TOOL_TRANSLATION -> getString(R.string.settings_screen_translation)
        PAGE_TOOL_GRAMMAR -> getString(R.string.settings_screen_grammar)
        PAGE_TOOL_AI_ROUTING -> getString(R.string.settings_screen_ai_routing)
        PAGE_TOOL_ROUTES -> getString(R.string.settings_screen_routes)
        PAGE_DICTATION -> getString(R.string.writer_page_dictation)
        PAGE_DICTIONARY -> getString(R.string.writer_page_dictionary)
        else -> id
    }

    private fun toolPage(id: String): WriterSettingsPage? = when (id) {
        PAGE_TOOL_ENHANCE -> TextEnhancePage(activity)
        PAGE_TOOL_TRANSLATION -> TranslationPage(activity)
        PAGE_TOOL_GRAMMAR -> GrammarCheckPage(activity)
        PAGE_TOOL_AI_ROUTING -> AiRoutingPage(activity)
        PAGE_TOOL_ROUTES -> RoutesPage(activity)
        else -> null
    }

    @Composable
    private fun PageBody(id: String) {
        when (id) {
            PAGE_ENHANCER -> EnhancerScreen()
            PAGE_DRAFTER -> DrafterScreen()
            PAGE_TRANSLATOR -> TranslatorScreen()
            PAGE_GRAMMAR -> GrammarScreen()
            PAGE_HOME, PAGE_EDITOR -> {
                // Home is the document list; a document opens in place and Back returns to the list.
                BackHandler(enabled = screen.value == Screen.EDITOR) { back() }
                if (screen.value == Screen.EDITOR) EditorScreen() else DocsScreen()
            }
            PAGE_TOOLS -> ToolsScreen()
            PAGE_CONFIGS -> ConfigsScreen()
            PAGE_DICTATION -> DictationPage()
            else -> {
                val tool = remember(id) { toolPage(id) }
                if (tool != null) tool.Body() else hostPages[id]?.invoke()
            }
        }
        result.value?.let { ResultDialog(it) }
        confirmDelete.value?.let { DeleteDialog(it) }
    }

    /**
     * #1652 VOICE TRANSCRIPTION as a page of its own: the Listen panel (start, pause, stop, the level, the live partial
     * text, the route, the translate-as-you-go switch) over the transcript. The text lands in a document of the Home
     * list, created on the first Start, so nothing said is lost when the page is left.
     */
    @Composable
    private fun DictationPage() {
        val s = ListenEngine.state.value
        var transcript by remember { mutableStateOf("") }
        LaunchedEffect(s.segments, s.running) {
            val id = dictationDoc
            transcript = if (id == null) "" else withContext(Dispatchers.IO) { store.read(id).orEmpty() }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(PageGutter)) {
            ListenPanel(dictation = true)
            Spacer(Modifier.height(BlockGap))
            if (transcript.isNotBlank()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    Text(transcript, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
                }
                WriterButton(stringResource(R.string.action_copy), { copy(transcript) }, kind = ControlKind.TONAL)
            }
        }
    }

    @Composable
    private fun App() {
        BackHandler(enabled = screen.value != Screen.DOCS) { back() }
        WriterShell()
        result.value?.let { ResultDialog(it) }
        confirmDelete.value?.let { DeleteDialog(it) }
    }

    /**
     * #1164 THE one shell of the app: the shared first row (title island with the section's pages, Profile top-right launches
     * Cloud Account), the shared bottom nav (libs:ui-nav-bars, fed by build.json::ui through [WriterNav.decl]), the shared page
     * background and the shared scroll-collapse (a page scrolled down hides the first row and compacts the rows, up restores).
     * Documents, Tools and Settings are the content of the host; the editor, whose own format and tool bars take the bottom
     * bar's place, is the content of the first-row scaffold alone.
     */
    @Composable
    private fun WriterShell() {
        val current = screen.value
        val dark = WriterTheme.isDark()
        val sectionId = when (current) {
            Screen.DOCS -> WriterNav.DOCUMENTS
            Screen.EDITOR -> WriterNav.WRITER
            Screen.TOOLS -> WriterNav.TOOLS
            Screen.CONFIGS -> WriterNav.SETTINGS
            Screen.ENHANCER -> WriterNav.ENHANCER
            Screen.DRAFTER -> WriterNav.DRAFTER
            Screen.TRANSLATOR -> WriterNav.TRANSLATOR
            Screen.GRAMMAR -> WriterNav.GRAMMAR
        }
        val section = WriterNav.decl.section(sectionId)
        val title = when (current) {
            Screen.DOCS -> stringResource(R.string.writer_surface_name)
            Screen.EDITOR -> Markdown.title(field.value.text).ifBlank { stringResource(R.string.doc_untitled) }
            Screen.TOOLS -> stringResource(R.string.nav_tools)
            Screen.CONFIGS -> stringResource(R.string.configs_title)
            Screen.ENHANCER, Screen.DRAFTER, Screen.TRANSLATOR, Screen.GRAMMAR -> sectionLabel(sectionId, section?.label.orEmpty())
        }
        val entries = WriterNav.decl.bottomSections().map { s ->
            navEntry(s.id, sectionLabel(s.id, s.label), s.icon)
        }
        NavBarTopFirstRowScaffold(
            title = title,
            hierarchy = section?.let { titleHierarchy(it, if (current == Screen.TOOLS) toolsPage.value.ifBlank { null } else if (current == Screen.CONFIGS) configsPage.value else null) }.orEmpty(),
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = WriterNav.decl.sections,
            onNavigate = { e -> goSection(e.sectionId); if (e.depth > 0 && e.sectionId == WriterNav.TOOLS) toolsPage.value = e.id; if (e.depth > 0 && e.sectionId == WriterNav.SETTINGS) configsPage.value = e.id },
            onAccount = rememberAccountLauncher(),
            onConfigs = { if (screen.value != Screen.CONFIGS) { saveNow(); openConfigs() } },
            onAi = rememberSuperAppAiLauncher(),
            modifier = Modifier.fillMaxSize().then(chrome),
            dark = dark,
        ) {
            TopBarDrawer { close ->
                // #1418 every declared section, so Writer, Tools and Settings (off the bar) stay one tap away.
                WriterNav.decl.sections.forEach { s ->
                    Text(
                        sectionLabel(s.id, s.label),
                        Modifier.fillMaxWidth().clickable { goSection(s.id); close() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            // The editor sits under the same bar as every other screen (the walk, and a user, reach Tools and Settings from an open
            // document); the bar steps aside by itself while the keyboard is up.
            NavBarBottomHost(
                entries = entries,
                selectedId = sectionId,
                onSelect = { e -> if (e.id != sectionId) goSection(e.id) },
                dark = dark,
            ) {
                // #1532 fade-through between screens (the list opening a document slides forward, closing it back).
                AnimatedContent(
                    targetState = current,
                    modifier = Modifier.fillMaxSize(),
                    transitionSpec = { writerTransition(initialState, targetState) },
                    label = "writer_screen",
                ) { shown ->
                    when (shown) {
                        Screen.DOCS -> DocsScreen()
                        Screen.TOOLS -> ToolsScreen()
                        Screen.CONFIGS -> ConfigsScreen()
                        Screen.EDITOR -> EditorScreen()
                        Screen.ENHANCER -> EnhancerScreen()
                        Screen.DRAFTER -> DrafterScreen()
                        Screen.TRANSLATOR -> TranslatorScreen()
                        Screen.GRAMMAR -> GrammarScreen()
                    }
                }
            }
        }
    }

    /** #1532 the content transition between two screens, from core WriterScreens' timings and its forward / back / fade-through decision. */
    private fun writerTransition(from: Screen, to: Screen): ContentTransform {
        val leave = fadeOut(tween(WriterScreens.FADE_OUT_MS))
        val delay = WriterScreens.FADE_OUT_MS
        val arrive = fadeIn(tween(WriterScreens.FADE_IN_MS, delayMillis = delay))
        return when (WriterScreens.transition(from, to)) {
            Transition.FORWARD -> (arrive + slideInHorizontally(tween(WriterScreens.FADE_IN_MS, delayMillis = delay)) { it / WriterScreens.SLIDE_FRACTION }) togetherWith leave
            Transition.BACKWARD -> (arrive + slideInHorizontally(tween(WriterScreens.FADE_IN_MS, delayMillis = delay)) { -it / WriterScreens.SLIDE_FRACTION }) togetherWith leave
            else -> (arrive + scaleIn(tween(WriterScreens.FADE_IN_MS, delayMillis = delay), initialScale = 0.92f)) togetherWith leave
        }
    }

    /** The localized label of a declared section; the declared English one for an id with no string. */
    private fun sectionLabel(id: String, declared: String): String = when (id) {
        WriterNav.DOCUMENTS -> getString(R.string.nav_home)
        WriterNav.WRITER -> getString(R.string.nav_writer)
        WriterNav.TOOLS -> getString(R.string.nav_tools)
        WriterNav.SETTINGS -> getString(R.string.nav_settings)
        WriterNav.ENHANCER -> getString(R.string.nav_enhancer)
        WriterNav.DRAFTER -> getString(R.string.nav_drafter)
        WriterNav.TRANSLATOR -> getString(R.string.nav_translator)
        WriterNav.GRAMMAR -> getString(R.string.nav_grammar)
        else -> declared
    }

    /**
     * #1532 Home, premium: a headline with the totals, a rounded search field, the status of the text-tool peer, the import
     * actions as tonal buttons, sort and tag filter chips, then the documents as cards (title, two-line preview, updated
     * time, words, reading time, tags) over a floating New document button that collapses to its icon while scrolling. A
     * skeleton shows while the list loads; the empty state is an illustration with its call to action. Every #1457 action
     * is still here: new, import file, scan, import image, search, sort, share, delete.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DocsScreen() {
        val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { importFile(it) } }
        val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { importImage(it) } }
        var shot by remember { mutableStateOf<Uri?>(null) }
        val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> shot?.takeIf { ok }?.let { importImage(it) } }
        var tagFilter by remember { mutableStateOf<String?>(null) }
        val sorted = DocList.sort(docs.value, docSort.value)
        val tags = DocList.allTags(docs.value)
        val activeTag = tagFilter?.takeIf { it in tags }
        val list = DocList.withTag(sorted, activeTag)
        val listState = rememberLazyListState()
        val fabExpanded by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
        val openFile = { pickFile.launch(arrayOf("text/*", "text/markdown", "text/html", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream")) }
        KitBridge {
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = PageGutter, end = PageGutter, top = WriterDims.gap, bottom = WriterDims.fabClearance),
                    verticalArrangement = Arrangement.spacedBy(WriterDims.gap + WriterDims.half),
                ) {
                    item(key = "header") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    stringResource(R.string.doc_list_title),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                if (docsLoaded.value && sorted.isNotEmpty()) {
                                    Text(
                                        stringResource(R.string.doc_list_summary, list.size, DocList.totalWords(list)),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            ThemeMenu()
                        }
                    }
                    item(key = "search") {
                        WriterSearchField(
                            query = query.value,
                            onQueryChange = { query.value = it; reloadDocs() },
                            placeholder = stringResource(R.string.doc_search_hint),
                            clearLabel = stringResource(R.string.action_clear),
                        )
                    }
                    item(key = "status") { StatusCard() }
                    item(key = "import") {
                        Column {
                            WriterLabel(stringResource(R.string.doc_import_title))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = WriterDims.gap),
                                horizontalArrangement = Arrangement.spacedBy(WriterDims.gap),
                            ) {
                                WriterButton(
                                    stringResource(R.string.import_file), { openFile() },
                                    Modifier.testTag("writer:import-file"), ControlKind.TONAL, Icons.Rounded.FileOpen,
                                )
                                WriterButton(
                                    stringResource(R.string.import_scan),
                                    {
                                        val dir = File(cacheDir, "ocr").apply { mkdirs() }
                                        val uri = FileProvider.getUriForFile(activity, packageName + ".ocr", File(dir, "shot.jpg"))
                                        shot = uri
                                        takePhoto.launch(uri)
                                    },
                                    Modifier.testTag("writer:import-scan"), ControlKind.TONAL, Icons.Rounded.PhotoCamera,
                                )
                                WriterButton(
                                    stringResource(R.string.import_image), { pickImage.launch("image/*") },
                                    Modifier.testTag("writer:import-image"), ControlKind.TONAL, Icons.Rounded.AddPhotoAlternate,
                                )
                            }
                        }
                    }
                    importNote.value?.let { (line, state) -> item(key = "note") { KitBridge { KitStatusBanner(line, state, tag = "import") } } }
                    if (docsLoaded.value && sorted.isNotEmpty()) {
                        item(key = "sort") {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WriterDims.gap)) {
                                listOf(
                                    DocSort.RECENT.id to R.string.sort_recent,
                                    DocSort.LONGEST.id to R.string.sort_longest,
                                    DocSort.TITLE.id to R.string.sort_title,
                                ).forEach { (id, label) ->
                                    WriterFilterChip(id, "sort", stringResource(label), docSort.value.id == id, { docSort.value = DocSort.of(id) })
                                }
                            }
                        }
                        if (tags.isNotEmpty()) {
                            item(key = "tags") {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(WriterDims.gap)) {
                                    WriterFilterChip("all", "tag", stringResource(R.string.doc_filter_all), activeTag == null, { tagFilter = null })
                                    tags.forEach { t -> WriterFilterChip(t, "tag", "#$t", activeTag == t, { tagFilter = t }) }
                                }
                            }
                        }
                    }
                    when {
                        !docsLoaded.value -> item(key = "skeleton") { KitSkeletonRows(5) }
                        list.isEmpty() -> item(key = "empty") {
                            val searching = query.value.isNotBlank() || activeTag != null
                            WriterEmptyState(
                                title = stringResource(if (searching) R.string.doc_no_match_title else R.string.doc_empty_title),
                                caption = stringResource(if (searching) R.string.doc_no_match_caption else R.string.doc_empty_caption),
                            ) {
                                if (searching) {
                                    WriterButton(stringResource(R.string.action_clear), { query.value = ""; tagFilter = null; reloadDocs() }, kind = ControlKind.TONAL, icon = Icons.Rounded.Close)
                                } else {
                                    WriterButton(stringResource(R.string.doc_new), { newDoc() }, icon = Icons.Rounded.Add, large = true)
                                    WriterButton(stringResource(R.string.import_file), { openFile() }, kind = ControlKind.TEXT, icon = Icons.Rounded.FileOpen)
                                }
                            }
                        }
                        else -> items(list, key = { it.id }) { DocCard(it, Modifier.animateItem()) }
                    }
                }
                ExtendedFloatingActionButton(
                    onClick = { newDoc() },
                    expanded = fabExpanded,
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text(stringResource(R.string.doc_new), style = MaterialTheme.typography.labelLarge) },
                    shape = WriterDims.cardShape,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(PageGutter).testTag("writer:new"),
                )
            }
        }
    }

    @Composable
    private fun DocCard(d: DocMeta, modifier: Modifier = Modifier) {
        var menu by remember { mutableStateOf(false) }
        val minutes = DocList.readingMinutes(d.words)
        val meta = listOfNotNull(
            DateUtils.getRelativeTimeSpanString(d.modified).toString(),
            stringResource(R.string.editor_words, d.words),
            if (minutes > 0) stringResource(R.string.doc_badge_read, minutes) else null,
        ).joinToString("  ·  ")
        WriterDocCard(
            tag = d.id,
            title = d.title.ifBlank { stringResource(R.string.doc_untitled) },
            preview = d.snippet,
            meta = meta,
            tags = d.tags,
            onClick = { open(d.id) },
            modifier = modifier,
            trailing = {
                Box {
                    WriterIconButton(Icons.Rounded.MoreVert, stringResource(R.string.doc_more), { menu = true })
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.doc_share)) },
                            leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                            onClick = { menu = false; share(store.read(d.id).orEmpty()) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.doc_delete)) },
                            leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                            onClick = { menu = false; confirmDelete.value = d.id },
                        )
                    }
                }
            },
        )
    }

    // ── import: a file or an image becomes a NEW document and opens ─────

    private fun importFile(uri: Uri) {
        importNote.value = getString(R.string.import_running) to KitState.BUSY
        worker.execute {
            val name = displayName(this, uri)
            val kind = Import.kind(name, contentResolver.getType(uri))
            val bytes = runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            // Markdown and plain text keep their own characters; HTML and DOCX come through the shared box importer as text.
            val text = bytes?.let {
                if (kind == ImportKind.MD || kind == ImportKind.TXT) it.toString(Charsets.UTF_8)
                else Import.toHtml(kind, it)?.let { h -> HtmlText.htmlToText(h) }
            }
            main.post {
                when {
                    kind == ImportKind.UNSUPPORTED -> importNote.value = getString(R.string.box_load_unsupported, name) to KitState.BAD
                    text == null -> importNote.value = getString(R.string.box_load_failed, name) to KitState.BAD
                    else -> {
                        importNote.value = getString(R.string.import_done, name) to KitState.OK
                        open(store.create(text))
                    }
                }
            }
        }
    }

    private fun importImage(uri: Uri) {
        importNote.value = getString(R.string.ocr_running) to KitState.BUSY
        worker.execute {
            val bmp = OcrImage.load(this, uri)
            val a = bmp?.let { WriterRoutes.ocr(this, it) }
            main.post {
                when {
                    a == null -> importNote.value = getString(R.string.ocr_no_image) to KitState.BAD
                    !a.ok -> importNote.value = getString(R.string.ocr_failed, a.error.orEmpty()) to KitState.BAD
                    else -> {
                        importNote.value = (
                            if (a.fellBack) getString(R.string.ocr_fell_back, a.fallbackReason.orEmpty())
                            else getString(R.string.ocr_done, a.text.orEmpty().length, a.route?.id.orEmpty())
                        ) to KitState.OK
                        open(store.create(a.text.orEmpty()))
                    }
                }
            }
        }
    }

    @Composable
    private fun ThemeMenu() {
        var open by remember { mutableStateOf(false) }
        Box {
            WriterIconButton(Icons.Rounded.DarkMode, stringResource(R.string.theme_title), { open = true })
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                themes().forEach { (label, id) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { open = false; WriterTheme.set(activity, id) },
                    )
                }
            }
        }
    }

    private fun themes(): List<Pair<String, String>> = listOf(
        getString(R.string.theme_system) to WriterTheme.SYSTEM,
        getString(R.string.theme_light) to WriterTheme.LIGHT,
        getString(R.string.theme_dark) to WriterTheme.DARK,
    )

    // ── the editor ───────────────────────────────────────────────────────

    /**
     * #1532 the writing surface: a centred column of comfortable measure (core WriterGrid.MEASURE) at a 1.6 line height, the
     * rich Markdown drawing over the plain text, a floating format toolbar that slides in above the keyboard while the text
     * has focus, the four tool chips, a words / characters / reading-time line with the autosave state, and focus mode (the
     * chrome fades away and every paragraph but the current one is dimmed). Every #1457 action stays: outline, find, Listen,
     * share, export, options, configs, delete.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen() {
        val id = docId.value ?: return
        val v = field.value
        val focus = remember { FocusRequester() }
        var focused by remember { mutableStateOf(false) }
        val scheme = MaterialTheme.colorScheme
        val focusing = focusMode.value
        val spans = remember(v.text) { Markdown.spans(v.text) }
        val q = findQuery.value
        val matches = remember(v.text, q, findOpen.value) {
            if (findOpen.value && q.isNotEmpty()) Markdown.find(v.text, q).map { it until it + q.length } else emptyList()
        }
        val dim = if (focusing) Focus.dimmed(v.text, v.selection.max) else emptyList()
        val look = remember(spans, matches, scheme, dim) { MarkdownLook(spans, matches, scheme, dim) }

        // Autosave: half a second after the last change, off the main thread.
        LaunchedEffect(id, v.text) {
            saveState.value = SaveState.SAVING
            delay(SAVE_DELAY_MS)
            val text = v.text
            val ok = withContext(Dispatchers.IO) { runCatching { store.write(id, text) }.isSuccess }
            saveState.value = if (ok) SaveState.SAVED else SaveState.FAILED
            if (ok) savedAt.value = System.currentTimeMillis()
        }
        // A new, empty document opens with the caret in it and the keyboard up.
        LaunchedEffect(id) {
            if (field.value.text.isEmpty()) {
                delay(WriterScreens.FADE_OUT_MS + 50L)
                runCatching { focus.requestFocus() }
            }
        }

        val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { export(it) } }
        val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { export(it) } }
        val listening = ListenEngine.state.value.running
        var menu by remember { mutableStateOf(false) }

        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                // #1164 the title is the shared first row's; this row keeps the editor's own actions.
                AnimatedVisibility(visible = !focusing) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = WriterDims.half), verticalAlignment = Alignment.CenterVertically) {
                        WriterIconButton(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.action_back), { back() })
                        Spacer(Modifier.weight(1f))
                        WriterIconButton(Icons.Rounded.Toc, stringResource(R.string.editor_outline), { outlineOpen.value = true })
                        WriterIconButton(Icons.Rounded.Search, stringResource(R.string.editor_find), { findOpen.value = !findOpen.value })
                        WriterIconButton(Icons.Rounded.CenterFocusStrong, stringResource(R.string.editor_focus), { focusMode.value = true })
                        WriterIconButton(
                            Icons.Rounded.Mic,
                            stringResource(R.string.editor_listen),
                            { if (listening) listenOpen.value = true else requestListen() },
                            tint = if (listening) MaterialTheme.colorScheme.error else Color.Unspecified,
                        )
                        Box {
                            WriterIconButton(Icons.Rounded.MoreVert, stringResource(R.string.doc_more), { menu = true })
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_share)) },
                                    leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                                    onClick = { menu = false; share(field.value.text) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_md)) },
                                    leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                                    onClick = { menu = false; exportMd.launch(fileName(".md")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_txt)) },
                                    leadingIcon = { Icon(Icons.Rounded.FileDownload, contentDescription = null) },
                                    onClick = { menu = false; exportTxt.launch(fileName(".txt")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_options)) },
                                    leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                    onClick = { menu = false; optionsOpen.value = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_configs)) },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    onClick = { menu = false; saveNow(); openConfigs() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_delete)) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                                    onClick = { menu = false; confirmDelete.value = id },
                                )
                            }
                        }
                    }
                }
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    if (findOpen.value && !focusing) FindBar(matches)
                    report.value?.let { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = PageGutter, vertical = WriterDims.half),
                        )
                    }
                    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.TopCenter) {
                        BasicTextField(
                            value = v,
                            onValueChange = { field.value = it },
                            modifier = Modifier
                                .widthIn(max = WriterDims.measure)
                                .fillMaxSize()
                                .focusRequester(focus)
                                .onFocusChanged { focused = it.isFocused }
                                .padding(horizontal = WriterDims.section, vertical = WriterDims.gutter),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                                lineHeight = WriterGrid.LINE_HEIGHT_EM.em,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            visualTransformation = look,
                            decorationBox = { inner ->
                                Box {
                                    if (v.text.isEmpty()) {
                                        Text(
                                            stringResource(R.string.editor_placeholder),
                                            style = MaterialTheme.typography.bodyLarge.copy(lineHeight = WriterGrid.LINE_HEIGHT_EM.em),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    inner()
                                }
                            },
                        )
                    }
                    AnimatedVisibility(visible = !focusing) {
                        KitBridge {
                            val words = Markdown.words(v.text)
                            val minutes = DocList.readingMinutes(words)
                            val state = saveState.value
                            KitEditorStatus(
                                facts = listOfNotNull(
                                    stringResource(R.string.editor_words, words),
                                    stringResource(R.string.editor_chars, DocList.chars(v.text)),
                                    if (minutes > 0) stringResource(R.string.doc_badge_read, minutes) else null,
                                ),
                                saveWord = when (state) {
                                    SaveState.SAVING -> stringResource(R.string.save_saving)
                                    SaveState.FAILED -> stringResource(R.string.save_failed)
                                    SaveState.SAVED -> if (savedAt.value == 0L) stringResource(R.string.save_saved)
                                    else stringResource(R.string.save_saved_at, DateUtils.formatDateTime(activity, savedAt.value, DateUtils.FORMAT_SHOW_TIME))
                                },
                                saveState = when (state) {
                                    SaveState.SAVING -> KitState.BUSY
                                    SaveState.FAILED -> KitState.BAD
                                    SaveState.SAVED -> KitState.OK
                                },
                            )
                        }
                    }
                }
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    if (listening || listenOpen.value) ListenPanel()
                    AnimatedVisibility(
                        visible = focused,
                        enter = fadeIn(tween(WriterScreens.FADE_IN_MS)) + slideInVertically(tween(WriterScreens.FADE_IN_MS)) { it / 2 },
                        exit = fadeOut(tween(WriterScreens.FADE_OUT_MS)) + slideOutVertically(tween(WriterScreens.FADE_OUT_MS)) { it / 2 },
                    ) { FormatBar() }
                    AnimatedVisibility(visible = !focusing) { ToolBar() }
                }
            }
            if (focusing) {
                WriterChip(
                    "focus-exit", Icons.Rounded.FullscreenExit, stringResource(R.string.editor_focus_exit), { focusMode.value = false },
                    Modifier.align(Alignment.TopEnd).padding(WriterDims.gap), kind = ControlKind.TONAL, showLabel = true,
                )
            }
        }
        if (outlineOpen.value) OutlineSheet(v.text, focus)
        if (optionsOpen.value) OptionsSheet()
    }

    /** Apply a core Markdown action to the selection. */
    private fun format(action: (String, Int, Int) -> Edit) {
        val v = field.value
        field.value = action(v.text, v.selection.start, v.selection.end).asValue()
    }

    /**
     * #1532 the format strip: one chip per core [FormatAction], grouped as the enum declares them. The behaviour behind each
     * chip is FormatAction.apply, which core's FormatTest holds.
     */
    @Composable
    private fun FormatBar() {
        WriterBar {
            FormatAction.GROUPS.forEachIndexed { i, group ->
                if (i > 0) BarDivider()
                group.forEach { a ->
                    WriterChip(a.id, iconFor(a), getString(labelFor(a)), { format { t, s, e -> a.apply(t, s, e) } })
                }
            }
        }
    }

    /** Exhaustive over the enum WITHOUT an `else`, so adding a chip is a compile error here. */
    private fun iconFor(a: FormatAction): ImageVector = when (a) {
        FormatAction.H1 -> Icons.Rounded.Title
        FormatAction.H2 -> Icons.Rounded.FormatSize
        FormatAction.BOLD -> Icons.Rounded.FormatBold
        FormatAction.ITALIC -> Icons.Rounded.FormatItalic
        FormatAction.CODE -> Icons.Rounded.Code
        FormatAction.LINK -> Icons.Rounded.Link
        FormatAction.BULLETS -> Icons.AutoMirrored.Rounded.FormatListBulleted
        FormatAction.NUMBERED -> Icons.Rounded.FormatListNumbered
        FormatAction.CHECKLIST -> Icons.Rounded.Checklist
        FormatAction.QUOTE -> Icons.Rounded.FormatQuote
    }

    @StringRes
    private fun labelFor(a: FormatAction): Int = when (a) {
        FormatAction.H1 -> R.string.fmt_h1
        FormatAction.H2 -> R.string.fmt_h2
        FormatAction.BOLD -> R.string.fmt_bold
        FormatAction.ITALIC -> R.string.fmt_italic
        FormatAction.CODE -> R.string.fmt_code
        FormatAction.LINK -> R.string.fmt_link
        FormatAction.BULLETS -> R.string.fmt_bullets
        FormatAction.NUMBERED -> R.string.fmt_numbered
        FormatAction.CHECKLIST -> R.string.fmt_checklist
        FormatAction.QUOTE -> R.string.fmt_quote
    }

    /**
     * The four tools, built from the enum so a tool added there gets a chip here: Enhance is the filled accent chip, the
     * others tonal, Options is text. Disabled while a run is in flight; the runner still refuses a second call with a
     * sentence if one arrives.
     */
    @Composable
    private fun ToolBar() {
        WriterBar {
            WriterTool.values().forEach { tool ->
                WriterChip(
                    tool.name, iconFor(tool), getString(tool.label), { start(tool) },
                    kind = if (tool == WriterTool.ENHANCE) ControlKind.PRIMARY else ControlKind.TONAL,
                    enabled = !busy.value,
                    showLabel = true,
                )
            }
            WriterChip("options", Icons.Rounded.Tune, getString(R.string.editor_options), { optionsOpen.value = true }, showLabel = true)
            if (busy.value) CircularProgressIndicator(Modifier.padding(horizontal = WriterDims.gap).size(WriterDims.gutter + WriterDims.gap), strokeWidth = 2.dp)
        }
    }

    /** Exhaustive over the enum WITHOUT an `else`, so adding a tool is a compile error here. */
    private fun iconFor(tool: WriterTool): ImageVector = when (tool) {
        WriterTool.ENHANCE -> Icons.Rounded.AutoAwesome
        WriterTool.GRAMMAR -> Icons.Rounded.Spellcheck
        WriterTool.SUMMARY -> Icons.Rounded.Summarize
        WriterTool.TRANSLATE -> Icons.Rounded.Translate
    }

    @Composable
    private fun FindBar(matches: List<IntRange>) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = findQuery.value,
                onValueChange = { findQuery.value = it; findIndex.value = 0 },
                singleLine = true,
                shape = WriterDims.cardShape,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.editor_find_hint)) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            )
            Text(
                stringResource(R.string.find_count, if (matches.isEmpty()) 0 else findIndex.value % matches.size + 1, matches.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { step(matches, -1) }, enabled = matches.isNotEmpty(), modifier = Modifier.size(WriterDims.touch)) {
                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous))
            }
            IconButton(onClick = { step(matches, 1) }, enabled = matches.isNotEmpty(), modifier = Modifier.size(WriterDims.touch)) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next))
            }
            IconButton(onClick = { findOpen.value = false; findQuery.value = "" }, modifier = Modifier.size(WriterDims.touch)) {
                Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
            }
        }
    }

    /** Select the next or previous match, wrapping round. */
    private fun step(matches: List<IntRange>, by: Int) {
        val n = matches.size
        if (n == 0) return
        findIndex.value = ((findIndex.value + by) % n + n) % n
        val m = matches[findIndex.value]
        field.value = field.value.copy(selection = TextRange(m.first, m.last + 1))
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OutlineSheet(text: String, focus: FocusRequester) {
        val heads = remember(text) { Markdown.outline(text) }
        ModalBottomSheet(onDismissRequest = { outlineOpen.value = false }) {
            Text(
                stringResource(R.string.editor_outline),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
            )
            if (heads.isEmpty()) NoteText(stringResource(R.string.outline_empty))
            LazyColumn(contentPadding = PaddingValues(bottom = 32.dp)) {
                items(heads) { h ->
                    Text(
                        h.title,
                        style = if (h.level == 1) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                outlineOpen.value = false
                                field.value = field.value.copy(selection = TextRange(h.offset))
                                runCatching { focus.requestFocus() }
                            }
                            .padding(start = PageGutter + ((h.level - 1) * 16).dp, end = PageGutter, top = 12.dp, bottom = 12.dp),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun OptionsSheet() {
        ModalBottomSheet(onDismissRequest = { optionsOpen.value = false }) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
                OptionsSection()
                Spacer(Modifier.height(BlockGap))
                ModelSection()
            }
        }
    }

    // ── Listen ───────────────────────────────────────────────────────────

    private fun requestListen(dictation: Boolean = false) {
        listenOpen.value = true
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO to "microphone")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS to "notifications")
        }
        val missing = wanted.filter { checkSelfPermission(it.first) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) { startListen(dictation); return }
        // #1515 P7: granted in Configs > Perms (the microphone / notifications rows), never asked from here.
        if (missing.any { it.first == Manifest.permission.RECORD_AUDIO }) say(getString(R.string.listen_no_mic))
        com.diegonmarcos.superapp.perms.PermsLink.open(this, "listen", missing.map { it.second })
    }

    private fun startListen(dictation: Boolean = false) {
        val id = if (dictation) dictationDoc ?: store.create("").also { dictationDoc = it } else docId.value ?: return
        saveNow()
        listenOpen.value = true
        ListenService.start(this, id)
    }

    @Composable
    private fun ListenPanel(dictation: Boolean = false) {
        val s = ListenEngine.state.value
        listenPrefs.value.let { }
        val translateOn = WriterRoutes.listenTranslate(this)
        Card(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Mic,
                        contentDescription = null,
                        tint = if (s.running && !s.paused) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(
                            when {
                                s.running && s.paused -> R.string.listen_paused
                                s.running -> R.string.listen_listening
                                else -> R.string.listen_title
                            },
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (s.running) {
                        IconButton(onClick = { ListenEngine.pause(!s.paused) }) {
                            Icon(
                                if (s.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                contentDescription = stringResource(if (s.paused) R.string.listen_resume else R.string.listen_pause),
                            )
                        }
                        IconButton(onClick = { ListenService.stop(activity) }) {
                            Icon(Icons.Rounded.Stop, contentDescription = stringResource(R.string.listen_stop))
                        }
                    } else {
                        WriterButton(stringResource(R.string.listen_start), { requestListen(dictation) }, kind = ControlKind.TONAL, icon = Icons.Rounded.Mic)
                        IconButton(onClick = { listenOpen.value = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    }
                }
                if (s.running) LinearProgressIndicator(progress = { (s.level * 4f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                if (s.partial.isNotBlank()) {
                    Text(s.partial, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, maxLines = 3)
                }
                Text(listenRouteLine(s), style = MaterialTheme.typography.labelSmall)
                s.error?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.listen_translate), modifier = Modifier.weight(1f))
                    Switch(checked = translateOn, onCheckedChange = {
                        WriterPrefs.putFlag(activity, WriterRoutes.KEY_LISTEN_TRANSLATE, it)
                        listenPrefs.value++
                    })
                }
                if (translateOn) {
                    ChoiceRow(
                        stringResource(R.string.routes_listen_target),
                        null,
                        WriterRegistry.languages.filter { WriterRoutes.tagOf(it.id) != null }.map { it.label to it.id },
                        WriterRoutes.listenTarget(activity),
                        WriterRoutes.translation.optString("default_target", "english"),
                    ) { WriterPrefs.put(activity, WriterRoutes.KEY_LISTEN_TARGET, it); listenPrefs.value++ }
                    ChoiceRow(
                        stringResource(R.string.routes_listen_mode),
                        null,
                        listOf(
                            stringResource(R.string.routes_mode_alongside) to "alongside",
                            stringResource(R.string.routes_mode_instead) to "instead",
                        ),
                        WriterRoutes.listenMode(activity),
                        "alongside",
                    ) { WriterPrefs.put(activity, WriterRoutes.KEY_LISTEN_MODE, it); listenPrefs.value++ }
                }
            }
        }
    }

    /** Which route was asked for, which one answered last, and why it fell back. */
    private fun listenRouteLine(s: ListenEngine.Status): String {
        val last = s.lastRoute?.let { routeName(it) } ?: getString(R.string.listen_route_none)
        val fell = s.fallbackReason?.let { " " + getString(R.string.listen_fell_back, it) }.orEmpty()
        return getString(R.string.listen_route_line, routeName(s.requested), last) + fell + " · " + getString(R.string.listen_segments, s.segments)
    }

    // ── #1418 the four tool tabs ─────────────────────────────────────────
    //
    // Each is an input box, the tools, a run button and an output box. Every run goes through
    // WriterToolRunner, i.e. the keyboard's engines, AI routing, models and prompts over the shared
    // ITextTools binder (libs:text-tools) — the same path as the editor's tools, no second copy.

    @Composable
    private fun ToolTab(
        input: BoxState,
        inputLabel: String,
        output: BoxState,
        outputLabel: String,
        runLabel: String,
        onRun: () -> Unit,
        tools: @Composable () -> Unit,
    ) {
        val io = rememberBoxIo(this, worker) { tabNote.value = it }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PageGutter),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            WriterBox(inputLabel, input, io, input = true)
            tools()
            WriterButton(
                if (tabBusy.value) stringResource(R.string.run_running) else runLabel, onRun, Modifier.fillMaxWidth(),
                icon = Icons.Rounded.AutoAwesome, enabled = !tabBusy.value, large = true,
            )
            if (tabBusy.value) LinearProgressIndicator(Modifier.fillMaxWidth())
            tabNote.value?.let { line -> KitBridge { KitStatusBanner(line, if (tabBusy.value) KitState.BUSY else KitState.IDLE, tag = "tab") } }
            WriterBox(outputLabel, output, io, input = false)
            Spacer(Modifier.height(BlockGap))
        }
    }

    /** One run's outcome into the output box, or its reason into the note. */
    private fun landIn(output: BoxState): (WriterOutcome) -> Unit = { o ->
        tabBusy.value = false
        val a = o.answer
        if (o.text != null) {
            output.setText(o.text)
            tabNote.value = if (a != null && a.fellBack) getString(R.string.translator_fell_back, a.fallbackReason.orEmpty()) else null
        } else tabNote.value = o.error
    }

    private fun runTab(tool: WriterTool, input: BoxState, output: BoxState) {
        tabBusy.value = true
        runner.run(tool, input.text(), landIn(output))
    }

    @Composable
    private fun EnhancerScreen() = ToolTab(
        enhIn, stringResource(R.string.box_input), enhOut, stringResource(R.string.box_output),
        stringResource(R.string.run_enhance), { runTab(WriterTool.ENHANCE, enhIn, enhOut) },
    ) { OptionsSection() }

    @Composable
    private fun DrafterScreen() = ToolTab(
        draftIn, stringResource(R.string.box_incoming), draftOut, stringResource(R.string.box_answer),
        stringResource(R.string.run_draft), { tabBusy.value = true; runner.draft(draftIn.text(), landIn(draftOut)) },
    ) { OptionsSection() }

    @Composable
    private fun TranslatorScreen() = ToolTab(
        trIn, stringResource(R.string.box_input), trOut, stringResource(R.string.box_output),
        stringResource(R.string.run_translate), { runTab(WriterTool.TRANSLATE, trIn, trOut) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            // The Translation route (Configs > Routes): ML = the keyboard's on-device ML Kit engine
            // through libs:translate; OpenRouter = a catalogue model, falling back to ML (core Routing.run).
            ChoiceRow(
                stringResource(R.string.translator_engine),
                null,
                listOf(getString(R.string.translator_engine_ml) to Route.ML.id, getString(R.string.translator_engine_openrouter) to Route.MODEL.id),
                WriterRoutes.route(activity, WriterRoutes.Function.TRANSLATION).id,
                WriterRoutes.defaultRoute(WriterRoutes.Function.TRANSLATION).id,
            ) { WriterRoutes.setRoute(activity, WriterRoutes.Function.TRANSLATION, Route.of(it, Route.ML)) }
            ChoiceRow(
                stringResource(R.string.translator_language),
                null,
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(activity),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(activity, WriterPrefs.KEY_ENHANCE_LANGUAGE, it) }
        }
    }

    @Composable
    private fun GrammarScreen() = ToolTab(
        grIn, stringResource(R.string.box_input), grOut, stringResource(R.string.box_output),
        stringResource(R.string.run_grammar), { runTab(WriterTool.GRAMMAR, grIn, grOut) },
    ) { }

    /** #1418 Configs > OCR: the engine (ML on-device by default, or OpenRouter) and the vision model. */
    @Composable
    private fun OcrSection() {
        ocrGeneration.value
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.ocr_title))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    NoteText(stringResource(R.string.ocr_summary))
                    ChoiceRow(
                        stringResource(R.string.ocr_engine),
                        null,
                        listOf(getString(R.string.ocr_engine_ml) to OcrEngine.ML.id, getString(R.string.ocr_engine_openrouter) to OcrEngine.OPENROUTER.id),
                        WriterRoutes.ocrEngine(activity).id,
                        OcrEngine.DEFAULT.id,
                    ) { WriterRoutes.setOcrEngine(activity, OcrEngine.of(it)); ocrGeneration.value++ }
                    val current = WriterRoutes.ocrModel(activity)
                    val models = WriterRoutes.visionModels(activity).map { it.name to it.id }
                    ChoiceRow(
                        stringResource(R.string.ocr_model),
                        if (models.isEmpty()) stringResource(R.string.ocr_models_none) else null,
                        (if (models.any { it.second == current }) models else listOf(current to current) + models),
                        current,
                        WriterRoutes.defaultOcrModel(),
                    ) { WriterRoutes.setOcrModel(activity, it); ocrGeneration.value++ }
                    TextButton(onClick = {
                        worker.execute { WriterRoutes.fetchCatalogue(activity); main.post { ocrGeneration.value++ } }
                    }) { Text(stringResource(R.string.ocr_model_refresh)) }
                }
            }
        }
    }

    // ── configs ──────────────────────────────────────────────────────────

    /**
     * #1457 Configs, the fleet pattern: General | Perms | About (libs:ui-nav-bars' shared pages) first on the second row, then this
     * app's own pages (Appearance, Models, OCR). The ids and icons are build.json::ui.sections[settings].pages; only the localized
     * label is code.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfigsScreen() {
        val dark = WriterTheme.isDark()
        // #1630 {own tabs} | Agents, Perms, Logcat, X-Ray App, About
        val declared: List<NavPage> = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(
            WriterNav.decl.section(WriterNav.SETTINGS)?.pages.orEmpty().map { it.copy(label = configsLabel(it.id, it.label)) })
        val selected = declared.firstOrNull { it.id == configsPage.value } ?: declared.firstOrNull()
        Column(Modifier.fillMaxSize()) {
            NavBarTopSecondRow(
                pages = declared,
                dark = dark,
                selectedId = selected?.id,
                onSelect = { configsPage.value = it.id },
                underTopChrome = false,
                surface = WriterNav.decl.stripSurface(WriterNav.SETTINGS, dark),
            )
            val id = selected?.id.orEmpty()
            if (id == "general" || id == "perms" || id == "about" || com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage(id)) {
                // The shared pages draw their own scroll and colours.
                FleetConfigsPageBody(id, dark, onAppConfigs = { configsPage.value = "appearance" })
            } else {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = PageGutter),
                    verticalArrangement = Arrangement.spacedBy(BlockGap),
                ) {
                    when (id) {
                        "appearance" -> {
                            KitBridge { KitSectionHeader(stringResource(R.string.theme_title), stringResource(R.string.theme_summary)) }
                            ChoiceRow(
                                stringResource(R.string.theme_title),
                                null,
                                themes(),
                                WriterTheme.current(activity),
                                WriterTheme.SYSTEM,
                            ) { WriterTheme.set(activity, it) }
                        }
                        "models" -> { StatusCard(); ModelSection(); NoteText(stringResource(R.string.token_note)) }
                        "ocr" -> OcrSection()
                    }
                    Spacer(Modifier.height(BlockGap))
                }
            }
        }
    }

    /** The localized label of a declared Configs page; the declared English one for an id with no string. */
    private fun configsLabel(id: String, declared: String): String = when (id) {
        "general" -> getString(R.string.configs_page_general)
        "perms" -> getString(R.string.configs_page_perms)
        "about" -> getString(R.string.configs_page_about)
        "appearance" -> getString(R.string.theme_title)
        "models" -> getString(R.string.configs_page_models)
        "ocr" -> getString(R.string.ocr_title)
        else -> declared
    }

    // ── dialogs ──────────────────────────────────────────────────────────

    @Composable
    private fun ResultDialog(r: ToolResult) {
        AlertDialog(
            onDismissRequest = { result.value = null },
            title = { Text(stringResource(r.tool.label)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer { Text(r.text, style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(8.dp))
                    Text(r.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { replace(r) }) { Text(stringResource(R.string.result_replace)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { insertBelow(r) }) { Text(stringResource(R.string.result_insert)) }
                    TextButton(onClick = { copy(r.text) }) { Text(stringResource(R.string.action_copy)) }
                }
            },
        )
    }

    @Composable
    private fun DeleteDialog(id: String) {
        val title = docs.value.firstOrNull { it.id == id }?.title?.ifBlank { null }
            ?: Markdown.title(store.read(id).orEmpty()).ifBlank { stringResource(R.string.doc_untitled) }
        AlertDialog(
            onDismissRequest = { confirmDelete.value = null },
            title = { Text(stringResource(R.string.doc_delete_title)) },
            text = { Text(stringResource(R.string.doc_delete_text, title)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete.value = null; delete(id) }) { Text(stringResource(R.string.doc_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete.value = null }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    /** The answer replaces exactly the range the tool read (the selection, or the whole document). */
    private fun replace(r: ToolResult) {
        val text = field.value.text
        val from = r.from.coerceIn(0, text.length)
        val to = r.to.coerceIn(from, text.length)
        field.value = TextFieldValue(text.substring(0, from) + r.text + text.substring(to), TextRange(from, from + r.text.length))
        result.value = null
    }

    /** The answer goes after the range the tool read, as its own paragraph. */
    private fun insertBelow(r: ToolResult) {
        val text = field.value.text
        val at = r.to.coerceIn(0, text.length)
        val block = "\n\n" + r.text
        field.value = TextFieldValue(text.substring(0, at) + block + text.substring(at), TextRange(at + block.length))
        result.value = null
    }

    // ── the per-tool model choice and the options rows (unchanged) ───────

    @Composable
    private fun OptionsSection() {
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.options_title))
            ChoiceRow(
                stringResource(R.string.options_structure),
                null,
                WriterRegistry.styles.map { it.label to it.id },
                WriterPrefs.enhanceStyleId(activity),
                WriterRegistry.defaultStyle,
            ) { WriterPrefs.put(activity, WriterPrefs.KEY_ENHANCE_STYLE, it) }
            ChoiceRow(
                stringResource(R.string.options_tone),
                null,
                WriterRegistry.tones.map { it.label to it.id },
                WriterPrefs.enhanceToneId(activity),
                WriterRegistry.defaultTone,
            ) { WriterPrefs.put(activity, WriterPrefs.KEY_ENHANCE_TONE, it) }
            ChoiceRow(
                stringResource(R.string.options_size),
                null,
                WriterRegistry.lengths.map { it.label to it.id },
                WriterPrefs.enhanceLengthId(activity),
                WriterRegistry.defaultLength,
            ) { WriterPrefs.put(activity, WriterPrefs.KEY_ENHANCE_LENGTH, it) }
            ChoiceRow(
                stringResource(R.string.options_language),
                null,
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(activity),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(activity, WriterPrefs.KEY_ENHANCE_LANGUAGE, it) }
        }
    }

    @Composable
    private fun ModelSection() {
        val provider = WriterRegistry.provider(WriterPrefs.providerId(this))
        Column(Modifier.fillMaxWidth()) {
            SectionHeader(stringResource(R.string.models_heading))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    NoteText(stringResource(R.string.models_note))
                    ChoiceRow(
                        title = stringResource(R.string.provider_label),
                        summary = null,
                        items = WriterRegistry.providers.map { it.label to it.id },
                        current = WriterPrefs.providerId(activity),
                        fallback = WriterRegistry.defaultProvider,
                    ) {
                        WriterPrefs.put(activity, WriterPrefs.KEY_PROVIDER, it)
                        // The model lists belong to the provider, so the rows below are rebuilt
                        // rather than left showing the previous provider's ids — which would offer
                        // the owner a model this provider has never heard of and fail at call
                        // time. Recomposition does that here; the old screen removed the views by
                        // hand and added them back.
                        providerGeneration.value++
                        refreshStatus()
                    }

                    // Read so the rows below recompose when the provider changes.
                    providerGeneration.value
                    WriterTool.values().filter { it.usesModel }.forEach { tool ->
                        ChoiceRow(
                            title = stringResource(R.string.model_for_tool, stringResource(tool.label)),
                            summary = null,
                            items = provider.models.map { it.name to it.id },
                            current = WriterPrefs.modelFor(activity, tool, provider.id),
                            fallback = provider.defaultModel,
                        ) { WriterPrefs.putToolModel(activity, tool, provider.id, it) }
                    }
                }
            }
        }
    }

    /** Bumped when the provider changes, to recompose the model rows over the new provider. */
    private val providerGeneration: MutableState<Int> = mutableStateOf(0)
    // ── the five configuration pages ─────────────────────────────────────

    /**
     * THE CONFIGURATION PAGES. Each opens an activity IN THIS APPLICATION over THIS APPLICATION'S
     * preference file. None of them is an Intent into Cloud Keyboard's settings, and none of them
     * reads a value the keyboard wrote — which is the difference between the pages the owner asked
     * for and the pages task 209 delivered. #800 added the fifth, Routes.
     *
     * ONE LIST WITH FOUR FIELDS, not a list of names beside a parallel list of icons: a page and
     * its icon and its description cannot fall out of step if there is nowhere for them to drift
     * apart, and adding a page is one entry.
     */
    private class Page(
        @StringRes val label: Int,
        @StringRes val summary: Int,
        val icon: ImageVector,
        val screen: Class<out Activity>,
    )

    /**
     * #868 The Tools section: its declared pages (build.json ui.sections[tools].pages) are a
     * NavBarTopSecondRow strip (libs:ui-nav-bars), and the selected page is the card that opens it. The five
     * used to be a column of cards under Configs; they are the app's tool pages, so they have a
     * section of their own. Which declared id a [Page] is comes from its activity, WriterNav.pageId.
     */
    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ToolsScreen() {
        run {
            Column(Modifier.fillMaxSize()) {
                val tools = listOf(
                    Page(
                        R.string.settings_screen_enhance,
                        R.string.settings_screen_enhance_summary,
                        Icons.Rounded.AutoAwesome,
                        activityClass("TextEnhanceActivity"),
                    ),
                    Page(
                        R.string.settings_screen_translation,
                        R.string.settings_screen_translation_summary,
                        Icons.Rounded.Translate,
                        activityClass("TranslationActivity"),
                    ),
                    Page(
                        R.string.settings_screen_grammar,
                        R.string.settings_screen_grammar_summary,
                        Icons.Rounded.Spellcheck,
                        activityClass("GrammarCheckActivity"),
                    ),
                    Page(
                        R.string.settings_screen_ai_routing,
                        R.string.settings_screen_ai_routing_summary,
                        Icons.Rounded.AltRoute,
                        activityClass("AiRoutingActivity"),
                    ),
                    Page(
                        R.string.settings_screen_routes,
                        R.string.settings_screen_routes_summary,
                        Icons.Rounded.Hearing,
                        activityClass("RoutesActivity"),
                    ),
                )
                val declared: List<NavPage> = WriterNav.decl.section(WriterNav.TOOLS)?.pages.orEmpty().map { p ->
                    tools.firstOrNull { WriterNav.pageId(it.screen) == p.id }?.let { p.copy(label = stringResource(it.label)) } ?: p
                }
                val selected = declared.firstOrNull { it.id == toolsPage.value } ?: declared.firstOrNull()
                NavBarTopSecondRow(
                    pages = declared,
                    dark = WriterTheme.isDark(),
                    selectedId = selected?.id,
                    onSelect = { toolsPage.value = it.id },
                    underTopChrome = false,
                    // build.json declares Tools' background "theme": the lib's light strip in light mode.
                    surface = WriterNav.decl.stripSurface(WriterNav.TOOLS, WriterTheme.isDark()),
                )
                Column(Modifier.fillMaxWidth().padding(horizontal = PageGutter)) {
                    tools.filter { WriterNav.pageId(it.screen) == selected?.id }.forEach { page ->
                    FeatureCard(
                        icon = page.icon,
                        title = stringResource(page.label),
                        summary = stringResource(page.summary),
                        onClick = { startActivity(Intent(activity, page.screen)) },
                    )
                    }
                }
            }
        }
    }

    // ── status ───────────────────────────────────────────────────────────────
    /**
     * Who is actually answering, on a card. THE ICON IS DERIVED FROM THE STATE: nothing installed is
     * an install, silent or too old is an update in the Store (the card's button), a named peer is
     * nothing to repair. While the probe runs the card shows a spinner.
     */
    @Composable
    private fun StatusCard() {
        val line = status.value
        KitBridge {
            KitStatusBanner(
                text = line ?: stringResource(R.string.status_checking),
                state = if (line == null) KitState.BUSY else if (servingAppNamed.value) KitState.OK else KitState.WARN,
                tag = "serving",
                action = if (line != null && storeRepair.value) {
                    KitAction(stringResource(R.string.action_store_update), "writer:store-update") { ServingApp.openStore(activity) }
                } else null,
            )
        }
    }
    /** True once the probe has named a peer; drives the status card's icon and its colour. */
    private val servingAppNamed: MutableState<Boolean> = mutableStateOf(false)

    /** True when the Store can repair what the probe found; shows the card's one-tap button. */
    private val storeRepair: MutableState<Boolean> = mutableStateOf(false)

    // ── status ───────────────────────────────────────────────────────────

    /**
     * Who is actually answering, named from THEIR reply and not from a constant here — see
     * [ServingApp.probe], which waits for the bind before it asks (#800). Every call BLOCKS, so all
     * of it runs on [worker].
     */
    private fun refreshStatus() {
        worker.execute {
            val serving = ServingApp.probe(this, runner)
            val provider = if (serving.state == ServingApp.State.OK) runner.providerLabel() else null
            val line = when (serving.state) {
                ServingApp.State.NONE -> getString(R.string.status_no_serving_app)
                ServingApp.State.SILENT -> getString(
                    R.string.status_serving_app_silent,
                    serving.label,
                    (ServingApp.bindWaitMs / 1000).toInt(),
                )
                ServingApp.State.TOO_OLD -> getString(
                    R.string.status_serving_app_too_old,
                    serving.label,
                    serving.version ?: "?",
                )
                ServingApp.State.OK -> getString(
                    R.string.status_served_by,
                    serving.packageName,
                    provider ?: getString(R.string.status_provider_unknown),
                )
            }
            main.post {
                servingAppNamed.value = serving.state == ServingApp.State.OK
                storeRepair.value = serving.needsStore
                status.value = line
            }
        }
    }
    // ── running a tool ───────────────────────────────────────────────────

    /**
     * The range of the document a tool reads, and its answer replaces.
     *
     * Text Enhance follows "Qué se mejora" on the Text Enhancements page: the whole document, only
     * the selection ("selection" with nothing selected sends nothing, and run() answers that with
     * its empty-input sentence), or auto (the selection if there is one, else everything). The
     * other tools work on the selection when there is one and on the whole document otherwise.
     * Selection start/end are NOT ordered — dragging right-to-left puts start after end.
     */
    private fun rangeFor(tool: WriterTool, v: TextFieldValue): Pair<Int, Int> {
        val n = v.text.length
        val from = minOf(v.selection.start, v.selection.end).coerceIn(0, n)
        val to = maxOf(v.selection.start, v.selection.end).coerceIn(0, n)
        val hasSelection = from < to
        if (tool != WriterTool.ENHANCE) return if (hasSelection) from to to else 0 to n
        return when (WriterPrefs.enhanceScope(this)) {
            WriterPrefs.SCOPE_FIELD -> 0 to n
            WriterPrefs.SCOPE_SELECTION -> from to to
            else -> if (hasSelection) from to to else 0 to n
        }
    }

    private fun start(tool: WriterTool) {
        val v = field.value
        val (from, to) = rangeFor(tool, v)
        busy.value = true
        say(getString(R.string.working, getString(tool.label)))
        runner.run(tool, v.text.substring(from, to)) { outcome ->
            busy.value = false
            val produced = outcome.text
            if (produced != null) {
                val a = outcome.answer
                val note = when {
                    a == null -> getString(R.string.done, getString(outcome.tool.label))
                    a.fellBack -> getString(R.string.route_fell_back, routeName(a.route), a.fallbackReason.orEmpty())
                    else -> getString(R.string.route_answered_by, routeName(a.route))
                }
                say(note)
                result.value = ToolResult(tool, produced, note, from, to)
            } else {
                // The engine's own reason, verbatim. A generic apology in its place is how a
                // provider outage, a missing key and an empty field become one unreadable state.
                say(outcome.error ?: getString(R.string.run_no_reason))
            }
        }
    }

    private fun routeName(r: Route?): String = getString(if (r == Route.ML) R.string.route_ml else R.string.route_model)

    private fun say(line: String) {
        report.value = line
    }

    private fun copy(text: String) {
        if (text.isBlank()) {
            say(getString(R.string.nothing_to_copy))
            return
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.writer_surface_name), text))
        Toast.makeText(this, R.string.copied, Toast.LENGTH_SHORT).show()
    }

    // ── share and export ─────────────────────────────────────────────────

    private fun share(text: String) {
        if (text.isBlank()) {
            say(getString(R.string.nothing_to_copy))
            return
        }
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, Markdown.title(text))
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, getString(R.string.doc_share)))
    }

    private fun fileName(ext: String): String =
        Markdown.title(field.value.text).ifBlank { getString(R.string.doc_untitled) }
            .replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().take(60).ifBlank { "document" } + ext

    private fun export(uri: Uri) {
        val text = field.value.text
        runCatching { contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: error("no stream") }
            .onSuccess { say(getString(R.string.exported)) }
            .onFailure { say(getString(R.string.export_failed, it.message ?: it.javaClass.simpleName)) }
    }

    init {
        activity.lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> onStart()
                Lifecycle.Event.ON_RESUME -> onResume()
                Lifecycle.Event.ON_STOP -> onStop()
                else -> Unit
            }
        })
    }

    companion object {
        private const val SAVE_DELAY_MS = 500L

        /** The ui.sections id a tool page's bottom nav asks the main screen to open. */
        const val EXTRA_SECTION = "section"
        const val EXTRA_PAGE = "page"

        // #1652 the addressable pages ([Page]). The five bottom-nav pages use build.json::ui.bottom_nav's own ids.
        const val PAGE_ENHANCER = WriterTabs.ENHANCER
        const val PAGE_DRAFTER = WriterTabs.DRAFTER
        const val PAGE_HOME = WriterTabs.HOME
        const val PAGE_TRANSLATOR = WriterTabs.TRANSLATOR
        const val PAGE_GRAMMAR = WriterTabs.GRAMMAR
        const val PAGE_EDITOR = "writer"
        const val PAGE_TOOLS = "tools"
        const val PAGE_CONFIGS = "settings"
        const val PAGE_TOOL_ENHANCE = "tools.enhance"
        const val PAGE_TOOL_TRANSLATION = "tools.translation"
        const val PAGE_TOOL_GRAMMAR = "tools.grammar"
        const val PAGE_TOOL_AI_ROUTING = "tools.ai_routing"
        const val PAGE_TOOL_ROUTES = "tools.routes"
        /** Voice transcription: the Listen (dictation) panel with its transcript. */
        const val PAGE_DICTATION = "dictation"
        /** The user Dictionary: cloud-writer has none of its own, so the host supplies it through `hostPages`. */
        const val PAGE_DICTIONARY = "dictionary"

        /** The former bottom-nav pages, in bar order (core WriterTabs.ORDER). */
        val BOTTOM_NAV_PAGES: List<String> = WriterTabs.ORDER
        /** The Tools pages, in the order build.json::ui.sections[tools].pages declares them. */
        val TOOLS_PAGES: List<String> = listOf(PAGE_TOOL_ENHANCE, PAGE_TOOL_TRANSLATION, PAGE_TOOL_GRAMMAR, PAGE_TOOL_AI_ROUTING, PAGE_TOOL_ROUTES)
        val ALL_PAGES: List<String> = BOTTOM_NAV_PAGES + TOOLS_PAGES + listOf(PAGE_EDITOR, PAGE_TOOLS, PAGE_CONFIGS, PAGE_DICTATION)

        /** The tool-page activity shells live in the host app (it applies the inset); the lib reaches them by their manifest name. */
        private fun activityClass(simple: String): Class<out Activity> =
            Class.forName("com.diegonmarcos.cloudwriter.$simple").asSubclass(Activity::class.java)

        /** The activity that hosts the app: cloud-writer's MainActivity, or the host application's launcher where there is none. */
        fun mainIntent(context: Context): Intent {
            val own = Intent().setClassName(context.packageName, "com.diegonmarcos.cloudwriter.MainActivity")
            if (own.resolveActivity(context.packageManager) != null) return own
            return context.packageManager.getLaunchIntentForPackage(context.packageName) ?: own
        }
    }
}
