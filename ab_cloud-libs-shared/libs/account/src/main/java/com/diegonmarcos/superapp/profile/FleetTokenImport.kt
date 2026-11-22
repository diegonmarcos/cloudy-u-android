package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONObject

/**
 * #1835 THE vault-to-Cloud-Account leg of the fleet mesh token, so nothing is pasted. The decrypted vault export (VaultConnect, landed by
 * [AccountModel.landBundle]) carries the token at [SECTION].[KEY]; it is filed into the App credential `fleet.mesh_token`
 * ([AppCredentialsStore]), which Cloud C3 Webserver, its owner, takes into its own Keystore store when it is in front (FleetTokens.deliver).
 * Cloud Account is a relay and a cache here, never a second source: the vault's value always wins over the cached copy. A value is never
 * logged, never put in an [Outcome] or an error; the bundle itself is only read.
 *
 * Pure over two seams ([held], [file]) so the JVM suite drives it with a fake store. Declared in ac_cloud-c3-webserver/build.json::fleet_token.delivery.vault_import.
 */
object FleetTokenImport {
    const val CREDENTIAL = "fleet.mesh_token"
    const val SECTION = "fleet"
    const val KEY = "mesh_token"

    enum class Outcome { NOT_DECLARED, PENDING, INVALID, UNCHANGED, FILED, REPLACED, REFUSED }

    /** Same shape the webserver accepts for a bearer token (FleetTokenLogic.cleanPaste). */
    private val SHAPE = Regex("^[A-Za-z0-9._~+/=-]{16,512}$")

    /** What the vault declares: the trimmed string, or null when it is absent, pending (`{"pending": true}`) or not a string. */
    fun declared(bundle: JSONObject): String? =
        (bundle.optJSONObject(SECTION)?.opt(KEY) as? String)?.trim()?.takeIf { it.isNotEmpty() }

    fun import(bundle: JSONObject, held: () -> String, file: (String) -> Boolean): Outcome {
        val node = bundle.optJSONObject(SECTION)?.opt(KEY)
        val v = declared(bundle)
        if (v == null) return if (node is JSONObject && node.optBoolean("pending", false)) Outcome.PENDING else Outcome.NOT_DECLARED
        if (!SHAPE.matches(v)) return Outcome.INVALID
        val cur = held()
        if (cur == v) return Outcome.UNCHANGED
        return if (!file(v)) Outcome.REFUSED else if (cur.isBlank()) Outcome.FILED else Outcome.REPLACED
    }

    /** The landing hook: files the declared token into Cloud Account's own vault. Never throws into the landing. */
    fun land(ctx: Context, bundle: JSONObject): Outcome = runCatching {
        val vault = AccountVault(ctx)
        import(bundle, { AppCredentialsStore.get(vault, CREDENTIAL) }, { AppCredentialsStore.put(vault, CREDENTIAL, it) })
    }.getOrDefault(Outcome.REFUSED)

    init { check(AppCredentialsContract.entry(CREDENTIAL) != null) }
}
