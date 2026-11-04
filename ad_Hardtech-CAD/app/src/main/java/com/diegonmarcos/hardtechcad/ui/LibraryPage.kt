package com.diegonmarcos.hardtechcad.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.hardtechcad.BuildConfig
import com.diegonmarcos.hardtechcad.Decl
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.CadLibraries
import com.diegonmarcos.superapp.cad.CadLibrary
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCache
import com.diegonmarcos.superapp.cad.ModelManifest
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val VIEWER_URL = CloudWeb.assetUrl("cad/viewer.html")
private const val MODELS_PATH = "/models/"

/**
 * #1404 A model-library sub-page of MCAD (ui.libraries[id]): the viewer with the page's default model, the Core Difference
 * table of every library side by side, and the formats with a one-line purpose, their viewer lib or store-only reason and the
 * library's models of that format (tap one to view it). The models come from the public LFS repo's models.json, cached.
 */
@Composable
fun LibraryPage(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val libs = remember { CadLibraries.parse(Decl.libraries) }
    val lib = libs.firstOrNull { it.id == id } ?: run { onBack(); return }
    val dir = remember { File(ctx.cacheDir, "cad-models") }
    var models by remember { mutableStateOf<List<Model>?>(null) }
    var manifestFailed by remember { mutableStateOf(false) }
    var selected by remember(id) { mutableStateOf<Model?>(null) }
    LaunchedEffect(id) {
        val text = withContext(Dispatchers.IO) { ModelCache.manifest(BuildConfig.MODELS_BASE, dir) }
        val ms = text?.let(ModelManifest::parse).orEmpty()
        manifestFailed = text == null
        models = ms
        if (selected == null) selected = CadLibraries.defaultModel(lib, ms)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).testTag("library:$id"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lib_back), color = p.accent, fontSize = 14.sp,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        KitSectionHeader(lib.title, lib.subtitle)
        // The viewer stays pinned above the scrolling content: a WebView inside a scrolling column did not paint on the emulator.
        selected?.let { m -> ModelViewer(m, dir) } ?: Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1116)))
        if (manifestFailed) Text(stringResource(R.string.lib_manifest_failed), color = p.bad, fontSize = 13.sp)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupTitle(stringResource(R.string.lib_core_title))
        CoreTable(libs, lib)

        GroupTitle(stringResource(R.string.lib_formats_title))
        lib.formats.forEach { f ->
            val route = CadFormats.route(f.exts.firstOrNull().orEmpty())
            val how = when (route) {
                is CadFormats.Route.Viewer -> stringResource(R.string.lib_viewer, route.lib)
                is CadFormats.Route.StoreOnly -> route.reason
            }
            KitCard(Modifier.testTag("format:${f.label}")) {
                KitSettingsRow(title = f.label, subtitle = f.purpose + "\n" + how)
                val ms = models?.let { CadLibraries.modelsOf(lib, f, it) }.orEmpty()
                if (ms.isEmpty()) Text(stringResource(R.string.lib_models_none), color = p.textSecondary, fontSize = 12.sp)
                ms.forEach { m ->
                    Text("• " + m.name + "  (" + mb(m.size) + ", " + m.licence + ")",
                        color = if (m.id == selected?.id) p.accent else p.textPrimary, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { selected = m }.padding(vertical = 4.dp))
                }
            }
        }
        }
    }
}

/** Core Difference: 3 rows (geometry, need, standards), one column per declared library, this page's column highlighted. */
@Composable
private fun CoreTable(libs: List<CadLibrary>, current: CadLibrary) {
    val p = LocalKitPalette.current
    val labels = mapOf(
        "geometry" to stringResource(R.string.lib_core_geometry),
        "need" to stringResource(R.string.lib_core_need),
        "standards" to stringResource(R.string.lib_core_standards),
    )
    KitCard(Modifier.testTag("core_table")) {
        Row(Modifier.fillMaxWidth()) {
            Cell(stringResource(R.string.lib_aspect), p.textSecondary, bold = true, w = 0.8f)
            libs.forEach { Cell(it.title, if (it.id == current.id) p.accent else p.textSecondary, bold = true) }
        }
        CadLibraries.CORE_ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Cell(labels[row].orEmpty(), p.textSecondary, w = 0.8f)
                libs.forEach { Cell(it.core[row].orEmpty(), if (it.id == current.id) p.textPrimary else p.textSecondary, bold = row == "standards") }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(t: String, c: Color, bold: Boolean = false, w: Float = 1f) =
    Text(t, color = c, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(w).padding(4.dp))

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)

private sealed class ViewState {
    data object Fetching : ViewState()
    data object Loading : ViewState()
    data object Shown : ViewState()
    data class Failed(val why: String) : ViewState()
    data class StoreOnly(val reason: String) : ViewState()
}

/** The viewer: plans [m] through libs:cad-core (derived view, original, or store-only), fetches it once into [dir], shows it. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun ModelViewer(m: Model, dir: File) {
    val p = LocalKitPalette.current
    val plan = remember(m) { CadFormats.plan(m) }
    var state by remember(m) { mutableStateOf<ViewState>(if (plan is CadFormats.Plan.StoreOnly) ViewState.StoreOnly(plan.reason) else ViewState.Fetching) }
    var ready by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var file by remember(m) { mutableStateOf<File?>(null) }
    val bridge = remember {
        object {
            @JavascriptInterface
            fun post(json: String) {
                android.util.Log.i("CadHost", json)
                val e = runCatching { JSONObject(json) }.getOrNull() ?: return
                when (e.optString("type")) {
                    "ready" -> ready = true
                    "shown" -> state = ViewState.Shown
                    "error" -> state = ViewState.Failed(e.optString("message"))
                }
            }
        }
    }
    LaunchedEffect(m) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        file = runCatching { withContext(Dispatchers.IO) { ModelCache.fetch(BuildConfig.MODELS_BASE, dir, show.path, show.format, show.sha256) } }
            .onFailure { state = ViewState.Failed(it.message ?: it.javaClass.simpleName) }.getOrNull()
        if (file != null) state = ViewState.Loading
    }
    LaunchedEffect(ready, file) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        val f = file ?: return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val arg = JSONObject().put("lib", show.lib).put("url", MODELS_PATH + f.name).put("format", show.format)
        web?.evaluateJavascript("window.cadOpen&&window.cadOpen($arg)", null)
        android.util.Log.i("CadHost", "cadOpen " + arg)
        kotlinx.coroutines.delay(20000)
        web?.evaluateJavascript("document.getElementById('s')&&document.getElementById('s').textContent") { android.util.Log.i("CadHost", "status=" + it) }
        // The proof pulls this PNG (cache/cad-snapshot-<id>.png) with run-as: a WebView screenshot does not capture WebGL on an emulator.
        web?.evaluateJavascript("window.cadSnapshot&&window.cadSnapshot()") { v ->
            runCatching {
                val b64 = v.orEmpty().trim('"').substringAfter("base64,", "")
                if (b64.isNotEmpty()) File(dir.parentFile, "cad-snapshot-" + m.id + ".png").writeBytes(android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
            }
        }
    }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1116)).testTag("viewer")) {
            if (plan is CadFormats.Plan.Show) AndroidView(
                factory = { c ->
                    val loader = WebViewAssetLoader.Builder()
                        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(c))
                        .addPathHandler(MODELS_PATH, WebViewAssetLoader.InternalStoragePathHandler(c, dir.apply { mkdirs() }))
                        .build()
                    CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                        wv.addJavascriptInterface(bridge, "CadHost")
                        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, loader) {
                            override fun onRecovered(dead: WebView, fresh: WebView?) {
                                fresh?.addJavascriptInterface(bridge, "CadHost"); web = fresh; ready = false
                            }
                        }
                        wv.loadUrl(VIEWER_URL)
                        web = wv
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            val overlay = when (val s = state) {
                ViewState.Fetching -> stringResource(R.string.viewer_fetching, m.name)
                ViewState.Loading, ViewState.Shown -> null
                is ViewState.Failed -> stringResource(R.string.viewer_failed, s.why)
                is ViewState.StoreOnly -> s.reason
            }
            if (overlay != null) Text(overlay, color = if (state is ViewState.Fetching) p.textSecondary else p.warn, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(20.dp).testTag("viewer_status"))
        }
        Text(stringResource(R.string.viewer_model, m.name, m.format.uppercase(), m.licence), color = p.textPrimary, fontSize = 13.sp)
        if (plan is CadFormats.Plan.Show && plan.derived)
            Text(stringResource(R.string.viewer_derived, plan.format.uppercase(), m.format.uppercase()), color = p.textSecondary, fontSize = 12.sp)
    }
}
