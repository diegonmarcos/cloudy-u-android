package com.diegonmarcos.cloudnav.routes

import android.content.Context
import android.location.Location
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudnav.CockpitMode
import com.diegonmarcos.cloudnav.FragmentHost
import com.diegonmarcos.cloudnav.NavConfig
import com.diegonmarcos.cloudnav.R
import com.diegonmarcos.cloudnav.ResultPicker
import com.diegonmarcos.cloudnav.cockpit.CockpitFeed
import com.diegonmarcos.cloudnav.cockpit.CockpitGauge
import com.diegonmarcos.cloudnav.cockpit.CockpitGauges
import com.diegonmarcos.cloudnav.cockpit.CockpitSensors
import com.diegonmarcos.cloudnav.maps.MapsMapFragment
import com.diegonmarcos.cloudnav.maps.MapsProviderClient
import com.diegonmarcos.cloudnav.maps.NavCardState
import com.diegonmarcos.cloudnav.maps.NavInfo
import com.diegonmarcos.cloudnav.places.PlaceDetailLogic
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.KitSearchBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Navigation tab — a multi-transport cockpit over the shared [MapsMapFragment].
 * A mode switcher (Bike · Car · Boat · Airplane, data-driven from
 * build.json::ui.cockpit_modes → [NavConfig.cockpitModes]) retunes both the map
 * camera/basemap and the live instrument panel. Each mode declares its own gauge
 * cluster; gauges are fed from GPS fixes + [CockpitSensors] (heading, attitude,
 * barometric altitude/vertical-speed). No mocks — a gauge whose sensor is absent
 * simply reads its GPS fallback or stays blank.
 *
 * Compose (KitComposeFragment): the map is libs:maps' fragment inside a [FragmentHost]; the gauges
 * are Canvas composables over one [CockpitFeed].
 */
class NavigationFragment : KitComposeFragment() {

    private val modes = NavConfig.cockpitModes
    private var mode by mutableStateOf(modes.firstOrNull() ?: error("No cockpit_modes configured in build.json"))
    private var mapFragment: MapsMapFragment? = null
    private var sensors: CockpitSensors? = null
    private val feed = CockpitFeed()
    private val picker = ResultPicker()

    private var query by mutableStateOf("")
    private var maneuverText by mutableStateOf("Set a destination")
    private var maneuverSub by mutableStateOf("Pick a mode, then “Where to?”")

    private var destLat: Double? = null
    private var destLon: Double? = null
    private var destLabel: String = ""
    private var speedSum = 0.0
    private var speedCount = 0
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastAlt = Double.NaN

    override fun palette(): KitPalette = TrackerInk.palette()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val saved = savedMode(requireContext())
        mode = modes.firstOrNull { it.id == saved }
            ?: modes.firstOrNull { it.id == NavConfig.defaultCockpitMode }
            ?: mode
    }

    /** A fresh map page in the current mode, wired to this tab. Called by the host each time its container is new. */
    private fun newMap(): MapsMapFragment =
        MapsMapFragment.newInstance(nav3d = true, fab = true, style = mode.style, autoLocate = true).also { map ->
            mapFragment = map
            map.onUserLocation = { loc -> onFix(loc) }
            map.onMapReady = { _ -> map.applyNavMode(mode.zoom, mode.tilt, mode.style, mode.followBearing) }
        }

    @Composable
    override fun Content() {
        val keyboard = LocalSoftwareKeyboardController.current
        Box(Modifier.fillMaxSize()) {
            // ── Map (nav3d so tilt/heading-follow apply; mode reconfigures camera).
            FragmentHost(childFragmentManager, R.id.nav_host_navigation, 0, Modifier.fillMaxSize()) { newMap() }

            // ── Top stack: mode switcher · search · maneuver banner ───────────
            Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    modes.forEach { m -> ModeChip(m, m.id == mode.id) { if (m.id != mode.id) switchMode(m) } }
                }
                KitSearchBar(query, { query = it }, { setDestination(query); keyboard?.hide() }, placeholder = "Where to? Address or place…")
                ManeuverBanner(mode, maneuverText, maneuverSub)
            }

            // ── Bottom instrument cluster (data-driven per mode) ──────────────
            Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 8.dp).height(150.dp)
                .clip(RoundedCornerShape(20.dp)).background(Color(0xFF0A0D14)).testTag(NavigationTags.CLUSTER)) {
                mode.gauges.forEach { id ->
                    CockpitGauge(id, mode, feed, Modifier.weight(1f).fillMaxHeight().padding(horizontal = 3.dp))
                }
            }
        }
        picker.Host()
    }

    override fun onResume() {
        super.onResume()
        sensors = CockpitSensors(requireContext()).also {
            it.onHeading = { deg -> feed.onHeading(deg) }
            it.onAttitude = { p, r -> feed.onAttitude(p, r) }
            it.onBaroAltitude = { m, v ->
                feed.onAltitude(CockpitGauges.metersTo(mode.altUnit, m.toDouble()))
                feed.onVerticalMps(v.toDouble())
            }
            it.start()
        }
    }

    override fun onDestroy() { NavCardState.clear(); super.onDestroy() }   // #1944 no destination, no navigation lines on the card

    override fun onPause() { sensors?.stop(); sensors = null; super.onPause() }

    // ── mode switching ───────────────────────────────────────────────────
    private fun switchMode(m: CockpitMode) {
        mode = m
        saveMode(requireContext(), m.id)
        speedSum = 0.0; speedCount = 0
        feed.reset()
        mapFragment?.applyNavMode(m.zoom, m.tilt, m.style, m.followBearing)
    }

    // ── live data ────────────────────────────────────────────────────────
    private fun onFix(loc: Location) {
        val kmh = if (loc.hasSpeed()) loc.speed * 3.6 else 0.0
        val sUnit = CockpitGauges.speedUnitLabel(mode.speedUnit)
        feed.onSpeed(CockpitGauges.kmhTo(mode.speedUnit, kmh), sUnit)

        if (kmh > 0.5) { speedSum += kmh; speedCount++ }
        val avgKmh = if (speedCount > 0) speedSum / speedCount else 0.0
        feed.onMetric("avg", if (speedCount > 0) "%.0f %s".format(CockpitGauges.kmhTo(mode.speedUnit, avgKmh), sUnit) else "—")

        if (loc.hasBearing()) feed.onMetric("track", "%03d°".format(loc.bearing.roundToInt() % 360))
        feed.onMetric("position", fmtCoord(loc.latitude, "N", "S"), fmtCoord(loc.longitude, "E", "W"))

        // Altitude: barometer feeds it in onResume; GPS is the fallback.
        if (loc.hasAltitude() && sensors?.hasBarometer != true) {
            feed.onAltitude(CockpitGauges.metersTo(mode.altUnit, loc.altitude))
        }

        // Grade (%) from consecutive fixes: rise / horizontal run.
        if (!lastLat.isNaN() && loc.hasAltitude() && !lastAlt.isNaN()) {
            val runM = PlaceDetailLogic.haversineKm(lastLat, lastLon, loc.latitude, loc.longitude) * 1000.0
            if (runM > 5.0) {
                val grade = (loc.altitude - lastAlt) / runM * 100.0
                feed.onMetric("grade", "%+.0f%%".format(grade))
            }
        }
        lastLat = loc.latitude; lastLon = loc.longitude
        if (loc.hasAltitude()) lastAlt = loc.altitude

        // Distance + ETA to destination.
        val dLat = destLat; val dLon = destLon
        if (dLat != null && dLon != null) {
            val km = PlaceDetailLogic.haversineKm(loc.latitude, loc.longitude, dLat, dLon)
            val distText = if (km < 1.0) "${(km * 1000).roundToInt()} m" else "%.1f km".format(km)
            feed.onMetric("dist", distText)
            val useSpeed = if (avgKmh > 3.0) avgKmh else kmh
            val etaText = if (useSpeed > 3.0) {
                val mins = km / useSpeed * 60.0
                SimpleDateFormat("HH:mm", Locale.US).format(Date(System.currentTimeMillis() + (mins * 60_000).toLong()))
            } else "—"
            feed.onMetric("eta", etaText)
            // #1944 the background card shows what this tab knows: destination, maneuver line, distance and ETA.
            NavCardState.publish(NavInfo(destLabel, maneuverText, maneuverSub, distText, etaText, "${mode.emoji} ${mode.label}"))
        }
    }

    private fun setDestination(q: String) {
        if (q.isBlank()) return
        val ctx = context ?: return
        val center = mapFragment?.centerTarget()
        Thread {
            val hits = MapsProviderClient.forwardSearch(ctx, q, center?.first ?: 0.0, center?.second ?: 0.0)
            ui {
                if (hits.isEmpty()) { Toast.makeText(ctx, "No match for \"$q\"", Toast.LENGTH_SHORT).show(); return@ui }
                picker.offer(hits) { hit ->
                    destLat = hit.lat; destLon = hit.lon; destLabel = hit.title
                    query = hit.title
                    maneuverText = "Head to $destLabel"
                    maneuverSub = "${mode.emoji} ${mode.label} · follow the route"
                    NavCardState.publish(NavInfo(destLabel, maneuverText, maneuverSub, "", "", "${mode.emoji} ${mode.label}"))   // #1944
                    mapFragment?.setPins(listOf(MapsMapFragment.Pin(hit.lat, hit.lon, MapsMapFragment.COLOR_RESULT)))
                    mapFragment?.recenterOnUser()
                }
            }
        }.start()
    }

    private fun fmtCoord(v: Double, pos: String, neg: String): String =
        "%.4f°%s".format(kotlin.math.abs(v), if (v >= 0) pos else neg)

    private fun savedMode(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
    private fun saveMode(ctx: Context, id: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, id).apply()

    private fun ui(block: () -> Unit) { if (!isAdded) return; requireActivity().runOnUiThread { if (isAdded) block() } }

    private companion object {
        const val PREFS = "cloud_nav_cockpit"
        const val KEY_MODE = "mode"
    }
}

object NavigationTags {
    const val CLUSTER = "navigation:cluster"
    const val BANNER = "navigation:banner"
    const val MANEUVER = "navigation:maneuver"
    fun mode(id: String) = "navigation:mode:$id"
}

/** One vehicle of the switcher; the current one is filled with the mode's accent. */
@Composable
private fun ModeChip(m: CockpitMode, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.testTag(NavigationTags.mode(m.id)).clip(RoundedCornerShape(18.dp))
        .background(if (on) Color(m.accent) else Color(CockpitGauges.BG)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(m.emoji, Modifier.padding(end = 6.dp), fontSize = 18.sp)
        Text(m.label, color = if (on) Color(0xFF0A0D14) else Color(CockpitGauges.TEXT), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ManeuverBanner(mode: CockpitMode, text: String, sub: String) {
    Row(Modifier.fillMaxWidth().testTag(NavigationTags.BANNER).clip(RoundedCornerShape(16.dp)).background(Color(CockpitGauges.BG))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("▲", Modifier.padding(end = 14.dp), color = Color(mode.accent), fontSize = 24.sp)
        Column {
            Text(text, Modifier.testTag(NavigationTags.MANEUVER), color = Color(CockpitGauges.TEXT), fontSize = 17.sp)
            Text(sub, color = Color(CockpitGauges.SUB), fontSize = 12.sp)
        }
    }
}
