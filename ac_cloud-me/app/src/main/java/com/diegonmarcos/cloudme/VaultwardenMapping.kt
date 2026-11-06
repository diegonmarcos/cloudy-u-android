package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject

/**
 * Maps a Vaultwarden / Bitwarden export into the authenticator-first tables the Buro > Secrets
 * pages draw. Pure Kotlin over org.json — no Android, no I/O, no secret ever logged — so it is
 * unit-tested with SYNTHETIC fixtures only ([VaultwardenMappingTest]); the live bytes are the
 * decrypted me-secrets pack, read on-device and handed in here already in memory.
 *
 * The owner's Secrets section has two groups and seven tables:
 *   Web: Logins, TOTP, Passkeys       CLI: SSH, API tokens, JWT, Certificates
 *
 * Two rules the owner set, encoded here rather than in the UI:
 *  1. the FIRST column of every table is the AUTHENTICATOR — the account / identity the secret
 *     authenticates as (a login's username, a TOTP's account, an SSH key's name). The remaining
 *     columns are that secret's fields.
 *  2. a secret value, and any huge value (a long token, a PEM certificate or a private key), is
 *     NEVER inline: its cell is flagged [SecretCell.reveal] so the UI shows a file-link that opens
 *     a popup to view / copy / export the single value. Identifiers (username, URL, issuer) stay
 *     inline. This is also why nothing secret lands in a dense table that could be screenshotted.
 *
 * Scope mirrors Vaultwarden's folders: [folders] lists them and [table] takes an optional folder
 * so the UI's "All | By Folders" control is a filter here, not a second data path.
 */
object VaultwardenMapping {

    enum class SecretType(val id: String, val label: String, val cli: Boolean) {
        LOGINS("logins", "Logins", false),
        TOTP("totp", "TOTP", false),
        PASSKEYS("passkeys", "Passkeys", false),
        SSH("ssh", "SSH keys", true),
        APITOKENS("apitokens", "API tokens", true),
        JWT("jwt", "JWT", true),
        CERTS("certs", "Certificates", true);

        companion object {
            fun of(id: String?): SecretType? = entries.firstOrNull { it.id == id }
        }
    }

    /** One cell. [reveal] = a secret or huge value: shown as a file-link popup, never inline. */
    data class SecretCell(val label: String, val value: String, val reveal: Boolean)

    /** One table row: the authenticator is [cells].first(); [folder] is its Vaultwarden folder (null = none). */
    data class SecretRow(val folder: String?, val cells: List<SecretCell>) {
        val authenticator: String get() = cells.firstOrNull()?.value.orEmpty()
    }

    data class SecretTable(val type: SecretType, val columns: List<String>, val rows: List<SecretRow>)

    /** Bitwarden item types that matter here. */
    private const val TYPE_LOGIN = 1
    private const val TYPE_SSHKEY = 5

    /** A value this long, or spanning lines, is "huge": popup only, never inline. */
    const val HUGE = 100
    private val JWT_RE = Regex("""eyJ[\w-]+\.eyJ[\w-]+\.[\w-]+""")

    private fun huge(v: String) = v.length > HUGE || v.contains('\n')
    private fun id(label: String, v: String) = SecretCell(label, v, reveal = huge(v))     // identifier: inline unless huge
    private fun secret(label: String, v: String) = SecretCell(label, v, reveal = true)    // secret: always popup

    private class Export(root: JSONObject) {
        val folderById: Map<String, String> = buildMap {
            val arr = root.optJSONArray("folders") ?: JSONArray()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let {
                val fid = it.optString("id"); val name = it.optString("name")
                if (fid.isNotBlank() && name.isNotBlank()) put(fid, name)
            }
        }
        val items: List<JSONObject> = (root.optJSONArray("items") ?: JSONArray()).let { a ->
            (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        fun folder(o: JSONObject): String? = o.optString("folderId").takeIf { it.isNotBlank() }?.let { folderById[it] }
    }

    /** Every folder name in the export, in declared order (for the "By Folders" scope). */
    fun folders(exportJson: String): List<String> =
        runCatching { Export(JSONObject(exportJson)).folderById.values.toList() }.getOrDefault(emptyList())

    /** The seven tables, each already authenticator-first. [onlyFolder] limits rows to one folder. */
    fun tables(exportJson: String, onlyFolder: String? = null): List<SecretTable> {
        val ex = runCatching { Export(JSONObject(exportJson)) }.getOrNull() ?: return emptyList()
        return SecretType.entries.map { table(ex, it, onlyFolder) }
    }

    fun table(exportJson: String, type: SecretType, onlyFolder: String? = null): SecretTable {
        val ex = runCatching { Export(JSONObject(exportJson)) }.getOrNull()
            ?: return SecretTable(type, columnsFor(type), emptyList())
        return table(ex, type, onlyFolder)
    }

    private fun columnsFor(t: SecretType): List<String> = when (t) {
        SecretType.LOGINS -> listOf("Account", "Username", "URL", "Password")
        SecretType.TOTP -> listOf("Account", "Issuer", "Seed")
        SecretType.PASSKEYS -> listOf("Account", "Relying party", "Credential")
        SecretType.SSH -> listOf("Key", "Fingerprint", "Public key", "Private key")
        SecretType.APITOKENS -> listOf("Service", "Scope", "Token")
        SecretType.JWT -> listOf("Subject", "Issuer", "Token")
        SecretType.CERTS -> listOf("Name", "Subject", "Certificate", "Key")
    }

    private fun table(ex: Export, type: SecretType, onlyFolder: String?): SecretTable {
        val rows = ex.items.mapNotNull { row(ex, it, type) }
            .filter { onlyFolder == null || it.folder == onlyFolder }
        return SecretTable(type, columnsFor(type), rows)
    }

    private fun field(o: JSONObject, vararg names: String): String {
        val arr = o.optJSONArray("fields") ?: return ""
        for (i in 0 until arr.length()) {
            val f = arr.optJSONObject(i) ?: continue
            if (f.optString("name").lowercase() in names.map { it.lowercase() }) return f.optString("value")
        }
        return ""
    }

    private fun anyFieldValue(o: JSONObject, pred: (String) -> Boolean): String {
        val arr = o.optJSONArray("fields") ?: return ""
        for (i in 0 until arr.length()) {
            val v = arr.optJSONObject(i)?.optString("value").orEmpty()
            if (pred(v)) return v
        }
        return ""
    }

    private fun fieldNameMatches(o: JSONObject, pred: (String) -> Boolean): Boolean {
        val arr = o.optJSONArray("fields") ?: return false
        for (i in 0 until arr.length()) if (pred(o.optJSONArray("fields")!!.optJSONObject(i)?.optString("name")?.lowercase().orEmpty())) return true
        return false
    }

    private fun looksSsh(o: JSONObject): Boolean =
        o.optInt("type") == TYPE_SSHKEY || o.has("sshKey") ||
            o.optString("notes").let { it.contains("BEGIN OPENSSH PRIVATE KEY") || it.contains("ssh-ed25519") || it.contains("ssh-rsa") }

    private fun looksCert(o: JSONObject): Boolean =
        !looksSsh(o) && (o.optString("notes").contains("BEGIN CERTIFICATE") ||
            anyFieldValue(o) { it.contains("BEGIN CERTIFICATE") || it.contains("PRIVATE KEY") }.isNotBlank())

    private fun jwtValue(o: JSONObject): String =
        anyFieldValue(o) { JWT_RE.containsMatchIn(it) }.ifBlank {
            o.optString("notes").takeIf { JWT_RE.containsMatchIn(it) }?.let { JWT_RE.find(it)!!.value } ?: ""
        }

    private fun looksApiToken(o: JSONObject): Boolean =
        !looksSsh(o) && !looksCert(o) && jwtValue(o).isBlank() &&
            fieldNameMatches(o) { it.contains("token") || it == "api_key" || it == "apikey" || it.contains("api key") || it == "pat" }

    private fun row(ex: Export, o: JSONObject, type: SecretType): SecretRow? {
        val login = o.optJSONObject("login")
        val name = o.optString("name")
        val folder = ex.folder(o)
        fun of(cells: List<SecretCell>) = SecretRow(folder, cells)
        return when (type) {
            SecretType.TOTP -> {
                val seed = login?.optString("totp").orEmpty()
                if (seed.isBlank()) null else of(listOf(
                    id("Account", login?.optString("username").orEmpty().ifBlank { name }),
                    id("Issuer", field(o, "issuer").ifBlank { name }),
                    secret("Seed", seed),
                ))
            }
            SecretType.PASSKEYS -> {
                val creds = login?.optJSONArray("fido2Credentials")
                if (creds == null || creds.length() == 0) null else of(listOf(
                    id("Account", creds.optJSONObject(0)?.optString("userDisplayName").orEmpty().ifBlank { name }),
                    id("Relying party", creds.optJSONObject(0)?.optString("rpId").orEmpty()),
                    secret("Credential", creds.optJSONObject(0)?.optString("credentialId").orEmpty()),
                ))
            }
            SecretType.SSH -> {
                if (!looksSsh(o)) null else {
                    val k = o.optJSONObject("sshKey")
                    of(listOf(
                        id("Key", name),
                        id("Fingerprint", k?.optString("keyFingerprint").orEmpty().ifBlank { field(o, "fingerprint") }),
                        id("Public key", k?.optString("publicKey").orEmpty()),
                        secret("Private key", k?.optString("privateKey").orEmpty().ifBlank { o.optString("notes") }),
                    ))
                }
            }
            SecretType.CERTS -> {
                if (!looksCert(o)) null else of(listOf(
                    id("Name", name),
                    id("Subject", field(o, "subject", "cn")),
                    secret("Certificate", o.optString("notes").ifBlank { anyFieldValue(o) { it.contains("BEGIN CERTIFICATE") } }),
                    secret("Key", anyFieldValue(o) { it.contains("PRIVATE KEY") }),
                ))
            }
            SecretType.JWT -> {
                val tok = jwtValue(o)
                if (tok.isBlank()) null else of(listOf(
                    id("Subject", field(o, "subject", "sub").ifBlank { login?.optString("username").orEmpty().ifBlank { name } }),
                    id("Issuer", field(o, "issuer", "iss").ifBlank { name }),
                    secret("Token", tok),
                ))
            }
            SecretType.APITOKENS -> {
                if (!looksApiToken(o)) null else of(listOf(
                    id("Service", name),
                    id("Scope", field(o, "scope").ifBlank { login?.optString("username").orEmpty() }),
                    secret("Token", anyFieldValue(o) { it.isNotBlank() }.ifBlank { login?.optString("password").orEmpty() }),
                ))
            }
            SecretType.LOGINS -> {
                // the default bucket: a login with a password that is not a more specific CLI secret
                val pw = login?.optString("password").orEmpty()
                if (o.optInt("type") != TYPE_LOGIN || pw.isBlank() || looksSsh(o) || looksCert(o) || jwtValue(o).isNotBlank() || looksApiToken(o)) null
                else of(listOf(
                    id("Account", login?.optString("username").orEmpty().ifBlank { name }),
                    id("Username", login?.optString("username").orEmpty()),
                    id("URL", login?.optJSONArray("uris")?.optJSONObject(0)?.optString("uri").orEmpty()),
                    secret("Password", pw),
                ))
            }
        }
    }
}
