package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #608 the Sync ▸ Git page's own logic, executed: the three reads of a clone against REAL
 * directories, the listing parser against a recorded provider body, and the page's
 * declaration parsed by the EXACT parser the phone runs against THIS repository's
 * build.json. A hook counted from git's own samples, a size walk that follows a symlink
 * back up the tree, a public/private split that is not the provider's flag, or a public
 * set that drifted out of its own rule all fail here, before they fail on a device.
 */
class GitPageTest {

    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val buildJson: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
    private val page: Declarations.GitPageDecl by lazy {
        Declarations.parseGitPage(buildJson["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
    }

    private lateinit var tmp: File

    @Before fun setUp() { tmp = Files.createTempDirectory("git-page-test").toFile() }
    @After fun tearDown() { tmp.deleteRecursively() }

    private fun write(path: String, text: String): File {
        val f = File(tmp, path); f.parentFile.mkdirs(); f.writeText(text); return f
    }

    @Test fun hooksSkipTheSamples() {
        assertEquals(emptyList<String>(), GitRepoScan.hooks(tmp))
        write(".git/hooks/pre-commit.sample", "#!/bin/sh\n")
        write(".git/hooks/pre-push.sample", "#!/bin/sh\n")
        assertEquals("a fresh clone's samples are NOT installed hooks", emptyList<String>(), GitRepoScan.hooks(tmp))
        write(".git/hooks/pre-commit", "#!/bin/sh\nexit 0\n")
        write(".git/hooks/commit-msg", "#!/bin/sh\nexit 0\n")
        assertEquals(listOf("commit-msg", "pre-commit"), GitRepoScan.hooks(tmp))
    }

    @Test fun workflowsAreTheYamlOnes() {
        assertEquals(emptyList<String>(), GitRepoScan.workflows(tmp))
        write(".github/workflows/ship.yml", "on: push\n")
        write(".github/workflows/guard.yaml", "on: push\n")
        write(".github/workflows/notes.md", "not a workflow\n")
        write(".github/ISSUE_TEMPLATE/bug.yml", "not a workflow either\n")
        assertEquals(listOf("guard.yaml", "ship.yml"), GitRepoScan.workflows(tmp))
    }

    @Test fun sizeCountsTheTreeAndTheGitDirSeparately() {
        write("a.txt", "0123456789")             // 10 bytes, worktree
        write("src/b.txt", "01234")              // 5 bytes, worktree
        write(".git/objects/pack/p.pack", "0123456789012345") // 16 bytes, history
        val s = GitRepoScan.size(tmp)
        assertEquals(31L, s.bytes)
        assertEquals(16L, s.gitBytes)
        assertEquals(3, s.files)
        // src, .git, .git/objects, .git/objects/pack
        assertEquals(4, s.folders)
        // A symlink pointing back at the root is counted, never descended into: a walk that
        // followed it would not terminate, so reaching this assertion IS the proof.
        runCatching { Files.createSymbolicLink(File(tmp, "loop").toPath(), tmp.toPath()) }.onSuccess {
            val looped = GitRepoScan.size(tmp)
            assertEquals(31L, looped.bytes)
            assertEquals(5, looped.folders)
        }
        assertEquals("10 B", GitRepoScan.humanBytes(10))
        assertTrue(GitRepoScan.humanBytes(1024L * 1024L).startsWith("1.0 MB"))
    }

    @Test fun listingSplitsOnTheProvidersOwnFlag() {
        val body = """
            [
              {"name":"zeta","private":false,"fork":false,"default_branch":"main","owner":{"login":"o"},
               "clone_url":"https://example.invalid/o/zeta.git","ssh_url":"git@example.invalid:o/zeta.git",
               "html_url":"https://example.invalid/o/zeta","size":12,"stargazers_count":3,"language":"Kotlin"},
              {"name":"alpha","private":true,"fork":false,"default_branch":"main","owner":{"login":"o"}},
              {"name":"beta","private":false,"fork":true,"default_branch":"trunk","owner":{"login":"o"}},
              {"private":false,"owner":{"login":"o"}}
            ]
        """.trimIndent()
        val parsed = GitHubRepos.parse(body)
        // The nameless entry is dropped, not rendered as an empty row.
        assertEquals(listOf("zeta", "alpha", "beta"), parsed.map { it.name })
        val pub = GitHubRepos.group(parsed, wantPrivate = false)
        val priv = GitHubRepos.group(parsed, wantPrivate = true)
        assertEquals(listOf("beta", "zeta"), pub.map { it.name })   // alphabetical, not response order
        assertEquals(listOf("alpha"), priv.map { it.name })
        assertTrue(pub.single { it.name == "beta" }.fork)
        assertEquals("o", parsed.first().owner)
        assertEquals("Kotlin", parsed.first().language)
        assertEquals(12L, parsed.first().sizeKb)
        // An error DOCUMENT is an object, not a list: it must yield nothing rather than a row.
        assertEquals(emptyList<GitHubRepos.Repo>(), GitHubRepos.parse("""{"message":"Bad credentials"}"""))
        assertEquals(emptyList<GitHubRepos.Repo>(), GitHubRepos.parse("not json at all"))
        // No token, no request: the failure is reported instead of a GET that cannot succeed.
        assertTrue(GitHubRepos.fetch(page.api, "").isFailure)
    }

    @Test fun declaredPublicSetMatchesItsOwnRule() {
        assertTrue(page.publicRepos.isNotEmpty())
        assertEquals(listOf("public", "personal"), page.sections.map { it.id })
        assertEquals(listOf("public", "private"), page.personalGroups.map { it.id })
        assertEquals(14, page.ops.size)
        assertEquals(listOf("force_push", "force_pull"), page.ops.filter { it.destructive }.map { it.id })
        // Every declared repository is in a declared name family, and the rule is the page's own.
        val names = page.publicRepos.map { it.name }
        assertTrue("every public repository is in a declared name family", names.all { page.inNameFamilies(it) })
        assertFalse("the rule is a rule, not a rubber stamp", page.inNameFamilies("back-Algo"))
        assertEquals("alphabetical by name", names.sorted(), names)
        assertTrue("#608 names this repository explicitly", "cloudy-u-android" in names)
        // The seeded set of the shared store is a SUBSET of the page's public set: a repository
        // the store clones on first run must be one the page can show.
        val seeded = Declarations.parseGitFamily(File(root, "data/drive-git-repos.json").readText()).repos.filter { it.seed }
        assertTrue(seeded.isNotEmpty())
        assertTrue("seeded repositories absent from the public set: " + seeded.map { it.name }.filterNot { it in names },
            seeded.all { it.name in names })
        // #641 the ONLY declared way in is the user's own SSH key, and it cannot list an account:
        // the HTTPS credential comes from the vault import, never from a browser login. A `webauth`
        // way back in the declaration turns this red.
        assertEquals(listOf(Declarations.KIND_SSH_KEY), page.loginWays.map { it.kind })
        assertFalse("no browser login may be declared for this page", page.loginWays.any { it.kind == "webauth" })
        assertTrue("the ssh way is declared", page.sshWay != null)
        assertFalse(page.sshWay?.lists == true)
        assertTrue(page.historyMax >= 1)
    }

    @Test fun remoteModesComposeTheDeclaredUrls() {
        val owner = page.owner
        assertTrue(owner.isNotBlank())
        val https = page.remoteMode(Declarations.REMOTE_HTTPS)!!
        val ssh = page.remoteMode(Declarations.REMOTE_SSH)!!
        val readonly = page.remoteMode(Declarations.REMOTE_READONLY)!!
        val name = "cloudy-u-android"
        assertEquals(https.urlFor(owner, name), page.cloneUrl(name))
        assertTrue(page.cloneUrl(name).startsWith("https://"))
        assertTrue(page.cloneUrl(name).endsWith("/$owner/$name.git"))
        assertTrue(page.cloneUrl(name, Declarations.REMOTE_SSH).startsWith("git@"))
        assertTrue(page.cloneUrl(name, Declarations.REMOTE_SSH).endsWith(":$owner/$name.git"))
        assertEquals(ssh.urlFor(owner, name), page.cloneUrl(name, Declarations.REMOTE_SSH))
        assertTrue("the read-only mode is marked, so the page can refuse push on it", readonly.readOnly)
        assertFalse(https.readOnly)
        assertFalse(ssh.readOnly)
        // No placeholder survives the substitution — a leftover {name} would be cloned literally.
        page.remoteModes.forEach { m ->
            assertFalse(m.label, m.urlFor(owner, name).contains("{"))
        }
        assertEquals("", page.cloneUrl(name, "no-such-mode"))
        // The web link and the paged list route are the declaration's, substituted once.
        assertTrue(page.webUrl(name).endsWith("/$owner/$name"))
        assertTrue(page.api.reposUrl(1).endsWith("page=1"))
        assertTrue(page.api.reposUrl(2).contains("&page=2") || page.api.reposUrl(2).contains("?page=2"))
        assertTrue(page.api.baseUrl.startsWith("https://"))
        // A blank declaration is the EMPTY page, not an invented endpoint.
        assertTrue(Declarations.parseGitPage(null).ops.isEmpty())
        assertEquals("", Declarations.parseGitPage(null).cloneUrl(name))
    }
}
