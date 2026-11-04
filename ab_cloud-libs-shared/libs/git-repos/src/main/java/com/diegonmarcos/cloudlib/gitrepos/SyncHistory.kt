package com.diegonmarcos.cloudlib.gitrepos

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * #579 the sync history log — what GitSync shows as its sync record. App-owned (the
 * library owns the engine, the app owns when it ran and what it said), one JSON file
 * beside the registry, newest first, capped so a year of hourly syncs cannot grow
 * without bound. Pure Kotlin over a File; the JVM suite (SyncHistoryTest) round-trips it.
 */
@Serializable
data class SyncEvent(
    val epochSeconds: Long,
    val repoId: String,
    val repoName: String,
    /** "manual" | "scheduled" */
    val trigger: String,
    val ok: Boolean,
    val summary: String,
    val details: String = "",
) {
    val conflicted: Boolean get() = !ok && summary.contains("conflict", ignoreCase = true)
}

class SyncHistory(private val file: File, private val cap: Int = CAP) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(SyncEvent.serializer())

    /** Newest first. A malformed file reads as empty and is overwritten on the next append. */
    fun load(): List<SyncEvent> {
        if (!file.isFile) return emptyList()
        return runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyList() }.sortedByDescending { it.epochSeconds }
    }

    fun append(event: SyncEvent): List<SyncEvent> {
        val next = (listOf(event) + load()).take(cap)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, next))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        return next
    }

    fun forRepo(repoId: String): List<SyncEvent> = load().filter { it.repoId == repoId }

    fun lastFor(repoId: String): SyncEvent? = load().firstOrNull { it.repoId == repoId }

    companion object {
        const val CAP = 200
        const val FILE = "git-sync/history.json"
        const val TRIGGER_MANUAL = "manual"
        const val TRIGGER_SCHEDULED = "scheduled"
    }
}

/**
 * The per-repository scheduling decision the worker makes on every tick of the base
 * period, as a pure function so SyncScheduleTest can hold it: a repository is due when
 * it opted in, its own period (or the base when it declares none) has elapsed since its
 * last sync, and its network rule holds on the network the device has right now.
 */
object SyncSchedule {
    fun isDue(
        autoSync: Boolean,
        repoIntervalMinutes: Long,
        baseIntervalMinutes: Long,
        lastSyncEpochSeconds: Long,
        nowEpochSeconds: Long,
        requireUnmetered: Boolean,
        networkUnmetered: Boolean?,
    ): Boolean {
        if (!autoSync) return false
        val period = if (repoIntervalMinutes > 0) repoIntervalMinutes else baseIntervalMinutes
        // A minute of slack: the tick itself lands a little late, and "60 minutes and 3 seconds
        // since the last run" must not slip to the next tick.
        val elapsed = nowEpochSeconds - lastSyncEpochSeconds
        if (lastSyncEpochSeconds > 0 && elapsed < (period - 1) * 60) return false
        if (requireUnmetered && networkUnmetered != true) return false
        return true
    }

    /** Minutes until the base period next fires, from when it last fired; null when it has not yet. */
    fun minutesUntilNext(nextScheduleEpochMillis: Long?, nowEpochMillis: Long): Long? =
        nextScheduleEpochMillis?.takeIf { it > 0 }?.let { ((it - nowEpochMillis).coerceAtLeast(0L) + 59_999L) / 60_000L }
}
