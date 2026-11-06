package com.diegonmarcos.cloudnav.tracker

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.annotation.DrawableRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.diegonmarcos.cloudnav.maps.MapStyles
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource

/**
 * The Day page's map (#1145): a real OpenStreetMap basemap (the app's `light` raster style from build.json::ui.map_styles,
 * OSM attribution shown by MapLibre, tiles cached by MapLibre's ambient tile cache), the route as one polyline per move
 * coloured by mode, mode icons on the moves, category icons on the visits and the current position. [RouteGeo] decides
 * what is drawn; this only renders it.
 */
@Composable
fun DayRouteMap(model: RouteModel, modifier: Modifier = Modifier, styleKey: String = "light", onPin: ((Int) -> Unit)? = null) {
    val ctx = LocalContext.current
    val mv = remember { MapLibre.getInstance(ctx); MapView(ctx).apply { onCreate(null) } }
    val lifecycle = remember(ctx) { ctx.lifecycleOwner()?.lifecycle }
    DisposableEffect(lifecycle, mv) {
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> mv.onStart(); Lifecycle.Event.ON_RESUME -> mv.onResume()
                Lifecycle.Event.ON_PAUSE -> mv.onPause(); Lifecycle.Event.ON_STOP -> mv.onStop()
                else -> {}
            }
        }
        lifecycle?.addObserver(observer)
        // a map inside a scrolling list keeps its own pan / zoom gestures
        mv.setOnTouchListener { v, ev -> v.parent?.requestDisallowInterceptTouchEvent(ev.actionMasked != MotionEvent.ACTION_UP && ev.actionMasked != MotionEvent.ACTION_CANCEL); false }
        onDispose { lifecycle?.removeObserver(observer); mv.onPause(); mv.onStop(); mv.onDestroy() }
    }
    val pinTap by rememberUpdatedState(onPin)
    // a tap on a pin reports the day segment it stands for (RouteMarker.seg); a tap elsewhere is left to the map
    LaunchedEffect(mv) {
        mv.getMapAsync { map ->
            map.addOnMapClickListener { at ->
                val cb = pinTap ?: return@addOnMapClickListener false
                val seg = map.queryRenderedFeatures(map.projection.toScreenLocation(at), "day-marks").firstOrNull()?.getNumberProperty("seg")?.toInt()
                if (seg != null && seg >= 0) { cb(seg); true } else false
            }
        }
    }
    LaunchedEffect(model, styleKey) {
        mv.getMapAsync { map ->
            map.uiSettings.isRotateGesturesEnabled = false
            map.setStyle(Style.Builder().fromJson(MapStyles.styleJson(MapStyles.get(styleKey)))) { style -> draw(ctx, map, style, model) }
        }
    }
    AndroidView(factory = { mv }, modifier = modifier)
}

private fun Context.lifecycleOwner(): LifecycleOwner? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is LifecycleOwner) return c; c = c.baseContext }
    return null
}

private const val SRC_LINES = "day-lines"; private const val SRC_MARKS = "day-marks"; private const val SRC_NOW = "day-now"

private fun draw(ctx: Context, map: MapLibreMap, style: Style, m: RouteModel) {
    for (name in RouteGeo.ALL_ICONS) style.addImage(name, icon(ctx, name))
    style.addSource(GeoJsonSource(SRC_LINES, RouteGeo.linesJson(m)))
    style.addSource(GeoJsonSource(SRC_MARKS, RouteGeo.markersJson(m)))
    style.addSource(GeoJsonSource(SRC_NOW, RouteGeo.currentJson(m)))
    style.addLayer(LineLayer("day-lines-casing", SRC_LINES).withProperties(PropertyFactory.lineColor("#FFFFFF"), PropertyFactory.lineWidth(8f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(LineLayer("day-lines", SRC_LINES).withProperties(PropertyFactory.lineColor(Expression.get("color")), PropertyFactory.lineWidth(5f),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND)))
    style.addLayer(SymbolLayer("day-marks", SRC_MARKS).withProperties(PropertyFactory.iconImage(Expression.get("icon")),
        PropertyFactory.iconAllowOverlap(true), PropertyFactory.iconIgnorePlacement(true), PropertyFactory.iconSize(0.9f)))
    style.addLayer(CircleLayer("day-now-halo", SRC_NOW).withProperties(PropertyFactory.circleRadius(14f), PropertyFactory.circleColor("#3B82F6"), PropertyFactory.circleOpacity(0.25f)))
    style.addLayer(CircleLayer("day-now", SRC_NOW).withProperties(PropertyFactory.circleRadius(7f), PropertyFactory.circleColor("#3B82F6"),
        PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2.5f)))
    val pts = m.points
    if (pts.isEmpty()) return
    if (pts.size == 1 || pts.all { it == pts[0] }) {
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(pts[0].lat, pts[0].lon), 15.0)); return
    }
    val b = LatLngBounds.Builder(); pts.forEach { b.include(LatLng(it.lat, it.lon)) }
    map.moveCamera(CameraUpdateFactory.newLatLngBounds(b.build(), 72))
}

/** A map pin: a coloured disc with the white vector glyph (mode colour, indigo for sleep, accent grey for visits). */
private fun icon(ctx: Context, name: String): Bitmap {
    val (res, argb) = iconSpec(name)
    val size = 64
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() })
    c.drawCircle(size / 2f, size / 2f, size / 2f - 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb.toInt() })
    ContextCompat.getDrawable(ctx, res)?.let { d -> d.setBounds(14, 14, size - 14, size - 14); d.draw(c) }
    return bmp
}

@DrawableRes private fun iconRes(name: String): Int = when {
    name.startsWith("mode_") -> DayIcons.mode(Mode.parse(name.removePrefix("mode_")))
    name == "stay_sleep" -> com.diegonmarcos.cloudnav.R.drawable.ic_stay_sleep
    else -> DayIcons.poi(PoiCategory.parse(name.removePrefix("poi_")))
}

private fun iconSpec(name: String): Pair<Int, Long> = iconRes(name) to when {
    name.startsWith("mode_") -> RouteGeo.modeArgb(Mode.parse(name.removePrefix("mode_")))
    name == "stay_sleep" -> 0xFF6366F1
    else -> 0xFF475569
}
