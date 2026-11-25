package com.diegonmarcos.mediacenter.timeline

import com.diegonmarcos.mediacenter.feature_node.domain.model.FilterableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.LocationFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaTypeFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineDates
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilterCodec
import com.diegonmarcos.mediacenter.feature_node.domain.model.bucketOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

class TimelineFilterTest {

    private fun media(
        bucket: String = "Camera",
        image: Boolean = true,
        favorite: Boolean = false,
        takenSec: Long = 1_700_000_000L,
        year: Int = 2023,
        located: Boolean = false,
        albumId: Long = 1L,
    ) = FilterableMedia(albumId, bucket, image, !image, favorite, takenSec, year, located)

    @Test
    fun timelineOpensOnTheCameraFolderOnly() {
        val default = TimelineFilter.CAMERA_DEFAULT
        assertEquals(setOf("Camera"), default.folders)
        assertTrue(default.isActive)
        assertTrue(default.matches(media(bucket = "Camera")))
        assertFalse(default.matches(media(bucket = "Screenshots")))
        assertFalse(default.matches(media(bucket = "Download")))
    }

    @Test
    fun defaultFilterLeavesEveryOtherCriterionOpen() {
        val default = TimelineFilter.CAMERA_DEFAULT
        assertEquals(MediaTypeFilter.ALL, default.mediaType)
        assertEquals(LocationFilter.ANY, default.location)
        assertNull(default.dateFromSec)
        assertNull(default.dateToSec)
        assertFalse(default.favoritesOnly)
        assertTrue(default.matches(media(image = false, favorite = true, located = true)))
    }

    @Test
    fun emptyFilterIsInactiveAndMatchesEverything() {
        val none = TimelineFilter()
        assertFalse(none.isActive)
        assertTrue(none.matches(media(bucket = "Anything")))
    }

    @Test
    fun folderMatchIgnoresCase() {
        val f = TimelineFilter(folders = setOf("screenshots"))
        assertTrue(f.matches(media(bucket = "Screenshots")))
    }

    @Test
    fun severalFoldersAreAnyOf() {
        val f = TimelineFilter(folders = setOf("Camera", "Download"))
        assertTrue(f.matches(media(bucket = "Download")))
        assertFalse(f.matches(media(bucket = "Pictures")))
    }

    @Test
    fun mediaTypeSeparatesPhotosFromVideos() {
        assertTrue(TimelineFilter(mediaType = MediaTypeFilter.PHOTOS).matches(media(image = true)))
        assertFalse(TimelineFilter(mediaType = MediaTypeFilter.PHOTOS).matches(media(image = false)))
        assertTrue(TimelineFilter(mediaType = MediaTypeFilter.VIDEOS).matches(media(image = false)))
        assertFalse(TimelineFilter(mediaType = MediaTypeFilter.VIDEOS).matches(media(image = true)))
    }

    @Test
    fun favouritesOnlyDropsTheRest() {
        val f = TimelineFilter(favoritesOnly = true)
        assertTrue(f.matches(media(favorite = true)))
        assertFalse(f.matches(media(favorite = false)))
    }

    @Test
    fun locationFilterSplitsPhotosByPosition() {
        val with = TimelineFilter(location = LocationFilter.WITH)
        val without = TimelineFilter(location = LocationFilter.WITHOUT)
        assertTrue(with.matches(media(located = true)))
        assertFalse(with.matches(media(located = false)))
        assertTrue(without.matches(media(located = false)))
        assertFalse(without.matches(media(located = true)))
    }

    @Test
    fun dateRangeIsInclusiveAtBothEnds() {
        val f = TimelineFilter(dateFromSec = 1_000L, dateToSec = 2_000L)
        assertTrue(f.matches(media(takenSec = 1_000L)))
        assertTrue(f.matches(media(takenSec = 2_000L)))
        assertFalse(f.matches(media(takenSec = 999L)))
        assertFalse(f.matches(media(takenSec = 2_001L)))
    }

    @Test
    fun openEndedDateRangeBoundsOnlyOneSide() {
        assertTrue(TimelineFilter(dateFromSec = 1_000L).matches(media(takenSec = 9_999_999_999L)))
        assertFalse(TimelineFilter(dateFromSec = 1_000L).matches(media(takenSec = 5L)))
        assertTrue(TimelineFilter(dateToSec = 1_000L).matches(media(takenSec = 5L)))
    }

    @Test
    fun criteriaCombine() {
        val f = TimelineFilter(
            folders = setOf("Camera"),
            mediaType = MediaTypeFilter.PHOTOS,
            favoritesOnly = true,
            location = LocationFilter.WITH,
        )
        assertTrue(f.matches(media(favorite = true, located = true)))
        assertFalse(f.matches(media(favorite = true, located = false)))
        assertFalse(f.matches(media(favorite = false, located = true)))
        assertFalse(f.matches(media(bucket = "Download", favorite = true, located = true)))
    }

    @Test
    fun yearAndAlbumCriteriaStillWork() {
        assertTrue(TimelineFilter(selectedYears = setOf(2023)).matches(media(year = 2023)))
        assertFalse(TimelineFilter(selectedYears = setOf(2022)).matches(media(year = 2023)))
        assertTrue(TimelineFilter(selectedAlbumIds = setOf(7L)).matches(media(albumId = 7L)))
        assertFalse(TimelineFilter(selectedAlbumIds = setOf(7L)).matches(media(albumId = 8L)))
    }

    @Test
    fun bucketOfIsTheLastPathSegment() {
        assertEquals("Camera", bucketOf("DCIM/Camera/"))
        assertEquals("Screenshots", bucketOf("Pictures/Screenshots"))
        assertEquals("Download", bucketOf("Download/"))
        assertEquals("", bucketOf(""))
    }

    @Test
    fun theUsersChoiceRoundTripsThroughItsPersistedForm() {
        val chosen = TimelineFilter(
            mediaType = MediaTypeFilter.VIDEOS,
            favoritesOnly = true,
            folders = setOf("Download", "Camera"),
            dateFromSec = 100L,
            dateToSec = 200L,
            location = LocationFilter.WITHOUT,
            selectedYears = setOf(2021),
            selectedAlbumIds = setOf(3L),
        )
        assertEquals(chosen, TimelineFilterCodec.decode(TimelineFilterCodec.encode(chosen)))
    }

    @Test
    fun clearingAllFiltersIsPersistedAsClearedNotAsTheDefault() {
        val cleared = TimelineFilter()
        assertEquals(cleared, TimelineFilterCodec.decode(TimelineFilterCodec.encode(cleared)))
    }

    @Test
    fun nothingStoredMeansTheAllDefault() {
        assertEquals(TimelineFilter.ALL_DEFAULT, TimelineFilterCodec.decode(null))
        assertEquals(TimelineFilter.ALL_DEFAULT, TimelineFilterCodec.decode(""))
        assertEquals(TimelineFilter.CAMERA_DEFAULT, TimelineFilterCodec.decode(TimelineFilterCodec.encode(TimelineFilter.CAMERA_DEFAULT)))
    }

    @Test
    fun unreadableStoredTextFallsBackToTheAllDefault() {
        assertEquals(TimelineFilter.ALL_DEFAULT, TimelineFilterCodec.decode("{not json"))
        assertEquals(TimelineFilter.ALL_DEFAULT, TimelineFilterCodec.decode("""{"mediaType":"HOLOGRAMS"}"""))
    }

    @Test
    fun storedTextFromAnOlderVersionKeepsWhatItKnew() {
        val old = """{"mediaType":"PHOTOS","favoritesOnly":true,"someFutureKey":1}"""
        val decoded = TimelineFilterCodec.decode(old)
        assertEquals(MediaTypeFilter.PHOTOS, decoded.mediaType)
        assertTrue(decoded.favoritesOnly)
        assertTrue(decoded.folders.isEmpty())
    }

    @Test
    fun pickerDaysBecomeTheWholeLocalDays() {
        val utc = ZoneOffset.UTC
        // 2026-01-03 and 2026-01-05 as the picker reports them (UTC midnight).
        val start = java.time.LocalDate.of(2026, 1, 3).atStartOfDay(utc).toInstant().toEpochMilli()
        val end = java.time.LocalDate.of(2026, 1, 5).atStartOfDay(utc).toInstant().toEpochMilli()
        val (from, to) = TimelineDates.rangeFromPicker(start, end, utc)
        assertEquals(java.time.LocalDate.of(2026, 1, 3).atStartOfDay(utc).toEpochSecond(), from)
        assertEquals(java.time.LocalDate.of(2026, 1, 6).atStartOfDay(utc).toEpochSecond() - 1, to)
    }

    @Test
    fun theRangeIsCutInTheDevicesZoneNotInUtc() {
        val zone = ZoneId.of("Pacific/Auckland")
        val day = java.time.LocalDate.of(2026, 1, 3).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val (from, to) = TimelineDates.rangeFromPicker(day, day, zone)
        assertEquals(java.time.LocalDate.of(2026, 1, 3).atStartOfDay(zone).toEpochSecond(), from)
        assertEquals(java.time.LocalDate.of(2026, 1, 4).atStartOfDay(zone).toEpochSecond() - 1, to)
    }

    @Test
    fun aSinglePickedDayIsAOneDayRange() {
        val utc = ZoneOffset.UTC
        val day = java.time.LocalDate.of(2026, 3, 9).atStartOfDay(utc).toInstant().toEpochMilli()
        val (from, to) = TimelineDates.rangeFromPicker(day, null, utc)
        assertEquals(86_399L, to!! - from!!)
    }

    @Test
    fun nothingPickedLeavesTheRangeOpen() {
        assertEquals(null to null, TimelineDates.rangeFromPicker(null, null))
    }

    @Test
    fun pickerPreselectionRoundTrips() {
        val utc = ZoneOffset.UTC
        val sec = java.time.LocalDate.of(2026, 1, 3).atStartOfDay(utc).toEpochSecond()
        val millis = TimelineDates.pickerMillis(sec, utc)
        assertEquals(sec * 1000L, millis)
        assertNull(TimelineDates.pickerMillis(null))
    }

    @Test
    fun theRangeLabelNamesBothEnds() {
        val utc = ZoneOffset.UTC
        val from = java.time.LocalDate.of(2026, 1, 3).atStartOfDay(utc).toEpochSecond()
        val to = java.time.LocalDate.of(2026, 2, 10).atStartOfDay(utc).toEpochSecond()
        assertEquals("2026-01-03 - 2026-02-10", TimelineDates.label(from, to, utc))
        assertEquals("2026-01-03 - ...", TimelineDates.label(from, null, utc))
        assertNull(TimelineDates.label(null, null, utc))
    }
}
