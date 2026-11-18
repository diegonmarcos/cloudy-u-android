package com.diegonmarcos.cgomniverse

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }

    private val entry = ui.getJSONObject("stacks").getJSONArray("voxelverse").getJSONObject(0)
    private val decl = Voxelverse.decl("https://github.com/diegonmarcos/cg-voxel-rust/tree/main/" + entry.getString("path"), entry.getString("path"),
        mapOf("goals" to "Goals", "architecture" to "Architecture", "risks" to "Risks", "milestones" to "Milestones"))

    @Test fun theDeclarationPointsAtThePlanInItsRepo() {
        // the stack entry names ONE source (repo + path, resolved through the SuperApp's repo_base); no address is typed in build.json::ui
        assertEquals("cg-voxel-rust", entry.getString("repo"))
        assertTrue(entry.getString("path").startsWith("docs/") && entry.getString("path").endsWith(".md"))
        assertFalse(ui.toString().contains("http"))
        assertEquals("https://github.com/diegonmarcos/cg-voxel-rust/raw/main/" + entry.getString("path"), decl.planUrl)
        assertEquals("https://github.com/diegonmarcos/cg-voxel-rust/commits/main.atom", decl.commitUrl)
        assertEquals("https://github.com/diegonmarcos/cg-voxel-rust/raw/main/Cargo.toml", decl.markerUrl)
        assertEquals("diegonmarcos/cg-voxel-rust", decl.repoName)
        assertEquals(listOf("Goals", "Architecture", "Risks"), decl.sections.map { it.title })
        assertEquals("Milestones", decl.roadmapTitle)
        // the page's words are string resources, one per key the page reads
        val res = file("src/main/res/values/strings.xml", "app/src/main/res/values/strings.xml").readText()
        for (k in listOf("plan", "viewer", "goals", "architecture", "risks", "milestones", "status", "live", "cached", "unavailable", "viewer_title", "viewer_body", "last_commit"))
            assertTrue("string vox_$k", res.contains("name=\"vox_$k\""))
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
        val atom = """<feed><title>Recent Commits</title><updated>2026-11-17T22:26:52Z</updated><entry>
            <id>tag:github.com,2008:Grit::Commit/085009c8e52acc1469f9cb26914c1a242122c165</id><updated>2026-11-17T22:26:51Z</updated>
            <title>
              first line &amp; more
            </title></entry><entry><id>tag:github.com,2008:Grit::Commit/1111111111</id><updated>2026-01-01T00:00:00Z</updated><title>old</title></entry></feed>"""
        assertEquals(VoxCommit("085009c", "2026-11-17", "first line & more"), Voxelverse.commit(atom))
        assertEquals(null, Voxelverse.commit("not a feed"))
    }
}
