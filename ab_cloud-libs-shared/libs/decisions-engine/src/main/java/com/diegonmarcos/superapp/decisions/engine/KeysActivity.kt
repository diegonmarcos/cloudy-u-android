package com.diegonmarcos.superapp.decisions.engine

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.decisions.core.KeyBook

/**
 * #1801 THE AI KEYS SCREEN: the one place the fleet's AI provider keys are entered, replaced, revealed or cleared, inside
 * the engine that holds them. Every option the key used to have elsewhere is here (rule 16, nothing removed):
 *
 *   - the FLEET key per provider (what the keyboard's AI Routing "API key" row and the SuperApp's Account ▸ AI tokens
 *     apply used to write over ITextTools.setAiRouting): set, replace, clear;
 *   - REVEAL the stored key (what the SuperApp's Tokens Fleet console used to ask ITextTools.revealAiKey for): a deliberate
 *     tap, shown on this screen only, in this process only;
 *   - a PER-APP override (what Cloud Calc's Jev "manual override" kept in Calc's own encrypted prefs): for any app a relay
 *     use lists, used before the fleet key for that app's calls.
 *
 * Every consumer's settings link here (DecisionsLink.openKeySettings, extras `provider` and `app` preselect). The status
 * line per slot is the same presence + last-four hint the `keys` method answers. Exported under CONSTELLATION_DATA, so
 * only a Cloud-signed app can open it. Nothing on this screen is logged.
 */
class KeysActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val book = EngineHolder.keys(this)
        val relay = EngineHolder.relay(this)
        val providers = relay?.providers()?.takeIf { it.isNotEmpty() } ?: mapOf("openrouter" to "OpenRouter")
        val apps = relay?.apps().orEmpty()
        val pickProvider = intent?.getStringExtra(EXTRA_PROVIDER)?.takeIf { it in providers }
        val pickApp = intent?.getStringExtra(EXTRA_APP)?.takeIf { it in apps }
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    KeysScreen(book, providers, apps, pickProvider, pickApp)
                }
            }
        }
    }

    companion object {
        /** The action consumers open this screen with ({package}.AI_KEYS, the engine's own package). */
        fun action(pkg: String): String = pkg + ".AI_KEYS"
        const val EXTRA_PROVIDER = "provider"
        const val EXTRA_APP = "app"
    }
}

@Composable
private fun KeysScreen(book: KeyBook, providers: Map<String, String>, apps: List<String>, pickProvider: String?, pickApp: String?) {
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("AI keys", style = MaterialTheme.typography.headlineSmall)
        Text(
            "The fleet's AI provider keys live here and nowhere else. Apps name a provider, a model and their text; this engine " +
                "adds the key to the one call and returns the answer. No app holds, reads or stores a key.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val order = providers.keys.sortedBy { if (it == pickProvider) 0 else 1 }
        for (id in order) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${providers[id]} — fleet key", style = MaterialTheme.typography.titleMedium)
                    KeySlot(book, id, null, tick) { tick++ }
                    if (apps.isNotEmpty()) {
                        Text("Per-app override (used before the fleet key for that app)", style = MaterialTheme.typography.titleSmall)
                        for (a in book.overrides(id)) {
                            Text(a, style = MaterialTheme.typography.bodySmall)
                            KeySlot(book, id, a, tick) { tick++ }
                        }
                        NewOverride(book, id, apps, pickApp.takeIf { id == (pickProvider ?: order.first()) }) { tick++ }
                    }
                }
            }
        }
    }
}

/** One slot: its status (present · …hint / no key), a masked entry field, Save, Clear, and a deliberate Reveal. */
@Composable
private fun KeySlot(book: KeyBook, provider: String, app: String?, tick: Int, changed: () -> Unit) {
    var entry by remember(provider, app) { mutableStateOf("") }
    var shown by remember(provider, app, tick) { mutableStateOf<String?>(null) }
    var visible by remember(provider, app) { mutableStateOf(false) }
    val present = remember(provider, app, tick) { book.present(provider, app) }
    val hint = remember(provider, app, tick) { book.hint(provider, app) }
    Text(if (present) "Key held" + (if (hint.isNotEmpty()) " · …$hint" else "") else "No key", style = MaterialTheme.typography.bodyMedium)
    OutlinedTextField(
        value = entry, onValueChange = { entry = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text(if (present) "Replace key" else "Set key") },
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { visible = !visible }) { Text(if (visible) "Hide" else "Show") } },
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = entry.isNotBlank(), onClick = { book.set(provider, entry, app); entry = ""; changed() }) { Text("Save") }
        OutlinedButton(enabled = present, onClick = { book.clear(provider, app); changed() }) { Text("Clear") }
        OutlinedButton(enabled = present, onClick = { shown = if (shown == null) book.get(provider, app.orEmpty().ifEmpty { null }).takeIf { present } else null }) {
            Text(if (shown == null) "Reveal" else "Hide stored")
        }
    }
    shown?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

/** Add an override for one of the apps a relay use lists. */
@Composable
private fun NewOverride(book: KeyBook, provider: String, apps: List<String>, preselect: String?, changed: () -> Unit) {
    var app by remember(provider) { mutableStateOf(preselect ?: apps.first()) }
    var open by remember { mutableStateOf(false) }
    var entry by remember(provider) { mutableStateOf("") }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { open = true }) { Text(app) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (a in apps) DropdownMenuItem(text = { Text(a) }, onClick = { app = a; open = false })
        }
    }
    OutlinedTextField(
        value = entry, onValueChange = { entry = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Override key for $app") }, visualTransformation = PasswordVisualTransformation(),
    )
    Button(enabled = entry.isNotBlank(), onClick = { book.set(provider, entry, app); entry = ""; changed() }) { Text("Save override") }
}
