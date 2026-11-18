package app.sterna.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import app.sterna.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import app.sterna.R
import app.sterna.ui.theme.MailMetrics
import java.text.DateFormat
import java.util.Date

/*
 * #1803 the recipient-deliverability UI: the mark at the start of every chip, the detail sheet a tap on
 * it opens, and the question Send asks when a recipient will fail. Each verdict has its OWN shape as well
 * as its own colour, so the mark reads without colour vision.
 */

internal fun verdictIcon(verdict: RecipientVerdict): ImageVector? = when (verdict) {
    RecipientVerdict.OK -> Icons.Filled.CheckCircle
    RecipientVerdict.RISKY -> Icons.Filled.Warning
    RecipientVerdict.WILL_FAIL -> Icons.Filled.Error
    RecipientVerdict.UNKNOWN -> Icons.AutoMirrored.Outlined.HelpOutline
    RecipientVerdict.CHECKING -> null
}

@Composable
internal fun verdictColor(verdict: RecipientVerdict): Color = when (verdict) {
    RecipientVerdict.OK -> MaterialTheme.colorScheme.primary
    RecipientVerdict.RISKY -> MaterialTheme.colorScheme.tertiary
    RecipientVerdict.WILL_FAIL -> MaterialTheme.colorScheme.error
    RecipientVerdict.UNKNOWN, RecipientVerdict.CHECKING -> MaterialTheme.colorScheme.onSurfaceVariant
}

internal fun verdictLabel(verdict: RecipientVerdict): Int = when (verdict) {
    RecipientVerdict.OK -> R.string.compose_recipient_check_verdict_ok
    RecipientVerdict.RISKY -> R.string.compose_recipient_check_verdict_risky
    RecipientVerdict.WILL_FAIL -> R.string.compose_recipient_check_verdict_will_fail
    RecipientVerdict.UNKNOWN -> R.string.compose_recipient_check_verdict_unknown
    RecipientVerdict.CHECKING -> R.string.compose_recipient_check_verdict_checking
}

internal fun checkKindLabel(kind: RecipientCheckKind): Int = when (kind) {
    RecipientCheckKind.SYNTAX -> R.string.compose_recipient_check_kind_syntax
    RecipientCheckKind.DOMAIN -> R.string.compose_recipient_check_kind_domain
    RecipientCheckKind.MAILBOX -> R.string.compose_recipient_check_kind_mailbox
    RecipientCheckKind.PAST_BOUNCE -> R.string.compose_recipient_check_kind_past_bounce
    RecipientCheckKind.DISPOSABLE_ROLE -> R.string.compose_recipient_check_kind_disposable_role
    RecipientCheckKind.SIZE_LIMIT -> R.string.compose_recipient_check_kind_size_limit
}

internal fun outcomeLabel(outcome: CheckOutcome): Int = when (outcome) {
    CheckOutcome.PASS -> R.string.compose_recipient_check_outcome_pass
    CheckOutcome.WARN -> R.string.compose_recipient_check_outcome_warn
    CheckOutcome.FAIL -> R.string.compose_recipient_check_outcome_fail
    CheckOutcome.UNKNOWN -> R.string.compose_recipient_check_outcome_unknown
}

internal fun outcomeVerdict(outcome: CheckOutcome): RecipientVerdict = when (outcome) {
    CheckOutcome.PASS -> RecipientVerdict.OK
    CheckOutcome.WARN -> RecipientVerdict.RISKY
    CheckOutcome.FAIL -> RecipientVerdict.WILL_FAIL
    CheckOutcome.UNKNOWN -> RecipientVerdict.UNKNOWN
}

internal fun sourceLabel(source: CheckSource): Int = when (source) {
    CheckSource.PHONE -> R.string.compose_recipient_check_source_phone
    CheckSource.SERVER -> R.string.compose_recipient_check_source_server
    CheckSource.NONE -> R.string.compose_recipient_check_source_none
}

internal fun syntaxProblemText(problem: SyntaxProblem): Int = when (problem) {
    SyntaxProblem.EMPTY -> R.string.compose_recipient_check_syntax_empty
    SyntaxProblem.SPACE -> R.string.compose_recipient_check_syntax_space
    SyntaxProblem.NO_AT -> R.string.compose_recipient_check_syntax_no_at
    SyntaxProblem.TOO_LONG -> R.string.compose_recipient_check_syntax_too_long
    SyntaxProblem.LOCAL_TOO_LONG -> R.string.compose_recipient_check_syntax_local_too_long
    SyntaxProblem.LOCAL_DOTS -> R.string.compose_recipient_check_syntax_local_dots
    SyntaxProblem.DOMAIN_NO_DOT -> R.string.compose_recipient_check_syntax_domain_no_dot
    SyntaxProblem.DOMAIN_DOTS -> R.string.compose_recipient_check_syntax_domain_dots
    SyntaxProblem.DOMAIN_LABEL_TOO_LONG -> R.string.compose_recipient_check_syntax_domain_label_too_long
    SyntaxProblem.DOMAIN_HYPHEN -> R.string.compose_recipient_check_syntax_domain_hyphen
    SyntaxProblem.DOMAIN_CHARACTER -> R.string.compose_recipient_check_syntax_domain_character
    SyntaxProblem.TLD -> R.string.compose_recipient_check_syntax_tld
    SyntaxProblem.FORMAT -> R.string.compose_recipient_check_syntax_format
}

/** One verdict, drawn: its icon in its colour, or a small ring while it is still being checked. */
@Composable
internal fun VerdictGlyph(verdict: RecipientVerdict, modifier: Modifier = Modifier) {
    val icon = verdictIcon(verdict)
    if (icon == null) {
        CircularProgressIndicator(modifier = modifier.size(MailMetrics.s16), strokeWidth = MailMetrics.s2)
    } else {
        Icon(icon, contentDescription = null, tint = verdictColor(verdict), modifier = modifier.size(MailMetrics.s18))
    }
}

/**
 * The mark at the start of a chip. Not checked yet ([status] null) shows the checking ring, so a chip is
 * never drawn as if it had passed. The tap is the mark's own (the chip's tap still hands the address
 * back for editing, #94) and opens the detail sheet.
 */
@Composable
internal fun RecipientStatusMark(status: RecipientStatus?, onClick: () -> Unit) {
    val verdict = status?.verdict ?: RecipientVerdict.CHECKING
    val description = stringResource(R.string.compose_recipient_check_mark, stringResource(verdictLabel(verdict)))
    VerdictGlyph(
        verdict,
        modifier = Modifier
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    )
}

/** The text of one check's detail line: the phone's syntax problem localised, else the server's words. */
@Composable
private fun checkDetailText(item: RecipientCheckItem): String? {
    if (item.kind == RecipientCheckKind.SYNTAX && item.source == CheckSource.PHONE) {
        val problem = item.detail?.let { d -> SyntaxProblem.entries.firstOrNull { it.name == d } }
        if (problem != null) return stringResource(syntaxProblemText(problem))
    }
    if (item.kind == RecipientCheckKind.MAILBOX && item.acceptsAll) {
        val accepts = stringResource(R.string.compose_recipient_check_mailbox_accepts_all)
        return listOfNotNull(accepts, item.detail).joinToString("\n")
    }
    return item.detail
}

/**
 * The detail sheet: the address, its verdict, then EVERY check (syntax, domain, mailbox, past bounce,
 * disposable/role, size limit) with its outcome, who answered it and the reason given; when it was
 * checked, whether the check service is connected or was reached, and "Check again".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecipientStatusSheet(
    address: String,
    status: RecipientStatus?,
    checkerBound: Boolean,
    onCheckAgain: () -> Unit,
    onDismiss: () -> Unit,
) {
    val shown = status ?: localRecipientStatus(address, pending = true, nowMillis = 0L)
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = MailMetrics.s24)
                .padding(bottom = MailMetrics.s32),
            verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
        ) {
            Text(stringResource(R.string.compose_recipient_check_title), style = MaterialTheme.typography.titleLarge)
            Text(address, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                VerdictGlyph(shown.verdict)
                Text(
                    stringResource(verdictLabel(shown.verdict)),
                    style = MaterialTheme.typography.titleMedium,
                    color = verdictColor(shown.verdict),
                )
            }
            shown.serverReason?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (shown.serverCached) {
                Text(
                    stringResource(R.string.compose_recipient_check_server_cached),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            shown.checks.forEach { item ->
                Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                    VerdictGlyph(outcomeVerdict(item.outcome), modifier = Modifier.padding(top = MailMetrics.s2))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8)) {
                            Text(
                                stringResource(checkKindLabel(item.kind)),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                stringResource(outcomeLabel(item.outcome)),
                                style = MaterialTheme.typography.labelMedium,
                                color = verdictColor(outcomeVerdict(item.outcome)),
                            )
                        }
                        checkDetailText(item)?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(
                            stringResource(sourceLabel(item.source)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
            if (shown.checkedAtMillis > 0L) {
                val at = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(shown.checkedAtMillis))
                Text(
                    stringResource(R.string.compose_recipient_check_checked_at, at),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!checkerBound) {
                Text(
                    stringResource(R.string.compose_recipient_check_not_connected),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            shown.serverNote?.let {
                Text(
                    stringResource(R.string.compose_recipient_check_unreachable, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8, Alignment.End),
            ) {
                TextButton(onClick = onCheckAgain) { Text(stringResource(R.string.compose_recipient_check_again)) }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.compose_recipient_check_close)) }
            }
        }
    }
}

/**
 * Send's question when a recipient will fail. It names each one with the reason its failing check gave
 * (a tap opens that recipient's sheet), and offers "Send anyway" next to "Back": it only asks.
 */
@Composable
internal fun UndeliverableRecipientsDialog(
    recipients: List<String>,
    statuses: Map<String, RecipientStatus>,
    onSendAnyway: () -> Unit,
    onBack: () -> Unit,
    onDetail: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onBack,
        icon = { Icon(Icons.Filled.RemoveCircleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
        title = { Text(stringResource(R.string.compose_recipient_check_undeliverable_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(MailMetrics.s8),
            ) {
                Text(stringResource(R.string.compose_recipient_check_undeliverable_message))
                recipients.forEach { address ->
                    val status = statuses[recipientKey(address)]
                    val failing = status?.checks?.filter { it.outcome == CheckOutcome.FAIL }.orEmpty()
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { onDetail(address) },
                        horizontalArrangement = Arrangement.spacedBy(MailMetrics.s8),
                    ) {
                        VerdictGlyph(RecipientVerdict.WILL_FAIL, modifier = Modifier.padding(top = MailMetrics.s2))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(address, style = MaterialTheme.typography.bodyMedium)
                            failing.forEach { item ->
                                val reason = checkDetailText(item)
                                Text(
                                    stringResource(checkKindLabel(item.kind)) + (reason?.let { ": $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onSendAnyway) { Text(stringResource(R.string.compose_recipient_check_send_anyway)) }
        },
        dismissButton = {
            TextButton(onClick = onBack) { Text(stringResource(R.string.compose_recipient_check_back)) }
        },
    )
}
