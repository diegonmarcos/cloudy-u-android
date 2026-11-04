// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import helium314.keyboard.keyboard.KeyboardSwitcher
import helium314.keyboard.latin.settings.GrammarMode
import helium314.keyboard.latin.settings.Settings
import helium314.keyboard.latin.utils.Log
import helium314.keyboard.latin.utils.prefs
import helium314.keyboard.latin.utils.toKitPalette
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitImeComposeView
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * Grammar Check ▸ Highlight: a tap on a highlighted word in the field moves the cursor into it; the IME sees
 * that in onUpdateSelection and shows this popup with the suggestion and two actions.
 *
 * It is a Compose view laid over the top of the keyboard's InputView, so it lives in the INPUT METHOD's window: an input
 * method has no Activity to host a dialog, and a dialog would take focus from the field being edited.
 *
 *   Replace → the suggestion is committed over the span through the InputConnection.
 *   Ignore  → the span's own text is committed back over itself, which drops the underline the checker set,
 *             and [GrammarHighlights] remembers the text/fix pair so a re-check does not mark it again this session.
 */
object GrammarPopup {
    private const val TAG = "GrammarPopup"
    private var window: View? = null
    /** The mark the popup is open for, and where the cursor was when it opened. */
    @Volatile var shownIssue: GrammarIssue? = null; private set
    private var cursorAt = 0

    val isShowing: Boolean get() = window?.isAttachedToWindow == true

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
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt() }
        val box = GrammarPopupView(
            ctx,
            if (issue.suggestion.isEmpty()) ctx.getString(R.string.grammar_popup_remove, issue.original)
            else ctx.getString(R.string.grammar_popup_suggestion, issue.original, issue.suggestion),
            onIgnore = { ignore(ime) }, onReplace = { replace(ime) },
        )
        // The popup is a child of the IME's own InputView, over the top of the keys: Compose hosts there
        // (KitImeComposeView), an input method has no Activity for a dialog, and a separate window would take
        // focus from the field being edited.
        var host: View? = anchor
        while (host != null && host !is InputView) host = host.parent as? View
        val frame = host as? InputView ?: return
        val inAnchor = IntArray(2).also { anchor.getLocationInWindow(it) }
        val inFrame = IntArray(2).also { frame.getLocationInWindow(it) }
        val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = inAnchor[1] - inFrame[1] + dp(8) }
        frame.addView(box, lp)
        window = box; shownIssue = issue; cursorAt = cursor
        Log.i(TAG, "shown for '${issue.original}' -> '${issue.suggestion}' at ${issue.start}..${issue.end}")
    }

    @JvmStatic
    fun dismiss() {
        val w = window ?: return
        window = null; shownIssue = null
        runCatching { (w.parent as? ViewGroup)?.removeView(w) }
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

/** The popup's body: the suggestion line and the two actions, in Compose on the keyboard's palette. */
private class GrammarPopupView(
    context: android.content.Context,
    private val message: String,
    private val onIgnore: () -> Unit,
    private val onReplace: () -> Unit,
) : KitImeComposeView(context) {
    override fun palette(): KitPalette = Settings.getValues().mColors.toKitPalette()

    @Composable
    override fun Body() {
        val pal = palette()
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
            color = pal.surface,
            border = BorderStroke(1.dp, pal.hairline),
            shadowElevation = 6.dp,
        ) {
            Column(Modifier.padding(start = 14.dp, top = 10.dp, end = 14.dp, bottom = 6.dp)) {
                Text(message, color = pal.textPrimary, fontSize = 15.sp)
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onIgnore) { Text(context.getString(R.string.grammar_popup_ignore), color = pal.textSecondary) }
                    TextButton(onClick = onReplace) { Text(context.getString(R.string.grammar_popup_replace), color = pal.accent) }
                }
            }
        }
    }
}
