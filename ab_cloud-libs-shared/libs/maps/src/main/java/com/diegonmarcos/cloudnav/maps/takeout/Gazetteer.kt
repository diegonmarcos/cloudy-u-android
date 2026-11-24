package com.diegonmarcos.cloudnav.maps.takeout

import com.diegonmarcos.cloudnav.maps.TimelineBuilder
import java.io.Reader
import kotlin.math.cos
import kotlin.math.floor

/**
 * #1887 an offline gazetteer: populated places with their country, coordinates and IANA time zone. The nearest one names a location that
 * came without an address (a Timeline KML or a Timeline export holds only coordinates), so Cities and World fill offline, and gives the
 * time zone of a city (the Day page's sleep-city daylight). Cloud Nav ships GeoNames cities15000 as assets/geo/gazetteer.tsv; the JVM
 * tests hand in their own. Pure: no network, no Android.
 */
class Gazetteer(entries: List<Entry>) {
    data class Entry(val name: String, val country: String, val lat: Double, val lon: Double, val zone: String)

    private val cells = HashMap<Long, MutableList<Entry>>()
    val size: Int = entries.size

    init { for (e in entries) cells.getOrPut(key(floor(e.lat).toInt(), floor(e.lon).toInt())) { ArrayList() }.add(e) }

    private fun key(la: Int, lo: Int): Long = (la + 90).toLong() * 400L + Math.floorMod(lo + 180, 360).toLong()

    /** The nearest place within [maxM] metres of (lat, lon); null when none is that close (open sea, a coordinate that is not one). */
    fun nearest(lat: Double, lon: Double, maxM: Double = MAX_M): Entry? {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val la0 = floor(lat).toInt(); val lo0 = floor(lon).toInt()
        val c = cos(Math.toRadians(minOf(89.0, Math.abs(lat) + 1.0))).coerceAtLeast(0.02)
        val rLa = (maxM / 111_000.0).toInt() + 1
        val rLo = minOf(180, (maxM / (111_000.0 * c)).toInt() + 1)
        var best: Entry? = null
        var bestD = Double.MAX_VALUE
        for (dla in -rLa..rLa) {
            val la = la0 + dla
            if (la < -90 || la > 90) continue
            for (dlo in -rLo..rLo) {
                val cell = cells[key(la, lo0 + dlo)] ?: continue
                for (e in cell) {
                    val d = TimelineBuilder.haversineM(lat, lon, e.lat, e.lon)
                    if (d < bestD) { bestD = d; best = e }
                }
            }
        }
        return if (bestD <= maxM) best else null
    }

    companion object {
        /** How far a place may lie and still name a location (a town of 15,000 people or more is rarely farther in a populated area). */
        const val MAX_M = 100_000.0

        /** `name<TAB>country<TAB>lat<TAB>lon<TAB>zone` lines; `#` comments and malformed lines are skipped. */
        fun parse(reader: Reader): Gazetteer {
            val out = ArrayList<Entry>()
            reader.buffered().useLines { lines ->
                for (l in lines) {
                    if (l.isBlank() || l.startsWith("#")) continue
                    val f = l.split('\t')
                    if (f.size < 5) continue
                    val la = f[2].trim().toDoubleOrNull() ?: continue
                    val lo = f[3].trim().toDoubleOrNull() ?: continue
                    out.add(Entry(f[0].trim(), f[1].trim(), la, lo, f[4].trim()))
                }
            }
            return Gazetteer(out)
        }
    }
}
