package com.diegonmarcos.cloudwebserver.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.cloudwebserver.absolute
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The link-open declaration (build.json::links, /__app__/api/links), native: a tapped link is classified by the first type whose
 * schemes and hosts match (local = loopback and the private .app names of /__app__/api/serving), and that type's choices are offered:
 * Open in browser (POST /__app__/api/open: cloud-browser, explicit package) or Open embedded (an in-app frame; a private name is
 * rewritten to 127.0.0.1:<its port>, which is all the web shell's embedUrl did). A remembered choice per type lives in this app's
 * preferences; Configs > Links shows and resets it. Nothing here names a type or a choice.
 */
object Links {
    var decl by mutableStateOf<JSONObject?>(null)
    var endpoints by mutableStateOf<List<JSONObject>>(emptyList())
    /** The link being decided ({url, type}) or the URL shown embedded. */
    var asking by mutableStateOf<Pair<String, JSONObject>?>(null)
    var embedded by mutableStateOf<String?>(null)

    private const val PREFS = "c3_links"

    fun remembered(ctx: Context, type: String): String? = runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(type, null) }.getOrNull()

    fun remember(ctx: Context, type: String, choice: String?) {
        runCatching { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply { if (choice == null) remove(type) else putString(type, choice) }.apply() }
    }

    private fun names(): List<String> = endpoints.map { it.optString("name") }

    /** classifyLink: the first declared type matching the URL's scheme and host locality. */
    fun classify(url: String): JSONObject? {
        val d = decl ?: return null
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return null
        val scheme = u.scheme?.lowercase().orEmpty()
        val host = u.host.orEmpty()
        val local = host == "127.0.0.1" || host == "localhost" || host == "[::1]" || host == "::1" || host in names()
        return d.optJSONArray("types").objs().firstOrNull { t ->
            val schemes = t.optJSONArray("schemes").strs()
            val s = "*" in schemes || scheme in schemes
            val h = when (t.optString("hosts")) { "any" -> true; "local" -> local; else -> !local }
            s && h
        }
    }

    /** embedUrl: a private name becomes 127.0.0.1 and its declared port. */
    fun embedUrl(url: String): String {
        val u = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val ep = endpoints.firstOrNull { it.optString("name") == u.host } ?: return url
        val port = if (u.port > 0) u.port else ep.optInt("port")
        return runCatching { java.net.URI(u.scheme, u.userInfo, "127.0.0.1", port, u.path, u.query, u.fragment).toString() }.getOrDefault(url)
    }

    fun choiceLabel(id: String): String = decl?.optJSONArray("choices").objs().firstOrNull { it.optString("id") == id }?.optString("label")?.ifEmpty { null } ?: id
}

/** Opens [href] the declared way (asks, or runs the one / remembered choice). */
fun openLink(ctx: Context, href: String, actions: Actions) {
    val url = Api.absolute(href)
    val t = Links.classify(url)
    val choices = t?.optJSONArray("choices").strs()
    val remembered = t?.let { Links.remembered(ctx, it.optString("id")) }?.takeIf { it in choices && Links.decl?.optBoolean("remember") == true }
    val choice = when {
        t == null || choices.isEmpty() -> "browser"
        choices.size == 1 -> choices.first()
        remembered != null -> remembered
        else -> null
    }
    when (choice) {
        null -> Links.asking = url to (t ?: return)
        else -> runChoice(choice, url, actions)
    }
}

private fun runChoice(choice: String, url: String, actions: Actions) {
    if (choice == "embedded") Links.embedded = Links.embedUrl(url)
    else actions.post(Api.OPEN, JSONObject().put("url", url)) { "Opening in cloud-browser" }
}

/** The chooser sheet and the embedded view, hosted once by the shell; loads the declaration and the endpoint names once. */
@Composable
fun LinkHost() {
    val ctx = LocalContext.current
    val actions = rememberActions {}
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        scope.launch {
            (Api.get(Api.LINKS) as? Api.Reply.Ok)?.let { Links.decl = it.value as? JSONObject }
            (Api.get(Api.SERVING) as? Api.Reply.Ok)?.let { Links.endpoints = (it.value as? JSONObject)?.objs("endpoints").orEmpty() }
        }
    }
    Links.asking?.let { (url, t) ->
        var rememberIt by remember(url) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { Links.asking = null },
            title = { Text(t.optString("label")) },
            text = {
                Column {
                    Text(url, style = MaterialTheme.typography.bodySmall)
                    if (Links.decl?.optBoolean("remember") == true) Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberIt, { rememberIt = it }, modifier = Modifier.testTag("c3_link_remember"))
                        Text("Remember my choice for ${t.optString("label").lowercase()}s", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Row {
                    t.optJSONArray("choices").strs().forEach { id ->
                        TextButton({ Links.asking = null; if (rememberIt) Links.remember(ctx, t.optString("id"), id); runChoice(id, url, actions) }, Modifier.testTag("c3_link_choice_$id")) { Text(Links.choiceLabel(id)) }
                    }
                }
            },
            dismissButton = { TextButton({ Links.asking = null }) { Text("Cancel") } },
        )
    }
    Links.embedded?.let { url ->
        val handle = remember(url) { FrameHandle() }
        androidx.activity.compose.BackHandler { if (!handle.back()) Links.embedded = null }
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag("c3_embedded")) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Act("‹ Back", "c3_embedded_close") { if (!handle.back()) Links.embedded = null }
                Act("✕", "c3_embedded_exit") { Links.embedded = null }
                Text(url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            WebFrame(url, handle, Modifier.weight(1f))
        }
    }
}
