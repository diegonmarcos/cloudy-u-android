package com.diegonmarcos.capitalfinterm

import android.content.Context

/** The declared data shipped in the APK's assets (the app's data/ folder): the source catalog and the dashboards. Parsed once per process. */
object AssetData {
    const val SOURCES = "sources.json"
    const val DASHBOARDS = "dashboards.json"
    const val CONTROL = "control.json"
    const val UNIVERSE = "universe.json"

    @Volatile private var sourcesJson: String? = null
    @Volatile private var sourcesList: List<Source>? = null
    @Volatile private var dashboardsList: List<Dashboard>? = null

    fun read(ctx: Context, name: String): String = ctx.applicationContext.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    fun sourcesJson(ctx: Context): String = sourcesJson ?: read(ctx, SOURCES).also { sourcesJson = it }

    fun sources(ctx: Context): List<Source> = sourcesList ?: Catalog.parse(sourcesJson(ctx)).also { sourcesList = it }

    fun control(ctx: Context): ControlDecl? = Control.parse(read(ctx, CONTROL))

    fun dashboards(ctx: Context): List<Dashboard> = dashboardsList ?: Dashboards.parse(read(ctx, DASHBOARDS)).also { dashboardsList = it }
}
