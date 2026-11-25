/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.presentation.timeline

import com.diegonmarcos.mediacenter.feature_node.domain.model.FilterableMedia
import com.diegonmarcos.mediacenter.feature_node.domain.model.Media
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaItem
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaMetadata
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaKind
import com.diegonmarcos.mediacenter.feature_node.domain.model.MediaState
import com.diegonmarcos.mediacenter.feature_node.domain.model.StorageSource
import com.diegonmarcos.mediacenter.feature_node.domain.model.StorageSources
import com.diegonmarcos.mediacenter.feature_node.domain.model.groupByKind
import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.domain.model.bucketOf
import com.diegonmarcos.mediacenter.feature_node.domain.util.isFavorite
import com.diegonmarcos.mediacenter.feature_node.domain.util.isImage
import com.diegonmarcos.mediacenter.feature_node.domain.util.isVideo
import com.diegonmarcos.mediacenter.shell.GeoPositions

/** The ids of the media whose metadata carries a GPS position. */
fun geoTaggedIds(metadata: List<MediaMetadata>): Set<Long> =
    metadata.filter { GeoPositions.isUsable(it.gpsLatitude, it.gpsLongitude) }.mapTo(HashSet()) { it.mediaId }

/** The filter's view of [this]; [geoIds] is only read when the filter has a location criterion. */
fun Media.UriMedia.toFilterable(geoIds: Set<Long>, needYear: Boolean): FilterableMedia {
    val taken = definedTimestamp
    val year = if (needYear) {
        java.util.Calendar.getInstance().apply { timeInMillis = taken * 1000L }.get(java.util.Calendar.YEAR)
    } else 0
    return FilterableMedia(
        albumId = albumID,
        bucket = bucketOf(relativePath),
        isImage = isImage,
        isVideo = isVideo,
        favorite = isFavorite,
        takenSec = taken,
        year = year,
        hasLocation = id in geoIds,
        source = StorageSources.classify(path.ifBlank { relativePath }),
    )
}

/**
 * [this] narrowed to the media [filter] accepts, with the grouped/mapped views rebuilt from the same
 * ids. Returns [this] when the filter is inactive.
 */
fun MediaState<Media.UriMedia>.filteredBy(filter: TimelineFilter, geoIds: Set<Long>): MediaState<Media.UriMedia> {
    if (!filter.isActive) return this
    val needYear = filter.selectedYears.isNotEmpty()
    return keeping { filter.matches(it.toFilterable(geoIds, needYear)) }
}

/** [this] narrowed to the media in the Home-selected [sources]; every page reads through this. */
fun MediaState<Media.UriMedia>.fromSources(sources: Set<StorageSource>): MediaState<Media.UriMedia> =
    if (sources.size == StorageSource.entries.size) this
    else keeping { StorageSources.classify(it.path.ifBlank { it.relativePath }) in sources }

private fun MediaState<Media.UriMedia>.keeping(accept: (Media.UriMedia) -> Boolean): MediaState<Media.UriMedia> {
    val kept = media.filter(accept)
    val keptIds = kept.mapTo(HashSet(kept.size)) { it.id }
    fun List<MediaItem<Media.UriMedia>>.narrow(): List<MediaItem<Media.UriMedia>> = filter { item ->
        when (item) {
            is MediaItem.MediaViewItem -> item.media.id in keptIds
            is MediaItem.Header -> item.data.any { it in keptIds }
        }
    }
    return copy(
        media = kept,
        pagerMedia = pagerMedia.filter { it.id in keptIds },
        mappedMedia = mappedMedia.narrow(),
        mappedMediaWithMonthly = mappedMediaWithMonthly.narrow(),
        mappedMediaWithYearly = mappedMediaWithYearly.narrow(),
        headers = headers.filter { header -> header.data.any { it in keptIds } },
    )
}

/** [this] regrouped into one section per media kind (GIF, Images, Videos, ...), each with a header carrying its count. */
fun MediaState<Media.UriMedia>.groupedByKind(): MediaState<Media.UriMedia> {
    val groups = groupByKind(media) { MediaKind.of(it.mimeType, it.label) }
    val headers = groups.map { g ->
        MediaItem.Header<Media.UriMedia>("header_kind_${g.kind.name}", g.header, g.items.mapTo(HashSet(g.items.size)) { it.id })
    }
    val mapped = groups.flatMapIndexed { i, g ->
        listOf<MediaItem<Media.UriMedia>>(headers[i]) + g.items.map { MediaItem.MediaViewItem("media_${it.id}_${it.label}", it) }
    }
    return copy(
        media = groups.flatMap { it.items },
        mappedMedia = mapped,
        mappedMediaWithMonthly = mapped,
        mappedMediaWithYearly = mapped,
        headers = headers,
    )
}
