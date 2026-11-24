package com.diegonmarcos.clouddrive.share

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.diegonmarcos.clouddrive.MainActivity
import com.diegonmarcos.clouddrive.PdfReaderActivity
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.editor.EditorHook
import com.diegonmarcos.clouddrive.editor.ExternalOpen

/**
 * #1888 "Open with" in the share sheet: the shared file(s) open straight in Drive's own Editor/viewer, as a VIEW does. No folder prompt:
 * a content:// stream is copied into the cache area first (ExternalOpen), a PDF goes to PdfReaderActivity, the rest to an Editor tab.
 */
class ShareOpenActivity : ComponentActivity() {
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND_MULTIPLE -> intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
            else -> emptyList()
        }
        if (uris.isEmpty()) { Toast.makeText(this, R.string.share_nothing, Toast.LENGTH_LONG).show(); finish(); return }
        val app = applicationContext
        Thread {
            var editor = false
            for (u in uris) {
                val mime = runCatching { app.contentResolver.getType(u) }.getOrNull() ?: intent.type
                if (ShareImport.openTarget(mime, u.lastPathSegment) == ShareImport.OpenTarget.PDF_READER) {
                    runOnUiThread { startActivity(Intent(this, PdfReaderActivity::class.java).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                } else {
                    val f = ExternalOpen.resolve(app, u)
                    if (f != null) { editor = true; runOnUiThread { EditorHook.open(f.absolutePath) } }
                }
            }
            runOnUiThread {
                if (editor) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }.start()
    }
}
