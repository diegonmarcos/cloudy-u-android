package com.diegonmarcos.superapp.settings

import android.content.Context
import android.util.Base64
import com.diegonmarcos.superapp.fleetendpoints.FleetEndpointsContract
import com.diegonmarcos.superapp.fleetendpoints.MailEndpointsContract
import com.diegonmarcos.superapp.fleetconfig.SetupStores
import com.diegonmarcos.superapp.profile.AccountData
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountStore.Slot
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * #874 THE ACCOUNT VAULT: the one place Cloud Account holds the owner's connections, data, configs and
 * secrets - four sections in the ONE keystore-backed encrypted file [ConfigsPrefs] already is
 * (`import_configs`), not a new silo:
 *
 *   Connections  the imported configs blob (ConfigsPrefs.json): endpoints, mesh/WireGuard, DNS preset,
 *                fleet bearer, GitHub/engine tokens, mail, Dagu, cloud APIs - at `section.key` paths
 *   Data         identities (this class) + the profile (`profile_prefs`) + the S/R/L slots (AccountStore)
 *   Configs      every app's declared settings, keyed `app > store > file > key` (fleet-config.json)
 *   Secrets      per-app GRANTS: which package may read which key; revocable ([SecretGrants]), enforced
 *                by AccountData.Provider (`secret`) and by this app's setup provider ([authorizer])
 *
 * and the whole of it leaves and arrives as ONE encrypted, versioned bundle ([exportBundle]/[importBundle]),
 * which replaces the ad-hoc configs paste. Values are never logged, never put in an exception message.
 */
class AccountVault(context: Context) {
    private val ctx: Context = context.applicationContext ?: context
    val prefs = ConfigsPrefs(ctx)

    // ── Connections ──────────────────────────────────────────────────────

    fun connections(): JSONObject = parse(prefs.json)

    /** The value at `a.b`, typed as stored; null when absent. */
    fun connection(path: String): Any? {
        var cur: Any? = connections()
        for (seg in path.split('.')) cur = (cur as? JSONObject)?.opt(seg) ?: return null
        return cur.takeUnless { it == JSONObject.NULL }
    }

    /** Read-modify-write of one value at `a.b`, the same blob [ConfigsPrefs.putSecret] writes. */
    fun putConnection(path: String, value: Any) {
        val root = connections()
        val segs = path.split('.')
        var cur = root
        for (s in segs.dropLast(1)) cur = cur.optJSONObject(s) ?: JSONObject().also { cur.put(s, it) }
        cur.put(segs.last(), value)
        prefs.json = root.toString()
    }

    // ── Data ─────────────────────────────────────────────────────────────

    fun identities(): JSONArray = parse(prefs.text(ConfigsPrefs.K_DATA)).optJSONArray("identities") ?: JSONArray()
    fun putIdentities(a: JSONArray) = prefs.putText(ConfigsPrefs.K_DATA, JSONObject().put("identities", a).toString())

    /** #1103 the Autocomplete document Account holds for Cloud Keyboard ("" before the first sync); one keystore-backed prefs key, like the sections above. */
    fun autocompleteJson(): String = prefs.text(ConfigsPrefs.K_AUTOCOMPLETE)
    fun putAutocomplete(json: String) = prefs.putText(ConfigsPrefs.K_AUTOCOMPLETE, json)

    // ── Configs ──────────────────────────────────────────────────────────

    /** `{"<app id>": {"<store>": {"<file>": {"<key>": value, "_types": {…}}}}}` */
    fun appConfigs(): JSONObject = parse(prefs.text(ConfigsPrefs.K_APP_CONFIGS))
    fun putAppConfigs(o: JSONObject) = prefs.putText(ConfigsPrefs.K_APP_CONFIGS, o.toString())

    /** Folds one app's export (`{"stores": {"<store>": {"<file>": {…}}}}`, the setup contract's) into Configs. */
    fun captureConfigs(appId: String, export: JSONObject) {
        val all = appConfigs()
        val stores = export.optJSONObject("stores") ?: return
        all.put(appId, JSONObject(stores.toString()).also { it.remove(com.diegonmarcos.superapp.profile.SetupPlan.VAULT_STORE) })
        putAppConfigs(all)
    }

    // ── Secrets ──────────────────────────────────────────────────────────

    val grants = SecretGrants({ parse(prefs.text(ConfigsPrefs.K_GRANTS)) }, { prefs.putText(ConfigsPrefs.K_GRANTS, it.toString()) })

    /** Section sizes for the pages and the debug API: counts only, never a value. */
    fun summary(): JSONObject = JSONObject()
        .put("connections", leaves(connections()))
        .put("data", identities().length() + (if (profileValues() > 0) 1 else 0) + Slot.values().count { runCatching { storeSource(ctx).read(it) }.getOrNull() != null })
        .put("configs", appConfigs().let { c -> c.keys().asSequence().sumOf { app -> c.optJSONObject(app)?.length() ?: 0 } })
        .put("secrets", grants.all().values.sumOf { it.size })

    private fun profileValues() = ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).all.size

    // ── Mail signatures ──────────────────────────────────────────────────

    /**
     * Mail's signatures as the bundle's `signatures` section: live from Mail when it answers (and kept as
     * the held copy), else the copy this vault last held, else null. BLOCKS (a provider call).
     */
    fun signatures(): JSONObject? {
        MailSignatures.capture(ctx)?.let { live ->
            val doc = MailSignatures.document(live)
            prefs.putText(K_SIGNATURES, doc.toString())
            return doc
        }
        return parse(prefs.text(K_SIGNATURES)).takeIf { it.length() > 0 }
    }

    /** Holds [doc] and hands it to Mail; Mail not being there is not a failed import, the copy is kept for the next export. */
    fun takeSignatures(doc: JSONObject): MailSignatures.Handed {
        prefs.putText(K_SIGNATURES, doc.toString())
        val values = MailSignatures.valuesOf(doc) ?: return MailSignatures.Handed(false, 0, "not a signature document")
        return MailSignatures.handBack(ctx, values)
    }

    // ── the whole bundle ─────────────────────────────────────────────────

    /** Every section as the plaintext document [exportBundle] encrypts. */
    fun bundleJson(now: String): JSONObject {
        val slots = JSONObject()
        val store = storeSource(ctx)
        for (s in Slot.values()) runCatching { store.export(s) }.getOrNull()?.let { slots.put(s.name, it) }
        return JSONObject().put("kind", KIND).put("v", VERSION).put("at", now)
            .put("connections", connections())
            .put("data", JSONObject().put("identities", identities())
                .put("profile", AccountData.profileJson(ctx.getSharedPreferences("profile_prefs", Context.MODE_PRIVATE).all))
                .put("slots", slots))
            .put("configs", appConfigs())
            .put("secrets", JSONObject().put("grants", grants.asJson()))
            .also { b -> signatures()?.let { b.put("signatures", it) } }
    }

    /** The encrypted, versioned bundle text: PBKDF2-HMAC-SHA256 -> AES-256-GCM, the header bound as AAD. */
    fun exportBundle(passphrase: CharArray, now: String = java.time.Instant.now().toString()): String =
        BundleCrypto.encrypt(bundleJson(now).toString(), passphrase)

    data class ImportResult(val ok: Boolean, val sections: List<String>, val why: String = "")

    /** Replaces the four sections with the bundle's. Refuses a wrong passphrase, damage, a newer version, a foreign document. */
    fun importBundle(text: String, passphrase: CharArray): ImportResult {
        val plain = try { BundleCrypto.decrypt(text, passphrase) } catch (e: BundleCrypto.Refused) { return ImportResult(false, emptyList(), e.message.orEmpty()) }
        val b = runCatching { JSONObject(plain) }.getOrNull() ?: return ImportResult(false, emptyList(), "the bundle holds no JSON")
        if (b.optString("kind") != KIND) return ImportResult(false, emptyList(), "not a Cloud Account bundle")
        val done = ArrayList<String>()
        b.optJSONObject("connections")?.let { prefs.json = it.toString(); done += "connections" }
        b.optJSONObject("data")?.let { d ->
            putIdentities(d.optJSONArray("identities") ?: JSONArray())
            d.optJSONObject("profile")?.let { AccountData.restoreProfile(ctx, it) }
            d.optJSONObject("slots")?.let { sl ->
                val store = storeSource(ctx)
                for (s in Slot.values()) {
                    val doc = sl.optString(s.name).takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() } ?: continue
                    val meta = doc.optJSONObject("_meta") ?: continue
                    val body = doc.optJSONObject("body") ?: continue
                    store.write(s, body, meta.optString("source"), meta.optString("at"), doc.optJSONObject("apps"))
                }
                runCatching { AccountModel.get(ctx).invalidate() }
            }
            done += "data"
        }
        b.optJSONObject("configs")?.let { putAppConfigs(it); done += "configs" }
        b.optJSONObject("secrets")?.optJSONObject("grants")?.let { grants.replace(it); done += "secrets" }
        b.optJSONObject("signatures")?.let { takeSignatures(it); done += "signatures" }
        return ImportResult(true, done)
    }

    /** Once per install: the grants the fleet relied on before this section existed. Revocable like any other. */
    fun seedDefaults() {
        // The marker is the seed VERSION ("1" from before versions existed): a later version seeds only what it
        // added, so a grant the owner revoked from an earlier one is never put back.
        val done = prefs.text(K_SEEDED).toIntOrNull() ?: 0
        if (done >= SEED_VERSION) return
        for ((v, defaults) in DEFAULT_GRANTS_BY_VERSION) if (v > done) for ((pkg, keys) in defaults) keys.forEach { grants.grant(pkg, it) }
        prefs.putText(K_SEEDED, SEED_VERSION.toString())
    }

    // ── enforcement ──────────────────────────────────────────────────────

    /** Cloud Account's setup provider: a secret key leaves or arrives only for this app itself or a package granted `store.key`. */
    fun authorizer() = SetupStores.Authorizer { c, caller, _, store, key, secret ->
        !secret || caller == c.packageName || grants.allow(caller, "$store.$key")
    }

    companion object {
        const val KIND = "cloud-account-bundle"
        private const val K_SEEDED = "grants_seeded"
        private const val K_SIGNATURES = "mail_signatures"

        /** Where the S/R/L files are; the JVM suite hands in plain files (the phone's are EncryptedFile). */
        @Volatile var storeSource: (Context) -> com.diegonmarcos.superapp.profile.AccountStore = { AccountModel.get(it).store }
        const val VERSION = 1

        /** Wires the grants into this app's setup provider and seeds the defaults once; call from Application.onCreate. */
        fun install(ctx: Context) {
            val v = AccountVault(ctx)
            v.seedDefaults()
            SetupStores.authorizer = v.authorizer()
        }

        /** What was read-through before grants existed, now explicit: SuperApp takes the export, SuperApp and Store the fleet bearer. */
        val DEFAULT_GRANTS_BY_VERSION: Map<Int, Map<String, List<String>>> = mapOf(
            1 to mapOf(
                "com.diegonmarcos.superapp" to listOf(AccountData.GRANT_EXPORT, "fleet.bearer", "auth.*"),
                "com.diegonmarcos.cloudstore" to listOf("fleet.bearer"),
            ),
            // #1090 Cloud Mail reads its accounts' mesh + public endpoints and the fleet bearer from here.
            2 to mapOf(MAIL_PACKAGE to listOf(MailEndpointsContract.GRANT_ENDPOINTS, MailEndpointsContract.GRANT_BEARER)),
            // #1093 Cloud Vault authenticates to the self-hosted vault over the fleet endpoints Account publishes.
            3 to mapOf(VAULT_PACKAGE to listOf(FleetEndpointsContract.GRANT_ENDPOINTS, FleetEndpointsContract.GRANT_BEARER)),
            // #1093 the SuperApp and the Store run the fleet endpoints too (the updater's mesh-first leg, Telemetry, news, mail): they
            // already hold fleet.bearer (v1) and now read the endpoint pairs.
            4 to mapOf(
                "com.diegonmarcos.superapp" to listOf(FleetEndpointsContract.GRANT_ENDPOINTS),
                "com.diegonmarcos.cloudstore" to listOf(FleetEndpointsContract.GRANT_ENDPOINTS),
            ),
            // #1103 Cloud Keyboard's own Autocomplete store is filled from here (never from the network, never from Cloud Vault).
            5 to mapOf(KEYBOARD_PACKAGE to listOf(com.diegonmarcos.superapp.profile.AutocompletePublisher.GRANT)),
            // Cloud Drive takes its endpoints and bearer, and the Drive / Git credentials (rclone remotes, the ssh key, mounts) from here; revocable per layer in Secrets.
            6 to mapOf(DRIVE_PACKAGE to listOf(FleetEndpointsContract.GRANT_ENDPOINTS, FleetEndpointsContract.GRANT_BEARER) +
                com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.GRANTS),
            // #1173 the Folders Sync (Unison) pairs got their own grant after v6 was seeded: a phone already at v6 gets it here.
            7 to mapOf(DRIVE_PACKAGE to listOf(com.diegonmarcos.superapp.fleetendpoints.DriveCredentialsContract.GRANT_UNISON)),
            // #1693 Cloud Mail takes the fleet's mail accounts (address, password, endpoints) from here instead of a second sign-in; revocable in Secrets.
            8 to mapOf(MAIL_PACKAGE to listOf(com.diegonmarcos.superapp.fleetendpoints.MailAccountsContract.GRANT_READ)),
            // #1693 batch 1 of the App credentials: the git token (Drive, KG Store) and the self-hosted git host tokens (Drive).
            9 to mapOf(
                DRIVE_PACKAGE to listOf("cred.git.github_token", "cred.git.host.*"),
                "com.diegonmarcos.cloudkgstore" to listOf("cred.git.github_token"),
            ),
            // #1693 batch 2: Drive's per-repository sync token and ssh passphrase.
            10 to mapOf(DRIVE_PACKAGE to listOf("cred.git.repo.*", "cred.git.ssh.*")),
            // #1693 batch 3: Cloud Tasks' CalDAV password and mail-agent secret.
            11 to mapOf("com.diegonmarcos.cloudagenda" to listOf("cred.tasks.caldav_password", "cred.tasks.jmap_secret")),
            // #1693 batch 4: the SuperApp's Mattermost login and password.
            12 to mapOf("com.diegonmarcos.superapp" to listOf("cred.mattermost.login", "cred.mattermost.password")),
            // #1693 batch 5: the Dagu bearer token (SuperApp and Cloud C3).
            13 to mapOf("com.diegonmarcos.superapp" to listOf("cred.dagu.token"), "com.diegonmarcos.cloudc3" to listOf("cred.dagu.token")),
            // #1693 batch 7: the Cloud Me Buro backup passphrase.
            14 to mapOf("com.diegonmarcos.cloudme" to listOf("cred.me.buro_backup_passphrase")),
            // #1693 batch 8: Cloud Browser's Music page takes the YouTube Music client and the tokens the device grant filed in Cloud Account.
            15 to mapOf("com.diegonmarcos.cloudbrowser" to listOf("cred.music.ytmusic.*")),
            // #1693 batch 9: Cloud Mail's mailbox passwords, API tokens and gateway token are read from here (it types none).
            16 to mapOf(MAIL_PACKAGE to listOf("cred.mail.password.*", "cred.mail.token.*", "cred.mail.gateway_token")),
            // #1693 batch 10: Cloud Mail files OAuth sign-in requests with Cloud Account (which runs the device grant) and takes the tokens it filed.
            17 to mapOf(MAIL_PACKAGE to listOf(com.diegonmarcos.superapp.fleetendpoints.MailOAuthContract.GRANT_REQUEST, "cred.mail.oauth.*")),
        )
        const val DRIVE_PACKAGE = "com.diegonmarcos.clouddrive"
        const val KEYBOARD_PACKAGE = "com.diegonmarcos.cloudkeyboard"
        const val VAULT_PACKAGE = "com.diegonmarcos.cloudvault"
        const val MAIL_PACKAGE = "com.diegonmarcos.comms.mail"
        const val SEED_VERSION = 17
        val DEFAULT_GRANTS: Map<String, List<String>> = DEFAULT_GRANTS_BY_VERSION.values
            .fold(emptyMap<String, List<String>>()) { acc, m -> acc + m.mapValues { (k, v) -> acc[k].orEmpty() + v } }

        fun parse(text: String): JSONObject = runCatching { JSONObject(text.ifBlank { "{}" }) }.getOrDefault(JSONObject())

        fun leaves(o: JSONObject): Int = o.keys().asSequence().filter { !it.startsWith("_") }.sumOf { k ->
            val v = o.opt(k); if (v is JSONObject) leaves(v) else 1
        }
    }
}

/**
 * Per-app grants: `{"<package>": ["<key or pattern>", …]}`. A pattern is an exact key (`fleet.bearer`,
 * `dagu_prefs.bearer_token`), a prefix (`auth.*`) or `*`. Revocable key by key or whole. Persisted by [save].
 */
class SecretGrants(private val load: () -> JSONObject, private val save: (JSONObject) -> Unit) {
    fun all(): Map<String, List<String>> = load().let { o ->
        o.keys().asSequence().sorted().associateWith { p -> o.optJSONArray(p)?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty() }
    }

    fun allow(pkg: String, key: String): Boolean = all()[pkg].orEmpty().any { matches(it, key) }

    fun grant(pkg: String, pattern: String) {
        val o = load(); val a = o.optJSONArray(pkg) ?: JSONArray().also { o.put(pkg, it) }
        if ((0 until a.length()).none { a.getString(it) == pattern }) a.put(pattern)
        save(o)
    }

    /** One pattern, or every grant of [pkg] when [pattern] is null. */
    fun revoke(pkg: String, pattern: String? = null) {
        val o = load()
        if (pattern == null) o.remove(pkg) else o.optJSONArray(pkg)?.let { a ->
            val keep = (0 until a.length()).map { a.getString(it) }.filter { it != pattern }
            if (keep.isEmpty()) o.remove(pkg) else o.put(pkg, JSONArray(keep))
        }
        save(o)
    }

    fun asJson(): JSONObject = load()
    fun replace(o: JSONObject) = save(o)

    companion object {
        fun matches(pattern: String, key: String) = pattern == "*" || pattern == key || (pattern.endsWith(".*") && key.startsWith(pattern.dropLast(1)))
    }
}

/** The bundle's cipher: `{"kind","v","kdf","iter","salt","iv","ct"}`, the header authenticated, the key never stored. */
object BundleCrypto {
    class Refused(why: String) : Exception(why)

    const val ITER = 200_000
    private const val KDF = "pbkdf2-hmac-sha256"

    private fun key(pass: CharArray, salt: ByteArray, iter: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, iter, 256)
        try { return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES") } finally { spec.clearPassword() }
    }

    private fun aad(v: Int) = "${AccountVault.KIND}|$v".toByteArray()
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)

    fun encrypt(plain: String, pass: CharArray, iter: Int = ITER): String {
        require(pass.isNotEmpty()) { "a bundle needs a passphrase" }
        val rnd = SecureRandom(); val salt = ByteArray(16).also(rnd::nextBytes); val iv = ByteArray(12).also(rnd::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pass, salt, iter), GCMParameterSpec(128, iv)); updateAAD(aad(AccountVault.VERSION)) }
        return JSONObject().put("kind", AccountVault.KIND).put("v", AccountVault.VERSION).put("kdf", KDF).put("iter", iter)
            .put("salt", b64(salt)).put("iv", b64(iv)).put("ct", b64(c.doFinal(plain.toByteArray()))).toString()
    }

    fun decrypt(text: String, pass: CharArray): String {
        val h = runCatching { JSONObject(text) }.getOrNull() ?: throw Refused("not a bundle: no JSON header")
        if (h.optString("kind") != AccountVault.KIND) throw Refused("not a Cloud Account bundle")
        val v = h.optInt("v", 0)
        if (v < 1) throw Refused("the bundle has no version")
        if (v > AccountVault.VERSION) throw Refused("the bundle is version $v, this Cloud Account reads up to ${AccountVault.VERSION}: update Cloud Account")
        if (h.optString("kdf") != KDF) throw Refused("unknown key derivation")
        val iter = h.optInt("iter", 0)
        if (iter < 100_000 || iter > 5_000_000) throw Refused("unreasonable key-derivation cost")
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(pass, unb64(h.getString("salt")), iter), GCMParameterSpec(128, unb64(h.getString("iv")))); updateAAD(aad(v))
            }
            String(c.doFinal(unb64(h.getString("ct"))))
        } catch (e: java.security.GeneralSecurityException) {
            throw Refused("wrong passphrase, or the bundle is damaged")
        } catch (e: org.json.JSONException) {
            throw Refused("the bundle is damaged")
        }
    }
}
