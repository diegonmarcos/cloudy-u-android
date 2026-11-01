package com.diegonmarcos.clouddrive.sync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile

/** #1395 the menu of the repository web viewer, in order; pure so RepoWebViewerTest pins it. */
object RepoWebMenu {
    enum class Item { OTHER_BROWSER, CHROME, COPY, SHARE, RELOAD }
    val ORDER = listOf(Item.OTHER_BROWSER, Item.CHROME, Item.COPY, Item.SHARE, Item.RELOAD)

    /** The system chooser over every app that views [url] (never this viewer). */
    fun chooser(url: String, title: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_VIEW, Uri.parse(url)), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun share(url: String, title: String): Intent =
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url), title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}

/**
 * #1395 Git ▸ Open repo on web, IN the app: libs:web-core's CloudWeb with the BROWSER profile (the fleet's one web view, not a
 * fork), full screen; a top-right ⋮ menu (Open in another browser, Open in Chrome, Copy link, Share, Reload); Back closes it.
 */
@Composable
fun RepoWebViewer(url: String, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var web by remember { mutableStateOf<WebView?>(null) }
    var title by remember { mutableStateOf(url) }
    var menu by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = onClose)
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface).testTag(DriveTags.SYNC_GIT_STORAGE + "_web")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, stringResource(R.string.repo_status_window_close)) }
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Box {
                    IconButton(onClick = { menu = true }, modifier = Modifier.testTag(DriveTags.SYNC_GIT_STORAGE + "_web_menu")) { Icon(Icons.Filled.MoreVert, stringResource(R.string.repo_web_menu)) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        val current = web?.url ?: url
                        RepoWebMenu.ORDER.forEach { item ->
                            DropdownMenuItem(
                                text = { Text(stringResource(when (item) {
                                    RepoWebMenu.Item.OTHER_BROWSER -> R.string.repo_web_other_browser
                                    RepoWebMenu.Item.CHROME -> R.string.repo_web_chrome
                                    RepoWebMenu.Item.COPY -> R.string.repo_web_copy
                                    RepoWebMenu.Item.SHARE -> R.string.repo_web_share
                                    RepoWebMenu.Item.RELOAD -> R.string.repo_web_reload
                                })) },
                                onClick = {
                                    menu = false
                                    when (item) {
                                        RepoWebMenu.Item.OTHER_BROWSER -> runCatching { ctx.startActivity(RepoWebMenu.chooser(current, ctx.getString(R.string.repo_web_other_browser))) }
                                        RepoWebMenu.Item.CHROME -> CloudWeb.openInChrome(ctx, current)
                                        RepoWebMenu.Item.COPY -> ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("url", current))
                                        RepoWebMenu.Item.SHARE -> runCatching { ctx.startActivity(RepoWebMenu.share(current, ctx.getString(R.string.repo_web_share))) }
                                        RepoWebMenu.Item.RELOAD -> web?.reload()
                                    }
                                },
                                modifier = Modifier.testTag(DriveTags.SYNC_GIT_STORAGE + "_web_" + item.name.lowercase()),
                            )
                        }
                    }
                }
            }
            AndroidView(
                factory = { c: Context ->
                    CloudWeb.create(c, WebProfile.BROWSER).apply {
                        webViewClient = object : CloudWebClient(WebProfile.BROWSER) {
                            override fun onPageFinished(view: WebView?, u: String?) { super.onPageFinished(view, u); view?.title?.takeIf { it.isNotBlank() }?.let { title = it } }
                        }
                        loadUrl(url)
                    }.also { web = it }
                },
                onRelease = { it.destroy() },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
