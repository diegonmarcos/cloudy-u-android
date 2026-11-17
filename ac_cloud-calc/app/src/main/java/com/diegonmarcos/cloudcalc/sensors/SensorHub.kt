package com.diegonmarcos.cloudcalc.sensors

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.location.GnssStatus
import com.diegonmarcos.superapp.locationclient.NavLocationClient
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import com.diegonmarcos.cloudcalc.measure.Sensors

/**
 * The phone side of build.json::sensors: what the phone has, what it may be asked, and live values. Every reading is the platform's
 * own; a sensor the phone lacks reports [present] = false and is never given a value, and a reading the platform cannot give is NaN.
 * Asking for a runtime permission is the screens' job (ui/SensorScreens.kt, on a tap); this object only checks [granted].
 */
object SensorHub {
    /**
     * A seam for the JVM suites (the JevStore.http precedent): when set it stands in for the phone, so a test decides what is present,
     * granted and reported without Robolectric's sensor shadows. Production never sets it.
     */
    interface Platform {
        fun present(ctx: Context, d: Sensors.Decl): Boolean
        fun granted(ctx: Context, d: Sensors.Decl): Boolean
        fun listen(ctx: Context, d: Sensors.Decl, onValues: (DoubleArray) -> Unit): () -> Unit
    }

    @Volatile var platform: Platform? = null

    fun manager(ctx: Context): SensorManager = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    /** Everything the phone reports, as SensorManager lists it (TYPE_ALL). */
    fun all(ctx: Context): List<Sensor> = manager(ctx).getSensorList(Sensor.TYPE_ALL)

    fun sensorOf(ctx: Context, d: Sensors.Decl): Sensor? = if (d.source == "sensor") manager(ctx).getDefaultSensor(d.androidType) else null

    fun present(ctx: Context, d: Sensors.Decl): Boolean {
        platform?.let { return it.present(ctx, d) }
        val pm = ctx.packageManager
        return when (d.source) {
            "sensor" -> sensorOf(ctx, d) != null
            "gnss" -> pm.hasSystemFeature(PackageManager.FEATURE_LOCATION_GPS)
            "battery_temperature", "battery_voltage" -> true
            "wifi" -> pm.hasSystemFeature(PackageManager.FEATURE_WIFI)
            "cell" -> pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)
            "nfc" -> pm.hasSystemFeature(PackageManager.FEATURE_NFC)
            else -> false
        }
    }

    /** The Manifest permission a declared runtime permission name stands for; empty for none. */
    fun permissionOf(name: String): String = when (name) {
        "ACTIVITY_RECOGNITION" -> Manifest.permission.ACTIVITY_RECOGNITION
        "BODY_SENSORS" -> Manifest.permission.BODY_SENSORS
        "ACCESS_FINE_LOCATION" -> Manifest.permission.ACCESS_FINE_LOCATION
        else -> ""
    }

    /** True when [d] needs no runtime permission, or has it. Activity recognition is a runtime permission from Android 10 only. */
    fun granted(ctx: Context, d: Sensors.Decl): Boolean {
        platform?.let { return it.granted(ctx, d) }
        val p = permissionOf(d.permission)
        if (p.isEmpty()) return true
        if (p == Manifest.permission.ACTIVITY_RECOGNITION && Build.VERSION.SDK_INT < 29) return true
        return ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * Live raw values of [d] (before [Sensors.Decl.rawScale]) until the returned function is called; [onValues] runs on the main thread.
     * A trigger sensor (one-shot) reports 1.0 each time it fires and is armed again. A source that has nothing to say yet reports NaN.
     */
    fun listen(ctx: Context, d: Sensors.Decl, onValues: (DoubleArray) -> Unit): () -> Unit = platform?.listen(ctx, d, onValues) ?: when (d.source) {
        "sensor" -> listenSensor(ctx, d, onValues)
        "gnss" -> listenGnss(ctx, onValues)
        "battery_temperature" -> listenBattery(ctx, BatteryManager.EXTRA_TEMPERATURE, onValues)
        "battery_voltage" -> listenBattery(ctx, BatteryManager.EXTRA_VOLTAGE, onValues)
        "wifi" -> poll(1000L) { onValues(doubleArrayOf(wifiRssi(ctx))) }
        "cell" -> poll(1500L) { onValues(doubleArrayOf(cellDbm(ctx))) }
        "nfc" -> poll(2000L) { onValues(doubleArrayOf(nfcState(ctx))) }
        else -> ({})
    }

    private fun listenSensor(ctx: Context, d: Sensors.Decl, onValues: (DoubleArray) -> Unit): () -> Unit {
        val sm = manager(ctx)
        val s = sm.getDefaultSensor(d.androidType) ?: return {}
        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            var armed = true
            val l = object : TriggerEventListener() {
                override fun onTrigger(e: TriggerEvent) {
                    onValues(doubleArrayOf(1.0))
                    if (armed) sm.requestTriggerSensor(this, s)
                }
            }
            sm.requestTriggerSensor(l, s)
            return { armed = false; sm.cancelTriggerSensor(l, s) }
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                onValues(if (d.display == "event") doubleArrayOf(1.0) else DoubleArray(e.values.size) { e.values[it].toDouble() })
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        return { sm.unregisterListener(l) }
    }

    /**
     * lat, lon, altitude, accuracy, speed, bearing, satellites used, satellites in view; NaN until known.
     * #1770 the position is Cloud Nav's last known fix read over the fleet IPC door (libs:location-client): this app starts no
     * location fix. Altitude, speed and bearing are not part of that contract and stay NaN. The satellite counts come from the
     * platform's GNSS status callback, a passive observer that starts nothing (it reports while Cloud Nav or any other app runs GNSS).
     */
    @SuppressLint("MissingPermission") // the screen asks first; a SecurityException is caught below
    private fun listenGnss(ctx: Context, onValues: (DoubleArray) -> Unit): () -> Unit {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val cur = DoubleArray(8) { Double.NaN }
        val status = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                var used = 0
                for (i in 0 until s.satelliteCount) if (s.usedInFix(i)) used++
                cur[6] = used.toDouble(); cur[7] = s.satelliteCount.toDouble()
                onValues(cur.copyOf())
            }
        }
        val handler = Handler(Looper.getMainLooper())
        val satellites = runCatching { lm.registerGnssStatusCallback(status, handler) }.getOrDefault(false)
        val io = java.util.concurrent.Executors.newSingleThreadExecutor()
        var on = true
        val read = object : Runnable {
            override fun run() {
                if (!on) return
                runCatching {
                    io.execute {
                        val a = NavLocationClient.ask(ctx)
                        handler.post {
                            if (on && a is NavLocationClient.Answer.Ok) {
                                cur[0] = a.fix.lat; cur[1] = a.fix.lon; cur[3] = a.fix.accuracyM?.toDouble() ?: Double.NaN
                                onValues(cur.copyOf())
                            }
                        }
                    }
                }
                handler.postDelayed(this, NAV_READ_MS)
            }
        }
        handler.post(read)
        return { on = false; handler.removeCallbacks(read); io.shutdown(); if (satellites) runCatching { lm.unregisterGnssStatusCallback(status) } }
    }

    /** How often the card re-reads Cloud Nav's last fix: a cached read, it never makes the phone locate. */
    private const val NAV_READ_MS = 10_000L

    private fun listenBattery(ctx: Context, extra: String, onValues: (DoubleArray) -> Unit): () -> Unit {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.hasExtra(extra)) onValues(doubleArrayOf(i.getIntExtra(extra, 0).toDouble()))
            }
        }
        // Sticky: the first value is delivered at once.
        ContextCompat.registerReceiver(ctx, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        return { runCatching { ctx.unregisterReceiver(r) } }
    }

    private fun poll(periodMs: Long, tick: () -> Unit): () -> Unit {
        val h = Handler(Looper.getMainLooper())
        var on = true
        val r = object : Runnable {
            override fun run() { if (!on) return; tick(); h.postDelayed(this, periodMs) }
        }
        h.post(r)
        return { on = false; h.removeCallbacks(r) }
    }

    /** The connected Wi-Fi's RSSI in dBm; NaN when not connected. */
    @Suppress("DEPRECATION")
    private fun wifiRssi(ctx: Context): Double = runCatching {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = wm.connectionInfo
        if (info == null || info.networkId == -1 || info.rssi <= -127) Double.NaN else info.rssi.toDouble()
    }.getOrDefault(Double.NaN)

    /** The cellular signal in dBm as the telephony stack reports it (Android 10+); NaN when it does not. */
    private fun cellDbm(ctx: Context): Double = runCatching {
        if (Build.VERSION.SDK_INT < 29) return@runCatching Double.NaN
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dbm = tm.signalStrength?.cellSignalStrengths?.firstOrNull()?.dbm
        if (dbm == null || dbm == Int.MAX_VALUE) Double.NaN else dbm.toDouble()
    }.getOrDefault(Double.NaN)

    /** 1.0 NFC on, 0.0 present but switched off. */
    private fun nfcState(ctx: Context): Double = runCatching {
        if (NfcAdapter.getDefaultAdapter(ctx)?.isEnabled == true) 1.0 else 0.0
    }.getOrDefault(0.0)
}
