package com.diegonmarcos.superapp.scraper

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import com.diegonmarcos.superapp.apify.ApifyBackendStore
import com.diegonmarcos.superapp.apify.ApifyBackends
import com.diegonmarcos.superapp.apify.EncryptedApifyKv
import com.diegonmarcos.superapp.themebg.ThemeColors
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** #1475 the Cloudflare Browser Run endpoints offered as chips: id (the path under browser-rendering) and label. */
enum class CfEndpoint(val path: String, val label: String) { MARKDOWN("markdown", "Markdown"), CONTENT("content", "HTML"), LINKS("links", "Links") }

/** #1475 Cloudflare Browser Run (browser-rendering REST API): POST <base>/<endpoint> {"url": ...}, bearer API token, answer {success, result, errors}. */
class CloudflareClient(private val base: String, private val token: String, private val http: MusicHttp = MusicHttp()) {
    fun render(endpoint: CfEndpoint, url: String): String = result(http.postJson(ApifyBackends.cloudflareUrl(base, endpoint.path), JSONObject().put("url", url.trim()), mapOf("Authorization" to "Bearer $token")))

    companion object {
        /** The `result` of an answer as text (a list becomes one line per entry); an unsuccessful answer throws its first error message. */
        fun result(body: String): String {
            val o = JSONObject(body)
            if (!o.optBoolean("success", false)) throw java.io.IOException(o.optJSONArray("errors")?.optJSONObject(0)?.optString("message").orEmpty().ifBlank { "Cloudflare refused the request" })
            val r = o.opt("result")
            return if (r is org.json.JSONArray) (0 until r.length()).joinToString("\n") { r.optString(it) } else r?.toString().orEmpty()
        }
    }
}

/** #1475 Cloudflare Browser Run page: account id + API token (stored encrypted in the shared libs:apify store), then render a URL on Cloudflare's headless browser. */
@Composable
fun CloudflarePage(c: ThemeColors) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { ApifyBackendStore(EncryptedApifyKv(ctx)) }
    val cf = remember { store.decl.backends.first { it.cloudflare } }
    var rev by remember { mutableStateOf(0) }
    var account by remember { mutableStateOf(store.accountId().orEmpty()) }
    var tokenText by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf(CfEndpoint.MARKDOWN) }
    var url by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var output by remember { mutableStateOf("") }
    val connected = rev.let { store.hasToken(cf) }
    val colors = OutlinedTextFieldDefaults.colors(focusedTextColor = c.ink, unfocusedTextColor = c.ink, focusedLabelColor = c.accent, unfocusedLabelColor = c.inkDim)

    fun render() {
        val r = store.resolve(cf) ?: run { message = "Add the account id and the API token first"; return }
        message = "Rendering…"
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { CloudflareClient(r.first, r.second).render(endpoint, url) } }
                .onSuccess { output = it; message = "${it.length} characters" }.onFailure { message = "Cloudflare: ${it.message}" }
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("cloudflare:list")) {
        item(key = "notice") { KitStoreRowLine { KitStoreCaption(CF_NOTICE, color = c.inkDim, modifier = Modifier.testTag("cloudflare:notice")) } }
        item(key = "account") {
            Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH)) {
                OutlinedTextField(account, { account = it }, label = { Text("Account id (32 hex)") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth().testTag("cloudflare:account"))
                OutlinedTextField(tokenText, { tokenText = it }, label = { Text(if (connected) "API token (stored encrypted, set)" else "API token (stored encrypted)") }, singleLine = true, colors = colors,
                    modifier = Modifier.fillMaxWidth().testTag("cloudflare:token"))
                Row {
                    Button(onClick = {
                        if (!store.setAccountId(account)) message = "That is not a Cloudflare account id"
                        else if (tokenText.isNotBlank() && !store.setToken(cf, tokenText)) message = "A token has no spaces"
                        else { tokenText = ""; rev++; message = "Saved" }
                    }, enabled = account.isNotBlank() || tokenText.isNotBlank(), colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.testTag("cloudflare:save")) { Text("Save") }
                    if (connected) TextButton(onClick = { store.setToken(cf, ""); store.setAccountId(""); account = ""; rev++ }, modifier = Modifier.testTag("cloudflare:clear")) { Text("Remove", color = c.accent) }
                    TextButton(onClick = { store.setBackend(cf.id); rev++; message = "Cloudflare Browser Run is now the scraper backend" }, enabled = connected, modifier = Modifier.testTag("cloudflare:use")) { Text("Use as backend", color = c.accent) }
                }
            }
        }
        item(key = "render") {
            Column(Modifier.fillMaxWidth().padding(horizontal = KitStoreRow.padH)) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    for (e in CfEndpoint.entries) Chip(e.label, endpoint == e, c, "cloudflare:endpoint:${e.name}") { endpoint = e }
                }
                OutlinedTextField(url, { url = it }, label = { Text("Page URL") }, singleLine = true, colors = colors, modifier = Modifier.fillMaxWidth().testTag("cloudflare:url"))
                Button(onClick = { render() }, enabled = connected && url.startsWith("http"), colors = ButtonDefaults.buttonColors(containerColor = c.accent), modifier = Modifier.testTag("cloudflare:render")) { Text("Render") }
            }
        }
        if (message.isNotEmpty()) item(key = "status") { KitStoreRowLine { KitStoreCaption(message, color = c.ink, modifier = Modifier.testTag("cloudflare:status")) } }
        if (output.isNotEmpty()) item(key = "output") { KitStoreRowLine { KitStoreCaption(output.take(20_000), color = c.ink, modifier = Modifier.testTag("cloudflare:output")) } }
    }
}

private const val CF_NOTICE = "Cloudflare Browser Run renders a page on Cloudflare's headless browser (REST API, billed to your Cloudflare account). Apify stays the default backend."
