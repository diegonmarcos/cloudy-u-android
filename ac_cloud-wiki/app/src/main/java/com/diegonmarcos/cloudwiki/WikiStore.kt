package com.diegonmarcos.cloudwiki

import android.content.Context
import com.diegonmarcos.superapp.fleetdata.FleetData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * #1867 the pages come from the content repo at RUN TIME: the app's own read-only clone of diegonmarcos/Projects-Library-KG ([ChronosRepo],
 * kept in its storage, so it serves offline) holds the generated wiki.json at its root ([ChronosRepo.datasetText]); it wins as soon as a
 * clone is in place. Until then (first run, no network yet) the pages are the runtime dataset `wiki` (repo data/fleet/app/com.diegonmarcos.cloudwiki/wiki.json,
 * the same file at the pin), served by Cloud Store and read through libs:fleet-data-client (#1547). Nothing is bundled as an asset. The flow emits
 * null while there is nothing (no clone, the provider absent, nothing synced yet) and the parsed pages again after every refresh of either source.
 */
object WikiStore {
    fun observe(ctx: Context): Flow<WikiData?> =
        combine(
            FleetData.observe(ctx, Wiki.DATASET, own = true) { Wiki.parse(it) },
            ChronosRepo.state.map { it.sha }.distinctUntilChanged(),
        ) { served, _ ->
            ChronosRepo.datasetText()?.let { Wiki.parse(it) }?.takeIf { it.topics.isNotEmpty() } ?: served
        }.flowOn(Dispatchers.IO)
}
