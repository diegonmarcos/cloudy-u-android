package app.sterna.ui.message

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * #1869 the open path never waits on the network: [job]'s value when it has already landed, else null AT ONCE. Not a
 * suspend function on purpose, so no caller can turn it into a wait. A fetch that failed or was cancelled is null too.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun <T> completedOrNull(job: Deferred<T>): T? =
    if (job.isCompleted && !job.isCancelled) runCatching { job.getCompleted() }.getOrNull() else null
