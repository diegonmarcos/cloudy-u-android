package com.diegonmarcos.cloudaccount.personal

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.RevealGate
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitAction
import com.diegonmarcos.superapp.uikit.KitActionBar
import com.diegonmarcos.superapp.uikit.KitListRow
import com.diegonmarcos.superapp.uikit.KitState
import com.diegonmarcos.superapp.uikit.KitStatusBanner
import java.util.UUID

object PersonalTags {
    fun page(kind: PersonalKind) = "personal:${kind.key}"
    fun row(kind: PersonalKind, id: String) = "personal:${kind.key}:row:$id"
    fun value(kind: PersonalKind, id: String, field: String) = "personal:${kind.key}:value:$id:$field"
    fun reveal(kind: PersonalKind, id: String) = "personal:${kind.key}:reveal:$id"
    fun delete(kind: PersonalKind, id: String) = "personal:${kind.key}:delete:$id"
    fun add(kind: PersonalKind) = "personal:${kind.key}:add"
    fun save(kind: PersonalKind) = "personal:${kind.key}:save"
    const val UNAVAILABLE = "personal:unavailable"
}

private fun title(kind: PersonalKind) = when (kind) {
    PersonalKind.IDS -> "ID documents"
    PersonalKind.CARDS -> "Payment cards"
    PersonalKind.OTHERS -> "Other personal entries"
}

private fun fieldLabel(f: String) = f.replaceFirstChar { it.uppercase() }

/**
 * #1438 Personal Info > IDs / Pay Cards / Others > Entries: the entries of one [kind], kept only in
 * EncryptedSharedPreferences ([PersonalStore]). A masked field ([PersonalKind.masked]) is drawn through
 * [CardMask.display] and shows its clear value only after device auth ([RevealGate] over [LocalDeviceAuth]);
 * leaving the page masks everything again. Nothing here logs.
 */
@Composable
fun PersonalEntriesPage(kind: PersonalKind, repoOf: (Context) -> PersonalRepo? = PersonalStore::repo) {
    val ctx = LocalContext.current
    val repo = remember { repoOf(ctx) }
    val gate = RevealGate(LocalDeviceAuth.current)
    var tick by remember { mutableIntStateOf(0) }
    val revealed = remember { mutableStateMapOf<String, Boolean>() }
    var adding by remember { mutableStateOf(false) }
    var line by remember { mutableStateOf("") }
    DisposableEffect(Unit) { onDispose { revealed.clear() } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp).testTag(PersonalTags.page(kind)),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        GroupTitle(title(kind))
        if (repo == null) {
            KitStatusBanner("Secure storage is unavailable on this phone: nothing is stored", KitState.BAD, tag = PersonalTags.UNAVAILABLE)
        } else {
        val entries = remember(tick) { repo.list(kind) }
        KitStatusBanner(if (entries.isEmpty()) "Nothing stored yet" else "${entries.size} stored, encrypted on this phone",
            if (entries.isEmpty()) KitState.IDLE else KitState.OK, tag = "personal:${kind.key}:summary")
        if (line.isNotBlank()) KitStatusBanner(line, KitState.WARN, tag = "personal:${kind.key}:line")
        for (e in entries) {
            val shown = revealed[e.id] == true
            KitListRow(e.label.ifBlank { fieldLabel(kind.key) }, tag = PersonalTags.row(kind, e.id))
            for (f in kind.fields) {
                val v = e.fields[f].orEmpty()
                if (v.isEmpty()) continue
                Text("${fieldLabel(f)}: ${CardMask.display(kind, f, v, shown)}",
                    Modifier.padding(start = 12.dp).testTag(PersonalTags.value(kind, e.id, f)))
            }
            Row {
                if (kind.masked.any { e.fields[it].orEmpty().isNotEmpty() }) {
                    TextButton(onClick = {
                        if (shown) revealed.remove(e.id)
                        else gate.reveal("Show ${e.label.ifBlank { kind.key }}",
                            read = { repo.get(kind, e.id)?.let { x -> kind.masked.mapNotNull { x.fields[it] }.firstOrNull { it.isNotEmpty() } } },
                            show = { revealed[e.id] = true },
                            denied = { line = "Not shown: device auth was not confirmed" })
                    }, Modifier.testTag(PersonalTags.reveal(kind, e.id))) { Text(if (shown) "Hide" else "Reveal") }
                }
                TextButton(onClick = { repo.remove(kind, e.id); revealed.remove(e.id); tick++ },
                    Modifier.testTag(PersonalTags.delete(kind, e.id))) { Text("Delete") }
            }
        }
        if (adding) {
            AddForm(kind) { label, fields ->
                repo.put(kind, PersonalEntry(UUID.randomUUID().toString(), label, fields)); adding = false; tick++
            }
        } else {
            KitActionBar(listOf(KitAction("Add", PersonalTags.add(kind)) { adding = true }))
        }
        }
    }
}

@Composable
private fun AddForm(kind: PersonalKind, save: (String, Map<String, String>) -> Unit) {
    var label by remember { mutableStateOf("") }
    val values = remember { mutableStateMapOf<String, String>() }
    OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth().testTag("personal:${kind.key}:field:label"), label = { Text("Label") }, singleLine = true)
    for (f in kind.fields) {
        val secret = f in kind.masked
        OutlinedTextField(values[f].orEmpty(), { values[f] = it }, Modifier.fillMaxWidth().testTag("personal:${kind.key}:field:$f"),
            label = { Text(fieldLabel(f)) }, singleLine = f != "value",
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (kind == PersonalKind.CARDS && f == "number") KeyboardType.Number else KeyboardType.Text))
    }
    KitActionBar(listOf(KitAction("Save", PersonalTags.save(kind), label.isNotBlank()) {
        save(label.trim(), values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() })
    }))
}
