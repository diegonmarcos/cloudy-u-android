package com.diegonmarcos.clouddrive.sync

import android.content.Context
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.StatusLight
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1149 Sync ▸ Git ▸ Me: the sign-in state of the declared hosts (ui.sync.git_hosts) and their listings. The token and
 * the user name live ONLY in libs:git-sync's [GitCredentialStore] (SafeEncryptedPrefs, opened off the main thread);
 * what this holds in memory is the listing and whether a token exists, never the token. A clone asks [tokenOf].
 */
class GitHostsState(private val ctx: Context, private val scope: CoroutineScope) {
    data class Host(
        val signedIn: Boolean = false,
        val user: String = "",
        val loading: Boolean = false,
        val loaded: Boolean = false,
        val repos: List<GitHubRepos.Repo> = emptyList(),
        val complete: Boolean = true,
        val error: String = "",
    )

    var hosts by mutableStateOf<Map<String, Host>>(emptyMap())
        private set

    private val store: GitCredentialStore by lazy { GitCredentialStore(ctx) }

    /** The secret under [key]: GitCredentialStore hands secrets out only as a [GitAuth], so a throwaway https entry with that key reads it back. Never logged. */
    private fun read(key: String): String =
        (store.authFor(ManagedRepo(id = key, name = "", path = "", authKind = "https")) as? GitAuth.Https)?.secret.orEmpty()

    private fun put(id: String, h: Host) { hosts = hosts + (id to h) }

    /** #1693 the one line the cards show about the Cloud Account read ("" = nothing to say). */
    var said by mutableStateOf("")
        private set

    /** #1693 Signs in with the token Cloud Account holds for [d] (App credentials); a missing one says where to enter it. */
    fun takeFromCloudAccount(d: GitHostsDecl.GitHostDecl, user: String) {
        scope.launch {
            val c = withContext(Dispatchers.IO) { CloudCredentials.read(ctx, CloudCredentials.hostId(d.id)) }
            if (c.present) { said = ""; signIn(d, user, c.value) } else said = c.line
        }
    }

    fun openCloudAccount() { said = CloudCredentials.openAccount(ctx).orEmpty() }

    /** On entering the page: every declared host that already holds a token lists itself. */
    fun restore() {
        scope.launch {
            for (d in Declarations.gitHosts) {
                val user = withContext(Dispatchers.IO) { read(GitHosts.userKey(d.id)) }
                if (withContext(Dispatchers.IO) { store.hasSecret(GitHosts.tokenKey(d.id)) }) { put(d.id, Host(signedIn = true, user = user)); refresh(d) }
            }
        }
    }

    fun signIn(d: GitHostsDecl.GitHostDecl, user: String, token: String) {
        if (token.isBlank()) return
        scope.launch {
            withContext(Dispatchers.IO) { store.setSecret(GitHosts.tokenKey(d.id), token.trim()); store.setSecret(GitHosts.userKey(d.id), user.trim()) }
            put(d.id, Host(signedIn = true, user = user.trim()))
            refresh(d)
        }
    }

    fun signOut(d: GitHostsDecl.GitHostDecl) {
        scope.launch {
            withContext(Dispatchers.IO) { store.clear(GitHosts.tokenKey(d.id)); store.clear(GitHosts.userKey(d.id)) }
            put(d.id, Host())
        }
    }

    fun refresh(d: GitHostsDecl.GitHostDecl) {
        val cur = hosts[d.id] ?: Host()
        put(d.id, cur.copy(loading = true, error = ""))
        scope.launch {
            val r = withContext(Dispatchers.IO) { GitHosts.fetch(d, cur.user, read(GitHosts.tokenKey(d.id))) }
            put(d.id, r.fold(
                onSuccess = { cur.copy(loading = false, loaded = true, repos = it.repos, complete = it.complete, error = "") },
                onFailure = { cur.copy(loading = false, loaded = true, repos = emptyList(), error = it.message ?: it.javaClass.simpleName) },
            ))
        }
    }

    /** The stored token for a clone of one of this host's repositories ("" = none); read off the main thread by the caller's coroutine. */
    fun tokenOf(id: String): String = read(GitHosts.tokenKey(id))
}

/** One host's card: status light, the token field(s) while signed out, Sign out / Refresh while signed in, and the listing's one-line state. */
@Composable
fun GitHostCard(
    decl: GitHostsDecl.GitHostDecl,
    state: GitHostsState.Host,
    onSignOut: () -> Unit,
    onRefresh: () -> Unit,
    onOpenTokenPage: () -> Unit,
    onTakeToken: (user: String) -> Unit,
    onOpenCredentials: () -> Unit,
    said: String = "",
) {
    var user by remember { mutableStateOf("") }
    DriveCard(
        decl.label,
        light = if (state.signedIn) StatusLight.State.ON else StatusLight.State.UNKNOWN,
        summary = when {
            state.loading -> stringResource(R.string.git_listing_loading)
            state.signedIn && state.loaded && state.error.isBlank() -> stringResource(R.string.git_host_repos, state.user.ifBlank { decl.label }, state.repos.size)
            state.signedIn -> stringResource(R.string.git_host_signed_in, state.user.ifBlank { decl.label })
            else -> stringResource(R.string.git_host_signed_out)
        },
        tag = DriveTags.hostCard(decl.id),
    ) {
        if (!state.signedIn) {
            if (decl.needsUser) OutlinedTextField(
                user, { user = it }, label = { Text(decl.userLabel.ifBlank { stringResource(R.string.git_host_user) }) },
                singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = DriveMetrics.gapWide),
            )
            // #1693 the token is typed in Cloud Account (App credentials), not here: Take reads it back over the provider.
            Text(stringResource(R.string.git_host_token_in_account), Modifier.padding(top = DriveMetrics.gapWide).testTag(DriveTags.hostToken(decl.id)),
                style = MaterialTheme.typography.bodySmall)
            if (said.isNotBlank()) Text(said, Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall,
                color = colorResource(R.color.status_light_off))
            PillRow {
                Pill(
                    stringResource(R.string.git_host_take_token, decl.label), { onTakeToken(user) },
                    icon = IconCatalog.vectorOrDefault(decl.icon), filled = true,
                    enabled = !decl.needsUser || user.isNotBlank(),
                    modifier = Modifier.testTag(DriveTags.hostSignIn(decl.id)),
                )
                Pill(stringResource(R.string.git_host_set_token), onOpenCredentials)
                if (decl.tokenUrl.isNotBlank()) Pill(stringResource(R.string.git_host_get_token), onOpenTokenPage)
            }
        } else {
            PillRow {
                Pill(stringResource(R.string.chrome_retry), onRefresh, enabled = !state.loading)
                Pill(stringResource(R.string.git_host_sign_out), onSignOut, danger = true)
            }
            if (state.error.isNotBlank()) Text(
                stringResource(R.string.git_listing_failed, state.error), Modifier.padding(top = DriveMetrics.gapWide),
                style = MaterialTheme.typography.bodySmall, color = colorResource(R.color.status_light_off), fontWeight = FontWeight.SemiBold,
            )
            else if (state.loaded && !state.complete) Text(stringResource(R.string.git_host_truncated, state.repos.size), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
            else if (state.loaded && state.repos.isEmpty()) Text(stringResource(R.string.git_listing_empty), Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall)
        }
    }
}
