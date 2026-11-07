package com.diegonmarcos.cloudcalc.ui

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.diegonmarcos.cloudcalc.tools.WorldClockData as W

/**
 * #1423 the World Clock's declared data (worldmap/cities, worldmap/land), the runtime datasets Cloud Store serves through libs:fleet-data-client
 * (#1547; repo data/fleet/app/com.diegonmarcos.cloudcalc/worldmap/), never baked into the APK. Null while not served (first run, provider absent):
 * never throws and a null is never cached, the caller retries.
 */
object WorldAssets {
    @Volatile private var cat: W.Catalogue? = null
    @Volatile private var land: List<DoubleArray>? = null
    private fun <T : Any> served(ctx: Context, dataset: String, parse: (String) -> T): T? =
        runCatching { FleetData.get(ctx, dataset, own = true) { parse(it) } }.getOrNull()
    fun catalogue(ctx: Context): W.Catalogue? = cat ?: served(ctx, "worldmap/cities") { W.parse(it) }?.also { cat = it }
    fun land(ctx: Context): List<DoubleArray>? = land ?: served(ctx, "worldmap/land") { W.parseLand(it) }?.also { land = it }
}

/** The value [load] returns, null until it does (retried every few seconds off the main thread, #1547: Cloud Store has not served the dataset yet). */
@Composable
fun <T : Any> rememberServed(key: Any, load: () -> T?): T? {
    var value by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) {
        while (value == null) {
            value = withContext(Dispatchers.IO) { load() }
            if (value == null) delay(3_000)
        }
    }
    return value
}

const val WORLD_MAP_TAG = "clock_world_map"

/**
 * #1423 the world map: Natural Earth land (public domain) in an equirectangular projection on a Compose Canvas, the 25
 * nominal time-zone bands with their UTC offset, the night side under the day/night terminator at [now], and a pin for
 * each selected zone's city. A tap toggles the zone of the nearest catalogue city: added when absent, removed when shown.
 */
@Composable
fun WorldMap(zones: List<String>, now: Long, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val cat = rememberServed("cities") { WorldAssets.catalogue(ctx) }
    val land = rememberServed("land") { WorldAssets.land(ctx) } ?: emptyList()
    val cs = MaterialTheme.colorScheme
    val ocean = cs.surfaceVariant; val ground = cs.secondaryContainer; val line = cs.outline
    val pin = cs.primary; val text = cs.onSurface
    val pins = remember(zones, cat) { cat?.let { c -> zones.mapNotNull { W.pinFor(c, it) } } ?: emptyList() }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(2f).testTag(WORLD_MAP_TAG).semantics { contentDescription = "World map" }.pointerInput(zones, cat) {
            detectTapGestures { o ->
                val w = size.width.toDouble(); val h = size.height.toDouble()
                cat?.let { c -> W.nearest(c, W.lat(o.y.toDouble(), h), W.lon(o.x.toDouble(), w), 6.0) }?.let { onToggle(it.zone) }
            }
        },
    ) {
        val w = size.width.toDouble(); val h = size.height.toDouble()
        fun pt(lon: Double, lat: Double) = Offset(W.x(lon, w).toFloat(), W.y(lat, h).toFloat())
        fun poly(p: DoubleArray) = Path().apply {
            moveTo(W.x(p[0], w).toFloat(), W.y(p[1], h).toFloat())
            var i = 2
            while (i < p.size) { lineTo(W.x(p[i], w).toFloat(), W.y(p[i + 1], h).toFloat()); i += 2 }
            close()
        }
        drawRect(ocean)
        val bands = W.bands()
        for (b in bands) if (b.offset % 2 != 0) drawRect(line.copy(alpha = 0.10f), pt(b.west, 90.0), Size((W.x(b.east, w) - W.x(b.west, w)).toFloat(), size.height))
        for (p in land) drawPath(poly(p), ground)
        drawPath(poly(W.nightPolygon(W.sun(now))), Color.Black.copy(alpha = 0.40f))
        for (b in bands) if (b.west > -180.0) drawLine(line.copy(alpha = 0.5f), pt(b.west, 90.0), pt(b.west, -90.0), strokeWidth = 1f)
        label(bands, w, text)
        for (c in pins) {
            val o = pt(c.lon, c.lat)
            val d = density
            drawCircle(Color.White, 6 * d, o)
            drawCircle(pin, 4.5f * d, o)
            drawCircle(Color.Black, 6 * d, o, style = Stroke(d))
            drawText(c.name, o.x + 8 * d, o.y - 4 * d, text, 11f)
        }
    }
}

private fun DrawScope.label(bands: List<W.Band>, w: Double, color: Color) {
    for (b in bands) if (b.offset % 3 == 0) {
        val x = W.x((b.west + b.east) / 2, w).toFloat()
        drawText(b.label.removePrefix("UTC"), x, size.height - 3 * density, color, 9f, center = true)
    }
}

private fun DrawScope.drawText(s: String, x: Float, y: Float, color: Color, sp: Float, center: Boolean = false) {
    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb(); textSize = sp * density * fontScale
        textAlign = if (center) android.graphics.Paint.Align.CENTER else android.graphics.Paint.Align.LEFT
    }
    drawContext.canvas.nativeCanvas.drawText(s, x, y, p)
}
