package com.diegonmarcos.clocktools

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import java.text.Normalizer
import java.time.ZoneId

/** A city the world clock can add, with its coordinates for the day bar (#1667). */
data class ClockPlace(val name: String, val subtitle: String, val zone: String, val lat: Double, val lon: Double, val capital: Boolean)

/**
 * Where the world clock finds cities and their coordinates. [load] runs off the main thread and may return null while the
 * host's catalogue is not there yet (Cloud Calc's is a runtime dataset, #1547); the screen retries. Without a catalogue
 * the picker lists every IANA zone and the day bar is an estimate (WorldTable.Sky.ESTIMATED).
 */
fun interface ClockPlaces {
    class Catalogue(val places: List<ClockPlace>, val zones: List<String>) {
        private val byZone: Map<String, ClockPlace> by lazy { places.groupBy { it.zone }.mapValues { (z, ps) -> pin(z, ps) } }

        /** The place a zone is drawn at: one named like the zone's last part, else its capital, else any. */
        fun at(zone: String): ClockPlace? = byZone[zone]

        fun search(query: String): List<ClockPlace> {
            val q = fold(query.trim())
            return places.filter { q.isEmpty() || q in fold(it.name) || q in fold(it.subtitle) || q in fold(it.zone) }
                .sortedWith(compareBy({ !it.capital }, { it.name }))
        }

        fun searchZones(query: String): List<String> {
            val q = query.trim().lowercase()
            return zones.filter { q.isEmpty() || q in it.lowercase().replace('_', ' ') || q in it.lowercase() }
        }

        private fun pin(zone: String, ps: List<ClockPlace>): ClockPlace {
            val named = fold(ClockLogic.city(zone))
            return ps.firstOrNull { fold(it.name).startsWith(named) } ?: ps.firstOrNull { it.capital } ?: ps.first()
        }

        private fun fold(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
    }

    fun load(ctx: Context): Catalogue?

    /** Every region zone of the device's tzdata, no coordinates. */
    object Zones : ClockPlaces {
        override fun load(ctx: Context): Catalogue = Catalogue(
            emptyList(),
            ZoneId.getAvailableZoneIds().filter { it.contains('/') && !it.startsWith("Etc/") && !it.startsWith("SystemV/") }.sorted(),
        )
    }
}

/**
 * What differs per app that carries the lib. Subclass it, keep a no-argument constructor, and name the class in the
 * app manifest's `<meta-data android:name="com.diegonmarcos.clocktools.HOST" android:value="..."/>`: the lib makes it on
 * first use in any process (a wakeup can start one with no activity), so it never depends on an activity having run.
 */
open class ClockHost {
    /** The declared numbers; Cloud Calc returns its build.json::ui.modes[].clock objects. */
    open fun config(ctx: Context): ClockConfig = ClockConfig.DEFAULT

    /** The id a notification tap hands the app for a clock page kind (worldclock, alarms, timers, stopwatch, interval, bedtime). */
    open fun modeId(kind: String): String? = kind

    /** What a notification tap opens; ClockEngine.EXTRA_MODE carries [modeId]. Null opens nothing. */
    open fun openIntent(ctx: Context): Intent? = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)

    open fun places(ctx: Context): ClockPlaces = ClockPlaces.Zones

    /** The theme of the lock-screen ring screen (the app's own screens are drawn inside the app's theme). */
    @Composable
    open fun Theme(content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(), content = content)
    }

    /** True when [WorldMap] draws something: the world clock then shows it above the table. */
    open val hasMap: Boolean get() = false

    /** An optional world map (Cloud Calc's #1423): a tap toggles a zone. */
    @Composable
    open fun WorldMap(zones: List<String>, now: Long, onToggle: (String) -> Unit) {}
}

/** The process's one host, read from the manifest once. A missing or broken name is the default host, never a crash. */
object ClockTools {
    const val HOST_META = "com.diegonmarcos.clocktools.HOST"
    @Volatile private var host: ClockHost? = null

    fun host(ctx: Context): ClockHost = host ?: synchronized(this) {
        host ?: load(ctx.applicationContext ?: ctx).also { host = it }
    }

    fun config(ctx: Context): ClockConfig = runCatching { host(ctx).config(ctx) }.getOrDefault(ClockConfig.DEFAULT)

    /** Tests and a host that prefers code to the manifest can set it directly. */
    fun install(h: ClockHost?) { host = h }

    private fun load(ctx: Context): ClockHost = runCatching {
        @Suppress("DEPRECATION")
        val name = ctx.packageManager.getApplicationInfo(ctx.packageName, PackageManager.GET_META_DATA).metaData?.getString(HOST_META)
        if (name.isNullOrBlank()) ClockHost() else Class.forName(name).getDeclaredConstructor().newInstance() as ClockHost
    }.getOrElse { ClockHost() }
}
