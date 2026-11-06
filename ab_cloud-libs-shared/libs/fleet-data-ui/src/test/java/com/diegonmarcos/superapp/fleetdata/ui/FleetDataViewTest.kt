package com.diegonmarcos.superapp.fleetdata.ui

import com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore
import com.diegonmarcos.superapp.fleetdata.provider.FleetManifest
import com.diegonmarcos.superapp.fleetdata.provider.ManifestEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Fleet Data page view-model: endpoint grouping, manifest -> consumer/tier/source mapping, and sync-state rendering. */
class FleetDataViewTest {
    private val authority = "cloud.store.data"
    private fun meta(sv: Int = 1, size: Long = 100, etag: String? = "e", synced: Long = 1000) =
        FleetDataStore.Meta(etag, sv, size, synced, synced)
    private val manifest = FleetManifest(
        shared = mapOf(
            "app-perms" to ManifestEntry(3, listOf("*"), null),
            "constellation-fleet" to ManifestEntry(3, listOf("*"), "aa_cloud-superapp/data/constellation-fleet.json"),
        ),
        apps = mapOf("cld.camera" to listOf("vr-filters"), "com.diegonmarcos.cloudtoolssmallllms" to listOf("tools-models", "tools/sensors")),
    )

    @Test fun endpointAndDatasetParsing() {
        assertEquals("app/cld.camera", FleetDataView.endpointOf("app/cld.camera/vr-filters"))
        assertEquals("vr-filters", FleetDataView.datasetOf("app/cld.camera/vr-filters"))
        assertEquals("shared", FleetDataView.endpointOf("shared/app-perms"))
        assertEquals("app-perms", FleetDataView.datasetOf("shared/app-perms"))
        // nested dataset name is kept
        assertEquals("tools/sensors", FleetDataView.datasetOf("app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors"))
    }

    @Test fun fullContentUri() {
        assertEquals("content://cloud.store.data/app/cld.camera/vr-filters", FleetDataView.uri(authority, "app/cld.camera/vr-filters"))
    }

    @Test fun tierComesFromManifestForSharedAndIsThreeForApp() {
        assertEquals(3, FleetDataView.tierOf("shared/app-perms", manifest))
        assertEquals(3, FleetDataView.tierOf("app/cld.camera/vr-filters", manifest))
        assertNull("an unknown shared dataset has no manifest tier", FleetDataView.tierOf("shared/nope", manifest))
    }

    @Test fun consumersMapFromManifest() {
        assertEquals(listOf("*"), FleetDataView.consumersOf("shared/app-perms", manifest))
        assertEquals("an app dataset is readable by its own package only",
            listOf("cld.camera"), FleetDataView.consumersOf("app/cld.camera/vr-filters", manifest))
    }

    @Test fun sourcePathFromManifestOrConvention() {
        assertEquals("aa_cloud-superapp/data/constellation-fleet.json", FleetDataView.sourceOf("shared/constellation-fleet", manifest))
        assertEquals("data/fleet/app-perms.json", FleetDataView.sourceOf("shared/app-perms", manifest))
        assertEquals("data/fleet/app/cld.camera/vr-filters.json", FleetDataView.sourceOf("app/cld.camera/vr-filters", manifest))
    }

    @Test fun syncStateRendering() {
        assertEquals(SyncState.OK, FleetDataView.stateOf("shared/app-perms", meta(synced = 1000), emptySet()))
        assertEquals(SyncState.STALE, FleetDataView.stateOf("shared/app-perms", null, emptySet()))
        assertEquals(SyncState.STALE, FleetDataView.stateOf("shared/app-perms", meta(synced = 0), emptySet()))
        assertEquals(SyncState.ERROR, FleetDataView.stateOf("shared/app-perms", meta(), setOf("shared/app-perms")))
        // a failure keyed by dataset name (not full path) is still an error
        assertEquals(SyncState.ERROR, FleetDataView.stateOf("app/cld.camera/vr-filters", meta(), setOf("vr-filters")))
    }

    @Test fun groupingByEndpointSharedFirstThenAppsSortedRowsByDataset() {
        val entries = listOf(
            FleetDataStore.Entry("app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors", meta()),
            FleetDataStore.Entry("app/cld.camera/vr-filters", meta()),
            FleetDataStore.Entry("shared/constellation-fleet", meta()),
            FleetDataStore.Entry("app/com.diegonmarcos.cloudtoolssmallllms/tools-models", meta()),
            FleetDataStore.Entry("shared/app-perms", meta()),
        )
        val g = FleetDataView.groups(entries, manifest, emptySet(), authority)
        assertEquals(listOf("shared", "app/cld.camera", "app/com.diegonmarcos.cloudtoolssmallllms"), g.map { it.endpoint })
        // shared rows sorted by dataset name
        assertEquals(listOf("app-perms", "constellation-fleet"), g[0].rows.map { it.dataset })
        // the tools endpoint keeps both datasets, sorted
        assertEquals(listOf("tools-models", "tools/sensors"), g[2].rows.map { it.dataset })
        // every row carries its full URI and endpoint URI is the content:// prefix
        assertEquals("content://cloud.store.data/shared/app-perms", g[0].rows[0].uri)
        assertTrue(g[1].endpointUri.startsWith("content://cloud.store.data/app/cld.camera/"))
    }
}
