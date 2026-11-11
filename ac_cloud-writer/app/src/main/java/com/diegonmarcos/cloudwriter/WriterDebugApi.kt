package com.diegonmarcos.cloudwriter


import com.diegonmarcos.cloudwriter.writerui.BuildConfig

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONObject

/**
 * Cloud Writer on the fleet debug API (#800), for checks with the screen locked:
 *
 *   /api/writer/listen/status          Listen's live state: running, paused, requested and answering
 *                                      route, why it fell back, the partial text, segments written,
 *                                      microphone permission, on-device engine bind, the model
 *   /api/writer/translate?text=&to=    a real translation on the Translation route (to = a Language
 *                                      Output id such as `spanish`, or a tag such as `es`), answering
 *                                      the text and the route that produced it
 *   /api/writer/route                  both functions' route, model and last answer, online, whether
 *                                      the Account holds a key (never the key), and the text-tools
 *                                      serving app's state — the #800 status probe, on demand
 *
 * The op is never `state`: update-ack-guard.json owns that branch key fleet-wide (GET /api/state).
 */
object WriterDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_GROUP,
            listOf(
                AppDebugServer.Op("listen/status", "", "Listen: running, paused, requested and answering route, fallback reason, partial, segments, mic, engine"),
                AppDebugServer.Op("translate", "text=<text>&to=<language id or tag>", "translate on the Translation route; answers the text and the route that did it"),
                AppDebugServer.Op("route", "", "speech and translation route, model and last answer; online; Account key present; serving app state"),
            ),
        ) { op, q ->
            when (op) {
                "listen/status" -> ListenEngine.statusJson(app).toString()
                "translate" -> translate(app, q).toString()
                "route" -> WriterRoutes.routeJson(app, ServingApp.probe(app, runner(app))).toString()
                else -> null
            }
        }
    }

    @Volatile private var tools: WriterToolRunner? = null

    private fun runner(app: Context): WriterToolRunner =
        tools ?: synchronized(this) { tools ?: WriterToolRunner(app).also { tools = it } }

    private fun translate(app: Context, q: Map<String, String>): JSONObject {
        val text = q["text"].orEmpty()
        if (text.isBlank()) return JSONObject().put("ok", false).put("error", "text= is empty")
        val to = q["to"]?.takeIf { it.isNotBlank() } ?: WriterRoutes.listenTarget(app)
        val lang = WriterRoutes.languageIdOf(to)
            ?: return JSONObject().put("ok", false).put("error", "to=$to names no Language Output (an id like spanish, or a tag like es)")
        return WriterRoutes.translate(app, text, lang).toJson()
            .put("to", lang)
            .put("tag", WriterRoutes.tagOf(lang) ?: JSONObject.NULL)
            .put("model", WriterRoutes.model(app, WriterRoutes.Function.TRANSLATION))
    }
}

/**
 * Registers [WriterDebugApi] before Application.onCreate (the CalcDebugApiProvider trick): a
 * ContentProvider's onCreate runs first, so the routes exist even if the app's own startup later
 * throws. Not a real provider and not exported.
 */
class WriterDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        // A debug facility must never take the host app down.
        runCatching { WriterDebugApi.register(ctx) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
