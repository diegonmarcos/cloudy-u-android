package app.sterna.ui.errors

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.Dialog
import app.sterna.ui.theme.MailMetrics
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.superapp.fleetendpoints.errors.ErrorLog

/** #1925 The three actions every error banner and snackbar offers; labels are data so a test pins them. */
object ErrorActions {
    const val SEE_LOG = "See log"
    const val EXPORT_MD = "Export to Markdown"
    const val COPY_ALL = "Copy all"
    val ALL = listOf(SEE_LOG, EXPORT_MD, COPY_ALL)

    /** THE content of all three: the latest report (or [report] text) plus the mail log ring, secrets redacted. */
    fun content(log: ErrorLog = ErrorLog.shared): String = log.text()
    fun markdown(log: ErrorLog = ErrorLog.shared): String = log.markdown()

    fun copyAll(context: Context, log: ErrorLog = ErrorLog.shared) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Cloud Mail error log", content(log)))
    }

    /** Hands the Markdown to the share sheet as a .md text (no storage permission needed). */
    fun exportMarkdown(context: Context, log: ErrorLog = ErrorLog.shared) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_SUBJECT, "cloud-mail-error.md")
            putExtra(Intent.EXTRA_TEXT, markdown(log))
        }
        // unguarded: the share chooser is a system sheet over this screen, a double tap only reopens it, nothing to leave twice.
        context.startActivity(Intent.createChooser(send, EXPORT_MD).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** The buttons row: See log, Export to Markdown, Copy all. [onSeeLog] opens [ErrorLogDialog]. */
@Composable
fun ErrorActionsRow(onSeeLog: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(MailMetrics.s4)) {
        TextButton(onClick = onSeeLog) { Text(ErrorActions.SEE_LOG) }
        TextButton(onClick = { ErrorActions.exportMarkdown(context) }) { Text(ErrorActions.EXPORT_MD) }
        TextButton(onClick = { ErrorActions.copyAll(context) }) { Text(ErrorActions.COPY_ALL) }
    }
}

/** Full-screen log: the full verbose error and the recent mail log ring, with Export and Copy all. */
@Composable
fun ErrorLogDialog(onDismiss: () -> Unit) {
    val text = remember { ErrorActions.content() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(MailMetrics.s12)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Cloud Mail log", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = onDismiss) { Text("Close") }
                }
                ErrorActionsRow(onSeeLog = {})
                SelectionContainer(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Text(text, fontFamily = FontFamily.Monospace, fontSize = MailMetrics.t13)
                }
            }
        }
    }
}

/** A trigger for call sites that only need "an error happened": the row plus the dialog it opens. */
@Composable
fun ErrorActionsWithLog(modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ErrorActionsRow(onSeeLog = { open = true }, modifier = modifier)
    if (open) ErrorLogDialog { open = false }
}

/** An error snackbar: the message with a "See log" action that [onSeeLog] answers; the line also goes to the mail log ring. */
suspend fun androidx.compose.material3.SnackbarHostState.showError(message: String, onSeeLog: () -> Unit) {
    ErrorLog.shared.log("snackbar", message)
    val r = showSnackbar(message, actionLabel = ErrorActions.SEE_LOG, duration = androidx.compose.material3.SnackbarDuration.Long)
    if (r == androidx.compose.material3.SnackbarResult.ActionPerformed) onSeeLog()
}
