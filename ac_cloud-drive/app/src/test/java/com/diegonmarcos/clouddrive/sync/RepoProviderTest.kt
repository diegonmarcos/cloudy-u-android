package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Provider column: the forge from the remote URL's host, through the DECLARED host map (build.json ui.sync.providers). */
class RepoProviderTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val decl: GitHostsDecl.ProvidersDecl by lazy {
        val b: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
        GitHostsDecl.parseProviders(b["ui"]!!.jsonObject["sync"]!!.toString())
    }
    private fun of(url: String) = RepoProvider.labelOf(url, decl)

    @Test fun `the declared map names GitHub, GitLab and the Cloud Gitea, and everything else is Other`() {
        assertEquals("GitHub", of("https://github.com/diegonmarcos/cloudy-u-android.git"))
        assertEquals("GitLab", of("https://gitlab.com/group/sub/project.git"))
        assertEquals("Gitea", of("https://git.diegonmarcos.com/diegonmarcos/x.git"))
        assertEquals("Gitea", of("http://10.0.0.6:3002/diegonmarcos/x.git"))
        assertEquals("Other", of("https://codeberg.org/someone/x.git"))
        assertEquals("Other", of("https://bitbucket.org/ws/x.git"))
    }

    @Test fun `every URL shape yields its host`() {
        assertEquals("github.com", RepoProvider.hostOf("git@github.com:diegonmarcos/x.git"))
        assertEquals("gitlab.com", RepoProvider.hostOf("ssh://git@gitlab.com:22/g/x.git"))
        assertEquals("github.com", RepoProvider.hostOf("https://user:secret@GitHub.com/o/r"))
        assertEquals("git.diegonmarcos.com", RepoProvider.hostOf("https://git.diegonmarcos.com:3000/o/r"))
        assertEquals("github.com", RepoProvider.hostOf("  github.com/o/r  "))
        assertEquals("", RepoProvider.hostOf(""))
    }

    @Test fun `a subdomain of a declared host belongs to it, a look-alike does not`() {
        assertEquals("GitHub", of("https://www.github.com/o/r"))
        assertEquals("Other", of("https://notgithub.com/o/r"))
        assertEquals("Other", of("https://github.com.evil.example/o/r"))
    }

    @Test fun `no URL is unknown, not Other`() {
        assertNull(of(""))
        assertNull(of("   "))
    }

    @Test fun `an id without a declared label prints as itself and an empty declaration makes everything Other`() {
        assertEquals("custom", RepoProvider.label("custom", mapOf("github" to "GitHub")))
        assertEquals("other", RepoProvider.idOf("https://github.com/o/r", emptyMap()))
        assertEquals("Other", RepoProvider.labelOf("https://github.com/o/r", GitHostsDecl.parseProviders("")).let { it?.replaceFirstChar(Char::uppercase) })
    }

    @Test fun `the column sorts by the printed word, unknown sinks`() {
        fun row(n: String, p: String?) = RepoTableRow(n, false, null, null, null, null, null, null, p)
        val rows = listOf(row("a", "Gitea"), row("b", null), row("c", "GitHub"), row("d", "GitLab"))
        assertEquals(listOf("a", "c", "d", "b"), RepoTable.sort(rows, RepoColumn.PROVIDER, true) { it }.map { it.name })
        assertEquals(listOf("d", "c", "a", "b"), RepoTable.sort(rows, RepoColumn.PROVIDER, false) { it }.map { it.name })
    }

    @Test fun `a row carries the provider it was given`() {
        assertEquals("GitHub", RepoTable.rowOf("r", false, false, null, null, "GitHub").provider)
        assertNull(RepoTable.rowOf("r", false, false, null, null).provider)
    }
}
