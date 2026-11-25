package com.diegonmarcos.mediacenter.storage

import com.diegonmarcos.mediacenter.feature_node.domain.model.GroupBy
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaKind
import com.diegonmarcos.mediacenter.feature_node.domain.model.groupByKind
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaKindTest {

    @Test
    fun kindsFollowMimeAndName() {
        assertEquals(MediaKind.GIF, MediaKind.of("image/gif", "a.gif"))
        assertEquals(MediaKind.IMAGES, MediaKind.of("image/jpeg", "a.jpg"))
        assertEquals(MediaKind.VIDEOS, MediaKind.of("video/mp4", "a.mp4"))
        assertEquals(MediaKind.HEIC, MediaKind.of("image/heic", "a.heic"))
        assertEquals(MediaKind.RAW, MediaKind.of("image/x-adobe-dng", "a.dng"))
        assertEquals(MediaKind.RAW, MediaKind.of("image/x-sony-arw", "a"))
        assertEquals(MediaKind.LIVE, MediaKind.of("image/jpeg", "MVIMG_2023.jpg"))
        assertEquals(MediaKind.DOCUMENTS, MediaKind.of("application/pdf", "a.pdf"))
        assertEquals(MediaKind.OTHER, MediaKind.of("audio/mpeg", "a.mp3"))
    }

    @Test
    fun groupsAreOrderedPresentOnlyAndCounted() {
        val files = listOf("a.mp4" to "video/mp4", "b.jpg" to "image/jpeg", "c.gif" to "image/gif", "d.jpg" to "image/jpeg", "e.dng" to "image/x-adobe-dng")
        val groups = groupByKind(files) { MediaKind.of(it.second, it.first) }
        assertEquals(listOf(MediaKind.GIF, MediaKind.IMAGES, MediaKind.VIDEOS, MediaKind.RAW), groups.map { it.kind })
        assertEquals(listOf("GIF (1)", "Images (2)", "Videos (1)", "RAW (1)"), groups.map { it.header })
        assertEquals(listOf("b.jpg", "d.jpg"), groups[1].items.map { it.first })
        assertEquals(files.size, groups.sumOf { it.items.size })
    }

    @Test
    fun noMediaMeansNoSections() {
        assertEquals(emptyList<Any>(), groupByKind(emptyList<String>()) { MediaKind.IMAGES })
        assertEquals(listOf(GroupBy.NONE, GroupBy.KIND), GroupBy.entries)
    }
}
