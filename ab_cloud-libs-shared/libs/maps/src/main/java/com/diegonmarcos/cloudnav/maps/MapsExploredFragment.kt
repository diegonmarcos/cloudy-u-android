package com.diegonmarcos.cloudnav.maps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Timeline → "Explored" tab — a world map of everywhere the user has been.
 *
 * A FAB (bottom-start) switches the aggregation level:
 *   • COUNTRY — one pin per country (at its cities' centroid). Tap → the list
 *     of cities visited in that country.
 *   • CITY (default) — one pin per city. Tap → every day the user was there.
 *   • PLACES — one pin per distinct place. Tap → that place's visit days.
 *
 * All pins are COLOURED BY COUNTRY (deterministic hue, [countryColor]). Data
 * is sourced from [computeDailyLocations] (Daily's one-place-per-day picks),
 * NOT raw per-Stop rows, so revisited spots collapse to one clean pin.
 */
class MapsExploredFragment : KitComposeFragment() {

    enum class Mode { COUNTRY, CITY, PLACES }

    /** The sheet that is open over the map, if any. */
    private sealed class Sheet {
        object Items : Sheet()
        object Layers : Sheet()
        class Detail(val title: String, val subtitle: String, val rows: List<String>) : Sheet()
    }

    private var mode by mutableStateOf(Mode.CITY)
    private var daily: List<DailyEntry> = emptyList()
    private var sheet by mutableStateOf<Sheet?>(null)

    // The live child map fragment. Pins are now NATIVE MapLibre CircleLayer
    // features (typed FeatureCollection — proven to render on this stack once the
    // string-geojson bug was fixed), NOT a Canvas overlay. Native pins are part
    // of the GL frame, so they never trail/shake during pan/zoom.
    private var mapFrag: MapsMapFragment? = null

    // Pins are a toggleable layer alongside the choropleth fills.
    private var pinsVisible by mutableStateOf(true)

    // Choropleth fill layers (visited continents/countries/regions/nomad).
    private var geo: MapsGeoLayers? = null
    // Default: paint visited COUNTRIES light gray (the original ask).
    private var geoLayers by mutableStateOf(setOf(MapsGeoLayers.Layer.COUNTRIES))
    // PIN = paint only visited areas; DEFAULT = colour the whole world by continent.
    private var paintMode by mutableStateOf(MapsGeoLayers.PaintMode.PIN)

    // Current mode's pin-backing objects, index-aligned with the pins' ids, so
    // a pin tap resolves straight back to its country/city/place.
    private var countries: List<ExploredCountry> = emptyList()
    private var cities: List<ExploredCity> = emptyList()
    private var places: List<ExploredPlace> = emptyList()
    // country name → [lat, lon] of its capital (COUNTRY-mode pin sits here).
    private var capitals: Map<String, DoubleArray> = emptyMap()

    override fun palette(): KitPalette = MapsInk.palette()

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val p = LocalKitPalette.current
        remember {
            val stops = MapsDb.get(ctx).stopsBetween(0L, System.currentTimeMillis())
            daily = computeDailyLocations(stops)
            cities = groupByCity(daily)
            countries = groupByCountry(cities)
            // PLACES groups ALL raw stops (not the one-per-day `daily`), so a city
            // with several distinct places shows several pins → PLACES renders many
            // more pins than CITY.
            places = groupByPlaceFromStops(stops)
            true
        }
        // #1547 the capitals are a served dataset: pins sit at the cities' centroid until it arrives, then move to the capitals.
        androidx.compose.runtime.LaunchedEffect(ctx) { MapsGeoData.observeCapitals(ctx).collect { if (it != null) { capitals = it; recomputePins(false) } } }

        Box(Modifier.fillMaxSize()) {
            // worldView: global overview — start showing the whole world, not the
            // user's last GPS fix. style "light" → vector_light (Timeline's default).
            ChildFragmentHost(
                fm = childFragmentManager,
                containerId = R.id.maps_host_explored_map,
                key = "map",
                modifier = Modifier.fillMaxSize(),
            ) { newMap(ctx) }

            // Summary "island" chip (top-center) — TAP it to open the full
            // scrollable list of the current mode's countries / cities / places.
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(start = 8.dp, top = 10.dp, end = 8.dp)
                    .testTag("maps:explored:chip").clickable { sheet = Sheet.Items },
                shape = RoundedCornerShape(22.dp),
                color = p.surfaceSelected,
                border = BorderStroke(1.dp, p.accent),
                shadowElevation = 8.dp,
            ) {
                Text(chipText(), color = p.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
            }

            // Single Layers FAB — small BLACK circle (matches the island), TOP-RIGHT.
            // One sheet controls the pins layer + the painted fills.
            FloatingActionButton(
                onClick = { sheet = Sheet.Layers },
                shape = CircleShape,
                containerColor = p.surfaceSelected,
                contentColor = p.textPrimary,
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp).testTag("maps:explored:layers"),
            ) {
                Icon(painterResource(android.R.drawable.ic_menu_mapmode), contentDescription = "Layers")
            }
        }

        when (val open = sheet) {
            null -> {}
            Sheet.Items -> ItemsSheet()
            Sheet.Layers -> LayersSheet()
            is Sheet.Detail -> DetailSheet(open)
        }
    }

    /** The child map, configured BEFORE it is committed: pins and layers install when its style loads. */
    private fun newMap(ctx: android.content.Context): MapsMapFragment {
        val frag = MapsMapFragment.newInstance(fab = true, worldView = true, style = "light")
        mapFrag = frag

        // Native pins: a tap resolves the pin id back to its country/city/place.
        frag.onPinClick = { id -> onPinTapped(id) }
        frag.onMapReady = { _ -> recomputePins(frame = true) }

        // Choropleth fill layers — paint visited areas. Visited countries come
        // from the same grouped data the pins use; regions/continents are
        // derived inside the manager. Reinstalled on every style (re)load.
        val geoMgr = MapsGeoLayers(ctx, frag)
        geo = geoMgr
        geoMgr.configure(
            cities.map { it.country },
            cities.map { doubleArrayOf(it.lat, it.lon) },   // visited points → visited States (PIP)
            geoLayers, paintMode,
        )
        frag.onStyleReady = { geoMgr.reinstall() }
        return frag
    }

    private fun chipText(): String {
        if (cities.isEmpty()) return "No places yet — load demo data or start the tracker (Configs → Tracker)."
        val countryCount = cities.mapNotNull { it.country }.toSet().size
        return when (mode) {
            Mode.COUNTRY -> "🌐  ${countries.size} countries · ${cities.size} cities — tap a pin"
            Mode.CITY -> "🏙️  ${cities.size} cities · $countryCount countries — tap a pin"
            Mode.PLACES -> "📍  ${places.size} places · ${cities.size} cities — tap a pin"
        }
    }

    /** One tappable line of a sheet. */
    @Composable
    private fun SheetLine(text: String, color: androidx.compose.ui.graphics.Color, size: Int, tag: String, onTap: () -> Unit) {
        Text(text, color = color, fontSize = size.sp,
            modifier = Modifier.fillMaxWidth().testTag(tag).clickable { onTap() }.padding(horizontal = 8.dp, vertical = 12.dp))
    }

    /** Full scrollable list of the current mode's items. Tap a row → recenter
     *  the map on it and open its detail sheet. */
    @Composable
    private fun ItemsSheet() {
        val p = LocalKitPalette.current
        val header = when (mode) {
            Mode.COUNTRY -> "🌐  ${countries.size} countries"
            Mode.CITY -> "🏙️  ${cities.size} cities"
            Mode.PLACES -> "📍  ${places.size} places"
        }
        // rows: (label, lat, lon, detail)
        class Line(val label: String, val lat: Double, val lon: Double, val detail: Sheet.Detail)
        val rows: List<Line> = when (mode) {
            Mode.COUNTRY -> countries.map { c ->
                val days = c.cities.sumOf { it.visits.size }
                Line("${c.country}   ·   ${c.cities.size} cities · $days days", c.lat, c.lon, countrySheet(c))
            }
            Mode.CITY -> cities.map { c ->
                val blocks = visitBlocks(c.visits.map { it.dayMs })
                Line("${c.city}${c.country?.let { ", $it" } ?: ""}   ·   $blocks visits · ${c.visits.size} days", c.lat, c.lon, citySheet(c))
            }
            Mode.PLACES -> places.map { pl ->
                Line("${pl.name}${pl.city?.let { ", $it" } ?: ""}   ·   ${pl.days.size} days", pl.lat, pl.lon, placeSheet(pl))
            }
        }
        MapsSheet(onDismiss = { sheet = null }) {
            Text(header, color = p.textPrimary, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
            rows.forEachIndexed { i, r ->
                SheetLine(r.label, p.textPrimary, 15, "maps:explored:item:$i") {
                    mapFrag?.recenter(r.lat, r.lon, 6.0)
                    sheet = r.detail
                }
            }
        }
    }

    /** ONE sheet for every layer — styled like the black "island" chip: the PINS layer (off /
     *  Countries / Cities / Places), the PAINT mode (Pin vs Default), and the painted choropleth fills. */
    @Composable
    private fun LayersSheet() {
        val p = LocalKitPalette.current
        @Composable
        fun header(t: String) = Text(t, color = p.textSecondary, fontSize = 15.sp,
            modifier = Modifier.padding(start = 8.dp, top = 12.dp, end = 8.dp, bottom = 6.dp))
        @Composable
        fun row(label: String, selected: Boolean, mark: String, tag: String, onTap: () -> Unit) =
            SheetLine("$mark  $label", if (selected) p.accent else p.textPrimary, 17, tag, onTap)

        MapsSheet(onDismiss = { sheet = null }) {
            // ── PINS layer (single-select aggregation, or off) ──
            header("Pins")
            listOf(
                null to "Off",
                Mode.COUNTRY to "🌐  Countries  (${countries.size})",
                Mode.CITY to "🏙️  Cities  (${cities.size})",
                Mode.PLACES to "📍  Places  (${places.size})",
            ).forEach { (m, label) ->
                val sel = if (m == null) !pinsVisible else (pinsVisible && mode == m)
                row(label, sel, if (sel) "◉" else "○", "maps:explored:pins:${m ?: "off"}") {
                    if (m == null) pinsVisible = false else { pinsVisible = true; mode = m }
                    recomputePins(frame = false)
                }
            }

            // ── PAINT mode: Pin (visited only) vs Default (whole world by continent) ──
            header("Paint mode")
            listOf(
                MapsGeoLayers.PaintMode.PIN to "Pin — only where I've been",
                MapsGeoLayers.PaintMode.DEFAULT to "Default — every country by continent",
            ).forEach { (pm, label) ->
                val sel = paintMode == pm
                row(label, sel, if (sel) "◉" else "○", "maps:explored:paint:${pm.name}") {
                    if (paintMode != pm) { paintMode = pm; geo?.setPaintMode(pm) }
                }
            }

            // ── PAINTED fills (multi-select) ──
            header(if (paintMode == MapsGeoLayers.PaintMode.DEFAULT) "Painted layers" else "Painted layers (visited)")
            MapsGeoLayers.Layer.values().forEach { layer ->
                val on = layer in geoLayers
                row(layer.label, on, if (on) "☑" else "☐", "maps:explored:layer:${layer.id}") {
                    geoLayers = if (on) geoLayers - layer else geoLayers + layer
                    geo?.setEnabled(geoLayers)
                }
            }
        }
    }

    // ── detail sheets ──────────────────────────────────────────────────
    @Composable
    private fun DetailSheet(d: Sheet.Detail) {
        val p = LocalKitPalette.current
        MapsSheet(onDismiss = { sheet = null }) {
            Text(d.title, color = p.textPrimary, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(d.subtitle, color = p.accent, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 3.dp, bottom = 12.dp))
            d.rows.forEach { r ->
                Text(r, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp))
            }
        }
    }

    private fun countrySheet(c: ExploredCountry) = Sheet.Detail(
        "🌐  ${c.country}",
        "${c.cities.size} cit${if (c.cities.size == 1) "y" else "ies"} · ${c.cities.sumOf { it.visits.size }} days total",
        c.cities.sortedByDescending { it.visits.size }.map {
            val blocks = visitBlocks(it.visits.map { v -> v.dayMs }); val days = it.visits.size
            "${it.city}  ·  $blocks visit${if (blocks == 1) "" else "s"} · $days day${if (days == 1) "" else "s"}"
        },
    )

    private fun citySheet(city: ExploredCity): Sheet.Detail {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        val blocks = visitBlocks(city.visits.map { it.dayMs }); val days = city.visits.size
        return Sheet.Detail(
            "🏙️  ${city.city}" + (city.country?.let { ", $it" } ?: ""),
            "$blocks visit${if (blocks == 1) "" else "s"} · $days day${if (days == 1) "" else "s"} total",
            city.visits.sortedByDescending { it.dayMs }
                .map { fmt.format(Date(it.dayMs)) + (it.placeName?.let { p -> "  ·  $p" } ?: "") },
        )
    }

    private fun placeSheet(p: ExploredPlace): Sheet.Detail {
        val fmt = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
        return Sheet.Detail(
            "📍  ${p.name}",
            listOfNotNull(p.city, p.country).joinToString(", ") + " · ${p.days.size} day${if (p.days.size == 1) "" else "s"}",
            p.days.sortedDescending().map { fmt.format(Date(it)) },
        )
    }

    /** Rebuild the NATIVE pins for the current [mode] (one CircleLayer feature
     *  per country/city/place, coloured by country, id = "MODE:index"). The map
     *  renders them in-frame (no shake) and sizes them by zoom. Optionally frames
     *  the camera to fit them (first load only). */
    private fun recomputePins(frame: Boolean) {
        val frag = mapFrag ?: return
        if (!pinsVisible) { frag.clearPins(); return }
        val pins = when (mode) {
            // Country pin sits at the CAPITAL (falls back to the cities centroid).
            Mode.COUNTRY -> countries.mapIndexed { i, c ->
                val cap = capitals[c.country]
                MapsMapFragment.Pin(cap?.get(0) ?: c.lat, cap?.get(1) ?: c.lon, countryColor(c.country), "", "C:$i")
            }
            Mode.CITY -> cities.mapIndexed { i, c -> MapsMapFragment.Pin(c.lat, c.lon, countryColor(c.country), "", "T:$i") }
            Mode.PLACES -> places.mapIndexed { i, p -> MapsMapFragment.Pin(p.lat, p.lon, countryColor(p.country), "", "P:$i") }
        }
        android.util.Log.i("MapPins", "Explored.pins mode=$mode pins=${pins.size}")
        frag.setPins(pins)
        if (frame && pins.isNotEmpty()) frag.fitTo(pins)
    }

    /** A native pin was tapped — its id is "MODE:index" → open the detail sheet. */
    private fun onPinTapped(id: String) {
        val i = id.substringAfter(':').toIntOrNull() ?: return
        when (id.firstOrNull()) {
            'C' -> countries.getOrNull(i)?.let { sheet = countrySheet(it) }
            'T' -> cities.getOrNull(i)?.let { sheet = citySheet(it) }
            'P' -> places.getOrNull(i)?.let { sheet = placeSheet(it) }
        }
    }

    override fun onDestroyView() {
        mapFrag = null; geo = null
        super.onDestroyView()
    }

    // ── test hooks ──────────────────────────────────────────────────────
    /** How many native pins the current mode built (Places must be > Cities). */
    fun debugPinModelCount(): Int = when (mode) {
        Mode.COUNTRY -> countries.size; Mode.CITY -> cities.size; Mode.PLACES -> places.size
    }
    /** Feature count the LIVE MapLibre pin SOURCE reports — proves the native
     *  CircleLayer actually renders the typed FeatureCollection on this stack. */
    fun debugPinSourceCount(): Int = mapFrag?.debugPinFeatureCount() ?: -1
    fun debugMapState(): String = mapFrag?.debugState() ?: "mapFrag=null"
    fun debugSetMode(m: Mode) { mode = m; recomputePins(frame = false) }
    /** Visited-only feature count the manager built for [layer] (needs assets). */
    fun debugGeoVisitedCount(layer: MapsGeoLayers.Layer): Int = geo?.visitedCount(layer) ?: -1
    /** Feature count the LIVE MapLibre fill SOURCE reports — proves the typed
     *  FeatureCollection registered on this stack (make-or-break for Approach A). */
    fun debugFillSourceCount(layer: MapsGeoLayers.Layer): Int =
        mapFrag?.debugFillFeatureCount("geo-src-${layer.id}") ?: -1

    // ── data model ─────────────────────────────────────────────────────
    data class CityVisit(val dayMs: Long, val placeName: String?)
    data class ExploredCity(
        val city: String, val country: String?,
        val lat: Double, val lon: Double, val visits: List<CityVisit>,
    )
    data class ExploredCountry(
        val country: String, val lat: Double, val lon: Double, val cities: List<ExploredCity>,
    )
    data class ExploredPlace(
        val name: String, val city: String?, val country: String?,
        val lat: Double, val lon: Double, val days: List<Long>,
    )

    companion object {
        /** One [ExploredCity] per distinct, non-blank city. Pure/tested. */
        fun groupByCity(daily: List<DailyEntry>): List<ExploredCity> =
            daily.filter { !it.city.isNullOrBlank() }
                .groupBy { it.city }
                .map { (city, entries) ->
                    ExploredCity(
                        city = city!!,
                        country = entries.firstNotNullOfOrNull { it.country },
                        lat = entries.map { it.lat }.average(), lon = entries.map { it.lon }.average(),
                        visits = entries.map { CityVisit(it.dayMs, it.placeName) },
                    )
                }.sortedByDescending { it.visits.maxOf { v -> v.dayMs } }

        /** One [ExploredCountry] per distinct country, positioned at the
         *  centroid of its cities. Pure/tested. */
        fun groupByCountry(cities: List<ExploredCity>): List<ExploredCountry> =
            cities.filter { !it.country.isNullOrBlank() }
                .groupBy { it.country }
                .map { (country, cs) ->
                    ExploredCountry(
                        country = country!!,
                        lat = cs.map { it.lat }.average(), lon = cs.map { it.lon }.average(),
                        cities = cs,
                    )
                }.sortedByDescending { it.cities.sumOf { c -> c.visits.size } }

        /** One [ExploredPlace] per distinct place name across ALL raw stops
         *  (not the one-per-day picks) — so every place the user stopped at gets
         *  its own pin, and a city with N distinct places shows N pins. Pure/
         *  tested. */
        fun groupByPlaceFromStops(stops: List<MapsDb.RichStop>): List<ExploredPlace> =
            stops.filter { !it.placeName.isNullOrBlank() }
                .groupBy { it.placeName }
                .map { (name, group) ->
                    val g0 = group.first()
                    ExploredPlace(
                        name = name!!, city = g0.city, country = g0.country,
                        lat = group.map { it.lat }.average(), lon = group.map { it.lon }.average(),
                        days = group.map { MapsDailyFragment.localMidnight(it.startedAt) }.distinct(),
                    )
                }.sortedByDescending { it.days.size }

        /** Deterministic pin colour per COUNTRY — stable hue from the name,
         *  golden-angle spread, vivid on the dark basemap. Null/blank → the
         *  neutral place-blue. Pure/tested. */
        fun countryColor(country: String?): String {
            val c = country?.trim().orEmpty()
            if (c.isEmpty()) return MapsMapFragment.COLOR_PLACE
            var h = 0
            for (ch in c) h = h * 31 + ch.code
            val hue = ((h * 137) % 360 + 360) % 360
            val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.78f, 0.95f))
            return "#%06X".format(rgb and 0xFFFFFF)
        }

        /** Number of separate VISIT blocks (runs of consecutive days) from a list
         *  of day-timestamps — distinct from the total day count. A gap of more
         *  than ~1.5 days starts a new visit. Pure/tested. */
        fun visitBlocks(dayMsList: List<Long>): Int {
            if (dayMsList.isEmpty()) return 0
            val days = dayMsList.map { MapsDailyFragment.localMidnight(it) }.distinct().sorted()
            var blocks = 1
            for (k in 1 until days.size) if (days[k] - days[k - 1] > 36L * 3600_000L) blocks++
            return blocks
        }

        fun newInstance() = MapsExploredFragment()
    }
}
