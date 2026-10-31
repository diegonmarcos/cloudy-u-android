package com.diegonmarcos.superapp.updater

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import com.diegonmarcos.superapp.core.DataSyncBudget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Cooperative stop for the Store workers. The downloads and installs under [body] are BLOCKING
 * code: cancelling the worker's coroutine does not reach them, so a stop (the dataSync timeout
 * WorkManager >= 2.10 turns into a stop, a constraint lost, a cancel) used to run on until the
 * pass finished. Here a stop raises [UpdateProgress.requestCancel], the flag every download and
 * chain phase already polls; the chain is persisted, so the next run resumes where this one stopped.
 */
suspend fun <T> CoroutineWorker.stopCooperatively(body: suspend CoroutineScope.() -> T): T = coroutineScope {
    val watch = launch {
        try { awaitCancellation() } finally { if (isStopped) UpdateProgress.requestCancel() }
    }
    try { body() } finally { watch.cancel() }
}

/**
 * Enter the dataSync foreground for this run ONLY when [userInitiated] and the app's dataSync budget
 * allows ([DataSyncBudget]). Returns the budget token to hand to [DataSyncBudget.end] when the run
 * ends (0 = not held: the run carries on as plain background work).
 */
suspend fun CoroutineWorker.holdDataSync(userInitiated: Boolean, info: (Context) -> ForegroundInfo): Long {
    val ctx = applicationContext
    if (!DataSyncBudget.mayHold(ctx, userInitiated)) return 0L
    return runCatching { setForeground(info(ctx)); DataSyncBudget.begin(ctx) }.getOrDefault(0L)
}
