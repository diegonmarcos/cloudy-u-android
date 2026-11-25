/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.domain.model

import androidx.compose.runtime.Stable
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Represents the set of active filters applied to the timeline.
 * Each field that is null/empty means "no filter for this category".
 *
 * The All tab (ex Timeline) opens on [ALL_DEFAULT] (every source, every folder; [CAMERA_DEFAULT] is the old Camera-only default) and keeps whatever the
 * user chose afterwards ([TimelineFilterCodec] is the persisted form).
 */
@Stable
@Serializable
data class TimelineFilter(
    val mediaType: MediaTypeFilter = MediaTypeFilter.ALL,
    val favoritesOnly: Boolean = false,
    val selectedYears: Set<Int> = emptySet(),
    val selectedAlbumIds: Set<Long> = emptySet(),
    /** Bucket (folder) names, matched against the last segment of a media's RELATIVE_PATH, case-insensitive. */
    val folders: Set<String> = emptySet(),
    /** Inclusive range over the taken date, epoch seconds; null = open end. */
    val dateFromSec: Long? = null,
    val dateToSec: Long? = null,
    val location: LocationFilter = LocationFilter.ANY,
    /** Storage source: All | Emulated | External | App. */
    val source: SourceFilter = SourceFilter.ALL,
) {
    val isActive: Boolean
        get() = mediaType != MediaTypeFilter.ALL ||
                favoritesOnly ||
                selectedYears.isNotEmpty() ||
                selectedAlbumIds.isNotEmpty() ||
                folders.isNotEmpty() ||
                dateFromSec != null ||
                dateToSec != null ||
                location != LocationFilter.ANY ||
                source != SourceFilter.ALL

    /** True when [media] passes every active criterion. */
    fun matches(media: FilterableMedia): Boolean {
        val typeMatch = when (mediaType) {
            MediaTypeFilter.ALL -> true
            MediaTypeFilter.PHOTOS -> media.isImage
            MediaTypeFilter.VIDEOS -> media.isVideo
        }
        if (!typeMatch) return false
        if (favoritesOnly && !media.favorite) return false
        if (selectedYears.isNotEmpty() && media.year !in selectedYears) return false
        if (selectedAlbumIds.isNotEmpty() && media.albumId !in selectedAlbumIds) return false
        if (folders.isNotEmpty() && folders.none { it.equals(media.bucket, ignoreCase = true) }) return false
        if (!source.accepts(media.source)) return false
        if (dateFromSec != null && media.takenSec < dateFromSec) return false
        if (dateToSec != null && media.takenSec > dateToSec) return false
        return when (location) {
            LocationFilter.ANY -> true
            LocationFilter.WITH -> media.hasLocation
            LocationFilter.WITHOUT -> !media.hasLocation
        }
    }

    companion object {
        const val CAMERA_BUCKET = "Camera"
        const val SCREENSHOTS_BUCKET = "Screenshots"
        const val DOWNLOADS_BUCKET = "Download"

        /** What the All tab (formerly Timeline) shows until the user changes it: every source, every folder. */
        val ALL_DEFAULT = TimelineFilter()

        /** What the Timeline tab opened on before it became All: the Camera folder only. */
        val CAMERA_DEFAULT = TimelineFilter(folders = setOf(CAMERA_BUCKET))

        /** The fixed filter behind a single-bucket tab (Screenshots, Downloads, Camera). */
        fun bucket(name: String) = TimelineFilter(folders = setOf(name))
    }
}

@Serializable
enum class MediaTypeFilter {
    ALL, PHOTOS, VIDEOS
}

/** Photos by whether they carry a GPS position. */
@Serializable
enum class LocationFilter {
    ANY, WITH, WITHOUT
}

/**
 * The part of a media item the filter reads, free of Android types so the filter is a pure,
 * JVM-testable function. [year] is the calendar year of [takenSec]; [bucket] the last segment
 * of the media's RELATIVE_PATH.
 */
data class FilterableMedia(
    val albumId: Long,
    val bucket: String,
    val isImage: Boolean,
    val isVideo: Boolean,
    val favorite: Boolean,
    val takenSec: Long,
    val year: Int,
    val hasLocation: Boolean,
    val source: StorageSource = StorageSource.EMULATED,
)

/** The bucket (folder) name of a MediaStore RELATIVE_PATH: `DCIM/Camera/` -> `Camera`. */
fun bucketOf(relativePath: String): String = relativePath.trim('/').substringAfterLast('/')

/** Select All / Unselect All of the folder list: selecting every listed folder, or none (no folder restriction). */
object FolderSelection {
    fun selectAll(available: List<String>): Set<String> = available.toSet()
    fun unselectAll(): Set<String> = emptySet()
    fun isAllSelected(selected: Set<String>, available: List<String>): Boolean =
        available.isNotEmpty() && available.all { a -> selected.any { it.equals(a, ignoreCase = true) } }
}

/** The persisted form of [TimelineFilter]: JSON in one DataStore string. Anything unreadable falls back to the default. */
object TimelineFilterCodec {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(filter: TimelineFilter): String = json.encodeToString(TimelineFilter.serializer(), filter)

    fun decode(text: String?): TimelineFilter =
        if (text.isNullOrBlank()) TimelineFilter.ALL_DEFAULT
        else runCatching { json.decodeFromString(TimelineFilter.serializer(), text) }.getOrDefault(TimelineFilter.ALL_DEFAULT)
}

/** The date-range filter's conversions between the picker (UTC-midnight millis of a calendar day) and the filter (epoch seconds in the device's zone). */
object TimelineDates {
    private const val DAY_SECONDS = 86_400L

    /** [startUtcMs] and [endUtcMs] are the picked first and last day; the range is [first day 00:00, last day 23:59:59] local. A single picked day is a one-day range. */
    fun rangeFromPicker(
        startUtcMs: Long?,
        endUtcMs: Long?,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Pair<Long?, Long?> {
        fun dayStart(utcMs: Long): Long = java.time.Instant.ofEpochMilli(utcMs).atZone(java.time.ZoneOffset.UTC)
            .toLocalDate().atStartOfDay(zone).toEpochSecond()
        val from = startUtcMs?.let(::dayStart)
        val to = (endUtcMs ?: startUtcMs)?.let { dayStart(it) + DAY_SECONDS - 1 }
        return from to to
    }

    /** The picker's UTC-midnight millis for the local day containing [sec], to preselect the current range. */
    fun pickerMillis(sec: Long?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? =
        sec?.let {
            java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate()
                .atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()
        }

    /** "2026-01-03 - 2026-02-10", or null when neither end is set. */
    fun label(fromSec: Long?, toSec: Long?, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): String? {
        if (fromSec == null && toSec == null) return null
        fun day(sec: Long?) = sec?.let { java.time.Instant.ofEpochSecond(it).atZone(zone).toLocalDate().toString() } ?: "..."
        return "${day(fromSec)} - ${day(toSec)}"
    }
}
