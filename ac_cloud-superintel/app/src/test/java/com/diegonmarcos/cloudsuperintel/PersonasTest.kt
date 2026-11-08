package com.diegonmarcos.cloudsuperintel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonasTest {
    private val all = Personas.parse(Data.asset("personas.json"))

    @Test fun personaListComesFromData() {
        assertEquals(
            listOf(
                "SI", "Math", "Physics", "Chemistry", "Energy", "Manufacturing", "Robotics", "CTO",
                "Biology", "Neuroscience", "Lawyer", "Asset Manager", "Wiki AI",
            ),
            all.map { it.label },
        )
    }

    @Test fun uiPagesAreExactlyThePersonas() {
        assertEquals(all.filter { it.group.isEmpty() }.map { it.id }, Data.pages("si"))
        assertEquals(all.filter { it.group.isNotEmpty() }.map { it.id }, Data.pages("cgcs"))
        assertEquals(listOf("notebook", "cgcs", "si", "tools", "ais", "cost"), Data.bottomNav())
    }

    /** The owner's six CGC groups, in order, each with exactly its experts; the groups in build.json::ui are the personas' `group`s. */
    @Test fun cgcGroupsAreTheOwnersSixWithTheirExperts() {
        val groups = Data.groups("cgcs")
        assertEquals(
            listOf(
                "Deep Science" to listOf("Math", "Physics", "Chemistry"),
                "Engineers" to listOf("Energy", "Manufacturing", "Robotics"),
                "Production" to listOf("CTO"),
                "Medicine" to listOf("Biology", "Neuroscience"),
                "Business" to listOf("Lawyer", "Asset Manager"),
                "General" to listOf("Wiki AI"),
            ),
            groups.map { (_, label, pages) -> label to pages.map { id -> all.first { it.id == id }.label } },
        )
        for ((id, _, pages) in groups) assertEquals("persona groups of $id", pages, all.filter { it.group == id }.map { it.id })
        assertEquals("horizontal_two_rows", Data.navMode("cgcs"))
        val sections = Data.buildJson().getJSONObject("ui").getJSONArray("sections").let { x -> (0 until x.length()).map { x.getJSONObject(it).getString("id") } }
        assertFalse("the News tab is gone", "news" in sections)
    }

    /** A persona chat is a declared persona: its preferred agent, models and MCPs are data, and its servers are the gateway's known ones. */
    @Test fun everyCgcExpertDeclaresItsPreferredAgentModelsAndMcps() {
        for (p in all.filter { it.group.isNotEmpty() }) {
            assertTrue("${p.id} models", p.models.isNotEmpty() && p.models.all { '/' in it })
            assertTrue("${p.id} mcps are named", p.mcps.all { it.endsWith("-mcp") })
            assertTrue("${p.id} agent is empty or a fleet agent", p.agent in listOf("", "claude", "hermes", "goose", "openclaw", "deepseek"))
        }
        assertEquals("claude", all.first { it.id == "cto" }.agent)
        assertEquals(listOf("cloud-cgc-pub-mcp"), all.first { it.id == "math" }.mcps)
    }

    @Test fun theComposerDeclarationHoldsToTheSharedDesign() {
        val design = com.diegonmarcos.superapp.ai.AiDesign.parse(java.io.File("../../ab_cloud-libs-shared/libs/ai/src/main/assets/ai/ai-design.json").readText())
        val decl = com.diegonmarcos.superapp.ai.AiComposerDecl.parse(Data.asset("../ai/composer.json"), design)
        assertEquals("cloud-superintel", decl.app)
        assertEquals(
            listOf("plus", "task", "specialist", "agent", "model", "effort", "mode", "skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more"),
            design.orderedLayout(decl.controls).map { it.id },
        )
        assertTrue("the Repos picker offers repositories", decl.repos.isNotEmpty())
    }

    @Test fun everyPersonaIsFullyDeclared() {
        for (p in all) {
            assertTrue("${p.id} system", p.system.length > 40)
            assertTrue("${p.id} suggestions", p.suggestions.size >= 3 && p.suggestions.all { it.isNotBlank() })
            assertTrue("${p.id} icon", p.icon.isNotBlank())
            assertTrue("${p.id} role", p.role.isNotBlank())
        }
    }

    @Test fun routerListsTheOtherPersonasAndNoPlaceholderSurvives() {
        val si = all.first { it.router }
        assertEquals("si", si.id)
        val sys = Personas.system(si, all)
        assertFalse(sys.contains("{{"))
        for (o in all.filter { it.id != si.id }) assertTrue("${o.label} listed", sys.contains("- ${o.label}: ${o.role}"))
        assertFalse("the router does not list itself", sys.contains("- SI:"))
        assertEquals(1, all.count { it.router })
    }

    @Test fun specialistPromptsAreTheirOwn() {
        for (id in listOf("biology", "neuroscience")) {
            val med = all.first { it.id == id }
            assertEquals(med.system, Personas.system(med, all))
            assertTrue("$id says when to seek urgent care", med.system.contains("urgent care"))
        }
    }

    @Test fun preferredModelIsTheFirstListedOne() {
        val p = all.first().copy(models = listOf("a", "b", "c"))
        assertEquals("b", Personas.preferredModel(p, listOf("c", "b")))
        assertNull(Personas.preferredModel(p, listOf("z")))
        assertNull(Personas.preferredModel(p.copy(models = emptyList()), listOf("a")))
    }

    @Test fun defaultPersonaExists() {
        val d = org.json.JSONObject(Data.asset("personas.json")).getString("default")
        assertTrue(all.any { it.id == d })
    }
}
