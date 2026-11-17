// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.handwrite

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import helium314.keyboard.keyboard.KeyboardActionListener
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.R
import helium314.keyboard.latin.common.ColorType
import helium314.keyboard.latin.common.Constants
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.prefs

/** #1781 the writing surface: strokes drawn with a finger or a stylus, painted as they come. */
@SuppressLint("ViewConstructor")
class InkPadView(context: Context, private val capture: StrokeCapture, private val onStrokeEnd: () -> Unit,
                 private val onStrokeStart: () -> Unit) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }

    fun setStrokeWidthDp(dp: Int) {
        paint.strokeWidth = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), resources.displayMetrics)
        invalidate()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val t = e.eventTime
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { parent?.requestDisallowInterceptTouchEvent(true); onStrokeStart(); capture.down(e.x, e.y, t) }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) capture.move(e.getHistoricalX(i), e.getHistoricalY(i), e.getHistoricalEventTime(i))
                capture.move(e.x, e.y, t)
            }
            MotionEvent.ACTION_UP -> { capture.up(e.x, e.y, t); onStrokeEnd() }
            MotionEvent.ACTION_CANCEL -> { capture.up(e.x, e.y, t); onStrokeEnd() }
            else -> return false
        }
        invalidate()
        return true
    }

    override fun onDraw(c: Canvas) {
        paint.color = Settings.getValues().mColors.get(ColorType.KEY_TEXT)
        for (s in capture.strokes) {
            val p = Path()
            s.points.forEachIndexed { i, pt -> if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y) }
            if (s.points.size == 1) c.drawPoint(s.points[0].x, s.points[0].y, paint) else c.drawPath(p, paint)
        }
    }
}

/**
 * #1781 the HandWrite panel: candidate chips on top, the writing pad, and a bottom row with the switch-back key (ABC),
 * the language, space and backspace. A pause after the last stroke (Configs ▸ HandWrite, auto-commit delay) commits the
 * first candidate; a tap on a chip commits that one. The recognition itself is the registered [HandwritingEngine].
 */
class HandwriteView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : LinearLayout(context, attrs) {
    lateinit var keyboardActionListener: KeyboardActionListener
    var textBeforeCursor: () -> String = { "" }

    private val ui = Handler(Looper.getMainLooper())
    private val autoCommit = Runnable { controller.autoCommit() }
    private val chips = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val status = TextView(context)
    private val pad: InkPadView
    private val langButton = Button(context)
    private val capture = StrokeCapture()
    val controller = HandwriteController(capture, { Handwriting.engine }, { language() }, { textBeforeCursor() },
        object : HandwriteSink {
            override fun commit(text: String) = keyboardActionListener.onTextInput(text)
            override fun space() = keyboardActionListener.onCodeInput(Constants.CODE_SPACE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
            override fun backspace() = keyboardActionListener.onCodeInput(KeyCode.DELETE, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false)
        }, onChange = { render() })

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun language(): String {
        val p = context.prefs()
        return HandwriteSettings.active(p.getString(HandwriteSettings.PREF_LANGUAGES, null), p.getString(HandwriteSettings.PREF_ACTIVE_LANGUAGE, null))
    }

    init {
        orientation = VERTICAL
        val chipScroll = HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(chips) }
        status.setPadding(dp(12), dp(4), dp(12), dp(4))
        pad = InkPadView(context, capture, { scheduleAutoCommit(); controller.strokeEnded() }, { ui.removeCallbacks(autoCommit) })
        addView(chipScroll, LayoutParams(LayoutParams.MATCH_PARENT, dp(44)))
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(pad, LayoutParams(LayoutParams.MATCH_PARENT, dp(170)))
        val row = LinearLayout(context).apply { orientation = HORIZONTAL }
        fun key(label: CharSequence, weight: Float, onClick: () -> Unit) = Button(context).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }
            row.addView(this, LayoutParams(0, dp(48), weight))
        }
        key("ABC", 1f) { keyboardActionListener.onCodeInput(KeyCode.ALPHA, Constants.NOT_A_COORDINATE, Constants.NOT_A_COORDINATE, false) }
            .contentDescription = "Back to the letters"
        langButton.apply { isAllCaps = false; setOnClickListener { cycleLanguage() } }
        row.addView(langButton, LayoutParams(0, dp(48), 1.2f))
        key("space", 3f) { ui.removeCallbacks(autoCommit); controller.space() }
        key("⌫", 1f) { ui.removeCallbacks(autoCommit); controller.backspace() }
        addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
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
        pad.setStrokeWidthDp(HandwriteSettings.strokeDp(context.prefs().getInt(HandwriteSettings.PREF_STROKE_DP, HandwriteSettings.DEFAULT_STROKE_DP)))
        render()
    }

    /** left, top, right, bottom of the writing pad on the screen (the CI emulator scene draws into it). */
    fun padBounds(): IntArray = IntArray(2).also { pad.getLocationOnScreen(it) }.let { intArrayOf(it[0], it[1], it[0] + pad.width, it[1] + pad.height) }

    fun stopHandwrite() { ui.removeCallbacks(autoCommit); controller.reset() }

    private fun render() {
        val colors = Settings.getValues().mColors
        val text = colors.get(ColorType.KEY_TEXT)
        setBackgroundColor(colors.get(ColorType.MAIN_BACKGROUND))
        langButton.text = language()
        status.setTextColor(text)
        chips.removeAllViews()
        controller.candidates.forEachIndexed { i, c ->
            chips.addView(Button(context).apply {
                this.text = c; isAllCaps = false; setOnClickListener { ui.removeCallbacks(autoCommit); controller.commitCandidate(i) }
            }, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
        }
        status.text = when (val r = controller.recognition) {
            Recognition.NoModel -> context.getString(R.string.handwrite_no_model, language())
            Recognition.NoEngine -> context.getString(R.string.handwrite_no_engine)
            is Recognition.Failed -> r.why
            is Recognition.Candidates -> if (r.texts.isEmpty()) context.getString(R.string.handwrite_nothing) else ""
            null -> context.getString(R.string.handwrite_hint)
        }
        pad.invalidate()
    }
}
