package com.diegonmarcos.superapp.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import com.diegonmarcos.superapp.uikit.LocalKitPalette

object AppCredentialsTags {
    const val PANEL = "credentials:panel"
    fun field(id: String) = "credentials:field:$id"
    fun save(id: String) = "credentials:save:$id"
    fun clear(id: String) = "credentials:clear:$id"
    const val NEW_ID = "credentials:new-id"
}

/**
 * #1693 App credentials: where a token, password or key the apps used to ask for on their own pages is entered
 * is entered in Cloud Account only. Owner-only: any other host draws nothing and its sign-in rows link here instead ([SignInOwner.isOwner]).
 * A value is never shown back, only whether one is filed; the apps read it over the provider's per-credential grant.
 */
@Composable
fun AppCredentialsPanel() {
    val ctx = LocalContext.current
    if (!SignInOwner.isOwner(ctx)) return
    val p = LocalKitPalette.current
    val vault = remember { AccountVault(ctx) }
    var tick by remember { mutableIntStateOf(0) }
    var said by remember { mutableStateOf("") }
    val ids = remember(tick) { AppCredentialsStore.ids(vault) }
    KitSectionHeader("App credentials", "", eyebrow = true)
    Column(Modifier.testTag(AppCredentialsTags.PANEL), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Tokens and passwords the other apps use; typed here once, read by each app over its grant, never shown back.",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (said.isNotBlank()) KitStatusBanner(said, KitState.IDLE, tag = "credentials:said")
        for (id in ids) CredentialRow(id, AppCredentialsStore.get(vault, id).isNotBlank(), AppCredentialsContract.entry(id)!!) { value ->
            said = if (value == null) { AppCredentialsStore.remove(vault, id); "cleared $id" }
            else if (AppCredentialsStore.put(vault, id, value)) "filed $id" else "not filed: empty, too long or unknown"
            tick++
        }
        for (e in AppCredentialsContract.CATALOG.filter { it.prefix }) PrefixedCard(e, vault) { said = it; tick++ }
        for (spec in MusicDeviceGrant.SPECS.values) MusicDeviceCard(spec, vault) { said = it; tick++ }
    }
}

/** #1693 a music service that signs in with a device grant: its tokens are filed by the grant, which runs here and nowhere else. */
@Composable
private fun MusicDeviceCard(spec: MusicDeviceGrant.Spec, vault: AccountVault, said: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val uri = androidx.compose.ui.platform.LocalUriHandler.current
    var busy by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    val connected = AppCredentialsStore.get(vault, "music.${spec.id}.access").isNotBlank()
    KitCard {
        Text("${spec.label} sign-in", color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text((if (connected) "connected" else "not connected") + " · file the client id (music.${spec.id}.client_id) and secret (music.${spec.id}.client_secret) above, then connect",
            color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        if (line.isNotBlank()) Text(line, color = p.textSecondary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag(AppCredentialsTags.field("music:${spec.id}:line")))
        KitActionBar(buildList {
            add(KitAction(if (busy) "Waiting for approval…" else "Connect ${spec.label}", AppCredentialsTags.save("music:${spec.id}"), enabled = !busy) {
                busy = true; line = "asking for a code…"
                scope.launch {
                    val o = MusicDeviceGrant.run(ctx, spec, onCode = { c ->
                        line = "Open ${c.url} and enter ${c.userCode}"
                        runCatching { uri.openUri(c.url) }
                    })
                    busy = false
                    when (o) {
                        is MusicDeviceGrant.Outcome.Connected -> { line = ""; said("${spec.label} connected: Cloud Browser can take the tokens") }
                        is MusicDeviceGrant.Outcome.Failed -> { line = o.why; said(o.why) }
                    }
                }
            })
            if (connected) add(KitAction("Disconnect", AppCredentialsTags.clear("music:${spec.id}")) {
                for (k in listOf("access", "refresh", "expires")) AppCredentialsStore.remove(vault, "music.${spec.id}.$k")
                said("${spec.label} disconnected")
            })
        })
    }
}

/** A prefixed credential (`git.host.<host>`, `git.repo.<repo>`): the name part is typed, then the value. */
@Composable
private fun PrefixedCard(e: AppCredentialsContract.Entry, vault: AccountVault, said: (String) -> Unit) {
    val p = LocalKitPalette.current
    var name by remember(e.id) { mutableStateOf("") }
    KitCard {
        Text(e.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(name, { name = it.trim() }, label = { Text("name (id ${e.id}<name>)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag(AppCredentialsTags.NEW_ID + ":" + e.id))
        val id = e.id + name
        if (name.isNotBlank() && AppCredentialsContract.entry(id) != null) CredentialRow(id, AppCredentialsStore.get(vault, id).isNotBlank(), e) { value ->
            if (value == null) { AppCredentialsStore.remove(vault, id); said("cleared $id") }
            else { said(if (AppCredentialsStore.put(vault, id, value)) "filed $id" else "not filed"); name = "" }
        }
    }
}

/** #1693 (moved from Cloud Mail's SettingTextField) whether a secret field offers the eye: only a secret, and only with something typed in it. */
fun revealIconOffered(isSecret: Boolean, value: String): Boolean = isSecret && value.isNotEmpty()

@Composable
private fun CredentialRow(id: String, filed: Boolean, e: AppCredentialsContract.Entry, submit: (String?) -> Unit) {
    val p = LocalKitPalette.current
    var draft by remember(id) { mutableStateOf("") }
    KitCard {
        Text(if (e.prefix) id else e.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text((if (filed) "filed" else "not set") + " · " + e.hint, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        // A credential box is kept out of the keyboard's learned words and corrections.
        val keep = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        // Keyed on emptiness, not on the value: the reveal survives typing but falls back to hidden as soon as the field is cleared, so the
        // next secret typed into it is never printed in clear.
        var revealRequested by remember(draft.isEmpty()) { mutableStateOf(false) }
        val masked = e.secret && !revealRequested
        OutlinedTextField(draft, { draft = it }, label = { Text("value") }, singleLine = true, keyboardOptions = keep,
            visualTransformation = if (masked) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            trailingIcon = if (revealIconOffered(e.secret, draft)) {
                { androidx.compose.material3.TextButton(onClick = { revealRequested = !revealRequested }) { Text(if (revealRequested) "Hide" else "Show") } }
            } else {
                null
            },
            modifier = Modifier.fillMaxWidth().testTag(AppCredentialsTags.field(id)))
        KitActionBar(buildList {
            add(KitAction("Save", AppCredentialsTags.save(id)) { val v = draft; draft = ""; submit(v) })
            if (filed) add(KitAction("Clear", AppCredentialsTags.clear(id)) { draft = ""; submit(null) })
        })
    }
}

