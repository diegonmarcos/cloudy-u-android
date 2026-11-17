package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1784 the Voxelverse tab: nav parity with the three tabs it joined (Avatar, Galaxy, Store and the hidden About stay, in order, with
 * their pages), the tab is declared and drawn, and the plan document loads: the declaration points at the plan, the readers turn a
 * document of the plan's shape into goals, architecture, risks and milestones, and the repo status answers parse.
 */
class VoxelverseTest {
    private fun file(vararg names: String) = names.map { File(it) }.first { it.exists() }
    private val build = JSONObject(file("../build.json", "build.json").readText())
    private val ui = build.getJSONObject("ui")
    private fun strings(a: org.json.JSONArray) = (0 until a.length()).map { a.getString(it) }

    @Test fun theBarKeepsEveryFormerTabInOrderAndAddsVoxelverse() {
        assertEquals(listOf("avatar", "galaxy", "store", "voxelverse"), strings(ui.getJSONArray("bottom_nav")))
        assertEquals("avatar", ui.getString("default_section"))
    }

    @Test fun everyFormerSectionAndPageIsStillDeclared() {
        val secs = ui.getJSONArray("sections").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        val byId = secs.associateBy { it.getString("id") }
        for ((id, label) in listOf("avatar" to "Avatar", "galaxy" to "Galaxy", "store" to "Store", "about" to "About", "voxelverse" to "Voxelverse"))
            assertEquals("section $id", label, byId.getValue(id).getString("label"))
        val stacks = ui.getJSONObject("stacks")
        for ((id, kind) in listOf("avatar" to "avatar", "galaxy" to "galaxy", "store" to "store", "about" to "about", "voxelverse" to "voxelverse"))
            assertEquals("page $id", kind, stacks.getJSONArray(id).getJSONObject(0).getString("kind"))
        assertEquals(5, secs.size)
    }

    @Test fun theVoxelverseTabIsPresentAndDrawn() {
        assertEquals("voxelverse", Pages.KIND_VOXELVERSE)
        val shell = file("src/main/java/com/diegonmarcos/cgomniverse/ui/SectionPage.kt", "app/src/main/java/com/diegonmarcos/cgomniverse/ui/SectionPage.kt").readText()
        assertTrue(shell.contains("Pages.KIND_VOXELVERSE -> return VoxelversePage()"))
        val gradle = file("build.gradle", "app/build.gradle").readText()
        assertTrue(gradle.contains("VOXELVERSE_B64") && gradle.contains("data/voxelverse.json"))
    }

    private val decl = Voxelverse.parse(file("../data/voxelverse.json", "data/voxelverse.json").readText())

    @Test fun theDeclarationPointsAtThePlanInItsRepo() {
        assertEquals(listOf("plan", "viewer"), decl.subpages.map { it.id })
        assertEquals("https://raw.githubusercontent.com/diegonmarcos/cg-voxel-rust/main/docs/vox-rust_Agent-Driven_Voxel_World_Full_Build_Plan.md", decl.planUrl)
        assertTrue(decl.commitUrl.startsWith("https://api.github.com/repos/diegonmarcos/cg-voxel-rust/commits"))
        assertEquals(listOf("Goals", "Architecture", "Risks"), decl.sections.map { it.title })
        assertEquals("Milestones", decl.roadmapTitle)
        assertTrue(decl.artifact == null)
        for (k in listOf("loading", "live", "cached", "unavailable", "viewer_title", "last_commit")) assertTrue("text $k", decl.t(k).isNotBlank())
    }

    /** The plan's shape (headings, bullets, numbered phases with a Gate), not its words: the real text is read at run time. */
    private val md = """
        # Title

        ## Goals and non-goals

        Intro **line**.

        **Goals**

        - Dedicated [server](https://x.example) in a VM.
        - Bots are `first-class` players.

        ## Architecture

        &#91;embedded content: x\]

        Humans and bots are both clients of one server.

        ## Roadmap

        Ten phases.

        1. **Phase 0, bootstrap (1 week)** — workspace, CI. Gate: server builds.
        2. **Phase 1, core world (3 weeks)** — `WorldGrid` and persistence. Gate: a world loads.

        ## Risks, open questions, rejected options

        | a | b |
        | - | - |
        - Lua API is the long tail
    """.trimIndent()

    @Test fun thePlanDocumentLoadsIntoItsBlocks() {
        val blocks = Voxelverse.plan(decl, md)
        assertEquals(listOf("Goals", "Architecture", "Risks"), blocks.map { it.title })
        assertEquals(listOf("Dedicated server in a VM.", "Bots are first-class players."), blocks[0].bullets)
        assertEquals(listOf("Humans and bots are both clients of one server."), blocks[1].paragraphs)
        assertEquals(listOf("Lua API is the long tail"), blocks[2].bullets)
        val ph = Voxelverse.phases(decl, md)
        assertEquals(2, ph.size)
        assertEquals("Phase 0, bootstrap (1 week)", ph[0].title)
        assertEquals("workspace, CI", ph[0].summary)
        assertEquals("server builds.", ph[0].gate)
        assertEquals("WorldGrid and persistence", ph[1].summary)
    }

    @Test fun aDocumentWithoutTheHeadingsGivesNothingNotACrash() {
        assertTrue(Voxelverse.plan(decl, "no headings at all").isEmpty())
        assertTrue(Voxelverse.phases(decl, "## Other\n1. **x** y").isEmpty())
        assertTrue(Voxelverse.plan(decl, "").isEmpty())
    }

    @Test fun theRepoStatusAnswersParse() {
        val c = Voxelverse.commit("""[{"sha":"085009c8e52acc","commit":{"author":{"date":"2026-11-17T22:26:51Z"},"message":"first line\n\nbody"}}]""")
        assertEquals(VoxCommit("085009c", "2026-11-17", "first line"), c)
        assertEquals(null, Voxelverse.commit("not json"))
        assertEquals(listOf(VoxMilestone("M1", 2, 1)), Voxelverse.milestones("""[{"title":"M1","open_issues":2,"closed_issues":1}]"""))
        assertTrue(Voxelverse.milestones("[]").isEmpty())
        assertTrue(Voxelverse.hasEngine("""[{"name":"Cargo.toml"},{"name":"docs"}]""", decl.engineMarker))
        assertFalse(Voxelverse.hasEngine("""[{"name":"docs"}]""", decl.engineMarker))
        assertNotNull(decl.engineMarker.takeIf { it.isNotEmpty() })
    }
}
