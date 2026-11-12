package com.diegonmarcos.superapp.boards

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.StatFs
import android.os.SystemClock
import android.os.storage.StorageManager
import android.telephony.TelephonyManager
import java.io.File
import java.util.Locale

/** One label/value line of the Resources board. */
data class ResRow(val label: String, val value: String)

/** Pure formatting and parsing of the raw numbers the platform hands over (unit-tested without a device). */
object ResourcesFormat {
    fun bytes(b: Long): String {
        if (b < 0) return "-"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        var v = b.toDouble(); var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return if (i == 0) "$b B" else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun used(total: Long, free: Long): String {
        if (total <= 0) return "-"
        val used = (total - free).coerceIn(0, total)
        return "${bytes(used)} used / ${bytes(free.coerceAtLeast(0))} free of ${bytes(total)} (${used * 100 / total}%)"
    }

    fun uptime(ms: Long): String {
        val s = ms / 1000
        val d = s / 86_400; val h = s % 86_400 / 3_600; val m = s % 3_600 / 60
        return if (d > 0) "${d}d ${h}h ${m}m" else if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
    }

    fun batteryStatus(code: Int): String = when (code) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "Charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "Discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "Full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "Not charging"
        else -> "Unknown"
    }

    fun batteryHealth(code: Int): String = when (code) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
        BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
        BatteryManager.BATTERY_HEALTH_COLD -> "Cold"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "Failure"
        else -> "Unknown"
    }

    /** Battery level in percent from the sticky broadcast's level and scale; null when the scale is unusable. */
    fun percent(level: Int, scale: Int): Int? = if (level < 0 || scale <= 0) null else level * 100 / scale

    /** The battery temperature arrives in tenths of a degree Celsius. */
    fun temperature(tenths: Int): String = String.format(Locale.US, "%.1f °C", tenths / 10.0)

    fun voltage(mv: Int): String = String.format(Locale.US, "%.2f V", mv / 1000.0)

    /** scaling_cur_freq is in kHz. */
    fun frequency(khz: Long): String = if (khz >= 1_000_000) String.format(Locale.US, "%.2f GHz", khz / 1_000_000.0) else "${khz / 1000} MHz"

    /** "0.52 0.58 0.59 1/345 1234" -> "0.52 / 0.58 / 0.59"; null when it is not a loadavg line. */
    fun loadAverage(text: String): String? {
        val p = text.trim().split(Regex("\\s+"))
        if (p.size < 3) return null
        val l = p.take(3).map { it.toDoubleOrNull() ?: return null }
        return l.joinToString(" / ") { String.format(Locale.US, "%.2f", it) }
    }

    /** RSSI dBm -> 0..4 bars, the same cut-offs WifiManager.calculateSignalLevel uses for 5 levels. */
    fun signalBars(dbm: Int): Int = when {
        dbm >= -55 -> 4; dbm >= -66 -> 3; dbm >= -77 -> 2; dbm >= -88 -> 1; else -> 0
    }

    fun ip(packed: Int): String = "${packed and 0xff}.${packed shr 8 and 0xff}.${packed shr 16 and 0xff}.${packed shr 24 and 0xff}"
}

/**
 * The phone, live: one function per declared section (build.json boards.stack_resources[].resource). Values come from the platform and
 * nothing else; a value the platform will not give is left out (never a placeholder), and a gated one says what gates it.
 */
object ResourcesProvider {
    val SECTIONS = listOf("battery", "memory", "storage", "cpu", "network", "device", "sensors")

    fun read(ctx: Context, section: String): List<ResRow> = runCatching {
        when (section) {
            "battery" -> battery(ctx)
            "memory" -> memory(ctx)
            "storage" -> storage(ctx)
            "cpu" -> cpu()
            "network" -> network(ctx)
            "device" -> device()
            "sensors" -> sensors(ctx)
            else -> emptyList()
        }
    }.getOrDefault(emptyList()).filter { it.value.isNotBlank() }

    /** The battery SoT's own report (libs:battery BatteryRepository.report): this board never reads BatteryManager itself. */
    fun battery(ctx: Context): List<ResRow> {
        val r = com.diegonmarcos.superapp.battery.BatteryRepository.report(ctx)?.reading ?: return emptyList()
        val rows = ArrayList<ResRow>()
        rows += ResRow("Level", "${r.levelPct}%")
        rows += ResRow("State", ResourcesFormat.batteryStatus(r.status))
        rows += ResRow("Power source", when {
            r.plugged and BatteryManager.BATTERY_PLUGGED_AC != 0 -> "AC"
            r.plugged and BatteryManager.BATTERY_PLUGGED_USB != 0 -> "USB"
            r.plugged and BatteryManager.BATTERY_PLUGGED_WIRELESS != 0 -> "Wireless"
            else -> "Battery"
        })
        rows += ResRow("Health", ResourcesFormat.batteryHealth(r.health))
        r.tempDc?.let { rows += ResRow("Temperature", ResourcesFormat.temperature(it)) }
        r.voltageMv?.takeIf { it > 0 }?.let { rows += ResRow("Voltage", ResourcesFormat.voltage(it)) }
        r.systemCycles?.takeIf { it >= 0 }?.let { rows += ResRow("Charge cycles", it.toString()) }
        return rows
    }

    fun memory(ctx: Context): List<ResRow> {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        val rows = arrayListOf(ResRow("RAM", ResourcesFormat.used(mi.totalMem, mi.availMem)))
        rows += ResRow("Low-memory state", if (mi.lowMemory) "Low" else "Normal")
        return rows
    }

    fun storage(ctx: Context): List<ResRow> {
        val sm = ctx.getSystemService(Context.STORAGE_SERVICE) as StorageManager
        val dirs: List<Pair<String, File>> =
            if (Build.VERSION.SDK_INT >= 30) sm.storageVolumes.mapNotNull { v -> v.directory?.let { v.getDescription(ctx) to it } }
            else listOf("Internal" to android.os.Environment.getDataDirectory())
        val rows = dirs.map { (name, dir) ->
            val st = StatFs(dir.path)
            ResRow(name, ResourcesFormat.used(st.totalBytes, st.availableBytes))
        }.toMutableList()
        if (rows.isEmpty()) {
            val st = StatFs(ctx.filesDir.path)
            rows += ResRow("Internal", ResourcesFormat.used(st.totalBytes, st.availableBytes))
        }
        return rows
    }

    fun cpu(): List<ResRow> {
        val rows = arrayListOf(ResRow("Cores", Runtime.getRuntime().availableProcessors().toString()))
        rows += ResRow("ABI", Build.SUPPORTED_ABIS.firstOrNull().orEmpty())
        val freqs = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { n ->
            runCatching { File("/sys/devices/system/cpu/cpu$n/cpufreq/scaling_cur_freq").readText().trim().toLong() }.getOrNull()
        }
        if (freqs.isNotEmpty()) rows += ResRow("Frequency (now)", "${ResourcesFormat.frequency(freqs.min())} - ${ResourcesFormat.frequency(freqs.max())}")
        runCatching { ResourcesFormat.loadAverage(File("/proc/loadavg").readText()) }.getOrNull()?.let { rows += ResRow("Load (1/5/15 min)", it) }
        return rows
    }

    @SuppressLint("MissingPermission", "HardwareIds")
    fun network(ctx: Context): List<ResRow> {
        val rows = ArrayList<ResRow>()
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val cell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        rows += ResRow("Active", when { wifi -> "Wi-Fi"; cell -> "Mobile data"; caps != null -> "Other"; else -> "Offline" })
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // #1666 a home board refreshes without the user asking for Wi-Fi: no WifiManager.getConnectionInfo here (a location
        // access, it carries the SSID / BSSID). Signal from the network's capabilities, the address from its link properties;
        // the network's name is on the Wi-Fi page, which reads it while it is open.
        if (wifi) {
            rows += ResRow("Wi-Fi", "Connected (name on the Wi-Fi page)")
            com.diegonmarcos.superapp.network.NetworkSignal.wifiRssi(ctx)?.let { rssi ->
                rows += ResRow("Wi-Fi signal", "$rssi dBm (${ResourcesFormat.signalBars(rssi)}/4)")
            }
            runCatching {
                cm.getLinkProperties(cm.activeNetwork)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }?.address?.hostAddress
            }.getOrNull()?.let { rows += ResRow("Wi-Fi IP", it) }
        } else rows += ResRow("Wi-Fi", if (wm?.isWifiEnabled == true) "On, not connected" else "Off")
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm?.networkOperatorName?.takeIf { it.isNotBlank() }?.let { rows += ResRow("Carrier", it) }
        if (cell) rows += ResRow("Mobile signal", "Connected" + (if (Build.VERSION.SDK_INT >= 29) caps?.signalStrength?.takeIf { it != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED }?.let { ", $it dBm" } ?: "" else ""))
        rows += ResRow("VPN / mesh", if (vpn) "Active (a VPN, e.g. the WireGuard mesh, carries traffic)" else "Not active")
        val bt = (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        rows += ResRow("Bluetooth", when { bt == null -> "No adapter"; bt.isEnabled -> "On"; else -> "Off" })
        return rows
    }

    fun device(): List<ResRow> {
        val rows = arrayListOf(
            ResRow("Model", "${Build.MANUFACTURER} ${Build.MODEL}"),
            ResRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            ResRow("Security patch", Build.VERSION.SECURITY_PATCH),
            ResRow("Build", Build.DISPLAY),
            ResRow("Uptime", ResourcesFormat.uptime(SystemClock.elapsedRealtime())),
        )
        return rows
    }

    fun sensors(ctx: Context): List<ResRow> {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val all = sm.getSensorList(Sensor.TYPE_ALL)
        val named = listOf(
            "Accelerometer" to Sensor.TYPE_ACCELEROMETER, "Gyroscope" to Sensor.TYPE_GYROSCOPE, "Magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
            "Light" to Sensor.TYPE_LIGHT, "Proximity" to Sensor.TYPE_PROXIMITY, "Barometer" to Sensor.TYPE_PRESSURE,
            "Step counter" to Sensor.TYPE_STEP_COUNTER, "Heart rate" to Sensor.TYPE_HEART_RATE,
        )
        val rows = arrayListOf(ResRow("Sensors", all.size.toString()))
        rows += ResRow("Present", named.filter { (_, t) -> sm.getDefaultSensor(t) != null }.joinToString { it.first }.ifBlank { "none of the common ones" })
        return rows
    }
}
