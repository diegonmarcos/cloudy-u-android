package com.diegonmarcos.superapp.core

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * #783 THE fleet configuration contract: every fleet app exports and imports its own
 * configuration through ONE call, so a brand-new phone can be made to match the old one.
 *
 *     FleetConfig.export(ctx, "com.diegonmarcos.cloudcalc")          // → Reply.Ok({"stores": {...}})
 *     FleetConfig.import(ctx, "com.diegonmarcos.cloudcalc", body)    // → per-store result
 *
 * WHAT moves is declared once, in the manifest (fleet-config.json, in the SuperApp's assets since
 * #796), and it TRAVELS WITH EVERY CALL ([KEY_MANIFEST]). Only `config` and `secret` classes
 * migrate; fleet-config-guard.yml fails the build when code opens a store the manifest does not
 * declare.
 *
 * #825 this is now only the in-app half: the constants, the client one fleet app uses to ask
 * another (or itself), and [FleetConfigProvider], which reads and writes the app's OWN
 * SharedPreferences (no other process can open them). Parsing the manifest and deciding what
 * migrates - the policy - runs in the Cloud-Lib-Fleetconfig engine ([FleetConfigEngine];
 * libs:fleetconfig over libs:fleetconfig-model), so a policy edit no longer rebuilds every app.
 *
 * WHO may call: the provider is exported behind CONSTELLATION_DATA and re-checks the caller.
 *
 * WIRE FORMAT (one app):
 *     {"contract": 2, "app": "calc", "schema_version": 1,
 *      "stores": {"<file>": {"<key>": <value>, …, "_types": {"<key>": "i|l|f"}}}}
 */
object FleetConfig {

    private const val TAG = "FleetConfig"

    /** 2 = the manifest travels with the call ([KEY_MANIFEST]); 1 = each app carried a copy. */
    const val CONTRACT = 2
    const val ASSET = "fleet-config.json"
    const val AUTHORITY_SUFFIX = ".fleetconfig"
    const val PERMISSION = "com.diegonmarcos.cloud.permission.CONSTELLATION_DATA"

    const val METHOD_EXPORT = "export"
    const val METHOD_IMPORT = "import"
    /** The handshake: `{"contract": CONTRACT, "manifest": "self"|"caller"}`. */
    const val METHOD_HELLO = "hello"
    const val KEY_JSON = "json"
    const val KEY_ERROR = "error"
    /** Export/import extra: the manifest JSON the provider applies. */
    const val KEY_MANIFEST = "manifest"
    const val KEY_CONTRACT = "contract"
    /** Import extra: the app restarts once the reply is out, so no cached copy of the old
     *  values outlives the import (the next launch reads what was written). */
    const val KEY_RESTART = "restart"

    const val TYPES = "_types"

    fun authority(pkg: String) = pkg + AUTHORITY_SUFFIX

    /** The manifest text this APK carries in its assets (the SuperApp), or null. Never parsed here. */
    fun manifestText(ctx: Context): String? = try {
        ctx.applicationContext.assets.open(ASSET).bufferedReader().use { it.readText() }
    } catch (e: java.io.FileNotFoundException) {
        null
    }

    /** This app's store file: a plain one directly, an encrypted one through its cipher. Never
     *  creates a file unless [create]: an absent plain store reads as null, an absent encrypted
     *  one is not opened at all (opening one writes its keyset). The cipher (security-crypto)
     *  is compileOnly: in an app that ships none, opening an encrypted store throws, and the
     *  caller reports that file alone. */
    fun openStore(ctx: Context, kind: String, file: String, create: Boolean): SharedPreferences? {
        val app = ctx.applicationContext
        return when (kind) {
            "prefs" -> app.getSharedPreferences(file, Context.MODE_PRIVATE).takeIf { create || it.all.isNotEmpty() }
            // An app without security-crypto gets SafeEncryptedPrefs' plain "unencrypted fallback" (never a crash):
            // an encrypted store is never opened that way here, so an import refuses it instead of writing its
            // secrets in clear (FleetMigrationTest: refused for that file alone).
            "encrypted" -> if (!create && !File(app.dataDir, "shared_prefs/$file.xml").isFile) null
                else SafeEncryptedPrefs.create(app, file).takeUnless { it.unencryptedFallback }?.prefs
            else -> null
        }
    }

    sealed class Reply {
        data class Ok(val json: JSONObject) : Reply()
        /** Not installed, not visible, or an older build without the contract. */
        data class Unreachable(val why: String) : Reply()
        data class Refused(val why: String) : Reply()
    }

    /** [pkg]'s configuration under this app's manifest, which travels with the call. Starts the
     *  app if it is stopped. BLOCKS: call off the main thread. [pkg] may be this app. */
    fun export(ctx: Context, pkg: String): Reply = call(ctx, pkg, METHOD_EXPORT, withManifest(ctx, Bundle()))

    /** Applies [body] to [pkg]; [restart] lets the app restart so nothing cached outlives it
     *  (never this app: the caller is still running in it). */
    fun import(ctx: Context, pkg: String, body: JSONObject, restart: Boolean = true): Reply =
        call(ctx, pkg, METHOD_IMPORT, withManifest(ctx, Bundle().apply {
            putString(KEY_JSON, body.toString()); putBoolean(KEY_RESTART, restart && pkg != ctx.packageName)
        }))

    /** The handshake with [pkg]: its [CONTRACT], and whether it carries a manifest of its own. */
    fun hello(ctx: Context, pkg: String): Reply = call(ctx, pkg, METHOD_HELLO, Bundle())

    private fun withManifest(ctx: Context, b: Bundle): Bundle = b.apply { manifestText(ctx)?.let { putString(KEY_MANIFEST, it) } }

    private fun call(ctx: Context, pkg: String, method: String, extras: Bundle): Reply {
        try {
            val client = ctx.contentResolver.acquireUnstableContentProviderClient(authority(pkg))
                ?: return Reply.Unreachable("no ${authority(pkg)} — not installed, or a build older than the contract")
            try {
                val b = client.call(method, null, extras) ?: return Reply.Unreachable("$method returned nothing")
                b.getString(KEY_ERROR)?.let { return Reply.Refused(it) }
                return Reply.Ok(JSONObject(b.getString(KEY_JSON) ?: return Reply.Refused("no body")))
            } finally {
                client.close()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "$method $pkg: ${t.javaClass.simpleName}")
            return Reply.Unreachable("${t.javaClass.simpleName}: ${t.message.orEmpty().take(120)}")
        }
    }
}
