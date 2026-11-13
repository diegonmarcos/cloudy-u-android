package com.diegonmarcos.superapp.fleetendpoints.android

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.fleetendpoints.SignInContract
import org.json.JSONObject

/**
 * #1693 THE CLIENT HALF of Cloud Account for an app that does not host libs:account (the SuperApp and every other
 * fleet app): everything such an app needs from Cloud Account goes over its `accountdata` provider, and nothing
 * here starts a sign-in or keeps a copy of Cloud Account's stores. Every call BLOCKS (a provider call) and never
 * throws: a missing, refusing or older Cloud Account reads as empty / [SignInContract.State.UNKNOWN] with a reason.
 */
object AccountClient {
    const val PKG = SignInContract.ACCOUNT_PACKAGE
    private const val AUTHORITY = SignInContract.AUTHORITY
    // The provider's method names (libs:account AccountData), as strings: this lib does not link libs:account.
    const val METHOD_SECRET = "secret"
    const val METHOD_EXPORT = "export"
    const val METHOD_MESH_DEVICE = "mesh_device"
    private const val KEY_OK = "ok"
    private const val KEY_VALUE = "value"
    private const val KEY_ERROR = "error"
    private const val KEY_PROFILE = "profile"
    const val KEY_MESH_JSON = "mesh_device_json"

    fun installed(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(PKG, 0).enabled
    }.getOrDefault(false)

    private fun call(ctx: Context, method: String, arg: String?): Bundle? {
        if (ctx.packageName == PKG || !installed(ctx)) return null
        return runCatching { ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), method, arg, null) }.getOrNull()
    }

    /** A connection value Cloud Account granted this package (`fleet.bearer`, …), or "". Use it, never log or keep it. */
    fun secret(ctx: Context, path: String): String {
        val r = call(ctx, METHOD_SECRET, path) ?: return ""
        return if (r.getBoolean(KEY_OK)) r.getString(KEY_VALUE).orEmpty() else ""
    }

    /** The profile Cloud Account holds (name, email, …) as plain strings; empty when absent or not granted. */
    data class Profile(val fields: Map<String, String>) {
        operator fun get(key: String): String = fields[key].orEmpty()
        val name: String get() = get("name")
        val email: String get() = get("email")
        /** Avatar initials, derived from the name exactly as Cloud Account's ProfilePrefs.derivedInitials does. */
        fun initials(): String {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            return when (words.size) {
                0 -> "•"
                1 -> words[0].take(1).uppercase()
                else -> (words.first().take(1) + words.last().take(1)).uppercase()
            }
        }
    }

    fun profile(ctx: Context): Profile {
        val r = call(ctx, METHOD_EXPORT, null) ?: return Profile(emptyMap())
        if (!r.getBoolean(KEY_OK)) return Profile(emptyMap())
        return Profile(parseProfile(r.getString(KEY_PROFILE)))
    }

    /** `{"<key>": {"t": "s", "v": "…"}}` (the export's typed profile) -> the string fields only. */
    fun parseProfile(text: String?): Map<String, String> {
        val o = runCatching { JSONObject(text ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (k in o.keys()) {
            val e = o.optJSONObject(k) ?: continue
            if (e.optString("t") == "s") out[k] = e.optString("v")
        }
        return out
    }

    /** The mesh addresses of the device picked in Cloud Account, or null when none is picked / landed / reachable. */
    fun meshDeviceAddresses(ctx: Context): Set<String>? {
        val r = call(ctx, METHOD_MESH_DEVICE, null) ?: return null
        if (!r.getBoolean(KEY_OK)) return null
        val o = runCatching { JSONObject(r.getString(KEY_MESH_JSON).orEmpty()) }.getOrNull() ?: return null
        val a = o.optJSONArray("addresses") ?: return null
        return (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() }.toSet().ifEmpty { null }
    }

    /** [provider]'s sign-in state as Cloud Account reports it (state only). */
    fun signInStatus(ctx: Context, provider: String): SignInContract.Status {
        if (!installed(ctx)) return SignInContract.missing(provider)
        val r = call(ctx, SignInContract.METHOD, provider)
            ?: return SignInContract.Status(provider, SignInContract.State.UNKNOWN, "Cloud Account did not answer: update it or open it once")
        return SignInContract.parse(provider, r.getBoolean(KEY_OK), r.getString(SignInContract.KEY_JSON), r.getString(KEY_ERROR))
    }

    /**
     * #1693 The short-lived [provider] session Cloud Account holds (cookie + expiry), for the one rung that must present it.
     * BLOCKING, never throws; a missing / refusing / signed-out Cloud Account is a session with no cookie and the reason.
     * The cookie is a credential: present it, never log it or keep it past its expiry ([SignInContract.Session.toString] hides it).
     */
    fun session(ctx: Context, provider: String, now: Long = System.currentTimeMillis()): SignInContract.Session {
        if (!installed(ctx)) return SignInContract.noSession(provider, SignInContract.State.UNKNOWN, SignInContract.missing(provider).line)
        val r = call(ctx, SignInContract.METHOD_SESSION, provider)
            ?: return SignInContract.noSession(provider, SignInContract.State.UNKNOWN, "Cloud Account did not answer: update it or open it once")
        return SignInContract.parseSession(provider, r.getBoolean(KEY_OK), r.getString(SignInContract.KEY_SESSION_JSON), r.getString(KEY_ERROR), now)
    }

    /**
     * #1693 THE SIGN-IN LINK: opens Cloud Account at [provider]'s sign-in page. This starts nothing itself (Cloud Account
     * owns every sign-in); null when it opened, else the reason it could not (never throws).
     */
    fun openSignIn(ctx: Context, provider: String): String? {
        if (!installed(ctx)) return SignInContract.missing(provider).line
        val extras = SignInContract.openIntentExtras(provider)
        fun fill(i: android.content.Intent) = i.also { for ((k, v) in extras) it.putExtra(k, v) }.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { ctx.startActivity(fill(android.content.Intent(SignInContract.ACTION_OPEN).setPackage(PKG))); null }.getOrElse {
            runCatching {
                val launch = ctx.packageManager.getLaunchIntentForPackage(PKG) ?: return "Cloud Account cannot be opened (${it.javaClass.simpleName})"
                ctx.startActivity(fill(launch)); null
            }.getOrElse { e -> "Cloud Account cannot be opened (${e.javaClass.simpleName})" }
        }
    }

    /**
     * #1693 A credential the person typed in Cloud Account (App credentials), read over the provider's per-credential grant.
     * BLOCKS; never throws; the value is in no log and no line: a missing, refusing or unknown credential is a [AppCredentialsContract.Credential]
     * with an empty value and the sentence naming the fix.
     */
    fun credential(ctx: Context, id: String, label: String = id): AppCredentialsContract.Credential {
        if (AppCredentialsContract.entry(id) == null) return AppCredentialsContract.Credential(id, "", "unknown credential id")
        if (!installed(ctx)) return AppCredentialsContract.Credential(id, "", SignInContract.missing(AppCredentialsContract.PROVIDER).line)
        val r = call(ctx, AppCredentialsContract.METHOD, id)
            ?: return AppCredentialsContract.Credential(id, "", "Cloud Account did not answer: update it or open it once")
        if (!r.getBoolean(KEY_OK)) return AppCredentialsContract.Credential(id, "", AppCredentialsContract.refusal(r.getString(KEY_ERROR)))
        val v = r.getString(AppCredentialsContract.KEY_VALUE).orEmpty()
        return AppCredentialsContract.Credential(id, v, if (v.isBlank()) AppCredentialsContract.missing(label) else "from Cloud Account")
    }

    /** Opens Cloud Account at the App credentials page; null when it opened, else the reason. */
    fun openCredentials(ctx: Context): String? = openSignIn(ctx, AppCredentialsContract.PROVIDER)
}
