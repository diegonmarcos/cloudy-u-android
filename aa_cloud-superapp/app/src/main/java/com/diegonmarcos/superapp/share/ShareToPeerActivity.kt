package com.diegonmarcos.superapp.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.kdeconnect.KdeConnectConfig
import com.diegonmarcos.superapp.kdeconnect.KdeConnectManager
import com.diegonmarcos.superapp.kdeconnect.KdeDevicePrefs
import com.diegonmarcos.superapp.kdeconnect.KdePeers
import com.diegonmarcos.superapp.kdeconnect.SharePlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

/**
 * #1888 "Send to peer" in the share sheet (Cloud SuperApp / Send to peer, Peer Control's KDE Connect). Dials the declared peers
 * (KdePeers), keeps the paired ones that answer, auto-picks when exactly one does, otherwise asks; then every shared stream goes through
 * SharePlugin.sendFile and EXTRA_TEXT through the same text/link share the Peer Control page uses. Sent/failed per item, then it finishes.
 * With no paired reachable peer it says so and offers to open Peer Control (the app).
 */
class ShareToPeerActivity : ComponentActivity() {

    @Suppress("DEPRECATION")
    private fun streams(): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        else -> emptyList()
    }

    /** One dialog at a time: choose a peer, no peer, or the per-item result (Compose). */
    private sealed class Stage {
        data class Choose(val ids: List<String>, val labels: List<String>, val items: List<PeerShare.Item>) : Stage()
        data object None : Stage()
        data class Result(val text: String) : Stage()
    }
    private var stage by mutableStateOf<Stage?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply { setContent { CloudKitTheme(LauncherPalette.kit(this@ShareToPeerActivity)) { Dialog() } } })
        if (savedInstanceState != null) return
        val uris = streams()
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val items = PeerShare.items(intent.action, uris.map { it.toString() }, text)
        if (items.isEmpty()) { Toast.makeText(this, R.string.share_peer_nothing, Toast.LENGTH_LONG).show(); finish(); return }
        Toast.makeText(this, R.string.share_peer_looking, Toast.LENGTH_SHORT).show()
        val app = applicationContext
        Thread {
            KdeConnectManager.init(app)
            val prefs = KdeDevicePrefs(app)
            val port = KdeConnectConfig.get().discoveryPort
            // device id (link key) -> label, for every declared peer that is paired and answers
            val reachable = LinkedHashMap<String, String>()
            runBlocking {
                KdePeers.all().map { dev ->
                    async {
                        val id = KdeConnectManager.connect(prefs.host(dev.id, dev.wgIp), prefs.port(dev.id, port)).getOrNull()
                        if (id != null && KdeConnectManager.isPaired(id) && KdeConnectManager.isConnected(id)) synchronized(reachable) { reachable[id] = prefs.label(dev.id, dev.label) }
                    }
                }.awaitAll()
            }
            runOnUiThread {
                when (val p = PeerShare.pick(reachable.keys.toList())) {
                    is PeerShare.Pick.None -> stage = Stage.None
                    is PeerShare.Pick.Auto -> send(p.id, items, uris)
                    is PeerShare.Pick.Ask -> stage = Stage.Choose(p.ids, p.ids.map { reachable[it] ?: it }, items)
                }
            }
        }.start()
    }

    private fun name(u: Uri): String = runCatching {
        contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull() ?: u.lastPathSegment ?: "file"

    private fun send(id: String, items: List<PeerShare.Item>, uris: List<Uri>) {
        val link = KdeConnectManager.link(id)
        val lines = items.map { item ->
            when (item) {
                is PeerShare.Item.File -> {
                    val u = Uri.parse(item.uri)
                    PeerShare.line(name(u), link != null && SharePlugin.sendFile(this, link, u))
                }
                is PeerShare.Item.Text -> PeerShare.line("text", KdeConnectManager.send(id, SharePlugin.text(item.text)))
                is PeerShare.Item.Link -> PeerShare.line("link", KdeConnectManager.send(id, SharePlugin.url(item.url)))
            }
        }
        stage = Stage.Result(lines.joinToString("\n"))
    }

    @Composable
    private fun Dialog() {
        when (val st = stage) {
            null -> {}
            is Stage.Choose -> AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.share_peer_choose)) },
                text = { Column { st.ids.forEachIndexed { i, id -> TextButton(onClick = { stage = null; send(id, st.items, emptyList()) }) { Text(st.labels[i]) } } } },
                confirmButton = {},
            )
            is Stage.None -> AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.share_peer_none)) },
                text = { Text(stringResource(R.string.share_peer_none_detail)) },
                confirmButton = { TextButton(onClick = { packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }; finish() }) { Text(stringResource(R.string.share_peer_open)) } },
                dismissButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.cancel)) } },
            )
            is Stage.Result -> AlertDialog(
                onDismissRequest = { finish() },
                title = { Text(stringResource(R.string.share_peer_result)) },
                text = { Text(st.text) },
                confirmButton = { TextButton(onClick = { finish() }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
    }
}
