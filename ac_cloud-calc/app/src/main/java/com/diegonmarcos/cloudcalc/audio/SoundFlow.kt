package com.diegonmarcos.cloudcalc.audio

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.R
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.jev.JevRouter
import com.diegonmarcos.cloudcalc.sound.Air
import com.diegonmarcos.cloudcalc.sound.Analysis
import com.diegonmarcos.cloudcalc.sound.Dsp
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.cloudcalc.sound.Notes
import com.diegonmarcos.cloudcalc.sound.Pitch
import com.diegonmarcos.cloudcalc.sound.Session
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min
import com.diegonmarcos.superapp.image.mlkit.Recognition
import com.diegonmarcos.superapp.sound.SoundEngine
import com.diegonmarcos.superapp.sound.SoundConfig
import com.diegonmarcos.superapp.sound.SoundPrefs
import com.diegonmarcos.superapp.image.mlkit.RecognitionRoutes
import java.io.File

/**
 * The Sound tools end to end, shared by the screens and /api/sound/{generate,analyze,status} so a green debug route is
 * the app's real path: a live reading, a buffer's analysis, the guarded generator, and the
 * "What is this sound?" question. Blocks: call off the main thread.
 */
object SoundFlow {
    const val SOUND_USE = "sound"

    /** One live reading: what the meter, the History graphs and the waterfall show. */
    data class Reading(
        val levelDb: Double,
        val aDb: Double,
        val cDb: Double,
        val peakDb: Double,
        val dominantHz: Double,
        val f0: Double?,
        val note: Notes.Note?,
        val wavelengthM: Double?,
        val spectrumDb: DoubleArray,
        val pcm: ShortArray,
    )

    /** [pcm]'s size must be a power of two (the FFT size). Levels are calibrated by [knobs]. */
    fun reading(pcm: ShortArray, sampleRate: Int, cfg: SoundDecl.Config, knobs: SoundStore.Knobs): Reading {
        val level = Dsp.levelDbfs(pcm)
        val spectrum = Dsp.spectrumDb(pcm, pcm.size)
        val a = cfg.analysis
        val f0 = if (level < a.silenceDb) null
            else Pitch.yin(Dsp.toDoubles(pcm, min(pcm.size, a.frame)), sampleRate, a.fmin, a.fmax, a.yinThreshold)?.hz
        val dominant = Dsp.peakHzInterpolated(spectrum, sampleRate)
        return Reading(
            level + knobs.calibrationDb,
            Dsp.aWeightedDbfs(level, spectrum, sampleRate) + knobs.calibrationDb,
            Dsp.cWeightedDbfs(level, spectrum, sampleRate) + knobs.calibrationDb,
            Dsp.peakDbfs(pcm),
            dominant,
            f0,
            f0?.let { Notes.of(it, knobs.a4Hz) },
            Air.wavelength(f0 ?: dominant, knobs.speed),
            spectrum,
            pcm,
        )
    }

    fun analyze(pcm: ShortArray, sampleRate: Int, cfg: SoundDecl.Config = SoundDecl.config): Analysis.Result =
        Analysis.analyze(Dsp.toDoubles(pcm), sampleRate, cfg.analysis)

    /** What a screen and the debug route report for a buffer: the model's summary plus wavelength. */
    fun summary(r: Analysis.Result, knobs: SoundStore.Knobs): JSONObject {
        val d = Session.describe(r, knobs.calibrationDb, knobs.a4Hz)
        val f = r.f0 ?: r.peakHz
        val note = r.f0?.let { Notes.of(it, knobs.a4Hz) }
        return d.put("frequency_hz", Math.round(f * 10) / 10.0)
            .put("cents", note?.let { Math.round(it.cents) } ?: JSONObject.NULL)
            .put("wavelength_m", Air.wavelength(f, knobs.speed)?.let { Math.round(it * 10000) / 10000.0 } ?: JSONObject.NULL)
            .put("speed_of_sound_m_s", Math.round(knobs.speed * 10) / 10.0)
    }

    /** Guard, render, keep (for /api/sound/analyze?source=generator) and, unless [play] is false, play. */
    fun generate(ctx: Context, spec: Generator.Spec, play: Boolean): SoundStore.Generated {
        val cfg = SoundDecl.config
        val g = Generator.guard(spec, cfg.limits, cfg.sampleRate, Player.deviceVolume(ctx))
        val pcm = Generator.pcm16(Generator.render(g.spec, cfg.sampleRate, cfg.limits.fadeMs))
        // played reports what the speaker accepted, not what was asked for.
        val played = play && runCatching { Player.play(pcm, cfg.sampleRate) }.isSuccess
        return SoundStore.Generated(g.spec, g.notes, pcm, cfg.sampleRate, System.currentTimeMillis(), played).also { SoundStore.lastGenerated = it }
    }

    @Volatile private var engine: SoundEngine? = null
    private fun engine(ctx: Context): SoundEngine =
        engine ?: synchronized(this) { engine ?: SoundEngine(ctx.applicationContext).also { engine = it } }

    /**
     * #798 "What is this sound?" on device — the default route (libs:ml-l-sound/sound.json): YAMNet's
     * 521 AudioSet classes in Cloud-Lib-Ml-L-Sound-Yamnet.apk, offline, the audio never leaving the
     * phone. The answer is the fleet's one Recognition type, with the clip's timeline.
     */
    fun identifyOnDevice(ctx: Context, pcm: ShortArray, sampleRate: Int): Recognition = engine(ctx).classify(pcm, sampleRate)

    /** #798 /api/sound/classify?path=<wav>: a WAV file this app can read, on device. */
    fun identifyOnDevice(ctx: Context, wav: File): Recognition = engine(ctx).classify(wav)

    /** #799 one "What is this sound?" answer: the uniform result (which route answered, and why) and, when the model answered, its decision. */
    data class Identified(val result: Recognition, val decision: Decision?)

    /**
     * #799 "What is this sound?" on the user's route (libs:ml-l-sound/sound.json; Model (Jev) by
     * default) or [route]: the decision model is asked through this app's own Jev client with this
     * app's measurements; offline, no token, a timeout or an error fall back to YAMNet on device, and
     * the result says so. Recorded as the sound type's last route (RecognitionRoutes).
     */
    fun identifyRouted(ctx: Context, pcm: ShortArray, sampleRate: Int, r: Analysis.Result?, knobs: SoundStore.Knobs, route: String? = null): Identified {
        val chosen = route ?: SoundPrefs.route(ctx)
        var decision: Decision? = null
        val hasToken = if (chosen == SoundConfig.OPENROUTER) JevStore.token(ctx).present else null
        val res = RecognitionRoutes.routed(RecognitionRoutes.SOUND, chosen, RecognitionRoutes.online(ctx), hasToken, SoundConfig.fallback(),
            onDevice = { identifyOnDevice(ctx, pcm, sampleRate) },
            model = { identify(ctx, pcm, sampleRate, r ?: analyze(pcm, sampleRate), knobs).also { decision = it }.let(::recognition) })
        return Identified(res, decision?.takeIf { it.ok && res.route == SoundConfig.OPENROUTER })
    }

    /** A decision in the fleet's one result shape: the class probabilities as labels, the extra questions as answers. */
    fun recognition(d: Decision): Recognition {
        val answers = d.answers?.keys()?.asSequence()?.filter { it != JevConfig.IDENTIFY_CLASS }
            ?.associateWith { q -> d.options(q).map { Recognition.Label(it.key, it.p) } }.orEmpty()
        return Recognition(
            ok = d.ok, route = SoundConfig.OPENROUTER, requested = SoundConfig.OPENROUTER, fellBack = false, reason = "",
            labels = d.options(JevConfig.IDENTIFY_CLASS).map { Recognition.Label(it.key, it.p) }, boxes = emptyList(), text = "",
            colours = emptyList(), barcode = null, answers = answers, model = d.model, latencyMs = d.latencyMs, cost = d.cost,
            width = 0, height = 0, error = if (d.ok) null else d.error.ifBlank { "the model did not answer" },
        )
    }

    /** Null when the sound engine is installed and answers, else what to do (the contract handshake). */
    fun engineStatus(ctx: Context): String? = engine(ctx).check()

    fun model(ctx: Context): String = JevStore.model(ctx, SOUND_USE)

    /** Whether the sound model's catalogue entry takes images, so a spectrogram may ride along. */
    fun modelTakesImages(ctx: Context): Boolean {
        val m = model(ctx)
        return JevStore.catalogue(ctx).firstOrNull { it.slug == m }?.images == true
    }

    /**
     * "What is this sound?": the measurements (never the audio) to the sound model, one choice
     * over build.json::jev.identify.sound's classes plus its extras. The spectrogram of [pcm]
     * rides along only when the model takes images.
     */
    fun identify(ctx: Context, pcm: ShortArray, sampleRate: Int, r: Analysis.Result, knobs: SoundStore.Knobs): Decision {
        val image = if (modelTakesImages(ctx)) Spectrogram.dataUrl(spectrogramImage(ctx, pcm, sampleRate)) else null
        return JevRouter.identify(JevStore.config(ctx), JevStore.http, null, model(ctx), SOUND_USE, summary(r, knobs), image)
    }

    /** A spectrogram of [pcm] (time across, log frequency up) at the declared image size. */
    fun spectrogramImage(ctx: Context, pcm: ShortArray, sampleRate: Int): Bitmap {
        val cfg = SoundDecl.config
        val w = cfg.spectrogramWidth; val h = cfg.spectrogramHeight
        val n = cfg.analysis.frame
        val quiet = ContextCompat.getColor(ctx, R.color.calc_background)
        val mid = ContextCompat.getColor(ctx, R.color.calc_accent)
        val loud = ContextCompat.getColor(ctx, R.color.calc_text)
        val px = IntArray(w * h) { quiet }
        val span = (pcm.size - n).coerceAtLeast(0)
        for (x in 0 until w) {
            val start = if (w > 1) (span.toLong() * x / (w - 1)).toInt() else 0
            if (start + n > pcm.size) continue
            val bands = Session.logBands(Dsp.spectrumDb(pcm.copyOfRange(start, start + n), n), sampleRate, h, cfg.history.fminHz)
            for (y in 0 until h) px[(h - 1 - y) * w + x] = Spectrogram.colour(bands[y], FLOOR_DB, quiet, mid, loud)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    /** The decision's class and extra answers as JSON (every option's probability), for the debug route. */
    fun decisionJson(d: Decision): JSONObject {
        val answers = JSONObject()
        d.answers?.keys()?.forEach { q ->
            answers.put(q, JSONArray().apply { d.options(q).forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("p", it.p)) } })
        }
        return JSONObject().put("ok", d.ok).put("model", d.model).put("error", d.error).put("latency_ms", d.latencyMs)
            .put("cost", d.cost ?: JSONObject.NULL).put("class", d.options(JevConfig.IDENTIFY_CLASS).firstOrNull()?.key ?: JSONObject.NULL).put("answers", answers)
    }

    /** The waterfall's quiet end: a band this far below full scale is drawn as silence. */
    const val FLOOR_DB = -100.0
}
