package app.sterna.ui.message

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import app.sterna.R
import app.sterna.ui.theme.MailMetrics

/**
 * The full information of the open message (#1661), opened by tapping its subject the way the
 * participants panel is opened by tapping its sender.
 *
 * It draws EXACTLY the groups [messageDetails] decided and judges nothing. The header fetch is
 * asked for when the sheet opens ([onOpened]) and never when a message does, so the groups that
 * read headers fill in a moment after the sheet is up. "Copy all" sits at the end and copies the
 * whole block as it is drawn.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MessageDetailsSheet(
    sections: List<DetailSection>,
    onOpened: () -> Unit,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { onOpened() }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = MailMetrics.s24),
        ) {
            Text(
                stringResource(R.string.message_details_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
            )
            // Selectable, so one value can be copied on its own without taking the whole block.
            SelectionContainer {
                Column(Modifier.fillMaxWidth()) {
                    for (section in sections) {
                        HorizontalDivider()
                        Text(
                            section.title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s8),
                        )
                        for (line in section.lines) {
                            Text(
                                line.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(
                                    start = MailMetrics.s24,
                                    end = MailMetrics.s24,
                                    top = MailMetrics.s6,
                                ),
                            )
                            // Unbounded on purpose: this is the place a long DKIM or Received line
                            // is read in full, and the sheet scrolls.
                            Text(
                                line.value,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = MailMetrics.s24, end = MailMetrics.s24),
                            )
                        }
                    }
                }
            }
            HorizontalDivider(Modifier.padding(top = MailMetrics.s12))
            OutlinedButton(
                onClick = {
                    // A clipboard that refuses (a block too large for the binder) must not take the
                    // reader down with it: the word is shown only when the copy happened.
                    val copied = runCatching {
                        clipboard.setText(AnnotatedString(detailsText(sections)))
                    }.isSuccess
                    if (copied) {
                        Toast.makeText(context, R.string.message_details_copied, Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.padding(horizontal = MailMetrics.s24, vertical = MailMetrics.s12),
            ) {
                Text(stringResource(R.string.message_details_copy_all))
            }
        }
    }
}
