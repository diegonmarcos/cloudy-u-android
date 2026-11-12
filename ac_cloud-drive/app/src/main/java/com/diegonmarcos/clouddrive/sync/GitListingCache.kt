package com.diegonmarcos.clouddrive.sync

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * #1679 THE REPO LIST IS CACHED. Each listing slot (the GitHub way, the gh way, the Cloud git way, the Cloud public list) is
 * persisted with the time it was read, and the Git page draws it back INSTANTLY with no network on every open and tab change.
 * A slot is written only by an explicit refresh (pull-to-refresh, Refresh ▸ Refresh repo list): there is no automatic re-read.
 * There is no built-in default list: an empty cache is an empty page with a Refresh button.
 * A corrupt or missing file reads as empty, never a crash.
 */
class GitListingCache(private val file: File) {
    data class Slot(val repos: List<GitHubRepos.Repo>, val atMs: Long, val complete: Boolean = true, val viaGh: Boolean = false, val rungId: String = "")

    fun load(): Map<String, Slot> = runCatching {
        if (!file.isFile) return emptyMap()
        decode(file.readText())
    }.getOrDefault(emptyMap())

    fun get(slot: String): Slot? = load()[slot]

    fun put(slot: String, value: Slot) {
        runCatching {
            val next = load() + (slot to value)
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(encode(next))
            if (!tmp.renameTo(file)) { file.writeText(tmp.readText()); tmp.delete() }
        }
    }

    companion object {
        const val FILE = "git-listing-cache.json"
        const val SLOT_OWN = "own"
        const val SLOT_FLEET = "fleet"
        const val SLOT_CLOUD_PUBLIC = "cloud_public"

        /** The newest read time among [slots] (0 = never read). */
        fun newest(slots: Collection<Slot?>): Long = slots.filterNotNull().maxOfOrNull { it.atMs } ?: 0L

        private fun repoJson(r: GitHubRepos.Repo) = JsonObject(mapOf(
            "name" to JsonPrimitive(r.name), "owner" to JsonPrimitive(r.owner), "private" to JsonPrimitive(r.private), "fork" to JsonPrimitive(r.fork),
            "defaultBranch" to JsonPrimitive(r.defaultBranch), "description" to JsonPrimitive(r.description), "sizeKb" to JsonPrimitive(r.sizeKb),
            "updatedAt" to JsonPrimitive(r.updatedAt), "pushedAt" to JsonPrimitive(r.pushedAt), "cloneUrl" to JsonPrimitive(r.cloneUrl),
            "sshUrl" to JsonPrimitive(r.sshUrl), "webUrl" to JsonPrimitive(r.webUrl), "stars" to JsonPrimitive(r.stars), "language" to JsonPrimitive(r.language),
        ))

        fun encode(slots: Map<String, Slot>): String = JsonObject(mapOf("schema" to JsonPrimitive("git-listing-cache/v1"), "slots" to JsonObject(slots.mapValues { (_, v) ->
            JsonObject(mapOf("atMs" to JsonPrimitive(v.atMs), "complete" to JsonPrimitive(v.complete), "viaGh" to JsonPrimitive(v.viaGh),
                "rungId" to JsonPrimitive(v.rungId), "repos" to JsonArray(v.repos.map { repoJson(it) })))
        }))).toString()

        fun decode(text: String): Map<String, Slot> = runCatching {
            val slots = Json.parseToJsonElement(text).jsonObject["slots"]?.jsonObject ?: return emptyMap()
            slots.mapNotNull { (k, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                fun JsonObject.s(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
                fun JsonObject.b(key: String, d: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: d
                fun JsonObject.l(key: String) = (this[key] as? JsonPrimitive)?.longOrNull ?: 0L
                val repos = runCatching { o["repos"]?.jsonArray.orEmpty() }.getOrDefault(emptyList()).mapNotNull { e ->
                    val r = e as? JsonObject ?: return@mapNotNull null
                    val name = r.s("name").ifBlank { return@mapNotNull null }
                    GitHubRepos.Repo(name, r.s("owner"), r.b("private"), r.b("fork"), r.s("defaultBranch"), r.s("description"), r.l("sizeKb"),
                        r.s("updatedAt"), r.s("pushedAt"), r.s("cloneUrl"), r.s("sshUrl"), r.s("webUrl"), r.l("stars"), r.s("language"))
                }
                k to Slot(repos, o.l("atMs"), o.b("complete", true), o.b("viaGh"), o.s("rungId"))
            }.toMap()
        }.getOrDefault(emptyMap())
    }
}
