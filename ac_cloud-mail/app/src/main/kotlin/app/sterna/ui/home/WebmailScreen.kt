package app.sterna.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import app.sterna.BuildConfig
import app.sterna.R
import app.sterna.ui.components.Icon
import app.sterna.ui.components.IconButton
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile

/**
 * Home > Webmail: the fleet's webmail page in an embedded WebView.
 *
 * The address is [BuildConfig.WEBMAIL_URL], baked at build time from the fleet's own declaration
 * (build.json::web.target looked up in ac_cloud-c3/data/infra_slice.json), so it is never typed
 * in here. The view is built by libs:web-core ([CloudWeb], BROWSER profile: sign-in popups, cookies);
 * system Back walks its history first and only then leaves the page.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WebmailScreen(onBack: () -> Unit) {
    var webView by remember { mutableStateOf<WebView?>(null) }

    BackHandler {
        val wv = webView
        if (wv != null && wv.canGoBack()) wv.goBack() else onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.home_webmail)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    CloudWeb.create(context, WebProfile.BROWSER).apply {
                        webView = this
                        loadUrl(BuildConfig.WEBMAIL_URL)
                    }
                },
                onRelease = { it.destroy(); if (webView === it) webView = null },
            )
        }
    }
}
