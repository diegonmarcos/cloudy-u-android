package com.diegonmarcos.cloudwriter

import com.diegonmarcos.cloudwriter.writerui.R

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudkeyboardlibs.IVoiceCallback
import com.diegonmarcos.cloudkeyboardlibs.IVoiceEngine
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.cloudwriter.core.Dictation
import com.diegonmarcos.cloudwriter.core.DocStore
import com.diegonmarcos.cloudwriter.core.Outcome
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.cloudwriter.core.Segmenter
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The on-device speech engine: IVoiceEngine in the keyboard-engines companion (Cloud-Keyboard-Libs),
 * the service cloud-keyboard's AidlVoiceEngineClient binds. Vosk runs THERE; this app records the
 * microphone and feeds it frames. Package and action are build.json::writer_routes.voice_engine.
 */
class VoiceEngineBinder(context: Context) {
    private val app = context.applicationContext
    private val pkg = WriterRoutes.voiceEngine.optString("package")
    private val action = WriterRoutes.voiceEngine.optString("action")

    @Volatile private var engine: IVoiceEngine? = null

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            engine = IVoiceEngine.Stub.asInterface(binder)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            engine = null
        }

        override fun onBindingDied(name: ComponentName) {
            engine = null
            runCatching { app.unbindService(this) }
            bind()
        }
    }

    fun installed(): Boolean =
        runCatching { app.packageManager.queryIntentServices(Intent(action).setPackage(pkg), 0).isNotEmpty() }.getOrDefault(false)

    fun bind(): Boolean =
        runCatching { app.bindService(Intent(action).setPackage(pkg), conn, Context.BIND_AUTO_CREATE) }.getOrDefault(false)

    fun unbind() {
        engine = null
        runCatching { app.unbindService(conn) }
    }

    val connected: Boolean get() = engine != null

    fun awaitBound(timeoutMs: Long): Boolean {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (engine == null && SystemClock.elapsedRealtime() < until) Thread.sleep(100)
        return engine != null
    }

    /** Open a recognition session; false when the engine is not bound or refused. Callbacks arrive on a binder thread. */
    fun start(languageTag: String?, onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (String) -> Unit): Boolean {
        val e = engine ?: return false
        // Captured under other names: inside the Stub, onPartial/onFinal/onError are the Stub's own
        // methods, and calling them by those names would recurse forever (AidlVoiceEngineClient's note).
        val partial = onPartial
        val committed = onFinal
        val failed = onError
        return runCatching {
            if (!languageTag.isNullOrBlank()) e.setLanguageTag(languageTag)
            e.start(object : IVoiceCallback.Stub() {
                override fun onPartial(text: String?) { partial(text.orEmpty()) }
                override fun onFinal(text: String?) { committed(text.orEmpty()) }
                override fun onError(message: String?) { failed(message.orEmpty()) }
            })
        }.isSuccess
    }

    fun feed(pcm: ByteArray, len: Int) {
        runCatching { engine?.feed(pcm, len) }
    }

    fun stop() {
        runCatching { engine?.stop() }
    }
}

/**
 * LISTEN — continuous dictation into a document (#800).
 *
 * The microphone is read here in 100 ms frames. EVERY frame goes to the on-device engine when it
 * is bound, so the live partial text exists on both routes and the model route's fallback is the
 * on-device transcript of the same audio, not a second pass. On the MODEL route the frames also go
 * through core's [Segmenter]; each utterance becomes one OpenRouter request, and when that request
 * cannot run or fails, the on-device text heard over the same stretch is written instead and the
 * status says so. On the ON-DEVICE route the engine's finals are the transcript.
 *
 * With auto-translate on, each written segment is translated on the Translation route and written
 * beside the original or instead of it (core [Dictation.withTranslation]).
 *
 * Where the text goes: [sink] when an editor is showing the document (it inserts at the caret),
 * otherwise appended to the document file — so a locked screen keeps writing. One worker thread
 * does every request and every write, in order, so segments never land out of sequence.
 */
object ListenEngine {

    data class Status(
        val running: Boolean = false,
        val paused: Boolean = false,
        val requested: Route = Route.MODEL,
        val lastRoute: Route? = null,
        val fallbackReason: String? = null,
        val partial: String = "",
        val segments: Int = 0,
        val chars: Int = 0,
        val level: Float = 0f,
        val error: String? = null,
        val docId: String? = null,
        val translate: Boolean = false,
        val target: String = "",
        val mode: String = Dictation.TranslateMode.ALONGSIDE.id,
        val engineConnected: Boolean = false,
        val model: String = "",
        val startedAt: Long = 0L,
    )

    /** Compose reads this; [status] is the same value for threads and the debug route. */
    val state = mutableStateOf(Status())

    @Volatile var status = Status()
        private set

    /** The open editor's insert-at-caret, or null to append to the document file. Main thread. */
    @Volatile var sink: ((String) -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val heard = StringBuilder()

    @Volatile private var stopRequested = false
    @Volatile private var pausedFlag = false
    private var thread: Thread? = null
    private var voice: VoiceEngineBinder? = null

    private fun update(change: (Status) -> Status) {
        val next = synchronized(this) { change(status).also { status = it } }
        main.post { state.value = next }
    }

    fun hasMic(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Begin listening into [docId]. Answers a reason when it cannot start, null when it did. */
    fun start(context: Context, docId: String): String? {
        if (status.running) return null
        val app = context.applicationContext
        if (!hasMic(app)) return app.getString(R.string.listen_no_mic)
        stopRequested = false
        pausedFlag = false
        synchronized(heard) { heard.setLength(0) }
        val v = VoiceEngineBinder(app).also { voice = it; it.bind() }
        update {
            Status(
                running = true,
                requested = WriterRoutes.route(app, WriterRoutes.Function.SPEECH),
                docId = docId,
                translate = WriterRoutes.listenTranslate(app),
                target = WriterRoutes.listenTarget(app),
                mode = WriterRoutes.listenMode(app),
                model = WriterRoutes.model(app, WriterRoutes.Function.SPEECH),
                startedAt = System.currentTimeMillis(),
            )
        }
        thread = Thread({ loop(app, v) }, "writer-listen").apply { start() }
        return null
    }

    /** The service could not start a session: say why on the screen and on the debug route. */
    fun reportRefusal(why: String) = update { it.copy(running = false, error = why) }

    fun pause(paused: Boolean) {
        if (!status.running) return
        pausedFlag = paused
        update { it.copy(paused = paused, level = 0f) }
    }

    fun stop() {
        stopRequested = true
    }

    // MissingPermission: start() refuses without RECORD_AUDIO before this thread exists, and a
    // revoked grant mid-session surfaces as the SecurityException / uninitialised state handled below.
    @SuppressLint("MissingPermission")
    private fun loop(app: Context, v: VoiceEngineBinder) {
        val rate = WriterRoutes.sampleRate
        val frame = rate / 10 * 2
        val seg = WriterRoutes.speech.optJSONObject("segment") ?: JSONObject()
        val segmenter = Segmenter(
            rate,
            seg.optDouble("silence_rms", 450.0),
            seg.optInt("silence_ms", 900),
            seg.optInt("min_ms", 500),
            seg.optInt("max_ms", 20000),
        )
        val requested = status.requested
        // Checked once: with no token or no network the model is not even tried per segment, and the
        // session runs on the on-device transcript from its first word, saying why.
        val blocker = WriterRoutes.speechBlocker(app)
        val modelLive = requested == Route.MODEL && blocker == null
        if (requested == Route.MODEL && blocker != null) update { it.copy(lastRoute = Route.ML, fallbackReason = blocker) }

        val lang = WriterRoutes.tagOf(WriterRoutes.listenLanguage(app))
        val bound = v.awaitBound(WriterRoutes.speech.optLong("engine_wait_ms", 3000L))
        val onDevice = bound && v.start(
            lang,
            onPartial = { p -> update { it.copy(partial = p) } },
            onFinal = { f -> heardFinal(app, f, modelLive) },
            onError = { e -> update { it.copy(error = app.getString(R.string.listen_engine_error, e)) } },
        )
        update { it.copy(engineConnected = onDevice) }
        if (!modelLive && !onDevice) {
            val why = if (v.installed()) app.getString(R.string.listen_engine_silent, engineLabel())
            else app.getString(R.string.listen_engine_missing, engineLabel())
            update { it.copy(running = false, error = listOfNotNull(blocker, why).joinToString(" — ")) }
            main.post { v.unbind() }
            ListenService.stop(app)
            return
        }

        val rec = try {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, maxOf(min, frame * 4))
        } catch (e: SecurityException) {
            null
        }
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            rec?.release()
            update { it.copy(running = false, error = app.getString(R.string.listen_mic_unavailable)) }
            v.stop()
            main.post { v.unbind() }
            ListenService.stop(app)
            return
        }

        val buf = ByteArray(frame)
        var wasPaused = false
        var lastLevelAt = 0L
        try {
            rec.startRecording()
            while (!stopRequested) {
                val n = rec.read(buf, 0, buf.size)
                if (n < 0) {
                    update { it.copy(error = app.getString(R.string.listen_mic_read_error, n)) }
                    break
                }
                if (n == 0) continue
                if (pausedFlag) {
                    // A pause closes the utterance in progress, so it is written now rather than
                    // joined to whatever is said after the pause.
                    if (!wasPaused && modelLive) segmenter.flush()?.let { submit(app, it, blocker) }
                    wasPaused = true
                    continue
                }
                wasPaused = false
                val now = SystemClock.elapsedRealtime()
                if (now - lastLevelAt >= 200) {
                    lastLevelAt = now
                    val level = Segmenter.level(buf, n)
                    update { it.copy(level = level) }
                }
                if (onDevice) v.feed(buf, n)
                if (modelLive) segmenter.feed(buf, n)?.let { submit(app, it, blocker) }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            if (modelLive) segmenter.flush()?.let { submit(app, it, blocker) }
            v.stop()
            // After every queued segment: the session is over only when its last words are written.
            worker.execute {
                Thread.sleep(400)
                update { it.copy(running = false, paused = false, partial = "", level = 0f) }
                main.post { v.unbind() }
            }
        }
    }

    /** An on-device final: the transcript on the on-device route, the fallback text on the model route. */
    private fun heardFinal(app: Context, text: String, modelLive: Boolean) {
        if (text.isBlank()) return
        if (modelLive) {
            synchronized(heard) { heard.append(text).append(' ') }
        } else {
            val ml = Dictation.punctuate(text)
            val requested = status.requested
            worker.execute { commit(app, Answer(ml, Route.ML, requested, status.fallbackReason, null)) }
        }
        update { it.copy(partial = "") }
    }

    private fun drainHeard(): String = synchronized(heard) { heard.toString().also { heard.setLength(0) } }

    private fun submit(app: Context, pcm: ByteArray, blocker: String?) {
        worker.execute {
            val answer = WriterRoutes.transcribe(app, pcm, blocker) {
                if (!status.engineConnected) Outcome.failed(app.getString(R.string.listen_engine_silent, engineLabel()))
                else Outcome.ok(Dictation.punctuate(drainHeard()))
            }
            // The model answered: what the on-device engine heard over the same audio is not needed.
            if (answer.route == Route.MODEL) drainHeard()
            commit(app, answer)
        }
    }

    /** Write one segment (translated if asked) and record which route produced it. Worker thread. */
    private fun commit(app: Context, answer: Answer) {
        val heardText = answer.text?.trim().orEmpty()
        if (!answer.ok) {
            update { it.copy(error = answer.error, lastRoute = null, fallbackReason = answer.fallbackReason) }
            return
        }
        update { it.copy(lastRoute = answer.route, fallbackReason = answer.fallbackReason, error = null) }
        if (heardText.isEmpty()) return
        // Read live, not from the session's start: the panel's switch applies to the next segment.
        val translate = WriterRoutes.listenTranslate(app)
        val target = WriterRoutes.listenTarget(app)
        val mode = WriterRoutes.listenMode(app)
        update { it.copy(translate = translate, target = target, mode = mode) }
        val written = if (translate) {
            val t = WriterRoutes.translate(app, heardText, target)
            if (!t.ok) update { it.copy(error = app.getString(R.string.listen_translate_failed, t.error.orEmpty())) }
            Dictation.withTranslation(heardText, t.text, Dictation.TranslateMode.of(mode))
        } else heardText
        update { it.copy(segments = it.segments + 1, chars = it.chars + written.length) }
        val docId = status.docId
        main.post {
            val into = sink
            if (into != null) into(written)
            else if (docId != null) worker.execute { DocStore(docsDir(app)).append(docId, written) }
        }
    }

    fun docsDir(context: Context): File = File(context.applicationContext.filesDir, "documents")

    private fun engineLabel(): String = WriterRoutes.voiceEngine.optString("label", "the on-device engine")

    /** /api/writer/listen/status — never the token, never the transcript beyond the live partial. */
    fun statusJson(context: Context): JSONObject {
        val s = status
        return JSONObject()
            .put("running", s.running)
            .put("paused", s.paused)
            .put("requested_route", s.requested.id)
            .put("last_route", s.lastRoute?.id ?: JSONObject.NULL)
            .put("fallback_reason", s.fallbackReason ?: JSONObject.NULL)
            .put("partial", s.partial)
            .put("segments", s.segments)
            .put("chars", s.chars)
            .put("level", s.level.toDouble())
            .put("error", s.error ?: JSONObject.NULL)
            .put("doc_id", s.docId ?: JSONObject.NULL)
            .put("translate", s.translate)
            .put("translate_target", s.target)
            .put("translate_mode", s.mode)
            .put("engine_connected", s.engineConnected)
            .put("engine_installed", VoiceEngineBinder(context).installed())
            .put("model", s.model.ifEmpty { WriterRoutes.model(context, WriterRoutes.Function.SPEECH) })
            .put("mic_permission", hasMic(context))
            .put("started_at", s.startedAt)
            .put("sink", if (sink != null) "editor" else "document file")
    }
}

/**
 * Keeps Listen alive with the screen off: a microphone foreground service. Android silences a
 * background app's AudioRecord, so without this a locked phone would record nothing and say nothing.
 * Its notification is a foreground-service notice, not an alert (fleet-alerts guard: no notify()).
 */
class ListenService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            ListenEngine.stop()
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.listen_channel), NotificationManager.IMPORTANCE_LOW))
        }
        val stop = PendingIntent.getService(this, 1, Intent(this, ListenService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, WriterSurface.mainIntent(this),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_cloud_writer_fg)
            .setContentTitle(getString(R.string.listen_notification_title))
            .setContentText(getString(R.string.listen_notification_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.listen_stop), stop)
            .build()
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        val docId = intent?.getStringExtra(EXTRA_DOC)
        val refused = if (docId != null) ListenEngine.start(this, docId) else getString(R.string.listen_no_document)
        if (refused != null) {
            ListenEngine.reportRefusal(refused)
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "writer_listen"
        private const val NOTIFICATION_ID = 800
        private const val ACTION_STOP = "com.diegonmarcos.cloudwriter.LISTEN_STOP"
        private const val EXTRA_DOC = "doc"

        fun start(context: Context, docId: String) {
            ContextCompat.startForegroundService(context, Intent(context, ListenService::class.java).putExtra(EXTRA_DOC, docId))
        }

        fun stop(context: Context) {
            runCatching { context.startService(Intent(context, ListenService::class.java).setAction(ACTION_STOP)) }
                .onFailure { ListenEngine.stop() }
        }
    }
}
