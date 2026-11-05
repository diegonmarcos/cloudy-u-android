package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1254 the depth commands: one clone per repository (renames folded in), the keep-depth gate, the wire verb and the declaration. */
class BulkDepthTest {
    private val renames = mapOf("ffront" to "front", "cloud-u-android" to "cloudy-u-android", "cloud" to "cloud___", "cloud___" to "cloudy-___")
    private val canonical: (String) -> String = { n -> var c = n; var i = 0; while (i++ < 10) c = renames[c] ?: break; c }
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Test fun `identity folds the renamed_from chain and reads https, ssh and scp urls alike`() {
        assertEquals("cloudy-u-android", BulkDepth.identity("https://github.com/diegonmarcos/cloud-u-android.git", canonical))
        assertEquals("cloudy-u-android", BulkDepth.identity("git@github.com:diegonmarcos/cloudy-u-android.git", canonical))
        assertEquals("cloudy-___", BulkDepth.identity("cloud", canonical))
        assertEquals("", BulkDepth.identity("", canonical))
    }

    @Test fun `a repository already cloned anywhere - under an old name or another folder - is never cloned again`() {
        val plan = BulkDepth.planClones(
            listOf(
                BulkDepth.Target("cloudy-u-android", "https://github.com/diegonmarcos/cloudy-u-android.git"),
                BulkDepth.Target("front", "https://github.com/diegonmarcos/front.git"),
                BulkDepth.Target("notes", "https://github.com/diegonmarcos/notes.git"),
            ),
            listOf(
                BulkDepth.Existing("/home/user/cloud-u-android", "https://github.com/diegonmarcos/cloud-u-android"),
                BulkDepth.Existing("/sdcard/git/my-front-copy", "git@github.com:diegonmarcos/ffront.git"),
            ),
            canonical,
        )
        assertEquals(listOf("notes"), plan.toClone.map { it.name })
        assertEquals("/home/user/cloud-u-android", plan.held["cloudy-u-android"])
        assertEquals("/sdcard/git/my-front-copy", plan.held["front"])
    }

    @Test fun `a folder with no origin counts by its folder name, and two candidates for one repository clone once`() {
        val plan = BulkDepth.planClones(
            listOf(BulkDepth.Target("a", "https://h/o/a.git"), BulkDepth.Target("ffront", "https://h/o/ffront.git"), BulkDepth.Target("front", "https://h/o/front.git")),
            listOf(BulkDepth.Existing("/x/a", "")),
            canonical,
        )
        assertEquals(listOf("ffront"), plan.toClone.map { it.name })
        assertTrue("a" in plan.held)
    }

    @Test fun `keep depth runs only on a clean, conflict-free, safe, tracked clone with nothing unpushed`() {
        assertNull(BulkDepth.keepSkip(true, 0, 0, 0, "origin/main", "SAFE"))
        assertNull("behind is fine: the shallow fetch brings it up to date", BulkDepth.keepSkip(true, 0, 0, 0, "origin/main", "SAFE"))
        assertEquals(BulkDepth.SKIP_NOT_CLONED, BulkDepth.keepSkip(false, 0, 0, 0, "origin/main", "SAFE"))
        assertTrue(BulkDepth.keepSkip(true, 3, 0, 0, "origin/main", "SAFE")!!.startsWith(BulkDepth.SKIP_DIRTY))
        assertTrue(BulkDepth.keepSkip(true, 0, 0, 2, "origin/main", "SAFE")!!.startsWith(BulkDepth.SKIP_AHEAD))
        assertEquals(BulkDepth.SKIP_NO_UPSTREAM, BulkDepth.keepSkip(true, 0, 0, 0, null, "SAFE"))
        assertTrue(BulkDepth.keepSkip(true, 0, 1, 0, "origin/main", "MERGING")!!.startsWith(BulkDepth.SKIP_CONFLICT))
        assertTrue(BulkDepth.keepSkip(true, 0, 0, 0, "origin/main", "REBASING")!!.startsWith(BulkDepth.SKIP_STATE))
    }

    @Test fun `the run reports skipped and failed first and names each repository's outcome`() {
        val r = BulkDepth.Run("keep_depth1_all", Declarations.DEPTH_KEEP, 3, 3, ok = mapOf("a" to "kept depth 1"), skipped = mapOf("b" to "dirty: 2 uncommitted"), failed = mapOf("c" to "x"), ended = true)
        // #1358 one line per repository: status, the literal name, then the reason (git's real line).
        assertEquals(listOf("skipped  b  dirty: 2 uncommitted", "failed  c  x", "ok  a  kept depth 1"), r.lines())
        assertEquals("skipped: dirty: 2 uncommitted", r.outcomeOf("b"))
        assertNull(r.outcomeOf("zzz"))
    }

    @Test fun `the keep_depth wire verb re-checks the gate, fetches the default branch shallow, resets, expires and gcs`() {
        val (plan, why) = TermuxGitWire.plan("keep_depth", "/store/git/r", JSONObject().put("depth", 1), null)
        assertNull(why)
        val s = plan!!.script
        for (t in listOf("status --porcelain", "skipped: uncommitted changes", "skipped: no upstream", "rev-list --count '@{u}..HEAD'", "skipped: unpushed commits",
                "fetch -q --depth 1", "checkout -q -B", "reflog expire --expire=now --all", "gc --prune=now")) assertTrue("script lacks $t", t in s)
        assertTrue("the gate comes before the fetch", s.indexOf("status --porcelain") < s.indexOf("fetch -q --depth"))
        assertTrue("never a clone", "clone" !in s)
        assertTrue("never a push", " push" !in s)
        assertTrue(plan.network)
        assertNull(TermuxGitWire.plan("keep_depth", "/d", JSONObject().put("depth", 0), null).first)
        assertNull(TermuxGitWire.plan("keep_depth", "/d", JSONObject().put("depth", 1).put("remote", "o\$x"), null).first)
        assertTrue("keep_depth" in TermuxGitWire.OPS)
    }

    @Test fun `the declared depth commands parse - both kinds in both scopes, depth 1, bounded workers`() {
        val page = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        val all = page.depthActions(Declarations.DEPTH_SCOPE_ALL)
        val one = page.depthActions(Declarations.DEPTH_SCOPE_REPO)
        assertEquals(Declarations.DEPTH_KINDS, all.map { it.kind }.toSet())
        assertEquals(Declarations.DEPTH_KINDS, one.map { it.kind }.toSet())
        assertNotNull(page.depthAction("clone_all_full")); assertNotNull(page.depthAction("keep_depth1_all"))
        assertTrue(page.depthActions.all { it.depth == 1 && it.workers in 1..4 })
        assertTrue("shrinking every clone asks first", all.first { it.kind == Declarations.DEPTH_KEEP }.confirm)
        assertTrue("no declared id collides with a RepoActions id", page.depthActions.none { a -> a.id in RepoActions.PAGE_IDS })
    }
}
