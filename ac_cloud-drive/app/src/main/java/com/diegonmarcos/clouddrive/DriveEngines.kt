package com.diegonmarcos.clouddrive

import android.util.Log
import com.diegonmarcos.cloudlib.drivecore.DriveCore
import com.diegonmarcos.cloudlib.drivecore.Engine
import com.diegonmarcos.cloudlib.drivecore.Impl
import com.diegonmarcos.cloudlib.drivecore.ListedFile
import com.diegonmarcos.cloudlib.drivecore.WalkOpts
import java.io.File
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler

/**
 * #1729 the host side of libs:drive-core-rs: applies the declared switches (build.json::native_core, baked as BuildConfig.DRIVE_CORE_DEFAULTS)
 * once per process, and wraps every engine call in an X-Ray profiler span named `drive.core.<engine>.<rust|kotlin>`, so the Performance
 * page and its benchmark report the Rust and the Kotlin engine side by side. Idempotent, never throws.
 */
object DriveEngines {
    @Volatile private var done = false

    fun ensure() {
        if (done) return
        synchronized(this) {
            if (done) return
            runCatching { DriveCore.configure(BuildConfig.DRIVE_CORE_DEFAULTS) }
            runCatching {
                DriveCore.spans = object : DriveCore.Spans {
                    override fun <T> span(op: String, impl: String, block: () -> T): T = XrayProfiler.span("$op.$impl") { sp ->
                        // thread CPU time counts the calling thread only: the Rust workers run on their own threads
                        if (impl == "rust") sp.note = "rust workers' cpu not in cpu%"
                        block()
                    }
                }
            }
            done = true
            runCatching { Log.i(DriveCore.TAG, status()) }
        }
    }

    /** The one startup line: which core is loaded (or why not) and the switches in force. Logged to logcat (tag DriveCore). */
    fun status(): String {
        val core = DriveCore.nativeVersion()?.let { "drive-core-rs $it" } ?: "unavailable (${DriveCore.nativeUnavailableReason()})"
        return "startup: core=$core switches=" + Engine.entries.joinToString(",") { "${it.id}=${DriveCore.impl(it).id}" }
    }

    /** MainActivity extra: run [selfTest] in the background and log its lines (tag DriveCore); the emulator smoke test sends it. */
    const val EXTRA_SELFTEST = "com.diegonmarcos.clouddrive.EXTRA_CORE_SELFTEST"

    /**
     * The JNI smoke test: hashes, walks and diffs a tiny fixture in [dir] through the Rust core AND the Kotlin reference, compares them,
     * checks the known SHA-256 of "abc" and that a deliberate Rust panic comes back as an error answer. Returns the log lines; the last
     * one is `selftest: PASS rust` only when the core is loaded and everything agreed. Writes only under [dir]; never throws.
     */
    fun selfTest(dir: File): List<String> {
        val out = ArrayList<String>()
        try {
            ensure()
            val root = File(dir, "drivecore-selftest").apply { deleteRecursively(); mkdirs() }
            File(root, "abc.txt").writeText("abc")
            File(root, "sub").mkdirs()
            File(root, "sub/data.bin").writeBytes(ByteArray(300_000) { (it % 251).toByte() })
            val loaded = DriveCore.nativeUnavailableReason() == null
            out += "selftest: " + status().removePrefix("startup: ")
            val paths = listOf(File(root, "abc.txt").path, File(root, "sub/data.bin").path)
            val hr = DriveCore.withImpl(Engine.HASH, Impl.RUST) { DriveCore.hashFiles(paths) }
            val hk = DriveCore.withImpl(Engine.HASH, Impl.KOTLIN) { DriveCore.hashFiles(paths) }
            val knownAbc = hr[0].sha256 == "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            out += "selftest: hash rust sha256(abc)=${hr[0].sha256.take(16)} blake3(abc)=${hr[0].blake3.take(16)} known=$knownAbc equalToKotlin=${hr == hk}"
            val o = WalkOpts(topFiles = 5, looseLabel = "loose", listFiles = true)
            val wr = DriveCore.withImpl(Engine.WALK, Impl.RUST) { DriveCore.walk(root.path, o) }
            val wk = DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.walk(root.path, o) }
            out += "selftest: walk rust files=${wr.files} dirs=${wr.dirs} bytes=${wr.bytes} equalToKotlin=${wr == wk}"
            if (wr != wk) out += "selftest: walk differs: rust=${wr.toString().take(400)} kotlin=${wk.toString().take(400)}"
            val left = wr.listing
            val right = left.drop(1) + ListedFile("/new/x", 1, 1)
            val dr = DriveCore.withImpl(Engine.DIFF, Impl.RUST) { DriveCore.diff(left, right) }
            val dk = DriveCore.withImpl(Engine.DIFF, Impl.KOTLIN) { DriveCore.diff(left, right) }
            out += "selftest: diff rust delta=${dr.size} equalToKotlin=${dr == dk}"
            val panic = DriveCore.panicProbe()
            val caught = panic?.startsWith("ERR\tpanic:") == true
            out += "selftest: panic probe caught=$caught"
            val ok = loaded && knownAbc && hr == hk && wr == wk && dr == dk && wr.files == 2L && wr.bytes == 300_003L && dr.size == 2 && caught
            out += if (ok) "selftest: PASS rust" else "selftest: FAIL (core loaded=$loaded)"
            root.deleteRecursively()
        } catch (t: Throwable) {
            out += "selftest: FAIL ${t.javaClass.simpleName}: ${t.message.orEmpty().take(160)}"
        }
        return out
    }
}
