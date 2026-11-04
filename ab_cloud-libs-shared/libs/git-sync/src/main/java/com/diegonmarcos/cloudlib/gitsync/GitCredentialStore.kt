package com.diegonmarcos.cloudlib.gitsync

import com.diegonmarcos.superapp.crash.SafeEncryptedPrefs
import android.content.Context
import android.content.SharedPreferences
import java.io.File

/**
 * Where a repository's SECRET half lives: the HTTPS token or the SSH key
 * passphrase, keyed by [ManagedRepo.id]. Encrypted at rest with the
 * Android-keystore-backed master key; never inside repos.json, never in a
 * git config file (JGit would happily write a token into .git/config's URL if
 * it were embedded there, and that file syncs with backups).
 */
class GitCredentialStore(context: Context) {
    private val prefs: SharedPreferences = SafeEncryptedPrefs.prefs(context, FILE)
    private val knownHosts = File(context.filesDir, "git-sync/known_hosts")

    fun setSecret(repoId: String, secret: String) = prefs.edit().putString(repoId, secret).apply()
    /** #1445 the stored secret of [repoId] (libs:git-ui GitConnect's token); never log it. */
    fun secret(repoId: String): String? = prefs.getString(repoId, null)?.takeIf { it.isNotBlank() }
    fun clear(repoId: String) = prefs.edit().remove(repoId).apply()
    fun hasSecret(repoId: String): Boolean = prefs.contains(repoId)

    /** Move [from]'s secret to [to] (a repository whose path, hence id, changed). No-op when [from] holds none. */
    fun rekey(from: String, to: String) {
        if (from == to) return
        val secret = prefs.getString(from, null) ?: return
        prefs.edit().putString(to, secret).remove(from).apply()
    }

    /** The [GitAuth] the engine needs for [repo], assembled from its public fields plus the stored secret. */
    fun authFor(repo: ManagedRepo): GitAuth = when (repo.authKind) {
        "https" -> GitAuth.Https(repo.authUsername, prefs.getString(repo.id, "") ?: "")
        "ssh" -> GitAuth.Ssh(repo.sshKeyPath, prefs.getString(repo.id, null), knownHosts.absolutePath)
        else -> GitAuth.None
    }

    companion object {
        /** The encrypted prefs file; SafeEncryptedPrefs.wasReset(context, FILE) says its sign-ins were reset (#1106). */
        const val FILE = "git-sync-credentials"
    }
}
