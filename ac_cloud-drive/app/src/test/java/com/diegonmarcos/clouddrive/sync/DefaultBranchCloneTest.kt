package com.diegonmarcos.clouddrive.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1731 a repo whose default branch is not main (master, ...) must clone: no script pins `--branch main`; an explicit branch still works. */
class DefaultBranchCloneTest {
    private val q: (String) -> String = { "'$it'" }

    @Test fun `the clone script names no branch so git takes the remote default`() {
        val s = StoreGit.cloneScript("https://h/o/r.git", "/g/r", null)
        assertFalse(s, s.contains("--branch"))
        assertTrue(s, s.contains("clone --single-branch 'https://h/o/r.git' '/g/r'"))
        assertTrue(StoreGit.cloneScript("u", "d", 3).contains("clone --single-branch --depth 3 'u' 'd'"))
    }

    @Test fun `an explicit per-repo branch still pins the clone`() {
        assertTrue(StoreGit.cloneScript("u", "d", null, "release").contains("clone --single-branch --branch 'release' 'u' 'd'"))
        assertEquals("clone --bare --single-branch --branch 'dev' 'u' 'd'", BareClones.cloneArgs("u", "d", 0, q, "dev"))
        assertEquals("clone --bare --single-branch 'u' 'd'", BareClones.cloneArgs("u", "d", 0, q))
    }

    @Test fun `checkout_main goes to the remote default branch first and falls back to main then master`() {
        val s = TermuxGitWire.plan("checkout_main", "/g/r", JSONObject(), null).first!!.script
        assertTrue(s, s.contains("symbolic-ref --short refs/remotes/origin/HEAD"))
        assertTrue(s, s.indexOf("symbolic-ref") < s.indexOf("checkout main") && s.indexOf("checkout main") < s.indexOf("checkout master"))
    }

    @Test fun `no Drive git source pins --branch main`() {
        val dir = listOf("src/main/java/com/diegonmarcos/clouddrive/sync", "app/src/main/java/com/diegonmarcos/clouddrive/sync", "ac_cloud-drive/app/src/main/java/com/diegonmarcos/clouddrive/sync")
            .map { java.io.File(it) }.first { it.isDirectory }
        val bad = dir.walkTopDown().filter { it.extension == "kt" }.filter { Regex("--branch main\\b").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertTrue("pinned --branch main in $bad", bad.isEmpty())
    }
}
