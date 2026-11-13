package com.diegonmarcos.superapp.scraper

import android.content.Context

/**
 * #1693 THE HOST HOOKS for the music services that sign in with a device grant (YouTube Music): the grant runs in Cloud Account only
 * (App credentials > Connect), which files the client and the tokens; the host (Cloud Browser) wires [read] and [open] to its
 * AccountClient, and the Music page takes them over the provider's per-credential grant ([take]). This lib links nothing of the fleet's
 * IPC. A read BLOCKS (a provider call): call it off the main thread. No value is logged; an unwired host answers with the sentence
 * naming where to connect, never a crash.
 */
object MusicCredentials {
    data class Cred(val value: String, val line: String) { val present: Boolean get() = value.isNotBlank()
        override fun toString() = "Cred(value=${if (present) "<redacted>" else "none"}, line=$line)" }

    const val UNWIRED = "Connect it in Cloud Account > App credentials, then take it here"

    @Volatile var read: (Context, String) -> Cred = { _, _ -> Cred("", UNWIRED) }
    /** Opens Cloud Account at App credentials; null when it opened, else the reason. */
    @Volatile var open: (Context) -> String? = { UNWIRED }

    fun id(service: String, field: String) = "music.$service.$field"

    sealed class Taken {
        data class Ok(val client: String, val secret: String, val tokens: TokenSet) : Taken()
        data class Missing(val line: String) : Taken()
    }

    /** The pure rule over what was read: the access token is required (the client id too, to renew it); else the first sentence naming the fix. */
    fun assemble(client: Cred, secret: Cred, access: Cred, refresh: Cred, expiresAtMs: Cred, nowMs: Long): Taken {
        if (!access.present) return Taken.Missing(access.line.ifBlank { UNWIRED })
        val exp = expiresAtMs.value.trim().toLongOrNull() ?: 0L
        val left = if (exp == 0L) 0L else ((exp - nowMs) / 1000).coerceAtLeast(1L)
        return Taken.Ok(client.value.trim(), secret.value.trim(), TokenSet(access.value.trim(), refresh.value.trim().ifBlank { null }, left))
    }

    /** Reads the five credentials of [service] (BLOCKS). */
    fun take(ctx: Context, service: String, nowMs: Long = System.currentTimeMillis()): Taken = runCatching {
        fun r(f: String) = read(ctx, id(service, f))
        assemble(r("client_id"), r("client_secret"), r("access"), r("refresh"), r("expires"), nowMs)
    }.getOrElse { Taken.Missing("Cloud Account read failed") }
}
