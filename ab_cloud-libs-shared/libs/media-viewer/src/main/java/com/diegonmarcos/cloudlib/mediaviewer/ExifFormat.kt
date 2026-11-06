package com.diegonmarcos.cloudlib.mediaviewer

import java.util.Locale

/** The EXIF values a viewer shows, already read from the file (null = the file does not carry it). */
data class ExifValues(
    val make: String? = null,
    val model: String? = null,
    val dateTime: String? = null,
    val exposureSeconds: Double? = null,
    val fNumber: Double? = null,
    val iso: Int? = null,
    val focalMm: Double? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

/** #1537 the Info panel's text: pure formatting of the numbers, then the label/value rows. */
object ExifFormat {
    /** 0.004 s -> "1/250 s", 2.5 -> "2.5 s", 30 -> "30 s". */
    fun exposure(seconds: Double): String = when {
        seconds <= 0.0 -> "?"
        seconds < 1.0 -> "1/" + Math.round(1.0 / seconds) + " s"
        seconds == Math.floor(seconds) -> seconds.toLong().toString() + " s"
        else -> String.format(Locale.ROOT, "%.1f s", seconds)
    }

    fun aperture(f: Double): String = "f/" + trim(f)

    fun focal(mm: Double): String = trim(mm) + " mm"

    fun gps(lat: Double, lon: Double): String =
        String.format(Locale.ROOT, "%.5f %s, %.5f %s", Math.abs(lat), if (lat >= 0) "N" else "S", Math.abs(lon), if (lon >= 0) "E" else "W")

    fun megapixels(w: Int, h: Int): String = String.format(Locale.ROOT, "%.1f MP", w.toLong() * h / 1_000_000.0)

    fun bytes(n: Long): String {
        if (n < 1000) return "$n B"
        val units = listOf("kB", "MB", "GB")
        var v = n / 1000.0
        var i = 0
        while (v >= 1000 && i < units.lastIndex) { v /= 1000; i++ }
        return String.format(Locale.ROOT, if (v >= 100) "%.0f %s" else "%.1f %s", v, units[i])
    }

    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.1f", d)

    /** The rows of the Info panel in a fixed order: file facts first, then what the camera recorded; a value the file lacks is left out. */
    fun rows(name: String, sizeBytes: Long, width: Int, height: Int, e: ExifValues): List<Pair<String, String>> = buildList {
        add("Name" to name)
        add("Size" to bytes(sizeBytes))
        if (width > 0 && height > 0) add("Dimensions" to "$width x $height (${megapixels(width, height)})")
        val camera = listOfNotNull(e.make?.trim()?.takeIf { it.isNotEmpty() }, e.model?.trim()?.takeIf { it.isNotEmpty() })
            .let { parts -> if (parts.size == 2 && parts[1].startsWith(parts[0], ignoreCase = true)) listOf(parts[1]) else parts }
        if (camera.isNotEmpty()) add("Camera" to camera.joinToString(" "))
        e.dateTime?.takeIf { it.isNotBlank() }?.let { add("Taken" to it) }
        e.exposureSeconds?.takeIf { it > 0.0 }?.let { add("Exposure" to exposure(it)) }
        e.fNumber?.takeIf { it > 0.0 }?.let { add("Aperture" to aperture(it)) }
        e.iso?.takeIf { it > 0 }?.let { add("ISO" to it.toString()) }
        e.focalMm?.takeIf { it > 0.0 }?.let { add("Focal length" to focal(it)) }
        if (e.latitude != null && e.longitude != null) add("Location" to gps(e.latitude, e.longitude))
    }
}
