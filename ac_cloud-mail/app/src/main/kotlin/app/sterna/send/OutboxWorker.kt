package app.sterna.send

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.sterna.container
import app.sterna.core.data.db.OutboxLogic
import app.sterna.core.data.db.OutboxState
import app.sterna.core.data.mail.accountDepartureIsProven

/**
 * Delivers one persistent outbox item. WorkManager persists and fires this and gates it on
 */
class OutboxWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val id = inputData.getLong(KEY_ID, -1L)
        if (id < 0) return Result.success()
        val container = (applicationContext as android.app.Application).container
        val repo = container.mailRepository
        val item = repo.outboxItem(id) ?: return Result.success() // sent, cancelled or deleted

        // A delivery this row was already in the middle of, whose run never came back: PARK it, do
        // not start it again. Written back with its own attemptCount and lastError — nothing was
        // attempted here. This runs on the row AS READ, ahead of every other question: below
        // isReadyToSend it is dead code, below the delivery it fires on a message just sent twice.
        OutboxLogic.parkOnPickup(item.state)?.let { parked ->
            repo.updateOutboxState(id, parked, item.attemptCount, item.lastError)
            return Result.success()
        }

        // Still inside the undo/scheduled window: try again once it has elapsed.
        if (!OutboxLogic.isReadyToSend(item.state, item.notBeforeMillis, System.currentTimeMillis())) {
            if (item.state == OutboxState.HELD) {
                Outbox.enqueue(applicationContext, id, item.notBeforeMillis - System.currentTimeMillis())
            }
            return Result.success()
        }
        if (item.state == OutboxState.FAILED) return Result.success() // no auto-retry once parked

        val credentials = container.accountStore.credentials(item.accountId) ?: run {
            // A `null` here is NOT proof that the account left, and once queued this row is the
            // ONLY copy of the message: `credentials` also answers null for a secret that will not
            // decode, and an unreadable blob makes `accounts()` empty. So the departure is proved
            // first, in the one place a JVM test can execute the rule.
            val departed = accountDepartureIsProven(
                accountsUnreadable = container.accountStore.accountsUnreadable(),
                accountStillListed = container.accountStore.accounts().any { it.id == item.accountId },
            )
            if (departed) {
                // Nothing can ever deliver this message again: the row goes, as it always has.
                repo.deleteOutbox(id)
                return Result.success()
            }
            // Unproven ⇒ nothing is destroyed, and NOTHING is written on the row: no state, no
            // attemptCount, no lastError. Nothing was attempted here, so a bump would spend a retry
            return Result.retry()
        }

        repo.updateOutboxState(id, OutboxState.SENDING, item.attemptCount, item.lastError)
        return try {
            repo.performSend(credentials, item)
            repo.deleteOutbox(id)
            Result.success()
        } catch (t: Throwable) {
            val attempts = OutboxLogic.attemptsAfterFailure(item.attemptCount, t)
            val error = t.message ?: t.javaClass.simpleName
            // The failure itself decides, not the count alone: a delivery the SERVER refused is
            // parked at once (#183), the message being already in Sent and each auto-retry adding a
            // copy there. `stateAfterFailure` is where that rule is executed by a JVM test.
            val next = OutboxLogic.stateAfterFailure(attempts, t)
            repo.updateOutboxState(id, next, attempts, error)
            if (next == OutboxState.QUEUED) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "outbox_id"
    }
}
