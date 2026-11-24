package com.diegonmarcos.superapp.profile

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import org.json.JSONObject

/**
 * #1807 the vault-to-Cloud-Account delivery of app credentials. The vault repo (Shared/configs/delivery/manifest.json) seals the values it
 * already holds in an encrypted bundle whose plaintext carries `delivery.appcred.<credential id>`; [AccountVault.importBundle] hands that
 * section here. Only catalogue ids are filed (through [AppCredentialsStore]); nothing is created or rotated. A value already held is KEPT
 * (a manual paste is the override), never replaced. A value is never logged and never put in an [Outcome].
 * First entry: `mesh.relay_key` (#1798). Pure over two seams so the JVM suite drives it with a fake store.
 */
object DeliveryImport {
    const val SECTION = "delivery"
    const val APPCRED = "appcred"

    enum class Outcome { FILED, KEPT_MANUAL, UNKNOWN_ID, INVALID, REFUSED }

    private val SHAPE = Regex("^\\S{8,512}$")

    fun import(delivery: JSONObject?, held: (String) -> String, file: (String, String) -> Boolean): Map<String, Outcome> {
        val ac = delivery?.optJSONObject(APPCRED) ?: return emptyMap()
        val out = LinkedHashMap<String, Outcome>()
        for (id in ac.keys().asSequence().toList()) {
            val v = (ac.opt(id) as? String)?.trim().orEmpty()
            out[id] = when {
                AppCredentialsContract.entry(id) == null -> Outcome.UNKNOWN_ID
                !SHAPE.matches(v) -> Outcome.INVALID
                held(id).isNotBlank() -> Outcome.KEPT_MANUAL
                file(id, v) -> Outcome.FILED
                else -> Outcome.REFUSED
            }
        }
        return out
    }

    /** The import hook: files the delivered credentials into Cloud Account's own vault. Never throws. */
    fun land(ctx: Context, bundle: JSONObject): Map<String, Outcome> = runCatching {
        val vault = AccountVault(ctx)
        import(bundle.optJSONObject(SECTION), { AppCredentialsStore.get(vault, it) }, { id, v -> AppCredentialsStore.put(vault, id, v) })
    }.getOrDefault(emptyMap())
}
