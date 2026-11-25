package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GalleryTabsTest {

    /** build.json::ui, read from this application's own declaration (the unit task runs in app/). */
    private val ui: JsonObject by lazy {
        val file = listOf("../build.json", "build.json", "ac_cloud-gallery/build.json").map(::File).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("ui").jsonObject
    }

    private fun ids(a: JsonArray) = a.map { it.jsonPrimitive.content }

    @Test
    fun theBarIsExactlyTheOwnersSevenInTheirOrder() {
        assertEquals(
            listOf("screenshots", "downloads", "camera", "home", "all", "maps", "objects"),
            ids(ui.getValue("bottom_nav").jsonArray),
        )
    }

    @Test
    fun theTabsInCodeAreTheTabsInTheDeclaration() {
        assertEquals(ids(ui.getValue("bottom_nav").jsonArray), GalleryTab.ORDER)
    }

    @Test
    fun everyBarEntryHasASectionWithALabelAndAnIcon() {
        val sections = ui.getValue("sections").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        for (tab in GalleryTab.entries) {
            val section = sections[tab.id] ?: throw AssertionError("no ui.sections entry for ${tab.id}")
            assertTrue("${tab.id} has no label", section.getValue("label").jsonPrimitive.content.isNotBlank())
            assertTrue("${tab.id} has no icon", section.getValue("icon").jsonPrimitive.content.isNotBlank())
        }
    }

    @Test
    fun homeIsWhereTheAppOpens() {
        assertEquals("home", ui.getValue("default_section").jsonPrimitive.content)
    }

    @Test
    fun mapsAndObjectsCarryTheSecondRow() {
        val sections = ui.getValue("sections").jsonArray.map { it.jsonObject }.associateBy { it.getValue("id").jsonPrimitive.content }
        fun pages(id: String) = sections.getValue(id)["pages"]?.jsonArray?.map { it.jsonObject.getValue("id").jsonPrimitive.content }.orEmpty()
        assertEquals(listOf(GalleryPages.MAPS_MAP, GalleryPages.MAPS_NO_LOCATION), pages("maps"))
        assertEquals(
            listOf(GalleryPages.OBJECTS_FACES, GalleryPages.OBJECTS_SCENES, GalleryPages.OBJECTS_TEXT, GalleryPages.OBJECTS_DOCUMENTS),
            pages("objects"),
        )
    }

    @Test
    fun everyTabHasItsOwnRouteAndRoutesMapBack() {
        val routes = GalleryTab.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        for (tab in GalleryTab.entries) {
            assertEquals(tab, GalleryTab.ofRoute(tab.route))
            assertEquals(tab, GalleryTab.ofId(tab.id))
        }
    }

    @Test
    fun onlyTabRoutesGetTheChrome() {
        assertTrue(GalleryTab.isTabRoute(Screen.TimelineScreen.route))
        assertFalse(GalleryTab.isTabRoute(Screen.AlbumsScreen.route))
        assertFalse(GalleryTab.isTabRoute(Screen.SettingsScreen.route))
        assertFalse(GalleryTab.isTabRoute(Screen.MediaViewScreen.route))
        assertFalse(GalleryTab.isTabRoute(null))
        assertNull(GalleryTab.ofRoute("nope"))
    }

    @Test
    fun theThreeBucketTabsAreFixedToTheirFolder() {
        assertEquals("Screenshots", GalleryTab.SCREENSHOTS.bucket)
        assertEquals("Download", GalleryTab.DOWNLOADS.bucket)
        assertEquals("Camera", GalleryTab.CAMERA.bucket)
        assertEquals(listOf(GalleryTab.SCREENSHOTS, GalleryTab.DOWNLOADS, GalleryTab.CAMERA), GalleryTab.entries.filter { it.bucket != null })
        assertEquals(setOf("Camera"), TimelineFilter.bucket(GalleryTab.CAMERA.bucket!!).folders)
    }

    @Test
    fun theAllTabOpensOnEverythingAndIsNotFixedToAFolder() {
        assertNull(GalleryTab.TIMELINE.bucket)
        assertFalse(TimelineFilter.ALL_DEFAULT.isActive)
        assertTrue(TimelineFilter.ALL_DEFAULT.folders.isEmpty())
        assertEquals(com.diegonmarcos.mediacenter.feature_node.domain.model.SourceFilter.ALL, TimelineFilter.ALL_DEFAULT.source)
        assertEquals(setOf(TimelineFilter.CAMERA_BUCKET), TimelineFilter.CAMERA_DEFAULT.folders)
    }

    @Test
    fun everyTabAsksForTheLibraryAndMapsAlsoForPositions() {
        for (sdk in listOf(29, 33, 36)) {
            for (tab in GalleryTab.entries) {
                val needed = TabPermissions.forTab(tab, sdk)
                assertTrue("$tab sdk $sdk reads no media", needed.containsAll(TabPermissions.media(sdk)))
                assertEquals("$tab sdk $sdk", tab == GalleryTab.MAPS, TabPermissions.MEDIA_LOCATION in needed)
            }
        }
    }

    @Test
    fun mediaPermissionsFollowTheAndroidVersion() {
        assertEquals(
            listOf("android.permission.READ_MEDIA_IMAGES", "android.permission.READ_MEDIA_VIDEO"),
            TabPermissions.media(33),
        )
        assertEquals(listOf("android.permission.READ_EXTERNAL_STORAGE"), TabPermissions.media(29))
        assertEquals("android.permission.ACCESS_MEDIA_LOCATION", TabPermissions.MEDIA_LOCATION)
    }
}
