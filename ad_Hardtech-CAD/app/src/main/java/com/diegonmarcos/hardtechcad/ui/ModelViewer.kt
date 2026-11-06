package com.diegonmarcos.hardtechcad.ui

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.hardtechcad.BuildConfig
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCache
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private val VIEWER_URL = CloudWeb.assetUrl("cad/viewer.html")
private const val MODELS_PATH = "/models/"
private const val ASSETS_PATH = "/assets/"

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
internal fun ModelViewer(m: Model, dir: File) {
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
    // #1542 a view bundled in the APK (Plan.Show.asset) is drawn from the app's own assets: no download, no LFS, nothing to fail on the network
    val bundled = (plan as? CadFormats.Plan.Show)?.asset.orEmpty()
    LaunchedEffect(m) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        if (bundled.isNotEmpty()) { state = ViewState.Loading; return@LaunchedEffect }
        file = runCatching { withContext(Dispatchers.IO) { ModelCache.fetch(BuildConfig.MODELS_BASE, dir, show.path, show.format, show.sha256) } }
            .onFailure { state = ViewState.Failed(it.message ?: it.javaClass.simpleName) }.getOrNull()
        if (file != null) state = ViewState.Loading
    }
    LaunchedEffect(ready, file, bundled) {
        val show = plan as? CadFormats.Plan.Show ?: return@LaunchedEffect
        val f = file
        if (bundled.isEmpty() && f == null) return@LaunchedEffect
        if (!ready) return@LaunchedEffect
        val url = if (bundled.isNotEmpty()) ASSETS_PATH + bundled else MODELS_PATH + f!!.name
        val arg = JSONObject().put("lib", show.lib).put("url", url).put("format", show.format)
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
        // #1560 no rounded clip around the WebView: the page's GL surface is drawn by the WebView's hardware functor, which a non-rectangular
        // Compose clip layer can drop (the only fleet WebGL world inside one; CG-Omniverse and Cloud Nav draw it unclipped). Rounded border only.
        Box(Modifier.fillMaxWidth().height(300.dp).background(Color(0xFF0E1116)).border(1.dp, p.textSecondary.copy(alpha = 0.3f), RoundedCornerShape(12.dp)).testTag("viewer")) {
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
            if (overlay != null) Text(overlay, color = if (state is ViewState.Fetching) p.textSecondary else p.warn, fontSize = KitStoreRow.body,
                modifier = Modifier.align(Alignment.Center).padding(20.dp).testTag("viewer_status"))
        }
        if (plan is CadFormats.Plan.Show) ViewerControls(
            onFit = { web?.evaluateJavascript("window.cadFit&&window.cadFit()", null) },
            onWire = { on -> web?.evaluateJavascript("window.cadWireframe&&window.cadWireframe($on)", null) },
        )
        Text(stringResource(R.string.viewer_model, m.name, m.format.uppercase(), m.licence), color = p.textPrimary, fontSize = KitStoreRow.body)
        if (plan is CadFormats.Plan.Show && plan.derived)
            Text(stringResource(R.string.viewer_derived, plan.format.uppercase(), m.format.uppercase()), color = p.textSecondary, fontSize = KitStoreRow.meta)
    }
}

/** #1542 the viewer's two buttons: fit the model to the view, and the wireframe toggle (orbit, zoom and pan are the touch gestures of the stage). */
@Composable
private fun ViewerControls(onFit: () -> Unit, onWire: (Boolean) -> Unit) {
    val p = LocalKitPalette.current
    var wire by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(KitStoreRow.gap), verticalAlignment = Alignment.CenterVertically) {
        ControlChip(stringResource(R.string.viewer_fit), false, Modifier.testTag("viewer_fit"), onFit)
        ControlChip(stringResource(R.string.viewer_wire), wire, Modifier.testTag("viewer_wire")) { wire = !wire; onWire(wire) }
        Text(stringResource(R.string.viewer_gestures), color = p.textSecondary, fontSize = KitStoreRow.caption)
    }
}

@Composable
private fun ControlChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(KitStoreRow.tight)
    Text(label, color = if (on) p.tileInk else p.accent, fontSize = KitStoreRow.body,
        modifier = modifier.clip(shape).background(if (on) p.accent else Color.Transparent).border(1.dp, p.accent, shape)
            .clickable(onClick = onClick).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight))
}
