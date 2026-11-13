package com.diegonmarcos.cloudstore

import android.content.Context
import android.os.Process
import com.diegonmarcos.superapp.apps.PhoneApp
import com.diegonmarcos.superapp.apps.PhoneAppClassifier
import com.diegonmarcos.superapp.apps.PhoneFolders
import com.diegonmarcos.superapp.appstore.BuildConfig as StoreBuild
import com.diegonmarcos.superapp.appstore.SourceResolver
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmark
import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import com.diegonmarcos.superapp.fleetxray.perf.XrayProfiler
import com.diegonmarcos.superapp.updater.Fleet
import java.io.File
import java.security.MessageDigest

/**
 * #1728 Cloud Store's declared benchmarks (Configs > X-Ray App > Performance). Read-only and bounded: they parse the catalogue baked
 * into the build, ask the package manager, group the installed apps with the Phone tab's classifier and hash a bounded sample of
 * installed APKs (the update check's content fingerprint). The network update check itself is not benchmarked: it would measure
 * the network, and a benchmark never touches it.
 */
object StoreBenchmarks {
    const val PARSE_REPEATS = 20
    const val HASH_MAX_FILES = 20
    const val HASH_MAX_BYTES = 48L * 1024 * 1024
    const val HASH_MAX_MS = 5_000L

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024)
        f.inputStream().use { s -> while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** At most [maxFiles] readable files totalling at most [maxBytes], in the given order. Pure. */
    fun pickSample(files: Sequence<File>, maxFiles: Int = HASH_MAX_FILES, maxBytes: Long = HASH_MAX_BYTES): List<File> {
        val out = ArrayList<File>(); var total = 0L
        for (f in files) {
            if (out.size >= maxFiles) break
            val len = runCatching { if (f.isFile && f.canRead()) f.length() else -1L }.getOrDefault(-1L)
            if (len <= 0 || total + len > maxBytes) continue
            out += f; total += len
        }
        return out
    }

    fun all(): List<XrayBenchmark> = listOf(
        XrayBenchmark("store.catalogue.parse", "Catalogue parse (fleet manifest)", "read-only; the manifest baked into this build, parsed $PARSE_REPEATS times in memory", 15_000, 3) { _ ->
            XrayProfiler.span("store.catalogue.parse") { sp -> var n = 0L; repeat(PARSE_REPEATS) { n += Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64).size }; sp.add(items = n, bytes = StoreBuild.CONSTELLATION_FLEET_B64.length.toLong() * PARSE_REPEATS) }
            null
        },
        XrayBenchmark("store.installed.diff", "Installed-app diff against the catalogue", "read-only; one package-manager lookup per catalogue app", 15_000, 3) { ctx ->
            val fleet = Fleet.parse(StoreBuild.CONSTELLATION_FLEET_B64)
            if (fleet.isEmpty()) return@XrayBenchmark "the catalogue is empty"
            XrayProfiler.span("store.installed.diff") { sp -> val n = fleet.count { SourceResolver.installed(ctx, it.pkg) != null }; sp.add(items = fleet.size.toLong()); sp.note = "$n installed of ${fleet.size}" }
            null
        },
        XrayBenchmark("store.apk.hash", "APK size and hash checks (bounded sample)", "read-only; at most $HASH_MAX_FILES installed APKs, ${HASH_MAX_BYTES / 1048576} MB, 5 s", 25_000, 2) { ctx ->
            @Suppress("DEPRECATION")
            val apks = runCatching { ctx.packageManager.getInstalledApplications(0) }.getOrDefault(emptyList()).asSequence().map { File(it.sourceDir) }
            val files = pickSample(apks)
            if (files.isEmpty()) return@XrayBenchmark "no readable APK"
            val end = System.currentTimeMillis() + HASH_MAX_MS
            XrayProfiler.span("store.apk.hash") { sp -> for (f in files) { if (System.currentTimeMillis() > end) { sp.note = "time cap"; break }; sha256(f); sp.add(items = 1, bytes = f.length()) } }
            null
        },
        XrayBenchmark("store.phone.grouping", "Phone tab grouping build", "read-only; the installed app list grouped by the declared folders, no icons loaded", 15_000, 3) { ctx ->
            @Suppress("DEPRECATION")
            val apps = runCatching { ctx.packageManager.getInstalledApplications(0) }.getOrDefault(emptyList())
                .map { PhoneApp(it.packageName, null, runCatching { ctx.packageManager.getApplicationLabel(it).toString() }.getOrDefault(it.packageName), null, Process.myUserHandle()) }
            val folders = runCatching { PhoneFolders.loadFromBuildConfig() }.getOrDefault(emptyList())
            if (apps.isEmpty() || folders.isEmpty()) return@XrayBenchmark "no installed app or no declared folder"
            XrayProfiler.span("store.phone.grouping") { sp -> PhoneAppClassifier.groupByFolder(apps, folders).also { sp.add(items = apps.size.toLong()) } }
            null
        },
    )

    fun register() { XrayBenchmarks.register(*all().toTypedArray()) }
}
