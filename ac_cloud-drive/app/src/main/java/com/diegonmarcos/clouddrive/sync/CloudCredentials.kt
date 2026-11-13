package com.diegonmarcos.clouddrive.sync

import android.content.Context
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.fleetendpoints.android.AccountClient

/**
 * #1693 Drive does not ask for a git token or a passphrase: the person types it in Cloud Account (App credentials) and Drive
 * reads it back over the provider's per-credential grant. The ids are pure (JVM-tested); a read BLOCKS, never throws, and the
 * value is in no line and no log: a missing or refusing Cloud Account is a sentence naming the fix.
 */
object CloudCredentials {
    /** `git.host.<host id>`: the token or password of one declared self-hosted git host. */
    fun hostId(declId: String) = "git.host." + AppCredentialsContract.slug(declId)
    /** `git.repo.<repo>`: the https token or password of one synced repository. */
    fun repoTokenId(repoId: String) = "git.repo." + AppCredentialsContract.slug(repoId)
    /** `git.ssh.<repo>`: the passphrase of one synced repository's ssh key. */
    fun sshPassphraseId(repoId: String) = "git.ssh." + AppCredentialsContract.slug(repoId)

    fun read(ctx: Context, id: String): AppCredentialsContract.Credential = AccountClient.credential(ctx, id)

    /** Opens Cloud Account at App credentials; null when it opened, else the reason. */
    fun openAccount(ctx: Context): String? = AccountClient.openCredentials(ctx)
}
