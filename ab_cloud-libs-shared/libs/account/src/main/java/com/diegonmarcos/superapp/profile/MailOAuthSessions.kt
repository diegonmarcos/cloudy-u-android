package com.diegonmarcos.superapp.profile

import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * #1693 THE MAIL OAUTH DEVICE GRANT, run by Cloud Account for Cloud Mail (it left Mail's OAuthClient). [start] asks the provider for a code
 * (endpoints already checked by [MailOAuthContract.parse]) and keeps the device code in memory under a random handle; [poll] makes ONE token
 * request per call. Pure over an injected [MusicDeviceGrant.Post] so each step is JVM-tested. No device code or token reaches a log or an error;
 * a provider's answer body never does either, only its protocol `error` word, its description and an AADSTS code.
 */
object MailOAuthSessions {
    private class Session(val spec: MusicDeviceGrant.Spec, val clientId: String, val deviceCode: String, val deadlineMs: Long)
    private val sessions = ConcurrentHashMap<String, Session>()
    private const val MAX = 16
    @Volatile var post: MusicDeviceGrant.Post = MusicDeviceGrant.defaultPost()
    @Volatile var nowMs: () -> Long = { System.currentTimeMillis() }

    private fun ok(json: String) = Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, true); putString(MailOAuthContract.KEY_JSON, json) }
    private fun refuse(why: String) = Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, false); putString(MailOAuthContract.KEY_ERROR, why) }

    fun start(arg: String?): Bundle {
        val r = MailOAuthContract.parse(arg) ?: return refuse("the request was refused: check its endpoints and address")
        return startUnchecked(r)
    }

    /** The grant for a request whose endpoints were already checked ([MailOAuthContract.parse]); also the seam the transport tests use (a local test server is not https). */
    fun startUnchecked(r: MailOAuthContract.Request): Bundle {
        val spec = MusicDeviceGrant.Spec("mail", r.email, r.deviceEndpoint, r.tokenEndpoint, r.scope)
        return try {
            val raw = post.form(spec.deviceUrl, mapOf("client_id" to r.clientId, "scope" to r.scope))
            val auth = runCatching { JSONObject(raw) }.getOrNull()
            val code = auth?.let { runCatching { MusicDeviceGrant.parseCode(raw) }.getOrNull() } ?: return refuse("the provider answered something that is not a device authorization")
            if (sessions.size >= MAX) sessions.entries.removeIf { it.value.deadlineMs < nowMs() }
            if (sessions.size >= MAX) return refuse("too many sign-ins are waiting")
            val handle = ByteArray(18).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
            sessions[handle] = Session(spec, r.clientId, code.deviceCode, nowMs() + code.expiresInSec * 1000)
            ok(MailOAuthContract.StartAnswer(handle, code.userCode, code.url, auth.optString("verification_uri_complete").ifBlank { null }, code.intervalSec.toInt(), code.expiresInSec.toInt()).json())
        } catch (e: MusicDeviceGrant.Http) {
            refuse("the provider refused: ${errorWord(e.body).ifBlank { "http_${e.status}" }}")
        } catch (_: IOException) { refuse("the provider could not be reached") }
    }

    fun poll(handle: String?): Bundle {
        val s = sessions[handle.orEmpty()] ?: return ok(failed("expired_token").json())
        if (nowMs() > s.deadlineMs) { sessions.remove(handle); return ok(failed("expired_token").json()) }
        val a = try {
            val raw = post.form(s.spec.tokenUrl, mapOf("client_id" to s.clientId, "device_code" to s.deviceCode, "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
            val o = runCatching { JSONObject(raw) }.getOrNull()
            if (o == null || o.optString("access_token").isBlank()) failed("malformed_response") else {
                sessions.remove(handle)
                MailOAuthContract.PollAnswer("success", o.optString("access_token"), o.optString("refresh_token").ifBlank { null }, o.optLong("expires_in", 3600),
                    o.optString("id_token").ifBlank { null }, o.optString("scope").ifBlank { null })
            }
        } catch (e: MusicDeviceGrant.Http) {
            val err = errorWord(e.body)
            when (err) {
                "authorization_pending" -> MailOAuthContract.PollAnswer("pending")
                "slow_down" -> MailOAuthContract.PollAnswer("slow_down")
                else -> { sessions.remove(handle); MailOAuthContract.PollAnswer("failed", error = err.ifBlank { "http_${e.status}" }, description = descriptionOf(e.body), aadsts = Regex("AADSTS\\d+").find(descriptionOf(e.body))?.value) }
            }
        } catch (_: IOException) { MailOAuthContract.PollAnswer("failed", error = "network_error") }
        return ok(a.json())
    }

    private fun failed(error: String) = MailOAuthContract.PollAnswer("failed", error = error)
    private fun errorWord(body: String) = runCatching { JSONObject(body).optString("error") }.getOrDefault("")
    private fun descriptionOf(body: String) = runCatching { JSONObject(body).optString("error_description") }.getOrDefault("")

    /** Test hook: forget every waiting session. */
    fun clear() = sessions.clear()
}
