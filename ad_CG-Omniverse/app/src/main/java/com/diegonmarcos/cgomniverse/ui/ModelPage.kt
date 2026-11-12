package com.diegonmarcos.cgomniverse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ViewInAr
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cgomniverse.ModelRef
import com.diegonmarcos.cgomniverse.R
import com.diegonmarcos.cgomniverse.World
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** #1701 The model viewer's page (assets/model/index.html, three.js over libs:cgc-3d's loader), served by CloudWeb.assetLoader so its ES modules load. */
object ModelWeb {
    val URL: String get() = CloudWeb.assetUrl("model/index.html")

    /** What the page's omniModel(cfg) takes: the GLB address, its SHA-256 and size, the name (JSON, quoted for evaluateJavascript). */
    fun config(w: World, m: ModelRef): String = JSONObject()
        .put("glb", m.glb).put("sha256", m.sha256).put("size", m.size).put("name", w.name).toString()

    /** The facts line of the card and the viewer's header: format, parts and triangles (what the declaration knows). */
    fun facts(m: ModelRef): String = buildList {
        add(m.format)
        if (m.parts > 0) add(m.parts.toString() + (if (m.parts == 1) " part" else " parts"))
        if (m.triangles > 0) add("%,d triangles".format(java.util.Locale.US, m.triangles))
        add("%.1f MB".format(java.util.Locale.US, m.size / 1048576.0))
    }.joinToString(" - ")
}

/**
 * One 3D model filling its Galaxy tab: the model viewer page in a WebView (rotate, pinch zoom, auto-rotate, reset, wireframe), a bar with
 * reload and the originals repo in the browser. The page downloads the GLB at runtime (progress, cancel, SHA-256 check, cached), and says
 * why when it cannot; a crashed renderer is replaced by the page again, never a crash.
 */
@Composable
fun ModelView(w: World, m: ModelRef, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var generation by remember(w.id) { mutableIntStateOf(0) }
    DisposableEffect(w.id) { onDispose { web?.destroy(); web = null } }
    Column(modifier.background(Color.Black).testTag(Tags.MODEL)) {
        Row(Modifier.fillMaxWidth().background(p.surface).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.ViewInAr, null, tint = p.accent, modifier = Modifier.padding(start = 10.dp).size(20.dp))
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(w.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = p.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(ModelWeb.facts(m), style = MaterialTheme.typography.labelSmall, color = p.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { generation++ }, modifier = Modifier.testTag(Tags.MODEL_RELOAD)) {
                Icon(Icons.Filled.Refresh, stringResource(R.string.world_reload), tint = p.textPrimary)
            }
            IconButton(onClick = { Web.openInBrowser(ctx, w.url) }, modifier = Modifier.testTag(Tags.MODEL_SOURCE)) {
                Icon(Icons.Filled.OpenInBrowser, stringResource(R.string.open_browser), tint = p.textPrimary)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            key(w.id, generation) {
                AndroidView(factory = { c ->
                    CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                        wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, CloudWeb.assetLoader(c)) {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                view.evaluateJavascript("window.omniModel&&window.omniModel(" + ModelWeb.config(w, m) + ")", null)
                            }
                            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                if (web === view) web = null
                                view.destroy(); generation++; return true
                            }
                        }
                        wv.loadUrl(ModelWeb.URL)
                        web = wv
                    }
                }, modifier = Modifier.fillMaxSize().testTag(Tags.MODEL_WEB))
            }
        }
    }
}

/**
 * A model's card photo: downloaded at runtime (https only) into the app's cache and decoded small; while it loads, or when it cannot
 * (offline), a quiet placeholder with the cube icon, never an error and never a baked image.
 */
@Composable
fun ModelPhoto(m: ModelRef, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val bmp by produceState<Bitmap?>(null, m.photo) { value = withContext(Dispatchers.IO) { ModelPhotos.load(File(ctx.cacheDir, "model-photos"), m.photo) } }
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(p.surface), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b.asImageBitmap(), null, Modifier.fillMaxSize().testTag(Tags.MODEL_PHOTO), contentScale = ContentScale.Crop)
        else Icon(Icons.Filled.ViewInAr, null, tint = p.textSecondary, modifier = Modifier.size(36.dp))
    }
}

/** The photo loader: a disk cache by the address's SHA-256, a 4 MB / 10 s bound, https only; any failure answers null. */
object ModelPhotos {
    private const val MAX_BYTES = 4 * 1024 * 1024
    private const val TARGET_PX = 720

    fun cacheName(url: String): String = MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".img"

    fun load(dir: File, url: String): Bitmap? = runCatching {
        if (!url.startsWith("https://")) return null
        dir.mkdirs()
        val f = File(dir, cacheName(url))
        if (!f.isFile || f.length() == 0L) {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 10_000
            try {
                if (c.responseCode != 200) return null
                val tmp = File(dir, f.name + ".part")
                c.inputStream.use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(16 * 1024); var n = 0; var r: Int
                    while (i.read(buf).also { r = it } > 0) { n += r; if (n > MAX_BYTES) { tmp.delete(); return null }; o.write(buf, 0, r) }
                } }
                if (!tmp.renameTo(f)) { tmp.delete(); return null }
            } finally { c.disconnect() }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var s = 1
        while (bounds.outWidth / (s * 2) >= TARGET_PX) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()
}
