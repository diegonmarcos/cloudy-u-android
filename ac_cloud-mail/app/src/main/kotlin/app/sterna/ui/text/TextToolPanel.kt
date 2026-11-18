package app.sterna.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.ui.text.input.TextFieldValue
import app.sterna.core.data.text.RichBody
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.sterna.R
import app.sterna.ui.theme.MailMetrics

/**
 * The one surface both tools report through, on both surfaces they run on.
 *
 * [onApply] is what separates the two directions the owner named, and it is deliberately
 * the ONLY difference:
 *
 *   null      the message is one that was RECEIVED. It is read-only: the result is shown
 *             here to read or copy, and nothing is written back. The stored body is not a
 *             target — an incoming message is a record of what somebody sent, and a tool
 *             that edits it in place has destroyed the only copy of that.
 *   non-null  the message is a DRAFT the user is writing. The result goes back into the
 *             compose field, because that is where the user wants it.
 *
 * A dialog rather than a sheet: it is modal on purpose. A rewrite lands in the field, and
 * a control that applies text to a field the user can still be typing in is a race.
 */
@Composable
fun TextToolPanel(runner: TextToolRunner, onApply: ((String) -> Unit)?) {
    val busy = runner.busy
    val outcome = runner.outcome
    if (busy == null && outcome == null) return
    val tool = busy ?: outcome!!.tool
    // AI Resume reports somewhere else: the owner asked for its summary in a box UNDER THE SENDER,
    // not in a modal over the message. Same runner, same progress and the same verbatim error — see
    // ResumeBox — so this is a routing decision about where one outcome is drawn, not a second copy
    // of the machinery. Returning early here is what keeps a dialog from opening over that box.
    // The tools this dialog answers for are exactly the ones drawn in an overflow menu, so it asks
    // TextTool rather than naming RESUME: a fourth tool that draws itself is handled already.
    // Tools that apply in place (Answer Prediction, Enhance, Check Grammar) have no dialog: the composer
    // applies their result as an undoable edit.
    if (!tool.inOverflow || tool.appliesInPlace) return

    val clipboard = LocalClipboardManager.current
    val toolName = stringResource(tool.label)

    AlertDialog(
        // While a call is in flight there is nothing to dismiss TO: the reply would arrive
        // with no surface to land on. The engines are blocking and cannot be cancelled.
        onDismissRequest = { if (busy == null) runner.dismiss() },
        title = { Text(toolName) },
        text = {
            Column(Modifier.fillMaxWidth()) {
                when {
                    busy != null -> {
                        // Names the provider, as the keyboard's Enhance bar does: a slow call
                        // then reads as a slow provider rather than as a stuck app.
                        val provider = runner.providerLabel()
                        Text(
                            if (provider != null) {
                                stringResource(R.string.text_tool_running_with, provider)
                            } else {
                                stringResource(R.string.text_tool_running)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = MailMetrics.s12))
                    }
                    // The engine's OWN reason, verbatim — "no API key for OpenRouter", "the
                    // model cut the reply off", the provider's HTTP error. A generic apology
                    // here would throw away the one thing that says what to do next.
                    outcome?.error != null -> Text(outcome.error, style = MaterialTheme.typography.bodyMedium)
                    else -> Text(
                        outcome?.text.orEmpty(),
                        Modifier.verticalScroll(rememberScrollState()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            val text = outcome?.text
            if (text != null) {
                Row {
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Text(stringResource(R.string.text_tool_copy))
                    }
                    // Only a draft gets this. On a received message there is nothing to apply
                    // it to, and offering the button would imply otherwise.
                    if (onApply != null) {
                        TextButton(onClick = { onApply(text); runner.dismiss() }) {
                            Text(stringResource(R.string.text_tool_apply))
                        }
                    }
                }
            }
        },
        dismissButton = {
            if (busy == null) {
                TextButton(onClick = { runner.dismiss() }) {
                    Text(stringResource(R.string.text_tool_close))
                }
            }
        },
    )
}

/**
 * [surface]'s tools as ONE ROW of one-tap icons, drawn FROM the surface's own declaration.
 *
 * The only shape either screen draws them in, and the only one there is. Both used to hand-write
 * their own: the reader listed Enhance and Translate, the composer listed its own pair, and the two
 * disagreed about which tools existed. A shared helper fixed that by generating one full-width
 * DropdownMenuItem per tool — then #293 replaced the reader's stack with this row and left the
 * composer on the stack, so the surfaces disagreed again, this time about SHAPE instead of
 * membership. #308 put both on the row and deleted the stacked helper outright rather than leaving
 * it callerless, because a composable nothing calls is exactly what the next screen reaches for.
 *
 * This does NOT filter on [TextTool.inOverflow]. A row IS a toolbar, so a tool that draws its own
 * toolbar icon draws it here — which is why AI Resume appears in this row while [TextToolPanel]
 * still uses the same flag to decide that Resume's OUTCOME is drawn somewhere else.
 *
 * [enabled] is the caller's own gate — the composer closes its tools while a send is in flight —
 * and is deliberately separate from membership. Whether an action EXISTS on a surface and whether
 * it can be tapped right now are different questions, and answering them with one flag is how an
 * action ends up permanently greyed out instead of honestly absent.
 *
 * [skip] is the caller's per-MESSAGE veto, and it is a third thing separate from both membership
 * and [enabled] for the same reason those two are separate: whether a tool exists on this surface,
 * whether it can be tapped at this instant, and whether it has anything to act on in this
 * particular message are three different questions. The reader hides Resume on a message with
 * nothing to summarise, and an action that cannot apply here is honestly absent rather than
 * permanently greyed out.
 *
 * [trailing] is for icons that are not text tools and never will be — the reader's Show Images.
 * They belong in this Row because the owner asked for the one-tap actions on ONE line, and a
 * second Row beside this one would put them on two.
 */
@Composable
fun TextToolIconRow(
    surface: TextToolSurface,
    enabled: Boolean = true,
    skip: (TextTool) -> Boolean = { false },
    /** Drawn straight after the Translate icon, if the surface has one: the reader puts Show Original /
     *  Show Translated there so the toggle sits where the action it undoes is. */
    afterTranslate: @Composable () -> Unit = {},
    trailing: @Composable () -> Unit = {},
    onPick: (TextTool) -> Unit,
) {
    Row(
        modifier = Modifier.padding(horizontal = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        surface.tools.forEach { tool ->
            if (skip(tool)) return@forEach
            IconButton(enabled = enabled, onClick = { onPick(tool) }) {
                Icon(tool.icon, contentDescription = stringResource(tool.label))
            }
            if (tool == TextTool.TRANSLATE) afterTranslate()
        }
        trailing()
    }
}

/**
 * The composer's dense in-place toolbar: the surface's tools that apply to the draft ([TextTool.appliesInPlace]),
 * one icon each, plus Undo once one has been applied. Drawn FROM the surface, never by name, so a tool added
 * to the surface with that flag shows up here and in the overflow without another list to edit.
 */
@Composable
fun TextToolInPlaceBar(
    surface: TextToolSurface,
    enabled: Boolean,
    running: TextTool?,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onPick: (TextTool) -> Unit,
    /** #1800: also draw the surface's Translate icon after the in-place tools (it opens its dialog). */
    withTranslate: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = MailMetrics.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        surface.tools.filter { it.appliesInPlace || (withTranslate && it == TextTool.TRANSLATE) }.forEach { tool ->
            IconButton(enabled = enabled && running == null, onClick = { onPick(tool) }) {
                Icon(tool.icon, contentDescription = stringResource(tool.label))
            }
        }
        if (running != null) {
            LinearProgressIndicator(Modifier.weight(1f).padding(horizontal = MailMetrics.s8))
        } else {
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        }
        if (canUndo) {
            IconButton(onClick = onUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = stringResource(R.string.text_tool_undo),
                )
            }
        }
    }
}

/** The in-place tools as named overflow items (leading icon and label): what the toolbar's icons do, spelled out. */
@Composable
fun ColumnScope.TextToolNamedItems(surface: TextToolSurface, enabled: Boolean, onPick: (TextTool) -> Unit) {
    surface.tools.filter { it.appliesInPlace }.forEach { tool ->
        DropdownMenuItem(
            text = { Text(stringResource(tool.label)) },
            leadingIcon = { Icon(tool.icon, contentDescription = null) },
            enabled = enabled,
            onClick = { onPick(tool) },
        )
    }
}

/** The draft before an in-place text tool changed it, for the Undo icon. */
class TextToolUndo(val body: TextFieldValue, val rich: RichBody)
