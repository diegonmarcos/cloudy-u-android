package com.diegonmarcos.cloudsuperintel

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudsuperintel.ui.SiTheme
import com.diegonmarcos.superapp.ai.AiAttachment
import com.diegonmarcos.superapp.ai.AiAttachments
import java.io.File
import java.util.UUID

/**
 * "Ask AI · Cloud SI": the target of the open-with chooser, the share sheet and the text selection toolbar. It copies what it
 * was given into the cache while the source app's URI grant lives (a file over [AskAi.MAX_BYTES] is refused with its size),
 * routes each file by mime ([AskAi.route]: images are vision input, a PDF goes to the fleet PDF extractor and falls back to the
 * wire's own file part, text is read as is, audio, video and documents carry a note that no extractor exists), then shows the
 * composer prefilled with "Explain this" and the quick actions. Ask opens a NEW SI chat holding the attachments and the text;
 * the model is the chat's own pick (the fleet's AI engine and model selection). No key is held or read here, and no storage
 * permission is needed: the URI grant of the intent is enough.
 */
class AskAiActivity : ComponentActivity() {
    private var prompt by mutableStateOf(AskAi.DEFAULT_PROMPT)
    private var busy by mutableStateOf(true)
    private var error by mutableStateOf<String?>(null)
    private var files by mutableStateOf<List<AiAttachment>>(emptyList())
    private var notes by mutableStateOf<List<String>>(emptyList())
    private var text by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val req = request(intent)
        if (req == null) { finish(); return }
        text = req.text
        setContent { SiTheme { Sheet() } }
        val app = applicationContext
        Thread {
            val out = runCatching { copyAll(app, req) }.getOrElse { Triple(emptyList(), emptyList(), it.message ?: "cannot read the shared file") }
            runOnUiThread { files = out.first; notes = out.second; error = out.third; busy = false }
        }.start()
    }

    @androidx.compose.runtime.Composable
    private fun Sheet() {
        Surface(Modifier.fillMaxWidth().padding(top = 40.dp, bottom = 24.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Ask AI · Cloud SI", style = MaterialTheme.typography.titleLarge)
                if (busy) Text("Copying into SI…")
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (f in files) AssistChip(onClick = {}, label = { Text(f.name + " · " + AskAi.route(f.mime).name.lowercase() + " · " + f.bytes / 1000 + " KB") })
                }
                for (n in notes) Text(n, style = MaterialTheme.typography.bodySmall)
                text?.let { Text(it.take(400), style = MaterialTheme.typography.bodySmall, maxLines = 6) }
                OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("Prompt") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (q in AskAi.quick()) AssistChip(onClick = { prompt = q.prompt }, label = { Text(q.label) })
                }
                Button(onClick = ::ask, enabled = !busy && (files.isNotEmpty() || text != null)) { Text("Ask SI") }
            }
        }
    }

    /** A NEW SI chat holding the files and the composer text, then the chat itself. */
    private fun ask() {
        val chat = SiAi.chat(applicationContext)
        chat.fresh()
        for (f in files) chat.attach(f)
        chat.draft = AskAi.compose(prompt, notes, text)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun request(i: Intent): AskAi.Request? {
        val stream = ArrayList<String>()
        @Suppress("DEPRECATION")
        when (i.action) {
            Intent.ACTION_SEND -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { stream.add(it.toString()) }
            Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) i.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else i.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM))?.forEach { stream.add(it.toString()) }
        }
        return AskAi.parse(i.action, i.type, i.dataString, stream, i.getCharSequenceExtra(Intent.EXTRA_TEXT), i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT))
    }

    /** Blocking. (attachments, notes, error): a file over the cap stops the import and nothing of the share is attached. */
    private fun copyAll(app: Context, req: AskAi.Request): Triple<List<AiAttachment>, List<String>, String?> {
        val out = ArrayList<AiAttachment>()
        val notes = ArrayList<String>()
        var total = 0L
        for (u in req.uris) {
            val uri = Uri.parse(u)
            val size = app.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else -1L } ?: -1L
            val label = uri.lastPathSegment.orEmpty().substringAfterLast('/').ifEmpty { "file" }
            AskAi.capError(label, size)?.let { return Triple(emptyList(), emptyList(), it) }
            AskAi.totalError(total, maxOf(size, 0L))?.let { return Triple(emptyList(), emptyList(), it) }
            val a = AiAttachments.import(app, uri, AskAi.MAX_BYTES).getOrElse { return Triple(emptyList(), emptyList(), it.message ?: "cannot read $label") }
            total += a.bytes
            val kind = AskAi.route(a.mime)
            if (kind == AskAi.Kind.PDF) {
                val cfg = NotebookConfig.current(app)
                val t = FleetRank.shared(cfg)?.pdfText(a.file.readBytes())?.take(AskAi.MAX_TEXT)
                if (t != null) {
                    val f = File(a.file.parentFile, a.file.nameWithoutExtension + ".txt").also { it.writeText(t) }
                    a.file.delete()
                    out.add(AiAttachment(UUID.randomUUID().toString(), f.name, "text/plain", f.path, f.length()))
                    continue
                }
            }
            AskAi.note(kind, a.name, a.mime, a.bytes).takeIf { kind != AskAi.Kind.OTHER }?.let { notes.add(it) }
            out.add(a)
        }
        return Triple(out, notes, null)
    }
}
