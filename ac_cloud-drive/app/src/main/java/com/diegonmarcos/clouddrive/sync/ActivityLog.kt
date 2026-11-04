package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.clouddrive.ui.StatusLightRow
import java.text.DateFormat
import java.util.Date

/**
 * Activity Logs' PURE RULES (JVM-tested in ActivityLogTest): what an entry says, what is copied, which entries are shown.
 *
 *  - An entry with no repository, no summary and no details carries nothing: it is not listed (these were the bare
 *    "manual" rows: a writer stored a blank engine summary). One with only a blank summary is shown with its kind and
 *    "no result recorded" instead of an empty line.
 *  - The copied text is the FULL entry, never truncated: timestamp, result, repository, trigger, kind, summary and the
 *    whole details (error output). "Copy all" / "Copy filtered" / Share are blocks of that text, one per entry,
 *    separated by a blank line.
 */
object ActivityLog {
    const val NO_RESULT = "no result recorded"

    enum class Status { ALL, FAILED, OK }

    fun isEmpty(e: SyncEvent): Boolean = e.summary.isBlank() && e.details.isBlank() && e.repoName.isBlank()

    /** What ran: the operation id the writers prefix ("clone: …", "sync_no_winner: …"), else "sync". */
    fun kindOf(e: SyncEvent): String {
        val head = e.summary.substringBefore(':', "").trim()
        return if (head.isNotEmpty() && Regex("[A-Za-z0-9_-]+").matches(head)) head else "sync"
    }

    /** The summary without the kind prefix, or [NO_RESULT] (falling back to the first details line). */
    fun headline(e: SyncEvent): String {
        val k = e.summary.substringBefore(':', "").trim()
        val rest = if (k.isNotEmpty() && k == kindOf(e) && e.summary.contains(':')) e.summary.substringAfter(':').trim() else e.summary.trim()
        return rest.ifBlank { e.details.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: NO_RESULT }
    }

    fun visible(events: List<SyncEvent>): List<SyncEvent> = events.filterNot(::isEmpty)

    fun filter(events: List<SyncEvent>, status: Status, query: String): List<SyncEvent> = visible(events).filter { e ->
        (status == Status.ALL || (status == Status.FAILED) == !e.ok) &&
            (query.isBlank() || listOf(e.repoName, e.summary, e.details, e.trigger).any { it.contains(query.trim(), ignoreCase = true) })
    }

    /** One entry as plain text, untruncated. */
    fun entryText(e: SyncEvent, time: (Long) -> String): String = buildString {
        append(time(e.epochSeconds)).append("  ").append(if (e.ok) "OK" else "FAILED").append("  ")
            .append(e.repoName.ifBlank { "(no repository)" }).append("  (").append(e.trigger).append(")\n")
        append("kind: ").append(kindOf(e)).append('\n')
        append(headline(e))
        if (e.details.isNotBlank()) append("\n").append(e.details.trimEnd())
    }

    fun allText(events: List<SyncEvent>, time: (Long) -> String): String = events.joinToString("\n\n") { entryText(it, time) }
}

/** The Activity Logs view: filter, Copy all / Copy filtered / Share in the header, entries tap-to-expand, long-press or Copy per entry. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ActivityLogsView(events: List<SyncEvent>, fmt: DateFormat) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    var status by remember { mutableStateOf(ActivityLog.Status.ALL) }
    var query by remember { mutableStateOf("") }
    val time: (Long) -> String = { fmt.format(Date(it * 1000)) }
    val all = ActivityLog.visible(events).take(Declarations.sync.git.activityMax)
    val shown = ActivityLog.filter(all, status, query)
    val copied = stringResource(R.string.activity_copied)
    fun copy(text: String) { clip.setText(AnnotatedString(text)); Toast.makeText(ctx, copied, Toast.LENGTH_SHORT).show() }
    val shareTitle = stringResource(R.string.sync_history_section)

    PillRow {
        Pill(stringResource(R.string.activity_copy_all), { copy(ActivityLog.allText(all, time)) }, enabled = all.isNotEmpty())
        Pill(stringResource(R.string.activity_copy_filtered), { copy(ActivityLog.allText(shown, time)) }, enabled = shown.isNotEmpty())
        Pill(stringResource(R.string.activity_share), {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, shareTitle).putExtra(Intent.EXTRA_TEXT, ActivityLog.allText(shown, time))
            ctx.startActivity(Intent.createChooser(send, shareTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, enabled = shown.isNotEmpty())
    }
    PillRow {
        for (s in ActivityLog.Status.values()) Pill(stringResource(when (s) {
            ActivityLog.Status.ALL -> R.string.activity_filter_all
            ActivityLog.Status.FAILED -> R.string.activity_filter_failed
            ActivityLog.Status.OK -> R.string.activity_filter_ok
        }), { status = s }, filled = status == s)
    }
    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(stringResource(R.string.activity_filter_query)) })
    if (shown.isEmpty()) Text(stringResource(R.string.sync_history_empty), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    LazyColumn(Modifier.fillMaxWidth()) {
        items(shown.distinctBy { "${it.epochSeconds}-${it.repoId}-${it.summary.hashCode()}-${it.details.hashCode()}" }, key = { "${it.epochSeconds}-${it.repoId}-${it.summary.hashCode()}-${it.details.hashCode()}" }) { e ->
            var open by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().combinedClickable(onClick = { open = !open }, onLongClick = { copy(ActivityLog.entryText(e, time)) }).padding(vertical = DriveMetrics.gap)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLightRow(if (e.ok) StatusLight.State.ON else StatusLight.State.OFF, e.repoName.ifBlank { ActivityLog.kindOf(e) })
                    Spacer(Modifier.width(DriveMetrics.pad))
                    Text(time(e.epochSeconds), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    CapsuleBadge(ActivityLog.kindOf(e))
                    Spacer(Modifier.width(DriveMetrics.pad))
                    CapsuleBadge(stringResource(if (e.trigger == SyncHistory.TRIGGER_SCHEDULED) R.string.sync_trigger_scheduled else R.string.sync_trigger_manual))
                }
                if (open) {
                    SelectionContainer { Column {
                        Text(ActivityLog.headline(e), style = MaterialTheme.typography.bodySmall)
                        if (e.details.isNotBlank()) Text(e.details, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } }
                    Pill(stringResource(R.string.activity_copy_entry), { copy(ActivityLog.entryText(e, time)) })
                } else Text(ActivityLog.headline(e), style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
