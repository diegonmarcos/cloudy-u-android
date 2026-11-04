package com.diegonmarcos.superapp.scraper

import android.graphics.BitmapFactory
import android.content.Context
import android.webkit.WebView
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import android.widget.MediaController
import android.widget.VideoView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.themebg.ThemeColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** #1427 a file opened in-app: code with syntax highlighting, images/audio/video in a full viewer, pages in a WebView. */
@Composable
fun FileViewer(f: SiteFile, c: ThemeColors, onClose: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val route = remember(f) { ViewerRoute.route(f.name, f.mime) }
    var file by remember(f) { mutableStateOf<File?>(null) }
    var error by remember(f) { mutableStateOf<String?>(null) }
    LaunchedEffect(f) {
        withContext(Dispatchers.IO) { runCatching { SiteFilesSource.materialize(ctx, f) } }
            .onSuccess { file = it }.onFailure { error = it.message ?: "cannot open" }
    }
    Column(Modifier.fillMaxSize().background(c.top).testTag("scrapers:viewer:${route.viewer.name}")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹ Back", color = c.accent, modifier = Modifier.clickable(onClick = onClose).padding(end = 14.dp).testTag("scrapers:viewer:back"))
            Column(Modifier.weight(1f)) {
                Text(f.name, color = c.ink, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text("${f.host} · ${f.source.label} · ${human(f.bytes)}", color = c.inkDim, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
        }
        val fl = file
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                error != null -> Text(error.orEmpty(), color = c.inkDim, modifier = Modifier.padding(16.dp))
                fl == null -> Text("Opening…", color = c.inkDim, modifier = Modifier.padding(16.dp))
                else -> when (route.viewer) {
                    Viewer.CODE -> CodeView(fl, route.lang, c)
                    Viewer.IMAGE -> ImagePane(fl)
                    Viewer.AUDIO, Viewer.VIDEO -> MediaView(fl)
                    Viewer.WEB -> AndroidView({ cx -> pageView(cx, fl) }, Modifier.fillMaxSize().testTag("scrapers:viewer:web"))
                    Viewer.NONE -> Text("No in-app viewer for this type (${f.mime.ifBlank { "unknown" }}).", color = c.inkDim, modifier = Modifier.padding(16.dp))
                }
            }
        }
    }
}

/** A cached page or saved archive in the fleet's web view: a local archive, so file access on, JavaScript off. */
private fun pageView(cx: Context, f: File): WebView = CloudWeb.create(cx, WebProfile.BROWSER).apply {
    CloudWeb.site(this, javaScript = false, fileAccess = true)
    loadUrl("file://" + f.absolutePath)
}

/** Token colours: one dark palette, readable on the app's dark background. */
fun styleOf(t: Tok): SpanStyle = when (t) {
    Tok.COMMENT -> SpanStyle(color = Color(0xFF7F8C98), fontStyle = FontStyle.Italic)
    Tok.STRING -> SpanStyle(color = Color(0xFFA5D6A7))
    Tok.NUMBER -> SpanStyle(color = Color(0xFFFFCC80))
    Tok.KEYWORD -> SpanStyle(color = Color(0xFFCE93D8), fontWeight = FontWeight.SemiBold)
    Tok.KEY -> SpanStyle(color = Color(0xFF81D4FA))
    Tok.TAG -> SpanStyle(color = Color(0xFFEF9A9A))
}

fun highlighted(text: String, lang: String): AnnotatedString {
    val t = if (text.length > Highlighter.MAX_CHARS) text.substring(0, Highlighter.MAX_CHARS) else text
    val spans = Highlighter.spans(t, lang)
    return buildAnnotatedString {
        append(t)
        for (s in spans) addStyle(styleOf(s.kind), s.start, s.end)
    }
}

@Composable
private fun CodeView(f: File, lang: String, c: ThemeColors) {
    var text by remember(f) { mutableStateOf<AnnotatedString?>(null) }
    LaunchedEffect(f) {
        text = withContext(Dispatchers.Default) {
            val raw = runCatching { f.readText() }.getOrDefault("")
            highlighted(if (lang == "json") prettyJson(raw) else raw, lang)
        }
    }
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(12.dp)) {
        Text(text ?: AnnotatedString("…"), color = c.ink, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false,
            modifier = Modifier.testTag("scrapers:viewer:code"))
    }
}

/** Minified JSON is unreadable: indent it when it parses, keep it as is otherwise. */
fun prettyJson(raw: String): String = runCatching {
    val s = raw.trim()
    if (s.startsWith("[")) org.json.JSONArray(s).toString(2) else org.json.JSONObject(s).toString(2)
}.getOrDefault(raw)

@Composable
private fun ImagePane(f: File) {
    val bmp = remember(f) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (o.outWidth / s > 4096 || o.outHeight / s > 4096) s *= 2
        BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var ox by remember { mutableFloatStateOf(0f) }
    var oy by remember { mutableFloatStateOf(0f) }
    if (bmp == null) { Text("This image cannot be decoded.", color = Color.Gray, modifier = Modifier.padding(16.dp)); return }
    Box(Modifier.fillMaxSize().background(Color.Black).pointerInput(Unit) {
        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 8f); ox += pan.x; oy += pan.y }
    }, contentAlignment = Alignment.Center) {
        Image(bmp.asImageBitmap(), contentDescription = f.name,
            modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = scale, scaleY = scale, translationX = ox, translationY = oy)
                .testTag("scrapers:viewer:image"))
    }
}

@Composable
private fun MediaView(f: File) {
    AndroidView(
        factory = { cx ->
            VideoView(cx).apply {
                val mc = MediaController(cx); mc.setAnchorView(this); setMediaController(mc)
                setVideoPath(f.path)
                setOnPreparedListener { start(); mc.show(0) }
            }
        },
        onRelease = { it.stopPlayback() },
        modifier = Modifier.fillMaxSize().background(Color.Black).testTag("scrapers:viewer:media"),
    )
}
