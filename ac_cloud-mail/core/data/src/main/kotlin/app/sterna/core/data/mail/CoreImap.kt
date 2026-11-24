package app.sterna.core.data.mail

import app.sterna.core.imap.ImapClient
import app.sterna.core.imap.MailSecurity
import app.sterna.core.imap.MailServerConfig
import com.diegonmarcos.cloudlib.mailcore.MailCore
import com.diegonmarcos.cloudlib.mailcore.MailCoreImapIo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.Base64

/**
 * #1837 phase 3: IMAP fetch through the Rust core over the app's own socket (TLS, trust store, timeouts stay here), behind
 * [MailCore.imapEnabled] (default FALSE until the on-phone check). Null from any function = "use the Kotlin ImapSession".
 */
internal object CoreImap {
    /** The core logs in with a password over a plain or TLS socket; STARTTLS, XOAUTH2 and non-ASCII folder names stay on Kotlin. */
    fun eligible(c: MailServerConfig, mailbox: String): Boolean =
        c.security != MailSecurity.STARTTLS && c.accessToken == null && mailbox.all { it.code in 0x20..0x7E }

    /** The socket as the core's byte pipe: a read timeout is an EMPTY array, the server closing the connection is null. */
    class SocketIo(private val socket: Socket) : MailCoreImapIo {
        override fun write(bytes: ByteArray) {
            socket.getOutputStream().apply { write(bytes); flush() }
        }

        override fun read(timeoutMs: Int): ByteArray? {
            socket.soTimeout = timeoutMs.coerceAtLeast(1)
            val buf = ByteArray(16 * 1024)
            return try {
                val n = socket.getInputStream().read(buf)
                if (n < 0) null else buf.copyOf(n)
            } catch (_: SocketTimeoutException) {
                ByteArray(0)
            }
        }
    }

    /** Raw RFC 822 source of [uid], or null (switched off, ineligible, renumbered folder, absent message, any error). */
    fun fetchSource(
        config: MailServerConfig,
        mailbox: String,
        uid: Long,
        open: (MailServerConfig) -> Socket = { ImapClient().openRawSocket(it, 20_000) },
        fetch: (String, MailCoreImapIo) -> Result<String> = MailCore::imapFetch,
    ): String? {
        if (!MailCore.imapEnabled || MailCore.unavailableReason() != null || !eligible(config, mailbox)) return null
        return runCatching {
            open(config).use { socket ->
                val args = buildJsonObject {
                    put("user", config.username); put("password", config.password); put("mailbox", mailbox)
                    put("uids", JsonArray(listOf(JsonPrimitive(uid)))); put("timeoutMs", 30_000)
                }.toString()
                val report = Json.parseToJsonElement(fetch(args, SocketIo(socket)).getOrThrow()).jsonObject
                val m = report["messages"]!!.jsonArray.map { it.jsonObject }.firstOrNull { it["uid"]!!.jsonPrimitive.content == uid.toString() }
                    ?: return@use null
                String(Base64.getDecoder().decode(m["raw"]!!.jsonPrimitive.content), Charsets.ISO_8859_1)
            }
        }.onFailure { RustMailEngine.log("imap $mailbox/$uid: core failed, Kotlin session: ${it.message}") }.getOrNull()
    }
}
