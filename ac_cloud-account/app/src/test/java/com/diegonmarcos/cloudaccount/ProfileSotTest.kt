package com.diegonmarcos.cloudaccount

import com.diegonmarcos.cloudaccount.configs.ConfigsSources
import com.diegonmarcos.cloudaccount.personal.PersonalKind
import com.diegonmarcos.superapp.accountui.AccountUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1445 the profile is read from the declaration (Cloud Me's SoT, baked): renderers, field kinds, connectors. Fake values only. */
@RunWith(RobolectricTestRunner::class)
class ProfileSotTest {
    @Test fun everyLeafNamesItsRenderer() {
        val leaves = AccountUi.sections.flatMap { it.leaves }
        assertTrue(leaves.containsAll(listOf("ids", "cards", "entries", "me", "personal", "sites", "snippets", "cloud-apps", "phone-apps", "runbook", "desktop", "infra", "containers", "repos")))
        assertEquals("entries:cards", AccountUi.renderOf("personal", "cards"))
        assertEquals("configs:repos", AccountUi.renderOf("configs", "repos"))
        assertEquals("", AccountUi.renderOf("configs", "nope"))
    }

    @Test fun fieldKindsComeFromTheDeclaration() {
        assertEquals(listOf("holder", "number", "expiry", "brand"), PersonalKind.CARDS.fields)
        assertEquals(setOf("number"), PersonalKind.CARDS.masked)
        assertTrue(PersonalKind.CARDS.numeric("number"))
        assertEquals("Payment cards", PersonalKind.CARDS.title)
    }

    @Test fun connectorsParse() {
        val c = ConfigsSources.parse("""{"owner":"example-owner","connectors":[{"id":"x","label":"X","repo":"example-repo","ref":"main","paths":["a/b.json"]}]}""").connectors
        assertEquals(1, c.size)
        assertEquals("example-owner", c[0].owner)
        assertEquals(listOf("a/b.json"), c[0].paths)
        assertTrue(ConfigsSources.baked.connectors.isNotEmpty())
    }
}
