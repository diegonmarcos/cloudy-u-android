package com.diegonmarcos.cloudkeyboard.grabber

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import com.diegonmarcos.cloudwriter.WriterTool
import com.diegonmarcos.cloudwriter.WriterToolRunner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * [draft] is the working text; the search box (B) and the media box (C) load from it in PARALLEL, each into its own [Load].
 */
/** #1793 how often, and for how long, a place card with no fix or a stale one asks Cloud Nav again. */
private const val WATCH_MS = 15_000L
private const val WATCH_FOR_MS = 10L * 60_000L

class GrabberModel(private val app: Context, private val scope: CoroutineScope, val source: String) {
    var draft by mutableStateOf(TextFieldValue(""))
    /** True when the text was selected in a field that cannot be edited (EXTRA_PROCESS_TEXT_READONLY), or on a page with no field. */
    var readOnly by mutableStateOf(true)
    var search by mutableStateOf<Load<SearchHit>>(Load.Idle)
    var media by mutableStateOf<Load<MediaResult>>(Load.Idle)
    var busy by mutableStateOf<String?>(null)
    val log = mutableStateListOf<LogLine>()
    /** #1793 the Place card's trip: where from (Cloud Nav's fix) and the air estimate; each routed mode loads into [legs]. */
    var trip by mutableStateOf<Load<TripPlan>>(Load.Idle)
    val legs = mutableStateMapOf<TravelMode, Load<Leg>>()
    /** True when the position is missing because Cloud Nav is not installed (the card offers to open it otherwise). */
    var navMissing by mutableStateOf(false)

    private var searchJob: Job? = null
    private var mediaJob: Job? = null
    private var tripJob: Job? = null
    private var watchJob: Job? = null
    private var generation = 0
    private var runner: WriterToolRunner? = null

    val kind: SelectionKind get() = GrabberClassifier.classify(draft.text)

    fun setText(text: String, selectAll: Boolean = false) {
        draft = TextFieldValue(text, if (selectAll) TextRange(0, text.length) else TextRange(text.length))
    }

    private fun say(what: String, outcome: String, ok: Boolean = true) {
        log.add(0, LogLine(SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date()), what, outcome, ok))
        while (log.size > 6) log.removeAt(log.size - 1)
    }

    /** The text an action works on: the selected range of the working text when there is one, else all of it. */
    fun target(): String = if (draft.selection.collapsed) draft.text else draft.text.substring(draft.selection.min, draft.selection.max)

    /** Load boxes B and C for the working text, both at once; a newer grab cancels the older one. */
    fun grab(remember: Boolean = true) {
        val text = draft.text
        val query = GrabberClassifier.query(text)
        val kind = GrabberClassifier.classify(text)
        searchJob?.cancel(); mediaJob?.cancel(); tripJob?.cancel(); watchJob?.cancel()
        val gen = ++generation
        trip = Load.Idle; legs.clear()
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
            val m = r.getOrNull()
            if (m is MediaResult.MapView) loadTrip(gen, m)
        }
    }

    /**
     * #1793 box C. The text is geocoded (OSM Nominatim, cached, 1 request a second); when the answer is a place
     * ([PlaceDecision.isPlace]) the card is the map with the address, the type and the distances, otherwise it is not a place and
     * the card is an image for the term (Wikipedia / Wikimedia Commons with attribution, the first web image, or a placeholder).
     */
    private suspend fun loadMedia(kind: SelectionKind, query: String): MediaResult {
        val hit = try {
            GrabberNet.geocode(app, query)
        } catch (e: GrabberNetException) {
            // the geocoder down or walled is not the end of the card: an image for the term still answers
            if (e.reason == FailReason.OFFLINE) throw e
            null
        }
        return if (PlaceDecision.isPlace(kind, query, hit)) GrabberNet.placeMap(hit!!, kind, query) else GrabberNet.termImage(query)
    }

    /** The distances of a place card: the position is asked of Cloud Nav only now, when a place is on screen. */
    private fun loadTrip(gen: Int, to: MediaResult.MapView) {
        tripJob?.cancel()
        trip = Load.Loading
        tripJob = scope.launch {
            val t0 = SystemClock.elapsedRealtime()
            val where = withContext(Dispatchers.IO) { GrabberLocation.where(app) }
            if (gen != generation) return@launch
            when (where) {
                is GrabberLocation.Where.Unknown -> {
                    navMissing = where.navMissing
                    trip = Load.Failed(FailReason.EMPTY, where.why)
                    say("distances", "no position", false)
                    if (!where.navMissing) watchForFix(gen, to, 0L)
                }
                is GrabberLocation.Where.At -> {
                    navMissing = false
                    trip = Load.Ready(TripPlan.of(where.lat, where.lon, to.lat, to.lon, where.note, where.stale, where.ts), SystemClock.elapsedRealtime() - t0)
                    if (where.stale) watchForFix(gen, to, where.ts)
                    TravelMode.entries.filter { it.routed }.forEach { mode ->
                        legs[mode] = Load.Loading
                        launch {
                            val l0 = SystemClock.elapsedRealtime()
                            val r = runCatching { GrabberNet.route(mode, where.lat, where.lon, to.lat, to.lon) }
                            if (gen != generation) return@launch
                            legs[mode] = r.fold({ Load.Ready(it, SystemClock.elapsedRealtime() - l0) }, { failure(it) })
                        }
                    }
                }
            }
        }
    }

    /**
     * #1793 no fix, or a stale one: ask Cloud Nav again every [WATCH_MS] (an IPC read of its store, never a fix of ours) and
     * recompute the distances as soon as it publishes a newer fix; gives up after [WATCH_FOR_MS] or when the text changes.
     */
    private fun watchForFix(gen: Int, to: MediaResult.MapView, shownTs: Long) {
        watchJob?.cancel()
        watchJob = scope.launch {
            val until = SystemClock.elapsedRealtime() + WATCH_FOR_MS
            while (SystemClock.elapsedRealtime() < until) {
                kotlinx.coroutines.delay(WATCH_MS)
                if (gen != generation) return@launch
                val w = withContext(Dispatchers.IO) { GrabberLocation.where(app) }
                if (gen != generation) return@launch
                if (w is GrabberLocation.Where.At && FixAge.newer(shownTs, w.ts)) {
                    say("distances", "new position from Cloud Nav")
                    loadTrip(gen, to)
                    return@launch
                }
            }
        }
    }

    /** Ask Cloud Nav again (after it was opened, or a fix came in). */
    fun retryTrip() {
        val m = (media as? Load.Ready<*>)?.value as? MediaResult.MapView ?: return
        loadTrip(generation, m)
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
                draft = draft.copy(selection = TextRange(0, draft.text.length))
                say(action.id, "${draft.text.length} chars selected")
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
            val f = draft
            val sel = if (f.selection.collapsed) TextRange(0, f.text.length) else f.selection
            val next = f.text.substring(0, sel.min) + result + f.text.substring(sel.max)
            draft = TextFieldValue(next, TextRange(sel.min, sel.min + result.length))
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
