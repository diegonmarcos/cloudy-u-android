package com.diegonmarcos.superapp.profile

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import com.diegonmarcos.superapp.core.FleetConfig
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject

/**
 * #867 What the Cloud Account app owns, as it leaves the app and as another host takes it in.
 *
 * Cloud Account is the home of three stores: the imported configs (`import_configs`, one JSON
 * blob), the profile (`profile_prefs`) and the S/R/L account files. SuperApp used to hold them;
 * once Cloud Account is installed SuperApp COPIES them over once ([migrate]) and, while its own
 * copy is empty, reads through to Cloud Account ([configsOrRemote]).
 *
 * [Provider] is the way out: `<package>.accountdata`, exported behind CONSTELLATION_DATA (the
 * fleet's signature permission, as FleetConfigProvider is) and re-checked in call(), because the
 * framework does not permission-check call() by itself. Six methods, [METHOD_EXPORT], (#874)
 * [METHOD_SECRET], (#1090) [METHOD_MAIL_ENDPOINTS], (#1093) [METHOD_FLEET_ENDPOINTS], [METHOD_DRIVE_CREDENTIALS], [METHOD_DRIVE_CREDENTIALS_PUT] and (#1103) [AutocompletePublisher.METHOD]; everything else a ContentProvider can do is refused. It READS, except [METHOD_DRIVE_CREDENTIALS_PUT] (a granted package stores or removes an rclone remote or a mount). 
 *
 * Limits, on purpose: the copy is one-way (Cloud Account -> SuperApp) and never overwrites a
 * store the receiver already holds, so a SuperApp that kept working before Cloud Account arrived
 * loses nothing; after the copy the two evolve apart (only the empty-configs read-through stays
 * live). The profile picture/banner URIs are copied as text; the pictures themselves are not.
 */
object AccountData {
    const val PKG = com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract.ACCOUNT_PACKAGE
    const val AUTHORITY = "$PKG.accountdata"
    const val METHOD_EXPORT = "export"
    /** #874 one connection value (`arg` = its `section.key` path), only to a package the Secrets section granted it. */
    const val METHOD_SECRET = "secret"
    const val KEY_VALUE = "value"
    /** #1090 the per-account mail endpoints (mesh + public) and, to a package also granted `fleet.bearer`, the bearer. */
    const val METHOD_MAIL_ENDPOINTS = com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract.METHOD
    /** #1093 every declared fleet service's mesh + public endpoint, and to a package also granted `fleet.bearer`, the bearer. */
    const val METHOD_FLEET_ENDPOINTS = com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract.METHOD
    /** The Drive / Git credentials Cloud Drive (and any granted app) takes from Account: rclone remotes, the ed25519 key, mounts. */
    const val METHOD_DRIVE_CREDENTIALS = com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.METHOD
    /** Cloud Drive stores or removes an rclone remote or a mount (`arg` = the JSON body); needs the `drive.write` grant. */
    const val METHOD_DRIVE_CREDENTIALS_PUT = com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.METHOD_PUT
    /** #1528 Cloud Dialer's SIM PIN / PUK: `arg` = "<sim key>.<pin|puk>" (read, or write with the value in the extras); needs the [GRANT_SIM_SECRETS] grant. Held in the vault at `sim.<arg>`, never returned in an error. */
    const val METHOD_SIM_SECRET = "sim_secret"
    const val METHOD_SIM_SECRET_PUT = "sim_secret_put"
    const val GRANT_SIM_SECRETS = "sim.secrets"
    private val SIM_SECRET_ARG = Regex("[A-Za-z0-9_-]+\\.(pin|puk)")
    /** #874 the grant that lets another package take [METHOD_EXPORT] (SuperApp's one-shot copy). */
    const val GRANT_EXPORT = "account.export"

    const val KEY_OK = "ok"
    const val KEY_ERROR = "error"
    const val KEY_CONFIGS = "configs_json"
    /** `{"<pref key>": {"t": "s|b|i|l|f", "v": …}}` — the profile_prefs file, typed. */
    const val KEY_PROFILE = "profile"
    /** slot S / R / L: the file's own JSON text. */
    fun slotKey(s: Slot) = "slot_${s.name}"

    private const val PROFILE_FILE = "profile_prefs"
    private const val MIGRATION_FILE = "account_data_migration"
    private const val K_DONE = "done"
    private const val TAG = "AccountData"

    // ── what leaves ──────────────────────────────────────────────────────

    /** The export bundle for this app's three stores. A store with nothing in it is simply absent. */
    fun export(ctx: Context): Bundle = Bundle().apply {
        putBoolean(KEY_OK, true)
        runCatching { ConfigsPrefs(ctx).json }.getOrNull()?.takeIf { it.isNotBlank() }?.let { putString(KEY_CONFIGS, it) }
        runCatching { profileJson(ctx.getSharedPreferences(PROFILE_FILE, Context.MODE_PRIVATE).all) }.getOrNull()
            ?.takeIf { it.length() > 0 }?.let { putString(KEY_PROFILE, it.toString()) }
        val store = AccountModel.get(ctx).store
        for (s in Slot.values()) runCatching { store.export(s) }.getOrNull()?.let { putString(slotKey(s), it) }
    }

    fun profileJson(all: Map<String, *>): JSONObject = JSONObject().also { o ->
        for ((k, v) in all) {
            val (t, value) = when (v) {
                is String -> "s" to v
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                else -> continue
            }
            o.put(k, JSONObject().put("t", t).put("v", value))
        }
    }

    // ── what arrives ─────────────────────────────────────────────────────

    /** True once the copy has run to the end, whether or not it had anything to copy. */
    fun migrated(ctx: Context): Boolean =
        ctx.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).getBoolean(K_DONE, false)

    fun accountInstalled(ctx: Context): Boolean = runCatching {
        ctx.packageManager.getApplicationInfo(PKG, 0).enabled
    }.getOrDefault(false)

    /** Asks Cloud Account for [METHOD_EXPORT]; null when it is not installed, refuses, or fails. */
    fun fetch(ctx: Context): Bundle? {
        if (ctx.packageName == PKG || !accountInstalled(ctx)) return null
        val r = runCatching {
            ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD_EXPORT, null, null)
        }.getOrNull()
        return r?.takeIf { it.getBoolean(KEY_OK) }
    }

    /**
     * #874 A connection value (`fleet.bearer`, `auth.authelia_token`, …) from Cloud Account, or "" when it is not
     * installed, refuses (no grant) or holds none. The value is the caller's to use, never to log or keep.
     */
    fun secret(ctx: Context, path: String): String {
        if (ctx.packageName == PKG || !accountInstalled(ctx)) return ""
        val r = runCatching { ctx.contentResolver.call(Uri.parse("content://$AUTHORITY"), METHOD_SECRET, path, null) }.getOrNull()
        return if (r?.getBoolean(KEY_OK) == true) r.getString(KEY_VALUE).orEmpty() else ""
    }

    /**
     * The one-shot copy: runs when Cloud Account is installed and has not run to the end yet.
     * Each store is filled only if this app's own is EMPTY. Returns what was copied (for the log
     * and the tests); an empty list also marks the migration done, since there was nothing to take.
     * A failed call leaves it undone, so the next start tries again.
     */
    fun migrate(ctx: Context): List<String> {
        if (migrated(ctx)) return emptyList()
        val b = fetch(ctx) ?: return emptyList()
        val copied = apply(ctx, b)
        ctx.getSharedPreferences(MIGRATION_FILE, Context.MODE_PRIVATE).edit().putBoolean(K_DONE, true).apply()
        android.util.Log.i(TAG, "migrated from Cloud Account: $copied")
        return copied
    }

    /** Takes [b] into this app's stores where they are empty. Never overwrites. */
    fun apply(ctx: Context, b: Bundle): List<String> {
        val copied = ArrayList<String>()
        b.getString(KEY_CONFIGS)?.takeIf { it.isNotBlank() }?.let { json ->
            val prefs = ConfigsPrefs(ctx)
            if (prefs.localJson.isBlank()) { prefs.json = json; copied += "import_configs" }
        }
        b.getString(KEY_PROFILE)?.let { if (applyProfile(ctx.getSharedPreferences(PROFILE_FILE, Context.MODE_PRIVATE), it)) copied += PROFILE_FILE }
        val model = AccountModel.get(ctx)
        for (s in Slot.values()) {
            val text = b.getString(slotKey(s)) ?: continue
            if (model.store.read(s) != null) continue
            val doc = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val m = doc.optJSONObject("_meta") ?: continue
            val body = doc.optJSONObject("body") ?: continue
            model.store.write(s, body, m.optString("source"), m.optString("at"), doc.optJSONObject("apps"))
            copied += "slot ${s.name}"
        }
        if (copied.any { it.startsWith("slot") }) model.invalidate()
        return copied
    }

    /** #874 Whole-bundle import: writes the typed profile over this app's, key by key (a key the bundle lacks stays). */
    fun restoreProfile(ctx: Context, typed: JSONObject): Boolean {
        val sp = ctx.getSharedPreferences(PROFILE_FILE, Context.MODE_PRIVATE)
        val ed = sp.edit()
        for (k in typed.keys()) {
            val e = typed.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.optString("v"))
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
            }
        }
        return ed.commit()
    }

    /** Fills [sp] from the typed export when it holds no profile of its own (only schema/install keys). */
    fun applyProfile(sp: SharedPreferences, text: String): Boolean {
        val src = runCatching { JSONObject(text) }.getOrNull() ?: return false
        // "Empty" = nothing the user typed: the install id/secret and schema version are generated on first read.
        val generated = setOf("schema_version", "install_id", "install_secret")
        if (sp.all.keys.any { it !in generated }) return false
        val ed = sp.edit()
        for (k in src.keys()) {
            val e = src.optJSONObject(k) ?: continue
            when (e.optString("t")) {
                "s" -> ed.putString(k, e.optString("v"))
                "b" -> ed.putBoolean(k, e.optBoolean("v"))
                "i" -> ed.putInt(k, e.optInt("v"))
                "l" -> ed.putLong(k, e.optLong("v"))
                "f" -> ed.putFloat(k, e.optDouble("v").toFloat())
            }
        }
        return ed.commit()
    }

    /**
     * Read-through for the imported configs: this app's own blob, else Cloud Account's.
     * [fetched] is tried at most once per process, so a blank blob costs one provider call.
     */
    @Volatile private var triedRemote = false
    fun configsOrRemote(ctx: Context, local: String): String {
        if (local.isNotBlank() || triedRemote || !AccountHost.readThrough) return local
        triedRemote = true
        return fetch(ctx)?.getString(KEY_CONFIGS).orEmpty()
    }

    // ── the provider ─────────────────────────────────────────────────────

    /** The calling package; a JVM test hands in another one (Binder cannot be spoofed under Robolectric). */
    @Volatile var callerResolver: (Context) -> String =
        { c -> c.packageManager.getPackagesForUid(Binder.getCallingUid())?.firstOrNull() ?: c.packageName }

    /** Open for the host app's subclass (Cloud Account adds the fleet data routes: openFile of app/<pkg>/<dataset>, shared/<dataset>, index). */
    open class Provider : ContentProvider() {
        override fun onCreate(): Boolean = true

        override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
            val ctx = context ?: return error("no context")
            if (Binder.getCallingUid() != Process.myUid() &&
                ctx.checkCallingPermission(FleetConfig.PERMISSION) != PackageManager.PERMISSION_GRANTED)
                return error("caller is not a fleet app")
            return when (method) {
                METHOD_EXPORT -> {
                    // #874 the whole export carries every connection: only a package the Secrets section granted `account.export`.
                    val who = caller(ctx)
                    if (who != ctx.packageName && !AccountVault(ctx).grants.allow(who, GRANT_EXPORT)) error("no grant for $who")
                    else runCatching { export(ctx) }.getOrElse { error("${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }
                }
                METHOD_SECRET -> secretFor(ctx, caller(ctx), arg)
                METHOD_MAIL_ENDPOINTS -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract.GRANT_ENDPOINTS)) error("no grant for $who")
                    else runCatching { MailEndpointsPublisher.publish(ctx, who, vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                // #1103 the Autocomplete rows for Cloud Keyboard's own store: only a package granted `autocomplete.read`
                AutocompletePublisher.METHOD -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, AutocompletePublisher.GRANT)) error("no grant for $who")
                    else runCatching { AutocompletePublisher.publish(vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                // The Drive / Git credentials (rclone remotes, the ed25519 key, mounts): `drive.credentials` for the public layer, a grant per secret layer
                METHOD_DRIVE_CREDENTIALS -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.GRANT_READ)) error("no grant for $who")
                    else runCatching { DriveCredentialsPublisher.publish(ctx, who, vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                METHOD_DRIVE_CREDENTIALS_PUT -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.GRANT_WRITE)) error("no grant for $who")
                    else runCatching { DriveCredentialsPublisher.put(ctx, arg, vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                METHOD_SIM_SECRET, METHOD_SIM_SECRET_PUT -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (arg == null || !SIM_SECRET_ARG.matches(arg)) error("bad key")
                    else if (who != ctx.packageName && !vault.grants.allow(who, GRANT_SIM_SECRETS)) error("no grant for $who")
                    else if (method == METHOD_SIM_SECRET) Bundle().apply { putBoolean(KEY_OK, true); putString(KEY_VALUE, vault.connection("sim.$arg")?.toString().orEmpty()) }
                    else runCatching { vault.putConnection("sim.$arg", extras?.getString(KEY_VALUE).orEmpty()); Bundle().apply { putBoolean(KEY_OK, true) } }
                        .getOrElse { error(it.javaClass.simpleName) }
                }
                // #1693 the fleet's mail accounts (address + password + endpoints) for Cloud Mail: default deny, the `mail.accounts` grant
                // #1693 a credential typed in Cloud Account's App credentials page, per-credential grant `cred.<id>`, default deny
                com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract.METHOD -> AppCredentialsStore.read(ctx, caller(ctx), arg)
                // #1693 Cloud Mail's OAuth device grant runs HERE: start (a code to show) and one poll (the tokens once approved); grant `mail.oauth`, default deny
                com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_START, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_POLL -> {
                    val who = caller(ctx)
                    if (who != ctx.packageName && !AccountVault(ctx).grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.GRANT_REQUEST)) error("no grant for $who")
                    else if (method == com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_START) MailOAuthSessions.start(ctx, arg) else MailOAuthSessions.poll(ctx, arg)
                }
                // #1703 the same grant's authorization-code half (PKCE + browser): start (a handle), finish (the redirect's code + state in, the tokens out); same grant `mail.oauth`
                com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_START, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_FINISH -> {
                    val who = caller(ctx)
                    if (who != ctx.packageName && !AccountVault(ctx).grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.GRANT_REQUEST)) error("no grant for $who")
                    else if (method == com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.METHOD_CODE_START) MailOAuthCodeSessions.start(ctx, arg, who) else MailOAuthCodeSessions.finish(ctx, arg)
                }
                com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.METHOD -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.GRANT_READ)) error("no grant for $who")
                    else runCatching { MailAccountsPublisher.publish(ctx, vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                METHOD_FLEET_ENDPOINTS -> {
                    val who = caller(ctx)
                    val vault = AccountVault(ctx)
                    if (who != ctx.packageName && !vault.grants.allow(who, com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract.GRANT_ENDPOINTS)) error("no grant for $who")
                    else runCatching { FleetEndpointsPublisher.publish(ctx, who, vault) }.getOrElse { error(it.javaClass.simpleName) }
                }
                // #1693 the picked device's mesh addresses (not secret): the SuperApp's Cloud-preset guard reads them here
                "mesh_device" -> runCatching {
                    val picked = VaultCockpit.selectedDevice(ctx)
                    val bundle = AccountModel.get(ctx).server()?.body
                    val d = if (picked.isBlank() || bundle == null) null else VaultCockpit.devices(bundle).firstOrNull { it.id == picked }
                    val a = org.json.JSONArray(); d?.let { listOf(it.wgIp, it.wgIpv6).filter { x -> x.isNotBlank() }.forEach(a::put) }
                    Bundle().apply { putBoolean(KEY_OK, true); putString("mesh_device_json", JSONObject().put("device", d?.id.orEmpty()).put("addresses", a).toString()) }
                }.getOrElse { error(it.javaClass.simpleName) }
                // #1693 sign-in STATE only (no token, no identity secret): every fleet app's sign-in row reads it
                com.diegonmarcos.superapp.fleetendpoints.SignInContract.METHOD -> {
                    val provider = arg?.trim().orEmpty()
                    if (!Regex("[a-z0-9_-]{1,40}").matches(provider)) error("bad provider")
                    else Bundle().apply {
                        putBoolean(KEY_OK, true)
                        putString(com.diegonmarcos.superapp.fleetendpoints.SignInContract.KEY_JSON, SignInOwner.local(ctx, provider).json().toString())
                    }
                }
                // #1693 the short-lived Authelia session: signature-gated (the check above AND here), refreshed by Cloud Account, never logged
                com.diegonmarcos.superapp.fleetendpoints.SignInContract.METHOD_SESSION -> {
                    val allowed = Binder.getCallingUid() == Process.myUid() ||
                        ctx.checkCallingPermission(FleetConfig.PERMISSION) == PackageManager.PERMISSION_GRANTED
                    val sess = SessionLease.answer(arg?.trim().orEmpty(), allowed, SessionProbe.probe())
                    if (!allowed) error("caller is not a fleet app")
                    else Bundle().apply {
                        putBoolean(KEY_OK, true)
                        putString(com.diegonmarcos.superapp.fleetendpoints.SignInContract.KEY_SESSION_JSON, sess.json().toString())
                    }
                }
                else -> error("unknown method $method")
            }
        }

        private fun caller(ctx: Context): String = callerResolver(ctx)

        private fun secretFor(ctx: Context, caller: String, path: String?): Bundle {
            if (path.isNullOrBlank()) return error("no key")
            val vault = AccountVault(ctx)
            if (caller != ctx.packageName && !vault.grants.allow(caller, path)) return error("no grant for $caller")
            val v = vault.connection(path) ?: return Bundle().apply { putBoolean(KEY_OK, true); putString(KEY_VALUE, "") }
            return Bundle().apply { putBoolean(KEY_OK, true); putString(KEY_VALUE, v.toString()) }
        }

        private fun error(why: String) = Bundle().apply { putBoolean(KEY_OK, false); putString(KEY_ERROR, why) }

        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }
}
