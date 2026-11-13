package com.diegonmarcos.superapp.configs

import android.content.Context
import com.diegonmarcos.superapp.shellclient.ShellClient
import com.diegonmarcos.superapp.shellclient.ShellWire

/**
 * #1682 P3: the owner-side reads of the broker (audit ring, per-caller rule summaries) as the owner's UI and the X-Ray counters take
 * them over the client; moved out of libs:shell-client so the wire client stays minimal. Only a caller whose allowlist entry declares
 * `"audit": true` gets an answer; a refusal or an unreachable owner is null, never an empty list.
 */
object ShellAudit {
    fun rows(client: ShellClient): List<ShellWire.AuditRow>? =
        runCatching { decodeAudit(client.transport.call(ShellWire.M_AUDIT, "", emptyMap())) }.getOrNull()

    fun rows(ctx: Context): List<ShellWire.AuditRow>? = rows(ShellClient.over(ctx))

    /** (caller, rule summary) of every allowlist entry, or null when refused / unreachable. */
    fun callers(client: ShellClient): List<Pair<String, String>>? =
        runCatching { decodeCallers(client.transport.call(ShellWire.M_CALLERS, "", emptyMap())) }.getOrNull()

    /** null = not an audit answer (refused, outdated owner, unreadable). */
    fun decodeAudit(json: String?): List<ShellWire.AuditRow>? {
        val a = runCatching { org.json.JSONArray(json.orEmpty()) }.getOrNull() ?: return null
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
            ShellWire.AuditRow(o.optLong("ts"), o.optString("caller"), o.optString("cmd"), o.optString("outcome"), o.optInt("exit", -1),
                o.optLong("ms"), o.optString("source")) } }
    }


    fun decodeCallers(json: String?): List<Pair<String, String>>? {
        val a = runCatching { org.json.JSONArray(json.orEmpty()) }.getOrNull() ?: return null
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { it.optString("caller") to it.optString("summary") } }
    }
}
