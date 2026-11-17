package com.diegonmarcos.cloudkeyboard.handwrite

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudkeyboard.grabber.GC
import com.diegonmarcos.cloudkeyboard.grabber.GrabberTheme
import helium314.keyboard.keyboard.handwrite.Handwriting
import helium314.keyboard.keyboard.handwrite.HandwriteSettings
import helium314.keyboard.keyboard.handwrite.Recognition
import helium314.keyboard.keyboard.handwrite.StrokeCapture
import helium314.keyboard.latin.utils.prefs

/** #1781 the two pages of the HandWrite tab (host routes handwrite.settings and handwrite.practice), drawn in the Grabber's console palette. */
object HandWriteHost {
    @Composable
    fun Page(id: String) {
        GrabberTheme {
            Column(Modifier.fillMaxSize().background(GC.bg).verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (id == "handwrite.practice") PracticePage() else SettingsPage()
            }
        }
    }
}

private val shape = RoundedCornerShape(10.dp)

@Composable
private fun Btn(label: String, enabled: Boolean = true, onClick: () -> Unit) =
    Box(Modifier.clip10().background(if (enabled) GC.keyHi else GC.key).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label, color = if (enabled) GC.text else GC.text3, fontSize = 12.sp)
    }

private fun Modifier.clip10() = this.then(Modifier.border(1.dp, GC.hairline, shape))

@Composable
private fun SettingsPage() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.prefs() }
    var langs by remember { mutableStateOf(HandwriteSettings.languages(prefs.getString(HandwriteSettings.PREF_LANGUAGES, null))) }
    var delay by remember { mutableIntStateOf(prefs.getInt(HandwriteSettings.PREF_AUTOCOMMIT_MS, HandwriteSettings.DEFAULT_AUTOCOMMIT_MS)) }
    var width by remember { mutableIntStateOf(prefs.getInt(HandwriteSettings.PREF_STROKE_DP, HandwriteSettings.DEFAULT_STROKE_DP)) }
    // language tag -> status line: "checking", "ready", "not downloaded", "downloading", or the failure
    val status = remember { mutableStateOf(mapOf<String, String>()) }
    fun set(tag: String, s: String) { status.value = status.value + (tag to s) }
    fun refresh(tag: String) {
        val e = Handwriting.engine ?: return set(tag, "engine unavailable")
        e.isModelReady(tag) { set(tag, if (it) "ready" else "not downloaded") }
    }
    LaunchedEffect(Unit) { MlKitHandwritingEngine.LANGUAGES.forEach { refresh(it.first) } }

    Text("HANDWRITE", color = GC.accent, fontSize = 11.sp)
    Text("Write with a finger or a stylus on the keyboard's HandWrite panel (toolbar, last key). Recognition runs on this phone with Google ML Kit Digital Ink; " +
        "a language's model is downloaded once, from here, and is the only thing that uses the network.", color = GC.text2, fontSize = 12.sp)
    Text("LANGUAGES", color = GC.text3, fontSize = 10.sp)
    MlKitHandwritingEngine.LANGUAGES.forEach { (tag, name) ->
        val on = tag in langs
        val st = status.value[tag] ?: "checking"
        Row(Modifier.fillMaxWidth().clip10().background(GC.box).padding(10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text("$name ($tag)", color = GC.text, fontSize = 13.sp)
                Text("model: $st", color = if (st == "ready") GC.ok else GC.text2, fontSize = 11.sp)
            }
            Btn(if (on) "On" else "Off") {
                langs = if (on && langs.size > 1) langs - tag else if (on) langs else langs + tag
                prefs.edit().putString(HandwriteSettings.PREF_LANGUAGES, langs.joinToString(",")).apply()
            }
            if (st == "ready") Btn("Delete") { Handwriting.engine?.delete(tag) { refresh(tag) } }
            else Btn("Download", enabled = st != "downloading") {
                set(tag, "downloading")
                Handwriting.engine?.download(tag) { ok, why -> if (ok) refresh(tag) else set(tag, "failed: $why") }
            }
        }
    }
    Text("AUTO-COMMIT DELAY", color = GC.text3, fontSize = 10.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("-") { delay = HandwriteSettings.autoCommitMs(delay - 100); prefs.edit().putInt(HandwriteSettings.PREF_AUTOCOMMIT_MS, delay).apply() }
        Text("${HandwriteSettings.autoCommitMs(delay)} ms after the last stroke", color = GC.text, fontSize = 13.sp)
        Btn("+") { delay = HandwriteSettings.autoCommitMs(delay + 100); prefs.edit().putInt(HandwriteSettings.PREF_AUTOCOMMIT_MS, delay).apply() }
    }
    Text("STROKE WIDTH", color = GC.text3, fontSize = 10.sp)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("-") { width = HandwriteSettings.strokeDp(width - 1); prefs.edit().putInt(HandwriteSettings.PREF_STROKE_DP, width).apply() }
        Text("${HandwriteSettings.strokeDp(width)} dp", color = GC.text, fontSize = 13.sp)
        Btn("+") { width = HandwriteSettings.strokeDp(width + 1); prefs.edit().putInt(HandwriteSettings.PREF_STROKE_DP, width).apply() }
    }
}

@Composable
private fun PracticePage() {
    val ctx: Context = LocalContext.current
    val prefs = remember { ctx.prefs() }
    val capture = remember { StrokeCapture() }
    var tick by remember { mutableIntStateOf(0) }
    var result by remember { mutableStateOf<Recognition?>(null) }
    val lang = HandwriteSettings.active(prefs.getString(HandwriteSettings.PREF_LANGUAGES, null), prefs.getString(HandwriteSettings.PREF_ACTIVE_LANGUAGE, null))
    val widthDp = HandwriteSettings.strokeDp(prefs.getInt(HandwriteSettings.PREF_STROKE_DP, HandwriteSettings.DEFAULT_STROKE_DP))
    fun recognise() {
        val e = Handwriting.engine ?: run { result = Recognition.NoEngine; return }
        if (capture.isEmpty) { result = null; return }
        e.recognize(capture.strokes.toList(), lang, "") { result = it }
    }
    Text("PRACTICE PAD", color = GC.accent, fontSize = 11.sp)
    Text("Write here to try the recognizer ($lang). Nothing is typed anywhere.", color = GC.text2, fontSize = 12.sp)
    Canvas(Modifier.fillMaxWidth().height(240.dp).clip10().background(GC.box).pointerInput(Unit) {
        var last = Offset.Zero
        detectDragGestures(
            onDragStart = { o -> last = o; capture.down(o.x, o.y, System.currentTimeMillis()); tick++ },
            onDrag = { ch, _ -> last = ch.position; capture.move(ch.position.x, ch.position.y, System.currentTimeMillis()); tick++ },
            onDragEnd = { capture.up(last.x, last.y, System.currentTimeMillis()); tick++; recognise() },
            onDragCancel = { capture.up(last.x, last.y, System.currentTimeMillis()); tick++; recognise() },
        )
    }) {
        tick // read: a stroke point redraws the pad
        for (s in capture.strokes) {
            val p = Path()
            s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }
            drawPath(p, GC.text, style = Stroke(width = widthDp.dp.toPx(), cap = StrokeCap.Round))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Btn("Undo stroke") { capture.undoLast(); tick++; recognise() }
        Btn("Clear") { capture.clear(); tick++; result = null }
    }
    Text(when (val r = result) {
        null -> "Candidates appear here"
        is Recognition.Candidates -> if (r.texts.isEmpty()) "Nothing recognised" else "Candidates: " + r.texts.joinToString("  |  ")
        Recognition.NoModel -> "The $lang model is not downloaded: open Handwriting and download it"
        Recognition.NoEngine -> "Handwriting recognition is not available in this build"
        is Recognition.Failed -> r.why
    }, color = GC.text, fontSize = 14.sp)
}
