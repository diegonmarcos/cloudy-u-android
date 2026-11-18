package app.sterna.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import app.sterna.SternaApplication
import app.sterna.core.jmap.model.Email
import app.sterna.send.delivery.DeliveryKey
import app.sterna.send.delivery.DeliveryStatusRepository
import app.sterna.send.delivery.DeliveryTimeline
import app.sterna.send.delivery.PhoneDelivery
import java.time.Instant
import java.time.OffsetDateTime
import kotlinx.coroutines.delay

/** A folder role that files sent mail (JMAP/IMAP special-use `sent`). */
internal fun isSentRole(role: String?): Boolean = role?.trim()?.lowercase() == "sent"

/** The key the fleet mail API files a sent message under: its account and RFC 5322 Message-ID. */
internal fun deliveryKeyOf(email: Email): DeliveryKey? {
    val id = email.messageId.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return DeliveryKey(email.accountId.orEmpty(), id, accountAddress = email.from.firstOrNull()?.email)
}

internal fun sentAtMillis(email: Email): Long? = email.receivedAt?.let { iso ->
    runCatching { Instant.parse(iso).toEpochMilli() }
        .recoverCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        .getOrNull()
}

/**
 * Stage 1 for a message in the SENT folder: it is there because it left the outbox (a queued or
 * failed send lives in the Outbox screen, never in Sent), so the phone's record is "handed off",
 * at the message's own sent time.
 */
internal fun sentFolderPhoneDelivery(email: Email): PhoneDelivery =
    PhoneDelivery(queuedInOutbox = false, handedOffAtMillis = sentAtMillis(email))

/**
 * #1802: the delivery timeline of one Sent message, from the per-message cache, refreshed from the
 * fleet mail API while it is on screen and not settled ([DeliveryStatusRepository.nextPollDelayMillis]).
 * Null when [enabled] is false, when the message has no Message-ID, or outside the real app (a
 * test host has no container): the row then draws exactly what it drew before.
 */
@Composable
fun rememberSentDeliveryTimeline(email: Email, enabled: Boolean): DeliveryTimeline? {
    if (!enabled) return null
    val repo = (LocalContext.current.applicationContext as? SternaApplication)
        ?.takeIf { it.isContainerReady }?.container?.deliveryStatus ?: return null
    val key = remember(email.accountId, email.messageId) { deliveryKeyOf(email) } ?: return null
    return rememberDeliveryTimeline(repo, key, sentFolderPhoneDelivery(email), sentAtMillis(email))
}

@Composable
internal fun rememberDeliveryTimeline(
    repo: DeliveryStatusRepository,
    key: DeliveryKey,
    phone: PhoneDelivery,
    sentAt: Long?,
): DeliveryTimeline {
    val all by repo.all.collectAsState()
    LaunchedEffect(key, phone) {
        // A settled answer already cached is not asked again.
        if (repo.cached(key) != null && repo.timeline(key, phone).isTerminal) return@LaunchedEffect
        while (true) {
            repo.refresh(key)
            val age = sentAt?.let { System.currentTimeMillis() - it } ?: 0L
            val next = DeliveryStatusRepository.nextPollDelayMillis(repo.timeline(key, phone), age) ?: break
            delay(next)
        }
    }
    return remember(all[key], phone) { repo.timeline(key, phone) }
}
