package com.diegonmarcos.clouddrive.manage

import android.content.Context
import android.os.Environment
import com.diegonmarcos.clouddrive.BuildConfig
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveEngines
import com.diegonmarcos.cloudlib.drivecore.DriveCore
import com.diegonmarcos.cloudlib.drivecore.Engine
import com.diegonmarcos.cloudlib.drivecore.Impl
import com.diegonmarcos.cloudlib.drivecore.ListedFile
import com.diegonmarcos.cloudlib.drivecore.WalkOpts
import com.diegonmarcos.clouddrive.SharedStore
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.sync.GitSyncCoordinator
import com.diegonmarcos.clouddrive.sync.RepoCheap
import com.diegonmarcos.clouddrive.sync.RepoDiskSize
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmark
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler
import java.io.File
import java.security.MessageDigest

/**
 * #1728 Cloud Drive's declared benchmarks for Configs > X-Ray App > Performance. Every one runs a REAL hot path of the app (which
 * records its own span), read-only, bounded in entries, bytes and time, with no network and no write outside the profiler's own file.
 * The question they answer: is the hot path CPU-bound (a Rust engine may help) or IO-bound (it will not).
 *
 * Engines: hashing and the folder-size walk are open to alternative engines ([registerHashEngine], [registerSizeEngine]); each
 * registered engine runs as its own row ("files.hash.sha256.<engine>"), so a native engine and the Kotlin one are an A/B in the report.
 */
object DriveBenchmarks {
    const val MAX_FILES = 40
    const val MAX_BYTES = 48L * 1024 * 1024
    const val SAMPLE_MS = 5_000L
    const val WALK_MS = 6_000L
    const val SIZE_MAX_ENTRIES = 20_000
    const val MAX_REPOS = 3
    const val MAX_LIGHT_REPOS = 8

    /** engine name -> lowercase hex SHA-256 of a file. */
    private val hashEngines = LinkedHashMap<String, (File) -> String>().apply { put("kotlin", ::sha256Kotlin) }
    /** engine name -> bytes of a folder, stopping after maxEntries files. "kotlin" is [RepoDiskSize.computeKotlin] and records its own span; #1729 adds the "rust" row (the DriveCore walk). */
    private val sizeEngines = LinkedHashMap<String, (File, Int) -> Long>().apply { put("kotlin", { d, n -> RepoDiskSize.computeKotlin(d, n) }) }

    @Synchronized fun registerHashEngine(name: String, f: (File) -> String) { hashEngines[name] = f }
    @Synchronized fun registerSizeEngine(name: String, f: (File, Int) -> Long) { sizeEngines[name] = f }
    @Synchronized fun hashEngineNames(): List<String> = hashEngines.keys.toList()
    @Synchronized fun sizeEngineNames(): List<String> = sizeEngines.keys.toList()

    fun sha256Kotlin(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Picks at most [maxFiles] files / [maxBytes] bytes from [candidates], skipping empty and oversize ones. Pure: the tests drive it. */
    fun pickSample(candidates: Sequence<File>, maxFiles: Int = MAX_FILES, maxBytes: Long = MAX_BYTES, maxOne: Long = 16L * 1024 * 1024): List<File> {
        val out = ArrayList<File>(); var total = 0L
        for (f in candidates) {
            if (out.size >= maxFiles) break
            val len = runCatching { if (f.isFile && f.canRead()) f.length() else -1L }.getOrDefault(-1L)
            if (len < 4096 || len > maxOne || total + len > maxBytes) continue
            out += f; total += len
        }
        return out
    }

    /** A breadth-first listing of shared storage, bounded to [limit] entries, no symlink followed. */
    private fun sharedFiles(limit: Int = 4_000): Sequence<File> = sequence {
        val root = runCatching { Environment.getExternalStorageDirectory() }.getOrNull() ?: return@sequence
        val q = ArrayDeque<File>().apply { add(root) }
        var seen = 0
        while (q.isNotEmpty() && seen < limit) {
            val d = q.removeFirst()
            for (k in runCatching { d.listFiles() }.getOrNull().orEmpty()) {
                if (++seen > limit) return@sequence
                if (runCatching { java.nio.file.Files.isSymbolicLink(k.toPath()) }.getOrDefault(false)) continue
                if (k.isDirectory) { if (k.name != "Android") q.add(k) } else yield(k)
            }
        }
    }

    private fun apkFiles(ctx: Context): Sequence<File> = sequence {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val apps = runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
        for (a in apps) yield(File(a.sourceDir))
    }

    /** What the hash benchmark reads: shared-storage files when the app has all-files access, always topped up with installed APKs. */
    fun sampleFiles(ctx: Context): List<File> {
        val shared = if (Places.hasAllFilesAccess(ctx)) pickSample(sharedFiles(), MAX_FILES / 2, MAX_BYTES / 2) else emptyList()
        val apks = pickSample(apkFiles(ctx), MAX_FILES - shared.size, MAX_BYTES - shared.sumOf { it.length() })
        return shared + apks
    }

    private fun repoDirs(max: Int): List<File> = runCatching {
        SharedStore.gitRoot().listFiles().orEmpty().filter { File(it, ".git").exists() }.sortedBy { it.name }.take(max)
    }.getOrDefault(emptyList())

    fun all(): List<XrayBenchmark> = listOf(
        XrayBenchmark("xray.memory.scan", "X-Ray Memory scan", "read-only; shared-storage walk capped at 60000 entries and 6 s, no ADB channel", 20_000, 2) { ctx ->
            if (!Places.hasAllFilesAccess(ctx)) return@XrayBenchmark "all-files access not granted: the scan would read no storage"
            XrayMemoryCollector.collect(ctx, null, BuildConfig.VERSION_NAME, exec = { null }, maxEntries = 60_000, budgetMs = WALK_MS); null
        },
        XrayBenchmark("drive.largest-folders.walk", "Largest-folders walk", "read-only; stat only, cancelled after 6 s", 20_000, 3) { ctx ->
            if (!Places.hasAllFilesAccess(ctx)) return@XrayBenchmark "all-files access not granted"
            val end = System.currentTimeMillis() + WALK_MS
            val res = DataScanner().scan(listOfNotNull(runCatching { Environment.getExternalStorageDirectory() }.getOrNull()), emptyMap(), cancelled = { System.currentTimeMillis() > end })
            DataScanner.folders(res.items); null
        },
        XrayBenchmark("fsync.folder.scan", "Folder scan (clone sizes)", "read-only; at most $MAX_REPOS clones, $SIZE_MAX_ENTRIES files each", 25_000, 2) { ctx ->
            val dirs = repoDirs(MAX_REPOS).ifEmpty { listOf(ctx.cacheDir, ctx.filesDir) }.filter { it.isDirectory }
            if (dirs.isEmpty()) return@XrayBenchmark "no folder to scan"
            for ((name, f) in synchronized(this) { sizeEngines.toList() }) {
                dirs.forEach { d ->
                    if (name == "kotlin") f(d, SIZE_MAX_ENTRIES)
                    else XrayProfiler.span("fsync.folder.scan.$name") { sp -> val b = f(d, SIZE_MAX_ENTRIES); sp.add(bytes = b) }
                }
            }
            null
        },
        XrayBenchmark("git.status.read", "Git status read", "read-only; full status of at most $MAX_REPOS small clones, no fetch", 30_000, 2) { _ ->
            if (!GitSyncCoordinator.engineReady()) return@XrayBenchmark "git engine not initialised: open the Git page once"
            val dirs = repoDirs(40).filter { !RepoCheap.tooLarge(it, 5_000, 500) }.take(MAX_REPOS)
            if (dirs.isEmpty()) return@XrayBenchmark "no small cloned repository"
            dirs.forEach { runCatching { GitSyncCoordinator.probeStatus(it) } }; null
        },
        XrayBenchmark("git.sync.light-check", "Git sync check (refs only)", "read-only; the sync pre-check (branch, upstream, ahead/behind from refs) of at most $MAX_LIGHT_REPOS clones, no network", 30_000, 2) { _ ->
            if (!GitSyncCoordinator.engineReady()) return@XrayBenchmark "git engine not initialised: open the Git page once"
            val dirs = repoDirs(MAX_LIGHT_REPOS)
            if (dirs.isEmpty()) return@XrayBenchmark "no cloned repository"
            dirs.forEach { runCatching { GitSyncCoordinator.probeLight(it) } }; null
        },
        XrayBenchmark("apps.local.refresh", "Local Apps refresh", "read-only; package manager and storage stats, no shell channel", 25_000, 3) { ctx ->
            AppSnapshot.collect(ctx, useShell = false); null
        },
        XrayBenchmark("files.hash.sha256", "File hashing (bounded sample)", "read-only; at most $MAX_FILES files, ${MAX_BYTES / 1048576} MB in total, 5 s, one engine after the other", 30_000, 2) { ctx ->
            val files = sampleFiles(ctx)
            if (files.isEmpty()) return@XrayBenchmark "no readable file to hash"
            for ((name, f) in synchronized(this) { hashEngines.toList() }) {
                val end = System.currentTimeMillis() + SAMPLE_MS
                XrayProfiler.span("files.hash.sha256.$name") { sp ->
                    for (file in files) { if (System.currentTimeMillis() > end) { sp.note = "time cap"; break }; f(file); sp.add(items = 1, bytes = file.length()) }
                }
            }
            null
        },
        XrayBenchmark("mediastore.data-strips.query", "MediaStore Data strips query", "read-only; the declared strips, each limited by its declaration", 15_000, 3) { ctx ->
            val strips = runCatching { Declarations.manage.dataStrips }.getOrDefault(emptyList())
            if (strips.isEmpty()) return@XrayBenchmark "no Data strip declared"
            if (!hasGrant(ctx)) return@XrayBenchmark "media access not granted"
            strips.forEach { loadStrip(ctx, it) }; null
        },
        XrayBenchmark("engines.rust-vs-kotlin.ab", "Rust vs Kotlin engines (A/B)", "read-only; hashing of the bounded sample, a walk capped at ${SIZE_MAX_ENTRIES * 3} entries and 6 s, a tree diff of that listing; each engine twice, Rust then Kotlin", 40_000, 2) { ctx -> driveCoreAb(ctx) },
    )

    /**
     * #1729 the Rust engines join the per-engine rows ("files.hash.sha256.rust", "fsync.folder.scan.rust") when the core is loaded; when it is not
     * the row is simply absent and the reason is logged (tag DriveCore), never a fake Rust row that measured Kotlin.
     */
    fun registerDriveCoreEngines() {
        DriveEngines.ensure()
        val why = DriveCore.nativeUnavailableReason()
        if (why != null) { runCatching { android.util.Log.i(DriveCore.TAG, "no rust benchmark rows: $why") }; return }
        registerHashEngine("rust") { f -> DriveCore.withImpl(Engine.HASH, Impl.RUST) { DriveCore.sha256Hex(f.absolutePath) }.orEmpty() }
        registerSizeEngine("rust") { d, n -> DriveCore.withImpl(Engine.WALK, Impl.RUST) { DriveCore.walk(d.absolutePath, WalkOpts(maxEntries = n.toLong(), budgetMs = Long.MAX_VALUE / 4, topFiles = 0, topFolders = 1)).bytes } }
    }

    /** A deterministic "newer" listing of [left] (every 7th file gone, every 5th resized, a few new): the CPU work of a sync compare, no second tree needed. */
    fun perturbed(left: List<ListedFile>): List<ListedFile> =
        left.mapIndexedNotNull { i, f -> when { i % 7 == 6 -> null; i % 5 == 4 -> f.copy(bytes = f.bytes + 1); else -> f } } + (0 until 8).map { ListedFile("/new/n$it", it.toLong(), 1L) }

    /** The A/B benchmark: each Rust engine and its Kotlin reference run on the same bounded input, their spans `drive.core.<engine>.<rust|kotlin>` are the rows. */
    private fun driveCoreAb(ctx: Context): String? {
        DriveEngines.ensure()
        DriveCore.nativeUnavailableReason()?.let { return "the Rust core is not loaded ($it): nothing to compare" }
        val files = sampleFiles(ctx)
        val root = (if (Places.hasAllFilesAccess(ctx)) runCatching { Environment.getExternalStorageDirectory() }.getOrNull() else null)?.takeIf { it.isDirectory } ?: ctx.filesDir
        return XrayProfiler.span("engines.rust-vs-kotlin.ab") { sp ->
            for (impl in Impl.entries) {
                if (files.isNotEmpty()) DriveCore.withImpl(Engine.HASH, impl) { DriveCore.hashFiles(files.map { it.absolutePath }, DriveCore.BLAKE3 or DriveCore.SHA256) }
                val snap = DriveCore.withImpl(Engine.WALK, impl) { DriveCore.walk(root.absolutePath, WalkOpts(maxEntries = SIZE_MAX_ENTRIES.toLong() * 3, budgetMs = WALK_MS, listFiles = true)) }
                sp.add(items = snap.files, bytes = snap.bytes)
                DriveCore.withImpl(Engine.DIFF, impl) { DriveCore.diff(snap.listing, perturbed(snap.listing)) }
            }
            sp.note = "hash ${files.size} files, walk + diff of ${root.name}"
            null
        }
    }

    /** Registers the declared benchmarks with the shared runner. */
    fun register() { registerDriveCoreEngines(); XrayBenchmarks.register(*all().toTypedArray()) }
}
