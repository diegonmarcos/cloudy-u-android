package com.diegonmarcos.cloudkeyboard.grabber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.diegonmarcos.cloudwriter.WriterTool
import com.diegonmarcos.cloudwriter.WriterToolRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One line of the console log under the toolbar: what ran, on how much text, and how it ended. */
class LogLine(val time: String, val what: String, val outcome: String, val ok: Boolean)

/**
 * The state of one Grabber panel (the sheet over another app, the Text Selection page, the Screen Selection page).
 * [field] is the working text; the search box (B) and the media box (C) load from it in PARALLEL, each into its own [Load].
 */
class GrabberModel(private val app: Context, private val scope: CoroutineScope, val source: String) {
    var field by mutableStateOf(TextFieldValue(""))
    /** True when the text was selected in a field that cannot be edited (EXTRA_PROCESS_TEXT_READONLY), or on a page with no field. */
    var readOnly by mutableStateOf(true)
    var search by mutableStateOf<Load<SearchHit>>(Load.Idle)
    var media by mutableStateOf<Load<MediaResult>>(Load.Idle)
    var busy by mutableStateOf<String?>(null)
    val log = mutableStateListOf<LogLine>()

    private var searchJob: Job? = null
    private var mediaJob: Job? = null
    private var generation = 0
    private var runner: WriterToolRunner? = null

    val kind: SelectionKind get() = GrabberClassifier.classify(field.text)

    fun setText(text: String, selectAll: Boolean = false) {
        field = TextFieldValue(text, if (selectAll) TextRange(0, text.length) else TextRange(text.length))
    }

    private fun say(what: String, outcome: String, ok: Boolean = true) {
        log.add(0, LogLine(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()), what, outcome, ok))
        while (log.size > 6) log.removeAt(log.size - 1)
    }

    /** The text an action works on: the selected range of the working text when there is one, else all of it. */
    fun target(): String = if (field.selection.collapsed) field.text else field.text.substring(field.selection.min, field.selection.max)

    /** Load boxes B and C for the working text, both at once; a newer grab cancels the older one. */
    fun grab(remember: Boolean = true) {
        val text = field.text
        val query = GrabberClassifier.query(text)
        val kind = GrabberClassifier.classify(text)
        searchJob?.cancel(); mediaJob?.cancel()
        val gen = ++generation
        if (kind == SelectionKind.EMPTY) { search = Load.Idle; media = Load.Idle; return }
        if (remember) GrabberStore.push(app, Recent(text.trim(), kind.label, source, System.currentTimeMillis()))
        if (!GrabberNet.online(app)) {
            val off = Load.Failed(FailReason.OFFLINE, "No network. Connect and retry.")
            search = off; media = off
            say("grab", "offline", false)
            return
        }
        search = Load.Loading; media = Load.Loading
        searchJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val r = runCatching { GrabberNet.firstResult(query) }
            if (gen != generation) return@launch
            search = r.fold({ Load.Ready(it, SystemClock.elapsedRealtime() - t0) }, { failure(it) })
        }
        mediaJob = scope.launch {
            val r = runCatching { loadMedia(kind, query) }
            if (gen != generation) return@launch
            media = r.fold({ Load.Ready(it, it.ms) }, { failure(it) })
        }
    }

    private suspend fun loadMedia(kind: SelectionKind, query: String): MediaResult {
        if (!kind.showsMap) return GrabberNet.firstImage(query)
        return try {
            GrabberNet.map(query, if (kind == SelectionKind.ADDRESS) 16 else 14)
        } catch (e: GrabberNetException) {
            // a place the geocoder does not know still deserves a picture: fall back to the first image
            if (e.reason == FailReason.OFFLINE) throw e
            GrabberNet.firstImage(query)
        }
    }

    private fun failure(t: Throwable): Load.Failed = when (t) {
        is GrabberNetException -> Load.Failed(t.reason, t.message.orEmpty())
        else -> Load.Failed(FailReason.ERROR, t.message ?: t.javaClass.simpleName)
    }

    /**
     * Run [action] on the target text. Returns the reply for the app the text was selected in when the action transformed it in
     * an editable field (the caller sets it as the activity result and closes); null when the sheet stays.
     */
    fun run(action: GrabberAction, onReply: (ProcessTextReply) -> Unit = {}) {
        val text = target()
        if (text.isBlank()) { say(action.id, "nothing selected", false); return }
        when (val route = GrabberRouter.route(action, text)) {
            is GrabberRoute.SelectAll -> {
                field = field.copy(selection = TextRange(0, field.text.length))
                say(action.id, "${field.text.length} chars selected")
            }
            is GrabberRoute.Clipboard -> {
                val cm = app.getSystemService(ClipboardManager::class.java)
                cm?.setPrimaryClip(ClipData.newPlainText("Grabber", route.text))
                say(action.id, "${route.text.length} chars on the clipboard")
                if (route.remove) finishTransform(action, "", onReply)
            }
            is GrabberRoute.Transform -> finishTransform(action, route.result, onReply)
            is GrabberRoute.Open -> say(action.id, GrabberActions.open(app, route.plan), true)
            is GrabberRoute.Engine -> runEngine(action, route.engine, route.text, onReply)
        }
    }

    private fun finishTransform(action: GrabberAction, result: String, onReply: (ProcessTextReply) -> Unit) {
        val reply = ProcessTextReply.decide(action, readOnly, result)
        if (reply.replacesSelection) {
            say(action.id, "result returned to the field")
            onReply(reply)
        } else {
            // read-only selection (or a page with no field): the result replaces the selected range of the working text instead
            val f = field
            val sel = if (f.selection.collapsed) TextRange(0, f.text.length) else f.selection
            val next = f.text.substring(0, sel.min) + result + f.text.substring(sel.max)
            field = TextFieldValue(next, TextRange(sel.min, sel.min + result.length))
            say(action.id, "${result.length} chars, shown here (read-only)")
        }
    }

    private fun runEngine(action: GrabberAction, engine: WriterEngine, text: String, onReply: (ProcessTextReply) -> Unit) {
        val r = runner ?: WriterToolRunner(app).also { runner = it }
        val tool = when (engine) { WriterEngine.TRANSLATE -> WriterTool.TRANSLATE; WriterEngine.GRAMMAR -> WriterTool.GRAMMAR; WriterEngine.ENHANCE -> WriterTool.ENHANCE }
        busy = action.label
        r.run(tool, text) { outcome ->
            busy = null
            val out = outcome.text
            if (out != null) finishTransform(action, out, onReply)
            else say(action.id, outcome.error ?: "the engine answered nothing", false)
        }
    }
}

/** Opens what a route asks for; every branch ends in a sentence for the log, never in silence. */
object GrabberActions {
    private fun intent(spec: IntentSpec): Intent {
        val i = Intent(spec.action)
        spec.pkg?.let { i.setPackage(it) }
        if (spec.uri != null && spec.mime != null) i.setDataAndType(Uri.parse(spec.uri), spec.mime)
        else if (spec.uri != null) i.data = Uri.parse(spec.uri)
        else if (spec.mime != null) i.type = spec.mime
        spec.extras.forEach { (k, v) -> i.putExtra(k, v) }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    private fun tryStart(ctx: Context, i: Intent): Boolean = try { ctx.startActivity(i); true } catch (e: Exception) { false }

    fun open(ctx: Context, plan: OpenPlan): String {
        if (tryStart(ctx, intent(plan.primary))) return "${plan.label}: opened"
        return when (val f = plan.fallback) {
            is Fallback.View -> if (tryStart(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(f.url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))) "${plan.label}: app not installed, opened in the browser" else "${plan.label}: no app and no browser to open it"
            is Fallback.CopyAndLaunch -> {
                val text = plan.primary.extras.firstOrNull { it.first == GrabberRouter.EXTRA_TEXT }?.second.orEmpty()
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Grabber", text))
                val launch = ctx.packageManager.getLaunchIntentForPackage(f.pkg)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (launch != null && tryStart(ctx, launch)) "${f.app} takes no shared text: copied it, paste it there" else "${f.app} is not installed"
            }
            is Fallback.Missing -> "${plan.label}: ${f.app} is not installed or cannot open this"
        }
    }
}

/** The recent grabs: a private file of this app (cache-free, never leaves the phone), newest first, capped by [GrabberRecents.MAX]. */
object GrabberStore {
    private fun file(ctx: Context) = File(ctx.filesDir, "grabber-recent.json")

    @Synchronized fun load(ctx: Context): List<Recent> = runCatching {
        val f = file(ctx)
        if (!f.isFile) return emptyList()
        val a = JSONArray(f.readText())
        (0 until a.length()).map { val o = a.getJSONObject(it); Recent(o.getString("t"), o.optString("k"), o.optString("s"), o.optLong("at")) }
    }.getOrDefault(emptyList())

    @Synchronized private fun save(ctx: Context, list: List<Recent>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("t", it.text).put("k", it.kind).put("s", it.source).put("at", it.at)) }
        runCatching { file(ctx).writeText(a.toString()) }
        GrabberBus.recents = list
    }

    @Synchronized fun push(ctx: Context, r: Recent) = save(ctx, GrabberRecents.push(load(ctx), r))
    @Synchronized fun remove(ctx: Context, text: String) = save(ctx, GrabberRecents.remove(load(ctx), text))
    @Synchronized fun clear(ctx: Context) = save(ctx, emptyList())
}

/** In-process state the pages observe: the recents list and the last screen capture. */
object GrabberBus {
    var recents by mutableStateOf<List<Recent>>(emptyList())
    var lastShot by mutableStateOf<ShotInfo?>(null)
}

class ShotInfo(val path: String, val width: Int, val height: Int, val at: Long)
