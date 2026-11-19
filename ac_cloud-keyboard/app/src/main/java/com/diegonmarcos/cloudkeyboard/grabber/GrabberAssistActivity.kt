package com.diegonmarcos.cloudkeyboard.grabber

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudkeyboard.R
import com.diegonmarcos.superapp.mlocr.MlKitOcr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1825 Grabber Screen Selection from the default-assistant gesture ([GrabberVoiceSession]). The structure's text blocks are drawn
 * over the screenshot (when Android sent one) at their bounds; tap a block to pick it, drag across blocks to pick several. "Read
 * pixels" runs the on-device OCR (#1754) on the screenshot (the whole screen, or a dragged region in region mode) for text that is
 * only pixels. Without a screenshot, "Capture" starts the one-shot MediaProjection capture of Screen Selection instead.
 */
class GrabberAssistActivity : ComponentActivity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(AColor.TRANSPARENT))
        setContent { GrabberTheme { Screen() } }
    }

    override fun onDestroy() {
        GrabberAssistBus.screenshot = null
        scope.cancel()
        super.onDestroy()
    }

    private fun openPanel(text: String) = GrabberModel(applicationContext, scope, "screen").also { it.readOnly = true; it.setText(text); it.grab() }

    @Composable
    private fun Screen() {
        val grab = GrabberAssistBus.current
        val shot = GrabberAssistBus.screenshot
        val picked = remember(grab?.at) { mutableStateListOf<TextBlock>() }
        var model by remember(grab?.at) { mutableStateOf<GrabberModel?>(null) }
        var busy by remember { mutableStateOf(false) }
        var region by remember { mutableStateOf(false) }
        var note by remember { mutableStateOf("") }
        var view by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var drag by remember { mutableStateOf<Pair<Offset, Offset>?>(null) }
        val blocks = grab?.blocks.orEmpty()
        val sw = (grab?.screenW ?: 1).coerceAtLeast(1); val sh = (grab?.screenH ?: 1).coerceAtLeast(1)
        BackHandler { if (model != null) model = null else finish() }

        fun ocr(crop: android.graphics.Rect?) {
            val b = shot ?: return
            busy = true; note = ""
            scope.launch {
                val r = withContext(Dispatchers.Default) {
                    runCatching {
                        val c = if (crop == null) b else Bitmap.createBitmap(b, crop.left.coerceIn(0, b.width - 1), crop.top.coerceIn(0, b.height - 1),
                            (crop.width()).coerceIn(1, b.width - crop.left.coerceIn(0, b.width - 1)), (crop.height()).coerceIn(1, b.height - crop.top.coerceIn(0, b.height - 1)))
                        MlKitOcr.read(c)
                    }.getOrNull()
                }
                busy = false
                val t = r?.text?.trim().orEmpty()
                if (t.isEmpty()) note = r?.error ?: getString(R.string.grabber_assist_ocr_empty) else model = openPanel(t)
            }
        }

        Box(Modifier.fillMaxSize().background(GC.bg)) {
            if (shot != null) Image(shot.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = if (shot != null) 0.43f else 0f))
                .onSizeChanged { view = it }
                .pointerInput(blocks, region) {
                    detectTapGestures { p ->
                        val b = AssistFlatten.hit(blocks, (p.x * sw / view.width.coerceAtLeast(1)).toInt(), (p.y * sh / view.height.coerceAtLeast(1)).toInt())
                        if (b != null) { if (!picked.remove(b)) picked.add(b) }
                    }
                }
                .pointerInput(blocks, region, shot) {
                    var start = Offset.Zero
                    detectDragGestures(
                        onDragStart = { start = it; drag = it to it },
                        onDrag = { c, _ ->
                            drag = start to c.position
                            if (!region) {
                                val k = { o: Offset -> Pair((o.x * sw / view.width.coerceAtLeast(1)).toInt(), (o.y * sh / view.height.coerceAtLeast(1)).toInt()) }
                                val (x0, y0) = k(start); val (x1, y1) = k(c.position)
                                AssistFlatten.inRect(blocks, x0, y0, x1, y1).forEach { if (it !in picked) picked.add(it) }
                            }
                        },
                        onDragEnd = {
                            val d = drag; drag = null
                            if (region && d != null && shot != null) {
                                val fx = shot.width / view.width.coerceAtLeast(1).toFloat(); val fy = shot.height / view.height.coerceAtLeast(1).toFloat()
                                ocr(android.graphics.Rect((minOf(d.first.x, d.second.x) * fx).toInt(), (minOf(d.first.y, d.second.y) * fy).toInt(),
                                    (maxOf(d.first.x, d.second.x) * fx).toInt(), (maxOf(d.first.y, d.second.y) * fy).toInt()))
                            }
                        },
                        onDragCancel = { drag = null },
                    )
                }) {
                Canvas(Modifier.fillMaxSize()) {
                    val kx = size.width / sw; val ky = size.height / sh
                    blocks.forEach { b ->
                        val sel = b in picked
                        val tl = Offset(b.left * kx, b.top * ky); val sz = Size((b.right - b.left) * kx, (b.bottom - b.top) * ky)
                        if (sel) drawRect(GC.accent.copy(alpha = 0.30f), tl, sz)
                        drawRect(if (sel) GC.accent else GC.ok.copy(alpha = 0.55f), tl, sz, style = Stroke(if (sel) 2f else 1f))
                    }
                    drag?.let { (a, b) -> drawRect(GC.warn, Offset(minOf(a.x, b.x), minOf(a.y, b.y)), Size(kotlin.math.abs(a.x - b.x), kotlin.math.abs(a.y - b.y)), style = Stroke(3f)) }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(GC.sheet.copy(alpha = 0.96f)).navigationBarsPadding().padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(stringResource(R.string.grabber_assist_title), color = GC.accent, fontFamily = Mono, fontSize = 10.sp)
                Text(stringResource(R.string.grabber_assist_status, blocks.size, picked.size, if (shot != null) stringResource(R.string.grabber_assist_shot_ready) else stringResource(R.string.grabber_assist_shot_none)),
                    color = GC.text2, fontSize = 11.sp)
                if (blocks.isEmpty()) Text(stringResource(R.string.grabber_assist_no_blocks), color = GC.warn, fontSize = 11.sp)
                if (shot == null) Text(stringResource(R.string.grabber_assist_no_shot), color = GC.text3, fontSize = 10.sp)
                if (note.isNotEmpty()) Text(note, color = GC.bad, fontSize = 11.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KeyChip(stringResource(R.string.grabber_assist_grab, picked.size), accent = true, enabled = picked.isNotEmpty()) { model = openPanel(AssistFlatten.join(picked)) }
                    KeyChip(stringResource(R.string.grabber_assist_all)) { picked.clear(); picked.addAll(blocks) }
                    KeyChip(stringResource(R.string.grabber_assist_clear), enabled = picked.isNotEmpty()) { picked.clear() }
                    if (shot != null) {
                        KeyChip(stringResource(R.string.grabber_assist_ocr), enabled = !busy) { ocr(null) }
                        KeyChip(stringResource(R.string.grabber_assist_region), accent = region) { region = !region }
                    } else {
                        KeyChip(stringResource(R.string.grabber_assist_capture)) {
                            startActivity(Intent(this@GrabberAssistActivity, GrabberScreenActivity::class.java).putExtra(GrabberScreenActivity.EXTRA_DELAY, 700L).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            finish()
                        }
                    }
                    KeyChip(stringResource(R.string.grabber_assist_close)) { finish() }
                }
            }
            val m = model
            if (m != null) GrabberSheet(m, onReply = {}, onClose = { model = null }, heading = "GRABBER · SCREEN SELECTION")
        }
    }
}
