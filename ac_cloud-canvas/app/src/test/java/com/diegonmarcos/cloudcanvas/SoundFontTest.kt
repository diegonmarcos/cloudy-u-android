package com.diegonmarcos.cloudcanvas

import com.diegonmarcos.cloudcanvas.audio.Drum
import com.diegonmarcos.cloudcanvas.audio.EngineKind
import com.diegonmarcos.cloudcanvas.audio.FadeVoice
import com.diegonmarcos.cloudcanvas.audio.GmMap
import com.diegonmarcos.cloudcanvas.audio.Inst
import com.diegonmarcos.cloudcanvas.audio.MasterLimiter
import com.diegonmarcos.cloudcanvas.audio.MixEngine
import com.diegonmarcos.cloudcanvas.audio.MixerState
import com.diegonmarcos.cloudcanvas.audio.NoteEvent
import com.diegonmarcos.cloudcanvas.audio.OfflineMix
import com.diegonmarcos.cloudcanvas.audio.Session
import com.diegonmarcos.cloudcanvas.audio.SfBackend
import com.diegonmarcos.cloudcanvas.audio.SfDecl
import com.diegonmarcos.cloudcanvas.audio.Voice
import com.diegonmarcos.cloudcanvas.audio.Wave
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1809 the SoundFont engine, on the JVM: every Play instrument resolves to a preset of the bundled bank (read from the real
 * GeneralUser-GS.sf2), the pins hold, the channel layout gives each track its own output group, and MixEngine drives the engine
 * sample-exact, honours mute and solo, and limits the master. FluidSynth itself is exercised by the CI offline render
 * (canvas-soundfont-render.yml) and on the emulator (canvas-screens-emulator.yml).
 */
class SoundFontTest {
    private fun root(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "build.json").isFile) dir = dir.parentFile
        return dir!!
    }
    private fun buildJson() = JSONObject(File(root(), "build.json").readText())
    private fun asset(path: String) = File(root(), "app/src/main/assets/$path")
    private fun decl(): SfDecl = SfDecl.parse(asset(SfDecl.ASSET).readText())

    /** The presets of an SF2 file: (bank, program) to name, from the PHDR chunk of its pdta list. */
    private fun sf2Presets(f: File): Map<Pair<Int, Int>, String> {
        RandomAccessFile(f, "r").use { r ->
            fun u32(): Long { val b = ByteArray(4); r.readFully(b); return (b[0].toLong() and 0xFF) or ((b[1].toLong() and 0xFF) shl 8) or ((b[2].toLong() and 0xFF) shl 16) or ((b[3].toLong() and 0xFF) shl 24) }
            fun id(): String { val b = ByteArray(4); r.readFully(b); return String(b, Charsets.ISO_8859_1) }
            assertEquals("RIFF", id()); u32(); assertEquals("sfbk", id())
            while (r.filePointer < r.length()) {
                val cid = id(); val size = u32(); val start = r.filePointer
                if (cid == "LIST" && id() == "pdta") {
                    while (r.filePointer < start + size) {
                        val sid = id(); val ssize = u32(); val sstart = r.filePointer
                        if (sid == "phdr") {
                            val out = LinkedHashMap<Pair<Int, Int>, String>()
                            for (i in 0 until (ssize / 38).toInt() - 1) {
                                val rec = ByteArray(38); r.readFully(rec)
                                val name = String(rec, 0, 20, Charsets.ISO_8859_1).substringBefore('\u0000')
                                val prog = (rec[20].toInt() and 0xFF) or ((rec[21].toInt() and 0xFF) shl 8)
                                val bank = (rec[22].toInt() and 0xFF) or ((rec[23].toInt() and 0xFF) shl 8)
                                out[bank to prog] = name
                            }
                            return out
                        }
                        r.seek(sstart + ssize + (ssize and 1))
                    }
                }
                r.seek(start + size + (size and 1))
            }
        }
        error("no phdr chunk in $f")
    }

    @Test fun everyInstrumentResolvesToAPresetOfTheBank() {
        val d = decl()
        val presets = sf2Presets(asset(d.bankAsset))
        assertTrue("the bank holds its 287 presets", presets.size >= 259)
        for ((inst, wave) in GmMap.allKinds()) {
            val p = GmMap.preset(d, inst, wave)
            val name = presets[p.bank to p.program]
            assertNotNull("${GmMap.kind(inst, wave)} -> ${p.bank}:${p.program} is not a preset of the bank", name)
            assertEquals("${GmMap.kind(inst, wave)} names its preset as the bank does", name, p.name)
        }
        // the kit is a drum bank (128) and every pad has a GM percussion key (35..81) of its own
        assertEquals(128, GmMap.preset(d, Inst.Drums, Wave.Sine).bank)
        val keys = Drum.entries.map { GmMap.key(d, Inst.Drums, it.ordinal) }
        assertTrue(keys.all { it in 35..81 })
        assertEquals(Drum.entries.size, keys.toSet().size)
        assertEquals(36, GmMap.key(d, Inst.Drums, Drum.Kick.ordinal)); assertEquals(38, GmMap.key(d, Inst.Drums, Drum.Snare.ordinal))
        assertEquals(42, GmMap.key(d, Inst.Drums, Drum.HatClosed.ordinal)); assertEquals(49, GmMap.key(d, Inst.Drums, Drum.Crash.ordinal))
    }

    @Test fun theBankAndTheDeclarationMatchTheirPins() {
        val sf = buildJson().getJSONObject("audio").getJSONObject("soundfont")
        val d = decl()
        assertEquals(sf.getString("config_asset"), SfDecl.ASSET)
        assertEquals(sf.getJSONObject("bank").getString("asset"), d.bankAsset)
        assertEquals(sf.getJSONObject("bank").getString("version"), d.bankVersion)
        assertEquals(sf.getString("engine_version"), d.engineVersion)
        val md = MessageDigest.getInstance("SHA-256")
        asset(d.bankAsset).inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n <= 0) break; md.update(b, 0, n) } }
        assertEquals(sf.getJSONObject("bank").getString("sha256"), md.digest().joinToString("") { "%02x".format(it) })
        assertTrue(asset("soundfont/LICENSE.txt").readText().contains("GeneralUser GS"))
        // the declared libraries are libfluidsynth.so's NEEDED closure, Oboe included
        val libs = sf.getJSONArray("libs").let { a -> (0 until a.length()).map { a.getString(it) } }
        assertTrue(libs.containsAll(listOf("libfluidsynth.so", "liboboe.so", "libglib-2.0.so", "libsndfile.so")))
    }

    @Test fun engineDefaultsToSoundFontAndClassicStaysSelectable() {
        val a = buildJson().getJSONObject("audio")
        assertEquals("soundfont", a.getString("default_engine"))
        assertEquals(listOf("soundfont", "classic"), a.getJSONArray("engines").let { j -> (0 until j.length()).map { j.getString(it) } })
        assertEquals(EngineKind.SoundFont, EngineKind.of(null)); assertEquals(EngineKind.Classic, EngineKind.of("classic"))
        assertEquals(EngineKind.SoundFont, EngineKind.of("soundfont"))
    }

    @Test fun eachTrackHasItsOwnOutputGroupAndEachKindItsOwnChannel() {
        val d = decl()
        val seen = HashSet<Int>()
        for (t in 1..d.groups) for ((inst, wave) in GmMap.allKinds()) {
            val c = GmMap.channel(d, t, inst, wave)
            assertTrue(c in 0 until d.midiChannels)
            assertEquals("channel $c renders into the group of track $t", t - 1, c % d.groups)
            assertTrue("channel $c is used twice", seen.add(c))
        }
        assertEquals(MixerState.MAX_TRACKS, d.groups)
        assertEquals(1, GmMap.velocity(0f)); assertEquals(127, GmMap.velocity(1f)); assertEquals(64, GmMap.velocity(0.5f))
    }

    /** A synth that records its MIDI and renders a constant 0.25 into the group of every sounding channel, and 0.1 into the reverb planes. */
    private class FakeSf(override val groups: Int) : SfBackend {
        val log = ArrayList<String>()
        val sounding = HashSet<Int>()
        val volume = HashMap<Int, Int>()
        var renderedFrames = 0
        override fun noteOn(ch: Int, key: Int, vel: Int) { log.add("on $ch $key $vel @$renderedFrames"); sounding.add(ch) }
        override fun noteOff(ch: Int, key: Int) { log.add("off $ch $key @$renderedFrames"); sounding.remove(ch) }
        override fun cc(ch: Int, num: Int, value: Int) { if (num == 7) volume[ch] = value }
        override fun allNotesOff() { sounding.clear() }
        override fun process(planes: FloatArray, stride: Int, offset: Int, len: Int) {
            for (ch in sounding) {
                val g = ch % groups
                if (volume[ch] == 0) continue
                for (i in 0 until len) { planes[(2 * g) * stride + offset + i] += 0.25f; planes[(2 * g + 1) * stride + offset + i] += 0.25f }
            }
            if (sounding.any { volume[it] != 0 }) for (i in 0 until len) { planes[(2 * groups) * stride + offset + i] += 0.1f; planes[(2 * groups + 1) * stride + offset + i] += 0.1f }
            renderedFrames += len
        }
        override fun close() {}
    }

    @Test fun liveNotesGoToTheirTracksChannelAndAScheduledNoteStartsAtItsExactFrame() {
        val d = decl()
        val e = MixEngine(48000)
        val fake = FakeSf(d.groups)
        e.sfDecl = d; e.sf = fake
        e.update { it.copy(selected = 2, metronome = false) }
        val l = FloatArray(256); val r = FloatArray(256)
        e.noteOn(Inst.Piano, Wave.Sine, 60, 1f)
        e.render(l, r, 256)
        assertEquals("on ${GmMap.channel(d, 2, Inst.Piano, Wave.Sine)} 60 127 @0", fake.log.first())
        e.noteOff(Inst.Piano, 60)
        e.render(l, r, 256)
        assertTrue(fake.log.contains("off ${GmMap.channel(d, 2, Inst.Piano, Wave.Sine)} 60 @256"))
        // a loop event on track 3 at beat 0.25 (120 BPM: frame 6000) is struck at exactly that frame, inside its block
        val e2 = MixEngine(48000)
        val f2 = FakeSf(d.groups)
        e2.sfDecl = d; e2.sf = f2
        e2.update { Session(mixer = MixerState.initial().addEvent(3, NoteEvent(0.25, Inst.Drums, Drum.Snare.ordinal, 0.25, 1f)), bpm = 120, bars = 1, metronome = false, playing = true) }
        repeat(30) { e2.render(l, r, 256) }
        assertEquals("on ${GmMap.channel(d, 3, Inst.Drums, Wave.Sine)} 38 127 @6000", f2.log.first { it.startsWith("on") })
    }

    @Test fun aMutedOrUnsoloedSoundFontTrackIsSilentAndSendsNoReverb() {
        val d = decl()
        fun peak(s: Session): Float {
            val e = MixEngine(48000); val f = FakeSf(d.groups)
            e.sfDecl = d; e.sf = f
            e.update { s }
            val l = FloatArray(512); val r = FloatArray(512)
            var p = 0f
            repeat(20) { e.render(l, r, 512); p = maxOf(p, l.maxOf { Math.abs(it) }) }
            return p
        }
        val base = Session(mixer = MixerState.initial().addEvent(1, NoteEvent(0.0, Inst.Piano, 60, 4.0)), bpm = 120, bars = 1, metronome = false, playing = true)
        assertTrue(peak(base) > 0.2f)
        assertEquals(0f, peak(base.copy(mixer = base.mixer.mute(1, true))), 0f)
        assertEquals(0f, peak(base.copy(mixer = base.mixer.solo(2, true))), 0f)
    }

    @Test fun theMasterLimiterHoldsTheCeilingAndLeavesQuietSoundAlone() {
        val lim = MasterLimiter(48000)
        val quiet = FloatArray(1000) { (0.5 * Math.sin(it * 0.05)).toFloat() }
        val q2 = quiet.copyOf()
        lim.process(quiet, q2, 1000)
        assertEquals(0.5f, quiet.maxOf { Math.abs(it) }, 1e-3f)
        val loud = FloatArray(4800) { (3.0 * Math.sin(it * 0.05)).toFloat() }
        val l2 = loud.copyOf()
        lim.process(loud, l2, 4800)
        assertTrue(loud.all { Math.abs(it) <= lim.ceiling + 1e-6f })
        assertTrue(lim.ceiling < Math.pow(10.0, -1.0 / 20).toFloat())   // below -1 dBFS
        // the same engine through OfflineMix: a dense Classic chord never leaves the ceiling either
        val chord = (48..84 step 3).map { NoteEvent(0.0, Inst.Synth, it, 2.0, 1f, Wave.Square) }
        var m = MixerState.initial(); chord.forEach { m = m.addEvent(1, it) }
        val pcm = OfflineMix.render(Session(mixer = m.volume(1, 1f), bpm = 120, bars = 1, metronome = false), 8000)
        assertTrue(pcm.maxOf { Math.abs(it.toInt()) } <= (lim.ceiling * 32767f).toInt() + 1)
    }

    @Test fun aStolenVoiceFadesOutInsteadOfBeingCut() {
        val constant = object : Voice { override fun mix(out: FloatArray, n: Int): Boolean { for (i in 0 until n) out[i] += 1f; return true } }
        val f = FadeVoice(constant, 48000)
        val out = FloatArray(480)
        assertFalse("the fade ends within 5 ms", f.mix(out, 480))
        assertEquals(1f, out[0], 1e-6f)
        for (i in 1 until 240) assertTrue(out[i] <= out[i - 1] && out[i - 1] - out[i] < 0.01f)
        assertEquals(0f, out[240], 0f)
    }
}
