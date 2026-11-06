package com.diegonmarcos.superapp.ai

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** Where chat sessions live. The token is never part of a session. */
interface AiSessionStore {
    /** Every session, newest first. */
    fun all(): List<AiSession>
    fun put(s: AiSession)
    fun delete(id: String)
    /** Gives a stored session a new title (the sessions drawer's Rename); the order and the messages stay. */
    fun rename(id: String, title: String) { all().firstOrNull { it.id == id }?.let { put(it.copy(title = title)) } }
}

/** Sessions for the life of the process (Cloud Browser: the conversation outlives closing the page, not the app). */
class AiMemorySessions : AiSessionStore {
    private val map = LinkedHashMap<String, AiSession>()
    @Synchronized override fun all(): List<AiSession> = map.values.sortedByDescending { it.updated }
    @Synchronized override fun put(s: AiSession) { map[s.id] = s }
    @Synchronized override fun delete(id: String) { map.remove(id) }
}

/** Sessions in one JSON file (under filesDir). */
class AiFileSessions(private val file: File) : AiSessionStore {
    @Synchronized override fun all(): List<AiSession> = read().sortedByDescending { it.updated }

    @Synchronized override fun put(s: AiSession) = write(listOf(s) + read().filterNot { it.id == s.id })

    @Synchronized override fun delete(id: String) = write(read().filterNot { it.id == id })

    private fun read(): List<AiSession> = runCatching {
        val a = JSONArray(file.readText())
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun write(list: List<AiSession>) {
        file.parentFile?.mkdirs()
        file.writeText(JSONArray(list.map { toJson(it) }).toString())
    }

    companion object {
        fun toJson(s: AiSession): JSONObject = JSONObject().put("id", s.id).put("title", s.title).put("model", s.model).put("updated", s.updated)
            .put("messages", JSONArray(s.messages.map { JSONObject().put("role", it.role).put("content", it.content).also { o -> if (it.meta.isNotBlank()) o.put("meta", it.meta); if (it.prompt > 0) o.put("prompt", it.prompt); if (it.completion > 0) o.put("completion", it.completion); if (it.attachments.isNotEmpty()) o.put("attachments", JSONArray(it.attachments.map { a -> a.toJson() })); if (it.at > 0) o.put("at", it.at) } }))

        fun fromJson(o: JSONObject): AiSession = AiSession(
            id = o.getString("id"), title = o.optString("title"), model = o.optString("model"), updated = o.optLong("updated"),
            messages = o.optJSONArray("messages")?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { AiMsg(it.getString("role"), it.getString("content"), it.optString("meta"), it.optLong("prompt"), it.optLong("completion"), AiAttachment.list(it.optJSONArray("attachments")), it.optLong("at")) }
            } ?: emptyList(),
        )
    }
}

/** The sessions list's buckets. */
enum class AiBucket { TODAY, PREVIOUS_7_DAYS, OLDER }

object AiSessions {
    fun bucket(updated: Long, now: Long, zone: ZoneId): AiBucket {
        val day = Instant.ofEpochMilli(updated).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when {
            !day.isBefore(today) -> AiBucket.TODAY
            !day.isBefore(today.minusDays(7)) -> AiBucket.PREVIOUS_7_DAYS
            else -> AiBucket.OLDER
        }
    }

    /** Sessions grouped Today / Previous 7 Days / Older, each newest first; empty groups dropped. */
    fun group(sessions: List<AiSession>, now: Long, zone: ZoneId): List<Pair<AiBucket, List<AiSession>>> =
        sessions.sortedByDescending { it.updated }.groupBy { bucket(it.updated, now, zone) }
            .toSortedMap().map { (k, v) -> k to v }
}
