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
    var newHost by remember { mutableStateOf("") }
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
        for (e in AppCredentialsContract.CATALOG.filter { it.prefix }) KitCard {
            Text(e.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(newHost, { newHost = it.trim() }, label = { Text("host name (id ${e.id}<host>)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(AppCredentialsTags.NEW_ID))
            val id = e.id + newHost
            if (newHost.isNotBlank() && AppCredentialsContract.entry(id) != null) CredentialRow(id, AppCredentialsStore.get(vault, id).isNotBlank(), e) { value ->
                said = if (value == null) { AppCredentialsStore.remove(vault, id); "cleared $id" }
                else if (AppCredentialsStore.put(vault, id, value)) "filed $id" else "not filed"
                if (value != null) newHost = ""
                tick++
            }
        }
    }
}

@Composable
private fun CredentialRow(id: String, filed: Boolean, e: AppCredentialsContract.Entry, submit: (String?) -> Unit) {
    val p = LocalKitPalette.current
    var draft by remember(id) { mutableStateOf("") }
    KitCard {
        Text(if (e.prefix) id else e.label, color = p.textPrimary, style = MaterialTheme.typography.titleSmall)
        Text((if (filed) "filed" else "not set") + " · " + e.hint, color = p.textSecondary, style = MaterialTheme.typography.bodySmall)
        // A credential box is kept out of the keyboard's learned words and corrections.
        val keep = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false)
        OutlinedTextField(draft, { draft = it }, label = { Text("value") }, singleLine = true, keyboardOptions = keep,
            visualTransformation = if (e.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            modifier = Modifier.fillMaxWidth().testTag(AppCredentialsTags.field(id)))
        KitActionBar(buildList {
            add(KitAction("Save", AppCredentialsTags.save(id)) { val v = draft; draft = ""; submit(v) })
            if (filed) add(KitAction("Clear", AppCredentialsTags.clear(id)) { draft = ""; submit(null) })
        })
    }
}
