/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.feature_node.domain.model

import kotlinx.serialization.Serializable

/** Where a media file lives. */
@Serializable
enum class StorageSource {
    /** The primary shared storage (MediaStore volume external_primary, /storage/emulated/<user>). */
    EMULATED,

    /** A removable volume: SD card or USB (any other MediaStore volume name, /storage/<uuid>). */
    EXTERNAL,

    /** An app-specific directory: Android/data or Android/media. */
    APP,
}

/** The Source filter of the Timeline ("All"): every source, or exactly one. */
@Serializable
enum class SourceFilter(val source: StorageSource?) {
    ALL(null), EMULATED(StorageSource.EMULATED), EXTERNAL(StorageSource.EXTERNAL), APP(StorageSource.APP);

    fun accepts(s: StorageSource): Boolean = source == null || source == s
}

/** The ONE classifier of media into a [StorageSource]; every page and filter goes through it. */
object StorageSources {
    private const val PRIMARY_VOLUME = "external_primary"
    private const val LEGACY_PRIMARY_VOLUME = "external"

    /**
     * [path] is the absolute file path or the MediaStore RELATIVE_PATH; [volumeName] the MediaStore
     * volume name when known. App directories win over the volume they sit on.
     */
    fun classify(path: String, volumeName: String? = null): StorageSource {
        val parts = path.replace('\\', '/').split('/').filter { it.isNotEmpty() }
        val appAt = parts.indexOf("Android")
        if (appAt >= 0 && parts.getOrNull(appAt + 1).let { it == "data" || it == "media" }) return StorageSource.APP
        val volume = volumeName?.trim()?.lowercase()
        if (!volume.isNullOrEmpty()) {
            return if (volume == PRIMARY_VOLUME || volume == LEGACY_PRIMARY_VOLUME) StorageSource.EMULATED else StorageSource.EXTERNAL
        }
        if (parts.firstOrNull() == "storage") {
            val vol = parts.getOrNull(1)
            if (vol != null && vol != "emulated" && vol != "self") return StorageSource.EXTERNAL
        }
        if (parts.size > 2 && parts[0] == "mnt" && parts[1] == "media_rw") return StorageSource.EXTERNAL
        return StorageSource.EMULATED
    }
}

/** Home's persisted multi-select of the sources the whole app reads from. */
object SourceSelection {
    val ALL: Set<StorageSource> = StorageSource.entries.toSet()

    fun encode(selection: Set<StorageSource>): String =
        StorageSource.entries.filter { it in selection }.joinToString(",") { it.name }

    /** Blank, unreadable or empty text means every source. */
    fun decode(text: String?): Set<StorageSource> {
        val parsed = text.orEmpty().split(',').mapNotNull { n -> StorageSource.entries.firstOrNull { it.name == n.trim() } }.toSet()
        return parsed.ifEmpty { ALL }
    }

    /** Flips [source]; the last selected source cannot be switched off (the app would show nothing). */
    fun toggle(selection: Set<StorageSource>, source: StorageSource): Set<StorageSource> =
        if (source in selection) (if (selection.size == 1) selection else selection - source) else selection + source
}
