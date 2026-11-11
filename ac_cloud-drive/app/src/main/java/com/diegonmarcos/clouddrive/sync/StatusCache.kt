package com.diegonmarcos.clouddrive.sync

import java.io.File
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * The Git table's PERSISTED status cache: one JSON file in the app's files dir, keyed by the canonical repo name
 * ([GitSyncCoordinator.statusKey]), every entry stamped with the time it was read. It survives page changes, minimize,
 * process death and restart: on open the table renders from it at once and NO check runs by itself; statuses are
 * re-read only on an explicit owner action. Only terminal results are stored (a READING entry is never persisted).
 * Pure Kotlin over a File, JVM-tested (StatusCacheTest).
 */
class StatusCache(private val file: File) {
    @Serializable
    private data class Row(
        val state: String, val branch: String? = null, val upstream: String? = null,
        val ahead: Int = 0, val behind: Int = 0, val changed: Int = 0, val conflicts: Int = 0, val untracked: Int = 0,
        val repositoryState: String = "SAFE", val fetchedAtMs: Long = 0, val via: String = "",
        val reason: String? = null, val readAtMs: Long = 0, val tookMs: Long = 0, val level: String = "FULL", val path: String = "",
        val detached: Boolean = false, val stashed: Int = 0,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = MapSerializer(String.serializer(), Row.serializer())

    fun load(): Map<String, GitStatusReader.Status> {
        if (!file.isFile) return emptyMap()
        val rows = runCatching { json.decodeFromString(serializer, file.readText()) }.getOrElse { emptyMap() }
        return rows.mapNotNull { (k, r) ->
            val st = runCatching { GitStatusReader.State.valueOf(r.state) }.getOrNull()?.takeIf { it != GitStatusReader.State.READING } ?: return@mapNotNull null
            val level = runCatching { GitStatusReader.Level.valueOf(r.level) }.getOrDefault(GitStatusReader.Level.FULL)
            val probe = GitStatusReader.Probe(r.branch, r.upstream, r.ahead, r.behind, r.changed, r.conflicts, r.repositoryState, r.untracked, r.fetchedAtMs, r.via, r.detached, r.stashed)
            k to GitStatusReader.Status(st, probe.takeIf { st != GitStatusReader.State.ERROR && st != GitStatusReader.State.NOT_CLONED }, r.reason, r.readAtMs, r.tookMs, level, r.path)
        }.toMap()
    }

    /** Writes every terminal entry that was really read (readAtMs > 0), atomically. */
    fun save(m: Map<String, GitStatusReader.Status>) {
        val rows = m.filter { it.value.terminal && it.value.readAtMs > 0 }.mapValues { (_, s) ->
            val p = s.probe
            Row(s.state.name, p?.branch, p?.upstream, p?.ahead ?: 0, p?.behind ?: 0, p?.changed ?: 0, p?.conflicts ?: 0, p?.untracked ?: 0,
                p?.repositoryState ?: "SAFE", p?.fetchedAtMs ?: 0, p?.via.orEmpty(), s.reason, s.readAtMs, s.tookMs, s.level.name, s.path, p?.detached ?: false, p?.stashed ?: 0)
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, rows))
        if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
    }

    companion object { const val FILE = "git-sync/status-cache.json" }
}
