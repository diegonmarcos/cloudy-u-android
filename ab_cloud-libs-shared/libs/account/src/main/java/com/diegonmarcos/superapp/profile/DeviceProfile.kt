package com.diegonmarcos.superapp.profile

import android.content.Context
import android.os.Build
import org.json.JSONObject

/**
 * Cloud Account redesign (spec 5.1): the device file `Shared/configs/devices/<id>.json`.
 *
 * ```
 * { "kind": "cloud-account.device-profile", "schema": 1,
 *   "device":   { "id", "model", "android", "captured_at", "by" },
 *   "apps":     the cloud-sa.app-inventory JSON (libs:appstore AppInventory),
 *   "settings": AccountVault.appConfigs()'s shape {app: {store: {file: {key: value, "_types"}}}}
 *               with every SECRET-class key (fleet manifest classes) replaced by "@vault:<path>",
 *   "perms": { "<pkg>": { "granted": [], "appops": {}, "roles": [], "battery": false } },
 *   "system": {} }      (perms: PermsPlan.capture; system: a later task)
 * ```
 *
 * The repo is private but plaintext (#589): a literal value in a secret-class key is REFUSED on
 * [build] and on [parse] (import), named by its path — never written, never loaded.
 */
object DeviceProfile {

    const val KIND = "cloud-account.device-profile"
    const val SCHEMA = 1
    const val VAULT_REF = "@vault:"
    const val DEFAULT_ID = "DEFAULT"

    /** Is key [key] of store [store] secret-class for app [app]? (the fleet manifest's classes) */
    fun interface SecretClass { fun secret(app: String, store: String, key: String): Boolean }

    sealed class Parsed {
        data class Ok(val json: JSONObject) : Parsed()
        data class Refused(val reason: String) : Parsed()
    }

    /** The `@vault:` path a secret-class key points at: the vault's settings section, same nesting. */
    fun vaultPath(app: String, store: String, file: String, key: String) = "settings.$app.$store.$file.$key"

    private fun isLiteral(v: Any?): Boolean = when (v) {
        null, JSONObject.NULL -> false
        is String -> v.isNotEmpty() && !v.startsWith(VAULT_REF)
        else -> true
    }

    /** Walk settings: (app, store, file, key, value) for every real key (`_`-prefixed are metadata). */
    private inline fun walk(settings: JSONObject, f: (String, String, String, String, Any?) -> Unit) {
        for (app in settings.keys().asSequence().filter { !it.startsWith("_") }.toList()) {
            val stores = settings.optJSONObject(app) ?: continue
            for (store in stores.keys().asSequence().filter { !it.startsWith("_") }.toList()) {
                val files = stores.optJSONObject(store) ?: continue
                for (file in files.keys().asSequence().filter { !it.startsWith("_") }.toList()) {
                    val keys = files.optJSONObject(file) ?: continue
                    for (key in keys.keys().asSequence().filter { !it.startsWith("_") }.toList()) f(app, store, file, key, keys.opt(key))
                }
            }
        }
    }

    /** Every secret-class key holding a literal value, as `app.store.file.key` (names only). */
    fun secretViolations(settings: JSONObject, cls: SecretClass): List<String> {
        val bad = mutableListOf<String>()
        walk(settings) { app, store, file, key, v -> if (cls.secret(app, store, key) && isLiteral(v)) { bad += "$app.$store.$file.$key" } }
        return bad
    }

    /** A copy of [settings] with every secret-class literal replaced by its `@vault:` reference. */
    fun mask(settings: JSONObject, cls: SecretClass): JSONObject {
        val out = JSONObject(settings.toString())
        walk(out) { app, store, file, key, v ->
            if (cls.secret(app, store, key) && isLiteral(v))
                out.getJSONObject(app).getJSONObject(store).getJSONObject(file).put(key, VAULT_REF + vaultPath(app, store, file, key))
        }
        return out
    }

    /** The device file. Throws [IllegalArgumentException] naming the key when a secret literal is in [settings]. */
    fun build(device: JSONObject, apps: JSONObject, settings: JSONObject, cls: SecretClass,
              perms: JSONObject = JSONObject(), system: JSONObject = JSONObject()): JSONObject {
        val bad = secretViolations(settings, cls)
        require(bad.isEmpty()) { "refused: a secret value is in settings at ${bad.joinToString()} — it belongs in the vault (@vault:)" }
        return JSONObject().put("kind", KIND).put("schema", SCHEMA).put("device", device).put("apps", apps)
            .put("settings", settings).put("perms", perms).put("system", system)
    }

    /** Parse and gate a device file (import / load): kind, schema, and the secret-class check. */
    fun parse(text: String, cls: SecretClass): Parsed {
        val o = runCatching { JSONObject(text) }.getOrElse { return Parsed.Refused("not JSON") }
        if (o.optString("kind") != KIND) return Parsed.Refused("not a device profile (kind '${o.optString("kind")}')")
        if (o.optInt("schema", -1) != SCHEMA) return Parsed.Refused("schema ${o.opt("schema")} — this build knows $SCHEMA")
        val bad = secretViolations(o.optJSONObject("settings") ?: JSONObject(), cls)
        if (bad.isNotEmpty()) return Parsed.Refused("refused: a secret value is in settings at ${bad.joinToString()}")
        return Parsed.Ok(o)
    }

    /**
     * file → runtime: the config-class keys only, in appConfigs()'s shape (FleetSetup.plan's input).
     * Secret-class keys (the `@vault:` references) are left to the secrets step.
     */
    fun plan(profile: JSONObject, cls: SecretClass): JSONObject {
        val out = JSONObject(profile.optJSONObject("settings")?.toString() ?: "{}")
        val drop = mutableListOf<List<String>>()
        walk(out) { app, store, file, key, _ -> if (cls.secret(app, store, key)) { drop += listOf(app, store, file, key) } }
        drop.forEach { (a, s, f, k) -> out.getJSONObject(a).getJSONObject(s).getJSONObject(f).remove(k) }
        return out
    }

    /** Counts for commit messages and listings: apps in the inventory, settings keys. No values. */
    fun counts(profile: JSONObject): Pair<Int, Int> {
        val apps = profile.optJSONObject("apps")?.optJSONArray("apps")?.length() ?: 0
        var keys = 0
        walk(profile.optJSONObject("settings") ?: JSONObject()) { _, _, _, _, _ -> keys += 1 }
        return apps to keys
    }

    /** The fleet manifest's classes on this phone; no manifest = nothing is known secret, so nothing is captured as such. */
    fun manifestClass(ctx: Context): SecretClass {
        val m = runCatching { com.diegonmarcos.superapp.fleetconfig.FleetPolicy.manifestOrNull(ctx) }.getOrNull()
            ?: return SecretClass { _, _, _ -> false }
        return SecretClass { app, store, key -> m.stores[store]?.let { m.secret(it, key, app) } ?: false }
    }

    /** This phone now: inventory + captured configs (secrets masked), for [deviceId]. */
    fun capture(ctx: Context, deviceId: String, now: String = java.time.Instant.now().toString()): JSONObject {
        val cls = manifestClass(ctx)
        val inv = com.diegonmarcos.superapp.appstore.AppInventory
        val apps = JSONObject(inv.toJson(inv.entriesFor(ctx, inv.launchable(ctx))))
        val settings = mask(com.diegonmarcos.superapp.settings.AccountVault(ctx).appConfigs(), cls)
        val by = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
        val device = JSONObject().put("id", deviceId).put("model", Build.MODEL).put("android", Build.VERSION.SDK_INT)
            .put("captured_at", now).put("by", "cloud-account/$by")
        // perms (spec 4.9): exactly Setup ▸ perms, as granted now (PermsPlan.capture).
        val perms = runCatching { PermsPlan.capture(ctx, ShellChannels.active(ctx)) }.getOrDefault(JSONObject())
        return build(device, apps, settings, cls, perms = perms)
    }

    /** The bytes committed: stable two-space JSON with a final newline (so equal content = no commit). */
    fun text(profile: JSONObject): String = profile.toString(2) + "\n"
}
