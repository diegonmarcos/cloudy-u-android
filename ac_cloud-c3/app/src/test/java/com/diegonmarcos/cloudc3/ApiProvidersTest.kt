package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ApiProviders
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1471 Infra > APIs: the shipped data/api_providers.json parses, matches the nav declaration, and holds secret NAMES only. */
class ApiProvidersTest {

    private val text = File(FleetFixtures.moduleDir(), "data/api_providers.json").readText()
    private val decl = ApiProviders.parse(text)
    private val secretShape = Regex("sk-or-v1-[A-Za-z0-9]{16,}|sk-ant-[A-Za-z0-9_-]{16,}|apify_api_[A-Za-z0-9]{16,}|gh[pousr]_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}|eyJ[A-Za-z0-9_-]{20,}|(?i:bearer) +[A-Za-z0-9._~+/=-]{24,}")

    @Test
    fun `the AI and Scraper groups are declared and each provider sits in one of them`() {
        assertEquals(listOf("AI", "Scraper"), decl.groups.map { it.label })
        assertTrue(decl.providers.all { p -> decl.groups.any { it.id == p.group } })
        assertEquals(setOf("OpenRouter", "Claude (Anthropic API)"), decl.of(decl.groups[0]).map { it.label }.toSet())
        assertEquals(setOf("Apify", "Cloudy Crawlee", "Cloudflare Browser Run"), decl.of(decl.groups[1]).map { it.label }.toSet())
    }

    @Test
    fun `each provider carries a link, an auth method and at least one secret NAME, and no value-shaped text`() {
        decl.providers.forEach { p ->
            assertTrue("${p.id} has a link", p.links.isNotEmpty() && p.links.all { it.url.startsWith("https://") })
            assertTrue("${p.id} has an auth method", p.auth.isNotBlank())
            assertTrue("${p.id} has a secret name", p.secrets.isNotEmpty() && p.secrets.all { Regex("[A-Za-z][A-Za-z0-9_./-]{2,63}").matches(it.name) })
        }
        assertNull("no secret-shaped value anywhere in the declaration", secretShape.find(text))
    }

    @Test
    fun `every group page is a page the Infra section declares, and the APIs row lists exactly those pages between Bare and Personal`() {
        val infra = Json.parseToJsonElement(File(FleetFixtures.moduleDir(), "build.json").readText()).jsonObject["ui"]!!.jsonObject["sections"]!!.jsonArray
            .map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "infra" }
        val pages = infra["pages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val rows = infra["groups"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Bare", "APIs", "Personal", "Domains"), rows.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(decl.groups.map { it.page }, rows[1]["pages"]!!.jsonArray.map { it.jsonPrimitive.content })
        decl.groups.forEach { g ->
            assertTrue("${g.page} is declared", g.page in pages)
            assertEquals(g.id, decl.groupOfPage(g.page)!!.id)
        }
        assertNull(decl.groupOfPage("providers"))
    }

    @Test
    fun `a malformed declaration parses to nothing and a secret has no value field`() {
        assertTrue(ApiProviders.parse("not json").providers.isEmpty())
        val one = ApiProviders.parse("""{"groups":[{"id":"g","page":"p"}],"providers":[{"id":"x","group":"g","secrets":[{"name":"A_B","kind":"env","value":"hunter2"}]}]}""")
        assertEquals(listOf(ApiProviders.Secret("A_B", "env")), one.providers.single().secrets)
    }
}
