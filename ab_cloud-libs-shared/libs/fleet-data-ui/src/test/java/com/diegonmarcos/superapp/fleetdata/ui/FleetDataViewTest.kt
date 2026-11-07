package com.diegonmarcos.superapp.fleetdata.ui

import com.diegonmarcos.superapp.fleetdata.provider.FleetDataStore
import com.diegonmarcos.superapp.fleetdata.provider.FleetManifest
import com.diegonmarcos.superapp.fleetdata.provider.ManifestEntry
import org.junit.Assert.assertEquals
import com.diegonmarcos.superapp.uikit.KitState
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

    @Test fun indexEndpointSortsFirst() {
        val entries = listOf(FleetDataStore.Entry("app/cld.camera/vr-filters", meta()), FleetDataStore.Entry("shared/app-perms", meta()), FleetDataStore.Entry("index", meta()))
        assertEquals(listOf("index", "shared", "app/cld.camera"), FleetDataView.groups(entries, manifest, emptySet(), authority).map { it.endpoint })
    }

    @Test fun statusPillMapping() {
        assertEquals(KitState.OK, FleetDataView.kitState(SyncState.OK))
        assertEquals(KitState.WARN, FleetDataView.kitState(SyncState.STALE))
        assertEquals(KitState.BAD, FleetDataView.kitState(SyncState.ERROR))
        assertEquals("stale", FleetDataView.pillWord(SyncState.STALE))
    }

    @Test fun chipsAndSize() {
        assertEquals("T3", FleetDataView.tierChip(3)); assertEquals("T-", FleetDataView.tierChip(null))
        assertEquals("schema v1", FleetDataView.schemaChip(1))
        assertEquals("8 KB", FleetDataView.sizeLabel(8192)); assertEquals("100 B", FleetDataView.sizeLabel(100)); assertEquals("-", FleetDataView.sizeLabel(null))
    }

    @Test fun relativeTimeFormatting() {
        val now = 10_000_000_000L
        assertEquals("never", FleetDataView.relativeTime(0, now))
        assertEquals("just now", FleetDataView.relativeTime(now - 30_000, now))
        assertEquals("just now", FleetDataView.relativeTime(now + 5_000, now))
        assertEquals("2m ago", FleetDataView.relativeTime(now - 120_000, now))
        assertEquals("3h ago", FleetDataView.relativeTime(now - 3 * 3_600_000, now))
        assertEquals("2d ago", FleetDataView.relativeTime(now - 2 * 86_400_000, now))
        assertEquals("2mo ago", FleetDataView.relativeTime(now - 65L * 86_400_000, now))
    }

    @Test fun etagTruncatesAndExpands() {
        val e = "W/\"30abb9c4d5e6f7a8b9ef83\""
        assertEquals("W/\"30abb9…ef83\"", FleetDataView.etagLabel(e, false))
        assertEquals(e, FleetDataView.etagLabel(e, true))
        assertEquals("W/\"1\"", FleetDataView.etagLabel("W/\"1\"", false))
        assertEquals("-", FleetDataView.etagLabel(null, false))
    }

    @Test fun consumersLabels() {
        assertEquals("all", FleetDataView.consumersLabel(listOf("*"), false))
        assertEquals("cld.camera", FleetDataView.consumersLabel(listOf("cld.camera"), false))
        assertEquals("2 consumers", FleetDataView.consumersLabel(listOf("a", "b"), false))
        assertEquals("a, b", FleetDataView.consumersLabel(listOf("a", "b"), true))
        assertEquals("none", FleetDataView.consumersLabel(emptyList(), false))
    }

    @Test fun filterByQueryAndStatus() {
        val entries = listOf(FleetDataStore.Entry("shared/app-perms", meta()), FleetDataStore.Entry("app/cld.camera/vr-filters", meta(synced = 0)))
        val g = FleetDataView.groups(entries, manifest, emptySet(), authority)
        assertEquals(listOf("shared"), FleetDataView.filter(g, "PERMS", null).map { it.endpoint })
        assertEquals(listOf("app/cld.camera"), FleetDataView.filter(g, "", SyncState.STALE).map { it.endpoint })
        assertEquals(2, FleetDataView.filter(g, "", null).size)
        assertTrue(FleetDataView.filter(g, "nothing-matches", null).isEmpty())
    }

    @Test fun summaryLine() {
        val now = 10_000_000_000L
        val entries = listOf(FleetDataStore.Entry("shared/app-perms", meta(synced = now - 120_000)), FleetDataStore.Entry("shared/constellation-fleet", meta(synced = now - 600_000)))
        val rows = FleetDataView.groups(entries, manifest, emptySet(), authority).flatMap { it.rows }
        assertEquals("2 datasets · all ok · last sync 2m ago", FleetDataView.summary(rows, now))
        assertEquals(KitState.OK, FleetDataView.summaryState(rows))
        val bad = FleetDataView.groups(entries, manifest, setOf("shared/app-perms"), authority).flatMap { it.rows }
        assertEquals("2 datasets · 1 ok, 1 error · last sync 2m ago", FleetDataView.summary(bad, now))
        assertEquals(KitState.BAD, FleetDataView.summaryState(bad))
    }
}
