package com.diegonmarcos.superapp.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The shared composer: the design file, the declared controls and their order, the Permission
 * Modes, the MCP sentence, and the Task lock (a task greys Agent and Model and sets them). Plain JVM, over the real assets.
 */
class AiComposerTest {
    private val dir = File("src/main/assets/ai")
    private val design = AiDesign.parse(File(dir, "ai-design.json").readText())
    private val cfg = AiConfig.parse(File(dir, "ai.json").readText())

    @Test fun theBarKeepsItsOrderWhateverSubsetAnAppEnables() {
        assertEquals(listOf("plus", "task", "agent", "model", "effort", "mode", "mcp", "more"), design.controls.map { it.id })
        assertEquals("Perms", design.controls.first { it.id == "mode" }.label)
        assertEquals(listOf("task", "mcp"), design.ordered(listOf("mcp", "task", "nonsense")).map { it.id })
        assertEquals(listOf("agent", "model"), design.ordered(listOf("model", "agent")).map { it.id })
    }

    @Test fun everyMetricTheCodeReadsIsDeclared() {
        val used = listOf(
            "chat_font", "chat_line_height", "user_radius_top", "user_radius_tail", "user_pad_v", "user_pad_h", "assistant_width_pct",
            "list_gap", "list_pad_v", "meta_font", "hello_font", "composer_pad_top", "composer_pad_h", "composer_pad_bottom",
            "input_radius", "input_pad_v", "input_pad_h", "input_max_height", "row_gap", "send_size",
            "ctl_height", "ctl_radius", "ctl_font", "ctl_gap", "ctl_pad_h", "ctl_icon", "ctl_disabled_alpha", "bar_toggle_size", "compact_font",
        )
        for (k in used) assertTrue("metric $k", k in design.metricKeys)
        assertEquals("no metric is declared and unused", used.toSet(), design.metricKeys)
    }

    @Test fun permissionModesAreAskAutoEditReadOnlyPlanAndAskAddsNothing() {
        assertEquals(listOf("ask", "auto_edit", "read_only", "plan"), design.modes.map { it.id })
        assertEquals("ask", design.defaultMode)
        assertEquals("", design.mode("ask")!!.instruction)
        for (m in design.modes.filter { it.id != "ask" }) assertTrue(m.id, m.instruction.startsWith("Permission mode: "))
    }

    @Test fun mcpSentenceNamesTheChosenServersOrNothing() {
        assertEquals("", design.mcpSentence(emptySet()))
        assertEquals("MCP servers for this session: use only a, b when you call tools.", design.mcpSentence(setOf("b", "a")))
    }

    @Test fun anAppDeclarationNamesOnlyKnownControlsOnce() {
        val ok = AiComposerDecl.parse("""{"app":"x","controls":["task","agent","model","mode","mcp"],"default_task":"translate"}""", design)
        assertEquals("translate", ok.defaultTask)
        assertFalse(ok.taskRequired)
        assertTrue(AiComposerDecl.parse("""{"app":"x","controls":["task"],"task_required":true}""", design).taskRequired)
        for (bad in listOf("""{"app":"x","controls":["task","emoji"]}""", """{"app":"x","controls":["task","task"]}""")) {
            try { AiComposerDecl.parse(bad, design); fail(bad) } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun effortHasAnAutoDefaultThatSendsNothingAndFourLevelsThatDo() {
        assertEquals(listOf("auto", "low", "medium", "high", "max"), design.efforts.map { it.id })
        assertEquals("auto", design.defaultEffort)
        assertEquals("", design.effort("auto")!!.value)
        val req = AiRequest("m", listOf(AiMsg("user", "hi")), effort = "high")
        assertEquals("high", JSONObject(AiProtocol.body(cfg, req, "m", false, false)).getJSONObject("reasoning").getString("effort"))
        assertFalse(JSONObject(AiProtocol.body(cfg, AiRequest("m", listOf(AiMsg("user", "hi"))), "m", false, false)).has("reasoning"))
    }

    @Test fun theMoreSheetIsGroupedUnderHeadersAndCoversUsagePluginsSkillsAndTheRest() {
        val groups = design.moreGroups
        assertEquals(listOf("Usage & Cost", "Plugins - Optimisation", "Plugins - Skills", "Session", "System prompt", "Help"), groups.map { it.label })
        assertTrue(groups.all { it.items.isNotEmpty() })
        val items = groups.flatMap { it.items }
        assertEquals("ids are unique", items.size, items.map { it.id }.toSet().size)
        assertTrue(items.any { it.kind == "usage" })
        assertEquals(listOf("headroom", "rtk", "caveman", "principles"), groups[1].items.map { it.id })
        assertTrue(groups[1].items.all { it.kind == "plugin" && it.header.startsWith("x-") && it.plugin.isNotBlank() })
        assertTrue(groups[2].items.any { it.id == "skills" && it.kind == "prompt" && it.prompt.isNotBlank() })
        assertEquals(listOf("export", "retry", "undo"), groups[3].items.map { it.id })
        assertTrue(items.any { it.id == "shortcuts" && it.text.isNotBlank() } && items.any { it.id == "about" && it.text.isNotBlank() })
        assertTrue(items.all { it.kind in listOf("usage", "plugin", "prompt", "export", "retry", "undo", "system", "info") })
    }

    @Test fun theBarCollapsesToOneChipAndRemembersItPerApp() {
        assertFalse(design.collapsedByDefault)
        assertEquals("ai_composer_collapsed.cloud-search", design.collapseKey("cloud-search"))
        assertTrue(design.collapseKey("a") != design.collapseKey("b"))
        val chat = AiChatState(FakeHost())
        assertFalse(chat.collapsed)
        chat.collapsed = true
        assertTrue(chat.collapsed)
    }

    @Test fun thePlusMenuOffersFilesImageCameraAndNewChat() {
        assertEquals(listOf("files", "images", "camera"), design.attachKinds.map { it.first })
        assertEquals(listOf("Files", "Image", "Camera"), design.attachKinds.map { it.second })
        assertEquals("New chat", design.newChatLabel)
    }

    @Test fun exportRendersTheSessionAsMarkdown() {
        val md = AiExport.markdown(AiSession("s", "Plan", "m", 1, listOf(AiMsg("user", "hi"), AiMsg("assistant", "hello", "Hermes · m"))))
        assertEquals("# Plan\n\n**You**\n\nhi\n\n**Assistant** (Hermes · m)\n\nhello\n", md)
    }

    @Test fun undoDropsTheLastExchangeAndReturnsTheAsk() {
        val chat = AiChatState(FakeHost())
        chat.session = chat.session.copy(messages = listOf(AiMsg("user", "a"), AiMsg("assistant", "A"), AiMsg("user", "b"), AiMsg("assistant", "B")))
        assertEquals("b", chat.undoLast())
        assertEquals(listOf("a", "A"), chat.session.messages.map { it.content })
        assertEquals("a", chat.undoLast())
        assertEquals(null, chat.undoLast())
    }

    @Test fun usageAndPluginsParseFromTheBackendsOwnWords() {
        val u = AiProtocol.parseWhole(200, """{"choices":[{"message":{"content":"x"}}],"usage":{"prompt_tokens":12,"completion_tokens":5,"cost":0.0021}}""").usage!!
        assertEquals(AiUsage(12, 5, 0.0021), u)
        assertEquals(null, AiProtocol.parseWhole(200, """{"choices":[{"message":{"content":"x"}}]}""").usage)
        val sse = AiProtocol.Sse()
        sse.push("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}\n")
        sse.push("data: {\"choices\":[],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":4}}\n")
        assertEquals(AiUsage(3, 4, null), sse.usage)
        assertEquals(mapOf("headroom" to true, "rtk" to false), AiProtocol.parsePlugins("""{"plugins":{"headroom":true,"rtk":false}}"""))
        assertTrue(AiProtocol.parsePlugins("""{"modes":[]}""").isEmpty())
    }

    @Test fun reportedUsageIsTalliedPerSessionAndNothingIsInventedWhenNoneIsReported() {
        var reply = AiReply("ok")
        val gateway = object : AiBackend {
            override val id = "gateway"
            override fun models() = emptyList<AiModel>()
            override fun chat(req: AiRequest, onDelta: (String) -> Unit) = reply
        }
        val host = AiStandardHost(cfg, AiMemorySessions(), AiMemoryPrefs("m", false, "hermes"), gateway = gateway, agents = listOf(AiAgent("hermes", "Hermes", "sub")), design = design)
        val s = host.newSession("m", 1)
        host.send(s, "q", "m", false, 2, AiSink.None)
        assertTrue("no usage reported, none shown", host.usage(s.id).isEmpty())
        reply = AiReply("ok", usage = AiUsage(10, 20, 0.5))
        host.send(s, "q2", "m", false, 3, AiSink.None)
        val lines = host.usage(s.id)
        assertEquals(listOf("This session", "Today"), lines.map { it.label })
        assertEquals("10 in · 20 out · \$0.5000", lines[0].value)
    }

    @Test fun pluginTogglesRideTheGatewayRequestAsHeadersAndEffortAsReasoning() {
        var seen: AiRequest? = null
        val gateway = object : AiBackend {
            override val id = "gateway"
            override fun models() = emptyList<AiModel>()
            override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply { seen = req; return AiReply("ok") }
        }
        val host = AiStandardHost(cfg, AiMemorySessions(), AiMemoryPrefs("m", false, "hermes"), gateway = gateway, agents = listOf(AiAgent("hermes", "Hermes", "sub")), design = design)
        val chat = AiChatState(host)
        chat.setPlugin(design.moreGroups[1].items[0], true)
        chat.chooseEffort(design.effort("high")!!)
        host.send(host.newSession("m", 1), "q", "m", false, 2, AiSink.None)
        assertEquals(mapOf("x-headroom" to "on"), seen!!.headers)
        assertEquals("high", seen!!.effort)
    }

    @Test fun theSharedTaskMappingRunsEveryTaskOnAnOpenRouterModel() {
        val tasks = AiDesign.parseTasks(File(dir, "ai-tasks.json").readText())
        assertTrue(tasks.size >= 10)
        assertEquals(tasks.size, tasks.map { it.id }.toSet().size)
        for (t in tasks) {
            assertEquals(t.id, "openrouter", t.agent)
            assertTrue(t.id, '/' in t.model)
        }
        // The mapping's agent is one the design makes available to Compose.
        assertTrue(design.extraAgents.any { it.id == "openrouter" && it.mode == "openrouter" })
    }

    private class FakeHost : AiHost {
        override var model = "free/model"
        override var web = false
        var agent = ""
        var mode = ""
        var mcps: Set<String> = emptySet()
        override fun newSession(model: String, now: Long) = AiSession("s", "", model, now, emptyList())
        override fun models() = emptyList<AiModel>()
        override fun send(session: AiSession, text: String, model: String, web: Boolean, now: Long, sink: AiSink) = AiOutcome(session, null, emptyList())
        override fun agent() = agent
        override fun pickAgent(id: String) { agent = id }
        override fun pickMode(id: String) { mode = id }
        override fun pickMcps(names: Set<String>) { mcps = names }
    }


    private val tasks = listOf(AiTask("translate", "Translate", "openrouter", "mistralai/mistral-small-3.2-24b-instruct"), AiTask("summarize", "Summarize", "openrouter", "meta-llama/llama-3.1-8b-instruct"))

    @Test fun aSelectedTaskGreysAgentAndModelAndSetsThemAndClearingRestoresThePicks() {
        val host = FakeHost()
        val chat = AiChatState(host)
        chat.tasks = tasks
        chat.chooseAgent("hermes")
        chat.pick("anthropic/claude-opus-4")
        assertFalse(chat.locked)
        chat.chooseTask("translate")
        assertTrue(chat.locked)
        assertEquals("openrouter", chat.agent)
        assertEquals("mistralai/mistral-small-3.2-24b-instruct", chat.model)
        assertEquals("the host follows", "openrouter", host.agent)
        // Locked: the user cannot change them.
        chat.chooseAgent("goose"); chat.pick("x/y")
        assertEquals("openrouter", chat.agent)
        assertEquals("mistralai/mistral-small-3.2-24b-instruct", chat.model)
        // Another task re-maps; the picks from before any task are still remembered.
        chat.chooseTask("summarize")
        assertEquals("meta-llama/llama-3.1-8b-instruct", chat.model)
        chat.chooseTask("")
        assertFalse(chat.locked)
        assertEquals("hermes", chat.agent)
        assertEquals("anthropic/claude-opus-4", chat.model)
        assertEquals("hermes", host.agent)
        chat.pick("x/y")
        assertEquals("x/y", chat.model)
    }

    @Test fun anUnknownTaskIsNotATask() {
        val chat = AiChatState(FakeHost())
        chat.tasks = tasks
        chat.chooseTask("nope")
        assertFalse(chat.locked)
        assertEquals("", chat.task)
    }

    @Test fun modeAndMcpChoicesReachTheHost() {
        val host = FakeHost()
        val chat = AiChatState(host)
        chat.chooseMode("plan")
        assertEquals("plan", host.mode)
        chat.toggleMcp("fleet"); chat.toggleMcp("docs")
        assertEquals(setOf("fleet", "docs"), host.mcps)
        chat.toggleMcp("fleet")
        assertEquals(setOf("docs"), host.mcps)
        chat.chooseMcps(setOf("a"))
        assertEquals(setOf("a"), chat.mcpOn)
    }

    @Test fun theGatewayMcpListParses() {
        assertEquals(listOf(AiMcpServer("fleet", 12), AiMcpServer("docs", null)), AiProtocol.parseMcp("""[{"server":"fleet","count":12},{"server":"docs"},{"server":"fleet","count":1}]"""))
        assertEquals(listOf(AiMcpServer("x", 3)), AiProtocol.parseMcp("""{"servers":[{"name":"x","count":3}]}"""))
        assertTrue(AiProtocol.parseMcp("not json").isEmpty())
        assertTrue(AiProtocol.parseMcp("""{"error":"no"}""").isEmpty())
    }

    @Test fun theChosenModeAndMcpServersRideTheRequestAsTheStandingSystemMessage() {
        var seen: AiRequest? = null
        val gateway = object : AiBackend {
            override val id = "gateway"
            override fun models() = emptyList<AiModel>()
            override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply { seen = req; return AiReply("ok") }
        }
        val host = AiStandardHost(
            cfg, AiMemorySessions(), AiMemoryPrefs("m", false, "hermes"), gateway = gateway,
            agents = listOf(AiAgent("hermes", "Hermes", "sub")), system = "You are a persona.", design = design,
        )
        host.pickMode("plan")
        host.pickMcps(setOf("fleet"))
        val out = host.send(host.newSession("m", 1), "hello", "m", false, 2, AiSink.None)
        assertEquals(null, out.error)
        val system = seen!!.wire!!.getJSONObject(0)
        assertEquals("system", system.getString("role"))
        val text = system.getString("content")
        assertTrue(text.startsWith("You are a persona."))
        assertTrue(text.contains("Permission mode: plan."))
        assertTrue(text.contains("use only fleet when you call tools."))
        assertEquals("the answer carries its agent line", "Hermes · m", out.session.messages.last().meta)
        // Ask adds nothing and nothing else: no persona, no servers, no wire.
        val plain = AiStandardHost(cfg, AiMemorySessions(), AiMemoryPrefs("m", false, "hermes"), gateway = gateway, agents = listOf(AiAgent("hermes", "Hermes", "sub")), design = design)
        plain.pickMode("ask")
        plain.send(plain.newSession("m", 1), "hello", "m", false, 2, AiSink.None)
        assertEquals(null, seen!!.wire)
        assertNotNull(JSONObject(AiProtocol.body(cfg, seen!!, "m", false, false)).getJSONArray("messages"))
    }

    @Test fun controlsLiveOnTheInputRowAndTwoToolbarRows() {
        fun row(r: Int) = design.layout.filter { it.row == r }.map { it.id }
        assertEquals(listOf("plus"), row(0))
        assertEquals(listOf("task", "specialist", "agent", "model", "effort", "mode"), row(1))
        // Row 2: Plugins-Skills | KG-Store, KG-Cloud-apps | KG-Repos, KG-Cloud-infra, Plugins-Perf | More
        assertEquals(listOf("skills", "kg_store", "kg_apps", "kg_repos", "kg_infra", "perf", "more"), row(2))
        assertEquals(
            listOf("Plugins-Skills", "KG-Store", "KG-Cloud-apps", "KG-Repos", "KG-Cloud-infra", "Plugins-Perf", "More"),
            design.layout.filter { it.row == 2 }.map { it.label },
        )
        // Dividers: before Agent in row 1, then after Plugins-Skills, after KG-Cloud-apps, after Plugins-Perf in row 2.
        assertEquals(listOf("agent", "kg_store", "kg_repos", "more"), design.layout.filter { it.dividerBefore }.map { it.id })
        // Every prior control keeps its label and icon in the layout; the single MCP control became KG-Store / KG-Cloud-apps / KG-Cloud-infra.
        for (c in design.controls.filter { it.id != "mcp" }) assertEquals(c, design.layout.first { it.id == c.id }.copy(row = 1, dividerBefore = false))
        assertEquals(listOf("task", "specialist", "mode"), design.orderedLayout(listOf("mode", "specialist", "task", "mcp", "nonsense")).map { it.id })
        assertEquals(design.mcpBuckets.map { it.control }.sorted(), listOf("kg_apps", "kg_infra", "kg_store"))
        assertTrue(design.mcpBuckets.all { b -> design.layout.any { it.id == b.control } })
    }

    @Test fun pluginsAreSkillPluginsOrPerformancePlugins() {
        val items = design.moreGroups.flatMap { it.items }.associateBy { it.id }
        assertEquals("Cloudy Principals", items.getValue("principles").label)
        assertTrue(items.values.none { it.label == "Principles" })
        assertEquals(listOf("caveman", "principles", "skills", "memory"), pickerGroups(design, "skill").single().items.map { it.id })
        assertEquals(listOf("headroom", "rtk"), pickerGroups(design, "performance").single().items.map { it.id })
        assertEquals(mapOf("skill" to "skills", "performance" to "perf"), design.pluginClassControl)
        // The plugin's gateway key and header did not change with the rename.
        assertEquals("agents_principles", items.getValue("principles").plugin)
        assertEquals("x-principles", items.getValue("principles").header)
    }

    @Test fun everyMcpServerIsSortedIntoOneOfTheThreeHomes() {
        assertEquals(listOf("infra", "apps", "store"), design.mcpBuckets.map { it.id })
        fun b(n: String) = design.mcpBucket(n)!!.id
        for (n in listOf("cloudy-c3-mcp", "cloud-infra-mcp", "c3-infra-mcp")) assertEquals(n, "infra", b(n))
        for (n in listOf("cloudy-all-mcp", "cloudy-apps-communication-mcp", "cloudy-apps-drive-mcp", "cloudy-cgc-pub-mcp", "cloud-me-cgc-pvt-mcp", "cloudy-android-mcp",
            "cloud-superapp-mcp", "cloud-vault-mcp", "cloud-mattermost-mcp", "cloud-drive-mcp", "cloud-mail-mcp", "cloudy-kg-mcp")) assertEquals(n, "apps", b(n))
        for (n in listOf("google-personal-mcp", "google-workspace-mcp", "browser-mcp", "neo4j-graph")) assertEquals(n, "store", b(n))
        // Every declared known server lands in the bucket that lists it; infra has the one MCP there is so far.
        for (k in design.mcpBuckets) for (n in k.known) assertEquals(n, k.id, b(n))
        assertEquals(listOf("cloudy-c3-mcp"), design.mcpBuckets.first { it.id == "infra" }.known)
    }

    @Test fun kgStoreIsAFullTabbedPageDeclaredInTheDesign() {
        assertEquals(listOf("web", "releases", "workspace", "personal", "other"), design.kgStoreTabs.map { it.id })
        assertEquals(listOf("Web Search", "Store / Releases", "Google Workspace", "Google Personal", "Other internet MCPs"), design.kgStoreTabs.map { it.label })
        assertEquals(listOf("google-workspace-mcp"), design.kgStoreTabs.first { it.id == "workspace" }.items.map { it.id })
        assertEquals(listOf("google-personal-mcp"), design.kgStoreTabs.first { it.id == "personal" }.items.map { it.id })
        assertTrue(design.kgStoreTabs.first { it.id == "other" }.dynamic)
        assertTrue(design.kgStoreTabs.flatMap { it.items }.all { it.kind in listOf("web", "source", "mcp") })
        assertEquals("", design.sourcesSentence(emptySet()))
        assertEquals("Public sources to use for this session: fetch, news.", design.sourcesSentence(setOf("news", "fetch")))
        val chat = AiChatState(FakeHost())
        chat.toggleSource("fetch"); chat.toggleSource("news"); chat.toggleSource("fetch")
        assertEquals(setOf("news"), chat.sourcesOn)
    }

    @Test fun nothingOfTheMoreSheetIsLostByTheReorganisation() {
        val before = listOf("usage", "headroom", "rtk", "caveman", "principles", "skills", "memory", "export", "retry", "undo", "system", "shortcuts", "about")
        val items = design.moreGroups.flatMap { it.items }
        assertEquals(before.toSet(), items.map { it.id }.toSet())
        val inPickers = listOf("skill", "performance").flatMap { pickerGroups(design, it).single().items }.map { it.id }
        // Each item has exactly one home: Usage & Cost in the top bar, class items in their picker, the rest in More.
        for (item in items) {
            val home = when { item.kind == "usage" -> "topbar"; item.cls.isNotEmpty() -> "picker"; else -> "more" }
            assertEquals(item.id, home == "picker", item.id in inPickers)
        }
    }

    @Test fun exposedReposRideAsAStandingSentenceOrNothing() {
        assertEquals("", design.reposSentence(emptySet()))
        assertEquals("Repositories exposed to you for this session: a, b. Read code from these only.", design.reposSentence(setOf("b", "a")))
        val decl = AiComposerDecl.parse("""{"app":"x","controls":["kg_repos"],"repos":["r1","r2"]}""", design)
        assertEquals(listOf("r1", "r2"), decl.repos)
        val chat = AiChatState(FakeHost())
        chat.toggleRepo("r1"); chat.toggleRepo("r2"); chat.toggleRepo("r1")
        assertEquals(setOf("r2"), chat.reposOn)
    }

    @Test fun contextBreakdownIsCountedOrReportedNeverEstimated() {
        val file = AiAttachment("f1", "a.txt", "text/plain", "/x/a.txt", 10)
        val s = AiSession("s", "t", "m", 0, listOf(
            AiMsg("user", "hello", attachments = listOf(file)),
            AiMsg("assistant", "hi there", prompt = 100, completion = 20),
        ))
        val lines = AiContext.breakdown(s, 1000, "be brief", listOf(file)).associate { it.label to it.value }
        assertEquals("1,000 tokens", lines["Window"])
        assertEquals("120 tokens (12.0%)", lines["In context (reported)"])
        assertEquals("1 you · 1 assistant", lines["Messages"])
        assertEquals("8 characters", lines["System prompt"])
        assertEquals("1 · 10 bytes", lines["Files"])
        assertEquals(listOf(file), AiContext.files(s, listOf(file)))
        assertEquals("nothing reported yet", AiContext.breakdown(AiSession("s", "", "m", 0, emptyList()), 0, "").associate { it.label to it.value }["In context (reported)"])
    }
}
