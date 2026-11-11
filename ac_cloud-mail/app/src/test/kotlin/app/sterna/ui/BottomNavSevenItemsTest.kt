package app.sterna.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.io.File

/** The bottom nav is the 7 items Mail, Alerts, Tasks, Home, Chat, Video, Contacts, in that order. */
class BottomNavSevenItemsTest {
    private val ui by lazy {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "build.json").isFile && File(it, "app").isDirectory }
        Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject
    }

    @Test fun `seven items, in the owner's order`() {
        assertEquals(
            listOf("mail", "rss", "tasks", "home", "chat", "video", "contacts"),
            ui["bottom_nav"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
    }

    @Test fun `each item has its own label and a section that says what it does`() {
        val sections = ui["sections"]!!.jsonArray.associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        for (id in listOf("mail", "rss", "tasks", "home", "chat", "video", "contacts")) {
            val pages = sections.getValue(id).jsonObject["pages"]!!.jsonArray
            assertEquals("one page opens $id", true, pages.isNotEmpty())
            if (id != "home") assertNotEquals("$id has its own label", itemDescription("home"), itemDescription(id))
        }
        assertEquals("launch:com.diegonmarcos.cloudagenda", sections.getValue("tasks").jsonObject["pages"]!!.jsonArray[0].jsonObject["action"]!!.jsonPrimitive.content)
        assertEquals("launch:com.diegonmarcos.cloudcontacts", sections.getValue("contacts").jsonObject["pages"]!!.jsonArray[0].jsonObject["action"]!!.jsonPrimitive.content)
    }
}
