package com.diegonmarcos.superapp.browser

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.print.PrintManager
import android.webkit.WebView
import com.diegonmarcos.superapp.texttools.TextToolsClient
import org.json.JSONObject
import org.json.JSONTokener

/**
 * #802 what the menu does TO A PAGE, apart from the fragment that draws the menu.
 * Script-driven actions read their JavaScript from assets/browser/ (declared files,
 * not strings in Kotlin) and answer through a callback, because evaluateJavascript does.
 */
object BrowserPageActions {

    /** assets/browser/<name>.js with `__N__` replaced. */
    fun script(ctx: Context, name: String, n: Int = 0): String =
        ctx.assets.open("browser/$name.js").bufferedReader().use { it.readText() }.replace("__N__", n.toString())

    /** evaluateJavascript answers a JSON-encoded string of what the script returned. */
    fun decode(raw: String?): JSONObject? = runCatching {
        val s = JSONTokener(raw ?: return null).nextValue() as? String ?: return null
        JSONObject(s)
    }.getOrNull()

    fun run(wv: WebView, js: String, done: (JSONObject?) -> Unit) = wv.evaluateJavascript(js) { done(decode(it)) }

    /** Reader view of the current page: extracted, then rendered with the app's declared CSS. */
    fun reader(wv: WebView, css: String, done: (JSONObject?) -> Unit) = run(wv, script(wv.context, "reader")) { r ->
        if (r != null) {
            val html = "<!doctype html><meta name=viewport content='width=device-width'><style>$css</style>" +
                "<h1>${esc(r.optString("title"))}</h1>${r.optString("html")}"
            wv.loadDataWithBaseURL(wv.url, html, "text/html", "utf-8", wv.url)
        }
        done(r)
    }

    /** Find, answering the match count once the async search finishes. */
    fun find(wv: WebView, q: String, done: (Int) -> Unit) {
        if (q.isBlank()) { wv.clearMatches(); done(0); return }
        wv.setFindListener { _, count, finished -> if (finished) done(count) }
        wv.findAllAsync(q)
    }

    fun print(wv: WebView) { printJob(wv, null) }

    /** A file-name-safe job name: the page title (else the host), no path or control characters, at most 60 chars. */
    fun jobName(title: String?, url: String?): String {
        val base = title?.takeIf { it.isNotBlank() } ?: url?.substringAfter("://", "")?.substringBefore('/')?.takeIf { it.isNotBlank() } ?: "page"
        return base.map { if (it.isLetterOrDigit() || it in " ._-()") it else '_' }.joinToString("").trim().take(60).ifBlank { "page" }
    }

    /** How a print job reaches the platform: the real one is [PrintManager.print]; a test hands its own to see the call. */
    fun interface PrintLauncher { fun launch(jobName: String, adapter: android.print.PrintDocumentAdapter, attrs: android.print.PrintAttributes?) }

    private fun platformLauncher(ctx: Context): PrintLauncher? {
        val pm = ctx.getSystemService(Context.PRINT_SERVICE) as? PrintManager ?: return null
        return PrintLauncher { n, a, attrs -> pm.print(n, a, attrs) }
    }

    /**
     * #1741 hands the page to Android's print framework (PrintManager + the web view's own PrintDocumentAdapter). This is the
     * "printer" path, not an in-process PDF renderer: the system print dialog's "Save as PDF" destination writes the file.
     * [attrs] pre-sets the sheet (A4) for Convert to PDF; null keeps the dialog's defaults (Print...).
     * Answers false when there is no print service (never throws).
     */
    fun printJob(wv: WebView, attrs: android.print.PrintAttributes?, launcher: PrintLauncher? = null): Boolean = runCatching {
        val l = launcher ?: platformLauncher(wv.context) ?: return false
        printWith(jobName(wv.title, wv.url), { n -> wv.createPrintDocumentAdapter(n) }, attrs, l)
        true
    }.getOrDefault(false)

    /** The seam under [printJob]: the adapter is made for [name] and launched with [attrs]. */
    fun printWith(name: String, adapterOf: (String) -> android.print.PrintDocumentAdapter, attrs: android.print.PrintAttributes?, launcher: PrintLauncher) {
        launcher.launch(name, adapterOf(name), attrs)
    }

    /** The A4 sheet Convert to PDF asks for. */
    fun a4(): android.print.PrintAttributes =
        android.print.PrintAttributes.Builder().setMediaSize(android.print.PrintAttributes.MediaSize.ISO_A4).build()

    /** #1741 Convert to PDF: the same print path with an A4 sheet; the person picks "Save as PDF" as the destination. */
    fun convertToPdf(wv: WebView, launcher: PrintLauncher? = null): Boolean = printJob(wv, a4(), launcher)

    /** One client for the process: constructing one binds the text-tools service. */
    @Volatile private var tools: TextToolsClient? = null
    fun textTools(ctx: Context): TextToolsClient =
        tools ?: synchronized(this) { tools ?: TextToolsClient(ctx.applicationContext).also { tools = it } }

    /** Can Android make this app the default browser right now (and it is not already)? */
    fun canRequestDefault(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return false
        val rm = ctx.getSystemService(RoleManager::class.java) ?: return false
        return rm.isRoleAvailable(RoleManager.ROLE_BROWSER) && !rm.isRoleHeld(RoleManager.ROLE_BROWSER)
    }

    fun defaultBrowserIntent(ctx: Context): Intent? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else ctx.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_BROWSER)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;")
}
