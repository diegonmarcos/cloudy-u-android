package com.diegonmarcos.cloudsuperintel

import com.diegonmarcos.superapp.ai.AiAgent
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1939 Models > Agents and Models > API: the declared lists, the live status layered on top, never empty with the gateway down, no secret-looking value. */
class AgentsTest {
    private val text = File("src/main/assets/agents/agents.json").readText()
    private val declared = Agents.parse(text)
    private val shared = JSONObject(File("../../ab_cloud-libs-shared/libs/model-catalogue/src/main/assets/agents/agents.json").readText()).getJSONArray("agents")
    private fun health(body: String) = Agents.healthAgents(body)

    @Test fun theFourNamedAgentsAndEveryAgentOfTheSharedDescriptorAreListed() {
        assertTrue(listOf("Hermes", "Goose", "Claude Code", "Google AGY").all { n -> declared.any { it.label == n } })
        for (i in 0 until shared.length()) assertTrue(shared.getJSONObject(i).getString("id"), declared.any { it.id == shared.getJSONObject(i).getString("id") })
        assertTrue(declared.any { it.id == "openclaw" })
        declared.forEach { d -> listOf(d.icon, d.what, d.runsOn, d.models, d.healthKey).forEach { assertTrue(d.id, it.isNotBlank()) } }
    }

    @Test fun theListRendersNonEmptyWithTheGatewayDown() {
        val rows = Agents.rows(declared, emptyList())
        assertEquals(declared.size, rows.size)
        val states = rows.map { Agents.state(it, null).state }
        assertTrue(states.none { it == AgentState.CHECKING || it == AgentState.REACHABLE })
        assertEquals(AgentState.DOWN, Agents.state(rows.first { it.id == "hermes" }, null).state)
        assertEquals(AgentState.NOT_DEPLOYED, Agents.state(rows.first { it.id == "agy" }, null).state)
        assertNull(Agents.healthAgents(null))
        assertNull(Agents.healthAgents("not json"))
    }

    @Test fun liveStatusFollowsTheGatewayHealth() {
        val h = health("""{"agents":{"hermes":"hermes-agent","goose":false,"claude_cli":true,"openclaw":""}}""")
        fun of(id: String) = Agents.state(declared.first { it.id == id }, h)
        assertEquals(AgentLive(AgentState.REACHABLE, "hermes-agent"), of("hermes"))
        assertEquals(AgentState.DOWN, of("goose").state)
        assertEquals(AgentState.REACHABLE, of("claude").state)
        assertEquals(AgentState.DOWN, of("openclaw").state)
        assertEquals(AgentState.NOT_DEPLOYED, of("deepseek").state)   // not listed by the gateway
        assertEquals(AgentState.NOT_DEPLOYED, of("agy").state)        // not a gateway mode
    }

    @Test fun anAgentOnlyTheComposerOffersIsNotDropped() {
        val extra = AiAgent("openrouter", "OpenRouter", "any model")
        assertTrue(Agents.rows(declared, listOf(extra)).any { it.id == "openrouter" })
    }

    @Test fun theComposerListIsTheSameFileWithoutTheNonGatewayAgents() {
        val all = declared.map { AiAgent(it.id, it.label, "") }
        val offered = Agents.composer(all, declared).map { it.id }
        assertFalse("agy" in offered)
        assertEquals(declared.filter { it.gateway }.map { it.id }, offered)
        assertTrue(listOf("hermes", "goose", "claude", "openclaw").all { it in offered })
    }

    @Test fun theApiAccountsAreOpenRouterClaudeCodeAndGoogleAgyWithNoSecretInThem() {
        val raw = File("src/main/assets/ai/api-accounts.json").readText()
        val accounts = ApiAccounts.parse(raw)
        assertEquals(listOf("OpenRouter", "Claude Code", "Google AGY"), accounts.map { it.label })
        accounts.forEach { a -> listOf(a.provider, a.auth, a.keyRef).forEach { assertTrue(a.id, it.isNotBlank()) }; assertTrue(a.id, a.cost) }
        val shapes = listOf(
            Regex("sk-ant-[A-Za-z0-9_-]{8,}"), Regex("sk-or-[A-Za-z0-9_-]{8,}"), Regex("sk-[A-Za-z0-9]{20,}"), Regex("gh[pousr]_[A-Za-z0-9]{20,}"),
            Regex("AIza[0-9A-Za-z_-]{20,}"), Regex("Bearer\\s+[A-Za-z0-9._-]{16,}"), Regex("-----BEGIN [A-Z ]*PRIVATE KEY-----"), Regex("\"[A-Za-z0-9+/=_-]{32,}\""),
        )
        shapes.forEach { assertNull("$it", it.find(raw)); assertNull("$it", it.find(text)) }
    }

    @Test fun accountStatusFollowsTheGatewayAndAgyIsNotDeployed() {
        val accounts = ApiAccounts.parse(File("src/main/assets/ai/api-accounts.json").readText())
        val h = health("""{"agents":{"openrouter":true,"claude_cli":false}}""")
        assertEquals(AgentState.REACHABLE, ApiAccounts.state(accounts.first { it.id == "openrouter" }, h).state)
        assertEquals(AgentState.DOWN, ApiAccounts.state(accounts.first { it.id == "claude-code" }, h).state)
        assertEquals(AgentState.NOT_DEPLOYED, ApiAccounts.state(accounts.first { it.id == "google-agy" }, h).state)
        assertEquals(AgentState.DOWN, ApiAccounts.state(accounts.first { it.id == "openrouter" }, null).state)
    }

    @Test fun theApisPageDeclaresTheAccountsAndKeepsThePrivateRows() {
        val spec = AiData.pages(Data.ai("pages.json")).getValue("apis")
        assertEquals("ai/api-accounts.json", spec.accounts)
        assertEquals("ai/apis.json", spec.asset)
    }
}
