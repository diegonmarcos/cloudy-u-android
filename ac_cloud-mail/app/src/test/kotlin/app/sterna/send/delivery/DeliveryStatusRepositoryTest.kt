package app.sterna.send.delivery

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1802: the per-message cache and the polling policy. */
class DeliveryStatusRepositoryTest {

    private val key = DeliveryKey("acct", "<m1@fleet>")
    private val other = DeliveryKey("acct", "<m2@fleet>")
    private val left = PhoneDelivery(queuedInOutbox = false, handedOffAtMillis = 1)
    private val report = ServerDeliveryReport(ServerDeliveryReport.Submission(accepted = true, atMillis = 2))

    @Test fun `a fetched report is cached per message and drives the timeline`() = runTest {
        val repo = DeliveryStatusRepository({ k -> if (k == key) report else null }, clock = { 42 })
        repo.refresh(key)
        repo.refresh(other)
        assertEquals(CachedDelivery(report, 42), repo.cached(key))
        assertEquals(CachedDelivery(null, 42), repo.cached(other))
        assertEquals(StageMark.Confirmed(2), repo.timeline(key, left)[DeliveryStage.SERVER_RECEIVED].mark)
        assertEquals(StageMark.Pending, repo.timeline(other, left)[DeliveryStage.SERVER_RECEIVED].mark)
    }

    @Test fun `a failed fetch keeps the last good report and records the error`() = runTest {
        var fail = false
        val repo = DeliveryStatusRepository({ if (fail) error("offline") else report }, clock = { 7 })
        repo.refresh(key)
        fail = true
        val entry = repo.refresh(key)
        assertEquals(report, entry.report)
        assertEquals("offline", entry.error)
        assertEquals(StageMark.Confirmed(2), repo.timeline(key, left)[DeliveryStage.SERVER_RECEIVED].mark)
    }

    @Test fun `an uncached message is all pending past stage 1, never guessed`() {
        val repo = DeliveryStatusRepository({ null })
        assertNull(repo.cached(key))
        assertEquals(StageMark.Pending, repo.timeline(key, left)[DeliveryStage.SERVER_RECEIVED].mark)
    }

    @Test fun `polling backs off with age and stops when terminal, queued, or stale`() {
        val open = DeliveryStateMachine.timeline(left, null)
        val minute = 60_000L
        val hour = 60 * minute
        assertEquals(10_000L, DeliveryStatusRepository.nextPollDelayMillis(open, 0))
        assertEquals(minute, DeliveryStatusRepository.nextPollDelayMillis(open, 5 * minute))
        assertEquals(15 * minute, DeliveryStatusRepository.nextPollDelayMillis(open, 2 * hour))
        assertEquals(hour, DeliveryStatusRepository.nextPollDelayMillis(open, 48 * hour))
        assertNull(DeliveryStatusRepository.nextPollDelayMillis(open, 6 * 24 * hour))

        val queued = DeliveryStateMachine.timeline(PhoneDelivery(queuedInOutbox = true), null)
        assertNull(DeliveryStatusRepository.nextPollDelayMillis(queued, 0))

        val failed = DeliveryStateMachine.timeline(left, ServerDeliveryReport(ServerDeliveryReport.Submission(accepted = false)))
        assertNull(DeliveryStatusRepository.nextPollDelayMillis(failed, 0))
    }
}
