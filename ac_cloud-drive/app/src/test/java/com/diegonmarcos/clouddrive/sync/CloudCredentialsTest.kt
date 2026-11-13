package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.superapp.fleetendpoints.AppCredentialsContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1693 the ids Drive asks Cloud Account for are catalogue ids Cloud Account grants Drive by default. */
class CloudCredentialsTest {
    @Test fun `every id Drive asks for is a catalogue id`() {
        for (id in listOf(CloudCredentials.hostId("gitea"), CloudCredentials.repoTokenId("My Repo/1"), CloudCredentials.sshPassphraseId("a.b"))) {
            assertNotNull(id, AppCredentialsContract.entry(id))
        }
    }

    @Test fun `ids are flat and stable`() {
        assertEquals("git.host.gitea", CloudCredentials.hostId("gitea"))
        assertEquals("git.repo.My_Repo_1", CloudCredentials.repoTokenId("My Repo/1"))
        assertEquals("git.ssh.a_b", CloudCredentials.sshPassphraseId("a.b"))
    }

    @Test fun `Drive is granted each of them by default`() {
        val g = AppCredentialsContract.defaultGrants(AppCredentialsContract.PKG_DRIVE)
        assertTrue(g.containsAll(listOf("cred.git.host.*", "cred.git.repo.*", "cred.git.ssh.*", "cred.git.github_token")))
    }
}
