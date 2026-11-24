package com.diegonmarcos.superapp.share

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
                    is PeerShare.Pick.None -> noPeer()
                    is PeerShare.Pick.Auto -> send(p.id, items, uris)
                    is PeerShare.Pick.Ask -> AlertDialog.Builder(this).setTitle(R.string.share_peer_choose)
                        .setItems(p.ids.map { reachable[it] ?: it }.toTypedArray()) { _, i -> send(p.ids[i], items, uris) }
                        .setOnCancelListener { finish() }.show()
                }
            }
        }.start()
    }

    private fun noPeer() {
        AlertDialog.Builder(this).setTitle(R.string.share_peer_none).setMessage(R.string.share_peer_none_detail)
            .setPositiveButton(R.string.share_peer_open) { _, _ ->
                packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
                finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.show()
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
        AlertDialog.Builder(this).setTitle(R.string.share_peer_result).setMessage(lines.joinToString("\n"))
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    }
}
