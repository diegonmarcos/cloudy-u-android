/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.domain.model

/** The kind of a media file, for the Group by control. [label] is the section header; declaration order is the section order. */
enum class MediaKind(val label: String) {
    GIF("GIF"), IMAGES("Images"), VIDEOS("Videos"), RAW("RAW"), HEIC("HEIC"), LIVE("Live photos"), DOCUMENTS("Documents"), OTHER("Other");

    companion object {
        private val RAW_EXT = setOf("dng", "arw", "cr2", "cr3", "nef", "nrw", "orf", "raf", "rw2", "pef", "srw")

        fun of(mimeType: String, label: String = ""): MediaKind {
            val mime = mimeType.lowercase()
            val name = label.lowercase()
            val ext = name.substringAfterLast('.', "")
            return when {
                mime == "image/gif" || ext == "gif" -> GIF
                mime.startsWith("video/") -> VIDEOS
                mime == "image/heic" || mime == "image/heif" || ext == "heic" || ext == "heif" -> HEIC
                ext in RAW_EXT || (mime.startsWith("image/x-") && RAW_EXT.any { mime.endsWith("-$it") }) -> RAW
                mime.startsWith("image/") && (name.startsWith("mvimg_") || ".mp." in name || name.endsWith(".mp")) -> LIVE
                mime.startsWith("image/") -> IMAGES
                mime == "application/pdf" || mime.startsWith("text/") || mime.startsWith("application/") -> DOCUMENTS
                else -> OTHER
            }
        }
    }
}

/** One Group by section. */
data class KindGroup<T>(val kind: MediaKind, val items: List<T>) {
    val header: String get() = "${kind.label} (${items.size})"
}

/** [items] split by kind, sections in [MediaKind] order, only kinds that are present; item order kept inside a section. */
fun <T> groupByKind(items: List<T>, kindOf: (T) -> MediaKind): List<KindGroup<T>> {
    val by = items.groupBy(kindOf)
    return MediaKind.entries.mapNotNull { k -> by[k]?.let { KindGroup(k, it) } }
}

/** The Group by choice of a bucket page. */
enum class GroupBy { NONE, KIND }
