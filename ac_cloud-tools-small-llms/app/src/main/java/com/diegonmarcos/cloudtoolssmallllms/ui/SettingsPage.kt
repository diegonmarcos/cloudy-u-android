package com.diegonmarcos.cloudtoolssmallllms.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.diegonmarcos.cloudtoolssmallllms.BuildConfig
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.Costs
import com.diegonmarcos.cloudtoolssmallllms.core.ToolsConfig
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue

/**
 * Settings: the OpenRouter token (pasted, saved encrypted, shown masked, tested, removed), the
 * fleet Account's status (whether it holds a key - never the key), the router (auto / Jev / a chat
 * router) with Jev's live availability, the worker per task, and About.
 */
@Composable
fun SettingsPage(model: ToolsModel) {
    val ctx = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    var openTask by remember { mutableStateOf<String?>(null) }
    @Suppress("UNUSED_VARIABLE") val v = model.pricesVersion
    val cheap = remember(model.pricesVersion) {
        model.catalogue.chatModels().filter { id -> model.catalogue.book.price(id)?.let { it.inPerM <= CHEAP && it.outPerM <= CHEAP } == true }
    }
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(ToolsMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.gap),
    ) {
        item {
            // How the open section's child pages are navigated: one row, two rows or the side menu (persisted, in the backup).
            Card("Navigation") { com.diegonmarcos.superapp.navbartop2.NavModeConfigCard(com.diegonmarcos.cloudtoolssmallllms.Decl.nav.sections) }
        }
        item {
            Card(stringResource(R.string.settings_token)) {
                Text(
                    if (model.tokenSet) stringResource(R.string.settings_token_stored, model.masked) else stringResource(R.string.settings_token_none),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (model.tokenSet) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                )
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                    placeholder = { Text(stringResource(R.string.settings_token_hint)) },
                    modifier = Modifier.fillMaxWidth().testTag(Tags.TOKEN),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                    Chip(stringResource(R.string.settings_save), selected = true, tag = Tags.TOKEN_SAVE) {
                        note = ctx.getString(if (model.saveToken(typed)) R.string.settings_saved else R.string.settings_invalid)
                        typed = ""
                    }
                    Chip(stringResource(R.string.settings_test), tag = Tags.TOKEN_TEST) {
                        model.testToken { k ->
                            note = if (k.ok) ctx.getString(R.string.settings_test_ok, k.label ?: "—", Costs.usd(k.usage))
                            else ctx.getString(R.string.settings_test_failed, k.error ?: "")
                        }
                    }
                    if (model.tokenSet) Chip(stringResource(R.string.settings_remove)) { model.removeToken(); note = null }
                }
                note?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary) }
                Text(stringResource(R.string.settings_token_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(stringResource(R.string.settings_account)) {
                val a = model.accountState
                val line = when {
                    a == null -> "…"
                    !a.connected -> stringResource(R.string.settings_account_absent)
                    a.keyPresent -> stringResource(R.string.settings_account_connected) + " · " +
                        stringResource(R.string.settings_account_key, if (a.keyHint.isNotEmpty()) "(…${a.keyHint})" else "")
                    else -> stringResource(R.string.settings_account_connected) + " · " + stringResource(R.string.settings_account_no_key)
                }
                Row {
                    Text(line, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Chip(stringResource(R.string.settings_refresh)) { model.refreshAccount() }
                }
                Text(stringResource(R.string.settings_account_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            Card(stringResource(R.string.settings_router)) {
                val r = model.cfg.router
                for (id in r.choices) {
                    val label = when (id) {
                        ToolsConfig.AUTO -> stringResource(R.string.settings_router_auto, short(r.chatDefault))
                        ToolsConfig.JEV -> stringResource(R.string.settings_router_jev, r.jevModel, short(r.chatDefault))
                        else -> short(id) + (model.catalogue.book.price(id)?.let { " · " + ModelCatalogue.usd(it.inPerM) + " / " + ModelCatalogue.usd(it.outPerM) } ?: "")
                    }
                    Chip(label, selected = model.router == id, tag = Tags.router(id)) { model.chooseRouter(id) }
                }
                Row {
                    Text(
                        when (model.jevListed) {
                            true -> stringResource(R.string.settings_jev_status_listed, ModelCatalogue.usd(model.jevPrice))
                            false -> stringResource(R.string.settings_jev_status_unlisted)
                            null -> stringResource(R.string.settings_jev_status_unknown)
                        },
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f),
                    )
                    Chip(stringResource(R.string.settings_refresh)) { model.refreshJev() }
                }
            }
        }
        item {
            Card(stringResource(R.string.settings_workers)) {
                for (t in model.cfg.tasks) {
                    val current = model.registry().worker(t)
                    Row(Modifier.fillMaxWidth().clickable { openTask = if (openTask == t.id) null else t.id }) {
                        Text(t.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        Text(short(current) + if (current == t.worker) " (" + stringResource(R.string.settings_worker_default) + ")" else "",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (openTask == t.id) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                            items(cheap) { id -> Chip(short(id), selected = id == current) { model.chooseWorker(t.id, id) } }
                        }
                    }
                }
            }
        }
        item {
            Card(stringResource(R.string.settings_pin)) {
                Text(stringResource(R.string.settings_pin_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Chip(stringResource(R.string.settings_pin_auto), selected = model.pinned.isBlank(), tag = Tags.PIN_AUTO) { model.pin("") }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(ToolsMetrics.small)) {
                    items(model.models.chatRows(), key = { it.model }) { r -> Chip(r.model, selected = model.pinned == r.model, tag = Tags.pin(r.model)) { model.pin(r.model) } }
                }
            }
        }
        item {
            Card(stringResource(R.string.settings_about)) {
                Text(stringResource(R.string.settings_about_line, stringResource(R.string.app_name), BuildConfig.GIT_SHORT_SHA, BuildConfig.BUILD_TIMESTAMP),
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Card(title: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(ToolsMetrics.corner)).background(MaterialTheme.colorScheme.surface).padding(ToolsMetrics.gutter),
        verticalArrangement = Arrangement.spacedBy(ToolsMetrics.small),
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        content()
    }
}

/** Small and cheap only: a worker the picker offers costs at most this many dollars per 1M tokens either way. */
private const val CHEAP = 1.0

/** A model id without its provider prefix ("openai/gpt-x" -> "gpt-x"). */
fun short(model: String): String = model.substringAfter('/').ifBlank { model }
