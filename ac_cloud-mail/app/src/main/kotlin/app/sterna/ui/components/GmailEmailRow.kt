package app.sterna.ui.components

import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import app.sterna.R
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailBodyPart
import app.sterna.ui.message.CopyCodeIcon
import app.sterna.ui.message.copyVerificationCodeOrSayNone
import app.sterna.ui.message.receivedTextToolSource
import app.sterna.ui.rememberMotionEnabled
import app.sterna.ui.text.TextTool
import app.sterna.ui.text.TextToolSurface
import app.sterna.ui.text.rememberTextToolRunner
import app.sterna.ui.theme.GmailStarYellow
import app.sterna.ui.theme.LocalMailListPalette
import app.sterna.ui.theme.MailMetrics
import app.sterna.ui.theme.mailListTextInk
import app.sterna.util.MailDates
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The semantics tags of Gmail Dark's row, for the tests that render it. One declaration, read by both.
 */
internal object GmailRowTags {
    const val CARD = "gmail_row_card"
    const val AVATAR_SILHOUETTE = "gmail_row_avatar_silhouette"
    const val AVATAR_SELECTED = "gmail_row_avatar_selected"
    const val IMPORTANT = "gmail_row_important"
    const val SENDER = "gmail_row_sender"
    const val COUNT = "gmail_row_count"
    const val TIME = "gmail_row_time"
    const val UNREAD_DOT = "gmail_row_unread_dot"
    const val SUBJECT = "gmail_row_subject"
    const val STAR = "gmail_row_star"
    const val SNIPPET = "gmail_row_snippet"
    const val LABEL = "gmail_row_label"
    const val CHIP_LINE = "gmail_row_chip_line"
    const val CHIP_AI = "gmail_row_chip_ai"
    const val CHIP_COPY = "gmail_row_chip_copy"
    const val CHIP_FILE = "gmail_row_chip_file"
}

/** The importance marker's source: the `$important` keyword, when the server or cache carries it. */
internal fun gmailIsImportant(email: Email): Boolean = email.keywords["\$important"] == true

/**
 * Whether the fourth line is drawn: only when something is on it. The ✨ and copy chips need a
 * message to act on (a loader that fetches the body, or at least a snippet); the file chips need a
 * tap handler and a file.
 */
internal fun gmailChipLineShown(hasMessageToActOn: Boolean, fileChips: Int): Boolean =
    hasMessageToActOn || fileChips > 0

/** Gmail's card: 16dp corners all round. */
private val GmailCardShape = RoundedCornerShape(MailMetrics.gmailCardCorner)

/**
 * One conversation in Gmail Dark, drawn the way the Gmail Android app draws it: a rounded card on
 * the page, avatar (sender photo, or Gmail's grey silhouette), then
 *  1. [▸] sender [count] … time [•]   — bold with a filled unread dot when unread
 *  2. subject …                      ☆ — the outline star on the subject line
 *  3. snippet (grey, regular)  [label]
 *  4. [✨] [⧉] [file] [file]           — only when something is on it
 * The parameters are [EmailListItem]'s, handed through unchanged; that function calls this one when
 * the style is Gmail Dark, so every list (folders, conversations, search, Starred) follows.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
internal fun GmailEmailRow(
    email: Email,
    onClick: () -> Unit,
    modifier: Modifier,
    originLabel: String?,
    originColor: Color?,
    onToggleFavourite: (() -> Unit)?,
    selected: Boolean,
    current: Boolean,
    onLongClick: (() -> Unit)?,
    unread: Boolean,
    threadCount: Int,
    threadExpandable: Boolean,
    onToggleExpand: (() -> Unit)?,
    expanded: Boolean,
    highlighted: Boolean,
    onHighlightShown: () -> Unit,
    showRecipients: Boolean,
    showDraftBadge: Boolean,
    showNotUploadedBadge: Boolean,
    onOpenAttachment: ((EmailBodyPart) -> Unit)?,
    openingAttachmentKey: String?,
    onLoadMessage: (suspend (Email) -> Email)?,
    deliveryTimeline: app.sterna.send.delivery.DeliveryTimeline? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val palette = LocalMailListPalette.current
    // The same ONE read/unread decision every row wears (#478): ink and weight together.
    val ink = mailListTextInk(unread, palette)
    val senderName = email.from.firstOrNull()?.display() ?: stringResource(R.string.message_unknown_sender)
    val recipient = if (showRecipients) email.to.firstOrNull() else null
    val nameLine = if (recipient != null) {
        stringResource(R.string.list_to_recipients, email.to.joinToString { it.display() })
    } else {
        senderName
    }
    val receivedLabel = remember(email.receivedAt) { MailDates.formatListDate(email.receivedAt) }
    val motionOn = rememberMotionEnabled()
    val highlight = remember { Animatable(0f) }
    LaunchedEffect(highlighted) {
        if (!highlighted) return@LaunchedEffect
        if (motionOn) {
            delay(100)
            highlight.animateTo(1f, tween(160))
            highlight.animateTo(0f, tween(840, easing = FastOutSlowInEasing))
        }
        onHighlightShown()
    }
    val haptics = LocalHapticFeedback.current
    val enterSelection = onLongClick?.let { enter ->
        {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            enter()
        }
    }
    val attachmentParts = remember(email.id, email.attachments) {
        if (onOpenAttachment == null) emptyList() else email.fileAttachmentParts()
    }
    val base = MaterialTheme.typography.bodyMedium
    val senderStyle = base.copy(fontSize = MailMetrics.gmailSender, lineHeight = MailMetrics.gmailSenderLine)
    val bodyStyle = base.copy(fontSize = MailMetrics.gmailBody, lineHeight = MailMetrics.gmailBodyLine)
    val smallStyle = base.copy(fontSize = MailMetrics.gmailSmall, lineHeight = MailMetrics.gmailSmallLine)
    val grey = scheme.onSurfaceVariant
    val rowColor = rowBackground(scheme, selected = selected, current = current, flash = highlight.value, card = palette.card)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MailMetrics.gmailCardInsetH, vertical = MailMetrics.gmailCardGap)
            .clip(GmailCardShape)
            .background(rowColor)
            .combinedClickable(onClick = onClick, onLongClick = enterSelection)
            .testTag(GmailRowTags.CARD)
            .padding(horizontal = MailMetrics.gmailRowPadH, vertical = MailMetrics.gmailRowPadV),
        verticalAlignment = Alignment.Top,
    ) {
        if (LocalListMonogram.current) {
            GmailAvatar(
                selected = selected,
                email = (recipient ?: email.from.firstOrNull())?.email ?: senderName,
                name = recipient?.display() ?: senderName,
            )
            Spacer(Modifier.width(MailMetrics.gmailAvatarGap))
        }
        Column(Modifier.weight(1f)) {
            // 1. [▸] sender [count] [Draft] … [📎] time [•]
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    if (gmailIsImportant(email)) {
                        val important = stringResource(R.string.folder_important)
                        Text(
                            text = "▸",
                            style = senderStyle,
                            color = GmailStarYellow,
                            modifier = Modifier
                                .testTag(GmailRowTags.IMPORTANT)
                                .semantics { contentDescription = important },
                        )
                        Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                    }
                    Text(
                        text = nameLine,
                        style = senderStyle,
                        color = ink.color,
                        fontWeight = ink.weight,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false).testTag(GmailRowTags.SENDER),
                    )
                    if (threadExpandable && threadCount > 1) {
                        Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                        GmailThreadCount(threadCount, expanded, onToggleExpand, bodyStyle, grey)
                    }
                    if (showDraftBadge) {
                        Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                        Text(stringResource(R.string.draft_label), style = bodyStyle, color = scheme.error, maxLines = 1)
                    }
                    if (showNotUploadedBadge) {
                        Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                        Text(stringResource(R.string.local_draft_not_uploaded), style = bodyStyle, color = grey, maxLines = 1)
                    }
                }
                Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                // A file the row cannot chip (a search hit, a row cached before parts were kept):
                // Gmail's paperclip beside the date says only that there is one.
                if (email.hasAttachment && attachmentParts.isEmpty()) {
                    Icon(
                        Icons.Filled.AttachFile,
                        contentDescription = stringResource(R.string.a11y_has_attachment),
                        tint = grey,
                        modifier = Modifier.size(MailMetrics.gmailChipIcon),
                    )
                    Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                }
                Text(
                    text = receivedLabel,
                    style = smallStyle,
                    color = ink.color,
                    fontWeight = ink.weight,
                    maxLines = 1,
                    modifier = Modifier.testTag(GmailRowTags.TIME),
                )
                deliveryTimeline?.let { DeliveryTicker(it, ink = ink.color) }
                if (unread) {
                    Spacer(Modifier.width(MailMetrics.gmailDotGap))
                    Box(
                        Modifier
                            .size(MailMetrics.gmailUnreadDot)
                            .clip(CircleShape)
                            .background(scheme.primary)
                            .testTag(GmailRowTags.UNREAD_DOT),
                    )
                }
            }
            // 2. subject … ☆
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = email.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_no_subject),
                    style = bodyStyle,
                    color = ink.color,
                    fontWeight = ink.weight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).testTag(GmailRowTags.SUBJECT),
                )
                if (onToggleFavourite != null) {
                    GmailStar(flagged = email.isFlagged, onToggle = onToggleFavourite, lineStyle = bodyStyle)
                }
            }
            // 3. snippet [label]
            val preview = email.preview?.takeIf { it.isNotBlank() && LocalPreviewLines.current.lines > 0 }
            val label = originLabel?.takeIf { it.isNotBlank() }
            if (preview != null || label != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = preview.orEmpty(),
                        style = bodyStyle,
                        color = grey,
                        fontWeight = FontWeight.Normal,
                        fontStyle = FontStyle.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).testTag(GmailRowTags.SNIPPET),
                    )
                    if (label != null) {
                        Spacer(Modifier.width(MailMetrics.gmailLabelGap))
                        Text(
                            text = label,
                            style = smallStyle,
                            fontWeight = FontWeight.Medium,
                            color = originColor ?: grey,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clip(RoundedCornerShape(MailMetrics.gmailLabelCorner))
                                .background(originColor?.copy(alpha = 0.16f) ?: scheme.surfaceContainerHighest)
                                .padding(horizontal = MailMetrics.gmailLabelPadH)
                                .testTag(GmailRowTags.LABEL),
                        )
                    }
                }
            }
            // 4. [✨] [⧉] [files] — only when something is on it.
            val fileChips = if (onOpenAttachment != null) attachmentParts.size else 0
            val hasMessage = onLoadMessage != null || !email.preview.isNullOrBlank()
            if (gmailChipLineShown(hasMessage, fileChips)) {
                GmailChipLine(
                    email = email,
                    showActions = hasMessage,
                    onLoadMessage = onLoadMessage,
                    parts = if (onOpenAttachment != null) attachmentParts else emptyList(),
                    openingKey = openingAttachmentKey,
                    onOpen = onOpenAttachment,
                    textStyle = bodyStyle,
                )
            }
        }
    }
}

/** The avatar: the sender's photo (or domain logo) when the app has one, else Gmail's grey silhouette; a check when selected. */
@Composable
private fun GmailAvatar(selected: Boolean, email: String, name: String) {
    val scheme = MaterialTheme.colorScheme
    if (selected) {
        Box(
            Modifier
                .size(MailMetrics.gmailAvatar)
                .clip(CircleShape)
                .background(scheme.primary)
                .testTag(GmailRowTags.AVATAR_SELECTED),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = scheme.onPrimary)
        }
        return
    }
    ContactAvatar(
        email = email,
        name = name,
        photoUri = null,
        size = MailMetrics.gmailAvatar,
        placeholder = {
            Box(
                Modifier
                    .size(MailMetrics.gmailAvatar)
                    .clip(CircleShape)
                    .background(scheme.surfaceContainerHighest)
                    .testTag(GmailRowTags.AVATAR_SILHOUETTE),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(MailMetrics.gmailStar),
                )
            }
        },
    )
}

/** The plain grey message count after the sender; it still unfolds the conversation when tapped. */
@Composable
private fun GmailThreadCount(
    count: Int,
    expanded: Boolean,
    onToggleExpand: (() -> Unit)?,
    style: TextStyle,
    color: Color,
) {
    val label = stringResource(R.string.a11y_conversation_pill, count)
    val state = stringResource(
        if (expanded) R.string.a11y_conversation_expanded else R.string.a11y_conversation_collapsed,
    )
    val tap = if (onToggleExpand != null) {
        Modifier
            .clickable(onClick = onToggleExpand)
            .semantics {
                contentDescription = label
                stateDescription = state
                role = Role.Button
            }
    } else {
        Modifier
    }
    Text(
        text = count.toString(),
        style = style,
        color = color,
        fontWeight = FontWeight.Normal,
        maxLines = 1,
        modifier = Modifier.then(tap).testTag(GmailRowTags.COUNT),
    )
}

/**
 * The outline star (filled yellow when starred), on the subject line. It takes no more height than
 * the line itself — the row stays Gmail-compact — while its touch box is the full square, centred on
 * the line and overhanging it above and below.
 */
@Composable
private fun GmailStar(flagged: Boolean, onToggle: () -> Unit, lineStyle: TextStyle) {
    val label = stringResource(if (flagged) R.string.a11y_unfavourite else R.string.a11y_favourite)
    val lineHeight = with(LocalDensity.current) { lineStyle.lineHeight.roundToPx() }
    Box(
        modifier = Modifier
            .layout { measurable, constraints ->
                val box = MailMetrics.gmailStarBox.roundToPx()
                val placeable = measurable.measure(Constraints.fixed(box, box))
                layout(box, minOf(lineHeight, constraints.maxHeight)) {
                    placeable.place(0, (lineHeight - box) / 2)
                }
            }
            .clip(CircleShape)
            .clickable(onClick = onToggle)
            .testTag(GmailRowTags.STAR)
            .clearAndSetSemantics {
                contentDescription = label
                role = Role.Button
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (flagged) Icons.Filled.Star else Icons.Outlined.StarOutline,
            contentDescription = null,
            tint = if (flagged) GmailStarYellow else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.requiredSize(MailMetrics.gmailStar),
        )
    }
}

/** One chip of the fourth line, in Gmail's attachment-chip shape: an outlined pill. */
@Composable
private fun GmailChip(
    tag: String,
    onClick: (() -> Unit)?,
    enabled: Boolean = true,
    spoken: String? = null,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val pill = RoundedCornerShape(MailMetrics.gmailChipCorner)
    Row(
        modifier = Modifier
            .height(MailMetrics.gmailChipHeight)
            .clip(pill)
            .border(MailMetrics.gmailChipBorder, scheme.outlineVariant, pill)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, onClick = onClick) else Modifier)
            .padding(horizontal = MailMetrics.gmailChipPadH)
            .testTag(tag)
            .then(
                if (spoken != null) {
                    Modifier.clearAndSetSemantics {
                        contentDescription = spoken
                        role = Role.Button
                    }
                } else {
                    Modifier
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/**
 * The fourth line: ✨ (the reader's Resume Mail) and copy (the reader's Copy Code) as compact chips,
 * then the message's file chips, all in Gmail's attachment-chip style. The two actions are the SAME
 * declarations the Cloud rows use ([TextTool.RESUME], [CopyCodeIcon], [copyVerificationCodeOrSayNone])
 * acting on the message fetched through [onLoadMessage] (#514), never on the snippet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GmailChipLine(
    email: Email,
    showActions: Boolean,
    onLoadMessage: (suspend (Email) -> Email)?,
    parts: List<EmailBodyPart>,
    openingKey: String?,
    onOpen: ((EmailBodyPart) -> Unit)?,
    textStyle: TextStyle,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    val runner = rememberTextToolRunner(TextToolSurface.READ)
    LaunchedEffect(runner.outcome) {
        val outcome = runner.outcome ?: return@LaunchedEffect
        Toast.makeText(context, outcome.text ?: outcome.error, Toast.LENGTH_SHORT).show()
        runner.dismiss()
    }
    val scheme = MaterialTheme.colorScheme
    FlowRow(
        modifier = Modifier.padding(top = MailMetrics.gmailChipLineTop).testTag(GmailRowTags.CHIP_LINE),
        horizontalArrangement = Arrangement.spacedBy(MailMetrics.gmailChipGap),
        verticalArrangement = Arrangement.spacedBy(MailMetrics.gmailChipGap),
    ) {
        if (showActions) {
            val busy = runner.busy != null
            GmailChip(
                tag = GmailRowTags.CHIP_AI,
                enabled = !busy,
                spoken = stringResource(TextTool.RESUME.label),
                onClick = {
                    scope.launch {
                        val message = rowMessage(email, onLoadMessage)
                        runner.run(scope, TextTool.RESUME, receivedTextToolSource(message))
                    }
                },
            ) {
                if (busy) {
                    LoadingRing(Modifier.size(MailMetrics.gmailChipIcon), strokeWidth = MailMetrics.gmailChipBorder)
                } else {
                    Icon(TextTool.RESUME.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(MailMetrics.gmailChipIcon))
                }
            }
            // No spoken label of its own: the reader's Copy Code glyph already carries it, and the
            // chip's click merges it in, so a screen reader hears the label once.
            GmailChip(
                tag = GmailRowTags.CHIP_COPY,
                onClick = { scope.launch { copyVerificationCodeOrSayNone(clipboard, context, rowMessage(email, onLoadMessage)) } },
            ) {
                // The reader's own Copy Code glyph, boxed to the chip's icon size.
                Box(Modifier.size(MailMetrics.gmailChipIcon)) {
                    CompositionLocalProvider(LocalContentColor provides scheme.onSurfaceVariant) { CopyCodeIcon() }
                }
            }
        }
        if (onOpen != null) {
            parts.take(MAX_ATTACHMENT_CHIPS).forEach { part ->
                val name = part.name?.takeIf { it.isNotBlank() } ?: stringResource(R.string.message_attachment_fallback)
                val busy = openingKey != null && openingKey == attachmentKey(email, part)
                GmailChip(
                    tag = GmailRowTags.CHIP_FILE,
                    enabled = !busy,
                    spoken = stringResource(R.string.a11y_open_attachment, name),
                    onClick = { onOpen(part) },
                ) {
                    if (busy) {
                        LoadingRing(Modifier.size(MailMetrics.gmailChipIcon), strokeWidth = MailMetrics.gmailChipBorder)
                    } else {
                        val (icon, tint) = gmailFileIcon(part.type, scheme.error, scheme.onSurfaceVariant)
                        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(MailMetrics.gmailChipIcon))
                    }
                    Spacer(Modifier.width(MailMetrics.gmailInlineGap))
                    Text(
                        text = attachmentChipLabel(name),
                        style = textStyle,
                        fontWeight = FontWeight.Medium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            val hidden = parts.size - MAX_ATTACHMENT_CHIPS
            if (hidden > 0) {
                // "+N" is a count, not a button: a tap falls through to the card and opens the message.
                GmailChip(tag = GmailRowTags.CHIP_FILE, onClick = null) {
                    Text(stringResource(R.string.list_more_attachments, hidden), style = textStyle, color = scheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Gmail's chip glyph for a file type: a red PDF, a picture, else the paperclip. */
private fun gmailFileIcon(type: String?, red: Color, grey: Color) = when {
    type.equals("application/pdf", ignoreCase = true) -> Icons.Filled.PictureAsPdf to red
    type?.startsWith("image/", ignoreCase = true) == true -> Icons.Filled.Image to red
    else -> Icons.Filled.AttachFile to grey
}
