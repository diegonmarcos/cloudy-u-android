package com.diegonmarcos.cloudme

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.cloudlib.gitsync.GitCredentialStore
import com.diegonmarcos.cloudlib.gitui.GitConnect
import com.diegonmarcos.cloudlib.gitui.GitConnectRules
import com.diegonmarcos.cloudlib.gitui.GitConnectSpec
import com.diegonmarcos.superapp.accountui.AccountUiHost
import com.diegonmarcos.superapp.jsoncanvas.CanvasLabels
import com.diegonmarcos.superapp.jsoncanvas.CanvasParser
import com.diegonmarcos.superapp.jsoncanvas.CanvasResult
import com.diegonmarcos.superapp.jsoncanvas.JsonCanvasView
import com.diegonmarcos.superapp.jsoncanvas.MarkdownLiteView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object RepoViewerTags {
    const val SELECTOR = "me:repoview:selector"
    fun chip(id: String) = "me:repoview:chip:$id"
    const val ERROR = "me:repoview:error"
    const val RETRY = "me:repoview:retry"
    const val HTML = "me:repoview:html"
    const val MD = "me:repoview:md"
}

/** #1932 the last view chosen per repo (key = [RepoViews.Ref.slug]). */
object RepoViewMemory {
    private const val PREFS = "me_repo_views"
    fun get(ctx: Context, ref: RepoViews.Ref): String? = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(ref.slug, null)
    fun put(ctx: Context, ref: RepoViews.Ref, view: String) { ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(ref.slug, view).apply() }
}

/**
 * Reads one repo file. A public file comes from raw.githubusercontent.com. When that says 401/403/404 the private route is tried with the token the app
 * already has for repo content: the host sign-in (AccountUiHost.ghToken, the gh engine), the credential filed as git.github_token, or the token the repo's
 * Configs > Repos connection stored (libs:git-ui GitConnect), through the contents API; failing that, the file of that connection's local clone.
 * The token is used for the one request and never stored or logged here. BLOCKS: call on IO.
 */
object RepoFetch {
    sealed class Result {
        data class Ok(val text: String) : Result()
        data class Fail(val failure: RepoViews.Failure) : Result()
    }

    private class Reply(val status: Int?, val body: String?, val error: String?)

    private fun get(url: String, token: String?, accept: String): Reply = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 20_000
            setRequestProperty("Accept", accept); setRequestProperty("User-Agent", "cloud-me")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = c.responseCode
            if (code == 200) Reply(code, c.inputStream.bufferedReader().use { it.readText() }, null) else Reply(code, null, null)
        } finally { c.disconnect() }
    }.getOrElse { Reply(null, null, "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}") }

    private fun spec(ref: RepoViews.Ref) = GitConnectSpec("gitconnect:${ref.owner}/${ref.repo}", ref.repo, ref.owner, ref.repo, ref.branch, emptyList())

    /** The token for [ref]: the host sign-in, the credential filed as git.github_token, else the one Configs > Repos stored for this repo (GitCredentialStore, the id GitConnect uses). */
    private fun token(ctx: Context, ref: RepoViews.Ref): String? = runCatching { AccountUiHost.current.ghToken(ctx) ?: AccountUiHost.current.appCredential(ctx, "git.github_token") }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: runCatching { GitCredentialStore(ctx.applicationContext).secret(GitConnectRules.credentialId(spec(ref))) }.getOrNull()

    fun fetch(ctx: Context, ref: RepoViews.Ref, file: String): Result {
        val rawUrl = ref.rawUrl(file)
        val first = get(rawUrl, null, "text/plain")
        first.body?.let { return Result.Ok(it) }
        if (first.status !in listOf(401, 403, 404)) return Result.Fail(RepoViews.Failure(rawUrl, first.status, first.error, authTried = false, tokenHeld = false))
        val token = token(ctx, ref)
        if (token != null) {
            val apiUrl = ref.apiUrl(file)
            val second = get(apiUrl, token, "application/vnd.github.raw")
            second.body?.let { return Result.Ok(it) }
            return Result.Fail(RepoViews.Failure(apiUrl, second.status, second.error, authTried = true, tokenHeld = true))
        }
        val path = if (ref.dir.isEmpty()) file else "${ref.dir}/$file"
        runCatching { GitConnect(ctx, spec(ref)).let { if (it.isCloned()) it.read(path) else null } }.getOrNull()?.let { return Result.Ok(it) }
        return Result.Fail(RepoViews.Failure(rawUrl, first.status, first.error, authTried = false, tokenHeld = false))
    }
}

object RepoViewerDecl {
    /** The repo and the declared views of a page block, or null when the block is not a repo entry with `views` (it then stays the plain WebPage). */
    fun of(block: JSONObject): Pair<RepoViews.Ref, List<String>>? {
        val ref = RepoViews.parse(block.optString("url")) ?: return null
        val arr = block.optJSONArray("views") ?: return null
        val views = RepoViews.declared((0 until arr.length()).map { arr.optString(it) })
        return if (RepoViews.GH in views) ref to views else null
    }
}

/** The view selector (GH Repo Page | README.md | README.html | README.canvas) over the chosen view of one repo entry. */
@Composable
fun RepoViewerScreen(block: JSONObject, ref: RepoViews.Ref, views: List<String>, modifier: Modifier = Modifier, pageId: String = block.optString("url")) {
    val ctx = LocalContext.current
    var view by remember(ref) { mutableStateOf(RepoViews.initial(RepoViewMemory.get(ctx, ref)).takeIf { it in views } ?: RepoViews.GH) }
    Column(modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp).testTag(RepoViewerTags.SELECTOR), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (v in views) FilterChip(
                selected = view == v,
                onClick = { view = v; RepoViewMemory.put(ctx, ref, v) },
                label = { Text(viewLabel(v), maxLines = 1) },
                modifier = Modifier.testTag(RepoViewerTags.chip(v)),
            )
        }
        val body = Modifier.weight(1f).fillMaxWidth()
        when (view) {
            RepoViews.GH -> WebPage(block, body, pageId = pageId)   // today's page, unchanged
            else -> RepoFileView(ctx, ref, view, body)
        }
    }
}

@Composable
private fun viewLabel(id: String) = when (id) {
    RepoViews.GH -> stringResource(R.string.repo_view_gh)
    RepoViews.MD -> stringResource(R.string.repo_view_md)
    RepoViews.HTML -> stringResource(R.string.repo_view_html)
    else -> stringResource(R.string.repo_view_canvas)
}

@Composable
private fun RepoFileView(ctx: Context, ref: RepoViews.Ref, view: String, modifier: Modifier) {
    val file = RepoViews.fileOf(view) ?: return
    var attempt by remember(ref, view) { mutableIntStateOf(0) }
    val result by produceState<RepoFetch.Result?>(null, ref, view, attempt) {
        value = null
        value = withContext(Dispatchers.IO) { RepoFetch.fetch(ctx, ref, file) }
    }
    when (val r = result) {
        null -> Column(modifier, verticalArrangement = Arrangement.Center, horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            CircularProgressIndicator(); Text(stringResource(R.string.repo_view_loading, ref.rawUrl(file)), Modifier.padding(12.dp))
        }
        is RepoFetch.Result.Fail -> FailureView(r.failure, file, modifier) { attempt++ }
        is RepoFetch.Result.Ok -> when (view) {
            RepoViews.MD -> Column(modifier.verticalScroll(rememberScrollState()).padding(12.dp).testTag(RepoViewerTags.MD)) { MarkdownLiteView(r.text) }
            RepoViews.HTML -> HtmlView(r.text, ref, modifier)
            else -> when (val parsed = remember(r.text) { CanvasParser.parse(r.text) }) {
                is CanvasResult.Ok -> JsonCanvasView(parsed.canvas, modifier, canvasLabels()) { target -> open(ctx, if (target.startsWith("http")) target else ref.githubBlobUrl(target)) }
                is CanvasResult.Err -> FailureView(null, file, modifier, parseError = "${ref.rawUrl(file)}: ${parsed.error.message}") { attempt++ }
            }
        }
    }
}

@Composable
private fun canvasLabels() = CanvasLabels(
    fit = stringResource(R.string.canvas_fit), open = stringResource(R.string.canvas_open), close = stringResource(R.string.canvas_close),
    file = stringResource(R.string.canvas_file), link = stringResource(R.string.canvas_link), group = stringResource(R.string.canvas_group), empty = stringResource(R.string.canvas_empty),
)

private fun open(ctx: Context, url: String) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }

/** The fetched text rendered against the repo's raw base URL (raw.githubusercontent.com serves text/plain, so the body is loaded, not navigated to). JavaScript stays off. */
@Composable
private fun HtmlView(html: String, ref: RepoViews.Ref, modifier: Modifier) {
    val ctx = LocalContext.current
    AndroidView(
        modifier = modifier.testTag(RepoViewerTags.HTML),
        factory = { c ->
            WebView(c).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                setBackgroundColor(0xFFFFFFFF.toInt())
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                        request?.url?.toString()?.let { if (it.startsWith("http")) open(ctx, it) }
                        return true
                    }
                }
                loadDataWithBaseURL(ref.baseUrl(), html, "text/html", "utf-8", null)
            }
        },
        onRelease = { it.stopLoading(); it.destroy() },
    )
}

/** A file that could not be shown: the exact URL, the HTTP status or exception, what it means, and Retry. */
@Composable
private fun FailureView(f: RepoViews.Failure?, file: String, modifier: Modifier, parseError: String? = null, onRetry: () -> Unit) {
    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp).testTag(RepoViewerTags.ERROR), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.repo_view_failed_title, file), style = MaterialTheme.typography.titleMedium)
        if (parseError != null) Text(stringResource(R.string.repo_view_bad_canvas, parseError))
        if (f != null) {
            Text(stringResource(R.string.repo_view_detail_url, f.url))
            Text(if (f.status != null) stringResource(R.string.repo_view_detail_status, f.status) else stringResource(R.string.repo_view_detail_error, f.error.orEmpty()))
            Text(when (RepoViews.kind(f)) {
                RepoViews.Kind.NotFound -> stringResource(R.string.repo_view_kind_notfound)
                RepoViews.Kind.PrivateNoCredential -> stringResource(R.string.repo_view_kind_private)
                RepoViews.Kind.CredentialRefused -> stringResource(R.string.repo_view_kind_refused)
                RepoViews.Kind.Server -> stringResource(R.string.repo_view_kind_server)
                RepoViews.Kind.Network -> stringResource(R.string.repo_view_kind_network)
            })
        }
        Button(onClick = onRetry, Modifier.testTag(RepoViewerTags.RETRY)) { Text(stringResource(R.string.repo_view_retry)) }
    }
}
