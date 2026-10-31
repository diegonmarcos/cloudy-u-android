@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.cgomniverse.ui

import android.app.backup.BackupManager
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.Checkroom
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.LocalFlorist
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cgomniverse.Avatar
import com.diegonmarcos.cgomniverse.AvatarSpec
import com.diegonmarcos.cgomniverse.Decl
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import com.diegonmarcos.superapp.cgc.AiResult
import com.diegonmarcos.superapp.cgc.CgcCategory
import com.diegonmarcos.superapp.cgc.CgcOption
import com.diegonmarcos.superapp.cgc.CgcSpec
import com.diegonmarcos.superapp.cgc.OpenRouterAvatar
import com.diegonmarcos.superapp.cgc.UrlConnectionHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream

/** Where the configuration is kept: its own small prefs file, the one file the backup rules (res/xml) take. */
const val AVATAR_PREFS = "avatar"
private const val AVATAR_KEY = "config"

/** The editor's test tags (Tags in AppShell keeps the page's and the scene's). */
object AvatarTags {
    const val HOME = "avatar_home"
    const val ROTATE = "avatar_rotate"
    const val EXPORT = "avatar_export"
    const val EXPORT_GLB = "avatar_export_glb"
    const val EXPORT_PNG = "avatar_export_png"
    const val AI = "avatar_ai"
    const val SHEET = "avatar_sheet"
    const val HANDLE = "avatar_sheet_handle"
    const val LOADING = "avatar_loading"
    fun category(id: String) = "avatar_cat_$id"
    fun card(category: String, id: String) = "avatar_card_${category}_$id"
}

// MetaPerson Creator's mobile layout and palette, replicated (layout only: no code or asset of theirs is used).
private val Scene = Color(0xFF121212)
private val SheetBg = Color(0xFF242424)
private val BarBg = Color(0xFF1B1B1B)
private val Chip = Color(0xFF2E2E2E)
private val Purple = Brush.linearGradient(listOf(Color(0xFF7A2FF7), Color(0xFFB13FE0)))
private val CardWhite = Color(0xFFFFFFFF)
private val Muted = Color(0xFF9A9A9A)

/** The declared icon names of data/avatar.json to their glyphs. */
private fun iconFor(name: String): ImageVector = when (name) {
    "outfit" -> Icons.Filled.Checkroom
    "body" -> Icons.Filled.AccessibilityNew
    "skin" -> Icons.Filled.Palette
    "features" -> Icons.Filled.LocalFlorist
    "head" -> Icons.Filled.Face
    "hair" -> Icons.Filled.ContentCut
    "glasses" -> Icons.Filled.Visibility
    "presets" -> Icons.Filled.Groups
    "animations" -> Icons.AutoMirrored.Filled.DirectionsRun
    else -> Icons.Filled.AutoAwesome
}

/**
 * The bridge cgc.js talks to (window.CgcHost): its events, and exports streamed in base64 chunks into Downloads. The scene
 * config is pushed to the page (window.cgcBoot from onPageFinished), never pulled by a synchronous call (#1373 launch freeze).
 * JavaScript calls arrive on a WebView thread; every state change is posted to the main thread.
 */
private class CgcBridge(private val ctx: Context, private val onEvent: (JSONObject) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var out: OutputStream? = null
    private var name = ""

    @JavascriptInterface fun event(json: String) {
        android.util.Log.i("CgcHost", json.take(400)) // scene events only (ready, loaded, quality, error): no user data
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        main.post { onEvent(o) }
    }

    @JavascriptInterface fun saveBegin(fileName: String, mime: String) {
        name = fileName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifEmpty { "avatar.bin" }
        out = runCatching { open(name, mime) }.getOrNull()
    }

    @JavascriptInterface fun saveChunk(b64: String) {
        runCatching { out?.write(Base64.decode(b64, Base64.DEFAULT)) }
    }

    @JavascriptInterface fun saveEnd() {
        val ok = out != null
        runCatching { out?.close() }
        out = null
        main.post { onEvent(JSONObject().put("type", if (ok) "stored" else "store_failed").put("name", name)) }
    }

    /** Downloads/CG-Omniverse on Android 10+ (MediaStore, no permission); the app's own Downloads folder before. */
    private fun open(fileName: String, mime: String): OutputStream {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val v = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CG-Omniverse")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("no uri")
            return ctx.contentResolver.openOutputStream(uri) ?: error("no stream")
        }
        val dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: ctx.filesDir
        return java.io.File(dir, fileName).outputStream()
    }
}

/**
 * The Avatar editor, laid out like MetaPerson Creator's mobile editor: the owner's avatar full screen on a dark stage
 * (libs:cgc-3d over three.js in a WEB_WORLD view: drag rotates, pinch zooms), Home and AI at the top left, Rotate and Export
 * (GLB, PNG) at the top right, a bottom sheet (drag handle, the category's title, a row of option cards) over a bar of category
 * icons. Everything shown comes from data/avatar.json; a category without an engine yet shows its cards as coming soon. Every
 * change is saved at once and announced to Android's backup.
 */
@Composable
fun AvatarPage() {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    val spec = Decl.avatar
    // #1373 launch freeze: nothing heavy on the main thread. The prefs file is read on IO (the defaults show meanwhile) and
    // the WebView is created only after the page's first frame is on screen, behind the loading state.
    var prefs by remember { mutableStateOf<SharedPreferences?>(null) }
    val keys = remember { AvatarKeys(ctx) }
    val scope = rememberCoroutineScope()
    var cfg by remember { mutableStateOf(Avatar.load(spec, null)) }
    var showScene by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val (p, saved) = withContext(Dispatchers.IO) {
            val f = ctx.getSharedPreferences(AVATAR_PREFS, Context.MODE_PRIVATE)
            f to f.getString(AVATAR_KEY, null)
        }
        cfg = Avatar.load(spec, saved); prefs = p
    }
    LaunchedEffect(Unit) { withFrameNanos { }; withFrameNanos { }; showScene = true }
    var catId by rememberSaveable { mutableStateOf(spec.categories.lastOrNull()?.id.orEmpty()) }
    var sheetOpen by rememberSaveable { mutableStateOf(true) }
    var exportOpen by remember { mutableStateOf(false) }
    var aiOpen by remember { mutableStateOf(false) }
    var web by remember { mutableStateOf<WebView?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var rotating by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var topPx by remember { mutableStateOf(0) }
    var bottomPx by remember { mutableStateOf(0) }
    fun css(px: Int) = (px / density.density).toInt()

    fun update(next: Map<String, String>) {
        if (next == cfg) return
        cfg = next
        prefs?.edit()?.putString(AVATAR_KEY, Avatar.save(next))?.apply()
        BackupManager(ctx).dataChanged()
    }
    fun js(code: String) { web?.evaluateJavascript("window.cgc&&($code)", null) }

    LaunchedEffect(cfg, loaded) { if (loaded) js("window.cgc.apply(" + JSONObject.quote(Avatar.sceneOps(spec, cfg)) + ")") }
    LaunchedEffect(topPx, bottomPx, loaded) {
        if (loaded) js("window.cgc.apply({insets:{top:${css(topPx)},bottom:${css(bottomPx)}}})")
    }
    LaunchedEffect(note) { if (note != null) { delay(2600); note = null } }
    DisposableEffect(Unit) { onDispose { web?.destroy(); web = null } }

    val bridge = remember {
        CgcBridge(ctx) { e ->
            when (e.optString("type")) {
                "loaded" -> loaded = true
                "camera" -> rotating = e.optBoolean("autoRotate")
                "stored" -> note = spec.text("saved") + ": " + e.optString("name")
                "store_failed", "error" -> note = spec.text("ai_failed")
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Scene).testTag(Tags.AVATAR)) {
        if (showScene) AndroidView(
            factory = { c ->
                CloudWeb.create(c, WebProfile.WEB_WORLD).also { wv ->
                    wv.addJavascriptInterface(bridge, "CgcHost")
                    wv.webViewClient = object : CloudWebClient(WebProfile.WEB_WORLD, CloudWeb.assetLoader(c)) {
                        override fun onPageFinished(view: WebView, url: String?) {
                            super.onPageFinished(view, url)
                            val conf = Avatar.sceneConfig(spec, css(topPx), css(bottomPx))
                            view.evaluateJavascript("window.cgcBoot&&window.cgcBoot(" + JSONObject.quote(conf) + ")", null)
                        }
                        override fun onRecovered(dead: WebView, fresh: WebView?) {
                            fresh?.addJavascriptInterface(bridge, "CgcHost")
                            web = fresh; loaded = false
                        }
                    }
                    wv.loadUrl(Web.AVATAR_URL)
                    web = wv
                }
            },
            modifier = Modifier.fillMaxSize().testTag(Tags.AVATAR_SCENE),
        )
        if (!loaded) Column(
            Modifier.align(Alignment.Center).testTag(AvatarTags.LOADING),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(color = Color(0xFFB13FE0), strokeWidth = 3.dp, modifier = Modifier.size(36.dp))
            Text(spec.text("loading"), color = Muted, fontSize = 13.sp)
        }

        // top chrome: Home (+ AI) at the left, Rotate + Export at the right
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp).onSizeChanged { topPx = it.height },
            verticalAlignment = Alignment.Top,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SquareButton(Icons.Outlined.Home, spec.text("home"), AvatarTags.HOME) { exportOpen = false; js("window.cgc.apply({camera:'reset'})") }
                if (spec.ai != null) SquareButton(Icons.Filled.AutoAwesome, spec.text("ai"), AvatarTags.AI) { aiOpen = !aiOpen; exportOpen = false }
            }
            Spacer(Modifier.weight(1f))
            SquareButton(Icons.Filled.Autorenew, spec.text("rotate"), AvatarTags.ROTATE, active = rotating) {
                js("window.cgc.apply({autoRotate:${!rotating}})")
            }
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
                PurplePill(spec.text("export"), AvatarTags.EXPORT) { exportOpen = !exportOpen }
                if (exportOpen) {
                    PurplePill(spec.text("export_glb"), AvatarTags.EXPORT_GLB) { exportOpen = false; js("window.cgc.exportGLB('avatar.glb')") }
                    PurplePill(spec.text("export_png"), AvatarTags.EXPORT_PNG) { exportOpen = false; js("window.cgc.snapshotPNG('avatar.png')") }
                }
            }
        }

        // bottom: the sheet (handle, title, cards) over the category bar
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { bottomPx = it.height }) {
            val cat = spec.category(catId)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp)).background(SheetBg)
                    .testTag(AvatarTags.SHEET),
            ) {
                Box(
                    Modifier.fillMaxWidth().height(28.dp).testTag(AvatarTags.HANDLE)
                        .clickable { sheetOpen = !sheetOpen }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dy -> if (dy > 12) sheetOpen = false else if (dy < -12) sheetOpen = true }
                        },
                    contentAlignment = Alignment.Center,
                ) { Box(Modifier.size(width = 44.dp, height = 5.dp).clip(CircleShape).background(Color(0xFF5A5A5A))) }
                if (sheetOpen && cat != null) {
                    Text(cat.title, color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 24.dp, bottom = 14.dp))
                    val soon = Avatar.soon(spec, cat.id)
                    val listState = rememberLazyListState()
                    LaunchedEffect(cat.id) {
                        val i = cat.options.indexOfFirst { it.id == cfg[cat.id] }
                        if (i > 0) listState.scrollToItem(i)
                    }
                    LazyRow(
                        state = listState, contentPadding = PaddingValues(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        items(cat.options, key = { it.id }) { o ->
                            OptionCard(spec, cat, o, selected = !soon && cfg[cat.id] == o.id, soon = soon) {
                                update(Avatar.set(spec, cfg, cat.id, o.id))
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
            Row(
                Modifier.fillMaxWidth().background(BarBg).padding(horizontal = 4.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // one equal slot per category, so all nine fit a 360 dp phone (fixed 44 dp tiles pushed the last off-screen)
                spec.categories.forEach { c ->
                    val on = c.id == catId
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(if (on) Color.Black else Color.Transparent)
                                .clickable { catId = c.id; sheetOpen = true }.testTag(AvatarTags.category(c.id)),
                            contentAlignment = Alignment.Center,
                        ) { Icon(iconFor(c.icon), contentDescription = c.label, tint = Color.White, modifier = Modifier.size(26.dp)) }
                    }
                }
            }
        }

        if (aiOpen && spec.ai != null) {
            AiPanel(spec, keys, Modifier.align(Alignment.TopCenter).padding(top = 84.dp, start = 16.dp, end = 16.dp),
                onClose = { aiOpen = false }) { text, done ->
                scope.launch {
                    val r = withContext(Dispatchers.IO) { OpenRouterAvatar(keys, UrlConnectionHttp()).describe(spec, cfg, text, Avatar.engine(spec)) }
                    when (r) {
                        is AiResult.Applied -> {
                            update(r.config)
                            r.applied.keys.lastOrNull()?.let { catId = it }
                            done(spec.text("ai_applied") + ": " + r.applied.entries.joinToString { it.key + " " + it.value }.ifEmpty { "-" })
                        }
                        AiResult.NoKey -> done(spec.text("ai_no_key"))
                        else -> done(spec.text("ai_failed") + " (" + r.javaClass.simpleName + (if (r is AiResult.Http) " " + r.code else "") + ")")
                    }
                }
            }
        }

        note?.let {
            Text(
                it, color = Color.White, fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xE6303030))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun SquareButton(icon: ImageVector, label: String, tag: String, active: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.size(56.dp).clip(RoundedCornerShape(18.dp)).then(if (active) Modifier.background(Purple) else Modifier.background(Chip))
            .clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(28.dp)) }
}

@Composable
private fun PurplePill(label: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.width(104.dp).height(56.dp).clip(RoundedCornerShape(18.dp)).background(Purple).clickable(onClick = onClick).testTag(tag),
        contentAlignment = Alignment.Center,
    ) { Text(label, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
}

private val thumbs = HashMap<String, ImageBitmap?>()

@Composable
private fun thumb(path: String?): ImageBitmap? {
    if (path == null) return null
    val ctx = LocalContext.current
    // decoded on IO (#1373): a sheet of cards must not decode PNGs on the main thread while the page is starting
    val img by produceState(thumbs[path], path) {
        if (value == null && !thumbs.containsKey(path)) {
            value = withContext(Dispatchers.IO) {
                runCatching { ctx.assets.open("avatar/$path").use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()
            }
            synchronized(thumbs) { thumbs[path] = value }
        }
    }
    return img
}

/** One option card: a white tile (the selected one purple) with the option's thumbnail or colour, or its label. */
@Composable
private fun OptionCard(spec: AvatarSpec, cat: CgcCategory, o: CgcOption, selected: Boolean, soon: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Box(
        Modifier.size(86.dp).clip(shape)
            .then(if (selected) Modifier.background(Purple) else Modifier.background(if (soon) Color(0xFFE6E6E6) else CardWhite))
            .clickable(enabled = !soon, onClick = onClick).testTag(AvatarTags.card(cat.id, o.id)),
        contentAlignment = Alignment.Center,
    ) {
        val img = thumb(o.thumb)
        val isColor = cat.kind == CgcSpec.KIND_COLOR || cat.kind == CgcSpec.KIND_TINT
        when {
            img != null -> Image(img, contentDescription = o.label, modifier = Modifier.fillMaxSize().padding(4.dp).alpha(if (soon) 0.35f else 1f))
            isColor -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val c = o.color
                Box(
                    Modifier.size(40.dp).clip(CircleShape)
                        .background(if (c != null) Color(android.graphics.Color.parseColor(c)) else Color.Transparent)
                        .border(2.dp, if (c == null) Color(0xFFBDBDBD) else Color.Transparent, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (c == null) Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = Color(0xFF8A8A8A), modifier = Modifier.size(18.dp)) }
                Spacer(Modifier.height(6.dp))
                Text(o.label, color = if (selected) Color.White else Color(0xFF444444), fontSize = 11.sp)
            }
            else -> Text(o.label, color = if (selected) Color.White else Color(0xFF333333).copy(alpha = if (soon) 0.5f else 1f),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)
        }
        if (soon) Text(
            spec.text("soon"), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp).clip(RoundedCornerShape(6.dp))
                .background(Color(0xCC7A2FF7)).padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "Describe your avatar": the free text, the user's own OpenRouter key (stored encrypted, never shown again), the result. */
@Composable
private fun AiPanel(spec: AvatarSpec, keys: AvatarKeys, modifier: Modifier, onClose: () -> Unit, run: (String, (String) -> Unit) -> Unit) {
    val slot = spec.ai?.keySlot.orEmpty()
    var text by rememberSaveable { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var hasKey by remember { mutableStateOf(false) }
    LaunchedEffect(slot) { hasKey = withContext(Dispatchers.IO) { keys.has(slot) } } // Keystore off the main thread
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(SheetBg).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(spec.text("ai"), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("×", color = Muted, fontSize = 22.sp, modifier = Modifier.clickable(onClick = onClose).padding(horizontal = 6.dp))
        }
        OutlinedTextField(value = text, onValueChange = { text = it }, placeholder = { Text(spec.text("ai_hint")) },
            modifier = Modifier.fillMaxWidth(), minLines = 2)
        if (hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(spec.text("ai_key_saved"), color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(spec.text("ai_key_clear"), color = Color(0xFFB98CFF), fontSize = 12.sp,
                    modifier = Modifier.clickable { keys.clear(slot); hasKey = false })
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = key, onValueChange = { key = it }, label = { Text(spec.text("ai_key")) }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.weight(1f))
                Box(Modifier.height(48.dp).clip(RoundedCornerShape(14.dp)).background(Purple)
                    .clickable { if (keys.save(slot, key)) { key = ""; hasKey = true } }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center) { Text(spec.text("ai_key_save"), color = Color.White, fontSize = 13.sp) }
            }
        }
        Box(
            Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp)).background(Purple)
                .clickable(enabled = !busy && text.isNotBlank()) {
                    busy = true; status = spec.text("ai_working")
                    run(text) { status = it; busy = false }
                },
            contentAlignment = Alignment.Center,
        ) { Text(spec.text("ai_go"), color = Color.White, fontWeight = FontWeight.Bold) }
        status?.let { Text(it, color = Muted, fontSize = 12.sp) }
    }
}
