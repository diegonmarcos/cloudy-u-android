package com.diegonmarcos.mediacenter.storage

import com.diegonmarcos.mediacenter.feature_node.domain.model.FilterableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.FolderSelection
import com.diegonmarcos.mediacenter.feature_node.domain.model.SourceFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.SourceSelection
import com.diegonmarcos.mediacenter.feature_node.domain.model.StorageSource
import com.diegonmarcos.mediacenter.feature_node.domain.model.StorageSources
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageSourceTest {

    private fun media(source: StorageSource, bucket: String = "Camera") =
        FilterableMedia(1L, bucket, true, false, false, 1_700_000_000L, 2023, false, source)

    @Test
    fun primaryStorageIsEmulated() {
        assertEquals(StorageSource.EMULATED, StorageSources.classify("/storage/emulated/0/DCIM/Camera/a.jpg"))
        assertEquals(StorageSource.EMULATED, StorageSources.classify("/storage/emulated/10/Pictures/a.jpg"))
        assertEquals(StorageSource.EMULATED, StorageSources.classify("DCIM/Camera/"))
        assertEquals(StorageSource.EMULATED, StorageSources.classify("DCIM/Camera/", "external_primary"))
    }

    @Test
    fun removableVolumesAreExternal() {
        assertEquals(StorageSource.EXTERNAL, StorageSources.classify("/storage/1A2B-3C4D/DCIM/a.jpg"))
        assertEquals(StorageSource.EXTERNAL, StorageSources.classify("DCIM/Camera/", "1a2b-3c4d"))
        assertEquals(StorageSource.EXTERNAL, StorageSources.classify("/mnt/media_rw/1A2B-3C4D/a.jpg"))
    }

    @Test
    fun appDirectoriesAreAppOnWhateverVolumeTheySitOn() {
        assertEquals(StorageSource.APP, StorageSources.classify("/storage/emulated/0/Android/data/com.x/files/a.jpg"))
        assertEquals(StorageSource.APP, StorageSources.classify("/storage/emulated/0/Android/media/com.x/a.jpg"))
        assertEquals(StorageSource.APP, StorageSources.classify("/storage/1A2B-3C4D/Android/data/com.x/a.jpg"))
        assertEquals(StorageSource.APP, StorageSources.classify("Android/media/com.x/", "external_primary"))
        assertEquals(StorageSource.EMULATED, StorageSources.classify("/storage/emulated/0/Android/obb/a.jpg"))
        assertEquals(StorageSource.EMULATED, StorageSources.classify("/storage/emulated/0/Pictures/Android/a.jpg"))
    }

    @Test
    fun sourceFilterNarrowsToOneSource() {
        val all = TimelineFilter(source = SourceFilter.ALL)
        assertFalse(all.isActive)
        for (s in StorageSource.entries) assertTrue(all.matches(media(s)))
        val ext = TimelineFilter(source = SourceFilter.EXTERNAL)
        assertTrue(ext.isActive)
        assertTrue(ext.matches(media(StorageSource.EXTERNAL)))
        assertFalse(ext.matches(media(StorageSource.EMULATED)))
        assertFalse(ext.matches(media(StorageSource.APP)))
        assertTrue(TimelineFilter(source = SourceFilter.APP).matches(media(StorageSource.APP)))
        assertTrue(TimelineFilter(source = SourceFilter.EMULATED).matches(media(StorageSource.EMULATED)))
    }

    @Test
    fun selectAllListsEveryFolderAndUnselectAllClearsThem() {
        val folders = listOf("Camera", "Screenshots", "Download")
        assertEquals(folders.toSet(), FolderSelection.selectAll(folders))
        assertTrue(FolderSelection.isAllSelected(FolderSelection.selectAll(folders), folders))
        assertTrue(FolderSelection.unselectAll().isEmpty())
        assertFalse(FolderSelection.isAllSelected(setOf("Camera"), folders))
        assertFalse(FolderSelection.isAllSelected(emptySet(), emptyList()))
        val f = TimelineFilter(folders = FolderSelection.selectAll(folders))
        for (b in folders) assertTrue(f.matches(media(StorageSource.EMULATED, b)))
        assertFalse(f.matches(media(StorageSource.EMULATED, "Other")))
    }

    @Test
    fun homeSelectionPersistsAndNeverEmpties() {
        assertEquals(SourceSelection.ALL, SourceSelection.decode(null))
        assertEquals(SourceSelection.ALL, SourceSelection.decode("junk"))
        val two = setOf(StorageSource.EMULATED, StorageSource.APP)
        assertEquals(two, SourceSelection.decode(SourceSelection.encode(two)))
        assertEquals(setOf(StorageSource.APP), SourceSelection.toggle(two, StorageSource.EMULATED))
        assertEquals(setOf(StorageSource.APP), SourceSelection.toggle(setOf(StorageSource.APP), StorageSource.APP))
        assertEquals(SourceSelection.ALL, SourceSelection.toggle(two, StorageSource.EXTERNAL))
    }
}
