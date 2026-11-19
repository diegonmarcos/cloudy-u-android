package com.diegonmarcos.superapp.translate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The colours the shared panel draws with; each host maps its own palette onto this. */
class WriterPanelColors(
    val surface: Color, val chip: Color, val onChip: Color, val accent: Color, val onAccent: Color,
    val text: Color, val muted: Color, val outline: Color,
)

/** One config selector chip of a mode's toolbar (Translate: from / to; Enhance and Draft: its options). */
class ConfigSelector(val id: String, val label: String, val onTap: () -> Unit)

/** One extra button a mode keeps next to the five shared actions (Resume, Undo, Clear, ...). */
class PanelButton(val id: String, val label: String, val onClick: () -> Unit)

/** What a mode supplies to the shared panel. */
class WriterPanelSpec(
    val mode: WriterMode,
    val title: String,
    val colors: WriterPanelColors,
    val selectors: List<ConfigSelector>,
    val source: InputSource,
    val onSource: (InputSource) -> Unit,
    val onAction: (WriterAction) -> Unit,
    val extras: List<PanelButton>,
    val status: String,
    val statusIsError: Boolean = false,
    val busy: Boolean = false,
    val onClose: () -> Unit,
)

/**
 * #1818 THE ONE PANEL of the three Writer modes. Top to bottom: the mode's toolbar of config selector chips
 * (+ close), the input source buttons (Type / Clipboard / App box), the framed INPUT box, the framed OUTPUT box,
 * the status line and the actions Generate, Auto Replace, Replace, Paste, Copy (then the mode's extras).
 * The boxes themselves are hosted views ([input], [output]); this draws their visible frame.
 */
@Composable
fun WriterPanel(spec: WriterPanelSpec, input: @Composable () -> Unit, output: @Composable () -> Unit, modifier: Modifier = Modifier) {
    val c = spec.colors
    val frame = RoundedCornerShape(10.dp)
    val closeLabel = stringResource(R.string.translate_bar_close)
    Column(modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 10.dp, bottom = 8.dp)) {
        // the mode's config selectors
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(spec.title, color = c.text, fontSize = 13.sp, maxLines = 1)
                spec.selectors.forEach { s -> WriterChip(s.label, c.chip, c.onChip) { s.onTap() } }
            }
            Text("✕", color = c.text, fontSize = 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 44.dp)
                    .semantics { contentDescription = closeLabel }.clickable { spec.onClose() }.padding(top = 10.dp))
        }
        // the input sources
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.writer_panel_input), color = c.muted, fontSize = 12.sp, maxLines = 1)
            WriterPanelLayout.SOURCE_ORDER.forEach { s ->
                val on = spec.source == s
                WriterChip(stringResource(sourceLabel(s)), if (on) c.accent else c.chip, if (on) c.onAccent else c.onChip) { spec.onSource(s) }
            }
        }
        // INPUT box, framed
        Box(Modifier.fillMaxWidth().padding(top = 4.dp).clip(frame).background(c.surface).border(1.dp, c.outline, frame)) { input() }
        Text(stringResource(R.string.writer_panel_output), color = c.muted, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        // OUTPUT box, framed
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).clip(frame).background(c.surface).border(1.dp, c.outline, frame)) { output() }
        if (spec.status.isNotEmpty())
            Text(spec.status, color = c.muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                fontStyle = if (spec.statusIsError) FontStyle.Normal else FontStyle.Italic,
                modifier = Modifier.padding(top = 4.dp, bottom = 2.dp))
        // the five actions, then the mode's own extras
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            WriterPanelLayout.ACTION_ORDER.forEach { a ->
                val primary = a == WriterAction.GENERATE
                WriterChip(stringResource(actionLabel(a)), if (primary) c.accent else c.chip, if (primary) c.onAccent else c.onChip) { spec.onAction(a) }
            }
            spec.extras.forEach { e -> WriterChip(e.label, c.chip, c.onChip) { e.onClick() } }
        }
    }
}

@Composable
fun WriterChip(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Text(label, color = fg, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(bg).clickable { onClick() }
            .sizeIn(minHeight = 36.dp).padding(horizontal = 14.dp, vertical = 8.dp))
}

private fun sourceLabel(s: InputSource): Int = when (s) {
    InputSource.OWN -> R.string.writer_source_own
    InputSource.CLIPBOARD -> R.string.writer_source_clipboard
    InputSource.APP_BOX -> R.string.writer_source_app
}

private fun actionLabel(a: WriterAction): Int = when (a) {
    WriterAction.GENERATE -> R.string.writer_action_generate
    WriterAction.AUTO_REPLACE -> R.string.writer_action_auto_replace
    WriterAction.REPLACE -> R.string.writer_action_replace
    WriterAction.PASTE -> R.string.writer_action_paste
    WriterAction.COPY -> R.string.writer_action_copy
}

/** [FieldOps] over the host's real InputConnection. */
class InputConnectionOps(private val ic: InputConnection) : FieldOps {
    override fun selectedText(): String? = ic.getSelectedText(0)?.toString()
    override fun allText(): String? = ic.getExtractedText(ExtractedTextRequest(), 0)?.text?.toString()
        ?: ((ic.getTextBeforeCursor(MAX, 0)?.toString().orEmpty()) + (ic.getTextAfterCursor(MAX, 0)?.toString().orEmpty()))
    override fun selectAll() { ic.performContextMenuAction(android.R.id.selectAll) }
    override fun commit(text: String) { ic.commitText(text, 1) }
    override fun beginBatch() { ic.beginBatchEdit() }
    override fun endBatch() { ic.endBatchEdit() }
    private companion object { const val MAX = 100_000 }
}

/** The system clipboard as [ClipPort]. */
class SystemClip(private val context: Context, private val label: String) : ClipPort {
    private fun cm() = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    override fun read(): String? = cm().primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
    override fun write(text: String) { cm().setPrimaryClip(ClipData.newPlainText(label, text)) }
}
