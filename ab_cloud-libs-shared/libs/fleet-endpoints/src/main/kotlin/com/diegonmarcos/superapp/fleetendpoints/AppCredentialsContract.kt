package com.diegonmarcos.superapp.fleetendpoints

/**
 * #1693 THE APP CREDENTIALS CONTRACT: a token, password or key that an app used to ask for on its own page is typed in Cloud
 * Account (App credentials) and the app reads it back here. Wire: `content://<account package>.accountdata`, method [METHOD],
 * `arg` = the credential id (a [CATALOG] id, or a prefixed one such as `git.host.<name>`); answer [KEY_OK] and the value under
 * [KEY_VALUE] ("" when nothing is filed yet). Default deny per credential: the caller needs the grant [grant] (`cred.<id>`,
 * revocable in Cloud Account's Secrets) on top of the fleet signature permission the provider checks first. The value rides
 * only this answer: nothing here logs one, and [Credential.toString] hides it. Pure over the JVM, so both ends and the tests share it.
 */
object AppCredentialsContract {
    const val ACCOUNT_PACKAGE = FleetEndpointsContract.ACCOUNT_PACKAGE
    const val AUTHORITY = FleetEndpointsContract.AUTHORITY
    const val METHOD = "app_credential"
    const val KEY_OK = FleetEndpointsContract.KEY_OK
    const val KEY_ERROR = FleetEndpointsContract.KEY_ERROR
    const val KEY_VALUE = "value"
    /** The provider name Cloud Account's sign-in pages and state answer to for app credentials (page `credentials`). */
    const val PROVIDER = "credentials"
    const val PAGE = "credentials"
    const val GRANT_PREFIX = "cred."
    const val MAX_VALUE = 16 * 1024

    /**
     * One credential an app may ask Cloud Account for. [id] ends with `.` when it is a prefix (`git.host.` + a host name);
     * [packages] are the apps granted it by default (seeded once, revocable); [secret] hides the field on the page.
     */
    data class Entry(val id: String, val label: String, val hint: String, val packages: List<String>, val secret: Boolean = true) {
        val prefix: Boolean get() = id.endsWith(".")
    }

    const val PKG_SUPERAPP = "com.diegonmarcos.superapp"
    const val PKG_DRIVE = "com.diegonmarcos.clouddrive"
    const val PKG_TASKS = "com.diegonmarcos.cloudagenda"
    const val PKG_C3 = "com.diegonmarcos.cloudc3"

    /** Grows with each migrated app (each batch adds its entries and a seed version in AccountVault). */
    val CATALOG: List<Entry> = listOf(
        Entry("git.github_token", "GitHub token", "personal access token for the in-app Git page (repo scope)", listOf(PKG_DRIVE, "com.diegonmarcos.cloudkgstore")),
        Entry("git.host.", "Self-hosted git host token", "one per host: id git.host.<host id>; token or password for that host", listOf(PKG_DRIVE)),
        Entry("git.repo.", "Per-repository sync token", "one per synced repository: id git.repo.<repo>; the https token or password", listOf(PKG_DRIVE)),
        Entry("git.ssh.", "Per-repository ssh passphrase", "one per synced repository: id git.ssh.<repo>; the key's passphrase", listOf(PKG_DRIVE)),
        Entry("tasks.caldav_password", "Cloud Tasks CalDAV password", "the password for the CalDAV server set up in Cloud Tasks > Configs", listOf(PKG_TASKS)),
        Entry("tasks.jmap_secret", "Cloud Tasks mail agent secret", "the JMAP bearer token or Authelia session of the mail agent (Cloud Tasks > Settings > Agent)", listOf(PKG_TASKS)),
        Entry("mattermost.login", "Mattermost email or username", "the account that signs in to the Mattermost server (SuperApp > Chat)", listOf(PKG_SUPERAPP), secret = false),
        Entry("mattermost.password", "Mattermost password", "the password of that Mattermost account", listOf(PKG_SUPERAPP)),
        Entry("dagu.token", "Dagu bearer token", "the Authelia bearer token of the Dagu server (SuperApp > C3 > Dagu, Cloud C3)", listOf(PKG_SUPERAPP, PKG_C3)),
    )

    /** The name part of a prefixed id made safe: letters, digits, `_` and `-` only, at most 60. */
    fun slug(raw: String): String = raw.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("").take(60).ifBlank { "_" }

    fun validId(id: String?): Boolean = !id.isNullOrBlank() && id.length <= 96 && id.all { it.isLetterOrDigit() || it in "._-" } && !id.startsWith(".") && !id.contains("..")

    /** The catalogue entry an id belongs to (exact, or the prefix entry), or null: an unknown id is refused. */
    fun entry(id: String?): Entry? = if (!validId(id)) null else CATALOG.firstOrNull { if (it.prefix) id!!.startsWith(it.id) && id.length > it.id.length else it.id == id }

    fun grant(id: String) = GRANT_PREFIX + id
    /** The grant pattern that covers a whole prefix entry (`cred.git.host.*`). */
    fun grantPattern(e: Entry) = GRANT_PREFIX + (if (e.prefix) e.id + "*" else e.id)

    /** Where Cloud Account files it in its vault: one flat key, so a dot in a host name never nests. */
    fun vaultPath(id: String) = "appcred." + id.replace('.', '_')

    /** The grants a package is seeded with by default. */
    fun defaultGrants(pkg: String): List<String> = CATALOG.filter { pkg in it.packages }.map { grantPattern(it) }

    /** What an app shows when Cloud Account cannot answer: a sentence naming the fix, never a value. */
    fun refusal(error: String?): String = "Cloud Account did not hand over the credential: ${error.orEmpty().ifBlank { "no reason given" }}"
    fun missing(label: String) = "$label is not set: enter it in Cloud Account > App credentials"

    /** A read credential. [toString] never carries the value. */
    data class Credential(val id: String, val value: String, val line: String) {
        val present: Boolean get() = value.isNotBlank()
        override fun toString() = "Credential(id=$id, value=${if (present) "<redacted>" else "none"}, line=$line)"
    }
}
