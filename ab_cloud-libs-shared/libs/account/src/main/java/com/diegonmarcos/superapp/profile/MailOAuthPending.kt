package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract
import com.diegonmarcos.superapp.settings.AccountVault
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * #1693 Cloud Mail's OAuth sign-ins, run here. Mail files a [MailOAuthContract.Request] over the provider (grant-gated, endpoints checked);
 * it waits in the vault until the person approves it in App credentials > Mail sign-ins, where [run] drives the device grant and files the
 * tokens under `mail.oauth.<address>.*` for Mail to take. The request carries no secret; no token is logged or put in an error.
 */
object MailOAuthPending {
    private const val PATH = "mailoauth.pending"
    private const val MAX = 8

    fun list(vault: AccountVault): List<MailOAuthContract.Request> {
        val o = (vault.connection(PATH) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return emptyList()
        return o.keys().asSequence().mapNotNull { MailOAuthContract.parse(o.optString(it)) }.sortedBy { it.email }.toList()
    }

    /** Files a request (replacing one for the same address); false when it is refused: bad shape, a disallowed endpoint, too many pending. */
    fun put(vault: AccountVault, json: String?): Boolean {
        val r = MailOAuthContract.parse(json) ?: return false
        val key = r.email.lowercase()
        val o = (vault.connection(PATH) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (!o.has(key) && o.length() >= MAX) return false
        o.put(key, r.json())
        vault.putConnection(PATH, o.toString())
        return true
    }

    fun remove(vault: AccountVault, email: String) {
        val o = (vault.connection(PATH) as? String)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return
        o.remove(email.lowercase()); vault.putConnection(PATH, o.toString())
    }

    /** The provider's answer to `mail_oauth_request` for [caller]: the grant `mail.oauth` is required (default deny). */
    fun accept(ctx: Context, caller: String, arg: String?, vault: AccountVault = AccountVault(ctx)): Bundle {
        fun refuse(why: String) = Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, false); putString(MailOAuthContract.KEY_ERROR, why) }
        if (caller != ctx.packageName && !vault.grants.allow(caller, MailOAuthContract.GRANT_REQUEST)) return refuse("no grant for $caller")
        return if (put(vault, arg)) Bundle().apply { putBoolean(MailOAuthContract.KEY_OK, true) } else refuse("the request was refused: check its endpoints and address")
    }

    /** The device grant of one pending request, owner only; files the tokens and drops the request. */
    suspend fun run(ctx: Context, r: MailOAuthContract.Request, post: MusicDeviceGrant.Post? = null, nowMs: () -> Long = { System.currentTimeMillis() },
                    onCode: (MusicDeviceGrant.Code) -> Unit): MusicDeviceGrant.Outcome {
        if (!SignInOwner.isOwner(ctx)) return MusicDeviceGrant.Outcome.Failed("the mail sign-in runs in Cloud Account only")
        val vault = AccountVault(ctx)
        val spec = MusicDeviceGrant.Spec("mail", r.email, r.deviceEndpoint, r.tokenEndpoint, r.scope)
        return try {
            val p = post ?: MusicDeviceGrant.defaultPost()
            val code = withContext(Dispatchers.IO) { MusicDeviceGrant.start(p, spec, r.clientId) }
            onCode(code)
            val deadline = nowMs() + code.expiresInSec * 1000
            var every = code.intervalSec * 1000
            while (nowMs() < deadline) {
                delay(every)
                val t = withContext(Dispatchers.IO) { MusicDeviceGrant.poll(p, spec, r.clientId, "", code.deviceCode) }
                if (t != null) {
                    MusicDeviceGrant.fileUnder(vault, "mail.oauth." + com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract.slug(r.email.trim().lowercase()), t, nowMs())
                    remove(vault, r.email)
                    return MusicDeviceGrant.Outcome.Connected(t)
                }
            }
            MusicDeviceGrant.Outcome.Failed("the code expired: start again")
        } catch (e: MusicDeviceGrant.Http) {
            MusicDeviceGrant.Outcome.Failed("${r.email} refused (HTTP ${e.status})")
        } catch (e: Exception) {
            MusicDeviceGrant.Outcome.Failed("${r.email}: ${e.javaClass.simpleName}")
        }
    }
}
