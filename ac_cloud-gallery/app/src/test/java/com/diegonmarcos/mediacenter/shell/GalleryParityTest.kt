package com.diegonmarcos.mediacenter.shell

import com.diegonmarcos.mediacenter.feature_node.domain.model.TimelineFilter
import com.diegonmarcos.mediacenter.feature_node.presentation.util.Screen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1910: the Timeline to All rename and the new controls add structure; no page, tab or filter field went away. */
class GalleryParityTest {

    private val ui by lazy {
        val file = listOf("../build.json", "build.json", "ac_cloud-gallery/build.json").map(::File).first { it.isFile }
        Json.parseToJsonElement(file.readText()).jsonObject.getValue("ui").jsonObject
    }

    @Test
    fun everyPageOfTheBarSurvivesTheRename() {
        val before = listOf("screenshots", "downloads", "camera", "home", "timeline", "maps", "objects")
        val after = GalleryTab.ORDER
        assertEquals(before.size, after.size)
        for (id in before) assertEquals("tab $id", GalleryTab.ofId(id), GalleryTab.ofId(GalleryAliases.id(id)))
        for (id in before) assertTrue("tab $id resolves", GalleryTab.ofId(id) != null)
    }

    @Test
    fun everyFilterFieldIsStillThere() {
        val fields = (0 until TimelineFilter.serializer().descriptor.elementsCount).map { TimelineFilter.serializer().descriptor.getElementName(it) }.toSet()
        val old = setOf("mediaType", "favoritesOnly", "selectedYears", "selectedAlbumIds", "folders", "dateFromSec", "dateToSec", "location")
        assertTrue("lost ${old - fields}", fields.containsAll(old))
        assertTrue("source filter missing", "source" in fields)
    }

    @Test
    fun theOldRouteAndSectionIdsAliasToAll() {
        assertEquals(GalleryTab.TIMELINE, GalleryTab.ofRoute("timeline_screen"))
        assertEquals(GalleryTab.TIMELINE, GalleryTab.ofRoute(Screen.TimelineScreen.route))
        assertEquals(GalleryTab.TIMELINE, GalleryTab.ofId("timeline"))
        assertEquals(GalleryTab.TIMELINE, GalleryTab.ofId("all"))
        assertEquals("all_screen", GalleryAliases.route("timeline_screen"))
        assertEquals("tab_home", GalleryAliases.route("tab_home"))
        assertEquals(null, GalleryTab.ofRoute(null))
    }

    @Test
    fun theDeclarationCarriesTheSameAliasesAsTheCode() {
        val aliases = ui.getValue("aliases").jsonObject
        val sections = aliases.getValue("sections").jsonObject.mapValues { it.value.jsonPrimitive.content }
        val routes = aliases.getValue("routes").jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertEquals(GalleryAliases.IDS, sections)
        assertEquals(GalleryAliases.ROUTES, routes)
        val labels = ui.getValue("sections").jsonArray.map { it.jsonObject }.associate { it.getValue("id").jsonPrimitive.content to it.getValue("label").jsonPrimitive.content }
        assertEquals("All", labels["all"])
        assertEquals(null, labels["timeline"])
    }
}
