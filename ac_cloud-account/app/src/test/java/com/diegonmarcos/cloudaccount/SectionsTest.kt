package com.diegonmarcos.cloudaccount

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.profile.MeshStatus
import com.diegonmarcos.superapp.profile.PublicConfig
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #1081 the five sections: Configs is PUBLIC (no secret value can reach a page of it), Data ▸ Devices ▸ Mesh words,
 * and the Activity log. Every secret-looking value is built at run time from fragments, so no token-shaped literal
 * sits in the source.
 */
@RunWith(RobolectricTestRunner::class)
class SectionsTest {
    private fun fakeToken() = "gh" + "p_" + "x".repeat(24)
    private fun fakeJwt() = "ey" + "J" + "a".repeat(20) + "." + "b".repeat(8)

    // ── Configs holds no secret (the guard) ──────────────────────────────

    @Test fun configsSectionIsExactlyTheAllowedPages() {
        val allowed = setOf("cloud-apps", "phone-apps", "runbook", "desktop", "infra", "containers")
        val configs = MainActivity.NAV.section("configs")!!
        assertEquals(allowed, configs.allPages().filter { it.pages.isEmpty() }.map { it.id }.toSet())
        // the pages that hold or show secrets live in Data and Connect, never in Configs
        for (secretPage in listOf("providers", "keys", "tokens", "grants", "import", "bundle", "signin", "settings", "connections"))
            assertFalse("$secretPage is in Configs", configs.allPages().any { it.id == secretPage })
    }

    @Test fun publicConfigDropsEverySecretKeyAndValue() {
        val hostile = JSONObject()
            .put("os", "nixos").put("home_manager", "diego").put("hostname", "box")
            .put("password", "hunter2").put("api_token", "t").put("wg_private_key", "k")
            .put("flake", fakeToken())          // an allowed field holding a token-shaped value
            .put("arch", fakeJwt())             // ... and a JWT-shaped one
            .put("nixos_secret", "s")
            .put("type", JSONObject().put("password", "nested"))
        val rows = PublicConfig.rows(hostile, PublicConfig.DESKTOP_FIELDS + listOf("password", "api_token", "wg_private_key", "nixos_secret"))
        assertEquals(listOf("os" to "nixos", "hostname" to "box", "home_manager" to "diego").sortedBy { it.first },
            rows.sortedBy { it.first })
        val shown = rows.joinToString { it.first + it.second }
        for (leak in listOf("hunter2", "nested", fakeToken(), fakeJwt())) assertFalse("leaked $leak", shown.contains(leak))
    }

    @Test fun declaredDesktopsAndContainersShowOnlyPublicFields() {
        val bundle = JSONObject("""{
          "electronics": {
            "computers": {"fedora-box": {"label": "Fedora box", "type": "notebook", "os": "fedora", "home_manager": "diego", "wg_private_key": "x", "password": "y"}},
            "phones": {"galaxy-s21": {"label": "Galaxy", "type": "phone", "os": "android"}}
          },
          "containers": {"caddy": {"image": "caddy:2", "token": "z"}, "authelia": {"host": "vm1", "secret": "q"}}
        }""")
        val d = PublicConfig.desktops(bundle)
        assertEquals(listOf("fedora-box"), d.map { it.id })
        assertEquals(listOf("type" to "notebook", "os" to "fedora", "home_manager" to "diego"), d[0].rows)
        val c = PublicConfig.containers(bundle)
        assertEquals(setOf("caddy", "authelia"), c.map { it.id }.toSet())
        assertEquals(listOf("image" to "caddy:2"), c.first { it.id == "caddy" }.rows)
        assertEquals(listOf("host" to "vm1"), c.first { it.id == "authelia" }.rows)
        assertTrue(PublicConfig.desktops(null).isEmpty() && PublicConfig.containers(null).isEmpty())
    }

    // ── Data ▸ Devices ▸ Mesh ────────────────────────────────────────────

    @Test fun meshStatusWords() {
        assertEquals(MeshStatus.Kind.THIS_PHONE_UP, MeshStatus.classify(true, true, null))
        assertEquals(MeshStatus.Kind.THIS_PHONE_DOWN, MeshStatus.classify(true, false, null))
        assertEquals(MeshStatus.Kind.TUNNEL_DOWN, MeshStatus.classify(false, false, true))
        assertEquals(MeshStatus.Kind.UNKNOWN, MeshStatus.classify(false, true, null))
        assertEquals(MeshStatus.Kind.REACHABLE, MeshStatus.classify(false, true, true))
        assertEquals(MeshStatus.Kind.UNREACHABLE, MeshStatus.classify(false, true, false))
        assertEquals(MeshStatus.Kind.entries.size, MeshStatus.Kind.entries.map { MeshStatus.word(it) }.toSet().size)
    }

    // ── Activity ─────────────────────────────────────────────────────────

    @Test fun activityLogKeepsNewestFirstAndCapsAndRefusesSecrets() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ActivityFeed.clear(ctx)
        ActivityFeed.record(ctx, "backup", "first", now = 1)
        ActivityFeed.record(ctx, "secret", "revealed github.token", now = 2)
        ActivityFeed.record(ctx, "secret", "pasted " + fakeToken(), now = 3)   // refused: looks like a secret
        assertEquals(listOf("revealed github.token", "first"), ActivityFeed.items(ctx).map { it.text })
        var json: String? = null
        for (i in 1..ActivityFeed.CAP + 25) json = ActivityFeed.append(json, ActivityFeed.Item(i.toLong(), "k", "e$i"))
        val kept = ActivityFeed.parse(json)
        assertEquals(ActivityFeed.CAP, kept.size)
        assertEquals("e${ActivityFeed.CAP + 25}", kept.last().text)
        assertEquals("e26", kept.first().text)
        ActivityFeed.clear(ctx)
        assertTrue(ActivityFeed.items(ctx).isEmpty())
    }
}
