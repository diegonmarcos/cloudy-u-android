package com.diegonmarcos.cloudnav.sky

import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.diegonmarcos.cloudnav.tracker.TrackerInk
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * Point-the-phone-at-the-sky view: real stars, full IAU constellation lines, Milky Way,
 * Sun + planets (see CelestialData.kt / PlanetEphemeris.kt / CREDITS.md) -- not a
 * Stardroid port (GPLv3, license-incompatible with reuse here). Uses TYPE_ROTATION_VECTOR
 * for device pointing direction (when present — drag/pinch on [SkyScreen] works regardless)
 * and the last-known/fresh location for the alt/az conversion. If location is truly
 * unavailable, it says so on-screen rather than silently rendering the wrong sky for (0,0).
 */
class ConstellationsActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private val sky = SkyState()
    private var status by mutableStateOf("")
    private var dataLoaded = false
    private var loadError: String? = null

    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(TrackerInk.palette()) { SkyScreen(sky, status) } }
        })

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        resolveLocation()
        loadCelestialDataAsync()
        updateStatus()
    }

    /** Parses ~1.2MB of Cloud Store served JSON (stars/lines/Milky Way/planet elements) off the main
     *  thread, then hands the results to [sky]. Planet/Sun positions barely move over
     *  a single viewing session, so they're computed once here rather than every frame. */
    private fun loadCelestialDataAsync() {
        val appCtx = applicationContext
        thread(name = "celestial-load") {
            // A raw Thread's uncaught exception kills the WHOLE APP on Android, not just
            // this thread -- any bad-asset-read/parse hiccup here would silently crash
            // the activity with no on-screen explanation. Catch and surface it instead.
            try {
                // #1547 the catalog is served by Cloud Store (not baked): each dataset is retried until it arrives (the client keeps the last-known-good copy).
                var stars: List<Star>? = null
                var lines: List<ConstellationSegment>? = null
                var mw: List<SkyPolygon>? = null
                var elements: Map<String, OrbitalElements>? = null
                while (stars == null || lines == null || mw == null || elements == null) {
                    if (isDestroyed) return@thread
                    stars = stars ?: CelestialData.loadStars(appCtx)
                    lines = lines ?: CelestialData.loadConstellationLines(appCtx)
                    mw = mw ?: CelestialData.loadMilkyWay(appCtx)
                    elements = elements ?: PlanetEphemeris.loadElements(appCtx)
                    if (stars == null || lines == null || mw == null || elements == null) Thread.sleep(3_000)
                }
                val fStars: List<Star> = checkNotNull(stars)
                val fLines: List<ConstellationSegment> = checkNotNull(lines)
                val fMw: List<SkyPolygon> = checkNotNull(mw)
                val bodies = PlanetEphemeris.positions(checkNotNull(elements), System.currentTimeMillis())
                Handler(Looper.getMainLooper()).post {
                    sky.stars = fStars
                    sky.constellationLines = fLines
                    sky.milkyWay = fMw
                    sky.bodies = bodies
                    dataLoaded = true
                    updateStatus()
                }
            } catch (t: Throwable) {
                android.util.Log.e("Constellations", "celestial data load failed", t)
                Handler(Looper.getMainLooper()).post {
                    loadError = "${t.javaClass.simpleName}: ${t.message}"
                    updateStatus()
                }
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    private fun resolveLocation() {
        if (!hasLocationPermission()) { updateStatus(); return }
        val lm = getSystemService(LOCATION_SERVICE) as? LocationManager ?: return
        val providers = try { lm.getProviders(true) } catch (e: SecurityException) { emptyList() }
        val last = providers.firstNotNullOfOrNull { lm.getLastKnownLocation(it) }
        if (last != null) {
            sky.observerLatDeg = last.latitude
            sky.observerLonDeg = last.longitude
            updateStatus()
            return
        }
        val provider = providers.firstOrNull() ?: return
        try {
            lm.requestSingleUpdate(provider, { loc ->
                sky.observerLatDeg = loc.latitude
                sky.observerLonDeg = loc.longitude
                updateStatus()
            }, mainLooper)
        } catch (e: SecurityException) { /* permission revoked mid-flight — status text covers it */ }
    }

    @SuppressLint("SetTextI18n")
    private fun updateStatus() {
        val hasFix = sky.observerLatDeg != 0.0 || sky.observerLonDeg != 0.0
        val locLine = if (hasFix) "%.2f, %.2f".format(sky.observerLatDeg, sky.observerLonDeg)
            else if (!hasLocationPermission()) "No location permission — using 0,0 (wrong sky)"
            else "Locating…"
        val sensorLine = if (rotationSensor == null) " · no orientation sensor, drag to look around" else ""
        val loadLine = when {
            loadError != null -> "\nFailed to load sky data: $loadError"
            !dataLoaded -> "\nLoading stars/constellations/planets…"
            else -> ""
        }
        status = "Constellations · Sun/planets · Milky Way (no moons)\n$locLine$sensorLine$loadLine"
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    override fun onPause() {
        sensorManager.unregisterListener(this)
        super.onPause()
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        try {
        val rotMatrix = FloatArray(9)
        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)

        // Raw getOrientation() reports where the SCREEN faces (its reference "forward" is
        // the device's own Y axis with the device flat, screen up) -- that's the wrong
        // question for a sky app, where the phone is held vertically, screen toward the
        // user, and the intent is "what's in the direction the BACK of the phone points."
        // Remap so the old Z axis (out the back) takes over the Y slot getOrientation()
        // treats as forward -- the same technique documented for AR apps referencing the
        // rear camera direction (Android's own sample naming: "rearCameraMatrix").
        val remapped = FloatArray(9)
        SensorManager.remapCoordinateSystem(rotMatrix, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        val orientation = FloatArray(3)
        SensorManager.getOrientation(remapped, orientation)

        sky.sensorAzimuthDeg = Math.toDegrees(orientation[0].toDouble()).let { if (it < 0) it + 360 else it }
        // UNVERIFIED sign: AOSP's pitch javadoc ("angle between a plane parallel to the
        // screen and a plane parallel to the ground") is written for the natural axes and
        // is ambiguous once remapped like this -- no physical device was available to
        // confirm which way pitch should go after the remap above. If tilting the phone
        // up at the sky moves stars the wrong way on a real device, flip this sign.
        sky.sensorPitchDeg = PITCH_SIGN * Math.toDegrees(orientation[1].toDouble())
        } catch (t: Throwable) {
            android.util.Log.e("Constellations", "onSensorChanged failed", t)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** Test-only accessor (ConstellationsRenderTest) -- mirrors the debugXxx() pattern
     *  MapsExploredFragment uses for ExploredRenderTest. */
    fun findSkyStateForTest(): SkyState = sky

    companion object {
        private const val PITCH_SIGN = 1.0
    }
}
