// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwrite

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.toKitPalette

/**
 * #1781 the HandWrite panel: candidate chips on top, the writing pad, and a bottom row with the switch-back key (ABC),
 * the language, space and backspace. A pause after the last stroke (Configs > HandWrite, auto-commit delay) commits the
 * first candidate; a tap on a chip commits that one. The recognition itself is the registered [HandwritingEngine].
 *
 * Compose, hosted like the Text Enhancements bar ([KitImeComposeView]); the writing surface is a Canvas that paints the
 * strokes of the [StrokeCapture] as they come, from finger or stylus.
 */
class HandwriteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : KitImeComposeView(context, attrs) {
    lateinit var keyboardActionListener: KeyboardActionListener
    var textBeforeCursor: () -> String = { "" }

    private val ui = Handler(Looper.getMainLooper())
    private val autoCommit = Runnable { controller.autoCommit() }
    private val capture = StrokeCapture()
    val controller = HandwriteController(capture, { Handwriting.engine }, { language() }, { textBeforeCursor() },
        object : HandwriteSink {
            override fun commit(text: String) = keyboardActionListener.onTextInput(text)
            override fun space() = keyboardActionListener.onCodeInput(Constants.CODE_SPACE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            override fun backspace() = keyboardActionListener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }, onChange = { render() })

    /** Bumped on every change the Compose tree has to redraw for (a stroke point, a candidate, the language). */
    private var tick by mutableIntStateOf(0)
    private var strokeDp by mutableIntStateOf(HandwriteSettings.DEFAULT_STROKE_DP)
    private var padTopLeft by mutableStateOf(Offset.Zero)
    private var padSize by mutableStateOf(IntSize.Zero)

    private fun language(): String {
        val p = context.prefs()
        return HandwriteSettings.active(p.getString(HandwriteSettings.PREF_LANGUAGES, null), p.getString(HandwriteSettings.PREF_ACTIVE_LANGUAGE, null))
    }

    private fun scheduleAutoCommit() {
        ui.removeCallbacks(autoCommit)
        ui.postDelayed(autoCommit, HandwriteSettings.autoCommitMs(
            context.prefs().getInt(HandwriteSettings.PREF_AUTOCOMMIT_MS, HandwriteSettings.DEFAULT_AUTOCOMMIT_MS)).toLong())
    }

    private fun cycleLanguage() {
        val p = context.prefs()
        val next = HandwriteSettings.next(p.getString(HandwriteSettings.PREF_LANGUAGES, null), language())
        p.edit().putString(HandwriteSettings.PREF_ACTIVE_LANGUAGE, next).apply()
        controller.reset(); render()
    }

    /** Opening the panel: a clean pad, the stroke width from the settings, the language on its key. */
    fun startHandwrite() {
        ui.removeCallbacks(autoCommit)
        controller.reset()
        strokeDp = HandwriteSettings.strokeDp(context.prefs().getInt(HandwriteSettings.PREF_STROKE_DP, HandwriteSettings.DEFAULT_STROKE_DP))
        render()
    }

    /** left, top, right, bottom of the writing pad on the screen (the CI emulator scene draws into it). */
    fun padBounds(): IntArray {
        val at = IntArray(2).also { getLocationOnScreen(it) }
        val l = at[0] + padTopLeft.x.toInt(); val t = at[1] + padTopLeft.y.toInt()
        return intArrayOf(l, t, l + padSize.width, t + padSize.height)
    }

    fun stopHandwrite() { ui.removeCallbacks(autoCommit); controller.reset() }

    private fun render() { tick++ }

    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val colors = Settings.getValues().mColors
        val text = Color(colors.get(ColorType.KEY_TEXT))
        val keyBg = Color(colors.get(ColorType.KEY_BACKGROUND))
        val version = tick // read: every render() redraws the chips, the status and the pad
        val candidates = controller.candidates
        val status = when (val r = controller.recognition) {
            Recognition.NoModel -> context.getString(R.string.handwrite_no_model, language())
            Recognition.NoEngine -> context.getString(R.string.handwrite_no_engine)
            is Recognition.Failed -> r.why
            is Recognition.Candidates -> if (r.texts.isEmpty()) context.getString(R.string.handwrite_nothing) else ""
            null -> context.getString(R.string.handwrite_hint)
        }
        val keyColors = ButtonDefaults.buttonColors(containerColor = keyBg, contentColor = text)
        Column(Modifier.fillMaxWidth().background(Color(colors.get(ColorType.MAIN_BACKGROUND)))) {
            Row(Modifier.fillMaxWidth().height(44.dp).horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically) {
                candidates.forEachIndexed { i, c ->
                    Button(onClick = { ui.removeCallbacks(autoCommit); controller.commitCandidate(i) }, colors = keyColors,
                        modifier = Modifier.padding(horizontal = 2.dp)) { Text(c) }
                }
            }
            Text(status, color = text, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp))
            InkPad(text, version)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Button(onClick = { keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false) },
                    colors = keyColors, modifier = Modifier.weight(1f).height(48.dp).semantics { contentDescription = "Back to the letters" }) { Text("ABC") }
                Button(onClick = { cycleLanguage() }, colors = keyColors, modifier = Modifier.weight(1.2f).height(48.dp)) { Text(language()) }
                Button(onClick = { ui.removeCallbacks(autoCommit); controller.space() }, colors = keyColors,
                    modifier = Modifier.weight(3f).height(48.dp)) { Text("space") }
                Button(onClick = { ui.removeCallbacks(autoCommit); controller.backspace() }, colors = keyColors,
                    modifier = Modifier.weight(1f).height(48.dp)) { Text("\u232B") }
            }
        }
    }

    /** The writing surface: a finger or stylus stroke is captured with its historical points and painted as it comes. */
    @Composable
    private fun InkPad(ink: Color, version: Int) {
        val width = with(LocalDensity.current) { strokeDp.dp.toPx() }
        Canvas(Modifier.fillMaxWidth().height(170.dp)
            .onGloballyPositioned { padTopLeft = it.positionInRoot(); padSize = it.size }
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    this@HandwriteView.parent?.requestDisallowInterceptTouchEvent(true)
                    ui.removeCallbacks(autoCommit)
                    capture.down(down.position.x, down.position.y, down.uptimeMillis)
                    down.consume(); tick++
                    var last = down.position; var lastT = down.uptimeMillis
                    try {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            for (h in change.historical) capture.move(h.position.x, h.position.y, h.uptimeMillis)
                            capture.move(change.position.x, change.position.y, change.uptimeMillis)
                            last = change.position; lastT = change.uptimeMillis
                            change.consume(); tick++
                            if (!change.pressed) break
                        }
                    } finally {
                        // the finger lifting and the gesture being cancelled both end the stroke
                        if (capture.isDrawing) capture.up(last.x, last.y, lastT)
                        scheduleAutoCommit(); controller.strokeEnded(); tick++
                    }
                }
            }) {
            version.let { }
            for (s in capture.strokes.toList()) {
                val pts = s.points.toList()
                if (pts.isEmpty()) continue
                if (pts.size == 1) {
                    drawCircle(ink, radius = width / 2f, center = Offset(pts[0].x, pts[0].y))
                } else {
                    val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (k in 1 until pts.size) lineTo(pts[k].x, pts[k].y) }
                    drawPath(path, ink, style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
        }
    }
}
