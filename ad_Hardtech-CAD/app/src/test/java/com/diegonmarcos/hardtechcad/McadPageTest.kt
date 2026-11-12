package com.diegonmarcos.hardtechcad

import com.diegonmarcos.hardtechcad.ViewerHealth.Present
import com.diegonmarcos.hardtechcad.ui.CadType
import com.diegonmarcos.hardtechcad.ui.PINNED
import com.diegonmarcos.hardtechcad.ui.SCROLLED
import com.diegonmarcos.superapp.cad.ModelManifest
import com.diegonmarcos.superapp.cad.ModelTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1664 the MCAD page and the model page: a readable table whose every column is reachable, a card per model with its preview image from the same
 * filtered list, a tap that opens the viewer, gestures forwarded to the page camera in every presentation, a fallback that is retried on the GPU
 * path after an update, and field parity with the previous version (rule 16: nothing the page had is gone).
 */
class McadPageTest {
    private val app = listOf(File("."), File("app")).first { File(it, "src/main/assets/cad/models.json").isFile }
    private val repo = generateSequence(app.absoluteFile) { it.parentFile }.first { File(it, "ab_cloud-libs-shared").isDirectory }
    private fun src(path: String) = File(app, path).readText()
    private val ui = "src/main/java/com/diegonmarcos/hardtechcad/ui/"
    private val manifest = File(app, "src/main/assets/cad/models.json").readText()
    private val models = ModelManifest.parse(manifest)
    private val cats = ModelManifest.categories(manifest)

    @Test fun theTableIsReadableAndEveryColumnIsReachable() {
        // no micro-text: the page's type scale never goes below 12 sp, rows are 15 sp
        assertTrue(CadType.body.value >= 15f && CadType.label.value >= 13f && CadType.caption.value >= 12f && CadType.display.value > CadType.title.value)
        // the name is pinned, every other column (Vert / Tri and Updated included) is in the block that scrolls sideways
        assertEquals(ModelTable.Column.NAME, PINNED)
        assertEquals(ModelTable.Column.entries.toSet(), (SCROLLED + PINNED).toSet())
        assertTrue(ModelTable.Column.GEOMETRY in SCROLLED && ModelTable.Column.UPDATED in SCROLLED)
        val page = src(ui + "ModelsPage.kt")
        for (needle in listOf("Modifier.weight(1f).horizontalScroll(scroll).testTag(\"models_scrolled\")", "testTag(\"models_pinned\")", "R.string.models_scroll_hint",
            "fontSize = if (mono) CadType.label else CadType.body"))
            assertTrue("ModelsPage.kt lacks $needle", needle in page)
        assertTrue("no Store micro ramp left on the page", "KitStoreRow." !in page && "KitStoreCaption(" !in page)
    }

    @Test fun cardsAreTheTableRowsUnderEveryFilter() {
        val page = src(ui + "ModelsPage.kt")
        // one list feeds both
        assertTrue("val rows = ModelTable.shown(catalog.models, ModelTable.Filters(category, extension, software), sort)" in page)
        assertTrue("ModelsTable(rows, catalog.categories, sort" in page && "ModelCards(rows, catalog.categories, onOpen)" in page)
        val opts = Triple(ModelTable.categoryOptions(models, cats), ModelTable.extensionOptions(models), ModelTable.softwareOptions(models))
        var combos = 0
        for (c in opts.first) for (e in opts.second) for (s in opts.third) {
            val f = ModelTable.Filters(c.first, e.first, s.first)
            val so = ModelTable.Sort(ModelTable.Column.NAME, false)
            assertEquals(ModelTable.sort(ModelTable.filter(models, f), so).map { it.id }, ModelTable.shown(models, f, so).map { it.id })
            combos++
        }
        assertTrue(combos >= 4 * 6 * 4)
        assertEquals(models.size, ModelTable.shown(models, ModelTable.Filters(), ModelTable.Sort(ModelTable.Column.NAME, false)).size)
    }

    @Test fun everyDeclaredModelHasAPreviewImageFromItsGlb() {
        assertTrue(models.size >= 3)
        for (m in models) {
            val t = ModelTable.thumb(m)
            assertTrue("${m.id} declares no preview", t.isNotBlank())
            assertEquals("${m.id}: the preview sits next to its GLB", m.view!!.asset.removeSuffix(".view.glb") + ".thumb.jpg", t)
            val f = File(app, "src/main/assets/$t")
            assertTrue("${m.id}: $t missing", f.isFile)
            val b = f.readBytes()
            assertTrue("${m.id}: $t is not a JPEG", b.size > 1000 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte())
        }
        // the generator renders from the GLB through the app's own viewer page, and CI checks it
        val gen = File(app, "../tools/make-thumbs.mjs").readText()
        assertTrue("window.cadOpen(a)" in gen && "cadView('iso')" in gen && "--check" in gen)
        val wf = File(repo, ".github/workflows/hardtech-cad-models.yml").readText()
        assertTrue("make-thumbs.mjs" in wf && "--check" in wf)
        // a missing or unreadable picture is a placeholder with the reason, never a crash
        val style = src(ui + "CadStyle.kt")
        assertTrue("Thumb.None(noneDeclared)" in style && "Thumb.None(unreadable" in style && "runCatching { ctx.assets.open(asset)" in style)
    }

    @Test fun aTapOnARowOrACardOpensTheViewer() {
        val page = src(ui + "ModelsPage.kt")
        assertTrue("Modifier.clickable { onOpen(m) }.testTag(\"model_row:\" + m.id)" in page)
        assertTrue("ModelCard(m, categories, Modifier.weight(1f)) { onOpen(m) }" in page && ".clickable(onClick = onClick).testTag(\"model_card:\" + m.id)" in page)
        val section = src(ui + "SectionPage.kt")
        assertTrue("Pages.KIND_MODELS -> ModelsPage(catalog) { openModel = it.id }" in section && "ModelPage(m, catalog!!.categories) { openModel = null }" in section)
        assertTrue("ModelViewer(m, dir, height = viewerH, full = full, onFull = { full = !full })" in src(ui + "ModelPage.kt"))
    }

    @Test fun gesturesReachThePageCameraInEveryPresentation() {
        assertEquals(listOf("window.cadOrbit&&window.cadOrbit(12.00,-3.50)"), ViewerHealth.gesture(1, 12f, -3.5f, 1f))
        assertEquals(emptyList<String>(), ViewerHealth.gesture(1, 0f, 0f, 1f))
        assertEquals(listOf("window.cadPan&&window.cadPan(4.00,2.00)", "window.cadZoom&&window.cadZoom(1.2500)"), ViewerHealth.gesture(2, 4f, 2f, 1.25f))
        assertEquals(listOf("window.cadZoom&&window.cadZoom(0.8000)"), ViewerHealth.gesture(2, 0f, 0f, 0.8f))
        assertEquals(emptyList<String>(), ViewerHealth.gesture(2, 0f, 0f, Float.NaN))
        assertEquals(emptyList<String>(), ViewerHealth.gesture(0, 5f, 5f, 2f))
        assertEquals(40L, ViewerHealth.pullDelayMs(100)); assertEquals(200L, ViewerHealth.pullDelayMs(5000))
        val viewer = src(ui + "ModelViewer.kt")
        // the touch surface sits OVER the WebView and the app-drawn frame (after both in the Box), consumes the move and forwards it
        val web = viewer.indexOf("AndroidView("); val host = viewer.indexOf("testTag(\"viewer_host_frame\")"); val touch = viewer.indexOf("testTag(\"viewer_touch\")")
        assertTrue(web in 0 until host && host < touch)
        for (needle in listOf("awaitEachGesture", "ViewerHealth.gesture(c, delta.x / d, delta.y / d, zoom)", "it.consume()", "onDoubleTap",
            "kotlinx.coroutines.delay(ViewerHealth.pullDelayMs(System.currentTimeMillis() - lastGesture))"))
            assertTrue("ModelViewer.kt lacks $needle", needle in viewer)
        val js = File(repo, "ab_cloud-libs-shared/libs/cad-core/src/main/assets/cad/viewer.js").readText()
        for (needle in listOf("window.cadOrbit = (dx, dy) =>", "window.cadZoom = (scale) =>", "window.cadPan = (dx, dy) =>", "window.cadView = (name) =>", "window.cadReset = () =>",
            "window.cadBackground = (mode) =>", "window.cadFrameHash = () =>", "function commit() { camera.lookAt(controls.target); camera.updateMatrixWorld(); render();"))
            assertTrue("viewer.js lacks $needle", needle in js)
        // render() copies the frame into the 2D canvas (blit) and counts it, which the host frame pull watches
        assertTrue("renderer.render(scene, camera); blit(); D.frames++" in js)
    }

    @Test fun aRememberedFallbackIsRetriedOnTheGpuPathAfterAnUpdate() {
        assertEquals(Present.WEBGL, ViewerHealth.start(null, null, "42-abc"))
        assertEquals(Present.CANVAS2D, ViewerHealth.start("2d", "42-abc", "42-abc"))
        assertEquals(Present.HOST, ViewerHealth.start("host", "42-abc", "42-abc"))
        assertEquals(Present.WEBGL, ViewerHealth.start("host", "41-xyz", "42-abc"))
        assertEquals(Present.WEBGL, ViewerHealth.start("bogus", "42-abc", "42-abc"))
        val viewer = src(ui + "ModelViewer.kt")
        assertTrue("ViewerHealth.start(prefs.getString(PREF_PRESENT, null), prefs.getString(PREF_BUILD, null), build)" in viewer)
        assertTrue("prefs.edit().putString(PREF_PRESENT, n.js).putString(PREF_BUILD, build).apply()" in viewer)
    }

    @Test fun theModelPageHasTheViewerControls() {
        val viewer = src(ui + "ModelViewer.kt")
        for (id in listOf("front", "side", "top", "iso")) assertTrue("R.string.viewer_$id" in viewer)
        for (needle in listOf("testTag(\"viewer_reset\")", "testTag(\"viewer_background\")", "testTag(\"viewer_full\")", "testTag(\"viewer_presets\")", "window.cadView&&window.cadView('\$v')"))
            assertTrue("ModelViewer.kt lacks $needle", needle in viewer)
        val page = src(ui + "ModelPage.kt")
        for (needle in listOf("FactTiles(m)", "testTag(\"model_title\")", "max(320.dp, avail * 0.58f)", "rememberSaveable(m.id) { mutableStateOf(false) }"))
            assertTrue("ModelPage.kt lacks $needle", needle in page)
    }

    /** Rule 16: every field, control, string and section of the previous version is still there. */
    @Test fun fieldParityWithThePreviousVersion() {
        val strings = src("src/main/res/values/strings.xml")
        val before = listOf("entry_open", "entry_planned", "about_body", "lib_back", "lib_core_title", "lib_formats_title", "lib_aspect", "lib_core_geometry", "lib_core_need",
            "lib_core_standards", "lib_models_none", "lib_viewer", "lib_manifest_failed", "viewer_fetching", "viewer_loading", "viewer_failed", "viewer_derived", "viewer_model",
            "models_loading", "models_none", "models_filter_model", "models_filter_extension", "models_filter_software", "models_col_name", "models_col_category",
            "models_col_extension", "models_col_software", "models_col_size", "models_col_geometry", "models_col_updated", "model_back", "model_data_title", "viewer_fit",
            "viewer_wire", "viewer_gestures", "detail_name", "detail_category", "detail_extension", "detail_software", "detail_size", "detail_updated", "detail_geometry",
            "detail_dimensions", "detail_bbox", "detail_units", "detail_materials", "detail_layers", "detail_components", "detail_source", "detail_licence", "detail_sha256",
            "detail_view_sha256", "detail_view_tool", "detail_derived_sha256", "detail_derived_tool", "detail_conversion", "detail_log")
        for (n in before) assertTrue("strings.xml lost $n", "<string name=\"$n\">" in strings)
        val page = src(ui + "ModelsPage.kt")
        // the three filter rows, every column with its header and sort, the row tap
        for (needle in listOf("R.string.models_filter_model", "R.string.models_filter_extension", "R.string.models_filter_software", "\"model\") { category = it }",
            "\"extension\") { extension = it }", "\"software\") { software = it }", "testTag(\"models_header:\" + c.name)", "ModelTable.toggle(sort, c)", "testTag(\"models_table\")",
            "testTag(\"models_empty\")", "ModelTable.geometryText(m)", "ModelTable.sizeText(m.size)", "m.updated.ifBlank", "ModelTable.categoryLabel(m, categories)", "\".\" + ModelTable.extension(m)"))
            assertTrue("ModelsPage.kt lost $needle", needle in page)
        for (c in ModelTable.Column.entries) assertTrue("column ${c.name} has no label", "ModelTable.Column.${c.name} -> R.string.models_col_" in page)
        // the agents card, MODEL LIBRARIES cards, every entry kind and the bottom nav
        val section = src(ui + "SectionPage.kt")
        for (needle in listOf("Pages.KIND_SECTION_TITLE -> GroupTitle(e.title)", "Pages.KIND_LIBRARY -> LibraryRow(e) { openLibrary = e.library }", "else -> EntryRow(e, Pages.resolve(e, Decl.targets))",
            "Pages.KIND_HOLDINGS -> HoldingsPanel()", "Pages.KIND_ABOUT -> AboutPanel()"))
            assertTrue("SectionPage.kt lost $needle", needle in section)
        assertTrue("NavBarBottomHost(" in src(ui + "AppShell.kt") && "nav.islandEntries()" in src(ui + "AppShell.kt"))
        // the model page: back, the full data rows, Fit / Wireframe, the diagnostics line, CORE DIFFERENCE / FORMATS of the libraries
        val model = src(ui + "ModelPage.kt")
        for (needle in listOf("testTag(\"model_back\")", "GroupTitle(stringResource(R.string.model_data_title))", "testTag(\"detail:\" + r.key)", "ModelDetail.rows(m, categories)"))
            assertTrue("ModelPage.kt lost $needle", needle in model)
        val viewer = src(ui + "ModelViewer.kt")
        for (needle in listOf("testTag(\"viewer_fit\")", "testTag(\"viewer_wire\")", "testTag(\"viewer_render\")", "testTag(\"viewer_status\")", "R.string.viewer_gestures"))
            assertTrue("ModelViewer.kt lost $needle", needle in viewer)
        val library = src(ui + "LibraryPage.kt")
        assertTrue("R.string.lib_core_title" in library && "R.string.lib_formats_title" in library)
        // every model is still declared
        assertTrue(models.map { it.id }.containsAll(listOf("one-lilo", "campidoglio", "drone")))
    }
}
