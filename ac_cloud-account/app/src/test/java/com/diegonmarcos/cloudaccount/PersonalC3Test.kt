package com.diegonmarcos.cloudaccount

import android.app.Application
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.configs.ComposeDecl
import com.diegonmarcos.cloudaccount.configs.ContainerRow
import com.diegonmarcos.cloudaccount.configs.ContainersPage
import com.diegonmarcos.cloudaccount.configs.TfDecl
import com.diegonmarcos.cloudaccount.configs.TreeNode
import com.diegonmarcos.cloudaccount.configs.containerLines
import com.diegonmarcos.cloudaccount.hub.DeviceAuth
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.personal.CardMask
import com.diegonmarcos.cloudaccount.personal.PersonalEntriesPage
import com.diegonmarcos.cloudaccount.personal.PersonalEntry
import com.diegonmarcos.cloudaccount.personal.PersonalKind
import com.diegonmarcos.cloudaccount.personal.PersonalRepo
import com.diegonmarcos.cloudaccount.personal.PersonalStore
import com.diegonmarcos.cloudaccount.personal.PersonalTags
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #1438 the runtime half of test-account-personal-c3.sh: the nav structure as the app bakes it, C3 secret values never
 * reaching a string or the screen, card masking with reveal only after device auth, and the encrypted store. Every
 * secret-looking value is built from fragments at run time; all are obvious fakes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PersonalC3Test {
    private val compose = createAndroidComposeRule<androidx.activity.ComponentActivity>()

    // Robolectric resolves the host activity only once it is registered (as SectionScreensTest does).
    @get:Rule
    val rules: org.junit.rules.RuleChain = org.junit.rules.RuleChain.outerRule(org.junit.rules.TestRule { base, _ ->
        object : org.junit.runners.model.Statement() {
            override fun evaluate() {
                val app = org.robolectric.RuntimeEnvironment.getApplication()
                org.robolectric.Shadows.shadowOf(app.packageManager)
                    .addActivityIfNotPresent(android.content.ComponentName(app, androidx.activity.ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun fakeSecret() = "s3" + "cr" + "et-" + "v".repeat(12)
    private fun fakeCard() = "4000" + " 0000 " + "0000 " + "0002"

    /** ui-kit composables need CloudKitTheme (as in the app and SectionScreensTest). */
    @androidx.compose.runtime.Composable
    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) =
        com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.profile.AccountHost.palette(compose.activity)) { content() }

    @After fun reset() { PersonalStore.testPrefs = null }

    // ── nav structure ────────────────────────────────────────────────

    @Test fun navIsTheDeclaredStructure() {
        val nav = MainActivity.NAV
        fun tabs(s: String) = nav.section(s)!!.pages.map { it.id }
        fun sub(s: String, p: String) = nav.section(s)!!.page(p)!!.pages.map { it.id }
        assertEquals(listOf("ids", "cards", "others", "autocomplete"), tabs("personal"))
        assertEquals(listOf("IDs", "Pay Cards", "Others", "Autocomplete"), nav.section("personal")!!.pages.map { it.label })
        assertEquals(listOf("entries", "me"), sub("personal", "others"))
        assertEquals(listOf("android", "desktop", "c3"), tabs("configs"))
        assertEquals(listOf("cloud-apps", "phone-apps", "runbook"), sub("configs", "android"))
        assertEquals(listOf("infra", "containers"), sub("configs", "c3"))
        assertTrue(nav.section("configs")!!.page("desktop")!!.pages.isEmpty())
        assertTrue(nav.problems().isEmpty())
    }

    // ── secret values never rendered ─────────────────────────────────

    private fun hostileCompose(v: String) = """
        services:
          api:
            image: ghcr.io/x/api:1
            environment:
              - DB_PASSWORD=$v
              - "API_TOKEN=$v"
              PLAIN_NAME
            env_file: .env.secret
            secrets:
              - db_password
              - source: api_key
            volumes:
              - data:/data
              - type: bind
                source: /opt/x
                target: /etc/x
          worker:
            environment:
              WORKER_KEY: $v
              MULTI: |
                $v
                line2
            volumes: ["./w:/w"]
        volumes:
          data: {}
    """.trimIndent()

    @Test fun composeKeepsNamesAndDropsEveryValue() {
        val v = fakeSecret()
        val s = ComposeDecl.parse(hostileCompose(v))
        assertEquals(listOf("api", "worker"), s.map { it.name })
        assertEquals(listOf("DB_PASSWORD", "API_TOKEN", "PLAIN_NAME"), s[0].envNames)
        assertEquals(listOf("db_password", "api_key"), s[0].secretNames)
        assertEquals(listOf(".env.secret"), s[0].envFiles)
        assertEquals(listOf("data:/data", "/opt/x:/etc/x"), s[0].volumes)
        assertEquals(listOf("WORKER_KEY", "MULTI"), s[1].envNames)
        val all = s.flatMap { containerLines(it) + it.name }.joinToString("\n")
        assertFalse("a value reached a line: $all", all.contains(v))
        assertFalse(all.contains("line2"))
    }

    @Test fun containersPageNeverDrawsAValue() {
        val v = fakeSecret()
        compose.setContent { themed { ContainersPage { listOf(ContainerRow("svc", ComposeDecl.parse(hostileCompose(v)))) } } }
        compose.waitForIdle()
        compose.onAllNodesWithText(v, substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("DB_PASSWORD", substring = true, useUnmergedTree = true).fetchSemanticsNodes().let { assertTrue(it.isNotEmpty()) }
    }

    @Test fun terraformKeepsNamesOnly() {
        val v = fakeSecret()
        val t = TfDecl.parse("""
            terraform { required_providers { oci = { source = "oracle/oci" } } }
            provider "oci" { private_key = "$v" }
            variable "api_key" { default = "$v" }
            resource "oci_core_instance" "apps" { metadata = { ssh = "$v" } }
        """.trimIndent())
        assertEquals(listOf("oci", "oracle/oci"), t.providers)
        assertEquals(listOf("oci_core_instance.apps"), t.resources)
        assertEquals(listOf("api_key"), t.variables)
        assertFalse((t.providers + t.resources + t.variables).joinToString().contains(v))
    }

    @Test fun desktopTreeFiltersAndExcludes() {
        val t = TreeNode.of(listOf("L/a/flake.nix", "L/a/README.md", "L/z_archive/old.nix", "X/b.nix"), "L", Regex("\\.nix$"), listOf("z_archive"))
        assertEquals(1, t.fileCount())
        assertEquals(listOf("a"), t.rows({ false }).map { it.second.name })
        assertEquals(listOf("a", "flake.nix"), t.rows({ true }).map { it.second.name })
    }

    // ── card masking ─────────────────────────────────────────────────

    @Test fun cardMaskShowsTheLastFourOnly() {
        val n = fakeCard()
        val m = CardMask.mask(n)
        assertEquals("•••• •••• •••• 0002", m)
        assertEquals(4, m.count(Char::isDigit))
        assertEquals("••••••••", CardMask.mask("123"))
        assertEquals(m, CardMask.display(PersonalKind.CARDS, "number", n, revealed = false))
        assertEquals(n, CardMask.display(PersonalKind.CARDS, "number", n, revealed = true))
        assertEquals("TEST", CardMask.display(PersonalKind.CARDS, "holder", "TEST", revealed = false))
        assertEquals("••••••••", CardMask.display(PersonalKind.IDS, "number", "X1234567", revealed = false))
    }

    private fun seededRepo(): PersonalRepo {
        val r = PersonalRepo(ctx.getSharedPreferences("personal_c3_test", Context.MODE_PRIVATE))
        r.put(PersonalKind.CARDS, PersonalEntry("c1", "Card", mapOf("number" to fakeCard(), "holder" to "TEST HOLDER")))
        return r
    }

    private fun cardsPage(repo: PersonalRepo, allow: Boolean) {
        compose.setContent { themed {
            CompositionLocalProvider(LocalDeviceAuth provides DeviceAuth { _, done -> done(allow) }) {
                PersonalEntriesPage(PersonalKind.CARDS) { repo }
            }
        } }
        compose.waitForIdle()
    }

    @Test fun aCardStaysMaskedWhenAuthIsRefused() {
        val repo = seededRepo()
        cardsPage(repo, allow = false)
        compose.onAllNodesWithText(fakeCard(), substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithText("•••• •••• •••• 0002", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag(PersonalTags.reveal(PersonalKind.CARDS, "c1")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(fakeCard(), substring = true, useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun aCardIsRevealedOnlyAfterAuth() {
        val repo = seededRepo()
        cardsPage(repo, allow = true)
        compose.onAllNodesWithText(fakeCard(), substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithTag(PersonalTags.reveal(PersonalKind.CARDS, "c1")).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText(fakeCard(), substring = true, useUnmergedTree = true).assertCountEquals(1)
    }

    // ── encrypted storage ────────────────────────────────────────────

    @Test fun theStoreIsEncryptedOrAbsentNeverPlain() {
        PersonalStore.testPrefs = null
        // Robolectric has no Android keystore: the real store either opens as EncryptedSharedPreferences or not at all.
        val r = runCatching { PersonalStore.encrypted(ctx) }
        assertTrue("a plain store came back", r.isFailure || r.getOrNull() is EncryptedSharedPreferences)
        val repo = PersonalStore.repo(ctx)
        repo?.put(PersonalKind.CARDS, PersonalEntry("c9", "Card", mapOf("number" to fakeCard())))
        val prefsDir = File(ctx.applicationInfo.dataDir, "shared_prefs")
        val plain = prefsDir.listFiles().orEmpty().filter { it.readText().contains(fakeCard().filter(Char::isDigit).takeLast(8)) || it.readText().contains(fakeCard()) }
        assertTrue("the card reached a plain prefs file: ${plain.map { it.name }}", plain.isEmpty())
    }

    @Test fun theRepoRoundTripsThroughTheStoreItIsGiven() {
        val prefs = ctx.getSharedPreferences("personal_c3_rt", Context.MODE_PRIVATE)
        PersonalStore.testPrefs = { prefs }
        val repo = PersonalStore.repo(ctx)!!
        repo.put(PersonalKind.IDS, PersonalEntry("i1", "Passport", mapOf("number" to "X0000000")))
        assertEquals("X0000000", PersonalStore.repo(ctx)!!.get(PersonalKind.IDS, "i1")!!.fields["number"])
        repo.remove(PersonalKind.IDS, "i1")
        assertTrue(repo.list(PersonalKind.IDS).isEmpty())
    }

    @Test fun anEntryNeverPrintsItsValues() {
        val e = PersonalEntry("c1", "My card", mapOf("number" to fakeCard(), "holder" to "TEST HOLDER"))
        val s = e.toString()
        assertFalse(s.contains(fakeCard())); assertFalse(s.contains("TEST HOLDER")); assertFalse(s.contains("My card"))
        assertTrue(s.contains("number"))
    }
}
