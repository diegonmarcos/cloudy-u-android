package com.diegonmarcos.superapp.fleetxray.perf

import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * Save + Share of a Markdown report, shared by every fleet app (moved from Cloud Drive's X-Ray Memory so both reports export
 * the same way): [write] fills the file the system save dialog (CreateDocument) picked, [share] opens the share sheet.
 */
public object XrayFileExport {
    public const val MAX_SHARE_CHARS: Int = 200_000

    /** Writes [md] (UTF-8) to [uri]; the failure reason, or null when written. */
    public fun write(ctx: Context, uri: Uri, md: String): String? = try {
        val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: return "the file could not be opened"
        out.use { it.write(md.toByteArray(Charsets.UTF_8)) }
        null
    } catch (t: Throwable) { t.message ?: t.javaClass.simpleName }

    /** The text the share sheet carries: capped so the binder call stays small (Save writes the whole file). */
    public fun shareText(md: String): String =
        if (md.length > MAX_SHARE_CHARS) md.take(MAX_SHARE_CHARS) + "\n\n(truncated for sharing; use Save .md for the whole report)\n" else md

    public fun share(ctx: Context, md: String, name: String) {
        val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_SUBJECT, name); putExtra(Intent.EXTRA_TEXT, shareText(md)) }
        runCatching { ctx.startActivity(Intent.createChooser(send, name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
