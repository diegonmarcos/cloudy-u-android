package com.diegonmarcos.cloudcalc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import com.diegonmarcos.cloudcalc.audio.SoundDecl
import com.diegonmarcos.cloudcalc.audio.SoundFlow
import com.diegonmarcos.cloudcalc.audio.SoundStore
import com.diegonmarcos.cloudcalc.debugapi.SoundDebugApi
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.sound.Analysis
import com.diegonmarcos.cloudcalc.sound.Generator
import com.diegonmarcos.cloudcalc.sound.Session
import com.diegonmarcos.cloudcalc.sound.Wav
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.SoundGeneratorMode
import com.diegonmarcos.cloudcalc.ui.SoundTags
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowAudioRecord
import kotlin.math.PI
import kotlin.math.sin

/**
 * #772 the Sound tools in the real app: the microphone path is fed a synthetic tone through
 * Robolectric's AudioRecord shadow, so /api/sound/analyze is exercised through the same
 * AudioRecord → SoundFlow → :sound path the phone runs; the generator → analyser loopback over
 * /api/sound/generate and source=generator; the safe-volume guard (declared limits and a loud
 * device); a background app's silenced microphone; "What is it?" against a fake Decisions
 * transport; the History session's CSV/WAV.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SoundAppTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val app: Context get() = RuntimeEnvironment.getApplication()
    private val sr get() = SoundDecl.config.sampleRate

    /** #1801 stands where the AI-key owner engine's relay stands; with no key [held] there, every keyed call is refused. */
    private class FakeHttp : com.diegonmarcos.superapp.decisions.EngineKeyedHttp {
        val bodies = mutableListOf<JSONObject>()
        @Volatile var reply: Http.Response = Http.Response(500, "{}")
        @Volatile var held = true
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            if (!held && body != null) throw java.io.IOException("No OpenRouter key in the AI keys engine")
            synchronized(bodies) { body?.let { bodies += JSONObject(it) } }
            return reply
        }
    }

    private val http = FakeHttp()
    private lateinit var savedHttp: Http
    private lateinit var savedAccount: (Context, String) -> JevStore.Token

    @Before fun up() {
        savedHttp = JevStore.http; savedAccount = JevStore.account
        JevStore.http = http
        http.held = true
        JevStore.account = { _, _ -> JevStore.Token(true, JevStore.SOURCE_ACCOUNT) }
        SoundStore.lastGenerated = null
        // Robolectric's media stream starts at its maximum (7 of 7); a phone at a quiet setting is
        // the ordinary case, and the loud case is its own test below.
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, 2, 0)
    }

    private val audio: AudioManager get() = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    @After fun down() {
        JevStore.http = savedHttp; JevStore.account = savedAccount
        ShadowAudioRecord.clearSource()
        app.getSharedPreferences("cloud_sound", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun grantMic() = shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.RECORD_AUDIO)

    /** The microphone hears a [hz] sine at half scale, continuous across reads. */
    private fun micHears(hz: Double) {
        var n = 0L
        ShadowAudioRecord.setSourceProvider {
            object : ShadowAudioRecord.AudioRecordSource {
                override fun readInShortArray(audioData: ShortArray, offsetInShorts: Int, sizeInShorts: Int, isBlocking: Boolean): Int {
                    for (i in 0 until sizeInShorts) audioData[offsetInShorts + i] = (16000 * sin(2 * PI * hz * n++ / sr)).toInt().toShort()
                    return sizeInShorts
                }
            }
        }
    }

    @Test fun `analyze reads the microphone - a 440 Hz tone comes back as 440 Hz, A4`() {
        grantMic(); micHears(440.0)
        val r = SoundDebugApi.analyze(app, mapOf("ms" to "1000"))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("mic", r.getString("source"))
        assertFalse(r.getBoolean("silenced"))
        assertEquals(1000L, r.getLong("ms"))
        assertEquals(440.0, r.getDouble("frequency_hz"), 1.0)
        assertEquals("A4", r.getString("note"))
        assertTrue("cents ${r.getInt("cents")}", Math.abs(r.getInt("cents")) <= 4)
        assertEquals("tone", r.getJSONArray("event_kinds").getString(0))
        assertEquals(343.2 / 440.0, r.getDouble("wavelength_m"), 0.002)
        assertEquals(440.0, r.getString("detected").substringBefore(" Hz").toDouble(), 1.0)
        assertTrue(r.getString("detected").endsWith("1 tone"))
    }

    @Test fun `the calibration and temperature knobs reach the reading`() {
        grantMic(); micHears(1000.0)
        val plain = SoundDebugApi.analyze(app, mapOf("ms" to "500"))
        SoundStore.setKnobs(app, SoundStore.Knobs(calibrationDb = 94.0, a4Hz = 440.0, temperatureC = 0.0))
        val cal = SoundDebugApi.analyze(app, mapOf("ms" to "500"))
        assertEquals(plain.getDouble("level_db") + 94.0, cal.getDouble("level_db"), 0.2)
        assertTrue(cal.getBoolean("calibrated"))
        assertEquals(331.3 / 1000.0, cal.getDouble("wavelength_m"), 0.001)
        assertEquals(331.3, cal.getDouble("speed_of_sound_m_s"), 0.05)
    }

    @Test fun `a silenced microphone says silenced instead of a frequency`() {
        grantMic() // the default shadow source delivers zeros, as Android does to a background app
        val r = SoundDebugApi.analyze(app, mapOf("ms" to "300"))
        assertTrue(r.getBoolean("ok"))
        assertTrue(r.getBoolean("silenced"))
        assertEquals(0, r.getJSONArray("events").length())
        assertEquals("nothing above the noise floor", r.getString("detected"))
    }

    @Test fun `without the permission analyze refuses and says how to grant it`() {
        val r = SoundDebugApi.analyze(app, mapOf("ms" to "300"))
        assertFalse(r.getBoolean("ok"))
        assertTrue(r.getString("error"), r.getString("error").contains("RECORD_AUDIO"))
    }

    @Test fun `generate then analyze source=generator is the loopback self-test`() {
        val g = SoundDebugApi.generate(app, mapOf("f" to "440", "ms" to "500"))
        assertTrue(g.toString(), g.getBoolean("ok"))
        assertEquals(sr / 2, g.getInt("samples"))
        assertEquals(440.0, g.getJSONObject("spec").getDouble("f1"), 0.0)
        assertEquals(0, g.getJSONArray("notes").length())
        val r = SoundDebugApi.analyze(app, mapOf("source" to "generator"))
        assertTrue(r.getBoolean("ok"))
        assertFalse(r.getBoolean("silenced"))
        assertEquals(440.0, r.getDouble("frequency_hz"), 1.0)
        assertEquals("A4", r.getString("note"))
        for ((wave, f) in listOf("square" to 330.0, "saw" to 196.0)) {
            SoundDebugApi.generate(app, mapOf("f" to f.toString(), "ms" to "500", "wave" to wave))
            assertEquals(wave, f, SoundDebugApi.analyze(app, mapOf("source" to "generator")).getDouble("frequency_hz"), f * 0.005)
        }
        val sweep = SoundDebugApi.generate(app, mapOf("kind" to "sweep", "f" to "200", "f2" to "2000", "ms" to "1000"))
        assertTrue(sweep.getBoolean("ok"))
        assertEquals("chirp", SoundDebugApi.analyze(app, mapOf("source" to "generator")).getJSONArray("event_kinds").getString(0))
    }

    @Test fun `analyze source=generator before any generation, and an unknown source, are refused`() {
        assertFalse(SoundDebugApi.analyze(app, mapOf("source" to "generator")).getBoolean("ok"))
        assertFalse(SoundDebugApi.analyze(app, mapOf("source" to "radio")).getBoolean("ok"))
        val bad = SoundDebugApi.generate(app, mapOf("kind" to "hum"))
        assertFalse(bad.getBoolean("ok"))
        assertTrue(bad.getString("error").startsWith("unknown kind"))
    }

    @Test fun `the safe-volume guard holds the declared limit and lowers it further on a loud device`() {
        val lim = SoundDecl.config.limits
        val g = SoundDebugApi.generate(app, mapOf("f" to "1000", "ms" to "200", "amp" to "0.95"))
        assertEquals(lim.maxAmplitude, g.getJSONObject("spec").getDouble("amplitude"), 0.0)
        assertTrue(g.getJSONArray("notes").getString(0).startsWith("amplitude 0.95"))
        assertTrue(SoundStore.lastGenerated!!.pcm.all { Math.abs(it.toInt()) <= lim.maxAmplitude * 32767 + 1 })
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        val loud = SoundDebugApi.generate(app, mapOf("f" to "1000", "ms" to "200", "amp" to "0.5"))
        assertEquals(lim.loudAmplitude, loud.getJSONObject("spec").getDouble("amplitude"), 0.0)
        assertTrue(loud.getJSONArray("notes").toString(), loud.getJSONArray("notes").toString().contains("device volume 100%"))
        assertEquals(1.0, loud.getDouble("device_volume"), 0.0)
    }

    @Test fun `what is it - the measurements go to the sound model as state, every probability comes back`() {
        http.reply = Http.Response(200, JSONObject().put("answers", JSONObject()
            .put(JevConfig.IDENTIFY_CLASS, JSONObject().put("type", "choice").put("choice", "alarm")
                .put("probabilities", JSONObject().put("alarm", 0.8).put("music", 0.15).put("other", 0.05)))
            .put("natural", JSONObject().put("type", "noul").put("noul", 0.1))).toString())
        val g = SoundFlow.generate(app, Generator.Spec(Generator.TONE, "sine", 3150.0, 0.0, 0.5, 600), play = false)
        val r = SoundFlow.analyze(g.pcm, g.sampleRate)
        val d = SoundFlow.identify(app, g.pcm, g.sampleRate, r, SoundStore.knobs(app))
        assertTrue(d.error, d.ok)
        val sent = http.bodies.single()
        assertEquals(SoundFlow.model(app), sent.getString("model"))
        val measured = sent.getJSONObject("state").getJSONObject("measured")
        assertEquals(3150.0, measured.getDouble("dominant_hz"), 5.0)
        assertEquals("tone", measured.getJSONArray("events").getJSONObject(0).getString("kind"))
        val classes = sent.getJSONObject("questions").getJSONObject(JevConfig.IDENTIFY_CLASS).getJSONObject("criteria").keys().asSequence().toSet()
        assertEquals(JevStore.config(app).identify.getValue("sound").classes.keys, classes)
        assertTrue("speech" in classes && "alarm" in classes && "silence" in classes)
        assertFalse("no image for a text-only model", sent.get("state") is org.json.JSONArray)
        val j = SoundFlow.decisionJson(d)
        assertEquals("alarm", j.getString("class"))
        assertEquals(0.8, j.getJSONObject("answers").getJSONArray(JevConfig.IDENTIFY_CLASS).getJSONObject(0).getDouble("p"), 0.0)
    }

    @Test fun `what is it offline - no key in the engine answers nothing and the reason is kept`() {
        JevStore.account = { _, _ -> JevStore.Token(false, "AI keys engine: no openrouter key") }
        http.held = false
        val g = SoundFlow.generate(app, Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.5, 300), play = false)
        val d = SoundFlow.identify(app, g.pcm, g.sampleRate, SoundFlow.analyze(g.pcm, g.sampleRate), SoundStore.knobs(app))
        assertFalse(d.ok)
        assertTrue(http.bodies.isEmpty())
        assertTrue(d.error, d.error.contains("key"))
    }

    @Test fun `the spectrogram image has the declared size`() {
        val g = SoundFlow.generate(app, Generator.Spec(Generator.SWEEP, "sine", 200.0, 4000.0, 0.5, 1000), play = false)
        val bmp = SoundFlow.spectrogramImage(app, g.pcm, g.sampleRate)
        assertEquals(SoundDecl.config.spectrogramWidth, bmp.width)
        assertEquals(SoundDecl.config.spectrogramHeight, bmp.height)
    }

    @Test fun `a History session saves CSV and WAV, and the WAV reads back`() {
        val live = SoundStore.Live(sr, 10, sr, 4, 8)
        live.add(Session.Sample(0.1, -20.0, -21.0, -20.2, 440.0, 440.0, "A4", 0.78))
        val g = SoundFlow.generate(app, Generator.Spec(Generator.TONE, "sine", 440.0, 0.0, 0.5, 200), play = false)
        live.keep(g.pcm)
        live.keep(ShortArray(sr * 2)) // more than maxAudio: kept up to the cap only
        assertEquals(sr, live.audioLength)
        val files = SoundStore.save(app, live)
        assertEquals(listOf("csv", "wav"), files.map { it.extension })
        assertTrue(files[0].readText().startsWith(Session.CSV_HEADER.joinToString(",")))
        val back = Wav.decode(files[1].readBytes())
        assertEquals(sr, back.sampleRate)
        assertEquals(sr, back.pcm.size)
        assertEquals(440.0, Analysis.analyze(com.diegonmarcos.cloudcalc.sound.Dsp.toDoubles(back.pcm.copyOf(g.pcm.size)), sr).f0!!, 1.0)
        val status = SoundDebugApi.status(app)
        assertTrue(status.getJSONArray("sessions").length() >= 2)
        for (i in 1..12) live.add(Session.Sample(i.toDouble(), -20.0, -21.0, -20.0, 440.0, null, null, null))
        assertEquals(10, live.snapshot().size)
    }

    @Test fun `the Generator screen plays through the guard and shows what it changed`() {
        compose.setContent { CalcTheme { SoundGeneratorMode(Declarations.modes.first { it.kind == "sound_generator" }) } }
        compose.onNodeWithTag(SoundTags.F1).performTextReplacement("30000")
        compose.onNodeWithTag(SoundTags.PLAY).performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag(SoundTags.GUARD_NOTE).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithTag(SoundTags.GUARD_NOTE)[0].assertTextContains("f1 30000 Hz → 19845 Hz")
        assertEquals(19845.0, SoundStore.lastGenerated!!.spec.f1, 0.0)
    }
}
