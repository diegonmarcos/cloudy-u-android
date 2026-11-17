package com.diegonmarcos.cloudwiki

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.flow.Flow

/**
 * The pages are the runtime dataset `wiki` (repo data/fleet/app/com.diegonmarcos.cloudwiki/wiki.json), served by Cloud Store and read through
 * libs:fleet-data-client (#1547): nothing is bundled as an asset. The flow emits null while there is nothing (the provider absent, nothing synced yet)
 * and the parsed pages again after every update Cloud Store announces.
 */
object WikiStore {
    fun observe(ctx: Context): Flow<WikiData?> = FleetData.observe(ctx, Wiki.DATASET, own = true) { Wiki.parse(it) }
}
