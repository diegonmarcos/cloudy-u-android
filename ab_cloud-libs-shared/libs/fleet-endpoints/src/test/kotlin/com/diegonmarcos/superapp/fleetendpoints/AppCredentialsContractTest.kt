package com.diegonmarcos.superapp.fleetendpoints

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppCredentialsContractTest {
    @Test fun `a catalogue id and a prefixed id resolve, anything else is refused`() {
        assertNotNull(AppCredentialsContract.entry("git.github_token"))
        assertNotNull(AppCredentialsContract.entry("git.host.git.example.org"))
        assertNull("the bare prefix names no host", AppCredentialsContract.entry("git.host."))
        assertNull(AppCredentialsContract.entry("nope"))
        assertNull(AppCredentialsContract.entry(null))
        assertNull(AppCredentialsContract.entry("git.github_token/../x"))
        assertNull(AppCredentialsContract.entry("git..x"))
        assertNull(AppCredentialsContract.entry("a".repeat(97)))
    }

    @Test fun `the grant is per credential and a prefix entry is one pattern`() {
        assertEquals("cred.git.github_token", AppCredentialsContract.grant("git.github_token"))
        assertEquals("cred.git.host.*", AppCredentialsContract.grantPattern(AppCredentialsContract.entry("git.host.x")!!))
        assertTrue(AppCredentialsContract.defaultGrants(AppCredentialsContract.PKG_DRIVE).contains("cred.git.host.*"))
        assertTrue(AppCredentialsContract.defaultGrants("com.example.other").isEmpty())
    }

    @Test fun `a host name with dots files under one flat key`() {
        assertEquals("appcred.git_host_git_example_org", AppCredentialsContract.vaultPath("git.host.git.example.org"))
        assertFalse(AppCredentialsContract.vaultPath("git.host.a.b").drop("appcred.".length).contains('.'))
    }

    @Test fun `a repository name is made safe for an id`() {
        assertEquals("my_repo_1", AppCredentialsContract.slug("my repo/1"))
        assertEquals(60, AppCredentialsContract.slug("x".repeat(90)).length)
        assertNotNull(AppCredentialsContract.entry("git.repo." + AppCredentialsContract.slug("a b/c")))
        assertNotNull(AppCredentialsContract.entry("git.ssh." + AppCredentialsContract.slug("k")))
    }

    @Test fun `no log form carries the value`() {
        val c = AppCredentialsContract.Credential("git.github_token", "ghp_SECRET", "from Cloud Account")
        assertFalse(c.toString().contains("ghp_SECRET"))
        assertTrue(c.toString().contains("<redacted>"))
        assertFalse(AppCredentialsContract.Credential("x", "", "l").present)
    }

    @Test fun `a refusal names the reason`() {
        assertTrue(AppCredentialsContract.refusal("no grant for x").contains("no grant for x"))
        assertTrue(AppCredentialsContract.refusal(null).contains("no reason given"))
        assertTrue(AppCredentialsContract.missing("GitHub token").contains("App credentials"))
    }

    @Test fun `the page the sign-in link opens exists for the provider`() {
        assertEquals("signin", SignInContract.openIntentExtras(AppCredentialsContract.PROVIDER)["page"])
    }
}
