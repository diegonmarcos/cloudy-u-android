package com.diegonmarcos.superapp.appstore

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.pm.PackageInfoCompat
import com.diegonmarcos.superapp.updater.cache.ApkCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * The status area under a Store page's buttons: a two-line tally of what the page already knows.
 *
 *   Installed 18 / 24 apps · 412 MB / 560 MB
 *   Updates 5 · 120 MB — 3 cached (70 MB) · 2 to download (50 MB)
 *
 * Declared is the page's own declared list (Cloud: the fleet registry; Phone: its declared rows).
 * Installed is PackageManager, sized from the APK files (base + splits). Release sizes are the ones
 * the page's checks already fetched ([com.diegonmarcos.superapp.updater.Fleet.State.bytes]); the
 * cache is [ApkCache]'s dir. Nothing here fetches: [aggregate] is pure and unit-tested, the device
 * glue only reads local files.
 */
object StoreTally {

    /**
     * One declared app as the page sees it. Sizes are bytes; a negative size is unknown and is left
     * out of a total (never counted as 0 in a way that looks measured: see [Tally.sizesComplete]).
     *
     * @param installedBytes the installed APK files' bytes (base + splits), -1 when not installed/unknown.
     * @param releaseBytes   the release asset's download size, -1 when no check has reported one.
     * @param update         a check found a newer build.
     * @param cachedBytes    bytes of a complete cached APK newer than the installed one, -1 when none.
     */
    class Item(
        val installed: Boolean,
        val installedBytes: Long = -1,
        val releaseBytes: Long = -1,
        val update: Boolean = false,
        val cachedBytes: Long = -1,
    )

    class Tally(
        val declared: Int, val installed: Int,
        val installedBytes: Long, val declaredBytes: Long,
        val updates: Int, val updateBytes: Long,
        val cached: Int, val cachedBytes: Long,
        val toDownload: Int, val toDownloadBytes: Long,
        /** Every size in the declared total was known. */
        val sizesComplete: Boolean,
    ) {
        fun installedLine(): String =
            "Installed $installed / $declared apps · ${StoreSizes.format(installedBytes)} / " +
                StoreSizes.format(declaredBytes) + if (sizesComplete) "" else "+"

        fun updatesLine(): String =
            if (updates == 0) "Updates 0 — all current"
            else "Updates $updates · ${StoreSizes.format(updateBytes)} — " +
                "$cached cached (${StoreSizes.format(cachedBytes)}) · $toDownload to download (${StoreSizes.format(toDownloadBytes)})"
    }

    /**
     * The tally. Declared size of an app is its release size when known, else its installed size.
     * An update is CACHED when its download is already on disk (count and size: the cached file);
     * otherwise it is TO DOWNLOAD (size: the release asset). Update total = cached + to download.
     */
    fun aggregate(items: List<Item>): Tally {
        var installed = 0; var instBytes = 0L; var declBytes = 0L; var complete = true
        var updates = 0; var cached = 0; var cachedBytes = 0L; var todo = 0; var todoBytes = 0L
        for (i in items) {
            if (i.installed) { installed++; if (i.installedBytes > 0) instBytes += i.installedBytes }
            val decl = if (i.releaseBytes >= 0) i.releaseBytes else if (i.installed) i.installedBytes else -1
            if (decl >= 0) declBytes += decl else complete = false
            if (i.update) {
                updates++
                if (i.cachedBytes >= 0) { cached++; cachedBytes += i.cachedBytes }
                else { todo++; if (i.releaseBytes > 0) todoBytes += i.releaseBytes }
            }
        }
        return Tally(items.size, installed, instBytes, declBytes, updates, cachedBytes + todoBytes,
            cached, cachedBytes, todo, todoBytes, complete)
    }

    // ── device glue: local reads only ──────────────────────────────────────────

    /** Installed APK bytes of [pkg]: `sourceDir` plus every split, or -1 when not installed. */
    fun apkBytes(ctx: Context, pkg: String): Long = runCatching {
        val ai = ctx.packageManager.getApplicationInfo(pkg, 0)
        (listOfNotNull(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList()))
            .sumOf { File(it).length() }
    }.getOrDefault(-1L)

    /** Installed versionCode of [pkg], or null. */
    fun installedCode(ctx: Context, pkg: String): Long? = runCatching {
        PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(pkg, 0))
    }.getOrNull()

    /** Complete cached downloads, newest per package: package → (versionCode, bytes). One dir listing. */
    fun cache(ctx: Context): Map<String, Pair<Long, Long>> {
        val out = HashMap<String, Pair<Long, Long>>()
        runCatching { ApkCache.entries(ctx) }.getOrDefault(emptyList()).forEach { e ->
            val r = e.record ?: return@forEach
            if (e.partial) return@forEach
            val had = out[r.pkg]
            if (had == null || r.versionCode > had.first) out[r.pkg] = r.versionCode to e.bytes
        }
        return out
    }

    /** The cached bytes for an update of [pkg]: a cached build newer than what is installed, else -1. */
    fun cachedUpdate(cache: Map<String, Pair<Long, Long>>, pkg: String, installedCode: Long?): Long {
        val (code, bytes) = cache[pkg] ?: return -1
        return if (installedCode == null || code > installedCode) bytes else -1
    }

    // ── the area ──────────────────────────────────────────────────────────────

    const val TAG = "store-tally"

    /**
     * The compact two-line area. [key] changes whenever the page's inputs or progress change; the
     * tally is recomputed off the main thread after a short settle (progress ticks fast), so a
     * download finishing moves an update from "to download" to "cached" without a fetch.
     */
    @Composable
    fun Area(key: Any?, compute: (Context) -> List<Item>, modifier: Modifier = Modifier) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        var tally by remember { mutableStateOf<Tally?>(null) }
        LaunchedEffect(key) {
            if (tally != null) delay(SETTLE_MS)
            tally = withContext(Dispatchers.IO) { runCatching { aggregate(compute(ctx.applicationContext)) }.getOrNull() }
        }
        val t = tally
        Column(modifier.fillMaxWidth().padding(vertical = sdp(StoreDensity.S4))
            .clip(RoundedCornerShape(4.dp)).background(Ink.CARD.ink())
            .padding(horizontal = sdp(StoreDensity.S8), vertical = sdp(StoreDensity.S4)).testTag(TAG)) {
            Line(t?.installedLine() ?: "Installed …", "$TAG:installed")
            Line(t?.updatesLine() ?: "Updates …", "$TAG:updates")
        }
    }

    @Composable
    private fun Line(text: String, tag: String) =
        Text(text, color = Ink.DIM.ink(), fontSize = StoreDensity.T_META.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(tag))

    private const val SETTLE_MS = 400L
}
