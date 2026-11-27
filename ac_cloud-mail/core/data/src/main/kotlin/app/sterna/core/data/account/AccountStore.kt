package app.sterna.core.data.account

import android.content.Context
import android.util.Base64
import android.util.Log
import app.sterna.core.data.crypto.KeystoreCrypto
import app.sterna.core.jmap.JmapException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** One end of an IMAP/SMTP connection. */
data class MailEndpoint(
    val host: String,
    val port: Int,
    val security: ConnectionSecurity,
)

/**
 * How an account authenticates. API_TOKEN is a server-generated Bearer token, stored encrypted in
 */
enum class AuthType { BASIC, OAUTH, API_TOKEN }

/**
 * OAuth material for an account (present when [AccountCredentials.oauth] is set). The refresh token
 * is the long-lived secret (stored encrypted); the access token is refreshed on demand.
 */
data class OAuthCredentials(
    val accessToken: String,
    val refreshToken: String,
    val accessExpiresAtMillis: Long,
    val tokenEndpoint: String,
    val clientId: String,
) {
    /**
     * Masked on purpose: the synthesised data-class toString() prints both tokens verbatim, so an
     * interpolated log line would write bearer material to logcat.
     */
    override fun toString(): String =
        "OAuthCredentials(accessToken=***, refreshToken=***, " +
            "accessExpiresAtMillis=$accessExpiresAtMillis, tokenEndpoint=$tokenEndpoint, clientId=$clientId)"
}

/** A configured account plus its (decrypted) secret, used to build auth. */
data class AccountCredentials(
    val server: String,
    val username: String,
    val password: String,
    val id: String = "",
    val protocol: MailProtocol = MailProtocol.JMAP,
    /**
     * The server-side JMAP account id to pin API calls to (RFC 8620 §1.6.2). Non-null for a linked
     */
    val jmapAccountId: String? = null,
    val imap: MailEndpoint? = null,
    val smtp: MailEndpoint? = null,
    /** Non-null for OAuth accounts; when set, prefer Bearer auth over the password. */
    val oauth: OAuthCredentials? = null,
    /** For API_TOKEN accounts [password] holds the token, sent as a Bearer header. */
    val authType: AuthType = AuthType.BASIC,
) {
    /**
     * Masked on purpose: [password] holds the account password (or an API token), which the
     * synthesised toString() would print verbatim into any log line interpolating the object.
     */
    override fun toString(): String =
        "AccountCredentials(id=$id, server=$server, username=$username, password=***, " +
            "protocol=$protocol, jmapAccountId=$jmapAccountId, imap=$imap, smtp=$smtp, " +
            "oauth=$oauth, authType=$authType)"
}

/**
 * A mail-capable JMAP account found in a login's session (RFC 8620 §1.6.2): its server account id
 * and display name. Primary-first when passed to [AccountStore.reconcileLinkedAccounts].
 */
data class DiscoveredMailAccount(val jmapAccountId: String, val name: String)

/**
 * The pure add/prune decision behind [AccountStore.reconcileLinkedAccounts]. Kept free of storage
 * and Android so the revocation prune stays unit-testable.
 */
data class LinkedAccountsDiff(
    /** The login's own JMAP account id, to pin on first discovery; null once already pinned. */
    val pinPrimaryId: String? = null,
    /** Newly-granted accounts to mint a linked [StoredAccount] for. */
    val toAdd: List<DiscoveredMailAccount> = emptyList(),
    /** Linked [StoredAccount.id]s whose server account vanished from the session (revoked). */
    val prunedIds: List<String> = emptyList(),
) {
    fun isEmpty(): Boolean = pinPrimaryId == null && toAdd.isEmpty() && prunedIds.isEmpty()
}

/**
 * Diff [existingLinked] against [discovered], the session's mail accounts primary-first. A session
 */
fun diffLinkedAccounts(
    login: StoredAccount,
    existingLinked: List<StoredAccount>,
    discovered: List<DiscoveredMailAccount>,
    probes: Map<String, Result<*>>,
): LinkedAccountsDiff {
    val primary = discovered.firstOrNull() ?: return LinkedAccountsDiff()
    // Eviction reads the RAW list, admission the probe-filtered one — never the other way round.
    val subs = discovered.drop(1)
    val admissible = retainReachableMailAccounts(discovered, probes).drop(1)
    val trackedJmapIds = existingLinked.mapNotNull { it.jmapAccountId }.toSet()
    val liveSubIds = subs.map { it.jmapAccountId }.toSet()
    // Self-healing: two linked records tracking the same server account under one login are
    // duplicates (a leftover of the pre-lock reconcile write race) — keep the oldest (records are
    // appended, so list order is age order) and prune the rest like a revocation.
    val duplicateIds = existingLinked
        .filter { it.jmapAccountId != null }
        .groupBy { it.jmapAccountId }
        .values
        .flatMap { it.drop(1) }
        .map { it.id }
    val revokedIds = existingLinked.filter { it.jmapAccountId !in liveSubIds }.map { it.id }
    return LinkedAccountsDiff(
        pinPrimaryId = primary.jmapAccountId.takeIf { login.jmapAccountId == null },
        // Skip the login's own account and ones already tracked.
        toAdd = admissible.filter {
            it.jmapAccountId != primary.jmapAccountId && it.jmapAccountId !in trackedJmapIds
        },
        prunedIds = (revokedIds + duplicateIds).distinct(),
    )
}

/**
 * Codeberg #129: which of a session's [discovered] mail accounts may become accounts at all. A
 */
internal fun retainReachableMailAccounts(
    discovered: List<DiscoveredMailAccount>,
    probes: Map<String, Result<*>>,
): List<DiscoveredMailAccount> = discovered.filterIndexed { index, candidate ->
    if (index == 0) return@filterIndexed true
    val failure = probes[candidate.jmapAccountId]?.exceptionOrNull() ?: return@filterIndexed true
    (failure as? JmapException)?.errorType != "forbidden"
}

/**
 * The account list as an observable value, behind [AccountStore.accountsFlow]. Split out of the
 */
internal class AccountsState(initial: List<StoredAccount>) {
    private val state = MutableStateFlow(initial)
    val flow: StateFlow<List<StoredAccount>> = state.asStateFlow()

    /** Publish the list that was just written to storage. */
    fun publish(accounts: List<StoredAccount>) {
        state.value = accounts
    }
}

/**
 * Persists one or more accounts. Metadata is JSON; each password is encrypted via [KeystoreCrypto]
 */
class AccountStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * #1869 decrypted secrets, memory only, keyed by account id AND the exact stored ciphertext: a write stores a new
     * ciphertext (fresh IV), so a changed, cleared or removed secret can never be answered from here. Before this every
     * [credentials] call paid an AndroidKeyStore round trip plus an AES-GCM decrypt, several times per opened message.
     */
    private val secretMemo = java.util.concurrent.ConcurrentHashMap<String, Pair<String, String>>()

    // Tells an empty account list apart from a list that would not decode, and blocks writes in the
    // second case. Declared before [live] because the seeding read below already goes through it.
    // Handed THIS store's parser and writer: a gate given a decoder that never fails never refuses.
    private val gate = AccountBlobGate(
        decode = { json.decodeFromString<List<StoredAccount>>(it) },
        encode = { json.encodeToString(it) },
    )

    // Seeded from storage at construction (which also runs the legacy single-account migration),
    // then kept current by [saveAccounts] — the single choke point every list mutator goes through.
    private val live = AccountsState(accounts())

    // ---- accounts ----

    /**
     * The account list as a live value, for screens that display it. The prefs blob is per-process
     */
    val accountsFlow: StateFlow<List<StoredAccount>> get() = live.flow

    /**
     * The stored accounts, or an empty list — including when the blob could not be decoded. The
     */
    fun accounts(): List<StoredAccount> {
        migrateIfNeeded()
        return gate.read(prefs.getString(KEY_ACCOUNTS, null))
    }

    fun hasAccount(): Boolean = accounts().isNotEmpty()

    /**
     * Whether the stored account list could not be read — the one thing [accounts] cannot say,
     */
    fun accountsUnreadable(): Boolean {
        accounts()
        return gate.blobUnreadable
    }

    fun currentId(): String? {
        val list = accounts()
        val current = prefs.getString(KEY_CURRENT, null)
        return when {
            list.any { it.id == current } -> current
            else -> list.firstOrNull()?.id
        }
    }

    fun currentAccount(): StoredAccount? = accounts().firstOrNull { it.id == currentId() }

    @Synchronized
    fun setCurrent(id: String) {
        if (accounts().any { it.id == id }) prefs.edit().putString(KEY_CURRENT, id).apply()
    }

    /** Add an account (encrypting its password, or an API token, identically) and make it current.
     *  Returns its id — the id of the account ALREADY stored under the same identity when there is
     *  one ([addOrRefresh]), so adding the same mailbox twice refreshes one row. */
    @Synchronized
    fun add(
        server: String,
        username: String,
        password: String,
        accountName: String = "",
        protocol: MailProtocol = MailProtocol.JMAP,
        authType: AuthType = AuthType.BASIC,
        imapHost: String = "",
        imapPort: Int = 993,
        imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
        smtpHost: String = "",
        smtpPort: Int = 465,
        smtpSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
    ): String {
        val id = UUID.randomUUID().toString()
        val account = StoredAccount(
            id = id,
            server = server.trim(),
            username = username.trim(),
            accountName = accountName,
            protocol = protocol,
            authType = authType,
            imapHost = imapHost.trim(),
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            smtpHost = smtpHost.trim(),
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
        )
        return addOrRefresh(account, password)
    }

    /**
     * Add a JMAP account authenticated via OAuth and make it current. The refresh token is encrypted
     */
    @Synchronized
    fun addOAuth(
        server: String,
        username: String,
        accountName: String,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
        protocol: MailProtocol = MailProtocol.JMAP,
        imapHost: String = "",
        imapPort: Int = 993,
        imapSecurity: ConnectionSecurity = ConnectionSecurity.TLS,
        smtpHost: String = "",
        smtpPort: Int = 587,
        smtpSecurity: ConnectionSecurity = ConnectionSecurity.STARTTLS,
        documentHost: String = "",
    ): String {
        val id = UUID.randomUUID().toString()
        val account = StoredAccount(
            id = id,
            server = server.trim(),
            username = username.trim(),
            accountName = accountName,
            protocol = protocol,
            authType = AuthType.OAUTH,
            oauthAccessToken = accessToken,
            oauthAccessExpiresAt = accessExpiresAtMillis,
            oauthTokenEndpoint = tokenEndpoint,
            oauthClientId = clientId,
            imapHost = imapHost,
            imapPort = imapPort,
            imapSecurity = imapSecurity,
            smtpHost = smtpHost,
            smtpPort = smtpPort,
            smtpSecurity = smtpSecurity,
        )
        // The other key THIS route has filed this address under: 1.5.0/1.5.1 stored the host the
        // OAuth document came from, this version the host it resolves. Under the current key alone
        // such an account would take a SECOND line at the first re-authentication — the fix for #55
        // manufacturing the duplicate of #55.
        val inherited = if (documentHost.isBlank()) null else accountKeyOf(protocol, documentHost, imapHost, username)
        return addOrRefresh(account, refreshToken, alsoRecognisedBy = listOfNotNull(inherited))
    }

    /**
     * Store [proven] as a new login, or refresh the login already carrying the same identity, and
     */
    private fun addOrRefresh(
        proven: StoredAccount,
        secret: String,
        alsoRecognisedBy: List<AccountKey?> = emptyList(),
    ): String {
        val list = accounts()
        val existing = resolveExistingLoginAmong(list, listOf(accountKeyOf(proven)) + alsoRecognisedBy)
        val id = existing?.id ?: proven.id
        writePassword(id, secret)
        val stored = if (existing == null) {
            list + proven
        } else {
            list.map { if (it.id == id) refreshedWith(it, proven) else it }
        }
        saveAccounts(stored)
        prefs.edit().putString(KEY_CURRENT, id).apply()
        return id
    }

    /**
     * Persist freshly minted OAuth tokens after a refresh. A blank [refreshToken] keeps the stored
     * one (some servers don't rotate it). No-op for unknown ids.
     */
    @Synchronized
    fun updateOAuthTokens(id: String, accessToken: String, refreshToken: String, accessExpiresAtMillis: Long) {
        // Tokens live on the login record; a refresh triggered by any sub-account updates the login
        // so every sub-account sharing that login observes the fresh access token.
        val loginId = account(id)?.loginKey() ?: return
        if (refreshToken.isNotBlank()) writePassword(loginId, refreshToken)
        saveAccounts(
            accounts().map {
                if (it.id == loginId) it.copy(oauthAccessToken = accessToken, oauthAccessExpiresAt = accessExpiresAtMillis) else it
            },
        )
    }

    /** Look up a single stored account by id. */
    fun account(id: String): StoredAccount? = accounts().firstOrNull { it.id == id }

    /**
     * Update the editable server settings for an account, preserving its id and inbox metadata.
     * No-op if the id is unknown.
     */
    @Synchronized
    fun updateAccount(
        id: String,
        server: String,
        username: String,
        accountName: String,
        signature: String? = null,
        imapHost: String? = null,
        imapPort: Int? = null,
        imapSecurity: ConnectionSecurity? = null,
        smtpHost: String? = null,
        smtpPort: Int? = null,
        smtpSecurity: ConnectionSecurity? = null,
    ) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        server = server.trim(),
                        username = username.trim(),
                        accountName = accountName.trim(),
                        signature = signature ?: it.signature,
                        imapHost = imapHost?.trim() ?: it.imapHost,
                        imapPort = imapPort ?: it.imapPort,
                        imapSecurity = imapSecurity ?: it.imapSecurity,
                        smtpHost = smtpHost?.trim() ?: it.smtpHost,
                        smtpPort = smtpPort ?: it.smtpPort,
                        smtpSecurity = smtpSecurity ?: it.smtpSecurity,
                    )
                } else {
                    it
                }
            },
        )
    }

    /** Switch how the account authenticates (None = BASIC, Bearer = API_TOKEN); the secret slot is rewritten by the next password / token save. */
    @Synchronized
    fun setAuthType(id: String, authType: AuthType) {
        if (authType == AuthType.OAUTH) return
        saveAccounts(accounts().map { if (it.id == id && it.authType != AuthType.OAUTH) it.copy(authType = authType) else it })
    }

    /** Re-encrypt and store a new password for the account (written under its login slot). */
    @Synchronized
    fun updatePassword(id: String, password: String) {
        val loginId = account(id)?.loginKey() ?: return
        writePassword(loginId, password)
    }

    /** Optional signature for an account (null id = current account); blank if none. */
    fun signature(accountId: String?): String =
        (accountId?.let { account(it) } ?: currentAccount())?.signature.orEmpty()

    /** Sending identities for an account (null id = current); never empty. A linked sub-account
     *  whose own address is unknown falls back to its LOGIN's identities: sends route through the
     *  login's submission anyway (#31), and offering them openly beats a silently wrong From. */
    fun identities(accountId: String?): List<StoredIdentity> {
        val account = (accountId?.let { account(it) } ?: currentAccount()) ?: return emptyList()
        return account.resolvedIdentities().ifEmpty {
            account.loginId?.let { account(it)?.resolvedIdentities() }.orEmpty()
        }
    }

    /** Persist the identity list for an account. */
    @Synchronized
    fun setIdentities(accountId: String, identities: List<StoredIdentity>) {
        saveAccounts(accounts().map { if (it.id == accountId) it.copy(identities = identities) else it })
    }

    /**
     * Persist the identities discovered from the JMAP server (RFC 8621 Identity/get). No-op if the
     * id is unknown or the list is unchanged. Manual [identities] are left untouched.
     */
    @Synchronized
    fun setServerIdentities(accountId: String, identities: List<StoredIdentity>) {
        val current = account(accountId) ?: return
        if (current.serverIdentities == identities) return
        saveAccounts(accounts().map { if (it.id == accountId) it.copy(serverIdentities = identities) else it })
    }

    /** The account's chosen default sending identity id, or null if none set / unknown id. */
    fun defaultIdentityId(accountId: String?): String? =
        (accountId?.let { account(it) } ?: currentAccount())?.defaultIdentityId

    /** Persist (or clear, with null) the account's default sending identity, keyed by identity id.
     *  Serialized like every other writer: the sub-account reconcile rewrites the same list (#31). */
    @Synchronized
    fun setDefaultIdentity(accountId: String, identityId: String?) {
        saveAccounts(accounts().map { if (it.id == accountId) it.copy(defaultIdentityId = identityId) else it })
    }

    /**
     * The identity pre-selected when composing for [accountId]: the stored [defaultIdentityId],
     */
    fun defaultIdentity(accountId: String?): StoredIdentity? =
        (accountId?.let { account(it) } ?: currentAccount())?.defaultIdentity()

    /** The per-account sync window (defaults to 90 days for unknown ids). */
    fun syncWindow(id: String): SyncWindow = account(id)?.syncWindow ?: SyncWindow.DAYS_90

    /** Persist a new sync window for the account. No-op if the id is unknown. */
    @Synchronized
    fun setSyncWindow(id: String, window: SyncWindow) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(syncWindow = window) else it })
    }

    /** Persist the account's accent colour (ARGB), or null for auto. No-op if the id is unknown. */
    @Synchronized
    fun setColor(id: String, color: Int?) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(color = color) else it })
    }

    /** Whether new-mail notifications fire for an account (defaults to true). */
    fun notificationsEnabled(id: String): Boolean = account(id)?.notificationsEnabled ?: true

    /** Enable/disable new-mail notifications for an account. No-op if the id is unknown. */
    @Synchronized
    fun setNotificationsEnabled(id: String, enabled: Boolean) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(notificationsEnabled = enabled) else it })
    }

    /** Whether the app files a copy of a sent message in the account's Sent folder (defaults to true). */
    fun uploadSentCopy(id: String): Boolean = account(id)?.uploadSentCopy ?: true

    /** Enable/disable the app's Sent-folder copy for an account. No-op if the id is unknown. */
    @Synchronized
    fun setUploadSentCopy(id: String, enabled: Boolean) {
        saveAccounts(accounts().map { if (it.id == id) it.copy(uploadSentCopy = enabled) else it })
    }

    /**
     * Whether this account's folder lists hide the folders it is not subscribed to on the server
     * (#174). Defaults to false, including for an unknown id — see [showOnlySubscribedFoldersOf].
     */
    fun showOnlySubscribedFolders(id: String): Boolean = showOnlySubscribedFoldersOf(account(id))

    /** Store the account's subscribed-folders-only choice. No-op if the id is unknown. */
    @Synchronized
    fun setShowOnlySubscribedFolders(id: String, enabled: Boolean) {
        saveAccounts(withShowOnlySubscribedFolders(accounts(), id, enabled))
    }

    /** Extra folders watched for new mail (beyond the Inbox, which is always watched). */
    fun watchedFolders(id: String): Set<String> = account(id)?.watchedFolders ?: emptySet()

    /** Folders whose new-mail notifications are switched off (the Inbox's "Notify about new mail" unticked). */
    fun mutedFolders(id: String): Set<String> = account(id)?.mutedFolders ?: emptySet()

    /** Switch a folder's new-mail notifications off ([muted]) or back on. No-op if the id is unknown. */
    @Synchronized
    fun setFolderMuted(id: String, folderId: String, muted: Boolean) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(mutedFolders = if (muted) it.mutedFolders + folderId else it.mutedFolders - folderId)
                } else {
                    it
                }
            },
        )
    }

    /** Add/remove a folder from the account's watched set. No-op if the id is unknown. */
    @Synchronized
    fun setFolderWatched(id: String, folderId: String, watched: Boolean) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        watchedFolders = if (watched) it.watchedFolders + folderId else it.watchedFolders - folderId,
                    )
                } else {
                    it
                }
            },
        )
    }

    /**
     * The folders of this account's drawer tree the user explicitly folded or unfolded; a folder
     * absent from the map is one nobody decided anything about. Empty for an unknown id.
     */
    fun collapsedFolders(id: String): Map<String, Boolean> = account(id)?.collapsedFolders ?: emptyMap()

    /** Store the user's fold/unfold choice for one folder. No-op if the id is unknown. */
    @Synchronized
    fun setFolderCollapsed(id: String, folderId: String, collapsed: Boolean) {
        saveAccounts(withCollapsedFolder(accounts(), id, folderId, collapsed))
    }

    /**
     * Re-key a FOLDED folder after an IMAP rename (ids are folder paths there), the twin of
     */
    @Synchronized
    fun replaceCollapsedFolder(id: String, oldId: String, newId: String, delimiter: String = "/") {
        saveAccounts(withRenamedCollapsedFolder(accounts(), id, oldId, newId, delimiter))
    }

    /**
     * Re-key a watched folder after an IMAP rename (ids are folder paths there). Also rewrites
     * watched children of [oldId]. No-op for JMAP ids, which are stable across renames.
     */
    @Synchronized
    fun replaceWatchedFolder(id: String, oldId: String, newId: String, delimiter: String = "/") {
        saveAccounts(
            accounts().map { account ->
                if (account.id == id) {
                    account.copy(
                        watchedFolders = account.watchedFolders.map { folder ->
                            when {
                                folder == oldId -> newId
                                folder.startsWith(oldId + delimiter) -> newId + folder.removePrefix(oldId)
                                else -> folder
                            }
                        }.toSet(),
                    )
                } else {
                    account
                }
            },
        )
    }

    /**
     * Persist the account's OpenPGP settings. No-op if the id is unknown.
     */
    @Synchronized
    fun setPgp(
        id: String,
        enabled: Boolean,
        signKeyId: Long,
        publicKey: String,
        encryptByDefault: Boolean,
    ) {
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(
                        pgpEnabled = enabled,
                        pgpSignKeyId = signKeyId,
                        pgpPublicKey = publicKey,
                        pgpEncryptByDefault = encryptByDefault,
                    )
                } else {
                    it
                }
            },
        )
    }

    /**
     * Write the public key read back for [signKeyId] into that account's cache, and only while the
     */
    @Synchronized
    fun setPgpPublicKey(id: String, signKeyId: Long, publicKey: String) {
        saveAccounts(withCachedPgpPublicKey(accounts(), id, signKeyId, publicKey))
    }

    /**
     * Drop the stored OpenPGP signing key of EVERY account, keeping their other PGP settings.
     * Called when the user switches OpenPGP app; see [withoutPgpSignKeys].
     */
    @Synchronized
    fun clearPgpSignKeys() {
        saveAccounts(withoutPgpSignKeys(accounts()))
    }

    /** Remove an account; if it was current, fall back to another (or none). */
    @Synchronized
    fun remove(id: String) {
        // Read BEFORE destroying anything. On an unreadable blob the list reads empty, so a stored
        // id looks unknown — and the encrypted password is the account's only copy of its secret,
        // which no later write-refusal can bring back.
        val list = accounts()
        if (list.none { it.id == id }) return
        prefs.edit().remove(passwordKey(id)).apply()
        val remaining = list.filterNot { it.id == id }
        // The third removal path, and it forgets what it removes like [removeCascading]: a linked
        // share can reach here, and PRIVACY.md promises that removing an account removes what it
        // left behind.
        saveLinkedMemory(forgetRemovedLinked(linkedMemory(), list.filter { it.id == id }))
        saveAccounts(remaining)
        if (currentId() == id || remaining.none { it.id == prefs.getString(KEY_CURRENT, null) }) {
            prefs.edit().putString(KEY_CURRENT, remaining.firstOrNull()?.id).apply()
        }
    }

    /** Remove every account (full reset), including the shared KeyStore key so any leftover
     *  ciphertext (e.g. in a stale prefs file) can no longer be decrypted. */
    @Synchronized
    fun clear() {
        prefs.edit().clear().apply()
        KeystoreCrypto.deleteKey()
        // The file is legitimately gone, so there is no unreadable blob left to protect: lift the
        // refusal, or a reset from that state could never store an account again.
        gate.onStorageWiped()
        // Not a [saveAccounts] path (the whole file goes), so publish the emptied list by hand.
        live.publish(emptyList())
    }

    // ---- current-account convenience (used by existing callers) ----

    fun load(): AccountCredentials? = currentId()?.let { credentials(it) }

    fun credentials(id: String): AccountCredentials? {
        val list = accounts()
        val account = list.firstOrNull { it.id == id } ?: return null
        // A linked sub-account borrows its login's encrypted secret and OAuth tokens: the secret
        // lives under the login id only, and a refresh on the login is observed by every
        // sub-account. Standalone accounts resolve to themselves (loginKey == id).
        val login = list.firstOrNull { it.id == account.loginKey() } ?: account
        // For OAuth accounts the encrypted slot holds the refresh token, not a password.
        val secret = readPassword(login.id) ?: return null
        val oauth = if (login.authType == AuthType.OAUTH) {
            OAuthCredentials(
                accessToken = login.oauthAccessToken,
                refreshToken = secret,
                accessExpiresAtMillis = login.oauthAccessExpiresAt,
                tokenEndpoint = login.oauthTokenEndpoint,
                clientId = login.oauthClientId,
            )
        } else {
            null
        }
        return AccountCredentials(
            server = account.server,
            username = account.username,
            password = if (oauth == null) secret else "",
            id = id,
            protocol = account.protocol,
            jmapAccountId = account.jmapAccountId,
            oauth = oauth,
            // The login's, not the record's: a sub-account authenticates however its login does,
            // and stays correct if the login's auth type changes later.
            authType = login.authType,
            imap = if (account.protocol == MailProtocol.IMAP) {
                MailEndpoint(account.imapHost, account.imapPort, account.imapSecurity)
            } else {
                null
            },
            smtp = if (account.protocol == MailProtocol.IMAP) {
                MailEndpoint(account.smtpHost, account.smtpPort, account.smtpSecurity)
            } else {
                null
            },
        )
    }

    fun allCredentials(): List<AccountCredentials> = accounts().mapNotNull { credentials(it.id) }

    // ---- linked sub-accounts (issue #31) ----

    /** Sub-accounts linked to [loginId] (they share its credential). Empty for a standalone login. */
    fun linkedAccounts(loginId: String): List<StoredAccount> = accounts().filter { it.loginId == loginId }

    /**
     * Reconcile the sub-accounts linked to [loginId] against the mail accounts its JMAP session
     */
    @Synchronized
    fun reconcileLinkedAccounts(
        loginId: String,
        discovered: List<DiscoveredMailAccount>,
        probes: Map<String, Result<*>>,
    ): List<String> {
        val list = accounts()
        val login = list.firstOrNull { it.id == loginId } ?: return emptyList()
        val existingLinked = list.filter { it.loginId == loginId }
        val diff = diffLinkedAccounts(login, existingLinked, discovered, probes)
        if (diff.isEmpty()) return emptyList()

        // The list transformation lives in [reconciledAccounts], where a unit test can execute it.
        // It also carries the pruned/re-granted memory, so a share the server drops for a moment
        // comes back with the settings the user gave it.
        val reconciled = reconciledAccounts(list, login, loginId, diff, linkedMemory()) {
            UUID.randomUUID().toString()
        }
        if (diff.prunedIds.isNotEmpty() && currentId() in diff.prunedIds) {
            prefs.edit().putString(KEY_CURRENT, loginId).apply()
        }
        saveLinkedMemory(reconciled.memory)
        saveAccounts(reconciled.accounts)
        return diff.prunedIds
    }

    /**
     * Remove an account and cascade: removing a login removes the sub-accounts linked to it (their
     */
    @Synchronized
    fun removeCascading(id: String): List<String> {
        val target = account(id) ?: return emptyList()
        val ids = (listOf(id) + if (target.isLinked) emptyList() else linkedAccounts(id).map { it.id }).distinct()
        prefs.edit().apply { ids.forEach { remove(passwordKey(it)) } }.apply()
        // Read the removed records BEFORE the list is filtered: `remaining` holds what SURVIVES and
        // can never name what is going away. Deleting an account IS the user's decision, and the
        // memory must not resurrect its settings on a later re-grant.
        val before = accounts()
        val remaining = before.filterNot { it.id in ids }
        saveLinkedMemory(forgetRemovedLinked(linkedMemory(), before.filter { it.id in ids }))
        saveAccounts(remaining)
        if (currentId() in ids || remaining.none { it.id == prefs.getString(KEY_CURRENT, null) }) {
            prefs.edit().putString(KEY_CURRENT, remaining.firstOrNull()?.id).apply()
        }
        return ids
    }

    // ---- backup export / import (configuration only, never secrets) ----

    /**
     * Accounts as a secret-free snapshot for a settings backup: the encrypted secret slot is never
     */
    fun accountsForBackup(): List<StoredAccount> = accounts()
        .filter { it.authType == AuthType.BASIC && !it.isLinked }
        .map {
            it.copy(
                oauthAccessToken = "",
                oauthAccessExpiresAt = 0,
                inboxId = null,
                inboxName = "Inbox",
                unread = 0,
            )
        }

    /**
     * Merge backed-up account configuration in. An incoming account is added only if no existing
     */
    @Synchronized
    fun importAccounts(incoming: List<StoredAccount>): Int {
        val existing = accounts()
        // One shared identity, in AccountIdentity.kt: a null key means a blank endpoint or
        // username, which is skipped rather than merged with anything.
        val seen = existing.mapNotNull(::accountKeyOf).toMutableSet()
        val added = mutableListOf<StoredAccount>()
        for (a in incoming) {
            val k = accountKeyOf(a) ?: continue
            if (k in seen) continue
            seen += k
            added += a.copy(
                id = UUID.randomUUID().toString(),
                oauthAccessToken = "",
                oauthAccessExpiresAt = 0,
                inboxId = null,
                inboxName = "Inbox",
                unread = 0,
                importPending = true,
            )
        }
        if (added.isEmpty()) return 0
        saveAccounts(existing + added)
        if (existing.isEmpty()) prefs.edit().putString(KEY_CURRENT, added.first().id).apply()
        return added.size
    }

    /** Attach freshly granted OAuth material to an existing (imported, inert) account, making it
     *  live. Optionally corrects the username to the provider's canonical address. Returns false
     *  for an unknown id. */
    @Synchronized
    fun attachOAuth(
        id: String,
        username: String? = null,
        accessToken: String,
        refreshToken: String,
        accessExpiresAtMillis: Long,
        tokenEndpoint: String,
        clientId: String,
    ): Boolean {
        if (accounts().none { it.id == id }) return false
        writePassword(id, refreshToken)
        saveAccounts(
            accounts().map {
                if (it.id == id) it.copy(
                    authType = AuthType.OAUTH,
                    username = username?.trim().takeUnless { u -> u.isNullOrBlank() } ?: it.username,
                    oauthAccessToken = accessToken,
                    oauthAccessExpiresAt = accessExpiresAtMillis,
                    oauthTokenEndpoint = tokenEndpoint,
                    oauthClientId = clientId,
                    importPending = false,
                ) else it
            },
        )
        return true
    }

    /** Accounts still awaiting their one-time import sign-in (inert, imported, not yet dismissed). */
    fun pendingImportAccounts(): List<StoredAccount> =
        accounts().filter { it.importPending && credentials(it.id) == null }

    /** Clear an account's import-pending flag (on a successful sign-in), so it leaves the
     *  "accounts to sign in" list and becomes a normal account. No-op for an unknown id. */
    @Synchronized
    fun setImportPending(id: String, pending: Boolean) {
        if (accounts().none { it.id == id }) return
        saveAccounts(accounts().map { if (it.id == id) it.copy(importPending = pending) else it })
    }

    /** Re-insert a dismissed imported account unchanged (undo of a swipe-dismiss): back on the
     *  "to sign in" list, still inert. No-op if an account with this id already exists. */
    @Synchronized
    fun readdImportedAccount(account: StoredAccount) {
        if (accounts().any { it.id == account.id }) return
        saveAccounts(accounts() + account.copy(importPending = true))
    }

    /** Switch an account to password (BASIC) auth, dropping any OAuth material and its stored slot,
     *  so it stays inert until a password is entered. Used for the OAuth→app-password fallback. */
    @Synchronized
    fun convertToBasicAuth(id: String) {
        if (accounts().none { it.id == id }) return
        prefs.edit().remove(passwordKey(id)).apply()
        saveAccounts(
            accounts().map {
                if (it.id == id) it.copy(
                    authType = AuthType.BASIC,
                    oauthAccessToken = "", oauthAccessExpiresAt = 0,
                    oauthTokenEndpoint = "", oauthClientId = "",
                ) else it
            },
        )
    }

    // [accountName] is the server-derived name; deliberately NOT written back here so a user-chosen
    // display name is never clobbered by a sync. A blank name falls back to the address via label().
    @Synchronized
    fun saveInboxMeta(mailboxId: String, mailboxName: String, @Suppress("UNUSED_PARAMETER") accountName: String, unread: Int) {
        val id = currentId() ?: return
        saveAccounts(
            accounts().map {
                if (it.id == id) {
                    it.copy(inboxId = mailboxId, inboxName = mailboxName, unread = unread)
                } else {
                    it
                }
            },
        )
    }

    fun accountName(): String = currentAccount()?.accountName.orEmpty()

    /** Display label for the current account: its name, or the address if unnamed. */
    fun accountLabel(): String = currentAccount()?.label().orEmpty()
    fun inboxMailboxId(): String? = currentAccount()?.inboxId
    fun inboxMailboxName(): String = currentAccount()?.inboxName ?: "Inbox"
    fun unreadCount(): Int = currentAccount()?.unread ?: 0

    // ---- unified inbox (all accounts) ----

    /** (account id, inbox id) pairs across every account — for reads that must stay account-scoped
     *  even when same-server accounts' mailbox ids collide. The rule itself is [UnifiedInbox.scopes].
     * No bare-id twin exists any more (it was #121 waiting for a caller). */
    fun allInboxScopes(): List<Pair<String, String>> = UnifiedInbox.scopes(accounts())

    /** Combined unread count across every account, for the unified-inbox header. */
    fun totalUnreadCount(): Int = accounts().sumOf { it.unread }

    /**
     * Mirror a drawer-count nudge into the stored inbox snapshot. The JMAP unified header no longer
     */
    @Synchronized
    fun adjustInboxUnread(accountId: String, mailboxId: String, delta: Int) {
        if (delta == 0) return
        val list = accounts()
        if (list.none { it.id == accountId && it.inboxId == mailboxId }) return
        saveAccounts(
            list.map {
                if (it.id == accountId && it.inboxId == mailboxId) {
                    it.copy(unread = (it.unread + delta).coerceAtLeast(0))
                } else {
                    it
                }
            },
        )
    }

    /** Record a specific account's inbox id/name/unread (used by the unified refresh fan-out). */
    @Synchronized
    fun saveInboxMetaFor(accountId: String, mailboxId: String, mailboxName: String, @Suppress("UNUSED_PARAMETER") accountName: String, unread: Int) {
        saveAccounts(
            accounts().map {
                if (it.id == accountId) {
                    it.copy(inboxId = mailboxId, inboxName = mailboxName, unread = unread)
                } else {
                    it
                }
            },
        )
    }

    // ---- push preference ----

    fun pushAllAccounts(): Boolean = prefs.getBoolean(KEY_PUSH_ALL, false)
    fun setPushAllAccounts(value: Boolean) = prefs.edit().putBoolean(KEY_PUSH_ALL, value).apply()

    // ---- app-lock preference ----

    fun appLockEnabled(): Boolean = prefs.getBoolean(KEY_APP_LOCK, false)
    fun setAppLockEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_APP_LOCK, value).apply()

    // ---- last drawer view ----

    /**
     * The view the mail list was last showing, as an OPAQUE string this store never interprets: its
     */
    fun storedView(): String? = prefs.getString(KEY_VIEW, null)

    fun setStoredView(value: String?) = prefs.edit().putString(KEY_VIEW, value).apply()

    // ---- refresh freshness (#178) ----

    /**
     * When each (account, folder) pair was last reconciled successfully, as an OPAQUE string —
     */
    fun refreshFreshness(): String? = prefs.getString(KEY_FRESHNESS, null)

    fun setRefreshFreshness(value: String?) = prefs.edit().putString(KEY_FRESHNESS, value).apply()

    /** #1952: when [accountId]'s list was last reconciled with the server successfully (epoch ms), null when never. Kept for good,
     *  unlike the 30 s [refreshFreshness] register: the stale/offline banner over the cached list says it. */
    fun lastSyncAt(accountId: String?): Long? =
        accountId?.let { prefs.getLong(KEY_LAST_SYNC_PREFIX + it, 0L) }?.takeIf { it > 0L }

    fun setLastSyncAt(accountId: String, at: Long) = prefs.edit().putLong(KEY_LAST_SYNC_PREFIX + accountId, at).apply()

    // ---- internals ----

    /**
     * The one place the account blob is written, through [AccountBlobGate.writeGuarded]: a list
     */
    private fun saveAccounts(list: List<StoredAccount>) {
        val written = gate.writeGuarded(list) { encoded ->
            prefs.edit().putString(KEY_ACCOUNTS, encoded).apply()
        }
        if (written) live.publish(list) else Log.e(TAG, REFUSED_WRITE)
    }

    /**
     * The pruned shared mailboxes' settings, keyed by login (see [rememberPrunedLinked]). Read
     */
    private fun linkedMemory(): Map<String, List<StoredAccount>> = runCatching {
        prefs.getString(KEY_LINKED_MEMORY, null)
            ?.let { json.decodeFromString<Map<String, List<StoredAccount>>>(it) }
            .orEmpty()
    }.getOrDefault(emptyMap())

    /**
     * Store the memory under its own key and deliberately NOT through [AccountBlobGate]: routing
     */
    private fun saveLinkedMemory(memory: Map<String, List<StoredAccount>>) {
        runCatching {
            prefs.edit().putString(KEY_LINKED_MEMORY, json.encodeToString(memory)).apply()
        }
    }

    private fun writePassword(id: String, password: String) {
        val encrypted = Base64.encodeToString(
            KeystoreCrypto.encrypt(password.toByteArray(Charsets.UTF_8), aadFor(id)),
            Base64.NO_WRAP,
        )
        prefs.edit().putString(passwordKey(id), encrypted).apply()
    }

    private fun readPassword(id: String): String? {
        val encrypted = prefs.getString(passwordKey(id), null) ?: return null.also { secretMemo.remove(id) }
        secretMemo[id]?.let { (cipher, plain) -> if (cipher == encrypted) return plain }
        val raw = Base64.decode(encrypted, Base64.NO_WRAP)
        // Current format binds the blob to this account id via AAD.
        runCatching {
            String(KeystoreCrypto.decrypt(raw, aadFor(id)), Charsets.UTF_8)
        }.getOrNull()?.let { secretMemo[id] = encrypted to it; return it }
        // Legacy blob (encrypted before AAD binding): decrypt unbound, then transparently
        // re-write it bound to the account so future reads use the hardened path.
        return runCatching {
            String(KeystoreCrypto.decrypt(raw), Charsets.UTF_8)
        }.getOrNull()?.also { writePassword(id, it) }
    }

    /** AAD tying a password blob to its account slot, so it can't be swapped between accounts. */
    private fun aadFor(id: String) = "pw:$id".toByteArray(Charsets.UTF_8)

    private fun passwordKey(id: String) = "pw_$id"

    /** Migrate a pre-multi-account single account into the accounts list once. Writes KEY_ACCOUNTS
     *  outside [saveAccounts], but needs no publish: the only call that can find anything to
     *  migrate is the [live] seeding read in the constructor. */
    @Synchronized
    private fun migrateIfNeeded() {
        if (prefs.contains(KEY_ACCOUNTS)) return
        val server = prefs.getString(LEGACY_SERVER, null) ?: return
        val username = prefs.getString(LEGACY_USERNAME, null) ?: return
        val passwordEnc = prefs.getString(LEGACY_PASSWORD, null) ?: return
        val id = UUID.randomUUID().toString()
        val account = StoredAccount(
            id = id,
            server = server,
            username = username,
            accountName = prefs.getString(LEGACY_ACCOUNT_NAME, "").orEmpty(),
            inboxId = prefs.getString(LEGACY_INBOX_ID, null),
            inboxName = prefs.getString(LEGACY_INBOX_NAME, "Inbox") ?: "Inbox",
            unread = prefs.getInt(LEGACY_UNREAD, 0),
        )
        prefs.edit()
            .putString(KEY_ACCOUNTS, json.encodeToString(listOf(account)))
            .putString(KEY_CURRENT, id)
            .putString(passwordKey(id), passwordEnc)
            .remove(LEGACY_SERVER)
            .remove(LEGACY_USERNAME)
            .remove(LEGACY_PASSWORD)
            .remove(LEGACY_ACCOUNT_NAME)
            .remove(LEGACY_INBOX_ID)
            .remove(LEGACY_INBOX_NAME)
            .remove(LEGACY_UNREAD)
            .apply()
    }

    internal companion object {
        const val TAG = "AccountStore"
        const val REFUSED_WRITE =
            "refusing to write the account list: it did not decode on the last read, so storing " +
                "this one would replace the real accounts for good. Nothing was changed."
        const val PREFS_NAME = "sterna_account"
        const val KEY_ACCOUNTS = "accounts"
        const val KEY_CURRENT = "current"

        // The drawer view the list was last showing, as an OPAQUE string: `Sel` lives in the :app
        // module, which :core:data does not see. Written and parsed by SelectionMemory.
        const val KEY_VIEW = "current_view"

        // When each (account, folder) pair was last reconciled successfully, as an OPAQUE string,
        // exactly like KEY_VIEW: written and parsed by RefreshFreshness in the :app module.
        const val KEY_FRESHNESS = "refresh_freshness"
        const val KEY_LAST_SYNC_PREFIX = "last_sync_at_"

        // The settings of shared mailboxes the server has pruned, keyed by login, so a re-granted
        // share is not minted factory-fresh (LinkedAccountMemory). Comfort state on its own key: it
        // sits outside the account blob's gate and may be lost without costing an account.
        const val KEY_LINKED_MEMORY = "linked_account_memory"
        const val KEY_PUSH_ALL = "push_all_accounts"
        const val KEY_APP_LOCK = "app_lock_enabled"

        // Legacy single-account keys (migrated on first run).
        const val LEGACY_SERVER = "server"
        const val LEGACY_USERNAME = "username"
        const val LEGACY_PASSWORD = "password_enc"
        const val LEGACY_ACCOUNT_NAME = "account_name"
        const val LEGACY_INBOX_ID = "inbox_id"
        const val LEGACY_INBOX_NAME = "inbox_name"
        const val LEGACY_UNREAD = "unread"
    }
}
