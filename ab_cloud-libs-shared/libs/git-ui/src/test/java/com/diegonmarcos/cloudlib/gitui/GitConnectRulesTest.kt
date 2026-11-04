package com.diegonmarcos.cloudlib.gitui

import com.diegonmarcos.cloudlib.gitsync.GitAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1445 GitConnect's pure rules. The repo and file names are FAKE fixtures; no real value is used. */
class GitConnectRulesTest {
    private val spec = GitConnectSpec("fake", "Fake", "example-owner", "example-repo", "main", listOf("profile/fake.json"))

    @Test fun urlAndCredentialId() {
        assertEquals("https://github.com/example-owner/example-repo.git", GitConnectRules.url(spec))
        assertEquals("gitconnect:example-owner/example-repo", GitConnectRules.credentialId(spec))
    }

    @Test fun authNeedsAToken() {
        assertEquals(GitAuth.None, GitConnectRules.auth(null))
        assertEquals(GitAuth.None, GitConnectRules.auth(" "))
        assertTrue(GitConnectRules.auth("fake-token") is GitAuth.Https)
    }

    @Test fun pathsStayInsideTheClone() {
        assertTrue(GitConnectRules.safePath("profile/fake.json"))
        for (bad in listOf("", "/etc/x", "../x", "a/../b", "a//b")) assertFalse(bad, GitConnectRules.safePath(bad))
    }

    @Test fun messageIsTheAutoCommitRule() {
        val diff = "diff --git a/profile/fake.json b/profile/fake.json\n--- a/profile/fake.json\n+++ b/profile/fake.json\n@@ -1 +1 @@\n-a\n+b\n"
        val m = GitConnectRules.message("profile/fake.json", diff) { 10L }
        assertTrue(m, m.startsWith("auto: 1 file (+1 −1, 10 B) in 1 folder"))
        assertEquals("profile: update x.json", GitConnectRules.message("x.json", "") { 0L })
    }

    @Test fun accessStateNamesReadWrite() {
        assertEquals("read + write", GitConnectRules.access(true))
        assertTrue(GitConnectRules.access(false).startsWith("read only"))
    }
}
