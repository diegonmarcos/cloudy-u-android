package com.diegonmarcos.cloudwriter

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
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AltRoute
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Spellcheck
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Summarize
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.filled.Toc
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Tune
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
import com.diegonmarcos.cloudwriter.core.DocMeta
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
import com.diegonmarcos.superapp.uikit.KitEmptyState
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

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
class MainActivity : AppCompatActivity() {

    private enum class Screen { DOCS, EDITOR, CONFIGS, TOOLS, ENHANCER, DRAFTER, TRANSLATOR, GRAMMAR }

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

    private lateinit var runner: WriterToolRunner
    private lateinit var store: DocStore

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        runner = WriterToolRunner(this)
        store = DocStore(ListenEngine.docsDir(this))

        adoptSharedText(intent)
        openRequestedSection(intent)

        setContent {
            CloudWriterTheme {
                Surface(color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequestedSection(intent)
    }

    /** #1164 a bottom-nav item pressed on a tool page (WriterSettingsActivity) asks for its section; the editor is reopened by its own item only. */
    private fun openRequestedSection(from: Intent?) {
        val id = from?.getStringExtra(EXTRA_SECTION) ?: return
        from.removeExtra(EXTRA_SECTION)
        if (id != WriterNav.WRITER) goSection(id)
    }

    override fun onStart() {
        super.onStart()
        // Listen may have written into the open document's file while the screen was off.
        if (screen.value == Screen.EDITOR) docId.value?.let { id -> store.read(id)?.let { field.value = TextFieldValue(it, TextRange(it.length)) } }
        attachSink()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        reloadDocs()
    }

    override fun onStop() {
        saveNow()
        // From here on Listen appends to the document FILE: a stopped activity recomposes nothing.
        ListenEngine.sink = null
        super.onStop()
    }

    /**
     * Text shared in from another application, or selected in one and sent here by PROCESS_TEXT,
     * becomes a NEW document and opens in the editor. Read in onCreate: this activity uses the
     * default launch mode, so a share starts a fresh instance and its intent arrives here.
     */
    private fun adoptSharedText(from: Intent?) {
        if (from == null) return
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
        screen.value = Screen.EDITOR
        attachSink()
    }

    private fun newDoc() = open(store.create(""))

    private fun back() {
        when (screen.value) {
            Screen.EDITOR -> {
                saveNow()
                ListenEngine.sink = null
                screen.value = Screen.DOCS
                docId.value = null
                reloadDocs()
            }
            Screen.CONFIGS -> screen.value = if (configsReturn.value == Screen.EDITOR && docId.value != null) Screen.EDITOR else Screen.DOCS
            Screen.TOOLS, Screen.ENHANCER, Screen.DRAFTER, Screen.TRANSLATOR, Screen.GRAMMAR -> screen.value = Screen.DOCS
            Screen.DOCS -> finish()
        }
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
            main.post { docs.value = list }
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
            Screen.DOCS -> stringResource(R.string.app_name)
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
            hierarchy = section?.let { titleHierarchy(it, if (current == Screen.TOOLS) toolsPage.value.ifBlank { null } else null) }.orEmpty(),
            onNavigate = { e -> goSection(e.sectionId); if (e.depth > 0 && e.sectionId == WriterNav.TOOLS) toolsPage.value = e.id },
            onAccount = rememberAccountLauncher(),
            onConfigs = { if (screen.value != Screen.CONFIGS) { saveNow(); openConfigs() } },
            onAi = rememberSuperAppAiLauncher(),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
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
                when (current) {
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DocsScreen() {
        Box(Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = PageGutter),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query.value,
                    onValueChange = { query.value = it; reloadDocs() },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.doc_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.value.isNotEmpty()) {
                            IconButton(onClick = { query.value = ""; reloadDocs() }) {
                                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_clear))
                            }
                        }
                    },
                )
                    ThemeMenu()
                }
                StatusCard()
                val list = docs.value
                if (list.isEmpty()) {
                    val searching = query.value.isNotBlank()
                    KitBridge {
                        KitEmptyState(
                            title = stringResource(if (searching) R.string.doc_no_match_title else R.string.doc_empty_title),
                            caption = stringResource(if (searching) R.string.doc_no_match_caption else R.string.doc_empty_caption),
                        )
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 96.dp),
                    ) {
                        items(list, key = { it.id }) { DocCard(it) }
                    }
                }
            }
            ExtendedFloatingActionButton(
                text = { Text(stringResource(R.string.doc_new)) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                onClick = { newDoc() },
                modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DocCard(d: DocMeta) {
        var menu by remember { mutableStateOf(false) }
        Card(onClick = { open(d.id) }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = PageGutter, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        d.title.ifBlank { stringResource(R.string.doc_untitled) },
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (d.snippet.isNotBlank()) {
                        Text(
                            d.snippet,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        stringResource(R.string.doc_meta, DateUtils.getRelativeTimeSpanString(d.modified).toString(), d.words),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Box {
                    IconButton(onClick = { menu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.doc_more))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.doc_share)) },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { menu = false; share(store.read(d.id).orEmpty()) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.doc_delete)) },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { menu = false; confirmDelete.value = d.id },
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ThemeMenu() {
        var open by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { open = true }) {
                Icon(Icons.Filled.DarkMode, contentDescription = stringResource(R.string.theme_title))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                themes().forEach { (label, id) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = { open = false; WriterTheme.set(this@MainActivity, id) },
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

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun EditorScreen() {
        val id = docId.value ?: return
        val v = field.value
        val focus = remember { FocusRequester() }
        val scheme = MaterialTheme.colorScheme
        val spans = remember(v.text) { Markdown.spans(v.text) }
        val q = findQuery.value
        val matches = remember(v.text, q, findOpen.value) {
            if (findOpen.value && q.isNotEmpty()) Markdown.find(v.text, q).map { it until it + q.length } else emptyList()
        }
        val look = remember(spans, matches, scheme) { MarkdownLook(spans, matches, scheme) }

        // Autosave: half a second after the last change, off the main thread.
        LaunchedEffect(id, v.text) {
            delay(SAVE_DELAY_MS)
            val text = v.text
            withContext(Dispatchers.IO) { store.write(id, text) }
        }

        val exportMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> uri?.let { export(it) } }
        val exportTxt = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri -> uri?.let { export(it) } }
        val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) startListen() else say(getString(R.string.listen_no_mic))
        }
        val listening = ListenEngine.state.value.running
        var menu by remember { mutableStateOf(false) }

        // #1164 the title is the shared first row's; this row keeps the editor's own actions (back, outline, find, Listen, more).
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
                Spacer(Modifier.weight(1f))
                        IconButton(onClick = { outlineOpen.value = true }) {
                            Icon(Icons.Filled.Toc, contentDescription = stringResource(R.string.editor_outline))
                        }
                        IconButton(onClick = { findOpen.value = !findOpen.value }) {
                            Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.editor_find))
                        }
                        IconButton(onClick = { if (listening) listenOpen.value = true else requestListen(askMic) }) {
                            Icon(
                                Icons.Filled.Mic,
                                contentDescription = stringResource(R.string.editor_listen),
                                tint = if (listening) MaterialTheme.colorScheme.error else LocalContentColor.current,
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.doc_more))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_share)) },
                                    leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                                    onClick = { menu = false; share(field.value.text) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_md)) },
                                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                    onClick = { menu = false; exportMd.launch(fileName(".md")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.export_txt)) },
                                    leadingIcon = { Icon(Icons.Filled.FileDownload, contentDescription = null) },
                                    onClick = { menu = false; exportTxt.launch(fileName(".txt")) },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.editor_options)) },
                                    leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null) },
                                    onClick = { menu = false; optionsOpen.value = true },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_configs)) },
                                    leadingIcon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                                    onClick = { menu = false; saveNow(); openConfigs() },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.doc_delete)) },
                                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                    onClick = { menu = false; confirmDelete.value = id },
                                )
                            }
                        }
            }
            Column(Modifier.fillMaxWidth().weight(1f)) {
                if (findOpen.value) FindBar(matches)
                report.value?.let { line ->
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp),
                    )
                }
                BasicTextField(
                    value = v,
                    onValueChange = { field.value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .focusRequester(focus)
                        .padding(horizontal = PageGutter, vertical = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    visualTransformation = look,
                    decorationBox = { inner ->
                        Box {
                            if (v.text.isEmpty()) {
                                Text(
                                    stringResource(R.string.editor_placeholder),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            inner()
                        }
                    },
                )
                Text(
                    stringResource(R.string.editor_words, Markdown.words(v.text)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp),
                )
            }
            Column(Modifier.navigationBarsPadding().imePadding()) {
                if (listening || listenOpen.value) ListenPanel(askMic)
                HorizontalDivider()
                FormatBar()
                ToolBar()
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

    @Composable
    private fun FormatBar() {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FormatButton(Icons.Filled.Title, R.string.fmt_h1) { format { t, s, e -> Markdown.heading(t, s, e, 1) } }
            TextButton(onClick = { format { t, s, e -> Markdown.heading(t, s, e, 2) } }) { Text(stringResource(R.string.fmt_h2)) }
            FormatButton(Icons.Filled.FormatBold, R.string.fmt_bold) { format { t, s, e -> Markdown.wrap(t, s, e, "**") } }
            FormatButton(Icons.Filled.FormatItalic, R.string.fmt_italic) { format { t, s, e -> Markdown.wrap(t, s, e, "*") } }
            FormatButton(Icons.Filled.Code, R.string.fmt_code) { format { t, s, e -> Markdown.wrap(t, s, e, "`") } }
            FormatButton(Icons.Filled.FormatListBulleted, R.string.fmt_bullets) { format { t, s, e -> Markdown.prefixLines(t, s, e, "- ") } }
            FormatButton(Icons.Filled.FormatListNumbered, R.string.fmt_numbered) { format { t, s, e -> Markdown.prefixLines(t, s, e, "1. ") } }
            FormatButton(Icons.Filled.FormatQuote, R.string.fmt_quote) { format { t, s, e -> Markdown.prefixLines(t, s, e, "> ") } }
        }
    }

    @Composable
    private fun FormatButton(icon: ImageVector, @StringRes label: Int, onClick: () -> Unit) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = stringResource(label)) }
    }

    /**
     * The four tools, built from the enum so a tool added there gets a chip here. Disabled while a
     * run is in flight; the runner still refuses a second call with a sentence if one arrives.
     */
    @Composable
    private fun ToolBar() {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WriterTool.values().forEach { tool ->
                AssistChip(
                    onClick = { start(tool) },
                    enabled = !busy.value,
                    label = { Text(stringResource(tool.label)) },
                    leadingIcon = { Icon(iconFor(tool), contentDescription = null, modifier = Modifier.size(18.dp)) },
                )
            }
            AssistChip(
                onClick = { optionsOpen.value = true },
                label = { Text(stringResource(R.string.editor_options)) },
                leadingIcon = { Icon(Icons.Filled.Tune, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
            if (busy.value) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }

    /** Exhaustive over the enum WITHOUT an `else`, so adding a tool is a compile error here. */
    private fun iconFor(tool: WriterTool): ImageVector = when (tool) {
        WriterTool.ENHANCE -> Icons.Filled.AutoAwesome
        WriterTool.GRAMMAR -> Icons.Filled.Spellcheck
        WriterTool.SUMMARY -> Icons.Filled.Summarize
        WriterTool.TRANSLATE -> Icons.Filled.Translate
    }

    @Composable
    private fun FindBar(matches: List<IntRange>) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = findQuery.value,
                onValueChange = { findQuery.value = it; findIndex.value = 0 },
                singleLine = true,
                modifier = Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.editor_find_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            Text(
                stringResource(R.string.find_count, if (matches.isEmpty()) 0 else findIndex.value % matches.size + 1, matches.size),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            IconButton(onClick = { step(matches, -1) }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.find_previous))
            }
            IconButton(onClick = { step(matches, 1) }, enabled = matches.isNotEmpty()) {
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.find_next))
            }
            IconButton(onClick = { findOpen.value = false; findQuery.value = "" }) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
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

    private fun requestListen(ask: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>) {
        listenOpen.value = true
        val wanted = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (wanted.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) startListen()
        else ask.launch(wanted.toTypedArray())
    }

    private fun startListen() {
        val id = docId.value ?: return
        saveNow()
        listenOpen.value = true
        ListenService.start(this, id)
    }

    @Composable
    private fun ListenPanel(ask: ManagedActivityResultLauncher<Array<String>, Map<String, Boolean>>) {
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
                        Icons.Filled.Mic,
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
                                if (s.paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                contentDescription = stringResource(if (s.paused) R.string.listen_resume else R.string.listen_pause),
                            )
                        }
                        IconButton(onClick = { ListenService.stop(this@MainActivity) }) {
                            Icon(Icons.Filled.Stop, contentDescription = stringResource(R.string.listen_stop))
                        }
                    } else {
                        FilledTonalButton(onClick = { requestListen(ask) }) { Text(stringResource(R.string.listen_start)) }
                        IconButton(onClick = { listenOpen.value = false }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
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
                        WriterPrefs.putFlag(this@MainActivity, WriterRoutes.KEY_LISTEN_TRANSLATE, it)
                        listenPrefs.value++
                    })
                }
                if (translateOn) {
                    ChoiceRow(
                        stringResource(R.string.routes_listen_target),
                        null,
                        WriterRegistry.languages.filter { WriterRoutes.tagOf(it.id) != null }.map { it.label to it.id },
                        WriterRoutes.listenTarget(this@MainActivity),
                        WriterRoutes.translation.optString("default_target", "english"),
                    ) { WriterPrefs.put(this@MainActivity, WriterRoutes.KEY_LISTEN_TARGET, it); listenPrefs.value++ }
                    ChoiceRow(
                        stringResource(R.string.routes_listen_mode),
                        null,
                        listOf(
                            stringResource(R.string.routes_mode_alongside) to "alongside",
                            stringResource(R.string.routes_mode_instead) to "instead",
                        ),
                        WriterRoutes.listenMode(this@MainActivity),
                        "alongside",
                    ) { WriterPrefs.put(this@MainActivity, WriterRoutes.KEY_LISTEN_MODE, it); listenPrefs.value++ }
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
            Button(onClick = onRun, enabled = !tabBusy.value, modifier = Modifier.fillMaxWidth()) {
                Text(if (tabBusy.value) stringResource(R.string.run_running) else runLabel)
            }
            if (tabBusy.value) LinearProgressIndicator(Modifier.fillMaxWidth())
            tabNote.value?.let { NoteText(it) }
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
                WriterRoutes.route(this@MainActivity, WriterRoutes.Function.TRANSLATION).id,
                WriterRoutes.defaultRoute(WriterRoutes.Function.TRANSLATION).id,
            ) { WriterRoutes.setRoute(this@MainActivity, WriterRoutes.Function.TRANSLATION, Route.of(it, Route.ML)) }
            ChoiceRow(
                stringResource(R.string.translator_language),
                null,
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(this@MainActivity),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_LANGUAGE, it) }
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
                        WriterRoutes.ocrEngine(this@MainActivity).id,
                        OcrEngine.DEFAULT.id,
                    ) { WriterRoutes.setOcrEngine(this@MainActivity, OcrEngine.of(it)); ocrGeneration.value++ }
                    val current = WriterRoutes.ocrModel(this@MainActivity)
                    val models = WriterRoutes.visionModels(this@MainActivity).map { it.name to it.id }
                    ChoiceRow(
                        stringResource(R.string.ocr_model),
                        if (models.isEmpty()) stringResource(R.string.ocr_models_none) else null,
                        (if (models.any { it.second == current }) models else listOf(current to current) + models),
                        current,
                        WriterRoutes.defaultOcrModel(),
                    ) { WriterRoutes.setOcrModel(this@MainActivity, it); ocrGeneration.value++ }
                    TextButton(onClick = {
                        worker.execute { WriterRoutes.fetchCatalogue(this@MainActivity); main.post { ocrGeneration.value++ } }
                    }) { Text(stringResource(R.string.ocr_model_refresh)) }
                }
            }
        }
    }

    // ── configs ──────────────────────────────────────────────────────────

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun ConfigsScreen() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = PageGutter),
            verticalArrangement = Arrangement.spacedBy(BlockGap),
        ) {
            StatusCard()
            KitBridge { KitSectionHeader(stringResource(R.string.theme_title), stringResource(R.string.theme_summary)) }
            ChoiceRow(
                stringResource(R.string.theme_title),
                null,
                themes(),
                WriterTheme.current(this@MainActivity),
                WriterTheme.SYSTEM,
            ) { WriterTheme.set(this@MainActivity, it) }
            ModelSection()
            OcrSection()
            NoteText(stringResource(R.string.token_note))
            Spacer(Modifier.height(BlockGap))
        }
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
                WriterPrefs.enhanceStyleId(this@MainActivity),
                WriterRegistry.defaultStyle,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_STYLE, it) }
            ChoiceRow(
                stringResource(R.string.options_tone),
                null,
                WriterRegistry.tones.map { it.label to it.id },
                WriterPrefs.enhanceToneId(this@MainActivity),
                WriterRegistry.defaultTone,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_TONE, it) }
            ChoiceRow(
                stringResource(R.string.options_size),
                null,
                WriterRegistry.lengths.map { it.label to it.id },
                WriterPrefs.enhanceLengthId(this@MainActivity),
                WriterRegistry.defaultLength,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_LENGTH, it) }
            ChoiceRow(
                stringResource(R.string.options_language),
                null,
                WriterRegistry.languages.map { it.label to it.id },
                WriterPrefs.enhanceLanguageId(this@MainActivity),
                WriterRegistry.defaultLanguage,
            ) { WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_ENHANCE_LANGUAGE, it) }
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
                        current = WriterPrefs.providerId(this@MainActivity),
                        fallback = WriterRegistry.defaultProvider,
                    ) {
                        WriterPrefs.put(this@MainActivity, WriterPrefs.KEY_PROVIDER, it)
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
                            current = WriterPrefs.modelFor(this@MainActivity, tool, provider.id),
                            fallback = provider.defaultModel,
                        ) { WriterPrefs.putToolModel(this@MainActivity, tool, provider.id, it) }
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
                        Icons.Filled.AutoAwesome,
                        TextEnhanceActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_translation,
                        R.string.settings_screen_translation_summary,
                        Icons.Filled.Translate,
                        TranslationActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_grammar,
                        R.string.settings_screen_grammar_summary,
                        Icons.Filled.Spellcheck,
                        GrammarCheckActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_ai_routing,
                        R.string.settings_screen_ai_routing_summary,
                        Icons.Filled.AltRoute,
                        AiRoutingActivity::class.java,
                    ),
                    Page(
                        R.string.settings_screen_routes,
                        R.string.settings_screen_routes_summary,
                        Icons.Filled.Hearing,
                        RoutesActivity::class.java,
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
                        onClick = { startActivity(Intent(this@MainActivity, page.screen)) },
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
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            ),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(PageGutter),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (line == null) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Icon(
                        imageVector = if (servingAppNamed.value) Icons.Filled.Cloud else Icons.Filled.CloudOff,
                        contentDescription = null,
                        tint = if (servingAppNamed.value) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(20.dp),
                    )
                }
                Spacer(Modifier.width(PageGutter))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = line ?: stringResource(R.string.status_checking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (line != null && storeRepair.value) {
                        TextButton(onClick = { ServingApp.openStore(this@MainActivity) }) {
                            Text(stringResource(R.string.action_store_update))
                        }
                    }
                }
            }
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
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), text))
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

    companion object {
        private const val SAVE_DELAY_MS = 500L

        /** The ui.sections id a tool page's bottom nav asks the main screen to open. */
        const val EXTRA_SECTION = "section"
    }
}
