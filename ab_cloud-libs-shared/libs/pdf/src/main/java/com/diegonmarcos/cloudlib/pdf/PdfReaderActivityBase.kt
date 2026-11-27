package com.diegonmarcos.cloudlib.pdf

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Toc
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Surface
import androidx.compose.ui.platform.testTag
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.Executors

/** One entry of the reader's More menu that an app adds (Drive uses [PdfReaderActivityBase.conversionTargets] instead; Notes: Open with, Attach to note). */
class ReaderAction(val label: String, val tag: String, val run: () -> Unit)

/**
 * The PDF reader screen (#577): a native activity around [PdfReaderView] and the pdfium
 * [PdfEngine], replacing the pdf.js page that used to live inside drive.html.
 *
 * It is what the manifest offers other apps for application/pdf (cloud-mail's attachment chooser
 * lands here, #458/#463) and what the Files tab opens for a PDF row (DriveActions.openPdf, #579).
 * Continuous and paged scrolling, pinch zoom, search with highlights, outline, night mode, share,
 * print and the four PDF→text conversions are all reached from this one toolbar.
 *
 * The document is opened from a file descriptor on a background thread, so a 500 MB PDF shows its
 * first page as fast as a 50 KB one: nothing here reads the file into memory.
 */
open class PdfReaderActivityBase : ComponentActivity(), PdfReaderView.Host {

    /** The app's theme around the reader (Drive: DriveTheme). */
    @Composable
    protected open fun ReaderTheme(content: @Composable () -> Unit) { MaterialTheme { Surface(Modifier.fillMaxSize()) { content() } } }

    /** The window insets of this full-screen activity, applied by the app (the shell top-inset rule keeps bar handling out of shared libs): Drive and Notes pass their bar and cutout padding. */
    protected open fun Modifier.screenInsets(): Modifier = this

    /** The app's own More-menu entries, after Share, Print and Go to page. */
    protected open fun extraActions(): List<ReaderAction> = emptyList()

    /** The PDF to text/markdown conversion targets the app offers; empty hides the Convert entry. */
    protected open val conversionTargets: List<String> = emptyList()

    /** Runs one conversion of the real file [file] into [target] next to it. */
    protected open fun runConversion(file: File, target: String): Result<File> = Result.failure(UnsupportedOperationException("no converter"))

    /** The FileProvider authority Share uses (the app's own). */
    protected open val fileAuthority: String get() = "$packageName.fileprovider"

    /** The SharedPreferences file of night mode, paged mode and the last page. */
    protected open val prefsName: String get() = "pdf-reader"

    // #1949 EDIT: the same PdfBox editor the Editor tab uses, on a private copy of the document.
    private var editing by mutableStateOf(false)
    private var editFile by mutableStateOf<File?>(null)
    private var savedOriginal = false

    /** The pdfium surface; a View kept on purpose (gestures, tiles and caches) and hosted by [ReaderScreen]. */
    private lateinit var reader: PdfReaderView

    // What the Compose chrome shows. Everything the old View bar held lives here as state.
    private var titleText by mutableStateOf("")
    private var statusText by mutableStateOf<String?>(null)
    private var pageLabel by mutableStateOf<String?>(null)
    private var searchOpen by mutableStateOf(false)
    private var searchQuery by mutableStateOf("")
    private var searchCountText by mutableStateOf("")
    private var nightOn by mutableStateOf(false)
    private var pagedOn by mutableStateOf(false)
    private var dialog by mutableStateOf<ReaderDialog?>(null)

    private sealed interface ReaderDialog {
        data class Password(val retry: Boolean) : ReaderDialog
        data class Outline(val items: List<PdfEngine.OutlineItem>) : ReaderDialog
        data object GoTo : ReaderDialog
        data object ConvertTargets : ReaderDialog
        data class Message(val text: String) : ReaderDialog
    }

    private val io = Executors.newSingleThreadExecutor()
    private val searchExec = Executors.newSingleThreadExecutor()
    private val prefs by lazy { getSharedPreferences(prefsName, Context.MODE_PRIVATE) }

    private var engine: PdfEngine? = null
    protected lateinit var source: Uri

    /** The file behind [source] when it is a real path (file://); null for a content:// hand-off. */
    protected var sourceFile: File? = null
    protected var displayName = "document.pdf"

    private val hits = ArrayList<SearchHit>()
    private var currentHit = -1
    private var searchGen = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (data == null) {
            finish()
            return
        }
        val file = if (data.scheme == "file") File(data.path ?: "") else null
        if (file != null && !isReadablePlace(file)) {
            Toast.makeText(this, "That location cannot be opened here.", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        source = data
        sourceFile = file
        displayName = file?.name ?: queryName(data) ?: "document.pdf"

        titleText = displayName
        buildUi()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (searchOpen) closeSearch() else finish()
            }
        })
        openDocument(null)
    }

    override fun onPause() {
        // Resume where the reader left off: the next open of this document lands on the same page.
        // Only for a real file: a content:// hand-off has a throwaway URI that would never match again.
        if (engine != null && sourceFile != null) {
            prefs.edit().putInt(LAST_PAGE + source, reader.currentPageIndex()).apply()
        }
        super.onPause()
    }

    override fun onDestroy() {
        searchGen++
        searchExec.shutdownNow()
        io.shutdownNow()
        if (::reader.isInitialized) reader.destroy()
        engine?.close()
        engine = null
        super.onDestroy()
    }

    // ── opening ──────────────────────────────────────────────────────────────────────────────

    private fun openDocument(password: String?) {
        showStatus("Opening…")
        io.execute {
            try {
                val opened = PdfEngine.open(applicationContext, source, password)
                val first = opened.pageSize(0)
                runOnUiThread {
                    if (isDestroyed || isFinishing) opened.close() else onOpened(opened, first)
                }
            } catch (error: PdfEngine.PasswordRequired) {
                runOnUiThread { askPassword(retry = password != null) }
            } catch (error: Throwable) {
                // Throwable, not Exception: a missing native library is an Error, and a blank
                // screen with no reason is the failure this reader exists to stop having.
                runOnUiThread { showStatus("Cannot open this PDF: " + (error.message ?: error.javaClass.simpleName)) }
            }
        }
    }

    private fun onOpened(opened: PdfEngine, first: FloatArray?) {
        if (opened.pageCount == 0 || first == null) {
            opened.close()
            showStatus("This PDF has no pages.")
            return
        }
        engine = opened
        statusText = null
        val night = prefs.getBoolean(NIGHT, false)
        val paged = prefs.getBoolean(PAGED, false)
        nightOn = night
        pagedOn = paged
        reader.open(opened, first, if (sourceFile != null) prefs.getInt(LAST_PAGE + source, 0) else 0, paged, night)
        titleText = displayName
    }

    private fun askPassword(retry: Boolean) {
        dialog = ReaderDialog.Password(retry)
    }

    private fun showStatus(text: String) {
        statusText = text
    }

    // ── host callbacks ───────────────────────────────────────────────────────────────────────

    override fun onPageChanged(page: Int, count: Int) {
        pageLabel = if (count > 1) getString(R.string.pdf_page_pill, page + 1, count) else null
    }

    override fun onWordSelected(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PDF text", text))
        Toast.makeText(this, "Copied “$text”", Toast.LENGTH_SHORT).show()
    }

    override fun onOpenLink(uri: String) {
        // A PDF is untrusted input: only links a person would expect a reader to follow.
        val parsed = Uri.parse(uri)
        val scheme = parsed.scheme?.lowercase() ?: return
        if (scheme !in SAFE_LINK_SCHEMES) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, parsed))
        } catch (error: ActivityNotFoundException) {
            Toast.makeText(this, "Nothing on this phone can open that link.", Toast.LENGTH_SHORT).show()
        }
    }

    // ── toolbar actions ──────────────────────────────────────────────────────────────────────

    private fun toggleNight() {
        val on = !reader.night
        reader.setNight(on)
        nightOn = on
        prefs.edit().putBoolean(NIGHT, on).apply()
    }

    private fun toggleMode() {
        val paged = !reader.paged
        reader.setPaged(paged)
        pagedOn = paged
        prefs.edit().putBoolean(PAGED, paged).apply()
    }

    private fun showOutline() {
        val e = engine ?: return
        io.execute {
            val items = try { e.outline() } catch (error: Exception) { emptyList() }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (items.isEmpty()) {
                    Toast.makeText(this, "This PDF has no outline.", Toast.LENGTH_SHORT).show()
                    return@runOnUiThread
                }
                dialog = ReaderDialog.Outline(items)
            }
        }
    }

    private fun askPage() {
        if (engine == null) return
        dialog = ReaderDialog.GoTo
    }

    private fun share() {
        io.execute {
            try {
                val uri = shareableUri()
                val send = Intent(Intent.ACTION_SEND)
                    .setType("application/pdf")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runOnUiThread { startActivity(Intent.createChooser(send, null)) }
            } catch (error: Exception) {
                runOnUiThread { toast("Cannot share this PDF: " + (error.message ?: "unknown error")) }
            }
        }
    }

    /**
     * A content:// URI another app may read. A file inside the provider's roots is shared in
     * place; anything else (a hand-off from another app, or a path outside those roots) goes
     * through a private copy, because the read grant we were given is not ours to pass on.
     */
    private fun shareableUri(): Uri {
        val authority = fileAuthority
        val file = sourceFile
        if (file != null) {
            try { return FileProvider.getUriForFile(this, authority, file) } catch (outside: IllegalArgumentException) { /* copy below */ }
        }
        return FileProvider.getUriForFile(this, authority, PdfEngine.spool(this, source))
    }

    /** Prints the ORIGINAL bytes through the system print service: the PDF is already the print format. */
    private fun printDocument() {
        val manager = getSystemService(Context.PRINT_SERVICE) as PrintManager
        manager.print(displayName, object : PrintDocumentAdapter() {
            override fun onLayout(
                oldAttributes: PrintAttributes?,
                newAttributes: PrintAttributes?,
                cancellationSignal: CancellationSignal?,
                callback: LayoutResultCallback?,
                extras: Bundle?
            ) {
                if (cancellationSignal?.isCanceled == true) {
                    callback?.onLayoutCancelled()
                    return
                }
                val info = PrintDocumentInfo.Builder(displayName)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                    .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
                    .build()
                callback?.onLayoutFinished(info, true)
            }

            override fun onWrite(
                pages: Array<out PageRange>?,
                destination: ParcelFileDescriptor?,
                cancellationSignal: CancellationSignal?,
                callback: WriteResultCallback?
            ) {
                try {
                    val input: InputStream = contentResolver.openInputStream(source)
                        ?: throw java.io.IOException("the document cannot be read")
                    input.use { src ->
                        FileOutputStream(destination?.fileDescriptor).use { out -> src.copyTo(out) }
                    }
                    callback?.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
                } catch (error: Exception) {
                    callback?.onWriteFailed(error.message)
                }
            }
        }, null)
    }

    private fun chooseConversion() {
        if (sourceFile == null) {
            dialog = ReaderDialog.Message(getString(R.string.pdf_convert_no_folder))
            return
        }
        dialog = ReaderDialog.ConvertTargets
    }

    private fun convert(target: String) {
        val path = sourceFile?.absolutePath ?: return
        toast("Converting…")
        io.execute {
            // #579 the ONE conversion path, shared with the Files tab's row action.
            val outcome = runConversion(File(path), target)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                outcome.onSuccess { toast("Saved as " + it.name + " next to the original.") }
                    .onFailure { dialog = ReaderDialog.Message(it.message ?: "conversion failed") }
            }
        }
    }

    // ── edit (#1949) ─────────────────────────────────────────────────────────────────────────

    private fun toggleEdit() {
        if (editing) {
            editing = false
            if (savedOriginal) { savedOriginal = false; engine?.close(); engine = null; openDocument(null) }
            return
        }
        if (engine == null) return
        io.execute {
            try {
                val copy = editFile ?: PdfEngine.spool(this, source)
                runOnUiThread { if (!isDestroyed) { editFile = copy; editing = true } }
            } catch (error: Exception) {
                runOnUiThread { toast("Cannot edit this PDF: " + (error.message ?: "unreadable")) }
            }
        }
    }

    /** Back to the source when it is writable, otherwise a copy in Downloads (a temporary read grant is read-only). */
    private fun saveEdited() {
        val f = editFile ?: return
        io.execute {
            try {
                val wrote = try {
                    if (source.scheme == "file") { f.inputStream().use { i -> File(source.path ?: "").outputStream().use { o -> i.copyTo(o) } }; true }
                    else contentResolver.openOutputStream(source, "wt")?.use { o -> f.inputStream().use { i -> i.copyTo(o) }; true } ?: false
                } catch (_: Exception) { false }
                if (wrote) savedOriginal = true
                val message = if (wrote) "Saved to the original file." else "Saved a copy: " + saveCopy(f)
                runOnUiThread { toast(message) }
            } catch (error: Exception) {
                runOnUiThread { toast("Cannot save (" + (error.message ?: "error") + ").") }
            }
        }
    }

    private fun saveCopy(edited: File): String {
        val base = displayName.removeSuffix(".pdf") + "-edited.pdf"
        if (android.os.Build.VERSION.SDK_INT >= 29) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, base)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/pdf")
            }
            val dest = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw java.io.IOException("no Downloads")
            contentResolver.openOutputStream(dest)?.use { o -> edited.inputStream().use { i -> i.copyTo(o) } } ?: throw java.io.IOException("no stream")
            return "Downloads/$base"
        }
        val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOCUMENTS) ?: filesDir
        edited.copyTo(File(dir, base), overwrite = true)
        return File(dir, base).path
    }

    // ── search ───────────────────────────────────────────────────────────────────────────────

    private fun openSearch() {
        searchOpen = true
    }

    private fun closeSearch() {
        searchGen++
        hits.clear()
        currentHit = -1
        reader.setHits(searchGen, emptyList(), -1)
        searchOpen = false
        searchCountText = ""
    }

    private fun startSearch(query: String) {
        val e = engine ?: return
        val id = ++searchGen
        hits.clear()
        currentHit = -1
        reader.setHits(id, emptyList(), -1)
        if (query.isBlank()) {
            searchCountText = ""
            return
        }
        searchCountText = "…"
        val from = reader.currentPageIndex()
        searchExec.execute {
            val n = e.pageCount
            val batch = ArrayList<SearchHit>()
            var lastPost = 0L
            for (k in 0 until n) {
                if (id != searchGen) return@execute
                // From the reader's own page to the end, then wrapping: the first match is the
                // nearest one below, and Next keeps going in reading order.
                val page = (from + k) % n
                for (f in e.findOnPage(page, query, false)) batch.add(SearchHit(page, f[0], f[1]))
                val now = System.currentTimeMillis()
                val last = k == n - 1
                if ((batch.isNotEmpty() && now - lastPost > BATCH_MS) || last) {
                    val chunk = ArrayList(batch)
                    batch.clear()
                    lastPost = now
                    runOnUiThread { if (id == searchGen) addHits(id, chunk, last) }
                }
            }
        }
    }

    private fun addHits(id: Int, chunk: List<SearchHit>, done: Boolean) {
        hits.addAll(chunk)
        val first = currentHit < 0 && hits.isNotEmpty()
        if (first) currentHit = 0
        reader.setHits(id, ArrayList(hits), currentHit)
        if (first) reader.showHit(0)
        searchCountText = when {
            hits.isEmpty() -> if (done) "No matches" else "…"
            else -> "${currentHit + 1} / ${hits.size}" + if (done) "" else "+"
        }
    }

    private fun stepHit(delta: Int) {
        if (hits.isEmpty()) return
        currentHit = (currentHit + delta + hits.size) % hits.size
        reader.showHit(currentHit)
        searchCountText = "${currentHit + 1} / ${hits.size}"
    }

    // ── UI ───────────────────────────────────────────────────────────────────────────────────

    private fun buildUi() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        reader = PdfReaderView(this).also { it.host = this }
        setContent { ReaderTheme { ReaderScreen() } }
    }

    @Composable
    private fun ReaderScreen() {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).screenInsets()) {
            Column(Modifier.fillMaxSize()) {
                ReaderBar()
                if (searchOpen) SearchBar()
                val ef = editFile
                if (editing && ef != null) PdfViewer(ef, editing = true, modifier = Modifier.fillMaxWidth().weight(1f))
                else AndroidView(factory = { reader }, modifier = Modifier.fillMaxWidth().weight(1f))
            }
            pageLabel?.let { label ->
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = PdfMetrics.listBottom)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.8f), RoundedCornerShape(PdfMetrics.cardRadius))
                        .padding(horizontal = PdfMetrics.padWide, vertical = PdfMetrics.gapWide),
                )
            }
            statusText?.let { text ->
                Text(
                    text,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).fillMaxWidth().padding(horizontal = PdfMetrics.padWide),
                )
            }
            dialog?.let { ReaderDialogs(it) }
        }
    }

    @Composable
    private fun ReaderBar() {
        var more by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().height(PdfMetrics.islandHeight), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { finish() }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.pdf_back)) }
            Text(
                titleText,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editing) TextButton(onClick = { saveEdited() }, modifier = Modifier.testTag("pdf_save")) { Text(stringResource(R.string.pdf_save)) }
            IconButton(onClick = { toggleEdit() }, modifier = Modifier.size(PdfMetrics.tap).alpha(if (editing) 1f else 0.55f).testTag("pdf_edit")) {
                Icon(Icons.Filled.Edit, stringResource(R.string.pdf_edit))
            }
            IconButton(onClick = { openSearch() }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.Filled.Search, stringResource(R.string.pdf_search)) }
            IconButton(onClick = { showOutline() }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.AutoMirrored.Filled.Toc, stringResource(R.string.pdf_outline)) }
            IconButton(onClick = { toggleNight() }, modifier = Modifier.size(PdfMetrics.tap).alpha(if (nightOn) 1f else 0.55f)) {
                Icon(Icons.Filled.DarkMode, stringResource(R.string.pdf_night))
            }
            IconButton(onClick = { toggleMode() }, modifier = Modifier.size(PdfMetrics.tap)) {
                Icon(if (pagedOn) Icons.Filled.Description else Icons.Filled.ViewList, stringResource(R.string.pdf_mode))
            }
            Box {
                IconButton(onClick = { more = true }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.Filled.MoreVert, stringResource(R.string.pdf_more)) }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_share)) }, onClick = { more = false; share() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_print)) }, onClick = { more = false; printDocument() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.pdf_goto)) }, onClick = { more = false; askPage() })
                    if (conversionTargets.isNotEmpty()) DropdownMenuItem(text = { Text(stringResource(R.string.pdf_convert)) }, onClick = { more = false; chooseConversion() })
                    extraActions().forEach { a -> DropdownMenuItem(text = { Text(a.label) }, modifier = Modifier.testTag(a.tag), onClick = { more = false; a.run() }) }
                }
            }
        }
    }

    @Composable
    private fun SearchBar() {
        val focus = remember { FocusRequester() }
        val focusManager = LocalFocusManager.current
        LaunchedEffect(Unit) { focus.requestFocus() }
        Row(Modifier.fillMaxWidth().height(PdfMetrics.rowHeight + PdfMetrics.padWide).padding(start = PdfMetrics.padWide), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text(stringResource(R.string.pdf_search_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    startSearch(searchQuery)
                    focusManager.clearFocus()
                }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            Text(
                searchCountText,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = PdfMetrics.pad),
            )
            IconButton(onClick = { stepHit(-1) }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.Filled.KeyboardArrowUp, stringResource(R.string.pdf_prev_match)) }
            IconButton(onClick = { stepHit(1) }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.Filled.KeyboardArrowDown, stringResource(R.string.pdf_next_match)) }
            IconButton(onClick = { closeSearch() }, modifier = Modifier.size(PdfMetrics.tap)) { Icon(Icons.Filled.Close, stringResource(R.string.pdf_close_search)) }
        }
    }

    @Composable
    private fun ReaderDialogs(shown: ReaderDialog) {
        val close = { dialog = null }
        when (shown) {
            is ReaderDialog.Password -> {
                var password by remember { mutableStateOf("") }
                AlertDialog(
                    onDismissRequest = {},
                    properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                    title = {
                        Text(stringResource(if (shown.retry) R.string.pdf_password_retry else R.string.pdf_password_title))
                    },
                    text = {
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = { close(); openDocument(password) }) { Text(stringResource(R.string.pdf_open)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { close(); finish() }) { Text(stringResource(R.string.pdf_cancel)) }
                    },
                )
            }
            is ReaderDialog.Outline -> AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.pdf_outline)) },
                text = {
                    LazyColumn {
                        items(shown.items) { item ->
                            Text(
                                "    ".repeat(item.depth) + item.title,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable { close(); reader.goToPage(item.page) }
                                    .padding(vertical = PdfMetrics.padWide),
                            )
                        }
                    }
                },
                confirmButton = {},
            )
            ReaderDialog.GoTo -> {
                var number by remember { mutableStateOf("") }
                val count = engine?.pageCount ?: 0
                AlertDialog(
                    onDismissRequest = close,
                    title = { Text(stringResource(R.string.pdf_goto_title)) },
                    text = {
                        OutlinedTextField(
                            value = number,
                            onValueChange = { number = it },
                            placeholder = { Text(stringResource(R.string.pdf_goto_hint, count)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            close()
                            val n = number.trim().toIntOrNull()
                            if (n != null) reader.goToPage(n - 1)
                        }) { Text(stringResource(R.string.pdf_go)) }
                    },
                    dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.pdf_cancel)) } },
                )
            }
            ReaderDialog.ConvertTargets -> AlertDialog(
                onDismissRequest = close,
                title = { Text(stringResource(R.string.pdf_convert_title)) },
                text = {
                    Column {
                        conversionTargets.forEach { target ->
                            Text(
                                target,
                                modifier = Modifier.fillMaxWidth().clickable { close(); convert(target) }.padding(vertical = PdfMetrics.padWide),
                            )
                        }
                    }
                },
                confirmButton = {},
            )
            is ReaderDialog.Message -> AlertDialog(
                onDismissRequest = close,
                text = { Text(shown.text) },
                confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.pdf_ok)) } },
            )
        }
    }

    protected fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    // ── source ───────────────────────────────────────────────────────────────────────────────

    /** file:// is honoured only inside places this app already browses; a content:// is the sender's grant. */
    private fun isReadablePlace(file: File): Boolean {
        val candidate = try { file.canonicalFile } catch (error: Exception) { return false }
        val roots = ArrayList<File>()
        roots.add(android.os.Environment.getExternalStorageDirectory())
        getExternalFilesDirs(null).filterNotNull().forEach { roots.add(it) }
        roots.add(cacheDir)
        return roots.any {
            val root = try { it.canonicalFile } catch (error: Exception) { return@any false }
            candidate.path.startsWith(root.path + File.separator)
        }
    }

    private fun queryName(uri: Uri): String? {
        val named = try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (error: Exception) {
            null
        }
        return named ?: uri.lastPathSegment
    }

    companion object {
        private const val NIGHT = "night"
        private const val PAGED = "paged"
        private const val LAST_PAGE = "last:"
        private const val BATCH_MS = 150L

        private val SAFE_LINK_SCHEMES = setOf("http", "https", "mailto", "tel")
    }
}
