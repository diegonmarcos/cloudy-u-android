package com.diegonmarcos.capitalfinterm

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * #1539 where the CB page reads its declarations and its numbers: the baked assets (cb-countries.json, cb-indicators.json, cb-snapshot.json) and, when the user refreshed
 * in the app, the cached refresh (newer than the baked snapshot). The refresh runs only when asked; nothing polls.
 */
object CbStore {
    const val COUNTRIES = "cb-countries.json"
    const val INDICATORS = "cb-indicators.json"
    const val SNAPSHOT = "cb-snapshot.json"
    private const val CACHE = "cb-refresh.json"

    fun decl(ctx: Context): CbDecl = CbDecl.parse(AssetData.read(ctx, COUNTRIES), AssetData.read(ctx, INDICATORS))

    fun snapshot(ctx: Context): CbSnapshot {
        val baked = CbSnapshot.parse(AssetData.read(ctx, SNAPSHOT))
        val f = File(ctx.applicationContext.filesDir, CACHE)
        val cached = if (f.isFile) runCatching { CbSnapshot.parse(f.readText()) }.getOrNull() else null
        return if (cached != null && cached.generated >= baked.generated) cached else baked
    }

    /** Fetches every declared source now (blocking: call off the main thread), caches the answer and returns it. */
    fun refresh(ctx: Context, decl: CbDecl): CbSnapshot {
        val now = Date()
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(now)
        val year = Calendar.getInstance().get(Calendar.YEAR)
        val json = CbRefresh(UrlHttp(maxBytes = 16_000_000), decl, today, year).run()
        val snap = CbSnapshot.parse(json)
        if (snap.series.values.sumOf { it.size } == 0) throw java.io.IOException("no source answered")
        File(ctx.applicationContext.filesDir, CACHE).writeText(json)
        return snap
    }
}
