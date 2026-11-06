package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import com.diegonmarcos.cloudtoolssmallllms.R
import com.diegonmarcos.cloudtoolssmallllms.core.HardwareItem
import com.diegonmarcos.cloudtoolssmallllms.core.Num
import com.diegonmarcos.cloudtoolssmallllms.core.SensorRaw
import com.diegonmarcos.cloudtoolssmallllms.core.SensorRegistrar
import com.diegonmarcos.cloudtoolssmallllms.core.SensorRow
import com.diegonmarcos.cloudtoolssmallllms.core.SensorSource

/** SensorManager as core's SensorSource: getSensorList(TYPE_ALL) (wake-up, uncalibrated and OEM sensors included) plus the dynamic sensors. */
class AndroidSensorSource(private val sm: SensorManager) : SensorSource {
    fun all(): List<Sensor> = sm.getSensorList(Sensor.TYPE_ALL) + sm.getDynamicSensorList(Sensor.TYPE_ALL)

    override fun sensors(): List<SensorRaw> = all().map {
        SensorRaw(
            it.id, it.name, it.vendor, it.type, it.stringType.orEmpty(), it.version, it.power, it.resolution, it.maximumRange,
            it.minDelay, it.maxDelay, it.reportingMode, it.isWakeUpSensor, it.fifoMaxEventCount, it.isDynamicSensor,
        )
    }
}

/** The ONE sensor listener at SENSOR_DELAY_UI (a trigger listener for a one-shot sensor); core's LiveReading decides when it exists. */
class AndroidRegistrar(private val sm: SensorManager) : SensorRegistrar {
    private var listener: SensorEventListener? = null
    private var trigger: TriggerEventListener? = null
    private var sensor: Sensor? = null

    override fun register(sensorId: Int, onValues: (FloatArray) -> Unit): Boolean {
        val s = AndroidSensorSource(sm).all().firstOrNull { it.id == sensorId } ?: return false
        sensor = s
        if (s.reportingMode == Sensor.REPORTING_MODE_ONE_SHOT) {
            val t = object : TriggerEventListener() { override fun onTrigger(event: TriggerEvent) = onValues(event.values) }
            trigger = t
            return sm.requestTriggerSensor(t, s)
        }
        val l = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) = onValues(event.values)
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        listener = l
        return sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
    }

    override fun unregister() {
        listener?.let { sm.unregisterListener(it) }
        val s = sensor
        if (s != null) trigger?.let { sm.cancelTriggerSensor(it, s) }
        listener = null; trigger = null; sensor = null
    }
}

/** What a hardware item reads: whether it is there and its detail lines. */
data class HwReading(val available: Boolean, val lines: List<String>)

/** Reads the hardware the SensorManager does not list (assets/sensors.json::hardware); every call is a point-in-time read, nothing listens. */
class HardwareProbe(private val ctx: Context, private val rows: List<SensorRow>) {
    private fun str(id: Int, vararg a: Any) = ctx.getString(id, *a)
    private fun yesNo(b: Boolean) = str(if (b) R.string.yes else R.string.no)

    fun read(item: HardwareItem): HwReading {
        val feature = item.feature?.let { ctx.packageManager.hasSystemFeature(it) }
        return when (item.probe) {
            "feature", "gnss" -> HwReading(feature == true, emptyList())
            "sensor_type" -> rows.filter { it.raw.type == item.sensorType }.let { r -> HwReading(r.isNotEmpty(), r.map { it.raw.name }) }
            "sensor_name" -> rows.filter { r -> item.match.any { r.raw.name.contains(it, true) || r.raw.stringType.contains(it, true) } }.let { r -> HwReading(r.isNotEmpty(), r.map { it.raw.name }) }
            "cameras" -> cameras()
            "microphones" -> microphones(feature == true)
            "biometric" -> biometric(feature == true)
            "ir" -> ctx.getSystemService(ConsumerIrManager::class.java).let { HwReading(it?.hasIrEmitter() == true, emptyList()) }
            "battery" -> battery()
            "thermal" -> thermal(item.forecastS)
            else -> HwReading(false, emptyList())
        }
    }

    private fun cameras(): HwReading = runCatching {
        val cm = ctx.getSystemService(CameraManager::class.java)
        val facing = ctx.resources.getStringArray(R.array.hw_facing)
        val levels = ctx.resources.getStringArray(R.array.hw_levels)
        val lines = cm.cameraIdList.map { id ->
            val c = cm.getCameraCharacteristics(id)
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            val focal = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString("/") { Num.short(it) }.orEmpty()
            val flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            val level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: -1
            str(R.string.hw_camera, id, facing.getOrElse(c.get(CameraCharacteristics.LENS_FACING) ?: -1) { "?" }, px?.width ?: 0, px?.height ?: 0, focal, yesNo(flash), levels.getOrElse(level) { "?" })
        }
        HwReading(lines.isNotEmpty(), lines)
    }.getOrElse { HwReading(false, emptyList()) }

    private fun microphones(has: Boolean): HwReading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return HwReading(has, emptyList())
        return runCatching {
            val where = ctx.resources.getStringArray(R.array.hw_mic_location)
            val lines = ctx.getSystemService(AudioManager::class.java).microphones.map { m -> str(R.string.hw_mic, m.description, where.getOrElse(m.location) { "?" }, m.address) }
            HwReading(has || lines.isNotEmpty(), lines)
        }.getOrElse { HwReading(has, emptyList()) }
    }

    private fun biometric(hasFeature: Boolean): HwReading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return HwReading(hasFeature, emptyList())
        return runCatching {
            val bm = ctx.getSystemService(android.hardware.biometrics.BiometricManager::class.java)
            fun status(authenticators: Int) = when (bm.canAuthenticate(authenticators)) {
                android.hardware.biometrics.BiometricManager.BIOMETRIC_SUCCESS -> R.string.bio_ready
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> R.string.bio_none_enrolled
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> R.string.bio_no_hardware
                android.hardware.biometrics.BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE -> R.string.bio_unavailable
                else -> R.string.bio_other
            }
            val strong = status(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG)
            val weak = status(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK)
            HwReading(strong != R.string.bio_no_hardware || weak != R.string.bio_no_hardware || hasFeature, listOf(str(R.string.hw_biometric, str(strong), str(weak))))
        }.getOrElse { HwReading(hasFeature, emptyList()) }
    }

    private fun battery(): HwReading {
        val i: Intent = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return HwReading(false, emptyList())
        val t = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val v = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)
        return HwReading(t != Int.MIN_VALUE, listOf(str(R.string.hw_battery, Num.short(t / 10f), v)))
    }

    private fun thermal(forecast: List<Int>): HwReading {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return HwReading(false, emptyList())
        val pm = ctx.getSystemService(PowerManager::class.java)
        val status = ctx.resources.getStringArray(R.array.hw_thermal_status)
        val lines = ArrayList<String>()
        lines += str(R.string.hw_thermal_status, status.getOrElse(pm.currentThermalStatus) { "?" })
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) for (s in forecast) {
            val h = pm.getThermalHeadroom(s)
            lines += str(R.string.hw_thermal_headroom, s, if (h.isNaN()) str(R.string.hw_headroom_na) else Num.short(h))
        }
        return HwReading(true, lines)
    }

    companion object {
        fun has(ctx: Context, permission: String) = androidx.core.content.ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED
    }
}
