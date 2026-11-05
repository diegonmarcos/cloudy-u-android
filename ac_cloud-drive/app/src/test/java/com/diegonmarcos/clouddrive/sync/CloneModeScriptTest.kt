package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1491 the clone modes run by the git binary in cld.termux (the filter modes, and any mode when the store needs it), and every wire verb working on a bare repo. */
class CloneModeScriptTest {
    private val git get() = Declarations.sync.git
    private fun script(id: String) = StoreGit.cloneModeScript(git.cloneMode(id)!!, "https://h/o/r.git", "/g/r.git")

    @Test fun `Bare Blobless and Bare Treeless are the git binary with the declared filter`() {
        assertTrue(script("bare_blobless").contains("git \"\$@\" clone --bare --filter=blob:none 'https://h/o/r.git' '/g/r.git'"))
        assertTrue(script("bare_treeless").contains("git \"\$@\" clone --bare --filter=tree:0 'https://h/o/r.git' '/g/r.git'"))
        assertFalse("a filter mode reads no default branch", script("bare_blobless").contains("ls-remote"))
    }

    @Test fun `Bare Main Only reads the default branch with the same credential then clones that branch`() {
        val s = script("bare_main")
        assertTrue(s.contains("ls-remote --symref 'https://h/o/r.git' HEAD"))
        assertTrue(s.contains("clone --bare --single-branch --branch \"\$B\" 'https://h/o/r.git' '/g/r.git'"))
        assertTrue("the credential travels on stdin, never in the script", s.contains("T=\"\$(cat)\"") && !s.contains("SECRET"))
        assertTrue("the default branch is read before the clone", s.indexOf("ls-remote") < s.indexOf("clone --bare"))
    }

    @Test fun `Full and Full Depth 3 carry their depth`() {
        assertTrue(script("full").contains("clone 'https://h/o/r.git' '/g/r.git'") && !script("full").contains("--depth"))
        assertTrue(script("depth3").contains("clone --depth 3 'https://h/o/r.git' '/g/r.git'"))
    }

    @Test fun `the wire clones a bare repo without tracking setup and every verb points git at a bare repo with --git-dir`() {
        val bare = TermuxGitWire.plan("clone", "/g/r.git", JSONObject().put("url", "https://h/r.git").put("bare", true).put("filter", "blob:none"), null).first!!.script
        assertTrue(bare.contains("clone --bare --filter=blob:none 'https://h/r.git' '/g/r.git'") && !bare.contains("rev-parse --abbrev-ref"))
        for (verb in listOf("fetch", "pull", "stash_list")) {
            val s = TermuxGitWire.plan(verb, "/g/r.git", JSONObject(), null).first!!.script
            assertTrue("$verb decides -C or --git-dir where the folder is", s.contains("echo -C") && s.contains("echo --git-dir") && s.contains("'/g/r.git/.git'"))
        }
    }
}
