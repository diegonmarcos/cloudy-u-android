package app.sterna.core.data.mail

import app.sterna.core.imap.BlindCopies
import app.sterna.core.imap.OutgoingMessage
import app.sterna.core.imap.OutgoingMime
import com.diegonmarcos.cloudlib.mailcore.MailCore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/**
 * #1837 phase 3: the outgoing message built by the Rust core (mail-core-rs compose.rs) behind [MailCore.enabled], with the Kotlin
 * [OutgoingMime] as the fallback for any message the core does not express yet and for ANY core error. The Kotlin builder stays
 * the source of truth until the on-phone parity check (phase 4).
 */
internal object CoreCompose {
    /** The core builds plain, alternative and file-attachment messages; everything else stays on the Kotlin builder. */
    fun eligible(m: OutgoingMessage): Boolean =
        m.prebuiltEntity == null && !m.requestReceipt && m.autocryptHeader == null && m.attachments.none { it.inline || it.cid != null }

    fun request(m: OutgoingMessage, blind: BlindCopies): String = buildJsonObject {
        fun list(v: List<String>) = JsonArray(v.map { JsonPrimitive(it) })
        put("op", "compose")
        put("from", m.from)
        put("to", list(m.to)); put("cc", list(m.cc)); put("bcc", list(m.bcc))
        put("writeBcc", blind == BlindCopies.WRITTEN)
        put("subject", m.subject)
        put("text", m.body)
        m.html?.let { put("html", it) }
        put("messageId", m.messageId)
        m.inReplyTo?.let { put("inReplyTo", it) }
        m.references?.let { put("references", it) }
        put("date", ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(m.dateMillis), ZoneOffset.UTC).format(DateTimeFormatter.RFC_1123_DATE_TIME))
        put("attachments", JsonArray(m.attachments.map {
            buildJsonObject {
                put("name", it.name); put("type", it.type)
                put("data", Base64.getEncoder().encodeToString(it.bytes))
            }
        }))
    }.toString()

    /** The message from the core, or null (switched off, not loaded, not expressible, or any error): the caller builds it in Kotlin. */
    fun tryBuild(m: OutgoingMessage, blind: BlindCopies): String? {
        if (!MailCore.composeEnabled || MailCore.unavailableReason() != null || !eligible(m)) return null
        return runCatching {
            val answer = MailCore.query(":memory:", request(m, blind)).getOrThrow()
            Json.parse(answer)
        }.onFailure { RustMailEngine.log("compose: core failed, Kotlin builder: ${it.message}") }.getOrNull()
    }

    /** Routes every [OutgoingMime.build] through the core (Kotlin fallback is inside it). Idempotent; called when the app's mail engine is created. */
    fun install() {
        OutgoingMime.coreBuilder = { m, blind -> tryBuild(m, blind) }
    }

    /** Core first, Kotlin on any failure. */
    fun build(m: OutgoingMessage, blind: BlindCopies = BlindCopies.OMITTED): String = tryBuild(m, blind) ?: OutgoingMime.buildInKotlin(m, blind)

    private object Json {
        fun parse(answer: String): String =
            kotlinx.serialization.json.Json.parseToJsonElement(answer).jsonObject["mime"]!!.jsonPrimitive.content
    }
}
