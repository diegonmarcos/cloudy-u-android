package com.diegonmarcos.clouddrive.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The agents are read at runtime from the served dataset `agents` (Cloud Store, repo source
 * data/fleet/app/com.diegonmarcos.clouddrive/agents.json, #1547 — never baked into the APK). Parsing the served file here with the
 * same AgentsDecl.parse the app uses through libs:fleet-data-client proves the provider yields the identical declared agents.
 */
class AgentsDeclTest {
    private val served = File("../../data/fleet/app/com.diegonmarcos.clouddrive/agents.json")
    private val text by lazy { served.readText() }

    @Test fun theServedDatasetParsesToTheDeclaredAgents() {
        val agents = AgentsDecl.parse(text)
        assertTrue("at least one agent is declared", agents.isNotEmpty())
        assertEquals("ids are unique", agents.size, agents.map { it.id }.toSet().size)
        agents.forEach { a ->
            assertTrue("${a.id} has a name", a.name.isNotBlank())
            a.actions.forEach { assertTrue("${a.id} action ${it.id} has a label", it.label.isNotBlank()) }
        }
    }

    @Test fun theServedDatasetCarriesAnIntegerSchemaVersion() {
        val v = org.json.JSONObject(text).optInt("schemaVersion", -1)
        assertTrue("the served dataset declares schemaVersion (required by the fleet-data client)", v >= 1)
    }
}
