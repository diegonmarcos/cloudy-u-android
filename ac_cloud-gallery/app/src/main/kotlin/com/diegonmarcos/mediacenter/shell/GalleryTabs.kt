/*
 * SPDX-FileCopyrightText: 2023-2026 IacobIacob01
 * SPDX-License-Identifier: Apache-2.0
 */

package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen

/**
 * The seven bottom-nav tabs, in the order build.json::ui.bottom_nav declares them. [id] is the
 * nav section id (the declaration is the source of the label and icon), [screen] the route the
 * tab opens, and [bucket] the MediaStore bucket a bucket tab is fixed to.
 */
enum class GalleryTab(val id: String, val screen: Screen, val bucket: String? = null) {
    SCREENSHOTS("screenshots", Screen.ScreenshotsTabScreen, TimelineFilter.SCREENSHOTS_BUCKET),
    DOWNLOADS("downloads", Screen.DownloadsTabScreen, TimelineFilter.DOWNLOADS_BUCKET),
    CAMERA("camera", Screen.CameraTabScreen, TimelineFilter.CAMERA_BUCKET),
    HOME("home", Screen.HomeTabScreen),
    TIMELINE("all", Screen.TimelineScreen),
    MAPS("maps", Screen.MapsTabScreen),
    OBJECTS("objects", Screen.ObjectsTabScreen);

    val route: String get() = screen.route

    companion object {
        /** Section ids in bar order: must equal build.json::ui.bottom_nav (GalleryTabsTest holds the two together). */
        val ORDER: List<String> = entries.map { it.id }

        fun ofRoute(route: String?): GalleryTab? = GalleryAliases.route(route).let { r -> if (r == null) null else entries.firstOrNull { it.route == r } }

        fun ofId(id: String?): GalleryTab? = GalleryAliases.id(id).let { i -> entries.firstOrNull { it.id == i } }

        fun isTabRoute(route: String?): Boolean = ofRoute(route) != null
    }
}

/** Old route and section ids that keep working after a rename (Timeline became All): old id -> current id. */
object GalleryAliases {
    val ROUTES: Map<String, String> = mapOf("timeline_screen" to Screen.TimelineScreen.route)
    val IDS: Map<String, String> = mapOf("timeline" to "all")

    fun route(route: String?): String? = route?.let { ROUTES[it] ?: it }
    fun id(id: String?): String? = id?.let { IDS[it] ?: it }
}

/** The sub-pages (second row) of the tabs that have them; ids as in build.json::ui.sections[].pages. */
object GalleryPages {
    const val MAPS_MAP = "map"
    const val MAPS_NO_LOCATION = "no_location"
    const val OBJECTS_FACES = "faces"
    const val OBJECTS_SCENES = "scenes"
    const val OBJECTS_TEXT = "text"
    const val OBJECTS_DOCUMENTS = "documents"
}
