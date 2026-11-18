package app.sterna.send.delivery

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * #1802: where the server's records come from. The real implementation is the fleet mail API's
 * per-message delivery-status endpoint (bound once its contract is live); tests bind a fake.
 * Returns null when the server has no record of the message at all, and THROWS on a transport or
 * auth failure, so "no record" and "could not ask" never read the same.
 */
fun interface DeliveryStatusSource {
    suspend fun fetch(key: DeliveryKey): ServerDeliveryReport?
}

/** One sent message: the account it went from, and its RFC 5322 Message-ID (the server's key). */
data class DeliveryKey(val accountId: String, val messageId: String)

/** One cached answer: the report and when it was fetched; [error] holds a failed ask's text. */
data class CachedDelivery(
    val report: ServerDeliveryReport?,
    val fetchedAtMillis: Long,
    val error: String? = null,
)

/**
 * Per-message cache plus the polling policy. A failed fetch keeps the last good report (a network
 * blip must not dim marks the server already confirmed) and records the error beside it.
 */
class DeliveryStatusRepository(
    private val source: DeliveryStatusSource,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val cache = MutableStateFlow<Map<DeliveryKey, CachedDelivery>>(emptyMap())
    val all: StateFlow<Map<DeliveryKey, CachedDelivery>> = cache.asStateFlow()

    fun cached(key: DeliveryKey): CachedDelivery? = cache.value[key]

    suspend fun refresh(key: DeliveryKey): CachedDelivery {
        val now = clock()
        val entry = try {
            CachedDelivery(source.fetch(key), now)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            CachedDelivery(cached(key)?.report, now, error = e.message ?: e.javaClass.simpleName)
        }
        cache.update { it + (key to entry) }
        return entry
    }

    fun timeline(key: DeliveryKey, phone: PhoneDelivery): DeliveryTimeline =
        DeliveryStateMachine.timeline(phone, cached(key)?.report)

    companion object {
        /**
         * When to ask again, or null to stop. Terminal timelines stop. Otherwise the interval grows
         * with the message's age: delivery normally settles in seconds, a deferral retries over
         * hours, and the server gives up on a deferral after days (it then bounces: terminal).
         */
        fun nextPollDelayMillis(timeline: DeliveryTimeline, sentAgeMillis: Long): Long? = when {
            timeline.isTerminal -> null
            timeline[DeliveryStage.LEFT_PHONE].mark !is StageMark.Confirmed -> null // the outbox drives stage 1
            sentAgeMillis < 2 * MINUTE -> 10 * SECOND
            sentAgeMillis < HOUR -> MINUTE
            sentAgeMillis < DAY -> 15 * MINUTE
            sentAgeMillis < 5 * DAY -> HOUR
            else -> null
        }

        private const val SECOND = 1_000L
        private const val MINUTE = 60 * SECOND
        private const val HOUR = 60 * MINUTE
        private const val DAY = 24 * HOUR
    }
}
