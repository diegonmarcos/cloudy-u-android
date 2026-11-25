package com.diegonmarcos.cloudme

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Profile > Health and DD: the real schemas load, status chips follow the data, the export names every field, imports are checked. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ProfileRecordTest {
    private fun schema(id: String) = JSONObject(BuroLlcTestData.text("data/profiles/profiles/$id.schema.json"))
    private val today = "2026-10-25"

    @Test fun `the Health and DD schemas load with unique field ids and no values`() {
        for (id in listOf("health", "dd")) {
            val doc = ProfileRecord.merge(schema(id), null, today)
            assertEquals(id, doc.id)
            assertTrue("$id has sections", doc.sections.size >= 10)
            val ids = doc.sections.flatMap { s -> s.fields.map { it.id } }
            assertEquals("$id field ids are unique", ids.size, ids.toSet().size)
            assertEquals(0, doc.filled)
            assertEquals(0, doc.percent)
            assertTrue(doc.sections.all { s -> s.fields.all { it.status == ProfileStatus.PENDING && it.value.isEmpty() } })
        }
    }

    @Test fun `status chips follow the data`() {
        val data = JSONObject("""{"schema":"dd","values":{
            "identity.full_name":{"value":"A","status":"declared"},
            "identity.cpf":{"value":"B","status":"verified","expires":"2030-01-01"},
            "identity.brazilian_passport":{"value":"C","status":"verified","expires":"2026-01-01"},
            "identity.spanish_passport":{"value":"D"},
            "identity.nie_dni":{"value":"","status":"verified"}}}""")
        val f = ProfileRecord.merge(schema("dd"), data, today).sections.first { it.id == "identity" }.fields.associateBy { it.id }
        assertEquals(ProfileStatus.DECLARED, f.getValue("identity.full_name").status)
        assertEquals(ProfileStatus.VERIFIED, f.getValue("identity.cpf").status)
        assertEquals(ProfileStatus.EXPIRED, f.getValue("identity.brazilian_passport").status)
        assertEquals("a value with no status is not trusted", ProfileStatus.PENDING, f.getValue("identity.spanish_passport").status)
        assertEquals("a status without a value is pending", ProfileStatus.PENDING, f.getValue("identity.nie_dni").status)
    }

    @Test fun `completeness counts filled fields`() {
        val data = JSONObject("""{"schema":"health","values":{"identity_and_emergency_contacts.full_name":{"value":"A","status":"declared"}}}""")
        val doc = ProfileRecord.merge(schema("health"), data, today)
        assertEquals(1, doc.filled)
        assertEquals(1, doc.count(ProfileStatus.DECLARED))
        assertEquals(1 * 100 / doc.total, doc.percent)
    }

    @Test fun `the export lists every field and marks the unfilled ones pending`() {
        val doc = ProfileRecord.merge(schema("health"), null, today)
        val sections = ProfileRecord.exportSections(doc)
        assertEquals("Summary", sections.first().first)
        assertEquals(doc.total, sections.drop(1).sumOf { it.second.size })
        assertTrue(sections.drop(1).all { (_, lines) -> lines.all { it.startsWith("[PENDING]") && it.endsWith(PENDING_TEXT) } })
        assertEquals("profile-health.pdf", ProfileRecord.pdfName(doc))
    }

    @Test fun `an import for another schema or with unknown fields or statuses is refused`() {
        val s = schema("health")
        assertTrue(ProfileRecord.validate(s, JSONObject("""{"schema":"health","values":{"allergies.drug":{"value":"x","status":"verified"}}}""")).isEmpty())
        assertFalse(ProfileRecord.validate(s, JSONObject("""{"schema":"dd","values":{}}""")).isEmpty())
        assertFalse(ProfileRecord.validate(s, JSONObject("""{"schema":"health","values":{"nope":{"value":"x"}}}""")).isEmpty())
        assertFalse(ProfileRecord.validate(s, JSONObject("""{"schema":"health","values":{"allergies.drug":{"value":"x","status":"clean"}}}""")).isEmpty())
        assertFalse(ProfileRecord.validate(s, JSONObject("""{"schema":"health"}""")).isEmpty())
    }

    @Test fun `Pers declares Social, the health record and DD, and each is an embedded web page`() {
        val ui = JSONObject(BuroLlcTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
        val pers = (0 until ui.length()).map { ui.getJSONObject(it) }.first { it.getString("id") == "pers" }
        fun flat(a: org.json.JSONArray): List<String> = (0 until a.length()).flatMap { val o = a.getJSONObject(it); listOf(o.getString("id")) + (o.optJSONArray("pages")?.let(::flat) ?: emptyList()) }
        val ids = flat(pers.getJSONArray("pages"))
        for (p in listOf("ig0", "health-profile", "dd")) assertTrue(p in ids)
        // #1901 the record page that was Lnktree > Health is Pers > Health > Record (page id health-profile; the id health is the container)
        for ((p, f) in listOf("health" to "pers/health/health-profile", "dd" to "pers/dd")) {
            val stack = org.json.JSONArray(BuroLlcTestData.text("data/ui/$f.json"))
            // Embedded render of the website page (like Professional and Personal); values are injected after load.
            assertEquals("fragment", stack.getJSONObject(0).getString("kind"))
            assertEquals("mysocials/$p.html", stack.getJSONObject(0).getString("url"))
            assertEquals(p, stack.getJSONObject(0).getString("inject"))
        }
    }
}
