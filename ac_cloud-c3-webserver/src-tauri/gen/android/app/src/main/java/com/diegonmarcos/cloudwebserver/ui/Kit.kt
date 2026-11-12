package com.diegonmarcos.cloudwebserver.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudwebserver.Api
import com.diegonmarcos.superapp.themebg.ThemeBackground
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The app's look: the fleet's dark palette (libs:theme-background) as a Material 3 scheme; the bars draw their own (libs:ui-nav-bars). */
@Composable
fun WebserverTheme(content: @Composable () -> Unit) {
    val c = ThemeBackground.colors(true)
    MaterialTheme(
        colorScheme = darkColorScheme(primary = c.accent, onSurface = c.ink, onSurfaceVariant = c.inkDim, surface = c.top, background = c.top),
        content = content,
    )
}

/** The status colours of the web shell's dots and badges (up / down / attention). */
object Tone {
    val Up = Color(0xFF3FB950)
    val Down = Color(0xFF8B949E)
    val Warn = Color(0xFFD29922)
    val Bad = Color(0xFFF85149)
    val Card = Color(0x1AFFFFFF)
    val Line = Color(0x26FFFFFF)
}

/** What a page shows for one API read: loading, the value, or the reason it failed (never a crash). */
sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Err(val reason: String) : Load<Nothing>
}

/** Reads [path] (a JSON object) and re-reads it whenever [tick] moves; no polling of its own (battery: the user refreshes). */
@Composable
fun rememberObj(path: String, tick: Int): Load<JSONObject> {
    var st by remember(path) { mutableStateOf<Load<JSONObject>>(Load.Loading) }
    LaunchedEffect(path, tick) {
        st = when (val r = Api.get(path)) {
            is Api.Reply.Ok -> (r.value as? JSONObject)?.let { Load.Ok(it) } ?: Load.Err("$path: not an object")
            is Api.Reply.Err -> Load.Err(r.reason)
        }
    }
    return st
}

/** Like [rememberObj] for a route that answers a JSON array. */
@Composable
fun rememberArr(path: String, tick: Int): Load<JSONArray> {
    var st by remember(path) { mutableStateOf<Load<JSONArray>>(Load.Loading) }
    LaunchedEffect(path, tick) {
        st = when (val r = Api.get(path)) {
            is Api.Reply.Ok -> (r.value as? JSONArray)?.let { Load.Ok(it) } ?: Load.Err("$path: not a list")
            is Api.Reply.Err -> Load.Err(r.reason)
        }
    }
    return st
}

/** Draws [load]: a spinner, the reason in red, or [body]. */
@Composable
fun <T> Loaded(load: Load<T>, what: String, body: @Composable (T) -> Unit) {
    when (load) {
        Load.Loading -> Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
            Text("Loading $what…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        is Load.Err -> ErrorNote("$what: ${load.reason}")
        is Load.Ok -> body(load.value)
    }
}

@Composable
fun ErrorNote(text: String) {
    Text(text, color = Tone.Bad, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp).testTag("c3_error"))
}

@Composable
fun Note(text: String) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
}

/** One scrolling page body. */
@Composable
fun PageColumn(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp).testTag(tag), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** A card with a title row (badges, an optional action at the right): the web shell's <section><h2>. */
@Composable
fun Card(title: String, tag: String = "", badges: @Composable () -> Unit = {}, trailing: @Composable () -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Tone.Card, RoundedCornerShape(10.dp)).border(1.dp, Tone.Line, RoundedCornerShape(10.dp)).padding(vertical = 6.dp)
            .let { if (tag.isNotEmpty()) it.testTag(tag) else it },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp)
            Spacer(Modifier.width(6.dp))
            badges()
            Spacer(Modifier.weight(1f))
            trailing()
        }
        content()
    }
}

/** A key / value row (the web shell's .row .k .v). */
@Composable
fun Kv(k: String, v: String, up: Boolean? = null, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Row(Modifier.width(118.dp), verticalAlignment = Alignment.CenterVertically) {
            if (up != null) { Dot(up); Spacer(Modifier.width(6.dp)) }
            Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Text(v, style = MaterialTheme.typography.bodySmall.copy(fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
    }
}

@Composable
fun Dot(up: Boolean) {
    Box(Modifier.size(8.dp).background(if (up) Tone.Up else Tone.Down, CircleShape))
}

@Composable
fun Badge(text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Text(
        text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
        modifier = Modifier.padding(end = 4.dp).border(1.dp, color.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
    )
}

/** A dense action button (the web shell's .act). */
@Composable
fun Act(label: String, tag: String = "", enabled: Boolean = true, onClick: () -> Unit) {
    OutlinedButton(onClick, enabled = enabled, modifier = Modifier.padding(end = 6.dp).let { if (tag.isNotEmpty()) it.testTag(tag) else it }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun LinkText(label: String, tag: String = "", onClick: () -> Unit) {
    TextButton(onClick, modifier = if (tag.isNotEmpty()) Modifier.testTag(tag) else Modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 0.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Acts(content: @Composable () -> Unit) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) { content() }
}

/** A refresh counter for a page plus the POST runner: runs [Api.post], toasts the outcome, then bumps the counter. */
class Actions(private val ctx: Context, private val scope: kotlinx.coroutines.CoroutineScope, private val bump: () -> Unit) {
    fun post(path: String, body: JSONObject, ok: (JSONObject?) -> String = { "Done" }) {
        scope.launch {
            when (val r = Api.post(path, body)) {
                is Api.Reply.Ok -> toast(ctx, ok(r.value as? JSONObject))
                is Api.Reply.Err -> toast(ctx, r.reason)
            }
            bump()
        }
    }

    /** A server-described action ({post, body, label, disabled, why}) as the web shell's renderAct runs it. */
    fun run(a: JSONObject) {
        val path = a.optString("post")
        if (path.isEmpty()) return
        post(path, a.optJSONObject("body") ?: JSONObject()) { r -> r?.optString("url")?.takeIf { it.isNotBlank() }?.let { "Serving $it" } ?: "Done" }
    }
}

@Composable
fun rememberActions(onDone: () -> Unit): Actions {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(ctx, scope) { Actions(ctx, scope, onDone) }
}

/** A server-described action as a button (disabled with its reason when the server says so). */
@Composable
fun ActButton(a: JSONObject?, actions: Actions, tag: String = "") {
    if (a == null) return
    val ctx = LocalContext.current
    val disabled = a.optBoolean("disabled", false)
    Act(a.optString("label", "Run"), tag, enabled = true) { if (disabled) toast(ctx, a.optString("why", "not available")) else actions.run(a) }
}

/** A page-local refresh counter. */
@Composable
fun rememberTick(): Pair<Int, () -> Unit> {
    var t by remember { mutableIntStateOf(0) }
    return t to { t++ }
}

fun toast(ctx: Context, text: String) {
    runCatching { Toast.makeText(ctx, text, Toast.LENGTH_SHORT).show() }
}

fun copyText(ctx: Context, label: String, text: String) {
    runCatching {
        (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text))
        toast(ctx, "Copied")
    }.onFailure { toast(ctx, "Copy failed: ${it.message}") }
}

// ── JSON helpers: every read is optional, a missing key is a "-" on screen, never an exception ────────────────────────────

fun JSONObject?.str(key: String, def: String = "-"): String {
    if (this == null || !has(key) || isNull(key)) return def
    val v = opt(key)
    return if (v is JSONArray) (0 until v.length()).joinToString(", ") { v.opt(it).toString() } else v.toString()
}

fun JSONObject?.objs(key: String): List<JSONObject> {
    val a = this?.optJSONArray(key) ?: return emptyList()
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
}

fun JSONArray?.objs(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

fun JSONArray?.strs(): List<String> = if (this == null) emptyList() else (0 until length()).map { opt(it)?.toString().orEmpty() }

/** `[[k, v], ...]` rows (the shape of the about / sections JSON). */
fun JSONArray?.pairs(): List<Pair<String, String>> = if (this == null) emptyList() else (0 until length()).mapNotNull { i ->
    optJSONArray(i)?.let { it.opt(0)?.toString().orEmpty() to it.opt(1)?.toString().orEmpty() }
}

/** The web shell's fmtUp. */
fun fmtUp(sec: Long?): String = when {
    sec == null || sec < 0 -> "-"
    sec >= 3600 -> "${sec / 3600} h ${sec % 3600 / 60} min"
    sec >= 60 -> "${sec / 60} min ${sec % 60} s"
    else -> "$sec s"
}

fun JSONObject?.longOrNull(key: String): Long? = if (this == null || !has(key) || isNull(key)) null else optLong(key)
