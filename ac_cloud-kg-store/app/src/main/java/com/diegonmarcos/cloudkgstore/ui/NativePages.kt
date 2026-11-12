package com.diegonmarcos.cloudkgstore.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudkgstore.HeadlessService
import com.diegonmarcos.cloudkgstore.KgServices
import com.diegonmarcos.cloudkgstore.MeRepo
import com.diegonmarcos.cloudkgstore.R
import com.diegonmarcos.cloudlib.gitui.GitConnect
import com.diegonmarcos.cloudlib.gitui.GitConnectSpec
import com.diegonmarcos.cloudlib.localserve.ServedPage
import com.diegonmarcos.cloudlib.localserve.ServedPageLabels
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

object NativeTags {
    const val SCRAPPED = "scrapped_list"
    const val ME_STATE = "me_state"
    const val ME_TOKEN = "me_token"
    const val ME_CONNECT = "me_connect"
    const val ME_PULL = "me_pull"
    const val ME_FRAME = "me_frame"
    const val ME_PAGE = "me_page"
    fun card(id: String) = "scrapped:$id"
    fun home(id: String) = "home:$id"
}

/** Scrapped: one card per service of the shared Headless Browsers declaration (title, tagline, stack, note, API/MCP link). */
@Composable
fun ScrappedPage(svc: KgServices) {
    // The list is the kg dataset's (#1547). A phone still holding the copy from before the list was added gets it at Cloud
    // Store's next sync: keep asking (off the main thread) until it is there, so the page fills in without a restart.
    var rows by remember { mutableStateOf(svc.kg.headless) }
    LaunchedEffect(Unit) { while (rows.isEmpty()) { kotlinx.coroutines.delay(3_000); rows = withContext(Dispatchers.IO) { svc.reload().headless } } }
    if (rows.isEmpty()) { KgNote(stringResource(R.string.scrapped_none)); return }
    LazyColumn(Modifier.fillMaxSize().testTag(NativeTags.SCRAPPED)) {
        item { KgHeading(stringResource(R.string.scrapped_heading)) }
        items(rows, key = { it.id }) { s ->
            Column(Modifier.fillMaxWidth().testTag(NativeTags.card(s.id))) {
                KgRow(s.title, s.tagline, s.service.takeIf { it.isNotBlank() }?.let { stringResource(R.string.scrapped_service, it) }.orEmpty())
                for ((k, v) in s.stack) Text("• ${k.replaceFirstChar { it.uppercase() }}: $v", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(horizontal = 12.dp, vertical = 1.dp))
                if (s.note.isNotBlank()) Text(s.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp))
                s.link?.let { l -> KgRow(stringResource(R.string.scrapped_link, l.label), l.url, l.detail, onClick = { svc.open(l.url) }) }
            }
        }
    }
}

/** Home: the overview of the bottom nav. One row per other section of the declaration, each opening it. */
@Composable
fun HomePage(entries: List<Pair<String, String>>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize()) {
        item { KgHeading(stringResource(R.string.home_heading)) }
        items(entries, key = { it.first }) { (id, label) ->
            KgRow(label, onClick = { onOpen(id) })
        }
    }
}

/** The html a clone serves: the declared [entry] if it is a file, else the first html at the root, else the first anywhere below it. */
fun meEntry(dir: File, entry: String): File? {
    if (entry.isNotBlank() && !entry.startsWith("/") && entry.split('/').none { it == ".." }) File(dir, entry).takeIf { it.isFile }?.let { return it }
    fun isHtml(f: File) = f.isFile && f.extension.lowercase() in setOf("html", "htm")
    dir.listFiles()?.filter(::isHtml)?.minByOrNull { it.name }?.let { return it }
    return dir.walkTopDown().onEnter { it.name != ".git" }.filter(::isHtml).minByOrNull { it.relativeTo(dir).path }
}

/**
 * #1669 the Me page's served frame: the stale note (when a pull failed) and the page, which takes EVERY remaining pixel between the
 * fleet's top bar and the bottom nav (weight(1f), no scroll parent around the web view), on the app's surface with a loading / error
 * state of libs:local-serve, never a black or wrap-content strip.
 */
@Composable
fun MeServedFrame(file: File, stale: String?) {
    val collapse = com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse.current
    Column(Modifier.fillMaxSize().testTag(NativeTags.ME_FRAME)) {
        if (stale != null) KgNote(stale, error = true)
        ServedPage(
            file,
            Modifier.weight(1f).fillMaxWidth().testTag(NativeTags.ME_PAGE),
            background = MaterialTheme.colorScheme.surface,
            // #1483 the served page's scroll feeds the shared chrome (a WebView reports no nested scroll): reading down hides the bars
            onWebView = { wv -> com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(wv, collapse) },
            onPageShown = { wv -> com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(wv) },
            labels = ServedPageLabels(
                loading = stringResource(R.string.me_loading),
                failed = stringResource(R.string.me_page_failed),
                serverDown = stringResource(R.string.me_server_down),
                retry = stringResource(R.string.me_retry),
            ),
        )
    }
}

/**
 * Me: the declared repository is CLONED AT RUNTIME through libs:git-ui's GitConnect (shallow; pulled on every open) and its html
 * is served from the clone by libs:local-serve, the Git Sites way. Nothing of the repo is in the APK. A repo that needs a login
 * asks once for a GitHub token, which GitConnect keeps encrypted; nothing is typed in the declaration.
 */
@Composable
fun MePage() {
    val decl = MeRepo.declared
    if (decl == null) { KgNote(stringResource(R.string.me_undeclared), error = true); return }
    val ctx = LocalContext.current
    val gc = remember(decl) { GitConnect(ctx, GitConnectSpec("me", "Me", decl.owner, decl.repo, decl.ref, emptyList())) }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(true) }
    var line by remember { mutableStateOf("") }
    var ok by remember { mutableStateOf(false) }
    var served by remember { mutableStateOf<File?>(null) }
    var typed by remember { mutableStateOf("") }

    LaunchedEffect(gc, tick) {
        busy = true
        val r = withContext(Dispatchers.IO) { runCatching { gc.open() }.getOrNull() }
        ok = r?.ok == true
        line = r?.summary.orEmpty()
        // A failed pull of an existing clone still serves what is on disk.
        served = withContext(Dispatchers.IO) { if (gc.isCloned()) meEntry(gc.dir, decl.entry) else null }
        busy = false
    }

    Column(Modifier.fillMaxSize()) {
        val f = served
        if (f != null) {
            MeServedFrame(f, if (!ok && line.isNotBlank()) stringResource(R.string.me_stale, line) else null)
        } else {
            Column(Modifier.padding(16.dp).testTag(NativeTags.ME_STATE), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${decl.owner}/${decl.repo}@${decl.ref}", style = MaterialTheme.typography.titleSmall)
                Text(
                    when { busy -> stringResource(R.string.me_cloning); ok -> stringResource(R.string.me_no_html); else -> stringResource(R.string.me_failed, line) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!busy && !ok) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (!busy) {
                    TextButton({ tick++ }, Modifier.testTag(NativeTags.ME_PULL)) { Text(stringResource(R.string.me_retry)) }
                    if (!ok) {
                        OutlinedTextField(typed, { typed = it }, Modifier.fillMaxWidth().testTag(NativeTags.ME_TOKEN), singleLine = true,
                            label = { Text(stringResource(R.string.me_token_hint)) }, visualTransformation = PasswordVisualTransformation())
                        Row { Button({ scope.launch { gc.saveToken(typed); typed = ""; tick++ } }, Modifier.testTag(NativeTags.ME_CONNECT), enabled = typed.isNotBlank()) { Text(stringResource(R.string.me_connect)) } }
                    }
                }
            }
        }
    }
}
