package com.diegonmarcos.cloudkeyboard

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import com.diegonmarcos.cloudwriter.ListenEngine
import com.diegonmarcos.cloudwriter.ServingApp
import com.diegonmarcos.cloudwriter.WriterRoutes
import com.diegonmarcos.cloudwriter.WriterToolRunner
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONObject

/**
 * #1707 the Writer's fleet debug API (/api/writer/*), moved from the retired standalone Cloud Writer into Cloud Keyboard
 * (checks with the screen locked): listen/status, translate?text=&to=, route. The op is never `state` (update-ack-guard owns it).
 */
object WriterDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            "writer", // = writer-config.json::writer_routes.debug_api.group (test-writer-parity.sh holds the two equal)
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

/** Registers [WriterDebugApi] before Application.onCreate. Not a real provider, not exported; a debug facility never takes the app down. */
class WriterDebugApiProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val ctx = context ?: return false
        runCatching { WriterDebugApi.register(ctx) }
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
