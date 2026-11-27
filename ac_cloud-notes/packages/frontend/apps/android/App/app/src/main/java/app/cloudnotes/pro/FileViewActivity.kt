package app.cloudnotes.pro

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import app.cloudnotes.pro.plugin.ExternalFilePlugin
import java.security.MessageDigest

/**
 * #1866 Cloud Notes is offered for every file. [OpenRouter] decides: images, PDF, audio and video are shown here (image view,
 * PDF shown in Notes' own reader (PdfViewActivity, libs:pdf), VideoView for audio and video); any other binary gets the file view (name, size, type, SHA-256, hex preview)
 * with the actions open as text, attach to a new note, open with another app. Every read is guarded: an unreadable URI or an
 * unknown type shows a message in the view and never crashes.
 */
class FileViewActivity : Activity() {
    private var uri: Uri? = null
    private var mime: String? = null
    private var name: String = "file"
    private var size: Long = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        setContentView(ScrollView(this).apply { addView(root) })
        try {
            uri = intent?.data
            val u = uri
            if (u == null) { root.addView(label("No file was given.")); return }
            mime = intent.type ?: try { contentResolver.getType(u) } catch (_: Exception) { null }
            queryNameSize(u)
            title = name
            root.addView(label(name, bold = true))
            var route = OpenRouter.route(mime, name)
            // A PDF that arrives as octet-stream with no extension is still a PDF: look at its first bytes.
            if (route == OpenRoute.BINARY && sniffPdf(u)) route = OpenRoute.PDF
            when (route) {
                OpenRoute.IMAGE -> showImage(root, u)
                OpenRoute.PDF -> showPdf(root, u)
                OpenRoute.AUDIO, OpenRoute.VIDEO -> showMedia(root, u)
                else -> showBinary(root, u)
            }
        } catch (e: Throwable) {
            root.addView(label("Cannot show this file (${e.message ?: e.javaClass.simpleName})."))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    private fun sniffPdf(u: Uri): Boolean = try {
        contentResolver.openInputStream(u)?.use { i -> OpenRouter.isPdfMagic(OpenRouter.readHead(i, 5)) } ?: false
    } catch (_: Exception) { false }

    private fun label(t: String, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = t
        textSize = if (mono) 11f else 14f
        setTextColor(Color.BLACK)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setTextIsSelectable(true)
    }

    private fun queryNameSize(u: Uri) {
        if (u.scheme == "content") {
            try {
                contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        c.getString(0)?.takeIf { it.isNotBlank() }?.let { name = it }
                        if (!c.isNull(1)) size = c.getLong(1)
                    }
                }
            } catch (_: Exception) {}
        } else {
            u.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }?.let { name = it }
            try { u.path?.let { size = java.io.File(it).length() } } catch (_: Exception) {}
        }
    }

    private fun showImage(root: LinearLayout, u: Uri) {
        val iv = ImageView(this).apply {
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        try { iv.setImageURI(u) } catch (e: Exception) { root.addView(label("Cannot decode the image (${e.message}).")) }
        root.addView(iv)
        addActions(root, withText = false)
    }

    /**
     * #1949 PDF opens in Cloud Notes' own reader (PdfViewActivity, libs:pdf), which copies the stream into the app cache first so a
     * temporary grant (Gmail) cannot lapse under it. Never hands the URI to another app any more; Open with stays an action there.
     */
    private fun showPdf(root: LinearLayout, u: Uri) {
        try {
            val view = Intent(this, PdfViewActivity::class.java).setDataAndType(u, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            view.clipData = android.content.ClipData.newRawUri(name, u)
            startActivity(view)
            finish()
        } catch (e: Exception) {
            root.addView(label("Cannot open this PDF (${e.message ?: e.javaClass.simpleName})."))
            addActions(root, withText = false)
        }
    }

    private fun showMedia(root: LinearLayout, u: Uri) {
        try {
            val vv = VideoView(this)
            vv.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 700)
            val mc = MediaController(this)
            mc.setAnchorView(vv)
            vv.setMediaController(mc)
            vv.setVideoURI(u)
            vv.setOnErrorListener { _, _, _ ->
                Toast.makeText(this, "This media cannot be played here.", Toast.LENGTH_SHORT).show(); true
            }
            root.addView(vv)
            vv.start()
        } catch (e: Exception) {
            root.addView(label("Cannot play the file (${e.message ?: "unreadable"})."))
        }
        addActions(root, withText = false)
    }

    private fun showBinary(root: LinearLayout, u: Uri) {
        val info = label("Size: ${OpenRouter.humanSize(size)}\nType: ${mime?.takeIf { it.isNotBlank() } ?: "unknown"}\nSHA-256: computing...")
        root.addView(info)
        val preview = label("", mono = true)
        root.addView(preview)
        addActions(root, withText = true)
        Thread {
            var hash = "unreadable"
            var hex = "The file cannot be read."
            try {
                val md = MessageDigest.getInstance("SHA-256")
                val head = java.io.ByteArrayOutputStream()
                var total = 0L
                contentResolver.openInputStream(u)?.use { inp ->
                    val b = ByteArray(8192)
                    while (true) {
                        val n = inp.read(b)
                        if (n < 0) break
                        md.update(b, 0, n)
                        if (head.size() < 512) head.write(b, 0, minOf(n, 512 - head.size()))
                        total += n
                    }
                    hash = md.digest().joinToString("") { "%02x".format(it) }
                    hex = if (total == 0L) "(empty file)" else OpenRouter.hexPreview(head.toByteArray())
                    if (size < 0) size = total
                }
            } catch (e: Exception) {
                hex = "The file cannot be read (${e.message ?: "unknown error"})."
            }
            runOnUiThread {
                info.text = "Size: ${OpenRouter.humanSize(size)}\nType: ${mime?.takeIf { it.isNotBlank() } ?: "unknown"}\nSHA-256: $hash"
                preview.text = hex
            }
        }.start()
    }

    private fun addActions(root: LinearLayout, withText: Boolean) {
        if (withText) {
            root.addView(Button(this).apply { text = "Open as text"; setOnClickListener { openAsText() } })
            root.addView(Button(this).apply { text = "Attach to a new note"; setOnClickListener { attachToNote() } })
        }
        root.addView(Button(this).apply { text = "Open with another app"; setOnClickListener { openWithOther() } })
    }

    // text/plain forces the editor import (ExternalFilePlugin.capture), whatever the sender called the file.
    private fun openAsText() {
        val u = uri ?: return
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW).setClass(this, MainActivity::class.java)
                    .setDataAndType(u, "text/plain").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            finish()
        } catch (e: Exception) { Toast.makeText(this, "Cannot open as text (${e.message}).", Toast.LENGTH_SHORT).show() }
    }

    // A new note describing the file (name, type, size, hash) with the file inlined as a data: link up to 512 KiB.
    private fun attachToNote() {
        val u = uri ?: return
        Thread {
            try {
                val limit = 512 * 1024
                val md = MessageDigest.getInstance("SHA-256")
                val buf = java.io.ByteArrayOutputStream()
                contentResolver.openInputStream(u)?.use { inp ->
                    val b = ByteArray(8192)
                    while (true) {
                        val n = inp.read(b)
                        if (n < 0) break
                        md.update(b, 0, n)
                        if (buf.size() <= limit) buf.write(b, 0, n)
                    }
                } ?: throw java.io.IOException("cannot open the file")
                val hash = md.digest().joinToString("") { "%02x".format(it) }
                val type = mime?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
                val sb = StringBuilder("# $name\n\n- Type: $type\n- Size: ${OpenRouter.humanSize(size)}\n- SHA-256: $hash\n\n")
                if (buf.size() <= limit) sb.append("[$name](data:$type;base64,${Base64.encodeToString(buf.toByteArray(), Base64.NO_WRAP)})\n")
                else sb.append("The file is larger than 512 KiB and is not embedded.\n")
                ExternalFilePlugin.offerNote("$name.md", sb.toString())
                runOnUiThread {
                    startActivity(Intent(ExternalFilePlugin.ACTION_NEW_NOTE).setClass(this, MainActivity::class.java))
                    finish()
                }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Cannot attach (${e.message ?: "unreadable"}).", Toast.LENGTH_SHORT).show() }
            }
        }.start()
    }

    // The chooser lists every app that can view the file except Cloud Notes itself.
    private fun openWithOther() {
        val u = uri ?: return
        try {
            val view = Intent(Intent.ACTION_VIEW).setDataAndType(u, mime?.takeIf { it.isNotBlank() } ?: "*/*")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val own = packageManager.queryIntentActivities(view, 0)
                .filter { it.activityInfo.packageName == packageName }
                .map { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
            val chooser = Intent.createChooser(view, "Open with")
            if (own.isNotEmpty()) chooser.putExtra(Intent.EXTRA_EXCLUDE_COMPONENTS, own.toTypedArray())
            startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(this, "No app can open this file.", Toast.LENGTH_SHORT).show()
        }
    }


    // #1949 trace
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) { LifecycleProbe.call(this, "startActivity", intent); super.startActivityForResult(intent, requestCode, options) }
    override fun navigateUpTo(upIntent: Intent): Boolean { LifecycleProbe.call(this, "navigateUpTo", upIntent); return super.navigateUpTo(upIntent) }
    override fun finish() { LifecycleProbe.call(this, "finish"); super.finish() }
}
