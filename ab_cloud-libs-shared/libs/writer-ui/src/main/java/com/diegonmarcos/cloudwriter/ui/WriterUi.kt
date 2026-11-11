package com.diegonmarcos.cloudwriter.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp

/**
 * The rows and containers every cloud-writer screen is built from.
 *
 * ONE SET OF COMPONENTS FOR ALL FIVE SCREENS, which is the actual substance of this change. What
 * these replace is not "an old-looking style": it is the ABSENCE of a component set. Each screen
 * used to build its own TextViews inline, and the two files that did the most of it each declared
 * their own private copies of the same four hex colours and their own heading()/caption() helpers
 * with different sizes. Two copies of a style are two styles, and they had already diverged — the
 * main screen's caption was 13sp and the settings pages' was 12sp, for the same kind of sentence.
 *
 * SIZING IS DP AND sp THROUGHOUT, WHICH IT WAS NOT. The old screens called setPadding(36, ...) with
 * a raw pixel constant, so every margin in the application was 36 PHYSICAL PIXELS: about 18dp on a
 * 2x phone and 9dp on a 4x one. The spacing was therefore a different size on every device and
 * roughly half what was intended on a modern Samsung. Compose has no unit-less path — 16.dp is
 * 16dp everywhere — so this class of bug cannot recur here.
 */

/** The page gutter and the gap between blocks. One number each, so no screen drifts from another. */
val PageGutter = 16.dp
val BlockGap = 16.dp

/**
 * A titled group of rows.
 *
 * The heading is `titleSmall` in the primary colour — a type-scale step and a colour role rather
 * than a hand-picked 16f and a hex literal, so it moves with the theme and stays legible on both
 * the light scheme and the owner's black one.
 */
@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(top = BlockGap, bottom = 4.dp),
    )
}

/**
 * The home screen's four feature entries: an icon, a name, and a line saying what the page is for.
 *
 * THIS IS THE ROW THAT REPLACES A BARE BUTTON. Four buttons reading "Text Enhancements",
 * "Translation", "Grammar check", "AI Model Routing" stacked in a column told the owner nothing
 * about what any of them would do, which is the screen he opens the application to.
 *
 * MINIMUM 72dp TALL, and that is a requirement rather than a look: Material's minimum touch target
 * is 48dp and a two-line list item is 72dp, whereas the default Button it replaces is 48dp
 * INCLUDING its own text padding — comfortably tappable only because there was nothing near it.
 */
@Composable
fun FeatureCard(
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        // Modifier.clickable rather than the Card(onClick=) overload: identical behaviour, and
        // `clickable` is stable foundation API that has never moved, which matters in a module
        // whose only compiler is CI.
        modifier = modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                // Decorative: the title beside it says the same thing, and a screen reader
                // announcing "Translate, Translation" reads the row twice.
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(PageGutter))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A settings row that opens a single-choice dialog — the Material 3 form of `ListPreference`, and
 * of the AlertDialog.setSingleChoiceItems the View-based pages used, so the INTERACTION the owner
 * already knows is unchanged and only its drawing moved.
 *
 * [items] is (label to id) in menu order. [current] is the stored id, and the value line is
 * resolved through the same list, so a stored id the registry has since dropped shows [fallback]
 * rather than an empty line.
 */
@Composable
fun ChoiceRow(
    title: String,
    summary: String?,
    items: List<Pair<String, String>>,
    current: String,
    fallback: String,
    onPick: (String) -> Unit,
) {
    var chosen by remember(current) { mutableStateOf(current) }
    var open by remember { mutableStateOf(false) }

    fun labelOf(id: String): String =
        items.firstOrNull { it.second == id }?.first
            ?: items.firstOrNull { it.second == fallback }?.first
            ?: items.firstOrNull()?.first.orEmpty()

    SettingRow(title = title, summary = summary, value = labelOf(chosen), onClick = { open = true })

    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                // The registry's language list is long enough to run off a phone, and a dialog
                // that cannot scroll simply hides the entries past the bottom edge.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    items.forEach { (label, id) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // selectable() on the whole row, not just the button: the radio
                                // dot is 20dp and tapping the label is how a list like this is
                                // actually used.
                                .selectable(
                                    selected = id == chosen,
                                    onClick = { chosen = id; onPick(id); open = false },
                                )
                                .heightIn(min = 48.dp)
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = id == chosen, onClick = null)
                            Spacer(Modifier.width(12.dp))
                            Text(label, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { open = false }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }
}

/** A settings row with a switch — the Material 3 form of `SwitchPreference`. */
@Composable
fun ToggleRow(title: String, summary: String?, checked: Boolean, onToggle: (Boolean) -> Unit) {
    var on by remember(checked) { mutableStateOf(checked) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = on, onClick = { on = !on; onToggle(on) })
            .padding(horizontal = PageGutter, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(PageGutter))
        // null: the whole row is the control, and a Switch with its own handler inside a
        // selectable row is two overlapping targets that can disagree about the state.
        Switch(checked = on, onCheckedChange = null)
    }
}

/**
 * Title, optional explanation, and the current value under it. The shared body of every tappable
 * settings row, so a list row and a text row cannot drift apart.
 */
@Composable
fun SettingRow(title: String, summary: String?, value: String?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = false, onClick = onClick)
            .padding(horizontal = PageGutter, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (summary != null) {
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (value != null) {
                Text(
                    value,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** A grey sentence on its own — the notes that say what a row does NOT do. */
@Composable
fun NoteText(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = PageGutter, vertical = 4.dp),
    )
}

/**
 * The application's text box.
 *
 * AN OutlinedTextField WITH A LABEL, replacing a bare EditText whose only affordance was a hint
 * that vanished the moment anything was typed — so a filled box gave no clue which of the two on
 * screen was the input and which was the result.
 *
 * NOT A REWRITE OF THE EDITOR, and there was deliberately none to rewrite. cloud-keyboard's
 * Enhance and Translate boxes are a hand-built text editor (its own caret, selection, word
 * boundaries, clipboard and surrogate-pair handling, driven by appendCodePoint/backspace/moveCaret
 * from the IME's key router) because an IME cannot put a real focusable field inside its own
 * window. That hand-built editor is where tasks 83, 87, 204 and 238 all lived. cloud-writer is a
 * normal application whose boxes are platform text fields, so caret movement, autocorrect, paste,
 * emoji and the space-bar cursor slide are the platform's and have never been this application's
 * to get wrong.
 */
@Composable
fun WriterTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String? = null,
    minLines: Int = 5,
    trailing: @Composable (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        trailingIcon = trailing,
        minLines = minLines,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * The same box, driven by a TextFieldValue so its SELECTION is readable.
 *
 * The Text Enhance scope setting has an "only the selected text" mode, and a Compose text field
 * does not expose its selection to the outside any other way. Carrying text and selection in ONE
 * value is the point: a separate "where is the caret" state updated alongside the text is two
 * facts that can disagree, and the mode would then send the wrong substring — or, if the mirror
 * were simply never written, send an empty string and refuse every run while looking correct.
 */
@Composable
fun WriterTextField(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    label: String,
    supporting: String? = null,
    minLines: Int = 5,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        minLines = minLines,
        modifier = modifier.fillMaxWidth(),
    )
}

/** A row of actions under a box, pushed to the trailing edge as Material lays out text buttons. */
@Composable
fun ActionRow(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}
