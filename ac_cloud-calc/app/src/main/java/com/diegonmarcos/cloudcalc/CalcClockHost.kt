package com.diegonmarcos.cloudcalc

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import com.diegonmarcos.clocktools.ClockConfig
import com.diegonmarcos.clocktools.ClockHost
import com.diegonmarcos.clocktools.ClockPlace
import com.diegonmarcos.clocktools.ClockPlaces
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import com.diegonmarcos.cloudcalc.ui.WorldAssets
import com.diegonmarcos.cloudcalc.ui.WorldMap as CalcWorldMap

/**
 * #1668 what Cloud Calc tells the shared clock (libs:clock-tools), named in the manifest (meta-data
 * com.diegonmarcos.clocktools.HOST) so a wakeup in a fresh process finds it too. No clock rule lives here: the lib holds
 * them all; this only hands over Calc's own data.
 */
class CalcClockHost : ClockHost() {
    /** build.json::ui.modes[].clock, by mode kind (#768). */
    override fun config(ctx: Context): ClockConfig =
        ClockConfig.parse(Declarations.modes.filter { it.clock.isNotBlank() }.associate { it.kind to it.clock })

    /** A notification tap opens the Clock mode of that kind (MainActivity reads ClockEngine.EXTRA_MODE). */
    override fun modeId(kind: String): String? = Declarations.modes.firstOrNull { it.kind == kind }?.id

    override fun openIntent(ctx: Context): Intent = Intent(ctx, MainActivity::class.java)

    /** #1423 / #1547 the worldmap/cities runtime dataset: coordinates for #1667's day bars, the city picker's catalogue. */
    override fun places(ctx: Context): ClockPlaces = ClockPlaces { c ->
        WorldAssets.catalogue(c)?.let { cat ->
            ClockPlaces.Catalogue(
                cat.cities.map { ClockPlace(it.name, cat.countries[it.country] ?: it.country, it.zone, it.lat, it.lon, it.capital) },
                cat.zones,
            )
        }
    }

    @Composable
    override fun Theme(content: @Composable () -> Unit) = CalcTheme(content)

    override val hasMap: Boolean get() = true

    @Composable
    override fun WorldMap(zones: List<String>, now: Long, onToggle: (String) -> Unit) = CalcWorldMap(zones, now, onToggle)
}
