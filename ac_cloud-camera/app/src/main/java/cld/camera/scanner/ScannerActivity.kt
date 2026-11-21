package cld.camera.scanner

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.os.Bundle
import android.provider.DocumentsContract
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import cld.camera.R
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * #1839 THE Scanner page (the bottom nav's fifth slot): documents in, a clean multi-page PDF out.
 *
 *  - input: take a photo (the system capture, this app's own camera by default) or import from the gallery / Files; several
 *    pages; reorder, delete, retake;
 *  - Frame: automatic edge detection, manual corner handles, perspective correction, crop (the corners are the crop),
 *    rotate, deskew;
 *  - Filter: B&W document, grayscale, colour document, original, brightness / contrast, per page or all pages;
 *  - Size: A4, Letter, Legal, ID card, receipt, business card, original; margins; orientation;
 *  - OCR: on-device through the fleet image engine, per page, copy / share the text;
 *  - Export: searchable PDF, JPEG / PNG, .txt; share to any app, save to Files / Cloud Drive (the system picker).
 * All the maths is the pure code beside this file (Geometry, Raster, DocFilter, EdgeDetector, PageFit, PdfPlan, PdfWriter).
 */
class ScannerActivity : AppCompatActivity() {
    private val pages = ArrayList<ScanPage>()
    private var sel = -1
    private var tab = TAB_PAGES
    private var nextId = 1L
    private var retakeId: Long? = null
    private var pendingCapture: File? = null
    private var options = ExportOptions()
    private var allPages = false
    private val worker = Executors.newSingleThreadExecutor()
    private var previewToken = 0
    private val ocr by lazy { ScannerOcr(this) }

    private lateinit var strip: LinearLayout
    private lateinit var corner: CornerEditView
    private lateinit var preview: ImageView
    private lateinit var hint: TextView
    private lateinit var panel: LinearLayout
    private lateinit var tabRow: LinearLayout
    private lateinit var busyBar: TextView
    private var pendingSave: List<ExportFile> = emptyList()

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = pendingCapture; pendingCapture = null
        if (ok && f != null) worker.execute { decode(ImageDecoder.createSource(f))?.let { b -> runOnUiThread { addOrRetake(b) } }; f.delete() }
        else { retakeId = null }
    }
    private val importImages = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) worker.execute {
            val bitmaps = uris.mapNotNull { decode(ImageDecoder.createSource(contentResolver, it)) }
            runOnUiThread { bitmaps.forEach { addOrRetake(it) }; if (bitmaps.size < uris.size) toast(R.string.scanner_import_failed) }
        }
    }
    /** One file: the system "save as" picker (Files, Cloud Drive, any document provider), with the file's own type and name. */
    private val saveOne = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val uri = r.data?.data
        if (uri != null) writeTo { contentResolver.openOutputStream(uri, "wt")?.use { it.write(pendingSave[0].bytes) } }
    }
    private val saveMany = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) writeTo {
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
            for (f in pendingSave) {
                val doc = DocumentsContract.createDocument(contentResolver, parent, f.mime, f.name) ?: error("create ${f.name}")
                contentResolver.openOutputStream(doc, "wt")?.use { it.write(f.bytes) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(BG) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, ins ->
            val i = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(i.left, i.top, i.right, i.bottom); ins
        }
        root.addView(header())
        strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        root.addView(HorizontalScrollView(this).apply { addView(strip) }, LinearLayout.LayoutParams(-1, dp(96)))

        corner = CornerEditView(this).apply { visibility = View.GONE; onChanged = { q -> current()?.let { it.quad = q; it.touch() } } }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        hint = label(R.string.scanner_empty).apply { gravity = Gravity.CENTER; setPadding(dp(24), dp(24), dp(24), dp(24)) }
        busyBar = label(R.string.scanner_working).apply { visibility = View.GONE; setBackgroundColor(0xAA000000.toInt()); gravity = Gravity.CENTER }
        val stage = FrameLayout(this).apply {
            addView(corner, FrameLayout.LayoutParams(-1, -1)); addView(preview, FrameLayout.LayoutParams(-1, -1))
            addView(hint, FrameLayout.LayoutParams(-1, -1)); addView(busyBar, FrameLayout.LayoutParams(-1, dp(40), Gravity.TOP))
        }
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(tabRow) })
        panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(6)) }
        root.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(-1, dp(190)))
        setContentView(root)
        refresh()
    }

    override fun onDestroy() { worker.shutdown(); super.onDestroy() }

    // ── input ────────────────────────────────────────────────────────────────────────────

    private fun header() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4))
        addView(btn(R.string.scanner_back) { finish() })
        addView(label(R.string.scanner_title).apply { textSize = 18f; gravity = Gravity.CENTER }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(btn(R.string.scanner_camera) { capture(null) })
        addView(btn(R.string.scanner_import) { retakeId = null; importImages.launch("image/*") })
    }

    private fun capture(retake: Long?) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            // Permissions are asked in one place: libs:perms (never a raw settings intent).
            runCatching { com.diegonmarcos.superapp.perms.PermsLink.open(this, "Scanner", listOf("camera")) }
            return
        }
        retakeId = retake
        val f = File(ScannerExport.dir(this), "capture_${System.nanoTime()}.jpg")
        pendingCapture = f
        runCatching { takePicture.launch(ScannerExport.uriFor(this, f)) }.onFailure { pendingCapture = null; retakeId = null; toast(R.string.scanner_no_camera) }
    }

    /** Decodes [source] at most [ScanPipeline.SOURCE_SIDE] on its longer side (EXIF turn applied by the decoder). */
    private fun decode(source: ImageDecoder.Source): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(source) { dec, info, _ ->
            dec.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longest = max(info.size.width, info.size.height)
            if (longest > ScanPipeline.SOURCE_SIDE) {
                val f = ScanPipeline.SOURCE_SIDE.toDouble() / longest
                dec.setTargetSize(max(1, (info.size.width * f).toInt()), max(1, (info.size.height * f).toInt()))
            }
        }
    }.getOrNull()

    private fun addOrRetake(b: Bitmap) {
        val id = retakeId
        retakeId = null
        val page = ScanPage(nextId++, b)
        page.quad = ScanPipeline.autoQuad(page)
        val at = if (id != null) pages.indexOfFirst { it.id == id } else -1
        if (at >= 0) { page.filter = pages[at].filter; pages[at] = page; sel = at } else { pages.add(page); sel = pages.lastIndex }
        if (tab == TAB_PAGES && pages.size == 1) tab = TAB_FRAME
        refresh()
    }

    // ── state → views ────────────────────────────────────────────────────────────────────

    private fun current(): ScanPage? = pages.getOrNull(sel)

    private fun refresh() {
        buildStrip(); buildTabs(); buildPanel()
        val p = current()
        hint.visibility = if (p == null) View.VISIBLE else View.GONE
        corner.visibility = if (p != null && tab == TAB_FRAME) View.VISIBLE else View.GONE
        preview.visibility = if (p != null && tab != TAB_FRAME) View.VISIBLE else View.GONE
        if (p != null) { if (tab == TAB_FRAME) corner.show(p.source, p.quad) else renderPreview(p) }
    }

    private fun renderPreview(p: ScanPage) {
        val token = ++previewToken
        busy(true)
        worker.execute {
            val bmp = runCatching { ScanPipeline.toBitmap(ScanPipeline.render(p, 1400)) }.getOrNull()
            runOnUiThread { if (token == previewToken) { busy(false); bmp?.let { preview.setImageBitmap(it) } } }
        }
    }

    private fun buildStrip() {
        strip.removeAllViews()
        pages.forEachIndexed { i, p ->
            val v = ImageView(this).apply {
                setImageBitmap(p.source); scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(if (i == sel) ACCENT else Color.DKGRAY); setPadding(dp(2), dp(2), dp(2), dp(2))
                contentDescription = getString(R.string.scanner_page_n, i + 1)
                setOnClickListener { sel = i; refresh() }
            }
            strip.addView(v, LinearLayout.LayoutParams(dp(64), dp(84)).apply { marginEnd = dp(6) })
        }
    }

    private fun buildTabs() {
        tabRow.removeAllViews()
        listOf(R.string.scanner_tab_pages, R.string.scanner_tab_frame, R.string.scanner_tab_filter, R.string.scanner_tab_size, R.string.scanner_tab_ocr, R.string.scanner_tab_export)
            .forEachIndexed { i, s -> tabRow.addView(btn(s, selected = i == tab) { tab = i; refresh() }) }
    }

    private fun buildPanel() {
        panel.removeAllViews()
        val p = current()
        if (p == null) { panel.addView(label(R.string.scanner_empty)); return }
        when (tab) {
            TAB_PAGES -> {
                panel.addView(row(
                    btn(R.string.scanner_move_left) { move(-1) }, btn(R.string.scanner_move_right) { move(1) },
                    btn(R.string.scanner_delete) { delete() }, btn(R.string.scanner_retake) { capture(p.id) },
                ))
            }
            TAB_FRAME -> {
                panel.addView(row(
                    btn(R.string.scanner_auto_edges) { p.quad = ScanPipeline.autoQuad(p); p.touch(); corner.show(p.source, p.quad) },
                    btn(R.string.scanner_reset_corners) { p.quad = Quad.full(p.source.width, p.source.height); p.touch(); corner.show(p.source, p.quad) },
                ))
                panel.addView(row(
                    btn(R.string.scanner_rotate_left) { p.quarters += 3; p.touch(); toast(R.string.scanner_rotated) },
                    btn(R.string.scanner_rotate_right) { p.quarters += 1; p.touch(); toast(R.string.scanner_rotated) },
                    btn(R.string.scanner_deskew) { deskew(p) },
                    btn(R.string.scanner_preview) { tab = TAB_FILTER; refresh() },
                ))
                panel.addView(label(R.string.scanner_frame_hint))
            }
            TAB_FILTER -> filterPanel(p)
            TAB_SIZE -> sizePanel()
            TAB_OCR -> ocrPanel(p)
            TAB_EXPORT -> exportPanel()
        }
    }

    private fun move(d: Int) {
        val to = sel + d
        if (to !in pages.indices) return
        pages.add(to, pages.removeAt(sel)); sel = to; refresh()
    }

    private fun delete() {
        if (sel !in pages.indices) return
        pages.removeAt(sel); sel = minOf(sel, pages.lastIndex); refresh()
    }

    private fun deskew(p: ScanPage) {
        busy(true)
        worker.execute {
            val d = runCatching { ScanPipeline.estimateDeskew(p) }.getOrDefault(0.0)
            runOnUiThread { busy(false); p.deskew = d; p.touch(); toast(getString(R.string.scanner_deskewed, d)) }
        }
    }

    // ── filter ───────────────────────────────────────────────────────────────────────────

    private fun filterPanel(p: ScanPage) {
        val modes = listOf(R.string.scanner_f_bw to FilterMode.BW, R.string.scanner_f_gray to FilterMode.GRAYSCALE, R.string.scanner_f_color to FilterMode.COLOR_DOC, R.string.scanner_f_original to FilterMode.ORIGINAL)
        panel.addView(row(*modes.map { (s, m) -> btn(s, selected = p.filter.mode == m) { setFilter(p) { f -> f.copy(mode = m) }; refresh() } }.toTypedArray()))
        panel.addView(label(R.string.scanner_brightness))
        panel.addView(slider(-100, 100, p.filter.brightness) { v -> setFilter(p) { f -> f.copy(brightness = v) } })
        panel.addView(label(R.string.scanner_contrast))
        panel.addView(slider(-100, 100, p.filter.contrast) { v -> setFilter(p) { f -> f.copy(contrast = v) } })
        panel.addView(CheckBox(this).apply {
            setText(R.string.scanner_all_pages); setTextColor(Color.WHITE); isChecked = allPages
            setOnCheckedChangeListener { _, on -> allPages = on; if (on) pages.forEach { it.filter = p.filter; it.touch() } }
        })
    }

    private fun setFilter(p: ScanPage, f: (FilterSpec) -> FilterSpec) {
        val n = f(p.filter)
        (if (allPages) pages else listOf(p)).forEach { it.filter = n; it.touch() }
    }

    /** A slider that re-renders the preview only when released (the filters are per-pixel). */
    private fun slider(min: Int, max: Int, value: Int, onSet: (Int) -> Unit) = SeekBar(this).apply {
        this.max = max - min; progress = value - min
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) onSet(p + min) }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) { current()?.let { renderPreview(it) } }
        })
    }

    // ── size ─────────────────────────────────────────────────────────────────────────────

    private fun sizePanel() {
        val sizes = listOf(R.string.scanner_s_a4 to PageSize.A4, R.string.scanner_s_letter to PageSize.LETTER, R.string.scanner_s_legal to PageSize.LEGAL,
            R.string.scanner_s_id to PageSize.ID_CARD, R.string.scanner_s_receipt to PageSize.RECEIPT, R.string.scanner_s_card to PageSize.BUSINESS_CARD, R.string.scanner_s_original to PageSize.ORIGINAL)
        panel.addView(HorizontalScrollView(this).apply { addView(row(*sizes.map { (s, z) -> btn(s, selected = options.size == z) { options = options.copy(size = z); buildPanel() } }.toTypedArray())) })
        panel.addView(row(*listOf(R.string.scanner_o_auto to Orientation.AUTO, R.string.scanner_o_portrait to Orientation.PORTRAIT, R.string.scanner_o_landscape to Orientation.LANDSCAPE)
            .map { (s, o) -> btn(s, selected = options.orientation == o) { options = options.copy(orientation = o); buildPanel() } }.toTypedArray()))
        panel.addView(label(getString(R.string.scanner_margin, options.marginMm.toInt())))
        panel.addView(SeekBar(this).apply {
            max = 30; progress = options.marginMm.toInt()
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) options = options.copy(marginMm = p.toDouble()) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { buildPanel() }
            })
        })
    }

    // ── OCR ──────────────────────────────────────────────────────────────────────────────

    private fun ocrPanel(p: ScanPage) {
        panel.addView(row(btn(R.string.scanner_ocr_page) { runOcr(listOf(p)) }, btn(R.string.scanner_ocr_all) { runOcr(pages.toList()) }))
        val box = EditText(this).apply { setText(p.text); setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); hint = getString(R.string.scanner_ocr_hint); textSize = 13f
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { p.text = s?.toString().orEmpty() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            }) }
        panel.addView(box)
        panel.addView(row(btn(R.string.scanner_copy_text) {
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("scan", p.text)); toast(R.string.scanner_copied)
        }, btn(R.string.scanner_share_text) {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, p.text), getString(R.string.scanner_share)))
        }))
    }

    private fun runOcr(targets: List<ScanPage>) {
        busy(true)
        worker.execute {
            var error: String? = null
            for (p in targets) {
                val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p))
                val r = ocr.read(bmp); bmp.recycle()
                if (r.error != null) { error = r.error; break }
                p.text = r.text; p.words = r.words; p.ocrDone = true
            }
            val failed = error
            runOnUiThread { busy(false); if (failed != null) Toast.makeText(this, getString(R.string.scanner_ocr_failed, failed), Toast.LENGTH_LONG).show(); buildPanel() }
        }
    }

    // ── export ───────────────────────────────────────────────────────────────────────────

    private fun exportPanel() {
        panel.addView(EditText(this).apply {
            setText(options.name); setTextColor(Color.WHITE); hint = getString(R.string.scanner_file_name); setSingleLine()
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { options = options.copy(name = s?.toString().orEmpty()) }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })
        panel.addView(row(*ExportFormat.entries.map { f -> btnText(f.name, selected = options.format == f) { options = options.copy(format = f); buildPanel() } }.toTypedArray()))
        panel.addView(label(getString(R.string.scanner_quality, options.quality)))
        panel.addView(SeekBar(this).apply {
            max = 90; progress = options.quality - 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, user: Boolean) { if (user) options = options.copy(quality = p + 10) }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) { buildPanel() }
            })
        })
        panel.addView(row(btn(R.string.scanner_save) { export(share = false) }, btn(R.string.scanner_share) { export(share = true) }))
    }

    private fun export(share: Boolean) {
        if (pages.isEmpty()) { toast(R.string.scanner_empty); return }
        val o = options
        busy(true)
        worker.execute {
            val result = runCatching {
                // A searchable PDF needs the text: read any page not read yet, once, before writing.
                if (o.format == ExportFormat.PDF) for (p in pages) if (!p.ocrDone) {
                    val bmp = ScanPipeline.toBitmap(ScanPipeline.render(p)); val r = ocr.read(bmp); bmp.recycle()
                    if (r.error == null) { p.text = r.text; p.words = r.words; p.ocrDone = true }
                }
                ScannerExport.build(pages.toList(), o)
            }
            runOnUiThread {
                busy(false)
                result.onFailure { Toast.makeText(this, getString(R.string.scanner_export_failed, it.message), Toast.LENGTH_LONG).show() }
                result.onSuccess { files -> if (share) shareFiles(files) else saveFiles(files) }
            }
        }
    }

    private fun shareFiles(files: List<ExportFile>) {
        val uris = ArrayList(ScannerExport.stage(this, files))
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.setType(if (files.map { it.mime }.distinct().size == 1) files[0].mime else "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        i.clipData = ClipData.newRawUri("scan", uris[0]).also { c -> uris.drop(1).forEach { c.addItem(ClipData.Item(it)) } }
        startActivity(Intent.createChooser(i, getString(R.string.scanner_share)))
    }

    private fun saveFiles(files: List<ExportFile>) {
        pendingSave = files
        if (files.size == 1) saveOne.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(files[0].mime).putExtra(Intent.EXTRA_TITLE, files[0].name))
        else saveMany.launch(null)
    }

    private fun writeTo(block: () -> Unit) {
        busy(true)
        worker.execute {
            val r = runCatching(block)
            runOnUiThread { busy(false); r.onSuccess { toast(R.string.scanner_saved) }.onFailure { Toast.makeText(this, getString(R.string.scanner_export_failed, it.message), Toast.LENGTH_LONG).show() } }
        }
    }

    // ── small views ──────────────────────────────────────────────────────────────────────

    private fun busy(on: Boolean) { busyBar.visibility = if (on) View.VISIBLE else View.GONE }
    private fun toast(res: Int) = Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(res: Int) = label(getString(res))
    private fun label(s: String) = TextView(this).apply { text = s; setTextColor(Color.WHITE); textSize = 14f }

    private fun btn(res: Int, selected: Boolean = false, onClick: () -> Unit) = btnText(getString(res), selected, onClick)

    private fun btnText(text: String, selected: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        isAllCaps = false; minWidth = 0; minimumWidth = 0
        setTextColor(if (selected) Color.BLACK else Color.WHITE)
        setBackgroundColor(if (selected) ACCENT else 0x00000000)
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; views.forEach { addView(it) } }

    companion object {
        private const val TAB_PAGES = 0
        private const val TAB_FRAME = 1
        private const val TAB_FILTER = 2
        private const val TAB_SIZE = 3
        private const val TAB_OCR = 4
        private const val TAB_EXPORT = 5
        private const val BG = 0xFF101010.toInt()
        private const val ACCENT = 0xFF4FC3F7.toInt()

        fun intent(ctx: Context) = Intent(ctx, ScannerActivity::class.java)
    }
}
