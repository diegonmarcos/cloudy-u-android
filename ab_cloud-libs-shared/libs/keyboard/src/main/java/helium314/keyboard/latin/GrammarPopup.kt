// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.toKitPalette

/**
 * Grammar Check ▸ Highlight: a tap on a highlighted word in the field moves the cursor into it; the IME sees
 * that in onUpdateSelection and shows this popup with the suggestion and two actions.
 *
 * It is a [PopupWindow] anchored to the keyboard view, so it lives in the INPUT METHOD's window: an input
 * method has no Activity to host a dialog, and a dialog would take focus from the field being edited.
 *
 *   Replace → the suggestion is committed over the span through the InputConnection.
 *   Ignore  → the span's own text is committed back over itself, which drops the underline the checker set,
 *             and [GrammarHighlights] remembers the text/fix pair so a re-check does not mark it again this session.
 */
object GrammarPopup {
    private const val TAG = "GrammarPopup"
    private var window: PopupWindow? = null
    /** The mark the popup is open for, and where the cursor was when it opened. */
    @Volatile var shownIssue: GrammarIssue? = null; private set
    private var cursorAt = 0

    val isShowing: Boolean get() = window?.isShowing == true

    @JvmStatic
    fun onSelectionChanged(ime: LatinIME, selStart: Int, selEnd: Int) {
        if (selStart != selEnd || GrammarMode.read(ime.prefs()) != GrammarMode.HIGHLIGHT) return dismiss()
        val issue = GrammarHighlights.session.at(selStart) ?: return dismiss()
        if (issue == shownIssue && isShowing) return
        show(ime, issue, selStart)
    }

    fun show(ime: LatinIME, issue: GrammarIssue, cursor: Int) {
        dismiss()
        val anchor: View = KeyboardSwitcher.getInstance().mainKeyboardView ?: return
        if (anchor.windowToken == null) return
        val ctx = anchor.context
        val pal = runCatching { Settings.getValues().mColors.toKitPalette() }.getOrNull()
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        val ink = pal?.textPrimary?.toArgb() ?: 0xFFFFFFFF.toInt()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(pal?.surface?.toArgb() ?: 0xFF2B2B2B.toInt())
                setStroke(dp(1), pal?.hairline?.toArgb() ?: 0xFF555555.toInt())
            }
            elevation = dp(6).toFloat()
        }
        box.addView(TextView(ctx).apply {
            text = if (issue.suggestion.isEmpty()) ctx.getString(R.string.grammar_popup_remove, issue.original)
                   else ctx.getString(R.string.grammar_popup_suggestion, issue.original, issue.suggestion)
            setTextColor(ink); textSize = 15f
            tag = "grammar_popup_text"
        })
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        val replace = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = ctx.getString(R.string.grammar_popup_replace); setTextColor(pal?.accent?.toArgb() ?: ink)
            setOnClickListener { replace(ime) }
        }
        val ignore = Button(ctx, null, android.R.attr.borderlessButtonStyle).apply {
            text = ctx.getString(R.string.grammar_popup_ignore); setTextColor(pal?.textSecondary?.toArgb() ?: ink)
            setOnClickListener { ignore(ime) }
        }
        row.addView(ignore); row.addView(replace)
        box.addView(row)
        val pw = PopupWindow(box, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false).apply {
            isOutsideTouchable = true
            isTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setOnDismissListener { if (window === this) { window = null; shownIssue = null } }
        }
        val at = IntArray(2).also { anchor.getLocationInWindow(it) }
        runCatching { pw.showAtLocation(anchor, Gravity.TOP or Gravity.CENTER_HORIZONTAL, 0, at[1] + dp(8)) }
            .onFailure { Log.w(TAG, "popup could not be shown: ${it.message}"); return }
        window = pw; shownIssue = issue; cursorAt = cursor
        Log.i(TAG, "shown for '${issue.original}' -> '${issue.suggestion}' at ${issue.start}..${issue.end}")
    }

    @JvmStatic
    fun dismiss() {
        val w = window ?: return
        window = null; shownIssue = null
        runCatching { w.dismiss() }
    }

    /** True when the field still holds the mark's text, read around the cursor the popup opened at. */
    private fun stillThere(ime: LatinIME, issue: GrammarIssue): Boolean {
        val ic = ime.currentInputConnection ?: return false
        val before = ic.getTextBeforeCursor(cursorAt - issue.start, 0)?.toString() ?: return false
        val after = ic.getTextAfterCursor(issue.end - cursorAt, 0)?.toString() ?: return false
        return before + after == issue.original
    }

    fun replace(ime: LatinIME): Boolean {
        val issue = shownIssue ?: return false
        val ic = ime.currentInputConnection ?: return false
        if (!stillThere(ime, issue)) { GrammarHighlights.session.drop(issue); dismiss(); return false }
        ic.beginBatchEdit()
        ic.setComposingRegion(issue.start, issue.end)
        ic.commitText(issue.suggestion, 1)
        ic.endBatchEdit()
        GrammarHighlights.session.replaced(issue)
        Log.i(TAG, "replaced '${issue.original}' with '${issue.suggestion}'")
        dismiss()
        return true
    }

    fun ignore(ime: LatinIME): Boolean {
        val issue = shownIssue ?: return false
        val ic = ime.currentInputConnection ?: return false
        // Remember first: the commit below moves the cursor and must not re-open the popup on this mark.
        GrammarHighlights.session.ignore(issue)
        if (stillThere(ime, issue)) {
            ic.beginBatchEdit()
            ic.setComposingRegion(issue.start, issue.end)
            ic.commitText(issue.original, 1) // plain text over the span: the SuggestionSpan underline goes with it
            ic.setSelection(cursorAt, cursorAt)
            ic.endBatchEdit()
        }
        Log.i(TAG, "ignored '${issue.original}' (ignored this session: ${GrammarHighlights.session.ignoredCount})")
        dismiss()
        return true
    }
}
