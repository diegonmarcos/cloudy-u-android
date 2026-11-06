package com.diegonmarcos.cloudtoolssmallllms.data

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/**
 * The app's data, read at RUNTIME from Cloud Store's provider (libs:fleet-data-client), never baked into the APK (#1547):
 * `tools-models` and `tools/sensors` are this app's own datasets, `constellation-fleet` is the shared Cloud Store fleet declaration the MLs
 * page derives from. The model data is required (the app waits on a loading state until it arrives); the other two only fill their pages.
 */
class ToolsData(val modelsJson: String, val fleet: String?, val sensors: String?) {
    /** Changes whenever any of the three datasets does (the model is rebuilt from the new data, no restart). */
    val version: Int = 31 * (31 * modelsJson.hashCode() + (fleet?.hashCode() ?: 0)) + (sensors?.hashCode() ?: 0)

    companion object {
        const val FLEET = "constellation-fleet"
        const val SENSORS = "tools/sensors"

        /** Null until Cloud Store has served `tools-models` (first run, provider not installed, nothing synced yet); never throws. */
        fun observe(ctx: Context): Flow<ToolsData?> = combine(
            FleetData.observe(ctx, ModelsData.DATASET, own = true) { json -> ModelsData(json); json },
            FleetData.observe(ctx, FLEET) { it },
            FleetData.observe(ctx, SENSORS, own = true) { it },
        ) { models, fleet, sensors -> models?.let { ToolsData(it, fleet, sensors) } }
    }
}
