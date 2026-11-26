package com.diegonmarcos.cloudc3

import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithText
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.cloudc3.pages.OrchGhcrScreen
import com.diegonmarcos.cloudc3.pages.OrchRegistries
import com.diegonmarcos.cloudc3.pages.OrchReleasesScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** #1942 Orch > GH Releases and GHCR tables over a FAKE feed: lazy per-repo load, the four columns, the rate-limit note, a private package. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w2000dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OrchRegistriesComposeTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private val palette = KitPalette(Color.Black, Color.DarkGray, Color.White, Color.LightGray, Color.Cyan, Color.Gray, Color.Black)
    private val relCalls = mutableListOf<String>()
    private val ghcrCalls = mutableListOf<String>()

    private val fake = object : OrchRegistries.Source {
        override suspend fun releases(repo: String): GitHubFeed.Feed<GitHubFeed.Release> {
            relCalls += repo
            return if (repo == "limited") GitHubFeed.Feed(emptyList(), GitHubFeed.Status.RATE_LIMITED)
            else GitHubFeed.Feed(
                listOf(GitHubFeed.Release("v1.2", listOf(GitHubFeed.Release.Asset("app.apk", 2_097_152, 0), GitHubFeed.Release.Asset("app.sha256", 1024, 0)), 1_700_000_000_000, "")),
                GitHubFeed.Status.OK,
            )
        }

        override suspend fun ghcr(pkg: OrchRegistries.Pkg): OrchRegistries.GhcrRow {
            ghcrCalls += pkg.name
            return if (pkg.name == "secret-img") OrchRegistries.GhcrRow(pkg.name, emptyList(), "private", "-")
            else OrchRegistries.GhcrRow(pkg.name, listOf("latest", "1.0"), "public", "2026-11-20")
        }
    }

    @Test
    fun `release table loads lazily per repo and shows tag, assets, size and updated`() {
        compose.setContent { CloudKitTheme(palette) { OrchReleasesScreen(listOf("alpha", "limited"), fake) } }
        compose.waitForIdle()
        assertEquals("nothing is fetched before a repo is opened (60/h anonymous quota)", emptyList<String>(), relCalls)
        compose.onNodeWithTag(OrchRegistries.Tags.repo("alpha")).performClick()
        compose.waitForIdle()
        assertEquals(listOf("alpha"), relCalls)
        compose.onNodeWithTag(OrchRegistries.Tags.release("alpha", "v1.2")).assertExists()
        compose.onNodeWithText("2: app.apk, app.sha256").assertExists()
        compose.onNodeWithText("2.0 MB").assertExists()
        compose.onNodeWithText("2023-11-14").assertExists()
        compose.onNodeWithText("Tag").assertExists(); compose.onNodeWithText("Assets").assertExists(); compose.onNodeWithText("Size").assertExists(); compose.onNodeWithText("Updated").assertExists()
        compose.onNodeWithTag(OrchRegistries.Tags.repo("limited")).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(OrchRegistries.Tags.note("limited")).assertTextContains("rate limited", substring = true)
        assertEquals("each repo is fetched once", listOf("alpha", "limited"), relCalls)
    }

    @Test
    fun `GHCR table groups by repo, loads on open, and a private package shows private with no tags`() {
        val pk = listOf(OrchRegistries.Pkg("o", "web", ""), OrchRegistries.Pkg("o", "secret-img", ""), OrchRegistries.Pkg("o", "cgc-db-alpha", "alpha"))
        compose.setContent { CloudKitTheme(palette) { OrchGhcrScreen(pk, fake) } }
        compose.waitForIdle()
        assertTrue("no package is read before its group is opened", ghcrCalls.isEmpty())
        compose.onNodeWithTag(OrchRegistries.Tags.group(OrchRegistries.FLEET)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("web", "secret-img"), ghcrCalls)
        compose.onNodeWithText("latest, 1.0").assertExists()
        compose.onNodeWithText("2026-11-20").assertExists()
        compose.onAllNodesWithText("private").let { assertEquals(1, it.fetchSemanticsNodes().size) }
        compose.onNodeWithText("Package").assertExists(); compose.onNodeWithText("Tags").assertExists(); compose.onNodeWithText("Visibility").assertExists()
        compose.onNodeWithTag(OrchRegistries.Tags.group("alpha")).assertExists()
    }

    @Test
    fun `GHCR packages derive from the declared container images plus one cgc-db package per repo`() {
        val pk = OrchRegistries.packages(
            listOf("ghcr.io/o/alerts-api", "ghcr.io/o/web:latest", "ghcr.io/o/web:1", "ghcr.io/o/caddy-l4@sha256:abc", "docker.io/x/y:1", ""),
            listOf("alpha"), owner = "o",
        )
        assertEquals(listOf("alerts-api", "web", "caddy-l4", "cgc-db-alpha"), pk.map { it.name })
        assertEquals("alpha", pk.last().repo)
        val real = OrchRegistries.packages(com.diegonmarcos.cloudc3.pages.ContainersSource.current().entries.map { it.image }, Declarations.containerProjects.repos.map { it.name })
        assertTrue(real.any { it.name == "alerts-api" })
        assertEquals(Declarations.containerProjects.repos.size, real.count { it.repo.isNotBlank() })
    }
}
